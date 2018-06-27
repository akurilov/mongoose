package com.emc.mongoose.load.controller;

import com.github.akurilov.commons.concurrent.AsyncRunnable;
import com.github.akurilov.commons.io.Input;
import com.github.akurilov.commons.io.Output;

import com.emc.mongoose.item.io.task.IoTask;
import com.emc.mongoose.item.Item;

/**
 Created on 11.07.16.
 */
public interface LoadController<I extends Item, O extends IoTask<I>>
extends AsyncRunnable, Output<O> {
	
	void ioResultsOutput(final Output<O> ioTaskResultsOutput);

	default Input<O> getInput() {
		throw new AssertionError("Shouldn't be invoked");
	}
}
