package de.bwjobs.scan;

import de.bwjobs.company.AtsType;
import de.bwjobs.company.Company;

import java.util.List;
import java.util.Set;

/**
 * Reads jobs from one kind of official career page. list() should be cheap; details() is only
 * called for jobs whose title already looks like a student job.
 */
public interface JobSourceAdapter {
    Set<AtsType> supports();

    List<RawJob> list(Company company);

    default RawJob details(Company company, RawJob job) {
        return job;
    }

    /** Search terms used by adapters that support server-side search (large tenants). */
    List<String> STUDENT_SEARCH_TERMS = List.of("Werkstudent", "Praktikum", "Intern", "Abschlussarbeit", "Thesis", "Working Student", "Student");
}
