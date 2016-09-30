package com.emc.mongoose.storage.driver.fs;

import com.emc.mongoose.model.api.io.task.IoTask;
import com.emc.mongoose.model.api.item.Item;
import com.emc.mongoose.model.api.load.StorageDriver;
import com.emc.mongoose.model.util.SizeInBytes;
import com.emc.mongoose.storage.driver.base.StorageDriverBuilderSvc;
import com.emc.mongoose.ui.config.Config.SocketConfig;

/**
 Created on 28.09.16.
 */
public interface FsStorageDriverBuilderSvc<
	I extends Item, O extends IoTask<I>, T extends StorageDriver<I, O>
	> extends StorageDriverBuilderSvc<I, O, T> {

	FsStorageDriverBuilderSvc<I, O, T> setIoBuffSize(final SizeInBytes ioBuffSize);

}
