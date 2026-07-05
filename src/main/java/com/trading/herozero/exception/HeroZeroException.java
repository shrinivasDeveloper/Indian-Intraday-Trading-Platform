package com.trading.herozero.exception;

/**
 * HeroZeroException - independent exception type for this module.
 * INDEPENDENCE: not reusing ManualSwingOrderClient.ManualSwingOrderException
 * or any other existing strategy's exception type, per the explicit
 * "completely separate ... Exception Handling" requirement.
 */
public class HeroZeroException extends RuntimeException {
    public HeroZeroException(String message) {
        super(message);
    }
    public HeroZeroException(String message, Throwable cause) {
        super(message, cause);
    }
}