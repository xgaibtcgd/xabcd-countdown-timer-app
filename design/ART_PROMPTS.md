# Generator prompts for new character art

Copy-paste prompts for producing new Morning Mission character art with an image
generator, in the order worth doing them.

Every prompt is three blocks stuck together:

```
[STYLE]  +  [CHARACTER]  +  [REQUEST]
```

**Attach the character's existing PNG as a reference image on every single
generation.** `app/src/main/res/drawable-nodpi/buddy_<key>.png`. The prompts
below describe each character in detail as a backstop, but the reference image
is what actually keeps it on-model, and losing the likeness between frames is
the failure that wastes the most time.

Do one character all the way through before starting the next.

---

## STYLE — paste this into every prompt

> 3D rendered character in a soft matte vinyl-toy style: chunky rounded forms,
> smooth clay-like surfaces, gentle specular highlights, soft ambient occlusion,
> no outlines and no cel shading. Soft key light from the upper left with soft
> fill. Bright, saturated children's palette. Full body, facing the viewer,
> centred in frame with a small even margin. Square image, 1024×1024, PNG with a
> real alpha channel.
>
> Transparent background. No ground shadow, no drop shadow, no backdrop, no
> scenery, no added text, no labels, no watermark, no border. (Markings that are
> part of the character's own design stay.)

The no-shadow instruction matters: the app draws its own contact shadow that
moves with the character, and a shadow baked into the image slides around
underneath it.

---

## CHARACTER — pick one

**burger — Burger Buddy**
> A chubby white cat with a large hot-pink bow on the left side of its head,
> eyes closed in happy upward curves, three fine whisker lines on each cheek,
> round pink blush, mouth open in a wide smile. It holds a cheeseburger — sesame
> bun, lettuce, tomato, cheese, patty — in both cream-coloured paws. It wears
> blue and white vertically striped shorts. Cream paws and tail.

**bee — Queen Bee**
> A round, chubby bee: bright yellow upper body, glossy black stripes across the
> lower body, a tiny gold crown on top of its head, two black antennae with
> yellow ball tips. Iridescent pastel translucent wings. Eyes closed in happy
> upward curves with lashes, round pink blush, mouth open in a wide smile. Small
> black arms and rounded black legs.

**pug — Pug Pal**
> A tan pug with a dark brown muzzle, dark brown floppy ears and dark brown
> paws, pink tongue sticking out, eyes closed in happy upward curves. It wears
> an orange hoodie with black tiger stripes and hood drawstrings.

**shark — Splash Buddy**
> A bright blue cartoon shark with a rounded teardrop body, a white oval belly
> patch, a big open smiling mouth with small white triangular teeth and a pink
> interior, eyes closed in happy upward curves, round pink blush. Small blue
> side fins, a dorsal fin and a tail fin.

**dino — Sprout Dino**
> A round mint-green baby dinosaur with a green sprout of two leaves growing
> from the top of its head, darker green spots, a row of small soft scale plates
> down its back and tail, a pale cream belly. Eyes closed in happy upward
> curves, mouth open showing two tiny teeth, round pink blush, pale paw pads on
> its hands and feet.

**cloud — Cloud Pup**
> A light sky-blue puppy with long floppy ears, a white fluffy cloud shape on
> its belly, darker blue paw pads, eyes closed in happy upward curves, round
> pink blush, mouth open in a wide smile.

**kitty — Sweet Kitty**
> A white cat with a large hot-pink bow on the top of its head, a small golden
> triangular nose, fine whiskers, eyes closed in happy upward curves, round pink
> blush. It wears a pink tartan dress with a white collar and small white
> hearts. Cream paws and tail.

**trike — Jungle Trike**
> A green plush triceratops with a darker green frill behind its head, a white
> nose horn and two white brow horns, eyes closed in happy upward curves, mouth
> open in a wide smile, round pink blush. A cream belly patch reads "LOVE ME" in
> red with a small red heart. Red-and-white gingham heart patches on the soles
> of its feet.

---

## REQUEST A — mid-stride *(do this first)*

Save as `buddy_<key>_stride.png`.

> Pose: a mid-walk stride seen from the front. The character's right leg is
> forward with the foot about to land; the left leg is back and pushing off.
> Arms swing in opposition — left arm forward, right arm back. The body leans
> very slightly into the step.
>
> This is the second frame of a two-frame walk cycle, so it must match the
> reference image exactly in every way except the pose: the same expression, the
> same size in frame, the head at the same height, the same distance from the
> camera. Only the arms and legs have moved.

That last paragraph is the whole ask — if the character changes size or drifts
up the frame, the two frames pop instead of walking.

## REQUEST B — arms-up cheer

Save as `buddy_<key>_cheer.png`.

> Pose: a joyful celebration. Both arms raised straight above the head, hands
> open, the body stretched a little taller, both feet off the ground in a small
> hop, head tilted back very slightly. The mouth is open wider than the
> reference, in a cheer.
>
> Match the reference image in every other way: same character, same colours and
> markings, same closed happy eyes, same size in frame.

## REQUEST C — face sheet

Save as `buddy_<key>_faces.png`. Ask for a **2048×512** canvas if the generator
allows a non-square one; a 2×2 square grid is a fine fallback, just say so when
you send it.

> Output an expression sheet: four copies of ONLY this character's head, in a
> single horizontal row, evenly spaced, on a transparent background. No bodies,
> no necks below the jaw, no labels, no frames. All four heads are exactly the
> same size, at exactly the same angle, with identical colours, markings, ears,
> horns, bow and crown, and identical lighting. The ONLY difference between them
> is the expression:
>
> 1. **Happy** — eyes closed in upward curves, mouth open in a wide smile.
>    Identical to the reference image.
> 2. **Surprised** — eyes wide open and round with large dark pupils and a bright
>    catchlight, brows raised, mouth a small round open "o".
> 3. **Sleepy** — eyes closed with heavy relaxed lids, mouth a small soft closed
>    curve, the whole face slightly drooping.
> 4. **Chomping** — eyes squeezed tightly shut, mouth stretched wide open as if
>    taking a big bite.

## REQUEST D — rig parts *(hardest; do one character first and stop)*

Five separate generations per character, saved as
`buddy_<key>_part_head.png`, `_part_torso.png`, `_part_arm_left.png`,
`_part_arm_right.png`, `_part_signature.png`.

For each one, use the STYLE and CHARACTER blocks, then:

> Show ONLY the character's **{PART}**. Everything else in the image is fully
> transparent.
>
> Do not move it, rotate it, resize it, re-centre it or redraw it. It must sit
> in exactly the same position within the 1024×1024 frame as it does on the
> complete character in the reference image — as though the rest of the
> character had simply been erased and this piece left untouched.
>
> Extend the piece roughly 20 pixels past the point where it joins **{NEIGHBOUR}**,
> so the edge is not cut off flat.

The pieces per character:

| {PART} | {NEIGHBOUR} | notes |
|---|---|---|
| head (with face and everything on it) | the neck / shoulders | includes bow, crown, horns, sprout, ears |
| torso and both legs, as one piece | the neck and the shoulders | no arms, no head |
| left arm and hand | the shoulder | the arm on the viewer's left |
| right arm and hand | the shoulder | the arm on the viewer's right |
| signature part | where it attaches | see below |

The signature part per character: **burger** the cheeseburger · **bee** the pair
of wings · **pug** the tail · **shark** the tail fin · **dino** the head sprout
(separate from the head) · **cloud** the two floppy ears · **kitty** the tail ·
**trike** the neck frill.

**Expect this one to need several attempts.** The thing that goes wrong is the
generator re-centring the piece instead of leaving it in place. That is
instantly checkable — I composite the five layers and diff them against the
original sprite — so send them over even if you are not sure and I will tell you
which ones landed.

---

## Before you send them over

- **PNG, not JPEG.** A JPEG has no alpha and the background will be white.
- **Transparent background**, not a white or checkerboard one painted in.
- **No drop shadow.** The commonest failure, and it will look wrong in the app.
- **Same character.** Colours, markings, bow, crown, stripes, the lot.
- **For poses: the same size in frame** as the reference.

Send them even if some are imperfect. I can measure the alpha bounds, the size
in frame and the colour profile against the existing sprite in a few seconds and
tell you exactly which ones to regenerate and why — much faster than eyeballing
it.

## One more, unrelated to the prompts

**Cloud Pup is drawn slightly smaller than the other seven.** Its artwork
occupies the vertical band `y[66..653]` of its 720×720 canvas where every other
character sits at `y[50..670]`, so it reads as shrunken next to them. If you are
regenerating anything, a fresh full-body Cloud Pup at the same scale as the rest
would fix it.
