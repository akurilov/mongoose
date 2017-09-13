package com.emc.mongoose.storage.driver.net.http.emc.atmos;

import com.emc.mongoose.api.model.io.task.IoTask;
import com.emc.mongoose.api.model.item.Item;
import com.emc.mongoose.storage.driver.net.http.base.HttpResponseHandlerBase;
import com.emc.mongoose.storage.driver.net.http.base.HttpStorageDriverBase;
import static com.emc.mongoose.storage.driver.net.http.emc.atmos.AtmosApi.NS_URI_BASE;
import static com.emc.mongoose.storage.driver.net.http.emc.atmos.AtmosApi.OBJ_URI_BASE;
import com.emc.mongoose.ui.log.Loggers;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;

/**
 Created by kurila on 11.11.16.
 */
public final class AtmosResponseHandler<I extends Item, O extends IoTask<I>>
extends HttpResponseHandlerBase<I, O> {
	
	private final boolean fsAccess;
	
	public AtmosResponseHandler(
		final HttpStorageDriverBase<I, O> driver, final boolean verifyFlag,
		final boolean fsAccess
	) {
		super(driver, verifyFlag);
		this.fsAccess = fsAccess;
	}
	
	@Override
	protected final void handleResponseHeaders(final O ioTask, final HttpHeaders respHeaders) {
		if(!fsAccess) {
			final String location = respHeaders.get(HttpHeaderNames.LOCATION);
			if(location != null && !location.isEmpty()) {
				if(location.startsWith(NS_URI_BASE)) {
					ioTask.getItem().setName(location.substring(NS_URI_BASE.length()));
				} else if(location.startsWith(OBJ_URI_BASE)) {
					ioTask.getItem().setName(location.substring(OBJ_URI_BASE.length()));
				} else {
					ioTask.getItem().setName(location);
					Loggers.ERR.warn("Unexpected location value: \"{}\"", location);
				}
				// set the paths to null to avoid the path calculation in the ioTaskCallback call
				ioTask.setSrcPath(null);
				ioTask.setDstPath(null);
			}
		}
	}
}
