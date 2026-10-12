/* Webmatica – Schematic-Editor.
 *
 * Eigene Seite mit nur der Schematic: drehen/zoomen wie in einem 3D-Programm,
 * Bloecke setzen und abbauen wie im Spiel, Bereiche fuellen/ersetzen. Jede
 * Aenderung geht sofort an den Server (viewer/editor.py), der die .schem-Datei
 * speichert; danach werden Blockdaten und Mesh neu geladen.
 *
 * Treffer per Voxel-Strahl (DDA) ueber die Blockdaten, nicht ueber das Mesh:
 * genau, schnell und unabhaengig von Blockformen.
 */
import * as THREE from '/static/vendor/three/three.module.min.js';

THREE.ColorManagement.enabled = false;

const $ = (s) => document.querySelector(s);
const $$ = (s) => Array.from(document.querySelectorAll(s));
const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const fmt = (n) => Math.round(n).toLocaleString(I18N.locale);
const pretty = (id) => id.replace('minecraft:', '').replace(/\[.*$/, '').replace(/_/g, ' ');
const baseOf = (st) => st.replace(/\[.*$/, '');
const AIR = new Set(['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:structure_void']);

async function api(path, opts) {
  const r = await fetch(path, opts);
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || r.status + ' ' + r.statusText);
  return j;
}
const post = (path, body) => api(path, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });

let toastTimer = null;
function toast(text) {
  const t = $('#toast');
  t.textContent = text;
  t.classList.add('show');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => t.classList.remove('show'), 3200);
}

const params = new URLSearchParams(location.search);
const ed = {
  file: params.get('file'),
  pid: params.get('pid'),
  srv: params.get('srv'),
  dim: params.get('dim'),
  origin: params.get('origin') ? params.get('origin').split(',').map(Number) : null,
  size: [1, 1, 1],
  palette: [],
  data: new Uint16Array(0),
  airIdx: new Set(),
  tool: 'place',
  boxA: null, boxB: null,
  busy: false,
  version: 0,
};

// ------------------------------------------------------------------ Szene

const view = $('#ed-view');
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2));
renderer.localClippingEnabled = true;
view.appendChild(renderer.domElement);
const scene = new THREE.Scene();
scene.background = new THREE.Color(0x1b1f27);
const camera = new THREE.PerspectiveCamera(60, 1, 0.05, 4000);

const atlas = new THREE.Texture();
atlas.magFilter = THREE.NearestFilter;
atlas.minFilter = THREE.NearestFilter;
atlas.generateMipmaps = false;
atlas.flipY = false;
atlas.colorSpace = THREE.NoColorSpace;
const clipPlane = new THREE.Plane(new THREE.Vector3(0, -1, 0), 1e6);
const clipList = [];
const mats = [
  new THREE.MeshBasicMaterial({ map: atlas, vertexColors: true, clippingPlanes: clipList }),
  new THREE.MeshBasicMaterial({ map: atlas, vertexColors: true, alphaTest: 0.5, side: THREE.DoubleSide, clippingPlanes: clipList }),
  new THREE.MeshBasicMaterial({ map: atlas, vertexColors: true, transparent: true, depthWrite: false, side: THREE.DoubleSide, opacity: 0.8, clippingPlanes: clipList }),
];
fetch('/api/blocks-info').then((r) => r.json()).then((j) => {
  if (!j.available) { toast('3D braucht die Blockmodelle (build_assets)'); return; }
  const img = new Image();
  img.onload = () => { atlas.image = img; atlas.repeat.set(1 / (16 * j.width), 1 / (16 * j.height)); atlas.needsUpdate = true; };
  img.src = `/api/blocks.png?v=${encodeURIComponent(j.version || '')}`;
});

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
  return index.subarray(0, quads * 6);
}
function parseMesh(buf) {
  const dv = new DataView(buf);
  if (buf.byteLength < 12) return [];
  let o = 12;
  const out = [];
  for (let p = 0; p < 3; p++) {
    const n = dv.getUint32(o, true); o += 4;
    if (!n) { out.push(null); continue; }
    const pos16 = new Int16Array(buf.slice(o, o + n * 24)); o += n * 24;
    const uv16 = new Uint16Array(buf.slice(o, o + n * 16)); o += n * 16;
    const rgb = new Uint8Array(buf.slice(o, o + n * 12)); o += n * 12;
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(pos16, 3));
    g.setAttribute('uv', new THREE.BufferAttribute(uv16, 2));
    g.setAttribute('color', new THREE.BufferAttribute(rgb, 3, true));
    g.setIndex(new THREE.BufferAttribute(indexFor(n), 1));
    g.computeBoundingSphere();
    out.push(g);
  }
  return out;
}

const blockGroup = new THREE.Group();
blockGroup.scale.setScalar(1 / 16);
scene.add(blockGroup);
const helpers = new THREE.Group();
scene.add(helpers);
const ghosts = new THREE.Group();
scene.add(ghosts);

const hoverBox = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(1.01, 1.01, 1.01)),
  new THREE.LineBasicMaterial({ color: 0xffffff, depthTest: false, transparent: true, opacity: 0.9 }));
hoverBox.renderOrder = 10;
hoverBox.visible = false;
scene.add(hoverBox);
const placeGhost = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshBasicMaterial({ color: 0x22a06b, transparent: true, opacity: 0.35, depthWrite: false }));
placeGhost.visible = false;
scene.add(placeGhost);
const pasteBox = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(1, 1, 1)),
  new THREE.LineBasicMaterial({ color: 0x22e08b, depthTest: false }));
pasteBox.renderOrder = 12;
pasteBox.visible = false;
scene.add(pasteBox);
const selBox = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(1, 1, 1)),
  new THREE.LineBasicMaterial({ color: 0xffd23f, depthTest: false }));
selBox.renderOrder = 11;
selBox.visible = false;
scene.add(selBox);

function rebuildHelpers() {
  helpers.children.slice().forEach((c) => { helpers.remove(c); c.geometry.dispose(); });
  const [W, H, L] = ed.size;
  const box = new THREE.LineSegments(new THREE.EdgesGeometry(new THREE.BoxGeometry(W, H, L)),
    new THREE.LineBasicMaterial({ color: 0x7fd1ff, transparent: true, opacity: 0.5 }));
  box.position.set(W / 2, H / 2, L / 2);
  helpers.add(box);
  // Raster auf der Bauebene (Boden oder gewaehlte Hoehe)
  const n = Math.max(W, L) + 16;
  const grid = new THREE.GridHelper(n, n, 0x3a4250, 0x2a303b);
  grid.position.set(W / 2 + (n % 2 ? 0.5 : 0), planeY(), L / 2 + (n % 2 ? 0.5 : 0));
  helpers.add(grid);
}

// ------------------------------------------------------------------ Kamera (Orbit)

const orbit = { target: new THREE.Vector3(), dist: 30, yaw: Math.PI * 0.75, pitch: 0.6 };
function updateCamera() {
  const cp = Math.cos(orbit.pitch);
  camera.position.set(
    orbit.target.x + orbit.dist * cp * Math.sin(orbit.yaw),
    orbit.target.y + orbit.dist * Math.sin(orbit.pitch),
    orbit.target.z + orbit.dist * cp * Math.cos(orbit.yaw));
  camera.lookAt(orbit.target);
}
function frame() {
  const [W, H, L] = ed.size;
  orbit.target.set(W / 2, Math.min(H, 8) / 2, L / 2);
  orbit.dist = Math.max(12, Math.max(W, H, L) * 1.6);
  updateCamera();
}
function resize() {
  const w = view.clientWidth, h = view.clientHeight;
  renderer.setSize(w, h);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
}
window.addEventListener('resize', resize);
resize();
renderer.setAnimationLoop(() => renderer.render(scene, camera));

// ------------------------------------------------------------------ Blockdaten

const idxOf = (x, y, z) => (y * ed.size[2] + z) * ed.size[0] + x;
function inside(x, y, z) { return x >= 0 && y >= 0 && z >= 0 && x < ed.size[0] && y < ed.size[1] && z < ed.size[2]; }
function stateAt(x, y, z) { return inside(x, y, z) ? ed.palette[ed.data[idxOf(x, y, z)]] : 'minecraft:air'; }
function solid(x, y, z) {
  if (!inside(x, y, z)) return false;
  if ($('#ed-cut').checked && y > cutY()) return false;
  return !ed.airIdx.has(ed.data[idxOf(x, y, z)]);
}
const cutY = () => Number($('#ed-cuty').value);
const planeY = () => ($('#ed-plane').checked ? cutY() : 0);

async function loadAll(keepCamera) {
  const v = ++ed.version;
  const [vox, meshBuf] = await Promise.all([
    api('/api/editor/voxels?' + new URLSearchParams({ file: ed.file })),
    fetch('/api/editor/mesh?' + new URLSearchParams({ file: ed.file, v })).then((r) => (r.ok ? r.arrayBuffer() : new ArrayBuffer(0))),
  ]);
  if (v !== ed.version) return;
  const sizeChanged = vox.size.join() !== ed.size.join();
  ed.size = vox.size;
  ed.palette = vox.palette;
  ed.airIdx = new Set(vox.palette.map((p, i) => (AIR.has(baseOf(p)) ? i : -1)).filter((i) => i >= 0));
  const raw = atob(vox.data);
  const bytes = new Uint8Array(raw.length);
  for (let i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
  ed.data = new Uint16Array(bytes.buffer);
  blockGroup.children.slice().forEach((m) => { blockGroup.remove(m); m.geometry.dispose(); });
  parseMesh(meshBuf).forEach((g, i) => { if (g) blockGroup.add(new THREE.Mesh(g, mats[i])); });
  ghosts.children.slice().forEach((m) => ghosts.remove(m));
  const H = ed.size[1];
  const slider = $('#ed-cuty');
  const wasTop = Number(slider.value) >= Number(slider.max);
  slider.max = H;
  if (wasTop || Number(slider.value) > H) slider.value = H;
  updateCut();
  $('#ed-size').textContent = `${ed.size[0]} × ${ed.size[1]} × ${ed.size[2]}`;
  if (sizeChanged) rebuildHelpers();
  if (!keepCamera) frame();
  renderCounts();
}

function renderCounts() {
  const counts = new Uint32Array(ed.palette.length);
  for (let i = 0; i < ed.data.length; i++) counts[ed.data[i]]++;
  const rows = ed.palette.map((p, i) => [p, counts[i]]).filter(([p, n]) => n && !AIR.has(baseOf(p)));
  const merged = new Map();
  for (const [p, n] of rows) {
    const b = baseOf(p);
    const e = merged.get(b) || { n: 0, state: p };
    e.n += n;
    merged.set(b, e);
  }
  const list = Array.from(merged.entries()).sort((a, b) => b[1].n - a[1].n);
  $('#ed-countsum').textContent = `(${fmt(list.reduce((a, [, e]) => a + e.n, 0))} Blöcke)`;
  $('#ed-counts').innerHTML = list.map(([b, e]) => `<div data-state="${esc(e.state)}" title="Als Block wählen">${icon(b, 20)} ${esc(pretty(b))}<span class="n">${fmt(e.n)}</span><button class="rp" data-rfrom="${esc(b)}" title="Alle ${esc(pretty(b))} ersetzen …">⇄</button><button class="rp del" data-rdel="${esc(b)}" title="Alle ${esc(pretty(b))} löschen">✕</button></div>`).join('');
  ed.countMap = new Map(list.map(([b, e]) => [b, e.n]));
  const rf = $('#ed-rfrom');
  const rkeep = rf.value;
  rf.innerHTML = list.map(([b, e]) => `<option value="${esc(b)}">${esc(pretty(b))} (${fmt(e.n)})</option>`).join('');
  if (rkeep && ed.countMap.has(rkeep)) rf.value = rkeep;
  renderReplace();
  const from = $('#ed-from');
  const keep = from.value;
  from.innerHTML = '<option value="*">alles außer Luft</option><option value="minecraft:air">Luft</option>' +
    list.map(([b]) => `<option value="${esc(b)}">${esc(pretty(b))}</option>`).join('');
  if (keep && Array.from(from.options).some((o) => o.value === keep)) from.value = keep;
}

// ------------------------------------------------------------------ Strahl

const ray = new THREE.Raycaster();
function castRay(cx, cy) {
  const rect = renderer.domElement.getBoundingClientRect();
  ray.setFromCamera(new THREE.Vector2(((cx - rect.left) / rect.width) * 2 - 1, -((cy - rect.top) / rect.height) * 2 + 1), camera);
  const o = ray.ray.origin, d = ray.ray.direction;
  // Voxel-DDA
  let x = Math.floor(o.x), y = Math.floor(o.y), z = Math.floor(o.z);
  const sx = Math.sign(d.x), sy = Math.sign(d.y), sz = Math.sign(d.z);
  const tdx = Math.abs(1 / d.x), tdy = Math.abs(1 / d.y), tdz = Math.abs(1 / d.z);
  let tx = d.x ? ((sx > 0 ? x + 1 - o.x : o.x - x) * tdx) : Infinity;
  let ty = d.y ? ((sy > 0 ? y + 1 - o.y : o.y - y) * tdy) : Infinity;
  let tz = d.z ? ((sz > 0 ? z + 1 - o.z : o.z - z) * tdz) : Infinity;
  let normal = [0, 0, 0], t = 0, hit = null;
  const maxT = orbit.dist * 4 + 600;
  for (let i = 0; i < 4000 && t < maxT; i++) {
    if (solid(x, y, z)) { hit = { pos: [x, y, z], normal, t }; break; }
    if (tx < ty && tx < tz) { x += sx; t = tx; tx += tdx; normal = [-sx, 0, 0]; }
    else if (ty < tz) { y += sy; t = ty; ty += tdy; normal = [0, -sy, 0]; }
    else { z += sz; t = tz; tz += tdz; normal = [0, 0, -sz]; }
  }
  // Bauebene (Boden oder gewaehlte Hoehe), nur von oben
  const py = planeY();
  if (d.y < 0 && o.y > py) {
    const tp = (py - o.y) / d.y;
    if (!hit || tp < hit.t) {
      const px = Math.floor(o.x + d.x * tp), pz = Math.floor(o.z + d.z * tp);
      return { pos: null, place: [px, py, pz], normal: [0, 1, 0], point: [o.x + d.x * tp, py, o.z + d.z * tp], t: tp };
    }
  }
  if (!hit) return null;
  hit.point = [o.x + d.x * hit.t, o.y + d.y * hit.t, o.z + d.z * hit.t];
  hit.place = [hit.pos[0] + hit.normal[0], hit.pos[1] + hit.normal[1], hit.pos[2] + hit.normal[2]];
  return hit;
}

// ------------------------------------------------------------------ Bloecke waehlen

let BLOCKS = [];          // [{ id, props, default }]
const META = new Map();
let itemsMeta = null;
const hotbar = { slots: load('ed.hotbar', ['minecraft:stone', 'minecraft:oak_planks', 'minecraft:glass', 'minecraft:obsidian',
  'minecraft:oak_log', 'minecraft:stone_bricks', 'minecraft:oak_stairs', 'minecraft:smooth_stone_slab', 'minecraft:torch']), sel: 0 };
let picked = null;        // genauer Zustand von der Pipette

function load(k, d) { try { const v = JSON.parse(localStorage.getItem(k)); return v || d; } catch (_) { return d; } }
function save(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (_) { /* egal */ } }

function icon(id, size = 32) {
  const i = itemsMeta && itemsMeta.index[baseOf(id)];
  if (i == null) return `<span class="ico none" style="width:${size}px;height:${size}px"></span>`;
  const x = (i % itemsMeta.cols) * size, y = Math.floor(i / itemsMeta.cols) * size;
  return `<span class="ico" style="width:${size}px;height:${size}px;background-position:-${x}px -${y}px;background-size:${itemsMeta.cols * size}px ${itemsMeta.rows * size}px"></span>`;
}
const current = () => hotbar.slots[hotbar.sel];

function setCurrent(id, exactState) {
  hotbar.slots[hotbar.sel] = baseOf(id);
  picked = exactState && exactState.includes('[') ? exactState : null;
  save('ed.hotbar', hotbar.slots);
  renderHotbar();
  renderPalette();
}
function renderHotbar() {
  $('#ed-hotbar').innerHTML = hotbar.slots.map((id, i) => `<div class="h ${i === hotbar.sel ? 'sel' : ''}" data-slot="${i}" title="${esc(pretty(id))}"><i>${i + 1}</i>${icon(id, 36)}</div>`).join('');
  const id = current();
  $('#ed-current').innerHTML = `${icon(id, 32)}<div>${esc(pretty(id))}<small>${esc(picked || id)}</small></div>`;
  renderReplace();
}
function renderPalette() {
  const q = $('#ed-search').value.trim().toLowerCase().replace(/ /g, '_');
  const list = BLOCKS.filter((b) => !q || b.id.includes(q)).slice(0, 600);
  $('#ed-blocks').innerHTML = list.map((b) => `<div class="b ${b.id === current() ? 'sel' : ''}" data-id="${esc(b.id)}" title="${esc(pretty(b.id))}">${icon(b.id, 32)}</div>`).join('');
}
$('#ed-search').addEventListener('input', renderPalette);
$('#ed-blocks').addEventListener('click', (e) => { const b = e.target.closest('[data-id]'); if (b) setCurrent(b.dataset.id); });
$('#ed-hotbar').addEventListener('click', (e) => { const h = e.target.closest('[data-slot]'); if (h) { hotbar.sel = Number(h.dataset.slot); picked = null; renderHotbar(); renderPalette(); } });
$('#ed-counts').addEventListener('click', (e) => {
  const rd = e.target.closest('[data-rdel]');
  if (rd) { deleteAll(rd.dataset.rdel); return; }
  const rp = e.target.closest('[data-rfrom]');
  if (rp) { $('#ed-rfrom').value = rp.dataset.rfrom; renderReplace(); $('#ed-rfrom').scrollIntoView({ block: 'nearest', behavior: 'smooth' }); return; }
  const r = e.target.closest('[data-state]');
  if (r) setCurrent(r.dataset.state, r.dataset.state);
});

/* Alle Bloecke einer Sorte in der ganzen Schematic durch den gewaehlten ersetzen */
function renderReplace() {
  const id = current();
  const from = $('#ed-rfrom').value;
  $('#ed-rto').innerHTML = id ? `${icon(id, 20)} <b>${esc(pretty(id))}</b>` : '<span class="muted">erst einen Block wählen</span>';
  const n = ed.countMap ? ed.countMap.get(from) || 0 : 0;
  const same = id && from && baseOf(id) === from;
  $('#ed-rgo').disabled = !from || !id || same || !n;
  $('#ed-rgo').textContent = n ? `${fmt(n)} ${n === 1 ? 'Block' : 'Blöcke'} ersetzen` : 'Ersetzen';
  $('#ed-rdel').disabled = !from || !n || from === 'minecraft:air';
  $('#ed-rdel').textContent = n ? `${fmt(n)} löschen` : 'Alle löschen';
}
/* Alle Bloecke einer Sorte entfernen (= durch Luft ersetzen) */
function deleteAll(from) {
  if (!from || from === 'minecraft:air') return;
  const [W, H, L] = ed.size;
  const n = ed.countMap ? ed.countMap.get(from) || 0 : 0;
  edit([{ op: 'replace', box: [0, 0, 0, W - 1, H - 1, L - 1], from, block: 'minecraft:air' }]);
  toast(`${fmt(n)} × ${pretty(from)} gelöscht (Strg+Z = rückgängig)`);
}
$('#ed-rdel').addEventListener('click', () => deleteAll($('#ed-rfrom').value));
$('#ed-rfrom').addEventListener('change', renderReplace);
$('#ed-rgo').addEventListener('click', () => {
  const id = current();
  const from = $('#ed-rfrom').value;
  if (!id || !from) return;
  const block = picked || (META.get(id) ? META.get(id).default : id);
  const [W, H, L] = ed.size;
  edit([{ op: 'replace', box: [0, 0, 0, W - 1, H - 1, L - 1], from, block, keep: $('#ed-rkeep').checked }]);
});

// ------------------------------------------------------------------ Ausrichtung beim Setzen

const DIRS = { north: [0, 0, -1], south: [0, 0, 1], east: [1, 0, 0], west: [-1, 0, 0], up: [0, 1, 0], down: [0, -1, 0] };
const OPP = { north: 'south', south: 'north', east: 'west', west: 'east', up: 'down', down: 'up' };
function dirOfNormal(n) {
  for (const [k, v] of Object.entries(DIRS)) if (v[0] === n[0] && v[1] === n[1] && v[2] === n[2]) return k;
  return 'up';
}
function lookDir() {
  const f = new THREE.Vector3();
  camera.getWorldDirection(f);
  return Math.abs(f.x) > Math.abs(f.z) ? (f.x > 0 ? 'east' : 'west') : (f.z > 0 ? 'south' : 'north');
}
// Bloecke, deren "facing" in Blickrichtung zeigt (wie im Spiel); sonst schauen sie dich an
const FACES_AWAY = /stairs|_door|trapdoor|fence_gate|_bed|repeater|comparator|anvil|lectern|bell|campfire|stonecutter|loom|beehive|bee_nest/;

function stateFor(hit) {
  const id = current();
  if (picked && $('#ed-dir').value === 'auto' && $('#ed-half').value === 'auto') return picked;
  const m = META.get(id);
  if (!m) return id;
  const props = {};
  const def = m.default.includes('[') ? m.default.slice(m.default.indexOf('[') + 1, -1).split(',') : [];
  for (const kv of def) { const [k, v] = kv.split('='); props[k] = v; }
  const has = (k, v) => m.props[k] && (v == null || m.props[k].includes(v));
  const dsel = $('#ed-dir').value, hsel = $('#ed-half').value;
  const n = hit.normal;
  if (has('facing')) {
    let f;
    if (dsel !== 'auto') f = dsel;
    else if (has('facing', 'up')) f = dirOfNormal(n);
    else f = FACES_AWAY.test(id) ? lookDir() : OPP[lookDir()];
    if (has('facing', f)) props.facing = f;
  }
  if (has('axis')) {
    const a = dsel === 'auto' ? (n[0] ? 'x' : n[2] ? 'z' : 'y') : (dsel === 'up' || dsel === 'down' ? 'y' : dsel === 'east' || dsel === 'west' ? 'x' : 'z');
    if (has('axis', a)) props.axis = a;
  }
  let top = false;
  if (hsel !== 'auto') top = hsel === 'top';
  else if (n[1] < 0) top = true;
  else if (n[1] === 0 && hit.point) top = hit.point[1] - Math.floor(hit.point[1]) > 0.5;
  if (has('half', 'top') && has('half', 'bottom')) props.half = top ? 'top' : 'bottom';
  if (has('type', 'top') && has('type', 'bottom')) props.type = top ? 'top' : 'bottom';
  if (has('face', 'wall')) {
    if (n[1] > 0) props.face = 'floor';
    else if (n[1] < 0) props.face = 'ceiling';
    else { props.face = 'wall'; if (dsel === 'auto' && has('facing')) props.facing = dirOfNormal(n); }
  }
  // Fackeln/Schilder an der Wand
  if (n[1] === 0 && /(^|_)torch$|_sign$|_banner$|^minecraft:ladder$/.test(id) && dsel === 'auto') {
    const wall = id.replace(/torch$/, 'wall_torch').replace(/_sign$/, '_wall_sign').replace(/_banner$/, '_wall_banner');
    if (wall !== id || id === 'minecraft:ladder') {
      return `${wall}[facing=${dirOfNormal(n)}]`;
    }
  }
  const keys = Object.keys(props).sort();
  return keys.length ? `${id}[${keys.map((k) => `${k}=${props[k]}`).join(',')}]` : id;
}

// ------------------------------------------------------------------ Bearbeiten

async function edit(ops, ghostPts) {
  if (ed.busy) return;
  ed.busy = true;
  for (const p of ghostPts || []) {
    const g = placeGhost.clone();
    g.material = placeGhost.material;
    g.position.set(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
    g.visible = true;
    ghosts.add(g);
  }
  $('#ed-status').textContent = 'Ändert …';
  try {
    const r = await post('/api/editor/edit', { file: ed.file, ops });
    applyResult(r);
    await loadAll(true);
    $('#ed-status').textContent = `${fmt(r.changed)} ${r.changed === 1 ? 'Block' : 'Blöcke'} geändert`;
  } catch (e) {
    ghosts.children.slice().forEach((m) => ghosts.remove(m));
    toast('Fehler: ' + e.message);
    $('#ed-status').textContent = '';
  } finally { ed.busy = false; }
}
function applyResult(r) {
  const s = r.shift || [0, 0, 0];
  if (s.some(Boolean)) {
    // Schematic ist nach -x/-y/-z gewachsen: Kamera und Auswahl mitschieben, damit nichts springt
    orbit.target.x += s[0]; orbit.target.y += s[1]; orbit.target.z += s[2];
    updateCamera();
    for (const c of [ed.boxA, ed.boxB]) if (c) { c[0] += s[0]; c[1] += s[1]; c[2] += s[2]; }
    if (ed.clip) for (const i of [0, 1, 2]) { ed.clip.box[i] += s[i]; ed.clip.box[i + 3] += s[i]; }
    if (ed.origin) ed.origin = [ed.origin[0] - s[0], ed.origin[1] - s[1], ed.origin[2] - s[2]];
    drawSel();
  }
  setDirty(!!r.dirty);
  $('#ed-undo').disabled = !r.undo;
  $('#ed-redo').disabled = !r.redo;
}

function act(kind, hit, ev) {
  if (!hit) return;
  if (ed.pasting && kind !== 'break' && kind !== 'pick') { pasteAt(hit.place); return; }
  if (kind === 'pick') {
    if (!hit.pos) return;
    const st = stateAt(...hit.pos);
    if (AIR.has(baseOf(st))) return;
    setCurrent(st, st);
    toast(`Pipette: ${pretty(st)}`);
    return;
  }
  if (kind === 'break') {
    if (!hit.pos) return;
    edit([{ op: 'set', pts: [hit.pos], block: 'minecraft:air' }]);
    return;
  }
  if (kind === 'place') {
    const st = stateFor(hit);
    edit([{ op: 'set', pts: [hit.place], block: st }], [hit.place]);
    return;
  }
  if (kind === 'box') {
    const p = hit.pos && !ev.shiftKey ? hit.pos : hit.place;
    if (!ed.boxA || ed.boxB) { ed.boxA = p.slice(); ed.boxB = null; }
    else ed.boxB = p.slice();
    drawSel();
  }
}

function boxCoords() {
  const a = ed.boxA, b = ed.boxB || ed.boxA;
  return a ? [a[0], a[1], a[2], b[0], b[1], b[2]] : null;
}
function drawSel() {
  const c = boxCoords();
  $('#ed-boxpanel').hidden = ed.tool !== 'box';
  if (!c) { selBox.visible = false; $('#ed-boxinfo').textContent = 'Erste Ecke anklicken … (Shift: in die Luft daneben)'; return; }
  const lo = [Math.min(c[0], c[3]), Math.min(c[1], c[4]), Math.min(c[2], c[5])];
  const hi = [Math.max(c[0], c[3]), Math.max(c[1], c[4]), Math.max(c[2], c[5])];
  const sz = [hi[0] - lo[0] + 1, hi[1] - lo[1] + 1, hi[2] - lo[2] + 1];
  selBox.scale.set(sz[0] + 0.04, sz[1] + 0.04, sz[2] + 0.04);
  selBox.position.set(lo[0] + sz[0] / 2, lo[1] + sz[1] / 2, lo[2] + sz[2] / 2);
  selBox.visible = true;
  $('#ed-boxinfo').textContent = `${sz[0]} × ${sz[1]} × ${sz[2]} = ${fmt(sz[0] * sz[1] * sz[2])} Blöcke${ed.boxB ? '' : ' · zweite Ecke anklicken'}`;
}
$('#ed-boxpanel').addEventListener('click', (e) => {
  const b = e.target.closest('[data-boxop]');
  const c = boxCoords();
  if (!b || !c) return;
  const op = b.dataset.boxop;
  const id = current();
  const block = picked || (META.get(id) ? META.get(id).default : id);
  if (op === 'clear') edit([{ op: 'fill', box: c, block: 'minecraft:air' }]);
  else if (op === 'replace') edit([{ op: 'replace', box: c, from: $('#ed-from').value, block }]);
  else edit([{ op, box: c, block }]);
});
$('#ed-boxreset').addEventListener('click', () => { ed.boxA = ed.boxB = null; drawSel(); });

// Kopieren / Verschieben: Kasten haengt am Mauszeiger, Klick setzt ihn ab
function startPaste(move) {
  const c = boxCoords();
  if (!c || !ed.boxB) { toast('Erst einen Bereich mit zwei Ecken wählen'); return; }
  ed.clip = { box: c.slice(), move, rot: 0, mirror: false };
  ed.pasting = true;
  renderPaste();
}
function clipSize() {
  const c = ed.clip.box;
  const s = [Math.abs(c[3] - c[0]) + 1, Math.abs(c[4] - c[1]) + 1, Math.abs(c[5] - c[2]) + 1];
  return ed.clip.rot % 180 ? [s[2], s[1], s[0]] : s;
}
function renderPaste() {
  $('#ed-pastepanel').hidden = !ed.pasting;
  if (!ed.pasting) { pasteBox.visible = false; return; }
  const s = clipSize();
  $('#ed-pasteinfo').innerHTML = `<b>${ed.clip.move ? 'Verschieben' : 'Einfügen'}</b>: ${s.join(' × ')}, ${ed.clip.rot}°${ed.clip.mirror ? ', gespiegelt' : ''}<br><span class="muted">Klick setzt ab${ed.clip.move ? '' : ', beliebig oft'}</span>`;
}
function endPaste() { ed.pasting = false; renderPaste(); }
function pasteAt(p) {
  const op = { op: 'clone', box: ed.clip.box, to: p, rot: ed.clip.rot, mirror: ed.clip.mirror, move: ed.clip.move, air: $('#ed-pair').checked };
  const wasMove = ed.clip.move;
  edit([op]).then(() => {
    if (wasMove) {
      // Auswahl wandert mit
      const s = clipSize();
      ed.boxA = p.slice(); ed.boxB = [p[0] + s[0] - 1, p[1] + s[1] - 1, p[2] + s[2] - 1];
      drawSel();
      endPaste();
    }
  });
}
$('#ed-copy').addEventListener('click', () => startPaste(false));
$('#ed-move').addEventListener('click', () => startPaste(true));
$('#ed-prot').addEventListener('click', () => { ed.clip.rot = (ed.clip.rot + 90) % 360; renderPaste(); });
$('#ed-pmir').addEventListener('click', () => { ed.clip.mirror = !ed.clip.mirror; renderPaste(); });
$('#ed-pdone').addEventListener('click', endPaste);

function setTool(t) {
  ed.tool = t;
  $$('[data-tool]').forEach((b) => b.classList.toggle('on', b.dataset.tool === t));
  drawSel();
}
$$('[data-tool]').forEach((b) => b.addEventListener('click', () => setTool(b.dataset.tool)));

// ------------------------------------------------------------------ Maus und Tasten

const canvas = renderer.domElement;
let drag = null;
canvas.addEventListener('contextmenu', (e) => e.preventDefault());
canvas.addEventListener('pointerdown', (e) => {
  canvas.setPointerCapture(e.pointerId);
  drag = { x: e.clientX, y: e.clientY, button: e.button, moved: 0, shift: e.shiftKey };
});
canvas.addEventListener('pointermove', (e) => {
  if (drag) {
    const dx = e.clientX - drag.x, dy = e.clientY - drag.y;
    drag.moved += Math.abs(dx) + Math.abs(dy);
    drag.x = e.clientX; drag.y = e.clientY;
    if (drag.moved > 4 && (drag.button === 2 || drag.button === 1 || (drag.button === 0 && e.altKey))) {
      if (drag.shift || drag.button === 1) {
        // verschieben in der Bildebene
        const k = orbit.dist * 0.0015;
        const right = new THREE.Vector3().setFromMatrixColumn(camera.matrix, 0);
        const up = new THREE.Vector3().setFromMatrixColumn(camera.matrix, 1);
        orbit.target.addScaledVector(right, -dx * k).addScaledVector(up, dy * k);
      } else {
        orbit.yaw -= dx * 0.006;
        orbit.pitch = Math.max(-1.5, Math.min(1.5, orbit.pitch + dy * 0.006));
      }
      updateCamera();
    }
    return;
  }
  hover(e);
});
canvas.addEventListener('pointerup', (e) => {
  const d = drag;
  drag = null;
  if (!d || d.moved > 4) return;
  const hit = castRay(e.clientX, e.clientY);
  if (d.button === 2) act('break', hit, e);
  else if (d.button === 1 || (d.button === 0 && e.altKey)) act('pick', hit, e);
  else if (d.button === 0) act(ed.tool, hit, e);
  hover(e);
});
canvas.addEventListener('wheel', (e) => {
  e.preventDefault();
  orbit.dist = Math.max(2, Math.min(2000, orbit.dist * (e.deltaY < 0 ? 1 / 1.12 : 1.12)));
  updateCamera();
}, { passive: false });

function hover(e) {
  const hit = castRay(e.clientX, e.clientY);
  const tip = $('#ed-tip');
  if (!hit) { hoverBox.visible = placeGhost.visible = false; tip.hidden = true; return; }
  if (ed.pasting) {
    const s = clipSize(), q = hit.place;
    pasteBox.scale.set(s[0] + 0.04, s[1] + 0.04, s[2] + 0.04);
    pasteBox.position.set(q[0] + s[0] / 2, q[1] + s[1] / 2, q[2] + s[2] / 2);
    pasteBox.visible = true;
  }
  const showPlace = ed.tool === 'place' || ed.pasting || (ed.tool === 'box' && (!hit.pos || e.shiftKey));
  const p = showPlace ? hit.place : hit.pos;
  if (!p) { hoverBox.visible = placeGhost.visible = false; tip.hidden = true; return; }
  hoverBox.position.set(p[0] + 0.5, p[1] + 0.5, p[2] + 0.5);
  hoverBox.material.color.set(ed.tool === 'break' ? 0xff6b6b : 0xffffff);
  hoverBox.visible = true;
  placeGhost.visible = ed.tool === 'place' && !ed.pasting;
  placeGhost.position.copy(hoverBox.position);
  tip.hidden = false;
  tip.style.left = e.clientX + 16 + 'px';
  tip.style.top = e.clientY + 12 + 'px';
  const what = hit.pos ? pretty(stateAt(...hit.pos)) : 'Bauebene';
  tip.textContent = `${p[0]} ${p[1]} ${p[2]} · ${what}`;
}
canvas.addEventListener('pointerleave', () => { hoverBox.visible = placeGhost.visible = false; $('#ed-tip').hidden = true; });

document.addEventListener('keydown', (e) => {
  if (e.target.matches('input, select, textarea')) return;
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') { e.preventDefault(); saveFile(); return; }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); e.shiftKey ? redo() : undo(); return; }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'y') { e.preventDefault(); redo(); return; }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'c') { e.preventDefault(); startPaste(false); return; }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'x') { e.preventDefault(); startPaste(true); return; }
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'v') { e.preventDefault(); if (ed.clip) { ed.clip.move = false; ed.pasting = true; renderPaste(); } return; }
  if (ed.pasting && e.key.toLowerCase() === 'q') { ed.clip.rot = (ed.clip.rot + 90) % 360; renderPaste(); return; }
  if (ed.pasting && e.key.toLowerCase() === 'm') { ed.clip.mirror = !ed.clip.mirror; renderPaste(); return; }
  if (ed.pasting && e.key === 'Escape') { endPaste(); return; }
  if (/^[1-9]$/.test(e.key)) { hotbar.sel = Number(e.key) - 1; picked = null; renderHotbar(); renderPalette(); return; }
  const k = e.key.toLowerCase();
  if (k === 'b') setTool('place');
  else if (k === 'e') setTool('break');
  else if (k === 'i') setTool('pick');
  else if (k === 'r') setTool('box');
  else if (k === 'f') frame();
  else if (k === 'escape') { ed.boxA = ed.boxB = null; drawSel(); }
});

/* Speichern / Verwerfen: Bearbeitungen bleiben im Editor, bis gespeichert wird.
   Karte, Litematica und Download sehen nur den gespeicherten Stand. */
function setDirty(d) {
  ed.dirty = d;
  $('#ed-save').disabled = !d;
  $('#ed-discard').disabled = !d;
  $('#ed-save').classList.toggle('primary', d);
  $('#ed-dirty').hidden = !d;
  const n = ed.file ? ed.file.split('/').pop().replace(/\.[^.]+$/, '') : 'Editor';
  document.title = (d ? '● ' : '') + n + ' · Editor';
}
async function saveFile() {
  if (!ed.file || !ed.dirty) return true;
  try {
    applyResult(await post('/api/editor/save', { file: ed.file }));
    const t = new Date().toLocaleTimeString(I18N.locale, { hour: '2-digit', minute: '2-digit' });
    $('#ed-status').textContent = `gespeichert um ${t}`;
    return true;
  } catch (e) { toast('Speichern fehlgeschlagen: ' + e.message); return false; }
}
async function discard(ask = true) {
  if (!ed.file || !ed.dirty) return;
  if (ask && !confirm('Alle ungespeicherten Änderungen verwerfen?')) return;
  try {
    applyResult(await post('/api/editor/discard', { file: ed.file }));
    await loadAll(true);
    rebuildHelpers();
    $('#ed-status').textContent = 'Änderungen verworfen';
  } catch (e) { toast(e.message); }
}
$('#ed-save').addEventListener('click', saveFile);
$('#ed-discard').addEventListener('click', () => discard(true));

/* Schliessen mit ungespeicherten Aenderungen: fragen */
function askClose(go) {
  if (!ed.dirty) { go(); return; }
  const m = $('#ed-close');
  m.hidden = false;
  const done = async (what) => {
    m.hidden = true;
    m.onclick = null;
    document.removeEventListener('keydown', esc_);
    if (what === 'save') { if (await saveFile()) go(); }
    else if (what === 'drop') { await post('/api/editor/discard', { file: ed.file }).catch(() => {}); ed.dirty = false; go(); }
  };
  const esc_ = (e) => { if (e.key === 'Escape') done('cancel'); };
  document.addEventListener('keydown', esc_);
  m.onclick = (e) => {
    const b = e.target.closest('[data-close]');
    if (b) done(b.dataset.close);
    else if (e.target === m) done('cancel');
  };
  $('#ed-close [data-close=save]').focus();
}
$('#ed-back').addEventListener('click', (e) => {
  if (!ed.dirty) return;
  e.preventDefault();
  askClose(() => { location.href = $('#ed-back').href; });
});
window.addEventListener('beforeunload', (e) => {
  if (!ed.dirty) return;
  e.preventDefault();
  e.returnValue = '';
});
// Tab trotz Warnung geschlossen: Aenderungen verwerfen, damit sie nicht beim naechsten Oeffnen wieder auftauchen
window.addEventListener('pagehide', () => {
  if (ed.dirty && ed.file) navigator.sendBeacon('/api/editor/discard', new Blob([JSON.stringify({ file: ed.file })], { type: 'application/json' }));
});
// Download und "In die Welt setzen" brauchen den gespeicherten Stand
$('#ed-download').addEventListener('click', async (e) => {
  if (!ed.dirty) return;
  e.preventDefault();
  if (await saveFile()) location.href = $('#ed-download').href;
});

async function undo() {
  try { applyResult(await post('/api/editor/undo', { file: ed.file })); await loadAll(true); } catch (e) { toast(e.message); }
}
async function redo() {
  try { applyResult(await post('/api/editor/redo', { file: ed.file })); await loadAll(true); } catch (e) { toast(e.message); }
}
$('#ed-undo').addEventListener('click', undo);
$('#ed-redo').addEventListener('click', redo);

// ------------------------------------------------------------------ Schnitt und Bauebene

function updateCut() {
  const on = $('#ed-cut').checked;
  clipList.length = 0;
  if (on) {
    clipPlane.constant = cutY() + 1.001;
    clipList.push(clipPlane);
  }
  mats.forEach((m) => { m.clippingPlanes = clipList; m.needsUpdate = true; });
  $('#ed-cutv').textContent = on ? `Y ${cutY()}` : 'aus';
}
$('#ed-cut').addEventListener('change', () => { updateCut(); rebuildHelpers(); });
$('#ed-cuty').addEventListener('input', () => { if (!$('#ed-cut').checked) $('#ed-cut').checked = true; updateCut(); rebuildHelpers(); });
$('#ed-plane').addEventListener('change', (e) => {
  if (e.target.checked && !$('#ed-cut').checked) { $('#ed-cut').checked = true; updateCut(); }
  rebuildHelpers();
});

// ------------------------------------------------------------------ Datei

$('#ed-new').addEventListener('click', async () => {
  if (ed.dirty && !confirm('Es gibt ungespeicherte Änderungen. Trotzdem neue Schematic anlegen? (Vorher 💾 Speichern, sonst gehen sie verloren)')) return;
  const name = prompt('Name der neuen Schematic:', 'neu');
  if (!name) return;
  const size = (prompt('Größe Breite × Höhe × Länge (wächst beim Bauen von selbst):', '16 16 16') || '16 16 16').split(/[^0-9]+/).filter(Boolean).map(Number);
  try {
    const r = await post('/api/editor/new', { name, size: [size[0] || 16, size[1] || 16, size[2] || 16] });
    const q = new URLSearchParams({ file: r.file });
    if (ed.srv) q.set('srv', ed.srv);
    if (ed.dim) q.set('dim', ed.dim);
    if (ed.dirty) { await post('/api/editor/discard', { file: ed.file }).catch(() => {}); ed.dirty = false; }
    location.href = '/editor?' + q;
  } catch (e) { toast(e.message); }
});

$('#ed-place').addEventListener('click', async () => {
  if (!(await saveFile())) return;
  let at = ed.origin;
  if (!at) {
    try {
      const p = await api('/api/player');
      if (p && p.active && p.server === ed.srv) at = [Math.floor(p.x) + 2, Math.floor(p.y), Math.floor(p.z) + 2];
    } catch (_) { /* egal */ }
  }
  if (!at) { toast('Spieler nicht online: die Schematic landet bei 0 0 0, verschieben kannst du sie auf der Karte'); at = [0, 64, 0]; }
  try {
    const pl = await post('/api/schematics/place', { file: ed.file, server: ed.srv, dim: ed.dim, x: at[0], y: at[1], z: at[2] });
    ed.pid = pl.id;
    $('#ed-place').hidden = true;
    toast(`In die Welt gesetzt bei ${at.join(' ')}. Auf der Karte unter „Schematics“.`);
  } catch (e) { toast(e.message); }
});

/* Name oben direkt aendern: Enter oder Klick daneben speichert, Esc verwirft */
function showName() {
  const n = ed.file.split('/').pop().replace(/\.[^.]+$/, '');
  $('#ed-name').value = n;
  $('#ed-name').style.width = Math.max(6, n.length + 1) + 'ch';
  document.title = (ed.dirty ? '● ' : '') + n + ' · Editor';
  $('#ed-download').href = '/api/editor/file?' + new URLSearchParams({ file: ed.file });
}
async function rename() {
  const want = $('#ed-name').value.trim();
  const cur = ed.file.split('/').pop().replace(/\.[^.]+$/, '');
  if (!want || want === cur) { showName(); return; }
  try {
    const r = await post('/api/editor/rename', { file: ed.file, name: want });
    ed.file = r.file;
    const q = new URLSearchParams(location.search);
    q.set('file', ed.file);
    history.replaceState(null, '', '?' + q.toString());
    toast('Umbenannt in „' + r.name + '“');
  } catch (e) { toast('Umbenennen: ' + e.message); }
  showName();
}
$('#ed-name').addEventListener('keydown', (e) => {
  if (e.key === 'Enter') { e.preventDefault(); e.target.blur(); }
  if (e.key === 'Escape') { showName(); e.target.blur(); }
});
$('#ed-name').addEventListener('input', (e) => { e.target.style.width = Math.max(6, e.target.value.length + 1) + 'ch'; });
$('#ed-name').addEventListener('blur', rename);

/* WASD: durch die Szene fliegen (Blickrichtung waagerecht), Leertaste/C hoch/runter, Shift schneller */
const fly = new Set();
const FLY_KEYS = new Set(['KeyW', 'KeyA', 'KeyS', 'KeyD', 'Space', 'KeyC']);
document.addEventListener('keydown', (e) => {
  if (e.target.matches('input, select, textarea') || e.metaKey || e.ctrlKey || e.altKey) return;
  if (FLY_KEYS.has(e.code)) { fly.add(e.code); e.preventDefault(); }
  fly.shift = e.shiftKey;
});
document.addEventListener('keyup', (e) => { fly.delete(e.code); fly.shift = e.shiftKey; });
window.addEventListener('blur', () => fly.clear());
let flyT = performance.now();
(function flyLoop(now) {
  requestAnimationFrame(flyLoop);
  const dt = Math.min(0.1, (now - flyT) / 1000);
  flyT = now;
  if (!fly.size) return;
  const v = Math.max(8, orbit.dist * 0.9) * dt * (fly.shift ? 3 : 1);
  const fx = -Math.sin(orbit.yaw), fz = -Math.cos(orbit.yaw);   // von der Kamera zum Ziel
  const d = new THREE.Vector3();
  if (fly.has('KeyW')) d.x += fx, d.z += fz;
  if (fly.has('KeyS')) d.x -= fx, d.z -= fz;
  if (fly.has('KeyD')) d.x += -fz, d.z += fx;
  if (fly.has('KeyA')) d.x -= -fz, d.z -= fx;
  if (fly.has('Space')) d.y += 1;
  if (fly.has('KeyC')) d.y -= 1;
  if (!d.lengthSq()) return;
  orbit.target.addScaledVector(d.normalize(), v);
  updateCamera();
  if (typeof requestRender === 'function') requestRender();
})(flyT);

(async function start() {
  if (!ed.file) {
    document.body.insertAdjacentHTML('beforeend', '<div class="panel" style="position:fixed;top:40%;left:50%;transform:translateX(-50%);padding:16px">Keine Datei gewählt. <a href="/" style="color:#7fd1ff">Zur Karte</a></div>');
    return;
  }
  showName();
  $('#ed-place').hidden = !!ed.pid || !ed.srv || !ed.dim;
  try {
    itemsMeta = await api('/api/items.json');
    document.documentElement.style.setProperty('--items-url', `url(/api/items.png?v=${Object.keys(itemsMeta.index).length})`);
  } catch (_) { itemsMeta = null; }
  try {
    BLOCKS = await api('/api/editor/blocks');
    BLOCKS.forEach((b) => META.set(b.id, b));
  } catch (e) { toast('Blockliste: ' + e.message); }
  renderHotbar();
  renderPalette();
  try {
    const info = await api('/api/editor/info?' + new URLSearchParams({ file: ed.file }));
    applyResult(info);
    await loadAll(false);
    rebuildHelpers();
  } catch (e) { toast('Laden fehlgeschlagen: ' + e.message); }
  drawSel();
})();
