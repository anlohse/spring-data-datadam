package com.anlohse.datadam.spring;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.*;

/** Explicit administrative operations. Preparation never drops an index or changes application routing. */
public final class DatadamIndexMigrations {
    private final DatadamCommands commands;
    private final DatadamIndexOperations indexes;
    public DatadamIndexMigrations(DatadamCommands commands) {
        this.commands = Objects.requireNonNull(commands);
        this.indexes = new DatadamIndexOperations(commands);
    }
    /** Read-only preflight; compatibility and readiness are independent. */
    public IndexMigrationStatus inspect(IndexMigration migration) {
        validate(migration);
        var previous = find(migration.previousIndex());
        if (previous != null) checkScope(migration, previous);
        return new IndexMigrationStatus(migration, previous, indexes.compare(migration.targetEntity(), migration.targetIndex()));
    }
    /** Create just the target if absent, or resume waiting for a compatible existing target. */
    public IndexMigrationStatus prepare(IndexMigration migration, Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) throw new IllegalArgumentException("Positive timeout required");
        var observed = inspect(migration);
        requirePrevious(observed);
        if (!observed.target().exists()) {
            try { indexes.createDeclaredIndex(migration.targetEntity(), migration.targetIndex()); }
            catch (InvalidDataAccessResourceUsageException e) {
                // A concurrent creator may have won. Verify its definition instead of assuming it matches.
                if (!Objects.equals(e.getMessage(), "ERR Index already exists")) throw e;
            }
        }
        requireTargetSchema(indexes.compare(migration.targetEntity(), migration.targetIndex()));
        try { indexes.awaitReady(migration.targetIndex(), timeout); }
        catch (DataIntegrityViolationException e) {
            throw new DataIntegrityViolationException("Target index " + migration.targetIndex()
                + " backfill failed; resolve the reported data problem explicitly, discard the failed target, then prepare again. "
                + "The previous index was not removed. " + e.getMessage(), e);
        }
        return validateReady(migration);
    }
    /** Revalidate immediately before deploying consumers that explicitly reference the new name. */
    public IndexMigrationStatus validateReady(IndexMigration migration) {
        var observed = inspect(migration);
        requirePrevious(observed);
        requireTargetSchema(observed.target());
        if (!observed.target().actual().status().ready()) throw new DataIntegrityViolationException("Target index "
            + migration.targetIndex() + " is " + observed.target().actual().status().state() + "; it is not ready for use");
        return observed;
    }
    /** Explicit cleanup only for a failed, matching target; never resets/recreates it automatically. */
    public void discardFailedTarget(IndexMigration migration) {
        var observed = inspect(migration);
        requirePrevious(observed); requireTargetSchema(observed.target());
        if (observed.target().actual().status().state() != IndexStatus.State.FAILED)
            throw new DataIntegrityViolationException("Only a FAILED target can be discarded: " + migration.targetIndex());
        commands.execute("IDX.DROP", migration.targetIndex());
    }
    /** Call only after every consumer has stopped using previousIndex. No document deletion option is used. */
    public void retirePrevious(IndexMigration migration) {
        validateReady(migration);
        commands.execute("IDX.DROP", migration.previousIndex());
    }
    private void validate(IndexMigration migration) {
        Objects.requireNonNull(migration, "migration");
        indexes.validateDeclaration(migration.targetEntity(), migration.targetIndex());
    }
    private IndexInfo find(String name) {
        try { return indexes.inspect(name); }
        catch (InvalidDataAccessResourceUsageException e) {
            if (!Objects.equals(e.getMessage(), "ERR no such index '" + name + "'")) throw e;
            return null;
        }
    }
    private void checkScope(IndexMigration migration, IndexInfo previous) {
        var model = new EntityModel<>(migration.targetEntity());
        if (previous.status().storage() != model.storage() || !previous.prefixes().equals(List.of(model.prefix())))
            throw new DataIntegrityViolationException("Previous and target indexes must cover the same storage format and exact prefix: " + migration.previousIndex());
    }
    private void requirePrevious(IndexMigrationStatus observed) {
        if (observed.previous() == null) throw new DataIntegrityViolationException("Previous index is missing: " + observed.migration().previousIndex());
        if (!observed.previous().status().ready()) throw new DataIntegrityViolationException("Previous index must remain READY: " + observed.migration().previousIndex());
    }
    private void requireTargetSchema(IndexSchemaComparison target) {
        if (!target.compatible()) throw new DataIntegrityViolationException("Target index schema differs or is missing: "
            + target.index() + "; " + target.describe() + "; no index was replaced");
    }
}
