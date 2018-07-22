package com.emc.mongoose.storage.driver;

import com.emc.mongoose.logging.Loggers;
import com.emc.mongoose.concurrent.DaemonBase;
import com.emc.mongoose.data.DataInput;
import com.emc.mongoose.exception.OmgShootMyFootException;
import com.emc.mongoose.item.op.Operation;
import com.emc.mongoose.item.op.data.DataOperation;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.storage.Credential;
import com.github.akurilov.commons.concurrent.ThreadUtil;
import com.github.akurilov.commons.io.Input;
import com.github.akurilov.confuse.Config;
import org.apache.logging.log4j.CloseableThreadContext;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

import static com.emc.mongoose.Constants.KEY_CLASS_NAME;
import static com.emc.mongoose.Constants.KEY_STEP_ID;

/**
 Created by kurila on 11.07.16.
 */
public abstract class StorageDriverBase<I extends Item, O extends Operation<I>>
extends DaemonBase
implements StorageDriver<I,O> {

	private final DataInput itemDataInput;
	protected final String stepId;
	private final BlockingQueue<O> opsResultsQueue;
	protected final int concurrencyLimit;
	protected final int ioWorkerCount;
	protected final Credential credential;
	protected final boolean verifyFlag;

	protected final ConcurrentMap<String, Credential> pathToCredMap = new ConcurrentHashMap<>(1);

	private final ConcurrentMap<String, String> pathMap = new ConcurrentHashMap<>(1);
	protected abstract String requestNewPath(final String path);
	protected Function<String, String> requestNewPathFunc = this::requestNewPath;

	protected final ConcurrentMap<Credential, String> authTokens = new ConcurrentHashMap<>(1);
	protected abstract String requestNewAuthToken(final Credential credential);
	protected Function<Credential, String> requestAuthTokenFunc = this::requestNewAuthToken;

	protected StorageDriverBase(
		final String stepId, final DataInput itemDataInput, final Config storageConfig, final boolean verifyFlag
	) throws OmgShootMyFootException {

		this.itemDataInput = itemDataInput;
		final Config driverConfig = storageConfig.configVal("driver");
		final Config limitConfig = driverConfig.configVal("limit");
		final int outputQueueCapacity = limitConfig.intVal("queue-output");
		this.opsResultsQueue = new ArrayBlockingQueue<>(outputQueueCapacity);
		this.stepId = stepId;
		final Config authConfig = storageConfig.configVal("auth");
		this.credential = Credential.getInstance(
			authConfig.stringVal("uid"), authConfig.stringVal("secret")
		);
		final String authToken = authConfig.stringVal("token");
		if(authToken != null) {
			if(this.credential == null) {
				this.authTokens.put(Credential.NONE, authToken);
			} else {
				this.authTokens.put(credential, authToken);
			}
		}
		this.concurrencyLimit = limitConfig.intVal("concurrency");
		this.verifyFlag = verifyFlag;

		final int confWorkerCount = driverConfig.intVal("threads");
		if(confWorkerCount > 0) {
			ioWorkerCount = confWorkerCount;
		} else if(concurrencyLimit > 0) {
			ioWorkerCount = Math.min(concurrencyLimit, ThreadUtil.getHardwareThreadCount());
		} else {
			ioWorkerCount = ThreadUtil.getHardwareThreadCount();
		}
	}

	protected void prepareOperation(final O op) {
		op.reset();
		if(op instanceof DataOperation) {
			((DataOperation) op).item().dataInput(itemDataInput);
		}
		final String dstPath = op.dstPath();
		final Credential credential = op.credential();
		if(credential != null) {
			pathToCredMap.putIfAbsent(dstPath == null ? "" : dstPath, credential);
			if(requestAuthTokenFunc != null) {
				authTokens.computeIfAbsent(credential, requestAuthTokenFunc);
			}
		}
		if(requestNewPathFunc != null) {
			// NOTE: in the distributed mode null dstPath becomes empty one
			if(dstPath != null && !dstPath.isEmpty()) {
				if(null == pathMap.computeIfAbsent(dstPath, requestNewPathFunc)) {
					Loggers.ERR.debug(
						"Failed to compute the destination path for the operation: {}", op
					);
					op.status(Operation.Status.FAIL_UNKNOWN);
				}
			}
		}
	}

	@SuppressWarnings("unchecked")
	protected void opCompleted(final O op) {
		if(Loggers.MSG.isTraceEnabled()) {
			Loggers.MSG.trace("{}: Load operation completed", op);
		}
		final O opResult = op.result();
		if(! opsResultsQueue.offer(opResult)) {
			Loggers.ERR.warn("{}: Load operations results queue overflow, dropping the result", toString());
		}
	}

	@Override
	public final int concurrencyLimit() {
		return concurrencyLimit;
	}

	@Override
	public final O get() {
		return opsResultsQueue.poll();
	}

	@Override
	public final int get(final List<O> buffer, final int limit) {
		return opsResultsQueue.drainTo(buffer, limit);
	}

	@Override
	public final long skip(final long count) {
		int n = (int) Math.min(count, Integer.MAX_VALUE);
		final List<O> tmpBuff = new ArrayList<>(n);
		n = opsResultsQueue.drainTo(tmpBuff, n);
		tmpBuff.clear();
		return n;
	}

	@Override
	public Input<O> getInput() {
		return this;
	}

	@Override
	protected void doClose()
	throws IOException, IllegalStateException {
		try(
			final CloseableThreadContext.Instance logCtx = CloseableThreadContext
				.put(KEY_STEP_ID, stepId)
				.put(KEY_CLASS_NAME, StorageDriverBase.class.getSimpleName())
		) {
			itemDataInput.close();
			final int opResultsQueueSize = opsResultsQueue.size();
			if(opResultsQueueSize > 0) {
				Loggers.ERR.warn(
					"{}: Load operations results queue contains {} unhandled elements", toString(), opResultsQueueSize
				);
			}
			opsResultsQueue.clear();
			authTokens.clear();
			pathToCredMap.clear();
			pathMap.clear();
			Loggers.MSG.debug("{}: closed", toString());
		}
	}

	@Override
	public String toString() {
		return "storage/driver/" + concurrencyLimit + "/%s/" + hashCode();
	}
}
