package com.emc.mongoose.load.step.weighted;

import com.emc.mongoose.base.env.Extension;
import com.emc.mongoose.base.exception.InterruptRunException;
import com.emc.mongoose.base.exception.OmgShootMyFootException;
import com.emc.mongoose.base.data.DataInput;
import com.emc.mongoose.base.item.op.OpType;
import com.emc.mongoose.base.item.Item;
import com.emc.mongoose.base.item.ItemFactory;
import com.emc.mongoose.base.item.io.ItemInfoFileOutput;
import com.emc.mongoose.base.item.ItemType;
import com.emc.mongoose.base.load.generator.LoadGenerator;
import com.emc.mongoose.base.load.generator.LoadGeneratorBuilder;
import com.emc.mongoose.base.load.generator.LoadGeneratorBuilderImpl;
import com.emc.mongoose.base.load.step.local.LoadStepLocalBase;
import com.emc.mongoose.base.load.step.local.context.LoadStepContext;
import com.emc.mongoose.base.load.step.local.context.LoadStepContextImpl;
import com.emc.mongoose.base.metrics.MetricsManager;
import com.emc.mongoose.base.storage.driver.StorageDriver;
import com.emc.mongoose.base.logging.LogUtil;
import com.emc.mongoose.base.logging.Loggers;

import com.github.akurilov.commons.io.Output;
import com.github.akurilov.commons.reflection.TypeUtil;
import com.github.akurilov.commons.system.SizeInBytes;
import com.github.akurilov.commons.concurrent.throttle.IndexThrottle;
import com.github.akurilov.commons.concurrent.throttle.RateThrottle;
import com.github.akurilov.commons.concurrent.throttle.SequentialWeightsThrottle;
import static com.github.akurilov.commons.collection.TreeUtil.reduceForest;

import static com.github.akurilov.confuse.Config.deepToMap;
import com.github.akurilov.confuse.Config;
import com.github.akurilov.confuse.exceptions.InvalidValuePathException;
import com.github.akurilov.confuse.exceptions.InvalidValueTypeException;
import com.github.akurilov.confuse.impl.BasicConfig;

import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class WeightedLoadStepLocal
extends LoadStepLocalBase {

	public WeightedLoadStepLocal(
		final Config baseConfig, final List<Extension> extensions, final List<Config> contextConfigs,
		final MetricsManager metricsManager
	) {
		super(baseConfig, extensions, contextConfigs, metricsManager);
	}

	@Override
	public String getTypeName() {
		return WeightedLoadStepExtension.TYPE;
	}

	@Override
	protected void init()
	throws InterruptRunException {

		final String autoStepId = "weighted_" + LogUtil.getDateTimeStamp();
		final Config stepConfig = config.configVal("load-step");
		if(stepConfig.boolVal("idAutoGenerated")) {
			stepConfig.val("id", autoStepId);
		}
		final int subStepCount = ctxConfigs.size();

		// 1st pass: determine the weights map
		final int[] weights = new int[subStepCount];
		final List<Config> subConfigs = new ArrayList<>(subStepCount);
		for(int originIndex = 0; originIndex < subStepCount; originIndex ++) {
			final Map<String, Object> mergedConfigTree = reduceForest(
				Arrays.asList(deepToMap(config), deepToMap(ctxConfigs.get(originIndex)))
			);
			final Config subConfig;
			try {
				subConfig = new BasicConfig(config.pathSep(), config.schema(), mergedConfigTree);
			} catch(final InvalidValueTypeException | InvalidValuePathException e) {
				LogUtil.exception(Level.FATAL, e, "Scenario syntax error");
				throw new InterruptRunException(e);
			}
			subConfigs.add(subConfig);
			final int weight = subConfig.intVal("load-op-weight");
			weights[originIndex] = weight;
		}

		final IndexThrottle weightThrottle = new SequentialWeightsThrottle(weights);

		// 2nd pass: initialize the sub steps
		for(int originIndex = 0; originIndex < subStepCount; originIndex ++) {

			final Config subConfig = subConfigs.get(originIndex);
			final Config loadConfig = subConfig.configVal("load");
			final Config opConfig = loadConfig.configVal("op");
			final OpType opType = OpType.valueOf(opConfig.stringVal("type").toUpperCase());
			final Config storageConfig = subConfig.configVal("storage");
			final int concurrencyLimit = storageConfig.intVal("driver-limit-concurrency");
			final Config outputConfig = subConfig.configVal("output");
			final Config metricsConfig = outputConfig.configVal("metrics");
			final SizeInBytes itemDataSize;
			final Object itemDataSizeRaw = subConfig.val("item-data-size");
			if(itemDataSizeRaw instanceof String) {
				itemDataSize = new SizeInBytes((String) itemDataSizeRaw);
			} else {
				itemDataSize = new SizeInBytes(TypeUtil.typeConvert(itemDataSizeRaw, long.class));
			}
			final boolean colorFlag = outputConfig.boolVal("color");

			initMetrics(originIndex, opType, concurrencyLimit, metricsConfig, itemDataSize, colorFlag);

			final Config itemConfig = subConfig.configVal("item");
			final Config dataConfig = itemConfig.configVal("data");
			final Config dataInputConfig = dataConfig.configVal("input");
			final Config limitConfig = stepConfig.configVal("limit");
			final Config dataLayerConfig = dataInputConfig.configVal("layer");

			final String testStepId = stepConfig.stringVal("id");

			try {

				final Object dataLayerSizeRaw = dataLayerConfig.val("size");
				final SizeInBytes dataLayerSize;
				if(dataLayerSizeRaw instanceof String) {
					dataLayerSize = new SizeInBytes((String) dataLayerSizeRaw);
				} else {
					dataLayerSize = new SizeInBytes(TypeUtil.typeConvert(dataLayerSizeRaw, int.class));
				}

				final DataInput dataInput = DataInput.instance(
					dataInputConfig.stringVal("file"), dataInputConfig.stringVal("seed"), dataLayerSize,
					dataLayerConfig.intVal("cache")
				);

				final int batchSize = loadConfig.intVal("batch-size");


				try {

					final StorageDriver driver = StorageDriver.instance(
						extensions, storageConfig, dataInput, dataConfig.boolVal("verify"), batchSize, testStepId
					);

					final ItemType itemType = ItemType.valueOf(itemConfig.stringVal("type").toUpperCase());
					final ItemFactory<Item> itemFactory = ItemType.getItemFactory(itemType);
					final double rateLimit = opConfig.doubleVal("limit-rate");

					try {
						final LoadGeneratorBuilder generatorBuilder = new LoadGeneratorBuilderImpl<>()
							.itemConfig(itemConfig)
							.loadConfig(loadConfig)
							.itemType(itemType)
							.itemFactory((ItemFactory) itemFactory)
							.loadOperationsOutput(driver)
							.authConfig(storageConfig.configVal("auth"))
							.originIndex(originIndex)
							.addThrottle(weightThrottle);
						if(rateLimit > 0) {
							generatorBuilder.addThrottle(new RateThrottle(rateLimit));
						}
						final LoadGenerator generator = generatorBuilder.build();

						final LoadStepContext stepCtx = new LoadStepContextImpl<>(
							testStepId, generator, driver, metricsContexts.get(originIndex), loadConfig,
							outputConfig.boolVal("metrics-trace-persist")
						);
						stepContexts.add(stepCtx);

						final String itemOutputFile = itemConfig.stringVal("output-file");
						if(itemOutputFile != null && itemOutputFile.length() > 0) {
							final Path itemOutputPath = Paths.get(itemOutputFile);
							if(Files.exists(itemOutputPath)) {
								Loggers.ERR.warn("Items output file \"{}\" already exists", itemOutputPath);
							}
							try {
								final Output<? extends Item> itemOutput = new ItemInfoFileOutput<>(itemOutputPath);
								stepCtx.operationsResultsOutput(itemOutput);
							} catch(final IOException e) {
								LogUtil.exception(
									Level.ERROR, e,
									"Failed to initialize the item output, the processed " +
										"items info won't be persisted"
								);
							}
						}
					} catch(final OmgShootMyFootException e) {
						throw new IllegalStateException("Failed to initialize the load generator", e);
					}
				} catch(final OmgShootMyFootException e) {
					throw new IllegalStateException("Failed to initialize the storage driver", e);
				} catch(final InterruptedException e) {
					throw new InterruptRunException(e);
				}
			} catch(final IOException e) {
				throw new IllegalStateException("Failed to initialize the data input", e);
			}
		}
	}
}
