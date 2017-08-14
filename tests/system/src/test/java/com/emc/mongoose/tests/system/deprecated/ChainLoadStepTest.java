package com.emc.mongoose.tests.system.deprecated;

import com.emc.mongoose.api.common.SizeInBytes;
import com.emc.mongoose.api.common.env.PathUtil;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.run.scenario.JsonScenario;
import com.emc.mongoose.tests.system.base.deprecated.EnvConfiguredScenarioTestBase;
import com.emc.mongoose.tests.system.util.DirWithManyFilesDeleter;
import com.emc.mongoose.ui.log.LogUtil;
import static com.emc.mongoose.api.common.Constants.KEY_TEST_STEP_ID;
import static com.emc.mongoose.api.common.env.PathUtil.getBaseDir;
import static com.emc.mongoose.run.scenario.Scenario.DIR_SCENARIO;
import static org.junit.Assume.assumeFalse;

import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.ThreadContext;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 Created by andrey on 09.06.17.
 */
@Ignore public class ChainLoadStepTest
extends EnvConfiguredScenarioTestBase {

	private static final long COUNT_LIMIT = 100_000;

	private static String ITEM_OUTPUT_PATH;
	private static String STD_OUTPUT;

	@BeforeClass
	public static void setUpClass()
	throws Exception {
		EXCLUDE_PARAMS.clear();
		EXCLUDE_PARAMS.put(KEY_ENV_STORAGE_DRIVER_TYPE, Arrays.asList("atmos"));
		EXCLUDE_PARAMS.put(KEY_ENV_STORAGE_DRIVER_CONCURRENCY, Arrays.asList(1, 1000));
		EXCLUDE_PARAMS.put(
			KEY_ENV_ITEM_DATA_SIZE,
			Arrays.asList(
				new SizeInBytes(0), new SizeInBytes("1MB"), new SizeInBytes("100MB"),
				new SizeInBytes("10GB")
			)
		);
		STEP_ID = ChainLoadStepTest.class.getSimpleName();
		SCENARIO_PATH = Paths.get(
			getBaseDir(), DIR_SCENARIO, "systest", "ChainStep.json"
		);
		ThreadContext.put(KEY_TEST_STEP_ID, STEP_ID);
		CONFIG_ARGS.add("--test-step-limit-count=" + COUNT_LIMIT);
		EnvConfiguredScenarioTestBase.setUpClass();
		if(SKIP_FLAG) {
			return;
		}
		switch(STORAGE_DRIVER_TYPE) {
			case STORAGE_TYPE_FS:
				ITEM_OUTPUT_PATH = Paths.get(
					Paths.get(PathUtil.getBaseDir()).getParent().toString(), STEP_ID
				).toString();
				CONFIG.getItemConfig().getOutputConfig().setPath(ITEM_OUTPUT_PATH);
				break;
			case STORAGE_TYPE_SWIFT:
				CONFIG.getStorageConfig().getNetConfig().getHttpConfig().setNamespace("ns1");
				break;
		}
		SCENARIO = new JsonScenario(CONFIG, SCENARIO_PATH.toFile());
		STD_OUT_STREAM.startRecording();
		SCENARIO.run();
		TimeUnit.SECONDS.sleep(10);
		LogUtil.flushAll();
		STD_OUTPUT = STD_OUT_STREAM.stopRecordingAndGet();
	}

	@AfterClass
	public static void tearDownClass()
	throws Exception {
		if(! SKIP_FLAG) {
			if(STORAGE_TYPE_FS.equals(STORAGE_DRIVER_TYPE)) {
				try {
					DirWithManyFilesDeleter.deleteExternal(ITEM_OUTPUT_PATH);
				} catch(final Exception e) {
					e.printStackTrace(System.err);
				}
			}
		}
		EnvConfiguredScenarioTestBase.tearDownClass();
	}

	@Test
	public final void testStdOutput()
	throws Exception {
		assumeFalse(SKIP_FLAG);
		testMetricsTableStdout(
			STD_OUTPUT, STEP_ID, STORAGE_DRIVERS_COUNT, COUNT_LIMIT,
			new HashMap<IoType, Integer>() {{
				put(IoType.CREATE, CONCURRENCY);
				put(IoType.READ, CONCURRENCY);
				put(IoType.UPDATE, CONCURRENCY);
				put(IoType.DELETE, CONCURRENCY);
				put(IoType.NOOP, CONCURRENCY);
			}}
		);
	}

	@Test
	public final void testTotalMetricsLogFile()
	throws Exception {
		assumeFalse(SKIP_FLAG);
		final List<CSVRecord> totalRecs = getMetricsTotalLogRecords();
		testTotalMetricsLogRecord(
			totalRecs.get(0), IoType.CREATE, CONCURRENCY, STORAGE_DRIVERS_COUNT, ITEM_DATA_SIZE,
			COUNT_LIMIT, 0
		);
		testTotalMetricsLogRecord(
			totalRecs.get(1), IoType.READ, CONCURRENCY, STORAGE_DRIVERS_COUNT, ITEM_DATA_SIZE,
			COUNT_LIMIT, 0
		);
		testTotalMetricsLogRecord(
			totalRecs.get(2), IoType.UPDATE, CONCURRENCY, STORAGE_DRIVERS_COUNT,
			new SizeInBytes(1, ITEM_DATA_SIZE.get(), 1), COUNT_LIMIT, 0
		);
		// looks like nagaina is not fast enough to reflect the immediate data changes...
		testTotalMetricsLogRecord(
			totalRecs.get(3), IoType.READ, CONCURRENCY, STORAGE_DRIVERS_COUNT, ITEM_DATA_SIZE, 0, 0
		);
		testTotalMetricsLogRecord(
			totalRecs.get(4), IoType.DELETE, CONCURRENCY, STORAGE_DRIVERS_COUNT, new SizeInBytes(0),
			0, 0
		);
		testTotalMetricsLogRecord(
			totalRecs.get(5), IoType.NOOP, CONCURRENCY, STORAGE_DRIVERS_COUNT, new SizeInBytes(0),
			0, 0
		);
	}
}