package com.emc.mongoose.system.feature.content;
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.SizeInBytes;
import com.emc.mongoose.common.log.appenders.RunIdFileManager;
import com.emc.mongoose.core.api.item.data.HttpDataItem;
import com.emc.mongoose.core.impl.item.base.ListItemOutput;
import com.emc.mongoose.core.impl.item.base.ListItemInput;
import com.emc.mongoose.system.base.StandaloneClientTestBase;
import com.emc.mongoose.system.tools.LogValidator;
import com.emc.mongoose.util.client.api.StorageClient;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
/**
 Created by kurila on 16.10.15.
 */
public class UpdateRikkiTikkiTaviTest
extends StandaloneClientTestBase {
	//
	private final static int COUNT_TO_WRITE = 1000, OBJ_SIZE = (int) SizeInBytes.toFixedSize("50KB");
	private final static String
		RUN_ID = UpdateRikkiTikkiTaviTest.class.getCanonicalName(),
		BASE_URL = "http://127.0.0.1:9020/" + UpdateRikkiTikkiTaviTest.class.getSimpleName() + "/";
	private final static List<HttpDataItem>
		OBJ_BUFF_WRITTEN = new ArrayList<>(COUNT_TO_WRITE),
		OBJ_BUFF_UPDATED = new ArrayList<>(COUNT_TO_WRITE),
		OBJ_BUFF_READ = new ArrayList<>(COUNT_TO_WRITE);
	//
	private static int countWritten, countUpdated, countRead;
	//
	@BeforeClass
	public static void setUpClass()
		throws Exception {
		System.setProperty(AppConfig.KEY_RUN_ID, RUN_ID);
		System.setProperty(AppConfig.KEY_ITEM_DATA_CONTENT_FILE, "conf/content/textexample");
		StandaloneClientTestBase.setUpClass();
		try(
			final StorageClient<HttpDataItem> client = CLIENT_BUILDER
				.setLimitTime(0, TimeUnit.SECONDS)
				.setLimitCount(COUNT_TO_WRITE)
				.setAPI("s3")
				.setDstContainer(UpdateRikkiTikkiTaviTest.class.getSimpleName())
				.build()
		) {
			countWritten = (int) client.create(
				new ListItemOutput<>(OBJ_BUFF_WRITTEN), COUNT_TO_WRITE, 10, OBJ_SIZE
			);
			countUpdated = (int) client.update(
				new ListItemInput<>(OBJ_BUFF_WRITTEN), new ListItemOutput<>(OBJ_BUFF_UPDATED),
				countWritten, 10, 16
			);
			countRead = (int) client.read(
				new ListItemInput<>(OBJ_BUFF_UPDATED), new ListItemOutput<>(OBJ_BUFF_READ),
				countUpdated, 10, true
			);
			//
			RunIdFileManager.flushAll();
		}
	}
	//
	@Test
	public void checkReturnedCount() {
		Assert.assertEquals(COUNT_TO_WRITE, countRead);
	}
	//
	@Test
	public void checkAllUpdatedAndVerifiedSuccessfully()
	throws Exception {
		try(
			final BufferedReader in = Files.newBufferedReader(
				LogValidator.getPerfTraceFile(RUN_ID).toPath(), StandardCharsets.US_ASCII
			)
		) {
			final Iterable<CSVRecord> recIter = CSVFormat.RFC4180.parse(in);
			boolean header = true;
			for(final CSVRecord nextRec : recIter) {
				if(header) {
					header = false;
				} else {
					if(OBJ_SIZE != Integer.parseInt(nextRec.get(4))) {
						Assert.fail(
							"The size of the read data " + nextRec.get(4) + " is not equal to " +
							OBJ_SIZE
						);
					}
					if(0 != Integer.parseInt(nextRec.get(5))) {
						Assert.fail("The status \"" + nextRec.get(5) + "\" is not successful");
					}
				}
			}
		}
	}
}

