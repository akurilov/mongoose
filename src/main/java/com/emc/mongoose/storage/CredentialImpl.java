package com.emc.mongoose.storage;

/**
 Created by andrey on 14.03.17.
 */
public final class CredentialImpl
implements Credential {

	private final String uid;
	private final String secret;

	protected CredentialImpl(final String uid, final String secret) {
		this.uid = uid;
		this.secret = secret;
	}

	@Override
	public final String getUid() {
		return uid;
	}

	@Override
	public final String getSecret() {
		return secret;
	}

	@Override
	public final boolean equals(final Object other) {

		if(!(other instanceof CredentialImpl)) {
			return false;
		}

		final CredentialImpl otherCred = (CredentialImpl) other;
		if(uid != null) {
			if(uid.equals(otherCred.uid)) {
				if(secret != null) {
					return secret.equals(otherCred.secret);
				} else if(otherCred.secret == null) {
					return true;
				}
			}
		} else {
			if(otherCred.uid == null) {
				if(secret != null) {
					return secret.equals(otherCred.secret);
				} else if(otherCred.secret == null) {
					return true;
				}
			}
		}
		return false;
	}

	@Override
	public final int hashCode() {
		return (uid == null ? 0 : uid.hashCode()) ^ (secret == null ? 0 : secret.hashCode());
	}
}
