package com.trading.dualentry.exception;

/** Own, isolated exception type - zero dependency on Momentum's
 *  MomentumStrategyException, per explicit isolation requirement. */
public class DualEntryStrategyException extends RuntimeException {
    public DualEntryStrategyException(String message) { super(message); }
    public DualEntryStrategyException(String message, Throwable cause) { super(message, cause); }
}