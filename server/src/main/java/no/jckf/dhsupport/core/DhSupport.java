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

import cc.taixue.lod.server.DhGuard;

import no.jckf.dhsupport.core.bytestream.Encoder;
import no.jckf.dhsupport.core.configuration.Configurable;
import no.jckf.dhsupport.core.configuration.Configuration;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import no.jckf.dhsupport.core.database.Database;
import no.jckf.dhsupport.core.database.migrations.AddLodVersionColumn;
import no.jckf.dhsupport.core.database.migrations.CreateLodsTable;
import no.jckf.dhsupport.core.database.migrations.DeleteLodsBelowVersion3;
import no.jckf.dhsupport.core.database.migrations.DeleteLodsWithoutVersion;
import no.jckf.dhsupport.core.database.models.LodModel;
import no.jckf.dhsupport.core.database.repositories.AsyncLodRepository;
import no.jckf.dhsupport.core.dataobject.Lod;
import no.jckf.dhsupport.core.dataobject.SectionPosition;
import no.jckf.dhsupport.core.handler.LodHandler;
import no.jckf.dhsupport.core.handler.PlayerConfigHandler;
import no.jckf.dhsupport.core.handler.PluginMessageHandler;
import no.jckf.dhsupport.core.lodbuilders.LodBuilder;
import no.jckf.dhsupport.core.message.plugin.FullDataChunkMessage;
import no.jckf.dhsupport.core.message.plugin.FullDataPartialUpdateMessage;
import no.jckf.dhsupport.core.message.plugin.PluginMessageSender;
import no.jckf.dhsupport.core.scheduling.Scheduler;
import no.jckf.dhsupport.core.world.WorldInterface;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import javax.annotation.Nullable;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public class DhSupport implements Configurable
{
    protected String pluginVersion;

    protected String gameVersion;

    protected String dataDirectory;

    protected Database database;

    protected AsyncLodRepository lodRepository;

    protected Configuration configuration;

    protected Logger logger;

    protected Scheduler scheduler;

    protected UpdateChecker updateChecker;

    @Nullable
    protected CompletableFuture<?> pause;

    protected PerformanceTracker generationTracker = new PerformanceTracker();

    protected Map<UUID, WorldInterface> worldInterfaces = new HashMap<>();

    protected PluginMessageHandler pluginMessageHandler;

    protected PluginMessageSender pluginMessageSender;

    protected Map<String, CompletableFuture<Lod>> queuedBuilders = new ConcurrentHashMap<>();

    protected Map<String, LodModel> touchedLods = new ConcurrentHashMap<>();

    protected Map<UUID, Map<UUID, Configuration>> playerConfigurations = new HashMap<>();

    protected Map<UUID, PreGenerator> preGenerators = new HashMap<>();

    public DhSupport(String pluginVersion, String gameVersion)
    {
        this.pluginVersion = pluginVersion;
        this.gameVersion = gameVersion;

        this.configuration = new Configuration();

        this.database = new Database();
        this.lodRepository = new AsyncLodRepository(this.database);

        this.pluginMessageHandler = new PluginMessageHandler(this);

        this.updateChecker = new UpdateChecker(62013887);
    }

    public void onEnable()
    {
        this.lodRepository.setLogger(this.getLogger());

        try {
            this.database.open(
                this.getConfig().getString(DhsConfig.DATABASE_PATH)
                    .replace("{datadir}", this.getDataDirectory())
            );

            this.database.addMigration(CreateLodsTable.class);
            this.database.addMigration(AddLodVersionColumn.class);
            this.database.addMigration(DeleteLodsWithoutVersion.class);
            this.database.addMigration(DeleteLodsBelowVersion3.class);

            this.database.migrate();
        } catch (Exception exception) {
            throw new RuntimeException("Failed to initialize database!", exception);
        }

        (new PlayerConfigHandler(this, this.pluginMessageHandler)).register();
        (new LodHandler(this, this.pluginMessageHandler)).register();

        this.pluginMessageHandler.onEnable();

        if (this.getConfig().getBool(DhsConfig.GENERATE_NEW_CHUNKS, true) && this.getConfig().getBool(DhsConfig.GENERATE_NEW_CHUNKS_WARNING, true)) {
            this.warning("Chunk generation is enabled. New chunks will be generated as needed to complete LOD generation. This could significantly increase the size of your world.");
            this.warning("If you understand what this means and would like to disable this warning, set " + DhsConfig.GENERATE_NEW_CHUNKS_WARNING + " to false in your config.");
        }

        if (this.getConfig().getBool(DhsConfig.CHECK_FOR_UPDATES, true)) {
            this.getScheduler().runOnSeparateThread(() -> {
                this.checkUpdates();
                return null;
            });
        }
    }

    public void onDisable()
    {
        if (this.pluginMessageHandler != null) {
            this.pluginMessageHandler.onDisable();
            this.lodRepository.close();
            try { this.database.close(); } catch (java.sql.SQLException error) { this.warning("Database close failed: " + error.getMessage()); }
        }
    }

    public void setDataDirectory(String dataDirectory)
    {
        this.dataDirectory = dataDirectory;
    }

    public String getDataDirectory()
    {
        return this.dataDirectory;
    }

    public void setScheduler(Scheduler scheduler)
    {
        this.scheduler = scheduler;
    }

    public Scheduler getScheduler()
    {
        return this.scheduler;
    }

    public boolean pause()
    {
        if (this.pause != null) {
            return false;
        }

        this.pause = new CompletableFuture<>();

        return true;
    }

    public boolean unpause()
    {
        if (this.pause == null) {
            return false;
        }

        this.pause.complete(null);
        this.pause = null;

        return true;
    }

    public boolean isPaused()
    {
        return this.pause != null;
    }

    public void joinPauseState()
    {
        if (this.pause != null) {
            this.pause.join();
        }
    }

    public PerformanceTracker getGenerationTracker()
    {
        return this.generationTracker;
    }

    public void setWorldInterface(UUID id, @Nullable WorldInterface worldInterface)
    {
        if (worldInterface == null) {
            this.worldInterfaces.remove(id);
            return;
        }

        this.worldInterfaces.put(id, worldInterface);
    }

    public Map<UUID, WorldInterface> getWorldInterfaces()
    {
        return this.worldInterfaces;
    }

    @Nullable
    public WorldInterface getWorldInterface(UUID id)
    {
        return this.worldInterfaces.get(id);
    }

    public PluginMessageHandler getPluginMessageHandler()
    {
        return this.pluginMessageHandler;
    }

    public void setPluginMessageSender(PluginMessageSender sender)
    {
        this.pluginMessageSender = sender;
    }

    @Nullable
    public PluginMessageSender getPluginMessageSender()
    {
        return this.pluginMessageSender;
    }

    public AsyncLodRepository getLodRepository()
    {
        return this.lodRepository;
    }

    public String getPluginVersion()
    {
        return this.pluginVersion;
    }

    public String getGameVersion()
    {
        return this.gameVersion;
    }

    public Configuration getConfig()
    {
        return this.configuration;
    }

    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    @Nullable
    public Logger getLogger()
    {
        return this.logger;
    }

    public void info(String message)
    {
        this.getLogger().info(message);
    }

    public void warning(String message)
    {
        this.getLogger().warning(message);
    }

    public void debug(String message)
    {
        // TODO: Use log levels properly?
        if (!this.getConfig().getBool(DhsConfig.DEBUG, false)) {
            return;
        }

        this.getLogger().info("[DEBUG] " + message);
    }

    public void checkUpdates()
    {
        if (this.updateChecker.isLatestVersion(this.pluginVersion)) {
            return;
        }

        this.warning("A newer version of the plugin is available.");
    }

    public Map<UUID, Map<UUID, Configuration>> getPlayerConfigurations()
    {
        return this.playerConfigurations;
    }

    public void setPlayerConfiguration(UUID playerId, Map<UUID, Configuration> playerConfigurations)
    {
        this.playerConfigurations.put(playerId, playerConfigurations);
    }

    public Configuration getPlayerConfiguration(UUID playerId, UUID worldId)
    {
        Map<UUID, Configuration> configurations = this.playerConfigurations.get(playerId);

        return configurations == null ? null : configurations.get(worldId);
    }

    public void clearPlayerConfiguration(UUID playerId)
    {
        this.playerConfigurations.remove(playerId);
    }

    public LodBuilder getBuilder(WorldInterface world, SectionPosition position)
    {
        String builderType = world.getConfig().getString(DhsConfig.BUILDER_TYPE);

        LodBuilder builder;

        try {
            Class<? extends LodBuilder> builderClass = Class.forName(LodBuilder.class.getPackageName() + "." + builderType).asSubclass(LodBuilder.class);

            Constructor<?> constructor = builderClass.getConstructor(WorldInterface.class, SectionPosition.class);

            builder = (LodBuilder) constructor.newInstance(world, position);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException | ClassNotFoundException exception) {
            return null;
        }

        return builder;
    }

    public CompletableFuture<Lod> queueBuilder(UUID worldId, SectionPosition position, LodBuilder builder)
    {
        String key = LodModel.create()
            .setWorldId(worldId)
            .setX(position.getX())
            .setZ(position.getZ())
            .toString();

        if (this.queuedBuilders.containsKey(key)) {
            return this.queuedBuilders.get(key);
        }

        CompletableFuture<Lod> queued = this.getScheduler().runOnSeparateThread(() -> {
                this.joinPauseState();

                return builder.generate();
            })
            .thenApply((lod) -> {
                this.queuedBuilders.remove(key);

                return lod;
            })
            .exceptionally((exception) -> {
                exception.printStackTrace();

                this.queuedBuilders.remove(key);

                return null;
            });

        this.queuedBuilders.put(key, queued);

        return queued;
    }

    public CompletableFuture<LodModel> getLod(UUID worldId, SectionPosition position)
    {
        if (!DhGuard.cacheAllowed(worldId)) return CompletableFuture.completedFuture(null);
        return this.getLodRepository()
            .loadLodAsync(worldId, position.getX(), position.getZ())
            .thenComposeAsync((modelFromDb) -> {
                if (!DhGuard.cacheAllowed(worldId)) return CompletableFuture.completedFuture(null);
                // If an LOD was found in the database, return it.
                if (modelFromDb != null) {
                    return CompletableFuture.completedFuture(modelFromDb);
                }

                // Otherwise generate a new one.
                return this.generateLod(worldId, position, true);
            });
    }

    private final java.util.Map<String,CompletableFuture<LodModel>> activeModels=new java.util.HashMap<>();
    public synchronized CompletableFuture<LodModel> refreshLod(UUID world,int sectionX,int sectionZ){
        SectionPosition position=new SectionPosition();position.setDetailLevel(6);position.setX(sectionX);position.setZ(sectionZ);
        var previous=activeModels.get(world+":"+sectionX+":"+sectionZ);
        if(previous!=null)return previous.handle((v,e)->null).thenComposeAsync(ignored->this.generateLod(world,position,true));
        return this.generateLod(world,position,true);
    }
    protected synchronized CompletableFuture<LodModel> generateLod(UUID worldId,SectionPosition position,boolean manageChunks){
        if(!DhGuard.cacheAllowed(worldId))return CompletableFuture.completedFuture(null);
        var quota = this.lodRepository.getCacheQuota();
        if(quota != null && quota.pauseReason() != null)return CompletableFuture.completedFuture(null);
        String key=worldId+":"+position.getX()+":"+position.getZ();var previous=activeModels.get(key);if(previous!=null)return previous;
        var result=new CompletableFuture<LodModel>();activeModels.put(key,result);
        try{buildLod(worldId,position,manageChunks).whenComplete((value,error)->{
            synchronized(this){activeModels.remove(key,result);}
            if(error==null)result.complete(value);else result.completeExceptionally(error);
        });}catch(Exception e){activeModels.remove(key,result);result.completeExceptionally(e);}return result;
    }

    private CompletableFuture<LodModel> buildLod(UUID worldId, SectionPosition position, boolean manageChunks)
    {
        if (!DhGuard.cacheAllowed(worldId)) return CompletableFuture.completedFuture(null);
        int sectionWorldX = Coordinates.sectionToBlock(position.getX());
        int sectionWorldZ = Coordinates.sectionToBlock(position.getZ());

        WorldInterface world = this.getWorldInterface(worldId).newInstance();

        boolean generateNewChunks = false; // Remote requests and prewarming only read saved terrain.

        List<CompletableFuture<Boolean>> loads = new ArrayList<>();

        if (manageChunks) {
            boolean discardNewChunks = world.getConfig().getBool(DhsConfig.DISCARD_NEW_CHUNKS, false);

            // Load all the chunks we need for this request.
            Coordinates.foreachChunkInSection(position.getX(), position.getZ(), (chunkWorldX, chunkWorldZ) -> {
                final boolean alreadyLoaded = world.isChunkLoaded(chunkWorldX, chunkWorldZ);

                loads.add(
                    (generateNewChunks ? world.loadOrGenerateChunkAsync(chunkWorldX, chunkWorldZ) : world.loadChunkAsync(chunkWorldX, chunkWorldZ))
                        .whenComplete((loaded, throwable) -> {
                            if (!alreadyLoaded) {
                                if (discardNewChunks) {
                                    world.discardChunk(chunkWorldX, chunkWorldZ);
                                } else {
                                    world.unloadChunkAsync(chunkWorldX, chunkWorldZ);
                                }
                            }
                        })
                        .exceptionally((throwable -> {
                            this.warning(throwable.getMessage());

                            return false;
                        }))
                );
            });
        }

        // Wait for chunk loads, then...
        return CompletableFuture.allOf(loads.toArray(new CompletableFuture[0]))
            .thenComposeAsync((asd) -> {
                boolean loadRejected = loads.stream()
                    .anyMatch((load) -> !load.join());

                if (loadRejected) {
                    // Use dummy chunks instead of real world data.
                    world.setDummyMode(true);
                } else if (!manageChunks) {
                    // This only executes for benchmark runs, and ensures that LOD builders receive a world interface that has already cached the necessary chunks.
                    // The normal execution path, with manageChunks=true, performs caching during chunk loading.
                    final int seaLevel = world.getSeaLevel();

                    Coordinates.foreachChunkInSection(
                        position.getX(),
                        position.getZ(),
                        (chunkWorldX, chunkWorldZ) -> world.getMaterialAt(chunkWorldX, seaLevel, chunkWorldZ)
                    );
                }

                // No LOD was found. Start building a new one.
                CompletableFuture<Lod> lodFuture = this.queueBuilder(worldId, position, this.getBuilder(world, position));

                // Combine the LOD and beacons and save the result in the database.
                return lodFuture.thenApply((lod) -> {
                    Encoder lodEncoder = new Encoder(8192);
                    lod.encode(lodEncoder);

                    Encoder beaconEncoder = new Encoder(16 + lod.getBeacons().size() * 16);
                    beaconEncoder.writeCollection(lod.getBeacons());

                    this.generationTracker.ping();

                    if (!DhGuard.cacheAllowed(worldId)) return CompletableFuture.<LodModel>completedFuture(null);
                    return this.lodRepository.saveLodAsync(
                        worldId,
                        position.getX(),
                        position.getZ(),
                        lodEncoder.toByteArray(),
                        beaconEncoder.toByteArray(),
                        LodModel.VERSION
                    );
                })
                .thenCompose((f) -> f); // Unwrap the nested future.
            });
    }

    public void touchLod(UUID worldId, int x, int z, @Nullable String reason)
    {
        int sectionX = Coordinates.blockToSection(x);
        int sectionZ = Coordinates.blockToSection(z);

        LodModel lodModel = LodModel.create()
            .setWorldId(worldId)
            .setX(sectionX)
            .setZ(sectionZ);

        String key = lodModel.toString();

        // Ignore events triggered during LOD generation.
        if (this.queuedBuilders.containsKey(key)) {
            return;
        }

        if (this.touchedLods.containsKey(key)) {
            return;
        }

        WorldInterface world = this.getWorldInterface(worldId);

        if (world == null) {
            this.debug("Received a touch for unloaded world " + worldId + ". Ignoring.");
            return;
        }

        this.debug("Touched LOD at " + world.getName() + " " + x + " " + z + (reason == null ? "." : ": " + reason));

        this.touchedLods.put(key, lodModel);
    }

    public void touchLod(UUID worldId, int x, int z)
    {
        this.touchLod(worldId, x, z, null);
    }

    public void updateTouchedLods()
    {
        if (this.isPaused()) {
            return;
        }

        for (String key : this.touchedLods.keySet()) {
            LodModel lodModelToDelete = this.touchedLods.get(key);
            this.touchedLods.remove(key);

            WorldInterface world = this.getWorldInterface(lodModelToDelete.getWorldId());

            this.debug("Changes detected in " + world.getName() + " " + lodModelToDelete.getX() + " " + lodModelToDelete.getZ() + ".");

            this.getLodRepository().lodExistsAsync(lodModelToDelete.getWorldId(), lodModelToDelete.getX(), lodModelToDelete.getZ()).thenAccept((exists) -> {
                if (!exists) {
                    return;
                }

                this.getLodRepository().deleteLodAsync(lodModelToDelete.getWorldId(), lodModelToDelete.getX(), lodModelToDelete.getZ())
                    .thenAccept((deleted) -> {
                        if (!deleted) {
                            this.warning("Could not delete LOD " + world.getName() + " " + lodModelToDelete.getX() + " " + lodModelToDelete.getZ() + ".");
                            return;
                        }

                        SectionPosition position = new SectionPosition();
                        position.setDetailLevel(6);
                        position.setX(lodModelToDelete.getX());
                        position.setZ(lodModelToDelete.getZ());

                        this.getLod(lodModelToDelete.getWorldId(), position)
                            .thenAcceptAsync((newLodModel) -> {
                                Configuration worldConfig = world.getConfig();

                                // If this is false, then it will be false for all players as well.
                                boolean updatesEnabled = worldConfig.getBool(DhsConfig.REAL_TIME_UPDATES_ENABLED);

                                if (!updatesEnabled) {
                                    this.debug("New LOD " + world.getName() + " " + lodModelToDelete.getX() + " " + lodModelToDelete.getZ() + " generated, but real-time updates are disabled.");
                                    return;
                                }

                                String levelKeyPrefix = worldConfig.getString(DhsConfig.LEVEL_KEY_PREFIX);
                                String levelKey = world.getKey();

                                if (levelKeyPrefix != null) {
                                    levelKey = levelKeyPrefix + levelKey;
                                }

                                int lodChunkX = Coordinates.sectionToChunk(lodModelToDelete.getX());
                                int lodChunkZ = Coordinates.sectionToChunk(lodModelToDelete.getZ());

                                int playersInRangeCount = 0;
                                int playersOutOfRangeCount = 0;
                                int playersWithoutDhCount = 0;

                                // TODO: Don't use Bukkit classes.
                                var loadedWorld = Bukkit.getWorld(newLodModel.getWorldId());
                                if (loadedWorld == null) return;
                                for (Player player : loadedWorld.getPlayers()) {
                                    final var lease = DhGuard.issue(player.getUniqueId(), newLodModel.getWorldId());
                                    if (lease == null) continue;
                                    Configuration playerConfig = this.getPlayerConfiguration(player.getUniqueId(), world.getId());

                                    // No config for this player? Probably not using DH.
                                    if (playerConfig == null) {
                                        playersWithoutDhCount++;
                                        continue;
                                    }

                                    if (!playerConfig.getBool(DhsConfig.REAL_TIME_UPDATES_ENABLED)) {
                                        continue;
                                    }

                                    int updatesRadius = playerConfig.getInt(DhsConfig.REAL_TIME_UPDATE_RADIUS);

                                    int playerChunkX = Coordinates.blockToChunk(player.getLocation().getBlockX());
                                    int playerChunkZ = Coordinates.blockToChunk(player.getLocation().getBlockZ());

                                    int distanceX = Math.abs(Math.max(lodChunkX, playerChunkX) - Math.min(lodChunkX, playerChunkX));
                                    int distanceZ = Math.abs(Math.max(lodChunkZ, playerChunkZ) - Math.min(lodChunkZ, playerChunkZ));

                                    // Update outside of player's range?
                                    if (distanceX > updatesRadius || distanceZ > updatesRadius) {
                                        playersOutOfRangeCount++;
                                        continue;
                                    }

                                    playersInRangeCount++;

                                    int myBufferId = playerConfig.increment("buffer-id");

                                    FullDataPartialUpdateMessage partialUpdateMessage = new FullDataPartialUpdateMessage();
                                    partialUpdateMessage.setLevelKey(levelKey);
                                    partialUpdateMessage.setBufferId(myBufferId);
                                    partialUpdateMessage.setBeacons(newLodModel.getBeacons());

                                    byte[] data = newLodModel.getData();

                                    int chunkCount = (int) Math.ceil((double) data.length / LodHandler.CHUNK_SIZE);

                                    for (int chunkNo = 0; chunkNo < chunkCount; chunkNo++) {
                                        FullDataChunkMessage chunkResponse = new FullDataChunkMessage();
                                        chunkResponse.setBufferId(myBufferId);
                                        chunkResponse.setIsFirst(chunkNo == 0);
                                        chunkResponse.setData(
                                            data,
                                            LodHandler.CHUNK_SIZE * chunkNo,
                                            Math.min(LodHandler.CHUNK_SIZE, data.length - LodHandler.CHUNK_SIZE * chunkNo)
                                        );

                                        DhGuard.send(lease, chunkResponse);
                                    }

                                    DhGuard.send(lease, partialUpdateMessage);
                                }

                                this.debug("Updated LOD " + world.getName() + " " + lodModelToDelete.getX() + " " + lodModelToDelete.getZ() + " sent to " + playersInRangeCount + " players. Found " + playersOutOfRangeCount + " players out of range, and " + playersWithoutDhCount + " players without DH.");
                            }, task -> this.getScheduler().runOnMainThread(() -> { task.run(); return null; }));
                    });
            });
        }
    }

    public boolean isPreGenerating(WorldInterface world)
    {
        return this.preGenerators.containsKey(world.getId()) && this.getPreGenerator(world).isRunning();
    }

    public void preGenerate(WorldInterface world, int centerX, int centerZ, int radius, boolean force)
    {
        if (this.isPreGenerating(world)) {
            this.info("Cannot run multiple pre-generators in the same world. Stopping current task for " + world.getName() + "...");

            this.stopPreGenerator(world);
        }

        this.preGenerators.put(world.getId(), new PreGenerator(this, world, centerX, centerZ, radius, force));

        this.getScheduler().runOnSeparateThread(() -> {
            this.preGenerators.get(world.getId()).run();

            return null;
        }).exceptionally((exception) -> {
            this.getLogger().log(Level.WARNING, "Pre-generation of " + world.getName() + " failed.", exception);

            this.stopPreGenerator(world);

            return null;
        });
    }

    public @Nullable PreGenerator getPreGenerator(WorldInterface world)
    {
        return this.preGenerators.get(world.getId());
    }

    public void stopPreGenerator(WorldInterface world)
    {
        if (this.isPreGenerating(world)) {
            this.getPreGenerator(world).stop();

            this.preGenerators.remove(world.getId());
        }
    }

    public CompletableFuture<Integer> trim(WorldInterface world, int centerX, int centerZ, int radius)
    {
        return this.getLodRepository().trimLodsAsync(
            world.getId(),
            Coordinates.blockToSection(centerX - radius),
            Coordinates.blockToSection(centerZ - radius),
            Coordinates.blockToSection(centerX + radius),
            Coordinates.blockToSection(centerZ + radius)
        );
    }

    public CompletableFuture<Double> benchmark(UUID worldId, int iterations, int concurrency)
    {
        WorldInterface world = this.getWorldInterface(worldId);

        List<SectionPosition> positions = new ArrayList<>(concurrency);

        for (int i = 0; i < concurrency; i++) {
            SectionPosition position = new SectionPosition();
            position.setX(Coordinates.blockToSection(world.getSpawnX()) + i);
            position.setZ(Coordinates.blockToSection(world.getSpawnZ()));

            positions.add(position);
        }

        return CompletableFuture.allOf(
                positions.stream()
                    .map((position) -> this.generateLod(world.getId(), position, false))
                    .toArray(CompletableFuture[]::new)
            )
            .thenCompose((foo) -> {
                List<CompletableFuture<?>> chains = new ArrayList<>(positions.size());

                long ts = System.currentTimeMillis();

                for (SectionPosition position : positions) {
                    CompletableFuture<?> chain = CompletableFuture.completedFuture(null);

                    for (int i = 0; i < iterations; i++) {
                        chain = chain.thenCompose((bar) -> this.generateLod(world.getId(), position, false));
                    }

                    chains.add(chain);
                }

                return CompletableFuture.allOf(chains.toArray(CompletableFuture[]::new))
                    .thenApply((bar) -> {
                        double elapsed = (System.currentTimeMillis() - ts) / 1000d;

                        return iterations * concurrency / elapsed;
                    });
            });
    }
}
