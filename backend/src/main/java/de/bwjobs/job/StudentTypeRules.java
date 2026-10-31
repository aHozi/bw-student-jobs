package de.bwjobs.job;

import de.bwjobs.common.Text;

import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Detects student job types from the title (DE + EN). The title is the only reliable
 * signal: descriptions often mention "Werkstudenten" in unrelated benefit sections.
 */
public final class StudentTypeRules {
    private StudentTypeRules() {}

    private static final Pattern THESIS = Pattern.compile(
            "abschlussarbeit|bachelorarbeit|masterarbeit|diplomarbeit|bachelor ?thesis|master ?thesis|bachelor'?s thesis|master'?s thesis|\\bthesis\\b|abschlussthesis");
    private static final Pattern WORKING_STUDENT = Pattern.compile(
            "werkstudent|working student|studentische (?:hilfskraft|mitarbeit|aushilfe)|studentische[rn]? mitarbeiter|student assistant|student job|studentenjob|\\bhiwi\\b|student employee|werkstudierende");
    private static final Pattern INTERNSHIP = Pattern.compile(
            "praktikum|praktikant|internship|\\bintern\\b|\\binterns\\b|pflichtpraktikum|praxissemester|praxisphase|vorpraktikum|werkstudent\\w*/praktik");
    private static final Pattern DUAL = Pattern.compile(
            "duales studium|duale[rsn]? student|dual student|\\bdhbw\\b|studium plus|bachelor of (?:science|engineering|arts) \\(dhbw\\)|dual study");
    private static final Pattern GENERIC_STUDENT = Pattern.compile("\\bstudent(?:in|en|innen)?\\b|\\bstudierende");
    /** Titles that are clearly not student roles even if "student" appears (e.g. recruiting roles). */
    private static final Pattern NOT_STUDENT = Pattern.compile(
            "student recruiting|recruiter|campus manager|hochschulmarketing|teamlead|team lead|head of|\\bsenior\\b|\\bprincipal\\b");

    public static Set<StudentType> detect(String title) {
        String t = Text.normalize(title);
        Set<StudentType> out = EnumSet.noneOf(StudentType.class);
        if (t.isEmpty()) return out;
        if (THESIS.matcher(t).find()) out.add(StudentType.ABSCHLUSSARBEIT);
        if (WORKING_STUDENT.matcher(t).find()) out.add(StudentType.WERKSTUDENT);
        if (INTERNSHIP.matcher(t).find()) out.add(StudentType.PRAKTIKUM);
        if (DUAL.matcher(t).find()) out.add(StudentType.DUALES_STUDIUM);
        if (out.isEmpty() && GENERIC_STUDENT.matcher(t).find()) out.add(StudentType.WERKSTUDENT);
        if (!out.isEmpty() && NOT_STUDENT.matcher(t).find()
                && !WORKING_STUDENT.matcher(t).find() && !INTERNSHIP.matcher(t).find() && !THESIS.matcher(t).find()) {
            out.clear();
        }
        return out;
    }

    /** Cheap pre-filter used before fetching detail pages. */
    public static boolean looksLikeStudentJob(String title) {
        return !detect(title).isEmpty();
    }
}
