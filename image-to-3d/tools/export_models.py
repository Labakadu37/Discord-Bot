"""
Exporte les modèles d'IA utilisés par l'application Image 3D au format ONNX.

  - encodeur TripoSR : image 512x512 -> triplan (scene code) [3, 40, 64, 64]
  - décodeur TripoSR : triplan + points 3D -> densité + couleur
  - u2netp.onnx          : détourage automatique (fond supprimé)

Usage :
  git clone https://github.com/VAST-AI-Research/TripoSR.git
  pip install torch==2.14.0 --index-url https://download.pytorch.org/whl/cpu
  pip install -r requirements.txt
  python export_models.py --triposr-src ./TripoSR --out ./models

Fichiers produits dans --out (à publier, l'application les télécharge) :
  triposr_encoder_int8.ort  (~455 Mo, qualité standard, recommandé)
  triposr_encoder.ort       (~1,7 Go, précision maximale)
  triposr_decoder.onnx, u2netp.onnx, manifest.json
"""

import argparse
import hashlib
import json
import os
import shutil
import sys
import urllib.request

import numpy as np
import onnxruntime as ort

import torch
import torch.nn as nn
import torch.nn.functional as F

IMAGE_SIZE = 512
U2NETP_URL = "https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2netp.onnx"


class Encoder(nn.Module):
    """Image (1, 3, 512, 512) dans [0, 1], fond gris -> triplan (3, 40, 64, 64)."""

    def __init__(self, tsr):
        super().__init__()
        self.tsr = tsr

    def forward(self, image):
        tsr = self.tsr
        tokens_img = tsr.image_tokenizer(image[:, None])  # B, Nv, C, Nt
        tokens_img = tokens_img[:, 0].permute(0, 2, 1)  # B, Nt, C
        tokens = tsr.tokenizer(1)
        tokens = tsr.backbone(tokens, encoder_hidden_states=tokens_img)
        scene_codes = tsr.post_processor(tsr.tokenizer.detokenize(tokens))
        return scene_codes[0]


class Decoder(nn.Module):
    """Triplan + positions (N, 3) dans [-radius, radius] -> densité (N, 1), couleur (N, 3)."""

    def __init__(self, tsr):
        super().__init__()
        self.mlp = tsr.decoder.layers
        self.radius = tsr.renderer.cfg.radius
        self.density_bias = tsr.renderer.cfg.density_bias

    def forward(self, triplane, positions):
        x = positions / self.radius
        idx = torch.stack((x[:, [0, 1]], x[:, [0, 2]], x[:, [1, 2]]), dim=0)  # 3, N, 2
        feats = F.grid_sample(
            triplane, idx[:, None], align_corners=False, mode="bilinear"
        )  # 3, 40, 1, N
        feats = feats[:, :, 0].permute(2, 0, 1).reshape(positions.shape[0], -1)  # N, 120
        out = self.mlp(feats)
        density = torch.exp(out[:, 0:1] + self.density_bias)
        color = torch.sigmoid(out[:, 1:4])
        return density, color


class ChunkedAttnProcessor:
    """Attention calculée par blocs de requêtes : même résultat, pic mémoire ~6x plus faible sur téléphone."""

    def __init__(self, chunk=512):
        self.chunk = chunk

    def __call__(self, attn, hidden_states, encoder_hidden_states=None, attention_mask=None):
        residual = hidden_states
        b = hidden_states.shape[0]
        query = attn.to_q(hidden_states)
        if encoder_hidden_states is None:
            encoder_hidden_states = hidden_states
        elif attn.norm_cross:
            encoder_hidden_states = attn.norm_encoder_hidden_states(encoder_hidden_states)
        key = attn.to_k(encoder_hidden_states)
        value = attn.to_v(encoder_hidden_states)
        head_dim = key.shape[-1] // attn.heads
        query = query.view(b, -1, attn.heads, head_dim).transpose(1, 2)
        key_t = key.view(b, -1, attn.heads, head_dim).permute(0, 2, 3, 1) * (head_dim ** -0.5)
        value = value.view(b, -1, attn.heads, head_dim).transpose(1, 2)
        outs = []
        for q in torch.split(query, self.chunk, dim=2):
            outs.append(torch.matmul(torch.softmax(torch.matmul(q, key_t), dim=-1), value))
        hidden_states = torch.cat(outs, dim=2).transpose(1, 2).reshape(b, -1, attn.heads * head_dim)
        hidden_states = attn.to_out[1](attn.to_out[0](hidden_states))
        if attn.residual_connection:
            hidden_states = hidden_states + residual
        return hidden_states / attn.rescale_output_factor


def use_chunked_attention(tsr):
    from tsr.models.transformer.attention import Attention

    for m in tsr.backbone.modules():
        if isinstance(m, Attention):
            assert m.group_norm is None and m.scale_qk
            m.set_processor(ChunkedAttnProcessor())


def bake_position_embeddings(tsr):
    """Pré-calcule l'interpolation des position embeddings DINO pour 512x512 (évite un Resize bicubique en ONNX)."""
    vit = tsr.image_tokenizer.model
    emb = vit.embeddings
    with torch.no_grad():
        dummy = torch.zeros(1, 1025, emb.position_embeddings.shape[-1])
        baked = emb.interpolate_pos_encoding(dummy, IMAGE_SIZE, IMAGE_SIZE)
    # La méthode renvoie désormais une constante : l'export ne contient plus d'interpolation
    emb.interpolate_pos_encoding = lambda embeddings, height, width: baked


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--triposr-src", required=True)
    ap.add_argument("--out", default="models")
    ap.add_argument("--work", default="build_onnx", help="dossier des fichiers intermédiaires")
    ap.add_argument("--skip-fp32", action="store_true", help="ne produit pas l'encodeur pleine précision (1,7 Go)")
    args = ap.parse_args()

    sys.path.insert(0, os.path.abspath(args.triposr_src))
    # Modules seulement utilisés par l'inférence PC de TripoSR : inutiles pour l'export
    import types

    for name in ("torchmcubes", "rembg", "imageio"):
        try:
            __import__(name)
        except ImportError:
            sys.modules[name] = types.SimpleNamespace(marching_cubes=None)
    from tsr.system import TSR

    os.makedirs(args.out, exist_ok=True)
    tsr = TSR.from_pretrained("stabilityai/TripoSR", config_name="config.yaml", weight_name="model.ckpt")
    tsr.eval()
    bake_position_embeddings(tsr)

    # Référence (attention standard) pour vérifier que l'export donne le même résultat
    torch.manual_seed(0)
    check_image = torch.rand(1, 3, IMAGE_SIZE, IMAGE_SIZE)
    with torch.no_grad():
        reference = Encoder(tsr).eval()(check_image).numpy()
    use_chunked_attention(tsr)

    encoder = Encoder(tsr).eval()
    decoder = Decoder(tsr).eval()

    work = args.work
    os.makedirs(work, exist_ok=True)
    enc_path = os.path.join(work, "triposr_encoder.onnx")
    dec_path = os.path.join(work, "triposr_decoder.onnx")

    with torch.no_grad():
        image = torch.rand(1, 3, IMAGE_SIZE, IMAGE_SIZE)
        print("Export de l'encodeur...")
        torch.onnx.export(
            encoder, (image,), enc_path, input_names=["image"], output_names=["triplane"],
            opset_version=17, dynamo=False,
        )
        triplane = encoder(image)
        print("Export du décodeur...")
        pts = torch.rand(4096, 3) * 1.6 - 0.8
        torch.onnx.export(
            decoder, (triplane, pts), dec_path, input_names=["triplane", "positions"],
            output_names=["density", "color"], dynamic_axes={"positions": {0: "n"}, "density": {0: "n"}, "color": {0: "n"}},
            opset_version=17, dynamo=False,
        )

    so = ort.SessionOptions()
    so.add_session_config_entry("session.disable_prepacking", "1")
    got = ort.InferenceSession(enc_path, so, providers=["CPUExecutionProvider"]).run(None, {"image": check_image.numpy()})[0]
    err = float(np.abs(got - reference).max())
    print(f"Écart max ONNX vs PyTorch : {err:.2e}")
    assert err < 1e-2, "l'export de l'encodeur ne correspond pas au modèle d'origine"

    print("Quantification int8 de l'encodeur...")
    from onnxruntime.quantization import QuantType, quantize_dynamic

    q_path = os.path.join(work, "triposr_encoder_int8.onnx")
    quantize_dynamic(enc_path, q_path, weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul", "Gemm"])

    # Format ORT optimisé pour ARM : le téléphone lit les poids directement depuis le fichier (mmap)
    print("Conversion au format ORT (ARM)...")
    import subprocess

    encoders = [q_path] + ([] if args.skip_fp32 else [enc_path])
    ort_dir = os.path.join(work, "ort")
    os.makedirs(ort_dir, exist_ok=True)
    for p in encoders:
        shutil.copy(p, ort_dir)
    subprocess.check_call([
        sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort", ort_dir,
        "--optimization_style", "Fixed", "--target_platform", "arm",
    ])
    for p in encoders:
        name = os.path.basename(p).replace(".onnx", ".ort")
        shutil.move(os.path.join(ort_dir, name), os.path.join(args.out, name))
        opts = ort.SessionOptions()
        opts.add_session_config_entry("session.disable_prepacking", "1")
        got = ort.InferenceSession(os.path.join(args.out, name), opts, providers=["CPUExecutionProvider"]).run(
            None, {"image": check_image.numpy()})[0]
        rel = float(np.abs(got - reference).mean() / np.abs(reference).mean())
        print(f"{name} : écart relatif moyen {rel:.3f}")
        assert rel < 0.2
    shutil.copy(dec_path, args.out)

    u2_path = os.path.join(args.out, "u2netp.onnx")
    if not os.path.exists(u2_path):
        print("Téléchargement de u2netp...")
        urllib.request.urlretrieve(U2NETP_URL, u2_path)

    manifest = {"version": 1, "files": {}}
    for f in sorted(os.listdir(args.out)):
        if f.endswith((".onnx", ".ort")):
            p = os.path.join(args.out, f)
            manifest["files"][f] = {"size": os.path.getsize(p), "sha256": sha256(p)}
    with open(os.path.join(args.out, "manifest.json"), "w") as fp:
        json.dump(manifest, fp, indent=2)
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
