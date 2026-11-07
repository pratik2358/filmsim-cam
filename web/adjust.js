// Fujifilm-style picture settings, baked together with the film look into one 3D LUT.
// Order follows the camera: white balance → film look (+ strength) → tone curve → Color Chrome.
// The iOS app has a line-for-line port in FujiFilmCam/Engine/Adjustments.swift; keep them in sync.

export const ADJUST_DEFAULTS = Object.freeze({
  highlight: 0,    // −2 … +4, steps of 0.5 (Fujifilm "Highlight" tone)
  shadow: 0,       // −2 … +4, steps of 0.5 (Fujifilm "Shadow" tone)
  kelvin: 5500,    // 2500 … 10000; 5500 = as shot. Like Kelvin WB: lower = cooler picture
  wbRed: 0,        // −9 … +9 white-balance shift (red ↔ cyan)
  wbBlue: 0,       // −9 … +9 white-balance shift (blue ↔ yellow)
  chrome: 0,       // Color Chrome Effect: 0 off, 1 weak, 2 strong
  chromeBlue: 0,   // Color Chrome FX Blue: 0 off, 1 weak, 2 strong
});

export const isDefaultAdjust = (a) => Object.keys(ADJUST_DEFAULTS).every((k) => a[k] === ADJUST_DEFAULTS[k]);

const toLin = (v) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
const toSrgb = (v) => (v <= 0.0031308 ? v * 12.92 : 1.055 * v ** (1 / 2.4) - 0.055);
const clamp01 = (v) => (v < 0 ? 0 : v > 1 ? 1 : v);
const smoothstep = (a, b, x) => { const t = clamp01((x - a) / (b - a)); return t * t * (3 - 2 * t); };

/** Linear-sRGB colour of a blackbody at `t` kelvin (Kim et al. Planckian-locus fit), Y = 1. */
function blackbody(t) {
  t = Math.min(25000, Math.max(1667, t));
  const x = t <= 4000
    ? -0.2661239e9 / t ** 3 - 0.2343589e6 / t ** 2 + 0.8776956e3 / t + 0.179910
    : -3.0258469e9 / t ** 3 + 2.1070379e6 / t ** 2 + 0.2226347e3 / t + 0.240390;
  const y = t <= 2222 ? -1.1063814 * x ** 3 - 1.34811020 * x ** 2 + 2.18555832 * x - 0.20219683
    : t <= 4000 ? -0.9549476 * x ** 3 - 1.37418593 * x ** 2 + 2.09137015 * x - 0.16748867
    : 3.0817580 * x ** 3 - 5.87338670 * x ** 2 + 3.75112997 * x - 0.37001483;
  const X = x / y, Z = (1 - x - y) / y;
  return [3.2406 * X - 1.5372 - 0.4986 * Z, -0.9689 * X + 1.8758 + 0.0415 * Z, 0.0557 * X - 0.2040 + 1.0570 * Z];
}

/** Per-channel gains (linear light) for the white-balance settings, normalised to keep brightness. */
export function wbGains(a) {
  let g = [1, 1, 1];
  if (a.kelvin !== 5500) {
    // Setting the camera to `kelvin` while the light is ~5500 K: neutralise a `kelvin` light.
    const ref = blackbody(5500), w = blackbody(a.kelvin);
    g = [ref[0] / w[0], ref[1] / w[1], ref[2] / w[2]];
  }
  g[0] *= Math.exp(0.03 * a.wbRed);
  g[2] *= Math.exp(0.03 * a.wbBlue);
  const y = 0.2126 * g[0] + 0.7152 * g[1] + 0.0722 * g[2];
  return [g[0] / y, g[1] / y, g[2] / y];
}

/** Tone curve: smooth bumps centred at 75 % (highlights) and 25 % (shadows); + = harder, like Fujifilm. */
function tone(c, a) {
  if (!a.highlight && !a.shadow) return c;
  const y = 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
  let d = 0;
  if (y > 0.5 && y < 1) d += 0.025 * a.highlight * Math.sin(2 * Math.PI * (y - 0.5)) ** 2;
  if (y > 0 && y < 0.5) d -= 0.025 * a.shadow * Math.sin(2 * Math.PI * y) ** 2;
  return [c[0] + d, c[1] + d, c[2] + d];
}

const CHROME = [0, 0.08, 0.15], CHROME_BLUE = [0, 0.10, 0.18];

/** Color Chrome Effect deepens strongly saturated colours; FX Blue does the same for blues (skies). */
function chrome(c, a) {
  if (!a.chrome && !a.chromeBlue) return c;
  const [r, g, b] = c;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b);
  if (mx <= 0) return c;
  const sat = (mx - mn) / mx;
  let k = 1;
  if (a.chrome) k -= CHROME[a.chrome] * smoothstep(0.25, 0.85, sat) * smoothstep(0.1, 0.6, mx);
  let boost = 0;
  if (a.chromeBlue && mx > mn) {
    const d = mx - mn;
    let h = mx === r ? ((g - b) / d) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
    h = (h * 60 + 360) % 360;
    const w = (1 - smoothstep(25, 60, Math.abs(h - 215))) * smoothstep(0.15, 0.6, sat);
    k -= CHROME_BLUE[a.chromeBlue] * w;
    boost = 0.5 * CHROME_BLUE[a.chromeBlue] * w;
  }
  let o = [r * k, g * k, b * k];
  if (boost) {
    const y = 0.2126 * o[0] + 0.7152 * o[1] + 0.0722 * o[2];
    o = o.map((v) => y + (v - y) * (1 + boost));
  }
  return o;
}

/**
 * Bakes look + strength + adjustments into an RGB8 33³ LUT (red fastest).
 * `sampleLook(rgb)` returns the look's output for an sRGB colour, or null for no look.
 */
export function bakeLUT(size, sampleLook, intensity, a) {
  const out = new Uint8Array(size * size * size * 3);
  const gains = wbGains(a), wbOn = gains.some((v) => Math.abs(v - 1) > 1e-6);
  const s = 1 / (size - 1);
  let i = 0;
  for (let b = 0; b < size; b++) for (let g = 0; g < size; g++) for (let r = 0; r < size; r++) {
    let c = [r * s, g * s, b * s];
    if (wbOn) c = c.map((v, k) => clamp01(toSrgb(clamp01(toLin(v) * gains[k]))));
    if (sampleLook && intensity > 0) {
      const l = sampleLook(c);
      c = [c[0] + (l[0] - c[0]) * intensity, c[1] + (l[1] - c[1]) * intensity, c[2] + (l[2] - c[2]) * intensity];
    }
    c = chrome(tone(c, a), a);
    out[i++] = Math.round(clamp01(c[0]) * 255);
    out[i++] = Math.round(clamp01(c[1]) * 255);
    out[i++] = Math.round(clamp01(c[2]) * 255);
  }
  return out;
}

/** Same pipeline for one colour (used by tests and to keep the app's port honest). */
export function adjustColor(c, sampleLook, intensity, a) {
  const gains = wbGains(a);
  c = c.map((v, k) => clamp01(toSrgb(clamp01(toLin(v) * gains[k]))));
  if (sampleLook && intensity > 0) {
    const l = sampleLook(c);
    c = c.map((v, k) => v + (l[k] - v) * intensity);
  }
  return chrome(tone(c, a), a).map(clamp01);
}
