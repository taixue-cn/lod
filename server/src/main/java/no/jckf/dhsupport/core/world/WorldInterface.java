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

package no.jckf.dhsupport.core.world;

import no.jckf.dhsupport.core.configuration.Configurable;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface WorldInterface extends Configurable
{
    WorldInterface newInstance();

    UUID getId();

    String getName();

    String getKey();

    double getCoordinateScale();

    @Nullable Integer getWorldBorderX();

    @Nullable Integer getWorldBorderZ();

    @Nullable Integer getWorldBorderRadius();

    boolean isInsideWorldBorder(int x, int z, int area);

    void setDummyMode(boolean enable);

    boolean isDummyMode();

    boolean chunkExists(int x, int z);

    boolean isChunkLoaded(int x, int z);

    boolean loadChunk(int x, int z);

    boolean loadOrGenerateChunk(int x, int z);

    CompletableFuture<Boolean> loadChunkAsync(int x, int z);

    CompletableFuture<Boolean> loadOrGenerateChunkAsync(int x, int z);

    boolean unloadChunk(int x, int z);

    boolean unloadChunkAsync(int x, int z);

    boolean discardChunk(int x, int z);

    int getMinY();

    int getMaxY();

    int getSeaLevel();

    int getSpawnX();

    int getSpawnY();

    int getSpawnZ();

    int getHighestYAt(int x, int z);

    String getBiomeAt(int x, int z);

    String getBiomeAt(int x, int y, int z);

    String getMaterialAt(int x, int y, int z);

    /**
     * Get the string representation of the block state at the given coordinates.
     * Block state properties are ordered in ascending order.
     */
    String getBlockStateAsStringAt(int x, int y, int z);

    /**
     * Get block properties as key/value pairs.
     * Ordered in ascending order.
     */
    Map<String, String> getBlockPropertiesAt(int x, int y, int z);

    byte getBlockLightAt(int x, int y, int z);

    byte getSkyLightAt(int x, int y, int z);

    boolean isBeacon(int x, int y, int z);

    int getBeaconColor(int x, int y, int z);
}
