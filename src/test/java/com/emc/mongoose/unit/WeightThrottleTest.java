package com.emc.mongoose.unit;
import com.emc.mongoose.common.conf.enums.LoadType;
import com.emc.mongoose.core.api.io.task.IoTask;
import com.emc.mongoose.core.api.item.base.Item;
import com.emc.mongoose.core.api.load.model.Throttle;
import com.emc.mongoose.core.impl.load.model.WeightThrottle;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
/**
 Created by kurila on 29.03.16.
 */
public class WeightThrottleTest {

	private final Map<LoadType, Integer> weightMap = new HashMap<LoadType, Integer>() {
		{
			put(LoadType.CREATE, 80);
			put(LoadType.READ, 20);
		}
	};

	private final Map<LoadType, AtomicInteger> resultsMap = new HashMap<LoadType, AtomicInteger>() {
		{
			put(LoadType.CREATE, new AtomicInteger(0));
			put(LoadType.READ, new AtomicInteger(0));
		}
	};

	private final Throttle<LoadType> fc = new WeightThrottle<>(weightMap, new AtomicBoolean(false));

	private final class IoTaskMock
	implements IoTask {
		public LoadType loadType = null;
		@Override
		public String getNodeAddr() {
			return null;
		}
		@Override
		public Item getItem() {
			return null;
		}
		@Override
		public Status getStatus() {
			return null;
		}

		@Override
		public long getReqTimeStart() {
			return 0;
		}

		@Override
		public long getCountBytesDone() {
			return 0;
		}

		@Override
		public int getDuration() {
			return 0;
		}

		@Override
		public int getLatency() {
			return 0;
		}

		@Override
		public int getDataLatency() {
			return 0;
		}

		@Override
		public LoadType getLoadType() {
			return loadType;
		}
	}

	private final class SubmTask
	implements Runnable {
		private final LoadType loadType;
		public SubmTask(final LoadType loadType) {
			this.loadType = loadType;
		}
		@Override
		public final void run() {
			while(true) {
				try {
					final IoTaskMock ioTask = new IoTaskMock();
					ioTask.loadType = loadType;
					if(fc.requestContinueFor(loadType)) {
						resultsMap.get(loadType).incrementAndGet();
					}
				} catch(final InterruptedException e) {
					break;
				}
			}
		}
	}

	@Test
	public void testRequestApprovalFor()
	throws Exception {
		final ExecutorService es = Executors.newFixedThreadPool(2);
		es.submit(new SubmTask(LoadType.CREATE));
		es.submit(new SubmTask(LoadType.READ));
		es.awaitTermination(10, TimeUnit.SECONDS);
		es.shutdownNow();
		final double writes = resultsMap.get(LoadType.CREATE).get();
		final long reads = resultsMap.get(LoadType.READ).get();
		assertEquals(80/20, writes / reads, 0.01);
		System.out.println("Rate was: " + (writes + reads) / 10 + " per sec");
	}

	private final class BatchSubmTask
	implements Runnable {
		private final LoadType loadType;
		public BatchSubmTask(final LoadType loadType) {
			this.loadType = loadType;
		}
		@Override
		public final void run() {
			while(true) {
				try {
					final List<IoTask> ioTasks = new ArrayList<>();
					IoTaskMock ioTask;
					for(int i = 0; i < 128; i ++) {
						ioTask = new IoTaskMock();
						ioTask.loadType = loadType;
						ioTasks.add(ioTask);
					}
					if(fc.requestContinueFor(loadType, 128)) {
						resultsMap.get(loadType).incrementAndGet();
					}
				} catch(final InterruptedException e) {
					break;
				}
			}
		}
	}

	@Test
	public void testRequestBatchApprovalFor()
	throws Exception {
		final ExecutorService es = Executors.newFixedThreadPool(2);
		es.submit(new BatchSubmTask(LoadType.CREATE));
		es.submit(new BatchSubmTask(LoadType.READ));
		es.awaitTermination(10, TimeUnit.SECONDS);
		es.shutdownNow();
		final double writes = resultsMap.get(LoadType.CREATE).get();
		final long reads = resultsMap.get(LoadType.READ).get();
		assertEquals(80/20, writes / reads, 0.01);
		System.out.println("Rate was: " + 128 * (writes + reads) / 10 + " per sec");
	}
}
