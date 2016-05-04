package com.emc.mongoose.client.impl.load.executor;
//
import com.emc.mongoose.client.api.load.executor.FileLoadClient;
//
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.io.Input;
//
import com.emc.mongoose.core.api.item.container.Directory;
import com.emc.mongoose.core.api.item.data.FileItem;
import com.emc.mongoose.core.api.io.conf.FileIoConfig;
import com.emc.mongoose.core.api.io.task.IOTask;
//
import com.emc.mongoose.core.impl.io.task.BasicFileIOTask;
//
import com.emc.mongoose.server.api.load.executor.FileLoadSvc;
//
import java.rmi.RemoteException;
import java.util.Map;
/**
 Created by kurila on 26.11.15.
 */
public class BasicFileLoadClient<T extends FileItem, W extends FileLoadSvc<T>>
extends LoadClientBase<T, W>
implements FileLoadClient<T, W> {
	//
	public BasicFileLoadClient(
		final AppConfig appConfig, final FileIoConfig<T, ? extends Directory<T>> ioConfig,
		final int threadCount, final Input<T> itemInput, final long countLimit,
		final long sizeLimit, final float rateLimit, final Map<String, W> remoteLoadMap
	) throws RemoteException {
		super(
			appConfig, ioConfig, null, threadCount, itemInput, countLimit, sizeLimit, rateLimit,
			remoteLoadMap
		);
	}
	//
	protected BasicFileLoadClient(
		final AppConfig appConfig, final FileIoConfig<T, ? extends Directory<T>> ioConfig,
		final int threadCount, final Input<T> itemInput, final long countLimit, final long sizeLimit,
		final float rateLimit, final Map<String, W> remoteLoadMap, final int instanceNum
	) throws RemoteException {
		super(
			appConfig, ioConfig, null, threadCount, itemInput, countLimit, sizeLimit, rateLimit,
			remoteLoadMap, instanceNum
		);
	}
	//
	@Override
	protected IOTask<T> getIOTask(final T item, final String nextNodeAddr) {
		return new BasicFileIOTask<>(
			item, (FileIoConfig<T, ? extends Directory<T>>) ioConfigCopy
		);
	}
}
