package de.bwjobs.scan;

public record FetchResult(int status, String body, String finalUrl, String contentType) {
    public boolean ok() { return status >= 200 && status < 300; }
}
