package com.emc.mongoose.common.concurrent;
//
//import static com.emc.mongoose.common.conf.RunTimeConfig.KEY_RUN_ID;
//import static com.emc.mongoose.common.conf.RunTimeConfig.getContext;
import com.emc.mongoose.common.log.LogUtil;
//
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
//import org.apache.logging.log4j.ThreadContext;
//
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
/**
 Created by kurila on 25.04.14.
 */
public class GroupThreadFactory
extends ThreadGroup
implements ThreadFactory {
	//
	private static final Logger LOG = LogManager.getLogger();
	//
	protected final AtomicInteger threadNumber = new AtomicInteger(0);
	//
	public GroupThreadFactory(final String threadNamePrefix) {
		this(threadNamePrefix, false);
	}
	//
	public GroupThreadFactory(final String threadNamePrefix, final boolean isDaemon) {
		super(Thread.currentThread().getThreadGroup(), threadNamePrefix);
		setDaemon(isDaemon);
	}
	//
	@Override
	public Thread newThread(final Runnable task) {
		final Thread t = new Thread(
			this, task, getName() + "#" + threadNumber.incrementAndGet()
		);
		t.setDaemon(isDaemon());
		return t;
	}
	//
	@Override
	public final String toString() {
		return getName();
	}
	//
	@Override
	public final void uncaughtException(final Thread thread, final Throwable thrown) {
		LogUtil.exception(
			LOG, Level.DEBUG, thrown, "Thread \"{}\" terminated because of the exception"
		);
	}
}
