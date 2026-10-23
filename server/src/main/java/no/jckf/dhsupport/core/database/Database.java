/*
 * DH Support, server-side support for Distant Horizons.
 * Copyright (C) 2024 Jim C K Flaten
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package no.jckf.dhsupport.core.database;

import no.jckf.dhsupport.core.database.migrations.Migration;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class Database
{
    protected String path;

    protected Connection connection;

    protected Map<String, PreparedStatement> preparedStatements = new ConcurrentHashMap<>();

    protected List<Class<? extends Migration>> migrations = new ArrayList<>();

    protected CompletableFuture<?> optimizing;

    public Connection getConnection() throws SQLException
    {
        if (this.connection == null || this.connection.isClosed()) {
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + this.path);
        }

        return this.connection;
    }

    public void open(String path) throws SQLException
    {
        this.path = path;

        this.getConnection();
        try(var statement=this.connection.createStatement()){statement.execute("PRAGMA auto_vacuum=INCREMENTAL");}
    }

    public void close() throws SQLException
    {
        this.clearQueryCache();

        if (this.connection == null || this.connection.isClosed()) {
            return;
        }

        this.getConnection().close();
    }

    public PreparedStatement prepareAndReuse(String sql) throws SQLException
    {
        this.waitForOptimization();

        if (!this.preparedStatements.containsKey(sql)) {
            this.preparedStatements.put(sql, this.getConnection().prepareStatement(sql));
        }

        return this.preparedStatements.get(sql);
    }

    public void clearQueryCache()
    {
        for (String key : this.preparedStatements.keySet()) {
            try {
                this.preparedStatements.remove(key).close();
            } catch (SQLException exception) {

            }
        }
    }

    protected void createMigrationsTable() throws SQLException
    {
        String sql = """
            CREATE TABLE IF NOT EXISTS migrations (
                name STRING PRIMARY KEY,
                timestamp INTEGER
            );
        """;

        try (Statement statement = this.getConnection().createStatement()) {
            statement.execute(sql);
        }
    }

    public void addMigration(Class<? extends Migration> migration)
    {
        this.migrations.add(migration);
    }

    public boolean hasMigrationRan(String name) throws SQLException
    {
        String sql = "SELECT 1 FROM migrations WHERE name = ?;";

        try (PreparedStatement statement = this.getConnection().prepareStatement(sql)) {
            statement.setString(1, name);

            ResultSet result = statement.executeQuery();

            return result.next();
        }
    }

    public void markMigrationAsRan(String name) throws SQLException
    {
        String sql = "INSERT INTO migrations (name, timestamp) VALUES (?, ?);";

        try (PreparedStatement statement = this.getConnection().prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setInt(2, (int) (System.currentTimeMillis() / 1000));

            statement.executeUpdate();
        }
    }

    public void migrate() throws Exception
    {
        this.createMigrationsTable();

        for (Class<? extends Migration> migrationClass : this.migrations) {
            String name = migrationClass.getSimpleName();

            if (this.hasMigrationRan(name)) {
                continue;
            }

            Migration migration = migrationClass.getConstructor(this.getClass()).newInstance(this);

            migration.up();

            this.markMigrationAsRan(name);
        }
    }

    public void optimize() throws Exception
    {
        this.optimizing = new CompletableFuture<>();

        this.clearQueryCache();

        try (Statement statement = this.getConnection().createStatement()) {
            statement.execute("PRAGMA optimize");
            statement.execute("ANALYZE");
            statement.execute("REINDEX");
            statement.execute("VACUUM");
        } finally {
            this.optimizing.complete(null);
            this.optimizing = null;
        }
    }

    public void waitForOptimization()
    {
        if (this.optimizing != null) {
            this.optimizing.join();
        }
    }
}
