// FilmCam for the web: live camera with film looks applied on the GPU (WebGL2 + 3D LUTs).
// Everything runs locally; nothing is uploaded.

import { ADJUST_DEFAULTS, isDefaultAdjust, bakeLUT, sharpAmount, clarityAmount, lutKey, isNeutralLut } from './adjust.js';

const LUT_SIZE = 33;

const FILMS = [
  { id: 'original', name: 'Original', note: 'No look applied', group: 'camera' },
  { id: 'provia', name: 'Provia', note: 'Standard, faithful colour', group: 'camera' },
  { id: 'velvia', name: 'Velvia', note: 'Vivid, deep landscapes', group: 'camera' },
  { id: 'astia', name: 'Astia', note: 'Soft, flattering skin', group: 'camera' },
  { id: 'classic-chrome', name: 'Classic Chrome', note: 'Muted documentary tones', group: 'camera' },
  { id: 'classic-neg', name: 'Classic Neg.', note: 'Hard tone, cyan shadows', group: 'camera' },
  { id: 'nostalgic-neg', name: 'Nostalgic Neg.', note: 'Amber highlights, warm', group: 'camera' },
  { id: 'reala-ace', name: 'Reala Ace', note: 'Natural, true-to-life', group: 'camera' },
  { id: 'pro-neg-std', name: 'Pro Neg. Std', note: 'Soft portrait tonality', group: 'camera' },
  { id: 'pro-neg-hi', name: 'Pro Neg. Hi', note: 'Punchier portraits', group: 'camera' },
  { id: 'eterna', name: 'Eterna', note: 'Cinematic, understated', group: 'camera' },
  { id: 'eterna-bleach', name: 'Bleach Bypass', note: 'Desaturated, high contrast', group: 'camera' },
  { id: 'acros', name: 'Acros', note: 'Fine black & white', group: 'camera' },
  { id: 's-provia-100f', name: 'Provia 100F', note: 'Slide film', group: 'stock' },
  { id: 's-velvia-50', name: 'Velvia 50', note: 'Slide film', group: 'stock' },
  { id: 's-velvia-100', name: 'Velvia 100', note: 'Slide film', group: 'stock' },
  { id: 's-astia-100f', name: 'Astia 100F', note: 'Slide film', group: 'stock' },
  { id: 's-sensia-100', name: 'Sensia 100', note: 'Slide film', group: 'stock' },
  { id: 's-pro-160c', name: 'Pro 160C', note: 'Portrait negative', group: 'stock' },
  { id: 's-pro-400h', name: 'Pro 400H', note: 'Portrait negative', group: 'stock' },
  { id: 's-pro-800z', name: 'Pro 800Z', note: 'Portrait negative', group: 'stock' },
  { id: 's-superia-200', name: 'Superia 200', note: 'Consumer negative', group: 'stock' },
  { id: 's-superia-400', name: 'Superia 400', note: 'Consumer negative', group: 'stock' },
  { id: 's-reala-100', name: 'Superia Reala 100', note: 'Consumer negative', group: 'stock' },
  { id: 's-xtra-800', name: 'Superia X-Tra 800', note: 'Consumer negative', group: 'stock' },
  { id: 's-fp-100c', name: 'FP-100C', note: 'Instant peel-apart', group: 'stock' },
  { id: 's-acros-100', name: 'Neopan Acros 100', note: 'B&W negative', group: 'stock' },
  { id: 's-neopan-1600', name: 'Neopan 1600', note: 'B&W negative', group: 'stock' },
];
const GROUPS = { camera: 'Camera looks', stock: 'Film stocks', custom: 'Imported' };
const GRAIN = [0, 0.5, 1];
const GRAIN_LABELS = ['Grain off', 'Grain weak', 'Grain strong'];
// Skin, sky, foliage, red, dark grey, light grey (sRGB 0..1) for the swatches.
const REFS = [[.87, .67, .55], [.45, .66, .90], [.33, .55, .24], [.80, .18, .16], [.25, .25, .25], [.85, .85, .85]];

const $ = (id) => document.getElementById(id);
const store = {
  get(k, d) { try { const v = localStorage.getItem(k); return v === null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); return true; } catch { return false; } },
};

const state = {
  filmId: store.get('filmId', 'classic-chrome'),
  intensity: store.get('intensity', 1),
  grain: store.get('grain', 0),
  grid: store.get('grid', false),
  ratio: store.get('ratio', 'full'),
  // Picture settings (only known ones, so settings from older versions don't linger)
  adjust: Object.fromEntries(Object.keys(ADJUST_DEFAULTS).map((k) => [k, store.get('adjust', {})[k] ?? ADJUST_DEFAULTS[k]])),
  crop: { x: 0, y: 0, w: 1, h: 1 },   // part of the source that's visible (and saved)
  facing: store.get('facing', 'environment'),
  mode: 'camera',          // 'camera' | 'still'
  still: null,             // ImageBitmap | HTMLImageElement
  stream: null,
  torch: false,
  lens: { deviceId: null, stops: [], current: null },
  focus: { point: null, locked: false, meterEV: 0, userEV: 0, hwExposure: false, hwFocus: false },
  dirty: true,
  videoMode: false,
  recording: null,         // { rec, mic, chunks, start, mirror, timer } while a video is being recorded
  pendingVideo: null,      // iPhone: a finished video waiting for a tap to open the share sheet
};
window.filmcam = { state };   // handy for debugging in the browser console
const lutData = new Map();   // id -> Uint8Array (RGB8, 33³)
const custom = loadCustomMeta();

// ---------- WebGL ----------
const canvas = $('view');
const gl = canvas.getContext('webgl2', { preserveDrawingBuffer: true, antialias: false, alpha: false });

const VERT = `#version 300 es
in vec2 aPos; out vec2 vUv; uniform bool uMirror; uniform vec4 uCrop;   // crop: x, y, w, h (top-left origin)
void main() {
  vUv = aPos * 0.5 + 0.5; if (uMirror) vUv.x = 1.0 - vUv.x; vUv.y = 1.0 - vUv.y;
  vUv = uCrop.xy + vUv * uCrop.zw;
  gl_Position = vec4(aPos, 0.0, 1.0);
}`;
const FRAG = `#version 300 es
precision highp float; precision highp sampler3D;
in vec2 vUv; out vec4 outColor;
uniform sampler2D uImage; uniform sampler3D uLut;
uniform bool uHasLut; uniform float uIntensity, uGrain, uGrainScale, uSeed, uGain;
uniform vec2 uPx, uClarityR; uniform float uSharp, uClarity, uClarityLod;
const vec3 LUM = vec3(0.2126, 0.7152, 0.0722);
vec3 toLin(vec3 c) { return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c)); }
vec3 toSrgb(vec3 c) { return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(0.0031308, c)); }
float hash(vec2 p) { vec3 p3 = fract(vec3(p.xyx) * 0.1031); p3 += dot(p3, p3.yzx + 33.33); return fract((p3.x + p3.y) * p3.z); }
float valueNoise(vec2 p) {
  vec2 i = floor(p), f = fract(p); vec2 u = f * f * (3.0 - 2.0 * f);
  return mix(mix(hash(i), hash(i + vec2(1, 0)), u.x), mix(hash(i + vec2(0, 1)), hash(i + vec2(1, 1)), u.x), u.y);
}
void main() {
  vec3 c = texture(uImage, vUv).rgb;
  if (uSharp != 0.0) {
    // Sharpness: detail = pixel minus its neighbours (one output pixel away), added on luminance;
    // negative settings blend towards the neighbours instead (softer).
    vec3 b = 0.25 * (texture(uImage, vUv + vec2(uPx.x, 0.0)).rgb + texture(uImage, vUv - vec2(uPx.x, 0.0)).rgb
                   + texture(uImage, vUv + vec2(0.0, uPx.y)).rgb + texture(uImage, vUv - vec2(0.0, uPx.y)).rgb);
    c = uSharp > 0.0 ? c + clamp(dot(c - b, LUM) * uSharp, -0.12, 0.12) : c + (c - b) * uSharp;
  }
  if (uClarity != 0.0) {
    // Clarity: local contrast against a wide blur (from the mipmaps), strongest in the midtones.
    vec3 b = textureLod(uImage, vUv, uClarityLod).rgb;
    for (int i = 0; i < 8; i++) {
      float a = float(i) * 0.7854;
      b += textureLod(uImage, vUv + vec2(cos(a), sin(a)) * uClarityR, uClarityLod).rgb;
    }
    float y = dot(c, LUM), d = y - dot(b / 9.0, LUM);
    c += uClarity * d * 4.0 * y * (1.0 - y);
  }
  if (uGain != 1.0) {
    vec3 l = toLin(clamp(c, 0.0, 1.0)) * uGain;
    if (uGain > 1.0) {
      // Highlight shoulder: brightening rolls bright areas off smoothly towards white instead of
      // clipping them. Applied to the brightest channel so colours keep their hue.
      const float K = 0.55;
      float m = max(max(l.r, l.g), l.b);
      if (m > K) l *= (K + (1.0 - K) * (1.0 - exp(-(m - K) / (1.0 - K)))) / m;
    }
    c = toSrgb(clamp(l, 0.0, 1.0));
  }
  if (uHasLut) {
    const float N = ${LUT_SIZE}.0;
    vec3 l = texture(uLut, clamp(c, 0.0, 1.0) * ((N - 1.0) / N) + 0.5 / N).rgb;
    c = mix(c, l, uIntensity);
  }
  if (uGrain > 0.0) {
    vec2 p = gl_FragCoord.xy / uGrainScale + uSeed;
    float n = (valueNoise(p) + valueNoise(p * 1.9 + 17.0)) * 0.5 - 0.5;
    float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    c += n * uGrain * 0.22 * (4.0 * y * (1.0 - y));
  }
  outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}`;

let prog, loc, imageTex, maxTex = 4096;

function initGL() {
  if (!gl) return false;
  const sh = (type, src) => {
    const s = gl.createShader(type); gl.shaderSource(s, src); gl.compileShader(s);
    if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s));
    return s;
  };
  prog = gl.createProgram();
  gl.attachShader(prog, sh(gl.VERTEX_SHADER, VERT));
  gl.attachShader(prog, sh(gl.FRAGMENT_SHADER, FRAG));
  gl.linkProgram(prog);
  if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog));
  gl.useProgram(prog);
  loc = Object.fromEntries(['uImage', 'uLut', 'uHasLut', 'uIntensity', 'uGrain', 'uGrainScale', 'uSeed', 'uMirror', 'uGain', 'uCrop',
    'uPx', 'uClarityR', 'uSharp', 'uClarity', 'uClarityLod']
    .map((n) => [n, gl.getUniformLocation(prog, n)]));
  const buf = gl.createBuffer();
  gl.bindBuffer(gl.ARRAY_BUFFER, buf);
  gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
  const aPos = gl.getAttribLocation(prog, 'aPos');
  gl.enableVertexAttribArray(aPos);
  gl.vertexAttribPointer(aPos, 2, gl.FLOAT, false, 0, 0);

  imageTex = gl.createTexture();
  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, imageTex);
  for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR],
    [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE]]) gl.texParameteri(gl.TEXTURE_2D, k, v);
  gl.uniform1i(loc.uImage, 0);
  gl.uniform1i(loc.uLut, 1);
  maxTex = Math.min(gl.getParameter(gl.MAX_TEXTURE_SIZE), 8192);
  return true;
}

/** The look, its strength and the picture settings baked into one 3D LUT (rebuilt only when they change). */
let bakedTex = null, bakedKey = '';
function currentLutTexture() {
  const look = state.filmId === 'original' ? null : lutData.get(state.filmId);
  if (!look && isNeutralLut(state.adjust)) return null;
  const key = `${look ? state.filmId : '-'}|${state.intensity}|${lutKey(state.adjust)}`;
  if (key !== bakedKey) {
    const data = bakeLUT(LUT_SIZE, look ? (c) => sampleLUT(look, c) : null, state.intensity, state.adjust);
    if (!bakedTex) {
      bakedTex = gl.createTexture();
      gl.activeTexture(gl.TEXTURE1);
      gl.bindTexture(gl.TEXTURE_3D, bakedTex);
      for (const [k, v] of [[gl.TEXTURE_MIN_FILTER, gl.LINEAR], [gl.TEXTURE_MAG_FILTER, gl.LINEAR],
        [gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE], [gl.TEXTURE_WRAP_R, gl.CLAMP_TO_EDGE]])
        gl.texParameteri(gl.TEXTURE_3D, k, v);
    }
    gl.activeTexture(gl.TEXTURE1);
    gl.bindTexture(gl.TEXTURE_3D, bakedTex);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texImage3D(gl.TEXTURE_3D, 0, gl.RGB8, LUT_SIZE, LUT_SIZE, LUT_SIZE, 0, gl.RGB, gl.UNSIGNED_BYTE, data);
    gl.activeTexture(gl.TEXTURE0);
    bakedKey = key;
  }
  return bakedTex;
}

/** Draws the `crop` part of `source` (video / image) with the current look into a w×h canvas. */
function render(source, w, h, { animateGrain = true, mirror = false, crop = FULL_CROP } = {}) {
  if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
  gl.viewport(0, 0, w, h);
  gl.activeTexture(gl.TEXTURE0);
  gl.bindTexture(gl.TEXTURE_2D, imageTex);
  gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, false);
  gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, source);
  const a = state.adjust;
  // Clarity samples a wide blur from the mipmaps (only built when it's on).
  if (a.clarity) gl.generateMipmap(gl.TEXTURE_2D);
  gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, a.clarity ? gl.LINEAR_MIPMAP_LINEAR : gl.LINEAR);
  const sw = source.videoWidth || source.width, sh = source.videoHeight || source.height;
  const r = 0.012 * Math.min(sw * crop.w, sh * crop.h);   // clarity radius: 1.2 % of the picture's short side
  gl.uniform2f(loc.uPx, crop.w / w, crop.h / h);
  gl.uniform1f(loc.uSharp, sharpAmount(a.sharpness));
  gl.uniform1f(loc.uClarity, clarityAmount(a.clarity));
  gl.uniform2f(loc.uClarityR, r / sw, r / sh);
  gl.uniform1f(loc.uClarityLod, Math.log2(Math.max(1, r * 0.5)));

  const tex = currentLutTexture();
  gl.activeTexture(gl.TEXTURE1);
  gl.bindTexture(gl.TEXTURE_3D, tex);
  gl.activeTexture(gl.TEXTURE0);
  gl.uniform1i(loc.uHasLut, tex ? 1 : 0);
  gl.uniform1f(loc.uIntensity, 1);   // strength is baked into the LUT
  gl.uniform1f(loc.uGrain, GRAIN[state.grain]);
  gl.uniform1f(loc.uGrainScale, Math.max(1, Math.min(w, h) / 1080));
  gl.uniform1f(loc.uSeed, animateGrain ? Math.random() * 1000 : 0);
  gl.uniform1i(loc.uMirror, mirror ? 1 : 0);
  gl.uniform4f(loc.uCrop, crop.x, crop.y, crop.w, crop.h);
  gl.uniform1f(loc.uGain, state.mode === 'camera' ? Math.pow(2, softwareEV()) : 1);
  gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
}

// ---------- Camera ----------
const video = document.createElement('video');
video.playsInline = true; video.muted = true; video.autoplay = true;

let cameraRequest = 0;
async function startCamera() {
  const req = ++cameraRequest;   // only the latest request wins if calls overlap
  stopCamera();
  if (!navigator.mediaDevices?.getUserMedia) {
    showStatus('Camera not available', 'This browser can’t open the camera here. Camera access needs HTTPS. You can still upload a photo.');
    return;
  }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({
      audio: false,
      video: {
        ...(state.lens.deviceId && state.facing === 'environment'
          ? { deviceId: { exact: state.lens.deviceId } } : { facingMode: { ideal: state.facing } }),
        width: { ideal: 4096 }, height: { ideal: 3072 },
      },
    });
    if (req !== cameraRequest || document.hidden) { stream.getTracks().forEach((t) => t.stop()); return; }
    state.stream = stream;
    stream.getVideoTracks()[0]?.addEventListener('ended', () => { if (state.stream === stream) { state.stream = null; updateUI(); } });
    video.srcObject = stream;
    await video.play().catch(() => {});
    hideStatus();
    setupTorch();
    setupFocus();
    setupLenses();
  } catch (err) {
    if (req !== cameraRequest) return;
    if (state.lens.deviceId) { state.lens.deviceId = null; return startCamera(); }   // that lens went away
    const denied = err && (err.name === 'NotAllowedError' || err.name === 'SecurityError');
    showStatus(denied ? 'Camera access is off' : 'No camera found',
      denied ? 'Allow camera access for this site in your browser settings, or upload a photo to try the looks.'
             : 'Connect a camera, or upload a photo to try the looks.');
  }
  updateUI();
}

function stopCamera() {
  state.stream?.getTracks().forEach((t) => t.stop());
  state.stream = null;
  state.torch = false;
  video.srcObject = null;
}

function setupTorch() {
  const track = state.stream?.getVideoTracks()[0];
  const caps = track?.getCapabilities?.() || {};
  $('btn-torch').hidden = !caps.torch;
}

async function toggleTorch() {
  const track = state.stream?.getVideoTracks()[0];
  if (!track) return;
  state.torch = !state.torch;
  try { await track.applyConstraints({ advanced: [{ torch: state.torch }] }); } catch { state.torch = false; }
  $('btn-torch').classList.toggle('on', state.torch);
}

// ---------- Lenses (.5 / 1 / tele) ----------
// iPhone Safari names each back camera, so we can pick a physical lens by deviceId.
// Elsewhere (mostly Android Chrome) the main camera usually offers a `zoom` control that
// switches lenses by itself; failing both, we list the back cameras as numbered buttons.
const IOS_LENSES = [
  [/ultra wide/i, 0.5, '.5'],
  [/telephoto/i, 3, 'Tele'],
  [/^back camera$/i, 1, '1'],
];

async function setupLenses() {
  const L = state.lens; L.stops = [];
  const t = track();
  if (!t || state.facing !== 'environment') return drawLenses();
  const cams = (await navigator.mediaDevices.enumerateDevices().catch(() => []))
    .filter((d) => d.kind === 'videoinput' && !/front|user|facing front/i.test(d.label));
  const currentId = t.getSettings?.().deviceId;
  const ios = cams.map((d) => { const m = IOS_LENSES.find(([re]) => re.test(d.label)); return m && { kind: 'device', id: d.deviceId, order: m[1], label: m[2], zoomLabel: true }; }).filter(Boolean);
  const caps = t.getCapabilities?.() || {};
  if (ios.length > 1) {
    L.stops = ios.sort((a, b) => a.order - b.order);
    L.current = L.stops.find((s) => s.id === currentId)?.label ?? '1';
  } else if (caps.zoom && caps.zoom.max >= 1.9) {
    const zs = [caps.zoom.min <= 0.6 ? 0.5 : null, 1, 2, caps.zoom.max >= 4.9 ? 5 : caps.zoom.max >= 2.9 ? 3 : null]
      .filter((z) => z !== null && z >= caps.zoom.min - 0.01 && z <= caps.zoom.max + 0.01);
    L.stops = zs.map((z) => ({ kind: 'zoom', zoom: z, label: z === 0.5 ? '.5' : String(z), zoomLabel: true }));
    const z = t.getSettings?.().zoom ?? 1;
    L.current = L.stops.reduce((best, s) => Math.abs(s.zoom - z) < Math.abs(best.zoom - z) ? s : best, L.stops[0]).label;
  } else {
    const backs = cams.filter((d) => /back|environment|rear/i.test(d.label) || cams.length > 1);
    if (backs.length > 1) {
      L.stops = backs.map((d, i) => ({ kind: 'device', id: d.deviceId, label: String(i + 1), name: d.label || `Camera ${i + 1}` }));
      L.current = L.stops.find((s) => s.id === currentId)?.label ?? '1';
    }
  }
  drawLenses();
}

async function pickLens(stop) {
  const L = state.lens;
  if (stop.label === L.current || state.recording) return;
  L.current = stop.label; drawLenses();
  if (stop.kind === 'zoom') {
    try { await track()?.applyConstraints({ advanced: [{ zoom: stop.zoom }] }); } catch { toast('This camera can’t zoom here.'); }
    resetFocus();
  } else {
    L.deviceId = stop.id;
    await startCamera();
  }
}

function drawLenses() {
  const el = $('lenses'), L = state.lens;
  const show = state.mode === 'camera' && !!state.stream && L.stops.length > 1;
  el.hidden = !show;
  if (!show) return;
  el.textContent = '';
  for (const s of L.stops) {
    const b = document.createElement('button');
    const on = s.label === L.current;
    b.className = 'lens' + (on ? ' on' : '');
    b.textContent = on && s.zoomLabel && /^[.\d]/.test(s.label) ? s.label + '×' : s.label;
    b.setAttribute('aria-label', s.name || `Lens ${s.label}`);
    if (s.name) b.title = s.name;
    b.onclick = (e) => { e.stopPropagation(); pickLens(s); };
    el.appendChild(b);
  }
}

// ---------- Focus & exposure ----------
const EV_RANGE = 2;
const softwareEV = () => {
  const f = state.focus;
  return Math.max(-3, Math.min(3, f.meterEV + (f.hwExposure ? 0 : f.userEV)));   // meter ±2 plus your drag ±2, capped at ±3
};
const track = () => state.stream?.getVideoTracks()[0];

function setupFocus() {
  const caps = track()?.getCapabilities?.() || {};
  const f = state.focus;
  f.hwFocus = !!caps.pointsOfInterest || (caps.focusMode || []).includes('single-shot');
  f.hwExposure = !!caps.exposureCompensation;
  resetFocus(false);
}

/** Hardware exposure compensation for `ev`, in the camera's steps (null if unsupported). */
function hwEV(ev) {
  const c = track()?.getCapabilities?.().exposureCompensation;
  if (!c) return null;
  const step = c.step || 0.1;
  return Math.max(c.min, Math.min(c.max, Math.round(ev / step) * step));
}

async function applyTrack(c) {
  try { await track()?.applyConstraints({ advanced: [c] }); return true; } catch { return false; }
}

function resetFocus(updateHw = true) {
  const f = state.focus;
  f.point = null; f.locked = false; f.meterEV = 0; f.userEV = 0;
  if (updateHw && track()) {
    const caps = track().getCapabilities?.() || {};
    const c = {};
    if ((caps.focusMode || []).includes('continuous')) c.focusMode = 'continuous';
    if ((caps.exposureMode || []).includes('continuous')) c.exposureMode = 'continuous';
    if (caps.exposureCompensation) c.exposureCompensation = hwEV(0);
    if (Object.keys(c).length) applyTrack(c);
  }
  drawFocusUI();
}

let warnedFocus = false;
async function focusAt(x, y, lock) {
  const f = state.focus, t = track();
  if (!t) return;
  f.point = { x, y }; f.locked = lock; f.userEV = 0;
  // Viewfinder point -> camera frame (the viewfinder may show a crop). The camera sees the unmirrored image.
  const cr = state.crop;
  const fx = cr.x + x * cr.w; y = cr.y + y * cr.h;
  const sx = state.facing === 'user' ? 1 - fx : fx;
  const caps = t.getCapabilities?.() || {};
  const c = {};
  if (caps.pointsOfInterest) c.pointsOfInterest = [{ x: sx, y }];
  const fm = caps.focusMode || [], em = caps.exposureMode || [];
  if (fm.includes('single-shot')) c.focusMode = 'single-shot';
  if (lock && em.includes('single-shot')) c.exposureMode = 'single-shot';
  else if (em.includes('continuous')) c.exposureMode = 'continuous';
  if (caps.exposureCompensation) c.exposureCompensation = hwEV(0);
  const hwOK = Object.keys(c).length ? await applyTrack(c) : false;
  // Without hardware metering at a point, meter the spot in software.
  f.meterEV = caps.pointsOfInterest && hwOK ? 0 : spotMeterEV(sx, y);
  if (!f.hwFocus && !warnedFocus) { warnedFocus = true; toast('This browser doesn’t let sites control focus, so tapping sets exposure only.', 3600); }
  drawFocusUI();
}

/** EV that brings the tapped spot to mid-grey (software metering fallback). */
const meterCanvas = document.createElement('canvas');
function spotMeterEV(x, y) {
  const w = 96, h = Math.max(1, Math.round(96 * video.videoHeight / video.videoWidth)) || 72;
  meterCanvas.width = w; meterCanvas.height = h;
  const ctx = meterCanvas.getContext('2d', { willReadFrequently: true });
  try { ctx.drawImage(video, 0, 0, w, h); } catch { return 0; }
  const r = 5, cx = Math.round(x * (w - 1)), cy = Math.round(y * (h - 1));
  const d = ctx.getImageData(Math.max(0, cx - r), Math.max(0, cy - r), 2 * r + 1, 2 * r + 1).data;
  let sum = 0, n = 0;
  const lin = (v) => { v /= 255; return v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
  for (let i = 0; i < d.length; i += 4) { sum += 0.2126 * lin(d[i]) + 0.7152 * lin(d[i + 1]) + 0.0722 * lin(d[i + 2]); n++; }
  const y0 = Math.max(1e-4, sum / Math.max(1, n));
  let ev = Math.log2(0.18 / y0);
  if (ev > 0) {
    // Don't brighten past what the rest of the frame can take: keep the bright end of the picture
    // (98th percentile) within about a stop of white, where the highlight shoulder still holds detail.
    const all = ctx.getImageData(0, 0, w, h).data, ys = new Float32Array(all.length / 4);
    for (let i = 0, j = 0; i < all.length; i += 4, j++) ys[j] = 0.2126 * lin(all[i]) + 0.7152 * lin(all[i + 1]) + 0.0722 * lin(all[i + 2]);
    ys.sort();
    const hi = Math.max(1e-3, ys[Math.floor(ys.length * 0.98)]);
    ev = Math.min(ev, Math.max(0, Math.log2(2.0 / hi)), 1.5);
  }
  return Math.max(-EV_RANGE, Math.min(EV_RANGE, ev));
}

let evTimer;
function setUserEV(ev) {
  const f = state.focus;
  f.userEV = Math.max(-EV_RANGE, Math.min(EV_RANGE, ev));
  if (f.hwExposure) {
    const v = hwEV(f.userEV);
    if (v !== null) { clearTimeout(evTimer); evTimer = setTimeout(() => applyTrack({ exposureCompensation: v }), 30); }
  }
  drawFocusUI();
}

let focusFade;
function drawFocusUI(reveal = true) {
  const f = state.focus, ui = $('focus-ui');
  const live = state.mode === 'camera' && !!state.stream;
  $('ae-lock').hidden = !(live && f.locked);
  const show = live && reveal && (f.point || Math.abs(f.userEV) > 0.01);
  if (!show) { if (!f.locked) ui.classList.remove('show'); return; }
  const p = f.point || { x: 0.5, y: 0.5 };
  ui.style.left = `${p.x * 100}%`; ui.style.top = `${p.y * 100}%`;
  const t = (f.userEV + EV_RANGE) / (2 * EV_RANGE);
  ui.style.setProperty('--ev', String(t));
  $('ev-label').textContent = Math.abs(f.userEV) >= 0.05 ? (f.userEV > 0 ? '+' : '') + f.userEV.toFixed(1) : '';
  ui.classList.toggle('locked', f.locked);
  ui.classList.add('show');
  clearTimeout(focusFade);
  if (!f.locked) focusFade = setTimeout(() => ui.classList.remove('show'), 2200);
}

/** One handler for the viewfinder: tap = focus/expose · double-tap = auto · hold = AE/AF lock ·
 *  drag up/down = exposure · swipe sideways = change look. */
function bindViewfinderGestures() {
  const el = $('frame');
  let t = null, lastTap = 0;
  const rel = (e) => { const r = el.getBoundingClientRect(); return [(e.clientX - r.left) / r.width, (e.clientY - r.top) / r.height]; };
  el.addEventListener('pointerdown', (e) => {
    if (e.target.closest('button')) return;
    if (closePanels()) { t = null; return; }   // a tap on the picture first closes an open panel or menu
    el.setPointerCapture?.(e.pointerId);
    const [x, y] = rel(e);
    t = { x0: e.clientX, y0: e.clientY, x, y, axis: null, held: false, startEV: state.focus.userEV };
    const live = state.mode === 'camera' && state.stream;
    if (live) t.hold = setTimeout(() => { if (t && !t.axis) { t.held = true; navigator.vibrate?.(15); focusAt(t.x, t.y, true); } }, 550);
  });
  el.addEventListener('pointermove', (e) => {
    if (!t) return;
    const dx = e.clientX - t.x0, dy = e.clientY - t.y0;
    if (!t.axis && Math.hypot(dx, dy) > 12) { t.axis = Math.abs(dx) > Math.abs(dy) ? 'h' : 'v'; clearTimeout(t.hold); }
    if (t.axis === 'v' && state.mode === 'camera' && state.stream) setUserEV(t.startEV - dy / 110);
  });
  const end = (e) => {
    if (!t) return;
    clearTimeout(t.hold);
    const dx = e.clientX - t.x0, cur = t; t = null;
    if (cur.axis === 'h') { if (Math.abs(dx) > 40) stepFilm(dx < 0 ? 1 : -1); return; }
    if (cur.axis || cur.held || e.type === 'pointercancel') return;
    if (!(state.mode === 'camera' && state.stream)) return;
    const now = performance.now();
    if (now - lastTap < 320) { lastTap = 0; resetFocus(); return; }
    lastTap = now;
    focusAt(cur.x, cur.y, false);
  };
  el.addEventListener('pointerup', end);
  el.addEventListener('pointercancel', end);
}

// ---------- Render loop ----------
function sourceSize() {
  if (state.mode === 'still' && state.still) return [state.still.width, state.still.height];
  return [video.videoWidth, video.videoHeight];
}

/** Preview renders at screen resolution (cheap), or 1080 px across while recording video; photos render at full resolution. */
function previewSize(w, h, recording = !!state.recording) {
  const cw = w * state.crop.w, ch = h * state.crop.h;
  const screen = $('frame').clientWidth * Math.min(2, devicePixelRatio || 1);
  const video1080 = 1080 * cw / Math.min(cw, ch);   // width that makes the short side 1080 px
  const s = Math.min(1, (recording ? Math.max(screen, video1080) : screen) / cw);
  return [Math.max(1, Math.round(cw * s)), Math.max(1, Math.round(ch * s))];
}

function frame() {
  if (state.capturing) { requestAnimationFrame(frame); return; }   // don't touch the canvas mid-encode
  if (state.mode === 'camera' && state.stream && video.readyState >= 2 && video.videoWidth) {
    const [w, h] = sourceSize();
    layoutFrame(w, h);
    render(video, ...previewSize(w, h), { mirror: state.facing === 'user', crop: state.crop });
    if (state.recording) copyToRecorder();
  } else if (state.mode === 'still' && state.still && state.dirty) {
    const [w, h] = sourceSize();
    layoutFrame(w, h);
    render(state.still, ...previewSize(w, h), { animateGrain: false });
    state.dirty = false;
  }
  requestAnimationFrame(frame);
}

const FULL_CROP = Object.freeze({ x: 0, y: 0, w: 1, h: 1 });
const isFull = () => state.ratio === 'full' && state.mode === 'camera';   // photos you open are always shown whole

/** Sizes the viewfinder as large as the screen allows. 'full' fills the whole screen behind the bars
 *  and crops the camera frame to match; 'fit' shows the whole frame, as wide as possible, centred
 *  between the bars when it fits there, otherwise running under them. */
let lastLayout = '';
function layoutFrame(sw, sh) {
  const app = $('app'), W = app.clientWidth, H = app.clientHeight;
  // Lay out against the bar tops, not the look panel, so opening the panel never moves the frame.
  const top = $('topbar').offsetHeight;
  const bottom = $('chip-row').getBoundingClientRect().top - app.getBoundingClientRect().top;
  const full = isFull();
  const key = [sw, sh, W, H, top, bottom, full].join();
  if (key === lastLayout) return;
  lastLayout = key;
  const ar = sw / sh;
  let x = 0, y = 0, w = W, h = H;
  if (!full) {
    w = Math.min(W, H * ar); h = w / ar;
    const gap = bottom - top;
    y = h <= gap ? top + (gap - h) / 2 : Math.max(0, Math.min(top, H - h));
    x = (W - w) / 2;
  }
  const f = $('frame').style;
  f.left = `${x}px`; f.top = `${y}px`; f.width = `${w}px`; f.height = `${h}px`;
  // Part of the camera frame the viewfinder shows (centred), which is also what gets saved.
  const fa = w / h;
  if (!full || Math.abs(fa - ar) < 1e-3) state.crop = FULL_CROP;
  else if (fa < ar) { const cw = fa / ar; state.crop = { x: (1 - cw) / 2, y: 0, w: cw, h: 1 }; }
  else { const ch = ar / fa; state.crop = { x: 0, y: (1 - ch) / 2, w: 1, h: ch }; }
  $('ratio-label').textContent = full ? 'Full' : ratioName(sw, sh);
}
function ratioName(w, h) {
  const r = Math.min(w, h) / Math.max(w, h), p = h >= w;
  const known = [[3 / 4, '3:4', '4:3'], [9 / 16, '9:16', '16:9'], [2 / 3, '2:3', '3:2'], [1, '1:1', '1:1']];
  const k = known.find(([v]) => Math.abs(v - r) < 0.02);
  return k ? (p ? k[1] : k[2]) : 'Fit';
}

// ---------- Capture & save ----------
async function shoot() {
  const btn = $('btn-shutter');
  if (state.capturing || state.recording) return;
  state.capturing = true;
  let w, h;
  if (state.mode === 'still' && state.still) {
    [w, h] = sourceSize();
    render(state.still, w, h, { animateGrain: false });
  } else if (state.stream && video.videoWidth) {
    [w, h] = sourceSize();
    const c = state.crop;   // save what the viewfinder shows
    render(video, Math.round(w * c.w), Math.round(h * c.h), { animateGrain: false, mirror: false, crop: c });
    const f = $('shutter-flash'); f.classList.add('on'); requestAnimationFrame(() => f.classList.remove('on'));
  } else { state.capturing = false; return; }
  btn.classList.add('busy');
  try {
    const blob = await new Promise((res) => canvas.toBlob(res, 'image/jpeg', 0.92));
    if (!blob) throw new Error('encode');
    const name = `FilmCam-${filmById(state.filmId)?.name.replace(/[^\w]+/g, '-') || 'photo'}-${stamp()}.jpg`;
    setThumb(blob);
    await saveBlob(blob, name);
  } catch (e) {
    if (e?.name !== 'AbortError') toast('Couldn’t save the photo.');
  } finally {
    btn.classList.remove('busy');
    state.capturing = false;
    state.dirty = true;
  }
}

// iPhone/iPad: the share sheet's "Save Image" puts the photo in Photos (Safari can't save there directly).
// Android and computers: a normal download, which lands in Downloads and shows up in the gallery app.
const isAppleMobile = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
  (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);   // iPadOS reports as a Mac

async function saveBlob(blob, name) {
  const file = new File([blob], name, { type: blob.type || 'image/jpeg' });
  const what = file.type.startsWith('video') ? 'Video' : 'Photo';
  if (isAppleMobile && navigator.canShare?.({ files: [file] })) {
    await navigator.share({ files: [file] });
    return;
  }
  const url = URL.createObjectURL(blob);
  const a = Object.assign(document.createElement('a'), { href: url, download: name });
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
  toast(/Android/i.test(navigator.userAgent) ? 'Saved to Downloads, also in your gallery' : `${what} saved to your downloads`);
}

// ---------- Video ----------
// Each camera frame is copied from the WebGL canvas onto recCanvas (un-mirrored for the selfie camera,
// like photos) and recorded from there with MediaRecorder, together with the microphone.
const recCanvas = document.createElement('canvas');
const recCtx = recCanvas.getContext('2d');
let recStarting = false, recCancel = false;

function videoMime() {
  const types = ['video/mp4;codecs=avc1,mp4a.40.2', 'video/mp4', 'video/webm;codecs=vp9,opus', 'video/webm;codecs=vp8,opus', 'video/webm'];
  return types.find((t) => MediaRecorder.isTypeSupported?.(t)) || '';
}

async function startRecording() {
  if (recStarting) { recCancel = true; return; }   // tapped again while the microphone was opening
  if (state.recording || !state.stream || state.mode !== 'camera') return;
  if (!window.MediaRecorder || !recCanvas.captureStream) { toast('This browser can’t record video.'); return; }
  recStarting = true; recCancel = false;
  $('btn-shutter').classList.add('busy');
  let mic = null;
  try { mic = await navigator.mediaDevices.getUserMedia({ audio: true }); }
  catch { toast('Recording without sound (no microphone access).'); }
  recStarting = false;
  $('btn-shutter').classList.remove('busy');
  if (recCancel || !state.stream || state.mode !== 'camera') { mic?.getTracks().forEach((t) => t.stop()); return; }
  const [rw, rh] = previewSize(...sourceSize(), true);
  recCanvas.width = rw & ~1; recCanvas.height = rh & ~1;   // encoders want even sizes
  const stream = recCanvas.captureStream(30);
  mic?.getAudioTracks().forEach((t) => stream.addTrack(t));
  const mimeType = videoMime();
  let rec;
  try { rec = new MediaRecorder(stream, { ...(mimeType && { mimeType }), videoBitsPerSecond: 8_000_000 }); }
  catch { mic?.getTracks().forEach((t) => t.stop()); toast('This browser can’t record video.'); return; }
  const r = { rec, mic, chunks: [], start: performance.now(), mirror: state.facing === 'user', timer: 0 };
  rec.ondataavailable = (e) => { if (e.data.size) r.chunks.push(e.data); };
  rec.onstop = () => finishRecording(r);
  state.recording = r;
  copyToRecorder();
  rec.start(1000);
  r.timer = setInterval(drawRecTimer, 250);
  updateUI();
}

function copyToRecorder() {
  const r = state.recording;
  if (!r) return;
  recCtx.save();
  if (r.mirror) { recCtx.translate(recCanvas.width, 0); recCtx.scale(-1, 1); }
  recCtx.drawImage(canvas, 0, 0, recCanvas.width, recCanvas.height);
  recCtx.restore();
}

function stopRecording() {
  const r = state.recording;
  if (!r) return;
  state.recording = null;
  clearInterval(r.timer);
  if (r.rec.state !== 'inactive') r.rec.stop(); else finishRecording(r);
  updateUI();
}

async function finishRecording(r) {
  r.mic?.getTracks().forEach((t) => t.stop());
  const type = (r.rec.mimeType || r.chunks[0]?.type || 'video/webm').split(';')[0];
  const blob = new Blob(r.chunks, { type });
  if (!blob.size) { toast('Couldn’t record that video.'); return; }
  recCanvas.toBlob((b) => b && setThumb(b), 'image/jpeg', 0.8);   // last frame as the thumbnail
  const name = `FilmCam-${filmById(state.filmId)?.name.replace(/[^\w]+/g, '-') || 'video'}-${stamp()}.${type.includes('mp4') ? 'mp4' : 'webm'}`;
  try { await saveBlob(blob, name); }
  catch (e) {
    if (e?.name === 'NotAllowedError') {   // iPhone: the share sheet needs a fresh tap
      state.pendingVideo = { blob, name };
      toast('Tap the thumbnail to save the video', 5000);
    } else if (e?.name !== 'AbortError') toast('Couldn’t save the video.');
  }
}

function drawRecTimer() {
  const r = state.recording;
  if (!r) return;
  const s = Math.floor((performance.now() - r.start) / 1000);
  $('rec-time').textContent = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
}

let micAsked = false;
function pressShutter() {
  if (state.videoMode && state.mode === 'camera') { if (state.recording) stopRecording(); else startRecording(); }
  else shoot();
}

let thumbURL;
function setThumb(blob) {
  if (thumbURL) URL.revokeObjectURL(thumbURL);
  thumbURL = URL.createObjectURL(blob);
  const img = $('last-thumb'); img.src = thumbURL; img.hidden = false; $('upload-icon').style.display = 'none';
}

const stamp = () => new Date().toISOString().replace(/[-:]/g, '').replace('T', '-').slice(0, 15);

// ---------- Stills ----------
async function openPhoto(file) {
  try {
    let img;
    try { img = await createImageBitmap(file, { imageOrientation: 'from-image' }); }
    catch { img = await loadImage(file); }
    // Keep within GPU limits.
    const scale = Math.min(1, maxTex / Math.max(img.width, img.height));
    if (scale < 1) img = await createImageBitmap(img, { resizeWidth: Math.round(img.width * scale), resizeHeight: Math.round(img.height * scale), resizeQuality: 'high' });
    state.still = img;
    state.mode = 'still';
    state.dirty = true;
    hideStatus();
    updateUI();
  } catch {
    toast('Couldn’t open that photo. Try a JPEG or PNG.');
  }
}
function loadImage(file) {
  return new Promise((res, rej) => {
    const img = new Image(); img.onload = () => res(img); img.onerror = rej; img.src = URL.createObjectURL(file);
  });
}
function backToCamera() {
  state.mode = 'camera'; state.still = null; updateUI();
  if (!state.stream) startCamera(); else setupTorch();
}

// ---------- LUT loading & import ----------
async function loadLUT(id) {
  if (lutData.has(id)) return lutData.get(id);
  if (id === 'original') return null;
  if (id.startsWith('u-')) {
    const b64 = store.get('lut:' + id, null);
    if (!b64) return null;
    const data = Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
    lutData.set(id, data); return data;
  }
  const res = await fetch(`luts/${id}.bin`);
  if (!res.ok) throw new Error('lut');
  const data = new Uint8Array(await res.arrayBuffer());
  lutData.set(id, data);
  return data;
}

function sampleLUT(data, [r, g, b]) {
  const n = LUT_SIZE - 1, f = (v) => Math.min(n, Math.max(0, v * n));
  const x = f(r), y = f(g), z = f(b);
  const x0 = Math.floor(x), y0 = Math.floor(y), z0 = Math.floor(z);
  const x1 = Math.min(n, x0 + 1), y1 = Math.min(n, y0 + 1), z1 = Math.min(n, z0 + 1);
  const tx = x - x0, ty = y - y0, tz = z - z0;
  const at = (i, j, k, c) => data[((k * LUT_SIZE + j) * LUT_SIZE + i) * 3 + c];
  const out = [];
  for (let c = 0; c < 3; c++) {
    const c00 = at(x0, y0, z0, c) * (1 - tx) + at(x1, y0, z0, c) * tx;
    const c10 = at(x0, y1, z0, c) * (1 - tx) + at(x1, y1, z0, c) * tx;
    const c01 = at(x0, y0, z1, c) * (1 - tx) + at(x1, y0, z1, c) * tx;
    const c11 = at(x0, y1, z1, c) * (1 - tx) + at(x1, y1, z1, c) * tx;
    out.push(((c00 * (1 - ty) + c10 * ty) * (1 - tz) + (c01 * (1 - ty) + c11 * ty) * tz) / 255);
  }
  return out;
}

/** Resamples a float LUT (RGB, red fastest) of any size to 33³ RGB8. */
function toStandard(values, size) {
  const out = new Uint8Array(LUT_SIZE ** 3 * 3);
  const n = size - 1;
  const at = (i, j, k, c) => values[((k * size + j) * size + i) * 3 + c];
  let o = 0;
  for (let b = 0; b < LUT_SIZE; b++) for (let g = 0; g < LUT_SIZE; g++) for (let r = 0; r < LUT_SIZE; r++) {
    const x = r / (LUT_SIZE - 1) * n, y = g / (LUT_SIZE - 1) * n, z = b / (LUT_SIZE - 1) * n;
    const x0 = Math.floor(x), y0 = Math.floor(y), z0 = Math.floor(z);
    const x1 = Math.min(n, x0 + 1), y1 = Math.min(n, y0 + 1), z1 = Math.min(n, z0 + 1);
    const tx = x - x0, ty = y - y0, tz = z - z0;
    for (let c = 0; c < 3; c++) {
      const c00 = at(x0, y0, z0, c) * (1 - tx) + at(x1, y0, z0, c) * tx;
      const c10 = at(x0, y1, z0, c) * (1 - tx) + at(x1, y1, z0, c) * tx;
      const c01 = at(x0, y0, z1, c) * (1 - tx) + at(x1, y0, z1, c) * tx;
      const c11 = at(x0, y1, z1, c) * (1 - tx) + at(x1, y1, z1, c) * tx;
      const v = (c00 * (1 - ty) + c10 * ty) * (1 - tz) + (c01 * (1 - ty) + c11 * ty) * tz;
      out[o++] = Math.round(Math.min(1, Math.max(0, v)) * 255);
    }
  }
  return out;
}

function parseCube(text) {
  let size = 0, lo = [0, 0, 0], hi = [1, 1, 1];
  const vals = [];
  for (const raw of text.split(/\r?\n/)) {
    const line = raw.trim();
    if (!line || line[0] === '#') continue;
    const p = line.split(/\s+/);
    const key = p[0].toUpperCase();
    if (key === 'LUT_3D_SIZE') size = parseInt(p[1], 10);
    else if (key === 'LUT_1D_SIZE') throw new Error('1D LUTs are not supported');
    else if (key === 'DOMAIN_MIN') lo = p.slice(1, 4).map(Number);
    else if (key === 'DOMAIN_MAX') hi = p.slice(1, 4).map(Number);
    else if (p.length >= 3 && !isNaN(parseFloat(p[0]))) {
      for (let c = 0; c < 3; c++) vals.push((parseFloat(p[c]) - lo[c]) / (hi[c] - lo[c]));
    }
  }
  if (size < 2) throw new Error('missing LUT_3D_SIZE');
  if (vals.length !== size ** 3 * 3) throw new Error(`expected ${size ** 3} entries, found ${vals.length / 3}`);
  return toStandard(vals, size);
}

async function parseHald(file) {
  const img = await createImageBitmap(file, { colorSpaceConversion: 'none', premultiplyAlpha: 'none' });
  const w = img.width, level = Math.round(Math.cbrt(w));
  if (img.height !== w || level ** 3 !== w) throw new Error('image must be a square Hald CLUT (e.g. 512×512 or 1728×1728)');
  const c = new OffscreenCanvas(w, w);
  const ctx = c.getContext('2d', { colorSpace: 'srgb' });
  ctx.drawImage(img, 0, 0);
  const px = ctx.getImageData(0, 0, w, w).data;
  const size = level * level, n = size ** 3;
  const vals = new Float32Array(n * 3);
  for (let i = 0; i < n; i++) { vals[i * 3] = px[i * 4] / 255; vals[i * 3 + 1] = px[i * 4 + 1] / 255; vals[i * 3 + 2] = px[i * 4 + 2] / 255; }
  return toStandard(vals, size);
}

function loadCustomMeta() { return store.get('customLUTs', []); }

async function importLUTs(files) {
  let last = null; const errors = [];
  for (const file of files) {
    try {
      const ext = file.name.split('.').pop().toLowerCase();
      const data = ext === 'cube' ? parseCube(await file.text()) : await parseHald(file);
      const base = file.name.replace(/\.[^.]+$/, '');
      const id = 'u-' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
      lutData.set(id, data);
      let bin = ''; for (let i = 0; i < data.length; i++) bin += String.fromCharCode(data[i]);
      const saved = store.set('lut:' + id, btoa(bin));
      custom.push({ id, name: base, note: saved ? 'Imported LUT' : 'Imported (this session only)', group: 'custom' });
      store.set('customLUTs', custom);
      last = id;
    } catch (e) { errors.push(`${file.name}: ${e.message || 'not a valid LUT'}`); }
  }
  buildStrip();
  if (last) selectFilm(last);
  if (errors.length) toast(errors.join(' · '), 5000);
}

function removeCustom(id) {
  const i = custom.findIndex((f) => f.id === id);
  if (i < 0) return;
  custom.splice(i, 1);
  store.set('customLUTs', custom);
  try { localStorage.removeItem('lut:' + id); } catch {}
  lutData.delete(id);
  bakedKey = '';
  if (state.filmId === id) selectFilm('original');
  buildStrip();
}

// ---------- UI ----------
const allFilms = () => [...FILMS, ...custom];
const filmById = (id) => allFilms().find((f) => f.id === id);

function buildStrip() {
  const strip = $('strip');
  strip.textContent = '';
  for (const [g, label] of Object.entries(GROUPS)) {
    const films = allFilms().filter((f) => f.group === g);
    if (!films.length) continue;
    const l = document.createElement('span'); l.className = 'group-label'; l.textContent = label; strip.appendChild(l);
    for (const f of films) {
      const b = document.createElement('button');
      b.className = 'tile'; b.setAttribute('role', 'option'); b.dataset.id = f.id; b.title = `${f.name}: ${f.note}`;
      b.setAttribute('aria-selected', String(f.id === state.filmId));
      const sw = document.createElement('div'); sw.className = 'swatch' + (f.id === 'original' ? ' none' : '');
      if (f.id === 'original') sw.innerHTML = '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/><path d="m6.5 17.5 11-11"/></svg>';
      const name = document.createElement('span'); name.className = 'name'; name.textContent = f.name;
      b.append(sw, name);
      b.addEventListener('click', () => selectFilm(f.id));
      if (f.group === 'custom') addLongPress(b, () => { if (confirm(`Remove “${f.name}”?`)) removeCustom(f.id); });
      strip.appendChild(b);
      if (f.id !== 'original') loadLUT(f.id).then((d) => d && paintSwatch(sw, d)).catch(() => {});
    }
  }
  scrollToSelected(false);
}

function paintSwatch(el, data) {
  el.textContent = '';
  for (const ref of REFS) {
    const [r, g, b] = sampleLUT(data, ref);
    const cell = document.createElement('i');
    cell.style.background = `rgb(${r * 255}, ${g * 255}, ${b * 255})`;
    el.appendChild(cell);
  }
}

function addLongPress(el, fn) {
  let t;
  const start = () => { t = setTimeout(fn, 600); };
  const cancel = () => clearTimeout(t);
  el.addEventListener('pointerdown', start);
  ['pointerup', 'pointerleave', 'pointercancel'].forEach((e) => el.addEventListener(e, cancel));
  el.addEventListener('contextmenu', (e) => { e.preventDefault(); cancel(); fn(); });
}

function scrollToSelected(smooth = true) {
  const el = $('strip').querySelector(`[data-id="${CSS.escape(state.filmId)}"]`);
  const strip = $('strip');
  if (!el || strip.offsetParent === null) return;   // panel closed: done when it opens
  strip.scrollTo({ left: el.offsetLeft - (strip.clientWidth - el.offsetWidth) / 2, behavior: smooth ? 'smooth' : 'auto' });
}

let badgeTimer;
async function selectFilm(id) {
  if (!filmById(id)) id = 'original';
  try { await loadLUT(id); } catch { toast('Couldn’t load that look.'); return; }
  state.filmId = id; store.set('filmId', id); state.dirty = true;
  for (const t of $('strip').querySelectorAll('.tile')) t.setAttribute('aria-selected', String(t.dataset.id === id));
  scrollToSelected();
  const f = filmById(id);
  $('badge-name').textContent = f.name; $('badge-note').textContent = f.note;
  const badge = $('badge'); badge.classList.add('show');
  clearTimeout(badgeTimer); badgeTimer = setTimeout(() => badge.classList.remove('show'), 1400);
  updateUI();
}

function stepFilm(delta) {
  const list = allFilms(), i = list.findIndex((f) => f.id === state.filmId);
  selectFilm(list[(i + delta + list.length) % list.length].id);
}

function updateUI() {
  const still = state.mode === 'still';
  $('grain-label').textContent = GRAIN_LABELS[state.grain];
  $('btn-grain').classList.toggle('on', state.grain > 0);
  $('btn-grid').classList.toggle('on', state.grid);
  $('look-name').textContent = filmById(state.filmId)?.name || 'Original';
  drawAdjust();
  $('btn-ratio').setAttribute('aria-label', `Frame size: ${state.ratio === 'full' ? 'full screen' : 'whole camera frame'}`);
  lastLayout = ''; state.dirty = true;
  $('grid').hidden = !state.grid;
  $('strength').value = Math.round(state.intensity * 100);
  $('strength-out').textContent = `${Math.round(state.intensity * 100)}%`;
  $('strength-row').classList.toggle('disabled', state.filmId === 'original');
  $('btn-back').hidden = !still;
  $('shutter-label').textContent = still ? 'Save' : '';
  const vid = state.videoMode && !still, rec = !!state.recording;
  $('btn-shutter').setAttribute('aria-label', still ? 'Save photo' : vid ? (rec ? 'Stop recording' : 'Record video') : 'Take photo');
  $('btn-shutter').disabled = !(still || state.stream);
  $('btn-shutter').classList.toggle('video', vid);
  $('btn-shutter').classList.toggle('recording', rec);
  $('btn-flip').disabled = still || !state.stream || rec;
  $('btn-ratio').disabled = still || rec;
  $('btn-upload').disabled = rec;
  $('mode-row').hidden = still;
  for (const b of $('mode-row').children) { b.setAttribute('aria-checked', String((b.dataset.mode === 'video') === state.videoMode)); b.disabled = rec; }
  $('rec-timer').hidden = !rec;
  if (rec) drawRecTimer();
  $('btn-torch').hidden = still || $('btn-torch').hidden;
  if (still) hideStatus();
  drawFocusUI(false);
  drawLenses();
}

const signed = (v) => (v > 0 ? '+' : v < 0 ? '−' : '') + String(Math.abs(v));
let adjustTab = store.get('adjustTab', 'tone');
function drawAdjust() {
  const a = state.adjust;
  for (const input of $('adjust-panel').querySelectorAll('input[data-k]')) {
    const k = input.dataset.k, v = a[k];
    input.value = v;
    const out = input.nextElementSibling;
    out.textContent = k === 'kelvin' ? (v === 5500 ? 'As shot' : `${v}K`) : signed(v);
    out.classList.toggle('changed', v !== ADJUST_DEFAULTS[k]);
  }
  for (const seg of $('adjust-panel').querySelectorAll('.seg'))
    for (const b of seg.children) b.setAttribute('aria-checked', String(Number(b.dataset.v) === a[seg.dataset.k]));
  $('adjust-dot').hidden = isDefaultAdjust(a);
  // Two tabs; each shows a dot when something in it is changed.
  for (const b of $('adjust-panel').querySelectorAll('.adj-tabs button')) {
    const page = $('adjust-panel').querySelector(`.adj-page[data-page="${b.dataset.tab}"]`);
    const on = b.dataset.tab === adjustTab;
    b.setAttribute('aria-selected', String(on));
    page.hidden = !on;
    b.querySelector('.dot').hidden = ![...page.querySelectorAll('[data-k]')].some((el) => a[el.dataset.k] !== ADJUST_DEFAULTS[el.dataset.k]);
  }
}

function showStatus(title, detail) {
  $('status-title').textContent = title; $('status-detail').textContent = detail; $('status').hidden = false;
}
function hideStatus() { $('status').hidden = true; }

let toastTimer;
function toast(msg, ms = 2600) {
  const t = $('toast'); t.textContent = msg; t.classList.add('show');
  clearTimeout(toastTimer); toastTimer = setTimeout(() => t.classList.remove('show'), ms);
}

/** Opens one of the panels above the shutter ('look' | 'adjust'), or closes them (null). */
let openPanel = null;
function setPanel(name) {
  openPanel = name;
  $('look-panel').hidden = name !== 'look';
  $('adjust-panel').hidden = name !== 'adjust';
  $('controls').classList.toggle('open', !!name);
  $('btn-look').setAttribute('aria-expanded', String(name === 'look'));
  $('btn-adjust').setAttribute('aria-expanded', String(name === 'adjust'));
  if (name === 'look') scrollToSelected(false);
}
function setMenu(open) {
  $('menu').hidden = !open;
  $('btn-more').setAttribute('aria-expanded', String(open));
}
/** Closes the look panel and the menu; true if anything was open. */
function closePanels() {
  const was = !!openPanel || !$('menu').hidden;
  setPanel(null); setMenu(false);
  return was;
}

function bindUI() {
  $('btn-look').onclick = () => { setMenu(false); setPanel(openPanel === 'look' ? null : 'look'); };
  $('btn-adjust').onclick = () => { setMenu(false); setPanel(openPanel === 'adjust' ? null : 'adjust'); };
  const setAdjust = (k, v) => {
    state.adjust[k] = v; store.set('adjust', state.adjust); state.dirty = true; updateUI();
  };
  for (const input of $('adjust-panel').querySelectorAll('input[data-k]')) input.oninput = () => setAdjust(input.dataset.k, Number(input.value));
  for (const seg of $('adjust-panel').querySelectorAll('.seg')) seg.onclick = (e) => { const b = e.target.closest('button'); if (b) setAdjust(seg.dataset.k, Number(b.dataset.v)); };
  $('adjust-panel').querySelector('.adj-tabs').onclick = (e) => {
    const b = e.target.closest('button'); if (!b) return;
    adjustTab = b.dataset.tab; store.set('adjustTab', adjustTab); drawAdjust();
  };
  $('adj-reset').onclick = () => {
    state.adjust = { ...ADJUST_DEFAULTS }; store.set('adjust', state.adjust); state.dirty = true; updateUI();
  };
  $('btn-more').onclick = (e) => { e.stopPropagation(); setMenu($('menu').hidden); };
  document.addEventListener('pointerdown', (e) => { if (!e.target.closest('#menu, #btn-more')) setMenu(false); });
  $('menu').addEventListener('click', (e) => { if (e.target.closest('#btn-import, #btn-about')) setMenu(false); });
  $('btn-grain').onclick = () => { state.grain = (state.grain + 1) % 3; store.set('grain', state.grain); state.dirty = true; updateUI(); };
  $('btn-grid').onclick = () => { state.grid = !state.grid; store.set('grid', state.grid); updateUI(); };
  $('btn-torch').onclick = toggleTorch;
  $('btn-ratio').onclick = () => {
    state.ratio = state.ratio === 'full' ? 'fit' : 'full'; store.set('ratio', state.ratio);
    updateUI();
    if (state.focus.point) resetFocus();   // the old point referred to the previous framing
  };
  addEventListener('resize', () => { lastLayout = ''; state.dirty = true; });
  $('btn-about').onclick = () => $('about').showModal();
  $('btn-import').onclick = () => $('file-lut').click();
  $('btn-upload').onclick = () => {
    const v = state.pendingVideo;
    if (v) { state.pendingVideo = null; saveBlob(v.blob, v.name).catch(() => {}); return; }
    $('file-photo').click();
  };
  $('status-upload').onclick = () => $('file-photo').click();
  $('btn-back').onclick = backToCamera;
  $('btn-shutter').onclick = pressShutter;
  $('mode-row').onclick = (e) => {
    const b = e.target.closest('button');
    if (!b || state.recording) return;
    state.videoMode = b.dataset.mode === 'video';
    updateUI();
    // Ask for the microphone now (once), so pressing record starts straight away.
    if (state.videoMode && !micAsked) {
      micAsked = true;
      navigator.mediaDevices?.getUserMedia({ audio: true }).then((s) => s.getTracks().forEach((t) => t.stop()), () => {});
    }
  };
  $('btn-flip').onclick = () => { state.facing = state.facing === 'user' ? 'environment' : 'user'; store.set('facing', state.facing); state.lens.stops = []; drawLenses(); startCamera(); };
  $('strength').oninput = (e) => { state.intensity = e.target.value / 100; store.set('intensity', state.intensity); state.dirty = true; updateUI(); };
  $('file-photo').onchange = (e) => { const f = e.target.files[0]; if (f) openPhoto(f); e.target.value = ''; };
  $('file-lut').onchange = (e) => { const fs = [...e.target.files]; if (fs.length) importLUTs(fs); e.target.value = ''; };

  bindViewfinderGestures();

  document.addEventListener('keydown', (e) => {
    if (e.target.closest('input, dialog')) return;
    if (e.key === 'ArrowRight') stepFilm(1);
    else if (e.key === 'ArrowLeft') stepFilm(-1);
    else if ((e.key === ' ' || e.key === 'Enter') && !e.target.closest('button')) { e.preventDefault(); pressShutter(); }
  });

  // Pause the camera when the page is hidden (saves battery, frees the camera).
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { stopRecording(); cameraRequest++; stopCamera(); updateUI(); }
    else if (state.mode === 'camera' && !state.stream) startCamera();
  });
}

// ---------- Boot ----------
(async function main() {
  bindUI();
  if (!gl) {
    showStatus('WebGL 2 not supported', 'This browser can’t run FilmCam. Try a recent Safari, Chrome, Firefox or Edge.');
    $('status-upload').hidden = true;
    return;
  }
  try { initGL(); } catch (e) { showStatus('Graphics error', String(e.message || e)); return; }
  buildStrip();
  await selectFilm(state.filmId);
  $('badge').classList.remove('show');
  updateUI();
  requestAnimationFrame(frame);
  startCamera();
})();
