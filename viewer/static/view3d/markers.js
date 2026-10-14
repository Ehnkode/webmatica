/* 3D view: markers that are not blocks: timeline changes, waypoints, other players, the trail. */
import * as THREE from '/static/vendor/three/three.module.min.js';

export function createMarkers(scene, st, disposeGroup) {
  const cubeGeo = new THREE.BoxGeometry(1.02, 1.02, 1.02);

  // Timeline: changes compared with an earlier day as colored cubes
  const DIFF = { added: 0x22c55e, removed: 0xe5484d, changed: 0xf5a524 };
  let diffGroup = null;
  function setDiff(points) {
    if (diffGroup) { scene.remove(diffGroup); diffGroup.children.forEach((m) => { m.material.dispose(); m.dispose && m.dispose(); }); diffGroup = null; }
    if (!points) return;
    diffGroup = new THREE.Group();
    const m4 = new THREE.Matrix4();
    for (const [kind, color] of Object.entries(DIFF)) {
      const pts = points[kind] || [];
      const n = pts.length / 3;
      if (!n) continue;
      const mat = new THREE.MeshBasicMaterial({ color, transparent: true, opacity: kind === 'removed' ? 0.35 : 0.5, depthWrite: false });
      const mesh = new THREE.InstancedMesh(cubeGeo, mat, n);
      for (let i = 0; i < n; i++) { m4.makeTranslation(pts[i * 3] + 0.5, pts[i * 3 + 1] + 0.5, pts[i * 3 + 2] + 0.5); mesh.setMatrixAt(i, m4); }
      mesh.instanceMatrix.needsUpdate = true;
      diffGroup.add(mesh);
    }
    scene.add(diffGroup);
  }

  // ------------------------------------------------------------ Waypoints and trail

  const wpGroup = new THREE.Group();
  scene.add(wpGroup);
  function label(text, color) {
    const c = document.createElement('canvas');
    const g = c.getContext('2d');
    g.font = 'bold 32px system-ui, sans-serif';
    const w = Math.ceil(g.measureText(text).width) + 20;
    c.width = w; c.height = 44;
    g.font = 'bold 32px system-ui, sans-serif';
    g.fillStyle = 'rgba(0,0,0,0.55)'; g.fillRect(0, 0, w, 44);
    g.fillStyle = color; g.textBaseline = 'middle'; g.fillText(text, 10, 23);
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.NoColorSpace;
    const sp = new THREE.Sprite(new THREE.SpriteMaterial({ map: t, depthTest: false, transparent: true, sizeAttenuation: false }));
    sp.scale.set(w / 44 * 0.035, 0.035, 1);
    sp.renderOrder = 9;
    return sp;
  }
  function setWaypoints(list) {
    wpGroup.children.slice().forEach((c) => { wpGroup.remove(c); disposeGroup(c); });
    for (const w of list) {
      if (w.server !== st.srv || w.dim !== st.dim) continue;
      const g = new THREE.Group();
      g.position.set(w.x + 0.5, w.y, w.z + 0.5);
      const beam = new THREE.Mesh(new THREE.BoxGeometry(0.35, 300, 0.35), new THREE.MeshBasicMaterial({ color: w.color, transparent: true, opacity: 0.4, depthWrite: false }));
      beam.position.y = 150;
      g.add(beam);
      const l = label(w.name || `${w.x} ${w.z}`, w.color);
      l.position.y = 3;
      g.add(l);
      wpGroup.add(g);
    }
  }

  // Radar: other players as a red pillar with their name
  const othersGroup = new THREE.Group();
  scene.add(othersGroup);
  const othersByName = new Map();
  function setOthers(list) {
    const seen = new Set();
    for (const o of list || []) {
      seen.add(o.name);
      let g = othersByName.get(o.name);
      if (!g) {
        g = new THREE.Group();
        const body = new THREE.Mesh(new THREE.BoxGeometry(0.6, 1.8, 0.6), new THREE.MeshBasicMaterial({ color: 0xff3b3b, transparent: true, opacity: 0.8 }));
        body.position.y = 0.9;
        g.add(body);
        const beam = new THREE.Mesh(new THREE.BoxGeometry(0.2, 200, 0.2), new THREE.MeshBasicMaterial({ color: 0xff3b3b, transparent: true, opacity: 0.25, depthWrite: false }));
        beam.position.y = 101;
        g.add(beam);
        const l = label(o.name, '#ff6b6b');
        l.position.y = 2.6;
        g.add(l);
        othersGroup.add(g);
        othersByName.set(o.name, g);
      }
      g.position.set(o.x, o.y, o.z);
    }
    for (const [n, g] of othersByName) {
      if (!seen.has(n)) { othersGroup.remove(g); disposeGroup(g); othersByName.delete(n); }
    }
  }

  let trailLine = null;
  function setTrail(points) {
    if (trailLine) { scene.remove(trailLine); trailLine.geometry.dispose(); trailLine = null; }
    if (!points || points.length < 2) return;
    const [ox, oy, oz] = points[0];
    const seg = [];
    for (let i = 1; i < points.length; i++) {
      const a = points[i - 1], b = points[i];
      if (Math.hypot(b[0] - a[0], b[2] - a[2]) > 120) continue;   // don't connect teleports/gaps
      seg.push(a[0] - ox, a[1] - oy + 0.1, a[2] - oz, b[0] - ox, b[1] - oy + 0.1, b[2] - oz);
    }
    const g = new THREE.BufferGeometry();
    g.setAttribute('position', new THREE.BufferAttribute(new Float32Array(seg), 3));
    trailLine = new THREE.LineSegments(g, new THREE.LineBasicMaterial({ color: 0xffd23f }));
    trailLine.position.set(ox, oy, oz);
    trailLine.frustumCulled = false;
    scene.add(trailLine);
  }

  return { setDiff, setWaypoints, setOthers, setTrail };
}
