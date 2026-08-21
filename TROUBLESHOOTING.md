# AudioManager — Troubleshooting Guide

## Dependency problems

### "FFmpeg is installed but is not visible to WhisperX"
This is an **advisory warning**, not a failure — it's a secondary check that has been known to report this immediately before transcription runs and succeeds anyway. It does **not** block processing. If transcription actually fails, that's a separate, specific error — see below.

### "FFmpeg not found" / Start Processing is disabled
FFmpeg (and FFprobe) are hard requirements — every file's duration is probed with FFprobe before any transcription decision is made. Install FFmpeg and ensure it's on your system PATH, then Help → Check Dependencies (`F5`) to re-verify.

## Models

### "Model 'X' is not installed locally, and this app does not download models automatically"
This app never downloads models on its own — you must install them manually first. This is intentional, to avoid silently consuming bandwidth/disk space.

**To install a model:**
1. On any machine with internet access, run:
   ```
   huggingface-cli download Systran/faster-whisper-<model-name>
   ```
   (e.g. `Systran/faster-whisper-small`, `Systran/faster-whisper-large-v3`)
2. This downloads to your HuggingFace cache (usually `~/.cache/huggingface/hub/`).
3. Copy the resulting `models--Systran--faster-whisper-<name>` folder to the same location on the machine running AudioManager.
4. The error message itself always shows the exact paths this installation of the app is searching, so you don't have to guess.

### Diarization needs a separate check
The main Whisper model, the alignment model, and the diarization model are all subject to the same no-auto-download policy. If diarization is enabled but its model isn't cached locally, it fails gracefully (transcription still completes — you just won't get speaker labels) rather than blocking the whole file. Check the log for a diarization-specific warning if speaker labels are missing.

## Performance

### Processing is much slower than it used to be
**Most common cause: speaker diarization.** If you've recently enabled it (or it started working after previously silently not running due to a bug that's since been fixed), expect roughly double the per-file processing time on CPU-only hardware — diarization is a full second ML pipeline pass on top of transcription. Check Tools → Performance Report: if the "Diarization" column shows real seconds, that's your answer. Disable it in Transcription Settings if speed matters more than speaker labels.

**Other things to check:**
- **Model size**: `large-v3` is dramatically slower than `small` or `base` on CPU. If you don't need maximum accuracy, a smaller model is the single biggest speed lever available.
- **GPU**: if you have an NVIDIA GPU with CUDA available, the app auto-detects and uses it — CPU-only transcription is inherently much slower. Check the log at startup for "CUDA available: True/False".
- **Concurrent system load**: the adaptive concurrency controller throttles down under memory pressure. If other heavy applications are running alongside a batch, expect the controller to reduce parallelism — this is working as intended, not a bug.

### The batch seems stuck / not finishing
Check Tools → Performance Report and the log for `RESOURCE_SAMPLE` lines (every 2 seconds during a batch). If memory is pinned near 100% and `activeFiles` isn't decreasing, the batch may be under severe memory pressure. As of this version, initial file admission is staggered specifically to prevent this scenario (all files starting simultaneously before memory pressure becomes visible) — if you still see this, consider lowering "max parallel files" in Batch Processing Settings.

### "Done" count in the Queue summary isn't updating
This was a real bug in earlier versions (the count only updated via a slow polling cycle that could miss the last file of a batch). If you're on a build where this still happens, it means you're running an older snapshot — this has been fixed to update immediately on every file completion.

## Queue / UI

### Undo (Ctrl+Z) isn't doing anything
Undo/redo only covers queue management (add/remove/reorder files) — not transcription progress or results. Make sure focus is on the queue table when pressing the shortcut (or use Edit → Undo from the menu, which works regardless of focus).

### Waveform preview shows "unavailable" or is blank
- Multiple files selected: preview only shows for a single selected file.
- File over 200MB: skipped deliberately (see User Manual).
- FFmpeg couldn't decode the file: usually means an unsupported or corrupt file — the same file will likely fail transcription too; check the file plays correctly in another application first.

### Dark mode doesn't fully re-theme everything
Known, documented limitation — some panels with heavily customized inline styling don't yet pick up the dark stylesheet. Cosmetic only; doesn't affect functionality.

## Output

### Transcript file wasn't created despite the file showing "Completed"
This was a documented historical bug class (a step reporting success while its output was actually missing/empty) that's since been addressed with integrity checks at each output-writing step. If you still see this, please report it with the file's entry from the log — it would be a regression worth flagging specifically.

### Word (.docx) copy is missing but SRT/TXT are fine
The .docx export is optional and best-effort — if it fails, the primary SRT/TXT output still succeeds and the failure is logged as a warning, not a batch-stopping error. Check the log for "Failed to save Word-compatible copy".

## Still stuck?

Check the log/Terminal panel first — nearly every failure mode logs a specific, actionable message rather than a generic error. If you're filing a bug report, include:
1. The exact error message from the log
2. Your model, language, and diarization settings
3. Whether the file is under or over 500MB (determines which processing path is used)
4. Output of Help → Check Dependencies
