package com.emc.mongoose.item.io.task.data;

import com.emc.mongoose.item.io.task.IoTaskBuilderImpl;
import com.emc.mongoose.item.io.task.composite.data.CompositeDataIoTaskImpl;
import com.emc.mongoose.item.DataItem;
import com.emc.mongoose.storage.Credential;
import com.github.akurilov.commons.collection.Range;
import com.github.akurilov.commons.math.Random;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.emc.mongoose.item.DataItem.rangeCount;
import static com.github.akurilov.commons.system.SizeInBytes.formatFixedSize;

/**
 Created by kurila on 14.07.16.
 */
public class DataIoTaskBuilderImpl<I extends DataItem, O extends DataIoTask<I>>
extends IoTaskBuilderImpl<I, O>
implements DataIoTaskBuilder<I, O> {

	private final Random rnd = new Random();

	protected volatile List<I> srcItemsForConcat = null;
	protected volatile int srcItemsCount = 0;
	protected volatile int srcItemsCountMin = 0;
	protected volatile int srcItemsCountMax = 0;
	protected volatile List<Range> fixedRanges = null;
	protected volatile int randomRangesCount = 0;
	protected volatile long sizeThreshold = 0;

	public DataIoTaskBuilderImpl(final int originIndex) {
		super(originIndex);
	}

	@Override
	public DataIoTaskBuilderImpl<I, O> setFixedRanges(final List<Range> fixedRanges) {
		this.fixedRanges = fixedRanges;
		return this;
	}
	
	@Override
	public DataIoTaskBuilderImpl<I, O> setRandomRangesCount(final int count) {
		this.randomRangesCount = count;
		return this;
	}
	
	@Override
	public DataIoTaskBuilderImpl<I, O> setSizeThreshold(final long sizeThreshold) {
		this.sizeThreshold = sizeThreshold > 0 ? sizeThreshold : Long.MAX_VALUE;
		return this;
	}

	@Override
	public DataIoTaskBuilderImpl<I, O> setSrcItemsCount(final int min, final int max) {
		this.srcItemsCountMin = min;
		this.srcItemsCountMax = max;
		return this;
	}

	@Override
	public DataIoTaskBuilderImpl<I, O> setSrcItemsForConcat(final List<I> srcItemsForConcat) {
		this.srcItemsForConcat = srcItemsForConcat;
		if(this.srcItemsForConcat != null) {
			this.srcItemsCount = srcItemsForConcat.size();
		}
		return this;
	}
	
	@Override
	public List<Range> getFixedRanges() {
		return fixedRanges;
	}
	
	@Override
	public int getRandomRangesCount() {
		return randomRangesCount;
	}
	
	@Override
	public long getSizeThreshold() {
		return sizeThreshold;
	}
	
	@Override @SuppressWarnings("unchecked")
	public O getInstance(final I dataItem)
	throws IOException, IllegalArgumentException {
		final String uid;
		if(dataItem.size() > sizeThreshold) {
			if(randomRangesCount > 0 || (fixedRanges != null && fixedRanges.size() > 0)) {
				throw new IllegalArgumentException(
					"Not supported - both byte ranges configured and size threshold"
				);
			}
			return (O) new CompositeDataIoTaskImpl<>(
				originIndex, ioType, dataItem, inputPath, getNextOutputPath(),
				Credential.getInstance(uid = getNextUid(), getNextSecret(uid)),
				fixedRanges, randomRangesCount, sizeThreshold
			);
		} else if(srcItemsCount > 0) {
			return (O) new DataIoTaskImpl<>(
				originIndex, ioType, dataItem, inputPath, getNextOutputPath(),
				Credential.getInstance(uid = getNextUid(), getNextSecret(uid)),
				fixedRanges, randomRangesCount, getNextSrcItemsForConcat()
			);
		} else {
			if(randomRangesCount > rangeCount(dataItem.size())) {
				throw new IllegalArgumentException(
					"Configured random ranges count (" + randomRangesCount + ") is more than " +
						"allowed for the data item w/ size " + formatFixedSize(dataItem.size())
				);
			}
			return (O) new DataIoTaskImpl<>(
				originIndex, ioType, dataItem, inputPath, getNextOutputPath(),
				Credential.getInstance(uid = getNextUid(), getNextSecret(uid)),
				fixedRanges, randomRangesCount
			);
		}
	}

	@Override @SuppressWarnings("unchecked")
	public void getInstances(final List<I> items, final List<O> buff)
	throws IOException, IllegalArgumentException {
		String uid;
		for(final I nextItem : items) {
			if(nextItem.size() > sizeThreshold) {
				if(randomRangesCount > 0 || (fixedRanges != null && fixedRanges.size() > 0)) {
					throw new IllegalArgumentException(
						"Not supported - both byte ranges configured and size threshold"
					);
				}
				buff.add(
					(O) new CompositeDataIoTaskImpl<>(
						originIndex, ioType, nextItem, inputPath, getNextOutputPath(),
						Credential.getInstance(uid = getNextUid(), getNextSecret(uid)),
						fixedRanges, randomRangesCount, sizeThreshold
					)
				);
			} else if(srcItemsCount > 0) {
				buff.add(
					(O) new DataIoTaskImpl<>(
						originIndex, ioType, nextItem, inputPath, getNextOutputPath(),
						Credential.getInstance(uid = getNextUid(), getNextSecret(uid)),
						fixedRanges, randomRangesCount, getNextSrcItemsForConcat()
					)
				);
			} else {
				if(randomRangesCount > rangeCount(nextItem.size())) {
					throw new IllegalArgumentException(
						"Configured random ranges count (" + randomRangesCount + ") is more than " +
							"allowed for the data item w/ size " + formatFixedSize(nextItem.size())
					);
				}
				buff.add(
					(O) new DataIoTaskImpl<>(
						originIndex, ioType, nextItem, inputPath, getNextOutputPath(),
						Credential.getInstance(uid = getNextUid(), getNextSecret(uid)),
						fixedRanges, randomRangesCount
					)
				);
			}
		}
	}

	@Override
	public void close()
	throws IOException {
		super.close();
		if(srcItemsForConcat != null) {
			srcItemsForConcat.clear();
			srcItemsForConcat = null;
		}
		if(fixedRanges != null) {
			fixedRanges.clear();
			fixedRanges = null;
		}
	}

	protected List<I> getNextSrcItemsForConcat() {
		final int n = srcItemsCountMin < srcItemsCountMax ?
			srcItemsCountMin + rnd.nextInt(srcItemsCountMax - srcItemsCountMin + 1) :
			srcItemsCountMin;
		final List<I> selectedItems = new ArrayList<>(n);
		for(int i = 0; i < n; i ++) {
			selectedItems.add(srcItemsForConcat.get(rnd.nextInt(srcItemsCount)));
		}
		return selectedItems;
	}
}
