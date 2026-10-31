package de.bwjobs.profile;

public final class LanguageRanks {
    private LanguageRanks() {}

    public static int rank(String level) {
        if (level == null) return 0;
        return switch (level.trim().toUpperCase()) {
            case "A1" -> 1;
            case "A2" -> 2;
            case "B1" -> 3;
            case "B2" -> 4;
            case "C1", "FLUENT" -> 5;
            case "C2" -> 6;
            case "NATIVE", "MUTTERSPRACHE" -> 7;
            default -> 0;
        };
    }
}
