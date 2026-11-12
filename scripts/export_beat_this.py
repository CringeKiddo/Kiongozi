#!/usr/bin/env python3
"""
Export CPJKU/beat_this to ONNX for the Kiongozi Android app.

What it does
------------
1. Loads a beat_this checkpoint (final0 / small0 / ...) on CPU.
2. Exports the neural network (NOT the mel frontend) to ONNX, trying several
   exporter strategies and validating each against PyTorch with onnxruntime.
   STATIC shapes are tried first: Android's GPU/NPU path (NNAPI) cannot run
   dynamic shapes, so a fixed 1500-frame chunk is the GPU-friendly choice.
3. Converts to FP16 (default), INT8-dynamic, or leaves FP32.
   (FP16 is also what mobile GPUs prefer.)
4. Writes:
     <out>                               the .onnx model
     <out dir>/beat_this_spec.json       exact I/O + frontend spec for Batch 2
     <out dir>/mel_filterbank_f32.bin    mel filterbank [n_freqs=513, n_mels=128] float32 LE
     <golden dir>/*.bin                  golden vectors to unit-test the Kotlin
                                         log-mel frontend and ONNX inference
5. Prints the exact input/output tensor shapes.

Usage
-----
python scripts/export_beat_this.py --checkpoint final0 --precision fp16 \
    --out app/src/main/assets/models/beat_this.onnx
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import traceback
import warnings
from pathlib import Path

import numpy as np
import torch

warnings.filterwarnings("ignore")

# --------------------------------------------------------------------------
# beat_this frontend constants (documented in the beat_this repo)
# --------------------------------------------------------------------------
SAMPLE_RATE = 22050
N_FFT = 1024
HOP_LENGTH = 441          # 22050 / 441 = 50 frames per second
N_MELS = 128
F_MIN = 30.0
F_MAX = 11000.0
FPS = SAMPLE_RATE / HOP_LENGTH
CHUNK_FRAMES = 1500       # beat_this infers in 30 s chunks (1500 frames @ 50 fps)

INPUT_NAME = "spect"
OUTPUT_NAMES = ["beat_logits", "downbeat_logits"]


def log(msg: str) -> None:
    print(f"[beat_this] {msg}", flush=True)


def banner(title: str) -> None:
    print("\n" + "=" * 72, flush=True)
    print(title, flush=True)
    print("=" * 72, flush=True)


# --------------------------------------------------------------------------
# Model loading
# --------------------------------------------------------------------------
class ExportWrapper(torch.nn.Module):
    """beat_this returns a dict {'beat': (B,T), 'downbeat': (B,T)}; ONNX wants a tuple."""

    def __init__(self, model: torch.nn.Module):
        super().__init__()
        self.model = model

    def forward(self, spect: torch.Tensor):
        out = self.model(spect)
        if isinstance(out, dict):
            return out["beat"], out["downbeat"]
        return out[0], out[1]


def load_beat_this(checkpoint: str) -> torch.nn.Module:
    try:
        from beat_this.inference import load_model

        model = load_model(checkpoint, "cpu")
    except Exception as exc:  # fallback path if the helper's signature differs
        log(f"load_model() failed ({exc!r}); falling back to File2Beats(...).model")
        from beat_this.inference import File2Beats

        model = File2Beats(checkpoint_path=checkpoint, device="cpu", dbn=False).model
    model.eval()
    for p in model.parameters():
        p.requires_grad_(False)
    n_params = sum(p.numel() for p in model.parameters())
    log(f"loaded checkpoint '{checkpoint}': {n_params / 1e6:.2f}M parameters "
        f"(~{n_params * 4 / 1e6:.0f} MB fp32, ~{n_params * 2 / 1e6:.0f} MB fp16)")
    return model


# --------------------------------------------------------------------------
# ONNX export strategies
# --------------------------------------------------------------------------
def export_torchscript(wrapper, dummy, path: Path, opset: int, dyn_batch: bool, dyn_time: bool):
    axes = None
    if dyn_batch or dyn_time:
        axes = {INPUT_NAME: {}, OUTPUT_NAMES[0]: {}, OUTPUT_NAMES[1]: {}}
        for name in axes:
            if dyn_batch:
                axes[name][0] = "batch"
            if dyn_time:
                axes[name][1] = "time"
    torch.onnx.export(
        wrapper,
        (dummy,),
        str(path),
        input_names=[INPUT_NAME],
        output_names=OUTPUT_NAMES,
        dynamic_axes=axes,
        opset_version=opset,
        do_constant_folding=True,
        dynamo=False,
    )


def export_dynamo(wrapper, dummy, path: Path, opset: int, dyn_batch: bool, dyn_time: bool):
    program = torch.onnx.export(wrapper, (dummy,), dynamo=True)
    program.save(str(path))


def run_ort(path: Path, x: np.ndarray):
    import onnxruntime as ort

    so = ort.SessionOptions()
    so.log_severity_level = 3
    sess = ort.InferenceSession(str(path), so, providers=["CPUExecutionProvider"])
    in_name = sess.get_inputs()[0].name
    outs = sess.run(None, {in_name: x})
    return sess, outs


def max_diff(outs, refs) -> float:
    return max(float(np.max(np.abs(o - r))) for o, r in zip(outs, refs))


def export_and_validate(wrapper, dummy, ref_outs, tmp_path: Path, opset: int,
                        dynamic_time: bool, atol: float = 2e-2) -> str:
    """Try strategies in order; return the name of the first that exports AND matches PyTorch."""
    strategies = []
    if dynamic_time:
        strategies.append(("torchscript, dynamic batch+time", export_torchscript, True, True))
    strategies += [
        # static first: required for NNAPI / mobile GPU execution
        ("torchscript, static shape", export_torchscript, False, False),
        ("torchscript, dynamic batch", export_torchscript, True, False),
        ("dynamo, static shape", export_dynamo, False, False),
    ]
    x = dummy.numpy()
    for name, fn, dyn_b, dyn_t in strategies:
        log(f"export attempt: {name} (opset {opset})")
        try:
            if tmp_path.exists():
                tmp_path.unlink()
            fn(wrapper, dummy, tmp_path, opset, dyn_b, dyn_t)
            _, outs = run_ort(tmp_path, x)
            diff = max_diff(outs, ref_outs)
            log(f"  onnxruntime vs PyTorch max |diff| = {diff:.2e} (tolerance {atol:.0e})")
            if diff > atol:
                log("  numerical mismatch, trying next strategy")
                continue
            if dyn_t:
                x2 = dummy[:, : CHUNK_FRAMES // 2].numpy()
                _, outs2 = run_ort(tmp_path, x2)
                log(f"  dynamic-time check OK: T={x2.shape[1]} -> {outs2[0].shape}")
            return name
        except Exception as exc:
            log(f"  failed: {type(exc).__name__}: {str(exc)[:300]}")
    raise RuntimeError("all ONNX export strategies failed (see log above)")


# --------------------------------------------------------------------------
# Precision conversion
# --------------------------------------------------------------------------
def convert_precision(src: Path, dst: Path, precision: str) -> None:
    import onnx

    if precision == "fp32":
        shutil.copyfile(src, dst)
    elif precision == "fp16":
        from onnxconverter_common import float16

        model = onnx.load(str(src))
        model16 = float16.convert_float_to_float16(model, keep_io_types=True)
        onnx.save(model16, str(dst))
    elif precision == "int8":
        from onnxruntime.quantization import QuantType, quantize_dynamic

        quantize_dynamic(
            str(src), str(dst),
            weight_type=QuantType.QInt8,
            op_types_to_quantize=["MatMul", "Gemm"],
        )
    else:
        raise ValueError(precision)


def tensor_shape(value_info) -> list:
    dims = []
    for d in value_info.type.tensor_type.shape.dim:
        if d.dim_param:
            dims.append(d.dim_param)
        elif d.HasField("dim_value"):
            dims.append(d.dim_value)
        else:
            dims.append("?")
    return dims


def tensor_dtype(value_info) -> str:
    import onnx

    return onnx.TensorProto.DataType.Name(value_info.type.tensor_type.elem_type)


# --------------------------------------------------------------------------
# Frontend (log-mel) info + golden vectors for Batch 2 Kotlin parity tests
# --------------------------------------------------------------------------
def build_logmel_module():
    from beat_this.preprocessing import LogMelSpect

    try:
        return LogMelSpect(device="cpu")
    except TypeError:
        return LogMelSpect()


def dump_frontend(out_dir: Path, golden_dir: Path) -> dict:
    info = {
        "sample_rate": SAMPLE_RATE,
        "channels": 1,
        "n_fft": N_FFT,
        "win_length": N_FFT,
        "hop_length": HOP_LENGTH,
        "window": "hann (periodic, torch default)",
        "center": True,
        "pad_mode": "reflect",
        "f_min": F_MIN,
        "f_max": F_MAX,
        "n_mels": N_MELS,
        "mel_scale": "slaney",
        "power": 1.0,
        "normalized": "frame_length",
        "log": "log1p(1000 * mel)",
        "fps": FPS,
        "output_layout": "[time_frames, n_mels]",
        "source": "documented defaults (could not introspect LogMelSpect)",
        "filterbank_file": None,
    }
    try:
        spect = build_logmel_module()
        log(f"LogMelSpect repr: {spect}")
        mel = getattr(spect, "spect_class", None)
        fb = None
        if mel is not None:
            fb = mel.mel_scale.fb.detach().cpu().numpy().astype("<f4")
        if fb is None:
            import torchaudio

            fb = torchaudio.functional.melscale_fbanks(
                n_freqs=N_FFT // 2 + 1, f_min=F_MIN, f_max=F_MAX, n_mels=N_MELS,
                sample_rate=SAMPLE_RATE, norm="slaney", mel_scale="slaney",
            ).numpy().astype("<f4")
            info["source"] = "torchaudio.melscale_fbanks fallback (verify against golden vectors)"
        else:
            info["source"] = "introspected from beat_this LogMelSpect"
        fb_path = out_dir / "mel_filterbank_f32.bin"
        fb.tofile(fb_path)
        info["filterbank_file"] = fb_path.name
        info["filterbank_shape"] = list(fb.shape)  # [n_freqs, n_mels]
        info["filterbank_layout"] = "row-major float32 little-endian [n_freqs=513, n_mels=128]"
        log(f"mel filterbank {fb.shape} -> {fb_path}")

        # ---- golden audio -> log-mel (frontend parity test) ----
        golden_dir.mkdir(parents=True, exist_ok=True)
        rng = np.random.default_rng(1234)
        t = np.arange(int(SAMPLE_RATE * 6.0)) / SAMPLE_RATE
        wave = (0.3 * np.sin(2 * np.pi * 220 * t) + 0.2 * np.sin(2 * np.pi * 1760 * t)
                + 0.02 * rng.standard_normal(t.shape)).astype(np.float32)
        clicks = np.zeros_like(wave)
        for k in range(12):  # 120 BPM clicks
            i = int(k * 0.5 * SAMPLE_RATE)
            clicks[i: i + 200] = np.hanning(200).astype(np.float32) * 0.8
        wave = (wave + clicks).astype(np.float32)
        with torch.no_grad():
            lm = spect(torch.from_numpy(wave))
        lm = lm.detach().cpu().numpy()
        if lm.shape[-1] != N_MELS and lm.shape[0] == N_MELS:
            lm = lm.T
        wave.astype("<f4").tofile(golden_dir / "golden_audio_f32.bin")
        lm.astype("<f4").tofile(golden_dir / "golden_logmel_f32.bin")
        info["golden_logmel_shape"] = list(lm.shape)
        log(f"golden audio {wave.shape} @ {SAMPLE_RATE} Hz -> log-mel {lm.shape}")
    except Exception as exc:
        log(f"WARNING: frontend introspection/golden export failed: {exc!r}")
        traceback.print_exc()
    return info


# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------
def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--checkpoint", default="final0", help="final0 | small0 | final1 | final2 ...")
    ap.add_argument("--precision", default="fp16", choices=["fp16", "fp32", "int8"])
    ap.add_argument("--out", default="app/src/main/assets/models/beat_this.onnx")
    ap.add_argument("--golden-dir", default="build/golden")
    ap.add_argument("--frames", type=int, default=CHUNK_FRAMES, help="time frames in the export chunk")
    ap.add_argument("--opset", type=int, default=17)
    ap.add_argument("--dynamic-time", action="store_true",
                    help="experimental: try a dynamic time axis (default: fixed 1500-frame chunk). "
                         "NOT GPU/NNAPI compatible.")
    args = ap.parse_args()

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    golden_dir = Path(args.golden_dir)
    tmp_dir = Path("build/export_tmp")
    tmp_dir.mkdir(parents=True, exist_ok=True)
    fp32_path = tmp_dir / "beat_this_fp32.onnx"

    banner(f"beat_this -> ONNX | checkpoint={args.checkpoint} precision={args.precision}")
    log(f"torch {torch.__version__}")

    model = load_beat_this(args.checkpoint)
    wrapper = ExportWrapper(model).eval()

    torch.manual_seed(0)
    dummy = torch.rand(1, args.frames, N_MELS) * 6.0  # log1p(1000*mel) lives roughly in [0, 8]
    with torch.no_grad():
        ref = wrapper(dummy)
    ref_np = [r.numpy() for r in ref]
    log(f"PyTorch reference output shapes: beat={tuple(ref[0].shape)} downbeat={tuple(ref[1].shape)}")

    banner("Exporting ONNX (fp32) and validating against PyTorch")
    strategy = export_and_validate(wrapper, dummy, ref_np, fp32_path, args.opset, args.dynamic_time)
    log(f"export strategy used: {strategy}")
    fp32_mb = fp32_path.stat().st_size / 1e6
    log(f"fp32 model size: {fp32_mb:.1f} MB")

    banner(f"Converting to {args.precision}")
    convert_precision(fp32_path, out, args.precision)
    final_mb = out.stat().st_size / 1e6
    log(f"final model size: {final_mb:.1f} MB -> {out}")
    if final_mb < 0.5:
        log("ERROR: output model is suspiciously small")
        return 1

    # Validate the final model (fatal for fp32, a warning for fp16/int8, because
    # desktop onnxruntime may lack FP16 kernels that a phone CPU/GPU has).
    final_diff = None
    try:
        _, outs = run_ort(out, dummy.numpy())
        final_diff = max_diff(outs, ref_np)
        log(f"final model vs PyTorch max |diff| = {final_diff:.3e}")
    except Exception as exc:
        if args.precision == "fp32":
            log(f"ERROR: final fp32 model failed to run: {exc!r}")
            return 1
        log(f"WARNING: desktop onnxruntime could not run the {args.precision} model: "
            f"{str(exc)[:300]}")
        log("         This is expected if the CPU provider lacks fp16/int8 kernels. "
            "The Android smoke test on your phone is the real check.")

    # Final I/O spec, read from the model file itself
    import onnx

    m = onnx.load(str(out))
    inputs = [{"name": i.name, "dtype": tensor_dtype(i), "shape": tensor_shape(i)} for i in m.graph.input]
    outputs = [{"name": o.name, "dtype": tensor_dtype(o), "shape": tensor_shape(o)} for o in m.graph.output]

    frontend = dump_frontend(out.parent, golden_dir)

    # Golden vectors for the model itself (Batch 2 inference parity)
    try:
        golden_dir.mkdir(parents=True, exist_ok=True)
        dummy.numpy().astype("<f4").tofile(golden_dir / "golden_model_input_f32.bin")
        ref_np[0].astype("<f4").tofile(golden_dir / "golden_beat_logits_f32.bin")
        ref_np[1].astype("<f4").tofile(golden_dir / "golden_downbeat_logits_f32.bin")
    except Exception as exc:
        log(f"WARNING: could not write golden model vectors: {exc!r}")

    spec = {
        "model": {
            "checkpoint": args.checkpoint,
            "precision": args.precision,
            "file": out.name,
            "size_mb": round(final_mb, 2),
            "fp32_size_mb": round(fp32_mb, 2),
            "onnx_opset": args.opset,
            "export_strategy": strategy,
            "max_abs_diff_vs_pytorch": final_diff,
            "io_types_note": "inputs/outputs stay float32 even when weights are fp16",
        },
        "inputs": inputs,
        "outputs": outputs,
        "layout": {
            "input": "[batch, time_frames, n_mels=128]  (log-mel, time-major, NOT channels-first)",
            "outputs": "[batch, time_frames] raw logits (apply sigmoid for probabilities)",
            "fixed_chunk_frames": None if args.dynamic_time else args.frames,
        },
        "chunking": {
            "chunk_frames": CHUNK_FRAMES,
            "chunk_seconds": CHUNK_FRAMES / FPS,
            "note": "Model is exported for one 1500-frame (30 s) chunk. For longer songs, "
                    "split the log-mel into overlapping chunks, run each, and stitch the "
                    "central parts (beat_this's own 'keep_middle' strategy); pad the last chunk.",
        },
        "frontend": frontend,
        "postprocess_minimal": {
            "beat_time_seconds": "frame_index / 50.0",
            "rule": "logit > 0 (== sigmoid > 0.5) AND local max within +/-3 frames, then merge "
                    "adjacent peaks; snap each downbeat to the nearest beat. "
                    "(to be confirmed against beat_this source in Batch 2)",
        },
    }
    spec_path = out.parent / "beat_this_spec.json"
    spec_path.write_text(json.dumps(spec, indent=2))

    banner("EXACT TENSOR SPECS FOR BATCH 2")
    for i in inputs:
        print(f"INPUT   name={i['name']!r:<18} dtype={i['dtype']:<6} shape={i['shape']}")
    for o in outputs:
        print(f"OUTPUT  name={o['name']!r:<18} dtype={o['dtype']:<6} shape={o['shape']}")
    print(f"\nFRONTEND  sample_rate={SAMPLE_RATE} Hz  n_fft={N_FFT}  hop={HOP_LENGTH}  "
          f"n_mels={N_MELS}  f_min={F_MIN}  f_max={F_MAX}  fps={FPS:.1f}")
    print(f"CHUNK     {CHUNK_FRAMES} frames = {CHUNK_FRAMES / FPS:.0f} s per inference call")
    print(f"MODEL     {out}  ({final_mb:.1f} MB, {args.precision})")
    print(f"SPEC      {spec_path}")

    shutil.rmtree(tmp_dir, ignore_errors=True)
    banner("EXPORT OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
