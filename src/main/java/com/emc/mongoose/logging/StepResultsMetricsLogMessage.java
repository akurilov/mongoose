package com.emc.mongoose.logging;

import com.emc.mongoose.item.op.OpType;
import com.emc.mongoose.metrics.DistributedMetricsSnapshot;
import com.emc.mongoose.metrics.context.DistributedMetricsContext;
import com.github.akurilov.commons.system.SizeInBytes;
import org.apache.logging.log4j.message.AsynchronouslyFormattable;

import static com.emc.mongoose.Constants.K;
import static com.emc.mongoose.Constants.M;
import static com.emc.mongoose.Constants.MIB;

/**
 Created by kurila on 18.05.17.
 */
@AsynchronouslyFormattable
public class StepResultsMetricsLogMessage
	extends LogMessageBase {

	private final OpType opType;
	private final String stepId;
	private final int concurrencyLimit;
	private final DistributedMetricsSnapshot snapshot;

	public StepResultsMetricsLogMessage(final DistributedMetricsContext metricsCtx) {
		this(metricsCtx.opType(), metricsCtx.id(), metricsCtx.concurrencyLimit(), metricsCtx.lastSnapshot());
	}

	StepResultsMetricsLogMessage(
		final OpType opType, final String stepId, final int concurrencyLimit, final DistributedMetricsSnapshot snapshot
	) {
		this.opType = opType;
		this.stepId = stepId;
		this.snapshot = snapshot;
		this.concurrencyLimit = concurrencyLimit;
	}

	@Override
	public final void formatTo(final StringBuilder buff) {
		final String lineSep = System.lineSeparator();
		buff
			.append("---").append(lineSep)
			.append(
				"# Results ##############################################################################################################").append(
			lineSep)
			.append("- Load Step Id:                ").append(stepId).append(lineSep)
			.append("  Operation Type:              ").append(opType).append(lineSep)
			.append("  Node Count:                  ").append(snapshot.nodeCount()).append(lineSep)
			.append("  Concurrency:                 ").append(lineSep)
			.append("    Limit Per Storage Driver:  ").append(concurrencyLimit).append(lineSep)
			.append("    Actual:                    ").append(lineSep)
			.append("      Last:                    ").append(snapshot.concurrencySnapshot().mean()).append(lineSep)
			.append("      Mean:                    ").append(snapshot.concurrencySnapshot().mean()).append(lineSep)
			.append("  Operations Count:            ").append(lineSep)
			.append("    Successful:                ").append(snapshot.successSnapshot().count()).append(lineSep)
			.append("    Failed:                    ").append(snapshot.failsSnapshot().count()).append(lineSep)
			.append("  Transfer Size:               ").append(
			SizeInBytes.formatFixedSize(snapshot.byteSnapshot().count())).append(lineSep)
			.append("  Duration [s]:                ").append(lineSep)
			.append("    Elapsed:                   ").append(snapshot.elapsedTimeMillis() / K).append(lineSep)
			.append("    Sum:                       ").append(snapshot.durationSnapshot().sum() / M).append(lineSep)
			.append("  Throughput [op/s]:           ").append(lineSep)
			.append("    Last:                      ").append(snapshot.successSnapshot().last()).append(lineSep)
			.append("    Mean:                      ").append(snapshot.successSnapshot().mean()).append(lineSep)
			.append("  Bandwidth [MB/s]:            ").append(lineSep)
			.append("    Last:                      ").append(snapshot.byteSnapshot().last() / MIB).append(lineSep)
			.append("    Mean:                      ").append(snapshot.byteSnapshot().mean() / MIB).append(lineSep)
			.append("  Operations Duration [us]:    ").append(lineSep)
			.append("    Avg:                       ").append(snapshot.durationSnapshot().mean()).append(lineSep)
			.append("    Min:                       ").append(snapshot.durationSnapshot().min()).append(lineSep)
			//TODO: make quantiles configured
			.append("    LoQ:                       ").append(snapshot.durationSnapshot().quantile(0.25)).append(
			lineSep)
			.append("    Med:                       ").append(snapshot.durationSnapshot().quantile(0.5)).append(lineSep)
			.append("    HiQ:                       ").append(snapshot.durationSnapshot().quantile(0.75)).append(
			lineSep)
			.append("    Max:                       ").append(snapshot.durationSnapshot().max()).append(lineSep)
			.append("  Operations Latency [us]:     ").append(lineSep)
			.append("    Avg:                       ").append(snapshot.latencySnapshot().mean()).append(lineSep)
			.append("    Min:                       ").append(snapshot.latencySnapshot().min()).append(lineSep)
			.append("    LoQ:                       ").append(snapshot.latencySnapshot().quantile(0.25)).append(lineSep)
			.append("    Med:                       ").append(snapshot.latencySnapshot().quantile(0.5)).append(lineSep)
			.append("    HiQ:                       ").append(snapshot.latencySnapshot().quantile(0.75)).append(lineSep)
			.append("    Max:                       ").append(snapshot.latencySnapshot().max()).append(lineSep)
			.append("...").append(lineSep);
	}
}
