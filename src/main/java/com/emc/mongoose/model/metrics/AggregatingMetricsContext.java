package com.emc.mongoose.model.metrics;

import com.codahale.metrics.Snapshot;
import com.codahale.metrics.UniformSnapshot;
import com.emc.mongoose.model.io.IoType;
import com.github.akurilov.commons.system.SizeInBytes;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class AggregatingMetricsContext
implements MetricsContext {

	private final long ts;
	private final String stepId;
	private final IoType ioType;
	private final int nodeCount;
	private final int concurrency;
	private final int thresholdConcurrency;
	private final SizeInBytes itemDataSize;
	private final boolean stdOutColorFlag;
	private final boolean avgPersistFlag;
	private final boolean sumPersistFlag;
	private final boolean perfDbResultsFileFlag;
	private final long outputPeriodMillis;
	private final Supplier<List<MetricsSnapshot>> snapshotsSupplier;

	private volatile long tsStart = -1, prevElapsedTime = 0;
	private volatile long lastOutputTs = 0;
	private volatile MetricsSnapshot lastSnapshot = null;
	private volatile MetricsListener metricsListener = null;
	private volatile MetricsContext thresholdMetricsCtx = null;
	private volatile boolean thresholdStateExitedFlag = false;

	public AggregatingMetricsContext(
		final String stepId, final IoType ioType, final int nodeCount, final int concurrency,
		final int thresholdConcurrency, final SizeInBytes itemDataSize, final int updateIntervalSec,
		final boolean stdOutColorFlag, final boolean avgPersistFlag, final boolean sumPersistFlag,
		final boolean perfDbResultsFileFlag, final Supplier<List<MetricsSnapshot>> snapshotsSupplier
	) {
		this.ts = System.nanoTime();
		this.stepId = stepId;
		this.ioType = ioType;
		this.nodeCount = nodeCount;
		this.concurrency = concurrency;
		this.thresholdConcurrency = thresholdConcurrency > 0 ?
			thresholdConcurrency : Integer.MAX_VALUE;
		this.itemDataSize = itemDataSize;
		this.snapshotsSupplier = snapshotsSupplier;

		this.stdOutColorFlag = stdOutColorFlag;
		this.avgPersistFlag = avgPersistFlag;
		this.sumPersistFlag = sumPersistFlag;
		this.perfDbResultsFileFlag = perfDbResultsFileFlag;
		this.outputPeriodMillis = TimeUnit.SECONDS.toMillis(updateIntervalSec);
	}

	@Override
	public void start() {
		tsStart = System.currentTimeMillis();
	}

	@Override
	public boolean isStarted() {
		return tsStart > -1;
	}

	@Override
	public void markSucc(final long size, final long duration, final long latency) {
	}

	@Override
	public void markPartSucc(final long size, final long duration, final long latency) {
	}

	@Override
	public void markSucc(
		final long count, final long bytes, final long[] durationValues, final long[] latencyValues
	) {
	}

	@Override
	public void markPartSucc(
		final long bytes, final long[] durationValues, final long[] latencyValues
	) {
	}

	@Override
	public void markFail() {
	}

	@Override
	public void markFail(final long count) {
	}

	@Override
	public void markElapsedTime(final long millis) {
	}

	@Override
	public String stepId() {
		return stepId;
	}

	@Override
	public IoType ioType() {
		return ioType;
	}

	@Override
	public int nodeCount() {
		return nodeCount;
	}

	@Override
	public int concurrency() {
		return concurrency;
	}

	@Override
	public int concurrencyThreshold() {
		return thresholdConcurrency;
	}

	@Override
	public int actualConcurrency() {
		return lastSnapshot().actualConcurrencyLast();
	}

	@Override
	public SizeInBytes itemDataSize() {
		return itemDataSize;
	}

	@Override
	public boolean stdOutColorEnabled() {
		return stdOutColorFlag;
	}

	@Override
	public boolean avgPersistEnabled() {
		return avgPersistFlag;
	}

	@Override
	public boolean sumPersistEnabled() {
		return sumPersistFlag;
	}

	@Override
	public boolean perfDbResultsFileEnabled() {
		return perfDbResultsFileFlag;
	}

	@Override
	public long outputPeriodMillis() {
		return outputPeriodMillis;
	}

	@Override
	public long lastOutputTs() {
		return lastOutputTs;
	}

	@Override
	public void lastOutputTs(final long ts) {
		this.lastOutputTs = ts;
	}

	@Override
	public void refreshLastSnapshot() {

		final List<MetricsSnapshot> snapshots = snapshotsSupplier.get();
		long countSucc = 0;
		double succRateLast = 0;
		long countFail = 0;
		double failRateLast = 0;
		long countByte = 0;
		double byteRateLast = 0;
		int actualConcurrencyLast = 0;
		double actualConcurrencyMean = 0;
		long sumDur = 0;
		int durValCount = 0;
		long sumLat = 0;
		int latValCount = 0;
		for(final MetricsSnapshot snapshot : snapshots) {
			countSucc += snapshot.succCount();
			succRateLast += snapshot.succRateLast();
			countFail += snapshot.failCount();
			failRateLast += snapshot.failRateLast();
			countByte += snapshot.byteCount();
			byteRateLast += snapshot.byteRateLast();
			actualConcurrencyLast += snapshot.actualConcurrencyLast();
			actualConcurrencyMean += snapshot.actualConcurrencyMean();
			sumDur += snapshot.durationSum();
			durValCount += snapshot.durationValues().length;
			sumLat += snapshot.latencySum();
			latValCount += snapshot.latencyValues().length;
		}
		final long[] allDurations = new long[durValCount];
		final long[] allLatencies = new long[latValCount];
		int lastDurIdx = 0;
		int lastLatIdx = 0;
		for(final MetricsSnapshot snapshot : snapshots) {
			for(final long dur : snapshot.durationValues()) {
				allDurations[lastDurIdx] = dur;
				lastDurIdx ++;
			}
			for(final long lat : snapshot.latencyValues()) {
				allLatencies[lastLatIdx] = lat;
				lastLatIdx ++;
			}
		}
		final Snapshot durSnapshot = new UniformSnapshot(allDurations);
		final Snapshot latSnapshot = new UniformSnapshot(allLatencies);
		final long currentTimeMillis = System.currentTimeMillis();
		final long currElapsedTime = tsStart > 0 ? currentTimeMillis - tsStart : 0;

		lastSnapshot = new BasicMetricsContext.BasicSnapshot(
			countSucc, succRateLast, countFail, failRateLast, countByte, byteRateLast,
			tsStart, prevElapsedTime + currElapsedTime, actualConcurrencyLast,
			actualConcurrencyMean, sumDur, sumLat, durSnapshot, latSnapshot
		);

		if(metricsListener != null) {
			metricsListener.notify(lastSnapshot);
		}
		if(thresholdMetricsCtx != null) {
			thresholdMetricsCtx.refreshLastSnapshot();
		}
	}

	@Override
	public MetricsSnapshot lastSnapshot() {
		if(lastSnapshot == null) {
			refreshLastSnapshot();
		}
		return lastSnapshot;
	}

	@Override
	public void metricsListener(final MetricsListener metricsListener) {
		this.metricsListener = metricsListener;
	}

	@Override
	public boolean thresholdStateEntered() {
		return thresholdMetricsCtx != null && thresholdMetricsCtx.isStarted();
	}

	@Override
	public void enterThresholdState()
	throws IllegalStateException {
		if(thresholdMetricsCtx != null) {
			throw new IllegalStateException("Nested metrics context already exists");
		}
		thresholdMetricsCtx = new AggregatingMetricsContext(
			stepId, ioType, nodeCount, concurrency, 0, itemDataSize,
			(int) TimeUnit.MILLISECONDS.toSeconds(outputPeriodMillis), stdOutColorFlag,
			avgPersistFlag, sumPersistFlag, perfDbResultsFileFlag, snapshotsSupplier
		);
		thresholdMetricsCtx.start();
	}

	@Override
	public boolean thresholdStateExited() {
		return thresholdStateExitedFlag;
	}

	@Override
	public MetricsContext thresholdMetrics() {
		if(thresholdMetricsCtx == null) {
			throw new IllegalStateException("Nested metrics context is not exist");
		}
		return thresholdMetricsCtx;
	}

	@Override
	public void exitThresholdState()
	throws IllegalStateException {
		if(thresholdMetricsCtx == null) {
			throw new IllegalStateException("Threshold state was not entered");
		}
		try {
			thresholdMetricsCtx.close();
		} catch(final IOException e) {
			e.printStackTrace(System.err);
		}
		thresholdStateExitedFlag = true;
	}

	@Override
	public final int hashCode() {
		return (int) ts;
	}

	@Override
	public final int compareTo(final MetricsContext other) {
		return Long.compare(hashCode(), other.hashCode());
	}

	@Override
	public void close() {
		prevElapsedTime = System.currentTimeMillis() - tsStart;
		tsStart = -1;
		lastSnapshot = null;
	}
}