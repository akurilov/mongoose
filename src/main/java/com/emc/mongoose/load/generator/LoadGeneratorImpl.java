package com.emc.mongoose.load.generator;

import com.emc.mongoose.concurrent.ServiceTaskExecutor;
import com.emc.mongoose.item.op.OpType;
import com.emc.mongoose.item.op.Operation;
import com.emc.mongoose.item.op.OperationsBuilder;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.logging.LogUtil;
import com.emc.mongoose.logging.Loggers;

import com.github.akurilov.commons.collection.OptLockArrayBuffer;
import com.github.akurilov.commons.collection.OptLockBuffer;
import com.github.akurilov.commons.io.Input;
import com.github.akurilov.commons.io.Output;
import com.github.akurilov.commons.concurrent.throttle.IndexThrottle;
import com.github.akurilov.commons.concurrent.throttle.Throttle;

import com.github.akurilov.fiber4j.Fiber;
import com.github.akurilov.fiber4j.FiberBase;

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

import static com.emc.mongoose.Constants.KEY_CLASS_NAME;

/**
 Created by kurila on 11.07.16.
 */
public class LoadGeneratorImpl<I extends Item, O extends Operation<I>>
extends FiberBase
implements LoadGenerator<I, O> {

	private static final String CLS_NAME = LoadGeneratorImpl.class.getSimpleName();

	private volatile boolean recycleQueueFullState = false;
	private volatile boolean itemInputFinishFlag = false;
	private volatile boolean opInputFinishFlag = false;
	private volatile boolean outputFinishFlag = false;

	private final Input<I> itemInput;
	private final OperationsBuilder<I, O> opsBuilder;
	private final int originIndex;
	private final Object[] throttles;
	private final Output<O> opOutput;
	private final Lock inputLock = new ReentrantLock();
	private final int batchSize;
	private final long countLimit;
	private final BlockingQueue<O> recycleQueue;
	private final boolean recycleFlag;
	private final boolean shuffleFlag;
	private final Random rnd;
	private final String name;
	private final ThreadLocal<OptLockBuffer<O>> threadLocalOpBuff;
	private final LongAdder builtTasksCounter = new LongAdder();
	private final LongAdder recycledOpCounter = new LongAdder();
	private final LongAdder outputOpCounter = new LongAdder();

	@SuppressWarnings("unchecked")
	public LoadGeneratorImpl(
		final Input<I> itemInput, final OperationsBuilder<I, O> opsBuilder, final List<Object> throttles,
		final Output<O> opOutput, final int batchSize, final long countLimit, final int recycleQueueSize,
		final boolean recycleFlag, final boolean shuffleFlag
	) {

		super(ServiceTaskExecutor.INSTANCE);

		this.itemInput = itemInput;
		this.opsBuilder = opsBuilder;
		this.originIndex = opsBuilder.originIndex();
		this.throttles = throttles.toArray(new Object[] {});
		this.opOutput = opOutput;
		this.batchSize = batchSize;
		this.countLimit = countLimit > 0 ? countLimit : Long.MAX_VALUE;
		this.recycleQueue = new ArrayBlockingQueue<>(recycleQueueSize);
		this.recycleFlag = recycleFlag;
		this.shuffleFlag = shuffleFlag;
		this.rnd = shuffleFlag ? new Random() : null;
		final String ioStr = opsBuilder.opType().toString();
		name = Character.toUpperCase(ioStr.charAt(0)) + ioStr.substring(1).toLowerCase() +
			(countLimit > 0 && countLimit < Long.MAX_VALUE ? Long.toString(countLimit) : "") +
			itemInput.toString();
		threadLocalOpBuff = ThreadLocal.withInitial(() -> new OptLockArrayBuffer<>(batchSize));
	}

	@Override
	protected final void invokeTimed(final long startTimeNanos) {

		ThreadContext.put(KEY_CLASS_NAME, CLS_NAME);
		final OptLockBuffer<O> opBuff = threadLocalOpBuff.get();
		int pendingOpCount = opBuff.size();
		int n = batchSize - pendingOpCount;

		try {

			if(n > 0) { // the tasks buffer has free space for the new tasks
				if(itemInputFinishFlag) { // items input was exhausted
					if(recycleFlag) { // never recycled -> recycling is not enabled
						opInputFinishFlag = true; // allow shutdown
					} else { // recycle the tasks if any
						n = recycleQueue.drainTo(opBuff, n);
						if(n > 0) {
							pendingOpCount += n;
							recycledOpCounter.add(n);
						}
					}
				} else {
					// try to produce new items from the items input
					if(inputLock.tryLock()) {
						try {
							// find the remaining count of the ops to generate
							final long remainingOpCount = countLimit - generatedOpCount();
							if(remainingOpCount > 0) {
								// make the limit not more than batch size
								n = (int) Math.min(remainingOpCount, n);
								final List<I> items = getItems(itemInput, n);
								if(items == null) {
									itemInputFinishFlag = true;
								} else {
									n = items.size();
									if(n > 0) {
										pendingOpCount += buildOps(items, opBuff, n);
									}
								}
							}
						} finally {
							inputLock.unlock();
						}
					}
				}
			}

			if(pendingOpCount > 0) {
				n = pendingOpCount;
				// acquire the permit for all the throttles
				for(int i = 0; i < throttles.length; i ++) {
					final Object throttle = throttles[i];
					if(throttle instanceof Throttle) {
						n = ((Throttle) throttle).tryAcquire(n);
					} else if(throttle instanceof IndexThrottle) {
						n = ((IndexThrottle) throttle).tryAcquire(originIndex, n);
					} else {
						throw new AssertionError("Unexpected throttle type: " + throttle.getClass());
					}
				}
				// try to output
				if(n > 0) {
					if(n == 1) { // single mode branch
						try {
							final O op = opBuff.get(0);
							if(opOutput.put(op)) {
								outputOpCounter.increment();
								if(pendingOpCount == 1) {
									opBuff.clear();
								} else {
									opBuff.remove(0);
								}
							}
						} catch(final EOFException e) {
							Loggers.MSG.debug("{}: finish due to output's EOF", name);
							outputFinishFlag = true;
						} catch(final IOException e) {
							LogUtil.exception(Level.ERROR, e, "{}: operation output failure", name);
						}
					} else { // batch mode branch
						try {
							n = opOutput.put(opBuff, 0, n);
							outputOpCounter.add(n);
							if(n < pendingOpCount) {
								opBuff.removeRange(0, n);
							} else {
								opBuff.clear();
							}
						} catch(final EOFException e) {
							Loggers.MSG.debug("{}: finish due to output's EOF", name);
							outputFinishFlag = true;
						} catch(final RemoteException e) {
							final Throwable cause = e.getCause();
							if(cause instanceof EOFException) {
								Loggers.MSG.debug("{}: finish due to output's EOF", name);
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
				LogUtil.exception(Level.ERROR, t, "{}: unexpected failure", name);
				t.printStackTrace(System.err);
			}
		} finally {
			if(isFinished()) {
				try {
					stop();
				} catch(final IllegalStateException ignored) {
				}
			}
		}
	}

	private static <I extends Item> List<I> getItems(final Input<I> itemInput, final int n)
	throws IOException {
		// prepare the items buffer
		final List<I> items = new ArrayList<>(n);
		try {
			// get the items from the input
			itemInput.get(items, n);
		} catch(final EOFException e) {
			Loggers.MSG.debug("End of items input \"{}\"", itemInput.toString());
			return null;
		}
		return items;
	}

	// build new tasks for the corresponding items
	private long buildOps(final List<I> items, final OptLockBuffer<O> opBuff, final int n)
	throws IOException {
		if(shuffleFlag) {
			Collections.shuffle(items, rnd);
		}
		try {
			opsBuilder.buildOps(items, opBuff);
			builtTasksCounter.add(n);
			return n;
		} catch(final IllegalArgumentException e) {
			LogUtil.exception(Level.ERROR, e, "Failed to generate the load operation");
		}
		return 0;
	}

	@Override
	public final long generatedOpCount() {
		return builtTasksCounter.sum() + recycledOpCounter.sum();
	}

	@Override
	public final OpType opType() {
		return opsBuilder.opType();
	}

	@Override
	public final boolean isRecycling() {
		return recycleFlag;
	}

	@Override
	public final void recycle(final O op) {
		if(!recycleQueue.offer(op)) {
			if(!recycleQueueFullState && 0 == recycleQueue.remainingCapacity()) {
				recycleQueueFullState = true;
				Loggers.ERR.error("{}: cannot recycle the operation, queue is full", name);
			}
		}
	}

	@Override
	public final boolean isFinished() {
		return outputFinishFlag ||
			itemInputFinishFlag && opInputFinishFlag && generatedOpCount() == outputOpCounter.sum();
	}

	@Override
	protected final void doShutdown()
	throws IllegalStateException {
		stop();
		Loggers.MSG.debug(
			"{}: generated {}, recycled {}, output {} operations",
			LoadGeneratorImpl.this.toString(), builtTasksCounter.sum(), recycledOpCounter.sum(),
			outputOpCounter.sum()
		);
	}

	@Override
	protected final void doClose()
	throws IOException {
		recycleQueue.clear();
		// the item input may be instantiated by the load generator builder which has no reference to it so the load
		// generator builder should close it
		if(itemInput != null) {
			try {
				inputLock.tryLock(Fiber.TIMEOUT_NANOS, TimeUnit.NANOSECONDS);
				itemInput.close();
			} catch(final Exception e) {
				LogUtil.exception(Level.WARN, e, "{}: failed to close the item input", toString());
			}
		}
		// Op builder is instantiated by the load generator builder which forgets it so the load generator should
		// close it
		opsBuilder.close();
	}

	@Override
	public final String toString() {
		return name;
	}
}
