package com.emc.mongoose.api.metrics.logging;

import com.emc.mongoose.api.metrics.MetricsContext;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.ui.log.LogMessageBase;
import com.emc.mongoose.ui.log.LogUtil;
import static com.emc.mongoose.api.common.Constants.K;
import static com.emc.mongoose.api.common.Constants.M;
import static com.emc.mongoose.api.common.Constants.MIB;
import static com.emc.mongoose.ui.log.LogUtil.RESET;
import static com.emc.mongoose.ui.log.LogUtil.WHITE;
import static com.emc.mongoose.ui.log.LogUtil.getFailureRatioAnsiColorCode;

import org.apache.logging.log4j.message.AsynchronouslyFormattable;

import static com.github.akurilov.commons.system.SizeInBytes.formatFixedSize;

/**
 Created by kurila on 18.05.17.
 */
@AsynchronouslyFormattable
public final class BasicMetricsLogMessage
extends LogMessageBase {
	
	private MetricsContext metricsCtx;
	
	public BasicMetricsLogMessage(final MetricsContext metricsCtx) {
		this.metricsCtx = metricsCtx;
	}
	
	@Override
	public final void formatTo(final StringBuilder buffer) {
		final MetricsContext.Snapshot snapshot = metricsCtx.getLastSnapshot();
		final long succCount = snapshot.getSuccCount();
		final long failCount = snapshot.getFailCount();
		final IoType ioType = metricsCtx.getIoType();
		final boolean stdOutColorFlag = metricsCtx.getStdOutColorFlag();
		String ioTypeColorCode = WHITE;
		switch(ioType) {
			case NOOP:
				ioTypeColorCode = LogUtil.NOOP_COLOR;
				break;
			case CREATE:
				ioTypeColorCode = LogUtil.CREATE_COLOR;
				break;
			case READ:
				ioTypeColorCode = LogUtil.READ_COLOR;
				break;
			case UPDATE:
				ioTypeColorCode = LogUtil.UPDATE_COLOR;
				break;
			case DELETE:
				ioTypeColorCode = LogUtil.DELETE_COLOR;
				break;
			case LIST:
				ioTypeColorCode = LogUtil.LIST_COLOR;
				break;
		}
		buffer.append("Step \"").append(metricsCtx.getStepId()).append("\" results:\n\t");
		if(stdOutColorFlag) {
			buffer.append(ioTypeColorCode);
		}
		buffer.append(metricsCtx.getIoType().name());
		if(stdOutColorFlag) {
			buffer.append(RESET);
		}
		buffer
			.append('-').append(metricsCtx.getConcurrency())
			.append('x').append(metricsCtx.getDriverCount())
			.append(": c=(").append(formatFixedWidth(snapshot.getActualConcurrencyMean(), 6))
			.append("); n=(");
		if(stdOutColorFlag) {
			buffer.append(WHITE);
		}
		buffer.append(succCount);
		if(stdOutColorFlag) {
			buffer.append(RESET);
		}
		buffer.append('/');
		if(stdOutColorFlag) {
			buffer.append(getFailureRatioAnsiColorCode(succCount, failCount));
		}
		buffer.append(failCount);
		if(stdOutColorFlag) {
			buffer.append(RESET);
		}
		buffer
			.append("); t[s]=(")
			.append(formatFixedWidth(snapshot.getElapsedTimeMillis() / K, 7)).append('/')
			.append(formatFixedWidth(snapshot.getDurationSum() / M, 7)).append("); size=(")
			.append(formatFixedSize(snapshot.getByteCount())).append("); TP[op/s]=(")
			.append(formatFixedWidth(snapshot.getSuccRateMean(), 7)).append('/')
			.append(formatFixedWidth(snapshot.getSuccRateLast(), 7)).append("); BW[MB/s]=(")
			.append(formatFixedWidth(snapshot.getByteRateMean() / MIB, 6)).append('/')
			.append(formatFixedWidth(snapshot.getByteRateLast() / MIB, 6)).append("); dur[us]=(")
			.append((long) snapshot.getDurationMean()).append('/')
			.append(snapshot.getDurationMin()).append('/')
			.append(snapshot.getDurationMax()).append("); lat[us]=(")
			.append((long) snapshot.getLatencyMean()).append('/')
			.append(snapshot.getLatencyMin()).append('/')
			.append(snapshot.getLatencyMax()).append(')')
			.append(System.lineSeparator());
	}
}
