package com.petr.exception;

import lombok.Getter;

@Getter
public class RetryAttemptsLeftException extends RuntimeException {
    private final int attempts;

    public RetryAttemptsLeftException(String message, int attempts) {
        super(message);
        this.attempts = attempts;
    }
}
