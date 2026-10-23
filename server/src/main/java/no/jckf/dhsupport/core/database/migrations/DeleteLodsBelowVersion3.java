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

package no.jckf.dhsupport.core.database.migrations;

import no.jckf.dhsupport.core.database.Database;

import java.sql.Statement;

/** Version 2 LODs lack the "regenerate" flag added by DH network protocol 16. */
public class DeleteLodsBelowVersion3 extends Migration
{
    public DeleteLodsBelowVersion3(Database database)
    {
        super(database);
    }

    @Override
    public void up() throws Exception
    {
        try (Statement statement = this.database.getConnection().createStatement()) {
            statement.execute("""
                DELETE FROM lods WHERE version < 3;
            """);
        }
    }
}
