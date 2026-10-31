package de.bwjobs.scan.adapters;

import de.bwjobs.company.AggregatorGuard;
import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;
import de.bwjobs.scan.BrowserService;
import de.bwjobs.scan.FetchException;
import de.bwjobs.scan.PoliteHttpClient;
import de.bwjobs.scan.RawJob;
import de.bwjobs.support.MockSite;
import de.bwjobs.support.TestProps;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Each adapter against a local mock of the ATS API (fixtures follow the public API formats). */
class AdapterTest {
    MockSite site;
    PoliteHttpClient http;

    @BeforeEach
    void setUp() throws Exception {
        site = new MockSite();
        http = new PoliteHttpClient(TestProps.props());
    }

    @AfterEach
    void tearDown() {
        site.close();
    }

    static Company company(AtsType type, String tenant, String careerUrl, String website) {
        return new Company(1, "Acme", website, careerUrl, "Stuttgart", "IT", "M", type, tenant, "ACTIVE", null, "SEED",
                12, 0, null, null, null);
    }

    @Test
    void personio() {
        site.on("/xml", "application/xml", """
                <?xml version="1.0" encoding="UTF-8"?>
                <workzag-jobs>
                  <position>
                    <id>1001</id><office>Stuttgart</office>
                    <additionalOffices><office>Remote</office></additionalOffices>
                    <name>Werkstudent Softwareentwicklung (m/w/d)</name>
                    <employmentType>intern</employmentType><schedule>part-time</schedule>
                    <createdAt>2026-10-01T09:00:00+00:00</createdAt>
                    <jobDescriptions>
                      <jobDescription><name>Deine Aufgaben</name><value><![CDATA[<ul><li>Java und Spring Boot</li></ul>]]></value></jobDescription>
                      <jobDescription><name>Dein Profil</name><value><![CDATA[<p>Sehr gute Deutschkenntnisse</p>]]></value></jobDescription>
                    </jobDescriptions>
                  </position>
                </workzag-jobs>""");
        PersonioAdapter.scheme = "http://";
        String host = site.base().replace("http://", "");
        List<RawJob> jobs = new PersonioAdapter(http).list(company(AtsType.PERSONIO, host, null, null));
        assertThat(jobs).hasSize(1);
        RawJob j = jobs.getFirst();
        assertThat(j.title()).isEqualTo("Werkstudent Softwareentwicklung (m/w/d)");
        assertThat(j.locations()).containsExactly("Stuttgart", "Remote");
        assertThat(j.descriptionHtml()).contains("<li>Java und Spring Boot</li>").contains("Sehr gute Deutschkenntnisse");
        assertThat(j.url()).isEqualTo(site.base() + "/job/1001");
        assertThat(j.postedAt()).isNotNull();
        PersonioAdapter.scheme = "https://";
    }

    @Test
    void smartRecruitersListsWithStudentSearchAndLoadsDetails() {
        site.json("/v1/companies/BoschGroup/postings", """
                {"offset":0,"limit":100,"totalFound":1,"content":[
                  {"id":"744000","name":"Werkstudent Data Science (w/m/div.)","releasedDate":"2026-10-20T10:00:00.000Z",
                   "location":{"city":"Renningen","region":"BW","country":"de","remote":false}}]}""");
        site.json("/v1/companies/BoschGroup/postings/744000", """
                {"id":"744000","name":"Werkstudent Data Science (w/m/div.)",
                 "postingUrl":"https://jobs.smartrecruiters.com/BoschGroup/744000","applyUrl":"https://jobs.smartrecruiters.com/BoschGroup/744000/apply",
                 "jobAd":{"sections":{"jobDescription":{"title":"Job Description","text":"<p>Python, Pandas</p>"},
                                      "qualifications":{"title":"Qualifications","text":"<p>Fluent English</p>"}}}}""");
        SmartRecruitersAdapter.apiBase = site.base();
        var adapter = new SmartRecruitersAdapter(http);
        Company c = company(AtsType.SMARTRECRUITERS, "BoschGroup", null, null);
        List<RawJob> jobs = adapter.list(c);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().locations()).containsExactly("Renningen, BW, de");
        RawJob d = adapter.details(c, jobs.getFirst());
        assertThat(d.descriptionHtml()).contains("Python, Pandas").contains("Fluent English");
        assertThat(d.applyUrl()).endsWith("/apply");
        assertThat(site.requests.stream().anyMatch(r -> r.query() != null && r.query().contains("q=Werkstudent"))).isTrue();
        SmartRecruitersAdapter.apiBase = "https://api.smartrecruiters.com";
    }

    @Test
    void greenhouseUnescapesContent() {
        site.json("/v1/boards/acme/jobs", """
                {"jobs":[{"id":42,"title":"Working Student Backend (f/m/d)","absolute_url":"https://boards.greenhouse.io/acme/jobs/42",
                  "location":{"name":"Karlsruhe"},"updated_at":"2026-10-10T10:00:00-04:00",
                  "content":"&lt;p&gt;Kotlin &amp;amp; Java&lt;/p&gt;"}]}""");
        GreenhouseAdapter.apiBase = site.base();
        List<RawJob> jobs = new GreenhouseAdapter(http).list(company(AtsType.GREENHOUSE, "acme", null, null));
        assertThat(jobs.getFirst().descriptionHtml()).contains("<p>Kotlin &amp; Java</p>");
        assertThat(jobs.getFirst().detailsLoaded()).isTrue();
        GreenhouseAdapter.apiBase = "https://boards-api.greenhouse.io";
    }

    @Test
    void leverEuTenant() {
        site.json("/v0/postings/acme", """
                [{"id":"abc","text":"Praktikum QA Automation","hostedUrl":"https://jobs.eu.lever.co/acme/abc","applyUrl":"https://jobs.eu.lever.co/acme/abc/apply",
                  "categories":{"location":"Heidelberg","commitment":"Internship"},"createdAt":1760000000000,
                  "description":"<p>Selenium</p>","lists":[{"text":"Requirements","content":"<li>Cypress</li>"}],"workplaceType":"hybrid"}]""");
        LeverAdapter.euApiBase = site.base();
        List<RawJob> jobs = new LeverAdapter(http).list(company(AtsType.LEVER, "eu/acme", null, null));
        assertThat(jobs.getFirst().title()).isEqualTo("Praktikum QA Automation");
        assertThat(jobs.getFirst().descriptionHtml()).contains("Selenium").contains("Cypress");
        assertThat(jobs.getFirst().applyUrl()).endsWith("/apply");
        LeverAdapter.euApiBase = "https://api.eu.lever.co";
    }

    @Test
    void recruitee() {
        site.json("/api/offers/", """
                {"offers":[{"id":7,"title":"Werkstudent DevOps","careers_url":"https://acme.recruitee.com/o/devops","careers_apply_url":"https://acme.recruitee.com/o/devops/c/new",
                  "location":"Freiburg","description":"<p>Kubernetes</p>","requirements":"<p>Docker</p>","published_at":"2026-10-01 10:00:00 UTC","remote":true}]}""");
        RecruiteeAdapter.scheme = "http://";
        RecruiteeAdapter.hostSuffix = "";
        List<RawJob> jobs = new RecruiteeAdapter(http).list(company(AtsType.RECRUITEE, site.base().replace("http://", ""), null, null));
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().descriptionHtml()).contains("Kubernetes").contains("Docker");
        assertThat(jobs.getFirst().remote()).isTrue();
        assertThat(jobs.getFirst().applyUrl()).endsWith("/c/new");
        RecruiteeAdapter.hostSuffix = ".recruitee.com";
        RecruiteeAdapter.scheme = "https://";
    }

    @Test
    void workdaySearchAndDetails() {
        String host = site.base().replace("http://", "");
        site.on("POST /wday/cxs/acme/Acme_Careers/jobs", "application/json", """
                {"total":1,"jobPostings":[{"title":"Werkstudent (m/w/d) KI-Entwicklung","externalPath":"/job/Stuttgart/Werkstudent-KI_R-1",
                  "locationsText":"Stuttgart","bulletFields":["R-1"]}]}""");
        site.json("/wday/cxs/acme/Acme_Careers/job/Stuttgart/Werkstudent-KI_R-1", """
                {"jobPostingInfo":{"id":"x","title":"Werkstudent (m/w/d) KI-Entwicklung","jobDescription":"<p>PyTorch, LLMs</p>",
                  "location":"Stuttgart","additionalLocations":["Ludwigsburg"],"startDate":"2026-10-15","externalUrl":"https://acme.wd3.myworkdayjobs.com/Acme_Careers/job/Stuttgart/Werkstudent-KI_R-1"}}""");
        WorkdayAdapter.scheme = "http://";
        var adapter = new WorkdayAdapter(http);
        Company c = company(AtsType.WORKDAY, host + "|acme|Acme_Careers", null, null);
        List<RawJob> jobs = adapter.list(c);
        assertThat(jobs).hasSize(1);
        RawJob d = adapter.details(c, jobs.getFirst());
        assertThat(d.descriptionHtml()).contains("PyTorch");
        assertThat(d.locations()).containsExactly("Stuttgart", "Ludwigsburg");
        assertThat(site.requests.stream().filter(r -> r.method().equals("POST")).findFirst().get().bodyText()).contains("\"searchText\"");
        WorkdayAdapter.scheme = "https://";
    }

    @Test
    void successFactorsCareerSiteBuilder() {
        site.html("/search/", """
                <html><body><table><tr class="data-row">
                  <td><a class="jobTitle-link" href="/job/Walldorf-Working-Student-Java-Developer/1234567/">Working Student Java Developer (f/m/d)</a></td>
                  <td><span class="jobLocation">Walldorf, DE, 69190</span></td></tr></table></body></html>""");
        site.html("/job/Walldorf-Working-Student-Java-Developer/1234567/", """
                <html><body><span itemprop="description"><p>We are looking for a working student. You know Java and Spring Boot and you speak fluent English.
                Our team builds cloud services, you will write tests and code reviews and learn a lot about enterprise software development.
                German is nice to have. Long enough description text for the extractor to accept this element as the job description.</p></span>
                <meta itemprop="datePosted" content="2026-10-21"/><a class="apply" href="/talentcommunity/apply/1234567/">Apply now</a></body></html>""");
        var adapter = new SuccessFactorsAdapter(http);
        Company c = company(AtsType.SUCCESSFACTORS, site.base(), site.base(), null);
        List<RawJob> jobs = adapter.list(c);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst().locations()).containsExactly("Walldorf, DE, 69190");
        RawJob d = adapter.details(c, jobs.getFirst());
        assertThat(d.descriptionHtml()).contains("Spring Boot");
        assertThat(d.applyUrl()).contains("/apply/");
        assertThat(d.postedAt()).isNotNull();
    }

    @Test
    void genericPageFindsStudentLinksAndJsonLdButSkipsAggregatorsAndOtherSites() {
        site.html("/karriere", """
                <html><body><h1>Karriere bei Acme</h1>
                <ul>
                  <li class="job"><a href="/jobs/werkstudent-java">Werkstudent Java Entwicklung (m/w/d)</a><span class="location">Stuttgart</span></li>
                  <li class="job"><a href="/jobs/senior-dev">Senior Software Engineer</a></li>
                  <li class="job"><a href="https://www.linkedin.com/jobs/view/1">Praktikum Data Science (LinkedIn)</a></li>
                  <li class="job"><a href="https://www.some-other-company.de/jobs/1">Werkstudent bei anderer Firma</a></li>
                </ul>
                <script type="application/ld+json">{"@context":"https://schema.org","@type":"JobPosting","title":"Abschlussarbeit KI",
                  "url":"{{BASE}}/jobs/thesis-ki","description":"<p>Machine Learning thesis with Python and PyTorch for predictive maintenance, including data pipelines and evaluation of models on real production data. Fluent German.</p>",
                  "datePosted":"2026-10-01","jobLocation":{"@type":"Place","address":{"addressLocality":"Karlsruhe","postalCode":"76131","addressCountry":"DE"}}}</script>
                </body></html>""");
        site.html("/jobs/werkstudent-java", """
                <html><body><main><h1>Werkstudent Java Entwicklung</h1><p>Du entwickelst Microservices mit Java und Spring Boot.
                Du hast gute Deutschkenntnisse und Lust auf Clean Code, Tests und Teamarbeit in einem agilen Team mit Scrum.
                Wir bieten flexible Arbeitszeiten, Homeoffice und eine faire Vergütung für Studierende.</p>
                <a href="/apply/werkstudent-java">Jetzt bewerben</a></main></body></html>""");
        var adapter = new GenericCareerPageAdapter(http, new BrowserService(TestProps.props()), new AggregatorGuard(), TestProps.props());
        Company c = company(AtsType.GENERIC, null, site.base() + "/karriere", site.base());
        List<RawJob> jobs = adapter.list(c);
        assertThat(jobs).extracting(RawJob::title)
                .containsExactlyInAnyOrder("Werkstudent Java Entwicklung (m/w/d)", "Abschlussarbeit KI");
        RawJob java = jobs.stream().filter(j -> j.title().startsWith("Werkstudent")).findFirst().orElseThrow();
        assertThat(java.locations()).containsExactly("Stuttgart");
        RawJob d = adapter.details(c, java);
        assertThat(d.descriptionHtml()).contains("Spring Boot");
        assertThat(d.applyUrl()).isEqualTo(site.base() + "/apply/werkstudent-java");
        RawJob thesis = jobs.stream().filter(j -> j.title().startsWith("Abschluss")).findFirst().orElseThrow();
        assertThat(thesis.locations().getFirst()).contains("Karlsruhe");
    }

    @Test
    void robotsTxtIsRespected() {
        site.on("/robots.txt", "text/plain", "User-agent: *\nDisallow: /private/\n");
        site.html("/private/jobs", "<html></html>");
        assertThatThrownBy(() -> http.get(site.base() + "/private/jobs")).isInstanceOf(FetchException.class)
                .hasMessageContaining("robots.txt");
    }
}
