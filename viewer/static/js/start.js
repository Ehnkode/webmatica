/* Webmatica map UI: Startup. */
'use strict';

// ------------------------------------------------------------------ Start

(async function start() {
  try {
    state.worlds = (await api('/api/worlds')).worlds;
  } catch (e) {
    toast('Server nicht erreichbar: ' + e.message);
    return;
  }
  if (!state.worlds.length) {
    toast('Noch keine Aufnahmen gefunden. Mit der Mod spielen, dann neu laden.');
    setInterval(async () => { await pollWorlds(); if (state.worlds.length) location.reload(); }, 5000);
    return;
  }
  const h = readHash();
  if (h && state.worlds.some((w) => w.id === h.server)) {
    select(h.server, h.dim, h.layer, true);
    map.setView(toLatLng(h.x, h.z), h.zoom);
  } else {
    select(state.worlds[0].id, null, null);
  }
  setCut(cut.mode, cut.y, true);
  loadWaypoints();
  loadLibrary();
  if (prefs.trail) setTrail(true);
  schedulePlayer();
  setInterval(pollChanges, 3000);
  setInterval(pollWorlds, 15000);
  pollPlayer();
})();
