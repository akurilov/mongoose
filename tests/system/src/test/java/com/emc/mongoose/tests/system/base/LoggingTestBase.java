package com.emc.mongoose.tests.system.base;

import com.github.akurilov.commons.system.SizeInBytes;
import com.emc.mongoose.api.common.env.PathUtil;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.tests.system.base.params.Concurrency;
import com.emc.mongoose.tests.system.base.params.DriverCount;
import com.emc.mongoose.tests.system.base.params.ItemSize;
import com.emc.mongoose.tests.system.base.params.StorageType;
import com.emc.mongoose.tests.system.util.BufferingOutputStream;
import com.emc.mongoose.tests.system.util.LogPatterns;
import com.emc.mongoose.ui.log.LogUtil;
import static com.emc.mongoose.api.common.Constants.K;
import static com.emc.mongoose.api.common.Constants.KEY_TEST_STEP_ID;
import static com.emc.mongoose.api.common.env.DateUtil.FMT_DATE_ISO8601;
import static com.emc.mongoose.api.common.env.DateUtil.FMT_DATE_METRICS_TABLE;
import static com.emc.mongoose.api.metrics.logging.MetricsAsciiTableLogMessage.TABLE_HEADER;
import static com.emc.mongoose.api.metrics.logging.MetricsAsciiTableLogMessage.TABLE_HEADER_PERIOD;
import static com.emc.mongoose.api.model.io.task.IoTask.Status.INTERRUPTED;
import static com.emc.mongoose.api.model.io.task.IoTask.Status.SUCC;

import com.emc.mongoose.ui.log.Loggers;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.ThreadContext;
import org.junit.After;
import org.junit.Before;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 Created by andrey on 19.01.17.
 */
public abstract class LoggingTestBase
extends ParameterizedSysTestBase {

	protected static int LOG_FILE_TIMEOUT_SEC = 15;
	
	protected String stepId;
	protected BufferingOutputStream stdOutStream;

	protected LoggingTestBase(
		final StorageType storageType, final DriverCount driverCount, final Concurrency concurrency,
		final ItemSize itemSize
	) throws Exception {
		super(storageType, driverCount, concurrency, itemSize);
	}

	@Before
	public void setUp()
	throws Exception {
		stepId = makeStepId();
		// remove previous logs if exist
		FileUtils.deleteDirectory(Paths.get(PathUtil.getBaseDir(), "log", stepId).toFile());
		LogUtil.init();
		ThreadContext.put(KEY_TEST_STEP_ID, stepId);
		Loggers.MSG.info(
			"{} params: {} = {}, {} = {}, {} = {}, {} = {}",
			getClass().getSimpleName(),
			StorageType.KEY_ENV, storageType.name(),
			DriverCount.KEY_ENV, driverCount.name(),
			Concurrency.KEY_ENV, concurrency.name(),
			ItemSize.KEY_ENV, itemSize.name()
		);
		stdOutStream = new BufferingOutputStream(System.out);
	}

	@After
	public void tearDown()
	throws Exception {
		stdOutStream.close();
		ThreadContext.remove(KEY_TEST_STEP_ID);
		//LogUtil.shutdown();
	}

	protected String makeStepId() {
		return getClass().getSimpleName() +  '-' + storageType.name() + '-' + driverCount.name() +
			'x' + concurrency.name() + '-' + itemSize.name();
	}

	private List<String> getLogFileLines(final String fileName)
	throws IOException {
		final File logFile = Paths.get(PathUtil.getBaseDir(), "log", stepId, fileName).toFile();
		try(final BufferedReader br = new BufferedReader(new FileReader(logFile))) {
			return br.lines().collect(Collectors.toList());
		}
	}

	protected List<String> getMessageLogLines()
	throws IOException {
		return getLogFileLines("messages.log");
	}

	protected List<String> getErrorsLogLines()
	throws IOException {
		return getLogFileLines("errors.log");
	}

	protected List<String> getConfigLogLines()
	throws IOException {
		return getLogFileLines("config.log");
	}

	protected List<String> getPartsUploadLogLines()
	throws IOException {
		return getLogFileLines("parts.upload.csv");
	}

	protected List<CSVRecord> getLogFileCsvRecords(final String fileName)
	throws IOException {
		final File logFile = Paths.get(PathUtil.getBaseDir(), "log", stepId, fileName).toFile();
		long prevSize = 1, nextSize;
		for(int t = 0; t < LOG_FILE_TIMEOUT_SEC; t ++) {
			if(logFile.exists()) {
				nextSize = logFile.length();
				if(prevSize == nextSize) {
					break;
				}
				prevSize = nextSize;
			}
			try {
				TimeUnit.SECONDS.sleep(1);
			} catch(final InterruptedException e) {
				return null;
			}
		}
		try(final BufferedReader br = new BufferedReader(new FileReader(logFile))) {
			try(final CSVParser csvParser = CSVFormat.RFC4180.withHeader().parse(br)) {
				final List<CSVRecord> csvRecords = new ArrayList<>();
				for(final CSVRecord csvRecord : csvParser) {
					csvRecords.add(csvRecord);
				}
				return csvRecords;
			}
		}
	}

	protected List<CSVRecord> getMetricsMedLogRecords()
	throws IOException {
		return getLogFileCsvRecords("metrics.threshold.csv");
	}

	protected List<CSVRecord> getMetricsMedTotalLogRecords()
	throws IOException {
		return getLogFileCsvRecords("metrics.threshold.total.csv");
	}

	protected List<CSVRecord> getMetricsLogRecords()
	throws IOException {
		return getLogFileCsvRecords("metrics.csv");
	}

	protected List<CSVRecord> getMetricsTotalLogRecords()
	throws IOException {
		return getLogFileCsvRecords("metrics.total.csv");
	}

	protected List<CSVRecord> getIoTraceLogRecords()
	throws IOException {
		return getLogFileCsvRecords("io.trace.csv");
	}

	protected void testIoTraceLogRecords(final Consumer<CSVRecord> csvRecordTestFunc)
	throws IOException {
		final File logFile = Paths
			.get(PathUtil.getBaseDir(), "log", stepId, "io.trace.csv")
			.toFile();
		long prevSize = 1, nextSize;
		for(int t = 0; t < LOG_FILE_TIMEOUT_SEC; t ++) {
			if(logFile.exists()) {
				nextSize = logFile.length();
				if(prevSize == nextSize) {
					break;
				}
				prevSize = nextSize;
			}
			try {
				TimeUnit.SECONDS.sleep(1);
			} catch(final InterruptedException e) {
				return;
			}
		}
		try(final BufferedReader br = new BufferedReader(new FileReader(logFile))) {
			try(final CSVParser csvParser = CSVFormat.RFC4180.withHeader().parse(br)) {
				csvParser.forEach(csvRecordTestFunc);
			}
		}
	}

	protected List<CSVRecord> getPartsUploadRecords()
	throws IOException {
		return getLogFileCsvRecords("parts.upload.csv");
	}

	protected static void testMetricsLogRecords(
		final List<CSVRecord> metrics, final IoType expectedIoType, final int expectedConcurrency,
		final int expectedDriverCount, final SizeInBytes expectedItemDataSize,
		final long expectedMaxCount, final int expectedLoadJobTime, final long metricsPeriodSec
	) throws Exception {
		final int countRecords = metrics.size();
		if(expectedLoadJobTime > 0) {
			assertTrue(expectedLoadJobTime + metricsPeriodSec >= countRecords * metricsPeriodSec);
		}

		Date lastTimeStamp = null, nextDateTimeStamp;
		String ioTypeStr;
		int concurrencyLevel;
		int driverCount;
		int concurrencyCurr;
		double concurrencyMean;
		long totalBytes;
		long prevCountSucc = Long.MIN_VALUE, countSucc;
		long countFail;
		long avgItemSize;
		double jobDuration;
		double prevDurationSum = Double.NaN, durationSum;
		double tpAvg, tpLast;
		double bwAvg, bwLast;
		double durAvg;
		int durMin, durLoQ, durMed, durHiQ, durMax;
		double latAvg;
		int latMin, latLoQ, latMed, latHiQ, latMax;

		for(final CSVRecord nextRecord : metrics) {
			nextDateTimeStamp = FMT_DATE_ISO8601.parse(nextRecord.get("DateTimeISO8601"));
			if(lastTimeStamp != null) {
				assertEquals(
					"Next metrics record is expected to be in " + metricsPeriodSec,
					metricsPeriodSec, (nextDateTimeStamp.getTime() - lastTimeStamp.getTime()) / K,
					((double) metricsPeriodSec) / 2
				);
			}
			lastTimeStamp = nextDateTimeStamp;
			ioTypeStr = nextRecord.get("TypeLoad").toUpperCase();
			assertEquals(expectedIoType.name(), ioTypeStr);
			concurrencyLevel = Integer.parseInt(nextRecord.get("Concurrency"));
			assertEquals(
				"Expected concurrency level: " + expectedConcurrency, expectedConcurrency,
				concurrencyLevel
			);
			driverCount = Integer.parseInt(nextRecord.get("DriverCount"));
			assertEquals("Expected driver count: " + driverCount, expectedDriverCount, driverCount);
			concurrencyCurr = Integer.parseInt(nextRecord.get("ConcurrencyCurr"));
			concurrencyMean = Double.parseDouble(nextRecord.get("ConcurrencyMean"));
			if(expectedConcurrency > 0) {
				assertTrue(concurrencyCurr <= driverCount * expectedConcurrency);
				assertTrue(concurrencyMean <= driverCount * expectedConcurrency);
			} else {
				assertTrue(concurrencyCurr >= 0);
				assertTrue(concurrencyMean >= 0);
			}
			totalBytes = SizeInBytes.toFixedSize(nextRecord.get("Size"));
			assertTrue(totalBytes >= 0);
			countSucc = Long.parseLong(nextRecord.get("CountSucc"));
			if(prevCountSucc == Long.MIN_VALUE) {
				assertTrue(Long.toString(countSucc), countSucc >= 0);
			} else {
				assertTrue(Long.toString(countSucc), countSucc >= prevCountSucc);
			}
			if(expectedMaxCount > 0) {
				assertTrue(
					"Expected no more than " + expectedMaxCount + " results, bot got " + countSucc,
					countSucc <= expectedMaxCount
				);
			}
			prevCountSucc = countSucc;
			countFail = Long.parseLong(nextRecord.get("CountFail"));
			assertTrue(Long.toString(countFail), countFail < 1);
			if(countSucc > 0) {
				avgItemSize = totalBytes / countSucc;
				if(expectedItemDataSize.getMin() < expectedItemDataSize.getMax()) {
					assertTrue(expectedItemDataSize.getMin() <= avgItemSize);
					assertTrue(expectedItemDataSize.getMax() >= avgItemSize);
				} else {
					assertEquals(
						"Actual average item size: " + avgItemSize, expectedItemDataSize.getAvg(),
						avgItemSize, expectedItemDataSize.get() / 100
					);
				}
			}
			jobDuration = Double.parseDouble(nextRecord.get("JobDuration[s]"));
			if(expectedLoadJobTime > 0) {
				assertTrue(
					"Step duration limit (" + expectedLoadJobTime + ") is broken: " + jobDuration,
					jobDuration <= expectedLoadJobTime + 1
				);
			}
			durationSum = Double.parseDouble(nextRecord.get("DurationSum[s]"));
			if(Double.isNaN(prevDurationSum)) {
				assertTrue(durationSum >= 0);
			} else {
				assertTrue(durationSum >= prevDurationSum);
				if(expectedConcurrency > 0 && jobDuration > 1) {
					final double
						effEstimate = durationSum / (driverCount * expectedConcurrency * jobDuration);
					assertTrue(
						"Efficiency estimate: " + effEstimate, effEstimate <= 1 && effEstimate >= 0
					);
				}
			}
			prevDurationSum = durationSum;
			tpAvg = Double.parseDouble(nextRecord.get("TPAvg[op/s]"));
			tpLast = Double.parseDouble(nextRecord.get("TPLast[op/s]"));
			bwAvg = Double.parseDouble(nextRecord.get("BWAvg[MB/s]"));
			bwLast = Double.parseDouble(nextRecord.get("BWLast[MB/s]"));
			assertEquals(bwAvg / tpAvg, bwAvg / tpAvg, expectedItemDataSize.getAvg() / 100);
			assertEquals(bwLast / tpLast, bwLast / tpLast, expectedItemDataSize.getAvg() / 100);
			durAvg = Double.parseDouble(nextRecord.get("DurationAvg[us]"));
			assertTrue(durAvg >= 0);
			durMin = Integer.parseInt(nextRecord.get("DurationMin[us]"));
			assertTrue(durAvg >= durMin);
			durLoQ = Integer.parseInt(nextRecord.get("DurationLoQ[us]"));
			assertTrue(durLoQ >= durMin);
			durMed = Integer.parseInt(nextRecord.get("DurationMed[us]"));
			assertTrue(durMed >= durLoQ);
			durHiQ = Integer.parseInt(nextRecord.get("DurationHiQ[us]"));
			assertTrue(durHiQ >= durMed);
			durMax = Integer.parseInt(nextRecord.get("DurationMax[us]"));
			assertTrue(durMax >= durHiQ);
			latAvg = Double.parseDouble(nextRecord.get("LatencyAvg[us]"));
			assertTrue(latAvg >= 0);
			latMin = Integer.parseInt(nextRecord.get("LatencyMin[us]"));
			assertTrue(latAvg >= latMin);
			latLoQ = Integer.parseInt(nextRecord.get("LatencyLoQ[us]"));
			assertTrue(latLoQ >= latMin);
			latMed = Integer.parseInt(nextRecord.get("LatencyMed[us]"));
			assertTrue(latMed >= latLoQ);
			latHiQ = Integer.parseInt(nextRecord.get("LatencyHiQ[us]"));
			assertTrue(latHiQ >= latMed);
			latMax = Integer.parseInt(nextRecord.get("LatencyMax[us]"));
			assertTrue(latMax >= latHiQ);
		}
	}

	protected static void testTotalMetricsLogRecord(
		final CSVRecord metrics,
		final IoType expectedIoType, final int expectedConcurrency, final int expectedDriverCount,
		final SizeInBytes expectedItemDataSize, final long expectedMaxCount,
		final int expectedLoadJobTime
	) throws Exception {
		try {
			FMT_DATE_ISO8601.parse(metrics.get("DateTimeISO8601"));
		} catch(final ParseException e) {
			fail(e.toString());
		}
		final String ioTypeStr = metrics.get("TypeLoad").toUpperCase();
		assertEquals(ioTypeStr, expectedIoType.name(), ioTypeStr);
		final int concurrencyLevel = Integer.parseInt(metrics.get("Concurrency"));
		assertEquals(Integer.toString(concurrencyLevel), expectedConcurrency, concurrencyLevel);
		final int driverCount = Integer.parseInt(metrics.get("DriverCount"));
		assertEquals(Integer.toString(driverCount), expectedDriverCount, driverCount);
		final double concurrencyLastMean = Double.parseDouble(metrics.get("ConcurrencyMean"));
		if(expectedConcurrency > 0) {
			assertTrue(concurrencyLastMean <= driverCount * expectedConcurrency);
		} else {
			assertTrue(concurrencyLastMean >= 0);
		}
		final long totalBytes = SizeInBytes.toFixedSize(metrics.get("Size"));
		if(
			expectedMaxCount > 0 && expectedItemDataSize.get() > 0 &&
				(
					IoType.CREATE.equals(expectedIoType) || IoType.READ.equals(expectedIoType) ||
						IoType.UPDATE.equals(expectedIoType)
				)
		) {
			assertTrue(Long.toString(totalBytes), totalBytes > 0);
		}
		final long countSucc = Long.parseLong(metrics.get("CountSucc"));
		if(expectedMaxCount > 0) {
			if(expectedLoadJobTime > 0) {
				assertTrue(expectedMaxCount > countSucc);
			} else {
				assertEquals(expectedMaxCount, countSucc);
			}
			assertTrue(Long.toString(countSucc), countSucc > 0);
		}
		final long countFail = Long.parseLong(metrics.get("CountFail"));
		assertTrue("Failures count: " + Long.toString(countFail), countFail < 1);
		if(countSucc > 0) {
			final long avgItemSize = totalBytes / countSucc;
			if(expectedItemDataSize.getMin() < expectedItemDataSize.getMax()) {
				assertTrue(avgItemSize >= expectedItemDataSize.getMin());
				assertTrue(avgItemSize <= expectedItemDataSize.getMax());
			} else {
				assertEquals(
					Long.toString(avgItemSize), expectedItemDataSize.get(), avgItemSize,
					expectedItemDataSize.getAvg() / 100
				);
			}
		}
		final double jobDuration = Double.parseDouble(metrics.get("JobDuration[s]"));
		if(expectedLoadJobTime > 0) {
			assertTrue(
				"Step duration was " + jobDuration + ", but expected not more than" +
					expectedLoadJobTime + 5, jobDuration <= expectedLoadJobTime + 5
			);
		}
		final double durationSum = Double.parseDouble(metrics.get("DurationSum[s]"));
		final double effEstimate = durationSum / (expectedConcurrency * expectedDriverCount * jobDuration);
		if(countSucc > 0 && expectedConcurrency > 0 && jobDuration > 1) {
			assertTrue(
				"Invalid efficiency estimate: " + effEstimate + ", summary duration: " + durationSum
					+ ", concurrency limit: " + expectedConcurrency + ", driver count: "
					+ driverCount + ", job duration: " + jobDuration,
				effEstimate <= 1 && effEstimate >= 0
			);
		}
		final double tpAvg = Double.parseDouble(metrics.get("TPAvg[op/s]"));
		final double tpLast = Double.parseDouble(metrics.get("TPLast[op/s]"));
		final double bwAvg = Double.parseDouble(metrics.get("BWAvg[MB/s]"));
		final double bwLast = Double.parseDouble(metrics.get("BWLast[MB/s]"));
		assertEquals(bwAvg / tpAvg, bwAvg / tpAvg, expectedItemDataSize.getAvg() / 100);
		assertEquals(bwLast / tpLast, bwLast / tpLast, expectedItemDataSize.getAvg() / 100);
		final double durAvg = Double.parseDouble(metrics.get("DurationAvg[us]"));
		assertTrue(durAvg >= 0);
		final int durMin = Integer.parseInt(metrics.get("DurationMin[us]"));
		assertTrue(durAvg >= durMin);
		final int durLoQ = Integer.parseInt(metrics.get("DurationLoQ[us]"));
		assertTrue(durLoQ >= durMin);
		final int durMed = Integer.parseInt(metrics.get("DurationMed[us]"));
		assertTrue(durMed >= durLoQ);
		final int durHiQ = Integer.parseInt(metrics.get("DurationHiQ[us]"));
		assertTrue(durHiQ >= durMed);
		final int durMax = Integer.parseInt(metrics.get("DurationMax[us]"));
		assertTrue(durMax >= durHiQ);
		final double latAvg = Double.parseDouble(metrics.get("LatencyAvg[us]"));
		assertTrue(latAvg >= 0);
		final int latMin = Integer.parseInt(metrics.get("LatencyMin[us]"));
		assertTrue(latAvg >= latMin);
		final int latLoQ = Integer.parseInt(metrics.get("LatencyLoQ[us]"));
		assertTrue(latLoQ >= latMin);
		final int latMed = Integer.parseInt(metrics.get("LatencyMed[us]"));
		assertTrue(latMed >= latLoQ);
		final int latHiQ = Integer.parseInt(metrics.get("LatencyHiQ[us]"));
		assertTrue(latHiQ >= latMed);
		final int latMax = Integer.parseInt(metrics.get("LatencyMax[us]"));
		assertTrue(latMax >= latHiQ);
	}

	protected static void testIoTraceRecord(
		final CSVRecord ioTraceRecord, final int ioTypeCodeExpected, final SizeInBytes sizeExpected
	) {
		assertEquals(ioTypeCodeExpected, Integer.parseInt(ioTraceRecord.get("IoTypeCode")));
		final int actualStatusCode = Integer.parseInt(ioTraceRecord.get("StatusCode"));
		if(INTERRUPTED.ordinal() == actualStatusCode) {
			return;
		}
		assertEquals(
			"Actual status code is " + IoTask.Status.values()[actualStatusCode],
			SUCC.ordinal(), actualStatusCode
		);
		final long duration = Long.parseLong(ioTraceRecord.get("Duration[us]"));
		final String latencyStr = ioTraceRecord.get("RespLatency[us]");
		if(latencyStr != null && !latencyStr.isEmpty()) {
			assertTrue(duration >= Long.parseLong(latencyStr));
		}
		final long size = Long.parseLong(ioTraceRecord.get("TransferSize"));
		if(sizeExpected.getMin() != sizeExpected.getMax()) {
			assertTrue(
				"Expected the size " + sizeExpected.toString() + ", but got " + size,
				sizeExpected.getMin() <= size && size <= sizeExpected.getMax()
			);
		} else {
			assertEquals(
				"Expected the size " + sizeExpected.toString() + " but got " + size,
				sizeExpected.get(), size, sizeExpected.get() / 100
			);
		}
	}

	protected static void testPartsUploadRecord(final List<CSVRecord> recs)
	throws Exception {
		String itemPath, uploadId;
		long respLatency;
		for(final CSVRecord rec : recs) {
			assertEquals(rec.size(), 3);
			itemPath = rec.get("ItemPath");
			assertNotNull(itemPath);
			uploadId = rec.get("UploadId");
			assertNotNull(uploadId);
			respLatency = Long.parseLong(rec.get("RespLatency[us]"));
			assertTrue(respLatency > 0);
		}
	}
	
	protected static void testSingleMetricsStdout(
		final String stdOutContent,
		final IoType expectedIoType, final int expectedConcurrency, final int expectedDriverCount,
		final SizeInBytes expectedItemDataSize, final long metricsPeriodSec
	) throws Exception {
		Date lastTimeStamp = null, nextDateTimeStamp;
		String ioTypeStr;
		int concurrencyLevel;
		int driverCount;
		double concurrencyMean;
		long prevTotalBytes = Long.MIN_VALUE, totalBytes;
		long prevCountSucc = Long.MIN_VALUE, countSucc;
		long countFail;
		long avgItemSize;
		double prevJobDuration = Double.NaN, jobDuration;
		double prevDurationSum = Double.NaN, durationSum;
		double tpAvg, tpLast;
		double bwAvg, bwLast;
		double durAvg;
		int durMin, durMax;
		double latAvg;
		int latMin, latMax;
		
		final Matcher m = LogPatterns.STD_OUT_METRICS_SINGLE.matcher(stdOutContent);
		while(m.find()) {
			nextDateTimeStamp = FMT_DATE_ISO8601.parse(m.group("dateTime"));
			if(lastTimeStamp != null) {
				assertEquals(
					metricsPeriodSec, (nextDateTimeStamp.getTime() - lastTimeStamp.getTime()) / K,
					((double) metricsPeriodSec) / 10
				);
			}
			lastTimeStamp = nextDateTimeStamp;
			ioTypeStr = m.group("typeLoad").toUpperCase();
			assertEquals(ioTypeStr, expectedIoType.name(), ioTypeStr);
			concurrencyLevel = Integer.parseInt(m.group("concurrency"));
			assertEquals(Integer.toString(concurrencyLevel), expectedConcurrency, concurrencyLevel);
			driverCount = Integer.parseInt(m.group("driverCount"));
			assertEquals(Integer.toString(driverCount), expectedDriverCount, driverCount);
			concurrencyMean = Double.parseDouble(m.group("concurrencyLastMean"));
			if(expectedConcurrency > 0) {
				assertTrue(concurrencyMean <= driverCount * expectedConcurrency);
			} else {
				assertTrue(concurrencyMean >= 0);
			}
			totalBytes = SizeInBytes.toFixedSize(m.group("size"));
			if(prevTotalBytes == Long.MIN_VALUE) {
				assertTrue(Long.toString(totalBytes), totalBytes >= 0);
			} else {
				assertTrue(Long.toString(totalBytes), totalBytes >= prevTotalBytes);
			}
			prevTotalBytes = totalBytes;
			countSucc = Long.parseLong(m.group("countSucc"));
			if(prevCountSucc == Long.MIN_VALUE) {
				assertTrue(Long.toString(countSucc), countSucc >= 0);
			} else {
				assertTrue(Long.toString(countSucc), countSucc >= prevCountSucc);
			}
			prevCountSucc = countSucc;
			countFail = Long.parseLong(m.group("countFail"));
			assertTrue(Long.toString(countFail), countFail < 1);
			if(countSucc > 0) {
				avgItemSize = totalBytes / countSucc;
				assertEquals(
					Long.toString(avgItemSize), expectedItemDataSize.getAvg(), avgItemSize,
					expectedItemDataSize.getAvg() / 100
				);
			}
			jobDuration = Double.parseDouble(m.group("jobDur"));
			if(Double.isNaN(prevJobDuration)) {
				assertEquals(Double.toString(jobDuration), 0, jobDuration, 1);
			} else {
				assertEquals(
					Double.toString(jobDuration), prevJobDuration + metricsPeriodSec, jobDuration, 1
				);
			}
			prevJobDuration = jobDuration;
			durationSum = Double.parseDouble(m.group("sumDur"));
			if(Double.isNaN(prevDurationSum)) {
				assertTrue(durationSum >= 0);
			} else {
				assertTrue(durationSum >= prevDurationSum);
			}
			final double
				effEstimate = durationSum / (concurrencyLevel * driverCount * jobDuration);
			assertTrue(Double.toString(effEstimate), effEstimate <= 1 && effEstimate >= 0);
			prevDurationSum = durationSum;
			tpAvg = Double.parseDouble(m.group("tpMean"));
			tpLast = Double.parseDouble(m.group("tpLast"));
			bwAvg = Double.parseDouble(m.group("bwMean"));
			bwLast = Double.parseDouble(m.group("bwLast"));
			assertEquals(bwAvg / tpAvg, bwAvg / tpAvg, expectedItemDataSize.getAvg() / 100);
			assertEquals(bwLast / tpLast, bwLast / tpLast, expectedItemDataSize.getAvg() / 100);
			durAvg = Double.parseDouble(m.group("durAvg"));
			assertTrue(durAvg >= 0);
			durMin = Integer.parseInt(m.group("durMin"));
			assertTrue(durAvg >= durMin);
			durMax = Integer.parseInt(m.group("durMax"));
			assertTrue(durMax >= durAvg);
			latAvg = Double.parseDouble(m.group("latAvg"));
			assertTrue(latAvg >= 0);
			latMin = Integer.parseInt(m.group("latMin"));
			assertTrue(latAvg >= latMin);
			latMax = Integer.parseInt(m.group("latMax"));
			assertTrue(latMax >= latAvg);
		}
	}

	protected void testMetricsTableStdout(
		final String stdOutContent, final String stepName, final int driverCount,
		final long countLimit, final Map<IoType, Integer> configConcurrencyMap
	) throws Exception {

		final Matcher m = LogPatterns.STD_OUT_METRICS_TABLE_ROW.matcher(stdOutContent);
		boolean ioTypeFoundFlag;
		int rowCount = 0;

		while(m.find()) {
			rowCount ++;

			final String actualStepNameEnding = m.group("stepName");
			final Date nextTimstamp = FMT_DATE_METRICS_TABLE.parse(m.group("timestamp"));
			final IoType actualIoType = IoType.valueOf(m.group("ioType"));
			//final int configConcurrency = Integer.parseInt(m.group("concurrency"));
			//final int actualDriverCount = Integer.parseInt(m.group("driverCount"));
			final int actualConcurrencyCurr = Integer.parseInt(m.group("concurrencyCurr"));
			final float actualConcurrencyLastMean = Float.parseFloat(m.group("concurrencyLastMean"));
			final long succCount = Long.parseLong(m.group("succCount"));
			final long failCount = Long.parseLong(m.group("failCount"));
			final float stepTimeSec = Float.parseFloat(m.group("stepTime"));
			final float tp = Float.parseFloat(m.group("tp"));
			final float bw = Float.parseFloat(m.group("bw"));
			final long lat = Long.parseLong(m.group("lat"));
			final long dur = Long.parseLong(m.group("dur"));

			assertEquals(
				stepName.length() > 10 ? stepName.substring(stepName.length() - 10) : stepName,
				actualStepNameEnding
			);
			ioTypeFoundFlag = false;
			for(final IoType nextIoType : configConcurrencyMap.keySet()) {
				if(nextIoType.equals(actualIoType)) {
					ioTypeFoundFlag = true;
					break;
				}
			}
			assertTrue(
				"I/O type \"" + actualIoType + "\" was found but expected one of: " +
					Arrays.toString(configConcurrencyMap.keySet().toArray()), ioTypeFoundFlag
			);
			final int expectedConfigConcurrency = configConcurrencyMap.get(actualIoType);
			if(expectedConfigConcurrency > 0) {
				assertTrue(actualConcurrencyCurr <= driverCount * expectedConfigConcurrency);
				assertTrue(actualConcurrencyLastMean <= driverCount * expectedConfigConcurrency);
			} else {
				assertTrue(actualConcurrencyCurr >= 0);
				assertTrue(actualConcurrencyLastMean >= 0);
			}
			if(countLimit > 0) {
				assertTrue(countLimit >= succCount); // count succ
			}
			assertTrue(failCount == 0);
			assertTrue(stepTimeSec >= 0);
			assertTrue(tp >= 0);
			assertTrue(bw >= 0);
			assertTrue(lat >= 0);
			if(!storageType.equals(StorageType.FS)) {
				assertTrue(lat <= dur);
			}
		}

		assertTrue(rowCount > 0);
		
		final int tableHeaderCount =
			(stdOutContent.length() - stdOutContent.replaceAll(TABLE_HEADER, "").length())
				/ TABLE_HEADER.length();
		if(tableHeaderCount > 0) {
			assertTrue(rowCount / tableHeaderCount <= TABLE_HEADER_PERIOD);
		}
	}
}
