/* Webmatica map UI: Jump and search, sidebar areas, shortcut help, collapsible sections. */
import './world.js';   // runs before this file (load order, see index.html)
import { $, $$, esc, map, state, toast, toLatLng } from './core.js';
import { select } from './world.js';
import { mode3d, setFollow, view3d } from './player.js';

// ------------------------------------------------------------------ Jump

$('#goto').addEventListener('submit', (e) => {
  e.preventDefault();
  const q = $('#goto-input').value.trim();
  const nums = (q.match(/-?\d+(\.\d+)?/g) || []).map(Number);
  let x, y = null, z;
  const w = findWaypoint(q);
  if (w) {
    if (w.server !== state.server || w.dim !== state.dim) select(w.server, w.dim, null, true);
    ({ x, y, z } = w);
  } else if (nums.length >= 2) {
    [x, z] = nums.length >= 3 ? [nums[0], nums[2]] : nums;
    if (nums.length >= 3) y = nums[1];
  } else {
    toast(q ? `Kein Wegpunkt „${q}“. Koordinaten gehen auch: „1200 -340“` : 'X und Z oder einen Wegpunkt eingeben, z. B. „1200 -340“');
    return;
  }
  setFollow(false);
  $('#goto-input').blur();
  searchOpen(false);
  if (mode3d && view3d) view3d.flyTo(x + 0.5, Math.max((y ?? view3d.position().y) + 12, 70), z + 0.5);
  else map.setView(toLatLng(x, z), Math.max(map.getZoom(), 0));
  flash(x, z);
});
/* Search is just a magnifier in the bar; clicking expands the field */
function searchOpen(on) {
  const f = $('#goto');
  f.classList.toggle('open', on);
  $('#goto-btn').classList.toggle('open', on);
  $('#goto-input').tabIndex = on ? 0 : -1;
  if (on) setTimeout(() => $('#goto-input').focus(), 30);
  else { $('#goto-input').value = ''; $('#goto-input').blur(); }
}
$('#goto-btn').addEventListener('click', () => {
  if (!$('#goto').classList.contains('open')) searchOpen(true);
  else if ($('#goto-input').value.trim()) $('#goto').requestSubmit();
  else searchOpen(false);
});
$('#goto-input').addEventListener('keydown', (e) => { if (e.key === 'Escape') { e.stopPropagation(); searchOpen(false); } });
$('#goto-input').addEventListener('blur', () => setTimeout(() => {
  if (document.activeElement !== $('#goto-input') && !$('#goto-input').value.trim()) searchOpen(false);
}, 150));
/* Waypoint by name: exact match first, then prefix, then anywhere in the name */
function findWaypoint(q) {
  const n = q.toLowerCase();
  if (!n || /^[-\d\s.,]+$/.test(n)) return null;
  const all = state.waypoints;
  return all.find((w) => w.name.toLowerCase() === n) || all.find((w) => w.name.toLowerCase().startsWith(n))
    || all.find((w) => w.name.toLowerCase().includes(n)) || null;
}
function fillGotoList() {
  $('#goto-list').innerHTML = state.waypoints.map((w) => `<option value="${esc(w.name)}">`).join('');
}

function flash(x, z) {
  const c = L.circleMarker(toLatLng(x + 0.5, z + 0.5), { radius: 14, color: '#fff', weight: 2, fill: false }).addTo(map);
  setTimeout(() => map.removeLayer(c), 1500);
}

// ------------------------------------------------------------------ Sidebar

/* Sidebar: the rail on the right picks an area, clicking again closes it.
 * An area can have several subpages (tabs at the top of the panel). sideTab is always
 * the subpage (= data-pane); the area follows from it.
 * New area: button in #rail, <section data-pane> in #side, register it here. */
const AREAS = {
  build: { title: 'Bauen', pages: [['editor', 'Editor'], ['schematics', 'Schematics']] },
  farm: { title: 'Automationen', pages: [['farm', '']] },
  chests: { title: 'Lager', pages: [['chests', 'Kisten und Items'], ['stashes', 'Stashes']] },
  places: { title: 'Orte', pages: [['waypoints', 'Wegpunkte'], ['signs', 'Schilder']] },
  history: { title: 'Verlauf', pages: [['history', '']] },
  radar: { title: 'Radar', pages: [['radar', '']] },
  settings: { title: 'Optionen', pages: [['settings', '']] },
};
const AREA_ORDER = ['build', 'farm', 'chests', 'places', 'history', 'radar', 'settings'];
const areaOf = (page) => Object.keys(AREAS).find((a) => AREAS[a].pages.some(([p]) => p === page)) || 'places';
const lastPage = {};
try { Object.assign(lastPage, JSON.parse(localStorage.getItem('areaPages') || '{}')); } catch (_) { /* ignore */ }
let sideTab = (() => { try { return localStorage.getItem('sideTab') || 'editor'; } catch (_) { return 'editor'; } })();
if (!$(`#side [data-pane="${sideTab}"]`)) sideTab = 'editor';
function showTab(tab) {
  // Area given: its most recently open subpage
  if (AREAS[tab] && !AREAS[tab].pages.some(([p]) => p === tab)) tab = lastPage[tab] || AREAS[tab].pages[0][0];
  sideTab = tab;
  const area = areaOf(tab);
  lastPage[area] = tab;
  try { localStorage.setItem('sideTab', tab); localStorage.setItem('areaPages', JSON.stringify(lastPage)); } catch (_) { /* ignore */ }
  const a = AREAS[area];
  $('#side-title').textContent = a.title;
  const sub = $('#side-sub');
  sub.hidden = a.pages.length < 2;
  sub.innerHTML = a.pages.length < 2 ? '' : a.pages.map(([p, label]) =>
    `<button data-page="${p}" class="${p === tab ? 'on' : ''}">${esc(label)}</button>`).join('');
  $$('#side .pane').forEach((p) => p.classList.toggle('active', p.dataset.pane === tab));
  railState();
}
$('#side-sub').addEventListener('click', (e) => {
  const b = e.target.closest('[data-page]');
  if (b) showTab(b.dataset.page);
});
function railState() {
  const open = !$('#side').hidden;
  const area = areaOf(sideTab);
  $$('#rail [data-tab]').forEach((b) => b.classList.toggle('active', open && b.dataset.tab === area));
}
function toggleSide(force, tab) {
  const side = $('#side');
  if (tab) showTab(tab);
  side.hidden = force === undefined ? !side.hidden : !force;
  document.body.classList.toggle('side-open', !side.hidden);
  railState();
}
$$('#rail [data-tab]').forEach((b) => b.addEventListener('click', () => {
  const open = !$('#side').hidden;
  if (open && areaOf(sideTab) === b.dataset.tab) toggleSide(false);
  else toggleSide(true, b.dataset.tab);
}));
$('#side-close').addEventListener('click', () => toggleSide(false));
document.addEventListener('click', (e) => {
  const a = e.target.closest('[data-goto-tab]');
  if (a) { e.preventDefault(); toggleSide(true, a.dataset.gotoTab); }
});
showTab(sideTab);

/* Keyboard shortcut help */
$$('#opt-lang [data-lang]').forEach((b) => {
  b.classList.toggle('on', b.dataset.lang === (window.I18N ? I18N.lang : 'de'));
  b.addEventListener('click', () => { if (window.I18N && I18N.lang !== b.dataset.lang) I18N.set(b.dataset.lang); });
});
$('#opt-keys').addEventListener('click', () => toggleHelp(true));
function toggleHelp(force) { $('#help').hidden = force === undefined ? !$('#help').hidden : !force; }
$('#btn-help').addEventListener('click', () => toggleHelp());
$('#help-close').addEventListener('click', () => toggleHelp(false));
$('#help').addEventListener('click', (e) => { if (e.target.id === 'help') toggleHelp(false); });

/* Collapsible sections remember their state */
$$('details.fold').forEach((d) => {
  try { d.open = localStorage.getItem('fold:' + d.dataset.fold) === '1'; } catch (_) { /* ignore */ }
  d.addEventListener('toggle', () => { try { localStorage.setItem('fold:' + d.dataset.fold, d.open ? '1' : '0'); } catch (_) { /* ignore */ } });
});

export { AREA_ORDER, areaOf, fillGotoList, flash, searchOpen, sideTab, toggleHelp, toggleSide };
