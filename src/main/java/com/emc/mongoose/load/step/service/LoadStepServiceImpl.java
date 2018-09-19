package com.emc.mongoose.load.step.service;

import com.emc.mongoose.env.Extension;
import com.emc.mongoose.exception.InterruptRunException;
import com.emc.mongoose.metrics.MetricsManager;
import com.emc.mongoose.metrics.MetricsSnapshot;
import com.emc.mongoose.svc.ServiceBase;
import com.emc.mongoose.load.step.LoadStep;
import com.emc.mongoose.load.step.LoadStepFactory;
import com.emc.mongoose.logging.Loggers;
import static com.emc.mongoose.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.Constants.KEY_STEP_ID;

import com.github.akurilov.confuse.Config;

import static org.apache.logging.log4j.CloseableThreadContext.Instance;
import static org.apache.logging.log4j.CloseableThreadContext.put;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class LoadStepServiceImpl
extends ServiceBase
implements LoadStepService {

	private final LoadStep localLoadStep;

	public LoadStepServiceImpl(
		final int port, final List<Extension> extensions, final String stepType, final Config baseConfig,
		final List<Config> ctxConfigs, final MetricsManager metricsManager
	) {
		super(port);
		baseConfig.val("load-step-idAutoGenerated", false); // don't override the step-id value on the remote node again
		localLoadStep = LoadStepFactory.createLocalLoadStep(
			baseConfig, extensions, ctxConfigs, metricsManager, stepType
		);
		final String stepId = baseConfig.stringVal("load-step-id");
		try(final Instance logCtx = put(KEY_STEP_ID, stepId).put(KEY_CLASS_NAME, getClass().getSimpleName())) {
			Loggers.MSG.info("New step service for type \"{}\"", stepType);
			super.doStart();
		}
	}

	@Override
	protected final void doStart() {
		try(
			final Instance logCtx = put(KEY_CLASS_NAME, getClass().getSimpleName()).put(KEY_STEP_ID, localLoadStep.id())
		) {
			localLoadStep.start();
			Loggers.MSG.info("Step service for \"{}\" is started", localLoadStep.id());
		} catch(final RemoteException ignored) {
		}
	}

	@Override
	protected void doStop() {
		try(
			final Instance logCtx = put(KEY_CLASS_NAME, getClass().getSimpleName()).put(KEY_STEP_ID, localLoadStep.id())
		) {
			localLoadStep.stop();
			Loggers.MSG.info("Step service for \"{}\" is stopped", localLoadStep.id());
		} catch(final RemoteException ignored) {
		}
	}

	@Override
	protected final void doClose()
	throws IOException {
		try(
			final Instance logCtx = put(KEY_CLASS_NAME, getClass().getSimpleName()).put(KEY_STEP_ID, localLoadStep.id())
		) {
			super.doStop();
			localLoadStep.close();
			Loggers.MSG.info("Step service for \"{}\" is closed", localLoadStep.id());
		}
	}

	@Override
	public String name() {
		return SVC_NAME_PREFIX + hashCode();
	}

	@Override
	public final String id()
	throws RemoteException {
		return localLoadStep.id();
	}

	@Override
	public final String getTypeName()
	throws RemoteException {
		return localLoadStep.getTypeName();
	}

	@Override
	public final List<MetricsSnapshot> metricsSnapshots()
	throws RemoteException {
		return localLoadStep.metricsSnapshots();
	}

	@Override
	public final boolean await(final long timeout, final TimeUnit timeUnit)
	throws InterruptRunException, IllegalStateException, InterruptedException {
		try(
			final Instance logCtx = put(KEY_CLASS_NAME, getClass().getSimpleName()).put(KEY_STEP_ID, localLoadStep.id())
		) {
			return localLoadStep.await(timeout, timeUnit);
		} catch(final RemoteException ignored) {
		}
		return false;
	}
}
