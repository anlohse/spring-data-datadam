package io.datadam.demo;

import java.time.Instant;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.*;

@DatadamDocument(prefix = "demo:audited:", timeToLive = 300)
@DatadamIndex(name = "demo:audited:category", fields = @IndexField(path = "$.category", alias = "category"))
@DatadamIndex(name = "demo:audited:reference", unique = true, fields = @IndexField(path = "$.reference", alias = "reference"))
@DatadamIndex(name = "demo:audited:search", kind = DatadamIndex.Kind.SEARCH,
    fields = @IndexField(path = "$.category", alias = "category", caseSensitive = true))
public record AuditedNote(@Id @DatadamGeneratedId String id, String reference, String category, String text,
    @CreatedDate Instant createdAt, @LastModifiedDate Instant modifiedAt,
    @CreatedBy String createdBy, @LastModifiedBy String modifiedBy) {
    public AuditedNote withContent(String reference, String category, String text) {
        return new AuditedNote(id, reference, category, text, createdAt, modifiedAt, createdBy, modifiedBy);
    }
}
