package com.emc.mongoose.storage.driver.coop.net.http.swift;

import com.emc.mongoose.data.DataInput;
import com.emc.mongoose.env.DateUtil;
import com.emc.mongoose.env.Extension;
import com.emc.mongoose.item.DataItemImpl;
import com.emc.mongoose.item.DataItem;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.item.ItemFactory;
import com.emc.mongoose.item.ItemType;
import com.emc.mongoose.item.io.IoType;
import com.emc.mongoose.item.io.task.composite.data.CompositeDataIoTaskImpl;
import com.emc.mongoose.item.io.task.composite.data.CompositeDataIoTask;
import com.emc.mongoose.item.io.task.data.DataIoTaskImpl;
import com.emc.mongoose.item.io.task.data.DataIoTask;
import com.emc.mongoose.item.io.task.partial.data.PartialDataIoTask;
import com.emc.mongoose.storage.Credential;
import static com.emc.mongoose.Constants.APP_NAME;

import com.github.akurilov.commons.collection.TreeUtil;
import com.github.akurilov.commons.system.SizeInBytes;
import com.github.akurilov.confuse.Config;
import com.github.akurilov.confuse.SchemaProvider;
import com.github.akurilov.confuse.impl.BasicConfig;

import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.stream.Collectors;

public class SwiftStorageDriverTest
extends SwiftStorageDriver {

	private static final Credential CREDENTIAL = Credential.getInstance(
		"user1", "u5QtPuQx+W5nrrQQEg7nArBqSgC8qLiDt2RhQthb"
	);
	private static final String AUTH_TOKEN = "AUTH_tk65840af9f6f74d1aaefac978cb8f0899";
	private static final String NS = "ns1";

	private static Config getConfig() {
		try {
			final List<Map<String, Object>> configSchemas = Extension
				.load(Thread.currentThread().getContextClassLoader())
				.stream()
				.map(Extension::schemaProvider)
				.filter(Objects::nonNull)
				.map(
					schemaProvider -> {
						try {
							return schemaProvider.schema();
						} catch(final Exception e) {
							fail(e.getMessage());
						}
						return null;
					}
				)
				.filter(Objects::nonNull)
				.collect(Collectors.toList());
			SchemaProvider
				.resolve(APP_NAME, Thread.currentThread().getContextClassLoader())
				.stream()
				.findFirst()
				.ifPresent(configSchemas::add);
			final Map<String, Object> configSchema = TreeUtil.reduceForest(configSchemas);
			final Config config = new BasicConfig("-", configSchema);
			config.val("load-batch-size", 4096);
			config.val("load-step-limit-concurrency", 0);
			config.val("storage-net-transport", "epoll");
			config.val("storage-net-reuseAddr", true);
			config.val("storage-net-bindBacklogSize", 0);
			config.val("storage-net-keepAlive", true);
			config.val("storage-net-rcvBuf", 0);
			config.val("storage-net-sndBuf", 0);
			config.val("storage-net-ssl", false);
			config.val("storage-net-tcpNoDelay", false);
			config.val("storage-net-interestOpQueued", false);
			config.val("storage-net-linger", 0);
			config.val("storage-net-timeoutMilliSec", 0);
			config.val("storage-net-ioRatio", 50);
			config.val("storage-net-node-addrs", Collections.singletonList("127.0.0.1"));
			config.val("storage-net-node-port", 9024);
			config.val("storage-net-node-connAttemptsLimit", 0);
			config.val("storage-net-http-namespace", NS);
			config.val("storage-net-http-fsAccess", true);
			config.val("storage-net-http-versioning", true);
			config.val("storage-net-http-headers", Collections.EMPTY_MAP);
			config.val("storage-auth-uid", CREDENTIAL.getUid());
			config.val("storage-auth-token", AUTH_TOKEN);
			config.val("storage-auth-secret", CREDENTIAL.getSecret());
			config.val("storage-driver-threads", 0);
			config.val("storage-driver-queue-input", 1_000_000);
			config.val("storage-driver-queue-output", 1_000_000);
			return config;
		} catch(final Throwable cause) {
			throw new RuntimeException(cause);
		}
	}

	private final Queue<FullHttpRequest> httpRequestsLog = new ArrayDeque<>();

	public SwiftStorageDriverTest()
	throws Exception {
		this(getConfig());
	}

	private SwiftStorageDriverTest(final Config config)
	throws Exception {
		super(
			"test-storage-driver-swift",
			DataInput.instance(null, "7a42d9c483244167", new SizeInBytes("4MB"), 16),
			config.configVal("load"), config.configVal("storage"), false
		);
	}

	@Override
	protected FullHttpResponse executeHttpRequest(final FullHttpRequest httpRequest) {
		httpRequestsLog.add(httpRequest);
		return new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
	}

	@After
	public void tearDown() {
		httpRequestsLog.clear();
	}

	@Test
	public void testRequestNewAuthToken()
	throws Exception {

		requestNewAuthToken(credential);
		assertEquals(1, httpRequestsLog.size());

		final FullHttpRequest req = httpRequestsLog.poll();
		assertEquals(HttpMethod.GET, req.method());
		assertEquals(SwiftApi.AUTH_URI, req.uri());
		final HttpHeaders reqHeaders = req.headers();
		assertEquals(storageNodeAddrs[0], reqHeaders.get(HttpHeaderNames.HOST));
		assertEquals(0, reqHeaders.getInt(HttpHeaderNames.CONTENT_LENGTH).intValue());
		final Date reqDate = DateUtil.FMT_DATE_RFC1123.parse(reqHeaders.get(HttpHeaderNames.DATE));
		assertEquals(new Date().getTime(), reqDate.getTime(), 10_000);
		assertEquals(CREDENTIAL.getUid(), reqHeaders.get(SwiftApi.KEY_X_AUTH_USER));
		assertEquals(CREDENTIAL.getSecret(), reqHeaders.get(SwiftApi.KEY_X_AUTH_KEY));
	}

	@Test
	public void testRequestNewPath()
	throws Exception {

		final String container = "/container0";
		assertEquals(container, requestNewPath(container));
		assertEquals(2, httpRequestsLog.size());

		FullHttpRequest req;
		HttpHeaders reqHeaders;
		Date reqDate;

		req = httpRequestsLog.poll();
		assertEquals(HttpMethod.HEAD, req.method());
		assertEquals(SwiftApi.URI_BASE + '/' + NS + container, req.uri());
		reqHeaders = req.headers();
		assertEquals(storageNodeAddrs[0], reqHeaders.get(HttpHeaderNames.HOST));
		assertEquals(0, reqHeaders.getInt(HttpHeaderNames.CONTENT_LENGTH).intValue());
		reqDate = DateUtil.FMT_DATE_RFC1123.parse(reqHeaders.get(HttpHeaderNames.DATE));
		assertEquals(new Date().getTime(), reqDate.getTime(), 10_000);
		assertEquals(AUTH_TOKEN, reqHeaders.get(SwiftApi.KEY_X_AUTH_TOKEN));

		req = httpRequestsLog.poll();
		assertEquals(HttpMethod.PUT, req.method());
		assertEquals(SwiftApi.URI_BASE + '/' + NS + container, req.uri());
		reqHeaders = req.headers();
		assertEquals(storageNodeAddrs[0], reqHeaders.get(HttpHeaderNames.HOST));
		assertEquals(0, reqHeaders.getInt(HttpHeaderNames.CONTENT_LENGTH).intValue());
		reqDate = DateUtil.FMT_DATE_RFC1123.parse(reqHeaders.get(HttpHeaderNames.DATE));
		assertEquals(new Date().getTime(), reqDate.getTime(), 10_000);
		assertEquals(
			SwiftApi.DEFAULT_VERSIONS_LOCATION, reqHeaders.get(SwiftApi.KEY_X_VERSIONS_LOCATION)
		);
		assertEquals(AUTH_TOKEN, reqHeaders.get(SwiftApi.KEY_X_AUTH_TOKEN));
	}

	@Test
	public void testContainerListingTest()
	throws Exception {

		final ItemFactory itemFactory = ItemType.getItemFactory(ItemType.DATA);
		final String container = "/container1";
		final String itemPrefix = "0000";
		final String markerItemId = "00003brre8lgz";
		final Item markerItem = itemFactory.getItem(
			markerItemId, Long.parseLong(markerItemId, Character.MAX_RADIX), 10240
		);

		final List<Item> items = list(
			itemFactory, container, itemPrefix, Character.MAX_RADIX, markerItem, 1000
		);

		assertEquals(0, items.size());
		assertEquals(1, httpRequestsLog.size());
		final FullHttpRequest req = httpRequestsLog.poll();
		assertEquals(HttpMethod.GET, req.method());
		assertEquals(
			SwiftApi.URI_BASE + '/' + NS + container + "?format=json&prefix=" + itemPrefix
				+ "&marker=" + markerItemId + "&limit=1000",
			req.uri()
		);
		final HttpHeaders reqHeaders = req.headers();
		assertEquals(storageNodeAddrs[0], reqHeaders.get(HttpHeaderNames.HOST));
		assertEquals(0, reqHeaders.getInt(HttpHeaderNames.CONTENT_LENGTH).intValue());
		final Date reqDate = DateUtil.FMT_DATE_RFC1123.parse(reqHeaders.get(HttpHeaderNames.DATE));
		assertEquals(new Date().getTime(), reqDate.getTime(), 10_000);
		assertEquals(AUTH_TOKEN, reqHeaders.get(SwiftApi.KEY_X_AUTH_TOKEN));
	}

	@Test
	public void testCopyRequest()
	throws Exception {

		final String containerSrcName = "/containerSrc";
		final String containerDstName = "/containerDst";
		final long itemSize = 10240;
		final String itemId = "00003brre8lgz";
		final DataItem dataItem = new DataItemImpl(
			itemId, Long.parseLong(itemId, Character.MAX_RADIX), itemSize
		);
		final DataIoTask<DataItem> ioTask = new DataIoTaskImpl<>(
			hashCode(), IoType.CREATE, dataItem, containerSrcName, containerDstName, CREDENTIAL,
			null, 0
		);
		final HttpRequest req = getHttpRequest(ioTask, storageNodeAddrs[0]);

		assertEquals(HttpMethod.PUT, req.method());
		assertEquals(SwiftApi.URI_BASE + '/' + NS + containerDstName + '/' + itemId, req.uri());
		final HttpHeaders reqHeaders = req.headers();
		assertEquals(storageNodeAddrs[0], reqHeaders.get(HttpHeaderNames.HOST));
		assertEquals(0, reqHeaders.getInt(HttpHeaderNames.CONTENT_LENGTH).intValue());
		final Date reqDate = DateUtil.FMT_DATE_RFC1123.parse(reqHeaders.get(HttpHeaderNames.DATE));
		assertEquals(new Date().getTime(), reqDate.getTime(), 10_000);
		assertEquals(
			SwiftApi.URI_BASE + '/' + NS + containerSrcName + '/' + itemId,
			reqHeaders.get(SwiftApi.KEY_X_COPY_FROM)
		);
		assertEquals(AUTH_TOKEN, reqHeaders.get(SwiftApi.KEY_X_AUTH_TOKEN));
	}

	@Test
	public void testCreateDloPartRequest()
	throws Exception {
		final String container = "/container2";
		final long itemSize = 12345;
		final long partSize = 1234;
		final String itemId = "00003brre8lgz";
		final DataItem dataItem = new DataItemImpl(
			itemId, Long.parseLong(itemId, Character.MAX_RADIX), itemSize
		);
		final CompositeDataIoTask<DataItem> dloTask = new CompositeDataIoTaskImpl<>(
			hashCode(), IoType.CREATE, dataItem, null, container, CREDENTIAL, null, 0, partSize
		);
		final PartialDataIoTask<DataItem> dloSubTask = dloTask.subTasks().get(0);
		final HttpRequest req = getHttpRequest(dloSubTask, storageNodeAddrs[0]);
		assertEquals(HttpMethod.PUT, req.method());
		assertEquals(
			SwiftApi.URI_BASE + '/' + NS + container + '/' + itemId + "/0000001",  req.uri()
		);
		final HttpHeaders reqHeaders = req.headers();
		assertEquals(storageNodeAddrs[0], reqHeaders.get(HttpHeaderNames.HOST));
		assertEquals(partSize, reqHeaders.getInt(HttpHeaderNames.CONTENT_LENGTH).intValue());
		final Date reqDate = DateUtil.FMT_DATE_RFC1123.parse(reqHeaders.get(HttpHeaderNames.DATE));
		assertEquals(new Date().getTime(), reqDate.getTime(), 10_000);
		assertEquals(AUTH_TOKEN, reqHeaders.get(SwiftApi.KEY_X_AUTH_TOKEN));
	}

	@Test
	public void testCreateDloManifestRequest()
	throws Exception {

		final String container = "container2";
		final long itemSize = 12345;
		final long partSize = 1234;
		final String itemId = "00003brre8lgz";
		final DataItem dataItem = new DataItemImpl(
			itemId, Long.parseLong(itemId, Character.MAX_RADIX), itemSize
		);
		final CompositeDataIoTask<DataItem> dloTask = new CompositeDataIoTaskImpl<>(
			hashCode(), IoType.CREATE, dataItem, null, '/' + container, CREDENTIAL, null, 0,
			partSize
		);

		// emulate DLO parts creation
		final List<? extends PartialDataIoTask<DataItem>> subTasks = dloTask.subTasks();
		for(final PartialDataIoTask<DataItem> subTask : subTasks) {
			subTask.startRequest();
			subTask.finishRequest();
			subTask.startResponse();
			subTask.finishResponse();
		}
		assertTrue(dloTask.allSubTasksDone());

		final HttpRequest req = getHttpRequest(dloTask, storageNodeAddrs[0]);
		assertEquals(HttpMethod.PUT, req.method());
		assertEquals(SwiftApi.URI_BASE + '/' + NS + '/' + container + '/' + itemId,  req.uri());
		final HttpHeaders reqHeaders = req.headers();
		assertEquals(storageNodeAddrs[0], reqHeaders.get(HttpHeaderNames.HOST));
		assertEquals(0, reqHeaders.getInt(HttpHeaderNames.CONTENT_LENGTH).intValue());
		assertEquals(container + '/' + itemId + '/', reqHeaders.get(SwiftApi.KEY_X_OBJECT_MANIFEST));
		final Date reqDate = DateUtil.FMT_DATE_RFC1123.parse(reqHeaders.get(HttpHeaderNames.DATE));
		assertEquals(new Date().getTime(), reqDate.getTime(), 10_000);
		assertEquals(AUTH_TOKEN, reqHeaders.get(SwiftApi.KEY_X_AUTH_TOKEN));
	}
}
