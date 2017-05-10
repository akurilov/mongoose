package com.emc.mongoose.storage.driver.base;

import com.emc.mongoose.common.api.ByteRange;
import com.emc.mongoose.common.api.SizeInBytes;
import com.emc.mongoose.common.io.Input;
import com.emc.mongoose.model.DaemonBase;
import com.emc.mongoose.model.io.IoType;
import com.emc.mongoose.model.io.task.IoTask;
import com.emc.mongoose.model.io.task.data.DataIoTask;
import com.emc.mongoose.model.item.DataItem;
import com.emc.mongoose.model.item.Item;
import com.emc.mongoose.model.item.ItemFactory;
import com.emc.mongoose.model.storage.StorageDriver;
import com.emc.mongoose.ui.log.Loggers;
import static com.emc.mongoose.ui.config.Config.LoadConfig;
import static com.emc.mongoose.ui.config.Config.StorageConfig;

import java.io.EOFException;
import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 Created by andrey on 11.05.17.
 */
public class BasicStorageDriverMock<I extends Item, O extends IoTask<I>>
extends DaemonBase
implements StorageDriver<I, O> {

	private final int batchSize;
	private final int queueCapacity;
	private final BlockingQueue<O> ioResultsQueue;
	private final LongAdder scheduledTaskCount = new LongAdder();
	private final LongAdder completedTaskCount = new LongAdder();

	public BasicStorageDriverMock(
		final String stepName, final LoadConfig loadConfig, final StorageConfig storageConfig,
		final boolean verifyFlag
	) {
		this.batchSize = loadConfig.getBatchConfig().getSize();
		this.queueCapacity = loadConfig.getQueueConfig().getSize();
		this.ioResultsQueue = new ArrayBlockingQueue<>(queueCapacity);
	}

	@Override
	public boolean put(final O task)
	throws IOException {
		if(!isStarted()) {
			throw new EOFException();
		}
		checkStateFor(task);
		if(ioResultsQueue.offer(task)) {
			scheduledTaskCount.increment();
			completedTaskCount.increment();
			return true;
		} else {
			return false;
		}
	}

	@Override
	public int put(final List<O> tasks, final int from, final int to)
	throws IOException {
		if(!isStarted()) {
			throw new EOFException();
		}
		int i = from;
		O nextTask;
		while(i < to && isStarted()) {
			nextTask = tasks.get(i);
			checkStateFor(nextTask);
			if(ioResultsQueue.offer(tasks.get(i))) {
				i ++;
			} else {
				break;
			}
		}
		final int n = i - from;
		scheduledTaskCount.add(n);
		completedTaskCount.add(n);
		return n;
	}

	@Override
	public int put(final List<O> tasks)
	throws IOException {
		if(!isStarted()) {
			throw new EOFException();
		}
		int n = 0;
		for(final O nextIoTask : tasks) {
			if(isStarted()) {
				checkStateFor(nextIoTask);
				if(ioResultsQueue.offer(nextIoTask)) {
					n ++;
				} else {
					break;
				}
			} else {
				break;
			}
		}
		scheduledTaskCount.add(n);
		completedTaskCount.add(n);
		return n;
	}

	private void checkStateFor(final O ioTask)
	throws IOException {
		ioTask.reset();
		ioTask.startRequest();
		ioTask.finishRequest();
		ioTask.startResponse();
		if(ioTask instanceof DataIoTask) {
			final DataIoTask dataIoTask = (DataIoTask) ioTask;
			final DataItem dataItem = dataIoTask.getItem();
			switch(dataIoTask.getIoType()) {
				case CREATE:
					dataIoTask.setCountBytesDone(dataItem.size());
					break;
				case READ:
					dataIoTask.startDataResponse();
				case UPDATE:
					final List<ByteRange> fixedByteRanges = dataIoTask.getFixedRanges();
					if(fixedByteRanges == null || fixedByteRanges.isEmpty()) {
						if(dataIoTask.hasMarkedRanges()) {
							dataIoTask.setCountBytesDone(dataIoTask.getMarkedRangesSize());
						} else {
							dataIoTask.setCountBytesDone(dataItem.size());
						}
					} else {
						dataIoTask.setCountBytesDone(dataIoTask.getMarkedRangesSize());
					}
					break;
				default:
					break;
			}
			dataIoTask.startDataResponse();
		}
		ioTask.finishResponse();
		ioTask.setStatus(IoTask.Status.SUCC);
	}

	@Override
	public Input<O> getInput()
	throws IOException {
		return this;
	}

	@Override
	public O get()
	throws EOFException, IOException {
		return ioResultsQueue.poll();
	}

	@Override
	public final List<O> getAll() {
		final List<O> ioTaskResults = new ArrayList<>(queueCapacity);
		ioResultsQueue.drainTo(ioTaskResults, queueCapacity);
		return ioTaskResults;
	}

	@Override
	public long skip(final long count)
	throws IOException {
		int n = (int) Math.min(count, Integer.MAX_VALUE);
		final List<O> tmpBuff = new ArrayList<>(n);
		n = ioResultsQueue.drainTo(tmpBuff, n);
		tmpBuff.clear();
		return n;
	}

	@Override
	public List<I> list(
		final ItemFactory<I> itemFactory, final String path, final String prefix, final int idRadix,
		final I lastPrevItem, final int count
	)
	throws IOException {
		return null;
	}

	@Override
	public int getConcurrencyLevel()
	throws RemoteException {
		return 0;
	}

	@Override
	public int getActiveTaskCount()
	throws RemoteException {
		return 0;
	}

	@Override
	public long getScheduledTaskCount()
	throws RemoteException {
		return scheduledTaskCount.sum();
	}

	@Override
	public long getCompletedTaskCount()
	throws RemoteException {
		return completedTaskCount.sum();
	}

	@Override
	public boolean isIdle()
	throws RemoteException {
		return true;
	}

	@Override
	public void adjustIoBuffers(
		final SizeInBytes avgDataItemSize, final IoType ioType
	) throws RemoteException {
	}

	@Override
	protected void doShutdown()
	throws IllegalStateException {
		Loggers.MSG.debug("{}: shut down", toString());
	}

	@Override
	public boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptedException, RemoteException {
		return true;
	}

	@Override
	protected void doInterrupt()
	throws IllegalStateException {
		Loggers.MSG.debug("{}: interrupted", toString());
	}

	@Override
	protected void doClose()
	throws IllegalStateException {
		ioResultsQueue.clear();
		Loggers.MSG.debug("{}: closed", toString());
	}
}
