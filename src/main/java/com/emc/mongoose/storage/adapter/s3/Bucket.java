package com.emc.mongoose.storage.adapter.s3;
//
import com.emc.mongoose.core.api.data.DataObject;
//
import java.io.Externalizable;
/**
 Created by kurila on 02.10.14.
 */
public interface Bucket<T extends DataObject>
extends Externalizable {
	//
	String getName();
	//
	boolean exists(final String addr)
	throws IllegalStateException;
	//
	void create(final String addr)
	throws IllegalStateException;
	//
	void delete(final String addr)
	throws IllegalStateException;
	//
}
