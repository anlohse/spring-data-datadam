package com.anlohse.datadam.spring;

import java.lang.reflect.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;

import com.anlohse.datadam.spring.annotation.DatadamDocument;
import com.anlohse.datadam.spring.annotation.DatadamTimeToLive;
import com.anlohse.datadam.spring.annotation.*;
import org.springframework.data.annotation.Id;
import org.springframework.util.ReflectionUtils;

/** Validates TTL metadata at repository bootstrap and values before any command is submitted. */
final class EntityTtl {
    // Leave ample room for the server's current epoch when it adds a relative TTL to its signed int64 clock.
    static final long MAX_MILLISECONDS = Long.MAX_VALUE / 2;
    private final OptionalLong defaultMilliseconds;
    private final Field field;
    private final TimeUnit unit;

    EntityTtl(Class<?> entity, DatadamDocument document) {
        defaultMilliseconds = document.timeToLive() == -1 ? OptionalLong.empty()
            : OptionalLong.of(milliseconds(document.timeToLive(), document.timeToLiveUnit()));
        List<Field> fields = new ArrayList<>();
        ReflectionUtils.doWithFields(entity, f -> { if (f.isAnnotationPresent(DatadamTimeToLive.class)) fields.add(f); });
        if (fields.size() > 1) throw new IllegalArgumentException("Entity may declare only one @DatadamTimeToLive field: " + entity.getName());
        field = fields.isEmpty() ? null : fields.get(0);
        unit = field == null ? TimeUnit.SECONDS : field.getAnnotation(DatadamTimeToLive.class).unit();
        if (field != null) {
            if (Modifier.isStatic(field.getModifiers()) || field.isAnnotationPresent(Id.class))
                throw new IllegalArgumentException("TTL must be an instance field distinct from @Id");
            Class<?> type = field.getType();
            if (!Set.of(byte.class, Byte.class, short.class, Short.class, int.class, Integer.class, long.class, Long.class, Duration.class).contains(type))
                throw new IllegalArgumentException("@DatadamTimeToLive requires an integral field or Duration");
            ReflectionUtils.makeAccessible(field);
        }
    }
    OptionalLong milliseconds(Object entity) {
        Object value = field == null ? null : ReflectionUtils.getField(field, entity);
        if (value == null) return defaultMilliseconds;
        return OptionalLong.of(value instanceof Duration duration ? milliseconds(duration)
            : milliseconds(((Number) value).longValue(), unit));
    }
    private static long milliseconds(long value, TimeUnit unit) {
        if (value <= 0) throw new IllegalArgumentException("TTL must be positive; use persist(id) to remove expiration");
        try { return milliseconds(Duration.of(value, unit.toChronoUnit())); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("TTL exceeds supported millisecond range", e); }
    }
    private static long milliseconds(Duration duration) {
        if (duration.isNegative() || duration.isZero()) throw new IllegalArgumentException("TTL must be positive; use persist(id) to remove expiration");
        if (duration.getNano() % 1_000_000 != 0) throw new IllegalArgumentException("TTL must be an exact whole number of milliseconds");
        long value;
        try { value = duration.toMillis(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException("TTL exceeds supported millisecond range", e); }
        if (value <= 0 || value > MAX_MILLISECONDS) throw new IllegalArgumentException("TTL exceeds supported range: 1 to " + MAX_MILLISECONDS + " milliseconds");
        return value;
    }
}
