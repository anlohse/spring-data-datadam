package io.datadam.spring.repository;

import java.util.*;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.datadam.spring.*;
import io.datadam.spring.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.*;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.data.repository.core.support.DefaultRepositoryMetadata;
import static org.assertj.core.api.Assertions.*;

class QueryDiagnosticsTest {
    final Recording commands = new Recording();
    final DatadamTemplate template = new DatadamTemplate(commands, new ObjectMapper());
    final DatadamQueryDiagnostics diagnostics = new DatadamQueryDiagnostics(template);
    java.lang.reflect.Method method(String name) { return Arrays.stream(Repo.class.getMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow(); }
    DerivedQueryPlan explain(String name) { return diagnostics.explain(Repo.class, method(name)); }
    @Test void uniqueEqualityPlanMatchesExecutionAndUsesNoIoToExplain() {
        var plan = explain("findByEmail");
        assertThat(plan.index()).isEqualTo("email"); assertThat(plan.commands()).containsExactly("IDX.GET");
        assertThat(plan.reason()).contains("UNIQUE", "highest compatibility score"); assertThat(commands.calls).isEmpty();
        execute("findByEmail", "secret@example.test");
        assertThat(commands.calls).isEqualTo(plan.commands()); assertThat(plan.toString()).doesNotContain("secret@example.test");
    }
    @Test void pagePlanUsesCoveringIdxAndListsDiscardedCandidates() {
        var plan = explain("findByCityAndAgeGreaterThanOrderByAgeDesc");
        assertThat(plan.index()).isEqualTo("city-age"); assertThat(plan.descending()).isTrue();
        assertThat(plan.commands()).containsExactly("IDX.QUERY", "IDX.COUNT");
        assertThat(plan.candidates()).anySatisfy(c -> { assertThat(c.index()).isEqualTo("email"); assertThat(c.compatible()).isFalse(); assertThat(c.reason()).contains("Missing predicate field"); });
        execute("findByCityAndAgeGreaterThanOrderByAgeDesc", "SP", 20, PageRequest.of(0, 10));
        assertThat(commands.calls).isEqualTo(plan.commands());
    }
    @Test void ftPlanResolvesJacksonPathAndSortAliasAndNegationReason() {
        var plan = explain("findByCityStartingWithAndAgeNotOrderByAgeAsc");
        assertThat(plan.kind()).isEqualTo(DatadamIndex.Kind.SEARCH); assertThat(plan.commands()).containsExactly("FT.SEARCH");
        assertThat(plan.nativeSort()).isEqualTo(Sort.by("years"));
        assertThat(plan.candidates()).anySatisfy(c -> { assertThat(c.index()).isEqualTo("city-age"); assertThat(c.reason()).contains("negation require FT"); });
        execute("findByCityStartingWithAndAgeNotOrderByAgeAsc", "SP", 20, PageRequest.of(0, 10));
        assertThat(commands.calls).isEqualTo(plan.commands());
    }
    @Test void numericCountAndExistsHaveNoEntityReads() {
        assertThat(explain("countByEmail").commands()).containsExactly("IDX.COUNT");
        assertThat(explain("existsByEmail").commands()).containsExactly("IDX.COUNT");
        assertThat(explain("countByAgeNot").commands()).containsExactly("FT.SEARCH"); assertThat(commands.calls).isEmpty();
    }
    @Test void dynamicSortChangesThePlanExactlyLikeExecution() {
        var method = method("findByCityAndAgeGreaterThan");
        var plan = diagnostics.explain(Repo.class, method, Sort.by("city", "age"));
        assertThat(plan.index()).isEqualTo("city-age");
        var ft = diagnostics.explain(Repo.class, method, Sort.by("email"));
        assertThat(ft.kind()).isEqualTo(DatadamIndex.Kind.SEARCH); assertThat(ft.nativeSort()).isEqualTo(Sort.by("email"));
        execute("findByCityAndAgeGreaterThan", "SP", 20, PageRequest.of(0, 10, Sort.by("email")));
        assertThat(commands.calls).isEqualTo(ft.commands());
    }
    @Test void invalidSortAndUnsupportedMethodsFailWithoutIo() throws Exception {
        assertThatIllegalArgumentException().isThrownBy(() -> diagnostics.explain(Repo.class, method("findByCityAndAgeGreaterThanOrderByAgeDesc"), Sort.by("age")))
            .withMessageContaining("Conflicting");
        assertThatIllegalArgumentException().isThrownBy(() -> diagnostics.explain(Repo.class, method("findByEmail"), Sort.by("email")))
            .withMessageContaining("no dynamic");
        assertThatIllegalArgumentException().isThrownBy(() -> diagnostics.explain(Repo.class, Repo.class.getMethod("findById", Object.class)));
        assertThatIllegalArgumentException().isThrownBy(() -> explain("annotated"));
        assertThatIllegalArgumentException().isThrownBy(() -> explain("findByCityContaining")); assertThat(commands.calls).isEmpty();
    }
    @Test void noCompatibleIndexExplainsMissingFieldAndTagCase() {
        assertThatIllegalArgumentException().isThrownBy(() -> explain("findByIdAndCity"))
            .withMessageContaining("Missing predicate field $.id");
        var plan = explain("findByCityIgnoreCase");
        assertThat(plan.index()).isEqualTo("folded");
        assertThat(plan.candidates()).anySatisfy(c -> { assertThat(c.index()).isEqualTo("search"); assertThat(c.reason()).contains("folded FT TAG"); });
    }
    @Test void equalScoresUseIndexNameAsDeterministicTieBreak() {
        var plan = explain("findByAgeNot");
        assertThat(plan.index()).isEqualTo("folded");
        assertThat(plan.candidates().stream().filter(DerivedQueryPlan.Candidate::compatible)).hasSize(2);
        assertThat(plan.reason()).contains("index name breaks ties");
        assertThatThrownBy(() -> plan.commands().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    void execute(String name, Object... arguments) {
        var query = new DerivedDatadamQuery(method(name), new DefaultRepositoryMetadata(Repo.class), new SpelAwareProxyProjectionFactory(), template);
        query.execute(arguments);
    }
    static class Recording extends DatadamCommands {
        final List<String> calls = new ArrayList<>(); Recording() { super(null); }
        @Override public Object execute(String command, Object... args) {
            calls.add(command);
            return switch(command) { case "IDX.GET", "IDX.QUERY" -> List.of(); case "IDX.COUNT" -> 0L; case "FT.SEARCH" -> List.of(0L); default -> throw new AssertionError(command); };
        }
    }
    @DatadamDocument(prefix = "diag:")
    @DatadamIndex(name = "email", unique = true, fields = @IndexField(path = "$.email", alias = "email"))
    @DatadamIndex(name = "city-age", fields = {@IndexField(path = "$.town", alias = "home"), @IndexField(path = "$.age", alias = "years", type = IndexField.Type.NUMERIC)})
    @DatadamIndex(name = "search", kind = DatadamIndex.Kind.SEARCH, fields = {@IndexField(path = "$.town", alias = "home", caseSensitive = true), @IndexField(path = "$.age", alias = "years", type = IndexField.Type.NUMERIC), @IndexField(path = "$.email", alias = "email", caseSensitive = true)})
    @DatadamIndex(name = "folded", kind = DatadamIndex.Kind.SEARCH, fields = {@IndexField(path = "$.town", alias = "home"), @IndexField(path = "$.age", alias = "years", type = IndexField.Type.NUMERIC)})
    record Doc(@Id String id, @JsonProperty("town") String city, int age, String email) {}
    interface Repo extends DatadamRepository<Doc, String> {
        Optional<Doc> findByEmail(String email);
        Page<Doc> findByCityAndAgeGreaterThanOrderByAgeDesc(String city, int age, Pageable pageable);
        Page<Doc> findByCityAndAgeGreaterThan(String city, int age, Pageable pageable);
        Page<Doc> findByCityStartingWithAndAgeNotOrderByAgeAsc(String prefix, int age, Pageable pageable);
        long countByEmail(String email); boolean existsByEmail(String email); long countByAgeNot(int age);
        List<Doc> findByAgeNot(int age); List<Doc> findByCityIgnoreCase(String city);
        List<Doc> findByCityContaining(String city); List<Doc> findByIdAndCity(String id, String city);
        @IdxQuery(index = "email", value = "email = ?0") List<Doc> annotated(String email);
    }
}
