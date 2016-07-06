package com.emc.mongoose.core.impl.load.tasks.processors;

import com.emc.mongoose.core.api.load.model.metrics.IoStats;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 Created on 29.06.16.
 */
@SuppressWarnings("WeakerAccess")
public class BasicPolylineManager {

	public static final Map<String, Map<String, BasicPolylineManager>> MANAGERS = new LinkedHashMap<>();
	protected final static double BYTES_PER_MBYTE = 1048576;

	private final Polyline durAvg, latAvg, tpAvg, bwAvg;

	public BasicPolylineManager() {
		durAvg = new Polyline();
		latAvg = new Polyline();
		tpAvg = new Polyline();
		bwAvg = new Polyline();
	}

	public final void updatePolylines(final int ordinate, final IoStats.Snapshot metricsSnapshot) {
		durAvg.addPoint(new Point(ordinate, metricsSnapshot.getDurationAvg()));
		latAvg.addPoint(new Point(ordinate, metricsSnapshot.getLatencyAvg()));
		tpAvg.addPoint(new Point(ordinate, metricsSnapshot.getSuccRateMean()));
		bwAvg.addPoint(new Point(ordinate, metricsSnapshot.getByteRateMean() / BYTES_PER_MBYTE));
	}

	protected final Polyline durAvg() {
		return durAvg;
	}

	protected final Polyline latAvg() {
		return latAvg;
	}

	protected final Polyline tpAvg() {
		return tpAvg;
	}

	protected final Polyline bwAvg() {
		return bwAvg;
	}

	public final List<Point> getBwAvgs() {
		return bwAvg.getPoints();
	}

	public final List<Point> getTpAvgs() {
		return tpAvg.getPoints();
	}

	public final List<Point> getLatAvgs() {
		return latAvg.getPoints();
	}

	public final List<Point> getDurAvgs() {
		return durAvg.getPoints();
	}
}
