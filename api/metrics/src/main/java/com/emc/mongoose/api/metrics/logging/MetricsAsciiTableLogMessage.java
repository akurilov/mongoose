package com.emc.mongoose.api.metrics.logging;

import com.emc.mongoose.api.metrics.MetricsContext;
import com.emc.mongoose.api.model.io.IoType;
import com.emc.mongoose.ui.log.LogMessageBase;
import static com.emc.mongoose.api.common.env.DateUtil.FMT_DATE_METRICS_TABLE;
import static com.emc.mongoose.ui.log.LogUtil.RESET;
import static com.emc.mongoose.api.common.Constants.MIB;
import com.emc.mongoose.ui.log.LogUtil;
import static com.emc.mongoose.ui.log.LogUtil.getFailureRatioAnsiColorCode;

import org.apache.commons.lang.text.StrBuilder;

import static org.apache.commons.lang.SystemUtils.LINE_SEPARATOR;

import java.util.Date;
import java.util.Set;

/**
 Created by kurila on 18.05.17.
 Not thread safe, relies on the MetricsManager's (caller) exclusive invocation lock
 */
public class MetricsAsciiTableLogMessage
extends LogMessageBase {

	public static final String TABLE_HEADER =
		"------------------------------------------------------------------------------------------------------------------------" + LINE_SEPARATOR +
		" Step Id  | Timestamp  |  Op  |     Concurrency     |       Count       | Step  |   Last Rate    |  Mean    |   Mean    " + LINE_SEPARATOR +
		" (last 10 |            | type |---------------------|-------------------| Time  |----------------| Latency  | Duration  " + LINE_SEPARATOR +
		" symbols) |yyMMddHHmmss|      | Current  |   Mean   |   Success  |Failed|  [s]  | [op/s] |[MB/s] |  [us]    |   [us]    " + LINE_SEPARATOR +
		"----------|------------|------|----------|----------|------------|------|-------|--------|-------|----------|-----------" + LINE_SEPARATOR;
	public static final String TABLE_BORDER_VERTICAL = "|";
	public static final int TABLE_HEADER_PERIOD = 20;

	private static volatile long ROW_OUTPUT_COUNTER = 0;

	private final Set<MetricsContext> metrics;
	private volatile String formattedMsg = null;
	
	public MetricsAsciiTableLogMessage(Set<MetricsContext> metrics) {
		this.metrics = metrics;
	}
	
	@Override
	public final void formatTo(final StringBuilder buffer) {
		if(formattedMsg == null) {
			final StrBuilder strb = new StrBuilder();
			MetricsContext.Snapshot snapshot;
			long succCount;
			long failCount;
			IoType ioType;
			boolean stdOutColorFlag;
			for(final MetricsContext metricsCtx : metrics) {
				snapshot = metricsCtx.getLastSnapshot();
				succCount = snapshot.getSuccCount();
				failCount = snapshot.getFailCount();
				ioType = metricsCtx.getIoType();
				stdOutColorFlag = metricsCtx.getStdOutColorFlag();
				if(0 == ROW_OUTPUT_COUNTER % TABLE_HEADER_PERIOD) {
					strb.append(TABLE_HEADER);
				}
				ROW_OUTPUT_COUNTER ++;
				strb
					.appendFixedWidthPadLeft(metricsCtx.getStepId(), 10, ' ')
					.append(TABLE_BORDER_VERTICAL)
					.appendFixedWidthPadLeft(FMT_DATE_METRICS_TABLE.format(new Date()), 12, ' ')
					.append(TABLE_BORDER_VERTICAL);
				if(stdOutColorFlag) {
					switch(ioType) {
						case NOOP:
							strb.append(LogUtil.NOOP_COLOR);
							break;
						case CREATE:
							strb.append(LogUtil.CREATE_COLOR);
							break;
						case READ:
							strb.append(LogUtil.READ_COLOR);
							break;
						case UPDATE:
							strb.append(LogUtil.UPDATE_COLOR);
							break;
						case DELETE:
							strb.append(LogUtil.DELETE_COLOR);
							break;
						case LIST:
							strb.append(LogUtil.LIST_COLOR);
							break;
					}
				}
				strb.appendFixedWidthPadRight(metricsCtx.getIoType().name(), 6, ' ');
				if(stdOutColorFlag) {
					strb.append(RESET);
				}
				strb
					.append(TABLE_BORDER_VERTICAL)
					.appendFixedWidthPadLeft(snapshot.getActualConcurrencyLast(), 10, ' ')
					.append(TABLE_BORDER_VERTICAL)
					.appendFixedWidthPadRight(
						formatFixedWidth(snapshot.getActualConcurrencyMean(), 10), 10, ' '
					)
					.append(TABLE_BORDER_VERTICAL)
					.appendFixedWidthPadLeft(succCount, 12, ' ').append(TABLE_BORDER_VERTICAL);
				if(stdOutColorFlag) {
					strb.append(getFailureRatioAnsiColorCode(succCount, failCount));
				}
				strb.appendFixedWidthPadLeft(failCount, 6, ' ');
				if(stdOutColorFlag) {
					strb.append(RESET);
				}
				strb
					.append(TABLE_BORDER_VERTICAL)
					.appendFixedWidthPadRight((double) snapshot.getElapsedTimeMillis() / 1000, 7, ' ')
					.append(TABLE_BORDER_VERTICAL)
					.appendFixedWidthPadRight(snapshot.getSuccRateLast(), 8, ' ')
					.append(TABLE_BORDER_VERTICAL)
					.appendFixedWidthPadRight(snapshot.getByteRateLast() / MIB, 7, ' ')
					.append(TABLE_BORDER_VERTICAL)
					.appendFixedWidthPadLeft((long) snapshot.getLatencyMean(), 10, ' ')
					.append(TABLE_BORDER_VERTICAL)
					.appendFixedWidthPadLeft((long) snapshot.getDurationMean(), 11, ' ')
					.appendNewLine();
			}
			formattedMsg = strb.toString();
		}
		buffer.append(formattedMsg);
	}
}
