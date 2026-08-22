<div align="center">

# 📖 AudioManager — User Manual

### Version 4.0.0 — Phoenix

[⬇ Download](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/) · [🐛 Report Issue](https://github.com/gregjava/Aburime-Sound-Manager/issues) · [📚 GitHub](https://github.com/gregjava/Aburime-Sound-Manager)

</div>

---

## Table of Contents

1. [What This App Does](#-what-this-app-does)
2. [Getting Started](#-getting-started)
3. [The Queue (Ready to Process)](#-the-queue-ready-to-process)
4. [Installing Models](#-installing-models)
5. [Speaker Diarization](#-speaker-diarization)
6. [Large Files](#-large-files)
7. [Performance Report](#-performance-report)
8. [Watch Folder](#-watch-folder)
9. [Dark Mode](#-dark-mode)
10. [Keyboard Shortcuts](#-keyboard-shortcuts)
11. [Output Formats](#-output-formats)
12. [Session Recovery](#-session-recovery)
13. [Troubleshooting](#-troubleshooting)
14. [FAQ](#-faq)

---

## 🎯 What This App Does

AudioManager is a **professional audio transcription and processing tool** that runs entirely **offline** on your computer. It uses **WhisperX** (a state-of-the-art speech recognition engine) to transcribe audio files with remarkable accuracy.

### Key Capabilities

| Feature | Description |
| :--- | :--- |
| **Batch Processing** | Transcribe hundreds of files in one go with adaptive concurrency. |
| **Speaker Diarization** | Identify *who* spoke when, with speaker labels. |
| **Word-Level Timestamps** | Precise timestamps for every word. |
| **Multiple Formats** | Output to SRT, TXT, and Word-compatible DOCX. |
| **100% Offline** | Your audio and transcripts **never leave your machine**. |

---

## 🚀 Getting Started

Follow these steps to start transcribing your audio files.

### Step 1: Install Dependencies

Before using AudioManager, you must have **FFmpeg** and **FFprobe** installed.

- **Download:** [https://ffmpeg.org/download.html](https://ffmpeg.org/download.html)
- **Windows:** Place `ffmpeg.exe` and `ffprobe.exe` in `C:\AI\ffmpeg\bin\` or add them to your system PATH.
- **macOS/Linux:** Install via your package manager (`brew install ffmpeg`, `sudo apt install ffmpeg`).

### Step 2: Install WhisperX

WhisperX is the transcription engine. Install it in a Python virtual environment:

```bash
# Create a virtual environment
python -m venv whisperx_env

# Activate it
# On Windows:
whisperx_env\Scripts\activate
# On macOS/Linux:
source whisperx_env/bin/activate

# Install WhisperX
pip install whisperx
```

### Step 3: Install Whisper Models

**Important:** AudioManager does **not** download models automatically. You must install them manually. See the [Installing Models](#-installing-models) section for detailed instructions.

### Step 4: Check Dependencies

Open AudioManager and go to `Help → Check Dependencies` (or press `F5`). The app will verify that all required components are installed and visible.

### Step 5: Add Files

- **Click "Browse"** to select audio files, or
- **Drag and drop** files or folders directly onto the queue table.

### Step 6: Configure Settings

- **Transcription:** `Tools → Transcription Settings` (model, language, diarization)
- **Audio Processing:** `Tools → Audio Processing Settings` (noise reduction, volume)
- **Batch Behavior:** `Tools → Batch Processing Settings` (parallel files)

### Step 7: Start Processing

Once your files are queued and configured, press the **"Start Processing"** button. The queue will update in real-time as each file is processed.

---

## 📋 The Queue (Ready to Process)

The queue is the heart of AudioManager. It shows every file you've added, its status, and its progress.

### Queue Summary

Above the queue table, you'll see a summary line:

```
📊 Queue: total | pending | processing | done | failed
```

This updates live — not just once a second, but immediately on every file completion.

### File Statuses

| Status | Icon | Description |
| :--- | :--- | :--- |
| **PENDING** | ⏳ | Waiting to be processed |
| **PROCESSING** | 🔄 | Currently being transcribed |
| **COMPLETED** | ✅ | Successfully processed |
| **FAILED** | ❌ | An error occurred; check the log |

### File Priority

Each file can be assigned a priority (High, Normal, Low). Files with higher priority are processed first. This is best-effort — files already running are not preempted.

**To set priority:** Right-click a file → `Set Priority` → choose High, Normal, or Low.

### Waveform Preview

Select a single file (not multiple) to see an amplitude preview below the file browser row.

> **Note:** Files over 200MB skip the preview to avoid excessive memory usage.

### Drag & Drop

- **Reorder:** Drag files up and down in the queue to change processing order.
- **Add files:** Drag audio files from your file explorer directly onto the queue table.

---

## 📦 Installing Models

AudioManager **never downloads models automatically**. This is a deliberate security and bandwidth decision. You must install Whisper models manually.

### Step 1: Install HuggingFace CLI

```bash
pip install huggingface_hub[cli]
```

### Step 2: Download a Model

```bash
huggingface-cli download Systran/faster-whisper-<model-name>
```

Replace `<model-name>` with one of:

| Model | Size | Use Case |
| :--- | :--- | :--- |
| `tiny` | ~75 MB | Fastest, least accurate |
| `base` | ~150 MB | Good balance for short files |
| `small` | ~500 MB | Better accuracy |
| `medium` | ~1.5 GB | High accuracy |
| `large-v3` | ~3 GB | Best accuracy, slowest |

### Step 3: Verify the Model

The model will be downloaded to:
- **Windows:** `%LOCALAPPDATA%\huggingface\hub\`
- **macOS/Linux:** `~/.cache/huggingface/hub/`

The folder will be named like: `models--Systran--faster-whisper-<model-name>`

### Step 4: Use in AudioManager

Once the model is downloaded, select it in `Tools → Transcription Settings → Model`. AudioManager will automatically find it in your HuggingFace cache.

---

## 🗣️ Speaker Diarization

Speaker diarization identifies *who* is speaking, not just *what* was said.

### Enabling Diarization

1. Go to `Tools → Transcription Settings`.
2. Check the **"Diarization"** checkbox.
3. Enter your **HuggingFace access token** (required for the pyannote model).

### Performance Impact

> **⚠️ Important:** Diarization is CPU-intensive. On non-GPU hardware, it can double processing time per file. If speed is more important than speaker labels, leave it disabled.

### Output

When diarization is enabled, transcripts will include speaker labels:

```
[SPEAKER_00] Hello, welcome to the meeting.
[SPEAKER_01] Thanks for having me.
```

A speaker analysis summary is also included at the end of the transcript.

---

## 📄 Large Files

Files over **500 MB** are automatically routed through **segmented processing**.

### How It Works

1. The audio file is split into segments (default: 30 seconds).
2. Each segment is transcribed independently (with retry on failure).
3. The results are merged with continuous timestamps.

### Benefits

- **Resumable:** If the app is interrupted, segmented processing resumes from where it left off.
- **Reliable:** A failure in one segment doesn't fail the whole file.
- **Memory Efficient:** Only one segment is processed at a time.

---

## 📊 Performance Report

`Tools → Performance Report` (or `Ctrl+Shift+P`) shows a detailed breakdown of processing time.

### What's Included

| Metric | Description |
| :--- | :--- |
| **Queue Wait** | Time spent waiting in the queue |
| **Model Acquisition** | Time waiting for a model instance |
| **Preprocessing** | Audio conversion and enhancement |
| **Model Load** | Time to load the Whisper model |
| **Transcription** | Core transcription time |
| **Alignment** | Word-level alignment time |
| **Diarization** | Speaker identification time |
| **Subtitle Generation** | Creating SRT/TXT files |
| **Output Saving** | Writing files to disk |
| **Total** | End-to-end time |

### Batch Summary

At the end of every batch, a summary is logged in the Terminal:
- Files processed
- Audio duration
- Elapsed time
- Throughput (minutes of audio per hour)
- CPU and RAM statistics
- Scaling events (how many times adaptive concurrency throttled)

---

## 📁 Watch Folder

`Tools → Watch Folder` lets you point the app at a directory.

### How It Works

- Any new audio file dropped into the watched folder is **automatically added** to the queue.
- Files are **not auto-started** — you still press "Start Processing" manually.

### Use Cases

- **Drop Zone:** Have a scanner or recorder save directly to the watched folder.
- **Automation:** Set up a script to drop files into the folder.

---

## 🌙 Dark Mode

`View → Dark Mode` (or `Ctrl+Shift+D`) switches to a dark theme.

> **Note:** The dark theme re-colors the main window, menus, and text areas. Some panels with heavily customized inline styling may keep their light colors — this is a known, cosmetic limitation.

---

## ⌨️ Keyboard Shortcuts

### Global Shortcuts

| Shortcut | Action |
| :--- | :--- |
| `Ctrl+R` | Start/Stop Processing |
| `Ctrl+O` | Browse for audio files |
| `Ctrl+Shift+O` | Select output directory |
| `Ctrl+Shift+D` | Toggle Dark Mode |
| `F5` | Check Dependencies |
| `F1` | Open Troubleshooting Guide |

### Queue Table Shortcuts

| Shortcut | Action |
| :--- | :--- |
| `Delete` | Remove selected file(s) |
| `Ctrl+A` | Select all files |
| `Ctrl+Z` | Undo (add/remove/reorder) |
| `Ctrl+Shift+Z` or `Ctrl+Y` | Redo |

> **Note:** Undo/redo only covers queue management (adding, removing, reordering files), not transcription itself.

---

## 📤 Output Formats

### SRT (SubRip)

The default output format. Includes timestamps for every subtitle block.

```
1
00:00:00,000 --> 00:00:02,000
Hello, welcome to the meeting.
```

### TXT (Plain Text)

A simple text file with the full transcript. No timestamps.

### DOCX (Word-Compatible)

A Microsoft Word-compatible document with formatted text and timestamps.

**To enable:** Check `Also Save Word-Compatible (.docx) Copy` in Transcription Settings.

---

## 💾 Session Recovery

If the app is closed or crashes mid-batch:

1. **On next launch**, AudioManager will detect the interrupted session.
2. A dialog will appear: *"Do you want to resume processing from where you left off?"*
3. **If you choose "Yes"**, processing resumes from the last completed file.
4. **If you choose "No"**, the session state is cleared, and you start fresh.

### What's Recovered

- Completed files are marked as `COMPLETED`.
- Files that were in progress are reset to `PENDING`.
- The queue order is preserved.

---

## 🔧 Troubleshooting

For detailed troubleshooting, see the **[Troubleshooting Guide](TROUBLESHOOTING.md)** (`Help → Troubleshooting Guide` or `F1`).

### Common Issues

| Issue | Quick Fix |
| :--- | :--- |
| **"FFmpeg not found"** | Install FFmpeg and add it to your system PATH. |
| **"Model not installed"** | Install the Whisper model manually. See [Installing Models](#-installing-models). |
| **Processing is very slow** | Try a smaller model, disable diarization, or reduce parallel files. |
| **Diarization not working** | Ensure you have a HuggingFace token and the model is installed. |
| **"A bound value cannot be set"** | This is a UI error. Restart the app. |

---

## ❓ FAQ

### Is my audio sent to the cloud?

**No.** AudioManager runs entirely offline. Your audio and transcripts **never leave your machine**.

### How do I get a HuggingFace token?

1. Go to [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens).
2. Create a new token with `read` access.
3. Accept the terms for the pyannote model.

### Why doesn't the app download models automatically?

This is a deliberate decision to avoid silently consuming bandwidth and disk space. Models range from 75 MB to 3 GB. You choose what to install.

### Can I use this on macOS or Linux?

The app is primarily built for Windows with native installers. macOS and Linux are not currently supported, but the source code is available for advanced users to compile.

### How do I update the app?

Download the latest installer from the [download page](#-download) and run it. Your settings and data are preserved.

### What audio formats are supported?

MP3, WAV, FLAC, OGG, M4A, WMA, AAC, OPUS, ALAC, AIFF, AMR, AC3, and more.

### What models are available?

`tiny`, `base`, `small`, `medium`, `large-v3` — each offering a trade-off between speed and accuracy.

---

<div align="center">

**Made with ❤️ by Greg Java**

[🌐 Website](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/) · [🐙 GitHub](https://github.com/gregjava/Aburime-Sound-Manager) · [🐛 Issues](https://github.com/gregjava/Aburime-Sound-Manager/issues)

</div>
