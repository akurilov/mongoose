package com.emc.mongoose.storage.driver.coop.net;

import com.emc.mongoose.storage.driver.coop.net.data.DataItemFileRegion;
import com.emc.mongoose.storage.driver.coop.net.data.SeekableByteChannelChunkedNioStream;
import com.github.akurilov.commons.collection.Range;
import com.github.akurilov.commons.net.ssl.SslContext;
import com.github.akurilov.commons.system.SizeInBytes;
import com.github.akurilov.commons.concurrent.ThreadUtil;

import static com.github.akurilov.fiber4j.Fiber.TIMEOUT_NANOS;

import com.github.akurilov.confuse.Config;

import com.github.akurilov.netty.connection.pool.BasicMultiNodeConnPool;
import com.github.akurilov.netty.connection.pool.NonBlockingConnPool;
import static com.github.akurilov.netty.connection.pool.NonBlockingConnPool.ATTR_KEY_NODE;

import com.emc.mongoose.storage.driver.coop.CoopStorageDriverBase;

import com.emc.mongoose.exception.OmgShootMyFootException;
import com.emc.mongoose.data.DataInput;
import com.emc.mongoose.item.op.composite.data.CompositeDataOperation;
import com.emc.mongoose.item.op.data.DataOperation;
import com.emc.mongoose.item.DataItem;
import com.emc.mongoose.item.op.OpType;
import com.emc.mongoose.item.op.Operation;
import com.emc.mongoose.item.Item;
import static com.emc.mongoose.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.Constants.KEY_STEP_ID;
import static com.emc.mongoose.item.op.Operation.Status.SUCC;
import static com.emc.mongoose.item.DataItem.rangeCount;
import com.emc.mongoose.logging.LogUtil;
import com.emc.mongoose.logging.Loggers;
import com.emc.mongoose.logging.LogContextThreadFactory;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.pool.ChannelPoolHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.IdleStateHandler;

import org.apache.logging.log4j.CloseableThreadContext;
import static org.apache.logging.log4j.CloseableThreadContext.Instance;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;
import org.apache.logging.log4j.message.ThreadDumpMessage;

import javax.net.ssl.SSLEngine;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.BitSet;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 Created by kurila on 30.09.16.
 */
public abstract class NetStorageDriverBase<I extends Item, O extends Operation<I>>
extends CoopStorageDriverBase<I, O>
implements NetStorageDriver<I, O>, ChannelPoolHandler {

	private static final String CLS_NAME = NetStorageDriverBase.class.getSimpleName();

	private static final Lock IO_EXECUTOR_LOCK = new ReentrantLock();
	private static EventLoopGroup IO_EXECUTOR = null;
	private static int IO_EXECUTOR_REF_COUNT = 0;

	protected final String storageNodeAddrs[];
	protected final Bootstrap bootstrap;
	protected final int storageNodePort;
	protected final int connAttemptsLimit;
	private final Class<SocketChannel> socketChannelCls;
	private final NonBlockingConnPool connPool;
	private final int socketTimeout;
	private final boolean sslFlag;

	@SuppressWarnings("unchecked")
	protected NetStorageDriverBase(
		final String stepId, final DataInput itemDataInput, final Config storageConfig, final boolean verifyFlag,
		final int batchSize
	) throws OmgShootMyFootException, InterruptedException {

		super(stepId, itemDataInput, storageConfig, verifyFlag, batchSize);

		final Config netConfig = storageConfig.configVal("net");
		sslFlag = netConfig.boolVal("ssl");
		if(sslFlag) {
			Loggers.MSG.info("{}: SSL/TLS is enabled", stepId);
		}
		final int sto = netConfig.intVal("timeoutMilliSec");
		if(sto > 0) {
			this.socketTimeout = sto;
		} else {
			this.socketTimeout = 0;
		}
		final Config nodeConfig = netConfig.configVal("node");
		storageNodePort = nodeConfig.intVal("port");
		connAttemptsLimit = nodeConfig.intVal("connAttemptsLimit");
		final String t[] = nodeConfig.<String>listVal("addrs").toArray(new String[]{});
		storageNodeAddrs = new String[t.length];
		String n;
		for(int i = 0; i < t.length; i ++) {
			n = t[i];
			storageNodeAddrs[i] = n + (n.contains(":") ? "" : ":" + storageNodePort);
		}
		
		final int workerCount;
		final int confWorkerCount = storageConfig.intVal("driver-threads");
		if(confWorkerCount < 1) {
			workerCount = ThreadUtil.getHardwareThreadCount();
		} else {
			workerCount = confWorkerCount;
		}
		final int ioRatio = netConfig.intVal("ioRatio");
		final Transport transportKey = Transport.valueOf(
			netConfig.stringVal("transport").toUpperCase()
		);

		if(IO_EXECUTOR_LOCK.tryLock(TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) {
			try {
				if(IO_EXECUTOR == null) {
					Loggers.MSG.info("{}: I/O executor doesn't exist yet", toString());
					if(IO_EXECUTOR_REF_COUNT != 0) {
						throw new AssertionError("I/O executor reference count should be 0");
					}
					try {
						final String ioExecutorClsName = IO_EXECUTOR_IMPLS.get(transportKey);
						final Class<EventLoopGroup> transportCls = (Class<EventLoopGroup>) Class
							.forName(ioExecutorClsName);
						IO_EXECUTOR = transportCls
							.getConstructor(Integer.TYPE, ThreadFactory.class)
							.newInstance(workerCount, new LogContextThreadFactory("ioWorker", true));
						Loggers.MSG.info("{}: use {} I/O workers", toString(), workerCount);
						try {
							final Method setIoRatioMethod = transportCls.getMethod(
								"setIoRatio", Integer.TYPE
							);
							setIoRatioMethod.invoke(IO_EXECUTOR, ioRatio);
						} catch(final ReflectiveOperationException e) {
							LogUtil.exception(Level.ERROR, e, "Failed to set the I/O ratio");
						}
					} catch(final ReflectiveOperationException e) {
						throw new AssertionError(e);
					}
				}
				IO_EXECUTOR_REF_COUNT ++;
				Loggers.MSG.debug(
					"{}: increased the I/O executor ref count to {}", toString(),
					IO_EXECUTOR_REF_COUNT
				);
			} finally {
				IO_EXECUTOR_LOCK.unlock();
			}
		} else {
			Loggers.ERR.error(new ThreadDumpMessage("Failed to obtain the I/O executor lock in time"));
		}

		final String socketChannelClsName = SOCKET_CHANNEL_IMPLS.get(transportKey);
		try {
			socketChannelCls = (Class<SocketChannel>) Class.forName(socketChannelClsName);
		} catch(final ReflectiveOperationException e) {
			throw new AssertionError(e);
		}

		bootstrap = new Bootstrap()
			.group(IO_EXECUTOR)
			.channel(socketChannelCls);
		//bootstrap.option(ChannelOption.ALLOCATOR, ByteBufAllocator)
		//bootstrap.option(ChannelOption.ALLOW_HALF_CLOSURE)
		//bootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, )
		//bootstrap.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR)
		//bootstrap.option(ChannelOption.AUTO_READ)
		bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, netConfig.intVal("timeoutMilliSec"));
		bootstrap.option(ChannelOption.WRITE_SPIN_COUNT, 1);
		int size = netConfig.intVal("rcvBuf");
		if(size > 0) {
			bootstrap.option(ChannelOption.SO_RCVBUF, size);
		}
		size = netConfig.intVal("sndBuf");
		if(size > 0) {
			bootstrap.option(ChannelOption.SO_SNDBUF, size);
		}
		//bootstrap.option(ChannelOption.SO_BACKLOG, netConfig.getBindBacklogSize());
		bootstrap.option(ChannelOption.SO_KEEPALIVE, netConfig.boolVal("keepAlive"));
		bootstrap.option(ChannelOption.SO_LINGER, netConfig.intVal("linger"));
		bootstrap.option(ChannelOption.SO_REUSEADDR, netConfig.boolVal("reuseAddr"));
		bootstrap.option(ChannelOption.TCP_NODELAY, netConfig.boolVal("tcpNoDelay"));
		try(
			final Instance logCtx = CloseableThreadContext
				.put(KEY_STEP_ID, this.stepId)
				.put(KEY_CLASS_NAME, CLS_NAME)
		) {
			connPool = createConnectionPool();
		}
	}

	protected NonBlockingConnPool createConnectionPool() {
		return new BasicMultiNodeConnPool(
			concurrencyThrottle, storageNodeAddrs, bootstrap, this, storageNodePort,
			connAttemptsLimit
		);
	}
	
	@Override
	public final void adjustIoBuffers(final long avgTransferSize, final OpType opType) {
		final int size;
		try(
			final Instance logCtx = CloseableThreadContext
				.put(KEY_STEP_ID, stepId)
				.put(KEY_CLASS_NAME, CLS_NAME)
		) {
			if(avgTransferSize < BUFF_SIZE_MIN) {
				size = BUFF_SIZE_MIN;
			} else if(BUFF_SIZE_MAX < avgTransferSize) {
				size = BUFF_SIZE_MAX;
			} else {
				size = (int) avgTransferSize;
			}
			if(OpType.CREATE.equals(opType)) {
				Loggers.MSG.info(
					"Adjust output buffer size: {}", SizeInBytes.formatFixedSize(size)
				);
				bootstrap.option(ChannelOption.SO_RCVBUF, BUFF_SIZE_MIN);
				bootstrap.option(ChannelOption.SO_SNDBUF, size);
			} else if(OpType.READ.equals(opType)) {
				Loggers.MSG.info("Adjust input buffer size: {}", SizeInBytes.formatFixedSize(size));
				bootstrap.option(ChannelOption.SO_RCVBUF, size);
				bootstrap.option(ChannelOption.SO_SNDBUF, BUFF_SIZE_MIN);
			} else {
				bootstrap.option(ChannelOption.SO_RCVBUF, BUFF_SIZE_MIN);
				bootstrap.option(ChannelOption.SO_SNDBUF, BUFF_SIZE_MIN);
			}
		}
	}

	protected Channel getUnpooledConnection()
	throws ConnectException, InterruptedException {

		final String na = storageNodeAddrs[0];
		final InetSocketAddress nodeAddr;
		if(na.contains(":")) {
			final String addrParts[] = na.split(":");
			nodeAddr = new InetSocketAddress(addrParts[0], Integer.parseInt(addrParts[1]));
		} else {
			nodeAddr = new InetSocketAddress(na, storageNodePort);
		}

		final Bootstrap bootstrap = new Bootstrap()
			.group(IO_EXECUTOR)
			.channel(socketChannelCls)
			.handler(
				new ChannelInitializer<SocketChannel>() {
					@Override
					protected final void initChannel(final SocketChannel channel)
					throws Exception {
						try(
							final Instance logCtx = CloseableThreadContext
								.put(KEY_STEP_ID, stepId)
								.put(KEY_CLASS_NAME, CLS_NAME)
						) {
							appendHandlers(channel.pipeline());
							Loggers.MSG.debug(
								"{}: new unpooled channel {}, pipeline: {}", stepId,
								channel.hashCode(), channel.pipeline()
							);
						}
					}
				}
			);

		return bootstrap.connect(nodeAddr).sync().channel();
	}

	@Override
	protected void doStart()
	throws IllegalStateException {
		super.doStart();
		if(concurrencyLimit > 0) {
			try {
				connPool.preCreateConnections(concurrencyLimit);
			} catch(final ConnectException e) {
				LogUtil.exception(Level.WARN, e, "Failed to pre-create the connections");
			}
		}
	}
	
	@Override
	protected boolean submit(final O op)
	throws IllegalStateException {

		ThreadContext.put(KEY_STEP_ID, stepId);
		ThreadContext.put(KEY_CLASS_NAME, CLS_NAME);

		if(!isStarted()) {
			throw new IllegalStateException();
		}
		try {
			if(OpType.NOOP.equals(op.type())) {
				if(concurrencyThrottle.tryAcquire()) {
					op.startRequest();
					sendRequest(null, null, op);
					op.finishRequest();
					concurrencyThrottle.release();
					op.status(SUCC);
					op.startResponse();
					complete(null, op);
				} else {
					return false;
				}
			} else {
				final Channel conn = connPool.lease();
				if(conn == null) {
					return false;
				}
				conn.attr(ATTR_KEY_OPERATION).set(op);
				op.nodeAddr(conn.attr(ATTR_KEY_NODE).get());
				op.startRequest();
				sendRequest(
					conn, conn.newPromise().addListener(new RequestSentCallback(op)), op
				);
			}
		} catch(final IllegalStateException e) {
			LogUtil.exception(Level.WARN, e, "Submit the load operation in the invalid state");
		} catch(final ConnectException e) {
			LogUtil.exception(Level.WARN, e, "Failed to lease the connection for the load operation");
			op.status(Operation.Status.FAIL_IO);
			complete(null, op);
		}
		return true;

	}
	
	@Override @SuppressWarnings("unchecked")
	protected int submit(final List<O> ops, final int from, final int to)
	throws IllegalStateException {

		ThreadContext.put(KEY_STEP_ID, stepId);
		ThreadContext.put(KEY_CLASS_NAME, CLS_NAME);

		Channel conn;
		O nextOp;
		try {
			for(int i = from; i < to && isStarted(); i ++) {
				nextOp = ops.get(i);
				if(OpType.NOOP.equals(nextOp.type())) {
					if(concurrencyThrottle.tryAcquire()) {
						nextOp.startRequest();
						sendRequest(null, null, nextOp);
						nextOp.finishRequest();
						concurrencyThrottle.release();
						nextOp.status(SUCC);
						nextOp.startResponse();
						complete(null, nextOp);
					} else {
						return i - from;
					}
				} else {
					conn = connPool.lease();
					if(conn == null) {
						return i - from;
					}
					conn.attr(ATTR_KEY_OPERATION).set(nextOp);
					nextOp.nodeAddr(conn.attr(ATTR_KEY_NODE).get());
					nextOp.startRequest();
					sendRequest(conn, conn.newPromise().addListener(new RequestSentCallback(nextOp)), nextOp);
				}
			}
		} catch(final IllegalStateException e) {
			LogUtil.exception(Level.WARN, e, "Submit the load operation in the invalid state");
		} catch(final RejectedExecutionException e) {
			if(!isStopped()) {
				LogUtil.exception(Level.WARN, e, "Failed to submit the load operation");
			}
		} catch(final ConnectException e) {
			LogUtil.exception(Level.WARN, e, "Failed to lease the connection for the load operation");
			for(int i = from; i < to; i ++) {
				nextOp = ops.get(i);
				nextOp.status(Operation.Status.FAIL_IO);
				complete(null, nextOp);
			}
		}
		return to - from;
	}
	
	@Override
	protected final int submit(final List<O> ops)
	throws IllegalStateException {
		return submit(ops, 0, ops.size());
	}
	
	/**
	 Note that the particular implementation should also invoke
	 the {@link #sendRequestData(Channel, Operation)} method to send the actual payload (if any).
	 @param channel the channel to send request to
	 @param channelPromise the promise which will be invoked when the request is sent completely
	 @param op the load operation describing the item and the operation type to perform
	 */
	protected abstract void sendRequest(final Channel channel, final ChannelPromise channelPromise, final O op);
	
	protected final void sendRequestData(final Channel channel, final O op)
	throws IOException {
		
		final OpType opType = op.type();
		
		if(OpType.CREATE.equals(opType)) {
			final I item = op.item();
			if(item instanceof DataItem) {
				final DataOperation dataOp = (DataOperation) op;
				if(!(dataOp instanceof CompositeDataOperation)) {
					final DataItem dataItem = (DataItem) item;
					final String srcPath = dataOp.srcPath();
					if(0 < dataItem.size() && (null == srcPath || srcPath.isEmpty())) {
						if(sslFlag) {
							channel.write(new SeekableByteChannelChunkedNioStream(dataItem));
						} else {
							channel.write(new DataItemFileRegion(dataItem));
						}
					}
					dataOp.countBytesDone(dataItem.size());
				}
			}
		} else if(OpType.UPDATE.equals(opType)) {
			final I item = op.item();
			if(item instanceof DataItem) {
				
				final DataItem dataItem = (DataItem) item;
				final DataOperation dataOp = (DataOperation) op;
				
				final List<Range> fixedRanges = dataOp.fixedRanges();
				if(fixedRanges == null || fixedRanges.isEmpty()) {
					// random ranges update case
					final BitSet updRangesMaskPair[] = dataOp.markedRangesMaskPair();
					final int rangeCount = rangeCount(dataItem.size());
					DataItem updatedRange;
					if(sslFlag) {
						// current layer updates first
						for(int i = 0; i < rangeCount; i ++) {
							if(updRangesMaskPair[0].get(i)) {
								dataOp.currRangeIdx(i);
								updatedRange = dataOp.currRangeUpdate();
								channel.write(
									new SeekableByteChannelChunkedNioStream(updatedRange)
								);
							}
						}
						// then next layer updates if any
						for(int i = 0; i < rangeCount; i ++) {
							if(updRangesMaskPair[1].get(i)) {
								dataOp.currRangeIdx(i);
								updatedRange = dataOp.currRangeUpdate();
								channel.write(
									new SeekableByteChannelChunkedNioStream(updatedRange)
								);
							}
						}
					} else {
						// current layer updates first
						for(int i = 0; i < rangeCount; i ++) {
							if(updRangesMaskPair[0].get(i)) {
								dataOp.currRangeIdx(i);
								updatedRange = dataOp.currRangeUpdate();
								channel.write(new DataItemFileRegion(updatedRange));
							}
						}
						// then next layer updates if any
						for(int i = 0; i < rangeCount; i ++) {
							if(updRangesMaskPair[1].get(i)) {
								dataOp.currRangeIdx(i);
								updatedRange = dataOp.currRangeUpdate();
								channel.write(new DataItemFileRegion(updatedRange));
							}
						}
					}
					dataItem.commitUpdatedRanges(dataOp.markedRangesMaskPair());
				} else { // fixed byte ranges case
					final long baseItemSize = dataItem.size();
					long beg;
					long end;
					long size;
					if(sslFlag) {
						for(final Range fixedRange : fixedRanges) {
							beg = fixedRange.getBeg();
							end = fixedRange.getEnd();
							size = fixedRange.getSize();
							if(size == -1) {
								if(beg == -1) {
									beg = baseItemSize - end;
									size = end;
								} else if(end == -1) {
									size = baseItemSize - beg;
								} else {
									size = end - beg + 1;
								}
							} else {
								// append
								beg = baseItemSize;
								// note down the new size
								dataItem.size(
									dataItem.size() + dataOp.markedRangesSize()
								);
							}
							channel.write(
								new SeekableByteChannelChunkedNioStream(
									dataItem.slice(beg, size)
								)
							);
						}
					} else {
						for(final Range fixedRange : fixedRanges) {
							beg = fixedRange.getBeg();
							end = fixedRange.getEnd();
							size = fixedRange.getSize();
							if(size == -1) {
								if(beg == -1) {
									beg = baseItemSize - end;
									size = end;
								} else if(end == -1) {
									size = baseItemSize - beg;
								} else {
									size = end - beg + 1;
								}
							} else {
								// append
								beg = baseItemSize;
								// note down the new size
								dataItem.size(
									dataItem.size() + dataOp.markedRangesSize()
								);
							}
							channel.write(new DataItemFileRegion(dataItem.slice(beg, size)));
						}
					}
				}
				dataOp.countBytesDone(dataOp.markedRangesSize());
			}
		}
	}

	@Override
	public void complete(final Channel channel, final O op) {

		ThreadContext.put(KEY_CLASS_NAME, CLS_NAME);
		ThreadContext.put(KEY_STEP_ID, stepId);

		try {
			op.finishResponse();
		} catch(final IllegalStateException e) {
			LogUtil.exception(Level.DEBUG, e, "{}: invalid load operation state", op.toString());
		}
		if(channel != null) {
			connPool.release(channel);
		}
		opCompleted(op);
	}

	@Override
	public final void channelReleased(final Channel channel)
	throws Exception {
	}
	
	@Override
	public final void channelAcquired(final Channel channel)
	throws Exception {
	}
	
	@Override
	public final void channelCreated(final Channel channel)
	throws Exception {
		try(
			final Instance ctx = CloseableThreadContext.put(KEY_STEP_ID, stepId)
				.put(KEY_CLASS_NAME, CLS_NAME)
		) {
			final ChannelPipeline pipeline = channel.pipeline();
			appendHandlers(pipeline);
			if(Loggers.MSG.isTraceEnabled()) {
				Loggers.MSG.trace("{}: new channel pipeline configured: {}", stepId, pipeline.toString());
			}
		}
	}

	protected void appendHandlers(final ChannelPipeline pipeline) {
		if(sslFlag) {
			Loggers.MSG.debug("{}: SSL/TLS is enabled for the channel", stepId);
			final SSLEngine sslEngine = SslContext.INSTANCE.createSSLEngine();
			sslEngine.setEnabledProtocols(
				new String[] { "TLSv1", "TLSv1.1", "TLSv1.2", "SSLv3" }
			);
			sslEngine.setUseClientMode(true);
			sslEngine.setEnabledCipherSuites(
				SslContext.INSTANCE.getServerSocketFactory().getSupportedCipherSuites()
			);
			pipeline.addLast(new SslHandler(sslEngine));
			/*try {
				final SslContext sslCtx = SslContextBuilder
					.forClient()
					.trustManager(InsecureTrustManagerFactory.INSTANCE)
					.build();
				pipeline.addLast(sslCtx.newHandler(pipeline.channel().alloc()));
			} catch(final SSLException e) {
				LogUtil.exception(
					Level.ERROR, e, "Failed to enable the SSL/TLS for the connection: {}",
					pipeline.channel()
				);
			}*/
		}
		if(socketTimeout > 0) {
			pipeline.addLast(
				new IdleStateHandler(
					socketTimeout, socketTimeout, socketTimeout, TimeUnit.MILLISECONDS
				)
			);
		}
	}
	
	@Override
	protected final void doStop()
	throws IllegalStateException {
		try(
			final Instance ctx = CloseableThreadContext
				.put(KEY_STEP_ID, stepId)
				.put(KEY_CLASS_NAME, CLS_NAME)
		) {
			try {
				if(IO_EXECUTOR_LOCK.tryLock(TIMEOUT_NANOS, TimeUnit.NANOSECONDS)) {
					try {
						IO_EXECUTOR_REF_COUNT --;
						Loggers.MSG.debug(
							"{}: decreased the I/O executor ref count to {}", toString(),
							IO_EXECUTOR_REF_COUNT
						);
						if(IO_EXECUTOR_REF_COUNT == 0) {
							Loggers.MSG.info("{}: shutdown the I/O executor", toString());
							if(
								IO_EXECUTOR
									.shutdownGracefully(0, 1, TimeUnit.MILLISECONDS)
									.await(10)
							) {
								Loggers.MSG.debug("{}: I/O workers stopped in time", toString());
							} else {
								Loggers.ERR.debug("{}: I/O workers stopping timeout", toString());
							}
							IO_EXECUTOR = null;
						}
					} finally {
						IO_EXECUTOR_LOCK.unlock();
					}
				} else {
					Loggers.ERR.error(new ThreadDumpMessage("Failed to obtain the I/O executor lock in time"));
				}
			} catch(final InterruptedException e) {
				LogUtil.exception(Level.WARN, e, "Graceful I/O workers shutdown was interrupted");
			}
		}
	}

	@Override
	protected void doClose()
	throws IllegalStateException, IOException {
		super.doClose();
		try {
			connPool.close();
		} catch(final IOException e) {
			LogUtil.exception(
				Level.WARN, e, "{}: failed to close the connection pool", toString()
			);
		}
	}
}
