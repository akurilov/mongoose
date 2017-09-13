package com.emc.mongoose.storage.driver.net.base.pool;

import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.pool.ChannelPoolHandler;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadLocalRandom;

/**
 Created by andrey on 23.01.17.
 The simple multi-endpoint connection pool which is throttled externally by providing the semaphore.
 The provided semaphore limits the count of the simultaneously used connections.
 Based on netty.
 */
public class BasicMultiNodeConnPool
implements NonBlockingConnPool {

	private final Semaphore concurrencyThrottle;
	private final String nodes[];
	private final int n;
	private final int connAttemptsLimit;
	private final Map<String, Bootstrap> bootstraps;
	private final Map<String, List<Channel>> allConns;
	private final Map<String, Queue<Channel>> availableConns;
	private final Object2IntMap<String> connCounts;
	private final Object2IntMap<String> failedConnAttemptCounts;

	public BasicMultiNodeConnPool(
		final int concurrencyLevel, final Semaphore concurrencyThrottle,  final String nodes[],
		final Bootstrap bootstrap, final ChannelPoolHandler connPoolHandler, final int defaultPort,
		final int connAttemptsLimit
	) {
		this.concurrencyThrottle = concurrencyThrottle;
		if(nodes.length == 0) {
			throw new IllegalArgumentException("Empty nodes array argument");
		}
		this.nodes = nodes;
		this.connAttemptsLimit = connAttemptsLimit;
		this.n = nodes.length;
		bootstraps = new HashMap<>(n);
		allConns = new HashMap<>(n);
		availableConns = new HashMap<>(n);
		connCounts = new Object2IntOpenHashMap<>(n);
		failedConnAttemptCounts = new Object2IntOpenHashMap<>(n);

		for(final String node : nodes) {
			final InetSocketAddress nodeAddr;
			if(node.contains(":")) {
				final String addrParts[] = node.split(":");
				nodeAddr = new InetSocketAddress(addrParts[0], Integer.parseInt(addrParts[1]));
			} else {
				nodeAddr = new InetSocketAddress(node, defaultPort);
			}
			bootstraps.put(
				node,
				bootstrap
					.clone()
					.remoteAddress(nodeAddr)
					.handler(
						new ChannelInitializer<Channel>() {
							@Override
							protected final void initChannel(final Channel conn)
							throws Exception {
								assert conn.eventLoop().inEventLoop();
								connPoolHandler.channelCreated(conn);
							}
						}
					)
			);
			availableConns.put(node, new ConcurrentLinkedQueue<>());
			connCounts.put(node, 0);
			failedConnAttemptCounts.put(node, 0);
		}

		// pre-create the connections
		if(concurrencyLevel > 0) {
			for(int i = 0; i < concurrencyLevel; i ++) {
				final Channel conn = connect();
				if(conn == null) {
					Loggers.ERR.warn("Failed to pre-create the connections to the target nodes");
					break;
				}
				final String nodeAddr = conn.attr(ATTR_KEY_NODE).get();
				if(conn.isActive()) {
					final Queue<Channel> connQueue = availableConns.get(nodeAddr);
					if(connQueue != null) {
						connQueue.add(conn);
					}
				} else {
					disconnect(nodeAddr, conn);
				}
			}
			Loggers.MSG.info("Pre-created {} connections", concurrencyLevel);
		}
	}

	private Channel connect() {

		Channel conn = null;
		String selectedNodeAddr = null;

		synchronized(connCounts) {

			// select the endpoint node having the minimum count of established connections
			int minConnsCount = Integer.MAX_VALUE, nextConnsCount = 0;
			String nextNodeAddr;
			for(int i = 0; i < n; i ++) {
				nextNodeAddr = nodes[i];
				nextConnsCount = connCounts.getInt(nextNodeAddr);
				if(nextConnsCount == 0) {
					selectedNodeAddr = nextNodeAddr;
					break;
				} else if(nextConnsCount < minConnsCount) {
					minConnsCount = nextConnsCount;
					selectedNodeAddr = nextNodeAddr;
				}
			}

			if(selectedNodeAddr != null) {
				// connect to the selected endpoint node
				Loggers.MSG.debug("New connection to \"{}\"", selectedNodeAddr);
				try {
					conn = connect(selectedNodeAddr);
					conn.attr(ATTR_KEY_NODE).set(selectedNodeAddr);
					allConns.computeIfAbsent(selectedNodeAddr, sna -> new ArrayList<>()).add(conn);
					connCounts.put(selectedNodeAddr, nextConnsCount + 1);
					if(connAttemptsLimit > 0) {
						// reset the connection failures counter if connected successfully
						failedConnAttemptCounts.put(selectedNodeAddr, 0);
					}
				} catch(final Exception e) {
					LogUtil.exception(
						Level.DEBUG, e, "Failed to create a new connection to {}", selectedNodeAddr
					);
					if(connAttemptsLimit > 0) {
						final int selectedNodeFailedConnAttemptsCount = failedConnAttemptCounts
							.getInt(selectedNodeAddr) + 1;
						failedConnAttemptCounts.put(
							selectedNodeAddr, selectedNodeFailedConnAttemptsCount
						);
						if(selectedNodeFailedConnAttemptsCount > connAttemptsLimit) {
							Loggers.ERR.error(
								"Failed to connect to the node \"{}\" {} times successively, " +
									"excluding the node from the connection pool forever",
								selectedNodeAddr, selectedNodeFailedConnAttemptsCount
							);
							// the node having virtually Integer.MAX_VALUE established connections
							// will never be selected by the algorithm
							connCounts.put(selectedNodeAddr, Integer.MAX_VALUE);
							boolean allNodesExcluded = true;
							for(final String node : nodes) {
								if(connCounts.getInt(node) < Integer.MAX_VALUE) {
									allNodesExcluded = false;
									break;
								}
							}
							if(allNodesExcluded) {
								Loggers.ERR.fatal("No storage nodes left in the connection pool!");
							}
						}
					}
				}
			}
		}

		return conn;
	}

	protected Channel connect(final String addr)
	throws Exception {
		return bootstraps.get(addr).connect().sync().channel();
	}

	private void disconnect(final String addr, final Channel conn) {
		synchronized(connCounts) {
			connCounts.put(addr, connCounts.getInt(addr) - 1);
		}
		synchronized(allConns) {
			allConns.get(addr).remove(conn);
		}
		conn.close();
	}
	
	protected Channel poll() {
		final int i = ThreadLocalRandom.current().nextInt(n);
		Queue<Channel> connQueue;
		Channel conn;
		for(int j = i; j < i + n; j ++) {
			connQueue = availableConns.get(nodes[j % n]);
			conn = connQueue.poll();
			if(conn != null) {
				return conn;
			}
		}
		return null;
	}

	@Override
	public final Channel lease()
	throws ConnLeaseException {
		Channel conn = null;
		if(concurrencyThrottle.tryAcquire()) {
			if(null == (conn = poll())) {
				conn = connect();
			}
			if(conn == null) {
				concurrencyThrottle.release();
				throw new ConnLeaseException();
			}
		}
		return conn;
	}
	
	@Override
	public final int lease(final List<Channel> conns, final int maxCount)
	throws ConnLeaseException {
		int availableCount = concurrencyThrottle.drainPermits();
		if(availableCount == 0) {
			return availableCount;
		}
		if(availableCount > maxCount) {
			concurrencyThrottle.release(availableCount - maxCount);
			availableCount = maxCount;
		}
		
		Channel conn;
		for(int i = 0; i < availableCount; i ++) {
			if(null == (conn = poll())) {
				conn = connect();
			}
			if(conn == null) {
				concurrencyThrottle.release(availableCount - i);
				throw new ConnLeaseException();
			} else {
				conns.add(conn);
			}
		}
		return availableCount;
	}

	@Override
	public final void release(final Channel conn) {
		final String nodeAddr = conn.attr(ATTR_KEY_NODE).get();
		if(conn.isActive()) {
			final Queue<Channel> connQueue = availableConns.get(nodeAddr);
			if(connQueue != null) {
				connQueue.add(conn);
			}
		} else {
			disconnect(nodeAddr, conn);
		}
		concurrencyThrottle.release();
	}
	
	@Override
	public final void release(final List<Channel> conns) {
		String nodeAddr;
		Queue<Channel> connQueue;
		for(final Channel conn : conns) {
			nodeAddr = conn.attr(ATTR_KEY_NODE).get();
			if(conn.isActive()) {
				connQueue = availableConns.get(nodeAddr);
				connQueue.add(conn);
			} else {
				disconnect(nodeAddr, conn);
			}
			concurrencyThrottle.release();
		}
	}

	@Override
	public void close()
	throws IOException {
		availableConns.clear();
		bootstraps.clear();
		synchronized(connCounts) {
			connCounts.clear();
		}
		int closedConnCount = 0;
		synchronized(allConns) {
			for(final String nodeAddr : allConns.keySet()) {
				for(final Channel conn : allConns.get(nodeAddr)) {
					conn.close();
					closedConnCount ++;
				}
			}
			allConns.clear();
		}
		Loggers.MSG.debug("Closed all {} connections", closedConnCount);
	}
}
