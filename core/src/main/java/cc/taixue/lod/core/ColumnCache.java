package cc.taixue.lod.core;

import java.io.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** World-scoped, atomic cache. A corrupt or expired entry is rebuilt from the world. */
public final class ColumnCache {
    public record Key(UUID world,int x,int z) {}
    public record Entry(long timestamp,byte[] body) {}
    private final Path root;
    private final long maxAgeMillis,maxBytes;
    public ColumnCache(Path root,Duration maxAge,long maxBytes) throws IOException {
        this.root=root;this.maxAgeMillis=maxAge.toMillis();this.maxBytes=maxBytes;Files.createDirectories(root);
    }
    private Path path(Key k){return root.resolve(k.world.toString()).resolve(k.x+"_"+k.z+".gz");}
    public synchronized Entry get(Key k) {
        Path p=path(k);
        try {
            if(!Files.isRegularFile(p) || (maxAgeMillis>=0 && System.currentTimeMillis()-Files.getLastModifiedTime(p).toMillis()>maxAgeMillis))return null;
            try(var in=new DataInputStream(new GZIPInputStream(Files.newInputStream(p)))) {
                if(in.readInt()!=0x41524c31)throw new IOException("cache version");
                long ts=in.readLong();int n=in.readInt();if(n<0||n>Wire.MAX_FRAME)throw new IOException("cache length");
                byte[] body=in.readNBytes(n);if(body.length!=n || in.read()!=-1)throw new IOException("cache truncated");
                return new Entry(ts,body);
            }
        }catch(IOException e){try{Files.deleteIfExists(p);}catch(IOException ignored){}return null;}
    }
    public synchronized void put(Key k,Entry value) throws IOException {
        if(value.body.length>Wire.MAX_FRAME)throw new IOException("column too large");
        Path target=path(k);Files.createDirectories(target.getParent());Path tmp=Files.createTempFile(target.getParent(),"column-",".tmp");
        try {
            try(var out=new DataOutputStream(new GZIPOutputStream(Files.newOutputStream(tmp)))) {
                out.writeInt(0x41524c31);out.writeLong(value.timestamp);out.writeInt(value.body.length);out.write(value.body);
            }
            try{Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(tmp,target,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(tmp);}
    }
    public synchronized void invalidate(Key k) throws IOException {Files.deleteIfExists(path(k));}
    public synchronized List<CacheRetention.Entry> entries() throws IOException {
        var out=new ArrayList<CacheRetention.Entry>();
        try(var paths=Files.walk(root)){for(Path p:paths.filter(p->p.toString().endsWith(".gz")).toList()){
            try{UUID world=UUID.fromString(p.getParent().getFileName().toString());out.add(new CacheRetention.Entry("voxy:"+world+":"+p.getFileName(),world,Files.size(p),Files.getLastModifiedTime(p).toMillis()));}catch(IllegalArgumentException ignored){}
        }}return out;
    }
    public synchronized void evict(CacheRetention.Entry entry) throws IOException {
        String[] parts=entry.id().split(":",3);if(parts.length!=3||!parts[0].equals("voxy")||!parts[2].matches("-?\\d+_-?\\d+\\.gz"))throw new IllegalArgumentException("cache entry");
        Files.deleteIfExists(root.resolve(entry.world().toString()).resolve(parts[2]));
    }
    public synchronized void trim() throws IOException {
        if(maxBytes<0)return;
        List<Path> paths;
        try(var walk=Files.walk(root)){paths=walk.filter(p->p.toString().endsWith(".gz")).sorted(Comparator.comparingLong(p->{try{return Files.getLastModifiedTime(p).toMillis();}catch(IOException e){return 0;}})).toList();}
        long total=0;for(Path p:paths)total+=Files.size(p);
        for(Path p:paths){if(total<=maxBytes)break;long n=Files.size(p);Files.deleteIfExists(p);total-=n;}
    }
}
