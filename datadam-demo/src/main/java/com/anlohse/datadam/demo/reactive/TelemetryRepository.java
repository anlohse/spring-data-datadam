package com.anlohse.datadam.demo.reactive;

import com.anlohse.datadam.spring.CompoundQuery;
import com.anlohse.datadam.spring.repository.ReactiveDatadamRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import reactor.core.publisher.Mono;

public interface TelemetryRepository extends ReactiveDatadamRepository<TelemetryReading, String> {
    default Mono<Page<TelemetryReading>> byDevice(String device, int page, int size) {
        return query("demo:examples:telemetry:device-sequence",
            CompoundQuery.all().where("device", "=", device), PageRequest.of(page, size));
    }
}
