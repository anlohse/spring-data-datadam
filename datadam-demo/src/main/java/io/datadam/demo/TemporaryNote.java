package io.datadam.demo;

import java.util.concurrent.TimeUnit;
import io.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;

/** A note lives for 60 seconds by default; a positive per-note value overrides it in milliseconds. */
@DatadamDocument(prefix = "demo:temporary:", timeToLive = 60)
@DatadamIndex(name = "demo:temporary:category", fields = @IndexField(path = "$.category", alias = "category"))
@DatadamIndex(name = "demo:temporary:reference", unique = true, fields = @IndexField(path = "$.reference", alias = "reference"))
@DatadamIndex(name = "demo:temporary:search", kind = DatadamIndex.Kind.SEARCH,
    fields = @IndexField(path = "$.category", alias = "category", caseSensitive = true))
public record TemporaryNote(@Id @DatadamGeneratedId String id, String reference, String category, String text,
    @DatadamTimeToLive(unit = TimeUnit.MILLISECONDS) Long ttlMillis) {}
