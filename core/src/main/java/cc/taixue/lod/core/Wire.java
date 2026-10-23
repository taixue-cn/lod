package cc.taixue.lod.core;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Minecraft plugin-message primitives, with bounded decoding before allocation. */
public final class Wire {
    private Wire() {}
    public static final int MAX_FRAME = 1_048_576;
    public static long pack(int x, int z) { return ((long)x << 32) | (z & 0xffffffffL); }
    public static int x(long p) { return (int)(p >> 32); }
    public static int z(long p) { return (int)p; }
    public static final class Reader {
        private final DataInputStream in;
        public Reader(byte[] bytes) {
            if (bytes.length > MAX_FRAME) throw new IllegalArgumentException("frame too large");
            in = new DataInputStream(new ByteArrayInputStream(bytes));
        }
        public int remaining() { try { return in.available(); } catch(IOException e) { throw bad(e); } }
        public int varInt() {
            int value=0;
            for(int shift=0; shift<35; shift+=7) {
                int b=u8();
                if(shift==28 && (b & 0xf0)!=0) throw bad(null);
                value |= (b & 127)<<shift;
                if((b&128)==0) return value;
            }
            throw bad(null);
        }
        public int count(int maximum) { int n=varInt(); if(n<0 || n>maximum) throw bad(null); return n; }
        public int u8() { try { return in.readUnsignedByte(); } catch(IOException e) { throw bad(e); } }
        public boolean bool() { int b=u8(); if(b>1)throw bad(null);return b==1; }
        public int i32() { try { return in.readInt(); } catch(IOException e) { throw bad(e); } }
        public long i64() { try { return in.readLong(); } catch(IOException e) { throw bad(e); } }
        public byte[] raw(int n) { if(n<0 || n>remaining())throw bad(null); try{return in.readNBytes(n);}catch(IOException e){throw bad(e);} }
        public byte[] bytes(int max) { return raw(count(max)); }
        public String utf(int maxBytes) { return new String(bytes(maxBytes),StandardCharsets.UTF_8); }
        public void end() { if(remaining()!=0)throw bad(null); }
        private IllegalArgumentException bad(Exception cause) { return new IllegalArgumentException("Malformed LOD message",cause); }
    }
    public static final class Writer {
        private final ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        private final DataOutputStream out=new DataOutputStream(buffer);
        private void put(IORunnable r) { try {r.run();}catch(IOException e){throw new UncheckedIOException(e);} }
        public Writer varInt(int n) { do { int b=n&127;n>>>=7;u8(n==0?b:b|128); }while(n!=0);return this; }
        public Writer u8(int b) { put(()->out.writeByte(b));return this; }
        public Writer bool(boolean v) { return u8(v?1:0); }
        public Writer i32(int n) { put(()->out.writeInt(n));return this; }
        public Writer i64(long n) { put(()->out.writeLong(n));return this; }
        public Writer raw(byte[] b) { put(()->out.write(b));return this; }
        public Writer bytes(byte[] b) { return varInt(b.length).raw(b); }
        public Writer utf(String s) { return bytes(s.getBytes(StandardCharsets.UTF_8)); }
        public byte[] result() { return buffer.toByteArray(); }
        @FunctionalInterface private interface IORunnable { void run() throws IOException; }
    }
}
