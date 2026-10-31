package de.bwjobs.job;

import de.bwjobs.common.Text;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Tags a posting with IT fields and extracts technology terms.
 * A job counts as IT if the title hits an IT field, or (when the title is neutral)
 * the description mentions at least three distinct technology terms.
 */
public final class ItFieldTaxonomy {
    private ItFieldTaxonomy() {}

    public static final Map<String, Pattern> FIELDS = new LinkedHashMap<>();
    static {
        FIELDS.put("SOFTWARE_ENGINEERING", p("software ?(?:entwicklung|engineering|engineer|developer|entwickler|architektur)|softwareentwickl|\\bentwickler(?:in)?\\b|\\bdeveloper\\b|\\bprogrammier|\\bcoding\\b|\\bsoftware\\b"));
        FIELDS.put("BACKEND", p("backend|back-end|microservice|\\brest ?api|spring boot|\\bnode\\.?js\\b|\\bdjango\\b|\\bapi[- ]entwicklung"));
        FIELDS.put("FRONTEND", p("frontend|front-end|\\breact\\b|\\bangular\\b|\\bvue(?:\\.js)?\\b|web ?entwicklung|web ?development|webentwickler|ui ?developer|ux engineer"));
        FIELDS.put("FULLSTACK", p("full ?stack"));
        FIELDS.put("JAVA", p("\\bjava\\b(?!script)|spring boot|\\bkotlin\\b|\\bjvm\\b|\\bjakarta ee\\b"));
        FIELDS.put("PYTHON", p("\\bpython\\b"));
        FIELDS.put("AI_ML", p("kuenstliche intelligenz|artificial intelligence|\\bki\\b|\\bai\\b|machine learning|maschinelles lernen|deep learning|\\bllms?\\b|large language model|\\bnlp\\b|computer vision|generative ai|\\bgenai\\b|neuronale|neural network|pytorch|tensorflow|reinforcement learning|\\bml\\b|ml ?ops"));
        FIELDS.put("DATA_SCIENCE", p("data scien|data analy|datenanaly|daten ?analyst|\\banalytics\\b|business intelligence|\\bbi\\b|power ?bi|tableau|statistik|statistic|datenwissenschaft"));
        FIELDS.put("DATA_ENGINEERING", p("data engineer|\\betl\\b|apache spark|\\bspark\\b|databricks|data pipeline|big data|data platform|datenbank|database|\\bsql\\b|snowflake|data warehouse"));
        FIELDS.put("QA_TESTING", p("software ?test|softwaretest|testautomati|test automation|\\bqa\\b|quality assurance engineer|software quality|\\btester\\b|testing|selenium|cypress|playwright|test ?engineer|testmanagement|validierung software"));
        FIELDS.put("DEVOPS_CLOUD", p("devops|\\bcloud\\b|\\baws\\b|\\bazure\\b|\\bgcp\\b|kubernetes|\\bdocker\\b|ci/cd|\\bsre\\b|site reliability|platform engineer|infrastructure as code|terraform"));
        FIELDS.put("EMBEDDED", p("embedded|firmware|\\bc\\+\\+|autosar|mikrocontroller|microcontroller|\\brtos\\b|steuergeraet|ecu software|fpga|hardwarenah"));
        FIELDS.put("IT_SECURITY", p("it-?security|it-?sicherheit|cyber ?security|informationssicherheit|security engineer|penetration|pentest|\\bsoc\\b analyst|\\bsiem\\b"));
        FIELDS.put("SAP_DEV", p("\\babap\\b|sap[- ]entwickl|sap developer|s/4 ?hana|sap btp|sap fiori"));
        FIELDS.put("MOBILE", p("\\bandroid\\b|\\bios\\b|\\bswift\\b|flutter|mobile app|app[- ]entwicklung|react native"));
        FIELDS.put("IT_GENERAL", p("\\binformatik|wirtschaftsinformatik|\\bit[- ](?:abteilung|systeme|infrastruktur|projekt|consult|beratung|service|support|administration)|\\bit\\b(?= |$)|digitalisierung|computer science|informationstechnik"));
    }

    /** Department words that make a title clearly non-IT (unless the title also hits an IT field). */
    private static final Pattern NON_IT_TITLE = p(
            "marketing|vertrieb|\\bsales\\b|personal(?:wesen|abteilung)?\\b|human resources|\\bhr\\b|recruiting|finanz|finance|controlling|einkauf|procurement|logistik|logistics|buchhaltung|accounting|steuer|\\btax\\b|\\blegal\\b|recht|kommunikation|communications|\\bpr\\b|event|kaufmaenn|office management|assistenz|produktion(?!ssystem)|fertigung|montage|lager|maschinenbau|konstruktion|verfahrenstechnik|chemie|biolog|pharma|medizin|pflege");

    /** Technology vocabulary for keyword matching (lowercase, normalized). */
    public static final List<String> TECH_TERMS = List.of(
            "java", "spring", "spring boot", "kotlin", "python", "javascript", "typescript", "react", "angular", "vue",
            "node.js", "c++", "c#", ".net", "go", "golang", "rust", "scala", "php", "ruby", "swift", "dart", "flutter",
            "sql", "postgresql", "mysql", "mongodb", "oracle", "redis", "kafka", "spark", "hadoop", "databricks", "snowflake",
            "docker", "kubernetes", "terraform", "ansible", "jenkins", "gitlab", "github", "git", "ci/cd", "linux",
            "aws", "azure", "gcp", "cloud", "microservices", "rest", "graphql", "html", "css",
            "machine learning", "deep learning", "pytorch", "tensorflow", "scikit-learn", "pandas", "numpy", "nlp",
            "computer vision", "llm", "genai", "langchain", "data science", "statistics", "r", "matlab", "simulink",
            "power bi", "tableau", "excel", "etl", "data engineering", "airflow",
            "selenium", "cypress", "playwright", "junit", "testautomatisierung", "test automation", "jira", "confluence",
            "scrum", "agile", "embedded", "autosar", "rtos", "firmware", "fpga", "vhdl", "can", "qt",
            "abap", "sap", "s/4hana", "android", "ios", "security", "devops", "uml", "bash");

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    public record Result(Set<String> fields, Set<String> techTerms, boolean isIt) {}

    public static Result classify(String title, String description) {
        String t = Text.normalize(title);
        String d = Text.normalize(description);
        Set<String> titleFields = new LinkedHashSet<>();
        Set<String> allFields = new LinkedHashSet<>();
        for (var e : FIELDS.entrySet()) {
            if (e.getValue().matcher(t).find()) titleFields.add(e.getKey());
        }
        allFields.addAll(titleFields);
        for (var e : FIELDS.entrySet()) {
            if (!allFields.contains(e.getKey()) && countMatches(e.getValue(), d) >= 2) allFields.add(e.getKey());
        }
        Set<String> tech = techTerms(t + " " + d);

        boolean isIt;
        if (!titleFields.isEmpty()) {
            isIt = true;
        } else if (NON_IT_TITLE.matcher(t).find()) {
            isIt = false;
        } else {
            isIt = tech.size() >= 3 && !allFields.isEmpty();
        }
        if (!isIt) allFields.clear();
        // Generic "IT_GENERAL" adds no information when a specific field is present.
        if (allFields.size() > 1) allFields.remove("IT_GENERAL");
        return new Result(allFields, tech, isIt);
    }

    public static Set<String> techTerms(String text) {
        String n = " " + Text.normalize(text) + " ";
        Set<String> out = new LinkedHashSet<>();
        for (String term : TECH_TERMS) {
            String re = "(?<![a-z0-9])" + Pattern.quote(term) + "(?![a-z0-9+#])";
            if (term.length() <= 2) {
                // very short terms (r, go) only as standalone words in a list-like context
                re = "(?<=[ ,(/])" + Pattern.quote(term) + "(?=[ ,)/])";
                if (term.equals("r") && !n.matches("(?s).*(?:programmiersprache|language|statistik|python|, r,| r,| r ).*")) continue;
            }
            if (Pattern.compile(re).matcher(n).find()) out.add(term);
        }
        return out;
    }

    private static int countMatches(Pattern p, String s) {
        var m = p.matcher(s);
        int c = 0;
        while (m.find() && c < 5) c++;
        return c;
    }
}
