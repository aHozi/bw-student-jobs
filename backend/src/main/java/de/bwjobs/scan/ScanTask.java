package de.bwjobs.scan;

public record ScanTask(long id, long companyId, String kind, int attempts) {}
