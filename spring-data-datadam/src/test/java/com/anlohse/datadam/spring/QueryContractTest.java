package com.anlohse.datadam.spring;

import java.util.*;
import com.anlohse.datadam.spring.annotation.DatadamDocument;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import static org.assertj.core.api.Assertions.*;

class QueryContractTest {
    @Test void boundValuesKeepWhitespaceAndProtocolSyntaxLiteral() {
        var original = CompoundQuery.all();
        var query = original.where("city", "=", "São Paulo DESC LIMIT 0 999").where("age", ">=", 18).desc();
        assertThat(original.conditions()).isEmpty();
        assertThat(query.arguments("people")).containsExactly("people", "city", "=", "São Paulo DESC LIMIT 0 999", "age", ">=", 18);
        assertThat(query.descending()).isTrue();
    }
    @Test void rejectsOperatorsUnsupportedByDatadam() {
        assertThatIllegalArgumentException().isThrownBy(() -> CompoundQuery.all().where("age", "!=", 18));
    }
    @Test void rejectsUnsupportedSearchSorting() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SearchQuery("*", Map.of(), Sort.by("a", "b")));
        assertThatIllegalArgumentException().isThrownBy(() -> new SearchQuery("*", Map.of(), Sort.by(Sort.Order.asc("a").ignoreCase())));
    }
    @Test void requiresExplicitIdentityAndNonGlobPrefix() {
        assertThatIllegalArgumentException().isThrownBy(() -> new EntityModel<>(String.class));
        assertThatIllegalArgumentException().isThrownBy(() -> new EntityModel<>(BadPrefix.class));
        var model = new EntityModel<>(Valid.class);
        assertThatIllegalArgumentException().isThrownBy(() -> model.key(null));
        assertThat(model.key("abc")).isEqualTo("valid:abc");
    }
    @Test void respMapDecoderRejectsMalformedMaps() {
        assertThat(DatadamCommands.map(List.of("state", "ready", "unique", 1L))).containsEntry("unique", 1L);
        assertThatThrownBy(() -> DatadamCommands.map(List.of("state"))).isInstanceOf(org.springframework.dao.DataRetrievalFailureException.class);
    }
    @DatadamDocument(prefix = "bad:*") record BadPrefix(@Id String id) {}
    @DatadamDocument(prefix = "valid:") record Valid(@Id String id) {}
}
