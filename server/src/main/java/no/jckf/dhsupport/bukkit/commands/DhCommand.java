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

package no.jckf.dhsupport.bukkit.commands;

import no.jckf.dhsupport.bukkit.DhSupportBukkitPlugin;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class DhCommand implements CommandExecutor
{
    protected DhSupportBukkitPlugin plugin;

    public DhCommand(DhSupportBukkitPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args)
    {
        if (!this.plugin.getDhSupport().getConfig().getBool(DhsConfig.ENABLE_DH_COMMAND_HELP_TEXT, true)) {
            return true;
        }

        sender.sendMessage(ChatColor.RED + "You have issued a " + ChatColor.YELLOW + "/dh" + ChatColor.RED + " command which is meant for the Distant Horizons " + ChatColor.YELLOW + "mod" + ChatColor.RED + ", but you are using the " + ChatColor.YELLOW + "plugin" + ChatColor.RED + ". Please make sure you are reading the correct documentation.");

        return true;
    }
}
