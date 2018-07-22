package com.emc.mongoose.item.op.composite.data;

import com.emc.mongoose.item.op.composite.CompositeOperation;
import com.emc.mongoose.item.op.partial.data.PartialDataOperation;
import com.emc.mongoose.item.DataItem;

import java.util.List;

/**
 Created by andrey on 25.11.16.
 */
public interface CompositeDataOperation<I extends DataItem>
extends CompositeOperation<I> {
	
	@Override
	List<? extends PartialDataOperation<I>> subOperations();
}
