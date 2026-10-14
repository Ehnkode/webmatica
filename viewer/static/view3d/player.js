/* 3D view: the player as a skin figure with name tag, walking animation and click box. */
import * as THREE from '/static/vendor/three/three.module.min.js';

const PX = 0.9375 / 16;                     // pixel -> block

/* st: the view's state (dim, srv, cam, selected); returns the figure's state with
 * update(p) for every new position and animate(dt) once per frame. */
export function createPlayer(scene, st, disposeGroup) {

  const player = {
    root: new THREE.Group(), parts: {}, version: -1, model: null,
    pos: null, target: null, yaw: 0, pitch: 0, phase: 0, speed: 0, data: null,
    interp: new window.PlayerInterp(),
  };
  scene.add(player.root);
  player.root.visible = false;

  function boxUV(geo, u, v, w, h, d, tw, th) {
    const rects = [
      [u + d + w, v + d, d, h],     // +x  left side of the figure
      [u, v + d, d, h],             // -x  right side
      [u + d, v, w, d],             // top
      [u + d + w, v, w, d],         // bottom
      [u + d, v + d, w, h],         // front (+z)
      [u + 2 * d + w, v + d, w, h], // back
    ];
    const uvs = geo.attributes.uv;
    for (let f = 0; f < 6; f++) {
      const [px, py, pw, ph] = rects[f];
      for (let k = 0; k < 4; k++) {
        const i = f * 4 + k;
        const U = uvs.getX(i), V = uvs.getY(i);
        uvs.setXY(i, (px + U * pw) / tw, 1 - (py + (1 - V) * ph) / th);
      }
    }
    uvs.needsUpdate = true;
  }

  function part(tex, tw, th, w, h, d, uv, overlay, inflate) {
    const g = new THREE.Group();
    const mk = (u, v, grow, mat) => {
      const geo = new THREE.BoxGeometry((w + grow * 2) * PX, (h + grow * 2) * PX, (d + grow * 2) * PX);
      boxUV(geo, u, v, w, h, d, tw, th);
      return new THREE.Mesh(geo, mat);
    };
    g.add(mk(uv[0], uv[1], 0, new THREE.MeshBasicMaterial({ map: tex, alphaTest: 0.1 })));
    if (overlay && (th >= 64 || overlay[1] < 32)) g.add(mk(overlay[0], overlay[1], inflate, new THREE.MeshBasicMaterial({ map: tex, alphaTest: 0.5, side: THREE.DoubleSide })));
    return g;
  }

  function buildPlayer(tex, slim) {
    const tw = tex.image.width, th = tex.image.height;
    const legacy = th < 64;
    if (player.model) { player.root.remove(player.model); disposeGroup(player.model); }
    const model = new THREE.Group();
    const aw = slim ? 3 : 4;
    const pivot = (mesh, px, py, pz, oy) => {
      const p = new THREE.Group();
      p.position.set(px * PX, py * PX, pz * PX);
      mesh.position.y = oy * PX;
      p.add(mesh);
      model.add(p);
      return p;
    };
    player.parts.head = pivot(part(tex, tw, th, 8, 8, 8, [0, 0], [32, 0], 0.5), 0, 24, 0, 4);
    player.parts.body = pivot(part(tex, tw, th, 8, 12, 4, [16, 16], [16, 32], 0.25), 0, 24, 0, -6);
    player.parts.rarm = pivot(part(tex, tw, th, aw, 12, 4, [40, 16], [40, 32], 0.25), -(4 + aw / 2), 22, 0, -4);
    player.parts.larm = pivot(part(tex, tw, th, aw, 12, 4, legacy ? [40, 16] : [32, 48], legacy ? null : [48, 48], 0.25), 4 + aw / 2, 22, 0, -4);
    player.parts.rleg = pivot(part(tex, tw, th, 4, 12, 4, [0, 16], [0, 32], 0.25), -1.9, 12, 0, -6);
    player.parts.lleg = pivot(part(tex, tw, th, 4, 12, 4, legacy ? [0, 16] : [16, 48], legacy ? null : [0, 48], 0.25), 1.9, 12, 0, -6);
    player.root.add(model);
    player.model = model;
  }

  // Without a skin: plain figure until the skin arrives
  function placeholderTexture() {
    const c = document.createElement('canvas');
    c.width = 64; c.height = 64;
    const g = c.getContext('2d');
    g.fillStyle = '#c69c6d'; g.fillRect(0, 0, 32, 16);
    g.fillStyle = '#3aa7a3'; g.fillRect(16, 16, 24, 16);
    g.fillStyle = '#c69c6d'; g.fillRect(40, 16, 16, 16); g.fillRect(32, 48, 16, 16);
    g.fillStyle = '#3b3f8f'; g.fillRect(0, 16, 16, 16); g.fillRect(16, 48, 16, 16);
    g.fillStyle = '#2b1d0e'; g.fillRect(8, 0, 16, 8);
    const t = new THREE.CanvasTexture(c);
    t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.colorSpace = THREE.NoColorSpace;
    return t;
  }
  buildPlayer(placeholderTexture(), false);

  function loadSkin(version, slim) {
    player.version = version;
    new THREE.TextureLoader().load(`/api/skin/full.png?v=${version}`, (t) => {
      t.magFilter = THREE.NearestFilter; t.minFilter = THREE.NearestFilter; t.colorSpace = THREE.NoColorSpace;
      t.generateMipmaps = false;
      buildPlayer(t, slim);
    }, undefined, () => { /* no skin: placeholder stays */ });
  }

  // Name tag
  let nameSprite = null;
  function setName(name) {
    if (nameSprite && nameSprite.userData.name === name) return;
    if (nameSprite) { player.root.remove(nameSprite); nameSprite.material.map.dispose(); nameSprite.material.dispose(); }
    const c = document.createElement('canvas');
    const g = c.getContext('2d');
    g.font = 'bold 36px system-ui, sans-serif';
    const w = Math.ceil(g.measureText(name).width) + 24;
    c.width = w; c.height = 52;
    g.font = 'bold 36px system-ui, sans-serif';
    g.fillStyle = 'rgba(0,0,0,0.45)'; g.fillRect(0, 0, w, 52);
    g.fillStyle = '#fff'; g.textBaseline = 'middle'; g.fillText(name, 12, 27);
    const t = new THREE.CanvasTexture(c);
    t.colorSpace = THREE.NoColorSpace;
    nameSprite = new THREE.Sprite(new THREE.SpriteMaterial({ map: t, depthTest: false, transparent: true }));
    nameSprite.scale.set(w / 52 * 0.35, 0.35, 1);
    nameSprite.position.y = 2.25;
    nameSprite.renderOrder = 10;
    nameSprite.userData.name = name;
    player.root.add(nameSprite);
  }

  // Invisible hit box for clicks, ring when selected
  const hit = new THREE.Mesh(new THREE.BoxGeometry(0.9, 2.1, 0.9), new THREE.MeshBasicMaterial({ visible: false }));
  hit.position.y = 1.0;
  hit.userData.pick = { type: 'player' };
  player.root.add(hit);
  const ring = new THREE.Mesh(new THREE.RingGeometry(0.55, 0.7, 32), new THREE.MeshBasicMaterial({ color: 0x22a06b, side: THREE.DoubleSide, depthTest: false, transparent: true }));
  ring.rotation.x = -Math.PI / 2;
  ring.position.y = 0.05;
  ring.visible = false;
  player.root.add(ring);

  function updatePlayer(p) {
    player.data = p;
    const vis = !!(p && p.active && p.dim === st.dim && (!p.server || p.server === st.srv));
    player.root.visible = vis;
    if (!p) return;
    if (p.hasSkin && p.skinVersion !== player.version) loadSkin(p.skinVersion, p.skinModel === 'slim');
    else if (!p.hasSkin && player.version < 0 && p.name) loadSkin(0, false);   // The server fetches the skin by name
    if (p.name) setName(p.name);
    if (!vis) return;
    player.interp.push(p);
    if (!player.pos) player.pos = new THREE.Vector3(p.x, p.y, p.z);
    player.target = true;
  }

  function animatePlayer(dt) {
    if (!player.root.visible || !player.target) return;
    const bx = player.pos.x, bz = player.pos.z;
    const smp = player.interp.sample();
    if (smp) { player.pos.set(smp.x, smp.y, smp.z); player.yaw = smp.yaw; player.pitch = smp.pitch; }
    const moved = Math.hypot(player.pos.x - bx, player.pos.z - bz) / Math.max(dt, 1e-3);
    player.speed += (moved - player.speed) * Math.min(1, dt * 6);
    player.root.position.copy(player.pos);
    const ry = -THREE.MathUtils.degToRad(player.yaw);
    if (player.model) player.model.rotation.y = ry;
    hit.rotation.y = ry;
    const pr = player.parts;
    if (pr.head) {
      pr.head.rotation.x = THREE.MathUtils.degToRad(player.pitch);
      player.phase += dt * Math.min(player.speed, 8) * 1.6;
      const swing = Math.sin(player.phase) * Math.min(1, player.speed / 4) * 0.9;
      pr.rleg.rotation.x = swing; pr.lleg.rotation.x = -swing;
      pr.rarm.rotation.x = -swing * 0.8; pr.larm.rotation.x = swing * 0.8;
    }
    if (player.model) player.model.visible = st.cam !== 'ego';
    if (nameSprite) nameSprite.visible = st.cam !== 'ego';
    ring.visible = !!(st.selected && st.selected.type === 'player');
  }

  player.hit = hit;
  player.update = updatePlayer;
  player.animate = animatePlayer;
  return player;
}
