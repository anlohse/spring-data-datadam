package io.datadam.demo;

import java.time.Instant;
import java.util.*;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.*;

@DatadamDocument(prefix="demo:hash:note:", storage=DatadamStorage.HASH, timeToLive=300)
public record HashNote(@Id @DatadamGeneratedId String id, String reference, String text, List<String> tags,
    Map<String, Object> details, @Version Long version, @CreatedDate Instant createdAt, @LastModifiedDate Instant modifiedAt,
    @CreatedBy String createdBy, @LastModifiedBy String modifiedBy) {
    public HashNote withContent(String reference, String text, List<String> tags, Map<String, Object> details, Long version) {
        return new HashNote(id, reference, text, tags, details, version, createdAt, modifiedAt, createdBy, modifiedBy);
    }
}
