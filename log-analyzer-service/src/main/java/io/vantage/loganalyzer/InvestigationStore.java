package io.vantage.loganalyzer;

import java.util.List;
import java.util.Optional;

public interface InvestigationStore {
    Investigation create(String title);
    Optional<Investigation> get(String id);
    List<Investigation> listAll();
}
