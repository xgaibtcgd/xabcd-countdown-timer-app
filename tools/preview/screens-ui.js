/* The seven screens, composed from the exported layout. */

const rw = r => r[2] - r[0];
const rh = r => r[3] - r[1];
const rcx = r => (r[0] + r[2]) / 2;
const rcy = r => (r[1] + r[3]) / 2;
const inset = (r, dx, dy) => [r[0] + dx, r[1] + dy, r[2] - dx, r[3] - dy];
const shift = (r, dy) => [r[0], r[1] + dy, r[2], r[3] + dy];

const MINUTES = [1, 2, 5, 10, 15, 30, 60];
const BUBBLE = ['#EAF5FF', '#D9F8F2', '#FFF0A9', '#E8DDFF', '#FF8657', '#B7D9FF', '#93ECC1'];
const LOGO = ['#E8533F', '#F2A02C', '#F6C844', '#3FA96A', '#2879ED', '#7B5BD6', '#EC4777'];
const NAV = ['Today', 'Rewards', 'Routine', 'Grown-Ups'];
/** Engine.FEAST_SECONDS and Engine.FEAST_LEAD, the beat of one collectible action. */
const FEAST_SECONDS = 2.0, FEAST_LEAD = 0.45;
/** ScreenTimePicker.PRESETS (seconds) and PRESET_COLORS. */
const PRESETS = [30, 60, 120, 300, 600, 900, 1800, 3600];
const PRESET_COLORS = ['#D9F8F2', '#CFEEFF', '#C9E4FF', '#DCDBFF',
                       '#FFF0A9', '#FFD98A', '#FFB067', '#FF8A6B'];
/** ScreenAdventure.EAT_START / EAT_END and Art.BITE_COUNT. */
const EAT_START = 0.12, EAT_END = 0.82, BITE_COUNT = 3;
const eatPhase = beat => (beat - EAT_START) / (EAT_END - EAT_START) * BITE_COUNT;
const biteAt = beat => {
  if (beat < 0) return BITE_COUNT;
  const eaten = eatPhase(beat);
  return eaten <= 0 ? 0 : Math.min(Math.floor(eaten), BITE_COUNT);
};
const NAV_GLYPH = ['home', 'star', 'list', 'people'];

const glyphCache = {};
function glyph(name) {
  if (!glyphCache[name]) {
    const g = DATA.art.glyphs.find(x => x.name === name);
    glyphCache[name] = toPath(g.d);
  }
  return glyphCache[name];
}

function drawGlyph(ctx, name, cx, cy, size, colour) {
  unitBox(ctx, cx, cy, size);
  ctx.fillStyle = colour;
  ctx.fill(glyph(name));
  ctx.restore();
}

function glyphChip(ctx, name, r, chipColour, glyphColour) {
  const size = Math.min(rw(r), rh(r));
  const cx = rcx(r), cy = rcy(r);
  contactShadow(ctx, cx, cy + size * 0.40, size * 0.38, size * 0.12);
  ctx.fillStyle = chipColour;
  ctx.beginPath(); ctx.arc(cx, cy, size * 0.5, 0, Math.PI * 2); ctx.fill();
  glossCircle(ctx, cx, cy, size * 0.5, 1);
  drawGlyph(ctx, name, cx, cy, size * 0.52, glyphColour);
}

function resolvePart(part, buddy) {
  return part.role ? buddy[DATA.art.roles[part.color]] : part.hex;
}

function drawParts(ctx, parts, buddy, tint) {
  for (const part of parts) {
    const colour = tint ? tint(resolvePart(part, buddy)) : resolvePart(part, buddy);
    drawPart(ctx, part._p || (part._p = toPath(part.d)), colour, part.flags);
  }
}

function drawActivity(ctx, kind, buddy, cx, cy, size) {
  unitBox(ctx, cx, cy, size);
  drawParts(ctx, DATA.art.activities[kind].parts, buddy);
  ctx.restore();
}

/** Icons.activityChip: the buddy-tinted round chip with the object on it. */
function activityChip(ctx, kind, buddy, cx, cy, size, done) {
  contactShadow(ctx, cx, cy + size * 0.42, size * 0.40, size * 0.12, 0.8);
  unitBox(ctx, cx, cy, size);
  ctx.fillStyle = done ? '#E2F6E6' : buddy.light;
  ctx.beginPath(); ctx.ellipse(50, 50, 46, 46, 0, 0, Math.PI * 2); ctx.fill();
  ctx.restore();
  drawActivity(ctx, kind, buddy, cx, cy, size * 0.70);
}

function drawCollectible(ctx, index, cx, cy, size, collected, pop = 0, badged = collected,
                         bites = 0) {
  const item = DATA.art.collectibles[index];
  if (size <= 0.5 || bites >= item.bites.length + 1) return;
  const buddy = DATA.buddies[index];
  unitBox(ctx, cx, cy, size * (1 + 0.35 * clamp(pop, 0, 1)));
  // Icons.biteVariants does this with a real boolean difference on the compiled path;
  // Canvas2D has no path ops, so clip the bites out instead -- same circles, from Art.
  if (bites > 0) {
    const cut = new Path2D();
    cut.rect(-200, -200, 500, 500);
    const [bx, by, br] = item.bites[Math.min(bites, item.bites.length) - 1];
    cut.moveTo(bx + br, by);
    cut.arc(bx, by, br, 0, Math.PI * 2);
    ctx.clip(cut, 'evenodd');
  }
  const parts = item.parts;
  for (const part of parts) {
    let colour = resolvePart(part, buddy);
    if (!collected) colour = desaturate(colour, 0.78);
    drawPart(ctx, part._p || (part._p = toPath(part.d)), colour,
             collected ? part.flags : (part.flags & ~SPEC));
  }
  ctx.restore();
  if (badged) {
    const badge = size * 0.34, bx = cx + size * 0.38, by = cy - size * 0.38;
    ctx.fillStyle = DATA.tokens.success;
    ctx.beginPath(); ctx.arc(bx, by, badge, 0, Math.PI * 2); ctx.fill();
    drawGlyph(ctx, 'check', bx, by, badge * 1.25, '#ffffff');
  }
}

// Mirrors MorningView.drawProp: `size` is the longest edge, the other follows the
// bitmap's own aspect, so a non-square prop is never stretched to fit.
function drawProp(ctx, name, cx, cy, size, spin, a) {
  const img = PROPS[name];
  if (!img || !img.complete || !img.naturalWidth) return;
  const longest = Math.max(img.naturalWidth, img.naturalHeight);
  const w = size * img.naturalWidth / longest, h = size * img.naturalHeight / longest;
  ctx.save();
  ctx.translate(cx, cy);
  if (spin) ctx.rotate(spin * Math.PI / 180);
  if (a < 1) ctx.globalAlpha = a;
  ctx.drawImage(img, -w / 2, -h / 2, w, h);
  ctx.restore();
}

// Mirrors MorningView.chestFrame: five registered frames, held on the last.
const CHEST_FRAMES = 5;
function chestFrame(open) {
  if (open <= 0) return 'chest_0';
  if (open >= 1) return 'chest_' + (CHEST_FRAMES - 1);
  return 'chest_' + Math.min(CHEST_FRAMES - 1, Math.floor(open * CHEST_FRAMES));
}

function drawGoal(ctx, index, cx, cy, size, open, glow) {
  // One shared treasure chest, where each character used to have its own goal drawn
  // from Art geometry. It is a bitmap now, so this loads it like the buddy sprites.
  if (glow > 0.01) {
    ctx.fillStyle = alpha(DATA.tokens.gold, 0.27 * glow);
    ctx.beginPath(); ctx.arc(cx, cy, size * 0.72, 0, Math.PI * 2); ctx.fill();
    ctx.fillStyle = alpha(DATA.tokens.gold, 0.19 * glow);
    ctx.beginPath(); ctx.arc(cx, cy, size * 0.56, 0, Math.PI * 2); ctx.fill();
  }
  contactShadow(ctx, cx, cy + size * 0.42, size * 0.40, size * 0.12);
  drawProp(ctx, chestFrame(open), cx, cy, size, 0, 1);
}

function partBounds(d) {
  let left = Infinity, bottom = -Infinity, i = 0;
  while (i < d.length) {
    const op = d[i++];
    if (op === CLOSE) continue;
    if (op === CIRCLE) { left = Math.min(left, d[i] - d[i + 2]); bottom = Math.max(bottom, d[i + 1] + d[i + 2]); i += 3; continue; }
    const n = (op === MOVE || op === LINE) ? 2 : (op === QUAD || op === OVAL) ? 4 : 6;
    for (let k = 0; k < n; k++) {
      if (op === RRECT && k >= 4) break;
      if (k % 2 === 0) left = Math.min(left, d[i + k]); else bottom = Math.max(bottom, d[i + k]);
    }
    i += n;
  }
  return { left, bottom };
}

// MorningView.topFraction measures this off the decoded bitmap. Here it is measured
// by tools/buildpreview.sh and shipped in data.js, because a file:// page is not
// allowed to getImageData its own sprites -- the same reason the backdrop skies are
// sampled at build time rather than in the browser.
function cheerTopFraction(index) {
  return DATA.buddies[index].cheerTop || 0;
}

// Mirrors MorningView.drawBuddyCheering: the cheer art is fitted to the walking
// sprite's own framing, so it is the same call with a different bitmap.
function drawBuddyCheering(ctx, index, cx, feetY, height, bob = 0) {
  const img = CHEER_IMAGES[index];
  if (!img || !img.complete || !img.naturalWidth) {
    drawBuddy(ctx, index, cx, feetY, height, bob);
    return;
  }
  contactShadow(ctx, cx, feetY + height * 0.02, height * 0.36, height * 0.055, 0.9);
  ctx.drawImage(img, cx - height / 2, feetY - height + bob, height, height);
}

function drawBuddy(ctx, index, cx, feetY, height, bob = 0, rotation = 0) {
  const img = BUDDY_IMAGES[index];
  if (!img || !img.complete) return;
  contactShadow(ctx, cx, feetY + height * 0.02, height * 0.36, height * 0.055, 0.9);
  if (!rotation) {
    ctx.drawImage(img, cx - height / 2, feetY - height + bob, height, height);
    return;
  }
  ctx.save();
  ctx.translate(cx, feetY - height / 2 + bob);
  ctx.rotate(rotation * Math.PI / 180);
  ctx.drawImage(img, -height / 2, -height / 2, height, height);
  ctx.restore();
}

/**
 * Anim.feast, enough of it to show the pose. The preview draws a still, so only the
 * offsets and the rotation matter -- the squash is velocity-driven in the app.
 */
/**
 * Anim.Temperament, as far as this page needs it.
 *
 * NOTE this file is NOT a port of Anim: it reproduces feastTransform and three inline
 * bobs (idle, walk, dance), not the seventeen-state table. What follows applies the
 * per-character dials to those three, which is enough to see a heavy trike against a
 * fluttering bee in a strip of frames. It is not enough to judge the motion -- that is
 * what SelfTest measures and what the APK shows.
 */
function bodyBob(buddy, t, amplitude, rate) {
  const lift = buddy.hover > 0
    ? -buddy.hover * (34 + 5 * Math.sin(t * buddy.tempo * 2.1))
      + buddy.hover * Math.sin(t * buddy.tempo * 19) * 1.6
    : 0;
  const dy = Math.sin(t * buddy.tempo * rate) * amplitude * buddy.bounce + lift;
  return buddy.hover > 0 ? Math.min(dy, -buddy.hover * 6) : dy;
}

function bodySway(buddy, t, amplitude, rate) {
  return Math.sin(t * buddy.tempo * rate) * amplitude * buddy.sway;
}

function feastTransform(kind, p, strength = 1) {
  const out = { dx: 0, dy: 0, rotation: 0 };
  if (p <= 0 || p >= 1) return out;
  const contact = 0.34;
  const hump = (q, peak) => q <= 0 || q >= 1 ? 0
    : Math.sin((q < peak ? q / peak : 1 - (q - peak) / (1 - peak)) * Math.PI * 0.5);
  switch (kind) {
    case 1: {                                    // sip
      const dip = hump(p, contact);
      out.dy = -30 + 46 * dip; out.dx = 12 * dip; out.rotation = 8 * dip; break;
    }
    case 2: {                                    // pounce
      if (p >= contact) {
        const j = (p - contact) / (1 - contact);
        out.dy = -110 * Math.sin(j * Math.PI);
        out.dx = 40 * Math.sin(j * Math.PI);
      }
      break;
    }
    case 3: {                                    // lunge
      const surge = hump(p, contact);
      out.dx = 88 * surge; out.rotation = 13 * surge; break;
    }
    case 4: {                                    // stomp
      if (p < contact) { const c = p / contact; out.rotation = -11 * c; out.dy = -26 * c; }
      else {
        const land = Math.min(1, (p - contact) / (1 - contact) * 3.2);
        out.rotation = -11 + 15 * land; out.dy = -26 + 26 * land;
      }
      break;
    }
    case 5: {                                    // spin
      const rise = hump(p, 0.5);
      out.dy = -58 * rise; out.dx = 18 * rise;
      out.rotation = 360 * (p < 0.5 ? 4 * p * p * p : 1 - Math.pow(-2 * p + 2, 3) / 2);
      break;
    }
    case 6: {                                    // nibble
      const nods = Math.sin(p * Math.PI * 6) * hump(p, 0.5);
      out.dy = -9 * Math.abs(nods); out.dx = 20 * hump(p, contact);
      out.rotation = 6 * nods; break;
    }
    default: {                                   // bite
      const lean = hump(p, contact);
      out.dx = 46 * lean; out.rotation = 9 * lean; break;
    }
  }
  const k = clamp(strength, 0, 1);
  out.dx *= k; out.dy *= k; out.rotation *= k;
  return out;
}

/* ====================================================================== HOME */

function screenHome(ctx, L, buddy, t) {
  const scene = buildScene(L.play, buddy.index, M_HOME, buddy.light, BACKDROPS.home);
  drawSceneBackground(ctx, scene, buddy, t);

  // wordmark
  const wm = L.wordmark;
  const size = Math.min(rh(wm) * 0.46, DATA.metrics.designWidth * 0.13);
  wordmark(ctx, 'Morning', rcx(wm), wm[1] + rh(wm) * 0.30, size, '#1857A5');
  wordmarkLetters(ctx, 'Mission', rcx(wm), wm[1] + rh(wm) * 0.74, size * 1.05, LOGO);
  ctx.strokeStyle = '#FFC94A';
  ctx.lineWidth = size * 0.11;
  ctx.lineCap = 'round';
  const sw = size * 2.6;
  ctx.beginPath();
  ctx.ellipse(rcx(wm), (wm[3] - size * 0.55 + wm[3] + size * 0.30) / 2, sw, (size * 0.85) / 2,
              0, 20 * Math.PI / 180, 160 * Math.PI / 180);
  ctx.stroke();

  drawBuddy(ctx, buddy.index, rcx(L.buddySlot), L.buddySlot[3] - rh(L.buddySlot) * 0.06,
            rh(L.buddySlot) * 0.82, bodyBob(buddy, t, 7, 1.15));

  // minute bubbles
  text(ctx, 'MINUTES', rcx(L.minutesLabel), rcy(L.minutesLabel), DATA.type.c1, '#5C7086', 'center', true);
  for (let i = 0; i < 7; i++) {
    const box = L.minuteBubble[i];
    const on = MINUTES[i] === 15;
    const scale = 0.60 + 0.40 * (i / 6);
    let radius = Math.min(rw(box), rh(box)) * 0.5 * scale;
    const dr = drift(i, t, driftLimit(box, radius));
    radius *= dr.scale;
    const cx = rcx(box) + dr.dx, cy = rcy(box) + dr.dy;
    contactShadow(ctx, cx, cy + radius * 0.85, radius * 0.8, radius * 0.28, 0.9);
    ctx.fillStyle = on ? buddy.primary : BUBBLE[i];
    ctx.beginPath(); ctx.arc(cx, cy, radius, 0, Math.PI * 2); ctx.fill();
    glossCircle(ctx, cx, cy, radius, 1);
    if (on) {
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = radius * 0.16;
      ctx.beginPath(); ctx.arc(cx, cy, radius * 1.06, 0, Math.PI * 2); ctx.stroke();
    }
    label(ctx, String(MINUTES[i]), cx, cy, Math.max(20, radius * 0.78),
          on ? '#ffffff' : '#173C79', 'center');
  }

  drawSceneForeground(ctx, scene, t, true);

  glyphChip(ctx, 'gear', L.gearChip, 'rgba(255,255,255,.97)', buddy.ink);
  const gu = L.grownUpsChip;
  card(ctx, gu, rh(gu) / 2, 'rgba(255,255,255,.97)');
  const gSize = rh(gu) * 0.46;
  const gWidth = measureLabel(ctx, 'Grown-Ups', DATA.type.b1);
  let gx = rcx(gu) - (gSize + 12 + gWidth) / 2;
  drawGlyph(ctx, 'people', gx + gSize / 2, rcy(gu), gSize, buddy.ink);
  label(ctx, 'Grown-Ups', gx + gSize + 12, rcy(gu), DATA.type.b1, buddy.ink, 'left');

  // timer card
  const tc = L.timerCard;
  card(ctx, tc, rh(tc) * 0.30, 'rgba(255,255,255,.98)');
  drawTime(ctx, 15 * 60000, rcx(tc), rcy(tc) - rh(tc) * 0.10,
           Math.min(DATA.type.d1, rh(tc) * 0.50), DATA.tokens.ink, 'center');
  text(ctx, 'Tap to customise', rcx(tc), tc[3] - rh(tc) * 0.19,
       DATA.type.b2, DATA.tokens.inkMuted, 'center', false);
  drawGlyph(ctx, 'pencil', rcx(L.timerPencil), rcy(L.timerPencil),
            Math.min(rw(L.timerPencil), rh(L.timerPencil)) * 0.62, buddy.accent);

  // routine header
  label(ctx, "Today's Mission", L.routineHeader[0], rcy(L.routineHeader),
        DATA.type.h2, DATA.tokens.ink, 'left');
  const ec = L.editChip;
  card(ctx, ec, rh(ec) / 2, 'rgba(255,255,255,.96)');
  const eSize = rh(ec) * 0.44;
  const eWidth = measureLabel(ctx, 'Edit', DATA.type.b1);
  const ex = rcx(ec) - (eSize + 10 + eWidth) / 2;
  drawGlyph(ctx, 'pencil', ex + eSize / 2, rcy(ec), eSize, buddy.ink);
  label(ctx, 'Edit', ex + eSize + 10, rcy(ec), DATA.type.b1, buddy.ink, 'left');

  // task rows -- first two done, third active, as a real morning in progress
  ctx.save();
  ctx.beginPath();
  ctx.rect(L.taskBand[0], L.taskBand[1], rw(L.taskBand), rh(L.taskBand));
  ctx.clip();
  for (let i = 0; i < L.taskRowCount; i++) {
    const row = L.taskRow[i];
    if (row[3] < L.taskBand[1] || row[1] > L.taskBand[3]) continue;
    const task = DATA.defaultRoutine[i];
    const done = i < 2, active = i === 2;
    card(ctx, row, rh(row) * 0.28, done ? '#EAF9EA' : active ? '#FFF3C9' : 'rgba(255,255,255,.98)');
    const pad = rh(row) * 0.14;
    const iconSize = rh(row) - pad * 2;
    activityChip(ctx, task.kind, buddy, row[0] + pad + iconSize / 2, rcy(row), iconSize, done);
    const checkSize = rh(row) * 0.42;
    const textLeft = row[0] + pad * 2 + iconSize;
    const textRight = row[2] - pad * 1.4 - checkSize;
    fitText(ctx, task.name, [textLeft, row[1] + pad * 0.5, textRight, rcy(row)],
            rowTitleSize(row), 25, done ? '#4A6B57' : DATA.tokens.ink, 'left', true);
    fitText(ctx, done ? 'Done!' : task.subtitle,
            [textLeft, rcy(row) + rh(row) * 0.03, textRight, row[3] - pad * 0.6],
            rowSubtitleSize(row), 17, done ? DATA.tokens.successDeep : DATA.tokens.inkMuted,
            'left', false);
    const ccx = row[2] - pad - checkSize / 2;
    if (done) {
      ctx.fillStyle = DATA.tokens.success;
      ctx.beginPath(); ctx.arc(ccx, rcy(row), checkSize / 2, 0, Math.PI * 2); ctx.fill();
      drawGlyph(ctx, 'check', ccx, rcy(row), checkSize * 0.62, '#ffffff');
    } else {
      ctx.strokeStyle = active ? DATA.tokens.warn : '#D3DCE8';
      ctx.lineWidth = checkSize * 0.11;
      ctx.beginPath(); ctx.arc(ccx, rcy(row), checkSize * 0.44, 0, Math.PI * 2); ctx.stroke();
    }
  }
  ctx.restore();

  // start button
  const sb = L.startBtn;
  button(ctx, sb, rh(sb) / 2, DATA.tokens.cta, DATA.tokens.ctaDeep, 1);
  const pSize = rh(sb) * 0.40;
  const pRoom = labelRoom(sb, pSize + 20);
  const pPx = labelSize(ctx, 'START MORNING', buttonLabelSize(sb), 16, pRoom);
  const pWidth = measureLabel(ctx, 'START MORNING', pPx);
  const px = rcx(sb) - (pSize + 20 + pWidth) / 2;
  drawGlyph(ctx, 'play', px + pSize / 2, rcy(sb), pSize, '#ffffff');
  label(ctx, 'START MORNING', px + pSize + 20, rcy(sb), pPx, '#ffffff', 'left');

  // nav
  card(ctx, L.navBar, rh(L.navBar) * 0.38, 'rgba(255,255,255,.97)');
  for (let i = 0; i < 4; i++) {
    const item = L.navItem[i];
    const colour = i === 0 ? buddy.primary : DATA.tokens.inkFaint;
    drawGlyph(ctx, NAV_GLYPH[i], rcx(item), rcy(item) - rh(item) * 0.12, rh(item) * 0.34, colour);
    text(ctx, NAV[i], rcx(item), item[3] - rh(item) * 0.20,
         clamp(rh(item) * 0.19, 16, 30), colour, 'center', i === 0);
  }
}

/* ================================================================= ADVENTURE */

function screenAdventure(ctx, L, buddy, t) {
  const scene = buildScene(L.play, buddy.index, M_ADVENTURE, buddy.light, BACKDROPS.adventure[buddy.index]);
  drawSceneBackground(ctx, scene, buddy, t);

  // The trail: the buddy walking along it, with the next few collectibles laid out
  // ahead. The whole walk-up-and-eat cycle is driven off the clock exactly as
  // Engine.feastBeat drives it, on a four-second segment, so with motion on the preview
  // actually plays the loop instead of freezing one arbitrary frame of it.
  const trail = L.advTrail;
  const total = 12;
  const SEGMENT = 4.0;                          // seconds between collectibles here
  const since = t % SEGMENT;                    // seconds since the last one
  const until = SEGMENT - since;
  // Eight steps, the last of which lands on the chest, so the preview loop actually
  // plays the finale instead of circling three collectibles short of it.
  const STEPS = 8;
  const step = Math.floor(t / SEGMENT) % STEPS;
  const collected = Math.min(total, total - (STEPS - 1) + step);
  const atPrize = collected >= total;
  const prizeTurn = atPrize ? Math.min(1, since / 1.4) : 0;    // PRIZE_SECONDS
  const fraction = since / SEGMENT;
  // No feast on the chest: it is danced at, not eaten, so the beat stops at the prize.
  const beat = atPrize ? -1
             : until <= FEAST_LEAD ? (FEAST_LEAD - until) / FEAST_SECONDS
             : (since + FEAST_LEAD) / FEAST_SECONDS < 1
               ? (since + FEAST_LEAD) / FEAST_SECONDS : -1;
  const progress = 0.20 + 0.58 * Math.min(1, (step + (atPrize ? 0 : fraction)) / (STEPS - 1));
  const walkX = trail[0] + rw(trail) * progress;
  const height = Math.min(rh(L.advScene) * 0.46, DATA.metrics.designWidth * 0.42);

  const cSize = Math.min(rh(L.advScene) * 0.20, DATA.metrics.designWidth * 0.19);
  const spacing = cSize * 1.35;
  const ground = rcy(trail) - height * 0.38;
  const itemBase = walkX + height * 0.30;
  const itemY = index => ground + Math.sin(t * 1.6 + index) * cSize * 0.06;
  for (let k = 1; k <= 4; k++) {
    const index = collected + k;
    if (index < 1 || index >= total) continue;   // the last one is the chest
    const x = itemBase + (k - fraction) * spacing;
    if (x < -cSize || x > trail[2] + cSize * 0.35) continue;
    drawCollectible(ctx, buddy.index, x, itemY(index), cSize, true, 0, false);
  }

  const goalBox = L.advGoal;
  const goalSize = Math.min(rw(goalBox), rh(goalBox));
  drawGoal(ctx, buddy.index, rcx(goalBox), rcy(goalBox), goalSize, prizeTurn,
           Math.min(1, prizeTurn * 2.2));
  if (!atPrize) {
    const lock = goalSize * 0.22;
    const lockY = rcy(goalBox) + goalSize * 0.08;
    ctx.fillStyle = 'rgba(80,98,125,.80)';
    ctx.beginPath(); ctx.arc(rcx(goalBox), lockY, lock, 0, Math.PI * 2); ctx.fill();
    drawGlyph(ctx, 'lock', rcx(goalBox), lockY, goalSize * 0.26, '#ffffff');
  } else if (prizeTurn < 1) {
    // The star climbing out, matching ScreenAdventure.drawGoal.
    const rise = Math.sin(prizeTurn * Math.PI * 0.5);
    const a = prizeTurn < 0.72 ? 1 : Math.max(0, 1 - (prizeTurn - 0.72) / 0.28);
    drawProp(ctx, 'star', rcx(goalBox),
             rcy(goalBox) - goalSize * (0.06 + 0.72 * rise),
             goalSize * (0.30 + 0.30 * rise), Math.sin(prizeTurn * 7) * 12, a);
  }

  // Three goes at the item, each less committed than the last.
  let chompP = -1, chompStrength = 1;
  if (beat >= 0) {
    const eaten = eatPhase(beat);
    if (eaten > 0 && eaten < BITE_COUNT) {
      chompP = eaten - Math.floor(eaten);
      chompStrength = 1 - 0.21 * Math.min(Math.floor(eaten), BITE_COUNT - 1);
    }
  }
  const feast = feastTransform(buddy.feastKind, chompP, chompStrength);
  const bx = walkX + bodySway(buddy, t, rw(L.advScene) * 0.016, 1.5) + feast.dx;
  drawBuddy(ctx, buddy.index, bx, rcy(trail), height,
            bodyBob(buddy, t, 8, 2.6) + feast.dy, feast.rotation);

  if (beat >= 0 && beat < 0.72) {
    const bw = DATA.metrics.designWidth * 0.24, bh = bw * 0.42;
    let bub = [bx + height * 0.22, rcy(trail) - height - bh * 0.4,
               bx + height * 0.22 + bw, rcy(trail) - height + bh * 0.6];
    if (bub[2] > DATA.metrics.designWidth - 20) {
      const d = DATA.metrics.designWidth - 20 - bub[2];
      bub = [bub[0] + d, bub[1], bub[2] + d, bub[3]];
    }
    card(ctx, bub, rh(bub) * 0.42, 'rgba(255,255,255,.97)');
    fitText(ctx, buddy.munchWord, bub, DATA.type.t2, 14, buddy.ink, 'center', true);
  }

  // The item in the buddy's mouth, drawn over it: the bites come out of the side the
  // buddy is standing on, so behind it nothing missing would ever show.
  if (collected >= 1 && collected <= total) {
    const bites = biteAt(beat);
    if (bites < BITE_COUNT) {
      const lane = beat >= 0 ? 0 : -fraction;
      const eaten = eatPhase(beat);
      const pop = beat < 0 ? 0 : Math.max(0, 1 - Math.abs(eaten - bites) * 6);
      drawCollectible(ctx, buddy.index, itemBase + lane * spacing, itemY(collected),
                      cSize, true, pop, false, bites);
    }
  }

  drawSceneForeground(ctx, scene, t, true);

  glyphChip(ctx, 'chevron-left', L.advBack, 'rgba(255,255,255,.92)', buddy.ink);
  glyphChip(ctx, 'pause', L.advPause, 'rgba(255,255,255,.92)', buddy.ink);
  // The quick mute, under the pause chip. Shown on here so the state can be looked at;
  // the preview has no sound, so this is the only thing about it that can be checked.
  glyphChip(ctx, 'speaker', L.advMute, 'rgba(255,255,255,.92)', buddy.ink);

  const title = L.advTitle;
  const tSize = Math.min(rh(title) * 0.44, DATA.metrics.designWidth * 0.062);
  wordmark(ctx, 'Buddy', rcx(title), title[1] + rh(title) * 0.32, tSize, '#1857A5');
  wordmark(ctx, 'Adventure!', rcx(title), title[1] + rh(title) * 0.78, tSize * 1.06, '#FFF06A');

  const clock = L.advClock;
  card(ctx, clock, rh(clock) * 0.34, 'rgba(255,255,255,.97)');
  drawTime(ctx, 564000, rcx(clock), rcy(clock) - rh(clock) * 0.11,
           Math.min(DATA.type.d1, rh(clock) * 0.52), DATA.tokens.ink, 'center');
  text(ctx, 'Keep going!', rcx(clock), clock[3] - rh(clock) * 0.19,
       DATA.type.b2, DATA.tokens.inkMuted, 'center', true);

  drawTreatBoard(ctx, L, buddy, total, collected, t);

  // the tally: one collectible at a size you can see, and the count
  const band = L.advTally;
  const tallyLabel = `${collected} of ${total} ${buddy.collectMany}`;
  const tIcon = rh(band) * 0.86;
  const tText = Math.min(DATA.type.t2, rh(band) * 0.44);
  setFont(ctx, tText, true);
  const tWidth = ctx.measureText(tallyLabel).width;
  const chipW = Math.min(rw(band), tIcon + 16 + tWidth + rh(band) * 0.9);
  const chip = [rcx(band) - chipW / 2, band[1], rcx(band) + chipW / 2, band[3]];
  card(ctx, chip, rh(chip) / 2, 'rgba(255,255,255,.95)');
  gloss(ctx, chip, rh(chip) / 2, 0.7);
  const tStart = rcx(chip) - (tIcon + 16 + tWidth) / 2;
  drawCollectible(ctx, buddy.index, tStart + tIcon / 2, rcy(chip), tIcon, true, 0, false);
  text(ctx, tallyLabel, tStart + tIcon + 16, rcy(chip), tText, buddy.ink, 'left', true);

  // progress
  const track = L.advProgress;
  const radius = rh(track) / 2;
  ctx.fillStyle = 'rgba(255,255,255,.35)';
  roundRect(ctx, track, radius); ctx.fill();
  ctx.fillStyle = buddy.primary;
  roundRect(ctx, [track[0], track[1], track[0] + rw(track) * (collected / total), track[3]], radius);
  ctx.fill();

  // current task
  const tcard = L.advTaskCard;
  card(ctx, tcard, rh(tcard) * 0.26, 'rgba(255,255,255,.97)');
  const pad = rh(tcard) * 0.16;
  const iconSize = rh(tcard) - pad * 2;
  const task = DATA.defaultRoutine[2];
  activityChip(ctx, task.kind, buddy, tcard[0] + pad + iconSize / 2, rcy(tcard), iconSize, false);
  const textLeft = tcard[0] + pad * 2 + iconSize;
  const counter = '3 of 5';
  const counterSize = rowSubtitleSize(tcard);
  setFont(ctx, counterSize, true);
  const counterWidth = ctx.measureText(counter).width + pad;
  fitText(ctx, task.name, [textLeft, tcard[1] + pad * 0.5, tcard[2] - pad - counterWidth, rcy(tcard) + rh(tcard) * 0.04],
          rowTitleSize(tcard), 22, DATA.tokens.ink, 'left', true);
  fitText(ctx, task.subtitle, [textLeft, rcy(tcard) + rh(tcard) * 0.06, tcard[2] - pad - counterWidth, tcard[3] - pad * 0.5],
          rowSubtitleSize(tcard), 16, DATA.tokens.inkMuted, 'left', false);
  text(ctx, counter, tcard[2] - pad, rcy(tcard), counterSize, buddy.ink, 'right', true);

  // action
  const act = L.advAction;
  button(ctx, act, rh(act) / 2, DATA.tokens.success, darken(DATA.tokens.success, 0.22), 1);
  const aSize = rh(act) * 0.40;
  const aRoom = labelRoom(act, aSize + 20);
  const aPx = labelSize(ctx, 'I DID IT!', buttonLabelSize(act), 16, aRoom);
  const aWidth = measureLabel(ctx, 'I DID IT!', aPx);
  const ax = rcx(act) - (aSize + 20 + aWidth) / 2;
  drawGlyph(ctx, 'check', ax + aSize / 2, rcy(act), aSize, '#ffffff');
  label(ctx, 'I DID IT!', ax + aSize + 20, rcy(act), aPx, '#ffffff', 'left');
}

/* ================================================================== COMPLETE */

function screenComplete(ctx, L, buddy, t) {
  const scene = buildScene(L.play, buddy.index, M_CELEBRATE, buddy.light);
  drawSceneBackground(ctx, scene, buddy, t);

  const title = L.cmpTitle;
  const size = Math.min(rh(title) * 0.44, DATA.metrics.designWidth * 0.075);
  wordmark(ctx, 'Mission', rcx(title), title[1] + rh(title) * 0.32, size, '#2879ED');
  wordmark(ctx, 'Complete!', rcx(title), title[1] + rh(title) * 0.80, size * 1.08, '#EC4777');

  const stage = L.cmpStage;
  const goalSize = Math.min(rh(stage) * 0.42, DATA.metrics.designWidth * 0.30);
  drawGoal(ctx, buddy.index, stage[2] - goalSize * 0.62, stage[3] - goalSize * 0.55,
           goalSize, 1, 0.75 + 0.25 * Math.sin(t * 2));

  const height = Math.min(rh(stage) * 0.80, DATA.metrics.designWidth * 0.50);
  const cx = stage[0] + rw(stage) * 0.40;
  const feet = stage[3] - rh(stage) * 0.06;
  const bob = bodyBob(buddy, t, 20, 6.4);
  drawBuddyCheering(ctx, buddy.index, cx, feet, height, bob);
  const crest = feet - height * (1 - cheerTopFraction(buddy.index));
  drawGlyph(ctx, 'crown', cx, crest + bob - height * 0.06, height * 0.26, DATA.tokens.gold);

  for (let i = 0; i < 7; i++) {
    const ang = t * 0.8 + i * 0.9;
    const radius = height * (0.52 + 0.10 * Math.sin(t * 1.3 + i));
    const twinkle = 0.4 + 0.6 * Math.abs(Math.sin(t * 2.2 + i));
    drawGlyph(ctx, 'star', cx + Math.cos(ang) * radius,
              feet - height * 0.55 + Math.sin(ang) * radius * 0.55,
              height * 0.075 * twinkle, alpha(DATA.tokens.gold, twinkle * 0.86));
  }

  drawSceneForeground(ctx, scene, t, false);
  drawConfetti(ctx, L.play, buddy, t);

  const box = L.cmpCard;
  card(ctx, box, rh(box) * 0.20, 'rgba(255,255,255,.98)');
  text(ctx, 'You finished with', rcx(box), box[1] + rh(box) * 0.20,
       DATA.type.b1, DATA.tokens.inkMuted, 'center', false);
  drawTime(ctx, 222000, rcx(box), box[1] + rh(box) * 0.50,
           Math.min(DATA.type.d1, rh(box) * 0.36), DATA.tokens.ink, 'center');
  text(ctx, 'left on the clock!', rcx(box), box[1] + rh(box) * 0.72,
       DATA.type.b1, DATA.tokens.inkMuted, 'center', false);
  fitText(ctx, "Amazing! You're a Morning Hero!",
          [box[0] + rw(box) * 0.06, box[3] - rh(box) * 0.24, box[2] - rw(box) * 0.06, box[3] - rh(box) * 0.04],
          DATA.type.b1, 13, buddy.ink, 'center', true);

  labelledButton(ctx, L.cmpPlayAgain, 'refresh', 'Play Again',
                 DATA.tokens.success, DATA.tokens.successDeep, '#ffffff', 1);
  labelledButton(ctx, L.cmpBackHome, 'home', 'Back to Home', '#ffffff', '#DCE6F2', buddy.ink);
}

function labelledButton(ctx, r, g, text, face, edge, colour, glow = 0) {
  button(ctx, r, rh(r) / 2, face, edge, glow);
  const size = rh(r) * 0.38;
  const room = labelRoom(r, size + 18);
  const px = labelSize(ctx, text, buttonLabelSize(r), 16, room);
  const width = measureLabel(ctx, text, px);
  const x = rcx(r) - (size + 18 + width) / 2;
  drawGlyph(ctx, g, x + size / 2, rcy(r), size, colour);
  label(ctx, text, x + size + 18, rcy(r), px, colour, 'left');
}

/** A still frame of the particle system: two corner cannons under gravity. */
function drawConfetti(ctx, area, buddy, t) {
  const palette = [buddy.primary, buddy.accent, DATA.tokens.gold, '#FF6B6B', '#5ED6F2', '#9B7BFF'];
  const w = rw(area), h = rh(area);
  let seed = 1;
  const rnd = () => { seed = (Math.imul(seed, 1103515245) + 12345) | 0; return ((seed >>> 8) & 0xFFFFFF) / 0xFFFFFF; };
  for (let cannon = 0; cannon < 2; cannon++) {
    const ox = cannon === 0 ? area[0] + w * 0.06 : area[2] - w * 0.06;
    const base = cannon === 0 ? -68 : -112;
    for (let i = 0; i < 60; i++) {
      const ang = (base + (rnd() - 0.5) * 44) * Math.PI / 180;
      const speed = 1500 + rnd() * 800;
      const age = 0.45 + rnd() * 1.5;
      const drag = (1 - Math.exp(-1.6 * age)) / 1.6;
      const x = ox + Math.cos(ang) * speed * drag + (rnd() - 0.5) * 28;
      const y = area[3] - h * 0.10 + Math.sin(ang) * speed * drag + 450 * age * age;
      if (y < area[1] - 40 || y > area[3] + 40) continue;
      const size = 11 + rnd() * 15;
      ctx.save();
      ctx.translate(x, y);
      ctx.rotate(rnd() * Math.PI * 2 + t);
      ctx.fillStyle = palette[Math.floor(rnd() * palette.length) % palette.length];
      const hh = size * (0.35 + rnd() * 0.5) / 2;
      ctx.beginPath(); ctx.roundRect(-size / 2, -hh, size, hh * 2, hh * 0.5); ctx.fill();
      ctx.restore();
    }
  }
}

/* ============================================================== BUDDY PICKER */

function screenBuddyPicker(ctx, L, buddy, t) {
  const scene = buildScene(L.play, buddy.index, M_HOME, buddy.light, BACKDROPS.home);
  drawSceneBackground(ctx, scene, buddy, t);
  drawSceneForeground(ctx, scene, t, false);
  ctx.fillStyle = DATA.tokens.scrim;
  ctx.fillRect(0, 0, DATA.metrics.designWidth, L.height);

  card(ctx, L.pickSheet, rw(L.pickSheet) * 0.05, '#FBFDFF');
  const title = L.pickTitle;
  wordmark(ctx, 'Choose Your Buddy', rcx(title), title[1] + rh(title) * 0.42,
           Math.min(rh(title) * 0.34, DATA.metrics.designWidth * 0.052), '#1D447E');
  text(ctx, 'Tap a buddy to hear them say hello', rcx(title), title[3] - rh(title) * 0.22,
       DATA.type.b1, DATA.tokens.inkMuted, 'center', false);
  glyphChip(ctx, 'close', L.pickClose, '#EDF3FA', DATA.tokens.inkMuted);

  for (let i = 0; i < DATA.buddies.length; i++) {
    const b = DATA.buddies[i];
    const box = L.buddyCard[i];
    const selected = i === buddy.index;
    card(ctx, box, rh(box) * 0.14, mix(b.light, '#ffffff', selected ? 0.05 : 0.30));
    if (selected) {
      ctx.strokeStyle = b.primary;
      ctx.lineWidth = rh(box) * 0.028;
      roundRect(ctx, box, rh(box) * 0.14); ctx.stroke();
    }
    const art = rh(box) * 0.52;
    drawBuddy(ctx, i, rcx(box), box[1] + rh(box) * 0.66, art,
              Math.sin(t * 6.4 + i * 0.7) * art * 0.05);
    text(ctx, b.name, rcx(box), box[3] - rh(box) * 0.27,
         Math.min(DATA.type.t2, rw(box) * 0.10), b.ink, 'center', true);

    const chipH = rh(box) * 0.13;
    setFont(ctx, DATA.type.b2, true);
    const labelW = ctx.measureText(b.soundWord).width;
    const chipW = chipH + 14 + labelW + chipH * 0.6;
    const cy = box[3] - rh(box) * 0.115;
    const chip = [rcx(box) - chipW / 2, cy - chipH / 2, rcx(box) + chipW / 2, cy + chipH / 2];
    ctx.fillStyle = '#ffffff';
    roundRect(ctx, chip, chipH / 2); ctx.fill();
    drawGlyph(ctx, 'speaker', chip[0] + chipH * 0.72, cy, chipH * 0.62, b.accent);
    text(ctx, b.soundWord, chip[0] + chipH * 1.2, cy, DATA.type.b2, b.ink, 'left', true);

    if (selected) {
      const badge = rw(box) * 0.10;
      ctx.fillStyle = b.primary;
      ctx.beginPath(); ctx.arc(box[2] - badge * 1.1, box[1] + badge * 1.1, badge, 0, Math.PI * 2); ctx.fill();
      drawGlyph(ctx, 'check', box[2] - badge * 1.1, box[1] + badge * 1.1, badge * 1.2, '#ffffff');
    }
  }

  const confirm = L.pickConfirm;
  button(ctx, confirm, rh(confirm) / 2, buddy.primary, darken(buddy.primary, 0.22));
  labelFit(ctx, 'Use ' + buddy.name,
           [confirm[0] + rh(confirm) * 0.25, confirm[1], confirm[2] - rh(confirm) * 0.25, confirm[3]],
           buttonLabelSize(confirm), 18, '#ffffff', 'center');
}

/** ScreenAdventure.drawTreatBoard: every treat in the morning, in rows. */
function drawTreatBoard(ctx, L, buddy, total, collected, t) {
  if (total <= 0) return;
  const top = L.advTally[3] + 24;
  const bottom = rcy(L.advTrail)
    - Math.min(rh(L.advScene) * 0.46, DATA.metrics.designWidth * 0.42) - 24;
  const left = L.advScene[0] + 70, right = L.advScene[2] - 70;
  if (bottom - top < 60 || right - left < 60) return;

  const boardW = right - left, boardH = bottom - top;
  const maxCell = DATA.metrics.designWidth * 0.115 / 0.82;
  let bestCols = 1, bestCell = 0;
  for (let c2 = 1; c2 <= total; c2++) {
    const rows = Math.ceil(total / c2);
    const cell = Math.min(boardW / c2, boardH / rows);
    if (cell > bestCell) { bestCell = cell; bestCols = c2; }
  }
  const target = Math.min(bestCell, maxCell);
  let cols = bestCols;
  for (let c2 = total; c2 >= 1; c2--) {
    if (Math.min(boardW / c2, boardH / Math.ceil(total / c2)) >= target - 0.01) {
      cols = c2; break;
    }
  }
  const rows = Math.ceil(total / cols);
  cols = Math.ceil(total / rows);          // even the rows out
  const size = Math.min(Math.min(boardW / cols, boardH / rows) * 0.82,
                        DATA.metrics.designWidth * 0.115);
  const cellW = boardW / cols, cellH = Math.min(boardH / rows, size * 1.5);
  const gridTop = top + (boardH - cellH * rows) / 2;

  for (let i = 0; i < total; i++) {
    const row = Math.floor(i / cols), col = i % cols;
    const inRow = Math.min(cols, total - row * cols);
    const rowLeft = left + (boardW - inRow * cellW) / 2;
    const x = rowLeft + col * cellW + cellW / 2;
    const y = gridTop + row * cellH + cellH / 2 + Math.sin(t * 1.3 + i * 0.7) * size * 0.05;
    const got = i < collected;
    if (i === total - 1) {
      // The last tile is the chest, matching the goal at the end of the lane.
      drawProp(ctx, got ? 'chest_3' : 'chest_0', x, y, size * 1.15, 0,
               got ? 1 : 105 / 255);
    } else {
      drawCollectible(ctx, buddy.index, x, y, size, got, 0, false);
    }
  }
}

/* =============================================================== TIME PICKER */

function screenTimePicker(ctx, L, buddy, t) {
  const scene = buildScene(L.play, buddy.index, M_HOME, buddy.light, BACKDROPS.home);
  drawSceneBackground(ctx, scene, buddy, t);
  drawSceneForeground(ctx, scene, t, false);
  ctx.fillStyle = DATA.tokens.scrim;
  ctx.fillRect(0, 0, DATA.metrics.designWidth, L.height);

  card(ctx, L.timeSheet, rw(L.timeSheet) * 0.05, '#F7FCFF');
  const title = L.timeTitle;
  wordmark(ctx, 'Customise Time', rcx(title), title[1] + rh(title) * 0.44,
           Math.min(rh(title) * 0.36, DATA.metrics.designWidth * 0.055), '#2473D4');
  text(ctx, 'Pick a bubble, nudge it, or slide to any time', rcx(title),
       title[3] - rh(title) * 0.20, DATA.type.b1, DATA.tokens.inkMuted, 'center', false);
  glyphChip(ctx, 'close', L.timeClose, '#EDF3FA', DATA.tokens.inkMuted);

  const display = L.timeDisplay;
  card(ctx, display, rh(display) * 0.30, '#ffffff');
  drawTime(ctx, 25 * 60000, rcx(display), rcy(display),
           Math.min(DATA.type.d1, rh(display) * 0.50), DATA.tokens.ink, 'center');
  glyphChip(ctx, 'minus', L.timeMinus, '#ffffff', buddy.primary);
  glyphChip(ctx, 'plus', L.timePlus, '#ffffff', buddy.primary);

  for (let i = 0; i < PRESETS.length; i++) {
    const box = L.presetBubble[i];
    if (!box) continue;
    const on = PRESETS[i] === 900;
    const scale = 0.62 + 0.38 * (i / (PRESETS.length - 1));
    let radius = Math.min(rw(box), rh(box)) * 0.5 * scale;
    const dr = drift(i, t, driftLimit(box, radius));
    radius *= dr.scale;
    const px = rcx(box) + dr.dx, py = rcy(box) + dr.dy;
    contactShadow(ctx, px, py + radius * 0.85, radius * 0.8, radius * 0.28, 0.85);
    ctx.fillStyle = on ? buddy.primary : PRESET_COLORS[i % PRESET_COLORS.length];
    ctx.beginPath(); ctx.arc(px, py, radius, 0, Math.PI * 2); ctx.fill();
    glossCircle(ctx, px, py, radius, 1);
    if (on) {
      ctx.strokeStyle = '#ffffff';
      ctx.lineWidth = radius * 0.16;
      ctx.beginPath(); ctx.arc(px, py, radius * 1.06, 0, Math.PI * 2); ctx.stroke();
    }
    const pl = PRESETS[i] < 60 ? PRESETS[i] + 's' : String(PRESETS[i] / 60);
    label(ctx, pl, px, py, Math.max(19, radius * (PRESETS[i] < 60 ? 0.56 : 0.76)),
          on ? '#ffffff' : '#4A3A22', 'center');
  }

  const slider = L.timeSlider;
  const cy = rcy(slider);
  const trackH = Math.max(14, rh(slider) * 0.18);
  ctx.fillStyle = '#D7E4F3';
  roundRect(ctx, [slider[0], cy - trackH / 2, slider[2], cy + trackH / 2], trackH / 2); ctx.fill();
  const fraction = (25 - 1) / 119;
  const knobX = slider[0] + rw(slider) * fraction;
  ctx.fillStyle = buddy.primary;
  roundRect(ctx, [slider[0], cy - trackH / 2, knobX, cy + trackH / 2], trackH / 2); ctx.fill();
  const knob = rh(slider) * 0.42;
  contactShadow(ctx, knobX, cy + knob * 0.8, knob * 0.8, knob * 0.3);
  ctx.fillStyle = '#ffffff';
  ctx.beginPath(); ctx.arc(knobX, cy, knob, 0, Math.PI * 2); ctx.fill();
  ctx.fillStyle = buddy.primary;
  ctx.beginPath(); ctx.arc(knobX, cy, knob * 0.42, 0, Math.PI * 2); ctx.fill();
  const scaleSize = clamp(rh(slider) * 0.26, 20, 34);
  text(ctx, '0:15', slider[0], slider[3] + rh(slider) * 0.24, scaleSize,
       DATA.tokens.inkMuted, 'center', true);
  text(ctx, '120:00', slider[2], slider[3] + rh(slider) * 0.24, scaleSize,
       DATA.tokens.inkMuted, 'center', true);

  const set = L.timeSet;
  button(ctx, set, rh(set) / 2, buddy.primary, darken(buddy.primary, 0.22));
  label(ctx, 'Set Time', rcx(set), rcy(set),
        labelSize(ctx, 'Set Time', buttonLabelSize(set), 18, labelRoom(set, 0)),
        '#ffffff', 'center');
}

/* ================================================================= GROWN-UPS */

function screenGrownUps(ctx, L, buddy, t) {
  const scene = buildScene(L.play, buddy.index, M_HOME, buddy.light, BACKDROPS.home);
  drawSceneBackground(ctx, scene, buddy, t);
  drawSceneForeground(ctx, scene, t, false);
  ctx.fillStyle = 'rgba(244,248,253,.91)';
  ctx.fillRect(0, 0, DATA.metrics.designWidth, L.height);

  glyphChip(ctx, 'chevron-left', L.guBack, '#ffffff', buddy.ink);
  labelFit(ctx, 'Grown-Ups', L.guTitle, DATA.type.h1, 22, DATA.tokens.ink, 'center');

  ctx.save();
  ctx.beginPath();
  ctx.rect(L.guBand[0], L.guBand[1], rw(L.guBand), rh(L.guBand));
  ctx.clip();
  for (let i = 0; i < DATA.grownUpRows.length; i++) {
    const row = L.guRow[i];
    if (row[3] < L.guBand[1] || row[1] > L.guBand[3]) continue;
    const spec = DATA.grownUpRows[i];
    card(ctx, row, rh(row) * 0.26, '#ffffff');
    const pad = rh(row) * 0.18;
    const bead = rh(row) - pad * 2;
    const bx = row[0] + pad + bead / 2;
    ctx.fillStyle = mix(buddy.light, '#ffffff', 0.2);
    ctx.beginPath(); ctx.arc(bx, rcy(row), bead / 2, 0, Math.PI * 2); ctx.fill();
    drawGlyph(ctx, DATA.art.glyphs[spec.glyph].name, bx, rcy(row), bead * 0.54, buddy.ink);

    let value = spec.value;
    if (value === '@buddy') value = buddy.name;
    if (value === '@minutes') value = '15 min';
    const chevron = rh(row) * 0.26;
    const valueSize = rowSubtitleSize(row);
    setFont(ctx, valueSize, true);
    const valueWidth = value ? ctx.measureText(value).width + 18 : 0;
    fitText(ctx, spec.label, [bx + bead / 2 + pad, row[1], row[2] - pad - chevron - valueWidth, row[3]],
            rowTitleSize(row), 24, DATA.tokens.ink, 'left', true);
    if (value) {
      const on = value === 'On';
      text(ctx, value, row[2] - pad - chevron - 12, rcy(row), valueSize,
           on ? DATA.tokens.successDeep : DATA.tokens.inkMuted, 'right', true);
    }
    drawGlyph(ctx, 'chevron-right', row[2] - pad - chevron / 2, rcy(row), chevron, DATA.tokens.inkFaint);
  }
  ctx.restore();

  const panel = L.guPanel;
  card(ctx, panel, rh(panel) * 0.16, mix(buddy.light, '#ffffff', 0.35));
  const bead = L.guUnlock;
  const radius = Math.min(rw(bead), rh(bead)) / 2;
  ctx.fillStyle = '#ffffff';
  ctx.beginPath(); ctx.arc(rcx(bead), rcy(bead), radius, 0, Math.PI * 2); ctx.fill();
  ctx.strokeStyle = '#E1EAF4';
  ctx.lineWidth = radius * 0.16;
  ctx.beginPath(); ctx.arc(rcx(bead), rcy(bead), radius * 0.88, 0, Math.PI * 2); ctx.stroke();
  ctx.strokeStyle = buddy.primary;
  ctx.beginPath();
  ctx.arc(rcx(bead), rcy(bead), radius * 0.88, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * 0.62);
  ctx.stroke();
  drawGlyph(ctx, 'lock', rcx(bead), rcy(bead), radius * 0.86, buddy.ink);
  text(ctx, 'Keep holding...', rcx(panel), panel[3] - rh(panel) * 0.16,
       DATA.type.b1, buddy.ink, 'center', true);
}

/* ============================================================= EDIT  ROUTINE */

function screenEditRoutine(ctx, L, buddy, t) {
  const scene = buildScene(L.play, buddy.index, M_HOME, buddy.light, BACKDROPS.home);
  drawSceneBackground(ctx, scene, buddy, t);
  drawSceneForeground(ctx, scene, t, false);
  ctx.fillStyle = 'rgba(244,248,253,.91)';
  ctx.fillRect(0, 0, DATA.metrics.designWidth, L.height);

  glyphChip(ctx, 'chevron-left', L.edBack, '#ffffff', buddy.ink);
  labelFit(ctx, 'Edit Routine', L.edTitle, DATA.type.h1, 22, DATA.tokens.ink, 'center');

  ctx.save();
  ctx.beginPath();
  ctx.rect(L.edBand[0], L.edBand[1], rw(L.edBand), rh(L.edBand));
  ctx.clip();
  for (let i = 0; i < DATA.defaultRoutine.length; i++) {
    const row = L.edRow[i];
    if (!row || row[3] < L.edBand[1] || row[1] > L.edBand[3]) continue;
    const task = DATA.defaultRoutine[i];
    const lifted = i === 2;
    card(ctx, lifted ? shift(row, -10) : row, rh(row) * 0.26,
         lifted ? mix(buddy.light, '#ffffff', 0.1) : '#ffffff');
    const r = lifted ? shift(row, -10) : row;
    const pad = rh(r) * 0.18;
    const handle = rh(r) * 0.30;
    drawGlyph(ctx, 'drag', r[0] + pad + handle / 2, rcy(r), handle, DATA.tokens.inkFaint);
    const icon = rh(r) - pad * 2;
    const ix = r[0] + pad * 2 + handle + icon / 2;
    activityChip(ctx, task.kind, buddy, ix, rcy(r), icon, false);
    const chevron = rh(r) * 0.24;
    fitText(ctx, task.name, [ix + icon / 2 + pad, r[1] + pad * 0.4, r[2] - pad - chevron, rcy(r) + rh(r) * 0.02],
            rowTitleSize(r), 25, DATA.tokens.ink, 'left', true);
    fitText(ctx, task.subtitle, [ix + icon / 2 + pad, rcy(r) + rh(r) * 0.04, r[2] - pad - chevron, r[3] - pad * 0.4],
            rowSubtitleSize(r), 17, DATA.tokens.inkMuted, 'left', false);
    drawGlyph(ctx, 'chevron-right', r[2] - pad - chevron / 2, rcy(r), chevron, DATA.tokens.inkFaint);
  }
  ctx.restore();

  const add = L.edAdd;
  button(ctx, add, rh(add) / 2, '#ffffff', '#DCE6F2');
  const gSize = rh(add) * 0.36;
  const addPx = labelSize(ctx, 'Add a Task', buttonLabelSize(add), 16,
                          labelRoom(add, gSize + 16));
  const gWidth = measureLabel(ctx, 'Add a Task', addPx);
  const gx = rcx(add) - (gSize + 16 + gWidth) / 2;
  drawGlyph(ctx, 'plus', gx + gSize / 2, rcy(add), gSize, buddy.primary);
  label(ctx, 'Add a Task', gx + gSize + 16, rcy(add), addPx, buddy.ink, 'left');

  const save = L.edSave;
  button(ctx, save, rh(save) / 2, buddy.primary, darken(buddy.primary, 0.22));
  label(ctx, 'Save Routine', rcx(save), rcy(save),
        labelSize(ctx, 'Save Routine', buttonLabelSize(save), 18, labelRoom(save, 0)),
        '#ffffff', 'center');
}

const SCREENS = [
  { key: 'home', name: 'Home',
    note: 'Pick a buddy and a length, then start the morning', draw: screenHome,
    bands: ['homeHeader', 'wordmark', 'hero', 'timerCard', 'routineHeader', 'taskBand', 'startBtn', 'navBar'] },
  { key: 'adventure', name: 'Buddy Adventure',
    note: 'The countdown, with the buddy travelling its trail', draw: screenAdventure,
    bands: ['advBack', 'advTitle', 'advPause', 'advMute', 'advClock', 'advTally', 'advScene', 'advTrail', 'advGoal', 'advProgress', 'advTaskCard', 'advAction'] },
  { key: 'complete', name: 'Mission Complete',
    note: 'New in v0.7. The frozen time is the subject', draw: screenComplete,
    bands: ['cmpTitle', 'cmpStage', 'cmpCard', 'cmpPlayAgain', 'cmpBackHome'] },
  { key: 'picker', name: 'Choose Your Buddy',
    note: 'Two columns, seven characters', draw: screenBuddyPicker,
    bands: ['pickSheet', 'pickTitle', 'pickClose', 'pickGrid', 'pickConfirm'] },
  { key: 'time', name: 'Customise Time',
    note: 'Bubbles, steppers and a slider that now drags', draw: screenTimePicker,
    bands: ['timeSheet', 'timeTitle', 'timeClose', 'timeDisplay', 'timeMinus', 'timePlus', 'timeSlider', 'timeSet'] },
  { key: 'grownups', name: 'Grown-Ups',
    note: 'Was a stock Android dialog', draw: screenGrownUps,
    bands: ['guBack', 'guTitle', 'guBand', 'guPanel'] },
  { key: 'editroutine', name: 'Edit Routine',
    note: 'Reorder by dragging a handle', draw: screenEditRoutine,
    bands: ['edBack', 'edTitle', 'edBand', 'edAdd', 'edSave'] },
];
