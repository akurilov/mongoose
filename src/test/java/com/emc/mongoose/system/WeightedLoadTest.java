package com.emc.mongoose.system;

import com.emc.mongoose.config.BundledDefaultsProvider;
import com.emc.mongoose.item.op.OpType;
import com.emc.mongoose.params.Concurrency;
import com.emc.mongoose.params.EnvParams;
import com.emc.mongoose.params.ItemSize;
import com.emc.mongoose.params.RunMode;
import com.emc.mongoose.params.StorageType;
import com.emc.mongoose.util.DirWithManyFilesDeleter;
import com.emc.mongoose.util.docker.HttpStorageMockContainer;
import com.emc.mongoose.util.docker.MongooseContainer;
import com.emc.mongoose.util.docker.MongooseAdditionalNodeContainer;
import com.github.akurilov.commons.concurrent.AsyncRunnableBase;
import com.github.akurilov.confuse.Config;
import com.github.akurilov.confuse.SchemaProvider;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.emc.mongoose.Constants.APP_NAME;
import static com.emc.mongoose.config.CliArgUtil.ARG_PATH_SEP;
import static com.emc.mongoose.util.LogValidationUtil.testMetricsTableStdout;
import static com.emc.mongoose.util.TestCaseUtil.stepId;
import static com.emc.mongoose.util.docker.MongooseContainer.HOST_SHARE_PATH;
import static com.emc.mongoose.util.docker.MongooseContainer.systemTestContainerScenarioPath;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class) public class WeightedLoadTest {

	@Parameterized.Parameters(name = "{0}, {1}, {2}, {3}")
	public static List<Object[]> envParams() {
		return EnvParams.PARAMS;
	}

	private final String SCENARIO_PATH = systemTestContainerScenarioPath(getClass());
	private final long DURATION_LIMIT = 120_000;
	private final int timeoutInMillis = 120_000;
	private final Map<String, HttpStorageMockContainer> storageMocks = new HashMap<>();
	private final Map<String, MongooseAdditionalNodeContainer> slaveNodes = new HashMap<>();
	private final MongooseContainer testContainer;
	private final String stepId;
	private final StorageType storageType;
	private final RunMode runMode;
	private final Concurrency concurrency;
	private final ItemSize itemSize;
	private final Config config;
	private final String hostItemOutputFile = HOST_SHARE_PATH + "/" + getClass().getSimpleName() + ".csv";
	private long duration;
	private String stdOutContent = null;
	private final String hostItemOutputPath = MongooseContainer.getHostItemOutputPath(getClass().getSimpleName());

	public WeightedLoadTest(
		final StorageType storageType, final RunMode runMode, final Concurrency concurrency, final ItemSize itemSize
	)
	throws Exception {
		final Map<String, Object> schema =
			SchemaProvider.resolveAndReduce(APP_NAME, Thread.currentThread().getContextClassLoader());
		config = new BundledDefaultsProvider().config(ARG_PATH_SEP, schema);
		stepId = stepId(getClass(), storageType, runMode, concurrency, itemSize);
		try {
			FileUtils.deleteDirectory(Paths.get(MongooseContainer.HOST_LOG_PATH.toString(), stepId).toFile());
		} catch(final IOException ignored) {
		}
		this.storageType = storageType;
		this.runMode = runMode;
		this.concurrency = concurrency;
		this.itemSize = itemSize;
		try {
			Files.delete(Paths.get(hostItemOutputFile));
		} catch(final Exception ignored) {
		}

		final List<String> env = System
			.getenv()
			.entrySet()
			.stream()
			.map(e -> e.getKey() + "=" + e.getValue())
			.collect(Collectors.toList());
		env.add("ITEM_OUTPUT_PATH=" + stepId);

		final List<String> args = new ArrayList<>();
		//args.add("--storage-mock-capacity=10000000");
		switch(storageType) {
			case ATMOS:
			case S3:
			case SWIFT:
				final HttpStorageMockContainer storageMock =
					new HttpStorageMockContainer(HttpStorageMockContainer.DEFAULT_PORT, false, null, null,
						Character.MAX_RADIX, HttpStorageMockContainer.DEFAULT_CAPACITY,
						HttpStorageMockContainer.DEFAULT_CONTAINER_CAPACITY,
						HttpStorageMockContainer.DEFAULT_CONTAINER_COUNT_LIMIT,
						HttpStorageMockContainer.DEFAULT_FAIL_CONNECT_EVERY,
						HttpStorageMockContainer.DEFAULT_FAIL_RESPONSES_EVERY, 0
					);
				final String addr = "127.0.0.1:" + HttpStorageMockContainer.DEFAULT_PORT;
				storageMocks.put(addr, storageMock);
				args.add("--storage-net-node-addrs=" + storageMocks.keySet().stream().collect(Collectors.joining(",")));
				break;
			case FS:
				args.add("--item-output-path=" + hostItemOutputPath);
				try {
					DirWithManyFilesDeleter.deleteExternal(hostItemOutputPath);
				} catch(final Exception e) {
					e.printStackTrace(System.err);
				}
				break;
		}
		switch(runMode) {
			case DISTRIBUTED:
				for(int i = 1; i < runMode.getNodeCount(); i++) {
					final int port = MongooseAdditionalNodeContainer.DEFAULT_PORT + i;
					final MongooseAdditionalNodeContainer nodeSvc = new MongooseAdditionalNodeContainer(port);
					final String addr = "127.0.0.1:" + port;
					slaveNodes.put(addr, nodeSvc);
				}
				args.add("--load-step-node-addrs=" + slaveNodes.keySet().stream().collect(Collectors.joining(",")));
				break;
		}
		testContainer = new MongooseContainer(
			stepId, storageType, runMode, concurrency, itemSize.getValue(), SCENARIO_PATH, env, args
		);
	}

	@Before
	public final void setUp()
	throws Exception {
		storageMocks.values().forEach(AsyncRunnableBase::start);
		slaveNodes.values().forEach(AsyncRunnableBase::start);
		duration = System.currentTimeMillis();
		testContainer.start();
		testContainer.await(timeoutInMillis, TimeUnit.MILLISECONDS);
		duration = System.currentTimeMillis() - duration;
		stdOutContent = testContainer.stdOutContent();
	}

	@After
	public final void tearDown()
	throws Exception {
		testContainer.close();
		slaveNodes
			.values()
			.parallelStream()
			.forEach(
				storageMock -> {
					try {
						storageMock.close();
					} catch(final Throwable t) {
						t.printStackTrace(System.err);
					}
				}
			);
		storageMocks
			.values()
			.parallelStream()
			.forEach(
				storageMock -> {
					try {
						storageMock.close();
					} catch(final Throwable t) {
						t.printStackTrace(System.err);
					}
				}
			);
	}

	@Test
	public final void test()
	throws Exception {
		final Map<OpType, Integer> concurrencyMap = new HashMap<>();
		concurrencyMap.put(OpType.CREATE, concurrency.getValue());
		concurrencyMap.put(OpType.READ, concurrency.getValue());
		testMetricsTableStdout(stdOutContent, stepId, storageType, runMode.getNodeCount(), 0, concurrencyMap);
		assertTrue("Scenario didn't finished in time, actual duration: " + duration / 1_000,
			duration <= DURATION_LIMIT
		);
	}
}
