package io.vantage.loganalyzer;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class InMemoryInvestigationStore implements InvestigationStore {

    private final ConcurrentHashMap<String, Investigation> store = new ConcurrentHashMap<>();

    @Override
    public Investigation create(String title) {
        String id = UUID.randomUUID().toString();
        Investigation investigation = new Investigation(id, title);
        store.put(id, investigation);
        return investigation;
    }

    @Override
    public Optional<Investigation> get(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<Investigation> listAll() {
        return store.values().stream()
                .sorted(Comparator.comparing((Investigation i) -> i.lastActivityAt).reversed())
                .toList();
    }
}
