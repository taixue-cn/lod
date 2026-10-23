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

import no.jckf.dhsupport.core.configuration.Configuration;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import no.jckf.dhsupport.core.database.models.LodModel;
import no.jckf.dhsupport.core.dataobject.SectionPosition;
import no.jckf.dhsupport.core.world.WorldInterface;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class PreGenerator implements Runnable
{
    protected DhSupport dhSupport;

    protected WorldInterface world;

    protected int radius;

    protected int centerX;

    protected int centerZ;

    protected boolean force;

    protected int totalSteps;

    protected int stepsSoFar = 0;

    protected int inFlight = 0;

    protected boolean run = true;

    protected long startTime = 0;

    protected long stopTime = 0;

    public PreGenerator(DhSupport dhSupport, WorldInterface world, int centerX, int centerZ, int radius, boolean force)
    {
        this.dhSupport = dhSupport;
        this.world = world;
        this.centerX = Coordinates.blockToSection(centerX);
        this.centerZ = Coordinates.blockToSection(centerZ);
        this.radius = radius;
        this.force = force;

        this.totalSteps = (int) Math.pow((double) Coordinates.blockToChunk(this.radius) / 2, 2);
    }

    public void run()
    {
        Configuration worldConfig = this.world.getConfig();

        // Fall back to the old key in case the server's config file is for an older plugin version.
        int rateLimit = Math.max(1, worldConfig.has(DhsConfig.CLIENT_LOD_REQUEST_CONCURRENCY_LIMIT)
            ? worldConfig.getInt(DhsConfig.CLIENT_LOD_REQUEST_CONCURRENCY_LIMIT)
            : worldConfig.getInt(DhsConfig.FULL_DATA_REQUEST_CONCURRENCY_LIMIT, 20));

        int currentX = this.centerX;
        int currentZ = this.centerZ;

        int[][] directions = {{ 0, 1 }, { 1, 0 }, { 0, -1 }, { -1, 0 }};
        int dirIndex = 0;

        List<CompletableFuture<LodModel>> requests = new ArrayList<>(rateLimit);

        this.startTime = System.currentTimeMillis();

        OUTER: for (int step = 1; this.stepsSoFar < totalSteps; step++) {
            for (int directionChanges = 0; directionChanges < 2; directionChanges++) {
                for (int stepsOnThisSide = 0; stepsOnThisSide < step; stepsOnThisSide++) {
                    if (!this.run) {
                        break OUTER;
                    }

                    this.stepsSoFar++;

                    if (this.stepsSoFar > this.totalSteps) {
                        break OUTER;
                    }

                    currentX += directions[dirIndex][0];
                    currentZ += directions[dirIndex][1];

                    if (!this.force && this.dhSupport.lodRepository.lodExists(this.world.getId(), currentX, currentZ)) {
                        continue;
                    }

                    SectionPosition position = new SectionPosition();
                    position.setX(currentX);
                    position.setZ(currentZ);
                    position.setDetailLevel(6);

                    CompletableFuture<LodModel> request = this.dhSupport.generateLod(this.world.getId(), position, true);

                    request.handle((lodModel, exception) -> this.inFlight--);

                    requests.add(request);

                    this.inFlight++;

                    while (this.inFlight >= rateLimit) {
                        CompletableFuture.anyOf(requests.toArray(new CompletableFuture[0])).join();

                        requests.removeIf(CompletableFuture::isDone);
                    }
                }

                dirIndex = (dirIndex + 1) % 4; // Change direction
            }
        }

        this.stopTime = System.currentTimeMillis();

        this.run = false;
    }

    public int getCompletedRequests()
    {
        return this.stepsSoFar - this.inFlight;
    }

    public int getTargetRequests()
    {
        return this.totalSteps;
    }

    public float getProgress()
    {
        return (float) this.getCompletedRequests() / this.getTargetRequests();
    }

    public Duration getElapsedTime()
    {
        if (this.startTime == 0) {
            return Duration.ZERO;
        }

        return Duration.ofMillis((this.stopTime == 0 ? System.currentTimeMillis() : this.stopTime) - this.startTime);
    }

    public boolean isRunning()
    {
        return this.run;
    }

    public void stop()
    {
        this.run = false;
    }
}
