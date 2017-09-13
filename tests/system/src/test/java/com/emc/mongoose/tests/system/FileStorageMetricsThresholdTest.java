package com.emc.mongoose.tests.system;

import com.github.akurilov.commons.system.SizeInBytes;
import com.emc.mongoose.api.common.env.PathUtil;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.run.scenario.JsonScenario;
import com.emc.mongoose.tests.system.base.ScenarioTestBase;
import com.emc.mongoose.tests.system.base.params.Concurrency;
import com.emc.mongoose.tests.system.base.params.DriverCount;
import com.emc.mongoose.tests.system.base.params.ItemSize;
import com.emc.mongoose.tests.system.base.params.StorageType;
import com.emc.mongoose.tests.system.util.DirWithManyFilesDeleter;
import com.emc.mongoose.tests.system.util.LogPatterns;
import com.emc.mongoose.ui.log.LogUtil;
import static com.emc.mongoose.api.common.env.DateUtil.FMT_DATE_ISO8601;
import static com.emc.mongoose.api.common.env.PathUtil.getBaseDir;
import static com.emc.mongoose.run.scenario.Scenario.DIR_SCENARIO;

import org.apache.commons.csv.CSVRecord;

import org.junit.After;
import org.junit.Before;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

/**
 Created by andrey on 10.06.17.
 */
public class FileStorageMetricsThresholdTest
extends ScenarioTestBase {

	private static final double LOAD_THRESHOLD = 0.1;
	private static final int RANDOM_RANGES_COUNT = 10;

	private String itemOutputPath;
	private String stdOutput;

	public FileStorageMetricsThresholdTest(
		final StorageType storageType, final DriverCount driverCount, final Concurrency concurrency,
		final ItemSize itemSize
	) throws Exception {
		super(storageType, driverCount, concurrency, itemSize);
	}

	@Override
	protected Path makeScenarioPath() {
		return Paths.get(
			getBaseDir(), DIR_SCENARIO, "systest", "FileStorageMetricsThreshold.json"
		);
	}

	@Override
	protected String makeStepId() {
		return FileStorageMetricsThresholdTest.class.getSimpleName() + '-' +
			storageType.name() + '-' + driverCount.name() + 'x' + concurrency.name() + '-' +
			itemSize.name();
	}

	@Before
	public void setUp()
	throws Exception {
		super.setUp();
		itemOutputPath = Paths.get(
			Paths.get(PathUtil.getBaseDir()).getParent().toString(), stepId
		).toString();
		config.getItemConfig().getOutputConfig().setPath(itemOutputPath);
		scenario = new JsonScenario(config, scenarioPath.toFile());
		stdOutStream.startRecording();
		scenario.run();
		LogUtil.flushAll();
		stdOutput = stdOutStream.stopRecordingAndGet();
		TimeUnit.SECONDS.sleep(10);
	}

	@After
	public void tearDown()
	throws Exception {
		try {
			DirWithManyFilesDeleter.deleteExternal(itemOutputPath);
		} catch(final Exception e) {
			e.printStackTrace(System.err);
		}
		super.tearDown();
	}

	@Override
	public void test()
	throws Exception {

		// metrics threshold in the stdout
		int n = 0;
		Matcher m;
		while(true) {
			m = LogPatterns.STD_OUT_LOAD_THRESHOLD_ENTRANCE.matcher(stdOutput);
			if(!m.find()) {
				break;
			}
			final Date dtEnter = FMT_DATE_ISO8601.parse(m.group("dateTime"));
			final int threshold = Integer.parseInt(m.group("threshold"));
			assertEquals(concurrency.getValue() * LOAD_THRESHOLD, threshold, 0);
			stdOutput = m.replaceFirst("");
			m = LogPatterns.STD_OUT_LOAD_THRESHOLD_EXIT.matcher(
				stdOutput.substring(m.regionStart())
			);
			assertTrue(m.find());
			final Date dtExit = FMT_DATE_ISO8601.parse(m.group("dateTime"));
			assertTrue(dtEnter.before(dtExit));
			stdOutput = m.replaceFirst("");
			n ++;
		}
		assertEquals(3, n);

		// threshold total metrics file
		final List<CSVRecord> totalThresholdMetricsRecs = getMetricsMedTotalLogRecords();
		testTotalMetricsLogRecord(
			totalThresholdMetricsRecs.get(0), IoType.CREATE, concurrency.getValue(), driverCount.getValue(),
			itemSize.getValue(), 0, 0
		);
		testTotalMetricsLogRecord(
			totalThresholdMetricsRecs.get(1), IoType.READ, concurrency.getValue(), driverCount.getValue(),
			itemSize.getValue(), 0, 0
		);
		testTotalMetricsLogRecord(
			totalThresholdMetricsRecs.get(2), IoType.UPDATE, concurrency.getValue(), driverCount.getValue(),
			new SizeInBytes(2 >> RANDOM_RANGES_COUNT - 1, itemSize.getValue().get(), 1), 0, 0
		);

		// total metrics log file
		final List<CSVRecord> totalMetricsRecs = getMetricsTotalLogRecords();
		testTotalMetricsLogRecord(
			totalMetricsRecs.get(0), IoType.CREATE, concurrency.getValue(), driverCount.getValue(),
			itemSize.getValue(), 0, 0
		);
		testTotalMetricsLogRecord(
			totalMetricsRecs.get(1), IoType.READ, concurrency.getValue(), driverCount.getValue(),
			itemSize.getValue(), 0, 0
		);
		testTotalMetricsLogRecord(
			totalMetricsRecs.get(2), IoType.UPDATE, concurrency.getValue(), driverCount.getValue(),
			new SizeInBytes(2 >> RANDOM_RANGES_COUNT - 1, itemSize.getValue().get(), 1), 0, 0
		);

		// metrics log file
		final List<CSVRecord> metricsLogRecs = getMetricsLogRecords();
		final List<CSVRecord> createMetricsRecs = new ArrayList<>();
		final List<CSVRecord> readMetricsRecs = new ArrayList<>();
		final List<CSVRecord> updateMetricsRecs = new ArrayList<>();
		IoType nextMetricsRecIoType;
		for(final CSVRecord metricsRec : metricsLogRecs) {
			nextMetricsRecIoType = IoType.valueOf(metricsRec.get("TypeLoad"));
			switch(nextMetricsRecIoType) {
				case NOOP:
					fail("Unexpected I/O type: " + nextMetricsRecIoType);
					break;
				case CREATE:
					createMetricsRecs.add(metricsRec);
					break;
				case READ:
					readMetricsRecs.add(metricsRec);
					break;
				case UPDATE:
					updateMetricsRecs.add(metricsRec);
					break;
				case DELETE:
					fail("Unexpected I/O type: " + nextMetricsRecIoType);
					break;
				case LIST:
					fail("Unexpected I/O type: " + nextMetricsRecIoType);
					break;
			}
		}
		final long period = config.getOutputConfig().getMetricsConfig().getAverageConfig().getPeriod();
		testMetricsLogRecords(
			createMetricsRecs, IoType.CREATE, concurrency.getValue(), driverCount.getValue(), itemSize.getValue(),
			0, 0, period
		);
		testMetricsLogRecords(
			readMetricsRecs, IoType.READ, concurrency.getValue(), driverCount.getValue(), itemSize.getValue(),
			0, 0, period
		);
		testMetricsLogRecords(
			updateMetricsRecs, IoType.UPDATE, concurrency.getValue(), driverCount.getValue(),
			new SizeInBytes(2 >> RANDOM_RANGES_COUNT - 1, itemSize.getValue().get(), 1),
			0, 0, period
		);

		// stdout
		testSingleMetricsStdout(
			stdOutput.replaceAll("[\r\n]+", " "),
			IoType.CREATE, concurrency.getValue(), driverCount.getValue(), itemSize.getValue(), period
		);
		testSingleMetricsStdout(
			stdOutput.replaceAll("[\r\n]+", " "),
			IoType.READ, concurrency.getValue(), driverCount.getValue(), itemSize.getValue(), period
		);
		testSingleMetricsStdout(
			stdOutput.replaceAll("[\r\n]+", " "),
			IoType.UPDATE, concurrency.getValue(), driverCount.getValue(),
			new SizeInBytes(2 >> RANDOM_RANGES_COUNT - 1, itemSize.getValue().get(), 1), period
		);
	}
}
