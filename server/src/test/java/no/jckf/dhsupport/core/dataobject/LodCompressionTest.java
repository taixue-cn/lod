package no.jckf.dhsupport.core.dataobject;
import org.junit.jupiter.api.Test;
import org.tukaani.xz.XZInputStream;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class LodCompressionTest {
 @Test void writesACompleteXzStreamIncludingTheEndMarker()throws Exception{
  byte[] input=new byte[100000];new Random(42).nextBytes(input);
  var lod=new Lod(null,null,List.of(),List.of(),List.of());
  try(var stream=new XZInputStream(new ByteArrayInputStream(lod.compress(input)))){assertArrayEquals(input,stream.readAllBytes());}
 }
}
