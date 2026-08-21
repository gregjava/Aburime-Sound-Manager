# AudioManager — User Manual

## What this app does

AudioManager batch-transcribes audio files locally using WhisperX (Whisper + forced alignment + optional speaker diarization), entirely offline — no audio or transcripts ever leave your machine. It's built for processing many files unattended (a folder of lecture recordings, interviews, meeting notes) with adaptive concurrency that scales to your hardware.

## Getting started

1. **Check dependencies**: Help → Check Dependencies (or press F5). This confirms FFmpeg, FFprobe, and WhisperX are installed and visible. If anything's missing, the log area (bottom panel) shows exactly what to install and where.
2. **Add files**: use the file browser at the top of the window, or drag-and-drop files/folders directly onto the queue table.
3. **Configure transcription**: Tools → Transcription Settings — model size, language (or auto-detect), speaker diarization on/off.
4. **Configure audio processing**: Tools → Audio Processing Settings — noise reduction, volume normalization/boost.
5. **Configure batch behavior**: Tools → Batch Processing Settings — how many files process in parallel.
6. **Start**: once files are queued, press Start Processing.

## The queue (Ready to Process)

Each file shows its status (pending / processing / completed / failed) and progress. The summary line above the table (📊 Queue: total | pending | processing | done | failed) updates live as files complete — not just once a second, but immediately on every completion.

**Keyboard shortcuts in the queue table:**
- `Delete` — remove selected file(s)
- `Ctrl+A` — select all
- `Ctrl+Z` — undo (add/remove/reorder)
- `Ctrl+Shift+Z` or `Ctrl+Y` — redo

Undo/redo covers queue management (adding files, removing files, reordering) — not transcription itself, since there's no meaningful way to "undo" a completed transcription.

**Waveform preview**: select a single file (not multiple) to see an amplitude preview below the file browser row. Files over 200MB skip the preview (it would mean holding the whole decoded file in memory for a purely cosmetic feature).

**Priority**: files can be marked High/Normal/Low priority. Among files not yet started, higher-priority ones get the next available processing slot first. This is best-effort — files already running aren't preempted.

## Models

This app **never downloads models automatically**. You must install Whisper models manually before use — see "Installing models" in the Troubleshooting Guide. This is deliberate: automatic downloads can silently consume significant bandwidth and disk space (models range from ~75MB to ~3GB+) without your explicit action.

## Speaker diarization

If enabled, diarization identifies *who* is speaking, not just *what* was said. It requires a HuggingFace access token (for the pyannote diarization model) entered in Transcription Settings.

**Important**: diarization is CPU-intensive — on non-GPU hardware it commonly takes as long as transcription itself, roughly doubling total processing time per file. If per-file speed matters more than speaker labels, leave it disabled.

## Large files

Files over 500MB are automatically routed through segmented processing: split into chunks, each transcribed independently (with retry on failure), then merged with continuous timestamps. If the app is interrupted mid-file, segmented processing resumes from where it left off rather than starting over.

## Performance Report

Tools → Performance Report (or `Ctrl+Shift+P`) shows a stage-by-stage timing breakdown for every file processed this session: queue wait, model acquisition, preprocessing, model load, transcription, alignment, diarization, subtitle generation, output writing, total time, peak memory, and average CPU — all measured, not estimated. Use this to see exactly where time is going on a slow file.

The app also logs a batch-level summary at the end of every run (files processed, audio duration, elapsed time, throughput, CPU/RAM stats, and how many times the adaptive concurrency controller throttled up or down) — visible in the Terminal/log panel.

## Watch Folder

Tools → Watch Folder lets you point the app at a directory; any new file dropped into it gets automatically added to the queue. Useful for a "drop zone" workflow (e.g., a scanner or recorder that saves directly to a watched folder).

## Dark Mode

View → Dark Mode (`Ctrl+Shift+D`). Note: this re-themes the main window chrome, menus, and text areas; a few panels with heavily customized styling may keep their light-theme colors for now — full coverage is still in progress.

## Output

Transcripts are saved to your configured output directory (Tools → Batch Processing Settings) in SRT and/or TXT format, with an optional Word-compatible (.docx) copy. Diarization, when enabled, labels each segment with a speaker.

## Session recovery

If the app is closed or crashes mid-batch, it detects the interrupted session on next launch and offers to resume from where it left off — no need to reprocess already-completed files.

---

For problems, see the **Troubleshooting Guide** (Help → Troubleshooting Guide, or `F1`).
