package com.emc.mongoose.tests.perf.util.mock;

import com.github.akurilov.commons.collection.Range;
import com.emc.mongoose.api.common.exception.UserShootHisFootException;
import com.emc.mongoose.api.model.data.DataInput;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.io.task.data.DataIoTask;
import com.emc.mongoose.api.model.item.DataItem;
import com.emc.mongoose.api.model.item.Item;
import com.emc.mongoose.api.model.item.ItemFactory;
import com.emc.mongoose.api.model.storage.Credential;
import com.emc.mongoose.storage.driver.nio.base.NioStorageDriverBase;
import com.emc.mongoose.ui.config.load.LoadConfig;
import com.emc.mongoose.ui.config.storage.StorageConfig;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.Collections;
import java.util.List;

/**
 Created by andrey on 11.05.17.
 */
public final class NioStorageDriverMock<I extends Item, O extends IoTask<I>>
extends NioStorageDriverBase<I, O> {

	public NioStorageDriverMock(
		final String jobName, final DataInput contentSrc, final LoadConfig loadConfig,
		final StorageConfig storageConfig, final boolean verifyFlag
	) throws UserShootHisFootException {
		super(jobName, contentSrc, loadConfig, storageConfig, verifyFlag);
	}

	@Override
	public final List<I> list(
		final ItemFactory<I> itemFactory, final String path, final String prefix, final int idRadix,
		final I lastPrevItem, final int count
	) throws IOException {
		return Collections.emptyList();
	}

	@Override
	public final void adjustIoBuffers(final long avgTransferSize, final IoType ioType)
	throws RemoteException {
	}

	@Override
	protected final String requestNewPath(final String path) {
		return path;
	}

	@Override
	protected final String requestNewAuthToken(final Credential credential) {
		throw new AssertionError("Should not be invoked");
	}

	@Override
	protected final void invokeNio(final O ioTask) {
		ioTask.reset();
		ioTask.startRequest();
		ioTask.finishRequest();
		ioTask.startResponse();
		if(ioTask instanceof DataIoTask) {
			final DataIoTask dataIoTask = (DataIoTask) ioTask;
			final DataItem dataItem = dataIoTask.getItem();
			switch(dataIoTask.getIoType()) {
				case CREATE:
					try {
						dataIoTask.setCountBytesDone(dataItem.size());
					} catch(final IOException ignored) {
					}
					break;
				case READ:
					dataIoTask.startDataResponse();
				case UPDATE:
					final List<Range> fixedRanges = dataIoTask.getFixedRanges();
					if(fixedRanges == null || fixedRanges.isEmpty()) {
						if(dataIoTask.hasMarkedRanges()) {
							dataIoTask.setCountBytesDone(dataIoTask.getMarkedRangesSize());
						} else {
							try {
								dataIoTask.setCountBytesDone(dataItem.size());
							} catch(final IOException e) {
							}
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
	public final String toString() {
		return String.format(super.toString(), "mock-nio");
	}
}
