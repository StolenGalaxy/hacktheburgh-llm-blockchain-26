package com.stolengalaxy.db;

import org.eclipse.persistence.platform.database.DatabasePlatform;

/**
 * EclipseLink has no SQLite platform, and the generic one adds constraints with
 * {@code ALTER TABLE ... ADD CONSTRAINT}, which SQLite doesn't support.
 * This puts unique constraints inside {@code CREATE TABLE} instead and skips foreign key DDL
 * (SQLite ignores foreign keys unless {@code PRAGMA foreign_keys} is on, and JPA keeps the relationships itself).
 */
public class SQLitePlatform extends DatabasePlatform {

    @Override
    public boolean requiresUniqueConstraintCreationOnTableCreate() {
        return true;
    }

    @Override
    public boolean supportsForeignKeyConstraints() {
        return false;
    }
}
