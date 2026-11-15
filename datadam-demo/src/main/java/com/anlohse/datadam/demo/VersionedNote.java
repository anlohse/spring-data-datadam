package com.anlohse.datadam.demo;

import java.time.Instant;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamGeneratedId;
import com.anlohse.datadam.spring.annotation.DatadamIndex;
import com.anlohse.datadam.spring.annotation.IndexField;
import org.springframework.data.annotation.*;

@DatadamDocument(prefix = "demo:versioned:", timeToLive = 300)
@DatadamIndex(name = "demo:versioned:category", fields = @IndexField(path = "$.category", alias = "category"))
@DatadamIndex(name = "demo:versioned:reference", unique = true, fields = @IndexField(path = "$.reference", alias = "reference"))
@DatadamIndex(name = "demo:versioned:search", kind = DatadamIndex.Kind.SEARCH,
    fields = @IndexField(path = "$.category", alias = "category", caseSensitive = true))
public record VersionedNote(@Id @DatadamGeneratedId String id, String reference, String category, String text,
                            @Version Long version, @CreatedDate Instant createdAt, @LastModifiedDate Instant modifiedAt,
                            @CreatedBy String createdBy, @LastModifiedBy String modifiedBy) {
    public VersionedNote withContent(String reference, String category, String text) {
        return new VersionedNote(id, reference, category, text, version, createdAt, modifiedAt, createdBy, modifiedBy);
    }
    public VersionedNote withVersion(Long version) {
        return new VersionedNote(id, reference, category, text, version, createdAt, modifiedAt, createdBy, modifiedBy);
    }
}
