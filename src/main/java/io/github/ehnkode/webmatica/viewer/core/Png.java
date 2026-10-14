package io.github.ehnkode.webmatica.viewer.core;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import javax.imageio.ImageIO;

/** RGBA PNG writing (fast, own encoder) and reading (ImageIO). */
public final class Png {
    private Png() {
    }

    /** rgba: w * h * 4 bytes, row by row. */
    public static byte[] encode(int w, int h, byte[] rgba, int level) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(rgba.length / 4 + 1024);
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        ByteBuffer ihdr = ByteBuffer.allocate(13);
        ihdr.putInt(w).putInt(h).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        // Filter "Sub" on every row: cheap and compresses map tiles well.
        int stride = w * 4;
        byte[] raw = new byte[h * (stride + 1)];
        for (int y = 0; y < h; y++) {
            int o = y * (stride + 1), s = y * stride;
            raw[o] = 1;
            for (int i = 0; i < stride; i++) {
                int left = i >= 4 ? rgba[s + i - 4] : 0;
                raw[o + 1 + i] = (byte) (rgba[s + i] - left);
            }
        }
        Deflater d = new Deflater(level);
        d.setInput(raw);
        d.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream(raw.length / 4 + 64);
        byte[] buf = new byte[65536];
        while (!d.finished()) {
            int n = d.deflate(buf);
            z.write(buf, 0, n);
        }
        d.end();
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    public static byte[] encode(int w, int h, byte[] rgba) {
        return encode(w, h, rgba, 3);
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(t);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    public static byte[] encode(BufferedImage img) {
        return encode(img.getWidth(), img.getHeight(), rgba(img), 6);
    }

    /** Pixels as RGBA bytes, not premultiplied. */
    public static byte[] rgba(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        byte[] out = new byte[w * h * 4];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            out[4 * i] = (byte) (p >> 16);
            out[4 * i + 1] = (byte) (p >> 8);
            out[4 * i + 2] = (byte) p;
            out[4 * i + 3] = (byte) (p >>> 24);
        }
        return out;
    }

    public static BufferedImage image(int w, int h, byte[] rgba) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] argb = new int[w * h];
        for (int i = 0; i < argb.length; i++) {
            argb[i] = ((rgba[4 * i + 3] & 255) << 24) | ((rgba[4 * i] & 255) << 16) | ((rgba[4 * i + 1] & 255) << 8) | (rgba[4 * i + 2] & 255);
        }
        img.setRGB(0, 0, w, h, argb, 0, w);
        return img;
    }

    /** Any PNG as an ARGB image (palette, gray etc. converted), or null. */
    public static BufferedImage read(byte[] data) {
        try (InputStream in = new ByteArrayInputStream(data)) {
            BufferedImage src = ImageIO.read(in);
            if (src == null) {
                return null;
            }
            if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
                return src;
            }
            if (src.getColorModel().getColorSpace().getType() == java.awt.color.ColorSpace.TYPE_GRAY) {
                // getRGB would convert gray from linear to sRGB (too bright): take the samples as they are
                java.awt.image.Raster r = src.getRaster();
                int w = src.getWidth(), h = src.getHeight(), bands = r.getNumBands();
                int bits = src.getColorModel().getComponentSize(0);
                int max = (1 << bits) - 1;
                int[] argb = new int[w * h];
                int[] px = new int[bands];
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        r.getPixel(x, y, px);
                        int g = px[0] * 255 / max;
                        int a = bands > 1 ? px[1] * 255 / max : 255;
                        argb[y * w + x] = a << 24 | g << 16 | g << 8 | g;
                    }
                }
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                img.setRGB(0, 0, w, h, argb, 0, w);
                return img;
            }
            BufferedImage img = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
            img.setRGB(0, 0, src.getWidth(), src.getHeight(), src.getRGB(0, 0, src.getWidth(), src.getHeight(), null, 0, src.getWidth()),
                    0, src.getWidth());
            return img;
        } catch (IOException e) {
            return null;
        }
    }

    public static BufferedImage read(Path p) throws IOException {
        BufferedImage img = read(Files.readAllBytes(p));
        if (img == null) {
            throw new IOException("kein PNG: " + p);
        }
        return img;
    }

    /** 1x1 transparent. */
    public static final byte[] EMPTY = encode(1, 1, new byte[4], 6);

    /** Writes atomically (temp file + rename). */
    public static void writeAtomic(Path p, byte[] data) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
