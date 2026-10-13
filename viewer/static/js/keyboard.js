/* Webmatica map UI: Keyboard shortcuts. */
'use strict';

// ------------------------------------------------------------------ Keyboard

document.addEventListener('keydown', (e) => {
  if (e.target.matches('input, select, textarea')) return;
  if (e.key === 'Tab') { e.preventDefault(); set3d(!mode3d); return; }
  if (schemKeys(e)) { e.preventDefault(); return; }
  if ((e.key === ',' || e.key === '.') && cut.mode !== 'off') {
    setCut(cut.mode, cut.y + (e.key === '.' ? 1 : -1) * (e.shiftKey ? 10 : 1));
    return;
  }
  if (e.key === 't' || e.key === 'T') { setTrail(!state.trail); return; }
  if (mode3d) {
    if ((e.key === 'f' || e.key === 'F') && state.player && state.player.active) view3d.gotoPlayer(state.player);
    else if (e.key === 'e' || e.key === 'E') setSelect(!sel.on);
    else if (e.key === 'Escape' && sel.on) setSelect(false);
    return;
  }
  if (e.key === '?') { toggleHelp(); return; }
  if (e.key === 'Escape' && !$('#help').hidden) { toggleHelp(false); return; }
  if (/^[1-7]$/.test(e.key) && !e.ctrlKey && !e.altKey && !e.metaKey) {
    const t = AREA_ORDER[Number(e.key) - 1];
    if (!$('#side').hidden && areaOf(sideTab) === t) toggleSide(false); else toggleSide(true, t);
    return;
  }
  if (e.key === 'f' || e.key === 'F') setFollow(!state.follow);
  else if (e.key === 's' || e.key === 'S') toggleSide();
  else if (e.key === 'e' || e.key === 'E') setSelect(!sel.on);
  else if (e.key === 'Escape' && sel.on) setSelect(false);
  else if (e.key === '/') { e.preventDefault(); searchOpen(true); }
});
