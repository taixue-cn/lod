package cc.taixue.lod.core;
import java.nio.*;import java.nio.file.*;
import org.junit.jupiter.api.Test;import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
class SavedChunksTest {
 @TempDir Path dir;
 @Test void scansOnlySavedSlotsAndHandlesNegativeRegions()throws Exception{
  byte[] data=new byte[16384];var b=ByteBuffer.wrap(data);b.putInt(0,513);b.putInt(4,769);b.putInt(1023*4,513);put(b,8192,"minecraft:full");put(b,12288,"minecraft:noise");Files.write(dir.resolve("r.-1.2.mca"),data);
  var scan=new SavedChunks(dir);assertEquals(new SavedChunks.Chunk(-32,64,-1,2),scan.next());assertEquals(new SavedChunks.Chunk(-1,95,-1,2),scan.next());assertNull(scan.next());assertEquals(1,Files.list(dir).count());
 }
 @Test void oversizedExternalChunksAreSkippedWithoutReadingThem()throws Exception{
  byte[] data=new byte[12288];var b=ByteBuffer.wrap(data);b.putInt(0,513);b.position(8192);b.putInt(1);b.put((byte)130);Files.write(dir.resolve("r.0.0.mca"),data);
  try(var external=new java.io.RandomAccessFile(dir.resolve("c.0.0.mcc").toFile(),"rw")){external.setLength(33554433);}
  assertNull(new SavedChunks(dir).next());
 }
 private static void put(ByteBuffer bytes,int offset,String status)throws Exception{
  var raw=new java.io.ByteArrayOutputStream();try(var out=new java.io.DataOutputStream(raw)){out.writeByte(10);out.writeUTF("");out.writeByte(8);out.writeUTF("Status");out.writeUTF(status);out.writeByte(0);}
  var zipped=new java.io.ByteArrayOutputStream();try(var out=new java.util.zip.DeflaterOutputStream(zipped)){out.write(raw.toByteArray());}byte[] data=zipped.toByteArray();bytes.position(offset);bytes.putInt(data.length+1);bytes.put((byte)2);bytes.put(data);
 }
}
