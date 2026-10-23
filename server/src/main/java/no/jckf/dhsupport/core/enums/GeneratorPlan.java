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

package no.jckf.dhsupport.core.enums;

/**
 * Sent as its ordinal. Declaration order must match EDhApiGeneratorPlan.
 *
 * @see EDhApiGeneratorPlan
 */
public enum GeneratorPlan
{
    SURFACE_THEN_CHUNKS(true),
    SURFACE_ONLY(true),
    CHUNKS_ONLY(true),
    DISABLED(false)
    ;

    public final boolean generationEnabled;

    GeneratorPlan(boolean generationEnabled)
    {
        this.generationEnabled = generationEnabled;
    }

    public static GeneratorPlan byOrdinal(int ordinal)
    {
        GeneratorPlan[] values = GeneratorPlan.values();

        return ordinal < 0 || ordinal >= values.length ? GeneratorPlan.DISABLED : values[ordinal];
    }
}
