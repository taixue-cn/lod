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

package no.jckf.dhsupport.core.handler;

import cc.taixue.lod.server.DhGuard;

import no.jckf.dhsupport.core.DhSupport;
import no.jckf.dhsupport.core.configuration.Configuration;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import no.jckf.dhsupport.core.dataobject.SectionPosition;
import no.jckf.dhsupport.core.message.plugin.ExceptionMessage;
import no.jckf.dhsupport.core.message.plugin.FullDataChunkMessage;
import no.jckf.dhsupport.core.message.plugin.FullDataSourceRequestMessage;
import no.jckf.dhsupport.core.message.plugin.FullDataSourceResponseMessage;
import no.jckf.dhsupport.core.world.WorldInterface;
import org.bukkit.Bukkit;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class LodHandler
{
    public static int CHUNK_SIZE = 1024 * 16; // TODO: Configurable?

    protected DhSupport dhSupport;

    protected PluginMessageHandler pluginMessageHandler;

    protected AtomicInteger requestsInFlight = new AtomicInteger(0);

    public LodHandler(DhSupport dhSupport, PluginMessageHandler pluginMessageHandler)
    {
        this.dhSupport = dhSupport;
        this.pluginMessageHandler = pluginMessageHandler;
    }

    public void register()
    {
        this.pluginMessageHandler.getEventBus().registerHandler(FullDataSourceRequestMessage.class, (requestMessage) -> {
            var player = Bukkit.getPlayer(requestMessage.getSender());
            if (player == null) return;
            UUID worldUuid = player.getWorld().getUID();
            final var lease = DhGuard.issue(requestMessage.getSender(), worldUuid);
            if (lease == null) return;
            // The authenticated player's current world is the only selectable source.

            if (requestMessage.getPosition().getDetailLevel() != 6) {
                ExceptionMessage exceptionMessage = new ExceptionMessage();
                exceptionMessage.isResponseTo(requestMessage);
                exceptionMessage.setTypeId(ExceptionMessage.TYPE_SECTION_REQUIRES_SPLITTING);
                exceptionMessage.setMessage("Only detail level 6 is supported");
                DhGuard.send(lease, exceptionMessage);
                return;
            }

            Configuration dhsConfig = this.dhSupport.getConfig();

            // TODO: Some sort of Player wrapper or interface object. Bukkit classes should not be imported here.


            WorldInterface world = this.dhSupport.getWorldInterface(worldUuid);

            if (world == null) return;
            String target = requestMessage.getWorldName();
            String expected = world.getConfig().getString(DhsConfig.LEVEL_KEY_PREFIX, "") + world.getKey();
            if (!expected.equals(target)) return;
            if (!DhGuard.inRange(lease, (long) requestMessage.getPosition().getX() * 4,
                    (long) requestMessage.getPosition().getZ() * 4)) return;
            Configuration config = world.getConfig();
            Configuration playerConfig = this.dhSupport.getPlayerConfiguration(requestMessage.getSender(), worldUuid);
            if (playerConfig == null) return;

            if (!config.getBool(DhsConfig.DISTANT_GENERATION_ENABLED) || !playerConfig.getBool(DhsConfig.DISTANT_GENERATION_ENABLED)) {
                ExceptionMessage exceptionMessage = new ExceptionMessage();
                exceptionMessage.isResponseTo(requestMessage);
                exceptionMessage.setTypeId(ExceptionMessage.TYPE_SECTION_REQUIRES_SPLITTING);
                exceptionMessage.setMessage("Server has disabled distant generation");
                DhGuard.send(lease, exceptionMessage);
                return;
            }

            SectionPosition position = requestMessage.getPosition();

            String builderType = config.getString(DhsConfig.BUILDER_TYPE);

            if (builderType.equalsIgnoreCase("none") && !this.dhSupport.getLodRepository().lodExists(worldUuid, position.getX(), position.getZ())) {
                ExceptionMessage exceptionMessage = new ExceptionMessage();
                exceptionMessage.isResponseTo(requestMessage);
                exceptionMessage.setTypeId(ExceptionMessage.TYPE_REQUEST_REJECTED);
                exceptionMessage.setMessage("Server has disabled LOD builder");
                DhGuard.send(lease, exceptionMessage);
                return;
            }

            if (this.dhSupport.isPaused() && !this.dhSupport.getLodRepository().lodExists(worldUuid, position.getX(), position.getZ())) {
                ExceptionMessage exceptionMessage = new ExceptionMessage();
                exceptionMessage.isResponseTo(requestMessage);
                exceptionMessage.setTypeId(ExceptionMessage.TYPE_REQUEST_REJECTED);
                exceptionMessage.setMessage("Server has paused Distant Horizons");
                DhGuard.send(lease, exceptionMessage);
                return;
            }

            int clientRateLimit = playerConfig.getInt(DhsConfig.CLIENT_LOD_REQUEST_CONCURRENCY_LIMIT, 0);
            int serverRateLimit = dhsConfig.getInt(DhsConfig.SERVER_LOD_REQUEST_CONCURRENCY_LIMIT, 0);

            // The client runs its sync-on-load and generation queues independently, each against its own limit.
            boolean isSyncRequest = requestMessage.getTimestamp() != null;

            if (isSyncRequest) {
                clientRateLimit = playerConfig.getInt(DhsConfig.LOGIN_DATA_SYNC_RC_LIMIT, 0);
            }

            String activeRequestCounter = isSyncRequest ? "active-sync-requests" : "active-requests";

            boolean exceedsClientRateLimit = playerConfig.increment(activeRequestCounter) > clientRateLimit;
            boolean exceedsServerRateLimit = serverRateLimit > 0 && this.requestsInFlight.incrementAndGet() > serverRateLimit;

            if (exceedsClientRateLimit || exceedsServerRateLimit) {
                if (serverRateLimit > 0) {
                    this.requestsInFlight.decrementAndGet();
                }

                playerConfig.decrement(activeRequestCounter);

                ExceptionMessage exceptionMessage = new ExceptionMessage();
                exceptionMessage.isResponseTo(requestMessage);
                exceptionMessage.setTypeId(ExceptionMessage.TYPE_RATE_LIMITED);
                exceptionMessage.setMessage("Rate limited by the server");
                DhGuard.send(lease, exceptionMessage);
                return;
            }

            this.dhSupport.getLod(worldUuid, position)
                .whenComplete((lodModel, exception) -> {
                    if (serverRateLimit > 0) {
                        this.requestsInFlight.decrementAndGet();
                    }

                    playerConfig.decrement(activeRequestCounter);
                })
                .thenAccept((lodModel) -> {
                    if (lodModel == null) {
                        ExceptionMessage exceptionMessage = new ExceptionMessage();
                        exceptionMessage.isResponseTo(requestMessage);
                        exceptionMessage.setTypeId(ExceptionMessage.TYPE_REQUEST_REJECTED);
                        exceptionMessage.setMessage("No LOD available");
                        DhGuard.send(lease, exceptionMessage);
                        return;
                    }

                    FullDataSourceResponseMessage responseMessage = new FullDataSourceResponseMessage();
                    responseMessage.isResponseTo(requestMessage);

                    boolean sendData = requestMessage.getTimestamp() == null || (requestMessage.getTimestamp() / 1000) < lodModel.getTimestamp();

                    if (sendData) {
                        int myBufferId = playerConfig.increment("buffer-id");

                        responseMessage.setBufferId(myBufferId);
                        responseMessage.setBeacons(lodModel.getBeacons());

                        byte[] data = lodModel.getData();

                        int chunkCount = (int) Math.ceil((double) data.length / CHUNK_SIZE);

                        for (int chunkNo = 0; chunkNo < chunkCount; chunkNo++) {
                            FullDataChunkMessage chunkResponse = new FullDataChunkMessage();
                            chunkResponse.setBufferId(myBufferId);
                            chunkResponse.setIsFirst(chunkNo == 0);
                            chunkResponse.setData(
                                data,
                                CHUNK_SIZE * chunkNo,
                                Math.min(CHUNK_SIZE, data.length - CHUNK_SIZE * chunkNo)
                            );

                            DhGuard.send(lease, chunkResponse);
                        }

                        //this.dhSupport.info("LOD in " + chunkCount + " parts sent for " + requestMessage.getPosition().getX() + " " + requestMessage.getPosition().getZ());
                    }

                    DhGuard.send(lease, responseMessage);
                })
                .exceptionally((exception) -> {
                    exception.printStackTrace();

                    ExceptionMessage exceptionMessage = new ExceptionMessage();
                    exceptionMessage.isResponseTo(requestMessage);
                    exceptionMessage.setTypeId(ExceptionMessage.TYPE_REQUEST_REJECTED);
                    exceptionMessage.setMessage("Internal error");
                    DhGuard.send(lease, exceptionMessage);

                    return null;
                });
        });
    }
}
