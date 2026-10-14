package io.github.ehnkode.webmatica.viewer.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal NBT reader and writer.
 *
 * <p>Compounds become {@code Map<String, Object>}, lists {@code List<Object>}, scalars their
 * boxed Java type (Byte, Short, Integer, Long, Float, Double), strings String and the arrays
 * byte[] / int[] / long[]. The Java type keeps the NBT type, so a read record writes back
 * losslessly (block entities in the schematic export).
 */
public final class Nbt {
    public static final int END = 0, BYTE = 1, SHORT = 2, INT = 3, LONG = 4, FLOAT = 5, DOUBLE = 6,
            BYTE_ARRAY = 7, STRING = 8, LIST = 9, COMPOUND = 10, INT_ARRAY = 11, LONG_ARRAY = 12;

    private Nbt() {
    }

    /** Reads an uncompressed NBT record with a compound as root. */
    public static Map<String, Object> read(byte[] buf) {
        if (buf.length == 0 || buf[0] != COMPOUND) {
            throw new IllegalArgumentException("NBT-Wurzel ist kein Compound");
        }
        ByteBuffer b = ByteBuffer.wrap(buf);
        b.get();
        string(b);
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) value(b, COMPOUND);
        return root;
    }

    private static String string(ByteBuffer b) {
        int n = b.getShort() & 0xFFFF;
        String s = decode(b.array(), b.position(), n);
        b.position(b.position() + n);
        return s;
    }

    /** Java's "modified UTF-8" is what NBT uses; plain UTF-8 decoding is what vanilla accepts too. */
    private static String decode(byte[] a, int off, int n) {
        for (int i = off; i < off + n; i++) {
            if (a[i] < 0) {
                return new String(a, off, n, StandardCharsets.UTF_8);
            }
        }
        return new String(a, off, n, StandardCharsets.ISO_8859_1);
    }

    private static Object value(ByteBuffer b, int tag) {
        switch (tag) {
            case BYTE:
                return b.get();
            case SHORT:
                return b.getShort();
            case INT:
                return b.getInt();
            case LONG:
                return b.getLong();
            case FLOAT:
                return b.getFloat();
            case DOUBLE:
                return b.getDouble();
            case STRING:
                return string(b);
            case COMPOUND: {
                Map<String, Object> d = new LinkedHashMap<>();
                while (true) {
                    int t = b.get();
                    if (t == END) {
                        return d;
                    }
                    String name = string(b);
                    d.put(name, value(b, t));
                }
            }
            case LIST: {
                int et = b.get();
                int n = b.getInt();
                List<Object> out = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) {
                    out.add(value(b, et));
                }
                return out;
            }
            case BYTE_ARRAY: {
                byte[] a = new byte[b.getInt()];
                b.get(a);
                return a;
            }
            case INT_ARRAY: {
                int[] a = new int[b.getInt()];
                b.asIntBuffer().get(a);
                b.position(b.position() + 4 * a.length);
                return a;
            }
            case LONG_ARRAY: {
                long[] a = new long[b.getInt()];
                b.asLongBuffer().get(a);
                b.position(b.position() + 8 * a.length);
                return a;
            }
            default:
                throw new IllegalArgumentException("unbekannter NBT-Tag " + tag + " bei Offset " + b.position());
        }
    }

    // ------------------------------------------------------------------ writing

    public static byte[] write(Map<String, ?> root, String name) {
        Out out = new Out();
        out.write(COMPOUND);
        out.str(name);
        writeValue(out, COMPOUND, root);
        return out.toByteArray();
    }

    public static int tagOf(Object v) {
        if (v instanceof Byte || v instanceof Boolean) {
            return BYTE;
        }
        if (v instanceof Short) {
            return SHORT;
        }
        if (v instanceof Integer) {
            return INT;
        }
        if (v instanceof Long) {
            return LONG;
        }
        if (v instanceof Float) {
            return FLOAT;
        }
        if (v instanceof Double) {
            return DOUBLE;
        }
        if (v instanceof String) {
            return STRING;
        }
        if (v instanceof Map) {
            return COMPOUND;
        }
        if (v instanceof List) {
            return LIST;
        }
        if (v instanceof byte[]) {
            return BYTE_ARRAY;
        }
        if (v instanceof int[]) {
            return INT_ARRAY;
        }
        if (v instanceof long[]) {
            return LONG_ARRAY;
        }
        throw new IllegalArgumentException("kein NBT-Typ fuer " + (v == null ? "null" : v.getClass()));
    }

    private static void writeValue(Out out, int tag, Object v) {
        switch (tag) {
            case BYTE -> out.write(v instanceof Boolean bo ? (bo ? 1 : 0) : ((Number) v).byteValue());
            case SHORT -> out.i16(((Number) v).shortValue());
            case INT -> out.i32(((Number) v).intValue());
            case LONG -> out.i64(((Number) v).longValue());
            case FLOAT -> out.i32(Float.floatToIntBits(((Number) v).floatValue()));
            case DOUBLE -> out.i64(Double.doubleToLongBits(((Number) v).doubleValue()));
            case STRING -> out.str((String) v);
            case COMPOUND -> {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    int t = tagOf(e.getValue());
                    out.write(t);
                    out.str(String.valueOf(e.getKey()));
                    writeValue(out, t, e.getValue());
                }
                out.write(END);
            }
            case LIST -> {
                List<?> l = (List<?>) v;
                int et = l.isEmpty() ? END : tagOf(l.get(0));
                if (et == INT) {
                    for (Object x : l) {
                        if (x instanceof Long) {
                            et = LONG;
                            break;
                        }
                    }
                }
                out.write(et);
                out.i32(l.size());
                for (Object x : l) {
                    writeValue(out, et, x);
                }
            }
            case BYTE_ARRAY -> {
                byte[] a = (byte[]) v;
                out.i32(a.length);
                out.write(a, 0, a.length);
            }
            case INT_ARRAY -> {
                int[] a = (int[]) v;
                out.i32(a.length);
                for (int x : a) {
                    out.i32(x);
                }
            }
            case LONG_ARRAY -> {
                long[] a = (long[]) v;
                out.i32(a.length);
                for (long x : a) {
                    out.i64(x);
                }
            }
            default -> throw new IllegalArgumentException("Tag " + tag);
        }
    }

    private static final class Out extends ByteArrayOutputStream {
        Out() {
            super(4096);
        }

        void i16(int v) {
            write(v >> 8);
            write(v);
        }

        void i32(int v) {
            i16(v >> 16);
            i16(v);
        }

        void i64(long v) {
            i32((int) (v >> 32));
            i32((int) v);
        }

        void str(String s) {
            byte[] e = s.getBytes(StandardCharsets.UTF_8);
            i16(e.length);
            write(e, 0, e.length);
        }
    }

    // ------------------------------------------------------------------ helpers for reading

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> list(Object o) {
        return o instanceof List ? (List<Object>) o : List.of();
    }

    /** Integer value of a number (any NBT scalar), or {@code def}. */
    public static long num(Object o, long def) {
        return o instanceof Number n ? n.longValue() : def;
    }

    public static Integer intOrNull(Object o) {
        if (o instanceof Number n) {
            return n.intValue();
        }
        if (o instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
}
