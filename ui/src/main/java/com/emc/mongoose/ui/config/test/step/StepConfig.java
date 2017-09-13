package com.emc.mongoose.ui.config.test.step;

import com.emc.mongoose.ui.config.test.step.limit.LimitConfig;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.Serializable;

/**
 Created by andrey on 05.07.17.
 */
public final class StepConfig
implements Serializable {

	public static final String KEY_LIMIT = "limit";
	public static final String KEY_ID = "id";
	public static final String KEY_ID_TMP = "idTmp";

	@JsonProperty(KEY_LIMIT)
	private LimitConfig limitConfig;

	@JsonProperty(KEY_ID)
	private String id;

	@JsonProperty(KEY_ID_TMP)
	private boolean idTmpFlag = false;

	public StepConfig() {
	}

	public StepConfig(final StepConfig other) {
		this.limitConfig = new LimitConfig(other.getLimitConfig());
		this.id = other.getId();
		this.idTmpFlag = other.getIdTmp();
	}

	public final LimitConfig getLimitConfig() {
		return limitConfig;
	}

	public final String getId() {
		return id;
	}

	public final void setLimitConfig(final LimitConfig limitConfig) {
		this.limitConfig = limitConfig;
	}

	public final void setId(final String id) {
		this.id = id;
	}

	public final void setIdTmp(final boolean idTmpFlag) {
		this.idTmpFlag = idTmpFlag;
	}

	public final boolean getIdTmp() {
		return idTmpFlag;
	}
}