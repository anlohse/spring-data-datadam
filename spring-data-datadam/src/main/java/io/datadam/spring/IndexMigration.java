package io.datadam.spring;

import java.util.Objects;

/** Explicit migration intent. targetEntity may be a staging model separate from active repositories. */
public record IndexMigration(Class<?> targetEntity, String previousIndex, String targetIndex) {
    public IndexMigration {
        Objects.requireNonNull(targetEntity, "targetEntity");
        if (previousIndex == null || previousIndex.isBlank()) throw new IllegalArgumentException("Previous index name is required");
        if (targetIndex == null || !targetIndex.matches(".+:v[1-9][0-9]*"))
            throw new IllegalArgumentException("Target index needs a versioned name ending in :vN (N >= 1)");
        if (previousIndex.equals(targetIndex)) throw new IllegalArgumentException("Previous and target index names must differ");
    }
}
