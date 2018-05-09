package com.emc.mongoose.model.supply.async;

import com.emc.mongoose.model.exception.OmgDoesNotPerformException;

import com.github.akurilov.concurrent.coroutine.CoroutinesExecutor;

import java.text.DecimalFormat;
import java.text.NumberFormat;

public final class AsyncRangeDefinedLongFormattingSupplier
extends AsyncRangeDefinedSupplierBase<Long> {
	
	private final NumberFormat format;
	
	public AsyncRangeDefinedLongFormattingSupplier(
		final CoroutinesExecutor executor, final long seed, final long minValue,
		final long maxValue, final String formatStr
	) throws OmgDoesNotPerformException {
		super(executor, seed, minValue, maxValue);
		this.format = formatStr == null || formatStr.isEmpty() ?
			null : new DecimalFormat(formatStr);
	}

	@Override
	protected final Long computeRange(final Long minValue, final Long maxValue) {
		return maxValue - minValue + 1;
	}

	@Override
	protected final Long rangeValue() {
		return minValue() + rnd.nextLong(range());
	}

	@Override
	protected final Long singleValue() {
		return rnd.nextLong();
	}

	@Override
	protected String toString(final Long value) {
		return format == null ? value.toString() : format.format(value);
	}
}