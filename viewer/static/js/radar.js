/* Webmatica map UI: Radar. */
'use strict';

// ------------------------------------------------------------------ Radar

var radar = { layer: L.layerGroup().addTo(map), markers: new Map(), alarmed: new Map(), log: [], lastLog: 0 };
$('#rd-radius').value = prefs.rdRadius || 96;
$('#rd-sound').checked = prefs.rdSound !== false;
$('#rd-radius-v').textContent = $('#rd-radius').value;
$('#rd-radius').addEventListener('input', (e) => { $('#rd-radius-v').textContent = e.target.value; savePref('rdRadius', Number(e.target.value)); });
$('#rd-sound').addEventListener('change', (e) => savePref('rdSound', e.target.checked));

function beep() {
  try {
    const ac = new (window.AudioContext || window.webkitAudioContext)();
    [0, 0.25].forEach((t) => {
      const o = ac.createOscillator(), g = ac.createGain();
      o.frequency.value = 880; o.connect(g); g.connect(ac.destination);
      g.gain.setValueAtTime(0.15, ac.currentTime + t); g.gain.exponentialRampToValueAtTime(0.001, ac.currentTime + t + 0.2);
      o.start(ac.currentTime + t); o.stop(ac.currentTime + t + 0.22);
    });
  } catch (_) { /* ignore */ }
}

function radarUpdate(p) {
  const others = p && p.active && p.dim === state.dim && (!p.server || p.server === state.server) ? (p.others || []) : [];
  const seen = new Set();
  for (const o of others) {
    seen.add(o.name);
    let m = radar.markers.get(o.name);
    if (!m) {
      m = L.marker(toLatLng(o.x, o.z), { icon: L.divIcon({ className: '', html: `<div class="radar-pin"><span>${esc(o.name)}</span></div>`, iconSize: [12, 12], iconAnchor: [6, 6] }), zIndexOffset: 900 }).addTo(radar.layer);
      radar.markers.set(o.name, m);
    }
    m.setLatLng(toLatLng(o.x, o.z));
  }
  for (const [n, m] of radar.markers) if (!seen.has(n)) { radar.layer.removeLayer(m); radar.markers.delete(n); }
  if (view3d) view3d.setOthers(others);
  // Alarm: someone close to you or to a build site
  const R = Number($('#rd-radius').value);
  const sites = placedHere().map((pl) => [pl.x + pl.size[0] / 2, pl.z + pl.size[2] / 2, pl.name]);
  for (const o of others) {
    let why = null;
    if (o.dist != null && o.dist < R) why = `${Math.round(o.dist)} m von dir`;
    for (const [sx, sz, name] of sites) {
      const d = Math.hypot(o.x - sx, o.z - sz);
      if (d < R) { why = `${Math.round(d)} m von „${name}“`; break; }
    }
    if (why && Date.now() - (radar.alarmed.get(o.name) || 0) > 120000) {
      radar.alarmed.set(o.name, Date.now());
      toast(`⚠ ${o.name} ist ${why}`);
      if ($('#rd-sound').checked) beep();
      if (window.Notification && Notification.permission === 'granted' && document.hidden) new Notification('Webmatica Radar', { body: `${o.name} ist ${why}` });
    }
  }
  const badge = $('#radar-badge');
  badge.hidden = !others.length;
  badge.textContent = others.length;
  $('#rd-now').innerHTML = others.length ? others.slice().sort((a, b) => a.dist - b.dist).map((o) => `<li data-x="${Math.round(o.x)}" data-z="${Math.round(o.z)}"><div class="main"><div class="title radar-name">${esc(o.name)}</div>
    <div class="sub mono">${fmt(o.x)} ${fmt(o.y)} ${fmt(o.z)} · ${Math.round(o.dist)} m</div></div></li>`).join('') : '<li class="muted">niemand</li>';
  if (Date.now() - radar.lastLog > 10000) { radar.lastLog = Date.now(); loadRadarLog(); }
}
async function loadRadarLog() {
  try { radar.log = await api('/api/radar?' + new URLSearchParams({ srv: state.server })); } catch (_) { return; }
  $('#rd-log').innerHTML = radar.log.length ? radar.log.slice(0, 100).map((e) => `<li data-x="${e.x}" data-z="${e.z}"><div class="main"><div class="title">${esc(e.name)}</div>
    <div class="sub">${agoText(e.last * 1000)} · ${Math.round((e.last - e.first) / 60)} min · am nächsten ${Math.round(e.closest)} m bei ${e.x} ${e.z}${e.dim !== 'overworld' ? ' (' + esc(e.dim) + ')' : ''}</div></div></li>`).join('') : '<li class="muted">noch niemand gesehen</li>';
}
for (const id of ['#rd-now', '#rd-log']) {
  $(id).addEventListener('click', (e) => {
    const li = e.target.closest('li[data-x]');
    if (li) map.setView(toLatLng(Number(li.dataset.x), Number(li.dataset.z)), Math.max(map.getZoom(), 2));
  });
}
$('#rd-sound').addEventListener('click', () => { if (window.Notification && Notification.permission === 'default') Notification.requestPermission(); });
