package com.emc.mongoose.storage.driver.coop.nio.mock;

import com.emc.mongoose.data.DataInput;
import com.emc.mongoose.exception.OmgShootMyFootException;
import com.emc.mongoose.item.DataItem;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.item.ItemFactory;
import com.emc.mongoose.item.op.OpType;
import com.emc.mongoose.item.op.Operation;
import com.emc.mongoose.item.op.data.DataOperation;
import com.emc.mongoose.storage.Credential;
import com.emc.mongoose.storage.driver.coop.nio.NioStorageDriverBase;
import com.github.akurilov.commons.collection.Range;
import com.github.akurilov.commons.math.Random;
import com.github.akurilov.confuse.Config;

import java.io.IOException;
import java.util.List;

public class NioStorageDriverMock<I extends Item, O extends Operation<I>>
extends NioStorageDriverBase<I, O> {

	private final Random rnd = new Random();

	public NioStorageDriverMock(
		final String testSteoName, final DataInput dataInput, final Config storageConfig, final boolean verifyFlag,
		final int batchSize
	) throws OmgShootMyFootException {
		super(testSteoName, dataInput, storageConfig, verifyFlag, batchSize);
	}

	@Override
	protected void invokeNio(final O op) {
		op.startResponse();
		if(op instanceof DataOperation) {
			final DataOperation dataOp = (DataOperation) op;
			final DataItem dataItem = dataOp.item();
			switch(dataOp.type()) {
				case CREATE:
					try {
						dataOp.countBytesDone(dataItem.size());
					} catch(final IOException ignored) {
					}
					break;
				case READ:
					dataOp.startDataResponse();
				case UPDATE:
					final List<Range> fixedRanges = dataOp.fixedRanges();
					if(fixedRanges == null || fixedRanges.isEmpty()) {
						if(dataOp.hasMarkedRanges()) {
							dataOp.countBytesDone(dataOp.markedRangesSize());
						} else {
							try {
								dataOp.countBytesDone(dataItem.size());
							} catch(final IOException ignored) {
							}
						}
					} else {
						dataOp.countBytesDone(dataOp.markedRangesSize());
					}
					break;
				default:
					break;
			}
			dataOp.startDataResponse();
		}
		op.finishResponse();
		op.status(Operation.Status.SUCC);
	}

	@Override
	protected String requestNewPath(final String path) {
		return path;
	}

	@Override
	protected String requestNewAuthToken(final Credential credential) {
		return Long.toHexString(rnd.nextLong());
	}

	@Override
	public List<I> list(
		final ItemFactory<I> itemFactory, final String path, final String prefix,
		final int idRadix, final I lastPrevItem, final int count
	) throws IOException {
		return null;
	}

	@Override
	public void adjustIoBuffers(final long avgTransferSize, final OpType opType) {
	}
}
