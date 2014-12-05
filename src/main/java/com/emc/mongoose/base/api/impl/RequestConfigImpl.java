package com.emc.mongoose.base.api.impl;
//
import com.emc.mongoose.base.api.AsyncIOClient;
import com.emc.mongoose.base.api.AsyncIOTask;
import com.emc.mongoose.base.api.RequestConfig;
import com.emc.mongoose.base.data.DataItem;
import com.emc.mongoose.base.data.DataSource;
import com.emc.mongoose.run.Main;
import com.emc.mongoose.util.conf.RunTimeConfig;
import com.emc.mongoose.base.data.impl.UniformDataSource;
import com.emc.mongoose.util.logging.ExceptionHandler;
import com.emc.mongoose.util.logging.Markers;
//
import org.apache.commons.lang.StringUtils;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
/**
 Created by kurila on 06.06.14.
 The most common implementation of the shared request configuration.
 */
public class RequestConfigImpl<T extends DataItem>
implements RequestConfig<T> {
	//
	private final static Logger LOG = LogManager.getLogger();
	protected final static String FMT_URI_ADDR = "%s://%s:%s";
	//
	protected String api, secret, userName;
	protected AsyncIOTask.Type loadType;
	protected DataSource<T> dataSrc;
	protected volatile boolean retryFlag, verifyContentFlag, closeFlag = false;
	protected volatile RunTimeConfig runTimeConfig = Main.RUN_TIME_CONFIG.get();
	protected volatile String addr, scheme, uriAddr;
	protected volatile int port;
	protected AsyncIOClient<T> storageClient = null;
	protected int loadNumber;
	//
	@SuppressWarnings("unchecked")
	public RequestConfigImpl() {
		this(null);
	}
	//
	@SuppressWarnings("unchecked")
	protected RequestConfigImpl(final RequestConfig<T> reqConf2Clone) {
		try {
			if(reqConf2Clone==null) {
				dataSrc = (DataSource<T>) UniformDataSource.DEFAULT;
				retryFlag = runTimeConfig.getRunRequestRetries();
				verifyContentFlag = runTimeConfig.getReadVerifyContent();
			} else {
				setDataSource(reqConf2Clone.getDataSource());
				setRetries(reqConf2Clone.getRetries());
				setVerifyContentFlag(reqConf2Clone.getVerifyContentFlag());
				//
				setAddr(reqConf2Clone.getAddr());
				setAPI(reqConf2Clone.getAPI());
				secret = reqConf2Clone.getSecret();
				setUserName(reqConf2Clone.getUserName());
				setPort(reqConf2Clone.getPort());
				setLoadType(reqConf2Clone.getLoadType());
				setClient(reqConf2Clone.getClient());
			}
		} catch(final Exception e) {
			ExceptionHandler.trace(LOG, Level.ERROR, e, "Request config instantiation failure");
		}
	}
	//
	@Override
	public RequestConfigImpl<T> clone() {
		return new RequestConfigImpl<>(this);
	}
	//
	@Override
	public final String getAPI() {
		return api;
	}
	@Override
	public RequestConfigImpl<T> setAPI(final String api) {
		this.api = api;
		return this;
	}
	//
	@Override
	public final AsyncIOTask.Type getLoadType() {
		return loadType;
	}
	@Override
	public RequestConfigImpl<T> setLoadType(final AsyncIOTask.Type loadType) {
		LOG.trace(Markers.MSG, "Setting load type {}", loadType);
		this.loadType = loadType;
		return this;
	}
	//
	@Override
	public final String getScheme() {
		return scheme;
	}
	@Override
	public final RequestConfigImpl<T> setScheme(final String scheme) {
		this.scheme = scheme;
		uriAddr = String.format(
			FMT_URI_ADDR,
			scheme, addr == null ? "%s" : addr,
			(port > 0 && port < 0x10000) ? Integer.toString(port) : "%s"
		);
		return this;
	}
	//
	@Override
	public final String getAddr() {
		return addr;
	}
	@Override
	public final RequestConfigImpl<T> setAddr(final String addr) {
		this.addr = addr;
		uriAddr = String.format(
			FMT_URI_ADDR,
			scheme == null ? "%s" : scheme, addr,
			(port > 0 && port < 0x10000) ? Integer.toString(port) : "%s"
		);
		return this;
	}
	//
	@Override
	public final int getPort() {
		return port;
	}
	@Override
	public final RequestConfigImpl<T> setPort(final int port)
	throws IllegalArgumentException {
		LOG.trace(Markers.MSG, "Using storage port: {}", port);
		if(port>0 || port<0x10000) {
			this.port = port;
			uriAddr = String.format(
				FMT_URI_ADDR,
				scheme == null ? "%s" : scheme, addr == null ? "%s" : addr, Integer.toString(port)
			);
		} else {
			throw new IllegalArgumentException("Port number value should be > 0");
		}
		return this;
	}
	//
	@Override
	public final String getUserName() {
		return userName;
	}
	@Override
	public RequestConfigImpl<T> setUserName(final String userName) {
		this.userName = userName;
		return this;
	}
	//
	@Override
	public final String getSecret() {
		return secret;
	}
	@Override
	public RequestConfigImpl<T> setSecret(final String secret) {
		this.secret = secret;
		return this;
	}
	//
	@Override
	public final DataSource<T> getDataSource() {
		return dataSrc;
	}
	@Override
	public RequestConfigImpl<T> setDataSource(final DataSource<T> dataSrc) {
		this.dataSrc = dataSrc;
		return this;
	}
	//
	@Override
	public final boolean getRetries() {
		return retryFlag;
	}
	@Override
	public RequestConfigImpl<T> setRetries(final boolean retryFlag) {
		this.retryFlag = retryFlag;
		return this;
	}
	//
	@Override
	public final boolean getVerifyContentFlag() {
		return verifyContentFlag;
	}
	//
	@Override
	public final RequestConfigImpl<T> setVerifyContentFlag(final boolean verifyContentFlag) {
		this.verifyContentFlag = verifyContentFlag;
		return this;
	}
	//
	@Override
	public RequestConfigImpl<T> setProperties(final RunTimeConfig runTimeConfig) {
		this.runTimeConfig = runTimeConfig;
		//
		final String api = runTimeConfig.getStorageApi();
		setAPI(api);
		setPort(this.runTimeConfig.getApiPort(api));
		setUserName(this.runTimeConfig.getAuthId());
		setSecret(this.runTimeConfig.getAuthSecret());
		setRetries(this.runTimeConfig.getRunRequestRetries());
		return this;
	}
	//
	@Override
	public final int getLoadNumber() {
		return loadNumber;
	}
	//
	@Override
	public final RequestConfig<T> setLoadNumber(int loadNumber) {
		this.loadNumber = loadNumber;
		return this;
	}
	//
	@Override
	public AsyncIOClient<T> getClient() {
		return storageClient;
	}
	//
	@Override
	public RequestConfig<T> setClient(final AsyncIOClient<T> storageClient) {
		this.storageClient = storageClient;
		return this;
	}
	//
	@Override
	public void writeExternal(final ObjectOutput out)
	throws IOException {
		out.writeObject(getAPI());
		out.writeObject(getAddr());
		out.writeObject(getLoadType());
		out.writeInt(getPort());
		out.writeObject(getUserName());
		out.writeObject(getSecret());
		out.writeObject(getDataSource());
		out.writeBoolean(getRetries());
	}
	//
	@Override @SuppressWarnings("unchecked")
	public void readExternal(final ObjectInput in)
	throws IOException, ClassNotFoundException {
		setAPI(String.class.cast(in.readObject()));
		LOG.trace(Markers.MSG, "Got API {}", api);
		setAddr(String.class.cast(in.readObject()));
		LOG.trace(Markers.MSG, "Got address {}", addr);
		setLoadType(AsyncIOTask.Type.class.cast(in.readObject()));
		LOG.trace(Markers.MSG, "Got load type {}", loadType);
		setPort(in.readInt());
		LOG.trace(Markers.MSG, "Got port {}", port);
		setUserName(String.class.cast(in.readObject()));
		LOG.trace(Markers.MSG, "Got user name {}", userName);
		setSecret(String.class.cast(in.readObject()));
		LOG.trace(Markers.MSG, "Got secret {}", secret);
		setDataSource((DataSource<T>) in.readObject());
		LOG.trace(Markers.MSG, "Got data source {}", dataSrc);
		setRetries(Boolean.class.cast(in.readBoolean()));
		LOG.trace(Markers.MSG, "Got retry flag {}", retryFlag);
	}
	//
	@Override
	public final String toString() {
		return StringUtils.capitalize(getAPI()) + '.' +
			StringUtils.capitalize(loadType.name().toLowerCase()) +
			((addr==null || addr.length()==0) ? "" : "@"+addr);
	}
	//
	@Override
	public void configureStorage() {
		throw new IllegalStateException("Not implemented");
	}
	//
	@Override
	public final synchronized void close() {
		closeFlag = true;
	}
	//
	@Override
	public final synchronized boolean isClosed() {
		return closeFlag;
	}
}
