package com.emc.mongoose.item.op;

import com.emc.mongoose.item.Item;
import com.emc.mongoose.supply.BatchSupplier;

import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 Created by kurila on 14.07.16.
 */
public interface OperationsBuilder<I extends Item, O extends Operation<I>>
extends Closeable {
	
	int originIndex();
	
	OpType opType();

	OperationsBuilder<I, O> opType(final OpType opType);

	String inputPath();

	OperationsBuilder<I, O> inputPath(final String inputPath);
	
	OperationsBuilder<I, O> outputPathSupplier(final BatchSupplier<String> ops);
	
	OperationsBuilder<I, O> uidSupplier(final BatchSupplier<String> uidSupplier);
	
	OperationsBuilder<I, O> secretSupplier(final BatchSupplier<String> secretSupplier);
	
	OperationsBuilder<I, O> credentialsMap(final Map<String, String> credentials);

	O buildOp(final I item)
	throws IOException, IllegalArgumentException;

	void buildOps(final List<I> items, final List<O> buff)
	throws IOException, IllegalArgumentException;
}
