package com.emc.mongoose.load.step;

import static com.emc.mongoose.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.Constants.KEY_STEP_ID;
import com.emc.mongoose.config.TimeUtil;
import com.emc.mongoose.env.Extension;
import com.emc.mongoose.metrics.MetricsContext;
import com.emc.mongoose.metrics.MetricsManager;
import com.emc.mongoose.metrics.MetricsSnapshot;
import com.emc.mongoose.concurrent.DaemonBase;
import com.emc.mongoose.item.op.OpType;
import com.emc.mongoose.logging.LogUtil;
import com.emc.mongoose.logging.Loggers;

import com.github.akurilov.commons.reflection.TypeUtil;
import com.github.akurilov.commons.system.SizeInBytes;

import com.github.akurilov.confuse.Config;

import static org.apache.logging.log4j.CloseableThreadContext.Instance;
import static org.apache.logging.log4j.CloseableThreadContext.put;

import com.github.akurilov.confuse.impl.BasicConfig;
import org.apache.logging.log4j.Level;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public abstract class LoadStepBase
extends DaemonBase
implements LoadStep, Runnable {

	protected final Config config;
	protected final List<Extension> extensions;
	protected final List<Config> ctxConfigs;
	protected final List<MetricsContext> metricsContexts = new ArrayList<>();

	private volatile long timeLimitSec = Long.MAX_VALUE;
	private volatile long startTimeSec = -1;

	protected LoadStepBase(final Config config, final List<Extension> extensions, final List<Config> ctxConfigs) {
		this.config = new BasicConfig(config);
		this.extensions = extensions;
		this.ctxConfigs = ctxConfigs;
	}

	@Override
	public final String id() {
		return config.stringVal("load-step-id");
	}

	@Override
	public final List<MetricsSnapshot> metricsSnapshots() {
		return metricsContexts
			.stream()
			.map(MetricsContext::lastSnapshot)
			.collect(Collectors.toList());
	}

	@Override
	public final void run() {
		try {
			start();
			try {
				await(timeLimitSec, TimeUnit.SECONDS);
			} catch(final IllegalStateException e) {
				LogUtil.exception(Level.WARN, e, "Failed to await \"{}\"", toString());
			} catch(final InterruptedException e) {
				throw new CancellationException();
			}
		} catch(final CancellationException e) {
			throw e;
		} catch(final IllegalStateException e) {
			LogUtil.exception(Level.ERROR, e, "Failed to start \"{}\"", toString());
		} catch(final Throwable cause) {
			LogUtil.exception(Level.ERROR, cause, "Load step execution failure \"{}\"", toString());
		} finally {
			try {
				close();
			} catch(final Exception e) {
				LogUtil.exception(Level.WARN, e, "Failed to close \"{}\"", toString());
				e.printStackTrace();
			}
		}
	}

	@Override
	protected void doStart()
	throws IllegalStateException {

		init();

		try(final Instance logCtx = put(KEY_STEP_ID, id()).put(KEY_CLASS_NAME, getClass().getSimpleName())) {
			doStartWrapped();
			final long t;
			final Object loadStepLimitTimeRaw = config.val("load-step-limit-time");
			if(loadStepLimitTimeRaw instanceof String) {
				t = TimeUtil.getTimeInSeconds((String) loadStepLimitTimeRaw);
			} else {
				t = TypeUtil.typeConvert(loadStepLimitTimeRaw, long.class);
			}
			if(t > 0) {
				timeLimitSec = t;
			}
			startTimeSec = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis());
		} catch(final Throwable cause) {
			LogUtil.exception(Level.WARN, cause, "{} step failed to start", id());
		}

		metricsContexts.forEach(
			metricsCtx -> {
				metricsCtx.start();
				try {
					MetricsManager.register(id(), metricsCtx);
				} catch(final InterruptedException e) {
					throw new CancellationException(e.getMessage());
				}
			}
		);
	}

	protected abstract void doStartWrapped();

	/**
	 * Initializes the actual configuration and metrics contexts
	 * @throws IllegalStateException if initialization fails
	 */
	protected abstract void init()
	throws IllegalStateException;

	protected abstract void initMetrics(
		final int originIndex, final OpType opType, final int concurrency, final Config metricsConfig,
		final SizeInBytes itemDataSize, final boolean outputColorFlag
	);

	@Override
	protected void doStop() {

		if(metricsContexts != null) {
			metricsContexts
				.forEach(
					metricsCtx -> {
						try {
							MetricsManager.unregister(id(), metricsCtx);
						} catch(final InterruptedException e) {
							throw new CancellationException(e.getMessage());
						}
					}
				);
		}

		final long t = TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis()) - startTimeSec;
		if(t < 0) {
			Loggers.ERR.warn("Stopped earlier than started, won't account the elapsed time");
		} else if(t > timeLimitSec) {
			Loggers.MSG.warn(
				"The elapsed time ({}[s]) is more than the limit ({}[s]), further resuming is not available",
				t, timeLimitSec
			);
			timeLimitSec = 0;
		} else {
			timeLimitSec -= t;
		}
	}

	@Override
	protected void doClose()
	throws IOException {
		metricsContexts
			.forEach(
				metricsCtx -> {
					try {
						metricsCtx.close();
					} catch(final IOException e) {
						LogUtil.exception(Level.WARN, e, "Failed to close the metrics context \"{}\"", metricsCtx);
					}
				}
			);
	}
}
