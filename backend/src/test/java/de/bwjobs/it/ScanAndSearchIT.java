package de.bwjobs.it;

import com.fasterxml.jackson.databind.JsonNode;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.company.CompanyRepository;
import de.bwjobs.scan.ScanService;
import de.bwjobs.support.MockSite;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Full pipeline: official career page → classify → store → search with user-chosen filters → close vanished jobs. */
class ScanAndSearchIT extends IntegrationTestBase {
    @Autowired CompanyRepository companies;
    @Autowired ScanService scanService;

    static String careerPage(boolean withJava) {
        return """
                <html><body><h1>Karriere</h1><ul>
                """ + (withJava ? "<li><a href=\"/jobs/java\">Werkstudent Java Backend (m/w/d)</a><span class=\"location\">70173 Stuttgart</span></li>" : "") + """
                <li><a href="/jobs/marketing">Werkstudent Marketing (m/w/d)</a><span class="location">Stuttgart</span></li>
                <li><a href="/jobs/senior">Senior Java Developer (m/w/d)</a></li>
                <li><a href="/jobs/ds-muc">Praktikum Data Science (m/w/d)</a><span class="location">München</span></li>
                <li><a href="https://www.stepstone.de/stellenangebote--Werkstudent-IT--123.html">Werkstudent IT (via StepStone)</a></li>
                </ul>
                <script type="application/ld+json">{"@context":"https://schema.org","@type":"JobPosting","title":"Master Thesis Machine Learning",
                 "url":"{{BASE}}/jobs/thesis","datePosted":"2026-10-20",
                 "description":"<p>We are looking for a master student for a thesis on machine learning with Python and PyTorch. You will build data pipelines and evaluate deep learning models. Fluent English is required, German is a plus.</p>",
                 "jobLocation":{"@type":"Place","address":{"addressLocality":"Karlsruhe","postalCode":"76131","addressCountry":"DE"}}}</script>
                </body></html>""";
    }

    @Test
    void scansFiltersAndSearches() throws Exception {
        try (MockSite site = new MockSite()) {
            site.html("/karriere", careerPage(true));
            site.html("/jobs/java", "<html><body><main><h1>Werkstudent Java Backend</h1><p>Du entwickelst Microservices mit Java und Spring Boot, schreibst Tests mit JUnit und arbeitest mit Docker. Sehr gute Deutschkenntnisse und gute Englischkenntnisse sind erforderlich. Wir bieten flexible Arbeitszeiten und spannende Projekte.</p></main></body></html>");
            site.html("/jobs/marketing", "<html><body><main><p>Social Media, Kampagnen, Excel. Du unterstützt unser Marketing-Team bei Events und Kommunikation, Content-Erstellung und Kampagnenplanung über alle Kanäle hinweg.</p></main></body></html>");
            site.html("/jobs/ds-muc", "<html><body><main><p>Data Science mit Python, Pandas und SQL. Gute Deutschkenntnisse. Du analysierst Daten, baust Dashboards mit Power BI und unterstützt das Team bei Machine-Learning-Projekten.</p></main></body></html>");

            Company c = companies.insert("Acme Test GmbH", site.base(), site.base() + "/karriere", "Stuttgart", "Software", "M",
                    AtsType.GENERIC, null, "ACTIVE", "ADMIN", 12);

            ScanService.Outcome first = scanService.scan(c.id());
            assertThat(first.ok()).isTrue();
            assertThat(first.stats().accepted()).isEqualTo(3); // java, data science (München), thesis
            List<String> titles = jdbc.sql("select title from job_posting where status='OPEN' order by title").query(String.class).list();
            assertThat(titles).containsExactly("Master Thesis Machine Learning", "Praktikum Data Science (m/w/d)", "Werkstudent Java Backend (m/w/d)");

            // No job ever comes from an aggregator
            long aggregatorUrls = jdbc.sql("select count(*) from job_posting where url ilike '%stepstone%' or url ilike '%linkedin%' or url ilike '%indeed%'")
                    .query(Long.class).single();
            assertThat(aggregatorUrls).isZero();

            // Language and location tagging
            assertThat(jdbc.sql("select german_level from job_posting where title like 'Werkstudent Java%'").query(String.class).single()).isEqualTo("C1_FLUENT");
            assertThat(jdbc.sql("select german_level || '/' || english_level from job_posting where title like 'Master Thesis%'").query(String.class).single())
                    .isEqualTo("NICE_TO_HAVE/FLUENT");
            assertThat(jdbc.sql("select in_bw from job_posting where title like 'Praktikum Data%'").query(Boolean.class).single()).isFalse();

            String token = register("student@example.org");
            uploadCv(token, CV_TEXT);
            setProfile(token, "{\"skills\":[\"Java\",\"Spring Boot\",\"Docker\"],\"languages\":[{\"language\":\"German\",\"level\":\"NATIVE\"},{\"language\":\"English\",\"level\":\"C1\"}]}");

            // Default: BW + remote → München job hidden by location (not by language)
            JsonNode res = getJson(token, "/api/jobs");
            assertThat(res.get("total").asInt()).isEqualTo(2);
            assertThat(res.get("items").get(0).get("title").asText()).isEqualTo("Werkstudent Java Backend (m/w/d)"); // best skill match first
            assertThat(getJson(token, "/api/jobs?locationMode=ALL").get("total").asInt()).isEqualTo(3);

            // A user who only accepts jobs without German requirement
            JsonNode englishOnly = getJson(token, "/api/jobs?locationMode=ALL&germanLevels=NONE,NICE_TO_HAVE&includeUnknownLanguage=false");
            assertThat(titlesOf(englishOnly)).containsExactly("Master Thesis Machine Learning");
            // Accepting B2 and fluent too shows the others again
            assertThat(getJson(token, "/api/jobs?locationMode=ALL&germanLevels=NONE,NICE_TO_HAVE,B2,C1_FLUENT").get("total").asInt()).isEqualTo(3);
            // Type and field filters
            assertThat(titlesOf(getJson(token, "/api/jobs?studentTypes=ABSCHLUSSARBEIT"))).containsExactly("Master Thesis Machine Learning");
            assertThat(titlesOf(getJson(token, "/api/jobs?itFields=JAVA"))).containsExactly("Werkstudent Java Backend (m/w/d)");

            // The Java job disappears: one miss keeps it open, the second closes it
            site.html("/karriere", careerPage(false));
            scanService.scan(c.id());
            assertThat(jdbc.sql("select status from job_posting where title like 'Werkstudent Java%'").query(String.class).single()).isEqualTo("OPEN");
            scanService.scan(c.id());
            assertThat(jdbc.sql("select status from job_posting where title like 'Werkstudent Java%'").query(String.class).single()).isEqualTo("CLOSED");
            assertThat(getJson(token, "/api/jobs").get("total").asInt()).isEqualTo(1);

            // Scan log is written
            assertThat(jdbc.sql("select count(*) from scan_run_log where company_id = ?").param(c.id()).query(Long.class).single()).isEqualTo(3);
        }
    }

    @Test
    void unreachableCareerPageMarksCompanyForReview() {
        Company c = companies.insert("Offline GmbH", "http://127.0.0.1:1", null, "Ulm", "IT", "S", AtsType.UNKNOWN, null, "ACTIVE", "ADMIN", 12);
        ScanService.Outcome o = scanService.scan(c.id());
        assertThat(o.ok()).isFalse();
        Company after = companies.findById(c.id()).orElseThrow();
        assertThat(after.status()).isEqualTo("NEEDS_REVIEW");
        assertThat(after.consecutiveFailures()).isEqualTo(1);
    }

    private static List<String> titlesOf(JsonNode res) {
        List<String> out = new ArrayList<>();
        res.get("items").forEach(i -> out.add(i.get("title").asText()));
        return out;
    }
}
