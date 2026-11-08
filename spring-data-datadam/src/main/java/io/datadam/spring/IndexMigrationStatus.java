package io.datadam.spring;

/** Observed server state, not a persistent migration history or evidence of application cutover. */
public record IndexMigrationStatus(IndexMigration migration, IndexInfo previous, IndexSchemaComparison target) {
    public boolean readyForUse() {
        return previous != null && previous.status().ready() && target.compatible() && target.actual().status().ready();
    }
}
