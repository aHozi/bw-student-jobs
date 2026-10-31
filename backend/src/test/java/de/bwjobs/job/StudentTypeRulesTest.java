package de.bwjobs.job;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class StudentTypeRulesTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Werkstudent Softwareentwicklung Java (m/w/d)                  | WERKSTUDENT",
            "Working Student Data Science (f/m/div.)                        | WERKSTUDENT",
            "Studentische Hilfskraft im Bereich IT                         | WERKSTUDENT",
            "Praktikum Backend-Entwicklung ab März 2027                     | PRAKTIKUM",
            "Internship Machine Learning Engineering                       | PRAKTIKUM",
            "Intern - QA Automation                                         | PRAKTIKUM",
            "Pflichtpraktikum Informatik                                    | PRAKTIKUM",
            "Abschlussarbeit (Bachelor/Master) KI in der Produktion         | ABSCHLUSSARBEIT",
            "Masterarbeit: LLM-basierte Testgenerierung                    | ABSCHLUSSARBEIT",
            "Master Thesis Computer Vision                                  | ABSCHLUSSARBEIT",
            "Duales Studium Informatik (DHBW) 2027                          | DUALES_STUDIUM",
            "Werkstudent oder Praktikum Frontend (m/w/d)                    | WERKSTUDENT,PRAKTIKUM",
            "Praktikum mit anschließender Abschlussarbeit Embedded Software | PRAKTIKUM,ABSCHLUSSARBEIT",
    })
    void detectsStudentTypes(String title, String expected) {
        Set<StudentType> want = EnumSet.noneOf(StudentType.class);
        Arrays.stream(expected.split(",")).map(String::trim).map(StudentType::valueOf).forEach(want::add);
        assertThat(StudentTypeRules.detect(title)).isEqualTo(want);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Senior Java Developer (m/w/d)",
            "Junior Backend Engineer",
            "Software Architect",
            "Team Lead Student Recruiting",
            "Internal Auditor IT",
            "Recruiter für Studierende und Absolventen",
    })
    void rejectsNonStudentRoles(String title) {
        assertThat(StudentTypeRules.detect(title)).isEmpty();
    }
}
