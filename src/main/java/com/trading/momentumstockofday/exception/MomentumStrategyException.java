package com.trading.momentumstockofday.exception;

/** Independent exception type for this strategy - zero shared
 *  exception hierarchy with any other strategy. */
public class MomentumStrategyException extends RuntimeException {
    public MomentumStrategyException(String msg) { super(msg); }
    public MomentumStrategyException(String msg, Throwable cause) { super(msg, cause); }
}