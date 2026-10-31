package de.bwjobs.scan;

import de.bwjobs.company.AtsType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class AdapterRegistry {
    private final Map<AtsType, JobSourceAdapter> byType = new EnumMap<>(AtsType.class);

    public AdapterRegistry(List<JobSourceAdapter> adapters) {
        for (JobSourceAdapter a : adapters) for (AtsType t : a.supports()) byType.put(t, a);
    }

    public Optional<JobSourceAdapter> forType(AtsType type) {
        return Optional.ofNullable(byType.get(type));
    }
}
