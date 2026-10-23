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

package no.jckf.dhsupport.core;

import java.util.function.BiConsumer;

// Block -> chunk -> section -> region
public class Coordinates
{
    protected static final int BLOCK_TO_CHUNK_POWER = 4;

    protected static final int CHUNK_TO_SECTION_POWER = 2;

    protected static final int SECTION_TO_REGION_POWER = 4;

    // UP

    public static int blockToChunk(int value)
    {
        return value >> BLOCK_TO_CHUNK_POWER;
    }

    public static int chunkToSection(int value)
    {
        return value >> CHUNK_TO_SECTION_POWER;
    }

    public static int sectionToRegion(int value)
    {
        return value >> SECTION_TO_REGION_POWER;
    }

    // DOWN

    public static int regionToSection(int value)
    {
        return value << SECTION_TO_REGION_POWER;
    }

    public static int sectionToChunk(int value)
    {
        return value << CHUNK_TO_SECTION_POWER;
    }

    public static int chunkToBlock(int value)
    {
        return value << BLOCK_TO_CHUNK_POWER;
    }

    // UP VIA

    public static int blockToSection(int value)
    {
        return value >> (BLOCK_TO_CHUNK_POWER + CHUNK_TO_SECTION_POWER);
    }

    public static int blockToRegion(int value)
    {
        return value >> (BLOCK_TO_CHUNK_POWER + CHUNK_TO_SECTION_POWER + SECTION_TO_REGION_POWER);
    }

    public static int chunkToRegion(int value)
    {
        return value >> (CHUNK_TO_SECTION_POWER + SECTION_TO_REGION_POWER);
    }

    // DOWN VIA

    public static int regionToChunk(int value)
    {
        return value << (CHUNK_TO_SECTION_POWER + SECTION_TO_REGION_POWER);
    }

    public static int regionToBlock(int value)
    {
        return value << (BLOCK_TO_CHUNK_POWER + CHUNK_TO_SECTION_POWER + SECTION_TO_REGION_POWER);
    }

    public static int sectionToBlock(int value)
    {
        return value << (BLOCK_TO_CHUNK_POWER + CHUNK_TO_SECTION_POWER);
    }

    // RELATIVE

    public static int blockToChunkRelative(int value)
    {
        return value & 0x0F;
    }

    // PACK/UNPACK

    public static long pack(int x, int z)
    {
        return ((long) x & 0xffffffffL)
            | (((long) z & 0xffffffffL) << 32);
    }

    public static int unpackX(long packed)
    {
        return (int) packed;
    }

    public static int unpackZ(long packed)
    {
        return (int) (packed >> 32);
    }

    // ???

    public static void foreachChunkInSection(int sectionX, int sectionZ, BiConsumer<Integer, Integer> consumer)
    {
        int worldX = sectionToBlock(sectionX);
        int worldZ = sectionToBlock(sectionZ);

        for (int xMultiplier = 0; xMultiplier < 4; xMultiplier++) {
            for (int zMultiplier = 0; zMultiplier < 4; zMultiplier++) {
                consumer.accept(
                    worldX + 16 * xMultiplier,
                    worldZ + 16 * zMultiplier
                );
            }
        }
    }
}
