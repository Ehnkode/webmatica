/* Webmatica map UI: small animated GIF encoder for the timelapse export.
 *
 * One shared palette for all frames (median cut over samples of every frame,
 * the frames of a timelapse look alike), no dithering, LZW as in the GIF89a spec.
 */
'use strict';

function gifPalette(frames, size = 256) {
  // Samples from all frames, at most ~60k pixels
  const total = frames.reduce((a, f) => a + f.width * f.height, 0);
  const step = Math.max(1, Math.floor(total / 60000));
  const px = [];
  for (const f of frames) {
    const d = f.data;
    for (let i = 0; i < d.length; i += 4 * step) px.push((d[i] << 16) | (d[i + 1] << 8) | d[i + 2]);
  }
  const ch = (c, k) => (c >> (16 - 8 * k)) & 255;
  const range = (b) => {
    let best = 0, axis = 0;
    for (let k = 0; k < 3; k++) {
      let lo = 255, hi = 0;
      for (const c of b) { const v = ch(c, k); if (v < lo) lo = v; if (v > hi) hi = v; }
      if (hi - lo > best) { best = hi - lo; axis = k; }
    }
    return [best, axis];
  };
  const boxes = [px];
  while (boxes.length < size) {
    let bi = -1, score = 0, ax = 0;
    boxes.forEach((b, i) => {
      if (b.length < 2) return;
      const [r, a] = range(b);
      if (r * b.length > score) { score = r * b.length; bi = i; ax = a; }
    });
    if (bi < 0) break;
    const b = boxes[bi].sort((p, q) => ch(p, ax) - ch(q, ax));
    const mid = b.length >> 1;
    boxes.splice(bi, 1, b.slice(0, mid), b.slice(mid));
  }
  const pal = boxes.map((b) => {
    let r = 0, g = 0, bl = 0;
    for (const c of b) { r += ch(c, 0); g += ch(c, 1); bl += ch(c, 2); }
    const n = Math.max(1, b.length);
    return [Math.round(r / n), Math.round(g / n), Math.round(bl / n)];
  });
  while (pal.length < size) pal.push([0, 0, 0]);
  return pal;
}

function gifIndices(f, pal, cache) {
  // cache: 15-bit color -> palette index + 1 (0 = not looked up yet)
  const d = f.data, out = new Uint8Array(f.width * f.height);
  for (let i = 0, p = 0; p < out.length; i += 4, p++) {
    const key = ((d[i] >> 3) << 10) | ((d[i + 1] >> 3) << 5) | (d[i + 2] >> 3);
    if (!cache[key]) {
      let best = 0, bd = Infinity;
      const r = d[i], g = d[i + 1], b = d[i + 2];
      for (let k = 0; k < pal.length; k++) {
        const c = pal[k], dd = (c[0] - r) ** 2 + (c[1] - g) ** 2 + (c[2] - b) ** 2;
        if (dd < bd) { bd = dd; best = k; }
      }
      cache[key] = best + 1;
    }
    out[p] = cache[key] - 1;
  }
  return out;
}

function gifLzw(idx, out) {
  const minCode = 8, clear = 1 << minCode, eoi = clear + 1;
  let size = minCode + 1, next = eoi + 1, table = new Map();
  const bytes = [];
  let cur = 0, bits = 0;
  const emit = (code) => {
    cur |= code << bits; bits += size;
    while (bits >= 8) { bytes.push(cur & 255); cur >>>= 8; bits -= 8; }
  };
  emit(clear);
  let prefix = idx[0];
  for (let i = 1; i < idx.length; i++) {
    const k = idx[i], key = (prefix << 8) | k;
    const hit = table.get(key);
    if (hit !== undefined) { prefix = hit; continue; }
    emit(prefix);
    if (next === 4096) {
      emit(clear);
      next = eoi + 1; size = minCode + 1; table = new Map();
    } else {
      if (next >= (1 << size)) size++;
      table.set(key, next++);
    }
    prefix = k;
  }
  emit(prefix);
  emit(eoi);
  if (bits > 0) bytes.push(cur & 255);
  out.push(minCode);
  for (let i = 0; i < bytes.length; i += 255) {
    const n = Math.min(255, bytes.length - i);
    out.push(n);
    for (let j = 0; j < n; j++) out.push(bytes[i + j]);
  }
  out.push(0);
}

/* frames: ImageData[] of the same size; delayMs per frame (number or array). -> Blob (image/gif) */
function encodeGif(frames, delayMs) {
  const w = frames[0].width, h = frames[0].height;
  const pal = gifPalette(frames);
  const cache = new Uint16Array(32768);
  const out = [];
  const str = (s) => { for (const c of s) out.push(c.charCodeAt(0)); };
  const u16 = (v) => out.push(v & 255, (v >> 8) & 255);
  str('GIF89a'); u16(w); u16(h); out.push(0xf7, 0, 0);
  for (const c of pal) out.push(c[0], c[1], c[2]);
  out.push(0x21, 0xff, 11); str('NETSCAPE2.0'); out.push(3, 1, 0, 0, 0);   // loop forever
  frames.forEach((f, i) => {
    const delay = Math.round((Array.isArray(delayMs) ? delayMs[i] : delayMs) / 10);
    out.push(0x21, 0xf9, 4, 0x04); u16(delay); out.push(0, 0);
    out.push(0x2c); u16(0); u16(0); u16(w); u16(h); out.push(0);
    gifLzw(gifIndices(f, pal, cache), out);
  });
  out.push(0x3b);
  return new Blob([new Uint8Array(out)], { type: 'image/gif' });
}
