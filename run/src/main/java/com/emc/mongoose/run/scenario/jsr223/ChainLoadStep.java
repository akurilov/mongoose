package com.emc.mongoose.run.scenario.jsr223;

import com.emc.mongoose.api.common.exception.UserShootHisFootException;
import com.emc.mongoose.api.model.data.DataInput;
import com.emc.mongoose.api.model.item.DelayedTransferConvertBuffer;
import com.emc.mongoose.api.model.item.ItemFactory;
import com.emc.mongoose.api.model.item.ItemInfoFileOutput;
import com.emc.mongoose.api.model.item.ItemType;
import com.emc.mongoose.api.model.item.TransferConvertBuffer;
import com.emc.mongoose.api.model.load.LoadController;
import com.emc.mongoose.api.model.load.LoadGenerator;
import com.emc.mongoose.api.model.storage.StorageDriver;
import com.emc.mongoose.load.controller.BasicLoadController;
import com.emc.mongoose.load.generator.BasicLoadGeneratorBuilder;
import com.emc.mongoose.run.scenario.ScenarioParseException;
import com.emc.mongoose.run.scenario.util.StorageDriverUtil;
import com.emc.mongoose.ui.config.Config;
import com.emc.mongoose.ui.config.item.ItemConfig;
import com.emc.mongoose.ui.config.item.data.DataConfig;
import com.emc.mongoose.ui.config.item.data.input.InputConfig;
import com.emc.mongoose.ui.config.item.data.input.layer.LayerConfig;
import com.emc.mongoose.ui.config.load.LoadConfig;
import com.emc.mongoose.ui.config.output.OutputConfig;
import com.emc.mongoose.ui.config.output.metrics.MetricsConfig;
import com.emc.mongoose.ui.config.storage.StorageConfig;
import com.emc.mongoose.ui.config.storage.driver.queue.QueueConfig;
import com.emc.mongoose.ui.config.test.step.StepConfig;
import com.emc.mongoose.ui.config.test.step.limit.LimitConfig;
import com.emc.mongoose.ui.log.LogUtil;
import com.emc.mongoose.ui.log.Loggers;

import com.github.akurilov.commons.io.Output;
import com.github.akurilov.commons.system.SizeInBytes;

import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 Created by andrey on 16.09.17.
 */
public class ChainLoadStep
extends StepBase {

	private final String autoStepId;
	private final List<LoadController> loadChain;

	public ChainLoadStep(final Config baseConfig) {
		this(baseConfig, null);
	}

	@SuppressWarnings("unchecked")
	protected ChainLoadStep(final Config baseConfig, final Map<String, Object> stepConfig) {
		super(baseConfig, stepConfig);
		autoStepId = getTypeName() + "_" + LogUtil.getDateTimeStamp() + "_" + hashCode();
		loadChain = stepConfig == null ? null : new ArrayList<>(stepConfig.size());
	}

	@Override @SuppressWarnings("unchecked")
	protected Config init()
	throws ScenarioParseException {
		final Config actualConfig = new Config(baseConfig);
		actualConfig.apply(Collections.emptyMap(), autoStepId);
		return actualConfig;
	}

	@Override @SuppressWarnings("unchecked")
	protected void invoke(final Config actualConfig)
	throws Throwable {

		if(loadChain == null) {
			return;
		}

		StepConfig testStepConfig = actualConfig.getTestConfig().getStepConfig();
		final String testStepName = testStepConfig.getId();
		Loggers.MSG.info("Run the chain load step \"{}\"", testStepName);
		final LimitConfig commonLimitConfig = testStepConfig.getLimitConfig();

		final long t = commonLimitConfig.getTime();
		final long timeLimitSec = t > 0 ? t : Long.MAX_VALUE;

		try {

			TransferConvertBuffer nextItemBuff = null;

			for(int i = 0; i < stepConfig.size(); i ++) {

				final Config config = new Config(actualConfig);
				config.apply(
					(Map<String, Object>) stepConfig.get(Integer.toString(i)),
					"chain-load-" + LogUtil.getDateTimeStamp() + "-" + config.hashCode()
				);

				testStepConfig = config.getTestConfig().getStepConfig();
				final ItemConfig itemConfig = config.getItemConfig();
				final DataConfig dataConfig = itemConfig.getDataConfig();
				final InputConfig dataInputConfig = dataConfig.getInputConfig();
				final com.emc.mongoose.ui.config.item.output.OutputConfig
					itemOutputConfig = itemConfig.getOutputConfig();

				final ItemType itemType = ItemType.valueOf(itemConfig.getType().toUpperCase());
				final LayerConfig dataLayerConfig = dataInputConfig.getLayerConfig();
				final DataInput dataInput = DataInput.getInstance(
					dataInputConfig.getFile(), dataInputConfig.getSeed(),
					dataLayerConfig.getSize(), dataLayerConfig.getCache()
				);

				final ItemFactory itemFactory = ItemType.getItemFactory(itemType);
				Loggers.MSG.info("Work on the " + itemType.toString().toLowerCase() + " items");

				final LoadConfig loadConfig = config.getLoadConfig();
				final OutputConfig outputConfig = config.getOutputConfig();
				final StorageConfig storageConfig = config.getStorageConfig();
				final QueueConfig queueConfig = storageConfig.getDriverConfig().getQueueConfig();
				final MetricsConfig metricsConfig = config.getOutputConfig().getMetricsConfig();

				final List<StorageDriver> drivers = new ArrayList<>();
				StorageDriverUtil.init(
					drivers, itemConfig, loadConfig, metricsConfig.getAverageConfig(),
					storageConfig, testStepConfig, dataInput
				);

				final LoadGenerator loadGenerator;
				if(nextItemBuff == null) {
					loadGenerator = new BasicLoadGeneratorBuilder<>()
						.setItemConfig(itemConfig)
						.setItemFactory(itemFactory)
						.setItemType(itemType)
						.setLoadConfig(loadConfig)
						.setLimitConfig(commonLimitConfig)
						.setStorageDrivers(drivers)
						.setAuthConfig(storageConfig.getAuthConfig())
						.build();
				} else {
					loadGenerator = new BasicLoadGeneratorBuilder<>()
						.setItemConfig(itemConfig)
						.setItemFactory(itemFactory)
						.setItemType(itemType)
						.setLoadConfig(loadConfig)
						.setLimitConfig(commonLimitConfig)
						.setStorageDrivers(drivers)
						.setAuthConfig(storageConfig.getAuthConfig())
						.setItemInput(nextItemBuff)
						.build();
				}

				final Map<LoadGenerator, List<StorageDriver>> driversMap = new HashMap<>();
				driversMap.put(loadGenerator, drivers);
				final Map<LoadGenerator, SizeInBytes> itemDataSizes = new HashMap<>();
				itemDataSizes.put(loadGenerator, dataConfig.getSize());
				final Map<LoadGenerator, LoadConfig> loadConfigMap = new HashMap<>();
				loadConfigMap.put(loadGenerator, loadConfig);
				final Map<LoadGenerator, OutputConfig> outputConfigMap = new HashMap<>();
				outputConfigMap.put(loadGenerator, outputConfig);
				final LoadController loadController = new BasicLoadController(
					testStepConfig.getId(), driversMap, null, itemDataSizes, loadConfigMap, testStepConfig,
					outputConfigMap
				);
				loadChain.add(loadController);

				if(i < stepConfig.size() - 1) {
					nextItemBuff = new DelayedTransferConvertBuffer<>(
						queueConfig.getOutput(), TimeUnit.SECONDS, itemOutputConfig.getDelay()
					);
					loadController.setIoResultsOutput(nextItemBuff);
				} else {
					// last controller in the chain
					final String itemOutputFile = actualConfig
						.getItemConfig().getOutputConfig().getFile();
					if(itemOutputFile != null && itemOutputFile.length() > 0) {
						final Path itemOutputPath = Paths.get(itemOutputFile);
						if(Files.exists(itemOutputPath)) {
							Loggers.ERR.warn(
								"Items output file \"{}\" already exists", itemOutputPath
							);
						}
						// NOTE: using null as an ItemFactory
						final Output itemOutput = new ItemInfoFileOutput<>(
							itemOutputPath
						);
						loadController.setIoResultsOutput(itemOutput);
					}
				}
			}
		} catch(final IOException e) {
			LogUtil.exception(Level.WARN, e, "Failed to init the data input");
		} catch(final UserShootHisFootException e) {
			LogUtil.exception(Level.WARN, e, "Failed to init the load generator");
		}

		try {
			for(final LoadController nextController : loadChain) {
				nextController.start();
				Loggers.MSG.info("Load step \"{}\" started", nextController.getName());
			}
		} catch(final RemoteException e) {
			LogUtil.exception(Level.WARN, e, "Unexpected failure while starting the controller");
		}

		long timeRemainSec = timeLimitSec;
		long tsStart;
		final int controllersCount = loadChain.size();

		for(int i = 0; i < controllersCount; i ++) {
			final LoadController controller = loadChain.get(i);
			if(timeRemainSec > 0) {
				tsStart = System.currentTimeMillis();
				try {
					try {
						if(controller.await(timeRemainSec, TimeUnit.SECONDS)) {
							Loggers.MSG.info("Load step \"{}\" done", controller.getName());
						} else {
							Loggers.MSG.info("Load step \"{}\" timeout", controller.getName());
						}
					} finally {
						controller.interrupt();
					}
				} catch(final RemoteException e) {
					throw new AssertionError(e);
				}
				timeRemainSec -= (System.currentTimeMillis() - tsStart) / 1000;
			} else {
				break;
			}
		}
	}

	@Override
	public void close()
	throws IOException {
		super.close();
		if(loadChain != null) {
			for(final LoadController nextController : loadChain) {
				try {
					nextController.close();
				} catch(final IOException e) {
					LogUtil.exception(
						Level.WARN, e, "Failed to close the step \"{}\"",  nextController.getName()
					);
				}
			}
			loadChain.clear();
		}
	}

	@Override
	protected ChainLoadStep copyInstance(final Map<String, Object> stepConfig) {
		return new ChainLoadStep(baseConfig, stepConfig);
	}

	@Override
	protected String getTypeName() {
		return "chain";
	}
}
