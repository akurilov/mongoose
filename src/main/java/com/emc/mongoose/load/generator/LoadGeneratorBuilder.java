package com.emc.mongoose.load.generator;

import com.emc.mongoose.exception.OmgShootMyFootException;
import com.emc.mongoose.item.op.Operation;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.item.ItemFactory;
import com.emc.mongoose.item.ItemType;
import com.emc.mongoose.storage.driver.StorageDriver;

import com.github.akurilov.commons.io.Input;
import com.github.akurilov.commons.concurrent.throttle.IndexThrottle;
import com.github.akurilov.commons.concurrent.throttle.Throttle;
import com.github.akurilov.confuse.Config;

import java.io.IOException;

/**
 Created by andrey on 12.11.16.
 */
public interface LoadGeneratorBuilder<I extends Item, O extends Operation<I>, T extends LoadGenerator<I, O>> {

	LoadGeneratorBuilder<I, O, T> itemConfig(final Config itemConfig);

	LoadGeneratorBuilder<I, O, T> loadConfig(final Config loadConfig);

	LoadGeneratorBuilder<I, O, T> limitConfig(final Config limitConfig);

	LoadGeneratorBuilder<I, O, T> itemType(final ItemType itemType);

	LoadGeneratorBuilder<I, O, T> itemFactory(final ItemFactory<I> itemFactory);
	
	LoadGeneratorBuilder<I, O, T> authConfig(final Config authConfig);
	
	LoadGeneratorBuilder<I, O, T> storageDriver(StorageDriver<I, O> storageDriver);
	
	LoadGeneratorBuilder<I, O, T> itemInput(final Input<I> itemInput);

	LoadGeneratorBuilder<I, O, T> originIndex(final int originIndex);

	LoadGeneratorBuilder<I, O, T> addThrottle(final Throttle throttle);

	LoadGeneratorBuilder<I, O, T> addThrottle(final IndexThrottle throttle);

	T build()
	throws OmgShootMyFootException, IOException;
}
