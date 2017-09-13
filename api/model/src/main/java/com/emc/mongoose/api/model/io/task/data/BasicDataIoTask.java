package com.emc.mongoose.api.model.io.task.data;

import com.github.akurilov.commons.collection.Range;
import static com.github.akurilov.commons.system.SizeInBytes.formatFixedSize;

import com.emc.mongoose.api.model.data.DataInput;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.item.DataItem;
import com.emc.mongoose.api.model.storage.Credential;
import com.emc.mongoose.api.model.io.task.BasicIoTask;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import static java.lang.System.nanoTime;

/**
 Created by andrey on 25.09.16.
 */
public class BasicDataIoTask<T extends DataItem>
extends BasicIoTask<T>
implements DataIoTask<T> {
	
	protected final BitSet[] markedRangesMaskPair = new BitSet[] {
		new BitSet(Long.SIZE), new BitSet(Long.SIZE)
	};
	private int randomRangesCount = 0;
	private List<Range> fixedRanges = null;
	private List<T> srcItemsToConcat = null;
	protected long contentSize = 0;

	protected transient volatile DataInput contentSrc = null;
	protected transient volatile long countBytesDone = 0;
	protected transient volatile long respDataTimeStart = 0;
	private volatile DataItem currRange = null;
	private volatile int currRangeIdx = 0;
	
	public BasicDataIoTask() {
		super();
	}
	
	public BasicDataIoTask(
		final int originCode, final IoType ioType, final T item, final String srcPath,
		final String dstPath, final Credential credential, final List<Range> fixedRanges,
		final int randomRangesCount
	) throws IllegalArgumentException {
		super(originCode, ioType, item, srcPath, dstPath, credential);
		this.fixedRanges = fixedRanges;
		this.randomRangesCount = randomRangesCount;
		reset();
		contentSrc = item.getDataInput();
	}

	public BasicDataIoTask(
		final int originCode, final IoType ioType, final T item, final String srcPath,
		final String dstPath, final Credential credential, final List<Range> fixedRanges,
		final int randomRangesCount, final List<T> srcItemsToConcat
	) throws IllegalArgumentException {
		this(
			originCode, ioType, item, srcPath, dstPath, credential, fixedRanges, randomRangesCount
		);
		this.srcItemsToConcat = srcItemsToConcat;
	}

	protected BasicDataIoTask(final BasicDataIoTask<T> other) {
		super(other);
		this.contentSize = other.contentSize;
		this.randomRangesCount = other.randomRangesCount;
		this.fixedRanges = other.fixedRanges;
		this.srcItemsToConcat = other.srcItemsToConcat;
		this.countBytesDone = other.countBytesDone;
		this.respDataTimeStart = other.respDataTimeStart;
	}

	@Override
	public BasicDataIoTask<T> getResult() {
		buildItemPath(item, dstPath == null ? srcPath : dstPath);
		return new BasicDataIoTask<>(this);
	}

	@Override
	public void reset()
	throws IllegalArgumentException {
		super.reset();

		countBytesDone = 0;
		respDataTimeStart = 0;
		currRange = null;
		currRangeIdx = 0;
		markedRangesMaskPair[0].clear();
		markedRangesMaskPair[1].clear();

		try {
			switch(ioType) {
				case CREATE:
					contentSize = item.size();
					break;
				case READ:
					if(fixedRanges == null || fixedRanges.isEmpty()) {
						if(randomRangesCount > 0) {
							markRandomRanges(randomRangesCount);
							contentSize = getMarkedRangesSize();
						} else {
							// read the entire data item
							contentSize = item.size();
						}
					} else {
						contentSize = getMarkedRangesSize();
						if(contentSize > item.size()) {
							throw new IllegalArgumentException(
								"Fixed ranges size (" + formatFixedSize(contentSize) + ") " +
									"is more than data item size (" + formatFixedSize(item.size())
							);
						}
					}
					break;
				case UPDATE:
					if(fixedRanges == null || fixedRanges.isEmpty()) {
						if(randomRangesCount > 0) {
							markRandomRanges(randomRangesCount);
						} else {
							// overwrite the entire data item
							fixedRanges = new ArrayList<>(1);
							fixedRanges.add(new Range(0L, item.size() - 1, -1));
						}
					}
					contentSize = getMarkedRangesSize();
					break;
				default:
					contentSize = 0;
					break;
			}
		} catch(final IOException e) {
			throw new AssertionError(e);
		}
	}
	
	@Override
	public final void markRandomRanges(final int count) {
		try {
			final int countRangesTotal = DataItem.getRangeCount(item.size());
			if(count < 1 || count > countRangesTotal) {
				throw new AssertionError(
					"Range count should be more than 0 and less than max " + countRangesTotal +
						" for the item size"
				);
			}
			for(int i = 0; i < count; i ++) {
				markRandomRangesActually(countRangesTotal);
			}
		} catch(final IOException e) {
			throw new AssertionError(e);
		}
	}
	
	private void markRandomRangesActually(final int countRangesTotal) {
		final int startCellPos = (int) (nanoTime() % countRangesTotal);
		int nextCellPos;
		if(countRangesTotal > item.getUpdatedRangesCount() + markedRangesMaskPair[0].cardinality()) {
			// current layer has not updated yet ranges
			for(int i = 0; i < countRangesTotal; i ++) {
				nextCellPos = (startCellPos + i) % countRangesTotal;
				if(!item.isRangeUpdated(nextCellPos)) {
					if(!markedRangesMaskPair[0].get(nextCellPos)) {
						markedRangesMaskPair[0].set(nextCellPos);
						break;
					}
				}
			}
		} else {
			// update the next layer ranges
			for(int i = 0; i < countRangesTotal; i ++) {
				nextCellPos = (startCellPos + i) % countRangesTotal;
				if(!markedRangesMaskPair[0].get(nextCellPos)) {
					if(!markedRangesMaskPair[1].get(nextCellPos)) {
						markedRangesMaskPair[1].set(nextCellPos);
						break;
					}
				}
			}
		}
	}
	
	@Override
	public final boolean hasMarkedRanges() {
		return !markedRangesMaskPair[0].isEmpty() || !markedRangesMaskPair[1].isEmpty();
	}
	
	@Override
	public final BitSet[] getMarkedRangesMaskPair() {
		return markedRangesMaskPair;
	}
	
	@Override
	public final long getMarkedRangesSize() {
		long sumSize = 0;
		if(fixedRanges == null || fixedRanges.isEmpty()) {
			try {
				for(int i = 0; i < DataItem.getRangeCount(item.size()); i++) {
					if(markedRangesMaskPair[0].get(i) || markedRangesMaskPair[1].get(i)) {
						sumSize += item.getRangeSize(i);
					}
				}
			} catch(final IOException e) {
				throw new AssertionError(e);
			}
		} else {
			long nextBeg, nextEnd, nextSize;
			for(final Range nextRange : fixedRanges) {
				nextBeg = nextRange.getBeg();
				nextEnd = nextRange.getEnd();
				nextSize = nextRange.getSize();
				if(nextSize == -1) {
					if(nextBeg == -1) {
						sumSize += nextEnd;
					} else if(nextEnd == -1) {
						try {
							sumSize += item.size() - nextBeg;
						} catch(final IOException e) {
							throw new AssertionError(e);
						}
					} else {
						sumSize += nextEnd - nextBeg + 1;
					}
				} else {
					sumSize += nextSize;
				}
			}
		}
		return sumSize;
	}
	
	@Override
	public final List<Range> getFixedRanges() {
		return fixedRanges;
	}

	@Override
	public final int getRandomRangesCount() {
		return randomRangesCount;
	}

	@Override
	public final List<T> getSrcItemsToConcat() {
		return srcItemsToConcat;
	}
	
	@Override
	public final int getCurrRangeIdx() {
		return currRangeIdx;
	}
	
	@Override
	public final void setCurrRangeIdx(final int currRangeIdx) {
		currRange = null;
		this.currRangeIdx = currRangeIdx;
	}
	
	@Override
	public final DataItem getCurrRange() {
		try {
			if(currRange == null && currRangeIdx < DataItem.getRangeCount(item.size())) {
				final long currRangeSize = item.getRangeSize(currRangeIdx);
				final long currRangeOffset = DataItem.getRangeOffset(currRangeIdx);
				final int layerIdx = item.layer();
				currRange = item.slice(currRangeOffset, currRangeSize);
				if(item.isRangeUpdated(currRangeIdx)) {
					currRange.layer(layerIdx + 1);
				}
			}
		} catch(final IOException e) {
			throw new AssertionError(e);
		}
		return currRange;
	}
	
	@Override
	public final DataItem getCurrRangeUpdate() {
		if(currRange == null) {
			final int layerIdx = item.layer();
			if(markedRangesMaskPair[0].get(currRangeIdx)) {
				final long currRangeSize = item.getRangeSize(currRangeIdx);
				final long currRangeOffset = DataItem.getRangeOffset(currRangeIdx);
				currRange = item.slice(currRangeOffset, currRangeSize);
				currRange.layer(layerIdx + 1);
			} else if(markedRangesMaskPair[1].get(currRangeIdx)) {
				final long currRangeSize = item.getRangeSize(currRangeIdx);
				final long currRangeOffset = DataItem.getRangeOffset(currRangeIdx);
				currRange = item.slice(currRangeOffset, currRangeSize);
				currRange.layer(layerIdx + 2);
			} else {
				currRange = null;
			}
		}
		return currRange;
	}

	@Override
	public final long getCountBytesDone() {
		return countBytesDone;
	}

	@Override
	public final void setCountBytesDone(final long n) {
		this.countBytesDone = n;
	}

	@Override
	public final long getRespDataTimeStart() {
		return respDataTimeStart;
	}

	@Override
	public final void startDataResponse() {
		respDataTimeStart = IoTask.START_OFFSET_MICROS + nanoTime() / 1000;
		if(reqTimeDone == 0) {
			throw new IllegalStateException(
				"Response data is started (" + respDataTimeStart +
				") before the request is finished (" + reqTimeDone + ")"
			);
		}
	}

	@Override
	public final long getDataLatency() {
		return respDataTimeStart - reqTimeDone;
	}

	@Override
	public void writeExternal(final ObjectOutput out)
	throws IOException {
		super.writeExternal(out);
		out.writeLong(contentSize);
		int n;
		if(fixedRanges == null) {
			out.writeInt(0);
		} else {
			n = fixedRanges.size();
			out.writeInt(n);
			if(n > 0) {
				Range nextRange;
				for(int i = 0; i < n; i ++) {
					nextRange = fixedRanges.get(i);
					out.writeLong(nextRange.getBeg());
					out.writeLong(nextRange.getEnd());
					out.writeLong(nextRange.getSize());
				}
			}
		}
		out.writeInt(randomRangesCount);
		out.writeLong(
			markedRangesMaskPair[0].isEmpty() ? 0 : markedRangesMaskPair[0].toLongArray()[0]
		);
		out.writeLong(
			markedRangesMaskPair[1].isEmpty() ? 0 : markedRangesMaskPair[1].toLongArray()[0]
		);
		out.writeLong(countBytesDone);
		out.writeLong(respDataTimeStart);
		if(srcItemsToConcat == null) {
			out.writeInt(0);
		} else {
			n = srcItemsToConcat.size();
			out.writeInt(n);
			if(n > 0) {
				for(int i = 0; i < n; i ++) {
					out.writeObject(srcItemsToConcat.get(i));
				}
			}
		}
	}

	@Override @SuppressWarnings("unchecked")
	public void readExternal(final ObjectInput in)
	throws IOException, ClassNotFoundException {
		super.readExternal(in);
		contentSrc = item.getDataInput();
		contentSize = in.readLong();
		int n = in.readInt();
		if(n == 0) {
			fixedRanges = null;
		} else {
			fixedRanges = new ArrayList<>(n);
			for(int i = 0; i < n; i ++) {
				fixedRanges.add(new Range(in.readLong(), in.readLong(), in.readLong()));
			}
		}
		randomRangesCount = in.readInt();
		markedRangesMaskPair[0].or(BitSet.valueOf(new long[] {in.readLong()}));
		markedRangesMaskPair[1].or(BitSet.valueOf(new long[] {in.readLong()}));
		countBytesDone = in.readLong();
		respDataTimeStart = in.readLong();
		n = in.readInt();
		if(n > 0) {
			srcItemsToConcat = new ArrayList<>(n);
			for(int i = 0; i < n; i ++) {
				srcItemsToConcat.add((T) in.readObject());
			}
		}
	}
}
