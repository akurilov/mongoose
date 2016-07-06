package com.emc.mongoose.system.feature.core;
// mongoose-common.jar
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.BasicConfig;
import com.emc.mongoose.common.conf.Constants;
import com.emc.mongoose.common.conf.SizeInBytes;
import com.emc.mongoose.common.log.Markers;
//
import com.emc.mongoose.common.log.appenders.RunIdFileManager;
import com.emc.mongoose.system.tools.StdOutUtil;
import com.emc.mongoose.system.tools.ContentGetter;
//
import com.emc.mongoose.system.tools.TestConstants;
import com.emc.mongoose.system.tools.LogValidator;
import com.emc.mongoose.system.tools.BufferingOutputStream;
//
import com.emc.mongoose.system.base.ScenarioTestBase;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
//
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
//
/**
 * Created by olga on 30.06.15.
 * Covers TC #1 (name: "Write some data items.", steps: all) in Mongoose Core Functional Testing
 * HLUC: 1.1.1.1, 1.1.2.1, 1.3.1.1, 1.4.1.1, 1.5.3.1(1)
 */
public final class DefaultWriteTest
extends ScenarioTestBase {

	private static BufferingOutputStream STD_OUTPUT_STREAM;

	private static final int LIMIT_COUNT = 10;
	private static String RUN_ID = DefaultWriteTest.class.getCanonicalName();
	private static final String DATA_SIZE = "1MB";

	@BeforeClass
	public static void setUpClass() {
		System.setProperty(AppConfig.KEY_RUN_ID, RUN_ID);
		System.setProperty(AppConfig.KEY_ITEM_DATA_SIZE, DATA_SIZE);
		System.setProperty(AppConfig.KEY_LOAD_LIMIT_COUNT, Integer.toString(LIMIT_COUNT));
		System.setProperty(AppConfig.KEY_ITEM_DST_CONTAINER, RUN_ID);
		//
		ScenarioTestBase.setUpClass();
		final Logger logger = LogManager.getLogger();
		final AppConfig appConfig = BasicConfig.THREAD_CONTEXT.get();
		logger.info(Markers.MSG, appConfig.toString());
		//
		try(
			final BufferingOutputStream stdOutStream = StdOutUtil
				.getStdOutBufferingStream()
		) {
			//  Run mongoose default scenario in standalone mode
			SCENARIO_RUNNER.run();
			//  Wait for "Scenario end" message
			TimeUnit.SECONDS.sleep(10);
			STD_OUTPUT_STREAM = stdOutStream;
		} catch(final IOException e) {
			e.printStackTrace();
		} catch(final InterruptedException e) {
			e.printStackTrace();
		}
		//
		try {
			RunIdFileManager.flushAll();
		} catch(final IOException e) {
			e.printStackTrace();
		}
	}

	@AfterClass
	public  static void tearDownClass() {
		ScenarioTestBase.tearDownClass();
	}

	@Test
	public void checkConsoleSummaryMetrics()
	throws Exception {
		Assert.assertTrue("Console doesn't contain information about summary metrics",
			STD_OUTPUT_STREAM.toString().contains(TestConstants.SUMMARY_INDICATOR)
		);
		Assert.assertTrue("Console doesn't contain information about end of scenario",
			STD_OUTPUT_STREAM.toString().contains(TestConstants.SCENARIO_END_INDICATOR)
		);
	}

	@Test
	public void chekAllLogFilesExist()
	throws Exception {
		Path expectedFile = LogValidator.getMessageLogFile(RUN_ID).toPath();
		//  Check that messages.log exists
		Assert.assertTrue("messages.log file doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getPerfAvgFile(RUN_ID).toPath();
		//  Check that perf.avg.csv file exists
		Assert.assertTrue("perf.avg.csv file doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getPerfSumFile(RUN_ID).toPath();
		//  Check that perf.sum.csv file exists
		Assert.assertTrue("perf.sum.csv file doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getPerfTraceFile(RUN_ID).toPath();
		//  Check that perf.trace.csv file exists
		Assert.assertTrue("perf.trace.csv file doesn't exist", Files.exists(expectedFile));

		expectedFile = LogValidator.getItemsListFile(RUN_ID).toPath();
		//  Check that data.items.csv file exists
		Assert.assertTrue("items.csv file doesn't exist", Files.exists(expectedFile));
	}

	@Test
	public void checkConfigTable()
		throws Exception {
		final String configTable = BasicConfig.THREAD_CONTEXT.get().toString();
		final Set<String> params = new HashSet<>();
		//  skip table header
		int start = 126;
		int lineOffset = 100;
		while (start + lineOffset < configTable.length()) {
			params.add(configTable.substring(start, start + lineOffset));
			start += lineOffset;
		}
		for (final String confParam : params) {
			if (confParam.contains(AppConfig.KEY_LOAD_LIMIT_COUNT)) {
				Assert.assertTrue(
					"Information about limit count in configuration table is wrong",
					confParam.contains(String.valueOf(LIMIT_COUNT))
				);
			}
			if (confParam.contains(AppConfig.KEY_STORAGE_ADDRS)) {
				Assert.assertTrue(
					"Information about storage address in configuration table is wrong",
					confParam.contains("127.0.0.1")
				);
			}
			if (confParam.contains(AppConfig.KEY_RUN_MODE)) {
				Assert.assertTrue(
					"Information about run mode in configuration table is wrong",
					confParam.contains(Constants.RUN_MODE_STANDALONE)
				);
			}
			if (confParam.contains(AppConfig.KEY_RUN_ID)) {
				if (RUN_ID.length() >= 64) {
					Assert.assertTrue(
						"Information about run id in configuration table is wrong",
						confParam.contains(RUN_ID.substring(0, 63).trim())
					);
				} else {
					Assert.assertTrue(
						"Information about run id in configuration table is wrong",
						confParam.contains(RUN_ID)
					);
				}
			}
			if (confParam.contains(AppConfig.KEY_LOAD_LIMIT_TIME)) {
				Assert.assertTrue(
					"Information about limit time in configuration table is wrong",
					confParam.contains("0")
				);
			}
		}
	}

	@Test
	public void checkForScenarioEndMessage()
	throws Exception {
		//  Read the message file and search for "Scenario end"
		final File messageFile = LogValidator.getMessageLogFile(RUN_ID);
		Assert.assertTrue(
			"messages.log file doesn't exist",
			messageFile.exists()
		);
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
	public void checkWrittenCountInThePerfSumFile()
	throws Exception {
		//  Read perf.summary file
		final File perfSumFile = LogValidator.getPerfSumFile(RUN_ID);

		//  Check that file exists
		Assert.assertTrue("perf.sum.csv file doesn't exist", perfSumFile.exists());

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

	@Test
	public void checkItemsFileSizeRecords()
	throws Exception {
		//  Read data.items.csv file
		final File dataItemsFile = LogValidator.getItemsListFile(RUN_ID);
		Assert.assertTrue("data.items.csv file doesn't exist", dataItemsFile.exists());
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
	public void checkItemsWrittenContent()
	throws Exception {
		//  Read data.items.csv file
		final File itemsListFile = LogValidator.getItemsListFile(RUN_ID);
		Assert.assertTrue("items.csv file doesn't exist", itemsListFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(itemsListFile.toPath(), StandardCharsets.UTF_8)
		) {
			//
			final List<String> dataObjectChecksums = new ArrayList<>(LIMIT_COUNT);
			//
			final Iterable<CSVRecord> recIter = CSVFormat.RFC4180.parse(in);
			int recCount = 0;
			for(final CSVRecord nextRec : recIter) {
				recCount ++;
				try(
					final InputStream inputStream = ContentGetter.getStream(nextRec.get(0), RUN_ID)
				) {
					dataObjectChecksums.add(DigestUtils.md2Hex(inputStream));
				}
			}
			//  If size of set with checksums is less then dataCount
			//  it's mean that some checksums are equals
			Assert.assertEquals(
				"The count of the objects got from the storage mock should be " + LIMIT_COUNT,
				LIMIT_COUNT, dataObjectChecksums.size()
			);

		}
	}

	@Test
	public void checkWrittenItemsSize()
	throws Exception {
		//  Read data.items.csv file
		final File itemsFile = LogValidator.getItemsListFile(RUN_ID);
		Assert.assertTrue(itemsFile.toPath().toAbsolutePath() + " file doesn't exist", itemsFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(itemsFile.toPath(), StandardCharsets.UTF_8)
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
	public void checkItemsListFile()
	throws Exception {
		//  Get data.items.csv file
		final File dataItemFile = LogValidator.getItemsListFile(RUN_ID);
		Assert.assertTrue("data.items.csv file doesn't exist", dataItemFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(dataItemFile.toPath(), StandardCharsets.UTF_8)
		) {
			LogValidator.assertCorrectItemsCsv(in);
		}
	}

	@Test
	public void checkPerfSumFile()
	throws Exception {
		//  Get perf.sum.csv file
		final File perfSumFile = LogValidator.getPerfSumFile(RUN_ID);
		Assert.assertTrue("perf.sum.csv file doesn't exist", perfSumFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(perfSumFile.toPath(), StandardCharsets.UTF_8)
		) {
			LogValidator.assertCorrectPerfSumCSV(in);
		}
	}

	@Test
	public void checkPerfAvgFile()
	throws Exception {
		//  Get perf.avg.csv file
		final File perfAvgFile = LogValidator.getPerfAvgFile(RUN_ID);
		Assert.assertTrue("perfAvg.csv file doesn't exist", perfAvgFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(perfAvgFile.toPath(), StandardCharsets.UTF_8)
		) {
			LogValidator.assertCorrectPerfAvgCSV(in);
		}
	}

	@Test
	public void checkPerfTraceFile()
	throws Exception {
		//  Get perf.trace.csv file
		final File perfTraceFile = LogValidator.getPerfTraceFile(RUN_ID);
		Assert.assertTrue("perf.trace.csv file doesn't exist", perfTraceFile.exists());
		//
		try(
			final BufferedReader
				in = Files.newBufferedReader(perfTraceFile.toPath(), StandardCharsets.UTF_8)
		) {
			LogValidator.assertCorrectPerfTraceCSV(in);
		}
	}

	@Test
	public void checkNoDuplicateItemsLogged()
	throws Exception {
		final Set<String> items = new TreeSet<>();
		String nextLine;
		int lineNum = 0;
		try(
			final BufferedReader in = Files.newBufferedReader(
				LogValidator.getItemsListFile(RUN_ID).toPath(), StandardCharsets.UTF_8
			)
		) {
			while((nextLine = in.readLine()) != null) {
				if(!items.add(nextLine)) {
					Assert.fail("Duplicate item \"" + nextLine + "\" at line #" + lineNum);
				}
				lineNum ++;
			}
		}
	}
}
