package bufs;

public class ByteBuffer {
    public static ByteBuffer allocateDirect(int cap) { return null; }
    public static ByteBuffer allocate(int cap) { return null; }
    public int capacity() { return 0; }
    public int position() { return 0; }
    public int limit() { return 0; }
    public int remaining() { return 0; }
    public boolean hasRemaining() { return false; }
    public ByteBuffer flip() { return this; }
    public ByteBuffer rewind() { return this; }
    public ByteBuffer clear() { return this; }
    public ByteBuffer position(int p) { return this; }
    public ByteBuffer limit(int l) { return this; }
    public byte get(int i) { return 0; }
    public byte get() { return 0; }
    public ByteBuffer put(int i, byte v) { return this; }
    public ByteBuffer put(byte v) { return this; }
    public int getInt(int i) { return 0; }
    public int getInt() { return 0; }
    public ByteBuffer putInt(int i, int v) { return this; }
    public ByteBuffer putInt(int v) { return this; }
    public long getLong(int i) { return 0; }
    public ByteBuffer putLong(int i, long v) { return this; }
    public float getFloat(int i) { return 0; }
    public ByteBuffer putFloat(int i, float v) { return this; }
}
