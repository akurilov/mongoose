package com.emc.mongoose.api.model.load;

import com.github.akurilov.commons.concurrent.Throttle;
import com.github.akurilov.commons.io.Output;

import com.emc.mongoose.api.model.concurrent.Daemon;
import com.emc.mongoose.api.common.concurrent.WeightThrottle;
import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.item.Item;
import com.emc.mongoose.api.model.io.IoType;

import java.util.List;

/**
 Created on 11.07.16.
 */
public interface LoadGenerator<I extends Item, O extends IoTask<I>>
extends Daemon {
	
	void setWeightThrottle(final WeightThrottle weightThrottle);

	void setRateThrottle(final Throttle<Object> rateThrottle);

	/**
	 Set the generated tasks destination
	 @param ioTaskOutputs list of the task outputs
	 */
	void setOutputs(final List<? extends Output<O>> ioTaskOutputs);

	/**
	 @return sum of the new tasks and recycled ones
	 */
	long getGeneratedTasksCount();

	long getTransferSizeEstimate();

	IoType getIoType();
	
	int getBatchSize();

	/**
	 @return the origin code shared by the generated tasks
	 */
	@Override
	int hashCode();

	/**
	 @return true if the load generator is configured to recycle the tasks, false otherwise
	 */
	boolean isRecycling();

	/**
	 Enqueues the task for further recycling
	 @param ioTask the task to recycle
	 */
	void recycle(final O ioTask);
}
