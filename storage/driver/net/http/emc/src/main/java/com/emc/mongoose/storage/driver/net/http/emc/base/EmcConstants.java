package com.emc.mongoose.storage.driver.net.http.emc.base;

/**
 Created by kurila on 11.11.16.
 */
public interface EmcConstants {
	
	String PREFIX_KEY_X_EMC = "x-emc-";
	String KEY_X_EMC_MULTIPART_COPY = PREFIX_KEY_X_EMC + "multipart-copy";
	String KEY_X_EMC_DATE = PREFIX_KEY_X_EMC + "date";
	String KEY_X_EMC_FILESYSTEM_ACCESS_ENABLED = PREFIX_KEY_X_EMC + "filesystem-access-enabled";
	String KEY_X_EMC_NAMESPACE = PREFIX_KEY_X_EMC + "namespace";
	String KEY_X_EMC_SIGNATURE = PREFIX_KEY_X_EMC + "signature";
	String KEY_X_EMC_SUBTENANT_ID = PREFIX_KEY_X_EMC + "subtenant-id";
	String KEY_X_EMC_UID = PREFIX_KEY_X_EMC + "uid";
}
