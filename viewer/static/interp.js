/* Fluessige Spielerposition: die Mod meldet bis zu 20x pro Sekunde, jeweils mit
 * Zeitstempel. Wir zeigen den Spieler ein kleines Stueck "in der Vergangenheit"
 * und interpolieren zwischen zwei echten Meldungen. So gibt es weder Ruckeln
 * noch Gummiband-Effekt, auch wenn Meldungen unregelmaessig ankommen.
 */
'use strict';

window.PlayerInterp = class PlayerInterp {
  constructor() {
    this.s = [];            // { t, x, y, z, yaw, pitch }
    this.offset = Infinity; // lokale Zeit - Mod-Zeit (kleinster gesehener Wert = geringste Latenz)
    this.key = null;
  }

  push(p) {
    if (!p || !p.active) return;
    const now = Date.now();
    const t = p.t || now;
    const key = `${p.server}|${p.dim}`;
    const last = this.s[this.s.length - 1];
    if (key !== this.key || (last && Math.hypot(p.x - last.x, p.z - last.z) > 64)) { this.s = []; this.key = key; }
    if (last && this.s.length && t <= last.t) return;
    // Offset darf langsam wachsen (Uhrdrift), faellt aber sofort auf neue Minima
    this.offset = Math.min(this.offset + 0.05, now - t);
    this.s.push({ t, x: p.x, y: p.y, z: p.z, yaw: p.yaw, pitch: p.pitch });
    if (this.s.length > 60) this.s.splice(0, this.s.length - 60);
  }

  /* Verzoegerung: etwa 1,5 Meldeabstaende, damit fast immer zwei Stuetzpunkte da sind. */
  delay() {
    const s = this.s;
    if (s.length < 3) return 120;
    const n = Math.min(10, s.length - 1);
    const avg = (s[s.length - 1].t - s[s.length - 1 - n].t) / n;
    return Math.max(70, Math.min(450, avg * 1.6));
  }

  sample() {
    const s = this.s;
    if (!s.length) return null;
    const rt = Date.now() - this.offset - this.delay();
    if (rt <= s[0].t) return s[0];
    const last = s[s.length - 1];
    if (rt >= last.t) return last;
    let i = s.length - 2;
    while (i > 0 && s[i].t > rt) i--;
    const a = s[i], b = s[i + 1];
    const k = (rt - a.t) / Math.max(1, b.t - a.t);
    let dy = ((b.yaw - a.yaw) % 360 + 540) % 360 - 180;   // kuerzester Weg
    return {
      t: rt, x: a.x + (b.x - a.x) * k, y: a.y + (b.y - a.y) * k, z: a.z + (b.z - a.z) * k,
      yaw: a.yaw + dy * k, pitch: a.pitch + (b.pitch - a.pitch) * k,
    };
  }
};
