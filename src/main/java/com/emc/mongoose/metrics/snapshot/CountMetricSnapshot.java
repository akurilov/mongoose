package com.emc.mongoose.metrics.snapshot;

import java.io.Serializable;

/**
 @author veronika K. on 12.10.18 */
public interface CountMetricSnapshot
extends Serializable {

	long count();
}
