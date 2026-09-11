/* Morning Mission -- screen preview renderer.
 *
 * Layout comes from the app's own solver, exported by tools/ExportScreens.java, so every
 * rectangle here is exactly where the built app puts it. Icon and glyph geometry comes
 * from tools/ExportArt.java, so the shapes are the same numbers the app compiles into
 * Paths. What IS re-implemented is the drawing itself -- the clay passes, the scenery and
 * the screen composition -- ported from Clay.java, Scene.java and the Screen classes.
 * It is a faithful port, not the app running.
 */

/* ------------------------------------------------------------------ path data */
const MOVE = 0, LINE = 1, QUAD = 2, CUBIC = 3, CLOSE = 4, OVAL = 5, CIRCLE = 6,
      RRECT = 7, HOLE = 8;

function toPath(d) {
  const p = new Path2D();
  let i = 0;
  while (i < d.length) {
    const op = d[i++];
    if (op === MOVE) p.moveTo(d[i++], d[i++]);
    else if (op === LINE) p.lineTo(d[i++], d[i++]);
    else if (op === QUAD) p.quadraticCurveTo(d[i++], d[i++], d[i++], d[i++]);
    else if (op === CUBIC) p.bezierCurveTo(d[i++], d[i++], d[i++], d[i++], d[i++], d[i++]);
    else if (op === CLOSE) p.closePath();
    else if (op === OVAL) {
      const l = d[i++], t = d[i++], r = d[i++], b = d[i++];
      p.moveTo(r, (t + b) / 2);
      p.ellipse((l + r) / 2, (t + b) / 2, (r - l) / 2, (b - t) / 2, 0, 0, Math.PI * 2);
      p.closePath();
    } else if (op === CIRCLE) {
      const cx = d[i++], cy = d[i++], rad = d[i++];
      p.moveTo(cx + rad, cy);
      p.arc(cx, cy, rad, 0, Math.PI * 2);
      p.closePath();
    } else if (op === HOLE) {
      // Wound the other way, so the non-zero fill rule subtracts it.
      const cx = d[i++], cy = d[i++], rad = d[i++];
      p.moveTo(cx + rad, cy);
      p.arc(cx, cy, rad, 0, Math.PI * 2, true);
      p.closePath();
    } else if (op === RRECT) {
      const l = d[i++], t = d[i++], r = d[i++], b = d[i++], rx = d[i++]; i++;
      p.roundRect(l, t, r - l, b - t, rx);
    }
  }
  return p;
}

/* ------------------------------------------------------------------- colour */
const rgb = h => [parseInt(h.slice(1, 3), 16), parseInt(h.slice(3, 5), 16), parseInt(h.slice(5, 7), 16)];
const hex = c => '#' + c.map(v => Math.max(0, Math.min(255, Math.round(v))).toString(16).padStart(2, '0')).join('');
const mix = (a, b, t) => { const x = rgb(a), y = rgb(b); return hex(x.map((v, i) => v + (y[i] - v) * t)); };
const lighten = (c, t) => mix(c, '#ffffff', t);
const darken = (c, t) => mix(c, '#000000', t);
const desaturate = (c, t) => mix(c, '#b9c6d6', t);
const alpha = (c, a) => { const x = rgb(c); return `rgba(${x[0]},${x[1]},${x[2]},${a})`; };
const clamp = (v, lo, hi) => v < lo ? lo : (v > hi ? hi : v);

/* Scene.hash, ported with Java's int overflow semantics so the scenery matches. */
function hash(seed) {
  let h = Math.imul(seed | 0, 0x27D4EB2D);
  h ^= h >>> 15;
  h = Math.imul(h, 0x85EBCA6B);
  h ^= h >>> 13;
  return (h >>> 8) / (1 << 24);
}
const hash2 = (seed, salt) => hash((Math.imul(seed, 73856093) ^ Math.imul(salt, 19349663)) | 0);

/* --------------------------------------------------------------- clay passes */
const LX = -0.55, LY = -0.83, U = 100;
const FLAT = 0, MODEL = 1, RIM = 2, SPEC = 4;

function drawPart(ctx, path, colour, flags) {
  if (!(flags & MODEL)) {
    ctx.fillStyle = colour;
    ctx.fill(path);
  } else {
    const g = ctx.createLinearGradient(50 + LX * 50, 50 + LY * 50, 50 - LX * 50, 50 - LY * 50);
    g.addColorStop(0, lighten(colour, .14));
    g.addColorStop(1, darken(colour, .17));
    ctx.fillStyle = g;
    ctx.fill(path);

    const occ = ctx.createRadialGradient(74, 80, 0, 74, 80, 70);
    occ.addColorStop(0, 'rgba(0,0,0,.18)');
    occ.addColorStop(.5, 'rgba(0,0,0,.06)');
    occ.addColorStop(1, 'rgba(0,0,0,0)');
    ctx.fillStyle = occ;
    ctx.fill(path);

    const hi = ctx.createRadialGradient(28, 24, 0, 28, 24, 62);
    hi.addColorStop(0, 'rgba(255,255,255,.40)');
    hi.addColorStop(.45, 'rgba(255,255,255,.10)');
    hi.addColorStop(1, 'rgba(255,255,255,0)');
    ctx.fillStyle = hi;
    ctx.fill(path);
  }
  if (flags & RIM) {
    ctx.save();
    ctx.clip(path);
    ctx.translate(-2.2, -2.2);
    ctx.strokeStyle = 'rgba(255,255,255,.40)';
    ctx.lineWidth = 4.4;
    ctx.lineJoin = 'round';
    ctx.stroke(path);
    ctx.restore();
  }
  if (flags & SPEC) {
    ctx.save();
    ctx.clip(path);
    ctx.translate(30, 24);
    ctx.rotate(-24 * Math.PI / 180);
    ctx.fillStyle = 'rgba(255,255,255,.55)';
    ctx.beginPath();
    ctx.ellipse(0, 0, 13, 7, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.restore();
  }
}

function contactShadow(ctx, cx, cy, rx, ry, strength = 1) {
  ctx.save();
  ctx.translate(cx, cy);
  ctx.scale(rx, ry);
  const g = ctx.createRadialGradient(0, 0, 0, 0, 0, 1);
  g.addColorStop(0, `rgba(16,24,40,${0.24 * strength})`);
  g.addColorStop(.55, `rgba(16,24,40,${0.12 * strength})`);
  g.addColorStop(1, 'rgba(16,24,40,0)');
  ctx.fillStyle = g;
  ctx.beginPath();
  ctx.arc(0, 0, 1, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();
}

function unitBox(ctx, cx, cy, size) {
  ctx.save();
  ctx.translate(cx, cy);
  ctx.scale(size / U, size / U);
  ctx.translate(-U / 2, -U / 2);
}

/* ------------------------------------------------------ surfaces, from Theme */
function roundRect(ctx, r, radius) {
  ctx.beginPath();
  ctx.roundRect(r[0], r[1], r[2] - r[0], r[3] - r[1], radius);
}

/** Theme.card: two offset passes, exactly as the app draws its shadows. */
function card(ctx, r, radius, colour) {
  ctx.fillStyle = 'rgba(14,42,74,.06)';
  roundRect(ctx, [r[0], r[1] + 14, r[2], r[3] + 14], radius); ctx.fill();
  ctx.fillStyle = 'rgba(14,42,74,.10)';
  roundRect(ctx, [r[0], r[1] + 7, r[2], r[3] + 7], radius); ctx.fill();
  ctx.fillStyle = colour;
  roundRect(ctx, r, radius); ctx.fill();
}

/** Theme.button: a face on a darker bottom edge. */
function button(ctx, r, radius, face, edge) {
  const lift = 10;
  ctx.fillStyle = 'rgba(14,42,74,.10)';
  roundRect(ctx, [r[0], r[1] + lift + 8, r[2], r[3] + 8], radius); ctx.fill();
  ctx.fillStyle = edge;
  roundRect(ctx, [r[0], r[1] + lift, r[2], r[3]], radius); ctx.fill();
  ctx.fillStyle = face;
  roundRect(ctx, [r[0], r[1] + lift, r[2], r[3] - lift], radius); ctx.fill();
}

/* ------------------------------------------------------------------ text */
const FAMILY = '"Nunito", system-ui, sans-serif';

function setFont(ctx, size, bold) {
  ctx.font = `${bold ? 800 : 400} ${size}px ${FAMILY}`;
}

function text(ctx, s, x, cy, size, colour, align = 'left', bold = false) {
  setFont(ctx, size, bold);
  ctx.textAlign = align;
  ctx.textBaseline = 'middle';
  ctx.fillStyle = colour;
  ctx.fillText(s, x, cy);
}

/** Theme.fitText: shrink, then ellipsise. */
function fitText(ctx, s, box, size, minSize, colour, align = 'left', bold = false) {
  const max = box[2] - box[0];
  let px = size;
  setFont(ctx, px, bold);
  while (px > minSize && ctx.measureText(s).width > max) { px -= 1; setFont(ctx, px, bold); }
  let out = s;
  if (ctx.measureText(out).width > max) {
    while (out.length > 1 && ctx.measureText(out + '...').width > max) out = out.slice(0, -1);
    out += '...';
  }
  const x = align === 'left' ? box[0] : align === 'right' ? box[2] : (box[0] + box[2]) / 2;
  text(ctx, out, x, (box[1] + box[3]) / 2, px, colour, align, bold);
}

/** Theme.wordmark: a thick white halo, then the fill. */
function wordmark(ctx, s, cx, cy, size, fill) {
  setFont(ctx, size, true);
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.lineJoin = 'round';
  ctx.lineWidth = size * 0.21;
  ctx.strokeStyle = '#ffffff';
  ctx.strokeText(s, cx, cy);
  ctx.save();
  ctx.shadowColor = 'rgba(0,0,0,.19)';
  ctx.shadowOffsetY = 5;
  ctx.shadowBlur = 7;
  ctx.fillStyle = fill;
  ctx.fillText(s, cx, cy);
  ctx.restore();
}

/** Theme.wordmarkLetters: one colour per letter, placed by advance. */
function wordmarkLetters(ctx, s, cx, cy, size, colours) {
  setFont(ctx, size, true);
  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  const total = ctx.measureText(s).width;
  let x = cx - total / 2;
  ctx.lineJoin = 'round';
  ctx.lineWidth = size * 0.21;
  ctx.strokeStyle = '#ffffff';
  ctx.strokeText(s, x, cy);
  ctx.save();
  ctx.shadowColor = 'rgba(0,0,0,.19)';
  ctx.shadowOffsetY = 5;
  ctx.shadowBlur = 7;
  let cursor = x;
  for (let i = 0; i < s.length; i++) {
    ctx.fillStyle = colours[i % colours.length];
    ctx.fillText(s[i], cursor, cy);
    cursor += ctx.measureText(s[i]).width;
  }
  ctx.restore();
}

function formatTime(ms) {
  const total = Math.ceil(Math.max(0, ms) / 1000);
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`;
}
