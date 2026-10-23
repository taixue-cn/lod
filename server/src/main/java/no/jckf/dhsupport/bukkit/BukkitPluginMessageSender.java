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

package no.jckf.dhsupport.bukkit;

import no.jckf.dhsupport.core.message.plugin.PluginMessageSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

public class BukkitPluginMessageSender implements PluginMessageSender
{
    protected JavaPlugin plugin;
    private final cc.taixue.lod.server.RawSender raw;

    public BukkitPluginMessageSender(JavaPlugin plugin)
    {
        this.plugin = plugin;
        try { this.raw = new cc.taixue.lod.server.RawSender(); } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    @Override
    public void sendPluginMessage(UUID recipientUuid, String channel, byte[] message)
    {
        Player player = this.plugin.getServer().getPlayer(recipientUuid);

        if (player == null || !player.isOnline()) {
            return;
        }

        this.raw.send(player, channel, message);
    }
}
