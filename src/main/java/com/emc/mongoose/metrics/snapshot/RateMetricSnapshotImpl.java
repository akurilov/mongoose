package com.emc.mongoose.metrics.snapshot;

import java.util.List;

/**
 @author veronika K. on 12.10.18 */
public class RateMetricSnapshotImpl
extends NamedCountMetricSnapshotImpl
implements RateMetricSnapshot {

	private final double last;
	private final double mean;
	private final long elapsedTimeMillis;

	public RateMetricSnapshotImpl(
		final double last, final double mean, final String metricName, final long count, final long elapsedTimeMillis
	) {
		super(metricName, count);
		this.last = last;
		this.mean = mean;
		this.elapsedTimeMillis = elapsedTimeMillis;
	}

	public static RateMetricSnapshot aggregate(final List<RateMetricSnapshot> snapshots) {
		final int snapshotsCount = snapshots.size();
		if(snapshotsCount == 1) {
			return snapshots.get(0);
		}
		double lastRateSum = 0;
		double meanRateSum = 0;
		long countSum = 0;
		long elapsedTimeMillisSum = 0;
		RateMetricSnapshot nextSnapshot;
		for(int i = 0; i < snapshotsCount; i++) {
			nextSnapshot = snapshots.get(i);
			countSum += nextSnapshot.count();
			lastRateSum += nextSnapshot.last();
			meanRateSum += nextSnapshot.mean();
			elapsedTimeMillisSum += nextSnapshot.elapsedTimeMillis();
		}
		return new RateMetricSnapshotImpl(
			lastRateSum / snapshotsCount, meanRateSum / snapshotsCount, snapshots.get(0).name(), countSum,
			elapsedTimeMillisSum / snapshotsCount
		);
	}

	@Override
	public final double last() {
		return last;
	}

	@Override
	public final long elapsedTimeMillis() {
		return elapsedTimeMillis;
	}

	@Override
	public final double mean() {
		return mean;
	}
}
