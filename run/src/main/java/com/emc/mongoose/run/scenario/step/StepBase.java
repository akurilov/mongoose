package com.emc.mongoose.run.scenario.step;

import com.emc.mongoose.ui.config.Config;
import static com.emc.mongoose.api.common.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.api.common.Constants.KEY_TEST_STEP_ID;
import com.emc.mongoose.ui.log.Loggers;

import org.apache.logging.log4j.CloseableThreadContext;

import java.util.concurrent.CancellationException;

/**
 Created by kurila on 08.04.16.
 */
public abstract class StepBase
implements Step {

	protected final Config localConfig;

	protected StepBase(final Config appConfig) {
		localConfig = new Config(appConfig);
	}

	@Override
	public final Config getConfig() {
		return localConfig;
	}
	
	@Override
	public void run() {
		try(
			final CloseableThreadContext.Instance ctx = CloseableThreadContext
				.put(KEY_TEST_STEP_ID, localConfig.getTestConfig().getStepConfig().getId())
				.put(KEY_CLASS_NAME, getClass().getSimpleName())
		) {
			invoke();
		}
	}

	protected abstract void invoke()
	throws CancellationException;
}
