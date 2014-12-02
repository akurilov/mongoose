package com.emc.mongoose.base.api;
//
import com.emc.mongoose.base.api.Request;
import com.emc.mongoose.base.data.DataItem;
//
import java.io.Closeable;
import java.util.concurrent.Future;
/**
 Created by kurila on 02.12.14.
 */
public interface StorageClient<T extends DataItem>
extends Closeable {
	Future<Request.Result> execute(final Request<T> request);
}
