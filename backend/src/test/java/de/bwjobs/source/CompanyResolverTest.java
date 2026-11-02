package de.bwjobs.source;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyResolverTest {
    @Test
    void companyNamesNormalise() {
        assertThat(CompanyResolver.key("Robert Bosch GmbH")).isEqualTo("robert bosch");
        assertThat(CompanyResolver.key("ROBERT BOSCH")).isEqualTo("robert bosch");
        assertThat(CompanyResolver.key("dm-drogerie markt GmbH + Co. KG")).isEqualTo("dm drogerie markt");
        assertThat(CompanyResolver.key("Dr. Ing. h.c. F. Porsche AG")).isEqualTo("dr ing h c f porsche");
        assertThat(CompanyResolver.key("SAP SE")).isEqualTo("sap");
        assertThat(CompanyResolver.key("Würth Elektronik eiSos GmbH & Co. KG")).isEqualTo("wuerth elektronik eisos");
    }

    @Test
    void fingerprintCityIgnoresPostalCodeRegionCountry() {
        assertThat(de.bwjobs.job.JobFingerprint.of(1, "Werkstudent Java (m/w/d)", List.of("70469 Stuttgart, Baden-Württemberg")))
                .isEqualTo(de.bwjobs.job.JobFingerprint.of(1, "Werkstudent Java (w/m/d)", List.of("Stuttgart, Baden-Württemberg, Deutschland")));
    }
}
