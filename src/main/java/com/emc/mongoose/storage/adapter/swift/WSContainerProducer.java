package com.emc.mongoose.storage.adapter.swift;
//
import com.emc.mongoose.core.api.load.model.Consumer;
import com.emc.mongoose.core.api.load.model.Producer;
import com.emc.mongoose.core.api.io.task.WSIOTask;
import com.emc.mongoose.core.api.data.DataObject;
import com.emc.mongoose.core.api.data.WSObject;
import com.emc.mongoose.core.api.load.executor.WSLoadExecutor;
import com.emc.mongoose.core.api.util.log.Markers;
import com.emc.mongoose.core.impl.util.log.TraceLogger;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.entity.ContentType;
import org.apache.http.util.EntityUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.rmi.RemoteException;
import java.util.concurrent.RejectedExecutionException;
/**
 Created by kurila on 04.03.15.
 */
public final class WSContainerProducer<T extends WSObject>
extends Thread
implements Producer<T> {
	//
	private final static Logger LOG = LogManager.getLogger();
	private final static JsonFactory JSON_FACTORY = new JsonFactory();
	//
	private volatile Consumer<T> consumer = null;
	private final WSContainerImpl<T> container;
	private final Constructor<T> dataConstructor;
	private final long maxCount;
	private final WSLoadExecutor<T> wsClient;
	//
	@SuppressWarnings("unchecked")
	public WSContainerProducer(
		final WSContainerImpl<T> container, final Class<? extends WSObject> dataCls, final long maxCount,
		final WSLoadExecutor<T> wsClient
	) throws ClassCastException, NoSuchMethodException {
		super("container-" + container + "-producer");
		this.container = container;
		this.dataConstructor = (Constructor<T>) dataCls.getConstructor(
			String.class, Long.class, Long.class
		);
		this.maxCount = maxCount > 0 ? maxCount : Long.MAX_VALUE;
		this.wsClient = wsClient;
	}
	//
	@Override
	public final void setConsumer(final Consumer<T> consumer) {
		this.consumer = consumer;
	}
	//
	@Override
	public final Consumer<T> getConsumer() {
		return consumer;
	}
	//
	@Override
	public final void run() {
		try {
			final HttpResponse httpResp = container.execute(wsClient, WSIOTask.HTTPMethod.GET);
			if(httpResp != null) {
				final StatusLine statusLine = httpResp.getStatusLine();
				if(statusLine == null) {
					LOG.warn(Markers.MSG, "No response status returned");
				} else {
					final int statusCode = statusLine.getStatusCode();
					if(statusCode >= 200 && statusCode < 300) {
						final HttpEntity respEntity = httpResp.getEntity();
						if(respEntity != null) {
							String respContentType = ContentType.APPLICATION_JSON.getMimeType();
							if(respEntity.getContentType() != null) {
								respContentType = respEntity.getContentType().getValue();
							} else {
								LOG.debug(Markers.ERR, "No content type returned");
							}
							if(ContentType.APPLICATION_JSON.getMimeType().equals(respContentType)) {
								try(final InputStream in = respEntity.getContent()) {
									handleJsonInputStream(in);
								} catch(final IOException e) {
									TraceLogger.failure(
										LOG, Level.ERROR, e,
										String.format(
											"Failed to list the content of container \"%s\"",
											container
										)
									);
								}
							} else {
								LOG.warn(
									Markers.MSG, "Unexpected response content type: \"{}\"",
									respContentType
								);
							}
							EntityUtils.consumeQuietly(respEntity);
						}
					}
				}
			}
		} catch(final IOException e) {
			TraceLogger.failure(
				LOG, Level.ERROR, e,
				String.format("Failed to list the container \"%s\"", container)
			);
		} catch(final InterruptedException e) {
			LOG.debug(Markers.MSG, "Container \"{}\" producer interrupted", container);
		} finally {
			try {
				consumer.submit(null);
			} catch(final Exception e) {
				TraceLogger.failure(
					LOG, Level.DEBUG, e,
					"Failed to submit the poison to the consumer"
				);
			}
		}
	}
	//
	private final static String KEY_SIZE = "bytes", KEY_ID = "name";
	private boolean isInsideObjectToken = false;
	private String lastId = null;
	private long lastSize = -1, offset, count = 0;
	//
	private void handleJsonInputStream(final InputStream in)
	throws IOException, InterruptedException {
		try(final JsonParser jsonParser = JSON_FACTORY.createParser(in)) {
			final JsonToken rootToken = jsonParser.nextToken();
			JsonToken nextToken;
			T nextDataItem;
			if(JsonToken.START_ARRAY.equals(rootToken)) {
				do {
					nextToken = jsonParser.nextToken();
					switch(nextToken) {
						case START_OBJECT:
							if(isInsideObjectToken) {
								LOG.debug(Markers.ERR, "Looks like the json response is not plain");
							}
							isInsideObjectToken = true;
							break;
						case END_OBJECT:
							if(isInsideObjectToken) {
								if(lastId != null && lastSize > -1) {
									try {
										offset = Long.parseLong(lastId, DataObject.ID_RADIX);
										if(offset < 0) {
											LOG.warn(
												Markers.ERR,
												"Calculated from id ring offset is negative"
											);
										} else if(count < maxCount) {
											nextDataItem = dataConstructor
												.newInstance(lastId, offset, lastSize);
											consumer.submit(nextDataItem);
											if(LOG.isTraceEnabled(Markers.MSG)) {
												LOG.trace(
													Markers.MSG, "Submitted \"{}\" to consumer",
													nextDataItem
												);
											}
											count++;
										} else {
											break;
										}
									} catch(
										final InstantiationException | IllegalAccessException |
											InvocationTargetException e
										) {
										TraceLogger.failure(
											LOG, Level.WARN, e,
											"Failed to create data item descriptor"
										);
									} catch(final RemoteException | RejectedExecutionException e) {
										TraceLogger.failure(
											LOG, Level.WARN, e,
											"Failed to submit new data object to the consumer"
										);
									} catch(final NumberFormatException e) {
										LOG.debug(Markers.ERR, "Invalid id: {}", lastId);
									}
								} else {
									LOG.trace(
										Markers.ERR, "Invalid object id ({}) or size ({})",
										lastId, lastSize
									);
								}
							} else {
								LOG.debug(Markers.ERR, "End of json object is not inside object");
							}
							isInsideObjectToken = false;
							break;
						case FIELD_NAME:
							if(KEY_SIZE.equals(jsonParser.getCurrentName())) {
								lastSize = jsonParser.nextLongValue(-1);
							}
							if(KEY_ID.equals(jsonParser.getCurrentName())) {
								lastId = jsonParser.nextTextValue();
							}
							break;
						case VALUE_NUMBER_INT:
						case VALUE_STRING:
						case VALUE_NULL:
						case VALUE_FALSE:
						case VALUE_NUMBER_FLOAT:
						case VALUE_TRUE:
						case VALUE_EMBEDDED_OBJECT:
						case NOT_AVAILABLE:
						default:
							break;
					}
				} while(!JsonToken.END_ARRAY.equals(nextToken));
			} else {
				LOG.warn(
					Markers.ERR,
					"Response contains root JSON token \"{}\", but array token was expected"
				);
			}
		}
	}
}
