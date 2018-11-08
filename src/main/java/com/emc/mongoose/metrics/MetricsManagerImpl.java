package com.emc.mongoose.metrics;

import com.emc.mongoose.exception.InterruptRunException;
import com.emc.mongoose.logging.ExtResultsXmlLogMessage;
import com.emc.mongoose.logging.LogUtil;
import com.emc.mongoose.logging.Loggers;
import com.emc.mongoose.logging.MetricsAsciiTableLogMessage;
import com.emc.mongoose.logging.MetricsCsvLogMessage;
import com.emc.mongoose.logging.StepResultsMetricsLogMessage;
import com.emc.mongoose.metrics.context.DistributedMetricsContext;
import com.emc.mongoose.metrics.context.MetricsContext;
import com.emc.mongoose.metrics.snapshot.AllMetricsSnapshot;
import com.emc.mongoose.metrics.snapshot.ConcurrencyMetricSnapshot;
import com.emc.mongoose.metrics.snapshot.DistributedAllMetricsSnapshot;
import com.emc.mongoose.metrics.util.PrometheusMetricsExporter;
import com.emc.mongoose.metrics.util.PrometheusMetricsExporterImpl;
import com.github.akurilov.fiber4j.ExclusiveFiberBase;
import com.github.akurilov.fiber4j.Fiber;
import com.github.akurilov.fiber4j.FibersExecutor;
import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.ThreadContext;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.emc.mongoose.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.Constants.KEY_STEP_ID;
import static com.emc.mongoose.metrics.MetricsConstants.METRIC_LABELS;
import static org.apache.logging.log4j.CloseableThreadContext.Instance;
import static org.apache.logging.log4j.CloseableThreadContext.put;

/**
 Created by kurila on 18.05.17.
 */
public class MetricsManagerImpl
	extends ExclusiveFiberBase
	implements MetricsManager {

	private static final String CLS_NAME = MetricsManagerImpl.class.getSimpleName();
	private final Set<MetricsContext> allMetrics = new ConcurrentSkipListSet<>();
	private final Map<MetricsContext, PrometheusMetricsExporter> exportedMetrics = new ConcurrentHashMap<>();
	private final Set<MetricsContext> selectedMetrics = new TreeSet<>();
	private final Lock outputLock = new ReentrantLock();

	public MetricsManagerImpl(final FibersExecutor instance) {
		super(instance);
	}

	@Override
	protected final void invokeTimedExclusively(final long startTimeNanos) {
		ThreadContext.put(KEY_CLASS_NAME, CLS_NAME);
		int actualConcurrency = 0;
		int nextConcurrencyThreshold;
		if(outputLock.tryLock()) {
			try {
				for(final MetricsContext metricsCtx : allMetrics) {
					ThreadContext.put(KEY_STEP_ID, metricsCtx.id());
					metricsCtx.refreshLastSnapshot();
					final AllMetricsSnapshot snapshot = metricsCtx.lastSnapshot();
					if(snapshot != null) {
						final ConcurrencyMetricSnapshot concurrencySnapshot = snapshot.concurrencySnapshot();
						if(concurrencySnapshot != null) {
							actualConcurrency = (int) concurrencySnapshot.last();
						}
						// threshold load state checks
						nextConcurrencyThreshold = metricsCtx.concurrencyThreshold();
						if(nextConcurrencyThreshold > 0 && actualConcurrency >= nextConcurrencyThreshold) {
							if(! metricsCtx.thresholdStateEntered() && ! metricsCtx.thresholdStateExited()) {
								Loggers.MSG.info(
									"{}: the threshold of {} active load operations count is reached, " +
										"starting the additional metrics accounting",
									metricsCtx.toString(), metricsCtx.concurrencyThreshold()
								);
								metricsCtx.enterThresholdState();
							}
						} else if(metricsCtx.thresholdStateEntered() && ! metricsCtx.thresholdStateExited()) {
							exitMetricsThresholdState(metricsCtx);
						}
						// periodic output
						final long outputPeriodMillis = metricsCtx.outputPeriodMillis();
						final long lastOutputTs = metricsCtx.lastOutputTs();
						final long nextOutputTs = System.currentTimeMillis();
						if(outputPeriodMillis > 0 && nextOutputTs - lastOutputTs >= outputPeriodMillis) {
							selectedMetrics.add(metricsCtx);
							metricsCtx.lastOutputTs(nextOutputTs);
							if(metricsCtx.avgPersistEnabled()) {
								Loggers.METRICS_FILE.info(
									new MetricsCsvLogMessage(
										snapshot, metricsCtx.opType(), metricsCtx.concurrencyLimit()
									)
								);
							}
						}
					}
				}
				// console output
				if(! selectedMetrics.isEmpty()) {
					Loggers.METRICS_STD_OUT.info(new MetricsAsciiTableLogMessage(selectedMetrics));
					selectedMetrics.clear();
				}
			} catch(final ConcurrentModificationException ignored) {
			} catch(final Throwable cause) {
				LogUtil.exception(Level.DEBUG, cause, "Metrics manager failure");
			} finally {
				outputLock.unlock();
			}
		}
	}

	public void startIfNotStarted() {
		if(! isStarted()) {
			super.start();
			Loggers.MSG.debug("Started the metrics manager fiber");
		}
	}

	@Override
	public void register(final MetricsContext metricsCtx) {
		try {
			startIfNotStarted();
			allMetrics.add(metricsCtx);
			final String[] labelValues = {
				metricsCtx.id(),
				metricsCtx.opType().name(),
				String.valueOf(metricsCtx.concurrencyLimit()),
				String.valueOf((metricsCtx instanceof DistributedMetricsContext)
							   ? ((DistributedMetricsContext) metricsCtx).nodeCount()
							   : 1),
				metricsCtx.itemDataSize().toString()
			};
			exportedMetrics.put(
				metricsCtx,
				new PrometheusMetricsExporterImpl(metricsCtx)
					.labels(METRIC_LABELS, labelValues)
					.quantiles(metricsCtx.quantileValues())
					.register()
			);
			Loggers.MSG.debug("Metrics context \"{}\" registered", metricsCtx);
		} catch(final Exception e) {
			LogUtil.exception(
				Level.WARN, e, "Failed to register the Prometheus Exporter for the metrics context \"{}\"",
				metricsCtx.toString()
			);
		}
	}

	@Override
	public void unregister(final MetricsContext metricsCtx) {
		try(
			final Instance logCtx = put(KEY_STEP_ID, metricsCtx.id())
				.put(KEY_CLASS_NAME, getClass().getSimpleName())
		) {
			if(allMetrics.remove(metricsCtx)) {
				try {
					if(! outputLock.tryLock(Fiber.WARN_DURATION_LIMIT_NANOS, TimeUnit.NANOSECONDS)) {
						Loggers.ERR.warn(
							"Acquire lock timeout while unregistering the metrics context \"{}\"", metricsCtx
						);
					}
					metricsCtx.refreshLastSnapshot(); // one last time
					final AllMetricsSnapshot snapshot = metricsCtx.lastSnapshot();
					// check for the metrics threshold state if entered
					if(metricsCtx.thresholdStateEntered() && ! metricsCtx.thresholdStateExited()) {
						exitMetricsThresholdState(metricsCtx);
					}
					if(snapshot != null) {
						// file output
						if(metricsCtx.sumPersistEnabled()) {
							Loggers.METRICS_FILE_TOTAL.info(
								new MetricsCsvLogMessage(snapshot, metricsCtx.opType(), metricsCtx.concurrencyLimit())
							);
						}
						if(metricsCtx.perfDbResultsFileEnabled()) {
							Loggers.METRICS_EXT_RESULTS_FILE.info(
								new ExtResultsXmlLogMessage(
									metricsCtx.id(), snapshot, metricsCtx.startTimeStamp(), metricsCtx.opType(),
									metricsCtx.concurrencyLimit(), metricsCtx.itemDataSize()
								)
							);
						}
					}
					// console output
					if(metricsCtx instanceof DistributedMetricsContext) {
						final DistributedMetricsContext distributedMetricsCtx = (DistributedMetricsContext) metricsCtx;
						Loggers.METRICS_STD_OUT.info(
							new MetricsAsciiTableLogMessage(Collections.singleton(metricsCtx))
						);
						final DistributedAllMetricsSnapshot aggregSnapshot = (DistributedAllMetricsSnapshot) snapshot;
						if(aggregSnapshot != null) {
							Loggers.METRICS_STD_OUT.info(
								new StepResultsMetricsLogMessage(
									metricsCtx.opType(), metricsCtx.id(), metricsCtx.concurrencyLimit(), aggregSnapshot
								)
							);
						}
					}
					final PrometheusMetricsExporter exporter = exportedMetrics.remove(metricsCtx);
					if(exporter != null) {
						CollectorRegistry.defaultRegistry.unregister((Collector) exporter);
					}
				} catch(final InterruptedException e) {
					throw new InterruptRunException(e);
				} finally {
					try {
						outputLock.unlock();
					} catch(final IllegalMonitorStateException ignored) {
					}
				}
			} else {
				Loggers.ERR.debug("Metrics context \"{}\" has not been registered", metricsCtx);
			}
			Loggers.MSG.debug("Metrics context \"{}\" unregistered", metricsCtx);
		} finally {
			if(allMetrics.size() == 0) {
				stop();
				Loggers.MSG.debug("Stopped the metrics manager fiber");
			}
		}
	}

	private static void exitMetricsThresholdState(final MetricsContext metricsCtx) {
		Loggers.MSG.info(
			"{}: the active load operations count is below the threshold of {}, stopping the additional metrics " +
				"accounting", metricsCtx.toString(), metricsCtx.concurrencyThreshold()
		);
		final MetricsContext lastThresholdMetrics = metricsCtx.thresholdMetrics();
		final AllMetricsSnapshot snapshot = lastThresholdMetrics.lastSnapshot();
		if(lastThresholdMetrics.sumPersistEnabled()) {
			Loggers.METRICS_THRESHOLD_FILE_TOTAL.info(
				new MetricsCsvLogMessage(snapshot, metricsCtx.opType(), metricsCtx.concurrencyLimit())
			);
		}
		if(lastThresholdMetrics.perfDbResultsFileEnabled()) {
			Loggers.METRICS_THRESHOLD_EXT_RESULTS_FILE.info(
				new ExtResultsXmlLogMessage(
					metricsCtx.id(), snapshot, metricsCtx.startTimeStamp(), metricsCtx.opType(),
					metricsCtx.concurrencyLimit(), metricsCtx.itemDataSize()
				)
			);
		}
		metricsCtx.exitThresholdState();
	}

	@Override
	protected final void doClose() {
		allMetrics.forEach(MetricsContext::close);
		allMetrics.clear();
		exportedMetrics.clear();
	}
}
