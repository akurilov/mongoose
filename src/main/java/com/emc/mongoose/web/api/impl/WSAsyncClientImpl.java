package com.emc.mongoose.web.api.impl;
//
import com.emc.mongoose.base.api.Request;
import com.emc.mongoose.run.Main;
import com.emc.mongoose.util.conf.RunTimeConfig;
import com.emc.mongoose.util.logging.ExceptionHandler;
import com.emc.mongoose.util.logging.Markers;
import com.emc.mongoose.web.api.WSClient;
import com.emc.mongoose.web.api.WSRequest;
import com.emc.mongoose.web.api.WSRequestConfig;
import com.emc.mongoose.web.data.WSObject;
//
import org.apache.http.config.ConnectionConfig;
import org.apache.http.impl.nio.DefaultHttpClientIODispatch;
import org.apache.http.impl.nio.pool.BasicNIOConnPool;
import org.apache.http.impl.nio.reactor.DefaultConnectingIOReactor;
import org.apache.http.nio.NHttpClientEventHandler;
import org.apache.http.nio.protocol.HttpAsyncRequestExecutor;
import org.apache.http.nio.protocol.HttpAsyncRequester;
import org.apache.http.nio.reactor.ConnectingIOReactor;
import org.apache.http.nio.reactor.IOEventDispatch;
import org.apache.http.nio.reactor.IOReactorException;
import org.apache.http.protocol.HttpProcessorBuilder;
import org.apache.http.protocol.RequestConnControl;
import org.apache.http.protocol.RequestContent;
import org.apache.http.protocol.RequestExpectContinue;
import org.apache.http.protocol.RequestTargetHost;
import org.apache.http.protocol.RequestUserAgent;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.Future;
/**
 Created by kurila on 02.12.14.
 */
public final class WSAsyncClientImpl<T extends WSObject>
extends HttpAsyncRequester
implements WSClient<T> {
	//
	private final static Logger LOG = LogManager.getLogger();
	private final ConnectingIOReactor ioReactor;
	private final BasicNIOConnPool connPool;
	//
	private final static class ExecuteClientTask
	implements Runnable {
		//
		private final WSClient client;
		private final IOEventDispatch ioEventDispatch;
		private final ConnectingIOReactor ioReactor;
		//
		protected ExecuteClientTask(
			WSClient client,
			final IOEventDispatch ioEventDispatch, final ConnectingIOReactor ioReactor
		) {
			this.client = client;
			this.ioEventDispatch = ioEventDispatch;
			this.ioReactor = ioReactor;
		}
		//
		@Override
		public final void run() {
			LOG.debug(Markers.MSG, "Running the web storage client");
			try {
				ioReactor.execute(ioEventDispatch);
			} catch(final InterruptedIOException e) {
				LOG.debug(Markers.MSG, "Interrupted");
			} catch(final IOException e) {
				ExceptionHandler.trace(
					LOG, Level.ERROR, e, "Failed to execute the web storage client"
				);
			} finally {
				try {
					client.close();
				} catch(final IOException e) {
					ExceptionHandler.trace(
						LOG, Level.WARN, e, "Failed to close the web storage client"
					);
				} finally {
					LOG.debug(Markers.MSG, "Closed the web storage client");
				}
			}
		}
	}
	//
	public WSAsyncClientImpl(final int threadCount, final WSRequestConfig<T> reqConf) {
		//
		super(
			HttpProcessorBuilder
				.create()
				.add(new RequestContent())
				.add(new RequestTargetHost())
				.add(new RequestConnControl())
				.add(new RequestUserAgent(reqConf.getUserAgent()))
				.add(new RequestExpectContinue(false))
				.build()
		);
		final RunTimeConfig thrLocalConfig = Main.RUN_TIME_CONFIG.get();
		//
		final NHttpClientEventHandler reqExecutor = new HttpAsyncRequestExecutor();
		//
		final ConnectionConfig connConfig = ConnectionConfig
			.custom()
			.setBufferSize((int) thrLocalConfig.getDataPageSize())
			.build();
		//
		final IOEventDispatch ioEventDispatch = new DefaultHttpClientIODispatch(
			reqExecutor, connConfig
		);
		//
		ConnectingIOReactor localIOReactor = null;
		BasicNIOConnPool localConnPool = null;
		try {
			localIOReactor = new DefaultConnectingIOReactor();
			//
			localConnPool = new BasicNIOConnPool(localIOReactor, connConfig);
			localConnPool.setDefaultMaxPerRoute(threadCount);
			localConnPool.setMaxTotal(threadCount);
		} catch(final IOReactorException e) {
			ExceptionHandler.trace(LOG, Level.FATAL, e, "Failed to build I/O reactor");
		} finally {
			ioReactor = localIOReactor;
			connPool = localConnPool;
		}
		//
		new Thread(
			new ExecuteClientTask(this, ioEventDispatch, ioReactor),
			this.getClass().getSimpleName()
		).start();
	}
	//
	@Override
	public final void close()
	throws IOException {
		LOG.debug(Markers.MSG, "Going to close the web storage client");
		final RunTimeConfig thrLocalConfig = Main.RUN_TIME_CONFIG.get();
		ioReactor.shutdown(thrLocalConfig.getRunReqTimeOutMilliSec());
	}
	//
	@Override
	public final Future<Request.Result> execute(final Request<T> request) {
		final WSRequest<T> wsRequest = (WSRequest<T>) request;
		return execute(wsRequest, wsRequest, connPool);
	}
}
