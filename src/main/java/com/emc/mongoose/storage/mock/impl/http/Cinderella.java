package com.emc.mongoose.storage.mock.impl.http;

import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.BasicConfig;
import com.emc.mongoose.common.io.value.async.AsyncCurrentDateInput;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
import com.emc.mongoose.common.net.http.BasicSslSetupHandler;
import com.emc.mongoose.common.net.ssl.SslContext;
import com.emc.mongoose.core.impl.item.data.ContentSourceUtil;
import com.emc.mongoose.storage.mock.api.HttpDataItemMock;
import com.emc.mongoose.storage.mock.api.HttpStorageMock;
import com.emc.mongoose.storage.mock.impl.base.StorageMockBase;
import com.emc.mongoose.storage.mock.impl.http.net.BasicHttpStorageMockConnFactory;
import com.emc.mongoose.storage.mock.impl.http.net.BasicSocketEventDispatcher;
import com.emc.mongoose.storage.mock.impl.http.request.APIRequestHandlerMapper;
import org.apache.http.HttpException;
import org.apache.http.HttpResponse;
import org.apache.http.HttpResponseInterceptor;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.impl.nio.DefaultNHttpServerConnection;
import org.apache.http.impl.nio.SSLNHttpServerConnectionFactory;
import org.apache.http.nio.NHttpConnectionFactory;
import org.apache.http.nio.protocol.HttpAsyncRequestHandlerMapper;
import org.apache.http.nio.protocol.HttpAsyncService;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.nio.util.DirectByteBufferAllocator;
import org.apache.http.protocol.HTTP;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpProcessor;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseServer;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;

import static com.emc.mongoose.common.conf.Constants.BUFF_SIZE_LO;

/**
 * Created by olga on 28.01.15.
 */
public final class Cinderella<T extends HttpDataItemMock>
extends StorageMockBase<T>
implements HttpStorageMock<T> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	private final BasicSocketEventDispatcher sockEvtDispatchers[] ;
	private final HttpAsyncService protocolHandler;
	private final NHttpConnectionFactory<DefaultNHttpServerConnection> plainConnFactory;
	private final NHttpConnectionFactory<DefaultNHttpServerConnection> sslConnFactory;
	private final int portStart;
	//
	public Cinderella(final AppConfig appConfig)
	throws IOException {
		this(
			appConfig.getStorageMockHeadCount(),
			appConfig.getStoragePort(),
			appConfig.getStorageMockCapacity(),
			appConfig.getStorageMockContainerCapacity(),
			appConfig.getStorageMockContainerCountLimit(),
			appConfig.getItemSrcBatchSize(),
			appConfig.getItemSrcFile(),
			appConfig.getLoadMetricsPeriod(),
			appConfig.getNetworkServeJmx()
		);
	}
	//
	@SuppressWarnings("unchecked")
	public Cinderella(
		final int headCount, final int portStart, final int storageCapacity,
		final int containerCapacity, final int containerCountLimit, final int batchSize,
		final String dataSrcPath, final int metricsPeriodSec, final boolean jmxServeFlag
	) throws IOException {
		super(
			(Class<T>) BasicHttpDataMock.class, ContentSourceUtil.getDefaultInstance(),
			storageCapacity, containerCapacity, containerCountLimit, batchSize,
			dataSrcPath, metricsPeriodSec, jmxServeFlag
		);
		this.portStart = portStart;
		sockEvtDispatchers = new BasicSocketEventDispatcher[headCount];
		LOG.info(Markers.MSG, "Starting with {} heads", sockEvtDispatchers.length);
		// connection config
		final ConnectionConfig connConfig = ConnectionConfig
			.custom()
			.setBufferSize(BUFF_SIZE_LO)
			.setFragmentSizeHint(0)
			.build();
		plainConnFactory = new BasicHttpStorageMockConnFactory(connConfig);
		sslConnFactory = new SSLNHttpServerConnectionFactory(
			SslContext.INSTANCE, BasicSslSetupHandler.INSTANCE, null, null,
			DirectByteBufferAllocator.INSTANCE, connConfig
		);
		// Set up the HTTP protocol processor
		final HttpProcessor httpProc = HttpProcessorBuilder.create()
			.add( // this is a date header generator below
				new HttpResponseInterceptor() {
					@Override
					public void process(
						final HttpResponse response, final HttpContext context
					) throws HttpException, IOException {
						response.setHeader(HTTP.DATE_HEADER, AsyncCurrentDateInput.INSTANCE.get());
					}
				}
			)
			.add( // user-agent header
				new ResponseServer(
					Cinderella.class.getSimpleName() + "/" +
					BasicConfig.THREAD_CONTEXT.get().getRunVersion()
				)
			)
			.add(new ResponseContent())
			.add(new ResponseConnControl())
			.build();
		// Create request handler registry
		final HttpAsyncRequestHandlerMapper apiReqHandlerMapper = new APIRequestHandlerMapper<>(
			BasicConfig.THREAD_CONTEXT.get(), this
		);
		// Register the default handler for all URIs
		protocolHandler = new HttpAsyncService(httpProc, apiReqHandlerMapper);
	}
	//
	@Override
	protected final void startListening() {
		int nextPort;
		for(int i = 0; i < sockEvtDispatchers.length; i++) {
			nextPort = portStart + i;
			try {
				if(i % 2 == 0) {
					sockEvtDispatchers[i] = new BasicSocketEventDispatcher(
						BasicConfig.THREAD_CONTEXT.get(), protocolHandler, nextPort,
						plainConnFactory, ioStats
					);
				} else {
					LOG.info(Markers.MSG, "Port #{}: TLS enabled", nextPort);
					sockEvtDispatchers[i] = new BasicSocketEventDispatcher(
						BasicConfig.THREAD_CONTEXT.get(), protocolHandler, nextPort,
						sslConnFactory, ioStats
					);
				}
				sockEvtDispatchers[i].start();
			} catch(final IOReactorException e) {
				LogUtil.exception(
					LOG, Level.ERROR, e, "Failed to start the head at port #{}", nextPort
				);
			}
		}
		if(sockEvtDispatchers.length > 1) {
			LOG.info(Markers.MSG, "Listening the ports {} .. {}",
				portStart, portStart + sockEvtDispatchers.length - 1);
		} else {
			LOG.info(Markers.MSG, "Listening the port {}", portStart);
		}
		//
	}
	//
	@Override
	@SuppressWarnings("unchecked")
	protected final T newDataObject(final String id, final long offset, final long size) {
		return (T) new BasicHttpDataMock(id, offset, size, 0, contentSrc);
	}
	//
	@Override
	protected final void await() {
		try {
			for(final BasicSocketEventDispatcher sockEvtDispatcher : sockEvtDispatchers) {
				if(sockEvtDispatcher != null) {
					sockEvtDispatcher.join();
				}
			}
		} catch(final InterruptedException e) {
			LOG.info(Markers.MSG, "Interrupting the Cinderella");
		}
	}
	//
	@Override
	public final void close()
	throws IOException {
		for(final BasicSocketEventDispatcher sockEventDispatcher : sockEvtDispatchers) {
			if(sockEventDispatcher != null) {
				try {
					sockEventDispatcher.close();
					LOG.debug(
						Markers.MSG, "Socket event dispatcher \"{}\" closed successfully",
						sockEventDispatcher
					);
				} catch(final IOException e) {
					LogUtil.exception(
						LOG, Level.WARN, e, "Closing socket event dispatcher \"{}\" failure",
						sockEventDispatcher
					);
				}
			}
		}
		//
		super.close();
	}
}
