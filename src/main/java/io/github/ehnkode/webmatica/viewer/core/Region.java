package io.github.ehnkode.webmatica.viewer.core;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * One region file. Not kept in memory: the header (positions and timestamps per chunk)
 * is reread when the file's mtime or size changes, so new chunks show up without a restart.
 */
public final class Region {
    public final Path path;
    public final int rx, rz;
    private volatile Header header;

    public Region(Path path, int rx, int rz) {
        this.path = path;
        this.rx = rx;
        this.rz = rz;
    }

    public static final class Header {
        public final long mtimeNs;
        public final long size;
        /** sector offset per chunk, 0 = chunk missing */
        public final int[] offsets = new int[1024];
        public final int[] sectors = new int[1024];
        /** Unix seconds of the last change */
        public final long[] timestamps = new long[1024];

        Header(long mtimeNs, long size) {
            this.mtimeNs = mtimeNs;
            this.size = size;
        }

        public boolean any() {
            for (int o : offsets) {
                if (o != 0) {
                    return true;
                }
            }
            return false;
        }
    }

    public Header header() {
        BasicFileAttributes st;
        try {
            st = Files.readAttributes(path, BasicFileAttributes.class);
        } catch (IOException e) {
            return null;
        }
        long mt = st.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS);
        Header h = header;
        if (h != null && h.mtimeNs == mt && h.size == st.size()) {
            return h;
        }
        synchronized (this) {
            byte[] raw = new byte[8192];
            try (InputStream in = Files.newInputStream(path)) {
                if (in.readNBytes(raw, 0, 8192) < 8192) {
                    return null;
                }
            } catch (IOException e) {
                return null;
            }
            h = new Header(mt, st.size());
            ByteBuffer b = ByteBuffer.wrap(raw);
            for (int i = 0; i < 1024; i++) {
                int loc = b.getInt(i * 4);
                h.offsets[i] = loc >>> 8;
                h.sectors[i] = loc & 0xFF;
                h.timestamps[i] = b.getInt(4096 + i * 4) & 0xFFFFFFFFL;
            }
            header = h;
            return h;
        }
    }

    public int[] chunkIndices() {
        Header h = header();
        if (h == null) {
            return new int[0];
        }
        int n = 0;
        for (int o : h.offsets) {
            if (o != 0) {
                n++;
            }
        }
        int[] out = new int[n];
        int k = 0;
        for (int i = 0; i < 1024; i++) {
            if (h.offsets[i] != 0) {
                out[k++] = i;
            }
        }
        return out;
    }

    public Map<String, Object> readNbt(int idx) {
        Header h = header();
        if (h == null || h.offsets[idx] == 0) {
            return null;
        }
        byte[] payload;
        int comp;
        try (RandomAccessFile f = new RandomAccessFile(path.toFile(), "r")) {
            f.seek((long) h.offsets[idx] * 4096);
            byte[] head = new byte[5];
            if (f.read(head) < 5) {
                return null;
            }
            int length = ByteBuffer.wrap(head).getInt(0);
            comp = head[4] & 0xFF;
            if (length > 1 && length < 256 * 1024 * 1024) {
                payload = new byte[length - 1];
                f.readFully(payload);
            } else {
                payload = new byte[0];
            }
        } catch (IOException e) {
            return null;
        }
        if ((comp & 0x80) != 0) {
            // An oversized chunk is stored externally in c.<x>.<z>.mcc next to the region.
            int cx = rx * 32 + (idx & 31);
            int cz = rz * 32 + (idx >> 5);
            try {
                payload = Files.readAllBytes(path.resolveSibling("c." + cx + "." + cz + ".mcc"));
            } catch (NoSuchFileException e) {
                return null;
            } catch (IOException e) {
                return null;
            }
            comp &= 0x7F;
        }
        byte[] raw;
        try {
            if (comp == 1) {
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(payload))) {
                    raw = in.readAllBytes();
                }
            } else if (comp == 2) {
                try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(payload))) {
                    raw = in.readAllBytes();
                }
            } else if (comp == 3) {
                raw = payload;
            } else {
                return null;   // the mod doesn't write LZ4 etc.
            }
        } catch (IOException e) {
            throw new IllegalStateException("Chunk nicht entpackbar: " + e.getMessage(), e);
        }
        return Nbt.read(raw);
    }
}
