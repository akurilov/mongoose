package com.emc.mongoose.system.feature.core;

import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.BasicConfig;
import com.emc.mongoose.common.conf.SizeInBytes;
import com.emc.mongoose.common.log.Markers;
import com.emc.mongoose.common.log.appenders.RunIdFileManager;
import com.emc.mongoose.system.base.LoggingTestBase;
import com.emc.mongoose.system.base.ScenarioTestBase;
import com.emc.mongoose.system.tools.StdOutUtil;
import com.emc.mongoose.system.tools.ContentGetter;
import com.emc.mongoose.system.tools.TestConstants;
import com.emc.mongoose.system.tools.LogValidator;
import com.emc.mongoose.system.tools.BufferingOutputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Created by olga on 07.07.15.
 * Covers TC #2(name: "Read back the data items written in the different run.", steps: 1-2 for data.size=200MB)
 * in Mongoose Core Functional Testing
 * HLUC: 1.1.2.6, 1.1.4.5, 1.1.5.4, 1.3.9.1
 */
public class Read200MBItemsTest
extends ScenarioTestBase {

	private static BufferingOutputStream STD_OUTPUT_STREAM;

	private static final int LIMIT_COUNT = 10;
	private static final String DATA_SIZE = "200MB";
	private static final String RUN_ID = Read200MBItemsTest.class.getCanonicalName();

	private static final String
		CREATE_RUN_ID = RUN_ID + TestConstants.LOAD_CREATE,
		READ_RUN_ID = RUN_ID + TestConstants.LOAD_READ;

	@BeforeClass
	public static void setUpClass() {
		System.setProperty(AppConfig.KEY_RUN_ID, CREATE_RUN_ID);
		ScenarioTestBase.setUpClass();
		//
		AppConfig appConfig = BasicConfig.THREAD_CONTEXT.get();
		appConfig.setProperty(AppConfig.KEY_LOAD_LIMIT_COUNT, Integer.toString(LIMIT_COUNT));
		appConfig.setProperty(AppConfig.KEY_ITEM_DATA_SIZE, DATA_SIZE);
		appConfig.setProperty(AppConfig.KEY_ITEM_DST_CONTAINER, RUN_ID);
		//
		final Logger logger = LogManager.getLogger();
		logger.info(Markers.MSG, BasicConfig.THREAD_CONTEXT.get().toString());
		//  write
		SCENARIO_RUNNER.run();
		//
		try {
			RunIdFileManager.flushAll();
			//
			appConfig.setRunId(READ_RUN_ID);
			appConfig = BasicConfig.THREAD_CONTEXT.get();
			appConfig.setProperty(
				AppConfig.KEY_ITEM_SRC_FILE, LogValidator.getItemsListFile(CREATE_RUN_ID).getPath()
			);
			appConfig.setProperty(AppConfig.KEY_LOAD_TYPE, TestConstants.LOAD_READ);
			appConfig.setProperty(AppConfig.KEY_ITEM_DST_CONTAINER, RUN_ID);
			//
			logger.info(Markers.MSG, BasicConfig.THREAD_CONTEXT.get().toString());
			//  read
			try(
				final BufferingOutputStream stdOutStream = StdOutUtil.getStdOutBufferingStream()
			) {
				SCENARIO_RUNNER.run();
				//  Wait for "Scenario end" message
				TimeUnit.SECONDS.sleep(10);
				STD_OUTPUT_STREAM = stdOutStream;
			}
			//
			RunIdFileManager.flushAll();
		} catch(final Exception e) {
			e.printStackTrace(System.err);
		}
	}

	@AfterClass
	public static void tearDownClass() {
		ScenarioTestBase.tearDownClass();
	}

	@Test
	public void shouldReportInformationAboutSummaryMetricsFromConsole()
	throws Exception {
		Assert.assertTrue(
			"Console doesn't contain information about summary metrics",
			STD_OUTPUT_STREAM.toString().contains(TestConstants.SUMMARY_INDICATOR)
		);
		Assert.assertTrue(
			"Console doesn't contain information about end of scenario",
			STD_OUTPUT_STREAM.toString().contains(TestConstants.SCENARIO_END_INDICATOR)
		);
	}

	@Test
	public void shouldCreateDataItemsFileWithInformationAboutAllObjects()
	throws Exception {
		//  Read data.items.csv file
		final File dataItemsFile = LogValidator.getItemsListFile(CREATE_RUN_ID);
		Assert.assertTrue(
			"data.items.csv file for create load doesn't exist", dataItemsFile.exists()
		);
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(dataItemsFile.toPath(), StandardCharsets.UTF_8)
		) {
			//
			int countDataItems = 0;
			final Iterable<CSVRecord> recIter = CSVFormat.RFC4180.parse(in);
			for(final CSVRecord nextRec : recIter) {
				Assert.assertEquals(
					"Size of data item isn't correct",
					Long.toString(SizeInBytes.toFixedSize(DATA_SIZE)), nextRec.get(2)
				);
				countDataItems++;
			}
			//  Check that there are 10 lines in data.items.csv file
			Assert.assertEquals(
				"Not correct information about created data items", LIMIT_COUNT, countDataItems
			);
		}
	}

	@Test
	public void shouldGetAllDataItemsFromServerAndDataSizeIsCorrect()
	throws Exception {
		//  Read data.items.csv file
		final File dataItemsFile = LogValidator.getItemsListFile(CREATE_RUN_ID);
		Assert.assertTrue("data.items.csv file doesn't exist", dataItemsFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(dataItemsFile.toPath(), StandardCharsets.UTF_8)
		) {
			int actualDataSize;
			//
			final Iterable<CSVRecord> recIter = CSVFormat.RFC4180.parse(in);
			for(final CSVRecord nextRec : recIter) {
				try {
					actualDataSize = ContentGetter.getDataSize(nextRec.get(0), RUN_ID);
					Assert.assertEquals(
						"Size of data item isn't correct", SizeInBytes.toFixedSize(DATA_SIZE), actualDataSize
					);
				} catch (final IOException e) {
					Assert.fail(String.format("Failed to get data item %s from server", nextRec.get(0)));
				}
			}
		}
	}

	@Test
	public void shouldReportScenarioEndToMessageLogFile()
	throws Exception {
		//  Read message file and search "Scenario End"
		final File messageFile = LogValidator.getMessageLogFile(READ_RUN_ID);
		Assert.assertTrue(messageFile.exists());
		//
		try (final BufferedReader bufferedReader =
				 new BufferedReader(new FileReader(messageFile))) {
			String line;
			while ((line = bufferedReader.readLine()) != null) {
				if (line.contains(TestConstants.SCENARIO_END_INDICATOR)) {
					break;
				}
			}
			Assert.assertNotNull(
				"Line with information about end of scenario must not be equal null ", line
			);
			Assert.assertTrue(
				"Information about end of scenario doesn't contain in message.log file",
				line.contains(TestConstants.SCENARIO_END_INDICATOR)
			);
		}
	}

	@Test
	public void shouldCreateAllFilesWithLogsAfterWriteScenario()
	throws Exception {
		Path expectedFile = LogValidator.getMessageLogFile(CREATE_RUN_ID).toPath();
		//  Check that messages.log exists
		Assert.assertTrue("messages.log file of create load doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getPerfAvgFile(CREATE_RUN_ID).toPath();
		//  Check that perf.avg.csv file exists
		Assert.assertTrue("perf.avg.csv file of create load doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getPerfSumFile(CREATE_RUN_ID).toPath();
		//  Check that perf.sum.csv file exists
		Assert.assertTrue("perf.sum.csv file of create load doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getPerfTraceFile(CREATE_RUN_ID).toPath();
		//  Check that perf.trace.csv file exists
		Assert.assertTrue("perf.trace.csv file of create load doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getItemsListFile(CREATE_RUN_ID).toPath();
		//  Check that data.items.csv file exists
		Assert.assertTrue("data.items.csv file of create load doesn't exist", Files.exists(expectedFile));
	}

	@Test
	public void shouldCreateAllFilesWithLogsAfterReadScenario()
	throws Exception {
		Path expectedFile = LogValidator.getMessageLogFile(READ_RUN_ID).toPath();
		//  Check that messages.log file is contained
		Assert.assertTrue("messages.log file of read load doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getPerfAvgFile(READ_RUN_ID).toPath();
		//  Check that perf.avg.csv file is contained
		Assert.assertTrue("perf.avg.csv file of read load doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getPerfSumFile(READ_RUN_ID).toPath();
		//  Check that perf.sum.csv file is contained
		Assert.assertTrue("perf.sum.csv file of read load doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getPerfTraceFile(READ_RUN_ID).toPath();
		//  Check that perf.trace.csv file is contained
		Assert.assertTrue("perf.trace.csv file of read load doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getItemsListFile(READ_RUN_ID).toPath();
		//  Check that data.items.csv file is contained
		Assert.assertTrue("data.items.csv file of read load doesn't exist", Files.exists(expectedFile));
	}

	@Test
	public void shouldCreateCorrectDataItemsFileAfterReadScenario()
	throws Exception {
		//  Get data.items.csv file of read scenario run
		final File readDataItemFile = LogValidator.getItemsListFile(READ_RUN_ID);
		Assert.assertTrue("data.items.csv file doesn't exist", readDataItemFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(readDataItemFile.toPath(), StandardCharsets.UTF_8)
		) {
			LogValidator.assertCorrectItemsCsv(in);
		}
	}

	@Test
	public void shouldCreateCorrectPerfSumFileAfterReadScenario()
	throws Exception {
		// Get perf.sum.csv file of read scenario run
		final File readPerfSumFile = LogValidator.getPerfSumFile(READ_RUN_ID);
		Assert.assertTrue("perf.sum.csv file doesn't exist", readPerfSumFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(readPerfSumFile.toPath(), StandardCharsets.UTF_8)
		) {
			LogValidator.assertCorrectPerfSumCSV(in);
		}
	}

	@Test
	public void shouldCreateCorrectPerfAvgFileAfterReadScenario()
	throws Exception {
		// Get perf.avg.csv file
		final File readPerfAvgFile = LogValidator.getPerfAvgFile(READ_RUN_ID);
		Assert.assertTrue("perfAvg.csv file doesn't exist", readPerfAvgFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(readPerfAvgFile.toPath(), StandardCharsets.UTF_8)
		) {
			LogValidator.assertCorrectPerfAvgCSV(in);
		}
	}

	@Test
	public void shouldCreateCorrectPerfTraceFileAfterReadScenario()
	throws Exception {
		// Get perf.trace.csv file
		final File readPerfTraceFile = LogValidator.getPerfTraceFile(READ_RUN_ID);
		Assert.assertTrue("perf.trace.csv file doesn't exist", readPerfTraceFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(readPerfTraceFile.toPath(), StandardCharsets.UTF_8)
		) {
			LogValidator.assertCorrectPerfTraceCSV(in);
		}
	}

	@Test
	public void shouldWriteAllDataItemsInCorrectSize()
	throws Exception {
		//  Read data.items.csv file
		final File dataItemsFile = LogValidator.getItemsListFile(CREATE_RUN_ID);
		Assert.assertTrue("data.items.csv file of create load doesn't exist", dataItemsFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(dataItemsFile.toPath(), StandardCharsets.UTF_8)
		) {
			//
			int countDataItems = 0;
			final Iterable<CSVRecord> recIter = CSVFormat.RFC4180.parse(in);
			for(final CSVRecord nextRec : recIter) {
				Assert.assertEquals(
					"Size of data item isn't correct",
					Long.toString(SizeInBytes.toFixedSize(DATA_SIZE)), nextRec.get(2)
				);
				countDataItems++;
			}
			//  Check that there are 10 lines in data.items.csv file
			Assert.assertEquals(
				"Not correct information about created data items", LIMIT_COUNT, countDataItems
			);
		}
	}

	@Test
	public void shouldReportCorrectCountOfReadObjectToSummaryLogFile()
	throws Exception {
		//  Read perf.summary file
		final File perfSumFile = LogValidator.getPerfSumFile(READ_RUN_ID);

		//  Check that file exists
		Assert.assertTrue("perf.sum.csv file of read load doesn't exist", perfSumFile.exists());

		try(
			final BufferedReader
				in = Files.newBufferedReader(perfSumFile.toPath(), StandardCharsets.UTF_8)
		) {
			boolean firstRow = true;
			//
			final Iterable<CSVRecord> recIter = CSVFormat.RFC4180.parse(in);
			for(final CSVRecord nextRec : recIter) {
				if (firstRow) {
					firstRow = false;
				} else if (nextRec.size() == 23) {
					Assert.assertTrue(
						"Count of success is not integer", LogValidator.isInteger(nextRec.get(7))
					);
					Assert.assertEquals(
						"Count of success isn't correct", Integer.toString(LIMIT_COUNT), nextRec.get(7)
					);
				}
			}
		}
	}
}
