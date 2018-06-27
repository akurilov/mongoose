package com.emc.mongoose.supply.async;

import com.emc.mongoose.exception.OmgDoesNotPerformException;
import com.emc.mongoose.supply.ValueUpdatingSupplier;

import com.github.akurilov.commons.concurrent.InitCallable;

import com.github.akurilov.fiber4j.ExclusiveFiberBase;
import com.github.akurilov.fiber4j.Fiber;
import com.github.akurilov.fiber4j.FibersExecutor;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 Created by kurila on 10.02.16.
 */
public class AsyncValueUpdatingSupplier<T>
extends ValueUpdatingSupplier<T> {
	
	private static final Logger LOG = Logger.getLogger(AsyncValueUpdatingSupplier.class.getName());
	
	private final Fiber updateTask;
	
	public AsyncValueUpdatingSupplier(
		final FibersExecutor executor, final T initialValue, final InitCallable<T> updateAction
	) throws OmgDoesNotPerformException {

		super(initialValue, null);
		if(updateAction == null) {
			throw new NullPointerException("Argument should not be null");
		}

		updateTask = new ExclusiveFiberBase(executor) {

			@Override
			protected final void invokeTimedExclusively(final long startTimeNanos) {
				try {
					lastValue = updateAction.call();
				} catch(final Exception e) {
					LOG.log(Level.WARNING, "Failed to execute the value update action", e);
					e.printStackTrace(System.err);
				}
			}

			@Override
			protected final void doClose()
			throws IOException {
				lastValue = null;
			}
		};

		try {
			updateTask.start();
		} catch(final RemoteException ignored) {
		}
	}
	
	public static abstract class InitCallableBase<T>
	implements InitCallable<T> {
		@Override
		public final boolean isInitialized() {
			return true;
		}
	}
	
	@Override
	public final T get() {
		// do not refresh on the request
		return lastValue;
	}
	
	@Override
	public final int get(final List<T> buffer, final int limit) {
		int count = 0;
		for(; count < limit; count ++) {
			buffer.add(lastValue);
		}
		return count;
	}
	
	@Override
	public long skip(final long count) {
		return 0;
	}
	
	@Override
	public void close()
	throws IOException {
		super.close();
		updateTask.close();
	}
}
