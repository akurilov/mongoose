package com.emc.mongoose.system;

import com.emc.mongoose.config.TimeUtil;
import com.emc.mongoose.item.io.IoType;
import com.emc.mongoose.svc.ServiceUtil;
import com.emc.mongoose.system.base.params.Concurrency;
import com.emc.mongoose.system.base.params.EnvParams;
import com.emc.mongoose.system.base.params.ItemSize;
import com.emc.mongoose.system.base.params.RunMode;
import com.emc.mongoose.system.base.params.StorageType;
import com.emc.mongoose.system.util.DirWithManyFilesDeleter;
import com.emc.mongoose.system.util.docker.HttpStorageMockContainer;
import com.emc.mongoose.system.util.docker.MongooseContainer;
import com.emc.mongoose.system.util.docker.MongooseSlaveNodeContainer;
import static com.emc.mongoose.system.util.LogValidationUtil.getContainerMetricsLogRecords;
import static com.emc.mongoose.system.util.LogValidationUtil.getContainerMetricsTotalLogRecords;
import static com.emc.mongoose.system.util.LogValidationUtil.testContainerIoTraceLogRecords;
import static com.emc.mongoose.system.util.LogValidationUtil.testFinalMetricsTableRowStdout;
import static com.emc.mongoose.system.util.LogValidationUtil.testIoTraceRecord;
import static com.emc.mongoose.system.util.LogValidationUtil.testMetricsLogRecords;
import static com.emc.mongoose.system.util.LogValidationUtil.testSingleMetricsStdout;
import static com.emc.mongoose.system.util.LogValidationUtil.testTotalMetricsLogRecord;
import static com.emc.mongoose.system.util.TestCaseUtil.stepId;
import static com.emc.mongoose.system.util.docker.MongooseContainer.BUNDLED_DEFAULTS;
import static com.emc.mongoose.system.util.docker.MongooseContainer.CONTAINER_SHARE_PATH;
import static com.emc.mongoose.system.util.docker.MongooseContainer.HOST_SHARE_PATH;
import static com.emc.mongoose.system.util.docker.MongooseContainer.containerScenarioPath;

import com.github.akurilov.commons.concurrent.AsyncRunnableBase;
import com.github.akurilov.commons.reflection.TypeUtil;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;

import org.junit.After;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RunWith(Parameterized.class)
public final class ReadUsingVariablePathTest {

	@Parameterized.Parameters(name = "{0}, {1}, {2}, {3}")
	public static List<Object[]> envParams() {
		return EnvParams.PARAMS;
	}

	private static final String ITEM_LIST_FILE = CONTAINER_SHARE_PATH + File.separator
		+ "read_using_variable_path_items.csv";
	private static final int EXPECTED_COUNT = 10000;

	private final Map<String, HttpStorageMockContainer> storageMocks = new HashMap<>();
	private final Map<String, MongooseSlaveNodeContainer> slaveNodes = new HashMap<>();
	private final MongooseContainer testContainer;
	private final String stepId;
	private final StorageType storageType;
	private final RunMode runMode;
	private final Concurrency concurrency;
	private final ItemSize itemSize;
	private final String containerFileOutputPath;

	public ReadUsingVariablePathTest(
		final StorageType storageType, final RunMode runMode, final Concurrency concurrency,
		final ItemSize itemSize
	) throws Exception {

		stepId = stepId(getClass(), storageType, runMode, concurrency, itemSize);
		try {
			FileUtils.deleteDirectory(MongooseContainer.HOST_LOG_PATH.toFile());
		} catch(final IOException ignored) {
		}

		this.storageType = storageType;
		this.runMode = runMode;
		this.concurrency = concurrency;
		this.itemSize = itemSize;

		containerFileOutputPath = CONTAINER_SHARE_PATH + '/' + stepId;
		if(storageType.equals(StorageType.FS)) {
			try {
				DirWithManyFilesDeleter.deleteExternal(
					containerFileOutputPath.replace(CONTAINER_SHARE_PATH, HOST_SHARE_PATH.toString())
				);
			} catch(final Throwable ignored) {
			}
		}

		new File(ITEM_LIST_FILE.replace(CONTAINER_SHARE_PATH, HOST_SHARE_PATH.toString())).delete();

		final List<String> env = System.getenv()
			.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + e.getValue())
			.collect(Collectors.toList());
		env.add("ITEM_DATA_SIZE=" + itemSize.getValue());
		env.add("ITEM_LIST_FILE=" + ITEM_LIST_FILE);
		env.add("ITEM_OUTPUT_PATH=" + containerFileOutputPath);
		env.add("STEP_LIMIT_COUNT=" + EXPECTED_COUNT);

		final List<String> args = new ArrayList<>();

		switch(storageType) {
			case ATMOS:
			case S3:
			case SWIFT:
				final HttpStorageMockContainer storageMock = new HttpStorageMockContainer(
					HttpStorageMockContainer.DEFAULT_PORT, false, null, null, Character.MAX_RADIX,
					HttpStorageMockContainer.DEFAULT_CAPACITY,
					HttpStorageMockContainer.DEFAULT_CONTAINER_CAPACITY,
					HttpStorageMockContainer.DEFAULT_CONTAINER_COUNT_LIMIT,
					HttpStorageMockContainer.DEFAULT_FAIL_CONNECT_EVERY,
					HttpStorageMockContainer.DEFAULT_FAIL_RESPONSES_EVERY,
					0
				);
				final String addr = "127.0.0.1:" + HttpStorageMockContainer.DEFAULT_PORT;
				storageMocks.put(addr, storageMock);
				args.add(
					"--storage-net-node-addrs="
						+ storageMocks.keySet().stream().collect(Collectors.joining(","))
				);
				break;
		}

		switch(runMode) {
			case DISTRIBUTED:
				final String localExternalAddr = ServiceUtil.getAnyExternalHostAddress();
				for(int i = 1; i < runMode.getNodeCount(); i ++) {
					final int port = MongooseSlaveNodeContainer.DEFAULT_PORT + i;
					final MongooseSlaveNodeContainer nodeSvc = new MongooseSlaveNodeContainer(port);
					final String addr = localExternalAddr + ":" + port;
					slaveNodes.put(addr, nodeSvc);
				}
				args.add(
					"--load-step-node-addrs="
						+ slaveNodes.keySet().stream().collect(Collectors.joining(","))
				);
				break;
		}

		final String containerScenarioPath = containerScenarioPath(getClass());
		testContainer = new MongooseContainer(
			stepId, storageType, runMode, concurrency, itemSize, containerScenarioPath, env, args
		);
	}

	@Before
	public final void setUp()
	throws Exception {
		storageMocks.values().forEach(AsyncRunnableBase::start);
		slaveNodes.values().forEach(AsyncRunnableBase::start);
		testContainer.start();
		testContainer.await(1000, TimeUnit.SECONDS);
	}

	@After
	public final void tearDown()
	throws Exception {

		testContainer.close();

		slaveNodes.values().parallelStream().forEach(
			storageMock -> {
				try {
					storageMock.close();
				} catch(final Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		);
		storageMocks.values().parallelStream().forEach(
			storageMock -> {
				try {
					storageMock.close();
				} catch(final Throwable t) {
					t.printStackTrace(System.err);
				}
			}
		);
	}

	@Test
	public final void test()
	throws Exception {

		final LongAdder ioTraceRecCount = new LongAdder();
		final int baseOutputPathLen = containerFileOutputPath.length();
		// Item path should look like:
		// ${FILE_OUTPUT_PATH}/1/b/0123456789abcdef
		// ${FILE_OUTPUT_PATH}/b/fedcba9876543210
		final Pattern subPathPtrn = Pattern.compile("(/[0-9a-f]){1,2}/[0-9a-f]{16}");
		final Consumer<CSVRecord> ioTraceReqTestFunc = ioTraceRec -> {
			testIoTraceRecord(ioTraceRec, IoType.READ.ordinal(), itemSize.getValue());
			String nextFilePath = ioTraceRec.get("ItemPath");
			assertTrue(nextFilePath.startsWith(containerFileOutputPath));
			nextFilePath = nextFilePath.substring(baseOutputPathLen);
			final Matcher m = subPathPtrn.matcher(nextFilePath);
			assertTrue(m.matches());
			ioTraceRecCount.increment();
		};
		testContainerIoTraceLogRecords(stepId, ioTraceReqTestFunc);
		assertEquals(
			"There should be more than 1 record in the I/O trace log file",
			EXPECTED_COUNT, ioTraceRecCount.sum()
		);

		final int outputMetricsAveragePeriod;
		final Object outputMetricsAveragePeriodRaw = BUNDLED_DEFAULTS.val("output-metrics-average-period");
		if(outputMetricsAveragePeriodRaw instanceof String) {
			outputMetricsAveragePeriod = (int) TimeUtil.getTimeInSeconds((String) outputMetricsAveragePeriodRaw);
		} else {
			outputMetricsAveragePeriod = TypeUtil.typeConvert(outputMetricsAveragePeriodRaw, int.class);
		}

		testMetricsLogRecords(
			getContainerMetricsLogRecords(stepId), IoType.READ, concurrency.getValue(), runMode.getNodeCount(),
			itemSize.getValue(), EXPECTED_COUNT, 0, outputMetricsAveragePeriod
		);

		testTotalMetricsLogRecord(
			getContainerMetricsTotalLogRecords(stepId).get(0),
			IoType.READ, concurrency.getValue(), runMode.getNodeCount(), itemSize.getValue(),
			EXPECTED_COUNT, 0
		);

		final String stdOutContent = testContainer.stdOutContent();

		testSingleMetricsStdout(
			stdOutContent.replaceAll("[\r\n]+", " "), IoType.READ, concurrency.getValue(), runMode.getNodeCount(),
			itemSize.getValue(), outputMetricsAveragePeriod
		);

		testFinalMetricsTableRowStdout(
			stdOutContent, stepId, IoType.CREATE, runMode.getNodeCount(), concurrency.getValue(),
			EXPECTED_COUNT, 0, itemSize.getValue()
		);
	}
}
