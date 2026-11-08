package io.datadam.spring.repository;

import java.util.List;
import io.datadam.spring.annotation.DatadamIndex;
import org.springframework.data.domain.Sort;

/** Declaration-based plan, not a server optimizer/cost estimate. Contains no query values. */
public record DerivedQueryPlan(String index, DatadamIndex.Kind kind, List<String> commands,
                               Sort nativeSort, boolean descending, String reason, List<Candidate> candidates) {
    public DerivedQueryPlan { commands = List.copyOf(commands); candidates = List.copyOf(candidates); }
    public record Candidate(String index, DatadamIndex.Kind kind, boolean compatible, int score, String reason) {}
}
