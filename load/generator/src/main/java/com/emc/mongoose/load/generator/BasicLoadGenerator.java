package com.emc.mongoose.load.generator;

import com.github.akurilov.commons.system.SizeInBytes;
import com.github.akurilov.commons.collection.OptLockArrayBuffer;
import com.github.akurilov.commons.collection.OptLockBuffer;
import com.github.akurilov.commons.concurrent.Throttle;
import com.github.akurilov.commons.io.Output;
import com.github.akurilov.commons.io.Input;

import com.github.akurilov.coroutines.Coroutine;
import com.github.akurilov.coroutines.OutputCoroutine;
import com.github.akurilov.coroutines.RoundRobinOutputCoroutine;

import com.emc.mongoose.api.common.concurrent.WeightThrottle;
import com.emc.mongoose.api.model.concurrent.DaemonBase;
import com.emc.mongoose.api.common.exception.UserShootHisFootException;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.io.task.IoTaskBuilder;
import com.emc.mongoose.api.model.item.Item;
import com.emc.mongoose.api.model.load.LoadGenerator;
import com.emc.mongoose.ui.log.Loggers;
import static com.emc.mongoose.api.common.Constants.KEY_CLASS_NAME;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;

import java.io.EOFException;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 Created by kurila on 11.07.16.
 */
public class BasicLoadGenerator<I extends Item, O extends IoTask<I>>
extends DaemonBase
implements LoadGenerator<I, O>, Coroutine {

	private final static String CLS_NAME = BasicLoadGenerator.class.getSimpleName();

	private volatile WeightThrottle weightThrottle = null;
	private volatile Throttle<Object> rateThrottle = null;
	private volatile OutputCoroutine<O> ioTaskOutput = null;
	private volatile boolean recycleQueueFullState = false;
	private volatile boolean itemInputFinishFlag = false;
	private volatile boolean taskInputFinishFlag = false;
	private volatile boolean outputFinishFlag = false;

	private final BlockingQueue<O> recycleQueue;
	private final int batchSize;
	private final Input<I> itemInput;
	private final Lock inputLock = new ReentrantLock();
	private final long transferSizeEstimate;
	private final Random rnd;
	private final long countLimit;
	private final boolean shuffleFlag;
	private final IoTaskBuilder<I, O> ioTaskBuilder;
	private final int originCode;

	private final LongAdder builtTasksCounter = new LongAdder();
	private final LongAdder recycledTasksCounter = new LongAdder();
	private final LongAdder outputTaskCounter = new LongAdder();
	private final String name;

	private final ThreadLocal<OptLockBuffer<O>> threadLocalTasksBuff = new ThreadLocal<>();

	@SuppressWarnings("unchecked")
	public BasicLoadGenerator(
		final Input<I> itemInput, final int batchSize, final long transferSizeEstimate,
		final IoTaskBuilder<I, O> ioTaskBuilder, final long countLimit, final SizeInBytes sizeLimit,
		final int recycleQueueSize, final boolean shuffleFlag
	) throws UserShootHisFootException {
		this.batchSize = batchSize;
		this.itemInput = itemInput;
		this.transferSizeEstimate = transferSizeEstimate;
		this.ioTaskBuilder = ioTaskBuilder;
		this.originCode = ioTaskBuilder.getOriginCode();
		if(countLimit > 0) {
			this.countLimit = countLimit;
		} else if(sizeLimit.get() > 0 && this.transferSizeEstimate > 0) {
			this.countLimit = sizeLimit.get() / this.transferSizeEstimate;
		} else {
			this.countLimit = Long.MAX_VALUE;
		}
		this.recycleQueue = recycleQueueSize > 0 ?
			new ArrayBlockingQueue<>(recycleQueueSize) : null;
		this.shuffleFlag = shuffleFlag;
		this.rnd = shuffleFlag ? new Random() : null;

		final String ioStr = ioTaskBuilder.getIoType().toString();
		name = Character.toUpperCase(ioStr.charAt(0)) + ioStr.substring(1).toLowerCase() +
			(countLimit > 0 && countLimit < Long.MAX_VALUE ? Long.toString(countLimit) : "") +
			itemInput.toString();
	}

	@Override
	public final void setWeightThrottle(final WeightThrottle weightThrottle) {
		this.weightThrottle = weightThrottle;
	}

	@Override
	public final void setRateThrottle(final Throttle<Object> rateThrottle) {
		this.rateThrottle = rateThrottle;
	}

	@Override
	public final void setOutputs(final List<? extends Output<O>> ioTaskOutputs) {
		if(this.ioTaskOutput != null && !this.ioTaskOutput.isClosed()) {
			try {
				this.ioTaskOutput.close();
			} catch(final IOException ignored) {
			}
		}
		this.ioTaskOutput = new RoundRobinOutputCoroutine<>(SVC_EXECUTOR, ioTaskOutputs, batchSize);
	}

	@Override
	public final long getGeneratedTasksCount() {
		return builtTasksCounter.sum() + recycledTasksCounter.sum();
	}

	@Override
	public final long getTransferSizeEstimate() {
		return transferSizeEstimate;
	}

	@Override
	public final IoType getIoType() {
		return ioTaskBuilder.getIoType();
	}
	
	@Override
	public final int getBatchSize() {
		return batchSize;
	}

	@Override
	public final boolean isRecycling() {
		return recycleQueue != null;
	}

	@Override
	public final void recycle(final O ioTask) {
		if(recycleQueue != null) {
			if(!recycleQueue.add(ioTask)) {
				if(!recycleQueueFullState && 0 == recycleQueue.remainingCapacity()) {
					recycleQueueFullState = true;
					Loggers.ERR.error("{}: cannot recycle I/O tasks, queue is full", name);
				}
			}
		}
	}

	@Override
	public final void run() {

		ThreadContext.put(KEY_CLASS_NAME, CLS_NAME);

		OptLockBuffer<O> tasksBuff = threadLocalTasksBuff.get();
		if(tasksBuff == null) {
			tasksBuff = new OptLockArrayBuffer<>(batchSize);
			threadLocalTasksBuff.set(tasksBuff);
		}
		int pendingTasksCount = tasksBuff.size();
		int n = batchSize - pendingTasksCount;

		try {
			if(n > 0) { // the tasks buffer has free space for the new tasks

				if(!itemInputFinishFlag) {
					// try to produce new items from the items input
					if(inputLock.tryLock()) {
						try {
							// find the remaining count of the tasks to generate
							final long remainingTasksCount = countLimit - getGeneratedTasksCount();
							if(remainingTasksCount > 0) {
								// make the limit not more than batch size
								n = (int) Math.min(remainingTasksCount, n);
								// prepare the items buffer
								final List<I> items = new ArrayList<>(n);
								try {
									// get the items from the input
									itemInput.get(items, n);
								} catch(final EOFException e) {
									Loggers.MSG.debug(
										"{}: end of items input @ the count {}",
										BasicLoadGenerator.this.toString(), builtTasksCounter.sum()
									);
									itemInputFinishFlag = true;
								}

								n = items.size();
								if(n > 0) {
									// build new tasks for the corresponding items
									if(shuffleFlag) {
										Collections.shuffle(items, rnd);
									}
									try {
										ioTaskBuilder.getInstances(items, tasksBuff);
										pendingTasksCount += n;
										builtTasksCounter.add(n);
									} catch(final IllegalArgumentException e) {
										LogUtil.exception(
											Level.ERROR, e, "Failed to generate the I/O task"
										);
									}
								}
							}
						} finally {
							inputLock.unlock();
						}
					}

				} else {
					// items input was exhausted
					if(recycleQueue == null) {
						// recycling is disabled
						taskInputFinishFlag = true; // allow shutdown
					} else {
						// recycle the tasks if any
						n = recycleQueue.drainTo(tasksBuff, n);
						if(n > 0) {
							pendingTasksCount += n;
							recycledTasksCounter.add(n);
						}
					}
				}
			}

			if(pendingTasksCount > 0) {
				// acquire the throttles permit
				n = pendingTasksCount;
				if(weightThrottle != null) {
					n = weightThrottle.tryAcquire(originCode, n);
				}
				if(rateThrottle != null) {
					n = rateThrottle.tryAcquire(originCode, n);
				}
				// try to output
				if(n > 0) {
					if(n == 1) {
						try {
							final O task = tasksBuff.get(0);
							if(ioTaskOutput.put(task)) {
								outputTaskCounter.increment();
								if(pendingTasksCount == 1) {
									tasksBuff.clear();
								} else {
									tasksBuff.remove(0);
								}
							}
						} catch(final EOFException e) {
							Loggers.MSG.debug(
								"{}: finish due to output's EOF", BasicLoadGenerator.this.toString()
							);
							outputFinishFlag = true;
						} catch(final RemoteException e) {
							final Throwable cause = e.getCause();
							if(cause instanceof EOFException) {
								Loggers.MSG.debug(
									"{}: finish due to output's EOF",
									BasicLoadGenerator.this.toString()
								);
								outputFinishFlag = true;
							} else {
								LogUtil.exception(Level.ERROR, cause, "Unexpected failure");
								e.printStackTrace(System.err);
							}
						}
					} else {
						try {
							n = ioTaskOutput.put(tasksBuff, 0, n);
							outputTaskCounter.add(n);
							if(n < pendingTasksCount) {
								tasksBuff.removeRange(0, n);
							} else {
								tasksBuff.clear();
							}
						} catch(final EOFException e) {
							Loggers.MSG.debug(
								"{}: finish due to output's EOF", BasicLoadGenerator.this.toString()
							);
							outputFinishFlag = true;
						} catch(final RemoteException e) {
							final Throwable cause = e.getCause();
							if(cause instanceof EOFException) {
								Loggers.MSG.debug(
									"{}: finish due to output's EOF",
									BasicLoadGenerator.this.toString()
								);
								outputFinishFlag = true;
							} else {
								LogUtil.exception(Level.ERROR, cause, "Unexpected failure");
								e.printStackTrace(System.err);
							}
						}
					}
				}
			}

		} catch(final Throwable t) {
			if(!(t instanceof EOFException)) {
				LogUtil.exception(Level.ERROR, t, "Unexpected failure");
				t.printStackTrace(System.err);
			}
		} finally {
			if(
				outputFinishFlag ||
					(
						itemInputFinishFlag && taskInputFinishFlag &&
							getGeneratedTasksCount() == outputTaskCounter.sum()
					)
			) {
				try {
					shutdown();
				} catch(final IllegalStateException ignored) {
				}
			}
		}
	}

	@Override
	protected void doStart()
	throws IllegalStateException {
		SVC_EXECUTOR.start(this);
		if(ioTaskOutput != null) {
			ioTaskOutput.start();
		}
	}

	@Override
	protected final void doShutdown() {
		interrupt();
	}

	@Override
	public final boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptedException {
		long remainingMillis = timeUnit.toMillis(timeout);
		long t;
		while(remainingMillis > 0) {
			t = System.currentTimeMillis();
			synchronized(state) {
				state.wait(remainingMillis);
			}
			if(!isStarted()) {
				return true;
			} else {
				t = System.currentTimeMillis() - t;
				remainingMillis -= t;
			}
		}
		return false;
	}

	@Override
	protected final void doInterrupt() {
		SVC_EXECUTOR.stop(this);
		Loggers.MSG.debug(
			"{}: generated {}, recycled {}, output {} I/O tasks",
			BasicLoadGenerator.this.toString(), builtTasksCounter.sum(), recycledTasksCounter.sum(),
			outputTaskCounter.sum()
		);
	}

	@Override
	protected final void doClose()
	throws IOException {
		if(recycleQueue != null) {
			recycleQueue.clear();
		}
		// the item input may be instantiated by the load generator builder which has no reference
		// to it so the load generator builder should close it
		if(itemInput != null) {
			try {
				inputLock.tryLock(Coroutine.TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
				itemInput.close();
			} catch(final Exception e) {
				LogUtil.exception(Level.WARN, e, "{}: failed to close the item input", toString());
			}
		}
		// I/O task builder is instantiated by the load generator builder which forgets it
		// so the load generator should close it
		ioTaskBuilder.close();
		//
		ioTaskOutput.close();
	}
	
	@Override
	public final String toString() {
		return name;
	}
	
	@Override
	public final int hashCode() {
		return originCode;
	}

	@Override
	public void stop() {
		interrupt();
	}

	@Override
	public boolean isStopped() {
		return isInterrupted();
	}
}
