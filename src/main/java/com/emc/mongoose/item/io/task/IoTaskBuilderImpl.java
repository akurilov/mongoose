package com.emc.mongoose.item.io.task;

import com.emc.mongoose.item.io.IoType;
import com.emc.mongoose.item.Item;
import com.emc.mongoose.storage.Credential;
import com.emc.mongoose.supply.BatchSupplier;
import com.emc.mongoose.supply.ConstantStringSupplier;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 Created by kurila on 14.07.16.
 */
public class IoTaskBuilderImpl<I extends Item, O extends IoTask<I>>
implements IoTaskBuilder<I, O> {
	
	protected final int originIndex;
	
	protected IoType ioType = IoType.CREATE; // by default
	protected String inputPath = null;
	
	protected BatchSupplier<String> outputPathSupplier;
	protected boolean constantOutputPathFlag;
	protected String constantOutputPath;
	
	protected BatchSupplier<String> uidSupplier;
	protected boolean constantUidFlag;
	protected String constantUid;
	
	protected BatchSupplier<String> secretSupplier;
	protected boolean constantSecretFlag;
	protected String constantSecret;
	
	protected Map<String, String> credentialsMap = null;

	public IoTaskBuilderImpl(final int originIndex) {
		this.originIndex = originIndex;
	}
	
	@Override
	public final int getOriginIndex() {
		return originIndex;
	}
	
	@Override
	public final IoType getIoType() {
		return ioType;
	}

	@Override
	public final IoTaskBuilderImpl<I, O> setIoType(final IoType ioType) {
		this.ioType = ioType;
		return this;
	}

	public final String getInputPath() {
		return inputPath;
	}

	@Override
	public final IoTaskBuilderImpl<I, O> setInputPath(final String inputPath) {
		this.inputPath = inputPath;
		return this;
	}
	
	@Override
	public final IoTaskBuilderImpl<I, O> setOutputPathSupplier(final BatchSupplier<String> ops) {
		this.outputPathSupplier = ops;
		if(outputPathSupplier == null) {
			constantOutputPathFlag = true;
			constantOutputPath = null;
		} else if(outputPathSupplier instanceof ConstantStringSupplier) {
			constantOutputPathFlag = true;
			constantOutputPath = outputPathSupplier.get();
		} else {
			constantOutputPathFlag = false;
		}
		return this;
	}
	
	@Override
	public final IoTaskBuilderImpl<I, O> setUidSupplier(final BatchSupplier<String> uidSupplier) {
		this.uidSupplier = uidSupplier;
		if(uidSupplier == null) {
			constantUidFlag = true;
			constantUid = null;
		} else if(uidSupplier instanceof ConstantStringSupplier) {
			constantUidFlag = true;
			constantUid = uidSupplier.get();
		} else {
			constantUidFlag = false;
		}
		return this;
	}
	
	@Override
	public final IoTaskBuilderImpl<I, O> setSecretSupplier(
		final BatchSupplier<String> secretSupplier
	) {
		this.secretSupplier = secretSupplier;
		if(secretSupplier == null) {
			constantSecretFlag = true;
			constantSecret = null;
		} else if(secretSupplier instanceof ConstantStringSupplier) {
			constantSecretFlag = true;
			constantSecret = secretSupplier.get();
		} else {
			constantSecretFlag = false;
		}
		return this;
	}
	
	@Override
	public IoTaskBuilderImpl<I, O> setCredentialsMap(final Map<String, String> credentials) {
		if(credentials != null) {
			this.credentialsMap = credentials;
			setSecretSupplier(null);
		}
		return this;
	}
	
	@Override @SuppressWarnings("unchecked")
	public O getInstance(final I item)
	throws IOException {
		final String uid;
		return (O) new IoTaskImpl<>(
			originIndex, ioType, item, inputPath, getNextOutputPath(),
			Credential.getInstance(uid = getNextUid(), getNextSecret(uid))
		);
	}

	@Override @SuppressWarnings("unchecked")
	public void getInstances(final List<I> items, final List<O> buff)
	throws IOException {
		String uid;
		for(final I item : items) {
			buff.add(
				(O) new IoTaskImpl<>(
					originIndex, ioType, item, inputPath, getNextOutputPath(),
					Credential.getInstance(uid = getNextUid(), getNextSecret(uid))
				)
			);
		}
	}
	
	protected final String getNextOutputPath() {
		return constantOutputPathFlag ? constantOutputPath : outputPathSupplier.get();
	}
	
	protected final String getNextUid() {
		return constantUidFlag ? constantUid : uidSupplier.get();
	}
	
	protected final String getNextSecret(final String uid) {
		if(uid != null && credentialsMap != null) {
			return credentialsMap.get(uid);
		} else if(constantSecretFlag) {
			return constantSecret;
		} else {
			return secretSupplier.get();
		}
	}
	
	@Override
	public void close()
	throws IOException {
		inputPath = null;
		if(outputPathSupplier != null) {
			outputPathSupplier.close();
			outputPathSupplier = null;
		}
		if(uidSupplier != null) {
			uidSupplier.close();
			uidSupplier = null;
		}
		if(secretSupplier != null) {
			secretSupplier.close();
			secretSupplier = null;
		}
		if(credentialsMap != null) {
			credentialsMap.clear();
			credentialsMap = null;
		}
	}
}
