"""Fit smooth 3D LUTs (Provia -> simulation) from pixel-aligned reference images.

Reference images: Fujifilm's film-simulation samples (three scenes, each rendered from the same RAW
in every simulation), as collected by https://github.com/friimaind/film-simulations-comparator
in its images/ folder (files named simulation_<subject>_<sim>.jpg). They are not included here.

Method: bin aligned Provia/simulation pixel pairs by colour, then solve for a 17³ lattice offset from
identity by least squares with a thin-plate (Laplacian²) smoothness penalty and a weak pull toward
identity, so colours absent from the photos fall back smoothly. lam_s=0.3 was picked by
leave-one-scene-out cross-validation. Output is resampled to 33³ (see the driver at the bottom).

Usage: python3 fit-camera-looks.py /path/to/images /path/to/output_dir
"""
import numpy as np, os, sys
from PIL import Image, ImageFilter

D = sys.argv[1] if len(sys.argv) > 1 else '.'
SUBJECTS = ['lili-liu', 'naoya-takahashi', 'ana-markovych']
SIMS = ['velvia', 'astia', 'classic-chrome', 'classic-neg', 'nostalgic-neg', 'reala-ace',
        'pro-neg-std', 'pro-neg-hi', 'eterna', 'eterna-bb', 'acros']
G = 17  # fitting lattice

def path(s, sim):
    f = f'{D}/simulation_{s}_{sim}.jpg'
    return f if os.path.exists(f) else f.replace('eterna-bb', 'eternabb')

def load(s, sim, w=800):
    im = Image.open(path(s, sim)).convert('RGB')
    im = im.resize((w, round(w * im.height / im.width)), Image.LANCZOS).filter(ImageFilter.GaussianBlur(1.2))
    return np.asarray(im, dtype=np.float64).reshape(-1, 3) / 255

def pairs(subjects, sim):
    P, Y = [], []
    for s in subjects:
        p, y = load(s, 'provia'), load(s, sim)
        ok = (p.min(1) > 0.01) & (p.max(1) < 0.985) & (y.max(1) < 0.985)
        P.append(p[ok]); Y.append(y[ok])
    return np.concatenate(P), np.concatenate(Y)

def binned(P, Y, nb=40):
    """Average samples into colour bins -> (p_mean, y_mean, weight)."""
    idx = np.clip((P * nb).astype(int), 0, nb - 1)
    key = (idx[:, 2] * nb + idx[:, 1]) * nb + idx[:, 0]
    uk, inv, cnt = np.unique(key, return_inverse=True, return_counts=True)
    pm = np.stack([np.bincount(inv, P[:, c]) for c in range(3)], 1) / cnt[:, None]
    ym = np.stack([np.bincount(inv, Y[:, c]) for c in range(3)], 1) / cnt[:, None]
    return pm, ym, np.minimum(np.sqrt(cnt), 30.0)

def interp_weights(pts, n=G):
    x = np.clip(pts, 0, 1) * (n - 1)
    i0 = np.minimum(np.floor(x).astype(int), n - 2); t = x - i0
    idx, wts = [], []
    for dz in (0, 1):
        for dy in (0, 1):
            for dx in (0, 1):
                ii = ((i0[:, 2] + dz) * n + (i0[:, 1] + dy)) * n + (i0[:, 0] + dx)
                ww = (t[:, 0] if dx else 1 - t[:, 0]) * (t[:, 1] if dy else 1 - t[:, 1]) * (t[:, 2] if dz else 1 - t[:, 2])
                idx.append(ii); wts.append(ww)
    return np.stack(idx, 1), np.stack(wts, 1)

def lap(o, n=G):
    """Graph Laplacian on the lattice (Neumann boundary), o: (n³, 3)."""
    g = o.reshape(n, n, n, 3); out = np.zeros_like(g)
    for ax in range(3):
        fwd = np.diff(g, axis=ax)
        pad = [(0, 0)] * 4
        p1 = pad.copy(); p1[ax] = (0, 1); p2 = pad.copy(); p2[ax] = (1, 0)
        out += np.pad(fwd, p1) - np.pad(fwd, p2)
    return (-out).reshape(-1, 3)

def fit(pm, ym, w, lam_s=0.3, lam_r=1e-3, iters=400):
    idx, wts = interp_weights(pm)
    n3 = G ** 3
    target = ym - pm                      # fit the offset from identity
    W2 = w ** 2
    def Wmul(o): return (o[idx] * wts[..., None]).sum(1)
    def WTmul(r):
        out = np.zeros((n3, 3))
        for k in range(8):
            for c in range(3): out[:, c] += np.bincount(idx[:, k], wts[:, k] * r[:, c], minlength=n3)
        return out
    scale = W2.sum() / n3
    def A(o): return WTmul(W2[:, None] * Wmul(o)) + scale * (lam_s * lap(lap(o)) + lam_r * o)
    b = WTmul(W2[:, None] * target)
    o = np.zeros((n3, 3)); r = b - A(o); p = r.copy(); rs = (r * r).sum(0)
    for _ in range(iters):            # conjugate gradient, per channel
        Ap = A(p); alpha = rs / np.maximum((p * Ap).sum(0), 1e-30)
        o += alpha * p; r -= alpha * Ap; rn = (r * r).sum(0)
        if np.all(rn < 1e-14): break
        p = r + (rn / np.maximum(rs, 1e-30)) * p; rs = rn
    grid = np.stack(np.meshgrid(*[np.linspace(0, 1, G)] * 3, indexing='ij')[::-1], -1).reshape(-1, 3)
    # meshgrid ij -> [b][g][r]; reversed gives (r, g, b) with red fastest
    return np.clip(grid + o, 0, 1)

def apply(lut, pts, n=G):
    idx, wts = interp_weights(pts, n)
    return (lut[idx] * wts[..., None]).sum(1)

# OKLab ΔE for evaluation
def to_lin(c): return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
def oklab(c):
    c = to_lin(np.clip(c, 0, 1))
    l = np.cbrt(c @ np.array([0.4122214708, 0.5363325363, 0.0514459929]))
    m = np.cbrt(c @ np.array([0.2119034982, 0.6806995451, 0.1073969566]))
    s = np.cbrt(c @ np.array([0.0883024619, 0.2817188376, 0.6299787005]))
    lms = np.stack([l, m, s], 1)
    return lms @ np.array([[0.2104542553, 1.9779984951, 0.0259040371], [0.7936177850, -2.4285922050, 0.7827717662], [-0.0040720468, 0.4505937099, -0.8086757660]])
def dE(a, b): return np.linalg.norm(oklab(a) - oklab(b), axis=1).mean() * 100


if __name__ == '__main__' and len(sys.argv) > 2:
    out = sys.argv[2]
    names = {'eterna-bb': 'eterna-bleach'}
    n33 = np.stack(np.meshgrid(*[np.linspace(0, 1, 33)] * 3, indexing='ij')[::-1], -1).reshape(-1, 3)
    for sim in SIMS:
        lut33 = np.clip(apply(fit(*binned(*pairs(SUBJECTS, sim)), lam_s=0.3), n33), 0, 1)
        if sim == 'acros': lut33[:] = lut33.mean(1, keepdims=True)
        np.round(lut33 * 65535).astype('<u2').tofile(f"{out}/cam-{names.get(sim, sim)}.lut")
        print('wrote', sim)
