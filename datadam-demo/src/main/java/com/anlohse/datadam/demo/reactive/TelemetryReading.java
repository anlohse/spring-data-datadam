package com.anlohse.datadam.demo.reactive;

import java.time.Instant;

import com.anlohse.datadam.spring.annotation.*;
import org.springframework.data.annotation.*;

@DatadamDocument(prefix = "demo:examples:telemetry:", timeToLive = 600)
@DatadamIndex(name = "demo:examples:telemetry:device-sequence", unique = true, fields = {
    @IndexField(path = "$.device", alias = "device"),
    @IndexField(path = "$.sequence", alias = "sequence", type = IndexField.Type.NUMERIC)
})
@DatadamIndex(name = "demo:examples:telemetry:search", kind = DatadamIndex.Kind.SEARCH, fields = {
    @IndexField(path = "$.device", alias = "device"),
    @IndexField(path = "$.value", alias = "value", type = IndexField.Type.NUMERIC)
})
public record TelemetryReading(@Id @DatadamGeneratedId String id, String device, Long sequence, Double value,
                               @Version Long version, @CreatedDate Instant createdAt, @LastModifiedDate Instant modifiedAt,
                               @CreatedBy String createdBy, @LastModifiedBy String modifiedBy) {}
