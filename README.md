<div align="center">

# 🎵 AudioManager v4.0.0 — Phoenix

### Professional Audio Transcription & Processing

[![Version](https://img.shields.io/badge/version-4.0.0-blue.svg)](https://github.com/gregjava/Aburime-Sound-Manager/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://adoptium.net/)
[![Downloads](https://img.shields.io/badge/downloads-latest-brightgreen.svg)](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/)
[![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)](https://github.com/gregjava/Aburime-Sound-Manager/pulls)

**Transcribe audio files locally with WhisperX — offline, secure, and blazing fast.**

[**Download Now**](#-download) · [**User Manual**](USER_MANUAL.md) · [**Report Issue**](https://github.com/gregjava/Aburime-Sound-Manager/issues)

</div>

---

## ✨ Features

| Feature | Description |
| :--- | :--- |
| 🎙️ **Batch Processing** | Process hundreds of files with **adaptive concurrency** that scales to your hardware. |
| 📝 **WhisperX Transcription** | State-of-the-art speech recognition with **forced alignment** for word-level timestamps. |
| 👥 **Speaker Diarization** | Identify **who spoke when** with speaker labels and summaries. |
| ⏱️ **Time Estimation** | **Learns from past runs** to predict completion times for each file. |
| 🌐 **REST API** | Full REST API for **headless automation** and integration with other tools. |
| 📊 **Performance Reporting** | Detailed **stage-by-stage timing** reports for every file processed. |
| 🎨 **Dark Mode** | Full dark mode support for comfortable late-night work. |
| 🔒 **100% Offline** | Your audio and transcripts **never leave your machine**. |
| 🎵 **Sound Recorder** | Record audio directly into the app and add it to the queue. |
| 🏷️ **ID3 Tagging** | Generate sidecar `.meta` files with title, artist, and metadata. |
| 📁 **Watch Folder** | Automatically add files dropped into a watched folder. |
| 📋 **SRT / TXT / DOCX** | Export transcripts in multiple formats, including Word-compatible `.docx`. |

---

## Download

Download AudioManager v4.0.0:

| Platform | Download |
|----------|----------|
| **Windows EXE** | [⬇ Download](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/AburimeSoundManager-v4.0.0.exe) |
| **Windows MSI** | [⬇ Download](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/AburimeSoundManager-v4.0.0.msi) |
| **Source Code** | [GitHub](https://github.com/gregjava/Aburime-Sound-Manager) |

> **🔒 Security Note:** We distribute native Windows installers (`.exe`/`.msi`) instead of a `.jar` file to protect our licensing logic and provide a seamless installation experience. This is a deliberate security decision.

### 📦 System Requirements

| Requirement | Details |
| :--- | :--- |
| **OS** | Windows 10 or later (64-bit) |
| **Java** | Bundled with the installer (no separate installation required) |
| **RAM** | 4 GB minimum (8 GB recommended) |
| **Storage** | 500 MB for the app + space for models and audio files |
| **Dependencies** | FFmpeg and FFprobe (required) — see [Troubleshooting Guide](TROUBLESHOOTING.md) |

---

## 📖 Quick Start

1.  **Download** the installer for your platform from the [Download](#-download) section.
2.  **Install** the app by running the installer.
3.  **Launch** AudioManager from your Start Menu.
4.  **Check dependencies:** `Help → Check Dependencies` (or press `F5`).
5.  **Add files** to the queue using the file browser or drag-and-drop.
6.  **Configure** transcription settings (`Tools → Transcription Settings`).
7.  **Press Start** and watch your files get transcribed!

---

## 📸 Screenshots

*Screenshots coming soon!*

| Main Window | Batch Queue | Performance Report |
| :---: | :---: | :---: |
| *[Placeholder]* | *[Placeholder]* | *[Placeholder]* |

---

## 🛠️ Installation & Setup

### Step 1: Install FFmpeg (Required)

AudioManager relies on FFmpeg for audio processing. Install it from:

- **Download:** [https://ffmpeg.org/download.html](https://ffmpeg.org/download.html)
- **Windows:** Place the `ffmpeg.exe` and `ffprobe.exe` in `C:\AI\ffmpeg\bin\` or add them to your system PATH.

### Step 2: Install WhisperX (Required for Transcription)

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

AudioManager **does not download models automatically**. To install a model:

```bash
huggingface-cli download Systran/faster-whisper-<model-name>
```

Replace `<model-name>` with `tiny`, `base`, `small`, `medium`, or `large-v3`.

---

## 📚 Documentation

| Document | Link |
| :--- | :--- |
| **User Manual** | [USER_MANUAL.md](USER_MANUAL.md) |
| **Troubleshooting Guide** | [TROUBLESHOOTING.md](TROUBLESHOOTING.md) |
| **Changelog** | [CHANGELOG.md](CHANGELOG.md) |
| **License** | [LICENSE](LICENSE) |

---

## 🤝 Contributing

We welcome contributions! Here's how you can help:

1.  **Fork the repository** and create your feature branch.
2.  **Write clean, well-documented code**.
3.  **Add tests** for new features.
4.  **Submit a pull request** with a clear description of your changes.

Please read our [CONTRIBUTING.md](CONTRIBUTING.md) for more details.

### Development Setup

```bash
# Clone the repository
git clone https://github.com/gregjava/Aburime-Sound-Manager.git
cd Aburime-Sound-Manager

# Build the project
./gradlew build

# Run tests
./gradlew test

# Create the JAR
./gradlew jar
```

---

## 🐛 Reporting Issues

If you encounter a problem, please [open an issue on GitHub](https://github.com/gregjava/Aburime-Sound-Manager/issues) with:

- A clear description of the issue.
- Steps to reproduce it.
- Your system details (OS, RAM, Java version).
- The error message from the log panel.

---

## 📄 License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](LICENSE) file for details.

---

## 🙏 Acknowledgments

- **WhisperX** — For providing state-of-the-art transcription.
- **FFmpeg** — For handling all audio processing.
- **JavaFX** — For the beautiful cross-platform UI.
- **OpenAI Whisper** — For the foundational transcription model.

---

<div align="center">

**Made with ❤️ by Greg Java**

[🌐 Website](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/) · [🐙 GitHub](https://github.com/gregjava/Aburime-Sound-Manager) · [🐛 Issues](https://github.com/gregjava/Aburime-Sound-Manager/issues)

</div>
