package com.emc.mongoose.core.impl.load.model.metrics;
//
import com.codahale.metrics.MetricRegistry;
//
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
/**
 Created by kurila on 14.09.15.
 */
public class BasicIOStats
extends IOStatsBase {
	//
	protected final int updateIntervalSec;
	protected final AtomicLong reqDurationSum = new AtomicLong(0);
	//
	protected CustomMeter throughPutSucc, throughPutFail, reqBytes;
	//
	public BasicIOStats(
		final String name, final int serveJmxPort, final int updateIntervalSec
	) {
		super(name, serveJmxPort);
		this.updateIntervalSec = updateIntervalSec;
	}
	//
	@Override
	public void start() {
		// init load exec time dependent metrics
		throughPutSucc = metrics.register(
			MetricRegistry.name(name, METRIC_NAME_SUCC), new CustomMeter(clock, updateIntervalSec)
		);
		throughPutFail = metrics.register(
			MetricRegistry.name(name, METRIC_NAME_FAIL), new CustomMeter(clock, updateIntervalSec)
		);
		reqBytes = metrics.register(
			MetricRegistry.name(name, METRIC_NAME_BYTE), new CustomMeter(clock, updateIntervalSec)
		);
		//
		super.start();
	}
	//
	@Override
	public void markSucc(final long size, final int duration, final int latency) {
		throughPutSucc.mark();
		reqBytes.mark(size);
		reqDuration.update(duration);
		reqDurationSum.addAndGet(duration);
		respLatency.update(latency);
	}
	//
	@Override
	public void markSucc(
		final long count, final long bytes, final long durationValues[], final long latencyValues[]
	) {
		throughPutSucc.mark(count);
		reqBytes.mark(bytes);
		for(final long duration : durationValues) {
			reqDuration.update(duration);
			reqDurationSum.addAndGet(duration);
		}
		for(final long latency : latencyValues) {
			respLatency.update(latency);
		}
	}
	//
	@Override
	public void markFail() {
		throughPutFail.mark();
	}
	//
	@Override
	public void markFail(final long count) {
		throughPutFail.mark(count);
	}
	//
	@Override
	public Snapshot getSnapshot() {
		final long currElapsedTime = tsStartMicroSec > 0 ?
			TimeUnit.NANOSECONDS.toMicros(System.nanoTime()) - tsStartMicroSec : 0;
		return new BasicSnapshot(
			throughPutSucc == null ? 0 : throughPutSucc.getCount(),
			throughPutSucc == null ? 0 : throughPutSucc.getLastRate(),
			throughPutFail == null ? 0 : throughPutFail.getCount(),
			throughPutFail == null ? 0 : throughPutFail.getLastRate(),
			reqBytes == null ? 0 : reqBytes.getCount(),
			reqBytes == null ? 0 : reqBytes.getLastRate(),
			prevElapsedTimeMicroSec + currElapsedTime,
			reqDurationSum.get(), reqDuration.getSnapshot(), respLatency.getSnapshot()
		);
	}
}
