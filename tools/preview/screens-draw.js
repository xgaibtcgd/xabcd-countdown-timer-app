/* Scene and screen drawing, ported from Scene.java and the Screen classes. */

const M_ADVENTURE = 0, M_HOME = 1, M_CELEBRATE = 2;
const REEF = 3, JUNGLE = 4, SKY_ENV = 5, BLOSSOM = 6, HIVE = 1, PARK = 2, PICNIC = 0;

/* ------------------------------------------------------------------- scenery */

function buildScene(area, envIndex, mode, tint, backdrop) {
  const env = DATA.environments[envIndex];
  let frac = env.horizon;
  if (mode === M_HOME) frac = Math.min(0.74, frac + 0.14);
  const h = area[3] - area[1];
  const horizon = area[1] + h * frac;
  const amp = h * 0.045;
  return {
    area, envIndex, env, mode, horizon, tint: tint || '#ffffff',
    backdrop: backdrop || null, backdropDst: backdrop ? placeBackdrop(area, backdrop) : null,
    far: wave(area, horizon - amp * 2.1, amp * 1.3, 3, 11),
    mid: wave(area, horizon - amp * 0.5, amp * 1.0, 4, 29),
    ground: wave(area, horizon + amp * 0.6, amp * 0.5, 5, 47),
  };
}

/**
 * Scene.layoutBackdrop: full width, anchored to the bottom, so nothing is cropped. The
 * band left above is filled with the artwork's own sky colour.
 */
function placeBackdrop(area, img) {
  const width = area[2] - area[0];
  const height = width * img.naturalHeight / img.naturalWidth;
  if (height >= area[3] - area[1]) {
    const scaled = (area[3] - area[1]) * img.naturalWidth / img.naturalHeight;
    const cx = (area[0] + area[2]) / 2;
    return [cx - scaled / 2, area[1], cx + scaled / 2, area[3]];
  }
  return [area[0], area[3] - height, area[2], area[3]];
}

/** Scene.wave: a closed wavy band running down to the bottom of the area. */
function wave(area, baseY, amplitude, humps, seed) {
  const p = new Path2D();
  const w = area[2] - area[0];
  p.moveTo(area[0], baseY);
  const step = w / humps;
  for (let i = 0; i < humps; i++) {
    const x0 = area[0] + i * step;
    const lift = amplitude * (0.55 + hash2(seed, i) * 0.9);
    p.quadraticCurveTo(x0 + step * 0.5, baseY - lift, x0 + step, baseY + amplitude * 0.12);
  }
  p.lineTo(area[2], area[3]);
  p.lineTo(area[0], area[3]);
  p.closePath();
  return p;
}

function skyColours(scene) {
  let { skyTop: top, skyMid: mid, skyLow: low } = scene.env;
  if (scene.mode === M_HOME) {
    top = mix(top, '#ffffff', 0.26); mid = mix(mid, '#ffffff', 0.26); low = mix(low, '#ffffff', 0.26);
  } else if (scene.mode === M_CELEBRATE) {
    top = mix('#FFD978', scene.tint, 0.22);
    mid = mix('#FFF0BE', scene.tint, 0.16);
    low = mix('#FFFBEC', scene.tint, 0.12);
  }
  return [top, mid, low];
}

function drawSceneBackground(ctx, scene, buddy, t) {
  const a = scene.area, h = a[3] - a[1], w = a[2] - a[0];

  if (scene.backdrop) {
    const d = scene.backdropDst;
    if (d[1] > a[1] + 0.5) {
      // Sampled from the artwork's top row at build time; see tools/buildpreview.sh.
      ctx.fillStyle = scene.mode === M_HOME
        ? (DATA.homeBackdropSky || '#BAE8F6')
        : (scene.env.backdropSky || scene.env.skyTop);
      ctx.fillRect(a[0], a[1], w, d[1] - a[1] + 1);
    }
    ctx.drawImage(scene.backdrop, d[0], d[1], d[2] - d[0], d[3] - d[1]);
    if (scene.envIndex === REEF) drawGodRays(ctx, scene, t);
    if (scene.mode === M_HOME) {
      ctx.fillStyle = 'rgba(255,255,255,.24)';
      ctx.fillRect(a[0], a[1], w, h);
    }
    return;
  }

  const [top, mid, low] = skyColours(scene);

  const sky = ctx.createLinearGradient(0, a[1], 0, scene.horizon + h * 0.12);
  sky.addColorStop(0, top); sky.addColorStop(0.55, mid); sky.addColorStop(1, low);
  ctx.fillStyle = sky;
  ctx.fillRect(a[0], a[1], w, h);

  if (scene.mode === M_CELEBRATE) drawSunburst(ctx, scene, t);
  else if (scene.envIndex === REEF) drawGodRays(ctx, scene, t);
  else drawSun(ctx, scene, t);

  if (scene.envIndex === SKY_ENV) {
    drawCloudBanks(ctx, scene, t);
    return;
  }

  ctx.fillStyle = mix(scene.env.groundFar, scene.env.skyLow, 0.38);
  ctx.fill(scene.far);

  const ground = ctx.createLinearGradient(0, scene.horizon, 0, a[3]);
  ground.addColorStop(0, lighten(scene.env.groundNear, 0.06));
  ground.addColorStop(1, darken(scene.env.groundNear, 0.16));
  ctx.fillStyle = ground;
  ctx.fill(scene.ground);
  drawGroundDetail(ctx, scene);
  drawMidProps(ctx, scene, t);
}

function drawSun(ctx, scene, t) {
  const a = scene.area, w = a[2] - a[0], h = a[3] - a[1];
  const r = w * 0.072, cx = a[2] - w * 0.17, cy = a[1] + h * 0.10;
  ctx.fillStyle = 'rgba(255,233,160,.20)';
  ctx.beginPath(); ctx.arc(cx, cy, r * 2.3, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = 'rgba(255,240,188,.30)';
  ctx.beginPath(); ctx.arc(cx, cy, r * 1.65, 0, Math.PI * 2); ctx.fill();

  ctx.save();
  ctx.translate(cx, cy); ctx.rotate((t * 5 % 360) * Math.PI / 180);
  ctx.fillStyle = 'rgba(255,207,69,.60)';
  for (let i = 0; i < 8; i++) {
    ctx.save(); ctx.rotate(i * Math.PI / 4);
    ctx.beginPath();
    ctx.roundRect(-r * 0.10, -r * 2.05, r * 0.20, r * 0.75, r * 0.10);
    ctx.fill();
    ctx.restore();
  }
  ctx.restore();

  unitBox(ctx, cx, cy, r * 2);
  drawPart(ctx, toPath([CIRCLE, 50, 50, 46]), '#FFD84D', MODEL | RIM | SPEC);
  ctx.restore();
}

function drawGodRays(ctx, scene, t) {
  const a = scene.area, w = a[2] - a[0], h = a[3] - a[1];
  for (let i = 0; i < 6; i++) {
    const x = a[0] + w * (0.08 + 0.17 * i);
    const sway = Math.sin(t * 0.42 + i * 1.7) * 3.2;
    const al = 0.16 + 0.10 * Math.sin(t * 0.6 + i);
    ctx.save();
    ctx.translate(x, a[1]);
    ctx.rotate((-6 + sway + hash(i) * 12) * Math.PI / 180);
    ctx.scale(1 + hash2(i, 5) * 0.7, 1);
    ctx.fillStyle = `rgba(255,255,255,${Math.max(0, al)})`;
    ctx.beginPath();
    ctx.moveTo(-14, 0); ctx.lineTo(14, 0);
    ctx.lineTo(72, h * 1.1); ctx.lineTo(-58, h * 1.1);
    ctx.closePath(); ctx.fill();
    ctx.restore();
  }
}

function drawSunburst(ctx, scene, t) {
  const a = scene.area, w = a[2] - a[0], h = a[3] - a[1];
  const cx = (a[0] + a[2]) / 2, cy = a[1] + h * 0.38, r = Math.max(w, h);
  ctx.save();
  ctx.translate(cx, cy); ctx.rotate((t * 4 % 360) * Math.PI / 180);
  ctx.fillStyle = 'rgba(255,255,255,.10)';
  ctx.beginPath();
  for (let i = 0; i < 16; i++) {
    const a0 = i * 22.5 * Math.PI / 180, a1 = (i * 22.5 + 11) * Math.PI / 180;
    ctx.moveTo(0, 0);
    ctx.lineTo(Math.cos(a0) * r, Math.sin(a0) * r);
    ctx.lineTo(Math.cos(a1) * r, Math.sin(a1) * r);
    ctx.closePath();
  }
  ctx.fill();
  ctx.restore();
}

function drawMidProps(ctx, scene, t) {
  switch (scene.envIndex) {
    case REEF: drawKelpAndCoral(ctx, scene, t); break;
    case JUNGLE: drawJungle(ctx, scene, t); break;
    case HIVE: drawFlowerField(ctx, scene, t); break;
    case BLOSSOM: drawBlossomTree(ctx, scene, t); break;
    case PARK: drawPath(ctx, scene); drawTrees(ctx, scene, t, 5); break;
    case PICNIC: drawTrees(ctx, scene, t, 4); drawBlanket(ctx, scene); break;
    default: drawTrees(ctx, scene, t, 4);
  }
}

function drawTrees(ctx, scene, t, count) {
  const a = scene.area, w = a[2] - a[0], h = a[3] - a[1];
  const unit = h * 0.085;
  for (let i = 0; i < count; i++) {
    const x = a[0] + w * (0.08 + 0.84 * hash2(i, 3));
    const s = 0.7 + hash2(i, 7) * 0.6;
    const baseY = scene.horizon + unit * 0.2 * hash2(i, 11);
    ctx.save();
    ctx.translate(x, baseY);
    ctx.rotate(Math.sin(t * 0.8 + i) * 2.4 * Math.PI / 180);
    ctx.fillStyle = '#6E4B2E';
    ctx.beginPath();
    ctx.roundRect(-unit * 0.10 * s, -unit * 1.1 * s, unit * 0.20 * s, unit * 1.1 * s, unit * 0.08 * s);
    ctx.fill();
    ctx.fillStyle = darken(scene.env.groundFar, 0.14);
    ctx.beginPath(); ctx.arc(0, -unit * 1.42 * s, unit * 0.52 * s, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = lighten(scene.env.groundFar, 0.10);
    ctx.beginPath(); ctx.arc(-unit * 0.22 * s, -unit * 1.58 * s, unit * 0.40 * s, 0, Math.PI * 2); ctx.fill();
    ctx.restore();
  }
}

function drawPath(ctx, scene) {
  const a = scene.area, w = a[2] - a[0], h = a[3] - a[1], cx = (a[0] + a[2]) / 2;
  ctx.fillStyle = '#F0DCA8';
  ctx.beginPath();
  ctx.moveTo(cx - w * 0.05, scene.horizon);
  ctx.quadraticCurveTo(cx - w * 0.30, a[3] - h * 0.10, a[0] - w * 0.10, a[3]);
  ctx.lineTo(a[2] + w * 0.10, a[3]);
  ctx.quadraticCurveTo(cx + w * 0.34, a[3] - h * 0.12, cx + w * 0.05, scene.horizon);
  ctx.closePath(); ctx.fill();
}

function drawBlanket(ctx, scene) {
  const a = scene.area, w = (a[2] - a[0]) * 0.34, h = (a[3] - a[1]) * 0.07;
  const cx = a[0] + (a[2] - a[0]) * 0.22, cy = scene.horizon + (a[3] - a[1]) * 0.09;
  ctx.save();
  ctx.translate(cx, cy); ctx.rotate(-7 * Math.PI / 180);
  ctx.fillStyle = '#E85A4C';
  ctx.beginPath(); ctx.roundRect(-w / 2, -h / 2, w, h, h * 0.18); ctx.fill();
  ctx.fillStyle = 'rgba(255,255,255,.53)';
  for (let i = 1; i < 5; i++) {
    const x = -w / 2 + w * i / 5;
    ctx.fillRect(x - w * 0.012, -h / 2, w * 0.024, h);
  }
  for (let i = 1; i < 3; i++) {
    const y = -h / 2 + h * i / 3;
    ctx.fillRect(-w / 2, y - h * 0.05, w, h * 0.10);
  }
  ctx.restore();
}

function drawKelpAndCoral(ctx, scene, t) {
  const a = scene.area, w = a[2] - a[0], unit = (a[3] - a[1]) * 0.10;
  ctx.lineCap = 'round';
  ctx.lineWidth = unit * 0.16;
  for (let i = 0; i < 7; i++) {
    const x = a[0] + w * (0.05 + 0.90 * hash2(i, 13));
    const hh = unit * (1.1 + hash2(i, 17) * 1.5);
    const sway = Math.sin(t * 0.9 + i * 0.8) * unit * 0.22;
    ctx.strokeStyle = i % 2 === 0 ? 'rgba(46,143,110,.80)' : 'rgba(63,168,126,.80)';
    ctx.beginPath();
    ctx.moveTo(x, scene.horizon + unit * 0.2);
    ctx.quadraticCurveTo(x + sway, scene.horizon - hh * 0.5, x + sway * 1.7, scene.horizon - hh);
    ctx.stroke();
  }
  const coral = ['#FF8AA8', '#B085F5', '#FFB35C'];
  for (let i = 0; i < 5; i++) {
    const x = a[0] + w * (0.08 + 0.84 * hash2(i, 23));
    const s = unit * (0.5 + hash2(i, 29) * 0.5);
    ctx.fillStyle = coral[i % coral.length];
    for (let k = -1; k <= 1; k++) {
      ctx.save();
      ctx.translate(x + k * s * 0.34, scene.horizon + unit * 0.1);
      ctx.rotate(k * 17 * Math.PI / 180);
      const top = -s * (0.7 + Math.abs(k) * -0.2);
      ctx.beginPath(); ctx.roundRect(-s * 0.13, top, s * 0.26, -top, s * 0.13); ctx.fill();
      ctx.restore();
    }
  }
}

function drawJungle(ctx, scene, t) {
  const a = scene.area, w = a[2] - a[0];
  ctx.fillStyle = 'rgba(47,143,110,.55)';
  const leaf = w * 0.32;
  for (let i = 0; i < 4; i++) {
    const x = a[0] + w * (0.05 + 0.30 * i);
    ctx.save();
    ctx.translate(x, a[1] - leaf * 0.22);
    ctx.rotate((18 + i * 11 + Math.sin(t * 0.55 + i * 1.3) * 3) * Math.PI / 180);
    ctx.beginPath();
    ctx.moveTo(0, 0);
    ctx.quadraticCurveTo(leaf * 0.55, leaf * 0.18, leaf, leaf * 0.62);
    ctx.quadraticCurveTo(leaf * 0.42, leaf * 0.50, 0, 0);
    ctx.closePath(); ctx.fill();
    ctx.restore();
  }
  drawTrees(ctx, scene, t, 3);
}

function drawFlowerField(ctx, scene, t) {
  drawTrees(ctx, scene, t, 2);
  const a = scene.area, w = a[2] - a[0], h = a[3] - a[1], unit = h * 0.022;
  const petals = ['#FF7298', '#FFD75E', '#B58CFF', '#FFFFFF'];
  for (let i = 0; i < 14; i++) {
    const x = a[0] + w * hash2(i, 31);
    const y = scene.horizon + h * 0.04 + h * 0.22 * hash2(i, 37);
    const sway = Math.sin(t * 1.1 + i) * unit * 0.25;
    ctx.fillStyle = '#4FA35F';
    ctx.fillRect(x - unit * 0.10, y, unit * 0.20, unit * 1.5);
    ctx.fillStyle = petals[i % petals.length];
    for (let k = 0; k < 5; k++) {
      const ang = k * Math.PI * 2 / 5;
      ctx.beginPath();
      ctx.arc(x + sway + Math.cos(ang) * unit * 0.5, y + Math.sin(ang) * unit * 0.5, unit * 0.36, 0, Math.PI * 2);
      ctx.fill();
    }
    ctx.fillStyle = '#FFC24A';
    ctx.beginPath(); ctx.arc(x + sway, y, unit * 0.28, 0, Math.PI * 2); ctx.fill();
  }
}

function drawBlossomTree(ctx, scene, t) {
  const a = scene.area, w = a[2] - a[0], unit = (a[3] - a[1]) * 0.10;
  const x = a[0] + w * 0.20;
  ctx.save();
  ctx.translate(x, scene.horizon + unit * 0.1);
  ctx.rotate(Math.sin(t * 0.6) * 1.6 * Math.PI / 180);
  ctx.fillStyle = '#8A6046';
  ctx.beginPath(); ctx.roundRect(-unit * 0.11, -unit * 1.5, unit * 0.22, unit * 1.5, unit * 0.1); ctx.fill();
  const blossom = ['#FFC2D9', '#FFD8E6', '#FFA9C8'];
  for (let i = 0; i < 7; i++) {
    ctx.fillStyle = blossom[i % blossom.length];
    ctx.beginPath();
    ctx.arc(unit * (hash2(i, 41) - 0.5) * 1.5, -unit * (1.4 + hash2(i, 43) * 0.7),
            unit * (0.34 + hash2(i, 47) * 0.24), 0, Math.PI * 2);
    ctx.fill();
  }
  ctx.restore();
  drawTrees(ctx, scene, t, 2);
}

function drawCloudBanks(ctx, scene, t) {
  const a = scene.area, w = a[2] - a[0], h = a[3] - a[1];
  const speeds = [5, 11, 19], alphas = [0.55, 0.75, 1], scales = [0.6, 0.85, 1.15];
  for (let band = 0; band < 3; band++) {
    const y = a[1] + h * (0.16 + band * 0.17);
    const unit = h * 0.05 * scales[band];
    const span = w * 0.75;
    const shift = (t * speeds[band]) % span;
    ctx.fillStyle = `rgba(255,255,255,${alphas[band]})`;
    for (let i = -1; i < 3; i++) puff(ctx, a[0] + i * span + shift, y + unit * hash2(band, i) * 0.6, unit);
  }
  const ground = ctx.createLinearGradient(0, scene.horizon, 0, a[3]);
  ground.addColorStop(0, lighten(scene.env.groundNear, 0.06));
  ground.addColorStop(1, darken(scene.env.groundNear, 0.16));
  ctx.fillStyle = ground;
  ctx.fill(scene.ground);
  ctx.fillStyle = '#ffffff';
  const unit = h * 0.045;
  for (let i = 0; i < 7; i++) {
    ctx.beginPath();
    ctx.arc(a[0] + w * (0.06 + 0.15 * i), scene.horizon + unit * (0.3 + hash2(i, 53) * 0.4),
            unit * (0.7 + hash2(i, 59) * 0.5), 0, Math.PI * 2);
    ctx.fill();
  }
  drawRainbow(ctx, scene);
}

function puff(ctx, x, y, unit) {
  ctx.beginPath(); ctx.arc(x, y, unit * 0.8, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.arc(x + unit * 0.9, y - unit * 0.35, unit * 1.05, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath(); ctx.arc(x + unit * 2.0, y, unit * 0.75, 0, Math.PI * 2); ctx.fill();
  ctx.beginPath();
  ctx.roundRect(x - unit * 0.8, y - unit * 0.1, unit * 3.6, unit * 0.95, unit * 0.5);
  ctx.fill();
}

function drawRainbow(ctx, scene) {
  const a = scene.area, w = a[2] - a[0], h = a[3] - a[1];
  const bands = ['#FF8A8A', '#FFC857', '#7ED86F', '#6FA8FF'];
  const r = w * 0.34, cx = a[2] - w * 0.24, cy = scene.horizon - h * 0.03;
  ctx.lineWidth = w * 0.026;
  for (let i = 0; i < bands.length; i++) {
    ctx.strokeStyle = alpha(bands[i], 0.59);
    ctx.beginPath();
    ctx.arc(cx, cy, r - i * w * 0.028, 195 * Math.PI / 180, 345 * Math.PI / 180);
    ctx.stroke();
  }
}

function drawGroundDetail(ctx, scene) {
  const a = scene.area, w = a[2] - a[0], h = a[3] - a[1], unit = h * 0.016;
  const colour = scene.envIndex === REEF ? '#D9BF86' : darken(scene.env.groundNear, 0.14);
  ctx.fillStyle = colour;
  for (let i = 0; i < 12; i++) {
    const x = a[0] + w * hash2(i, 61);
    const y = scene.horizon + h * 0.05 + (a[3] - scene.horizon) * 0.85 * hash2(i, 67);
    ctx.beginPath(); ctx.ellipse(x, y, unit * 1.3, unit * 0.55, 0, 0, Math.PI * 2); ctx.fill();
  }
}

/** Scene.drawForeground: atmosphere, then the readability wash. */
function drawSceneForeground(ctx, scene, t, scrimTop) {
  const a = scene.area, w = a[2] - a[0], h = a[3] - a[1];
  const count = scene.mode === M_HOME ? 10 : 20;
  for (let i = 0; i < count; i++) {
    const x = a[0] + w * hash2(i, 71);
    const phase = hash2(i, 73);
    const speed = 0.035 + hash2(i, 79) * 0.05;
    const progress = (phase + t * speed) % 1;
    const size = w * (0.006 + hash2(i, 83) * 0.010);
    const drift = Math.sin(t * 0.9 + i) * w * 0.02;

    if (scene.envIndex === REEF) {
      const y = a[3] - progress * h;
      ctx.fillStyle = 'rgba(255,255,255,.36)';
      ctx.beginPath(); ctx.arc(x + drift, y, size, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = 'rgba(255,255,255,.60)';
      ctx.beginPath(); ctx.arc(x + drift - size * .3, y - size * .3, size * .32, 0, Math.PI * 2); ctx.fill();
    } else if (scene.envIndex === BLOSSOM) {
      const y = a[1] + progress * h;
      ctx.save(); ctx.translate(x + drift, y); ctx.rotate((t * 60 + i * 40) * Math.PI / 180);
      ctx.fillStyle = i % 2 === 0 ? 'rgba(255,158,196,.80)' : 'rgba(255,211,227,.80)';
      ctx.beginPath(); ctx.roundRect(-size, -size * .55, size * 2, size * 1.1, size * .55); ctx.fill();
      ctx.restore();
    } else if (scene.envIndex === JUNGLE) {
      const y = a[1] + progress * h;
      ctx.save(); ctx.translate(x + drift, y); ctx.rotate((t * 40 + i * 60) * Math.PI / 180);
      ctx.fillStyle = 'rgba(79,174,114,.70)';
      ctx.beginPath(); ctx.ellipse(0, 0, size * 1.3, size * .5, 0, 0, Math.PI * 2); ctx.fill();
      ctx.restore();
    } else if (scene.envIndex === SKY_ENV) {
      const y = a[1] + h * hash2(i, 89) * 0.6;
      const twinkle = 0.4 + 0.6 * Math.abs(Math.sin(t * 1.6 + i));
      ctx.fillStyle = `rgba(255,244,196,${twinkle * .82})`;
      ctx.beginPath(); ctx.arc(x, y, size * .8, 0, Math.PI * 2); ctx.fill();
    } else {
      const y = a[3] - progress * h * 0.75;
      ctx.fillStyle = `rgba(255,246,200,${clamp((120 + 90 * Math.sin(t + i)) / 255, 0, 1)})`;
      ctx.beginPath(); ctx.arc(x + drift, y, size * .7, 0, Math.PI * 2); ctx.fill();
    }
  }

  if (scrimTop) {
    let strength = scene.env.scrim;
    if (scene.mode === M_HOME) strength = Math.max(0x18, strength - 0x12);
    const dark = darken(scene.env.skyTop, 0.55);
    const g = ctx.createLinearGradient(0, a[1], 0, a[1] + h * 0.20);
    g.addColorStop(0, alpha(dark, strength / 255));
    g.addColorStop(1, alpha(dark, 0));
    ctx.fillStyle = g;
    ctx.fillRect(a[0], a[1], w, h * 0.20);
  }
}
