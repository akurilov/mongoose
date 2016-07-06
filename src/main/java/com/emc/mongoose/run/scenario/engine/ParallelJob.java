package com.emc.mongoose.run.scenario.engine;
//
import com.emc.mongoose.common.concurrent.NamingThreadFactory;
import com.emc.mongoose.common.conf.AppConfig;
import com.emc.mongoose.common.log.LogUtil;
import com.emc.mongoose.common.log.Markers;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
/**
 Created by kurila on 02.02.16.
 */
public class ParallelJob
extends ParentJobBase {
	//
	private final static Logger LOG = LogManager.getLogger();
	//
	protected ParallelJob(final AppConfig appConfig, final Map<String, Object> subTree) {
		super(appConfig, subTree);
	}
	//
	@Override
	public final synchronized void run() {
		super.run();
		final ExecutorService parallelJobsExecutor = Executors.newFixedThreadPool(
			childJobs.size(), new NamingThreadFactory("jobWorker" + hashCode(), true)
		);
		for(final Job subJob : childJobs) {
			parallelJobsExecutor.submit(subJob);
		}
		LOG.debug(Markers.MSG, "{}: started {} child jobs", toString(), childJobs.size());
		parallelJobsExecutor.shutdown();
		final long limitTime = localConfig.getLoadLimitTime();
		try {
			if(limitTime > 0) {
				parallelJobsExecutor.awaitTermination(limitTime, TimeUnit.SECONDS);
			} else {
				parallelJobsExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.SECONDS);
			}
			LOG.debug(Markers.MSG, "{}: {} child jobs done", toString(), childJobs.size());
		} catch(final InterruptedException e) {
			LogUtil.exception(LOG, Level.WARN, e, "{}: interrupted the child jobs execution");
		}
	}
	//
	@Override
	public String toString() {
		return "parallelJob#" + hashCode();
	}
}
