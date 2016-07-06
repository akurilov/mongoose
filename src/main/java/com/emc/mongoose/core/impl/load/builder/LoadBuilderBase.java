package com.emc.mongoose.core.impl.load.builder;
// mongoose-common.jar
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.BasicConfig;
import com.emc.mongoose.common.conf.enums.LoadType;
import com.emc.mongoose.common.io.Input;
import com.emc.mongoose.common.io.Output;
import com.emc.mongoose.common.log.Markers;
import com.emc.mongoose.common.log.LogUtil;
// mongoose-core-api.jar
import com.emc.mongoose.core.api.item.base.Item;
import com.emc.mongoose.core.api.io.conf.IoConfig;
import com.emc.mongoose.core.api.item.container.Container;
import com.emc.mongoose.core.api.load.builder.LoadBuilder;
import com.emc.mongoose.core.api.load.executor.LoadExecutor;
//
import org.apache.commons.configuration.ConversionException;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 Created by kurila on 20.10.14.
 */
public abstract class LoadBuilderBase<T extends Item, U extends LoadExecutor<T>>
implements LoadBuilder<T, U> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	protected volatile AppConfig appConfig;
	protected long countLimit = 0;
	protected long sizeLimit = 0;
	protected volatile IoConfig ioConfig;
	protected float rateLimit;
	protected int threadCount = 1;
	protected Input<T> itemInput = null;
	protected Output<T> itemOutput = null;
	protected String storageNodeAddrs[];
	//
	protected abstract IoConfig<? extends Item, ? extends Container<? extends Item>>
		getIoConfig(final AppConfig appConfig);
	//
	public LoadBuilderBase()
	throws RemoteException {
		this(BasicConfig.THREAD_CONTEXT.get());
	}
	//
	public LoadBuilderBase(final AppConfig appConfig)
	throws RemoteException {
		try {
			setAppConfig(appConfig);
		} catch(final IllegalArgumentException | IllegalStateException e) {
			LogUtil.exception(LOG, Level.ERROR, e, "Failed to apply the configuration");
		}
	}
	//
	public LoadBuilder<T, U> setAppConfig(final AppConfig appConfig)
	throws IllegalStateException, RemoteException {
		this.appConfig = appConfig;
		setIoConfig(getIoConfig(appConfig));
		//
		setThreadCount(appConfig.getLoadThreads());
		//
		String paramName = AppConfig.KEY_LOAD_LIMIT_COUNT;
		try {
			setCountLimit(appConfig.getLoadLimitCount());
		} catch(final NoSuchElementException e) {
			LOG.error(Markers.ERR, MSG_TMPL_NOT_SPECIFIED, paramName);
		} catch(final IllegalArgumentException e) {
			LOG.error(Markers.ERR, MSG_TMPL_INVALID_VALUE, paramName, e.getMessage());
		}
		//
		paramName = AppConfig.KEY_LOAD_LIMIT_SIZE;
		try {
			setSizeLimit(appConfig.getLoadLimitSize());
		} catch(final NoSuchElementException e) {
			LOG.error(Markers.ERR, MSG_TMPL_NOT_SPECIFIED, paramName);
		} catch(final IllegalArgumentException e) {
			LOG.error(Markers.ERR, MSG_TMPL_INVALID_VALUE, paramName, e.getMessage());
		}
		//
		paramName = AppConfig.KEY_LOAD_LIMIT_RATE;
		try {
			setRateLimit((float) appConfig.getLoadLimitRate());
		} catch(final NoSuchElementException e) {
			LOG.error(Markers.ERR, MSG_TMPL_NOT_SPECIFIED, paramName);
		} catch(final IllegalArgumentException e) {
			LOG.error(Markers.ERR, MSG_TMPL_INVALID_VALUE, paramName, e.getMessage());
		}
		//
		paramName = AppConfig.KEY_STORAGE_ADDRS;
		try {
			setNodeAddrs(appConfig.getStorageAddrsWithPorts());
		} catch(final NoSuchElementException | ConversionException e) {
			LOG.error(Markers.ERR, MSG_TMPL_NOT_SPECIFIED, paramName);
		} catch(final IllegalArgumentException e) {
			LOG.error(Markers.ERR, MSG_TMPL_INVALID_VALUE, paramName, e.getMessage());
		}
		//
		return this;
	}
	//
	protected boolean itemsFileExists(final String filePathStr) {
		if(filePathStr != null && !filePathStr.isEmpty()) {
			final Path listFilePath = Paths.get(filePathStr);
			if(!Files.exists(listFilePath)) {
				throw new IllegalArgumentException(
					String.format("Specified input file \"%s\" doesn't exists", listFilePath)
				);
			} else if(!Files.isReadable(listFilePath)) {
				throw new IllegalArgumentException(
					String.format("Specified input file \"%s\" isn't readable", listFilePath)
				);
			} else if(Files.isDirectory(listFilePath)) {
				throw new IllegalArgumentException(
					String.format("Specified input file \"%s\" is a directory", listFilePath)
				);
			} else {
				return true;
			}
		}
		return false;
	}
	//
	@Override
	public final IoConfig<? extends Item, ? extends Container<? extends Item>> getIoConfig() {
		return ioConfig;
	}
	//
	@Override
	public final LoadBuilder<T, U> setIoConfig(
		final IoConfig<? extends Item, ? extends Container<? extends Item>> ioConfig
	) throws ClassCastException, RemoteException {
		if(this.ioConfig != null) {
			if(this.ioConfig.equals(ioConfig)) {
				return this;
			}
			LOG.debug(Markers.MSG, "Set request builder: {}", ioConfig.toString());
			try {
				this.ioConfig.close(); // see jira ticket #437
			} catch(final IOException e) {
				LogUtil.exception(
					LOG, Level.WARN, e, "Failed to close the replacing conf config instance #{}",
					hashCode()
				);
			}
		}
		this.ioConfig = ioConfig;
		return this;
	}
	//
	@Override
	public LoadBuilder<T, U> setLoadType(final LoadType loadType)
	throws IllegalStateException, RemoteException {
		LOG.debug(Markers.MSG, "Set load type: {}", loadType);
		if(ioConfig == null) {
			throw new IllegalStateException(
				"Request builder should be specified before setting an I/O loadType"
			);
		} else {
			ioConfig.setLoadType(loadType);
		}
		return this;
	}
	//
	@Override
	public LoadBuilder<T, U> setCountLimit(final long countLimit)
	throws IllegalArgumentException, RemoteException {
		LOG.debug(Markers.MSG, "Set max item count: {}", countLimit);
		if(countLimit < 0) {
			throw new IllegalArgumentException("Count should be >= 0");
		}
		this.countLimit = countLimit;
		return this;
	}
	//
	@Override
	public LoadBuilder<T, U> setSizeLimit(final long sizeLimit)
	throws IllegalArgumentException, RemoteException {
		LOG.debug(Markers.MSG, "Set max data size count: {}", sizeLimit);
		if(sizeLimit < 0) {
			throw new IllegalArgumentException("Count should be >= 0");
		}
		this.sizeLimit = sizeLimit;
		return this;
	}
	//
	@Override
	public LoadBuilder<T, U> setRateLimit(final float rateLimit)
	throws IllegalArgumentException, RemoteException {
		LOG.debug(Markers.MSG, "Set rate limit to: {}", rateLimit);
		if(rateLimit < 0) {
			throw new IllegalArgumentException("Rate limit should not be negative");
		} else {
			LOG.debug(Markers.MSG, "Using load rate limit: {}", rateLimit);
		}
		this.rateLimit = rateLimit;
		return this;
	}
	//
	@Override
	public LoadBuilder<T, U> setThreadCount(final int threadCount)
	throws IllegalArgumentException, RemoteException {
		LOG.debug(Markers.MSG, "Set default connection count per node: {}", threadCount);
		this.threadCount = threadCount;
		return this;
	}
	//
	@Override
	public LoadBuilder<T, U> setNodeAddrs(
		final String[] nodeAddrs
	) throws IllegalArgumentException, RemoteException {
		LOG.debug(Markers.MSG, "Set storage nodes: {}", Arrays.toString(nodeAddrs));
		if(nodeAddrs == null || nodeAddrs.length == 0) {
			throw new IllegalArgumentException("Data node address list should not be empty");
		}
		this.storageNodeAddrs = nodeAddrs;
		return this;
	}
	//
	@Override @SuppressWarnings("unchecked")
	public LoadBuilder<T, U> setInput(final Input<T> itemInput)
	throws RemoteException {
		LOG.debug(Markers.MSG, "Set data items input: {}", itemInput);
		this.itemInput = itemInput;
		return this;
	}
	//
	@Override
	public LoadBuilder<T, U> setOutput(final Output<T> itemOutput)
	throws RemoteException {
		LOG.debug(Markers.MSG, "Set data items output: {}", itemOutput);
		this.itemOutput = itemOutput;
		return this;
	}
	//
	@Override @SuppressWarnings("unchecked")
	public LoadBuilderBase<T, U> clone()
	throws CloneNotSupportedException {
		final LoadBuilderBase<T, U> lb = (LoadBuilderBase<T, U>) super.clone();
		lb.appConfig = (AppConfig) appConfig.clone();
		LOG.debug(Markers.MSG, "Cloning request config for {}", ioConfig.toString());
		lb.ioConfig = ioConfig.clone();
		lb.countLimit = countLimit;
		lb.threadCount = threadCount;
		lb.storageNodeAddrs = storageNodeAddrs;
		lb.itemInput = itemInput;
		lb.itemOutput = itemOutput;
		lb.rateLimit = rateLimit;
		return lb;
	}
	//
	protected abstract Input<T> getNewItemInput(final IoConfig<T, ?> ioConfigCopy)
	throws NoSuchMethodException;
	//
	protected Input<T> selectItemInput(final IoConfig<T, ?> ioConfigCopy)
	throws IllegalStateException {
		if(null == ioConfigCopy.getSrcContainer()) {
			try {
				return itemInput == null ? getNewItemInput(ioConfigCopy) : itemInput;
			} catch(final NoSuchMethodException e) {
				throw new IllegalStateException(e);
			}
		} else {
			if(itemInput == null) {
				return ioConfigCopy
					.getContainerListInput(
						countLimit, storageNodeAddrs == null ? null : storageNodeAddrs[0]
					);
			} else {
				return itemInput;
			}
		}
	}
	//
	@Override
	public final U build()
	throws RemoteException {
		try {
			invokePreConditions();
		} catch(final RemoteException | IllegalStateException e) {
			LogUtil.exception(LOG, Level.WARN, e, "Preconditions failure");
		}
		try {
			final U loadJob = buildActually();
			loadJob.setOutput(itemOutput);
			itemInput = null;
			return loadJob;
		} catch(final CloneNotSupportedException e) {
			throw new IllegalStateException(e);
		}
	}
	//
	protected abstract U buildActually()
	throws RemoteException, CloneNotSupportedException;
	//
	@Override
	public String toString() {
		return ioConfig.toString() + "." + threadCount;
	}
	//
	@Override
	public void close()
	throws IOException {
		ioConfig.close();
	}
}
