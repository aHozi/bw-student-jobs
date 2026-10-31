package de.bwjobs.job;

/** German requirement of a posting. Ordered from weakest to strongest requirement. */
public enum GermanLevel {
    NONE(0), NICE_TO_HAVE(1), B1(3), B2(4), C1_FLUENT(5), NATIVE(7), UNKNOWN(-1);

    /** Minimum user rank (see LanguageRanks) that satisfies this requirement. */
    public final int requiredRank;

    GermanLevel(int requiredRank) {
        this.requiredRank = requiredRank;
    }
}
