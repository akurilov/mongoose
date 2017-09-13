package com.emc.mongoose.api.common.supply.async;

import com.emc.mongoose.api.common.exception.OmgDoesNotPerformException;
import com.emc.mongoose.api.common.supply.RangeDefinedSupplier;

import com.github.akurilov.coroutines.CoroutinesProcessor;
import org.apache.commons.lang.time.FastDateFormat;

import java.text.Format;
import java.util.Date;

public final class AsyncRangeDefinedDateFormattingSupplier
extends AsyncRangeDefinedSupplierBase<Date> {

	private final Format format;
	private final RangeDefinedSupplier<Long> longGenerator;
	
	public AsyncRangeDefinedDateFormattingSupplier(
		final CoroutinesProcessor coroutinesProcessor,
		final long seed, final Date minValue, final Date maxValue, final String formatString
	) throws OmgDoesNotPerformException {
		super(coroutinesProcessor, seed, minValue, maxValue);
		this.format = formatString == null || formatString.isEmpty() ?
			null : FastDateFormat.getInstance(formatString);
		longGenerator = new AsyncRangeDefinedLongFormattingSupplier(
			coroutinesProcessor, seed, minValue.getTime(), maxValue.getTime(), null
		);
	}

	@Override
	protected final Date computeRange(final Date minValue, final Date maxValue) {
		return null;
	}

	@Override
	protected final Date rangeValue() {
		return new Date(longGenerator.value());
	}

	@Override
	protected final Date singleValue() {
		return new Date(longGenerator.value());
	}

	@Override
	protected final String toString(final Date value) {
		return format == null ? value.toString() : format.format(value);
	}

	@Override
	public final boolean isInitialized() {
		return longGenerator != null;
	}
}
