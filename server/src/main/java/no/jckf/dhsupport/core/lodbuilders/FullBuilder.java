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

package no.jckf.dhsupport.core.lodbuilders;

import no.jckf.dhsupport.core.Coordinates;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import no.jckf.dhsupport.core.dataobject.*;
import no.jckf.dhsupport.core.world.WorldInterface;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class FullBuilder extends LodBuilder
{
    public FullBuilder(WorldInterface worldInterface, SectionPosition position)
    {
        super(worldInterface, position);
    }

    public Lod generate()
    {
        int minY = this.worldInterface.getMinY();
        int maxY = this.worldInterface.getMaxY();
        int height = maxY - minY;

        int offsetX = Coordinates.sectionToBlock(this.position.getX());
        int offsetZ = Coordinates.sectionToBlock(this.position.getZ());

        int yStep = 1;//this.worldInterface.getConfig().getInt(DhsConfig.BUILDER_RESOLUTION);
        int originalStep = yStep;

        boolean includeNonCollidingTopLayer = this.worldInterface.getConfig().getBool(DhsConfig.INCLUDE_NON_COLLIDING_TOP_LAYER, true);
        boolean performUnderglowHack = this.worldInterface.getConfig().getBool(DhsConfig.PERFORM_UNDERGLOW_HACK, false);
        boolean sampleBiomes3d = this.worldInterface.getConfig().getBool(DhsConfig.SAMPLE_BIOMES_3D, false);

        List<IdMapping> idMappings = new ArrayList<>(64);
        Map<String, Map<String, Map<String, Integer>>> mapMap = new HashMap<>();

        List<List<DataPoint>> columns = new ArrayList<>(Lod.width * Lod.width);

        List<Beacon> beacons = new ArrayList<>(8);

        for (int relativeX = 0; relativeX < Lod.width; relativeX++) {
            for (int relativeZ = 0; relativeZ < Lod.width; relativeZ++) {
                int worldX = offsetX + relativeX;
                int worldZ = offsetZ + relativeZ;

                // Actual Y of top-most block.
                int topLayer = this.worldInterface.getHighestYAt(worldX, worldZ);
                int hardTopLayer = topLayer;

                if (includeNonCollidingTopLayer) {
                    outer: while (topLayer + 1 < maxY) {
                        String topSample = this.worldInterface.getMaterialAt(worldX, topLayer + 1, worldZ);

                        switch (topSample) {
                            case "minecraft:air":
                            case "minecraft:void_air":
                                break outer;
                        }

                        topLayer++;
                    }
                }

                // If these differ, the top layer is non-colliding and likely requires yStep=1.
                if (topLayer != hardTopLayer) {
                    yStep = 1;
                }

                // Distance from bottom to top-most block.
                int relativeTopLayer = topLayer - minY;

                String biome = "";

                if (!sampleBiomes3d) {
                    biome = this.worldInterface.getBiomeAt(worldX, worldZ);
                }

                List<DataPoint> column = new ArrayList<>(32);

                @Nullable
                DataPoint previous = null;

                int firstY = height - yStep;

                for (int relativeY = firstY; relativeY >= 1 - yStep; relativeY -= yStep) {
                    int thisStep = yStep;

                    if (relativeY < 0) {
                        thisStep -= -relativeY;
                        relativeY = 0;
                    }

                    int lowWorldY = minY + relativeY;
                    int highWorldY = lowWorldY + thisStep - 1;

                    // We've reached the top-most colliding block. Restore yStep.
                    if (highWorldY == hardTopLayer) {
                        yStep = originalStep;
                    }

                    String material = this.worldInterface.getMaterialAt(worldX, highWorldY, worldZ);

                    if (this.worldInterface.isBeacon(worldX, highWorldY, worldZ)) {
                        beacons.add(new Beacon(
                            worldX,
                            highWorldY,
                            worldZ,
                            worldInterface.getBeaconColor(
                                worldX,
                                highWorldY,
                                worldZ
                            )
                        ));
                    }

                    if (sampleBiomes3d) {
                        biome = this.worldInterface.getBiomeAt(worldX, highWorldY, worldZ);
                    }

                    Map<String, Map<String, Integer>> biomeMap = mapMap.get(biome);
                    if (biomeMap == null) {
                        biomeMap = new HashMap<>();
                        mapMap.put(biome, biomeMap);
                    }

                    Map<String, Integer> materialMap = biomeMap.get(material);
                    if (materialMap == null) {
                        materialMap = new HashMap<>();
                        biomeMap.put(material, materialMap);
                    }

                    String blockState = this.worldInterface.getBlockStateAsStringAt(worldX, highWorldY, worldZ);

                    Integer id = materialMap.get(blockState);

                    if (id == null) {
                        idMappings.add(new IdMapping(biome, material, this.worldInterface.getBlockPropertiesAt(worldX, highWorldY, worldZ)));
                        id = idMappings.size() - 1;
                        materialMap.put(blockState, id);
                    }

                    DataPoint point;

                    if (previous != null && previous.getMappingId() == id) {
                        point = previous;

                        point.setStartY(point.getStartY() - thisStep);
                        point.setHeight(point.getHeight() + thisStep);
                    } else {
                        point = new DataPoint();
                        column.add(point);

                        point.setStartY(relativeY);
                        point.setHeight(thisStep);
                        point.setMappingId(id);

                        point.setSkyLight(this.worldInterface.getSkyLightAt(worldX, highWorldY + 1, worldZ));
                        point.setBlockLight(this.worldInterface.getBlockLightAt(worldX, highWorldY + 1, worldZ));

                        if (material.equals("minecraft:air") || material.equals("minecraft:void_air")) {
                            // Start by filling the top of the column with air, then jump down to the top layer.
                            if (relativeY == firstY) {
                                point.setStartY(relativeTopLayer + 1);
                                point.setHeight(height - relativeTopLayer);

                                relativeY = point.getStartY();
                            } else {
                                // Encountered air that is below a non-air block. Set yStep=1 to avoid stretching the air into the ground or sea.
                                yStep = 1;

                                if (performUnderglowHack && previous != null && previous.getHeight() > 2) {
                                    // Retract 2 block. We need a gap between the end of the top data point and the bottom one to prevent DH from merging them.
                                    previous.setStartY(previous.getStartY() + 2);
                                    previous.setHeight(previous.getHeight() - 2);

                                    DataPoint bottomBlock = new DataPoint();
                                    bottomBlock.setMappingId(previous.getMappingId());
                                    bottomBlock.setStartY(relativeY + 1);
                                    bottomBlock.setHeight(1);
                                    bottomBlock.setSkyLight(this.worldInterface.getSkyLightAt(worldX, lowWorldY, worldZ));
                                    bottomBlock.setBlockLight(this.worldInterface.getBlockLightAt(worldX, lowWorldY, worldZ));

                                    column.add(bottomBlock);

                                    previous = bottomBlock;
                                }
                            }
                        } else {
                            // Entered a new material. Reset yStep in case we came from air with yStep=1.
                            yStep = originalStep;
                        }
                    }

                    previous = point;
                }

                columns.add(column);
            }
        }

        return new Lod(this.worldInterface, this.position, idMappings, columns, beacons);
    }
}
