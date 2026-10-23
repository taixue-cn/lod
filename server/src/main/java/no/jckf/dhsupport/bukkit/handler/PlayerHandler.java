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

package no.jckf.dhsupport.bukkit.handler;

import no.jckf.dhsupport.bukkit.DhSupportBukkitPlugin;
import no.jckf.dhsupport.core.DhSupport;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class PlayerHandler implements Listener
{
    protected DhSupportBukkitPlugin plugin;

    public PlayerHandler(DhSupportBukkitPlugin plugin)
    {
        this.plugin = plugin;
    }

    @EventHandler
    public void onPlayerConnect(PlayerJoinEvent event)
    {
        Player player = event.getPlayer();

        DhSupport dhSupport = this.plugin.getDhSupport();

        dhSupport.getScheduler().runOnMainThreadDelayed(() -> {
            if (dhSupport.getPlayerConfiguration(player.getUniqueId(), player.getWorld().getUID()) != null) {
                return null;
            }

            for (String message : dhSupport.getConfig().getStringList(DhsConfig.AD_MESSAGE, Collections.emptyList())) {
                player.sendMessage(message);
            }

            return null;
        }, 10, TimeUnit.SECONDS); // Arbitrary delay, waiting for DH config message (or the lack of one).
    }

    @EventHandler
    public void onPlayerDisconnect(PlayerQuitEvent event)
    {
        this.plugin.getDhSupport().clearPlayerConfiguration(event.getPlayer().getUniqueId());
    }
}
