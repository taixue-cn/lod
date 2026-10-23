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

package no.jckf.dhsupport.core.database.repositories;

import no.jckf.dhsupport.core.database.Database;
import no.jckf.dhsupport.core.database.models.LodModel;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.logging.Logger;

public class LodRepository
{
    protected static final String SQL_SAVE_LOD = "REPLACE INTO lods (worldId, x, z, data, beacons, timestamp, version) VALUES (?, ?, ?, ?, ?, ?, ?)";

    protected static final String SQL_LOAD_LOD = "SELECT data, beacons, timestamp FROM lods WHERE worldId = ? AND x = ? AND z = ? LIMIT 1";

    protected static final String SQL_LOD_EXISTS = "SELECT EXISTS( SELECT 1 FROM lods WHERE worldId = ? AND x = ? AND z = ? )";

    protected static final String SQL_DELETE_LOD = "DELETE FROM lods WHERE worldId = ? AND x = ? AND z = ?";

    protected static final String SQL_TRIM_LODS = "DELETE FROM lods WHERE worldId = ? AND (x < ? OR z < ? OR x > ? OR z > ?)";

    protected Database database;

    protected Logger logger;

    public LodRepository(Database database)
    {
        this.database = database;
    }

    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    public Logger getLogger()
    {
        return this.logger;
    }

    public LodModel saveLod(UUID worldId, int sectionX, int sectionZ, byte[] data, byte[] beacons, int version)
    {
        int timestamp = (int) (System.currentTimeMillis() / 1000);

        try {
            PreparedStatement statement = this.database.prepareAndReuse(SQL_SAVE_LOD);

            statement.setString(1, worldId.toString());
            statement.setInt(2, sectionX);
            statement.setInt(3, sectionZ);
            statement.setBytes(4, data);
            statement.setBytes(5, beacons);
            statement.setInt(6, timestamp);
            statement.setInt(7, version);

            statement.executeUpdate();

            return LodModel.create()
                .setWorldId(worldId)
                .setX(sectionX)
                .setZ(sectionZ)
                .setData(data)
                .setBeacons(beacons)
                .setTimestamp(timestamp)
                .setVersion(version);
        } catch (SQLException exception) {
            this.getLogger().warning("Could not save LOD: " + exception);

            this.database.clearQueryCache();

            return null;
        }
    }

    public LodModel loadLod(UUID worldId, int sectionX, int sectionZ)
    {
        try {
            PreparedStatement statement = this.database.prepareAndReuse(SQL_LOAD_LOD);

            statement.setString(1, worldId.toString());
            statement.setInt(2, sectionX);
            statement.setInt(3, sectionZ);

            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }

                byte[] data = result.getBytes("data");

                return LodModel.create()
                    .setWorldId(worldId)
                    .setX(sectionX)
                    .setZ(sectionZ)
                    .setData(data)
                    .setBeacons(result.getBytes("beacons"))
                    .setTimestamp(result.getInt("timestamp"));
            }
        } catch (SQLException exception) {
            this.getLogger().warning("Could not load LOD: " + exception);

            this.database.clearQueryCache();

            return null;
        }
    }

    public boolean lodExists(UUID worldId, int sectionX, int sectionZ)
    {
        try {
            PreparedStatement statement = this.database.prepareAndReuse(SQL_LOD_EXISTS);

            statement.setString(1, worldId.toString());
            statement.setInt(2, sectionX);
            statement.setInt(3, sectionZ);

            try (ResultSet result = statement.executeQuery()) {
                result.next();

                return result.getInt(1) == 1;
            }
        } catch (SQLException exception) {
            this.getLogger().warning("Could not check LOD existence: " + exception);

            this.database.clearQueryCache();

            return false;
        }
    }

    public boolean deleteLod(UUID worldId, int sectionX, int sectionZ)
    {
        try {
            PreparedStatement statement = this.database.prepareAndReuse(SQL_DELETE_LOD);

            statement.setString(1, worldId.toString());
            statement.setInt(2, sectionX);
            statement.setInt(3, sectionZ);

            int affectedRows = statement.executeUpdate();

            return affectedRows > 0;
        } catch (SQLException exception) {
            this.getLogger().warning("Could not delete LOD: " + exception);

            this.database.clearQueryCache();

            return false;
        }
    }

    public int trimLods(UUID worldId, int lowSectionX, int lowSectionZ, int highSectionX, int highSectionZ)
    {
        int affectedRows = 0;

        try {
            PreparedStatement statement = this.database.prepareAndReuse(SQL_TRIM_LODS);

            statement.setString(1, worldId.toString());
            statement.setInt(2, lowSectionX);
            statement.setInt(3, lowSectionZ);
            statement.setInt(4, highSectionX);
            statement.setInt(5, highSectionZ);

            affectedRows = statement.executeUpdate();

            this.database.optimize();
        } catch (Exception exception) {
            this.getLogger().warning("Could not trim LODs/optimize DB: " + exception);
        }

        return affectedRows;
    }
}
