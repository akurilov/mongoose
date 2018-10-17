package com.emc.mongoose;

import java.io.File;
import java.util.Locale;

/**
 Created on 11.07.16.
 */
public interface Constants {

	String APP_NAME = "mongoose";
	String USER_HOME = System.getProperty("user.home");
	String DIR_CONFIG = "config";
	String DIR_EXAMPLE = "example";
	String DIR_EXT = "ext";
	String DIR_EXAMPLE_SCENARIO = DIR_EXAMPLE + File.separator + "scenario";
	String PATH_DEFAULTS = DIR_CONFIG + "/" + "defaults.json";
	String KEY_HOME_DIR = "home_dir";
	String KEY_STEP_ID = "step_id";
	String KEY_CLASS_NAME = "class_name";
	//
	String METRIC_NAME_DUR = "DURATION";
	String METRIC_NAME_LAT = "LATENCY";
	String METRIC_NAME_CONC = "CONCURRENCY";
	String METRIC_NAME_SUCC = "SUCCESS";
	String METRIC_NAME_FAIL = "FAILS";
	String METRIC_NAME_BYTE = "BYTES";
	String METRIC_NAME_TIME = "ELAPSED_TIME";
	//
	String[] METRIC_LABELS = {
		"STEP_ID",
		"OP_TYPE",
		"CONCURRENCY",
		"NODE_COUNT",
		"ITEM_DATA_SIZE",
	};
	//
	int MIB = 0x10_00_00;
	double K = 1e3;
	double M = 1e6;
	Locale LOCALE_DEFAULT = Locale.ROOT;
}
