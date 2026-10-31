package de.bwjobs.common;

import org.springframework.http.HttpStatus;

/** Exception that maps directly to an HTTP error response. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final Object details;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, Object details) {
        super(message);
        this.status = status;
        this.details = details;
    }

    public HttpStatus status() { return status; }
    public Object details() { return details; }

    public static ApiException notFound(String what) {
        return new ApiException(HttpStatus.NOT_FOUND, what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }

    public static ApiException conflict(String message, Object details) {
        return new ApiException(HttpStatus.CONFLICT, message, details);
    }
}
