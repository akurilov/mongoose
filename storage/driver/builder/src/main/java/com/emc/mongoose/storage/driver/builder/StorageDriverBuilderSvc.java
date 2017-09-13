package com.emc.mongoose.storage.driver.builder;

import com.emc.mongoose.api.common.exception.UserShootHisFootException;
import com.emc.mongoose.api.model.svc.Service;
import com.emc.mongoose.api.model.data.DataInput;
import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.item.Item;
import com.emc.mongoose.api.model.storage.StorageDriverSvc;
import com.emc.mongoose.ui.config.item.ItemConfig;
import com.emc.mongoose.ui.config.load.LoadConfig;
import com.emc.mongoose.ui.config.output.metrics.average.AverageConfig;
import com.emc.mongoose.ui.config.storage.StorageConfig;

import java.io.IOException;
import java.rmi.RemoteException;

/**
 Created by andrey on 05.10.16.
 */
public interface StorageDriverBuilderSvc<
	I extends Item, O extends IoTask<I>, T extends StorageDriverSvc<I, O>
> extends StorageDriverBuilder<I, O, T>, Service {

	String SVC_NAME = "storage/driver/builder";

	@Override
	StorageDriverBuilderSvc<I, O, T> setTestStepName(final String jobName)
	throws RemoteException;
	
	@Override
	StorageDriverBuilderSvc<I, O, T> setContentSource(final DataInput contentSrc)
	throws RemoteException;

	@Override
	StorageDriverBuilderSvc<I, O, T> setItemConfig(final ItemConfig itemConfig)
	throws RemoteException;

	@Override
	StorageDriverBuilderSvc<I, O, T> setLoadConfig(final LoadConfig loadConfig)
	throws RemoteException;

	@Override
	StorageDriverBuilderSvc<I, O, T> setAverageConfig(final AverageConfig metricsConfig)
	throws RemoteException;

	@Override
	StorageDriverBuilderSvc<I, O, T> setStorageConfig(final StorageConfig storageConfig)
	throws RemoteException;

	String buildRemotely()
	throws IOException, UserShootHisFootException;
}
