package com.history.backend.common.error;

public class UnauthorizedException extends RuntimeException {

    private final boolean clearRefreshCookie;

    public UnauthorizedException(String message) {
        this(message, true);
    }

    public UnauthorizedException(String message, boolean clearRefreshCookie) {
        super(message);
        this.clearRefreshCookie = clearRefreshCookie;
    }

    public boolean clearsRefreshCookie() {
        return clearRefreshCookie;
    }
}
