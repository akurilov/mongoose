package com.emc.mongoose.storage.driver.net.base;

import com.emc.mongoose.common.concurrent.NamingThreadFactory;
import com.emc.mongoose.common.concurrent.ThreadUtil;
import com.emc.mongoose.common.net.ssl.SslContext;
import com.emc.mongoose.common.io.Input;
import com.emc.mongoose.model.io.task.IoTask;
import com.emc.mongoose.model.io.task.result.IoResult;
import com.emc.mongoose.model.item.Item;
import com.emc.mongoose.common.io.UniformOptionSelector;
import com.emc.mongoose.storage.driver.base.StorageDriverBase;
import static com.emc.mongoose.ui.config.Config.LoadConfig;
import static com.emc.mongoose.ui.config.Config.StorageConfig;
import static com.emc.mongoose.ui.config.Config.SocketConfig;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Markers;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.pool.ChannelPool;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.pool.FixedChannelPool;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.GenericFutureListener;
import org.apache.commons.lang.SystemUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 Created by kurila on 30.09.16.
 */
public abstract class NetStorageDriverBase<I extends Item, O extends IoTask<I>, R extends IoResult>
extends StorageDriverBase<I, O, R>
implements NetStorageDriver<I, O, R>, ChannelPoolHandler {
	
	private static final Logger LOG = LogManager.getLogger();
	
	protected final String storageNodeAddrs[];
	private final int storageNodePort;
	private final Input<String> nodeSelector;
	private final EventLoopGroup workerGroup;
	private final Map<String, ChannelPool> connPoolMap = new ConcurrentHashMap<>();
	private final boolean sslFlag;
	
	protected NetStorageDriverBase(
		final String jobName, final LoadConfig loadConfig,
		final StorageConfig storageConfig, final SocketConfig socketConfig, final boolean verifyFlag
	) {
		super(jobName, storageConfig.getAuthConfig(), loadConfig, verifyFlag);
		sslFlag = storageConfig.getSsl();
		storageNodePort = storageConfig.getPort();
		final String t[] = storageConfig.getNodeConfig().getAddrs().toArray(new String[]{});
		storageNodeAddrs = new String[t.length];
		String n;
		for(int i = 0; i < t.length; i ++) {
			n = t[i];
			storageNodeAddrs[i] = n + (n.contains(":") ? "" : ":" + storageNodePort);
		}
		nodeSelector = new UniformOptionSelector<>(storageNodeAddrs);
		if(SystemUtils.IS_OS_LINUX) {
			workerGroup = new EpollEventLoopGroup(
				ThreadUtil.getHardwareConcurrencyLevel(), new NamingThreadFactory("ioWorker", true)
			);
		} else {
			workerGroup = new NioEventLoopGroup(
				ThreadUtil.getHardwareConcurrencyLevel(), new NamingThreadFactory("ioWorker", true)
			);
		}
		final Bootstrap bootstrap = new Bootstrap()
			.group(workerGroup)
			.channel(SystemUtils.IS_OS_LINUX ? EpollSocketChannel.class : NioSocketChannel.class );
		//bootstrap.option(ChannelOption.ALLOCATOR, ByteBufAllocator)
		//bootstrap.option(ChannelOption.ALLOW_HALF_CLOSURE)
		//bootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, )
		//bootstrap.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR)
		//bootstrap.option(ChannelOption.AUTO_READ)
		//bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS)
		//bootstrap.option(ChannelOption.SO_RCVBUF);
		//bootstrap.option(ChannelOption.SO_SNDBUF);
		//bootstrap.option(ChannelOption.SO_BACKLOG, socketConfig.getBindBackLogSize());
		bootstrap.option(ChannelOption.SO_KEEPALIVE, socketConfig.getKeepAlive());
		bootstrap.option(ChannelOption.SO_LINGER, socketConfig.getLinger());
		bootstrap.option(ChannelOption.SO_REUSEADDR, socketConfig.getReuseAddr());
		//bootstrap.option(ChannelOption.SO_TIMEOUT, socketConfig.getTimeoutMillisec());
		bootstrap.option(ChannelOption.TCP_NODELAY, socketConfig.getTcpNoDelay());
		for(final String na : storageNodeAddrs) {
			final InetSocketAddress nodeAddr;
			if(na.contains(":")) {
				final String addrParts[] = na.split(":");
				nodeAddr = new InetSocketAddress(addrParts[0], Integer.parseInt(addrParts[1]));
			} else {
				nodeAddr = new InetSocketAddress(na, storageNodePort);
			}
			connPoolMap.put(
				na, new FixedChannelPool(bootstrap.remoteAddress(nodeAddr), this, concurrencyLevel)
			);
		}
	}

	protected Channel getChannel()
	throws InterruptedException {

		final String na = storageNodeAddrs[0];
		final InetSocketAddress nodeAddr;
		if(na.contains(":")) {
			final String addrParts[] = na.split(":");
			nodeAddr = new InetSocketAddress(addrParts[0], Integer.parseInt(addrParts[1]));
		} else {
			nodeAddr = new InetSocketAddress(na, storageNodePort);
		}

		final Bootstrap bootstrap = new Bootstrap()
			.group(workerGroup)
			.channel(SystemUtils.IS_OS_LINUX ? EpollSocketChannel.class : NioSocketChannel.class)
			.handler(
				new ChannelInitializer<SocketChannel>() {
					@Override
					protected final void initChannel(final SocketChannel channel)
					throws Exception {
						appendSpecificHandlers(channel.pipeline());
					}
				}
			);

		return bootstrap.connect(nodeAddr).sync().channel();
	}
	
	@Override
	public final void put(final O task)
	throws InterruptedIOException {
		final String bestNode;
		if(storageNodeAddrs.length == 1) {
			bestNode = storageNodeAddrs[0];
		} else {
			try {
				bestNode = nodeSelector.get();
			} catch(final IOException e) {
				LogUtil.exception(LOG, Level.WARN, e, "Failed to get the best node");
				return;
			}
		}
		if(bestNode == null) {
			return;
		}
		task.setNodeAddr(bestNode);
		try {
			concurrencyThrottle.acquire();
		} catch(final InterruptedException e) {
			throw new InterruptedIOException(e.getMessage());
		}
		connPoolMap.get(bestNode).acquire().addListener(new ConnectionLeaseCallback(task));
	}
	
	@Override
	public final int put(final List<O> tasks, final int from, final int to)
	throws IOException {
		final int n = to - from;
		if(storageNodeAddrs.length == 1) {
			O nextTask;
			try {
				for(int i = 0; i < n; i ++) {
					nextTask = tasks.get(i + from);
					nextTask.setNodeAddr(storageNodeAddrs[0]);
					concurrencyThrottle.acquire();
					connPoolMap
						.get(storageNodeAddrs[0]).acquire()
						.addListener(new ConnectionLeaseCallback(nextTask));
				}
			} catch(final InterruptedException e) {
				throw new InterruptedIOException(e.getMessage());
			}
		} else {
			final List<String> nodeBuff = new ArrayList<>(n);
			if(n != nodeSelector.get(nodeBuff, n)) {
				throw new IllegalStateException("Node selector unexpected behavior");
			}
			O nextTask;
			String nextNode;
			try {
				for(int i = 0; i < n; i++) {
					nextTask = tasks.get(i + from);
					nextTask.reset();
					nextNode = nodeBuff.get(i);
					nextTask.setNodeAddr(nextNode);
					concurrencyThrottle.acquire();
					connPoolMap
						.get(nextNode).acquire()
						.addListener(new ConnectionLeaseCallback(nextTask));
				}
			} catch(final InterruptedException e) {
				throw new InterruptedIOException(e.getMessage());
			}
		}
		return n;
	}
	
	@Override
	public final int put(final List<O> tasks)
	throws IOException {
		return put(tasks, 0, tasks.size());
	}

	private final class ConnectionLeaseCallback
	implements FutureListener<Channel> {

		private final O ioTask;

		public ConnectionLeaseCallback(final O ioTask) {
			this.ioTask = ioTask;
		}

		@Override
		public final void operationComplete(final Future<Channel> future)
		throws Exception {
			final Channel channel = future.getNow();
			if(channel == null) {
				if(!isClosed() && !isInterrupted()) {
					LOG.warn(Markers.ERR, "Failed to obtain the storage node connection");
				} // else ignore
			} else {
				channel.attr(ATTR_KEY_IOTASK).set(ioTask);
				final RequestSentCallback reqSentCallback = new RequestSentCallback(ioTask);
				ioTask.startRequest();
				sendRequest(channel, ioTask, reqSentCallback).addListener(reqSentCallback);
			}
		}
	}

	protected abstract ChannelFuture sendRequest(
		final Channel channel, final O ioTask,
		final GenericFutureListener<Future<Void>> reqSentCallback
	);

	private static final class RequestSentCallback
		implements FutureListener<Void> {

		private final IoTask ioTask;

		public RequestSentCallback(final IoTask ioTask) {
			this.ioTask = ioTask;
		}

		@Override
		public final void operationComplete(final Future<Void> future)
		throws Exception {
			ioTask.finishRequest();
		}
	}

	@Override
	public final void complete(final Channel channel, final O ioTask) {
		ioTask.finishResponse();
		final ChannelPool connPool = connPoolMap.get(ioTask.getNodeAddr());
		if(connPool != null) {
			connPool.release(channel);
		}
		ioTaskCompleted(ioTask);
	}
	
	@Override
	public final void channelReleased(final Channel channel)
	throws Exception {
		concurrencyThrottle.release();
	}
	
	@Override
	public final void channelAcquired(final Channel channel)
	throws Exception {
	}
	
	@Override
	public final void channelCreated(final Channel channel)
	throws Exception {
		appendSpecificHandlers(channel.pipeline());
	}

	protected void appendSpecificHandlers(final ChannelPipeline pipeline) {
		if(sslFlag) {
			final SSLEngine sslEngine = SslContext.INSTANCE.createSSLEngine();
			sslEngine.setUseClientMode(true);
			pipeline.addLast(new SslHandler(sslEngine));
		}
	}

	@Override
	protected void doStart()
	throws IllegalStateException {
	}
	
	@Override
	protected void doShutdown()
	throws IllegalStateException {
	}
	
	@Override
	public boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptedException {
		return false;
	}
	
	@Override
	protected final void doInterrupt()
	throws IllegalStateException {
		workerGroup.shutdownGracefully(1, 1, TimeUnit.SECONDS);
		/*try {
			f.await(1, TimeUnit.SECONDS);
		} catch(final InterruptedException e) {
			LOG.warn(Markers.ERR, "Failed to interrupt the storage driver gracefully");
		}*/
	}
	
	@Override
	protected void doClose()
	throws IOException {
		super.doClose();
		for(int i = 0; i < storageNodeAddrs.length; i ++) {
			if(!workerGroup.isShutdown()) {
				try {
					final ChannelPool connPool = connPoolMap.remove(storageNodeAddrs[i]);
					if(connPool != null) {
						connPool.close();
					}
				} catch(final Throwable cause) {
					LogUtil.exception(
						LOG, Level.WARN, cause, "Failed to close the connection pool for {}",
						storageNodeAddrs[i]
					);
				}
			}
			storageNodeAddrs[i] = null;
		}
		connPoolMap.clear();
		nodeSelector.close();
		workerGroup.shutdownGracefully(1, 1, TimeUnit.NANOSECONDS);
	}
}
