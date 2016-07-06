package com.emc.mongoose.run.scenario.engine;
//
import com.emc.mongoose.common.conf.enums.LoadType;

import com.emc.mongoose.common.conf.enums.ItemType;
import com.emc.mongoose.common.conf.enums.StorageType;
import static com.emc.mongoose.common.conf.AppConfig.KEY_ITEM_DATA_SIZE;
import static com.emc.mongoose.common.conf.AppConfig.KEY_LOAD_METRICS_PERIOD;
import static com.emc.mongoose.common.conf.AppConfig.KEY_LOAD_THREADS;
import static com.emc.mongoose.common.conf.AppConfig.KEY_LOAD_TYPE;
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.SizeInBytes;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
//
import com.emc.mongoose.core.api.item.base.Item;
import com.emc.mongoose.common.io.Output;
import com.emc.mongoose.core.api.item.data.ContentSource;
import com.emc.mongoose.core.api.load.builder.DataLoadBuilder;
import com.emc.mongoose.core.api.load.builder.LoadBuilder;
//
import com.emc.mongoose.core.api.load.executor.LoadExecutor;
import com.emc.mongoose.core.api.load.model.LoadState;
import com.emc.mongoose.core.api.load.model.metrics.IoStats;
import com.emc.mongoose.core.impl.item.ItemTypeUtil;
import com.emc.mongoose.core.impl.item.base.CsvFileItemOutput;
import com.emc.mongoose.core.impl.item.data.ContentSourceUtil;
import com.emc.mongoose.util.builder.LoadBuilderFactory;
//
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.text.StrBuilder;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.rmi.RemoteException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
/**
 Created by kurila on 17.03.16.
 */
public class RampupJob
extends SequentialJob {
	//
	private final static Logger LOG = LogManager.getLogger();
	private final static int tblWidth = 124;
	private final static char colSep = '|';
	private final static char rowSep = '-';
	private final static char padChar = ' ';
	private final static int DEFAULT_THREAD_COUNT = 1;
	private final static String DEFAULT_SIZE = "1MB";
	private final static String DEFAULT_LOAD_TYPE = LoadType.CREATE.name().toLowerCase();
	//
	private final StrBuilder strb = new StrBuilder();
	{
		strb
			.appendNewLine()
			.appendPadding(tblWidth, rowSep)
			.appendNewLine()
			.appendFixedWidthPadLeft("Job # " + colSep, 7, padChar)
			.appendFixedWidthPadLeft("Load type " + colSep, 12, padChar)
			.appendFixedWidthPadLeft("Thread count " + colSep, 15, padChar)
			.appendFixedWidthPadLeft("Data size " + colSep, 12, padChar)
			.appendFixedWidthPadLeft("Count " + colSep, 12, padChar)
			.appendFixedWidthPadLeft("Failed " + colSep, 9, padChar)
			.appendFixedWidthPadLeft("Duration[us] " + colSep, 15, padChar)
			.appendFixedWidthPadLeft("Latency[us] " + colSep, 14, padChar)
			.appendFixedWidthPadLeft("TP[op/s] " + colSep, 14, padChar)
			.appendFixedWidthPadLeft("BW[MB/s] " + colSep, 14, padChar)
			.appendNewLine()
			.appendPadding(tblWidth, rowSep);
	}
	//
	private final LoadBuilder loadJobBuilder;
	private final Class<? extends Item> itemCls;
	private final ContentSource contentSrc;
	private final long limitTime;
	//
	public RampupJob(final AppConfig appConfig, final Map<String, Object> subTree)
	throws IllegalStateException {
		super(appConfig, subTree);
		// disable periodic/intermediate metrics logging
		localConfig.setProperty(KEY_LOAD_METRICS_PERIOD, 0);
		// save the default values being replaced with rampup list values
		limitTime = localConfig.getLoadLimitTime();
		final ItemType itemType = localConfig.getItemType();
		final StorageType storageType = localConfig.getStorageType();
		itemCls = ItemTypeUtil.getItemClass(itemType, storageType);
		try {
			contentSrc = ContentSourceUtil.getInstance(localConfig);
		} catch(final IOException e) {
			throw new IllegalStateException("Failed to init the content source", e);
		}
		// get the lists of the rampup parameters
		final List rawThreadCounts = (List) localConfig.getProperty(KEY_LOAD_THREADS);
		final List rawSizes = (List) localConfig.getProperty(KEY_ITEM_DATA_SIZE);
		final List rawLoadTypes = (List) localConfig.getProperty(KEY_LOAD_TYPE);
		// return the default values replaced with the list values back
		localConfig.setProperty(KEY_LOAD_THREADS, DEFAULT_THREAD_COUNT);
		localConfig.setProperty(KEY_ITEM_DATA_SIZE, DEFAULT_SIZE);
		localConfig.setProperty(KEY_LOAD_TYPE, DEFAULT_LOAD_TYPE);
		//
		try {
			loadJobBuilder = LoadBuilderFactory.getInstance(localConfig);
		} catch(final ClassNotFoundException | NoSuchMethodException | InstantiationException | IllegalAccessException e) {
			throw new IllegalStateException("Failed to init the rampup job", e);
		} catch(final InvocationTargetException e) {
			throw new IllegalStateException("Failed to init the rampup job", e.getTargetException());
		} catch(final Throwable t) {
			t.printStackTrace(System.out);
			throw t;
		}
		//
		initForEach(rawThreadCounts, rawSizes, rawLoadTypes);
	}
	//
	private void initForEach(
		final List rawThreadCounts, final List rawSizes, final List rawLoadTypes
	) {
		int nextThreadCount;
		for(final Object rawThreadCount : rawThreadCounts) {
			//
			nextThreadCount = 0;
			if(rawThreadCount instanceof Integer) {
				nextThreadCount = (Integer) rawThreadCount;
			} else if(rawThreadCount instanceof String) {
				try {
					nextThreadCount = Integer.parseInt((String) rawThreadCount);
				} catch(final NumberFormatException e) {
					LogUtil.exception(
						LOG, Level.WARN, e, "Failed to parse the number \"{}\"", rawThreadCount
					);
				}
			} else {
				LOG.warn(Markers.ERR, "Invalid thread count type: \"{}\"", rawThreadCount);
			}
			if(nextThreadCount <= 0) {
				LOG.warn(
					Markers.ERR, "Invalid thread count value \"{}\", skipping", nextThreadCount
				);
				continue;
			}
			//
			initForThreadCount(nextThreadCount, rawSizes, rawLoadTypes);
		}
	}
	//
	private void initForThreadCount(
		final int nextThreadCount, final List rawSizes, final List rawLoadTypes
	) {
		if(rawSizes == null || rawSizes.size() == 0) {
			initForThreadCountAndSize(nextThreadCount, null, rawLoadTypes);
		} else {
			SizeInBytes nextSize;
			for(final Object rawSize : rawSizes) {
				nextSize = null;
				if(rawSize instanceof Integer) {
					final int s = (Integer) rawSize;
					nextSize = new SizeInBytes(s, s, 0);
				} else if(rawSize instanceof Long) {
					final long s = (Long) rawSize;
					nextSize = new SizeInBytes(s, s, 0);
				} else if(rawSize instanceof String) {
					try {
						nextSize = new SizeInBytes((String) rawSize);
					} catch(final IllegalArgumentException e) {
						LogUtil.exception(
							LOG, Level.WARN, e, "Failed to parse the size \"{}\"", rawSize
						);
					}
				} else {
					LOG.warn(Markers.ERR, "Invalid size type: \"{}\"", rawSize);
				}
				if(nextSize == null) {
					LOG.warn(Markers.ERR, "Invalid size value \"{}\", skipping the step", rawSize);
					continue;
				}
				//
				initForThreadCountAndSize(nextThreadCount, nextSize, rawLoadTypes);
			}
		}
	}
	//
	private void initForThreadCountAndSize(
		final int nextThreadCount, final SizeInBytes nextSize, final List rawLoadTypes
	) {
		LoadType nextLoadType;
		Output itemOutput = null;
		for(final Object rawLoadType : rawLoadTypes) {
			nextLoadType = null;
			if(rawLoadType instanceof LoadType) {
				nextLoadType = (LoadType) rawLoadType;
			} else if(rawLoadType instanceof String) {
				try {
					nextLoadType = LoadType.valueOf(((String) rawLoadType).toUpperCase());
				} catch(final IllegalArgumentException e) {
					LogUtil.exception(
						LOG, Level.WARN, e, "Failed to parse the load type \"{}\"",
						rawLoadType
					);
				}
			} else {
				LOG.warn(Markers.ERR, "Invalid load type: \"{}\"", rawLoadType);
			}
			if(nextLoadType == null) {
				LOG.warn(
					Markers.ERR, "Invalid load type \"{}\", skipping the step", rawLoadType
				);
				continue;
			}
			//
			try {
				itemOutput = append(itemOutput, nextThreadCount, nextSize, nextLoadType);
			} catch(final IOException e) {
				LogUtil.exception(
					LOG, Level.ERROR, e, "Failed to build the load job \"{} x {} x {}\"",
					nextLoadType.name(), nextThreadCount, nextSize.toString()
				);
			}
		}
	}
	//
	private final Map<Integer, Map<Integer, Map<SizeInBytes, Map<LoadType, LoadExecutor>>>>
		loadJobMap = new TreeMap<>();
	//
	private Output append(
		final Output prevItemOutput, final int nextThreadCount, final SizeInBytes nextSize,
		final LoadType nextLoadType
	) throws IOException {
		//
		final LoadExecutor nextLoadJob;
		final Output nextItemOutput = new CsvFileItemOutput<>(itemCls, contentSrc);
		//
		loadJobBuilder
			.setLoadType(nextLoadType)
			.setThreadCount(nextThreadCount)
			.setInput(prevItemOutput == null ? null : prevItemOutput.getInput())
			.setOutput(nextItemOutput);
		if(loadJobBuilder instanceof DataLoadBuilder && nextSize != null) {
			((DataLoadBuilder) loadJobBuilder).setDataSize(nextSize);
		}
		nextLoadJob = loadJobBuilder.build();
		if(nextLoadJob != null) {
			final int nextLoadNum = nextLoadJob.getLoadState().getLoadNumber();
			Map<Integer, Map<SizeInBytes, Map<LoadType, LoadExecutor>>> m1 = loadJobMap.get(
				nextLoadJob.getLoadState().getLoadNumber()
			);
			if(m1 == null) {
				loadJobMap.put(nextLoadNum, m1 = new HashMap<>());
			}
			Map<SizeInBytes, Map<LoadType, LoadExecutor>> m2 = m1.get(nextThreadCount);
			if(m2 == null) {
				m1.put(nextThreadCount, m2 = new HashMap<>());
			}
			Map<LoadType, LoadExecutor> m3 = m2.get(nextSize);
			if(m3 == null) {
				m2.put(nextSize, m3 = new HashMap<>());
			}
			m3.put(nextLoadType, nextLoadJob);
			//
			//append(new SingleJobContainer(nextLoadJob, limitTime));
		}
		return nextItemOutput;
	}
	//
	@Override
	public final void run() {
		super.run();
		LoadExecutor nextLoadJob;
		LoadState nextLoadState;
		Map<Integer, Map<SizeInBytes, Map<LoadType, LoadExecutor>>> m0;
		Map<SizeInBytes, Map<LoadType, LoadExecutor>> m1;
		Map<LoadType, LoadExecutor> m2;
		for(final int nextLoadNum : loadJobMap.keySet()) {
			m0 = loadJobMap.get(nextLoadNum);
			for(final int nextThreadCount : m0.keySet()) {
				m1 = m0.get(nextThreadCount);
				for(final SizeInBytes nextSize : m1.keySet()) {
					m2 = m1.get(nextSize);
					for(final LoadType nextLoadType : m2.keySet()) {
						nextLoadJob = m2.get(nextLoadType);
						try {
							nextLoadState = nextLoadJob.getLoadState();
						} catch(final RemoteException e) {
							LogUtil.exception(LOG, Level.WARN, e,
								"Failed to fetch the load state for {}", nextLoadJob
							);
							nextLoadState = null;
						}
						final String sizeStr = nextSize == null ? " " : nextSize.toString();
						final IoStats.Snapshot statsSnapshot =
							nextLoadState == null ? null : nextLoadState.getStatsSnapshot();
						final long countSucc = statsSnapshot == null ?
							0 : statsSnapshot.getSuccCount();
						final long countFail = statsSnapshot == null ?
							0 : statsSnapshot.getFailCount();
						long avgDur = statsSnapshot == null ?
							0 : statsSnapshot.getDurationSum();
						avgDur = avgDur == 0 ? 0 : avgDur / countSucc;
						long avgLat = 0;
						if(statsSnapshot != null) {
							final long[] latValues = statsSnapshot.getLatencyValues();
							for(final long nextLatencyValue : latValues) {
								avgLat += nextLatencyValue;
							}
							avgLat = avgLat == 0 ? 0 : avgLat / latValues.length;
						}
						final String tpStr = statsSnapshot == null ?
							"N/A" :
							String.format(
								LogUtil.LOCALE_DEFAULT, "%.3f", statsSnapshot.getSuccRateMean()
							);
						final String bwStr = statsSnapshot == null ?
							"N/A" :
							String.format(LogUtil.LOCALE_DEFAULT, "%.3f",
								statsSnapshot.getByteRateMean() / IoStats.MIB
							);
						strb
							.appendNewLine()
							.appendFixedWidthPadLeft(nextLoadNum + " " + colSep, 7, padChar)
							.appendFixedWidthPadRight(
								" " + StringUtils.capitalize(nextLoadType.name().toLowerCase()),
								11, padChar
							)
							.append(colSep)
							.appendFixedWidthPadLeft(
								nextThreadCount + " " + colSep, 15, padChar
							)
							.appendFixedWidthPadLeft(sizeStr + " " + colSep, 12, padChar)
							.appendFixedWidthPadLeft(countSucc + " " + colSep, 12, padChar)
							.appendFixedWidthPadLeft(countFail + " " + colSep, 9, padChar)
							.appendFixedWidthPadRight(" " + avgDur, 14, padChar)
							.appendFixedWidthPadRight(colSep + " " + avgLat, 14, padChar)
							.appendFixedWidthPadRight(colSep + " " + tpStr, 14, padChar)
							.appendFixedWidthPadRight(colSep + " " + bwStr, 14, padChar)
							.append(colSep);
					}
				}
			}
		}
		strb.appendNewLine().appendPadding(tblWidth, rowSep);
		LOG.info(Markers.MSG, strb.toString());
	}
	//
	@Override
	public final void close()
	throws IOException {
		super.close();
		loadJobMap.clear();
		try {
			loadJobBuilder.close();
		} catch(final IOException e) {
			LogUtil.exception(LOG, Level.WARN, e, "Failed to close the load job builder");
		}
		try {
			contentSrc.close();
		} catch(final IOException e) {
			LogUtil.exception(LOG, Level.WARN, e, "Failed to close the content source");
		}
	}
}
