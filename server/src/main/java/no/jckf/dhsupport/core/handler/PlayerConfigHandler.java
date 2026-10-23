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
import no.jckf.dhsupport.core.message.plugin.LevelInitMessage;
import no.jckf.dhsupport.core.message.plugin.LevelInitRequestMessage;
import no.jckf.dhsupport.core.message.plugin.RemotePlayerConfigMessage;
import no.jckf.dhsupport.core.world.WorldInterface;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerConfigHandler
{
    public final static String USAGE_PERMISSION = "distant_horizons.worlds.%s";

    protected DhSupport dhSupport;

    protected PluginMessageHandler pluginMessageHandler;

    public PlayerConfigHandler(DhSupport dhSupport, PluginMessageHandler pluginMessageHandler)
    {
        this.dhSupport = dhSupport;
        this.pluginMessageHandler = pluginMessageHandler;
    }

    public void register()
    {
        this.pluginMessageHandler.getEventBus().registerHandler(LevelInitRequestMessage.class, (initRequestMessage) -> {
            // TODO: Some sort of Player wrapper or interface object. Bukkit classes should not be imported here.
            Player player = Bukkit.getPlayer(initRequestMessage.getSender());

            NamespacedKey worldKey = NamespacedKey.fromString(initRequestMessage.getWorldKey());

            // TODO: This too 👎
            UUID worldUuid = null;

            for (World world : Bukkit.getServer().getWorlds()) {
                if (world.getKey().equals(worldKey)) {
                    worldUuid = world.getUID();
                    break;
                }
            }

            if (worldUuid == null) {
                this.dhSupport.warning("Player " + player.getName() + " requested init for unknown world " + worldKey + ".");
                return;
            }

            final var lease = DhGuard.issue(initRequestMessage.getSender(), worldUuid);
            if (lease == null) return;
            WorldInterface world = this.dhSupport.getWorldInterface(worldUuid);

            Configuration dhsConfig = world.getConfig();

            String levelKeyPrefix = dhsConfig.getString(DhsConfig.LEVEL_KEY_PREFIX);
            String levelKey = world.getKey();

            if (levelKeyPrefix != null) {
                levelKey = levelKeyPrefix + levelKey;
            }

            LevelInitMessage levelInitResponse = new LevelInitMessage();
            levelInitResponse.setNamespacedKey(worldKey.toString());
            levelInitResponse.setServerKey(dhsConfig.getString(DhsConfig.SERVER_KEY, ""));
            levelInitResponse.setLevelKey(levelKey);
            levelInitResponse.setTime(System.currentTimeMillis());
            DhGuard.send(lease, levelInitResponse);
        });

        this.pluginMessageHandler.getEventBus().registerHandler(RemotePlayerConfigMessage.class, (configMessage) -> {
            // TODO: Some sort of Player wrapper or interface object. Bukkit classes should not be imported here.
            Player player = Bukkit.getPlayer(configMessage.getSender());

            if (player == null) return;
            var lease = DhGuard.issue(player.getUniqueId(), player.getWorld().getUID());
            Map<UUID, Configuration> playerConfigurations = new HashMap<>();

            for (WorldInterface world : this.dhSupport.getWorldInterfaces().values()) {
                String permission = USAGE_PERMISSION.formatted(world.getName());
                boolean dhUseIsAllowed = !player.isPermissionSet(permission) || player.hasPermission(permission);

                Configuration dhsConfig = world.getConfig();

                Configuration clientConfig = configMessage.toConfiguration();

                // This is not very flexible, but will do for now.
                for (String key : RemotePlayerConfigMessage.KEYS) {
                    //this.dhSupport.getLogger().info("Config key " + key + ":");

                    Object dhsValue = dhsConfig.get(key);
                    Object clientValue = clientConfig.get(key);
                    Object keepValue = null;

                    if (key.equals(DhsConfig.DISTANT_GENERATION_ENABLED)) {
                        if (dhUseIsAllowed) {
                            if (!(boolean) clientValue) {
                                player.sendMessage(ChatColor.GOLD + "This server supports Distant Horizons, but you have disabled Distant Generation in your DH settings.");
                            }
                        } else {
                            this.dhSupport.info("Player " + player.getName() + " has been denied the use of Distant Horizons through permissions.");

                            keepValue = false;
                        }
                    }

                    if (key.equals(DhsConfig.REAL_TIME_UPDATES_ENABLED) && !dhUseIsAllowed) {
                        keepValue = false;
                    }

                    // If no value was found under the new key, read the old key to see if the server's config file is for an older plugin version.
                    if (key.equals(DhsConfig.CLIENT_LOD_REQUEST_CONCURRENCY_LIMIT) && dhsValue == null) {
                        dhsValue = dhsConfig.get(DhsConfig.FULL_DATA_REQUEST_CONCURRENCY_LIMIT);
                    }

                    // TODO: This is ugly. Move to comparator closures like in DH.
                    if (key.equals(DhsConfig.BORDER_CENTER_X)) {
                        keepValue = world.getWorldBorderX() == null ? 0 : Math.floorDiv(world.getWorldBorderX(), 16);
                    } else if (key.equals(DhsConfig.BORDER_CENTER_Z)) {
                        keepValue = world.getWorldBorderZ() == null ? 0 : Math.floorDiv(world.getWorldBorderZ(), 16);
                    } else if(key.equals(DhsConfig.BORDER_RADIUS)) {
                        keepValue = world.getWorldBorderRadius() == null ? 0 : (int) Math.min(Integer.MAX_VALUE, (world.getWorldBorderRadius().longValue() + 15) / 16);
                    }

                    if (keepValue == null) {
                        if (dhsValue instanceof Boolean dhsBool && clientValue instanceof Boolean clientBool) {
                            keepValue = dhsBool && clientBool;

                            //this.dhSupport.getLogger().info("    Server " + (dhsBool ? "Y" : "N") + " or client " + (clientBool ? "Y" : "N") + " = " + ((boolean) keepValue ? "Y" : "N"));
                        } else if (dhsValue instanceof Integer dhsInt && clientValue instanceof Integer clientInt) {
                            keepValue = dhsInt < clientInt ? dhsInt : clientInt;

                            //this.dhSupport.getLogger().info("    Server " + dhsInt + " or client " + clientInt + " = " + keepValue);
                        } else {
                            //this.dhSupport.getLogger().info("    Uhh... 😵‍💫");
                        }
                    }

                    clientConfig.set(key, keepValue == null ? -1 : keepValue);
                }

                playerConfigurations.put(world.getId(), clientConfig);
            }

            this.dhSupport.setPlayerConfiguration(configMessage.getSender(), playerConfigurations);

            RemotePlayerConfigMessage configResponse = new RemotePlayerConfigMessage();
            configResponse.fromConfiguration(playerConfigurations.get(player.getWorld().getUID()));

            if (lease == null) {
                configResponse.setDistantGenerationEnabled(false);
                configResponse.setRealTimeUpdatesEnabled(false);
                this.pluginMessageHandler.sendPluginMessage(configMessage.getSender(), configResponse);
            } else DhGuard.send(lease, configResponse);
        });
    }
}
