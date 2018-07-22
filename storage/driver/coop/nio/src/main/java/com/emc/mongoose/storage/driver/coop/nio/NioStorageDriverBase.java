package com.emc.mongoose.storage.driver.coop.nio;

import com.emc.mongoose.data.DataInput;
import com.emc.mongoose.exception.OmgShootMyFootException;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.item.op.Operation;
import com.emc.mongoose.logging.LogUtil;
import com.emc.mongoose.logging.Loggers;
import com.emc.mongoose.storage.driver.coop.CoopStorageDriverBase;
import static com.emc.mongoose.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.Constants.KEY_STEP_ID;
import static com.emc.mongoose.item.op.Operation.Status.ACTIVE;
import static com.emc.mongoose.item.op.Operation.Status.INTERRUPTED;
import static com.emc.mongoose.item.op.Operation.Status.PENDING;

import com.github.akurilov.commons.collection.OptLockArrayBuffer;
import com.github.akurilov.commons.collection.OptLockBuffer;
import com.github.akurilov.commons.concurrent.ThreadUtil;

import com.github.akurilov.confuse.Config;

import com.github.akurilov.fiber4j.ExclusiveFiberBase;
import com.github.akurilov.fiber4j.Fiber;
import com.github.akurilov.fiber4j.FibersExecutor;

import org.apache.logging.log4j.CloseableThreadContext;
import static org.apache.logging.log4j.CloseableThreadContext.Instance;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.message.ThreadDumpMessage;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 Created by kurila on 19.07.16.
 The multi-threaded non-blocking I/O storage driver.
 */
public abstract class NioStorageDriverBase<I extends Item, O extends Operation<I>>
extends CoopStorageDriverBase<I, O>
implements NioStorageDriver<I, O> {

	private final static String CLS_NAME = NioStorageDriverBase.class.getSimpleName();
	private final static FibersExecutor IO_EXECUTOR = new FibersExecutor(false);

	private final int ioWorkerCount;
	private final int opBuffCapacity;
	private final List<Fiber> ioFibers;
	private final OptLockBuffer<O> opBuffs[];
	private final AtomicLong rrc = new AtomicLong(0);

	@SuppressWarnings("unchecked")
	public NioStorageDriverBase(
		final String testStepId, final DataInput dataInput, final Config storageConfig, final boolean verifyFlag,
		final int batchSize
	) throws OmgShootMyFootException {
		super(testStepId, dataInput, storageConfig, verifyFlag, batchSize);
		final int confWorkerCount = storageConfig.intVal("driver-threads");
		if(confWorkerCount > 0) {
			ioWorkerCount = confWorkerCount;
		} else if(concurrencyLimit > 0) {
			ioWorkerCount = Math.min(concurrencyLimit, ThreadUtil.getHardwareThreadCount());
		} else {
			ioWorkerCount = ThreadUtil.getHardwareThreadCount();
		}
		ioFibers = new ArrayList<>(ioWorkerCount);
		opBuffs = new OptLockBuffer[ioWorkerCount];
		opBuffCapacity = Math.max(MIN_TASK_BUFF_CAPACITY, concurrencyLimit / ioWorkerCount);
		for(int i = 0; i < ioWorkerCount; i ++) {
			opBuffs[i] = new OptLockArrayBuffer<>(opBuffCapacity);
			ioFibers.add(new NioWorkerTask(IO_EXECUTOR, opBuffs[i]));
		}
	}

	/**
	 The class represents the non-blocking load operation execution algorithm.
	 The load operation itself may correspond to a large data transfer so it can't be non-blocking.
	 So the load operation may be invoked multiple times (in the reentrant manner).
	 */
	private final class NioWorkerTask
	extends ExclusiveFiberBase {

		private final OptLockBuffer<O> opBuff;
		private final List<O> opLocalBuff;

		private int opBuffSize;
		private O op;

		public NioWorkerTask(final FibersExecutor executor, final OptLockBuffer<O> opBuff) {
			super(executor, opBuff);
			this.opBuff = opBuff;
			this.opLocalBuff = new ArrayList<>(opBuffCapacity);
		}

		@Override
		protected final void invokeTimedExclusively(final long startTimeNanos) {

			ThreadContext.put(KEY_STEP_ID, stepId);

			opBuffSize = opBuff.size();
			if(opBuffSize > 0) {
				try {
					for(int i = 0; i < opBuffSize; i ++) {
						op = opBuff.get(i);
						// if timeout, put the op into the temporary buffer
						if(System.nanoTime() - startTimeNanos >= TIMEOUT_NANOS) {
							opLocalBuff.add(op);
							continue;
						}
						// check if the op is invoked 1st time
						if(PENDING.equals(op.status())) {
							// do not start the new op if the state is not more active
							if(!isStarted()) {
								continue;
							}
							// respect the configured concurrency level
							if(!concurrencyThrottle.tryAcquire()) {
								opLocalBuff.add(op);
								continue;
							}
							// mark the op as active
							op.startRequest();
							op.finishRequest();
						}
						// perform non blocking I/O for the op
						invokeNio(op);
						// remove the op from the buffer if it is not active more
						if(!ACTIVE.equals(op.status())) {
							concurrencyThrottle.release();
							opCompleted(op);
						} else {
							// the op remains in the buffer for the next iteration
							opLocalBuff.add(op);
						}
					}
				} catch(final Throwable cause) {
					LogUtil.exception(Level.ERROR, cause, "I/O worker failure");
				} finally {
					// put the active operations back into the buffer
					opBuff.clear();
					opBuffSize = opLocalBuff.size();
					if(opBuffSize > 0) {
						for(int i = 0; i < opBuffSize; i ++) {
							opBuff.add(opLocalBuff.get(i));
						}
						opLocalBuff.clear();
					}
				}
			}
		}

		@Override
		protected final void doClose() {
			opBuffSize = opBuff.size();
			Loggers.MSG.debug("Finish {} remaining active load operations finally", opBuffSize);
			for(int i = 0; i < opBuffSize; i ++) {
				op = opBuff.get(i);
				if(ACTIVE.equals(op.status())) {
					op.status(INTERRUPTED);
					concurrencyThrottle.release();
					opCompleted(op);
				}
			}
			Loggers.MSG.debug("Finish the remaining active load operations done");
		}
	}

	/**
	 Reentrant method which decorates the actual non-blocking create/read/etc I/O operation.
	 May change the task status or not change if the I/O operation is not completed during this
	 particular invocation
	 @param op
	 */
	protected abstract void invokeNio(final O op);

	@Override
	protected final void doStart()
	throws IllegalStateException {
		super.doStart();
		for(final Fiber ioFiber : ioFibers) {
			try {
				ioFiber.start();
			} catch(final RemoteException ignored) {
			}
		}
	}

	@Override
	protected final void doStop()
	throws IllegalStateException {
		for(final Fiber ioFiber: ioFibers) {
			try {
				ioFiber.stop();
			} catch(final RemoteException ignored) {
			}
		}
	}

	@Override
	protected final boolean submit(final O op)
	throws IllegalStateException {
		OptLockBuffer<O> opBuff;
		for(int i = 0; i < ioWorkerCount; i ++) {
			if(!isStarted()) {
				throw new IllegalStateException();
			}
			opBuff = opBuffs[(int) (rrc.getAndIncrement() % ioWorkerCount)];
			if(opBuff.tryLock()) {
				try {
					return opBuff.size() < opBuffCapacity && opBuff.add(op);
				} finally {
					opBuff.unlock();
				}
			} else {
				i ++;
			}
		}
		return false;
	}

	@Override
	protected final int submit(final List<O> ops, final int from, final int to)
	throws IllegalStateException {
		OptLockBuffer<O> opBuff;
		int j = from;
		int k;
		int n;
		for(int i = 0; i < ioWorkerCount; i ++) {
			if(!isStarted()) {
				throw new IllegalStateException();
			}
			opBuff = opBuffs[(int) (rrc.getAndIncrement() % ioWorkerCount)];
			if(opBuff.tryLock()) {
				try {
					n = Math.min(to - j, opBuffCapacity - opBuff.size());
					for(k = 0; k < n; k ++) {
						opBuff.add(ops.get(j + k));
					}
					j += n;
				} finally {
					opBuff.unlock();
				}
			}
		}
		return j - from;
	}

	@Override
	protected final int submit(final List<O> ops)
	throws IllegalStateException {
		return submit(ops, 0, ops.size());
	}
	
	protected final void finishOperation(final O op) {
		try {
			op.startResponse();
			op.finishResponse();
			op.status(Operation.Status.SUCC);
		} catch(final IllegalStateException e) {
			LogUtil.exception(
				Level.WARN, e, "{}: finishing the load operation which is in an invalid state", op.toString()
			);
			op.status(Operation.Status.FAIL_UNKNOWN);
		}
	}
	
	@Override
	protected void doClose()
	throws IOException {
		super.doClose();
		for(final Fiber ioFiber : ioFibers) {
			ioFiber.close();
		}
		for(int i = 0; i < ioWorkerCount; i ++) {
			try(final Instance logCtx = CloseableThreadContext.put(KEY_CLASS_NAME, CLS_NAME)) {
				if(opBuffs[i].tryLock(Fiber.TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) {
					opBuffs[i].clear();
				} else if(opBuffs[i].size() > 0){
					Loggers.ERR.debug(new ThreadDumpMessage("Failed to obtain the load operations buff lock in time"));
				}
			} catch(final InterruptedException e) {
				LogUtil.exception(
					Level.WARN, e, "Unexpected failure, load operations buff remains uncleared"
				);
			}
			opBuffs[i] = null;
		}
		ioFibers.clear();
	}
}
