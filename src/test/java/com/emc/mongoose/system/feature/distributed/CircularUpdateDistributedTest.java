package com.emc.mongoose.system.feature.distributed;

import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.BasicConfig;
import com.emc.mongoose.common.conf.SizeInBytes;
import com.emc.mongoose.common.conf.enums.LoadType;
import com.emc.mongoose.common.io.Output;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.appenders.RunIdFileManager;
import com.emc.mongoose.core.api.item.data.HttpDataItem;
import com.emc.mongoose.core.impl.item.data.BasicHttpData;
import com.emc.mongoose.core.impl.item.base.CsvFileItemOutput;
import com.emc.mongoose.core.impl.item.data.ContentSourceUtil;
import com.emc.mongoose.system.base.DistributedClientTestBase;
import com.emc.mongoose.system.tools.StdOutUtil;
import com.emc.mongoose.system.tools.BufferingOutputStream;
import com.emc.mongoose.util.client.api.StorageClient;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;

import static com.emc.mongoose.system.tools.LogPatterns.CONSOLE_METRICS_AVG_CLIENT;
import static com.emc.mongoose.system.tools.LogPatterns.CONSOLE_METRICS_SUM_CLIENT;

/**
 * Created by gusakk on 19.11.15.
 */
public class CircularUpdateDistributedTest
extends DistributedClientTestBase {
	//
	private static final Logger LOG = LogManager.getLogger();
	//
	private static final int BATCH_SIZE = 100;
	private static final String DATA_SIZE = "128B";
	//
	private static final int WRITE_COUNT = 1234;
	private static final int UPDATE_COUNT = 24680;
	//
	private static final int COUNT_OF_UPDATES = 20;
	private static final int LAYER_NUMBER_INDEX = 2;
	//
	private static long COUNT_WRITTEN, COUNT_UPDATED;
	private static byte[] STD_OUT_CONTENT;
	//
	@BeforeClass
	public static void setUpClass() {
		try {
			System.setProperty(
				AppConfig.KEY_RUN_ID, CircularUpdateDistributedTest.class.getCanonicalName()
			);
			DistributedClientTestBase.setUpClass();
			//
			final AppConfig rtConfig = BasicConfig.THREAD_CONTEXT.get();
			rtConfig.setProperty(AppConfig.KEY_LOAD_CIRCULAR, true);
			rtConfig.setProperty(AppConfig.KEY_ITEM_SRC_BATCH_SIZE, BATCH_SIZE);
			//
			try (
				final StorageClient<HttpDataItem> client = CLIENT_BUILDER
					.setAPI("s3")
					.setLimitTime(0, TimeUnit.SECONDS)
					.setLimitCount(WRITE_COUNT)
					.build()
			) {
				final Output<HttpDataItem> writeOutput = new CsvFileItemOutput<HttpDataItem>(
					BasicHttpData.class, ContentSourceUtil.getDefaultInstance()
				);
				COUNT_WRITTEN = client.create(
					writeOutput, WRITE_COUNT, 10, SizeInBytes.toFixedSize(DATA_SIZE)
				);
				TimeUnit.SECONDS.sleep(10);
				RunIdFileManager.flushAll();
				//
				try (
					final BufferingOutputStream
						stdOutInterceptorStream = StdOutUtil.getStdOutBufferingStream()
				) {
					stdOutInterceptorStream.reset();
					if(COUNT_WRITTEN > 0) {
						COUNT_UPDATED = client.update(writeOutput.getInput(), null, UPDATE_COUNT, 10, 1);
					} else {
						throw new IllegalStateException("Failed to update");
					}
					TimeUnit.SECONDS.sleep(1);
					STD_OUT_CONTENT = stdOutInterceptorStream.toByteArray();
				}
			}
			//
			RunIdFileManager.flushAll();
		} catch(final Exception e) {
			LogUtil.exception(LOG, Level.ERROR, e, "Failed");
		}
	}
	//
	@Test
	public void checkUpdatedCount()
	throws Exception {
		Assert.assertEquals(COUNT_OF_UPDATES * COUNT_WRITTEN, COUNT_UPDATED, COUNT_UPDATED / 10);
	}
	//
	@Test
	public void checkLayerNumberIndex()
	throws Exception {
		try (
			final BufferedReader
				in = Files.newBufferedReader(FILE_LOG_DATA_ITEMS.toPath(), StandardCharsets.UTF_8)
		) {
			final Iterable<CSVRecord> recIter = CSVFormat.RFC4180.parse(in);
			for (final CSVRecord nextRec : recIter) {
				Assert.assertEquals(
					LAYER_NUMBER_INDEX, Integer.parseInt(nextRec.get(3).split("/")[0])
				);
			}
		}
	}
	//
	@Test
	public void checkConsoleAvgMetricsLogging()
	throws Exception {
		boolean passed = false;
		long lastSuccCount = 0;
		try(
			final BufferedReader in = new BufferedReader(
				new InputStreamReader(new ByteArrayInputStream(STD_OUT_CONTENT))
			)
		) {
			String nextStdOutLine;
			Matcher m;
			do {
				nextStdOutLine = in.readLine();
				if(nextStdOutLine == null) {
					break;
				} else {
					m = CONSOLE_METRICS_AVG_CLIENT.matcher(nextStdOutLine);
					if(m.find()) {
						Assert.assertTrue(
							"Load type is not " + LoadType.UPDATE.name() + ": " + m.group("typeLoad"),
							LoadType.UPDATE.name().equalsIgnoreCase(m.group("typeLoad"))
						);
						long
							nextSuccCount = Long.parseLong(m.group("countSucc")),
							nextFailCount = Long.parseLong(m.group("countFail"));
						Assert.assertTrue(
							"Next updated items count " + nextSuccCount +
								" is less than previous: " + lastSuccCount,
							nextSuccCount >= lastSuccCount
						);
						lastSuccCount = nextSuccCount;
						Assert.assertTrue("There are failures reported", nextFailCount == 0);
						passed = true;
					}
				}
			} while(true);
		}
		Assert.assertTrue(
			"Average metrics line matching the pattern was not met in the stdout", passed
		);
	}
	//
	@Test
	public void checkConsoleSumMetricsLogging()
	throws Exception {
		boolean passed = false;
		try(
			final BufferedReader in = new BufferedReader(
				new InputStreamReader(new ByteArrayInputStream(STD_OUT_CONTENT))
			)
		) {
			String nextStdOutLine;
			Matcher m;
			do {
				nextStdOutLine = in.readLine();
				if(nextStdOutLine == null) {
					break;
				} else {
					m = CONSOLE_METRICS_SUM_CLIENT.matcher(nextStdOutLine);
					if(m.find()) {
						Assert.assertTrue(
							"Load type is not " + LoadType.UPDATE.name() + ": " + m.group("typeLoad"),
							LoadType.UPDATE.name().equalsIgnoreCase(m.group("typeLoad"))
						);
						long countFail = Long.parseLong(m.group("countFail"));
						Assert.assertTrue("There are failures reported", countFail == 0);
						Assert.assertFalse("Summary metrics are printed twice at least", passed);
						passed = true;
					}
				}
			} while(true);
		}
		Assert.assertTrue(
			"Summary metrics line matching the pattern was not met in the stdout", passed
		);
	}
}
