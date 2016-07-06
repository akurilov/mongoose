package com.emc.mongoose.common.conf;
//
import com.emc.mongoose.common.conf.enums.ItemNamingType;
import com.emc.mongoose.common.conf.enums.ItemType;
import com.emc.mongoose.common.conf.enums.LoadType;
import com.emc.mongoose.common.conf.enums.StorageType;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
//
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
//
import org.apache.commons.configuration.Configuration;
import org.apache.commons.configuration.ConversionException;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.commons.configuration.SystemConfiguration;
import org.apache.commons.configuration.tree.DefaultExpressionEngine;
//
import org.apache.commons.lang.text.StrBuilder;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.ThreadContext;
//
import java.io.File;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.emc.mongoose.common.conf.Constants.DIR_CONF;
/**
 Created by kurila on 20.01.16.
 */
public class BasicConfig
extends HierarchicalConfiguration
implements AppConfig {
	//
	public static final ThreadLocal<AppConfig>
		THREAD_CONTEXT = new InheritableThreadLocal<AppConfig>() {
		@Override
		protected final AppConfig initialValue() {
			return new BasicConfig();
		}
	};
	//
	static {
		setDefaultExpressionEngine(new DefaultExpressionEngine());
	}

	// http://stackoverflow.com/a/29665447
	public static String getBasePathForClass(final Class<?> cls) {
		try {
			String basePath;
			final File clsFile = new File(
				cls.getProtectionDomain().getCodeSource().getLocation().toURI().getPath()
			);
			if(
				clsFile.isFile() ||
				clsFile.getPath().endsWith(".jar") ||
				clsFile.getPath().endsWith(".zip")
			) {
				basePath = clsFile.getParent();
			} else {
				basePath = clsFile.getPath();
			}
			// bandage for eclipse
			if(
				basePath.endsWith(File.separator + "lib") ||
				basePath.endsWith(File.separator + "bin") ||
				basePath.endsWith("bin" + File.separator) ||
				basePath.endsWith("lib" + File.separator)
			) {
				basePath = basePath.substring(0, basePath.length() - 4);
			}
			// bandage for netbeans
			if (basePath.endsWith(File.separator + "build" + File.separator + "classes")) {
				basePath = basePath.substring(0, basePath.length() - 14);
			}
			// bandage for gradle
			if (basePath.endsWith(File.separator + "build" + File.separator + "classes" + File.separator + "main")) {
				basePath = basePath.substring(0, basePath.length() - 19);
			}
			// bandage for idea
			if (basePath.endsWith(File.separator + "build" + File.separator + "resources" + File.separator + "common")) {
				basePath = basePath.substring(0, basePath.length() - 23);
			}
			// final fix
			if(!basePath.endsWith(File.separator)) {
				basePath = basePath + File.separator;
			}
			return basePath;
		} catch(final URISyntaxException e) {
			throw new RuntimeException("Cannot figure out base path for class: " + cls.getName());
		}
	}
	//
	private static String DIR_WORKING = null;
	public static String getWorkingDir() {
		if(DIR_WORKING == null) {
			DIR_WORKING = getBasePathForClass(BasicConfig.class);
		}
		return DIR_WORKING;
	}
	//
	public final static Map<String, String[]> MAP_OVERRIDE = new HashMap<>();
	//
	public BasicConfig() {
		this(Paths.get(getWorkingDir(), DIR_CONF).resolve(FNAME_CONF));
	}
	//
	public BasicConfig(final Path cfgFilePath) {
		final Logger log = LogManager.getLogger();
		loadFromJson(cfgFilePath);
		loadFromEnv();
		log.info(Markers.CFG, toFormattedString());
	}
	//
	public BasicConfig(final byte[] appConfigBytes) {
		final Logger log = LogManager.getLogger();
		loadFromJson(appConfigBytes);
		log.info(Markers.CFG, toFormattedString());
	}
	//
	@Override
	public String getAuthId() {
		return getString(KEY_AUTH_ID);
	}
	//
	@Override
	public String getAuthSecret() {
		return getString(KEY_AUTH_SECRET);
	}
	//
	@Override
	public String getAuthToken() {
		return getString(KEY_AUTH_TOKEN);
	}
	//
	@Override
	public int getIoBufferSizeMin() {
		try {
			return (int) SizeInBytes.toFixedSize(getString(KEY_IO_BUFFER_SIZE_MIN));
		} catch(final ConversionException e) {
			return getInt(KEY_IO_BUFFER_SIZE_MIN);
		}

	}
	//
	@Override
	public int getIoBufferSizeMax() {
		try {
			return (int)SizeInBytes.toFixedSize(getString(KEY_IO_BUFFER_SIZE_MAX));
		} catch(final ConversionException e) {
			return getInt(KEY_IO_BUFFER_SIZE_MAX);
		}
	}
	//
	@Override
	public ItemType getItemType() {
		return ItemType.valueOf(getString(KEY_ITEM_TYPE).toUpperCase());
	}
	//
	@Override
	public String getItemDataContentFile() {
		return getString(KEY_ITEM_DATA_CONTENT_FILE);
	}
	//
	@Override
	public String getItemDataContentSeed() {
		return getString(KEY_ITEM_DATA_CONTENT_SEED);
	}
	//
	@Override
	public long getItemDataContentRingSize() {
		try {
			return SizeInBytes.toFixedSize(getString(KEY_ITEM_DATA_CONTENT_RING_SIZE));
		} catch(final ConversionException e) {
			return getLong(KEY_ITEM_DATA_CONTENT_RING_SIZE);
		}
	}
	//
	@Override
	public DataRangesConfig getItemDataRanges()
	throws DataRangesConfig.InvalidRangeException {
		try {
			return new DataRangesConfig(getInt(KEY_ITEM_DATA_RANGES));
		} catch(final ConversionException e) {
			return new DataRangesConfig(getString(KEY_ITEM_DATA_RANGES));
		}
	}
	//
	@Override
	public SizeInBytes getItemDataSize() {
		final Object raw = getProperty(KEY_ITEM_DATA_SIZE);
		if(raw instanceof Long) {
			return new SizeInBytes((long) raw);
		} else if(raw instanceof Integer) {
			return new SizeInBytes((int) raw);
		} else if(raw instanceof Short) {
			return new SizeInBytes((short) raw);
		} else if(raw instanceof String) {
			return new SizeInBytes((String) raw);
		} else if(raw instanceof SizeInBytes){
			return (SizeInBytes) raw;
		} else {
			throw new ConversionException(
				"Type of \"" + KEY_ITEM_DATA_SIZE + "\" is not supported: \"" + raw + "\""
			);
		}
	}
	//
	@Override
	public boolean getItemDataVerify() {
		return getBoolean(KEY_ITEM_DATA_VERIFY);
	}
	//
	@Override
	public String getItemDstContainer() {
		return getString(KEY_ITEM_DST_CONTAINER);
	}
	//
	@Override
	public String getItemDstFile() {
		return getString(KEY_ITEM_DST_FILE);
	}
	//
	@Override
	public String getItemSrcContainer() {
		return getString(KEY_ITEM_SRC_CONTAINER);
	}
	//
	@Override
	public String getItemSrcFile() {
		return getString(KEY_ITEM_SRC_FILE);
	}
	//
	@Override
	public int getItemSrcBatchSize() {
		return getInt(KEY_ITEM_SRC_BATCH_SIZE);
	}
	//
	@Override
	public ItemNamingType getItemNamingType() {
		return ItemNamingType.valueOf(
			getString(KEY_ITEM_NAMING_TYPE).toUpperCase()
		);
	}
	//
	@Override
	public String getItemNamingPrefix() {
		return getString(KEY_ITEM_NAMING_PREFIX);
	}
	//
	@Override
	public int getItemNamingRadix() {
		return getInt(KEY_ITEM_NAMING_RADIX);
	}
	//
	@Override
	public long getItemNamingOffset() {
		return getLong(KEY_ITEM_NAMING_OFFSET);
	}
	//
	@Override
	public int getItemNamingLength() {
		return getInt(KEY_ITEM_NAMING_LENGTH);
	}
	//
	@Override
	public int getItemQueueSizeLimit() {
		return getInt(KEY_ITEM_QUEUE_SIZE_LIMIT);
	}
	//
	@Override
	public boolean getLoadCircular() {
		return getBoolean(KEY_LOAD_CIRCULAR);
	}
	//
	@Override
	public long getLoadLimitCount() {
		return getLong(KEY_LOAD_LIMIT_COUNT);
	}
	//
	@Override
	public double getLoadLimitRate() {
		return getDouble(KEY_LOAD_LIMIT_RATE);
	}
	//
	@Override
	public long getLoadLimitSize() {
		final Object raw = getProperty(KEY_LOAD_LIMIT_SIZE);
		if(raw instanceof Long) {
			return ((long) raw);
		} else if(raw instanceof Integer) {
			return ((int) raw);
		} else if(raw instanceof Short) {
			return ((short) raw);
		} else if(raw instanceof String) {
			return SizeInBytes.toFixedSize((String) raw);
		} else if(raw instanceof SizeInBytes){
			return ((SizeInBytes) raw).get();
		} else {
			throw new ConversionException(
				"Type of \"" + KEY_LOAD_LIMIT_SIZE + "\" is not supported: \"" + raw + "\""
			);
		}
	}
	//
	@Override
	public long getLoadLimitTime() {
		final Object rawValue = getProperty(KEY_LOAD_LIMIT_TIME);
		if(rawValue instanceof String) {
			final String rawValueStr = (String) rawValue;
			return TimeUtil.getTimeUnit(rawValueStr).toSeconds(TimeUtil.getTimeValue(rawValueStr));
		} else if(rawValue instanceof Long) {
			return (Long) rawValue;
		} else if(rawValue instanceof Integer) {
			return (Integer) rawValue;
		} else if(rawValue instanceof Short) {
			return (Short) rawValue;
		} else {
			throw new ConversionException(
				"Invalid value @" + KEY_LOAD_LIMIT_TIME + ": \"" + rawValue + "\""
			);
		}
	}
	//
	@Override
	public boolean getLoadMetricsIntermediate() {
		return getBoolean(KEY_LOAD_METRICS_INTERMEDIATE);
	}
	//
	@Override
	public int getLoadMetricsPeriod() {
		final Object rawValue = getProperty(KEY_LOAD_METRICS_PERIOD);
		if(rawValue instanceof String) {
			final String rawStrValue = (String) rawValue;
			return (int) TimeUtil.getTimeUnit(rawStrValue)
				.toSeconds(TimeUtil.getTimeValue(rawStrValue));
		} else if(rawValue instanceof Long) {
			return ((Long) rawValue).intValue();
		} else if(rawValue instanceof Integer) {
			return (Integer) rawValue;
		} else if(rawValue instanceof Short) {
			return (Short) rawValue;
		} else {
			throw new ConversionException(
				"Invalid value @ " + KEY_LOAD_METRICS_PERIOD + ": \"" +
				(rawValue == null ? null : rawValue.getClass()) + "\""
			);
		}
	}
	//
	@Override
	public boolean getLoadMetricsPrecondition() {
		return getBoolean(KEY_LOAD_METRICS_PRECONDITION);
	}
	//
	@Override
	public String[] getLoadServerAddrs() {
		return getStringArray(KEY_LOAD_SERVER_ADDRS);
	}
	//
	@Override
	public boolean getLoadServerNodeMapping() {
		return getBoolean(KEY_LOAD_SERVER_NODE_MAPPING);
	}
	//
	@Override
	public int getLoadThreads() {
		return getInt(KEY_LOAD_THREADS);
	}
	//
	@Override
	public LoadType getLoadType() {
		final Object t = getProperty(KEY_LOAD_TYPE);
		if(t instanceof String) {
			return LoadType.valueOf(((String) t).toUpperCase());
		} else if(t instanceof List) {
			return LoadType.MIXED;
		} else {
			throw new IllegalArgumentException();
		}
	}
	//
	@Override
	public boolean getNetworkServeJmx() {
		return getBoolean(KEY_NETWORK_SERVE_JMX);
	}
	//
	@Override
	public int getNetworkSocketTimeoutMilliSec() {
		return getInt(KEY_NETWORK_SOCKET_TIMEOUT_MILLISEC);
	}
	//
	@Override
	public boolean getNetworkSocketReuseAddr() {
		return getBoolean(KEY_NETWORK_SOCKET_REUSE_ADDR);
	}
	//
	@Override
	public boolean getNetworkSocketKeepAlive() {
		return getBoolean(KEY_NETWORK_SOCKET_KEEP_ALIVE);
	}
	//
	@Override
	public boolean getNetworkSocketTcpNoDelay() {
		return getBoolean(KEY_NETWORK_SOCKET_TCP_NO_DELAY);
	}
	//
	@Override
	public int getNetworkSocketLinger() {
		return getInt(KEY_NETWORK_SOCKET_LINGER);
	}
	//
	@Override
	public int getNetworkSocketBindBacklogSize() {
		return getInt(KEY_NETWORK_SOCKET_BIND_BACKLOG_SIZe);
	}
	//
	@Override
	public boolean getNetworkSocketInterestOpQueued() {
		return getBoolean(KEY_NETWORK_SOCKET_INTEREST_OP_QUEUED);
	}
	//
	@Override
	public int getNetworkSocketSelectInterval() {
		return getInt(KEY_NETWORK_SOCKET_SELECT_INTERVAL);
	}
	//
	@Override
	public boolean getNetworkSsl() {
		return getBoolean(KEY_NETWORK_SSL);
	}
	//
	@Override
	public String getRunId() {
		return getString(KEY_RUN_ID);
	}
	//
	@Override
	public String getRunMode() {
		return getString(KEY_RUN_MODE);
	}
	//
	@Override
	public String getRunName() {
		return getString(KEY_RUN_NAME);
	}
	//
	@Override
	public String getRunVersion() {
		return getString(KEY_RUN_VERSION);
	}
	//
	@Override
	public String getRunFile() {
		return getString(KEY_RUN_FILE);
	}
	//
	@Override
	public boolean getRunResumeEnabled() {
		return getBoolean(KEY_RUN_RESUME_ENABLED);
	}
	//
	@Override
	public StorageType getStorageType() {
		return StorageType.valueOf(getString(KEY_STORAGE_TYPE).toUpperCase());
	}
	//
	@Override
	public String[] getStorageAddrs() {
		return getStringArray(KEY_STORAGE_ADDRS);
	}
	//
	@Override
	public String[] getStorageAddrsWithPorts() {
		final String
			nodeAddrs[] = getStorageAddrs(),
			nodeAddrsWithPorts[] = new String[nodeAddrs.length];
		String nodeAddr;
		int port = getStoragePort();
		for(int i = 0; i < nodeAddrs.length; i ++) {
			nodeAddr = nodeAddrs[i];
			nodeAddrsWithPorts[i] = nodeAddr + (nodeAddr.contains(":") ? "" : ":" + port);
		}
		return nodeAddrsWithPorts;
	}
	//
	@Override
	public String getStorageHttpApi() {
		return getString(KEY_STORAGE_HTTP_API);
	}
	//
	@Override
	public int getStoragePort() {
		return getInt(KEY_STORAGE_PORT);
	}
	//
	@Override
	public boolean getStorageHttpFsAccess() {
		return getBoolean(KEY_STORAGE_HTTP_FS_ACCESS);
	}
	//
	@Override
	public Configuration getStorageHttpHeaders() {
		return subset(KEY_STORAGE_HTTP_HEADERS);
	}
	//
	@Override
	public String getStorageHttpNamespace() {
		return getString(KEY_STORAGE_HTTP_NAMESPACE);
	}
	//
	@Override
	public boolean getStorageHttpVersioning() {
		return getBoolean(KEY_STORAGE_HTTP_VERSIONING);
	}
	//
	@Override
	public int getStorageMockHeadCount() {
		return getInt(KEY_STORAGE_MOCK_HEAD_COUNT);
	}
	//
	@Override
	public int getStorageMockCapacity() {
		return getInt(KEY_STORAGE_MOCK_CAPACITY);
	}
	//
	@Override
	public int getStorageMockContainerCapacity() {
		return getInt(KEY_STORAGE_MOCK_CONTAINER_CAPACITY);
	}
	//
	@Override
	public int getStorageMockContainerCountLimit() {
		return getInt(KEY_STORAGE_MOCK_CONTAINER_COUNT_LIMIT);
	}
	//
	@Override
	public void setRunId(final String runId) {
		setProperty(KEY_RUN_ID, runId);
		ThreadContext.put(KEY_RUN_ID, getRunId());
	}
	//
	@Override
	public void setRunMode(final String runMode) {
		setProperty(KEY_RUN_MODE, runMode);
		ThreadContext.put(KEY_RUN_MODE, getRunMode());
	}
	//
	@Override
	public void override(final String configBranch, final Map<String, ?> configTree) {
		Object v;
		String compositeKey;
		for(final String k : configTree.keySet()) {
			v = configTree.get(k);
			compositeKey = configBranch == null ?
				k :
				configBranch + DefaultExpressionEngine.DEFAULT_PROPERTY_DELIMITER + k;
			if(v instanceof Map) {
				override(compositeKey, (Map<String, ?>) v);
			} else if(v instanceof List) {
				setProperty(compositeKey, null);
				addPropertyDirect(compositeKey, v);
			} else {
				setProperty(compositeKey, v);
			}
		}
	}
	//
	@Override
	public void findAndSubstitute(final String replacePattern, final Object newValue) {
		final Iterator<String> keyIter = getKeys();
		Object oldValue;
		String key;
		while(keyIter.hasNext()) {
			key = keyIter.next();
			oldValue = getProperty(key);
			if(newValue == null) {
				findAndSubstituteWithNull(key, oldValue, replacePattern);
			} else if(newValue instanceof List) {
				findAndSubstituteWithList(key, oldValue, replacePattern, (List) newValue);
			} else {
				findAndSubstituteWith(key, oldValue, replacePattern, newValue);
			}
		}
	}
	//
	private void findAndSubstituteWithNull(
		final String key, final Object oldValue, final String pattern
	) {
		final Logger log = LogManager.getLogger();
		if(oldValue instanceof String) {
			if(oldValue.equals(pattern)) {
				setProperty(key, null);
			} else {
				log.warn(Markers.ERR, "Couldn't replace with null value(s) the string part");
			}
		} else if(oldValue instanceof List) {
			final List<Object> newValue = new ArrayList<>();
			for(final Object oldValueElement : (List) oldValue) {
				if(oldValueElement.equals(pattern)) {
					newValue.add(null);
				} else {
					log.warn(Markers.ERR, "Couldn't replace with null value(s) the string part");
					newValue.add(oldValueElement);
				}
			}
			clearProperty(key);
			setProperty(key, newValue);
		}
	}
	//
	private <T> void findAndSubstituteWith(
		final String key, final Object oldValue, final String pattern, final T newValue
	) {
		String t;
		if(oldValue instanceof String) {
			if(oldValue.equals(pattern)) {
				setProperty(key, newValue);
			} else {
				t = (String) oldValue;
				if(t.contains(pattern)) {
					setProperty(key, t.replace(pattern, newValue.toString()));
				}
			}
		} else if(oldValue instanceof List) {
			final List<Object> newValueList = new ArrayList<>();
			for(final Object oldValueElement : (List) oldValue) {
				if(oldValueElement instanceof String) {
					if(oldValueElement.equals(pattern)) {
						newValueList.add(newValue);
					} else {
						t = (String)oldValueElement;
						if(t.contains(pattern)) {
							newValueList.add(t.replace(pattern, newValue.toString()));
						} else {
							newValueList.add(oldValueElement);
						}
					}
				} else {
					newValueList.add(oldValueElement);
				}
			}
			clearProperty(key);
			setProperty(key, newValueList);
		}
	}
	//
	private void findAndSubstituteWithList(
		final String key, final Object oldValue, final String pattern, final List newValue
	) {
		final Logger log = LogManager.getLogger();
		if(oldValue instanceof String) {
			if(oldValue.equals(pattern)) {
				setProperty(key, newValue);
			} else {
				log.warn(Markers.ERR, "Couldn't replace with list value(s) the string part");
			}
		} else if(oldValue instanceof List) {
			final List<Object> newValueList = new ArrayList<>();
			for(final Object oldValueElement : (List) oldValue) {
				if(oldValueElement instanceof String) {
					if(oldValueElement.equals(pattern)) {
						newValueList.add(newValue);
					} else {
						log.warn(
							Markers.ERR, "Couldn't replace with list value(s) the string part"
						);
						newValueList.add(oldValueElement);
					}
				} else {
					newValueList.add(oldValueElement);
				}
			}
			clearProperty(key);
			setProperty(key, newValueList);
		}
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	// Serialization and formatting section
	////////////////////////////////////////////////////////////////////////////////////////////////
	@Override
	public ObjectNode toJsonTree(final ObjectMapper mapper) {
		final ObjectNode rootNode = mapper.createObjectNode();
		final ObjectNode configNode = mapper.createObjectNode();
		rootNode.set(CONFIG_ROOT, configNode);
		//
		int i;
		Object value;
		String compositeKey, keyParts[];
		ObjectNode currNode, parentNode;
		JsonNode n;
		for(final Iterator<String> keyIter = super.getKeys(); keyIter.hasNext(); ) {
			compositeKey = keyIter.next();
			keyParts = compositeKey.split("\\.");
			currNode = configNode;
			for(i = 0; i < keyParts.length; i ++) {
				parentNode = currNode;
				n = currNode.get(keyParts[i]);
				if(n instanceof ObjectNode ){
					currNode = (ObjectNode) n;
				} else if(n == null) {
					if(i == keyParts.length - 1) {
						value = getProperty(compositeKey);
						if(value == null) {
							parentNode.putNull(keyParts[i]);
						} else if(value instanceof Long) {
							parentNode.put(keyParts[i], (Long) value);
						} else if(value instanceof Integer) {
							parentNode.put(keyParts[i], (Integer) value);
						} else if(value instanceof Short) {
							parentNode.put(keyParts[i], (Short) value);
						} else if(value instanceof Boolean) {
							parentNode.put(keyParts[i], (Boolean) value);
						} else if(value instanceof Double) {
							parentNode.put(keyParts[i], (Double) value);
						} else if(value instanceof Float) {
							parentNode.put(keyParts[i], (Float) value);
						} else if(value instanceof String) {
							parentNode.put(keyParts[i], (String) value);
						} else if(value instanceof List) {
							// TODO something w/ this ugly code
							final ArrayNode arrayNode = parentNode.putArray(keyParts[i]);
							for(final Object listValue : (List) value) {
								if(listValue == null) {
									arrayNode.addNull();
								} else if(listValue instanceof Long) {
									arrayNode.add((Long) listValue);
								} else if(listValue instanceof Integer) {
									arrayNode.add((Integer) listValue);
								} else if(listValue instanceof Short) {
									arrayNode.add((Short) listValue);
								} else if(listValue instanceof Boolean) {
									arrayNode.add((Boolean) listValue);
								} else if(listValue instanceof Double) {
									arrayNode.add((Double) listValue);
								} else if(listValue instanceof Float) {
									arrayNode.add((Float) listValue);
								} else if(listValue instanceof String) {
									arrayNode.add((String) listValue);
								} else {
									throw new IllegalStateException(
										"Invalud configuration value type: " +
										(listValue == null ? null : listValue.getClass())
									);
								}
							}
						} else {
							throw new IllegalStateException(
								"Invalud configuration value type: " +
								(value == null ? null : value.getClass())
							);
						}
						currNode = parentNode;
					} else {
						currNode = mapper.createObjectNode();
						parentNode.set(keyParts[i], currNode);
					}
				}
			}
		}
		//
		return rootNode;
	}
	//
	private final static String
		TABLE_BORDER = "\n+--------------------------------+----------------------------------------------------------------+",
		TABLE_HEADER = "Configuration parameters:";
	@Override
	public String toString() {
		String nextKey;
		Object nextVal;
		final StrBuilder strBuilder = new StrBuilder()
			.append(TABLE_HEADER).append(TABLE_BORDER)
			.appendNewLine().append("| ").appendFixedWidthPadRight("Key", 31, ' ')
			.append("| ").appendFixedWidthPadRight("Value", 63, ' ').append('|')
			.append(TABLE_BORDER);
		for(
			final Iterator<String> keyIterator = getKeys();
			keyIterator.hasNext();
		) {
			nextKey = keyIterator.next();
			nextVal = getProperty(nextKey);
			switch(nextKey) {
				case KEY_ITEM_TYPE:
				case KEY_ITEM_DST_CONTAINER:
				case KEY_LOAD_TYPE:
				case KEY_LOAD_THREADS:
				case KEY_LOAD_LIMIT_COUNT:
				case KEY_LOAD_LIMIT_TIME:
				case KEY_RUN_ID:
				case KEY_RUN_MODE:
				case KEY_RUN_VERSION:
				case KEY_STORAGE_TYPE:
					strBuilder
						.appendNewLine().append("| ")
						.appendFixedWidthPadRight(nextKey, 31, ' ')
						.append("| ")
						.appendFixedWidthPadRight(nextVal, 63, ' ')
						.append('|');
					break;
			}
		}
		return strBuilder.append(TABLE_BORDER).toString();
	}
	//
	@Override
	public String toFormattedString() {
		final Logger log = LogManager.getLogger();
		final ObjectMapper
			mapper = new ObjectMapper().configure(SerializationFeature.INDENT_OUTPUT, true);
		try {
			return mapper.writeValueAsString(toJsonTree(mapper));
		} catch(final Exception e) {
			LogUtil.exception(log, Level.WARN, e, "Failed to convert the configuration to JSON");
		}
		return null;
	}
	//
	@Override
	public void writeExternal(final ObjectOutput out)
	throws IOException {
		final byte jsonData[] = toFormattedString().getBytes();
		out.writeInt(jsonData.length);
		out.write(jsonData);
	}
	//
	@Override
	public void readExternal(final ObjectInput in)
	throws IOException, ClassNotFoundException {
		final int l = in.readInt();
		final byte jsonData[] = new byte[l];
		in.readFully(jsonData);
		new JsonConfigLoader(this).loadPropsFromJsonByteArray(jsonData);
		THREAD_CONTEXT.set(this); // required for proper IoConfig (and its derivatives) instancing
	}
	////////////////////////////////////////////////////////////////////////////////////////////////
	// Load from the external sources
	////////////////////////////////////////////////////////////////////////////////////////////////
	private void loadFromJson(final Path filePath) {
		new JsonConfigLoader(this).loadPropsFromJsonCfgFile(filePath);
		applyAliasing();
	}
	//
	private void loadFromJson(final String string) {
		new JsonConfigLoader(this).loadPropsFromJsonString(string);
		applyAliasing();
	}
	//
	public void loadFromJson(final byte[] jsonBytes) {
		new JsonConfigLoader(this).loadPropsFromJsonByteArray(jsonBytes);
		applyAliasing();
	}
	//
	@SuppressWarnings("unchecked")
	private void applyAliasing() {
		final Logger log = LogManager.getLogger();
		final String prefixKeyAliasingWithDot = PREFIX_KEY_ALIASING + ".";
		log.debug(Markers.MSG, "Going to override the aliasing section");
		String keyAlias, deprecatedKey, overriderKeys[];
		Object overriderKeysRaw;
		for(final Iterator<String> keyIter = getKeys(PREFIX_KEY_ALIASING); keyIter.hasNext();) {
			keyAlias = keyIter.next();
			deprecatedKey = keyAlias.replaceAll(prefixKeyAliasingWithDot, "");
			overriderKeysRaw = getProperty(keyAlias);
			log.trace(
				Markers.MSG, "Alias: \"{}\" -> \"{}\"", deprecatedKey, overriderKeysRaw
			);
			if(overriderKeysRaw instanceof List) {
				overriderKeys = (String[]) ((List<String>) overriderKeysRaw).toArray();
			} else if(overriderKeysRaw instanceof String) {
				overriderKeys = new String[1];
				overriderKeys[0] = (String) overriderKeysRaw;
			} else if(overriderKeysRaw instanceof Boolean){
				overriderKeys = new String[0];
			} else {
				log.warn(Markers.ERR, "Invalid aliasing key value: {}", overriderKeysRaw);
				overriderKeys = null;
			}
			MAP_OVERRIDE.put(deprecatedKey, overriderKeys);
		}
	}
	//
	private void loadFromEnv() {
		final Logger log = LogManager.getLogger();
		final SystemConfiguration sysProps = new SystemConfiguration();
		final String envRunId = sysProps.getString(KEY_RUN_ID);
		if(envRunId != null && !envRunId.isEmpty()) {
			setRunId(envRunId);
		}
		final String envRunMode = sysProps.getString(KEY_RUN_MODE);
		if(envRunMode != null && !envRunMode.isEmpty()) {
			setRunMode(envRunMode);
		}
		//
		String key, overriderKeys[];
		Object sharedValue;
		for(final Iterator<String> keyIter = sysProps.getKeys(); keyIter.hasNext();) {
			key = keyIter.next();
			log.trace(
				Markers.MSG, "System property: \"{}\": \"{}\" -> \"{}\"",
				key, getProperty(key), sysProps.getProperty(key)
			);
			overriderKeys = MAP_OVERRIDE.get(key);
			sharedValue = sysProps.getProperty(key);
			setProperty(key, sharedValue);
			if(overriderKeys != null) {
				if(overriderKeys.length == 0) {
					log.error(
						Markers.ERR,
						"Option \"{}\" is deprecated and can't be mapped to any current option, " +
						"please refer to the documentation or contact via Mongoose.Support@emc.com",
						key
					);
				} else {
					log.warn(
						Markers.ERR, "Option \"{}\" is deprecated, use {} instead", key,
						Arrays.toString(overriderKeys)
					);
					for(final String key2override : overriderKeys) {
						setProperty(key2override, sharedValue);
					}
				}
			}
		}
	}
	//
}
