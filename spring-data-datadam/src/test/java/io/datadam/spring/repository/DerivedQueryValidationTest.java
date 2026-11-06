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

class DerivedQueryValidationTest {
    private final DatadamTemplate template = new DatadamTemplate(new DatadamCommands(null), new ObjectMapper());
    private RepositoryQueryFactory factory(Class<?> repository) { return new RepositoryQueryFactory(repository); }
    private class RepositoryQueryFactory {
        final Class<?> repository;
        RepositoryQueryFactory(Class<?> repository) { this.repository = repository; }
        DerivedDatadamQuery method(String name) {
            var method = Arrays.stream(repository.getMethods()).filter(m -> m.getName().equals(name)).findFirst().orElseThrow();
            return new DerivedDatadamQuery(method, new DefaultRepositoryMetadata(repository), new SpelAwareProxyProjectionFactory(), template);
        }
    }
    @Test void partialCompoundIndexCannotAnswerFindByCity() {
        assertThatIllegalArgumentException().isThrownBy(() -> factory(PartialRepository.class).method("findByCity"))
            .withMessageContaining("No compatible native index");
        assertThatNoException().isThrownBy(() -> factory(PartialRepository.class).method("findByCityAndAgeGreaterThan"));
    }
    @Test void foldedTagCannotImplementCaseSensitiveEquality() {
        assertThatIllegalArgumentException().isThrownBy(() -> factory(FoldedRepository.class).method("findByCity"))
            .withMessageContaining("No compatible native index");
        assertThatNoException().isThrownBy(() -> factory(FoldedRepository.class).method("findByCityIgnoreCase"));
    }
    @Test void unsupportedOperatorsAndTypesFailAtBootstrap() {
        assertThatIllegalArgumentException().isThrownBy(() -> factory(PartialRepository.class).method("findByCityContaining"))
            .withMessageContaining("CONTAINING");
        assertThatIllegalArgumentException().isThrownBy(() -> factory(PartialRepository.class).method("findByCityIsNull"))
            .withMessageContaining("IS_NULL");
        assertThatIllegalArgumentException().isThrownBy(() -> factory(PartialRepository.class).method("deleteByCity"))
            .withMessageContaining("Derived deletes");
        assertThatIllegalArgumentException().isThrownBy(() -> factory(PartialRepository.class).method("findTop2ByCityAndAge"))
            .withMessageContaining("single-result");
    }
    @Test void invalidIndexOrderingIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(() -> factory(PartialRepository.class).method("findByCityAndAgeGreaterThanOrderByIdAsc"))
            .withMessageContaining("No compatible native index");
    }
    @Test void jacksonPropertyNamesAndIndexAliasesAreResolved() {
        assertThatNoException().isThrownBy(() -> factory(RenamedRepository.class).method("findByCity"));
    }
    @Test void wrongParameterCountAndUnsupportedCountTypeFailEarly() {
        assertThatIllegalArgumentException().isThrownBy(() -> factory(PartialRepository.class).method("findByCityAndAgeBetween"))
            .withMessageContaining("argument count");
        assertThatIllegalArgumentException().isThrownBy(() -> factory(PartialRepository.class).method("countByCityAndAge"))
            .withMessageContaining("long/Long");
    }
    @Test void hashRootPropertiesResolveJacksonNamesForNativeIndexes() {
        assertThatNoException().isThrownBy(()->factory(HashRenamedRepository.class).method("findByCity"));
    }
    @Test void splitHashTagsCannotPretendToBeWholeFieldEquality() {
        assertThatIllegalArgumentException().isThrownBy(()->factory(HashSplitRepository.class).method("findByCity"))
            .withMessageContaining("No compatible native index");
        assertThatIllegalArgumentException().isThrownBy(()->factory(HashSplitRepository.class).method("findByCityIgnoreCase"))
            .withMessageContaining("No compatible native index");
    }
    @Test void hashNestedJsonInsideAFieldIsNotAQueryableHashProperty() {
        assertThatIllegalArgumentException().isThrownBy(()->factory(HashNestedRepository.class).method("findByAddressCity"))
            .withMessageContaining("root fields");
    }
    @DatadamDocument(prefix = "partial:")
    @DatadamIndex(name = "city-age", fields = {@IndexField(path = "$.city", alias = "city"), @IndexField(path = "$.age", alias = "age", type = IndexField.Type.NUMERIC)})
    record Partial(@Id String id, String city, int age) {}
    interface PartialRepository extends DatadamRepository<Partial, String> {
        List<Partial> findByCity(String city);
        List<Partial> findByCityAndAgeGreaterThan(String city, int age);
        List<Partial> findByCityContaining(String city);
        List<Partial> findByCityIsNull();
        void deleteByCity(String city);
        Optional<Partial> findTop2ByCityAndAge(String city, int age);
        List<Partial> findByCityAndAgeGreaterThanOrderByIdAsc(String city, int age);
        List<Partial> findByCityAndAgeBetween(String city, int minimum);
        int countByCityAndAge(String city, int age);
    }
    @DatadamDocument(prefix = "folded:")
    @DatadamIndex(name = "city", kind = DatadamIndex.Kind.SEARCH, fields = @IndexField(path = "$.city", alias = "city"))
    record Folded(@Id String id, String city) {}
    interface FoldedRepository extends DatadamRepository<Folded, String> {
        List<Folded> findByCity(String city);
        List<Folded> findByCityIgnoreCase(String city);
    }
    @DatadamDocument(prefix = "renamed:")
    @DatadamIndex(name = "renamed", fields = @IndexField(path = "$.homeCity", alias = "h"))
    record Renamed(@Id String id, @JsonProperty("homeCity") String city) {}
    interface RenamedRepository extends DatadamRepository<Renamed, String> { List<Renamed> findByCity(String city); }
    @DatadamDocument(prefix="hash:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="renamed",fields=@IndexField(path="home_city",alias="home_city"))
    record HashRenamed(@Id String id,@JsonProperty("home_city") String city){}
    interface HashRenamedRepository extends DatadamRepository<HashRenamed,String>{List<HashRenamed> findByCity(String city);}
    @DatadamDocument(prefix="hash:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="split",kind=DatadamIndex.Kind.SEARCH,fields=@IndexField(path="city",alias="city",caseSensitive=true))
    record HashSplit(@Id String id,String city){}
    interface HashSplitRepository extends DatadamRepository<HashSplit,String>{List<HashSplit> findByCity(String city);List<HashSplit> findByCityIgnoreCase(String city);}
    record Address(String city){}
    @DatadamDocument(prefix="hash:",storage=DatadamStorage.HASH)
    @DatadamIndex(name="nested",fields=@IndexField(path="address.city",alias="address.city"))
    record HashNested(@Id String id,Address address){}
    interface HashNestedRepository extends DatadamRepository<HashNested,String>{List<HashNested> findByAddressCity(String city);}
}
