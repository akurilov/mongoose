package com.emc.mongoose.load.generator;

import com.emc.mongoose.common.api.SizeInBytes;
import com.emc.mongoose.common.collection.OptLockArrayBuffer;
import com.emc.mongoose.common.collection.OptLockBuffer;
import com.emc.mongoose.common.concurrent.SvcTask;
import com.emc.mongoose.common.concurrent.WeightThrottle;
import com.emc.mongoose.model.DaemonBase;
import com.emc.mongoose.common.concurrent.Throttle;
import com.emc.mongoose.common.io.Output;
import com.emc.mongoose.common.exception.UserShootHisFootException;
import com.emc.mongoose.model.io.IoType;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.common.io.Input;
import com.emc.mongoose.model.io.task.IoTask;
import com.emc.mongoose.model.io.task.IoTaskBuilder;
import com.emc.mongoose.model.item.Item;
import com.emc.mongoose.model.load.LoadGenerator;
import com.emc.mongoose.ui.log.Loggers;

import org.apache.logging.log4j.Level;

import java.io.EOFException;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 Created by kurila on 11.07.16.
 */
public class BasicLoadGenerator<I extends Item, O extends IoTask<I>>
extends DaemonBase
implements LoadGenerator<I, O>, SvcTask {

	private volatile WeightThrottle weightThrottle = null;
	private volatile Throttle<Object> rateThrottle = null;
	private volatile Output<O> ioTaskOutput;
	private volatile boolean itemInputFinishFlag = false;
	private volatile boolean deferredTasksFlag = false;
	private volatile boolean outputFinishFlag = false;

	private final int batchSize;
	private final Input<I> itemInput;
	private final Lock inputLock = new ReentrantLock();
	private final SizeInBytes itemSizeEstimate;
	private final Random rnd;
	private final long countLimit;
	private final boolean shuffleFlag;
	private final IoTaskBuilder<I, O> ioTaskBuilder;
	private final int originCode;

	private final LongAdder builtTasksCounter = new LongAdder();
	private final LongAdder outputTaskCounter = new LongAdder();
	private final String name;

	@SuppressWarnings("unchecked")
	public BasicLoadGenerator(
		final Input<I> itemInput, final int batchSize, final SizeInBytes itemSizeEstimate,
		final IoTaskBuilder<I, O> ioTaskBuilder, final long countLimit, final SizeInBytes sizeLimit,
		final boolean shuffleFlag
	) throws UserShootHisFootException {
		this.batchSize = batchSize;
		this.itemInput = itemInput;
		this.itemSizeEstimate = itemSizeEstimate;
		this.ioTaskBuilder = ioTaskBuilder;
		this.originCode = ioTaskBuilder.hashCode();
		if(countLimit > 0) {
			this.countLimit = countLimit;
		} else if(
			sizeLimit.get() > 0 && itemSizeEstimate.get() > 0 &&
				itemSizeEstimate.getMin() == itemSizeEstimate.getMax()
		) {
			this.countLimit = sizeLimit.get() / itemSizeEstimate.get();
		} else {
			this.countLimit = Long.MAX_VALUE;
		}
		this.shuffleFlag = shuffleFlag;
		this.rnd = shuffleFlag ? new Random() : null;

		final String ioStr = ioTaskBuilder.getIoType().toString();
		name = Character.toUpperCase(ioStr.charAt(0)) + ioStr.substring(1).toLowerCase() +
			(countLimit > 0 && countLimit < Long.MAX_VALUE ? Long.toString(countLimit) : "") +
			itemInput.toString();
		svcTasks.add(this);
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
	public final void setOutput(final Output<O> ioTaskOutput) {
		this.ioTaskOutput = ioTaskOutput;
	}

	@Override
	public final long getGeneratedIoTasksCount() {
		return builtTasksCounter.sum();
	}

	@Override
	public final SizeInBytes getItemSizeEstimate() {
		return itemSizeEstimate;
	}

	@Override
	public final IoType getIoType() {
		return ioTaskBuilder.getIoType();
	}

	private static final ThreadLocal<OptLockBuffer> THREAD_LOCAL_BUFF = new ThreadLocal<>();

	@Override
	public final void run() {

		OptLockBuffer<O> thrLocTasksBuff = THREAD_LOCAL_BUFF.get();
		if(thrLocTasksBuff == null) {
			thrLocTasksBuff = new OptLockArrayBuffer<>(batchSize);
			THREAD_LOCAL_BUFF.set(thrLocTasksBuff);
		}
		int pendingTasksCount = thrLocTasksBuff.size();
		if(pendingTasksCount == 0) {
			deferredTasksFlag = false;
		}
		int n = batchSize - pendingTasksCount;

		try {
			if(n > 0 && !itemInputFinishFlag) {
				if(inputLock.tryLock()) {
					try {
						// find the limits and prepare the items buffer
						final long remainingTasksCount = countLimit - builtTasksCounter.sum();
						if(remainingTasksCount > 0) {
							n = (int) Math.min(remainingTasksCount, n);
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
								if(shuffleFlag) {
									Collections.shuffle(items, rnd);
								}
								ioTaskBuilder.getInstances(items, thrLocTasksBuff);
								pendingTasksCount += n;
								builtTasksCounter.add(n);
							}
						}
					} finally {
						inputLock.unlock();
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
							final O task = thrLocTasksBuff.get(0);
							if(ioTaskOutput.put(task)) {
								outputTaskCounter.increment();
								if(pendingTasksCount == 1) {
									thrLocTasksBuff.clear();
								} else {
									thrLocTasksBuff.remove(0);
									deferredTasksFlag = true;
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
							n = ioTaskOutput.put(thrLocTasksBuff, 0, n);
							outputTaskCounter.add(n);
							if(n < pendingTasksCount) {
								thrLocTasksBuff.removeRange(0, n);
								deferredTasksFlag = true;
							} else {
								thrLocTasksBuff.clear();
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
				outputFinishFlag |
					(itemInputFinishFlag && pendingTasksCount == 0 && !deferredTasksFlag)
			) {
				Loggers.MSG.debug(
					"{}: generated {}, output {} I/O tasks", BasicLoadGenerator.this.toString(),
					builtTasksCounter.sum(), outputTaskCounter.sum()
				);
				try {
					shutdown();
				} catch(final IllegalStateException ignored) {
				}
			}
		}
	}

	@Override
	protected final void doShutdown() {
		interrupt();
	}

	@Override
	protected final void doInterrupt() {
		svcTasks.remove(this);
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
	protected final void doClose()
	throws IOException {
		super.doClose();
		if(itemInput != null) {
			try {
				inputLock.tryLock(SvcTask.TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
				itemInput.close();
			} catch(final Exception e) {
				LogUtil.exception(Level.WARN, e, "{}: failed to close the item input", toString());
			}
		}
		ioTaskBuilder.close();
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
}
