package com.emc.mongoose.system.util;

import com.emc.mongoose.item.op.OpType;
import com.emc.mongoose.item.op.Operation;
import com.emc.mongoose.system.base.params.StorageType;
import com.github.akurilov.commons.system.SizeInBytes;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Paths;
import java.text.ParseException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

import static com.emc.mongoose.Constants.MIB;
import static com.emc.mongoose.env.DateUtil.FMT_DATE_ISO8601;
import static com.emc.mongoose.env.DateUtil.FMT_DATE_METRICS_TABLE;
import static com.emc.mongoose.item.op.Operation.Status.INTERRUPTED;
import static com.emc.mongoose.item.op.Operation.Status.SUCC;
import static com.emc.mongoose.logging.MetricsAsciiTableLogMessage.TABLE_HEADER;
import static com.emc.mongoose.logging.MetricsAsciiTableLogMessage.TABLE_HEADER_PERIOD;
import static com.emc.mongoose.system.util.docker.MongooseContainer.APP_HOME_DIR;
import static org.junit.Assert.*;

public interface LogValidationUtil {

    int LOG_FILE_TIMEOUT_SEC = 15;

    static List<String> getLogFileLines(final String stepId, final String fileName)
            throws IOException {
        final File logFile = Paths.get(APP_HOME_DIR, "log", stepId, fileName).toFile();
        try (final BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            return br.lines().collect(Collectors.toList());
        }
    }

    static List<String> getContainerLogFileLines(final String stepId, final String fileName)
            throws IOException {
        final File logFile = Paths.get("share", "log", stepId, fileName).toFile();
        try (final BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            return br.lines().collect(Collectors.toList());
        }
    }

    static List<String> getMessageLogLines(final String stepId)
            throws IOException {
        return getLogFileLines(stepId, "messages.log");
    }

    static List<String> getContainerMessageLogLines(final String stepId)
            throws IOException {
        return getContainerLogFileLines(stepId, "messages.log");
    }

    static List<String> getErrorsLogLines(final String stepId)
            throws IOException {
        return getLogFileLines(stepId, "errors.log");
    }

    static List<String> getContainerErrorsLogLines(final String stepId)
            throws IOException {
        return getContainerLogFileLines(stepId, "errors.log");
    }

    static List<String> getConfigLogLines(final String stepId)
            throws IOException {
        return getLogFileLines(stepId, "config.log");
    }

    static List<String> getContainerConfigLogLines(final String stepId)
            throws IOException {
        return getContainerLogFileLines(stepId, "config.log");
    }

    static List<String> getPartsUploadLogLines(final String stepId)
            throws IOException {
        return getLogFileLines(stepId, "parts.upload.csv");
    }

    static List<String> getContainerPartsUploadLogLines(final String stepId)
            throws IOException {
        return getContainerLogFileLines(stepId, "parts.upload.csv");
    }

    static File getLogFile(final String stepId, final String fileName) {
        return Paths.get(APP_HOME_DIR, "log", stepId, fileName).toFile();
    }

    static File getContainerLogFile(final String stepId, final String fileName) {
        return Paths.get(APP_HOME_DIR, "share", "log", stepId, fileName).toFile();
    }

    static List<CSVRecord> waitAndGetLogFileCsvRecords(final File logFile)
            throws IOException {
        long prevSize = 1, nextSize;
        for (int t = 0; t < LOG_FILE_TIMEOUT_SEC; t++) {
            if (logFile.exists()) {
                nextSize = logFile.length();
                if (prevSize == nextSize) {
                    break;
                }
                prevSize = nextSize;
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (final InterruptedException e) {
                return null;
            }
        }
        try (final BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            try (final CSVParser csvParser = CSVFormat.RFC4180.withHeader().parse(br)) {
                final List<CSVRecord> csvRecords = new ArrayList<>();
                for (final CSVRecord csvRecord : csvParser) {
                    csvRecords.add(csvRecord);
                }
                return csvRecords;
            }
        }
    }

    static List<CSVRecord> getLogFileCsvRecords(final String stepId, final String fileName)
            throws IOException {
        final File logFile = getLogFile(stepId, fileName);
        return waitAndGetLogFileCsvRecords(logFile);
    }

    static List<CSVRecord> getContainerLogFileCsvRecords(final String stepId, final String fileName)
            throws IOException {
        final File logFile = getContainerLogFile(stepId, fileName);
        return waitAndGetLogFileCsvRecords(logFile);
    }

    static List<CSVRecord> getMetricsMedLogRecords(final String stepId)
            throws IOException {
        return getLogFileCsvRecords(stepId, "metrics.threshold.csv");
    }

    static List<CSVRecord> getContainerMetricsMedLogRecords(final String stepId)
            throws IOException {
        return getContainerLogFileCsvRecords(stepId, "metrics.threshold.csv");
    }

    static List<CSVRecord> getMetricsMedTotalLogRecords(final String stepId)
            throws IOException {
        return getLogFileCsvRecords(stepId, "metrics.threshold.total.csv");
    }

    static List<CSVRecord> getContainerMetricsMedTotalLogRecords(final String stepId)
            throws IOException {
        return getContainerLogFileCsvRecords(stepId, "metrics.threshold.total.csv");
    }

    static List<CSVRecord> getMetricsLogRecords(final String stepId)
            throws IOException {
        return getLogFileCsvRecords(stepId, "metrics.csv");
    }

    static List<CSVRecord> getContainerMetricsLogRecords(final String stepId)
            throws IOException {
        return getContainerLogFileCsvRecords(stepId, "metrics.csv");
    }

    static List<CSVRecord> getMetricsTotalLogRecords(final String stepId)
            throws IOException {
        return getLogFileCsvRecords(stepId, "metrics.total.csv");
    }

    static List<CSVRecord> getContainerMetricsTotalLogRecords(final String stepId)
            throws IOException {
        return getContainerLogFileCsvRecords(stepId, "metrics.total.csv");
    }

    static List<CSVRecord> getIoTraceLogRecords(final String stepId)
            throws IOException {
        return getLogFileCsvRecords(stepId, "op.trace.csv");
    }

    static List<CSVRecord> getContainerIoTraceLogRecords(final String stepId)
            throws IOException {
        return getContainerLogFileCsvRecords(stepId, "op.trace.csv");
    }

    static void waitLogFile(final File logFile) {
        long prevSize = 1, nextSize;
        for (int t = 0; t < LOG_FILE_TIMEOUT_SEC; t++) {
            if (logFile.exists()) {
                nextSize = logFile.length();
                if (prevSize == nextSize) {
                    break;
                }
                prevSize = nextSize;
            }
            try {
                TimeUnit.SECONDS.sleep(1);
            } catch (final InterruptedException e) {
                return;
            }
        }
    }

    static void testIoTraceLogFile(
            final File logFile, final Consumer<CSVRecord> csvRecordTestFunc
    ) throws IOException {
        try (final BufferedReader br = new BufferedReader(new FileReader(logFile))) {
            try (final CSVParser csvParser = CSVFormat.RFC4180.withHeader().parse(br)) {
                csvParser.forEach(csvRecordTestFunc);
            }
        }
    }

    static void testIoTraceLogRecords(
            final String stepId, final Consumer<CSVRecord> csvRecordTestFunc
    ) throws IOException {
        final File logFile = getLogFile(stepId, "op.trace.csv");
        waitLogFile(logFile);
        testIoTraceLogFile(logFile, csvRecordTestFunc);
    }

    static void testContainerIoTraceLogRecords(
            final String stepId, final Consumer<CSVRecord> csvRecordTestFunc
    ) throws IOException {
        final File logFile = getContainerLogFile(stepId, "op.trace.csv");
        waitLogFile(logFile);
        testIoTraceLogFile(logFile, csvRecordTestFunc);
    }

    static List<CSVRecord> getPartsUploadRecords(final String stepId)
            throws IOException {
        return getLogFileCsvRecords(stepId, "parts.upload.csv");
    }

    static List<CSVRecord> getContainerPartsUploadRecords(final String stepId)
            throws IOException {
        return getContainerLogFileCsvRecords(stepId, "parts.upload.csv");
    }

    static void testMetricsLogRecords(
            final List<CSVRecord> metrics, final OpType expectedOpType, final int expectedConcurrency,
            final int expectedNodeCount, final SizeInBytes expectedItemDataSize,
            final long expectedMaxCount, final int expectedLoadJobTime, final long metricsPeriodSec
    ) throws Exception {
        final int countRecords = metrics.size();
        if (expectedLoadJobTime > 0) {
            assertTrue(expectedLoadJobTime + metricsPeriodSec >= countRecords * metricsPeriodSec);
        }

        Date lastTimeStamp = null, nextDateTimeStamp;
        String ioTypeStr;
        int concurrencyLevel;
        int nodeCount;
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

        for (final CSVRecord nextRecord : metrics) {
            nextDateTimeStamp = FMT_DATE_ISO8601.parse(nextRecord.get("DateTimeISO8601"));
            if (lastTimeStamp != null) {
                assertEquals(
                        "Next metrics record is expected to be in " + metricsPeriodSec,
                        metricsPeriodSec,
                        (nextDateTimeStamp.getTime() - lastTimeStamp.getTime()) / 1000,
                        ((double) metricsPeriodSec) / 2
                );
            }
            lastTimeStamp = nextDateTimeStamp;
            ioTypeStr = nextRecord.get("OpType").toUpperCase();
            assertEquals(expectedOpType.name(), ioTypeStr);
            concurrencyLevel = Integer.parseInt(nextRecord.get("Concurrency"));
            assertEquals(
                    "Expected concurrency level: " + expectedNodeCount * expectedConcurrency,
                    expectedNodeCount * expectedConcurrency, concurrencyLevel
            );
            nodeCount = Integer.parseInt(nextRecord.get("NodeCount"));
            assertEquals("Expected driver count: " + nodeCount, expectedNodeCount, nodeCount);
            concurrencyCurr = Integer.parseInt(nextRecord.get("ConcurrencyCurr"));
            concurrencyMean = Double.parseDouble(nextRecord.get("ConcurrencyMean"));
            if (expectedConcurrency > 0) {
                assertTrue(concurrencyCurr <= nodeCount * expectedConcurrency);
                assertTrue(concurrencyMean <= nodeCount * expectedConcurrency);
            } else {
                assertTrue(concurrencyCurr >= 0);
                assertTrue(concurrencyMean >= 0);
            }
            totalBytes = SizeInBytes.toFixedSize(nextRecord.get("Size"));
            assertTrue(totalBytes >= 0);
            countSucc = Long.parseLong(nextRecord.get("CountSucc"));
            if (prevCountSucc == Long.MIN_VALUE) {
                assertTrue(Long.toString(countSucc), countSucc >= 0);
            } else {
                assertTrue(Long.toString(countSucc), countSucc >= prevCountSucc);
            }
            if (expectedMaxCount > 0) {
                assertTrue(
                        "Expected no more than " + expectedMaxCount + " results, bot got " + countSucc,
                        countSucc <= expectedMaxCount
                );
            }
            prevCountSucc = countSucc;
            countFail = Long.parseLong(nextRecord.get("CountFail"));
            assertTrue(Long.toString(countFail), countFail < 1);
            if (countSucc > 0) {
                avgItemSize = totalBytes / countSucc;
                if (expectedItemDataSize.getMin() < expectedItemDataSize.getMax()) {
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
            if (expectedLoadJobTime > 0) {
                assertTrue(
                        "Step duration limit (" + expectedLoadJobTime + ") is broken: " + jobDuration,
                        jobDuration <= expectedLoadJobTime + 1
                );
            }
            durationSum = Double.parseDouble(nextRecord.get("DurationSum[s]"));
            if (Double.isNaN(prevDurationSum)) {
                assertTrue(durationSum >= 0);
            } else {
                assertTrue(durationSum >= prevDurationSum);
                if (expectedConcurrency > 0 && jobDuration > 1) {
                    final double
                            effEstimate = durationSum / (nodeCount * expectedConcurrency * jobDuration);
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

    static void testTotalMetricsLogRecord(
            final CSVRecord metrics,
            final OpType expectedOpType, final int expectedConcurrency, final int expectedNodeCount,
            final SizeInBytes expectedItemDataSize, final long expectedMaxCount,
            final int expectedLoadJobTime
    ) throws Exception {
        try {
            FMT_DATE_ISO8601.parse(metrics.get("DateTimeISO8601"));
        } catch (final ParseException e) {
            fail(e.toString());
        }
        final String ioTypeStr = metrics.get("OpType").toUpperCase();
        assertEquals(ioTypeStr, expectedOpType.name(), ioTypeStr);
        final int concurrencyLevel = Integer.parseInt(metrics.get("Concurrency"));
        assertEquals(
                Integer.toString(concurrencyLevel), expectedNodeCount * expectedConcurrency,
                concurrencyLevel
        );
        final int nodeCount = Integer.parseInt(metrics.get("NodeCount"));
        assertEquals(Integer.toString(nodeCount), expectedNodeCount, nodeCount);
        final double concurrencyLastMean = Double.parseDouble(metrics.get("ConcurrencyMean"));
        if (expectedConcurrency > 0) {
            assertTrue(concurrencyLastMean <= nodeCount * expectedConcurrency);
        } else {
            assertTrue(concurrencyLastMean >= 0);
        }
        final long totalBytes = SizeInBytes.toFixedSize(metrics.get("Size"));
        if (
                expectedMaxCount > 0 && expectedItemDataSize.get() > 0 &&
                        (
                                OpType.CREATE.equals(expectedOpType) || OpType.READ.equals(expectedOpType) ||
                                        OpType.UPDATE.equals(expectedOpType)
                        )
                ) {
            assertTrue(Long.toString(totalBytes), totalBytes > 0);
        }
        final long countSucc = Long.parseLong(metrics.get("CountSucc"));
        if (expectedMaxCount > 0) {
            if (expectedLoadJobTime > 0) {
                assertTrue(expectedMaxCount > countSucc);
            } else {
                assertEquals(expectedMaxCount, countSucc);
            }
            assertTrue(Long.toString(countSucc), countSucc > 0);
        }
        final long countFail = Long.parseLong(metrics.get("CountFail"));
        assertTrue("Failures count: " + Long.toString(countFail), countFail < 1);
        if (countSucc > 0) {
            final long avgItemSize = totalBytes / countSucc;
            if (expectedItemDataSize.getMin() < expectedItemDataSize.getMax()) {
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
        if (expectedLoadJobTime > 0) {
            assertTrue(
                    "Step duration was " + jobDuration + ", but expected not more than" +
                            expectedLoadJobTime + 5, jobDuration <= expectedLoadJobTime + 5
            );
        }
        final double durationSum = Double.parseDouble(metrics.get("DurationSum[s]"));
        final double effEstimate = durationSum / (expectedConcurrency * expectedNodeCount * jobDuration);
        if (countSucc > 0 && expectedConcurrency > 0 && jobDuration > 1) {
            assertTrue(
                    "Invalid efficiency estimate: " + effEstimate + ", summary duration: " + durationSum
                            + ", concurrency limit: " + expectedConcurrency + ", driver count: "
                            + nodeCount + ", job duration: " + jobDuration,
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

    static void testIoTraceRecord(
            final CSVRecord ioTraceRecord, final int ioTypeCodeExpected, final SizeInBytes sizeExpected
    ) {
        assertEquals(ioTypeCodeExpected, Integer.parseInt(ioTraceRecord.get("OpTypeCode")));
        final int actualStatusCode = Integer.parseInt(ioTraceRecord.get("StatusCode"));
        if (INTERRUPTED.ordinal() == actualStatusCode) {
            return;
        }
        assertEquals(
                "Actual status code is " + Operation.Status.values()[actualStatusCode],
                SUCC.ordinal(), actualStatusCode
        );
        final long duration = Long.parseLong(ioTraceRecord.get("Duration[us]"));
        final String latencyStr = ioTraceRecord.get("RespLatency[us]");
        if (latencyStr != null && !latencyStr.isEmpty()) {
            assertTrue(duration >= Long.parseLong(latencyStr));
        }
        final long size = Long.parseLong(ioTraceRecord.get("TransferSize"));
        if (sizeExpected.getMin() < sizeExpected.getMax()) {
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

    static void testPartsUploadRecord(final List<CSVRecord> recs)
            throws Exception {
        String itemPath, uploadId;
        long respLatency;
        for (final CSVRecord rec : recs) {
            assertEquals(rec.size(), 3);
            itemPath = rec.get("ItemPath");
            assertNotNull(itemPath);
            uploadId = rec.get("UploadId");
            assertNotNull(uploadId);
            respLatency = Long.parseLong(rec.get("RespLatency[us]"));
            assertTrue(respLatency > 0);
        }
    }

    static void testSingleMetricsStdout(
            final String stdOutContent,
            final OpType expectedOpType, final int expectedConcurrency, final int expectedNodeCount,
            final SizeInBytes expectedItemDataSize, final long metricsPeriodSec
    ) throws Exception {
        Date lastTimeStamp = null, nextDateTimeStamp;
        String ioTypeStr;
        int concurrencyLevel;
        int nodeCount;
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
        while (m.find()) {
            nextDateTimeStamp = FMT_DATE_ISO8601.parse(m.group("dateTime"));
            if (lastTimeStamp != null) {
                assertEquals(
                        metricsPeriodSec,
                        (nextDateTimeStamp.getTime() - lastTimeStamp.getTime()) / 1000,
                        ((double) metricsPeriodSec) / 10
                );
            }
            lastTimeStamp = nextDateTimeStamp;
            ioTypeStr = m.group("typeLoad").toUpperCase();
            assertEquals(ioTypeStr, expectedOpType.name(), ioTypeStr);
            concurrencyLevel = Integer.parseInt(m.group("concurrency"));
            assertEquals(Integer.toString(concurrencyLevel), expectedConcurrency, concurrencyLevel);
            nodeCount = Integer.parseInt(m.group("nodeCount"));
            assertEquals(Integer.toString(nodeCount), expectedNodeCount, nodeCount);
            concurrencyMean = Double.parseDouble(m.group("concurrencyLastMean"));
            if (expectedConcurrency > 0) {
                assertTrue(concurrencyMean <= nodeCount * expectedConcurrency);
            } else {
                assertTrue(concurrencyMean >= 0);
            }
            totalBytes = SizeInBytes.toFixedSize(m.group("size"));
            if (prevTotalBytes == Long.MIN_VALUE) {
                assertTrue(Long.toString(totalBytes), totalBytes >= 0);
            } else {
                assertTrue(Long.toString(totalBytes), totalBytes >= prevTotalBytes);
            }
            prevTotalBytes = totalBytes;
            countSucc = Long.parseLong(m.group("countSucc"));
            if (prevCountSucc == Long.MIN_VALUE) {
                assertTrue(Long.toString(countSucc), countSucc >= 0);
            } else {
                assertTrue(Long.toString(countSucc), countSucc >= prevCountSucc);
            }
            prevCountSucc = countSucc;
            countFail = Long.parseLong(m.group("countFail"));
            assertTrue(Long.toString(countFail), countFail < 1);
            if (countSucc > 0) {
                avgItemSize = totalBytes / countSucc;
                assertEquals(
                        Long.toString(avgItemSize), expectedItemDataSize.getAvg(), avgItemSize,
                        expectedItemDataSize.getAvg() / 100
                );
            }
            jobDuration = Double.parseDouble(m.group("jobDur"));
            if (Double.isNaN(prevJobDuration)) {
                assertEquals(Double.toString(jobDuration), 0, jobDuration, 1);
            } else {
                assertEquals(
                        Double.toString(jobDuration), prevJobDuration + metricsPeriodSec, jobDuration, 1
                );
            }
            prevJobDuration = jobDuration;
            durationSum = Double.parseDouble(m.group("sumDur"));
            if (Double.isNaN(prevDurationSum)) {
                assertTrue(durationSum >= 0);
            } else {
                assertTrue(durationSum >= prevDurationSum);
            }
            final double
                    effEstimate = durationSum / (concurrencyLevel * nodeCount * jobDuration);
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

    static void testMetricsTableStdout(
            final String stdOutContent, final String stepId, final StorageType storageType,
            final int nodeCount, final long countLimit, final Map<OpType, Integer> configConcurrencyMap
    ) throws Exception {

        final Matcher m = LogPatterns.STD_OUT_METRICS_TABLE_ROW.matcher(stdOutContent);
        boolean ioTypeFoundFlag;
        int rowCount = 0;

        while (m.find()) {
            rowCount++;

            final String actualStepNameEnding = m.group("stepName");
            final Date nextTimstamp = FMT_DATE_METRICS_TABLE.parse(m.group("timestamp"));
            final OpType actualOpType = OpType.valueOf(m.group("opType"));
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
                    stepId.length() > 10 ? stepId.substring(stepId.length() - 10) : stepId,
                    actualStepNameEnding
            );
            ioTypeFoundFlag = false;
            for (final OpType nextOpType : configConcurrencyMap.keySet()) {
                if (nextOpType.equals(actualOpType)) {
                    ioTypeFoundFlag = true;
                    break;
                }
            }
            assertTrue(
                    "I/O type \"" + actualOpType + "\" was found but expected one of: " +
                            Arrays.toString(configConcurrencyMap.keySet().toArray()), ioTypeFoundFlag
            );
            final int expectedConfigConcurrency = configConcurrencyMap.get(actualOpType);
            if (expectedConfigConcurrency > 0) {
                assertTrue(actualConcurrencyCurr <= nodeCount * expectedConfigConcurrency);
                assertTrue(actualConcurrencyLastMean <= nodeCount * expectedConfigConcurrency);
            } else {
                assertTrue(actualConcurrencyCurr >= 0);
                assertTrue(actualConcurrencyLastMean >= 0);
            }
            if (countLimit > 0) {
                assertTrue(countLimit >= succCount); // count succ
            }
            assertTrue(failCount == 0);
            assertTrue(stepTimeSec >= 0);
            assertTrue(tp >= 0);
            assertTrue(bw >= 0);
            assertTrue(lat >= 0);
            if (!storageType.equals(StorageType.FS)) {
                assertTrue(lat <= dur);
            }
        }

        assertTrue(rowCount > 0);

        final int tableHeaderCount =
                (stdOutContent.length() - stdOutContent.replaceAll(TABLE_HEADER, "").length())
                        / TABLE_HEADER.length();
        if (tableHeaderCount > 0) {
            assertTrue(rowCount / tableHeaderCount <= TABLE_HEADER_PERIOD);
        }
    }

    static void testFinalMetricsTableRowStdout(
            final String stdOutContent, final String stepId, final OpType expectedOpType,
            final int nodeCount, final int expectedConcurrency, final long countLimit,
            final long timeLimit, final SizeInBytes expectedItemDataSize
    ) throws Exception {

        final Matcher m = LogPatterns.STD_OUT_METRICS_TABLE_ROW_FINAL.matcher(stdOutContent);
        boolean rowFoundFlag = false;
        Date nextTimstamp = null;
        int actualConcurrencyCurr = -1;
        float actualConcurrencyLastMean = -1;
        long succCount = -1;
        long failCount = -1;
        float stepTimeSec = -1;
        float tp = -1;
        float bw = -1;
        long lat = 0;
        long dur = -1;

        while (m.find()) {
            final String actualStepNameEnding = m.group("stepName");
            if (stepId.endsWith(actualStepNameEnding) || stepId.equals(actualStepNameEnding)) {
                final OpType actualOpType = OpType.valueOf(m.group("opType"));
                if (actualOpType.equals(expectedOpType)) {
                    rowFoundFlag = true;
                    nextTimstamp = FMT_DATE_METRICS_TABLE.parse(m.group("timestamp"));
                    actualConcurrencyCurr = Integer.parseInt(m.group("concurrencyCurr"));
                    actualConcurrencyLastMean = Float.parseFloat(m.group("concurrencyLastMean"));
                    succCount = Long.parseLong(m.group("succCount"));
                    failCount = Long.parseLong(m.group("failCount"));
                    stepTimeSec = Float.parseFloat(m.group("stepTime"));
                    tp = Float.parseFloat(m.group("tp"));
                    bw = Float.parseFloat(m.group("bw"));
                    lat = Long.parseLong(m.group("lat"));
                    dur = Long.parseLong(m.group("dur"));
                    break;
                }
            }
        }

        assertTrue(
                "Summary metrics row with step id ending with \"" + stepId + "\" and I/O type \""
                        + expectedOpType + "\" was not found",
                rowFoundFlag
        );
        assertTrue(actualConcurrencyCurr >= 0);
        assertTrue(nodeCount * expectedConcurrency >= actualConcurrencyCurr);
        assertTrue(actualConcurrencyLastMean >= 0);
        assertTrue(nodeCount * expectedConcurrency >= actualConcurrencyLastMean);
        assertTrue("Successful operations count should be > 0", succCount > 0);
        assertEquals("Failure count should be 0", failCount, 0);
        assertTrue("Step time should be > 0", stepTimeSec > 0);
        if (timeLimit > 0) {
            assertTrue(
                    "Step time (" + stepTimeSec + ") should not be much more than time limit ("
                            + timeLimit + ")",
                    stepTimeSec <= timeLimit + 10
            );
        }
        assertTrue("Final throughput should be >= 0", tp >= 0);
        if (expectedItemDataSize != null && tp > 0) {
            final float avgItemSize = MIB * bw / tp;
            if (expectedItemDataSize.getMin() == expectedItemDataSize.getMax()) {
                assertEquals(
                        "Actual average items size (" + new SizeInBytes((long) avgItemSize)
                                + ") should be approx equal the expected (" + expectedItemDataSize + ")",
                        expectedItemDataSize.get(), avgItemSize, expectedItemDataSize.get() / 10
                );
            } else {
                assertTrue(
                        "Actual average items size (" + new SizeInBytes((long) avgItemSize)
                                + ") doesn't fit the expected (" + expectedItemDataSize + ")",
                        avgItemSize >= expectedItemDataSize.getMin()
                );
                assertTrue(
                        "Actual average items size (" + new SizeInBytes((long) avgItemSize)
                                + ") doesn't fit the expected (" + expectedItemDataSize + ")",
                        avgItemSize <= expectedItemDataSize.getMax()
                );
            }
        }
        assertTrue(
                "Mean latency (" + lat + ") should not be more than mean duration (" + dur + ")",
                lat <= dur
        );
    }
}
