package de.bwjobs.scan;

public class FetchException extends RuntimeException {
    private final int status;

    public FetchException(String message, int status) {
        super(message);
        this.status = status;
    }

    public FetchException(String message, Throwable cause) {
        super(message, cause);
        this.status = -1;
    }

    public int status() { return status; }
}
