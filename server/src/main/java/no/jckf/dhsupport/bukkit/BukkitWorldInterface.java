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

import cc.taixue.lod.server.LodServer;

import no.jckf.dhsupport.core.Coordinates;
import no.jckf.dhsupport.core.Utils;
import no.jckf.dhsupport.core.configuration.Configuration;
import no.jckf.dhsupport.core.configuration.DhsConfig;
import no.jckf.dhsupport.core.configuration.WorldConfiguration;
import no.jckf.dhsupport.core.scheduling.Scheduler;
import no.jckf.dhsupport.core.world.MaterialRecord;
import no.jckf.dhsupport.core.world.WorldInterface;
import org.bukkit.*;

import javax.annotation.Nullable;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public class BukkitWorldInterface implements WorldInterface
{
    protected static boolean BIOME_KEY_WARNING_SENT = false;

    protected static boolean ASYNC_LOAD_WARNING_SENT = false;

    protected LodServer plugin;

    protected World world;

    protected Configuration config;

    protected WorldConfiguration worldConfig;

    protected Logger logger;

    protected boolean dummyMode = false;

    protected Map<Integer, String> dummyColumn;

    protected boolean trustHeightMap = true;

    private Integer sectionX;
    private Integer sectionZ;

    private final ChunkSnapshot[] chunks = new ChunkSnapshot[16];

    protected EnumMap<Material, MaterialRecord> materialCache = new EnumMap<>(Material.class);

    protected Map<String, Map<String, String>> propertyCache = new HashMap<>();

    protected UnsafeValues unsafeValues;

    @Nullable
    protected Method getCoordinateScale;

    @Nullable
    protected Method getBiomeKey;

    @Nullable
    protected Method getChunkAtAsync;

    protected int lightOffset = 0;

    protected Integer seaLevel;

    protected Integer dummyMaxY;

    public BukkitWorldInterface(LodServer plugin, World world, Configuration config)
    {
        this.plugin = plugin;
        this.world = world;
        this.config = config;
        this.worldConfig = new WorldConfiguration(this, config);

        this.lightOffset = this.getConfig().getInt(DhsConfig.LIGHT_OFFSET_HACK, this.plugin.getDhSupport().getGameVersion().equals("1.20.1") ? this.world.getMinHeight() : 0);
        this.trustHeightMap = this.getConfig().getBool(DhsConfig.TRUST_HEIGHT_MAP, this.trustHeightMap);
    }

    public void setLogger(Logger logger)
    {
        this.logger = logger;
    }

    public Logger getLogger()
    {
        return this.logger;
    }

    public void doUnsafeThings()
    {
        this.unsafeValues = Bukkit.getUnsafe();

        // Does not exist for 1.16.5.
        try {
            this.getCoordinateScale = this.world.getClass().getMethod("getCoordinateScale");
        } catch (NoSuchMethodException e) {

        }

        // Detect if we're running under Paper (or a Paper fork) that has this patch:
        // https://github.com/PaperMC/Paper/commit/5bf259115c1ce29dd96df5fcf53739c94d39f902
        try {
            Class<?> regionAccessor = Class.forName("org.bukkit.RegionAccessor");

            this.getBiomeKey = this.unsafeValues.getClass().getMethod("getBiomeKey", regionAccessor, int.class, int.class, int.class);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            if (!BIOME_KEY_WARNING_SENT) {
                this.getLogger().warning("Custom biomes are not supported on this server.");

                BIOME_KEY_WARNING_SENT = true;
            }
        }

        try {
            this.getChunkAtAsync = this.world.getClass().getMethod("getChunkAtAsync", int.class, int.class, boolean.class);
        } catch (NoSuchMethodException exception) {
            if (!ASYNC_LOAD_WARNING_SENT) {
                this.getLogger().warning("Async chunk loading is not supported on this server. Performance will suffer.");

                ASYNC_LOAD_WARNING_SENT = true;
            }
        }
    }

    @Override
    public WorldInterface newInstance()
    {
        BukkitWorldInterface newInstance = new BukkitWorldInterface(this.plugin, this.world, this.config);

        newInstance.setLogger(this.getLogger());

        newInstance.doUnsafeThings();

        return newInstance;
    }

    protected ChunkSnapshot getChunk(int x, int z)
    {
        return this.getChunk(x, z, null);
    }

    protected ChunkSnapshot getChunk(int x, int z, @Nullable ChunkSnapshot alreadyLoaded)
    {
        int chunkX = Coordinates.blockToChunk(x);
        int chunkZ = Coordinates.blockToChunk(z);

        int sectionX = Coordinates.chunkToSection(chunkX);
        int sectionZ = Coordinates.chunkToSection(chunkZ);

        if (this.sectionX == null || this.sectionZ == null) {
            this.sectionX = sectionX;
            this.sectionZ = sectionZ;
        } else if (this.sectionX != sectionX || this.sectionZ != sectionZ) {
            throw new IllegalStateException("We appear to be leaking world interface instances across LOD sections");
        }

        int baseChunkX = Coordinates.sectionToChunk(sectionX);
        int baseChunkZ = Coordinates.sectionToChunk(sectionZ);

        int index = (chunkX - baseChunkX) + ((chunkZ - baseChunkZ) << 2);

        ChunkSnapshot chunk = this.chunks[index];

        if (chunk != null) {
            return chunk;
        }

        Scheduler scheduler = this.plugin.getDhSupport().getScheduler();

        if (alreadyLoaded != null) {
            chunk = alreadyLoaded;
        } else if (scheduler.canReadWorldAsync()) {
            chunk = this.world.isChunkGenerated(chunkX,chunkZ)?this.world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(true, true, false):this.world.getEmptyChunkSnapshot(chunkX,chunkZ,true,false);
        } else {
            chunk = scheduler.runOnRegionThread(world.getUID(), x, z, () ->
                this.world.isChunkGenerated(chunkX,chunkZ)?this.world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(true, true, false):this.world.getEmptyChunkSnapshot(chunkX,chunkZ,true,false)
            ).join();
        }

        this.chunks[index] = chunk;

        return chunk;
    }

    public boolean isInsideSection(int x, int z)
    {
        return Coordinates.blockToSection(x) == this.sectionX && Coordinates.blockToSection(z) == this.sectionZ;
    }

    @Override
    public UUID getId()
    {
        return this.world.getUID();
    }

    @Override
    public String getName()
    {
        return this.world.getName();
    }

    @Override
    public String getKey()
    {
        return this.getName().replaceAll("[^a-zA-Z0-9_-]", "");
    }

    @Override
    public double getCoordinateScale()
    {
        if (this.getCoordinateScale == null) {
            boolean isNether = this.world.getEnvironment().equals(World.Environment.NETHER);

            return isNether ? 8 : 1;
        }

        try {
            return (double) this.getCoordinateScale.invoke(this.world);
        } catch (InvocationTargetException | IllegalAccessException exception) {
            throw new RuntimeException(exception);
        }
    }

    protected boolean useVanillaWorldBorder()
    {
        return this.worldConfig.getBool(DhsConfig.USE_VANILLA_WORLD_BORDER, true);
    }

    @Override
    public void setDummyMode(boolean enable)
    {
        this.dummyMode = enable;
    }

    @Override
    public boolean isDummyMode()
    {
        return this.dummyMode;
    }

    protected Map<Integer, String> buildDummyColumn()
    {
        int minY = this.getMinY();

        Map<Integer, String> column = new HashMap<>();

        for (String spec : this.getConfig().getStringList(DhsConfig.DUMMY_CHUNK)) {
            String[] components = spec.toLowerCase().split(",", 2);

            String namespacedKey = Utils.namespaceKey(components[1]);

            if (namespacedKey.equals("minecraft:air") || namespacedKey.equals("minecraft:void_air")) {
                continue;
            }

            String[] range = components[0].split("-", 2);

            int rangeStart = Integer.parseInt(range[0]);
            int rangeEnd = range.length == 1 ? rangeStart : Integer.parseInt(range[1]);

            for (int i = rangeStart; i <= rangeEnd; i++) {
                column.put(minY + i, namespacedKey);
            }
        }

        return column;
    }

    protected Map<Integer, String> getDummyColumn()
    {
        if (this.dummyColumn == null) {
            this.dummyColumn = this.buildDummyColumn();
        }

        return this.dummyColumn;
    }

    protected String getDummyMaterialAt(int y)
    {
        return this.getDummyColumn().getOrDefault(y, "minecraft:air");
    }

    @Override
    public Integer getWorldBorderX()
    {
        if (this.useVanillaWorldBorder()) {
            return this.world.getWorldBorder().getCenter().getBlockX();
        }

        return this.worldConfig.getInt(DhsConfig.BORDER_CENTER_X);
    }

    @Override
    public Integer getWorldBorderZ()
    {
        if (this.useVanillaWorldBorder()) {
            return this.world.getWorldBorder().getCenter().getBlockZ();
        }

        return this.worldConfig.getInt(DhsConfig.BORDER_CENTER_Z);
    }

    @Override
    public Integer getWorldBorderRadius()
    {
        if (!this.useVanillaWorldBorder()) {
            return this.worldConfig.getInt(DhsConfig.BORDER_RADIUS);
        }

        int borderRadius = (int) (this.world.getWorldBorder().getSize() / 2.0);

        if (this.worldConfig.getString(DhsConfig.VANILLA_WORLD_BORDER_EXPANSION, "auto").equals("auto")) {
            borderRadius += Coordinates.chunkToBlock(this.world.getViewDistance());
        } else {
            borderRadius += Coordinates.chunkToBlock(this.worldConfig.getInt(DhsConfig.VANILLA_WORLD_BORDER_EXPANSION, 0));
        }

        return borderRadius;
    }

    @Override
    public boolean isInsideWorldBorder(int x, int z, int areaWidth)
    {
        Integer borderCenterX = this.getWorldBorderX();
        Integer borderCenterZ = this.getWorldBorderZ();
        Integer borderRadius = this.getWorldBorderRadius();

        if (borderCenterX == null || borderCenterZ == null || borderRadius == null) {
            return true;
        }

        int borderMinX = borderCenterX - borderRadius;
        int borderMaxX = borderCenterX + borderRadius;
        int borderMinZ = borderCenterZ - borderRadius;
        int borderMaxZ = borderCenterZ + borderRadius;

        int sectionMaxX = x + (areaWidth - 1);
        int sectionMaxZ = z + (areaWidth - 1);

        return x <= borderMaxX
            && sectionMaxX >= borderMinX
            && z <= borderMaxZ
            && sectionMaxZ >= borderMinZ;
    }

    @Override
    public boolean chunkExists(int x, int z)
    {
        if (this.isDummyMode()) {
            return true;
        }

        int chunkX = Coordinates.blockToChunk(x);
        int chunkZ = Coordinates.blockToChunk(z);

        boolean alreadyLoaded = this.world.isChunkLoaded(chunkX, chunkZ);

        if (alreadyLoaded) {
            return true;
        }

        int regionX = Coordinates.chunkToRegion(chunkX);
        int regionZ = Coordinates.chunkToRegion(chunkZ);

        File regionFile = new File(world.getWorldFolder() + "/region/r." + regionX + "." + regionZ + ".mca");

        if (!regionFile.exists()) {
            return false;
        }

        boolean exists = this.world.loadChunk(chunkX, chunkZ, false);

        if (exists) {
            this.world.unloadChunk(chunkX, chunkZ, false);
        }

        return exists;
    }

    @Override
    public boolean isChunkLoaded(int x, int z)
    {
        if (this.isDummyMode()) {
            return true;
        }

        int chunkX = Coordinates.blockToChunk(x);
        int chunkZ = Coordinates.blockToChunk(z);

        return this.world.isChunkLoaded(chunkX, chunkZ);
    }

    @Override
    public boolean loadChunk(int x, int z)
    {
        if (this.isDummyMode()) {
            return true;
        }

        int chunkX = Coordinates.blockToChunk(x);
        int chunkZ = Coordinates.blockToChunk(z);

        if (!this.world.loadChunk(chunkX, chunkZ, false)) {
            return false;
        }

        this.getChunk(x, z);

        return true;
    }

    @Override
    public boolean loadOrGenerateChunk(int x, int z)
    {
        if (this.isDummyMode()) {
            return true;
        }

        int chunkX = Coordinates.blockToChunk(x);
        int chunkZ = Coordinates.blockToChunk(z);

        if (!this.world.loadChunk(chunkX, chunkZ, true)) {
            return false;
        }

        this.getChunk(x, z);

        return true;
    }

    @Override
    public CompletableFuture<Boolean> loadChunkAsync(int x,int z){
        return this.plugin.getDhSupport().getScheduler().runOnMainThread(()->loadSavedChunkAsync(x,z)).thenCompose(f->f);
    }

    private CompletableFuture<Boolean> loadSavedChunkAsync(int x, int z)
    {
        if (this.isDummyMode()) {
            return CompletableFuture.completedFuture(true);
        }

        if (this.getChunkAtAsync == null) {
            return this.plugin.getDhSupport().getScheduler().runOnMainThread(() -> this.loadChunk(x, z));
        }

        int chunkX = Coordinates.blockToChunk(x);
        int chunkZ = Coordinates.blockToChunk(z);
        if(!this.world.isChunkGenerated(chunkX,chunkZ)){this.getChunk(x,z,this.world.getEmptyChunkSnapshot(chunkX,chunkZ,true,false));return CompletableFuture.completedFuture(true);}
        if(!this.world.isChunkLoaded(chunkX,chunkZ))cc.taixue.lod.server.BackgroundChunks.loaded(this.world.getUID(),chunkX,chunkZ);

        try {
            return ((CompletableFuture<Chunk>) this.getChunkAtAsync.invoke(this.world, chunkX, chunkZ, false))
                .thenApply((chunk) -> {
                    if (chunk == null) {
                        this.getChunk(x,z,this.world.getEmptyChunkSnapshot(chunkX,chunkZ,true,false));
                        return true;
                    }

                    this.getChunk(x, z, chunk.getChunkSnapshot(true, true, false));

                    return true;
                });
        } catch (InvocationTargetException | IllegalAccessException exception) {
            throw new RuntimeException(exception);
        }
    }

    @Override
    public CompletableFuture<Boolean> loadOrGenerateChunkAsync(int x, int z)
    {
        if (this.isDummyMode()) {
            return CompletableFuture.completedFuture(true);
        }

        if (this.getChunkAtAsync == null) {
            return this.plugin.getDhSupport().getScheduler().runOnMainThread(() -> this.loadOrGenerateChunk(x, z));
        }

        int chunkX = Coordinates.blockToChunk(x);
        int chunkZ = Coordinates.blockToChunk(z);

        try {
            return ((CompletableFuture<Chunk>) this.getChunkAtAsync.invoke(this.world, chunkX, chunkZ, true))
                .thenApply((chunk) -> {
                    if (chunk == null) {
                        return false;
                    }

                    this.getChunk(x, z, chunk.getChunkSnapshot(true, true, false));

                    return true;
                });
        } catch (InvocationTargetException | IllegalAccessException exception) {
            throw new RuntimeException(exception);
        }
    }

    @Override
    public boolean unloadChunk(int x, int z)
    {
        if (this.isDummyMode()) {
            return true;
        }

        int chunkX = Coordinates.blockToChunk(x);
        int chunkZ = Coordinates.blockToChunk(z);

        return this.world.unloadChunk(chunkX, chunkZ);
    }

    @Override
    public boolean unloadChunkAsync(int x, int z)
    {
        if (this.isDummyMode()) {
            return true;
        }

        int chunkX = Coordinates.blockToChunk(x);
        int chunkZ = Coordinates.blockToChunk(z);

        return this.world.unloadChunkRequest(chunkX, chunkZ);
    }

    @Override
    public boolean discardChunk(int x, int z)
    {
        if (this.isDummyMode()) {
            return true;
        }

        int chunkX = Coordinates.blockToChunk(x);
        int chunkZ = Coordinates.blockToChunk(z);

        return this.world.unloadChunk(chunkX, chunkZ, false);
    }

    @Override
    public int getMinY()
    {
        return this.world.getMinHeight();
    }

    @Override
    public int getMaxY()
    {
        return this.world.getMaxHeight();
    }

    @Override
    public int getSeaLevel()
    {
        if (this.seaLevel == null) {
            this.seaLevel = Math.clamp(this.world.getSeaLevel(), this.getMinY(), this.getMaxY() - 1);
        }

        return this.seaLevel;
    }

    @Override
    public int getSpawnX()
    {
        return this.world.getSpawnLocation().getBlockX();
    }

    @Override
    public int getSpawnY()
    {
        return this.world.getSpawnLocation().getBlockY();
    }

    @Override
    public int getSpawnZ()
    {
        return this.world.getSpawnLocation().getBlockZ();
    }

    @Override
    public int getHighestYAt(int x, int z)
    {
        if (!this.trustHeightMap) {
            return this.getMaxY() - 1;
        }

        if (this.isDummyMode()) {
            if (this.dummyMaxY == null) {
                this.dummyMaxY = this.getDummyColumn().isEmpty() ? this.getMinY() : Collections.max(this.getDummyColumn().keySet());
            }

            return this.dummyMaxY;
        }

        return Math.clamp(this.getChunk(x, z).getHighestBlockYAt(Coordinates.blockToChunkRelative(x), Coordinates.blockToChunkRelative(z)), this.getMinY(), this.getMaxY() - 1);
    }

    @Override
    public String getBiomeAt(int x, int y, int z)
    {
        if (this.isDummyMode()) {
            return "minecraft:plains";
        }

        NamespacedKey key = this.getChunk(x, z).getBiome(Coordinates.blockToChunkRelative(x), y, Coordinates.blockToChunkRelative(z)).getKey();

        // If the server just reports "custom" and we have access to getBiomeKey, try to get the correct biome name.
        if (key.toString().equals("minecraft:custom") && this.getBiomeKey != null) {
            try {
                key = (NamespacedKey) this.getBiomeKey.invoke(this.unsafeValues, this.world, x, y, z);
            } catch (IllegalAccessException | InvocationTargetException exception) {
                throw new RuntimeException(exception);
            }
        }

        return key.toString();
    }

    @Override
    public String getBiomeAt(int x, int z)
    {
        return this.getBiomeAt(x, this.getSeaLevel(), z);
    }

    @Override
    public String getMaterialAt(int x, int y, int z)
    {
        if (this.isDummyMode()) {
            return Utils.namespaceKey(this.getDummyMaterialAt(y));
        }

        return this.getMaterialRecordAt(x, y, z).toString();
    }

    public MaterialRecord getMaterialRecordAt(int x, int y, int z)
    {
        if (this.isDummyMode()) {
            return new MaterialRecord(Utils.namespaceKey(this.getDummyMaterialAt(y)), false);
        }

        return this.materialCache.computeIfAbsent(
            this.getChunk(x, z).getBlockType(Coordinates.blockToChunkRelative(x), y, Coordinates.blockToChunkRelative(z)),
            (mat) -> {
                String key = Utils.unNamespaceKey(mat.getKey().toString());
                String mapped = this.config.getString(DhsConfig.MATERIAL_MAP + '.' + key, key);

                return new MaterialRecord(
                    Utils.namespaceKey(mapped == null ? key : mapped),
                    !key.equals(mapped)
                );
            }
        );
    }

    @Override
    public int getIdenticalRunLength(int x, int y, int z, int minY)
    {
        if (this.isDummyMode()) return 1;
        return cc.taixue.lod.server.SnapshotStates.runLength(this.getChunk(x, z), Coordinates.blockToChunkRelative(x), y, Coordinates.blockToChunkRelative(z), minY);
    }

    @Override
    public String getBlockStateAsStringAt(int x, int y, int z)
    {
        MaterialRecord materialRecord = this.getMaterialRecordAt(x, y, z);

        if (this.isDummyMode() || materialRecord.isMapped()) {
            return materialRecord.toString();
        }

        String identity = cc.taixue.lod.server.SnapshotStates.identity(this.getChunk(x, z), Coordinates.blockToChunkRelative(x), y, Coordinates.blockToChunkRelative(z));
        if (identity != null) return identity;

        Map<String, String> properties = this.getBlockPropertiesAt(x, y, z);

        if (properties.isEmpty()) {
            return materialRecord.toString();
        }

        StringBuilder builder = new StringBuilder();

        builder.append(materialRecord.toString())
            .append('[');

        for (Map.Entry<String, String> property : properties.entrySet()) {
            builder.append(property.getKey())
                .append('=')
                .append(property.getValue())
                .append(',');
        }

        builder.setLength(builder.length() - 1);
        builder.append(']');

        return builder.toString();
    }

    @Override
    public Map<String, String> getBlockPropertiesAt(int x, int y, int z)
    {
        if (this.isDummyMode()) {
            return Collections.emptyMap();
        }

        Map<String, String> paletteProperties = cc.taixue.lod.server.SnapshotStates.properties(this.getChunk(x, z), Coordinates.blockToChunkRelative(x), y, Coordinates.blockToChunkRelative(z));
        if (paletteProperties != null) return paletteProperties;

        String dataString = this.getChunk(x, z).getBlockData(Coordinates.blockToChunkRelative(x), y, Coordinates.blockToChunkRelative(z)).getAsString();

        Map<String, String> cached = this.propertyCache.get(dataString);

        if (cached != null) {
            return cached;
        }

        int kvStart = dataString.indexOf('[');

        if (kvStart == -1) {
            return Collections.emptyMap();
        }

        String[] kvStrings = dataString.substring(kvStart + 1, dataString.length() - 1).split(",");

        Arrays.sort(kvStrings);

        Map<String, String> properties = new LinkedHashMap<>(kvStrings.length);

        for (String kvString : kvStrings) {
            String[] kv = kvString.split("=", 2);

            properties.put(kv[0], kv[1]);
        }

        properties = Collections.unmodifiableMap(properties);

        this.propertyCache.put(dataString, properties);

        return properties;
    }

    @Override
    public byte getBlockLightAt(int x, int y, int z)
    {
        if (this.isDummyMode()) {
            return 0;
        }

        return (byte) this.getChunk(x, z).getBlockEmittedLight(Coordinates.blockToChunkRelative(x), Math.clamp(y + this.lightOffset, this.getMinY(), this.getMaxY() - 1), Coordinates.blockToChunkRelative(z));
    }

    @Override
    public byte getSkyLightAt(int x, int y, int z)
    {
        if (this.isDummyMode()) {
            return (byte) (this.getDummyMaterialAt(y + 1).equals("minecraft:air") ? 15 : 0);
        }

        return (byte) this.getChunk(x, z).getBlockSkyLight(Coordinates.blockToChunkRelative(x), Math.clamp(y + this.lightOffset, this.getMinY(), this.getMaxY() - 1), Coordinates.blockToChunkRelative(z));
    }

    @Override
    public Configuration getConfig()
    {
        return this.worldConfig;
    }

    @Override
    public boolean isBeacon(int x, int y, int z)
    {
        if (this.isDummyMode()) {
            return false;
        }

        if (!this.getChunk(x, z).getBlockType(Coordinates.blockToChunkRelative(x), y, Coordinates.blockToChunkRelative(z)).equals(Material.BEACON)) {
            return false;
        }

        return this.hasTierOneBeaconBase(x, y, z) && this.hasClearBeaconBeamPath(x, y, z);
    }

    public boolean hasTierOneBeaconBase(int x, int y, int z)
    {
        int baseY = y - 1;

        if (baseY < this.getMinY()) {
            return false;
        }

        for (int checkX = x - 1; checkX <= x + 1; checkX++) {
            for (int checkZ = z - 1; checkZ <= z + 1; checkZ++) {
                if (this.isInsideSection(checkX, checkZ) && !this.isBeaconBaseBlock(this.getMaterialAt(checkX, baseY, checkZ))) {
                    return false;
                }
            }
        }

        return true;
    }

    private boolean isBeaconBaseBlock(String material)
    {
        switch (material) {
            case "minecraft:iron_block":
            case "minecraft:gold_block":
            case "minecraft:diamond_block":
            case "minecraft:emerald_block":
            case "minecraft:netherite_block":
                return true;
            default:
                return false;
        }
    }

    public boolean hasClearBeaconBeamPath(int x, int y, int z)
    {
        for (int checkY = y + 1; checkY < this.getMaxY(); checkY++) {
            String material = this.getMaterialAt(x, checkY, z);

            switch (material) {
                case "minecraft:air":
                case "minecraft:cave_air":
                case "minecraft:void_air":
                case "minecraft:glass":
                    continue;
                default:
                    return material.endsWith("_stained_glass");
            }
        }

        return true;
    }

    @Override
    public int getBeaconColor(int x, int y, int z)
    {
        int skipped = 0;
        int n = 0;
        int red = 0;
        int green = 0;
        int blue = 0;

        int maxY = this.getMaxY();

        while (y + 1 < maxY) {
            y++;

            String material = Utils.unNamespaceKey(this.getMaterialAt(x, y, z)).toUpperCase();

            if (!material.endsWith("_STAINED_GLASS") && !material.endsWith("_STAINED_GLASS_PANE")) {
                skipped++;

                if (skipped >= 5) {
                    break;
                }

                continue;
            }

            n++;

            Color color = null;

            for (DyeColor testColor : DyeColor.values()) {
                if (material.startsWith(testColor.name())) {
                    color = testColor.getColor();
                    break;
                }
            }

            if (color == null) {
                this.getLogger().warning("Encountered unknown color: " + material);
                continue;
            }

            red += color.getRed();
            green += color.getGreen();
            blue += color.getBlue();

            if (n > 1) {
                red /= 2;
                green /= 2;
                blue /= 2;
            }
        }

        if (n == 0) {
            return Color.WHITE.asRGB();
        }

        return (new java.awt.Color(red, green, blue)).getRGB();
    }
}
