package com.trading.auth.service;

public class AuthException extends RuntimeException {
    public AuthException(String msg) { super(msg); }
    public AuthException(String msg, Throwable cause) { super(msg, cause); }
}
