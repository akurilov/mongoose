package com.emc.mongoose.system.feature.filesystem;
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.SizeInBytes;
import com.emc.mongoose.common.log.appenders.RunIdFileManager;
import com.emc.mongoose.core.api.item.data.FileItem;
import com.emc.mongoose.core.impl.item.base.ListItemOutput;
import com.emc.mongoose.core.impl.item.base.ListItemInput;
import com.emc.mongoose.system.base.FileSystemTestBase;
import com.emc.mongoose.system.tools.LogValidator;
import com.emc.mongoose.util.client.api.StorageClient;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
/**
 Created by kurila on 04.12.15.
 */
public class UpdateAndVerifyTest
extends FileSystemTestBase {
	//
	private final static int COUNT_TO_WRITE = 1000;
	private final static String RUN_ID = UpdateAndVerifyTest.class.getCanonicalName();
	//
	private static long countWritten, countUpdated, countRead;
	//
	@BeforeClass
	public static void setUpClass()
	throws Exception {
		System.setProperty(AppConfig.KEY_RUN_ID, RUN_ID);
		System.setProperty(AppConfig.KEY_ITEM_DST_CONTAINER, "/tmp/" + RUN_ID);
		//System.setProperty(AppConfig.KEY_ITEM_DATA_CONTENT_FILE, "conf/content/zerobytes");
		FileSystemTestBase.setUpClass();
		final List<FileItem>
			itemBuffWritten = new ArrayList<>(COUNT_TO_WRITE),
			itemBuffUpdated = new ArrayList<>(COUNT_TO_WRITE);
		try(
			final StorageClient<FileItem> client = CLIENT_BUILDER
				.setLimitTime(0, TimeUnit.SECONDS)
				.setLimitCount(COUNT_TO_WRITE)
				.setStorageType("fs")
				.build()
		) {
			countWritten = client.create(
				new ListItemOutput<>(itemBuffWritten), COUNT_TO_WRITE, 10, SizeInBytes.toFixedSize("10B")
			);
			TimeUnit.SECONDS.sleep(10);
			countUpdated = client.update(
				new ListItemInput<>(itemBuffWritten), new ListItemOutput<>(itemBuffUpdated),
				countWritten, 1, 1
			);
			TimeUnit.SECONDS.sleep(10);
			countRead = client.read(
				new ListItemInput<>(itemBuffUpdated), null, countUpdated, 10, true
			);
			RunIdFileManager.flushAll();
			TimeUnit.SECONDS.sleep(10);
		}
	}
	//
	@AfterClass
	public static void tearDownClass()
	throws Exception {
		FileSystemTestBase.tearDownClass();
		final File tgtDir = Paths.get("/tmp/" + RUN_ID).toFile();
		for(final File f : tgtDir.listFiles()) {
			f.delete();
		}
		tgtDir.delete();
	}
	//
	@Test
	public void checkReturnedCount() {
		Assert.assertEquals(COUNT_TO_WRITE, countWritten);
		Assert.assertEquals(countWritten, countRead);
		Assert.assertEquals(countRead, countUpdated);
	}
	//
	@Test
	public void checkLoggedItemsCount()
	throws Exception {
		int itemsCount = 0;
		try(
			final BufferedReader in = Files.newBufferedReader(
				LogValidator.getItemsListFile(RUN_ID).toPath(), StandardCharsets.UTF_8
			)
		) {
			while(in.readLine() != null) {
				itemsCount ++;
			}
		}
		Assert.assertEquals(
			"Expected " + countRead + " in the output CSV file, but got " + itemsCount,
			itemsCount, countRead
		);
	}
	//
	@Test
	public void checkNoReadFailures() {
		try(
			final CSVParser csvParser = new CSVParser(
				Files.newBufferedReader(
					LogValidator.getPerfTraceFile(RUN_ID).toPath(), StandardCharsets.UTF_8
				),
				CSVFormat.RFC4180
			)
		) {
			String status;
			boolean firstRow = true;
			for(final CSVRecord csvRec : csvParser) {
				status = csvRec.get(5);
				if(firstRow) {
					firstRow = false;
					continue;
				}
				Assert.assertEquals(0, Integer.valueOf(status).intValue());
			}
		} catch(final Exception e) {
			Assert.fail(e.toString());
		}
	}
}
