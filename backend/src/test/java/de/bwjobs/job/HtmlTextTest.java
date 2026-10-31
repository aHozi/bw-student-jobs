package de.bwjobs.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlTextTest {
    @Test
    void sanitizesAndMakesLinksAbsolute() {
        String safe = HtmlText.safeHtml("<p onclick=\"x()\">Hi <a href=\"/apply/1\">Jetzt bewerben</a></p><script>alert(1)</script>",
                "https://karriere.example.de/jobs/1");
        assertThat(safe).contains("href=\"https://karriere.example.de/apply/1\"").contains("target=\"_blank\"")
                .doesNotContain("script").doesNotContain("onclick");
    }

    @Test
    void textKeepsLineBreaksAndBullets() {
        assertThat(HtmlText.toText("<h3>Profil</h3><ul><li>Java</li><li>SQL</li></ul>")).isEqualTo("Profil\n\n• Java\n\n• SQL");
    }
}
