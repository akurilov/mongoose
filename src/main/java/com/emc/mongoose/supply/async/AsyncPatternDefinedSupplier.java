package com.emc.mongoose.supply.async;

import com.emc.mongoose.exception.OmgDoesNotPerformException;
import com.emc.mongoose.exception.OmgShootMyFootException;
import com.emc.mongoose.supply.PatternDefinedSupplier;
import com.emc.mongoose.supply.RangePatternDefinedSupplier;

import com.github.akurilov.fiber4j.FibersExecutor;

public final class AsyncPatternDefinedSupplier
extends AsyncValueUpdatingSupplier<String>
implements PatternDefinedSupplier {
	
	private final PatternDefinedSupplier wrappedSupplier;
	
	public AsyncPatternDefinedSupplier(final FibersExecutor executor, final String pattern)
	throws OmgShootMyFootException {
		this(
			executor,
			new RangePatternDefinedSupplier(
				pattern, AsyncStringSupplierFactory.getInstance(executor)
			)
		);
	}
	
	private AsyncPatternDefinedSupplier(
		final FibersExecutor executor, final PatternDefinedSupplier wrappedSupplier
	) throws OmgDoesNotPerformException {
		super(
			executor,
			null,
			new InitCallableBase<String>() {
				private final StringBuilder result = new StringBuilder();
				@Override
				public final String call()
				throws Exception {
					result.setLength(0);
					return wrappedSupplier.format(result);
				}
			}
		);
		this.wrappedSupplier = wrappedSupplier;
	}
	
	@Override
	public String getPattern() {
		return wrappedSupplier.getPattern();
	}

	@Override
	public String format(final StringBuilder result) {
		return wrappedSupplier.format(result);
	}
}
