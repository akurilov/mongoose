package com.emc.mongoose.node;

import com.emc.mongoose.api.model.svc.ServiceBase;
import com.emc.mongoose.scenario.sna.StepManagerService;
import com.emc.mongoose.scenario.sna.StepService;
import com.emc.mongoose.ui.config.Config;
import com.emc.mongoose.ui.log.Loggers;

import java.rmi.RemoteException;

public final class BasicStepManagerService
extends ServiceBase
implements StepManagerService {

	public BasicStepManagerService(final int port) {
		super(port);
	}

	@Override
	public final String getName() {
		return SVC_NAME;
	}

	@Override
	protected final void doStart() {
		super.doStart();
		Loggers.MSG.info("Service \"{}\" started @ port #{}", SVC_NAME, port);
	}

	@Override
	protected final void doClose() {
		Loggers.MSG.info("Service \"{}\" closed", SVC_NAME);
	}

	@Override
	public final String getStepService(final String stepType, final Config config)
	throws RemoteException {
		final StepService stepSvc = new BasicStepService(port, stepType, config);
		stepSvc.start();
		Loggers.MSG.info("New step service started @ port #{}: {}", port, stepSvc.getName());
		return stepSvc.getName();
	}
}