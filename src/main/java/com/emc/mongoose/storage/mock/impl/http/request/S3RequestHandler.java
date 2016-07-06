package com.emc.mongoose.storage.mock.impl.http.request;
// mongoose-common.jar
// mongoose-storage-mock.jar
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
//
import com.emc.mongoose.core.api.item.data.ContainerHelper;
import com.emc.mongoose.core.api.io.conf.HttpRequestConfig;
import com.emc.mongoose.storage.adapter.s3.BucketHelper;
//
import com.emc.mongoose.storage.mock.api.ContainerMockException;
import com.emc.mongoose.storage.mock.api.ContainerMockNotFoundException;
import com.emc.mongoose.storage.mock.api.HttpStorageMock;
//
import com.emc.mongoose.storage.mock.api.HttpDataItemMock;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.entity.ContentType;
//
import org.apache.http.nio.entity.NByteArrayEntity;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
/**
 Created by andrey on 13.05.15.
 */
public final class S3RequestHandler<T extends HttpDataItemMock>
extends HttpStorageMockRequestHandlerBase<T> {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	private final static String
		MAX_KEYS = "maxKeys", MARKER = "marker",
		BUCKET = "bucket", OBJ_ID = "objId",
		AUTH_PREFIX = "AWS ";
	private final static Pattern
		PATTERN_URI = Pattern.compile("/(?<" + BUCKET + ">[^/^\\?]+)/?(?<" + OBJ_ID + ">[^\\?]+)?"),
		PATTERN_MAX_KEYS = Pattern.compile(
			BucketHelper.URL_ARG_MAX_KEYS + "=(?<" + MAX_KEYS +  ">[\\d]+)&?"
		),
		PATTERN_MARKER = Pattern.compile(BucketHelper.URL_ARG_MARKER + "=(?<" + MARKER + ">[a-z\\d]+)&?");
	//
	private final String prefix;
	private final int prefixLength, idRadix;
	//
	public S3RequestHandler(final AppConfig appConfig, final HttpStorageMock<T> sharedStorage)
	throws IllegalArgumentException {
		super(appConfig, sharedStorage);
		prefix = appConfig.getItemNamingPrefix();
		prefixLength = prefix == null ? 0 : prefix.length();
		idRadix = appConfig.getItemNamingRadix();
	}
	//
	@Override
	public boolean matches(final HttpRequest httpRequest) {
		return true;
	}
	//
	@Override
	public final void handleActually(
		final HttpRequest httpRequest, final HttpResponse httpResponse,
		final String method, final String requestURI
	) {
		final Matcher m = PATTERN_URI.matcher(requestURI);
		try {
			if(m.find()) {
				final String
					bucket = m.group(BUCKET),
					objName = m.group(OBJ_ID);
				if(bucket != null) {
					if(objName == null) {
						handleGenericContainerReq(httpRequest, httpResponse, method, bucket, null);
					} else {
						final long offset;
						if(
							HttpRequestConfig.METHOD_PUT.equalsIgnoreCase(method) ||
							HttpRequestConfig.METHOD_POST.equalsIgnoreCase(method)
						) {
							if(prefixLength > 0) {
								offset = Long.parseLong(objName.substring(prefixLength + 1), idRadix);
							} else {
								offset = Long.parseLong(objName, idRadix);
							}
						} else {
							offset = -1;
						}
						handleGenericDataReq(
							httpRequest, httpResponse, method, bucket, objName, offset
						);
					}
				} else {
					httpResponse.setStatusCode(HttpStatus.SC_BAD_REQUEST);
				}
			} else {
				httpResponse.setStatusCode(HttpStatus.SC_BAD_REQUEST);
			}
		} catch(final IllegalArgumentException | IllegalStateException e) {
			LogUtil.exception(
				LOG, Level.WARN, e, "Failed to parse the request URI: {}", requestURI
			);
			httpResponse.setStatusCode(HttpStatus.SC_BAD_REQUEST);
		}
	}
	//
	private final static DocumentBuilder DOM_BUILDER;
	private final static TransformerFactory TF = TransformerFactory.newInstance();
	static {
		try {
			DOM_BUILDER = DocumentBuilderFactory.newInstance().newDocumentBuilder();
		} catch(final ParserConfigurationException e) {
			throw new RuntimeException(e);
		}
	}
	//
	@Override
	protected final void handleContainerList(
		final HttpRequest req, final HttpResponse resp, final String name, final String dataId
	) {
		final String uri = req.getRequestLine().getUri();
		int maxCount = ContainerHelper.DEFAULT_PAGE_SIZE;
		String marker = null;
		final Matcher maxKeysMatcher = PATTERN_MAX_KEYS.matcher(uri);
		if(maxKeysMatcher.find()) {
			try {
				maxCount = Integer.parseInt(maxKeysMatcher.group(MAX_KEYS));
			} catch(final NumberFormatException e) {
				LOG.warn(Markers.ERR, "Failed to parse max keys argument value in the URI: " + uri);
			}
		}
		final Matcher markerMatcher = PATTERN_MARKER.matcher(uri);
		if(markerMatcher.find()) {
			try {
				marker = markerMatcher.group(MARKER);
			} catch(final IllegalArgumentException ignored) {
			}
		}
		//
		final List<T> buff = new ArrayList<>(maxCount);
		final T lastObj;
		try {
			lastObj = sharedStorage.listObjects(name, marker, buff, maxCount);
			if(LOG.isTraceEnabled(Markers.MSG)) {
				LOG.trace(
					Markers.MSG, "Bucket \"{}\": generated list of {} objects, last one is \"{}\"",
					name, buff.size(), lastObj
				);
			}
		} catch(final ContainerMockNotFoundException e) {
			resp.setStatusCode(HttpStatus.SC_NOT_FOUND);
			return;
		} catch(final ContainerMockException e) {
			resp.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
			return;
		}
		resp.setHeader(HttpHeaders.CONTENT_TYPE, ContentType.APPLICATION_XML.getMimeType());
		//
		final Document doc = DOM_BUILDER.newDocument();
		final Element eRoot = doc.createElementNS(
			"http://s3.amazonaws.com/doc/2006-03-01/", "ListBucketResult"
		);
		doc.appendChild(eRoot);
		//
		Element e = doc.createElement("Name"), ee;
		e.appendChild(doc.createTextNode(name));
		eRoot.appendChild(e);
		e = doc.createElement("IsTruncated");
		e.appendChild(doc.createTextNode(Boolean.toString(lastObj != null)));
		eRoot.appendChild(e);
		e = doc.createElement("Prefix"); // TODO prefix support
		eRoot.appendChild(e);
		e = doc.createElement("MaxKeys");
		e.appendChild(doc.createTextNode(Integer.toString(buff.size())));
		eRoot.appendChild(e);
		//
		for(final T dataObject : buff) {
			e = doc.createElement("Contents");
			ee = doc.createElement("Key");
			ee.appendChild(doc.createTextNode(dataObject.getName()));
			e.appendChild(ee);
			ee = doc.createElement("Size");
			ee.appendChild(doc.createTextNode(Long.toString(dataObject.getSize())));
			e.appendChild(ee);
			eRoot.appendChild(e);
		}
		//
		final ByteArrayOutputStream bos = new ByteArrayOutputStream();
		final StreamResult r = new StreamResult(bos);
		try {
			TF.newTransformer().transform(new DOMSource(doc), r);
		} catch(final TransformerException ex) {
			resp.setStatusCode(HttpStatus.SC_INTERNAL_SERVER_ERROR);
			LogUtil.exception(LOG, Level.ERROR, ex, "Failed to build bucket XML listing");
			return;
		}
		//
		resp.setEntity(new NByteArrayEntity(bos.toByteArray(), ContentType.APPLICATION_XML));
	}
}
