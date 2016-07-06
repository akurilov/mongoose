package com.emc.mongoose.common.log.appenders;
// mongoose-common.jar
import static com.emc.mongoose.common.conf.AppConfig.KEY_RUN_ID;
//
import org.apache.logging.log4j.ThreadContext;
//
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AppenderLoggingException;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginConfiguration;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.apache.logging.log4j.core.net.Advertiser;
import org.apache.logging.log4j.core.util.Booleans;
//
import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;
/**
 Created by andrey on 13.03.15.
 */
@Plugin(name = "RunIdFile", category = "Core", elementType = "appender", printObject = true)
public final class RunIdFileAppender
extends AbstractAppender {
	//
	private final String fName;
	private final Advertiser advertiser;
	private Object advertisement;
	private final boolean ignoreExceptions, flagFlush;
	private final RunIdFileManager manager;
	/**
	 Instantiate a WriterAppender and set the output destination to a
	 new {@link java.io.OutputStreamWriter} initialized with <code>os</code>
	 as its {@link java.io.OutputStream}.
	 @param name The name of the Appender.
	 @param layout The layout to format the message.
	 @param filter filter
	 @param ignoreExceptions ignore exceptions
	 @param manager The OutputStreamManager. */
	protected RunIdFileAppender(
		final String name,
		final Layout<? extends Serializable> layout,
		final Filter filter,
		final boolean ignoreExceptions,
		final boolean flagFlush,
		final RunIdFileManager manager,
		final String fName,
		final Advertiser advertiser
	) {
		super(name, filter, layout);
		if (advertiser != null) {
			final Map<String, String> configuration = new HashMap<>(layout.getContentFormat());
			configuration.putAll(manager.getContentFormat());
			configuration.put("contentType", layout.getContentType());
			configuration.put("name", name);
			advertisement = advertiser.advertise(configuration);
		}
		this.manager = manager;
		this.fName = fName;
		this.advertiser = advertiser;
		this.ignoreExceptions = ignoreExceptions;
		this.flagFlush = flagFlush;
	}
	//
	@Override
	public void stop() {
		super.stop();
		manager.release();
		manager.close();
		if(advertiser != null) {
			advertiser.unadvertise(advertisement);
		}
	}
	//
	public final String getFileName() {
		return fName;
	}
	//
	private final static int DEFAULT_SIZE_BUFF = 0x40000; // 256KB
	private final static long DEFAULT_SIZE_TO_ROTATE = 0x400000; // 4MB
	//
	@PluginFactory
	public static RunIdFileAppender createAppender(
		@PluginAttribute("fileName") final String fileNamePrefix,
		@PluginAttribute("name") final String name,
		@PluginAttribute("bufferSize") final String bufferSize,
		@PluginElement("Layout") Layout<? extends Serializable> layout,
		@PluginElement("Filter") final Filter filter,
		@PluginAttribute("ignoreExceptions") final String ignore,
		@PluginAttribute("advertise") final String advertise,
		@PluginAttribute("advertiseUri") final String advertiseUri,
		@PluginAttribute("immediateFlust") final String immediateFlush,
		@PluginConfiguration final Configuration config
	) {
		final boolean
			ignoreExceptions = Booleans.parseBoolean(ignore, true),
			isAdvertise = Boolean.parseBoolean(advertise),
			flagFlush = Booleans.parseBoolean(immediateFlush, false),
			isAppend = true,
			isLocking = false,
			isBuffering = true;
		if(fileNamePrefix == null) {
			throw new IllegalArgumentException("No file name prefix");
		}
		if(name == null) {
			throw new IllegalArgumentException("No appender name");
		}
		if(layout == null) {
			layout = PatternLayout.createDefaultLayout();
		}
		if(config == null) {
			throw new IllegalArgumentException("No config");
		}
		// determine the buffer size
		int buffSize = DEFAULT_SIZE_BUFF; // by default
		if(bufferSize != null) {
			try {
				buffSize = Integer.parseInt(bufferSize);
				if(buffSize < 1) {
					buffSize = DEFAULT_SIZE_BUFF;
				}
			} catch(final Exception e) {
				e.printStackTrace(System.err);
			}
		}
		//
		final RunIdFileManager manager = RunIdFileManager.getRunIdFileManager(
			fileNamePrefix, isAppend, isLocking, isBuffering, advertiseUri, layout, buffSize
		);
		//
		return new RunIdFileAppender(
			name, layout, filter, ignoreExceptions, flagFlush, manager, fileNamePrefix,
			isAdvertise ? config.getAdvertiser() : null
		);
	}
	//
	@Override
	public final void append(final LogEvent event) {
		final String currRunId;
		final Map<String, String> evtCtxMap = event.getContextMap();
		//
		if(evtCtxMap.containsKey(KEY_RUN_ID)) {
			currRunId = event.getContextMap().get(KEY_RUN_ID);
		} else if(ThreadContext.containsKey(KEY_RUN_ID)) {
			currRunId = ThreadContext.get(KEY_RUN_ID);
		} else {
			currRunId = null;
		}
		final byte[] buff = getLayout().toByteArray(event);
		if(buff.length > 0) {
			try {
				manager.write(currRunId, buff);
				if(flagFlush || event.isEndOfBatch()) {
					manager.flush();
				}
			} catch(final AppenderLoggingException ex) {
				error("Unable to write to stream " + manager.getName() + " for appender " + getName());
				throw ex;
			}
		}
	}
}
