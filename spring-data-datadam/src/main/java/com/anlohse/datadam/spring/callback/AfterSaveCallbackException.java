package com.anlohse.datadam.spring.callback;

import org.springframework.dao.DataAccessException;

/** The write was acknowledged before this callback failed; retrying can repeat the write. */
public final class AfterSaveCallbackException extends DataAccessException {
    private final String key;
    public AfterSaveCallbackException(String key, RuntimeException cause) {
        super("After-save callback failed after persistence was acknowledged", cause);
        this.key = key;
    }
    public String getKey() { return key; }
}
