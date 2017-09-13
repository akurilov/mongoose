package com.emc.mongoose.api.model.io.task.data;

import com.github.akurilov.commons.collection.Range;

import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.item.DataItem;

import java.util.BitSet;
import java.util.List;

/**
 Created by kurila on 11.07.16.
 */
public interface DataIoTask<I extends DataItem>
extends IoTask<I> {
	
	@Override
	I getItem();
	
	void markRandomRanges(final int count);
	
	boolean hasMarkedRanges();
	
	long getMarkedRangesSize();
	
	BitSet[] getMarkedRangesMaskPair();
	
	List<Range> getFixedRanges();

	int getRandomRangesCount();

	List<I> getSrcItemsToConcat();
	
	int getCurrRangeIdx();
	
	void setCurrRangeIdx(final int i);
	
	DataItem getCurrRange();
	
	DataItem getCurrRangeUpdate();

	long getCountBytesDone();

	void setCountBytesDone(long n);

	long getRespDataTimeStart();

	void startDataResponse()
	throws IllegalStateException;

	long getDataLatency();
}

