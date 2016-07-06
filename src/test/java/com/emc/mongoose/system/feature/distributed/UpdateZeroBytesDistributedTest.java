package com.emc.mongoose.system.feature.distributed;
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.SizeInBytes;
import com.emc.mongoose.common.log.appenders.RunIdFileManager;
import com.emc.mongoose.core.api.item.data.HttpDataItem;
import com.emc.mongoose.core.impl.item.base.ListItemOutput;
import com.emc.mongoose.core.impl.item.base.ListItemInput;
import com.emc.mongoose.system.base.DistributedClientTestBase;
import com.emc.mongoose.util.client.api.StorageClient;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
/**
 Created by andrey on 16.10.15.
 */
public class UpdateZeroBytesDistributedTest
extends DistributedClientTestBase {
	//
	private final static int COUNT_TO_WRITE = 100, OBJ_SIZE = (int) SizeInBytes.toFixedSize("100KB");
	private final static String
		RUN_ID = UpdateZeroBytesDistributedTest.class.getCanonicalName(),
		BASE_URL = "http://127.0.0.1:9020/" + UpdateZeroBytesDistributedTest.class.getSimpleName() + "/";
	private final static List<HttpDataItem>
		OBJ_BUFF_WRITTEN = new ArrayList<>(COUNT_TO_WRITE),
		OBJ_BUFF_UPDATED = new ArrayList<>(COUNT_TO_WRITE);
	//
	private static int countWritten, countUpdated;
	//
	@BeforeClass
	public static void setUpClass()
	throws Exception {
		System.setProperty(AppConfig.KEY_RUN_ID, RUN_ID);
		System.setProperty(AppConfig.KEY_ITEM_DATA_CONTENT_FILE, "conf/content/zerobytes");
		DistributedClientTestBase.setUpClass();
		try(
			final StorageClient<HttpDataItem> client = CLIENT_BUILDER
				.setLimitTime(0, TimeUnit.SECONDS)
				.setLimitCount(COUNT_TO_WRITE)
				.setAPI("s3")
				.setDstContainer(UpdateZeroBytesDistributedTest.class.getSimpleName())
				.build()
		) {
			countWritten = (int) client.create(
				new ListItemOutput<>(OBJ_BUFF_WRITTEN), COUNT_TO_WRITE, 10, OBJ_SIZE
			);
			countUpdated = (int) client.update(
				new ListItemInput<>(OBJ_BUFF_WRITTEN), new ListItemOutput<>(OBJ_BUFF_UPDATED),
				countWritten, 10, 16
			);
			//
			RunIdFileManager.flushAll();
			TimeUnit.SECONDS.sleep(10);
		}
	}
	//
	@Test
	public void checkReturnedCount() {
		Assert.assertEquals(COUNT_TO_WRITE, countUpdated);
	}
	// zero bytes update has no effect as far as xorshift of 0-word returns 0-word
	@Test
	public void checkAllUpdatedContainsNonZeroBytes()
	throws Exception {
		Assert.assertEquals(COUNT_TO_WRITE, OBJ_BUFF_UPDATED.size());
		URL nextObjURL;
		final byte buff[] = new byte[OBJ_SIZE];
		HttpDataItem nextObj;
		for(int i = 0; i < OBJ_BUFF_UPDATED.size(); i ++) {
			nextObj = OBJ_BUFF_UPDATED.get(i);
			nextObjURL = new URL(BASE_URL + nextObj.getName());
			try(final BufferedInputStream in = new BufferedInputStream(nextObjURL.openStream())) {
				int n = 0, m;
				do {
					m = in.read(buff, n, OBJ_SIZE - n);
					if(m < 0) {
						throw new EOFException(
							"#" + i + ": unexpected end of stream @ offset " + n +
								" while reading the content from " + nextObjURL
						);
					} else {
						n += m;
					}
				} while(n < OBJ_SIZE);
				//
				boolean nonZeroByte = false;
				for(int j = 0; j < OBJ_SIZE; j ++) {
					nonZeroByte = buff[j] != 0;
					if(nonZeroByte) {
						break;
					}
				}
				Assert.assertTrue(
					"Non-zero bytes have not been found in the " + i +
						"th updated object: " + nextObj.toString(),
					nonZeroByte
				);
			}
		}
	}
}
