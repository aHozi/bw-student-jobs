package de.bwjobs.job;

import de.bwjobs.common.Text;

import java.util.regex.Pattern;

/**
 * Extracts German/English requirements from a job description using sentence-level rules.
 * The result is only used for user-controlled filters, never to drop jobs.
 */
public final class LanguageRequirementExtractor {
    private LanguageRequirementExtractor() {}

    public record Result(GermanLevel german, EnglishLevel english, String postingLanguage) {}

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?;•·\\n])\\s+|\\n+| - | – ");
    private static final Pattern GERMAN_WORD = Pattern.compile("\\bdeutsch|\\bgerman\\b(?!y)|\\bgerman(?:-| )(?:language|skills)|\\bgerman,");
    private static final Pattern ENGLISH_WORD = Pattern.compile("\\benglisch|\\benglish\\b");

    private static final Pattern NATIVE = Pattern.compile("muttersprach|native|first language|erstsprache");
    private static final Pattern FLUENT = Pattern.compile(
            "fliessend|verhandlungssicher|fluent|fluency|\\bc1\\b|\\bc2\\b|sehr gut|very good|excellent|hervorragend|ausgezeichnet|business level|proficient|exzellent|perfekt|einwandfrei|professional working");
    private static final Pattern GOOD = Pattern.compile("\\bgute?n?\\b|\\bgood\\b|\\bb2\\b|solid|fundiert|strong|sicher");
    private static final Pattern BASIC = Pattern.compile("grundkenntnis|basic|\\bb1\\b|\\ba2\\b|erste kenntnisse|elementary|conversational");
    private static final Pattern OPTIONAL = Pattern.compile(
            "von vorteil|wuenschenswert|\\bplus\\b|nice to have|nice-to-have|is an advantage|an asset|beneficial|optional|bonus|would be great|willkommen|not required|nicht erforderlich|nicht notwendig|no german|kein deutsch|not necessary|not mandatory|keine voraussetzung");
    private static final Pattern NOT_REQUIRED = Pattern.compile(
            "not required|nicht erforderlich|nicht notwendig|no german|kein deutsch|not necessary|not mandatory|keine voraussetzung|without german");
    private static final Pattern ENGLISH_WORKING_LANGUAGE = Pattern.compile(
            "(?:working|company|team|business|corporate|official) language is english|english is our (?:working|company|team|main)|english-speaking (?:team|environment)|international(?:es)? team.{0,40}english");

    public static Result extract(String title, String description) {
        String raw = (title == null ? "" : title) + ".\n" + (description == null ? "" : description);
        String text = Text.normalize(raw);
        String postingLanguage = detectPostingLanguage(description == null || description.isBlank() ? title : description);

        GermanLevel german = GermanLevel.UNKNOWN;
        EnglishLevel english = EnglishLevel.UNKNOWN;
        boolean englishWorking = ENGLISH_WORKING_LANGUAGE.matcher(text).find();

        for (String sentence : SENTENCE_SPLIT.split(raw)) {
            String s = Text.normalize(sentence);
            if (s.isEmpty() || s.length() > 400) {
                if (s.length() > 400) s = s.substring(0, 400);
                else continue;
            }
            boolean mentionsGerman = GERMAN_WORD.matcher(s).find() && !s.contains("deutschland");
            boolean mentionsEnglish = ENGLISH_WORD.matcher(s).find();
            if (!mentionsGerman && !mentionsEnglish) continue;

            if (mentionsGerman) {
                GermanLevel g = germanLevelOf(s, mentionsEnglish);
                german = stronger(german, g);
            }
            if (mentionsEnglish) {
                EnglishLevel e = englishLevelOf(s);
                english = stronger(english, e);
            }
        }

        if (german == GermanLevel.UNKNOWN && englishWorking) german = GermanLevel.NONE;
        if (englishWorking) english = stronger(english, EnglishLevel.FLUENT);
        if (english == EnglishLevel.UNKNOWN && "EN".equals(postingLanguage)) english = EnglishLevel.GOOD;
        return new Result(german, english, postingLanguage);
    }

    private static GermanLevel germanLevelOf(String s, boolean alsoEnglish) {
        // In "fliessend Deutsch, Englisch von Vorteil" the optional part belongs to English only.
        String part = clauseAround(s, GERMAN_WORD);
        if (NOT_REQUIRED.matcher(part).find()) return GermanLevel.NONE;
        if (OPTIONAL.matcher(part).find() && !FLUENT.matcher(part).find() && !NATIVE.matcher(part).find()) return GermanLevel.NICE_TO_HAVE;
        if (NATIVE.matcher(part).find()) return GermanLevel.NATIVE;
        if (FLUENT.matcher(part).find()) return GermanLevel.C1_FLUENT;
        if (BASIC.matcher(part).find()) return GermanLevel.B1;
        if (GOOD.matcher(part).find()) return GermanLevel.B2;
        if (part.matches(".*(?:kenntnisse|skills|knowledge|sprachkenntnisse|language).*")) return GermanLevel.B2;
        return GermanLevel.UNKNOWN;
    }

    private static EnglishLevel englishLevelOf(String s) {
        String part = clauseAround(s, ENGLISH_WORD);
        if (OPTIONAL.matcher(part).find() && !FLUENT.matcher(part).find()) return EnglishLevel.NICE_TO_HAVE;
        if (FLUENT.matcher(part).find() || NATIVE.matcher(part).find()) return EnglishLevel.FLUENT;
        if (GOOD.matcher(part).find() || BASIC.matcher(part).find()) return EnglishLevel.GOOD;
        if (part.matches(".*(?:kenntnisse|skills|knowledge|language).*")) return EnglishLevel.GOOD;
        return EnglishLevel.UNKNOWN;
    }

    /**
     * Returns the clause of a sentence that contains the language word. Clauses are split on
     * commas and "and/und/sowie" only when both languages are in the same sentence.
     */
    private static String clauseAround(String s, Pattern word) {
        String[] clauses = s.split(",|\\bsowie\\b|\\bwhile\\b|\\bbut\\b|\\baber\\b|\\bwaehrend\\b");
        if (clauses.length == 1) return s;
        for (int i = 0; i < clauses.length; i++) {
            if (word.matcher(clauses[i]).find()) {
                // "sehr gute Deutsch- und Englischkenntnisse": keep the whole clause
                return clauses[i];
            }
        }
        return s;
    }

    private static GermanLevel stronger(GermanLevel current, GermanLevel candidate) {
        if (candidate == GermanLevel.UNKNOWN) return current;
        if (current == GermanLevel.UNKNOWN) return candidate;
        return candidate.requiredRank > current.requiredRank ? candidate : current;
    }

    private static EnglishLevel stronger(EnglishLevel current, EnglishLevel candidate) {
        if (candidate == EnglishLevel.UNKNOWN) return current;
        if (current == EnglishLevel.UNKNOWN) return candidate;
        return candidate.requiredRank > current.requiredRank ? candidate : current;
    }

    private static final Pattern DE_WORDS = Pattern.compile("\\b(?:und|der|die|das|mit|fuer|wir|sie|ist|bei|ein|eine|du|dich|deine|ihre|unser|oder|auf|von)\\b");
    private static final Pattern EN_WORDS = Pattern.compile("\\b(?:and|the|with|for|we|you|is|are|our|your|of|to|or|on|will)\\b");

    public static String detectPostingLanguage(String text) {
        String n = Text.normalize(text);
        int de = count(DE_WORDS, n);
        int en = count(EN_WORDS, n);
        if (de == 0 && en == 0) return null;
        return de >= en ? "DE" : "EN";
    }

    private static int count(Pattern p, String s) {
        var m = p.matcher(s);
        int c = 0;
        while (m.find()) c++;
        return c;
    }
}
