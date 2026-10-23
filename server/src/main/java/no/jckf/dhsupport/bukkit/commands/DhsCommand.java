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
import no.jckf.dhsupport.core.Coordinates;
import no.jckf.dhsupport.core.PreGenerator;
import no.jckf.dhsupport.core.Utils;
import no.jckf.dhsupport.core.configuration.Configuration;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import no.jckf.dhsupport.core.world.WorldInterface;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;

public class DhsCommand implements CommandExecutor
{
    protected DhSupportBukkitPlugin plugin;

    public DhsCommand(DhSupportBukkitPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args)
    {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Missing sub-command.");
            return true;
        }

        switch (args[0]) {
            case "cache": return this.plugin.getCacheManager().command(sender, Arrays.copyOfRange(args,1,args.length));
            case "warm": return this.plugin.getWarmService().command(sender, Arrays.copyOfRange(args,1,args.length));
            case "lodworlds":
                return this.lodWorlds(sender, Arrays.copyOfRange(args, 1, args.length));
            case "status":
                return this.status(sender, Arrays.copyOfRange(args, 1, args.length));

            case "reload":
                return this.reload(sender);

            case "worlds":
                return this.worlds(sender);

            case "pregen":
                return this.pregen(sender, Arrays.copyOfRange(args, 1, args.length));

            case "pause":
                return this.pause(sender);

            case "unpause":
                return this.unpause(sender);

            case "trim":
                return this.trim(sender, Arrays.copyOfRange(args, 1, args.length));

            case "bench":
                return this.bench(sender, Arrays.copyOfRange(args, 1, args.length));

            case "config":
                return this.config(sender, Arrays.copyOfRange(args, 1, args.length));
        }

        sender.sendMessage(ChatColor.RED + "Unknown sub-command.");

        return true;
    }

    private boolean lodWorlds(CommandSender sender,String[] args) {
        String key="lod.worlds";
        if(args.length==2 && args[0].equals("mode")) {
            if(!args[1].equals("whitelist")&&!args[1].equals("blacklist")){sender.sendMessage("Use whitelist or blacklist");return true;}
            plugin.getConfig().set(key+".mode",args[1]);
        } else if(args.length==2 && (args[0].equals("add")||args[0].equals("remove"))) {
            var entries=new java.util.ArrayList<>(plugin.getConfig().getStringList(key+".entries"));
            if(args[0].equals("add")){if(!entries.contains(args[1]))entries.add(args[1]);}else entries.remove(args[1]);
            plugin.getConfig().set(key+".entries",entries);
        } else if(args.length!=0){sender.sendMessage("/dhs lodworlds [mode whitelist|blacklist / add <world> / remove <world>]");return true;}
        plugin.saveConfig();plugin.getWorldAccess().refreshPolicy();
        sender.sendMessage("LOD worlds: "+plugin.getConfig().getString(key+".mode")+" "+plugin.getConfig().getStringList(key+".entries"));return true;
    }

    protected boolean status(CommandSender sender, String[] args)
    {
        if (args.length == 0) {
            return this.statusServer(sender);
        }

        return this.statusPlayer(sender, args[0]);
    }

    protected boolean statusServer(CommandSender sender)
    {
        Configuration config;

        if (sender instanceof Player) {
            World world = ((Player) sender).getWorld();

            config = this.plugin.getDhSupport().getWorldInterface(world.getUID()).getConfig();

            sender.sendMessage(ChatColor.BLUE + "Distant Horizons Support status for world " + ChatColor.GREEN + world.getName() + ChatColor.BLUE + ":");
        } else {
            config = this.plugin.getDhSupport().getConfig();

            sender.sendMessage(ChatColor.BLUE + "Distant Horizons Support status for " + ChatColor.GREEN + "global context" + ChatColor.BLUE + ":");
        }

        sender.sendMessage(ChatColor.BLUE + "Distant generation is " + (config.getBool(DhsConfig.DISTANT_GENERATION_ENABLED) ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ChatColor.BLUE + " (" + ChatColor.GREEN + config.getInt(DhsConfig.RENDER_DISTANCE) + ChatColor.BLUE + ").");
        sender.sendMessage(ChatColor.BLUE + "Real time updates is " + (config.getBool(DhsConfig.REAL_TIME_UPDATES_ENABLED) ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ChatColor.BLUE + " (" + ChatColor.GREEN + config.getInt(DhsConfig.REAL_TIME_UPDATE_RADIUS) + ChatColor.BLUE + ").");
        sender.sendMessage(ChatColor.BLUE + "Builder type is " + ChatColor.GREEN + config.getString(DhsConfig.BUILDER_TYPE) + ChatColor.BLUE + ".");

        int playerCount = this.plugin.getDhSupport().getPlayerConfigurations().size();

        StringBuilder playerList = new StringBuilder();

        for (UUID playerId : this.plugin.getDhSupport().getPlayerConfigurations().keySet()) {
            playerList.append(ChatColor.GREEN + this.plugin.getServer().getPlayer(playerId).getName() + ChatColor.BLUE + ", ");
        }

        sender.sendMessage(ChatColor.BLUE + "There " + (playerCount == 1 ? "is" : "are") + " " + ChatColor.GREEN + playerCount + ChatColor.BLUE + " " + (playerCount == 1 ? "player" : "players") + " online using Distant Horizons" + (playerCount == 0 ? "." : ": " + playerList.substring(0, playerList.length() - 2)));

        sender.sendMessage(ChatColor.BLUE + "Current generation speed: " + ChatColor.GREEN + String.format("%.2f", this.plugin.getDhSupport().getGenerationTracker().getPingsPerSecond() * 16) + " CPS");

        return true;
    }

    protected boolean statusPlayer(CommandSender sender, String playerName)
    {
        Player player = this.plugin.getServer().getPlayer(playerName);

        if (player == null) {
            sender.sendMessage(ChatColor.RED + "Player not found.");

            return true;
        }

        Configuration config = this.plugin.getDhSupport().getPlayerConfiguration(player.getUniqueId(), player.getWorld().getUID());

        if (config == null) {
            sender.sendMessage(ChatColor.YELLOW + "The player " + ChatColor.WHITE + player.getName() + ChatColor.YELLOW + " is not using Distant Horizons.");

            return true;
        }

        sender.sendMessage(ChatColor.BLUE + "Distant Horizons Support status for player " + ChatColor.GREEN + player.getName() + ChatColor.BLUE + ":");

        sender.sendMessage(ChatColor.BLUE + "Distant generation is " + (config.getBool(DhsConfig.DISTANT_GENERATION_ENABLED) ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ChatColor.BLUE + " (" + ChatColor.GREEN + config.getInt(DhsConfig.RENDER_DISTANCE) + ChatColor.BLUE + ").");
        sender.sendMessage(ChatColor.BLUE + "Real time updates is " + (config.getBool(DhsConfig.REAL_TIME_UPDATES_ENABLED) ? ChatColor.GREEN + "enabled" : ChatColor.RED + "disabled") + ChatColor.BLUE + " (" + ChatColor.GREEN + config.getInt(DhsConfig.REAL_TIME_UPDATE_RADIUS) + ChatColor.BLUE + ").");
        sender.sendMessage(ChatColor.BLUE + "Server has sent " + ChatColor.GREEN + config.getInt("buffer-id", 0) + ChatColor.BLUE + " LODs to them.");

        return true;
    }

    protected boolean reload(CommandSender sender)
    {
        sender.sendMessage(ChatColor.YELLOW + "Reloading config...");

        this.plugin.loadDhsConfig();

        sender.sendMessage(ChatColor.GREEN + "Reload complete.");

        return true;
    }

    protected boolean worlds(CommandSender sender)
    {
        sender.sendMessage(ChatColor.GREEN + "World list:");

        for (World bukkitWorld : this.plugin.getServer().getWorlds()) {
            sender.sendMessage(ChatColor.YELLOW + " - " + ChatColor.GREEN + bukkitWorld.getName() + " " + ChatColor.GRAY + "(" + bukkitWorld.getUID() + ")");
        }

        return true;
    }

    protected boolean pregen(CommandSender sender, String[] args)
    {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Missing action.");
            return true;
        }

        switch (args[0]) {
            case "start":
                return this.pregenStart(sender, Arrays.copyOfRange(args, 1, args.length));

            case "stop":
                return this.pregenStop(sender, Arrays.copyOfRange(args, 1, args.length));

            case "lodworlds":
                return this.lodWorlds(sender, Arrays.copyOfRange(args, 1, args.length));
            case "status":
                return this.pregenStatus(sender, Arrays.copyOfRange(args, 1, args.length));
        }

        sender.sendMessage(ChatColor.RED + "Unknown action.");

        return true;
    }

    protected boolean pregenStart(CommandSender sender, String[] args)
    {
        WorldInterface world;
        Integer centerX;
        Integer centerZ;
        Integer radius;

        if (args.length >= 1) {
            World bukkitWorld = this.plugin.getWorld(args[0]);

            if (bukkitWorld == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world.");
                return true;
            }

            world = this.plugin.getDhSupport().getWorldInterface(bukkitWorld.getUID());
        } else if (sender instanceof Player) {
            world = this.plugin.getDhSupport().getWorldInterface(((Player) sender).getWorld().getUID());
        } else {
            world = null;
        }

        if (world == null) {
            sender.sendMessage(ChatColor.RED + "No world specified.");
            return true;
        }

        if (args.length >= 3) {
            centerX = Integer.parseInt(args[1]);
            centerZ = Integer.parseInt(args[2]);
        } else {
            centerX = world.getWorldBorderX();
            centerZ = world.getWorldBorderZ();
        }

        if (centerX == null || centerZ == null) {
            sender.sendMessage(ChatColor.RED + "No center coordinates specified.");
            return true;
        }

        if (args.length >= 4) {
            radius = Coordinates.chunkToBlock(Integer.parseInt(args[3]));
        } else {
            radius = world.getWorldBorderRadius();
        }

        if (radius == null) {
            sender.sendMessage(ChatColor.RED + "No radius specified.");
            return true;
        }

        boolean force = args.length >= 5 && args[4].equals("force");

        sender.sendMessage(ChatColor.YELLOW + "Generating LODs for view distance of " + ChatColor.GREEN + Coordinates.blockToChunk(radius) + ChatColor.YELLOW + " chunks in world " + ChatColor.GREEN + world.getName() + ChatColor.YELLOW + " starting at center " + ChatColor.GREEN + centerX + " " + centerZ + ChatColor.YELLOW + "...");

        if (force) {
            sender.sendMessage(ChatColor.YELLOW + "All existing LODs in this area will be re-generated.");
        }

        this.plugin.getDhSupport().preGenerate(world, centerX, centerZ, radius, force);

        return true;
    }

    protected boolean pregenStop(CommandSender sender, String[] args)
    {
        WorldInterface world;

        if (args.length >= 1) {
            World bukkitWorld = this.plugin.getWorld(args[0]);

            if (bukkitWorld == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world.");
                return true;
            }

            world = this.plugin.getDhSupport().getWorldInterface(bukkitWorld.getUID());
        } else if (sender instanceof Player) {
            world = this.plugin.getDhSupport().getWorldInterface(((Player) sender).getWorld().getUID());
        } else {
            world = null;
        }

        if (world == null) {
            sender.sendMessage(ChatColor.RED + "No world specified.");
            return true;
        }

        if (!this.plugin.getDhSupport().isPreGenerating(world)) {
            sender.sendMessage(ChatColor.RED + "No pre-generator running in world " + ChatColor.YELLOW + world.getName() + ChatColor.RED + ".");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Stopping pre-generation in world " + ChatColor.GREEN + world.getName() + ChatColor.YELLOW + "...");

        this.plugin.getDhSupport().stopPreGenerator(world);

        return true;
    }

    protected boolean pregenStatus(CommandSender sender, String[] args)
    {
        WorldInterface world;

        if (args.length >= 1) {
            World bukkitWorld = this.plugin.getWorld(args[0]);

            if (bukkitWorld == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world.");
                return true;
            }

            world = this.plugin.getDhSupport().getWorldInterface(bukkitWorld.getUID());
        } else if (sender instanceof Player) {
            world = this.plugin.getDhSupport().getWorldInterface(((Player) sender).getWorld().getUID());
        } else {
            world = null;
        }

        if (world == null) {
            sender.sendMessage(ChatColor.RED + "No world specified.");
            return true;
        }

        PreGenerator generator = this.plugin.getDhSupport().getPreGenerator(world);

        if (generator == null) {
            sender.sendMessage(ChatColor.RED + "No pre-generator running in world " + ChatColor.YELLOW + world.getName() + ChatColor.RED + ".");
            return true;
        }

        Duration elapsedTime = generator.getElapsedTime();

        float momentaryLodsPerSecond = (float) this.plugin.getDhSupport().getGenerationTracker().getPingsPerSecond();
        float totalLodsPerSecond = (float) generator.getCompletedRequests() / elapsedTime.toSeconds();

        sender.sendMessage(ChatColor.GREEN + "Generation progress: " + ChatColor.YELLOW + String.format("%.2f", generator.getProgress() * 100f) + "%");
        sender.sendMessage(ChatColor.GREEN + "Processed LODs: " + ChatColor.YELLOW + generator.getCompletedRequests() + ChatColor.GREEN + " / " + ChatColor.YELLOW + generator.getTargetRequests() + ChatColor.GREEN + " (" + ChatColor.YELLOW + String.format("%.2f", totalLodsPerSecond * 16) + ChatColor.GREEN + " CPS)");
        sender.sendMessage(ChatColor.GREEN + "Time elapsed: " + ChatColor.YELLOW + Utils.humanReadableDuration(elapsedTime));

        if (generator.isRunning()) {
            sender.sendMessage(ChatColor.GREEN + "Time remaining: " + ChatColor.YELLOW + Utils.humanReadableDuration(Duration.ofSeconds((long) ((generator.getTargetRequests() - generator.getCompletedRequests()) / momentaryLodsPerSecond))));
        } else {
            sender.sendMessage(ChatColor.GREEN + "Generation is complete.");
        }

        return true;
    }

    protected boolean pause(CommandSender sender)
    {
        if (this.plugin.getDhSupport().isPaused()) {
            sender.sendMessage(ChatColor.RED + "Already paused.");
            return true;
        }

        if (!this.plugin.getDhSupport().pause()) {
            sender.sendMessage(ChatColor.RED + "Could not pause. Check server log for error messages.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "DHS has been paused.");

        return true;
    }

    protected boolean unpause(CommandSender sender)
    {
        if (!this.plugin.getDhSupport().isPaused()) {
            sender.sendMessage(ChatColor.RED + "Not paused.");
            return true;
        }

        if (!this.plugin.getDhSupport().unpause()) {
            sender.sendMessage(ChatColor.RED + "Could not unpause. Check server log for error messages.");
            return true;
        }

        sender.sendMessage(ChatColor.GREEN + "DHS has been unpaused.");

        return true;
    }

    protected boolean trim(CommandSender sender, String[] args)
    {
        WorldInterface world;
        Integer centerX;
        Integer centerZ;
        Integer radius;

        if (args.length >= 1) {
            World bukkitWorld = this.plugin.getWorld(args[0]);

            if (bukkitWorld == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world.");
                return true;
            }

            world = this.plugin.getDhSupport().getWorldInterface(bukkitWorld.getUID());
        } else if (sender instanceof Player) {
            world = this.plugin.getDhSupport().getWorldInterface(((Player) sender).getWorld().getUID());
        } else {
            world = null;
        }

        if (world == null) {
            sender.sendMessage(ChatColor.RED + "No world specified.");
            return true;
        }

        if (args.length >= 3) {
            centerX = Integer.parseInt(args[1]);
            centerZ = Integer.parseInt(args[2]);
        } else {
            centerX = world.getWorldBorderX();
            centerZ = world.getWorldBorderZ();
        }

        if (centerX == null || centerZ == null) {
            sender.sendMessage(ChatColor.RED + "No center coordinates specified.");
            return true;
        }

        if (args.length >= 4) {
            radius = Integer.parseInt(args[3]);
        } else {
            radius = world.getWorldBorderRadius();
        }

        if (radius == null) {
            sender.sendMessage(ChatColor.RED + "No radius specified.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "Trimming LODs outside view distance of " + ChatColor.GREEN + radius + ChatColor.YELLOW + " blocks in world " + ChatColor.GREEN + world.getName() + ChatColor.YELLOW + " centered at " + ChatColor.GREEN + centerX + " " + centerZ + ChatColor.YELLOW + "...");

        this.plugin.getDhSupport().trim(world, centerX, centerZ, radius)
            .thenAccept((trimmedCount) -> {
                sender.sendMessage(ChatColor.GREEN + "Trimming of " + ChatColor.YELLOW + trimmedCount + ChatColor.GREEN + " LODs in " + ChatColor.YELLOW + world.getName() + ChatColor.GREEN + " completed.");
            });

        return true;
    }

    protected boolean bench(CommandSender sender, String[] args)
    {
        int schedulerThreads = this.plugin.getDhSupport().getConfig().getInt(DhsConfig.SCHEDULER_THREADS);

        WorldInterface world;
        int iterations = 1000;
        int concurrency = schedulerThreads;

        if (args.length >= 1) {
            World bukkitWorld = this.plugin.getWorld(args[0]);

            if (bukkitWorld == null) {
                sender.sendMessage(ChatColor.RED + "Unknown world.");
                return true;
            }

            world = this.plugin.getDhSupport().getWorldInterface(bukkitWorld.getUID());
        } else if (sender instanceof Player) {
            world = this.plugin.getDhSupport().getWorldInterface(((Player) sender).getWorld().getUID());
        } else {
            world = null;
        }

        if (world == null) {
            sender.sendMessage(ChatColor.RED + "No world specified.");
            return true;
        }

        if (args.length >= 2) {
            iterations = Integer.parseInt(args[1]);
        }

        if (args.length >= 3) {
            concurrency = Integer.parseInt(args[2]);
        }

        if (concurrency > schedulerThreads) {
            sender.sendMessage(ChatColor.RED + "Concurrency of " + ChatColor.YELLOW + concurrency + ChatColor.RED + " is more than the " + ChatColor.YELLOW + schedulerThreads + ChatColor.RED + " scheduler threads this server is running.");
            return true;
        }

        sender.sendMessage(ChatColor.BLUE + "Starting benchmark with " + ChatColor.GREEN + iterations + ChatColor.BLUE + " iterations times " + ChatColor.GREEN + concurrency + ChatColor.BLUE + " threads in world " + ChatColor.GREEN + world.getName() + ChatColor.BLUE + "...");

        this.plugin.getDhSupport().benchmark(world.getId(), iterations, concurrency)
            .thenAccept((lodsPerSecond) -> {
                sender.sendMessage(ChatColor.GREEN + "Benchmark completed! Result: " + ChatColor.BLUE + String.format("%.2f", lodsPerSecond * 16) + ChatColor.GREEN + " CPS.");
            });

        return true;
    }

    public boolean config(CommandSender sender, String[] args)
    {
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Missing action.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Missing key.");
            return true;
        }

        switch (args[0]) {
            case "set":
                if (args.length < 3) {
                    sender.sendMessage(ChatColor.RED + "Missing value.");
                    return true;
                }

                this.plugin.getDhSupport().getConfig().set(args[1], args[2]);

                sender.sendMessage(ChatColor.GREEN + "Set " + ChatColor.BLUE + args[1] + ChatColor.GREEN + " to " + ChatColor.BLUE + args[2] + ChatColor.GREEN + ".");
                return true;

            case "get":
                sender.sendMessage(ChatColor.GREEN + "Value of " + ChatColor.BLUE + args[1] + ChatColor.GREEN + " is " + ChatColor.BLUE + this.plugin.getDhSupport().getConfig().get(args[1]) + ChatColor.GREEN + ".");
                return true;

            case "unset":
                this.plugin.getDhSupport().getConfig().unset(args[1]);

                sender.sendMessage(ChatColor.GREEN + "Unset " + ChatColor.BLUE + args[1] + ChatColor.GREEN + ".");
                return true;
        }

        sender.sendMessage(ChatColor.RED + "Unknown action.");

        return true;
    }
}
