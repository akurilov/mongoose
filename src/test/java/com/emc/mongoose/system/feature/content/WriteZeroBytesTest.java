package com.emc.mongoose.system.feature.content;
//
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.conf.SizeInBytes;
import com.emc.mongoose.common.log.appenders.RunIdFileManager;
import com.emc.mongoose.core.api.item.data.HttpDataItem;
import com.emc.mongoose.core.impl.item.base.ListItemOutput;
import com.emc.mongoose.system.base.StandaloneClientTestBase;
import com.emc.mongoose.util.client.api.StorageClient;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
/**
 Created by kurila on 16.10.15.
 */
public class WriteZeroBytesTest
extends StandaloneClientTestBase {
	//
	private final static int COUNT_TO_WRITE = 1000, OBJ_SIZE = (int) SizeInBytes.toFixedSize("10KB");
	private final static String
		RUN_ID = WriteZeroBytesTest.class.getCanonicalName(),
		BASE_URL = "http://127.0.0.1:9020/" + WriteZeroBytesTest.class.getSimpleName() + "/";
	private final static List<HttpDataItem> OBJ_BUFF = new ArrayList<>(COUNT_TO_WRITE);
	//
	private static long countWritten;
	//
	@BeforeClass
	public static void setUpClass()
		throws Exception {
		System.setProperty(AppConfig.KEY_RUN_ID, RUN_ID);
		System.setProperty(AppConfig.KEY_ITEM_DATA_CONTENT_FILE, "conf/content/zerobytes");
		StandaloneClientTestBase.setUpClass();
		try(
			final StorageClient<HttpDataItem> client = CLIENT_BUILDER
				.setLimitTime(0, TimeUnit.SECONDS)
				.setLimitCount(COUNT_TO_WRITE)
				.setAPI("s3")
				.setDstContainer(WriteZeroBytesTest.class.getSimpleName())
				.build()
		) {
			countWritten = client.create(
				new ListItemOutput<>(OBJ_BUFF), COUNT_TO_WRITE, 10, SizeInBytes.toFixedSize("10KB")
			);
			//
			RunIdFileManager.flushAll();
		}
	}
	//
	@Test
	public void checkReturnedCount() {
		Assert.assertEquals(COUNT_TO_WRITE, countWritten);
	}
	//
	@Test
	public void checkAllWrittenBytesAreZero()
	throws Exception {
		Assert.assertEquals(COUNT_TO_WRITE, OBJ_BUFF.size());
		URL nextObjURL;
		final byte buff[] = new byte[OBJ_SIZE];
		for(int i = 0; i < OBJ_BUFF.size(); i ++) {
			nextObjURL = new URL(BASE_URL + OBJ_BUFF.get(i).getName());
			try(final BufferedInputStream in = new BufferedInputStream(nextObjURL.openStream())) {
				int n = 0, m;
				do {
					m = in.read(buff, n, OBJ_SIZE - n);
					if(m < 0) {
						try(
							final BufferedInputStream listInput = new BufferedInputStream(
								new URL("http://127.0.0.1:9020/" + WriteZeroBytesTest.class.getSimpleName())
									.openStream()
							)
						) {
							final ByteArrayOutputStream baos = new ByteArrayOutputStream();
							do {
								m = listInput.read(buff);
								if(m < 0) {
									break;
								} else {
									baos.write(buff, 0, m);
								}
							} while(true);
							baos.writeTo(System.out);
						}
						throw new EOFException(
							"#" + i + ": unexpected end of stream @ offset " + n +
							" while reading the content from " + nextObjURL
						);
					} else {
						n += m;
					}
				} while(n < OBJ_SIZE);
				//
				for(int j = 0; j < OBJ_SIZE; j ++) {
					Assert.assertEquals(
						"Non-zero byte @ offset " + j + " in the content of " + nextObjURL,
						(byte) 0, buff[j]
					);
				}
			}
		}
	}
}
