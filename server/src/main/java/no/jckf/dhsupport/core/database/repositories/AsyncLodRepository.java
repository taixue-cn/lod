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

package no.jckf.dhsupport.core.database.repositories;

import no.jckf.dhsupport.core.database.Database;
import no.jckf.dhsupport.core.database.models.LodModel;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

public class AsyncLodRepository extends LodRepository
{
    protected java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor(r -> {var t=new Thread(r,"ArchLOD-SQLite");t.setDaemon(true);return t;});

    public void close() { this.executor.shutdownNow(); }

    public AsyncLodRepository(Database database)
    {
        super(database);
    }

    protected <T> CompletableFuture<T> queueTask(Supplier<T> task)
    {
        CompletableFuture<T> future = new CompletableFuture<>();

        this.executor.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Exception exception) {
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    public CompletableFuture<java.util.List<cc.taixue.lod.core.CacheRetention.Entry>> cacheEntriesAsync(){return cacheEntriesAsync(()->{});}
    public CompletableFuture<java.util.List<cc.taixue.lod.core.CacheRetention.Entry>> cacheEntriesAsync(Runnable checkpoint){return queueTask(()->{
        var entries=new java.util.ArrayList<cc.taixue.lod.core.CacheRetention.Entry>();
        try(var statement=database.getConnection().createStatement();var rows=statement.executeQuery("SELECT worldId,x,z,length(data)+coalesce(length(beacons),0) AS bytes,timestamp FROM lods")){
            while(rows.next()){checkpoint.run();UUID world=UUID.fromString(rows.getString(1));entries.add(new cc.taixue.lod.core.CacheRetention.Entry("dh:"+world+":"+rows.getInt(2)+":"+rows.getInt(3),world,rows.getLong(4),rows.getLong(5)*1000));}
        }catch(java.sql.SQLException e){throw new java.util.concurrent.CompletionException(e);}return entries;
    });}
    public CompletableFuture<Integer> evictAsync(java.util.List<cc.taixue.lod.core.CacheRetention.Entry> entries){return queueTask(()->{
        int count=0;for(var entry:entries){String[] parts=entry.id().split(":");if(parts[0].equals("dh")&&deleteLod(entry.world(),Integer.parseInt(parts[2]),Integer.parseInt(parts[3])))count++;}
        if(count>0)try(var statement=database.getConnection().createStatement()){
            int mode;try(var row=statement.executeQuery("PRAGMA auto_vacuum")){row.next();mode=row.getInt(1);}
            if(mode==0){database.clearQueryCache();statement.execute("PRAGMA auto_vacuum=INCREMENTAL");statement.execute("VACUUM");}
            else statement.execute("PRAGMA incremental_vacuum");
        }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}return count;
    });}

    public CompletableFuture<LodModel> saveLodAsync(UUID worldId, int sectionX, int sectionZ, byte[] data, byte[] beacons, int version)
    {
        return this.queueTask(() -> this.saveLod(worldId, sectionX, sectionZ, data, beacons, version));
    }

    public CompletableFuture<LodModel> loadLodAsync(UUID worldId, int sectionX, int sectionZ)
    {
        return this.queueTask(() -> this.loadLod(worldId, sectionX, sectionZ));
    }

    public CompletableFuture<Boolean> lodExistsAsync(UUID worldId, int sectionX, int sectionZ)
    {
        return this.queueTask(() -> this.lodExists(worldId, sectionX, sectionZ));
    }

    public CompletableFuture<Boolean> deleteLodAsync(UUID worldId, int sectionX, int sectionZ)
    {
        return this.queueTask(() -> this.deleteLod(worldId, sectionX, sectionZ));
    }

    public CompletableFuture<Integer> trimLodsAsync(UUID worldId, int lowSectionX, int lowSectionZ, int highSectionX, int highSectionZ)
    {
        return this.queueTask(() -> this.trimLods(worldId, lowSectionX,lowSectionZ, highSectionX, highSectionZ));
    }
}
