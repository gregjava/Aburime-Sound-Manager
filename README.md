<div align="center">

# 🎵 AudioManager v4.0.0 — Phoenix

### Professional Audio Transcription & Processing

[![Version](https://img.shields.io/badge/version-4.0.0-blue.svg)](https://github.com/gregjava/Aburime-Sound-Manager/releases)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-17+-orange.svg)](https://adoptium.net/)
[![Python](https://img.shields.io/badge/Python-3.10--3.12-blue.svg)](https://python.org/)
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
| ⚡ **GPU Acceleration** | Up to **3x faster** transcription with NVIDIA GPUs. |
| 🎬 **Streaming** | Automatic chunking for files >100MB to prevent memory issues. |
| 🚀 **Setup Wizard** | Guided setup for dependencies, Python version validation, and model selection. |

---

## 🚀 Download

### Secure Windows Installers

| Platform | Download Link | Size |
| :--- | :--- | :--- |
| **Windows (EXE)** | [⬇ Download AudioManager-Setup.exe](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/AburimeSoundManager-v4.0.0.exe) | ~197 MB |
| **Windows (MSI)** | [⬇ Download AudioManager-Installer.msi](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/AburimeSoundManager-v4.0.0.msi) | ~197 MB |

> **🔒 Security Note:** We distribute native Windows installers (`.exe`/`.msi`) instead of a `.jar` file to protect our licensing logic and provide a seamless installation experience. This is a deliberate security decision.

### 📦 System Requirements

| Requirement | Details |
| :--- | :--- |
| **OS** | Windows 10 or later (64-bit) |
| **Java** | Bundled with the installer (no separate installation required) |
| **RAM** | 4 GB minimum (8 GB recommended) |
| **Storage** | 500 MB for the app + space for models and audio files |
| **Python** | **3.10, 3.11, or 3.12** (3.13+ **NOT** supported) |
| **GPU** | NVIDIA GPU with 4+ GB VRAM (optional, for GPU acceleration) |
| **Dependencies** | FFmpeg and FFprobe (required) — see [Troubleshooting Guide](TROUBLESHOOTING.md) |

> ## ⚠️ Python Version Note
>
> **WhisperX does NOT work with Python 3.13 or newer.**
>
> If you have Python 3.13 or higher installed, you MUST install Python 3.10, 3.11, or 3.12 in a separate location.
>
> **Check your Python version:**
> ```bash
> python --version
> ```
>
> If you see `Python 3.13.x` or higher, download Python 3.12 from [python.org](https://www.python.org/downloads/) before proceeding.

---

## 📖 Quick Start

1.  **Download** the installer for your platform from the [Download](#-download) section.
2.  **Install** the app by running the installer.
3.  **Launch** AudioManager from your Start Menu.
4.  **Run the Setup Wizard:** `Help → Run Setup Wizard` (or press `Ctrl+Shift+S`) to:
    - Check your Python version
    - Verify FFmpeg and WhisperX installation
    - Select and verify models
5.  **Check dependencies:** `Help → Check Dependencies` (or press `F5`).
6.  **Add files** to the queue using the file browser or drag-and-drop.
7.  **Configure** transcription settings (`Tools → Transcription Settings`).
8.  **Press Start** and watch your files get transcribed!

---

## 📸 Screenshots

*Screenshots coming soon!*

| Main Window | Batch Queue | Performance Report |
| :---: | :---: | :---: |
| *[Placeholder]* | *[Placeholder]* | *[Placeholder]* |

---

## 🛠️ Installation & Setup

### Step 1: Install Python (Required for Transcription)

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

### Step 2: Install FFmpeg (Required)

AudioManager relies on FFmpeg for audio processing. Install it from:

- **Download:** [https://ffmpeg.org/download.html](https://ffmpeg.org/download.html)
- **Windows:** Place the `ffmpeg.exe` and `ffprobe.exe` in `C:\AI\ffmpeg\bin\` or add them to your system PATH.

**Verify FFmpeg installation:**
```bash
ffmpeg -version
ffprobe -version
```

### Step 3: Install WhisperX (Required for Transcription)

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

**If you get an error, try:**
```bash
# Upgrade pip first
pip install --upgrade pip

# Then install WhisperX
pip install whisperx
```

> **💡 Tip:** If you have multiple Python versions, use the full path:
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

AudioManager **does not download models automatically**. To install a model:

```bash
huggingface-cli download Systran/faster-whisper-<model-name>
```

Replace `<model-name>` with `tiny`, `base`, `small`, `medium`, or `large-v3`.

### Step 6: (Optional) Install CUDA for GPU Acceleration

If you have an NVIDIA GPU:

1. Install NVIDIA drivers from [nvidia.com](https://www.nvidia.com/Download/index.aspx)
2. Install CUDA Toolkit 12.x from [NVIDIA CUDA](https://developer.nvidia.com/cuda-downloads)
3. Verify with: `nvidia-smi`

---

## 📚 Documentation

| Document | Link | Description |
| :--- | :--- | :--- |
| **User Manual** | [USER_MANUAL.md](USER_MANUAL.md) | Complete user guide |
| **Troubleshooting Guide** | [TROUBLESHOOTING.md](TROUBLESHOOTING.md) | Common issues and solutions |
| **API Documentation** | [API.md](API.md) | REST API reference |
| **Changelog** | [CHANGELOG.md](CHANGELOG.md) | Version history |
| **License** | [LICENSE](LICENSE) | Apache 2.0 |

---

## ⌨️ Keyboard Shortcuts

| Shortcut | Action |
|----------|--------|
| `Ctrl+Shift+D` | Toggle Dark Mode |
| `F5` | Check Dependencies |
| `Ctrl+Shift+W` | Toggle Folder Watch |
| `Ctrl+Shift+P` | Performance Report |
| `Ctrl+Shift+S` | **Run Setup Wizard** (NEW) |
| `Ctrl+B` | Batch Settings |
| `Ctrl+Q` | Exit |
| `Ctrl+Shift+C` | Clear Session Data |
| `Ctrl+Z` | Undo |
| `Ctrl+Y` / `Ctrl+Shift+Z` | Redo |
| `F1` | Troubleshooting Guide |

---

## 🎯 Supported Formats

### Audio Formats
MP3, WAV, FLAC, OGG, M4A, WMA, AAC, OPUS, ALAC, AIFF, AMR, AC3

### Output Formats
SRT, TXT, DOCX, HTML, JSON

---

## ⚡ Performance

### Model Comparison

| Model | Speed | Accuracy | Memory |
|-------|-------|----------|--------|
| **Tiny** | ⚡ Very Fast | 🟡 Basic | 39 MB |
| **Base** | ⚡ Fast | 🟢 Good | 74 MB |
| **Small** | 🟢 Fast | 🔵 Better | 244 MB |
| **Medium** | 🟡 Moderate | 🟣 High | 769 MB |
| **Large** | 🔴 Slow | ⭐ Best | 1.5 GB |

### Performance Tips

| Tip | Benefit |
|-----|---------|
| **Use GPU** | 2-3x faster transcription |
| **Smaller model** | Faster processing, less memory |
| **Reduce parallel files** | Lower memory usage |
| **Enable streaming** | Better memory efficiency for large files |
| **Disable diarization** | Faster on CPU |

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
ant build

# Run tests
ant test-all

# Generate test report
ant test-report

# Create distribution
ant dist
```

### Testing

```bash
# Run unit tests
ant test

# Run integration tests
ant test-integration

# Run all tests with coverage
ant test-with-coverage

# Run a single test class
ant test-single -Dtest.class=audiomanager.util.TimeLeftEstimatorTest
```

### Build

```bash
# Build JAR
ant jar

# Build with GPU support
ant gpu-build

# Build signed distribution
ant release

# Build distribution package
ant dist
```

---

## 🐛 Reporting Issues

If you encounter a problem, please [open an issue on GitHub](https://github.com/gregjava/Aburime-Sound-Manager/issues) with:

- A clear description of the issue.
- Steps to reproduce it.
- Your system details (OS, RAM, Java version).
- **Your Python version** (`python --version`).
- The error message from the log panel.
- Output of `Help → Check Dependencies`.
- Output of `Help → Run Setup Wizard` (if applicable).

---

## 📄 License

This project is licensed under the **Apache License 2.0** — see the [LICENSE](LICENSE) file for details.

### Third-Party Components

| Component | License |
|-----------|---------|
| **FFmpeg** | LGPL |
| **WhisperX** | MIT |
| **JavaFX** | GPL with Classpath Exception |
| **Apache Log4j/SLF4J** | Apache 2.0 |
| **Gson** | Apache 2.0 |

---

## 🙏 Acknowledgments

- **[WhisperX](https://github.com/m-bain/whisperX)** — For providing state-of-the-art transcription with forced alignment.
- **[FFmpeg](https://ffmpeg.org/)** — For handling all audio processing and conversion.
- **[JavaFX](https://openjfx.io/)** — For the beautiful cross-platform UI framework.
- **[OpenAI Whisper](https://github.com/openai/whisper)** — For the foundational transcription model.
- **[HuggingFace](https://huggingface.co/)** — For model hosting and the pyannote diarization model.

---

## 📊 Project Status

| Metric | Status |
|--------|--------|
| **Build** | ✅ Passing |
| **Tests** | ✅ 25+ test classes |
| **Documentation** | ✅ Complete |
| **Code Signing** | ✅ Windows |
| **License** | ✅ Apache 2.0 |
| **GPU Support** | ✅ CUDA 12.x |
| **Setup Wizard** | ✅ Complete |

---

## 📞 Support

| Channel | Link |
|---------|------|
| **Website** | [https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/) |
| **GitHub** | [https://github.com/gregjava/Aburime-Sound-Manager](https://github.com/gregjava/Aburime-Sound-Manager) |
| **Issues** | [https://github.com/gregjava/Aburime-Sound-Manager/issues](https://github.com/gregjava/Aburime-Sound-Manager/issues) |
| **Email** | support@audiomanager.app |

---

<div align="center">

**Made with ❤️ by Greg Java**

<a href="https://www.producthunt.com/products/index-of-aburime?embed=true&amp;utm_source=badge-featured&amp;utm_medium=badge&amp;utm_campaign=badge-index-of-aburime" target="_blank" rel="noopener noreferrer"><img alt="Index of /Aburime - Professional audio transcription, 100% offline — no cloud | Product Hunt" width="250" height="54" src="https://api.producthunt.com/widgets/embed-image/v1/featured.svg?post_id=1228818&amp;theme=dark&amp;t=1787374992468"></a><br>
[🌐 Website](https://poseidon.org.uk/Aburime-Sound-Manager-v4.0/) · [🐙 GitHub](https://github.com/gregjava/Aburime-Sound-Manager) · [🐛 Issues](https://github.com/gregjava/Aburime-Sound-Manager/issues)

</div>
