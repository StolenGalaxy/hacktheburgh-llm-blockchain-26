package com.stolengalaxy.db;

import org.eclipse.persistence.platform.database.DatabasePlatform;

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
