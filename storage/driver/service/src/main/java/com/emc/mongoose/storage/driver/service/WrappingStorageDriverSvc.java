package com.emc.mongoose.storage.driver.service;

import com.github.akurilov.coroutines.Coroutine;
import com.github.akurilov.coroutines.CoroutineBase;
import com.emc.mongoose.api.model.svc.ServiceUtil;
import com.github.akurilov.commons.io.Input;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.item.Item;
import com.emc.mongoose.api.model.item.ItemFactory;
import com.emc.mongoose.api.model.storage.StorageDriver;
import com.emc.mongoose.api.model.storage.StorageDriverSvc;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;
import static com.emc.mongoose.api.common.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.api.common.Constants.KEY_TEST_STEP_ID;

import org.apache.logging.log4j.CloseableThreadContext;
import org.apache.logging.log4j.Level;
import static org.apache.logging.log4j.CloseableThreadContext.Instance;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.lang.System.nanoTime;

/**
 Created by andrey on 05.10.16.
 */
public final class WrappingStorageDriverSvc<I extends Item, O extends IoTask<I>>
implements StorageDriverSvc<I, O> {
	
	private final int port;
	private final StorageDriver<I, O> driver;
	//private final Coroutine stateReportCoroutine;

	public WrappingStorageDriverSvc(
		final int port, final StorageDriver<I, O> driver, final long metricsPeriodSec,
		final String stepId
	) throws RemoteException {
		/*if(metricsPeriodSec > 0 && metricsPeriodSec < Long.MAX_VALUE) {
			stateReportCoroutine = new StateReportingCoroutine(driver, metricsPeriodSec, stepId);
		} else {
			stateReportCoroutine = null;
		}*/
		this.port = port;
		this.driver = driver;
		Loggers.MSG.info("Service started: " + ServiceUtil.create(this, port));
	}
	
	/*private final static class StateReportingCoroutine
	extends CoroutineBase
	implements Coroutine {

		private final StorageDriver driver;
		private final long metricsPeriodNanoSec;
		private final String stepName;
		private final Lock invocationLock = new ReentrantLock();
		
		private long prevNanoTimeStamp;
		private long nextNanoTimeStamp;
		
		public StateReportingCoroutine(
			final StorageDriver driver, final long metricsPeriodSec, final String stepName
		) throws RemoteException {
			super(driver.getSvcCoroutines());
			this.driver = driver;
			this.metricsPeriodNanoSec = TimeUnit.SECONDS.toNanos(metricsPeriodSec);
			this.stepName = stepName;
			this.prevNanoTimeStamp = 0;
		}
		
		@Override
		protected final void invokeTimed(final long startTimeNanos) {
			if(invocationLock.tryLock()) {
				try {
					nextNanoTimeStamp = nanoTime();
					ThreadContext.put(KEY_TEST_STEP_ID, stepName);
					ThreadContext.put(KEY_CLASS_NAME, getClass().getSimpleName());
					if(metricsPeriodNanoSec < nextNanoTimeStamp - prevNanoTimeStamp) {
						prevNanoTimeStamp = nextNanoTimeStamp;
						try {
							Loggers.MSG.info(
								"{} I/O tasks: scheduled={}, active={}, completed={}",
								driver.toString(), driver.getScheduledTaskCount(),
								driver.getActiveTaskCount(), driver.getCompletedTaskCount()
							);
						} catch(final RemoteException ignored) {
						}
					}
				} finally {
					invocationLock.unlock();
				}
			}
		}
		
		@Override
		protected final void doClose() {
			prevNanoTimeStamp = Long.MAX_VALUE;
			invocationLock.tryLock();
		}
	}*/

	@Override
	public final int getRegistryPort()
	throws RemoteException {
		return port;
	}
	
	@Override
	public final String getName()
	throws RemoteException {
		return driver.toString();
	}
	
	@Override
	public final State getState()
	throws RemoteException {
		return driver.getState();
	}
	
	@Override
	public final void start()
	throws IllegalStateException {
		try {
			driver.start();
			/*if(stateReportCoroutine != null) {
				stateReportCoroutine.start();
			}*/
		} catch(final RemoteException e) {
			throw new AssertionError(e);
		}
	}
	
	@Override
	public final O get()
	throws IOException {
		return driver.get();
	}

	@Override
	public final List<O> getAll()
	throws IOException {
		return driver.getAll();
	}
	
	@Override
	public final long skip(final long count)
	throws IOException {
		return driver.skip(count);
	}
	
	@Override
	public final void close()
	throws IOException {
		Loggers.MSG.info("Service closed: " + ServiceUtil.close(this));
		driver.close();
	}

	@Override
	public final boolean put(final O ioTask)
	throws IOException {
		return driver.put(ioTask);
	}

	@Override
	public final int put(final List<O> buffer, final int from, final int to)
	throws IOException {
		return driver.put(buffer, from, to);
	}

	@Override
	public final int put(final List<O> buffer)
	throws IOException {
		return driver.put(buffer);
	}

	// just wrapping methods below

	@Override
	public final boolean isStarted()
	throws RemoteException {
		return driver.isStarted();
	}

	@Override
	public final void shutdown()
	throws IllegalStateException, RemoteException {
		driver.shutdown();
	}

	@Override
	public final boolean isShutdown()
	throws RemoteException {
		return driver.isShutdown();
	}

	@Override
	public final void await()
	throws InterruptedException, RemoteException {
		driver.await();
	}

	@Override
	public final boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptedException, RemoteException {
		return driver.await(timeout, timeUnit);
	}

	@Override
	public final void interrupt()
	throws IllegalStateException {
		try {
			driver.interrupt();
			/*if(stateReportCoroutine != null) {
				stateReportCoroutine.close();
			}*/
		} catch(final IOException e) {
			LogUtil.exception(Level.WARN, e, "Storage driver wrapping service failed on interrupt");
		}
	}

	@Override
	public final boolean isInterrupted() {
		try {
			return driver.isInterrupted();
		} catch(final RemoteException e) {
			throw new AssertionError(e);
		}
	}

	@Override
	public final boolean isClosed()
	throws RemoteException {
		return driver.isClosed();
	}

	@Override
	public final Input<O> getInput()
	throws IOException {
		throw new RemoteException();
	}
	
	@Override
	public final List<I> list(
		final ItemFactory<I> itemFactory, final String path, final String prefix, final int idRadix,
		final I lastPrevItem, final int count
	) throws IOException {
		return driver.list(itemFactory, path, prefix, idRadix, lastPrevItem, count);
	}

	@Override
	public final int getConcurrencyLevel()
	throws RemoteException {
		return driver.getConcurrencyLevel();
	}

	@Override
	public final int getActiveTaskCount()
	throws RemoteException {
		return driver.getActiveTaskCount();
	}
	
	@Override
	public final long getScheduledTaskCount()
	throws RemoteException {
		return driver.getScheduledTaskCount();
	}
	
	@Override
	public final long getCompletedTaskCount()
	throws RemoteException {
		return driver.getCompletedTaskCount();
	}
	
	@Override
	public final boolean isIdle()
	throws RemoteException {
		return driver.isIdle();
	}

	@Override
	public final void adjustIoBuffers(final long avgTransferSize, final IoType ioType)
	throws RemoteException {
		driver.adjustIoBuffers(avgTransferSize, ioType);
	}
}
