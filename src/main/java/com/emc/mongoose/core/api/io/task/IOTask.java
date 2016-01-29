package com.emc.mongoose.core.api.io.task;
//
import com.emc.mongoose.core.api.item.base.Item;
import com.emc.mongoose.core.api.load.model.metrics.IOStats;
/**
 Created by kurila on 02.06.14.
 Request entity supporting some common operations.
 */
public interface IOTask<T extends Item> {
	//
	enum Type {
		WRITE, READ, DELETE, UPDATE, APPEND
	}
	//
	enum Status {
		SUCC(0, "Success"),
		CANCELLED(1, "Cancelled"),
		FAIL_UNKNOWN(2, "Unknown failure"),
		FAIL_IO(3, "I/O failure"),
		FAIL_TIMEOUT(4, "Timeout"),
		RESP_FAIL_UNKNOWN(5, "Unrecognized storage response"),
		RESP_FAIL_CLIENT(6, "Client failure/invalid request"),
		RESP_FAIL_SVC(7, "Storage failure"),
		RESP_FAIL_NOT_FOUND(8, "Item not found"),
		RESP_FAIL_AUTH(9, "Authentication/access failure"),
		RESP_FAIL_CORRUPT(10, "Data item corruption"),
		RESP_FAIL_SPACE(11, "Not enough space on the storage");
		public final int code;
		public final String description;
		Status(final int code, final String description) {
			this.code = code;
			this.description = description;
		}
	}
	//
	String getNodeAddr();
	//
	T getItem();
	//
	Status getStatus();
	//
	void mark(final IOStats ioStats);
}
