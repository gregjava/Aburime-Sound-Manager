<div align="center">

# 📖 AudioManager — User Manual

### Version 4.0.0 — Phoenix

[⬇ Download](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/) · [🐛 Report Issue](https://github.com/gregjava/Aburime-Sound-Manager/issues) · [📚 GitHub](https://github.com/gregjava/Aburime-Sound-Manager)

</div>

---

## Table of Contents

1. [What This App Does](#-what-this-app-does)
2. [System Requirements](#-system-requirements)
3. [Getting Started](#-getting-started)
4. [The Queue (Ready to Process)](#-the-queue-ready-to-process)
5. [Installing Models](#-installing-models)
6. [Speaker Diarization](#-speaker-diarization)
7. [Large Files & Streaming](#-large-files--streaming)
8. [Performance Report](#-performance-report)
9. [Watch Folder](#-watch-folder)
10. [Dark Mode](#-dark-mode)
11. [Keyboard Shortcuts](#-keyboard-shortcuts)
12. [Output Formats](#-output-formats)
13. [Session Recovery](#-session-recovery)
14. [Troubleshooting](#-troubleshooting)
15. [FAQ](#-faq)

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
| **GPU Acceleration** | Up to 3x faster transcription with NVIDIA GPUs. |
| **Streaming Processing** | Automatic chunking for files >100 MB. |

---

## 📋 System Requirements

### Hardware Requirements

| Requirement | Minimum | Recommended |
| :--- | :--- | :--- |
| **Processor** | Intel Core i3 or equivalent | Intel Core i5/i7 or AMD Ryzen 5/7 |
| **RAM** | 4 GB | 8 GB or more |
| **Storage** | 500 MB (app) + 5 GB (models) | 1 GB (app) + 20 GB (models) |
| **GPU** | None (CPU mode) | NVIDIA GPU with 4+ GB VRAM |

### Software Requirements

| Requirement | Details |
| :--- | :--- |
| **Operating System** | Windows 10 or later (64-bit) — macOS and Linux support coming soon |
| **Java** | Bundled with the installer (no separate installation required) |
| **Python** | **3.10, 3.11, or 3.12 ONLY** (3.13+ NOT supported) |
| **FFmpeg** | Required for audio processing (see installation below) |
| **FFprobe** | Required for audio duration probing (included with FFmpeg) |

> ## ⚠️ IMPORTANT: Python Version Compatibility
>
> **WhisperX does NOT work with Python 3.13 or newer.**
>
> If you have Python 3.13 or higher installed, you MUST install Python 3.10, 3.11, or 3.12 in a separate location and use it for the WhisperX environment.
>
> **Check your Python version:**
> ```bash
> python --version
> ```
>
> If you see `Python 3.13.x` or higher, please download Python 3.12 from [python.org](https://www.python.org/downloads/) before proceeding.

---

## 🚀 Getting Started

Follow these steps to start transcribing your audio files.

### Step 1: Install Python (if not already installed)

> **⚠️ IMPORTANT:** Python 3.10, 3.11, or 3.12 is REQUIRED. Python 3.13+ is NOT supported.

**Check if Python is installed:**
```bash
python --version
```

**If Python is not installed or you have Python 3.13+:**

1. Download Python 3.12 from [python.org](https://www.python.org/downloads/)
2. Run the installer
3. **IMPORTANT:** Check **"Add Python to PATH"** during installation
4. Verify installation:
   ```bash
   python --version
   # Should show: Python 3.12.x
   ```

**If you have multiple Python versions:**

Use the full path to Python 3.12 for the commands below:
```bash
# Example: Using Python 3.12 installed at C:\Python312\
C:\Python312\python.exe --version
```

### Step 2: Install FFmpeg and FFprobe (Required)

Before using AudioManager, you must have **FFmpeg** and **FFprobe** installed.

- **Download:** [https://ffmpeg.org/download.html](https://ffmpeg.org/download.html)
- **Windows:** Place `ffmpeg.exe` and `ffprobe.exe` in `C:\AI\ffmpeg\bin\` or add them to your system PATH.
- **macOS/Linux:** Install via your package manager (`brew install ffmpeg`, `sudo apt install ffmpeg`).

**Verify FFmpeg installation:**
```bash
ffmpeg -version
ffprobe -version
```

### Step 3: Install WhisperX

WhisperX is the transcription engine. Install it in a Python virtual environment:

```bash
# Create a virtual environment with Python 3.12
python -m venv whisperx_env

# Activate it
# On Windows:
whisperx_env\Scripts\activate
# On macOS/Linux:
source whisperx_env/bin/activate

# Install WhisperX
pip install whisperx
```

**If you get an error, try:**
```bash
# Upgrade pip first
pip install --upgrade pip

# Then install WhisperX
pip install whisperx
```

> **💡 Tip:** If you have multiple Python versions and the `python` command uses the wrong version, use the full path:
> ```bash
> C:\Python312\python.exe -m venv whisperx_env
> ```

### Step 4: Install TorchCodec (Recommended for Windows)

TorchCodec improves audio loading performance on Windows:

```bash
# Activate your environment first, then:
pip install torchcodec==0.7.0
```

> **⚠️ Note:** If you see DLL errors after installing TorchCodec, you may need to copy FFmpeg DLLs to the TorchCodec folder. See the [Troubleshooting Guide](TROUBLESHOOTING.md) for details.

### Step 5: Install Whisper Models

**Important:** AudioManager does **not** download models automatically. You must install them manually. See the [Installing Models](#-installing-models) section for detailed instructions.

### Step 6: Run the Setup Wizard

Open AudioManager and the Setup Wizard will automatically launch on first run. It will:

1. **Check dependencies** (FFmpeg, Python, WhisperX, TorchCodec)
2. **Detect your Python version** and warn if incompatible
3. **Guide you through model selection**
4. **Verify model installation**

You can also run the Setup Wizard anytime from `Help → Run Setup Wizard` or by pressing `Ctrl+Shift+S`.

### Step 7: Check Dependencies Manually

Open AudioManager and go to `Help → Check Dependencies` (or press `F5`). The app will verify that all required components are installed and visible.

### Step 8: Add Files

- **Click "Browse"** to select audio files, or
- **Drag and drop** files or folders directly onto the queue table.

### Step 9: Configure Settings

- **Transcription:** `Tools → Transcription Settings` (model, language, diarization)
- **Audio Processing:** `Tools → Audio Processing Settings` (noise reduction, volume)
- **Batch Behavior:** `Tools → Batch Processing Settings` (parallel files)

### Step 10: Start Processing

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

### Undo/Redo

- `Ctrl+Z` — Undo the last queue action (add, remove, reorder, rename, priority change).
- `Ctrl+Shift+Z` or `Ctrl+Y` — Redo a previously undone action.

> **Note:** Undo/redo only covers queue management, not transcription progress or results.

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

| Model | Size | Speed | Accuracy | Use Case |
| :--- | :--- | :--- | :--- | :--- |
| `tiny` | ~75 MB | ⚡ Very Fast | 🟡 Basic | Quick drafts, low-resource |
| `base` | ~150 MB | ⚡ Fast | 🟢 Good | General purpose, balanced |
| `small` | ~500 MB | 🟢 Fast | 🔵 Better | Higher accuracy, moderate |
| `medium` | ~1.5 GB | 🟡 Moderate | 🟣 High | Professional transcripts |
| `large-v3` | ~3 GB | 🔴 Slow | ⭐ Best | Maximum accuracy |

### Step 3: Verify the Model

The model will be downloaded to:
- **Windows:** `%LOCALAPPDATA%\huggingface\hub\`
- **macOS/Linux:** `~/.cache/huggingface/hub/`

The folder will be named like: `models--Systran--faster-whisper-<model-name>`

### Step 4: Use in AudioManager

Once the model is downloaded, select it in `Tools → Transcription Settings → Model`. AudioManager will automatically find it in your HuggingFace cache.

### Alignment Model (for Diarization)

The alignment model is downloaded automatically when diarization is first used. This is the **only** model AudioManager downloads automatically.

---

## 🗣️ Speaker Diarization

Speaker diarization identifies *who* is speaking, not just *what* was said.

### Enabling Diarization

1. Go to `Tools → Transcription Settings`.
2. Check the **"Diarization"** checkbox.
3. Enter your **HuggingFace access token** (required for the pyannote model).

### Getting a HuggingFace Token

1. Go to [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens).
2. Create a new token with `read` access.
3. Accept the terms for the pyannote model.

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

## 📄 Large Files & Streaming

Files over **100 MB** are automatically processed using **streaming/chunking** to reduce memory usage.

### How Streaming Works

1. **Audio Splitting:** The audio is split into 30-second chunks using FFmpeg.
2. **Parallel Processing:** Chunks are processed in parallel (limited to 2-4 concurrent chunks).
3. **Result Merging:** Individual chunk transcripts are merged into a single, seamless transcript with continuous timestamps.

### Benefits

- **Memory Efficient:** Only one chunk is loaded at a time, preventing out-of-memory errors.
- **Parallel Processing:** Multiple chunks process simultaneously, improving throughput.
- **Reliable:** A failure in one chunk doesn't fail the whole file — other chunks continue.
- **Transparent:** You don't need to do anything — it's automatic!

### Chunk Size

The default chunk size is **30 seconds**. This provides a good balance between:
- **Memory usage:** Smaller chunks use less memory.
- **Accuracy:** Larger chunks give WhisperX more context for accurate transcription.
- **Throughput:** More chunks allow more parallel processing.

> **Note:** For files over 1 hour, the chunk size automatically adjusts to keep the number of chunks manageable (max 50 chunks).

### Disabling Streaming (Advanced)

Streaming is **enabled by default** for optimal performance. To disable it:

1. Edit the `TranscriptionConfig` in the code (advanced users only).
2. Set `streamingEnabled(false)` in the builder.

> **⚠️ Warning:** Disabling streaming may cause out-of-memory errors on very large files (>500 MB).

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

### Resource Usage

The Performance Report also shows:
- **Peak Memory (MB):** Maximum memory used during transcription
- **Avg CPU %:** Average CPU utilization during transcription
- **GPU Used:** Whether GPU acceleration was used

### Batch Summary

At the end of every batch, a summary is logged in the Terminal:

```
📊 Batch Performance Summary:
  • Files: 25/25
  • Duration: 15m 32s
  • Throughput: 1.61 files/sec
  • Data rate: 0.85 MB/sec
```

### Using the Report to Diagnose Issues

| Slow Stage | Likely Cause | Solution |
| :--- | :--- | :--- |
| **Transcription** | Model too large for hardware | Use smaller model |
| **Diarization** | CPU-intensive pass | Disable diarization if not needed |
| **Preprocessing** | Large file, slow disk | Use faster storage |
| **Model Acquisition** | Too many parallel files | Reduce parallel files |

---

## 📁 Watch Folder

`Tools → Watch Folder` lets you point the app at a directory.

### How It Works

- Any new audio file dropped into the watched folder is **automatically added** to the queue.
- Files are **not auto-started** — you still press "Start Processing" manually.

### Use Cases

- **Drop Zone:** Have a scanner or recorder save directly to the watched folder.
- **Automation:** Set up a script to drop files into the folder.
- **Network Drive:** Watch a network share for incoming files.

### Stopping the Watch

- Go to `Tools → Stop Watching Folder` to stop monitoring.

---

## 🌙 Dark Mode

`View → Dark Mode` (or `Ctrl+Shift+D`) switches to a dark theme.

### What's Themed

- ✅ Main window background
- ✅ Menu bar
- ✅ Tables and list views
- ✅ Buttons and controls
- ✅ Text areas and input fields
- ✅ Scroll bars
- ✅ Dialogs

### Limitations

> **Note:** Some panels with heavily customized inline styling may keep their light colors. This is a known cosmetic limitation that does not affect functionality.

---

## ⌨️ Keyboard Shortcuts

### Global Shortcuts

| Shortcut | Action |
| :--- | :--- |
| `Ctrl+Shift+D` | Toggle Dark Mode |
| `F5` | Check Dependencies |
| `Ctrl+Shift+W` | Toggle Folder Watch |
| `Ctrl+Shift+P` | Performance Report |
| `Ctrl+Shift+S` | **Run Setup Wizard** (NEW) |
| `Ctrl+B` | Batch Settings |
| `Ctrl+Q` | Exit Application |
| `Ctrl+Comma` | Preferences |
| `Ctrl+Shift+C` | Clear Session Data |
| `Ctrl+Z` | Undo |
| `Ctrl+Y` / `Ctrl+Shift+Z` | Redo |
| `F1` | Troubleshooting Guide |

### Queue Table Shortcuts

| Shortcut | Action |
| :--- | :--- |
| `Delete` | Remove selected file(s) |
| `Ctrl+A` | Select all files |
| `Ctrl+Z` | Undo (add/remove/reorder) |
| `Ctrl+Shift+Z` or `Ctrl+Y` | Redo |

---

## 📤 Output Formats

### SRT (SubRip)

The default output format. Includes timestamps for every subtitle block.

```
1
00:00:00,000 --> 00:00:02,000
Hello, welcome to the meeting.

2
00:00:02,000 --> 00:00:05,000
Thanks for having me.
```

### TXT (Plain Text)

A simple text file with the full transcript. No timestamps.

```
Hello, welcome to the meeting. Thanks for having me.
```

### DOCX (Word-Compatible)

A Microsoft Word-compatible document with formatted text and timestamps.

**Features:**
- Paragraph formatting
- Timestamps as headings
- Speaker labels in bold
- Confidence scores (if enabled)

**To enable:** Check `Also Save Word-Compatible (.docx) Copy` in Transcription Settings.

### HTML (PDF-Compatible)

A clean HTML file that can be printed to PDF from any browser.

**To enable:** Check `Also Save PDF-Compatible (.html) Copy` in Transcription Settings.

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
- Learned time estimation data is preserved.

### Clear Session Data

`File → Clear Session Data` (or `Ctrl+Shift+C`) clears all session data:

- Batch queue
- Time estimation data
- Log messages
- Application state

> **⚠️ Warning:** This action cannot be undone!

---

## 🎵 Sound Recorder

The Sound Recorder panel lets you record audio directly into the application.

### Recording

1. Select your input device from the dropdown.
2. Click **"Record"** to start recording.
3. Choose to add to the batch queue or save standalone.
4. Click **"Stop"** when finished.

### Playback

- Click **"Play"** to listen to the last recording.
- Useful for verifying quality before processing.

### Output

Recordings are saved as WAV files in the configured output directory.

---

## 🧹 Session Management

### Clear Session Data

`File → Clear Session Data` (or `Ctrl+Shift+C`) clears:

| Item | Cleared? |
| :--- | :--- |
| Batch queue | ✅ Yes |
| Learned time estimation | ✅ Yes |
| Log messages | ✅ Yes |
| Application state | ✅ Yes |
| File selection fields | ✅ Yes |
| Waveform preview | ✅ Yes |

### Clear Time Estimation Data

`Tools → Clear Time Estimation Data` resets only the learned time estimation patterns.

Use this if:
- The estimator is giving inaccurate predictions
- You've upgraded hardware
- You want to start fresh with default estimates

---

## 🔧 Troubleshooting

For detailed troubleshooting, see the **[Troubleshooting Guide](TROUBLESHOOTING.md)** (`Help → Troubleshooting Guide` or `F1`).

### Common Issues

| Issue | Quick Fix |
| :--- | :--- |
| **"FFmpeg not found"** | Install FFmpeg and add it to your system PATH. |
| **"Python version not compatible"** | Use Python 3.10, 3.11, or 3.12. **Python 3.13+ is NOT supported.** |
| **"WhisperX not found"** | Install WhisperX: `pip install whisperx` |
| **"TorchCodec missing DLLs"** | Install torchcodec: `pip install torchcodec==0.7.0` |
| **"Model not installed"** | Install the Whisper model manually. See [Installing Models](#-installing-models). |
| **Processing is very slow** | Try a smaller model, disable diarization, or reduce parallel files. |
| **Diarization not working** | Ensure you have a HuggingFace token and the model is installed. |
| **"A bound value cannot be set"** | This is a UI error. Restart the app. |
| **Chunk processing failed** | Check disk space for temporary files. |
| **Out of memory** | Reduce parallel files or use a smaller model. |

### Log Files

Logs are written to:
- **Application Log:** `~/.audiomanager/logs/`
- **Terminal:** Visible in the application's terminal panel
- **Error Reports:** `~/.audiomanager/error_reports/` (if enabled)

---

## ❓ FAQ

### Is my audio sent to the cloud?

**No.** AudioManager runs entirely offline. Your audio and transcripts **never leave your machine**.

### What Python version do I need?

**Python 3.10, 3.11, or 3.12 ONLY.** Python 3.13+ is NOT supported.

### How do I check my Python version?

```bash
python --version
```

### How do I get a HuggingFace token?

1. Go to [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens).
2. Create a new token with `read` access.
3. Accept the terms for the pyannote model.
4. Set the `HF_TOKEN` environment variable or place the token in `~/.audiomanager/hf_token`.

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

### How much does the Pro version cost?

The Pro version is available as a one-time purchase. Features include batch processing, larger file sizes (750MB), and priority support.

### Can I use my own GPU?

Yes! If you have an NVIDIA GPU with CUDA support, AudioManager automatically detects and uses it for up to 3x faster transcription.

### What's the maximum file size?

- **Free version:** 100 MB
- **Pro version:** 750 MB

### Does the app support multiple languages?

Yes! 30+ languages are supported, including English, Spanish, French, German, Chinese, Japanese, Arabic, and more.

### How do I run the Setup Wizard again?

Go to `Help → Run Setup Wizard` or press `Ctrl+Shift+S`.

---

## 📞 Support

### Contact

- **Email:** support@audiomanager.app
- **Website:** [https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/)
- **GitHub Issues:** [https://github.com/gregjava/Aburime-Sound-Manager/issues](https://github.com/gregjava/Aburime-Sound-Manager/issues)

### When Reporting Issues

Please include:
1. The exact error message
2. Your model, language, and diarization settings
3. Whether the file is under or over 100 MB (determines which processing path is used)
4. Output of `Help → Check Dependencies`
5. System information (OS, RAM, CPU)
6. Your Python version: `python --version`

---

<div align="center">

**Made with ❤️ by Greg Java**

[🌐 Website](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/) · [🐙 GitHub](https://github.com/gregjava/Aburime-Sound-Manager) · [🐛 Issues](https://github.com/gregjava/Aburime-Sound-Manager/issues)

</div>
