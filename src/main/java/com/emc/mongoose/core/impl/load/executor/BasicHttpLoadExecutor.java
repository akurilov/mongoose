package com.emc.mongoose.core.impl.load.executor;
//
import com.emc.mongoose.common.concurrent.ThreadUtil;
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.enums.LoadType;
//
import com.emc.mongoose.common.io.IOWorker;
import com.emc.mongoose.common.io.Input;
import com.emc.mongoose.common.io.value.async.AsyncPatternDefinedInput;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
import com.emc.mongoose.common.net.http.conn.pool.FixedRouteSequencingConnPool;
import com.emc.mongoose.common.net.http.conn.pool.HttpConnPool;
import com.emc.mongoose.common.net.http.request.HostHeaderSetter;
import com.emc.mongoose.core.api.io.task.HttpDataIoTask;
import com.emc.mongoose.core.api.io.task.IoTask;
import com.emc.mongoose.core.api.item.base.Item;
import com.emc.mongoose.core.api.item.container.Container;
import com.emc.mongoose.core.api.load.balancer.Balancer;
import com.emc.mongoose.core.api.load.barrier.Barrier;
import com.emc.mongoose.core.api.load.executor.HttpLoadExecutor;
import com.emc.mongoose.core.api.load.generator.LoadGenerator;
import com.emc.mongoose.core.impl.load.balancer.BasicNodeBalancer;
import com.emc.mongoose.core.impl.load.barrier.ActiveTaskCountLimitBarrier;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.URISyntaxException;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
/**
 Created by kurila on 12.04.16.
 */
public class BasicHttpLoadExecutor<T extends Item, A extends IoTask<T>>
implements HttpLoadExecutor<T, A> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	private final String[] nodeAddrs;
	private final int storageNodeCount;
	private final Balancer<String> nodeBalancer;
	private final int port;
	private final boolean pipeliningEnabled;
	protected Map<String, Header> sharedHeaders = new HashMap<>();
	protected Map<String, Header> dynamicHeaders = new HashMap<>();
	private final int totalThreadCount;
	protected final Barrier<T> activeTaskCountLimitBarrier;
	//
	public BasicHttpLoadExecutor(
		final AppConfig appConfig, final String nodeAddrs[], final int threadCount,
		final boolean pipeliningFlag
	) {
		this.storageNodeCount = nodeAddrs.length;
		this.nodeAddrs = nodeAddrs;
		//

	}
	//
	private final HttpAsyncRequestProducer requestProducer = new HttpAsyncRequestProducer() {
		@Override
		public HttpHost getTarget() {
			return null;
		}
		@Override
		public HttpRequest generateRequest() throws IOException, HttpException {
			return null;
		}
		@Override
		public void produceContent(final ContentEncoder encoder, final IOControl ioctrl) throws IOException {
		}
		@Override
		public void requestCompleted(final HttpContext context) {
		}
		@Override
		public void failed(final Exception ex) {
		}
		@Override
		public boolean isRepeatable() {
			return false;
		}
		@Override
		public void resetRequest() throws IOException {
		}
		@Override
		public void close() throws IOException {
		}
	};
	//
	private final HttpAsyncResponseConsumer responseConsumer = new HttpAsyncResponseConsumer() {
		@Override
		public void responseReceived(final HttpResponse response) throws IOException, HttpException {
		}
		@Override
		public void consumeContent(final ContentDecoder decoder, final IOControl ioctrl) throws IOException {
		}
		@Override
		public void responseCompleted(final HttpContext context) {
		}
		@Override
		public void failed(final Exception ex) {
		}
		@Override
		public Exception getException() {
			return null;
		}
		@Override
		public Object getResult() {
			return null;
		}
		@Override
		public boolean isDone() {
			return false;
		}
		@Override
		public boolean cancel() {
			return false;
		}
		@Override
		public void close() throws IOException {
		}
	};
	//
	private final Map<LoadGenerator<T, A>, FutureCallback<A>>
		LOAD_GENERATOR_CALLBACKS = new HashMap<>();
	private final static class LoadGeneratorFutureCallback<T extends Item, A extends IoTask<T>>
	implements FutureCallback<A> {
		//
		private final LoadGenerator<T, A> loadGenerator;
		//
		public LoadGeneratorFutureCallback(final LoadGenerator<T, A> loadGenerator) {
			this.loadGenerator = loadGenerator;
		}
		//
		@Override
		public final void completed(final A ioTask) {
			try {
				loadGenerator.ioTaskCompleted(ioTask);
			} catch(final RemoteException ignored) {
			}
		}
		//
		@Override
		public final void failed(final Exception e) {
			try {
				loadGenerator.ioTaskFailed(1, e);
			} catch(final RemoteException ignored) {
			}
		}
		//
		@Override
		public final void cancelled() {
			try {
				loadGenerator.ioTaskCancelled(1);
			} catch(final RemoteException ignored) {
			}
		}
	}
	//
	@Override
	public final int submit(
		final LoadGenerator<T, A> loadGenerator, final List<A> ioTasks, final int from, final int to
	) throws IOException {
		final int srcLimit = to - from;
		// select the target node
		final String nextNodeAddr = storageNodeCount == 1 ? nodeAddrs[0] : nodeBalancer.getNext();
		final HttpConnPool<HttpHost, BasicNIOPoolEntry>
			connPool = connPoolMap.get(getNodeHost(nextNodeAddr));
		FutureCallback<A> futureCallback = LOAD_GENERATOR_CALLBACKS.get(loadGenerator);
		if(futureCallback == null) {
			futureCallback = new LoadGeneratorFutureCallback<>(loadGenerator);
			LOAD_GENERATOR_CALLBACKS.put(loadGenerator, futureCallback);
		}
		// prepare the I/O tasks list (make the link between the data item and load type)
		// submit all I/O tasks
		int n = 0, m;
		while(n < srcLimit) {
			// don't fill the connection pool as fast as possible, this may cause a failure
			try {
				activeTaskCountLimitBarrier.getApprovalsFor(null, to - from);

				client.execute(
					this, this, connPool, wsTask, futureCallback
				);

				if(m < 1) {
					throw new RejectedExecutionException("No I/O tasks submitted");
				} else {
					n += m;
				}
				counterIn.addAndGet(m);
				// increment node's usage counter
				if(nodeBalancer != null) {
					nodeBalancer.markTasksStart(nextNodeAddr, m);
				}
			} catch(final InterruptedException | RejectedExecutionException e) {
				if(isInterrupted()) {
					throw new InterruptedIOException(getName() + " is interrupted");
				} else {
					m = srcLimit - n;
					countRej.addAndGet(m);
					LogUtil.exception(LOG, Level.DEBUG, e, "Rejected {} I/O tasks", m);
				}
			}
		}
		return 0;
	}
	//

	//
	@Override
	public HttpHost getNodeHost(final String nodeAddr) {
		return null;
	}
	//
	@Override
	public void applyHeadersFinally(final HttpRequest httpRequest) {
	}
	//
	protected void applySuccResponseToItem(final HttpResponse response, final T item) {
	}
	//
	@Override
	public HttpEntityEnclosingRequest createDataRequest(final T obj, final String nodeAddr)
	throws URISyntaxException {
		return null;
	}
	//
	@Override
	public HttpEntityEnclosingRequest createContainerRequest(
		final Container<T> container, final String nodeAddr
	) throws URISyntaxException {
		return null;
	}
	//
	@Override
	public HttpEntityEnclosingRequest createGenericRequest(final String method, final String uri) {
		return null;
	}
	//
	@Override
	public HttpResponse execute(
		final String tgtAddr, final HttpRequest request, final long timeOut, final TimeUnit timeUnit
	) {
		//
		HttpResponse response = null;
		//
		final HttpCoreContext ctx = new HttpCoreContext();
		final HttpHost tgtHost;
		if(tgtAddr != null) {
			if(tgtAddr.contains(":")) {
				final String t[] = tgtAddr.split(":");
				tgtHost = new HttpHost(t[0], Integer.parseInt(t[1]), SCHEME);
			} else {
				tgtHost = new HttpHost(tgtAddr, port, SCHEME);
			}
		} else {
			throw new IllegalArgumentException("Failed to determine the 1st storage node address");
		}
		//
		final HttpConnPool connPool = connPoolMap.get(tgtHost);
		if(connPool != null) {
			ctx.setTargetHost(tgtHost);
			//
			try {
				response = (HttpResponse) client
					.execute(
						new BasicAsyncRequestProducer(tgtHost, request),
						new BasicAsyncResponseConsumer(), connPool, ctx
					).get(timeOut, timeUnit);
			} catch(final TimeoutException e) {
				LOG.warn(Markers.ERR, "HTTP request timeout: {}", request.getRequestLine());
			} catch(final InterruptedException e) {
				LOG.debug(Markers.ERR, "Interrupted during HTTP request execution");
			} catch(final ExecutionException e) {
				LogUtil.exception(
					LOG, Level.WARN, e,
					"HTTP request \"{}\" execution failure @ \"{}\"", request, tgtHost
				);
			}
		}
		//
		return response;
	}
	//
	private final static Map<String, Input<String>> HEADER_VALUE_INPUTS = new ConcurrentHashMap<>();
	@Override
	public final void process(final HttpRequest request, final HttpContext context)
	throws HttpException, IOException {
		// add all the shared headers if missing
		Header nextHeader;
		String headerValue;
		Input<String> headerValueInput;
		for(final String nextKey : sharedHeaders.keySet()) {
			nextHeader = sharedHeaders.get(nextKey);
			if(!request.containsHeader(nextKey)) {
				request.setHeader(nextHeader);
			}
		}
		//
		for(final String nextKey : dynamicHeaders.keySet()) {
			nextHeader = sharedHeaders.get(nextKey);
			headerValue = nextHeader.getValue();
			if(headerValue != null) {
				// header value is a generator pattern
				headerValueInput  = HEADER_VALUE_INPUTS.get(nextKey);
				// try to find the corresponding generator in the registry
				if(headerValueInput == null) {
					// create new generator and put it into the registry for reuse
					headerValueInput = new AsyncPatternDefinedInput(headerValue);
					// spin while header value generator is not ready
					while(null == (headerValue = headerValueInput.get())) {
						LockSupport.parkNanos(1);
						Thread.yield();
					}
					HEADER_VALUE_INPUTS.put(nextKey, headerValueInput);
				} else {
					headerValue = headerValueInput.get();
				}
				// put the generated header value into the request
				request.setHeader(new BasicHeader(nextKey, headerValue));
			}
		}
		// add all other required headers
		applyHeadersFinally(request);
	}
	//
	@Override
	public final void close()
	throws IOException {
		super.interrupt();
		for(final HttpConnPool<HttpHost, BasicNIOPoolEntry> nextConnPool : connPoolMap.values()) {
			try {
				nextConnPool.closeExpired();
				LOG.debug(
					Markers.MSG, "{}: closed expired (if any) connections in the pool", getName()
				);
			} catch(final IllegalStateException e) {
				LogUtil.exception(
					LOG, Level.INFO, e,
					"{}: failed to closed expired connections in the pool", getName()
				);
			}
			try {
				nextConnPool.closeIdle(1, TimeUnit.MILLISECONDS);
				LOG.debug(
					Markers.MSG, "{}: closed idle connections (if any) in the pool", getName()
				);
			} catch(final IllegalStateException e) {
				LogUtil.exception(
					LOG, Level.INFO, e,
					"{}: failed to closed expired connections in the pool", getName()
				);
			} finally {
				try {
					nextConnPool.shutdown(1);
					LOG.debug(Markers.MSG, "{}: connection pool has been shut down", getName());
				} catch(final IOException e) {
					LogUtil.exception(
						LOG, Level.WARN, e, "{}: connection pool shutdown failure", getName()
					);
				}
			}
		}
		//
		try {
			ioReactor.shutdown();
			LOG.debug(Markers.MSG, "{}: I/O reactor has been shut down", getName());
		} catch(final IOException e) {
			LogUtil.exception(
				LOG, Level.WARN, e, "{}: failed to shut down the I/O reactor", getName()
			);
		}
	}
}
