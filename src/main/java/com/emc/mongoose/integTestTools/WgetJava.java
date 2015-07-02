package com.emc.mongoose.integTestTools;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.NoSuchAlgorithmException;

/**
 * Created by olga on 02.07.15.
 */
public final class WgetJava {


	public static InputStream getStream(final String dataID)
		throws IOException, NoSuchAlgorithmException {
		// There is url string w/o data ID
		String firstPartURLString = "http://localhost:9020/bucket/";
		URL url = new URL(firstPartURLString+dataID);
		return url.openStream();
	}

	public static int getDataSize(final String dataID)
		throws IOException, NoSuchAlgorithmException {
		byte[] buffer = new byte[1024];
		int numRead;
		int countByte = 0;

		try (InputStream inputStream = getStream(dataID)) {
			do {
				numRead = inputStream.read(buffer);
				if (numRead > 0) {
					countByte += numRead;
				}
			} while (numRead != -1);
		}
		return countByte;
	}
}
