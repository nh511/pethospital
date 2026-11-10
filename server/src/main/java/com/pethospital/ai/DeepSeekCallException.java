package com.pethospital.ai;

public final class DeepSeekCallException extends RuntimeException {
    private final String code;
    private final Integer status;
    private final Long retryAfterSeconds;

    public DeepSeekCallException(String code, String message, Integer status, Long retryAfterSeconds) {
        super(message);
        this.code = code;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public DeepSeekCallException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.status = null;
        this.retryAfterSeconds = null;
    }

    public String code() {
        return code;
    }

    public Integer status() {
        return status;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
