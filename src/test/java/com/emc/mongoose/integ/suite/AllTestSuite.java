package com.emc.mongoose.integ.suite;
/**
 Created by kurila on 14.08.15.
 */
import org.junit.runner.RunWith;
import org.junit.runners.Suite;
//
@RunWith(Suite.class)
@Suite.SuiteClasses({
	DistributedLoadTestSuite.class,
	StorageAdapterTestSuite.class,
	CoreTestSuite.class
})
public class AllTestSuite {
}
