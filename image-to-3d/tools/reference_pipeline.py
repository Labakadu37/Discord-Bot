"""
Pipeline de référence (PC) identique à celui de l'application Android.
Sert à valider les modèles ONNX et à comparer les résultats du code Kotlin.

  python reference_pipeline.py image.png --models ./models --out ./out [--resolution 192] [--encoder triposr_encoder.ort]

Étapes : détourage (u2netp) -> recadrage 85 % sur fond gris -> encodeur TripoSR -> grille de densité
-> surface nets -> nettoyage des morceaux flottants -> couleurs par sommet -> export .obj + aperçu .png
"""

import argparse
import os
import time

import numpy as np
import onnxruntime as ort
from PIL import Image

RADIUS = 0.87
THRESHOLD = 25.0
FOREGROUND_RATIO = 0.85


def session(path):
    so = ort.SessionOptions()
    so.add_session_config_entry("session.disable_prepacking", "1")
    return ort.InferenceSession(path, so, providers=["CPUExecutionProvider"])


def remove_background(img, u2net):
    """u2netp : 320x320, normalisation ImageNet après division par le max (comme rembg)."""
    rgb = img.convert("RGB")
    x = np.asarray(rgb.resize((320, 320), Image.LANCZOS)).astype(np.float32)
    x = x / max(x.max(), 1e-6)
    x = (x - np.array([0.485, 0.456, 0.406])) / np.array([0.229, 0.224, 0.225])
    x = x.transpose(2, 0, 1)[None].astype(np.float32)
    pred = u2net.run(None, {u2net.get_inputs()[0].name: x})[0][0, 0]
    pred = (pred - pred.min()) / max(pred.max() - pred.min(), 1e-6)
    mask = Image.fromarray((pred * 255).astype(np.uint8)).resize(rgb.size, Image.LANCZOS)
    out = rgb.convert("RGBA")
    out.putalpha(mask)
    return out


def prepare(img):
    """Recadre l'objet (alpha), le centre à 85 % et le pose sur un fond gris 0.5 en 512x512."""
    a = np.asarray(img).astype(np.float32) / 255.0
    ys, xs = np.where(a[..., 3] > 0.5)
    fg = a[ys.min() : ys.max() + 1, xs.min() : xs.max() + 1]
    h, w = fg.shape[:2]
    size = max(h, w)
    canvas_size = int(size / FOREGROUND_RATIO)
    canvas = np.zeros((canvas_size, canvas_size, 4), np.float32)
    y0, x0 = (canvas_size - h) // 2, (canvas_size - w) // 2
    canvas[y0 : y0 + h, x0 : x0 + w] = fg
    rgb = canvas[..., :3] * canvas[..., 3:4] + (1 - canvas[..., 3:4]) * 0.5
    im = Image.fromarray((rgb * 255).astype(np.uint8)).resize((512, 512), Image.BILINEAR)
    return im


def density_grid(decoder, triplane, res, chunk=65536):
    lin = np.linspace(-RADIUS, RADIUS, res, dtype=np.float32)
    x, y, z = np.meshgrid(lin, lin, lin, indexing="ij")
    pts = np.stack([x.ravel(), y.ravel(), z.ravel()], -1)
    out = np.empty(len(pts), np.float32)
    for i in range(0, len(pts), chunk):
        d, _ = decoder.run(None, {"triplane": triplane, "positions": pts[i : i + chunk]})
        out[i : i + chunk] = d[:, 0]
    return out.reshape(res, res, res), lin


def surface_nets(field, iso, lin):
    """Surface nets : un sommet par cellule traversée par la surface (moyenne des points de
    passage sur ses arêtes), un quad par arête de grille traversée. L'intérieur est field > iso ;
    les faces sont orientées vers l'extérieur."""
    res = field.shape[0]
    n = res - 1
    step = lin[1] - lin[0]
    inside = field > iso
    corners = [(i, j, k) for i in (0, 1) for j in (0, 1) for k in (0, 1)]

    def corner(arr, c):
        return arr[c[0] : c[0] + n, c[1] : c[1] + n, c[2] : c[2] + n]

    acc = np.zeros((n, n, n, 3), np.float32)
    cnt = np.zeros((n, n, n), np.float32)
    for a in range(8):
        for b in range(a + 1, 8):
            ca, cb = np.array(corners[a]), np.array(corners[b])
            if np.abs(ca - cb).sum() != 1:
                continue
            fa, fb = corner(field, ca), corner(field, cb)
            cross = corner(inside, ca) != corner(inside, cb)
            t = np.where(cross, (iso - fa) / np.where(cross, fb - fa, 1), 0)
            acc += cross[..., None] * (ca + t[..., None] * (cb - ca))
            cnt += cross
    cells = cnt > 0
    vid = -np.ones((n, n, n), np.int64)
    vid[cells] = np.arange(cells.sum())
    idx = np.argwhere(cells)
    verts = (idx + acc[cells] / cnt[cells][:, None]) * step + lin[0]

    faces = []
    for axis in range(3):
        u, v = (axis + 1) % 3, (axis + 2) % 3
        sl_a = [slice(None)] * 3
        sl_b = [slice(None)] * 3
        sl_a[axis] = slice(0, n)
        sl_b[axis] = slice(1, res)
        a = inside[tuple(sl_a)]
        b = inside[tuple(sl_b)]
        p = np.argwhere(a != b)
        p = p[(p[:, u] >= 1) & (p[:, u] <= n - 1) & (p[:, v] >= 1) & (p[:, v] <= n - 1)]
        quad = []
        for du, dv in ((-1, -1), (0, -1), (0, 0), (-1, 0)):  # sens trigo dans le plan (u, v)
            c = p.copy()
            c[:, u] += du
            c[:, v] += dv
            quad.append(vid[c[:, 0], c[:, 1], c[:, 2]])
        quad = np.stack(quad, 1)
        flip = ~a[p[:, 0], p[:, 1], p[:, 2]]  # extérieur -> intérieur : normale vers -axis
        quad[flip] = quad[flip][:, ::-1]
        faces.append(quad[:, [0, 1, 2]])
        faces.append(quad[:, [0, 2, 3]])
    return verts.astype(np.float32), np.concatenate(faces)


def keep_main_components(verts, faces, min_ratio=0.05):
    """Supprime les petits morceaux flottants (bruit) : garde les composantes >= 5 % de la plus grande."""
    from scipy.sparse import coo_matrix
    from scipy.sparse.csgraph import connected_components

    rows = np.concatenate([faces[:, 0], faces[:, 1]])
    cols = np.concatenate([faces[:, 1], faces[:, 2]])
    graph = coo_matrix((np.ones(len(rows)), (rows, cols)), shape=(len(verts),) * 2)
    _, labels = connected_components(graph, directed=False)
    face_label = labels[faces[:, 0]]
    counts = np.bincount(face_label)
    faces = faces[counts[face_label] >= counts.max() * min_ratio]
    used = np.unique(faces)
    remap = -np.ones(len(verts), np.int64)
    remap[used] = np.arange(len(used))
    return verts[used], remap[faces]


def to_y_up(v):
    """Repère TripoSR (Z en haut, face vers +X) -> glTF (Y en haut, face vers +Z)."""
    return np.stack([v[:, 1], v[:, 2], v[:, 0]], -1)


def render_preview(verts, faces, colors, path, size=512, yaw=0.5):
    """Petit rendu logiciel (z-buffer par face) pour contrôler forme, orientation et couleurs."""
    c, s = np.cos(yaw), np.sin(yaw)
    rot = np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    v = verts @ rot.T
    tri = v[faces]
    n = np.cross(tri[:, 1] - tri[:, 0], tri[:, 2] - tri[:, 0])
    n /= np.linalg.norm(n, axis=1, keepdims=True) + 1e-9
    light = np.clip(n @ np.array([0.3, 0.5, 0.8]) / np.linalg.norm([0.3, 0.5, 0.8]), 0, 1) * 0.7 + 0.3
    col = colors[faces].mean(1) * light[:, None]
    img = np.full((size, size, 3), 40, np.uint8)
    zbuf = np.full((size, size), -1e9)
    scale = size / 1.9
    front = n[:, 2] > 0
    for t, cc in zip(tri[front], col[front]):
        xs = t[:, 0] * scale + size / 2
        ys = size / 2 - t[:, 1] * scale
        z = t[:, 2].mean()
        x0, x1 = int(max(xs.min(), 0)), int(min(xs.max() + 1, size))
        y0, y1 = int(max(ys.min(), 0)), int(min(ys.max() + 1, size))
        for yy in range(y0, y1):
            for xx in range(x0, x1):
                p = np.array([xx + 0.5, yy + 0.5])
                d = [(xs[(k + 1) % 3] - xs[k]) * (p[1] - ys[k]) - (ys[(k + 1) % 3] - ys[k]) * (p[0] - xs[k]) for k in range(3)]
                if (min(d) >= 0 or max(d) <= 0) and z > zbuf[yy, xx]:
                    zbuf[yy, xx] = z
                    img[yy, xx] = (np.clip(cc, 0, 1) * 255).astype(np.uint8)
    Image.fromarray(img).save(path)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("image")
    ap.add_argument("--models", default="models")
    ap.add_argument("--encoder", default="triposr_encoder_int8.ort")
    ap.add_argument("--out", default="out")
    ap.add_argument("--resolution", type=int, default=128)
    ap.add_argument("--no-remove-bg", action="store_true")
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)

    t = time.time()
    img = Image.open(args.image).convert("RGBA")
    has_alpha = np.asarray(img)[..., 3].min() < 255
    if not args.no_remove_bg and not has_alpha:
        img = remove_background(img, session(os.path.join(args.models, "u2netp.onnx")))
    prepared = prepare(img)
    prepared.save(os.path.join(args.out, "input.png"))
    x = (np.asarray(prepared).astype(np.float32) / 255).transpose(2, 0, 1)[None]
    triplane = session(os.path.join(args.models, args.encoder)).run(None, {"image": x})[0]
    print(f"encodeur : {time.time() - t:.1f}s")

    t = time.time()
    decoder = session(os.path.join(args.models, "triposr_decoder.onnx"))
    field, lin = density_grid(decoder, triplane, args.resolution)
    np.save(os.path.join(args.out, "density.npy"), field)
    verts, faces = surface_nets(field, THRESHOLD, lin)
    verts, faces = keep_main_components(verts, faces)
    _, colors = decoder.run(None, {"triplane": triplane, "positions": verts.astype(np.float32)})
    verts = to_y_up(verts)
    print(f"maillage : {len(verts)} sommets, {len(faces)} triangles, {time.time() - t:.1f}s")

    with open(os.path.join(args.out, "mesh.obj"), "w") as f:
        for p, c in zip(verts, colors):
            f.write(f"v {p[0]:.5f} {p[1]:.5f} {p[2]:.5f} {c[0]:.4f} {c[1]:.4f} {c[2]:.4f}\n")
        for tri in faces + 1:
            f.write(f"f {tri[0]} {tri[1]} {tri[2]}\n")
    vol = np.einsum("ij,ij->i", verts[faces[:, 0]], np.cross(verts[faces[:, 1]], verts[faces[:, 2]])).sum() / 6
    print(f"volume signé : {vol:.4f} (positif = normales vers l'extérieur)")
    render_preview(verts, faces, colors, os.path.join(args.out, "preview.png"))


if __name__ == "__main__":
    main()
