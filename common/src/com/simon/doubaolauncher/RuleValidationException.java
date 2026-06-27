package com.simon.doubaolauncher;

final class RuleValidationException extends Exception {
    RuleValidationException(String message) {
        super(message);
    }

    RuleValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
