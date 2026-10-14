/* Webmatica – 3D view.
 *
 * Near: real chunk meshes (MSH3, see viewer/core/mesher.py) around the camera.
 * Far: one height field per region with the map tile as texture (far view),
 * so the whole recording is visible without fog.
 *
 * All objects sit in real world coordinates. Three.js computes matrices in
 * double precision; vertex data stays small (relative to the chunk or the
 * region), so nothing jitters even at coordinates in the millions.
 */
import * as THREE from '/static/vendor/three/three.module.min.js';
import { createMarkers } from './view3d/markers.js';
import { createPlayer } from './view3d/player.js';

THREE.ColorManagement.enabled = false;

const SKY = { overworld: 0x9cc3f5, the_nether: 0x2a0c0c, the_end: 0x120d1a };
const PASSES = 3;

export function create(container, ctx) {
  const renderer = new THREE.WebGLRenderer({ antialias: false, powerPreference: 'high-performance', preserveDrawingBuffer: true });
  renderer.outputColorSpace = THREE.LinearSRGBColorSpace;
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
  container.appendChild(renderer.domElement);

  const scene = new THREE.Scene();
  const camera = new THREE.PerspectiveCamera(70, 1, 0.1, 8000);
  camera.rotation.order = 'YXZ';

  // Load the atlas only once the version is known (otherwise an old atlas from the browser cache)
  const atlas = new THREE.Texture();
  atlas.magFilter = THREE.NearestFilter;
  atlas.minFilter = THREE.NearestFilter;
  atlas.generateMipmaps = false;
  atlas.flipY = false;
  atlas.colorSpace = THREE.NoColorSpace;

  // All materials the layer cut (clipping) should affect
  const clipped = new Set();
  const clip = { mode: 'off', y: 64, planes: [], query: '', timer: null, top: new THREE.Plane(new THREE.Vector3(0, -1, 0), 64), bottom: new THREE.Plane(new THREE.Vector3(0, 1, 0), -64) };
  renderer.localClippingEnabled = true;
  const track = (m) => { m.clippingPlanes = clip.planes; clipped.add(m); return m; };

  /* Light from the recording (fancy/shader graphics): block and sky light 0..1 per corner.
   * Sky light dims sun and sky (caves, interiors), block light
   * (torches, lava, glowstone) glows warm, stronger at night. */
  const lightUniforms = { uDay: { value: 1 } };
  const withBlockLight = (m) => {
    m.onBeforeCompile = (sh) => {
      sh.uniforms.uDay = lightUniforms.uDay;
      sh.vertexShader = 'attribute vec2 light;\nvarying vec2 vLight;\n' +
        sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\nvLight = light;');
      sh.fragmentShader = 'uniform float uDay;\nvarying vec2 vLight;\n' +
        sh.fragmentShader.replace('#include <opaque_fragment>', `
  // Helligkeitskurve wie Minecraft: l / (4 - 3 l)
  float bl = vLight.x / (4.0 - 3.0 * vLight.x);
  float sk = vLight.y / (4.0 - 3.0 * vLight.y);
  outgoingLight *= mix(0.07, 1.0, sk) * mix(0.55, 1.0, uDay);
  outgoingLight += diffuseColor.rgb * bl * vec3(1.0, 0.82, 0.58) * mix(1.1, 0.25, uDay);
  #include <opaque_fragment>`);
    };
    return m;
  };
  const makeMaterials = (opacity = 1, fancy = false) => {
    const Lit = fancy ? THREE.MeshLambertMaterial : THREE.MeshBasicMaterial;
    const extra = fancy ? { flatShading: true } : {};
    const Water = fancy ? THREE.MeshPhongMaterial : THREE.MeshBasicMaterial;
    const water = fancy ? { flatShading: true, shininess: 80, specular: 0x666666 } : {};
    const lit = fancy ? withBlockLight : (m) => m;
    return [
      track(lit(new Lit({ map: atlas, vertexColors: true, transparent: opacity < 1, opacity, ...extra }))),
      track(lit(new Lit({ map: atlas, vertexColors: true, alphaTest: 0.5, side: THREE.DoubleSide, transparent: opacity < 1, opacity, ...extra }))),
      track(lit(new Water({ map: atlas, vertexColors: true, transparent: true, depthWrite: false, side: THREE.DoubleSide, opacity: Math.min(opacity, 1), ...water }))),
    ];
  };
  const materials = makeMaterials();
  const fancyMaterials = makeMaterials(1, true);
  // Shadows of leaves, fences etc.: depth with alpha test
  const depthCutout = track(new THREE.MeshDepthMaterial({ map: atlas, alphaTest: 0.5, depthPacking: THREE.RGBADepthPacking }));
  const chunkMats = () => (st.fancy ? fancyMaterials : materials);
  function chunkMesh(g, pass) {
    const m = new THREE.Mesh(g, chunkMats()[pass]);
    m.userData.pass = pass;
    m.castShadow = pass < 2;
    m.receiveShadow = true;
    if (pass === 1) m.customDepthMaterial = depthCutout;
    return m;
  }

  let atlasW = 0, atlasH = 0;
  // Without the atlas nothing streams, so a failed load is retried and reported instead of leaving 3D empty.
  function loadAtlas(attempt = 0) {
    const retry = (why) => {
      if (attempt === 2) ctx.toast && ctx.toast('3D: Blocktexturen nicht geladen (' + why + '), versuche es weiter …');
      setTimeout(() => loadAtlas(attempt + 1), Math.min(10000, 1000 * (attempt + 1)));
    };
    fetch('/api/blocks-info').then((r) => r.json()).then((j) => {
      if (!j.available) { ctx.toast && ctx.toast('3D braucht die Blockmodelle: python -m viewer.tools.build_assets --jar <client.jar>'); return; }
      const img = new Image();
      img.onload = () => {
        atlas.image = img; atlas.repeat.set(1 / (16 * j.width), 1 / (16 * j.height)); atlas.needsUpdate = true;
        atlasW = j.width; atlasH = j.height;
      };
      img.onerror = () => retry('blocks.png');
      img.src = `/api/blocks.png?v=${encodeURIComponent(j.version || '')}`;
    }).catch((e) => retry(String(e && e.message || e)));
  }
  loadAtlas();

  // ------------------------------------------------------------ State

  const st = {
    srv: null, dim: null, at: null, radius: 10, lod: 4096, fog: false, full: false, fancy: false, hour: 10,
    chunks: new Map(),      // "cx,cz" -> { group, etag, empty, loading }
    regions: new Map(),     // "rx,rz" -> { mesh, etag, empty, loading }
    queue: 0, lodQueue: 0,
    active: false,
    keys: new Set(),
    speed: 20,
    yaw: 0, pitch: -0.5,
    since: Date.now() / 1000,
    cam: 'free',            // free | follow | ego | top
    dist: 6,
    selected: null,         // { type: 'player' } | { type: 'schematic', id }
  };

  let index = new Uint32Array(0);
  function indexFor(quads) {
    if (index.length < quads * 6) {
      const n = Math.max(quads, 16384) * 2;
      index = new Uint32Array(n * 6);
      for (let q = 0; q < n; q++) {
        const o = q * 6, v = q * 4;
        index[o] = v; index[o + 1] = v + 1; index[o + 2] = v + 2;
        index[o + 3] = v; index[o + 4] = v + 2; index[o + 5] = v + 3;
      }
    }
    return index.subarray(0, quads * 6);   // shares the buffer, does not copy
  }

  function parse(buf) {
    const dv = new DataView(buf);
    const magic = String.fromCharCode(dv.getUint8(0), dv.getUint8(1), dv.getUint8(2), dv.getUint8(3));
    if (magic !== 'MSH3') throw new Error('falsches Mesh-Format ' + magic);
    let o = 12;
    const out = [];
    for (let p = 0; p < PASSES; p++) {
      const n = dv.getUint32(o, true); o += 4;
      if (!n) { out.push(null); continue; }
      // Straight to the GPU without conversion: positions in 1/16 block (mesh is scaled),
      // UVs in 1/16 atlas pixel (atlas texture scaled via repeat).
      const pos16 = new Int16Array(buf.slice(o, o + n * 24)); o += n * 24;
      const uv16 = new Uint16Array(buf.slice(o, o + n * 16)); o += n * 16;
      const rgb = new Uint8Array(buf.slice(o, o + n * 12)); o += n * 12;
      const light = new Uint8Array(buf.slice(o, o + n * 8)); o += n * 8;
      const g = new THREE.BufferGeometry();
      g.setAttribute('position', new THREE.BufferAttribute(pos16, 3));
      g.setAttribute('uv', new THREE.BufferAttribute(uv16, 2));
      g.setAttribute('color', new THREE.BufferAttribute(rgb, 3, true));
      g.setAttribute('light', new THREE.BufferAttribute(light, 2, true));
      g.setIndex(new THREE.BufferAttribute(indexFor(n), 1));
      g.computeBoundingSphere();
      out.push(g);
    }
    // X-ray: positions of the searched blocks behind the passes (see viewer/xray.py)
    if (buf.byteLength >= o + 8 && dv.getUint32(o, true) === 0x31595258) {
      const m = dv.getUint32(o + 4, true); o += 8;
      const counts = new Uint32Array(buf.slice(o, o + m * 4)); o += m * 4;
      const n = dv.getUint32(o, true); o += 4;
      out.xray = { counts, pts: new Int16Array(buf.slice(o, o + n * 8)) };
    }
    return out;
  }

  const disposeGroup = (g) => g.traverse((m) => { if (m.geometry) m.geometry.dispose(); });

  // ------------------------------------------------------------ Chunks (near)

  function dropChunk(key) {
    const c = st.chunks.get(key);
    if (!c) return;
    if (c.group) { scene.remove(c.group); disposeGroup(c.group); const [x, z] = key.split(',').map(Number); setMask(x, z, false); }
    dropSpawn(c);
    if (c.xray) xrayDirty();
    st.chunks.delete(key);
  }

  const pending = [];
  function applyPending(budgetMs) {
    const t0 = performance.now();
    let n = 0;
    while (pending.length && (n < 1 || performance.now() - t0 < budgetMs)) { pending.shift()(); n++; }
  }

  const atQuery = () => (st.at ? '&at=' + st.at : '');
  const currentQuery = () => clip.query + xray.query + atQuery() + (st.full ? '&full' : '');
  async function loadChunk(cx, cz, force) {
    const key = `${cx},${cz}`;
    let c = st.chunks.get(key);
    if (c && (c.loading || (!force && (c.group || c.empty)))) return;
    if (!c) { c = {}; st.chunks.set(key, c); }
    c.loading = true;
    st.queue++;
    try {
      const url = `/api/mesh/${encodeURIComponent(st.srv)}/${encodeURIComponent(st.dim)}/${cx}/${cz}?full=${st.full ? 1 : 0}${clip.query}${xray.query}${atQuery()}`;
      const query = currentQuery();
      const r = await fetch(url);
      if (st.chunks.get(key) !== c) return;
      if (r.status === 204) {
        c.empty = true; if (query === currentQuery()) c.stale = false;
        if (c.group) { scene.remove(c.group); disposeGroup(c.group); c.group = null; setMask(cx, cz, false); }
        if (c.xray) { c.xray = null; xrayDirty(); }
        return;
      }
      if (!r.ok) { c.empty = true; ctx.toast && ctx.toast('3D: ' + (await r.text())); return; }
      const etag = r.headers.get('ETag');
      if (c.group && etag && etag === c.etag) { if (query === currentQuery()) c.stale = false; return; }
      const buf = await r.arrayBuffer();
      // Insert only in the next frame with a time budget, so many finished chunks don't stutter at once
      await new Promise((resolve) => pending.push(() => {
        if (st.chunks.get(key) === c) {
          const geos = parse(buf);
          const group = new THREE.Group();
          geos.forEach((g, i) => { if (g) group.add(chunkMesh(g, i)); });
          group.position.set(cx * 16, 0, cz * 16);
          group.scale.setScalar(1 / 16);
          if (c.group) { scene.remove(c.group); disposeGroup(c.group); }
          c.group = group; c.etag = etag; c.empty = false;
          if (c.xray || geos.xray) { c.xray = geos.xray ? { ...geos.xray, cx, cz } : null; xrayDirty(); }
          if (query === currentQuery()) c.stale = false;
          scene.add(group);
          setMask(cx, cz, true);
          if (st.spawn) loadSpawn(cx, cz, c);
        }
        resolve();
      }));
    } catch (e) {
      c.empty = true;
      console.warn('Mesh', key, e);
    } finally {
      c.loading = false;
      st.queue--;
    }
  }

  // ------------------------------------------------------------ Mob spawns (toggle)

  // 1 = only at night (under open sky), 2 = dark enough at any time
  const SPAWN_COLORS = { 1: 0xf5a524, 2: 0xe5484d };
  const spawnGeo = new THREE.PlaneGeometry(0.86, 0.86).rotateX(-Math.PI / 2);
  const spawnMats = {};
  for (const [k, color] of Object.entries(SPAWN_COLORS)) {
    spawnMats[k] = track(new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.6, depthWrite: false, side: THREE.DoubleSide }));
  }
  const spawnInfo = { noLight: new Set(), warned: false };

  function dropSpawn(c) {
    if (!c.spawn) return;
    scene.remove(c.spawn);
    c.spawn.children.forEach((m) => m.dispose && m.dispose());
    c.spawn = null;
  }

  async function loadSpawn(cx, cz, c) {
    const key = `${cx},${cz}`;
    const tag = c.etag || '';
    try {
      const r = await fetch(`/api/spawn/${encodeURIComponent(st.srv)}/${encodeURIComponent(st.dim)}/${cx}/${cz}`);
      if (!r.ok) return;
      const d = await r.json();
      if (!st.spawn || st.chunks.get(key) !== c || (c.etag || '') !== tag) return;
      dropSpawn(c);
      if (!d.light) {
        spawnInfo.noLight.add(key);
        if (!spawnInfo.warned && ctx.toast) {
          spawnInfo.warned = true;
          ctx.toast('Mob-Spawns: Für manche Chunks fehlen Lichtdaten. Mit der neuen Mod-Version dort noch einmal hinlaufen.');
        }
        return;
      }
      spawnInfo.noLight.delete(key);
      const by = { 1: [], 2: [] };
      for (let i = 0; i < d.pts.length; i += 4) by[d.pts[i + 3]].push(i);
      const g = new THREE.Group();
      const m4 = new THREE.Matrix4();
      for (const k of [1, 2]) {
        const idx = by[k];
        if (!idx.length) continue;
        const mesh = new THREE.InstancedMesh(spawnGeo, spawnMats[k], idx.length);
        idx.forEach((i, n) => { m4.makeTranslation(d.pts[i] + 0.5, d.pts[i + 1] + 0.03, d.pts[i + 2] + 0.5); mesh.setMatrixAt(n, m4); });
        mesh.instanceMatrix.needsUpdate = true;
        g.add(mesh);
      }
      c.spawn = g;
      scene.add(g);
    } catch (e) {
      console.warn('Spawn', key, e);
    }
  }

  function setSpawn(on) {
    st.spawn = !!on;
    spawnInfo.warned = false;
    spawnInfo.noLight.clear();
    for (const [key, c] of st.chunks) {
      if (!st.spawn) { dropSpawn(c); continue; }
      if (c.group) { const [x, z] = key.split(',').map(Number); loadSpawn(x, z, c); }
    }
  }

  // ------------------------------------------------------------ X-ray

  /* Only the searched blocks: chunks come from the server with everything else as air
   * (?xray=a,b), the far view is off. Around each find a glowing box, so single ores
   * can still be seen from far away; the boxes are rebuilt from all loaded chunks. */
  const XRAY_BG = 0x0b0e14;
  const XRAY_MAX = 20000;
  const xray = { on: false, names: [], colors: [], query: '', timer: null, mesh: null, sorted: [], visited: new Set() };
  const haloGeo = new THREE.BoxGeometry(1.25, 1.25, 1.25);
  const haloMat = track(new THREE.MeshBasicMaterial({ transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, side: THREE.DoubleSide }));
  // Bright edges, only a hint of fill: from close up the block itself stays visible
  haloMat.onBeforeCompile = (sh) => {
    sh.vertexShader = 'varying vec2 vEdgeUv;\n' + sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\nvEdgeUv = uv;');
    sh.fragmentShader = 'varying vec2 vEdgeUv;\n' + sh.fragmentShader.replace('#include <opaque_fragment>', `
  float e = min(min(vEdgeUv.x, 1.0 - vEdgeUv.x), min(vEdgeUv.y, 1.0 - vEdgeUv.y));
  float w = fwidth(e) * 1.5;
  outgoingLight *= mix(0.1, 0.9, 1.0 - smoothstep(0.04, 0.04 + w, e));
  #include <opaque_fragment>`);
  };

  function xrayDirty() {
    if (xray.timer) return;
    xray.timer = setTimeout(() => { xray.timer = null; rebuildXray(); }, 250);
  }

  function rebuildXray() {
    if (xray.mesh) { scene.remove(xray.mesh); xray.mesh.dispose(); xray.mesh = null; }
    const counts = xray.names.map(() => 0);
    const all = [];
    if (xray.on) {
      for (const c of st.chunks.values()) {
        if (!c.xray) continue;
        const { pts, cx, cz } = c.xray;
        c.xray.counts.forEach((v, k) => { if (k < counts.length) counts[k] += v; });
        for (let i = 0; i < pts.length; i += 4) {
          const k = pts[i + 3];
          if (k < 0 || k >= counts.length) continue;
          all.push([cx * 16 + pts[i], pts[i + 1], cz * 16 + pts[i + 2], k]);
        }
      }
    }
    const p = camera.position;
    const d2 = (a) => (a[0] + 0.5 - p.x) ** 2 + (a[1] + 0.5 - p.y) ** 2 + (a[2] + 0.5 - p.z) ** 2;
    all.sort((a, b) => d2(a) - d2(b));
    xray.sorted = all;
    const n = Math.min(all.length, XRAY_MAX);
    if (n) {
      const mesh = new THREE.InstancedMesh(haloGeo, haloMat, n);
      const m4 = new THREE.Matrix4(), col = new THREE.Color();
      for (let i = 0; i < n; i++) {
        const a = all[i];
        m4.makeTranslation(a[0] + 0.5, a[1] + 0.5, a[2] + 0.5);
        mesh.setMatrixAt(i, m4);
        mesh.setColorAt(i, col.set(xray.colors[a[3]] || '#ffffff'));
      }
      mesh.instanceMatrix.needsUpdate = true;
      mesh.instanceColor.needsUpdate = true;
      mesh.frustumCulled = false;
      mesh.renderOrder = 2;
      xray.mesh = mesh;
      scene.add(mesh);
    }
    ctx.onXray && ctx.onXray({ on: xray.on, counts, total: all.length });
  }

  /* names: block ids ("minecraft:diamond_ore"), colors: one CSS color per name.
   * Empty list = X-ray off. */
  function setXray(names, colors = []) {
    const pairs = names.map((n, i) => [n.includes(':') ? n : 'minecraft:' + n, colors[i]]).sort((a, b) => (a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0));
    const sorted = pairs.map((x) => x[0]);
    const query = sorted.length ? '&xray=' + encodeURIComponent(sorted.join(',')) : '';
    xray.colors = pairs.map((x) => x[1]);
    const changed = query !== xray.query;
    xray.names = sorted;
    xray.on = sorted.length > 0;
    if (changed) {
      xray.query = query;
      xray.visited.clear();
      for (const c of st.chunks.values()) { if (c.group || c.empty) { c.stale = true; c.empty = false; } c.xray = null; }
    }
    setSky();
    rebuildXray();
  }

  /* Fly to the nearest find not visited yet (then the next one, and so on). */
  function xrayNext() {
    const p = camera.position;
    const d2 = (a) => (a[0] + 0.5 - p.x) ** 2 + (a[1] + 0.5 - p.y) ** 2 + (a[2] + 0.5 - p.z) ** 2;
    const left = xray.sorted.filter((a) => !xray.visited.has(a.join(',')));
    if (!left.length) {
      if (!xray.sorted.length) return null;
      xray.visited.clear();
      return xrayNext();
    }
    let best = left[0];
    for (const a of left) if (d2(a) < d2(best)) best = a;
    xray.visited.add(best.join(','));
    st.cam = 'free';
    ctx.onCam && ctx.onCam('free');
    // a few blocks away, looking at it from slightly above
    const dx = p.x - best[0] - 0.5, dz = p.z - best[2] - 0.5;
    const len = Math.hypot(dx, dz) || 1;
    camera.position.set(best[0] + 0.5 + (dx / len) * 5, best[1] + 3.5, best[2] + 0.5 + (dz / len) * 5);
    st.yaw = Math.atan2(dx / len, dz / len);
    st.pitch = -Math.atan2(3, 5);
    return { x: best[0], y: best[1], z: best[2], name: xray.names[best[3]] };
  }

  // ------------------------------------------------------------ Regions (far view)

  /* Far view: one height field per region. A 32x32 mask (one texel per chunk)
   * hides it exactly where the real chunk is already loaded. That way there are
   * never holes: whatever is still loading or beyond view distance shows the far view. */
  function lodMaterial(tex, mask, size = 512) {
    const Mat = st.fancy ? THREE.MeshLambertMaterial : THREE.MeshBasicMaterial;
    const m = track(new Mat({ map: tex, alphaTest: 0.5, polygonOffset: true, polygonOffsetFactor: 2, polygonOffsetUnits: 4,
      ...(st.fancy ? { flatShading: true } : {}) }));
    m.onBeforeCompile = (sh) => {
      sh.uniforms.uMask = { value: mask };
      sh.uniforms.uSize = { value: size };
      sh.vertexShader = 'attribute float valid;\nvarying vec2 vLocal;\nvarying float vValid;\nvarying vec3 vPos;\n' +
        sh.vertexShader.replace('#include <begin_vertex>', '#include <begin_vertex>\nvLocal = position.xz;\nvValid = valid;\nvPos = position;');
      // Don't draw triangles that reach into a data gap as a slanted wall
      sh.uniforms.uDay = lightUniforms.uDay;
      sh.fragmentShader = 'uniform sampler2D uMask;\nuniform float uSize;\nuniform float uDay;\nvarying vec2 vLocal;\nvarying float vValid;\nvarying vec3 vPos;\n' +
        sh.fragmentShader.replace('void main() {', 'void main() {\n  if (vValid < 0.99 || texture2D(uMask, vLocal / uSize).r > 0.5) discard;')
          /* The map color comes without shading (raw=1). Apply the same brightness per
           * face direction as for the real chunks (top 1, north/south 0.8, east/west 0.6),
           * from the slope of the terrain; so there is no edge between near and far. */
          .replace('#include <map_fragment>', `#include <map_fragment>
  { vec3 n = normalize(cross(dFdx(vPos), dFdy(vPos)));
    diffuseColor.rgb *= n.y * n.y + 0.8 * n.z * n.z + 0.6 * n.x * n.x; }`)
          // as dark at night as the near chunks (done there in the light shader)
          .replace('#include <opaque_fragment>', (st.fancy ? 'outgoingLight *= mix(0.55, 1.0, uDay);\n' : '') + '#include <opaque_fragment>');
    };
    return m;
  }

  function newMask(n = 32) {
    const t = new THREE.DataTexture(new Uint8Array(n * n), n, n, THREE.RedFormat, THREE.UnsignedByteType);
    t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.flipY = false;
    t.needsUpdate = true;
    return t;
  }

  function setMask(cx, cz, on) {
    const r = st.regions.get(`${cx >> 5},${cz >> 5}`);
    if (!r || !r.mask) return;
    r.mask.image.data[(cz & 31) * 32 + (cx & 31)] = on ? 255 : 0;
    r.mask.needsUpdate = true;
  }

  function lodGeometry(h, step = 4) {
    const N = 129;
    let fill = 62;
    for (let i = 0; i < h.length; i++) if (h[i] !== -32768) { fill = h[i]; break; }
    const pos = new Float32Array(N * N * 3), uv = new Float32Array(N * N * 2), valid = new Uint8Array(N * N);
    for (let j = 0; j < N; j++) {
      for (let i = 0; i < N; i++) {
        const k = j * N + i;
        const v = h[k];
        pos[k * 3] = i * step; pos[k * 3 + 1] = (v === -32768 ? fill : v) + 1; pos[k * 3 + 2] = j * step;
        valid[k] = v === -32768 ? 0 : 255;
        uv[k * 2] = i / 128; uv[k * 2 + 1] = 1 - j / 128;
      }
    }
    const idx = new Uint32Array(128 * 128 * 6);
    let o = 0;
    for (let j = 0; j < 128; j++) {
      for (let i = 0; i < 128; i++) {
        const a = j * N + i, b = a + 1, c = a + N, d = c + 1;
        idx[o++] = a; idx[o++] = c; idx[o++] = b; idx[o++] = b; idx[o++] = c; idx[o++] = d;
      }
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    g.setAttribute('uv', new THREE.BufferAttribute(uv, 2));
    g.setAttribute('valid', new THREE.BufferAttribute(valid, 1, true));
    g.setIndex(new THREE.BufferAttribute(idx, 1));
    g.computeBoundingSphere();
    return g;
  }

  function disposeRegionMesh(m) {
    scene.remove(m); m.geometry.dispose(); m.material.map.dispose(); clipped.delete(m.material); m.material.dispose();
  }

  function dropRegion(key) {
    const r = st.regions.get(key);
    if (!r) return;
    if (r.mesh) disposeRegionMesh(r.mesh);
    if (r.mask) r.mask.dispose();
    st.regions.delete(key);
    const [x, z] = key.split(',').map(Number);
    setFarMask(1, x, z, false);
  }

  async function loadRegion(rx, rz, force) {
    const key = `${rx},${rz}`;
    let r = st.regions.get(key);
    if (r && (r.loading || (!force && (r.mesh || r.empty)))) return;
    if (!r) { r = {}; st.regions.set(key, r); }
    r.loading = true;
    st.lodQueue++;
    try {
      const base = `${encodeURIComponent(st.srv)}/${encodeURIComponent(st.dim)}`;
      const res = await fetch(`/api/lod/${base}/${rx}/${rz}`);
      if (st.regions.get(key) !== r) return;
      if (res.status === 204 || !res.ok) { r.empty = true; return; }
      const etag = res.headers.get('ETag');
      if (r.mesh && etag === r.etag) return;
      const h = new Int16Array(await res.arrayBuffer());
      const layer = st.dim === 'the_nether' ? 'cave127' : 'surface320';
      const tex = await new THREE.TextureLoader().loadAsync(`/api/tile/${base}/${layer}/0/${rx}/${rz}.png?raw=1&v=${encodeURIComponent(etag || Date.now())}`);
      if (st.regions.get(key) !== r) { tex.dispose(); return; }
      tex.magFilter = THREE.NearestFilter;
      tex.colorSpace = THREE.NoColorSpace;
      if (!r.mask) {
        r.mask = newMask();
        for (const [k, c] of st.chunks) {
          if (!c.group) continue;
          const [x, z] = k.split(',').map(Number);
          if (x >> 5 === rx && z >> 5 === rz) r.mask.image.data[(z & 31) * 32 + (x & 31)] = 255;
        }
        r.mask.needsUpdate = true;
      }
      const mesh = new THREE.Mesh(lodGeometry(h), lodMaterial(tex, r.mask));
      mesh.position.set(rx * 512, 0, rz * 512);
      mesh.receiveShadow = true;
      if (r.mesh) disposeRegionMesh(r.mesh);
      r.mesh = mesh; r.etag = etag; r.empty = false;
      mesh.visible = lodOn();
      scene.add(mesh);
      setFarMask(1, rx, rz, true);
    } catch (e) {
      r.empty = true;
      console.warn('LOD', key, e);
    } finally {
      r.loading = false;
      st.lodQueue--;
    }
  }

  // ------------------------------------------------------------ Wide far view (levels 1, 2)

  /* Like BlueMap: the farther away, the coarser the tiles. Level 1 = 2048 blocks (one point
   * every 16), level 2 = 8192 blocks (every 64). Each tile hides, via a 4x4 mask, the
   * parts a finer level already shows. They are drawn in a separate
   * pass (layer 1) before everything else, with their own depth range; otherwise the
   * depth buffer precision isn't enough over 60k blocks. */
  const FAR = 1;   // three.js layer for the sky and the wide far view
  const FAR_SIZE = [512, 2048, 8192];
  const farTiles = new Map();   // "level,x,z" -> { mesh, mask, etag, empty, loading }
  const farQueue = [0, 0, 0];   // running requests per level

  // Range per level in tiles: level 0 up to 2560, level 1 up to 10240 blocks, level 2 the rest
  const lodOn = () => st.lod > 0 && !xray.on;
  function lodRanges() {
    const d = xray.on ? 0 : st.lod;
    const r0 = d > 0 ? Math.min(Math.ceil(d / 512), 5) : 0;
    const r1 = d > r0 * 512 ? Math.min(Math.ceil(d / 2048), 5) : 0;
    const r2 = d > r1 * 2048 && r1 ? Math.min(Math.ceil(d / 8192), 8) : 0;
    return [r0, r1, r2];
  }

  // Tile of level `level` (1 or 2) at subtile (x, z) of the level below
  function setFarMask(level, x, z, on) {
    if (level > 2) return;
    const t = farTiles.get(`${level},${x >> 2},${z >> 2}`);
    if (t && t.mask) {
      t.mask.image.data[(z & 3) * 4 + (x & 3)] = on ? 255 : 0;
      t.mask.needsUpdate = true;
    }
  }

  function dropFar(key) {
    const t = farTiles.get(key);
    if (!t) return;
    if (t.mesh) disposeRegionMesh(t.mesh);
    if (t.mask) t.mask.dispose();
    farTiles.delete(key);
    const [level, x, z] = key.split(',').map(Number);
    setFarMask(level + 1, x, z, false);
  }

  async function loadFar(level, x, z) {
    const key = `${level},${x},${z}`;
    if (farTiles.has(key)) return;
    const t = { loading: true };
    farTiles.set(key, t);
    farQueue[level]++;
    try {
      const base = `${encodeURIComponent(st.srv)}/${encodeURIComponent(st.dim)}`;
      const res = await fetch(`/api/lod/${base}/${x}/${z}?level=${level}`);
      if (farTiles.get(key) !== t) return;
      if (res.status === 204 || !res.ok) { t.empty = true; return; }
      const etag = res.headers.get('ETag');
      const h = new Int16Array(await res.arrayBuffer());
      const layer = st.dim === 'the_nether' ? 'cave127' : 'surface320';
      const tex = await new THREE.TextureLoader().loadAsync(`/api/tile/${base}/${layer}/${-2 * level}/${x}/${z}.png?raw=1&v=${encodeURIComponent(etag || Date.now())}`);
      if (farTiles.get(key) !== t) { tex.dispose(); return; }
      tex.colorSpace = THREE.NoColorSpace;
      t.mask = newMask(4);
      // hide finer tiles that are already loaded
      for (let j = 0; j < 4; j++) {
        for (let i = 0; i < 4; i++) {
          const fx = x * 4 + i, fz = z * 4 + j;
          const finer = level === 1 ? st.regions.get(`${fx},${fz}`) : farTiles.get(`1,${fx},${fz}`);
          if (finer && finer.mesh) t.mask.image.data[j * 4 + i] = 255;
        }
      }
      t.mask.needsUpdate = true;
      const mesh = new THREE.Mesh(lodGeometry(h, FAR_SIZE[level] / 128), lodMaterial(tex, t.mask, FAR_SIZE[level]));
      mesh.position.set(x * FAR_SIZE[level], 0, z * FAR_SIZE[level]);
      mesh.layers.set(FAR);
      mesh.visible = lodOn();
      t.mesh = mesh; t.etag = etag;
      scene.add(mesh);
      if (level === 1) setFarMask(2, x, z, true);
    } catch (e) {
      t.empty = true;
      console.warn('LOD', key, e);
    } finally {
      t.loading = false;
      farQueue[level]--;
    }
  }

  function streamFar(w) {
    const ranges = lodRanges();
    for (const level of [1, 2]) {
      const R = ranges[level], size = FAR_SIZE[level];
      const cx = Math.floor(w.x / size), cz = Math.floor(w.z / size);
      for (const key of Array.from(farTiles.keys())) {
        const [l, x, z] = key.split(',').map(Number);
        if (l === level && (!R || Math.abs(x - cx) > R + 1 || Math.abs(z - cz) > R + 1)) dropFar(key);
      }
      if (!R) continue;
      const want = [];
      for (let dz = -R; dz <= R; dz++) {
        for (let dx = -R; dx <= R; dx++) {
          if (dx * dx + dz * dz > R * R + 1) continue;
          if (!farTiles.has(`${level},${cx + dx},${cz + dz}`)) want.push([dx * dx + dz * dz, cx + dx, cz + dz]);
        }
      }
      want.sort((a, b) => a[0] - b[0]);
      for (const [, x, z] of want) {
        if (farQueue[level] >= 2) break;
        loadFar(level, x, z);
      }
    }
  }

  // ------------------------------------------------------------ Streaming

  function stream() {
    if (!st.active || !st.srv || !atlasW) return;
    const w = camera.position;
    const ccx = Math.floor(w.x / 16), ccz = Math.floor(w.z / 16);
    const R = st.radius;
    for (const [key] of st.chunks) {
      const [x, z] = key.split(',').map(Number);
      if (Math.abs(x - ccx) > R + 2 || Math.abs(z - ccz) > R + 2) dropChunk(key);
    }
    // Missing chunks and stale ones (after a layer or cave switch) in one list,
    // nearest first. Separately, the stale ones would wait forever at large view distances
    // while new chunks are still loading.
    const want = [];
    for (let dz = -R; dz <= R; dz++) {
      for (let dx = -R; dx <= R; dx++) {
        if (dx * dx + dz * dz > R * R) continue;
        const key = `${ccx + dx},${ccz + dz}`;
        const c = st.chunks.get(key);
        if (!c) want.push([dx * dx + dz * dz, ccx + dx, ccz + dz, false]);
        else if (c.stale && !c.loading) want.push([dx * dx + dz * dz, ccx + dx, ccz + dz, true]);
      }
    }
    want.sort((a, b) => a[0] - b[0]);
    for (const [, x, z, force] of want) {
      if (st.queue >= 8) break;
      loadChunk(x, z, force);
    }

    streamFar(w);
    const LR = lodRanges()[0];
    const crx = Math.floor(w.x / 512), crz = Math.floor(w.z / 512);
    for (const [key] of st.regions) {
      const [x, z] = key.split(',').map(Number);
      if (Math.abs(x - crx) > LR + 1 || Math.abs(z - crz) > LR + 1) dropRegion(key);
    }
    if (LR <= 0) return;
    const wantR = [];
    for (let dz = -LR; dz <= LR; dz++) {
      for (let dx = -LR; dx <= LR; dx++) {
        if (dx * dx + dz * dz > LR * LR + 1) continue;
        const key = `${crx + dx},${crz + dz}`;
        if (!st.regions.has(key)) wantR.push([dx * dx + dz * dz, crx + dx, crz + dz]);
      }
    }
    wantR.sort((a, b) => a[0] - b[0]);
    for (const [, x, z] of wantR) {
      if (st.lodQueue >= 3) break;
      loadRegion(x, z);
    }
  }
  setInterval(stream, 150);

  async function pollChanges() {
    if (!st.active || !st.srv) return;
    try {
      const q = new URLSearchParams({ srv: st.srv, dim: st.dim, since: st.since });
      const r = await (await fetch('/api/changes?' + q)).json();
      st.since = r.now;
      const regions = new Set(r.regions.map(([x, z]) => `${x},${z}`));
      if (!regions.size) return;
      // Only refetch the chunks that were actually rewritten (and their neighbours, because of the edges)
      const todo = new Set();
      for (const [x, z] of r.chunks || []) {
        for (const [ax, az] of [[0, 0], [1, 0], [-1, 0], [0, 1], [0, -1]]) todo.add(`${x + ax},${z + az}`);
      }
      for (const key of todo) {
        const c = st.chunks.get(key);
        if (!c) continue;
        const [x, z] = key.split(',').map(Number);
        c.empty = false;
        loadChunk(x, z, true);
      }
      // The far view grows along: reload changed and new regions
      for (const key of regions) {
        const [x, z] = key.split(',').map(Number);
        const reg = st.regions.get(key);
        if (reg) { reg.empty = false; loadRegion(x, z, true); }
      }
    } catch (_) { /* ignore */ }
  }
  setInterval(pollChanges, 3000);

  // ------------------------------------------------------------ Player (skin figure, view3d/player.js)

  const player = createPlayer(scene, st, disposeGroup);

  // ------------------------------------------------------------ Schematics

  const schems = new Map();   // id -> { pl, group, blocks, box, hit, meshKey, mats, overlay }

  function schemBox(pl, selected) {
    const [w, h, l] = pl.size;
    const geo = new THREE.EdgesGeometry(new THREE.BoxGeometry(w, h, l));
    const line = new THREE.LineSegments(geo, new THREE.LineBasicMaterial({ color: selected ? 0xffd23f : 0x7fd1ff, transparent: true, opacity: selected ? 1 : 0.55, depthTest: !selected }));
    line.position.set(w / 2, h / 2, l / 2);
    line.renderOrder = selected ? 5 : 0;
    return line;
  }

  async function loadSchemMesh(s) {
    const pl = s.pl;
    const key = `${pl.file}|${pl.rot}|${pl.mirror}|${pl.mtime || 0}`;
    if (s.meshKey === key || pl.error) return;
    s.meshKey = key;
    try {
      const r = await fetch(`/api/schematics/${encodeURIComponent(pl.id)}/mesh`);
      if (!r.ok) { ctx.toast && ctx.toast('Schematic: ' + ((await r.json().catch(() => ({}))).error || r.status)); return; }
      const geos = parse(await r.arrayBuffer());
      if (schems.get(pl.id) !== s || s.meshKey !== key) return;
      if (s.blocks) { s.group.remove(s.blocks); disposeGroup(s.blocks); }
      s.blocks = new THREE.Group();
      geos.forEach((g, i) => { if (g) s.blocks.add(new THREE.Mesh(g, s.mats[i])); });
      s.blocks.scale.setScalar(1 / 16);
      s.group.add(s.blocks);
    } catch (e) { console.warn('Schematic-Mesh', e); }
  }

  function applySchem(s) {
    const pl = s.pl;
    s.group.position.set(pl.x, pl.y, pl.z);
    s.group.visible = pl.visible !== false;
    const op = pl.opacity ?? 1;
    s.mats.forEach((m, i) => { m.opacity = op; m.transparent = i === 2 || op < 1; m.depthWrite = i !== 2 && op >= 0.99; m.needsUpdate = true; });
    const sel = !!(st.selected && st.selected.type === 'schematic' && st.selected.id === pl.id);
    if (s.box) { s.group.remove(s.box); s.box.geometry.dispose(); }
    s.box = schemBox(pl, sel);
    s.group.add(s.box);
    if (s.hit) { s.group.remove(s.hit); s.hit.geometry.dispose(); }
    s.hit = new THREE.Mesh(new THREE.BoxGeometry(...pl.size), new THREE.MeshBasicMaterial({ visible: false }));
    s.hit.position.set(pl.size[0] / 2, pl.size[1] / 2, pl.size[2] / 2);
    s.hit.userData.pick = { type: 'schematic', id: pl.id };
    s.group.add(s.hit);
    loadSchemMesh(s);
  }

  function setSchematics(list) {
    const ids = new Set();
    for (const pl of list) {
      if (pl.server !== st.srv || pl.dim !== st.dim) continue;
      ids.add(pl.id);
      let s = schems.get(pl.id);
      if (!s) {
        s = { pl, group: new THREE.Group(), mats: makeMaterials(pl.opacity ?? 1) };
        schems.set(pl.id, s);
        scene.add(s.group);
      }
      s.pl = pl;
      applySchem(s);
    }
    for (const [id, s] of schems) {
      if (!ids.has(id)) { scene.remove(s.group); disposeGroup(s.group); s.mats.forEach((m) => clipped.delete(m)); schems.delete(id); }
    }
  }

  const OVERLAY = { missing: 0x3e9bf5, wrong: 0xe5484d, extra: 0xf5a524, rotated: 0xffe066 };
  const cubeGeo = new THREE.BoxGeometry(1.02, 1.02, 1.02);
  function setVerify(id, points) {
    const s = schems.get(id);
    if (!s) return;
    if (s.overlay) { s.group.remove(s.overlay); s.overlay.children.forEach((m) => m.dispose && m.dispose()); s.overlay = null; }
    if (!points) return;
    const g = new THREE.Group();
    const m4 = new THREE.Matrix4();
    for (const [kind, color] of Object.entries(OVERLAY)) {
      const pts = points[kind] || [];
      const n = pts.length / 3;
      if (!n) continue;
      const mesh = new THREE.InstancedMesh(cubeGeo, new THREE.MeshBasicMaterial({ color, transparent: true, opacity: 0.45, depthWrite: false }), n);
      for (let i = 0; i < n; i++) { m4.makeTranslation(pts[i * 3] + 0.5, pts[i * 3 + 1] + 0.5, pts[i * 3 + 2] + 0.5); mesh.setMatrixAt(i, m4); }
      mesh.instanceMatrix.needsUpdate = true;
      g.add(mesh);
    }
    s.overlay = g;
    s.group.add(g);
  }

  // Timeline diff, waypoints, other players, trail: view3d/markers.js
  const { setDiff, setWaypoints, setOthers, setTrail } = createMarkers(scene, st, disposeGroup);

  // ------------------------------------------------------------ Selection by click

  const ray = new THREE.Raycaster();
  function pick(ndcX, ndcY) {
    ray.setFromCamera(new THREE.Vector2(ndcX, ndcY), camera);
    const targets = [];
    if (player.root.visible) targets.push(player.hit);
    for (const s of schems.values()) if (s.group.visible && s.hit) targets.push(s.hit);
    const hit = ray.intersectObjects(targets, false)[0];
    return hit ? hit.object.userData.pick : null;
  }

  // Block under the mouse pointer (for "pick area in 3D")
  function pickBlock(ndcX, ndcY) {
    ray.setFromCamera(new THREE.Vector2(ndcX, ndcY), camera);
    const groups = [];
    for (const c of st.chunks.values()) if (c.group) groups.push(c.group);
    const hits = ray.intersectObjects(groups, true);
    for (const hit of hits) {
      if (!hit.face) continue;
      if (clip.planes.some((pl) => pl.distanceToPoint(hit.point) < -0.05)) continue;   // cut-away part
      const n = hit.face.normal.clone().transformDirection(hit.object.matrixWorld);
      const p = hit.point.clone().addScaledVector(n, -0.02);
      return { x: Math.floor(p.x), y: Math.floor(p.y), z: Math.floor(p.z) };
    }
    return null;
  }
  let blockPick = null;
  function setBlockPick(cb) {
    blockPick = cb;
    container.classList.toggle('picking', !!cb);
  }

  // Area (automations) as a frame; ``first`` = first picked block
  const areaMat = new THREE.LineBasicMaterial({ color: 0xf5a524, depthTest: false, transparent: true });
  let areaBox = null;
  function setArea(a) {
    if (areaBox) { scene.remove(areaBox); areaBox.geometry.dispose(); areaBox = null; }
    if (!a) return;
    const w = a.x1 - a.x0 + 1, h = a.y1 - a.y0 + 1, d = a.z1 - a.z0 + 1;
    const g = new THREE.EdgesGeometry(new THREE.BoxGeometry(w + 0.04, h + 0.04, d + 0.04));
    areaBox = new THREE.LineSegments(g, areaMat);
    areaBox.position.set(a.x0 + w / 2, a.y0 + h / 2, a.z0 + d / 2);
    areaBox.renderOrder = 10;
    scene.add(areaBox);
  }

  function select(sel, quiet) {
    st.selected = sel;
    for (const s of schems.values()) applySchem(s);
    if (!quiet && ctx.onSelect) ctx.onSelect(sel);
  }

  // ------------------------------------------------------------ Controls

  const canvas = renderer.domElement;
  canvas.tabIndex = 0;
  let downAt = null;
  canvas.addEventListener('mousedown', (e) => { downAt = [e.clientX, e.clientY]; });
  canvas.addEventListener('click', (e) => {
    if (downAt && Math.hypot(e.clientX - downAt[0], e.clientY - downAt[1]) > 4) return;
    const locked = document.pointerLockElement === canvas;
    const rect = canvas.getBoundingClientRect();
    const nx = locked ? 0 : ((e.clientX - rect.left) / rect.width) * 2 - 1;
    const ny = locked ? 0 : -((e.clientY - rect.top) / rect.height) * 2 + 1;
    if (blockPick) {
      const b = pickBlock(nx, ny);
      if (b) blockPick(b);
      return;
    }
    const hit = pick(nx, ny);
    if (hit) { select(hit); return; }
    if (!locked) canvas.requestPointerLock();
  });
  document.addEventListener('pointerlockchange', () => {
    container.classList.toggle('locked', document.pointerLockElement === canvas);
  });
  document.addEventListener('mousemove', (e) => {
    if (!st.active || document.pointerLockElement !== canvas) return;
    st.yaw -= e.movementX * 0.0025;
    st.pitch = Math.max(-1.55, Math.min(1.55, st.pitch - e.movementY * 0.0025));
  });
  canvas.addEventListener('wheel', (e) => {
    e.preventDefault();
    if (st.cam === 'follow' || st.cam === 'top') {
      st.dist = Math.max(2, Math.min(300, st.dist * (e.deltaY < 0 ? 1 / 1.15 : 1.15)));
      return;
    }
    st.speed = Math.max(2, Math.min(800, st.speed * (e.deltaY < 0 ? 1.2 : 1 / 1.2)));
    ctx.toast && ctx.toast(`Fluggeschwindigkeit ${Math.round(st.speed)} Blöcke/s`);
  }, { passive: false });
  const MOVE = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'Space']);
  document.addEventListener('keydown', (e) => {
    if (!st.active || e.target.matches('input, select, textarea')) return;
    st.keys.add(e.code);
    if (MOVE.has(e.code) && st.cam !== 'free') setCam('free');
  });
  document.addEventListener('keyup', (e) => st.keys.delete(e.code));
  window.addEventListener('blur', () => st.keys.clear());

  function setCam(mode) {
    if (mode !== 'free' && !(player.root.visible && player.pos)) {
      ctx.toast && ctx.toast('Spieler ist in dieser Dimension nicht online');
      mode = 'free';
    }
    if (mode === 'follow' && st.cam !== 'follow') {
      st.yaw = -THREE.MathUtils.degToRad(player.yaw) + Math.PI;
      st.pitch = -0.35;
      st.dist = Math.max(st.dist, 5);
    }
    if (mode === 'top') st.dist = Math.max(st.dist, 30);
    if (mode === 'free' && st.cam !== 'free') {
      // Take over the view direction of the previous camera
      const e = new THREE.Euler().setFromQuaternion(camera.quaternion, 'YXZ');
      st.yaw = e.y; st.pitch = Math.max(-1.55, Math.min(1.55, e.x));
    }
    st.cam = mode;
    ctx.onCam && ctx.onCam(mode);
  }

  const fwd = new THREE.Vector3(), right = new THREE.Vector3();
  let last = performance.now();
  function frame(now) {
    requestAnimationFrame(frame);
    const dt = Math.min(0.1, (now - last) / 1000);
    last = now;
    if (!st.active) return;
    applyPending(4);
    placeSun();
    player.animate(dt);
    if (st.cam === 'free' || !player.pos) {
      camera.rotation.set(st.pitch, st.yaw, 0);
      const k = st.keys;
      const boost = k.has('ControlLeft') || k.has('ControlRight') ? 4 : 1;
      fwd.set(-Math.sin(st.yaw), 0, -Math.cos(st.yaw));
      right.set(Math.cos(st.yaw), 0, -Math.sin(st.yaw));
      const v = st.speed * boost * dt;
      if (k.has('KeyW')) camera.position.addScaledVector(fwd, v);
      if (k.has('KeyS')) camera.position.addScaledVector(fwd, -v);
      if (k.has('KeyD')) camera.position.addScaledVector(right, v);
      if (k.has('KeyA')) camera.position.addScaledVector(right, -v);
      if (k.has('Space')) camera.position.y += v;
      if (k.has('ShiftLeft') || k.has('ShiftRight')) camera.position.y -= v;
    } else {
      const p = player.pos;
      if (st.cam === 'ego') {
        camera.position.set(p.x, p.y + 1.62, p.z);
        camera.rotation.set(-THREE.MathUtils.degToRad(player.pitch), -THREE.MathUtils.degToRad(player.yaw) + Math.PI, 0);
      } else if (st.cam === 'follow') {
        const cp = Math.cos(st.pitch);
        camera.position.set(p.x + Math.sin(st.yaw) * cp * st.dist, p.y + 1.6 - Math.sin(st.pitch) * st.dist, p.z + Math.cos(st.yaw) * cp * st.dist);
        camera.lookAt(p.x, p.y + 1.4, p.z);
      } else if (st.cam === 'top') {
        camera.position.set(p.x, p.y + st.dist, p.z);
        camera.rotation.set(-Math.PI / 2 + 0.0001, st.yaw, 0);
      }
    }
    render();
  }
  requestAnimationFrame(frame);

  /* Far out (2b2t: millions of blocks) the GPU computes world positions
   * only in float32, and shadows then flicker. So for drawing, shift the whole scene
   * including the camera towards 0,0 in steps of 4096 (idea from BlueMap). */
  const ORIGIN_STEP = 4096;

  // With the wide far view, two passes: first sky and coarse tiles (layer 1) with
  // their own near/far range, then clear depth and draw everything near precisely.
  function renderPasses() {
    const far = lodRanges()[1] > 0 && lodOn();
    if (!far) {
      camera.layers.enable(FAR);
      renderer.render(scene, camera);
      return;
    }
    const near = camera.near, f = camera.far;
    renderer.autoClear = false;
    renderer.clear();
    camera.layers.set(FAR);
    camera.near = 16; camera.far = st.lod * 1.3 + 2000;
    camera.updateProjectionMatrix();
    renderer.shadowMap.autoUpdate = false;
    renderer.render(scene, camera);
    renderer.clearDepth();
    camera.layers.set(0);
    // the second pass must still reach level 0 even from high up
    camera.near = near;
    camera.far = Math.max(f, Math.hypot(lodRanges()[0] * 512 + 512, camera.position.y - 64) * 1.2 + 200);
    camera.updateProjectionMatrix();
    renderer.shadowMap.autoUpdate = true;
    // a background color would paint over the first pass again
    const bg = scene.background;
    scene.background = null;
    renderer.render(scene, camera);
    scene.background = bg;
    renderer.autoClear = true;
    camera.far = f;
    camera.updateProjectionMatrix();
  }
  function render() {
    const ox = Math.round(camera.position.x / ORIGIN_STEP) * ORIGIN_STEP;
    const oz = Math.round(camera.position.z / ORIGIN_STEP) * ORIGIN_STEP;
    if (ox || oz) {
      scene.position.set(-ox, 0, -oz);
      camera.position.x -= ox; camera.position.z -= oz;
    }
    renderPasses();
    if (!ox && !oz) return;
    camera.position.x += ox; camera.position.z += oz;
    // Back, so clicks (raycaster) compute in world coordinates again
    scene.position.set(0, 0, 0);
    scene.updateMatrixWorld();
    camera.updateMatrixWorld();
  }

  function resize() {
    const w = container.clientWidth, h = container.clientHeight;
    renderer.setSize(w, h);
    camera.aspect = w / Math.max(1, h);
    camera.updateProjectionMatrix();
  }
  window.addEventListener('resize', resize);

  // ------------------------------------------------------------ Fancy graphics (shader): sun, shadows, sky

  const sun = new THREE.DirectionalLight(0xfff1d6, 2.2);
  sun.castShadow = true;
  sun.shadow.mapSize.set(2048, 2048);
  Object.assign(sun.shadow.camera, { left: -110, right: 110, top: 110, bottom: -110, near: 1, far: 900 });
  sun.shadow.bias = -0.0004;
  sun.shadow.normalBias = 0.04;
  const hemi = new THREE.HemisphereLight(0xbfd8ff, 0x5a4a38, 1.0);
  scene.add(sun, sun.target, hemi);
  sun.layers.enable(FAR); hemi.layers.enable(FAR);
  sun.visible = hemi.visible = false;

  // Sky as a dome around the camera: gradient horizon -> zenith, sun disc, sunset glow
  const skyUniforms = {
    uSun: { value: new THREE.Vector3(0, 1, 0) }, uZenith: { value: new THREE.Color() },
    uHorizon: { value: new THREE.Color() }, uGlow: { value: new THREE.Color() },
  };
  const skyDome = new THREE.Mesh(new THREE.SphereGeometry(1, 32, 16), new THREE.ShaderMaterial({
    uniforms: skyUniforms, side: THREE.BackSide, depthWrite: false, depthTest: false, fog: false,
    vertexShader: 'varying vec3 vDir; void main() { vDir = position; vec4 p = modelViewMatrix * vec4(position, 1.0); gl_Position = projectionMatrix * p; gl_Position.z = gl_Position.w; }',
    fragmentShader: `uniform vec3 uSun; uniform vec3 uZenith; uniform vec3 uHorizon; uniform vec3 uGlow; varying vec3 vDir;
      void main() {
        vec3 d = normalize(vDir);
        float h = max(d.y, 0.0);
        vec3 col = mix(uHorizon, uZenith, pow(h, 0.55));
        float s = max(dot(d, normalize(uSun)), 0.0);
        col += uGlow * pow(s, 6.0) * (1.0 - h) * 0.9;            // Leuchten um die Sonne am Horizont
        col += vec3(1.0, 0.95, 0.85) * smoothstep(0.9993, 0.9997, s); // Sonnenscheibe
        if (d.y < 0.0) col = mix(uHorizon, uHorizon * 0.55, min(-d.y * 3.0, 1.0));
        gl_FragColor = vec4(col, 1.0);
      }`,
  }));
  skyDome.frustumCulled = false;
  skyDome.layers.set(FAR);
  skyDome.renderOrder = -1;
  skyDome.visible = false;
  scene.add(skyDome);

  const tmpC = new THREE.Color();
  function updateLighting() {
    // Hour 6 = sunrise (east, +x), 12 = noon, 18 = sunset
    const ang = ((st.hour - 6) / 12) * Math.PI;
    const dir = new THREE.Vector3(Math.cos(ang), Math.sin(ang), 0.35).normalize();
    const day = THREE.MathUtils.clamp(dir.y * 3 + 0.25, 0, 1);       // 0 night .. 1 day
    const dusk = THREE.MathUtils.clamp(1 - Math.abs(dir.y) * 4, 0, 1); // evening/morning glow
    skyUniforms.uSun.value.copy(dir.y > -0.2 ? dir : dir.clone().negate());
    skyUniforms.uZenith.value.setRGB(0.10, 0.12, 0.25).lerp(tmpC.setRGB(0.28, 0.52, 0.95), day);
    skyUniforms.uHorizon.value.setRGB(0.05, 0.06, 0.12).lerp(tmpC.setRGB(0.70, 0.82, 0.98), day).lerp(tmpC.setRGB(0.98, 0.58, 0.35), dusk * 0.6);
    skyUniforms.uGlow.value.setRGB(1.0, 0.55, 0.25).multiplyScalar(dusk + 0.15 * day);
    sun.color.setRGB(1.0, 0.93, 0.82).lerp(tmpC.setRGB(1.0, 0.55, 0.3), dusk * 0.7);
    sun.intensity = 2.4 * THREE.MathUtils.clamp(dir.y * 4, 0, 1);
    hemi.intensity = 1.7 - 0.5 * day;   // everything stays recognizable at night (moonlight)
    hemi.color.setRGB(0.62, 0.70, 0.95).lerp(tmpC.setRGB(0.75, 0.85, 1.0), day);
    sun.userData.dir = dir;
    lightUniforms.uDay.value = day;
    if (st.fancy) {
      scene.background = null;
      if (scene.fog) scene.fog.color.copy(skyUniforms.uHorizon.value);
    }
  }

  function placeSun() {
    if (!st.fancy) return;
    const d = sun.userData.dir || new THREE.Vector3(0, 1, 0);
    // The shadow camera follows the camera, snapped to texels so shadows don't shimmer
    const step = 220 / 2048;
    const cx = Math.round(camera.position.x / step) * step, cz = Math.round(camera.position.z / step) * step;
    const cy = Math.round(camera.position.y);
    sun.target.position.set(cx, cy - 20, cz);
    sun.position.set(cx + d.x * 400, cy - 20 + d.y * 400, cz + d.z * 400);
    skyDome.position.copy(camera.position);
    skyDome.scale.setScalar(camera.far * 0.9);
  }

  function setFancy(on) {
    st.fancy = on;
    renderer.shadowMap.enabled = on;
    renderer.shadowMap.type = THREE.PCFSoftShadowMap;
    renderer.toneMapping = on ? THREE.ACESFilmicToneMapping : THREE.NoToneMapping;
    renderer.toneMappingExposure = 1.25;
    sun.visible = hemi.visible = skyDome.visible = on;
    const mats = chunkMats();
    for (const c of st.chunks.values()) {
      if (c.group) c.group.children.forEach((m) => { m.material = mats[m.userData.pass]; });
    }
    // Rebuild the far view with the matching material
    for (const k of Array.from(st.regions.keys())) dropRegion(k);
    for (const k of Array.from(farTiles.keys())) dropFar(k);
    for (const m of clipped) m.needsUpdate = true;
    setSky();
  }

  // ------------------------------------------------------------ Layers (Y cut)

  function setClip(mode, y) {
    const changed = mode !== clip.mode;
    clip.mode = mode; clip.y = y;
    clip.top.constant = y + 1.02;   // keeps all blocks with Y <= cut (top edge y+1)
    clip.bottom.constant = -y + 0.02;                           // single level only: everything with Y >= y
    // Immediately: clipping in the browser (cut face still hollow). Shortly after: meshes with
    // a closed cut face from the server.
    clearTimeout(clip.timer);
    clip.timer = setTimeout(() => {
      clip.query = mode === 'off' ? '' : mode === 'layer' ? `&ylo=${y}&yhi=${y}` : `&yhi=${y}`;
      for (const c of st.chunks.values()) if (c.group || c.empty) { c.stale = true; c.empty = false; }
    }, 450);
    if (!changed) return;
    clip.planes = mode === 'off' ? [] : mode === 'layer' ? [clip.top, clip.bottom] : [clip.top];
    for (const m of clipped) { m.clippingPlanes = clip.planes; m.needsUpdate = true; }
  }

  function setSky() {
    const c = xray.on ? XRAY_BG : SKY[st.dim] ?? SKY.overworld;
    const near = st.radius * 16;
    const far = lodOn() ? Math.max(near, st.lod) : near;
    // without the wide levels one pass reaches the view distance, otherwise only up to level 0
    const nearFar = lodRanges()[1] > 0 ? Math.max(near, lodRanges()[0] * 512 + 512) : far;
    const fancySky = st.fancy && !xray.on && st.dim !== 'the_nether' && st.dim !== 'the_end';
    scene.background = fancySky ? null : new THREE.Color(c);
    skyDome.visible = fancySky;
    const fogCol = fancySky ? skyUniforms.uHorizon.value.clone() : new THREE.Color(c);
    // Fancy: light haze in the distance instead of a hard edge
    scene.fog = st.fog ? new THREE.Fog(fogCol, near * 0.55, near * 0.98)
      : fancySky ? new THREE.Fog(fogCol, far * 0.35, far * 1.05) : new THREE.Fog(fogCol, far * 0.8, far * 1.05);
    camera.far = nearFar * 1.2 + 200;
    camera.updateProjectionMatrix();
    for (const r of st.regions.values()) if (r.mesh) r.mesh.visible = lodOn();
    for (const t of farTiles.values()) if (t.mesh) t.mesh.visible = lodOn();
    updateLighting();
  }

  // ------------------------------------------------------------ Public API

  return {
    open(srv, dim, x, y, z) {
      if (srv !== st.srv || dim !== st.dim) {
        for (const k of Array.from(st.chunks.keys())) dropChunk(k);
        for (const k of Array.from(st.regions.keys())) dropRegion(k);
        for (const k of Array.from(farTiles.keys())) dropFar(k);
        st.srv = srv; st.dim = dim;
        setSchematics([]);
      }
      if (st.cam === 'free') {
        camera.position.set(x, y, z + 24);
        st.yaw = 0; st.pitch = -0.6;
      }
      st.active = true;
      st.since = Date.now() / 1000;
      setSky();
      resize();
      canvas.focus();
    },
    close() {
      st.active = false;
      if (document.pointerLockElement === canvas) document.exitPointerLock();
      st.keys.clear();
    },
    position() { return { x: camera.position.x, y: camera.position.y, z: camera.position.z }; },
    lookTarget(dist = 12) {
      ray.setFromCamera(new THREE.Vector2(0, 0), camera);
      return ray.ray.at(dist, new THREE.Vector3());
    },
    setRadius(r) { st.radius = r; setSky(); },
    setFancy,
    setHour(h) { st.hour = h; updateLighting(); },
    setClip,
    setLod(blocks) { st.lod = blocks; setSky(); },
    setFog(f) { st.fog = f; setSky(); },
    /* Timelapse: chunks as they were at the start of that day (null = today). The old meshes stay
     * until the new ones are there, so playing it back does not flicker. */
    setAt(date) {
      date = date || null;
      if (st.at === date) return;
      st.at = date;
      for (const c of st.chunks.values()) if (c.group || c.empty) { c.stale = true; c.empty = false; }
    },
    // still loading chunks near the camera (the timelapse waits for this before the next step)
    busy() {
      if (st.queue > 0 || pending.length) return true;
      const ccx = Math.floor(camera.position.x / 16), ccz = Math.floor(camera.position.z / 16);
      for (const [key, c] of st.chunks) {
        if (!c.stale && !c.loading) continue;
        const [x, z] = key.split(',').map(Number);
        if ((x - ccx) ** 2 + (z - ccz) ** 2 <= st.radius * st.radius) return true;
      }
      return false;
    },
    canvas() { return renderer.domElement; },
    setFull(f) { if (st.full !== f) { st.full = f; for (const c of st.chunks.values()) if (c.group || c.empty) { c.stale = true; c.empty = false; } } },
    updatePlayer: player.update,
    setCam,
    cam() { return st.cam; },
    gotoPlayer(p) {
      if (!p) return;
      st.cam = 'free';
      camera.position.set(p.x - Math.sin(st.yaw) * 6, p.y + 4, p.z + Math.cos(st.yaw) * 6);
    },
    setCamera(x, y, z, yawDeg = 0, pitchDeg = 0) {
      st.cam = 'free';
      camera.position.set(x, y, z);
      st.yaw = -THREE.MathUtils.degToRad(yawDeg) + Math.PI; st.pitch = -THREE.MathUtils.degToRad(pitchDeg);
    },
    flyTo(x, y, z) {
      st.cam = 'free';
      camera.position.set(x, y + 20, z + 30);
      st.yaw = 0; st.pitch = -0.55;
    },
    setSchematics,
    setVerify,
    setDiff,
    setSpawn,
    setXray,
    xrayNext,
    setBlockPick,
    setArea,
    setOthers,
    setWaypoints,
    setTrail,
    select,
    selected() { return st.selected; },
    screenshot() { return renderer.domElement.toDataURL('image/png'); },
    stats() {
      return { chunks: Array.from(st.chunks.values()).filter((c) => c.group).length,
        regions: Array.from(st.regions.values()).filter((r) => r.mesh).length, loading: st.queue,
        active: st.active, srv: st.srv, dim: st.dim, atlas: atlasW, pending: pending.length,
        cam: [Math.round(camera.position.x), Math.round(camera.position.y), Math.round(camera.position.z)] };
    },
    debugMasks() {
      return Array.from(st.regions.entries()).map(([k, r]) => [k, r.mask ? r.mask.image.data.reduce((a, b) => a + (b ? 1 : 0), 0) : -1]);
    },
  };
}
