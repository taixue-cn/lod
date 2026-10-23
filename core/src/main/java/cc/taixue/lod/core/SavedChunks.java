package cc.taixue.lod.core;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Streams occupied Anvil slots without reading or creating a Minecraft chunk. */
public final class SavedChunks {
 public record Chunk(int x,int z,int regionX,int regionZ){}
 private static final Pattern NAME=Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
 private final Iterator<Path> files;
 private int[] locations=new int[0];private int cursor,rx,rz;private Path regionFile;
 public SavedChunks(Path region)throws IOException{
  if(!Files.isDirectory(region)){files=Collections.emptyIterator();return;}
  try(var stream=Files.list(region)){files=stream.filter(p->NAME.matcher(p.getFileName().toString()).matches()).sorted().toList().iterator();}
 }
 public Chunk next()throws IOException{
  while(true){
   while(cursor<locations.length){int index=cursor++;if(locations[index]!=0){var chunk=new Chunk(Math.addExact(Math.multiplyExact(rx,32),index&31),Math.addExact(Math.multiplyExact(rz,32),index>>5),rx,rz);if(full(regionFile,locations[index],chunk))return chunk;}}
   if(!files.hasNext())return null;Path path=files.next();regionFile=path;var match=NAME.matcher(path.getFileName().toString());match.matches();rx=Integer.parseInt(match.group(1));rz=Integer.parseInt(match.group(2));locations=new int[1024];cursor=0;
   try(var in=new DataInputStream(Files.newInputStream(path))){long sectors=Files.size(path)/4096;for(int i=0;i<1024;i++){int value=in.readInt(),offset=value>>>8,count=value&255;if(offset>=2&&count>0&&(long)offset+count<=sectors)locations[i]=value;}}
   catch(EOFException e){locations=new int[0];}
  }
 }
 // Unknown compression falls back to Paper's generated-status guard; known Anvil formats
 // are filtered here on the I/O worker so proto chunks never become load requests.
 private static boolean full(Path path,int location,Chunk chunk){
  try(var file=new RandomAccessFile(path.toFile(),"r")){
   file.seek((long)(location>>>8)*4096);int size=file.readInt();int type=file.readUnsignedByte();if(size<1||size>(location&255)*4096-4)return false;
   byte[] payload;
   if((type&128)!=0){Path external=path.getParent().resolve("c."+chunk.x()+"."+chunk.z()+".mcc");if(Files.size(external)>33554432)return false;payload=Files.readAllBytes(external);}else{payload=new byte[size-1];file.readFully(payload);}
   type&=127;if(type==4)return true;if(type<1||type>3)return false;
   InputStream raw=new ByteArrayInputStream(payload);InputStream decoded=type==1?new java.util.zip.GZIPInputStream(raw):type==2?new java.util.zip.InflaterInputStream(raw):raw;
   try(var in=new DataInputStream(new FilterInputStream(decoded){long remaining=33554432;@Override public int read()throws IOException{if(--remaining<0)throw new IOException("NBT too large");return super.read();}@Override public int read(byte[] b,int off,int len)throws IOException{if(remaining<=0)throw new IOException("NBT too large");int n=super.read(b,off,(int)Math.min(remaining,len));if(n>0)remaining-=n;return n;}@Override public long skip(long n)throws IOException{byte[] b=new byte[(int)Math.min(8192,n)];long total=0;while(total<n){int r=read(b,0,(int)Math.min(b.length,n-total));if(r<0)break;total+=r;}return total;}})){
    if(in.readUnsignedByte()!=10)return false;in.readUTF();return Boolean.TRUE.equals(compound(in,0));
   }
  }catch(IOException|RuntimeException e){return false;}
 }
 private static Boolean compound(DataInputStream in,int depth)throws IOException{
  if(depth>64)throw new IOException("NBT depth");int type;
  while((type=in.readUnsignedByte())!=0){String name=in.readUTF();
   if(type==8&&name.equals("Status")){String status=in.readUTF();return status.equals("full")||status.equals("minecraft:full");}
   if(type==10&&name.equals("Level")){Boolean result=compound(in,depth+1);if(result!=null)return result;}
   else skip(in,type,depth+1);
  }return null;
 }
 private static int length(DataInputStream in)throws IOException{int n=in.readInt();if(n<0||n>33554432)throw new IOException("NBT length");return n;}
 private static void skip(DataInputStream in,int type,int depth)throws IOException{
  if(depth>64)throw new IOException("NBT depth");switch(type){
   case 1->in.skipNBytes(1);case 2->in.skipNBytes(2);case 3,5->in.skipNBytes(4);case 4,6->in.skipNBytes(8);
   case 7->in.skipNBytes(length(in));case 8->in.readUTF();case 9->{int child=in.readUnsignedByte(),n=length(in);for(int i=0;i<n;i++)skip(in,child,depth+1);}
   case 10->{int child;while((child=in.readUnsignedByte())!=0){in.readUTF();skip(in,child,depth+1);}}
   case 11->in.skipNBytes((long)length(in)*4);case 12->in.skipNBytes((long)length(in)*8);default->throw new IOException("NBT tag");
  }
 }
}
