package de.bwjobs.application.formfill;

import de.bwjobs.common.Text;
import de.bwjobs.profile.UserProfile;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/** Maps an application form field (by its label/name/type) to a value from the user's profile and documents. */
public final class FieldMapper {
    private FieldMapper() {}

    public enum Kind { TEXT, FILE, CONSENT, NEEDS_INPUT, SKIP }

    public record Mapped(Kind kind, String value, List<Path> files) {
        static Mapped text(String v) { return v == null || v.isBlank() ? null : new Mapped(Kind.TEXT, v, List.of()); }
        static Mapped files(List<Path> f) { return f == null || f.isEmpty() ? null : new Mapped(Kind.FILE, null, f); }
        static final Mapped CONSENT = new Mapped(Kind.CONSENT, null, List.of());
        static final Mapped NEEDS_INPUT = new Mapped(Kind.NEEDS_INPUT, null, List.of());
        static final Mapped SKIP = new Mapped(Kind.SKIP, null, List.of());
    }

    /** Files and texts available for this application. */
    public record Material(UserProfile profile, Path cv, Path coverLetter, List<Path> extras, String coverLetterText) {}

    private static Pattern p(String re) {
        return Pattern.compile(re);
    }

    private static final Pattern CV = p("lebenslauf|\\bcv\\b|resume|curriculum|vita");
    private static final Pattern COVER = p("anschreiben|cover ?letter|motivationsschreiben|motivation letter|begleitschreiben");
    private static final Pattern ATTACH = p("zeugnis|transcript|certificate|notenspiegel|anlage|attachment|dokument|document|unterlagen|weitere|additional|sonstige|upload|datei|file");
    private static final Pattern CONSENT = p("datenschutz|privacy|einwilligung|consent|zustimm|agree|akzeptier|accept|terms|agb|nutzungsbedingungen|dsgvo|gdpr");
    private static final Pattern TALENT_POOL = p("talent ?pool|talentpool|newsletter|job ?alert|weitere stellen|future opportunities|marketing");
    private static final Pattern FIRST = p("vorname|first ?name|given ?name|firstname");
    private static final Pattern LAST = p("nachname|last ?name|surname|family ?name|lastname|familienname");
    private static final Pattern FULL = p("^(?:ihr |your |full |vollstaendiger )?name\\b|^name$|full ?name|vor- und nachname");
    private static final Pattern EMAIL = p("e-?mail");
    private static final Pattern PHONE = p("telefon|phone|mobil|handy|\\btel\\b|rufnummer");
    private static final Pattern ZIP = p("\\bplz\\b|postleitzahl|zip|postal");
    private static final Pattern CITY = p("\\bort\\b|stadt|\\bcity\\b|wohnort|town");
    private static final Pattern STREET = p("strasse|street|adresse|address|hausnummer");
    private static final Pattern LINKEDIN = p("linkedin");
    private static final Pattern GITHUB = p("github|gitlab");
    private static final Pattern WEBSITE = p("website|webseite|portfolio|homepage|url");
    private static final Pattern UNIVERSITY = p("hochschule|universitaet|university|\\buni\\b|college|school");
    private static final Pattern PROGRAM = p("studiengang|studienfach|field of study|major|degree program|studienrichtung");
    private static final Pattern SEMESTER = p("semester|fachsemester");
    private static final Pattern START = p("eintritt|verfuegbar ab|start ?date|available from|fruehest|startdatum|beginn|availability");
    private static final Pattern HOURS = p("wochenstunden|stunden pro woche|hours per week|wochenarbeitszeit|stunden/woche");
    private static final Pattern MESSAGE = p("anschreiben|cover ?letter|nachricht|message|motivation|why|warum|kommentar|comment|bemerkung");
    private static final Pattern NEEDS_USER = p("gehalt|salary|verguetung|anrede|salutation|geschlecht|gender|titel|title|nationalit|staatsangeh|arbeitserlaubnis|work permit|visa|wie sind sie|how did you hear|quelle|source|geburts|birth");

    public static Mapped map(String label, String name, String type, boolean multiple, int fileInputsOnPage, Material m) {
        String l = Text.normalize(label + " " + (name == null ? "" : name.replace('_', ' ').replace('-', ' ')));
        UserProfile p = m.profile();
        String t = type == null ? "text" : type.toLowerCase();

        if (t.equals("file")) {
            if (COVER.matcher(l).find()) return m.coverLetter() != null ? Mapped.files(List.of(m.coverLetter())) : Mapped.NEEDS_INPUT;
            if (CV.matcher(l).find()) return Mapped.files(List.of(m.cv()));
            if (ATTACH.matcher(l).find() || fileInputsOnPage == 1) {
                // Single upload field: send CV (+ cover letter + extras if the field accepts several files)
                if (multiple || fileInputsOnPage == 1) {
                    List<Path> all = new java.util.ArrayList<>();
                    if (fileInputsOnPage == 1) all.add(m.cv());
                    if (fileInputsOnPage == 1 && m.coverLetter() != null) all.add(m.coverLetter());
                    all.addAll(m.extras());
                    if (!multiple && !all.isEmpty()) all = List.of(all.getFirst());
                    return all.isEmpty() ? Mapped.SKIP : Mapped.files(all);
                }
                return m.extras().isEmpty() ? Mapped.SKIP : Mapped.files(List.of(m.extras().getFirst()));
            }
            return Mapped.SKIP;
        }
        if (t.equals("checkbox")) {
            if (TALENT_POOL.matcher(l).find()) return Mapped.SKIP;
            if (CONSENT.matcher(l).find()) return Mapped.CONSENT;
            return Mapped.SKIP;
        }
        if (t.equals("radio") || t.equals("select-one") || t.equals("select-multiple")) {
            return NEEDS_USER.matcher(l).find() ? Mapped.NEEDS_INPUT : Mapped.SKIP;
        }
        if (t.equals("hidden") || t.equals("submit") || t.equals("button") || t.equals("password") || t.equals("search")) return Mapped.SKIP;

        if (EMAIL.matcher(l).find() || t.equals("email")) return orNeeds(p.email());
        if (FIRST.matcher(l).find()) return orNeeds(p.firstName());
        if (LAST.matcher(l).find()) return orNeeds(p.lastName());
        if (FULL.matcher(l).find()) return orNeeds(p.fullName());
        if (PHONE.matcher(l).find() || t.equals("tel")) return orNeeds(p.phone());
        if (ZIP.matcher(l).find()) return orNeeds(p.postalCode());
        if (STREET.matcher(l).find()) return orNeeds(p.street());
        if (CITY.matcher(l).find()) return orNeeds(p.city());
        if (LINKEDIN.matcher(l).find()) return orSkip(p.linkedinUrl());
        if (GITHUB.matcher(l).find()) return orSkip(p.githubUrl());
        if (UNIVERSITY.matcher(l).find()) return orNeeds(p.university());
        if (PROGRAM.matcher(l).find()) return orNeeds(p.studyProgram());
        if (SEMESTER.matcher(l).find()) return orNeeds(p.semester() == null ? null : p.semester().toString());
        if (START.matcher(l).find()) return orNeeds(p.availableFrom());
        if (HOURS.matcher(l).find()) return orNeeds(p.hoursPerWeek() == null ? null : p.hoursPerWeek().toString());
        if (WEBSITE.matcher(l).find() || t.equals("url")) return orSkip(p.websiteUrl() != null ? p.websiteUrl() : p.githubUrl());
        if (NEEDS_USER.matcher(l).find()) return Mapped.NEEDS_INPUT;
        if (t.equals("textarea") && MESSAGE.matcher(l).find()) return orSkip(m.coverLetterText());
        return null; // unknown: caller decides by "required"
    }

    private static Mapped orNeeds(String v) {
        Mapped m = Mapped.text(v);
        return m != null ? m : Mapped.NEEDS_INPUT;
    }

    private static Mapped orSkip(String v) {
        Mapped m = Mapped.text(v);
        return m != null ? m : Mapped.SKIP;
    }
}
