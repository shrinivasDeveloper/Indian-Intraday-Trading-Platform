package com.trading.execution.client;

public class OrderException extends RuntimeException {
    public OrderException(String msg) { super(msg); }
    public OrderException(String msg, Throwable cause) { super(msg, cause); }
}
