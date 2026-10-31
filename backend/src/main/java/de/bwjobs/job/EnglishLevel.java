package de.bwjobs.job;

public enum EnglishLevel {
    NONE(0), NICE_TO_HAVE(1), GOOD(4), FLUENT(5), UNKNOWN(-1);

    public final int requiredRank;

    EnglishLevel(int requiredRank) {
        this.requiredRank = requiredRank;
    }
}
