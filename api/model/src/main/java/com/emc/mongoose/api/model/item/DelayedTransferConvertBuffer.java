package com.emc.mongoose.api.model.item;

import com.github.akurilov.commons.io.Input;
import com.github.akurilov.coroutines.StoppableTask;

import com.emc.mongoose.api.model.io.task.IoTask;

import java.io.EOFException;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;
import static java.lang.System.nanoTime;

/**
 Created by kurila on 16.01.17.
 */
public final class DelayedTransferConvertBuffer<I extends Item, O extends IoTask<I>>
implements TransferConvertBuffer<I, O> {

	private static final Logger LOG = Logger.getLogger(
		DelayedTransferConvertBuffer.class.getName()
	);
	
	private final List<O> ioResultsBuff;
	private final int ioResultsBuffLimit;
	private final List<O> markBuffer;
	private final long delayMicroseconds;
	private final Lock lock = new ReentrantLock();
	
	private volatile int markLimit = 0;
	private volatile int ioResultsBuffSize = 0;
	private volatile boolean poisonedFlag = false;

	public DelayedTransferConvertBuffer(
		final int limit, final TimeUnit timeUnit, final long delay
	) {
		this.ioResultsBuff = new LinkedList<>();
		this.ioResultsBuffLimit = limit;
		this.markBuffer = new LinkedList<>();
		this.delayMicroseconds = timeUnit.toMicros(delay);
	}

	/**
	 * Block until the free space in the buff is available
	 * @param ioResult
	 * @return always true
	 * @throws IOException
	 */
	@Override
	public final boolean put(final O ioResult)
	throws EOFException, IOException {
		if(poisonedFlag) {
			throw new EOFException("Poisoned already");
		}
		if(ioResult == null) {
			return poisonedFlag = true;
		}
		while(true) {
			if(lock.tryLock()) {
				try {
					if(ioResultsBuffSize < ioResultsBuffLimit) {
						ioResultsBuff.add(ioResult);
						ioResultsBuffSize ++;
						return true;
					}
				} finally {
					lock.unlock();
				}
			}
			LockSupport.parkNanos(1);
		}
	}

	/**
	 * Block until all the items from the given range are consumed
	 * @param ioResults
	 * @param from
	 * @param to
	 * @return
	 * @throws IOException
	 */
	@Override
	public final int put(final List<O> ioResults, final int from, final int to)
	throws EOFException, IOException {
		if(poisonedFlag) {
			throw new EOFException("Poisoned already");
		}
		int n;
		O ioResult;
		for(int i = from; i < to; i ++) {
			if(lock.tryLock()) {
				try {
					n = Math.min(to - i, ioResultsBuffLimit - ioResultsBuffSize);
					if(n > 0) {
						for(int j = 0; j < n; j ++) {
							ioResult = ioResults.get(i + j);
							if(ioResult == null) {
								poisonedFlag = true;
								return to - i - j;
							}
							ioResultsBuff.add(ioResult);
						}
						i += n;
						// avoid blocking, there's a chance to exit the outer loop
						continue;
					}
				} finally {
					lock.unlock();
				}
			}
			LockSupport.parkNanos(1);
		}
		return to - from;
	}

	/**
	 * Block until all the given items are consumed
	 * @param ioResults
	 * @return
	 * @throws IOException
	 */
	@Override
	public final int put(final List<O> ioResults)
	throws IOException {
		return put(ioResults, 0, ioResults.size());
	}

	/** Don't use this method, it will cause the assertion error */
	@Override
	public final Input<O> getInput()
	throws IOException {
		throw new AssertionError();
	}
	
	@Override
	public final I get()
	throws EOFException, IOException {

		I item = null;

		if(lock.tryLock()) {
			try {
				if(ioResultsBuffSize == 0 && poisonedFlag) {
					throw new EOFException();
				}

				O nextIoResult;
				long nextFinishTime, currTime;
				final ListIterator<O> ioResultsIter = ioResultsBuff.listIterator();
				while(ioResultsIter.hasNext()) {
					nextIoResult = ioResultsIter.next();
					if(delayMicroseconds > 0) {
						nextFinishTime = nextIoResult.getRespTimeDone();
						currTime = IoTask.START_OFFSET_MICROS + nanoTime() / 1000;
						if(currTime - nextFinishTime > delayMicroseconds) {
							item = nextIoResult.getItem();
							if(markLimit > 0 && markLimit > markBuffer.size()) {
								markBuffer.add(nextIoResult);
							}
							ioResultsIter.remove();
							ioResultsBuffSize --;
							break;
						}
					} else {
						item = nextIoResult.getItem();
						if(markBuffer.size() < markLimit) {
							markBuffer.add(nextIoResult);
						}
						ioResultsIter.remove();
						ioResultsBuffSize --;
						break;
					}
				}
			} finally {
				lock.unlock();
			}
		}

		return item;
	}
	
	@Override
	public final int get(final List<I> buffer, final int limit)
	throws IOException {

		int n = 0;

		if(lock.tryLock()) {
			try {
				if(ioResultsBuffSize == 0 && poisonedFlag) {
					throw new EOFException();
				}

				O nextIoResult;
				long nextFinishTime, currTime;
				final ListIterator<O> ioResultsIter = ioResultsBuff.listIterator();
				if(delayMicroseconds > 0) {
					while(ioResultsIter.hasNext() && n < limit) {
						nextIoResult = ioResultsIter.next();
						nextFinishTime = nextIoResult.getRespTimeDone();
						currTime = IoTask.START_OFFSET_MICROS + nanoTime() / 1000;
						if(currTime - nextFinishTime > delayMicroseconds) {
							buffer.add(nextIoResult.getItem());
							if(markLimit > 0 && markLimit > markBuffer.size()) {
								markBuffer.add(nextIoResult);
							}
							ioResultsIter.remove();
							ioResultsBuffSize --;
							n ++;
						}
					}
				} else {
					while(ioResultsIter.hasNext() && n < limit) {
						nextIoResult = ioResultsIter.next();
						buffer.add(nextIoResult.getItem());
						if(markLimit > 0 && markLimit > markBuffer.size()) {
							markBuffer.add(nextIoResult);
						}
						ioResultsIter.remove();
						ioResultsBuffSize --;
						n ++;
					}
				}
			} finally {
				lock.unlock();
			}
		}

		return n;
	}
	
	@Override
	public final long skip(final long count)
	throws IOException {
		long n = 0;
		if(lock.tryLock()) {
			try {
				final Iterator<O> ioResultsIter = ioResultsBuff.iterator();
				while(n < count && ioResultsIter.hasNext()) {
					ioResultsIter.remove();
					n ++;
				}
			} finally {
				lock.unlock();
			}
		}
		return n;
	}
	
	@Override
	public final void reset()
	throws IOException {
		throw new AssertionError("Unable to reset this input");
	}
	
	@Override
	public final void close() {
		try {
			if(!lock.tryLock(StoppableTask.CLOSE_TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) {
				LOG.log(Level.WARNING, "Failed to obtain the lock in time to close safely");
			}
		} catch(final InterruptedException e) {
			throw new CancellationException();
		} finally {
			ioResultsBuff.clear();
			ioResultsBuffSize = 0;
		}
	}

	@Override
	public final String toString() {
		if(delayMicroseconds > 0) {
			return "PreviousItemsWithDelay" + (delayMicroseconds / 1_000_000) + "s";
		} else {
			return "PreviousItems";
		}
	}
}
