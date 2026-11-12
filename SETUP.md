# Kiongozi, Batch 1 setup

## 1. Put the files in a GitHub repo
Unzip, then from inside the `kiongozi` folder:

    git init
    git add -A
    git commit -m "Batch 1: skeleton + CI + beat_this export"
    git branch -M main
    git remote add origin https://github.com/<you>/kiongozi.git
    git push -u origin main

(If you upload through the GitHub website instead, make sure the hidden `.github/workflows/build.yml`
and `.gitignore` get uploaded too. Many phone file managers hide dot-folders.)

## 2. Run the workflow
GitHub -> Actions -> "Build Kiongozi" (it also starts automatically on push to main).
First run takes roughly 8-15 min (torch install + export + Gradle). Later runs with a cache hit are much faster.

## 3. What to check in the Actions log
Step "Export beat_this to ONNX":
  - `loaded checkpoint 'final0': ...M parameters`
  - `onnxruntime vs PyTorch max |diff| = ...` (should be small, e.g. below 1e-3, and under the 2e-2 tolerance)
  - `final model size: ... MB`  (about 40 MB expected for final0 fp16)
  - the block "EXACT TENSOR SPECS FOR BATCH 2" with INPUT/OUTPUT lines
  - the last banner "EXPORT OK"
A WARNING about desktop onnxruntime not running the fp16 model is normal; the phone test is the real check.

Step "Verify model files exist": lists beat_this.onnx, beat_this_spec.json, mel_filterbank_f32.bin.
The run Summary page also shows beat_this_spec.json.
Step "Rename APK and verify it contains the model": must list assets/models/beat_this.onnx.
Re-run once: "Cache hit: true" and the Python steps are skipped.

## 4. Install and check on the phone
Download artifact "Kiongozi-debug" (unzip to get Kiongozi-debug.apk) and install it.
The title screen's NEURAL ENGINE card shows CPU and NNAPI (GPU) timings for one 30 s chunk.
Send me: that card's text, plus the "EXACT TENSOR SPECS" block from the log. Then we start Batch 2.

## If something fails
- FP16 session fails on CPU or NNAPI on the phone: Actions -> Run workflow -> precision = fp32
  (or checkpoint = small0).
- Export step fails: paste me the "export attempt" lines from the log.
