package com.anlohse.datadam.spring;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamTimeToLive;
import com.anlohse.datadam.spring.annotation.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.annotation.Id;
import static org.assertj.core.api.Assertions.*;

class EntityTtlTest {
    @Test void absentTtlLeavesExpiryUntouched() {
        assertThat(new EntityModel<>(Persistent.class).timeToLiveMilliseconds(new Persistent("id"))).isEmpty();
    }
    @Test void typeDefaultAndNullableOverridesHaveExplicitUnits() {
        var model = new EntityModel<>(DefaultTtl.class);
        assertThat(model.timeToLiveMilliseconds(new DefaultTtl("id", null))).hasValue(120_000);
        assertThat(model.timeToLiveMilliseconds(new DefaultTtl("id", 1500L))).hasValue(1500);
    }
    @Test void nullablePolicyWithoutTypeDefaultPreservesExpiry() {
        var model = new EntityModel<>(OptionalTtl.class);
        assertThat(model.timeToLiveMilliseconds(new OptionalTtl("id", null))).isEmpty();
        assertThat(model.timeToLiveMilliseconds(new OptionalTtl("id", 3))).hasValue(3000);
    }
    @Test void durationAndSubmillisecondUnitsCannotBeSilentlyTruncated() {
        var model = new EntityModel<>(DurationTtl.class);
        assertThat(model.timeToLiveMilliseconds(new DurationTtl("id", Duration.ofMillis(1250)))).hasValue(1250);
        assertThatIllegalArgumentException().isThrownBy(() -> model.timeToLiveMilliseconds(new DurationTtl("id", Duration.ofNanos(1))))
            .withMessageContaining("whole number of milliseconds");
        var micros = new EntityModel<>(Micros.class);
        assertThat(micros.timeToLiveMilliseconds(new Micros("id", 2000))).hasValue(2);
        assertThatIllegalArgumentException().isThrownBy(() -> micros.timeToLiveMilliseconds(new Micros("id", 2001)));
    }
    @Test void invalidTtlValuesAndOverflowAreRejected() {
        var model = new EntityModel<>(DefaultTtl.class);
        for (long value : new long[] {0, -1, Long.MIN_VALUE, Long.MAX_VALUE})
            assertThatIllegalArgumentException().isThrownBy(() -> model.timeToLiveMilliseconds(new DefaultTtl("id", value)));
        assertThatIllegalArgumentException().isThrownBy(() -> new EntityModel<>(DurationTtl.class)
            .timeToLiveMilliseconds(new DurationTtl("id", Duration.ofSeconds(Long.MAX_VALUE))));
    }
    @Test void malformedMetadataFailsAtBootstrap() {
        for (Class<?> type : new Class<?>[] {BadDefault.class, Multiple.class, FloatingPoint.class, SharedId.class, StaticTtl.class})
            assertThatIllegalArgumentException().isThrownBy(() -> new EntityModel<>(type));
    }
    @Test void inheritedMutableFieldsAreSupported() {
        var entity = new Mutable();
        entity.ttl = 5L;
        assertThat(new EntityModel<>(Mutable.class).timeToLiveMilliseconds(entity)).hasValue(5000);
    }
    @Test void invalidPolicyFailsBeforeAnyTransportCommand() {
        var commands = new DatadamCommands(null) {
            @Override public Object execute(String command, Object... args) { throw new AssertionError("No command should run"); }
        };
        var template = new DatadamTemplate(commands, new com.fasterxml.jackson.databind.ObjectMapper());
        assertThatIllegalArgumentException().isThrownBy(() -> template.save(new DefaultTtl("id", 0L), new EntityModel<>(DefaultTtl.class)));
    }
    @DatadamDocument(prefix = "persistent:") record Persistent(@Id String id) {}
    @DatadamDocument(prefix = "default:", timeToLive = 2, timeToLiveUnit = TimeUnit.MINUTES)
    record DefaultTtl(@Id String id, @DatadamTimeToLive(unit = TimeUnit.MILLISECONDS) Long ttl) {}
    @DatadamDocument(prefix = "optional:") record OptionalTtl(@Id String id, @DatadamTimeToLive Integer ttl) {}
    @DatadamDocument(prefix = "duration:") record DurationTtl(@Id String id, @DatadamTimeToLive Duration ttl) {}
    @DatadamDocument(prefix = "micros:") record Micros(@Id String id, @DatadamTimeToLive(unit = TimeUnit.MICROSECONDS) long ttl) {}
    @DatadamDocument(prefix = "invalid:", timeToLive = 0) record BadDefault(@Id String id) {}
    @DatadamDocument(prefix = "multiple:") record Multiple(@Id String id, @DatadamTimeToLive Long a, @DatadamTimeToLive Long b) {}
    @DatadamDocument(prefix = "double:") record FloatingPoint(@Id String id, @DatadamTimeToLive Double ttl) {}
    @DatadamDocument(prefix = "shared:") record SharedId(@Id @DatadamTimeToLive Long id) {}
    @DatadamDocument(prefix = "static:") static class StaticTtl { @Id String id; @DatadamTimeToLive static Long ttl; }
    static class Parent { @DatadamTimeToLive Long ttl; }
    @DatadamDocument(prefix = "mutable:") static class Mutable extends Parent { @Id String id; }
}
