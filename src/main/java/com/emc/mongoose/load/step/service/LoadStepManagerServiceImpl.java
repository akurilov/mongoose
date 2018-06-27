package com.emc.mongoose.load.step.service;

import com.emc.mongoose.env.Extension;
import com.emc.mongoose.svc.ServiceBase;
import com.emc.mongoose.load.step.LoadStepManagerService;
import com.emc.mongoose.logging.Loggers;
import static com.emc.mongoose.Constants.KEY_CLASS_NAME;

import com.github.akurilov.confuse.Config;

import org.apache.logging.log4j.CloseableThreadContext;
import static org.apache.logging.log4j.CloseableThreadContext.Instance;

import java.rmi.RemoteException;
import java.util.List;
import java.util.Map;

public final class LoadStepManagerServiceImpl
extends ServiceBase
implements LoadStepManagerService {

	private final List<Extension> extensions;

	public LoadStepManagerServiceImpl(final int port, final List<Extension> extensions) {
		super(port);
		this.extensions = extensions;
	}

	@Override
	public final String name() {
		return SVC_NAME;
	}

	@Override
	protected final void doStart() {
		try(
			final Instance logCtx = CloseableThreadContext
				.put(KEY_CLASS_NAME, LoadStepManagerServiceImpl.class.getSimpleName())
		) {
			super.doStart();
			Loggers.MSG.info("Service \"{}\" started @ port #{}", SVC_NAME, port);
		}
	}

	@Override
	protected final void doClose() {
		Loggers.MSG.info("Service \"{}\" closed", SVC_NAME);
	}

	@Override
	public final String getStepService(
		final String stepType, final Config config, final List<Map<String, Object>> stepConfigs
	) throws RemoteException {
		final LoadStepService stepSvc = new LoadStepServiceImpl(port, extensions, stepType, config, stepConfigs);
		Loggers.MSG.info("New step service started @ port #{}: {}", port, stepSvc.name());
		return stepSvc.name();
	}
}
