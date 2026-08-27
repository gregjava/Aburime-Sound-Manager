# AudioManager — Troubleshooting Guide

## Quick Reference

| Error Code | Problem | Quick Fix |
|------------|---------|-----------|
| **ERR-001** | FFmpeg Missing | Install FFmpeg or set PATH |
| **ERR-002** | WhisperX Not Found | `pip install whisperx` |
| **ERR-003** | Out of Memory | Reduce parallel files or use smaller model |
| **ERR-004** | GPU Not Detected | Install CUDA or NVIDIA drivers |
| **ERR-005** | Output File Missing | Check disk space and permissions |
| **ERR-006** | Bound Value Error | Restart application |
| **ERR-007** | API Port in Use | Use different port |
| **ERR-008** | Model Not Found | Install model manually |
| **ERR-009** | Chunk Processing Failed | Check disk space and FFmpeg |
| **ERR-010** | Diarization Failed | Check HF token and model |
| **ERR-011** | Timeout Error | Increase timeout or reduce file size |
| **ERR-012** | Python Version Incompatible | **NEW** — Use Python 3.10, 3.11, or 3.12 |
| **ERR-013** | TorchCodec DLL Missing | **NEW** — Install torchcodec==0.7.0 or copy FFmpeg DLLs |
| **ERR-014** | WhisperX Installation Fails | **NEW** — Check Python version and dependencies |

For detailed solutions, see the corresponding error section below.

---

## Dependency Problems

### ERR-001: FFmpeg Missing

**Message:** `FFmpeg is required for audio processing` / `Start Processing is disabled`

**Symptoms:**
- Dependency check fails
- Audio processing unavailable
- Application shows "FFmpeg Missing" status
- "Start Processing" button is disabled

**Root Cause:**
FFmpeg is not installed or not in the system PATH.

**Solutions:**

1. **Install FFmpeg**
   - Windows: Download from [gyan.dev](https://www.gyan.dev/ffmpeg/builds/)
   - macOS: `brew install ffmpeg`
   - Linux: `sudo apt install ffmpeg`

2. **Add to PATH**
   - Windows: `set PATH=%PATH%;C:\path\to\ffmpeg\bin`
   - macOS/Linux: `export PATH=$PATH:/path/to/ffmpeg/bin`

3. **Verify Installation**
   ```bash
   ffmpeg -version
   ffprobe -version
   ```

4. **In AudioManager**
   - Click `Help → Check Dependencies` (or press `F5`)
   - Or run `Help → Run Setup Wizard` (or press `Ctrl+Shift+S`)
   - Verify FFmpeg is found

---

### ERR-002: WhisperX Not Found

**Message:** `WhisperX not found or not installed` / `Transcription disabled`

**Symptoms:**
- Transcription disabled
- Dependency check fails for WhisperX
- Python environment issues

**Root Cause:**
WhisperX is not installed in the Python environment.

**Solutions:**

1. **Install WhisperX**
   ```bash
   pip install whisperx
   ```

2. **Set WHISPERX_PYTHON**
   ```bash
   # Windows
   set WHISPERX_PYTHON=C:\path\to\python.exe
   
   # macOS/Linux
   export WHISPERX_PYTHON=/path/to/python
   ```

3. **Use Virtual Environment**
   ```bash
   python -m venv whisperx_env
   whisperx_env\Scripts\activate  # Windows
   source whisperx_env/bin/activate  # macOS/Linux
   pip install whisperx
   ```

4. **Verify Installation**
   ```bash
   python -m whisperx --help
   ```

---

## Python & WhisperX Installation Issues

### ERR-012: Python Version Incompatible ⭐ NEW

**Message:** `Python 3.13+ is not supported` / `WhisperX installation fails` / `Python version not compatible`

**Symptoms:**
- `pip install whisperx` fails with errors
- Import errors when running transcription
- "Python version not compatible" warning in Setup Wizard
- Dependency check shows Python version warning

**Root Cause:**
WhisperX requires Python 3.10, 3.11, or 3.12. Python 3.13 and newer are not supported due to breaking changes in the Python C API that affect PyTorch and WhisperX dependencies.

**Solutions:**

1. **Check your Python version:**
   ```bash
   python --version
   ```

2. **If you have Python 3.13+ or newer:**
   - Download Python 3.12 from [python.org](https://www.python.org/downloads/)
   - Install it (you can have multiple Python versions)
   - Use the full path to Python 3.12 for WhisperX

3. **Create a virtual environment with the correct version:**
   ```bash
   # Replace with the path to your Python 3.12 installation
   C:\Python312\python.exe -m venv whisperx_env
   
   # Activate it
   whisperx_env\Scripts\activate
   
   # Install WhisperX
   pip install whisperx
   ```

4. **Set the WHISPERX_PYTHON environment variable:**
   ```bash
   # Windows (Command Prompt)
   set WHISPERX_PYTHON=C:\Python312\python.exe
   
   # Windows (PowerShell)
   $env:WHISPERX_PYTHON = "C:\Python312\python.exe"
   
   # Or set it permanently in System Environment Variables
   ```

5. **Verify the fix:**
   ```bash
   python -c "import whisperx; print('OK')"
   ```

**Prevention:**
- The Setup Wizard will warn you if Python 3.13+ is detected
- Run `Help → Run Setup Wizard` (or press `Ctrl+Shift+S`) to check your Python version
- The dependency check (`F5`) also shows Python version status

---

### ERR-013: TorchCodec DLL Missing ⭐ NEW

**Message:** `Could not load libtorchcodec` / `FFmpeg version X: Could not find module` / `torchcodec DLL error`

**Symptoms:**
- Transcription fails with "libtorchcodec" errors
- Windows-specific error about missing DLLs
- TorchCodec installed but not working

**Root Cause:**
TorchCodec on Windows requires FFmpeg DLLs that are not included in the PyPI wheel. This is a packaging issue with the torchcodec package.

**Solutions:**

1. **Install the correct version of TorchCodec:**
   ```bash
   # Uninstall current version
   pip uninstall torchcodec -y
   
   # Install the version WhisperX requires
   pip install torchcodec==0.7.0
   ```

2. **Copy FFmpeg DLLs to the TorchCodec folder:**
   ```bash
   # Copy all DLLs from FFmpeg bin to torchcodec folder
   copy "C:\AI\ffmpeg\bin\*.dll" "C:\Users\YOUR_USERNAME\AppData\Local\Programs\Python\Python311\Lib\site-packages\torchcodec\"
   ```
   
   > **Note:** Replace `YOUR_USERNAME` with your actual Windows username and adjust the Python path if using a different version.

3. **Add FFmpeg to your system PATH:**
   - Open System Properties → Environment Variables
   - Add `C:\AI\ffmpeg\bin` to the `PATH` variable (User or System)
   - Restart your command prompt
   - Test: `ffmpeg -version`

4. **Verify the fix:**
   ```bash
   python -c "import torchcodec; print('TorchCodec OK')"
   ```

5. **If problems persist, use the inline fallback script:**
   - Delete any custom `audio_transcription_script.py` file in your user directory
   - Let the app use the bundled fallback script that uses FFmpeg directly

**Prevention:**
- The Setup Wizard checks for TorchCodec installation
- Run `Help → Run Setup Wizard` (or press `Ctrl+Shift+S`) to verify TorchCodec

---

### ERR-014: WhisperX Installation Fails ⭐ NEW

**Message:** `ERROR: Could not find a version that satisfies the requirement whisperx` / `ERROR: No matching distribution found for whisperx`

**Symptoms:**
- `pip install whisperx` fails
- Installation process hangs or crashes
- Error messages about missing dependencies

**Root Cause:**
- Python version 3.13 or newer (not supported)
- Missing Visual C++ Redistributable on Windows
- Pip needs to be upgraded
- Missing build tools

**Solutions:**

1. **Check Python version first:**
   ```bash
   python --version
   ```
   If you have Python 3.13+, see ERR-012 above.

2. **Upgrade pip:**
   ```bash
   python -m pip install --upgrade pip
   ```

3. **Install Visual C++ Redistributable (Windows):**
   - Download from [Microsoft](https://support.microsoft.com/en-us/topic/the-latest-supported-visual-c-downloads-2647da03-1eea-4433-9aff-95f26a218cc0)
   - Install both x86 and x64 versions if on 64-bit Windows
   - Restart your computer

4. **Install build tools (if needed):**
   ```bash
   # Windows
   pip install setuptools wheel
   
   # Then try installing WhisperX again
   pip install whisperx
   ```

5. **Use a virtual environment with Python 3.12:**
   ```bash
   # Create a fresh virtual environment with Python 3.12
   C:\Python312\python.exe -m venv whisperx_env
   whisperx_env\Scripts\activate
   pip install --upgrade pip
   pip install whisperx
   ```

6. **Check for conflicting packages:**
   ```bash
   pip list
   # Look for conflicting PyTorch versions
   # Uninstall if needed: pip uninstall torch torchvision torchaudio
   # Then reinstall with WhisperX
   ```

---

## Performance Issues

### ERR-003: Out of Memory (OOM)

**Message:** `Out of memory` / `MemoryError` / `java.lang.OutOfMemoryError`

**Symptoms:**
- Processing fails mid-way
- Application becomes slow
- Java heap space errors
- "Could not allocate memory" errors

**Root Cause:**
Insufficient memory for the current workload.

**Solutions:**

1. **Reduce Parallel Files**
   - Decrease "Max Parallel Files" setting
   - Use sequential processing (max=1)

2. **Use Smaller Model**
   - Switch from large/medium to small/base/tiny

3. **Increase Heap Size**
   ```bash
   java -Xmx4g -jar AudioManager.jar
   ```

4. **Enable GPU**
   - Offloads memory to GPU VRAM
   - Reduces system RAM usage

5. **Clear Session Data**
   - Tools → Clear Session Data
   - Removes cached data

6. **Close Other Applications**
   - Free up system memory

---

### ERR-004: GPU Not Detected

**Message:** `No NVIDIA GPU detected` / `Running in CPU mode`

**Symptoms:**
- GPU status shows "No GPU detected"
- Processing uses CPU only
- Slower than expected

**Root Cause:**
- NVIDIA drivers not installed
- CUDA not installed
- Incompatible GPU
- GPU disabled in preferences

**Solutions:**

1. **Check GPU**
   ```bash
   nvidia-smi
   ```

2. **Install CUDA Toolkit**
   - Download from [NVIDIA CUDA](https://developer.nvidia.com/cuda-downloads)
   - Version 12.x recommended

3. **Install Drivers**
   - Windows: NVIDIA driver from [nvidia.com](https://www.nvidia.com/Download/index.aspx)
   - Linux: `sudo apt install nvidia-driver-535`
   - macOS: Limited GPU support

4. **Verify CUDA**
   ```bash
   nvcc --version
   ```

5. **Enable in Preferences**
   - Go to Performance section
   - Check "Enable GPU Acceleration (CUDA)"

6. **Refresh Detection**
   - Click "Refresh GPU Detection" in Performance section

---

### ERR-011: Timeout Error

**Message:** `Batch timed out after X hours` / `Process timed out`

**Symptoms:**
- Batch stops with timeout error
- Long-running files fail

**Root Cause:**
- File processing exceeds configured timeout
- Very large files
- Slow hardware

**Solutions:**

1. **Increase Timeout** (Advanced)
   - The timeout is calculated based on file size and model
   - For very large files, timeout auto-adjusts

2. **Use Smaller Model**
   - Smaller models process faster

3. **Enable GPU**
   - GPU processing is faster

4. **Split Large Files**
   - Use Audio Splitter tool to break into smaller chunks

---

## Model Issues

### ERR-008: Model Not Found

**Message:** `Model 'X' is not installed locally`

**Symptoms:**
- Transcription fails with model error
- Model download fails
- "Model not found" in logs

**Root Cause:**
- Model not cached
- Network issues
- Insufficient disk space

**Solutions:**

1. **Download Manually**
   ```bash
   huggingface-cli download Systran/faster-whisper-<model-name>
   ```

2. **Check Cache Location**
   - **Windows:** `%LOCALAPPDATA%\huggingface\hub\`
   - **macOS/Linux:** `~/.cache/huggingface/hub/`
   - Look for `models--Systran--faster-whisper-<model-name>`

3. **Use Different Model**
   - Try a smaller model (tiny, base)
   - Try a different model (small, medium)

4. **Check Disk Space**
   - Models range from 75 MB to 3 GB
   - Ensure enough free space

5. **Verify Cache**
   - The folder should contain a `snapshots` subfolder
   - Should contain `.bin` or `.safetensors` files

---

### ERR-010: Diarization Failed

**Message:** `Diarization failed` / `Speaker labels missing`

**Symptoms:**
- Speaker labels not appearing in transcripts
- Diarization errors in logs

**Root Cause:**
- Missing HuggingFace token
- PyAnnote model not installed
- Alignment model missing

**Solutions:**

1. **Check HF Token**
   - Set `HF_TOKEN` environment variable
   - Or place token in `~/.audiomanager/hf_token`

2. **Get a Token**
   - Go to [huggingface.co/settings/tokens](https://huggingface.co/settings/tokens)
   - Create a token with `read` access
   - Accept pyannote model terms

3. **Install PyAnnote Model**
   - The app attempts to download automatically
   - Ensure internet access on first use

4. **Check Alignment Model**
   - The alignment model is downloaded automatically
   - Check `~/.cache/torch/hub/checkpoints/`

5. **Disable Diarization**
   - If not needed, disable in Transcription Settings
   - Improves performance

---

## Output Issues

### ERR-005: Output File Missing/Empty

**Message:** `Output file is missing or empty`

**Symptoms:**
- Batch reports success but no files found
- Transcripts are empty (0 bytes)
- "Saved output" log entry but file missing

**Root Cause:**
- Disk full
- Permission denied
- Antivirus blocking
- Process crashed mid-write

**Solutions:**

1. **Check Disk Space**
   - Ensure at least 100MB free
   - Clean up temporary files

2. **Verify Permissions**
   - Output directory must be writable
   - Run as administrator if needed

3. **Disable Antivirus** (Temporarily)
   - Some AV block file creation
   - Add exception for output directory

4. **Check Output Directory**
   - Verify path is valid
   - Check for special characters in path

5. **Check Logs**
   - Look for error messages in terminal
   - Check `~/.audiomanager/logs/`

---

### Word (.docx) Copy Missing

**Message:** `Failed to save Word-compatible copy`

**Symptoms:**
- SRT/TXT files are fine
- DOCX file is missing
- Warning in logs

**Root Cause:**
- DOCX export is optional
- Best-effort, not batch-stopping

**Solutions:**

1. **Check Log**
   - Look for specific error
   - Usually a warning, not fatal

2. **Enable DOCX Export**
   - Check `Also Save Word-Compatible (.docx) Copy`
   - In Transcription Settings

3. **HTML Fallback**
   - DOCX generation uses XML
   - Should work on all systems

---

## UI/Application Issues

### ERR-006: Bound Value Error

**Message:** `A bound value cannot be set`

**Symptoms:**
- Error dialog appears
- Application may crash
- Theme switching fails
- UI elements don't update

**Root Cause:**
- Internal JavaFX property binding issue
- Usually related to theme switching

**Solutions:**

1. **Restart Application**
   - Simple restart often resolves it

2. **Reset Preferences**
   - Delete `~/.audiomanager/preferences.json`
   - Restart application

3. **Clear Theme Cache**
   - Switch theme, then restart

4. **Update Java**
   - Ensure latest Java version

---

### Waveform Preview Shows "Unavailable" or is Blank

**Symptoms:**
- No waveform displayed
- "Preview unavailable" message

**Root Cause:**
- Multiple files selected
- File over 200MB
- FFmpeg couldn't decode the file
- Unsupported or corrupt file

**Solutions:**

1. **Select Single File**
   - Waveform only shows for a single selected file

2. **Check File Size**
   - Files over 200MB are skipped

3. **Verify File**
   - Check file plays correctly in another application
   - Try re-encoding the file

---

### Dark Mode Doesn't Fully Re-Theme Everything

**Symptoms:**
- Some panels stay light-colored
- Inconsistent theming

**Root Cause:**
- Known, documented limitation
- Some panels have heavily customized inline styling

**Solutions:**

1. **Toggle Theme**
   - Toggle off and on again

2. **Restart**
   - Sometimes fixes residual issues

3. **Accept Limitation**
   - Cosmetic only
   - Doesn't affect functionality

---

### Setup Wizard Not Launching

**Symptoms:**
- Help → Run Setup Wizard does nothing
- `Ctrl+Shift+S` has no effect

**Root Cause:**
- The Setup Wizard callback may not be properly wired
- Application not fully initialized

**Solutions:**

1. **Restart the Application**
   - Simple restart often resolves it

2. **Check for Updates**
   - Ensure you're running the latest version

3. **Run from Terminal**
   - Look for errors in the terminal output

4. **Verify Installation**
   - Reinstall the application

---

## REST API Issues

### ERR-007: API Port in Use

**Message:** `Port X is already in use`

**Symptoms:**
- REST API fails to start
- Error message about port availability

**Root Cause:**
- Another application using the port
- Previous instance not fully closed

**Solutions:**

1. **Use Different Port**
   - Enter a different port number (e.g., 8757)

2. **Kill Process**
   ```bash
   # Windows
   netstat -ano | findstr :8756
   taskkill /PID <pid> /F
   
   # macOS/Linux
   lsof -i :8756
   kill -9 <pid>
   ```

3. **Check Other Apps**
   - Some apps use port 8756 (e.g., some game servers)
   - Use `netstat -ano` to identify

---

## Streaming/Chunking Issues

### ERR-009: Chunk Processing Failed

**Message:** `Chunk processing failed` / `FFmpeg chunk split failed` / `No chunks were created`

**Symptoms:**
- Large files fail with "chunk" in the error message
- Processing stops mid-way on large files
- Temporary directory errors

**Root Cause:**
- One or more audio chunks failed to transcribe
- FFmpeg couldn't split the file
- Temporary directory issues
- Disk space insufficient
- Corrupt audio file

**Solutions:**

1. **Check Disk Space**
   - Chunks require temporary storage (typically 2-3x file size)
   - Ensure at least 500MB free

2. **Check FFmpeg**
   - Ensure FFmpeg can split the file
   - Test manually:
   ```bash
   ffmpeg -i large_file.mp3 -ss 0 -t 30 -c copy test_chunk.mp3
   ```

3. **Check Temporary Directory**
   - Ensure write permissions
   - Default: System temp directory

4. **Disable Streaming** (Advanced)
   - If a specific file fails consistently, disable streaming
   - Note: May cause memory issues on very large files

5. **Check File Integrity**
   - The audio file may be corrupt
   - Try playing the file in another application

6. **Reduce Parallelism**
   - Lower max concurrent chunks
   - Reduces resource contention

**Diagnostic Steps:**
```bash
# 1. Check disk space
df -h  # Linux/macOS
dir     # Windows

# 2. Test FFmpeg splitting
ffmpeg -i large_file.mp3 -ss 0 -t 30 -c copy test_chunk.mp3

# 3. Check temp directory permissions
ls -la /tmp  # Linux/macOS
icacls %TEMP%  # Windows
```

---

## System-Specific Issues

### Windows

| Issue | Solution |
|-------|----------|
| **Path too long** | Use shorter file paths or enable long paths in Windows |
| **Antivirus blocking** | Add exception for application and output directories |
| **Permission denied** | Run as administrator |
| **Missing DLLs** | Install Visual C++ Redistributable |
| **Installer fails** | Run installer as administrator |
| **Windows Defender** | Add exclusion for app directory |
| **TorchCodec DLL errors** | See ERR-013 above |
| **Python version issues** | See ERR-012 and ERR-014 above |

### macOS

| Issue | Solution |
|-------|----------|
| **Untrusted developer** | Right-click → Open, or enable in System Preferences |
| **Homebrew issues** | `brew update` and `brew upgrade` |
| **Python environment** | Use `python3` instead of `python` |
| **Permission issues** | `chmod +x run.sh` |

### Linux

| Issue | Solution |
|-------|----------|
| **Missing libraries** | `sudo apt install libavcodec-extra` |
| **Permission issues** | `chmod +x run.sh` |
| **Wayland issues** | Use X11 or set `GDK_BACKEND=x11` |
| **Python environment** | Ensure `python3` is in PATH |

---

## Performance Optimization

### CPU Mode

| Setting | Recommended | Description |
|---------|-------------|-------------|
| **Max Parallel** | 1-2 | Reduce to avoid CPU overload |
| **Model** | small/medium | Balance speed vs accuracy |
| **Skip Segmentation** | On | Reduces CPU overhead |
| **Adaptive Scaling** | On | Prevents memory exhaustion |
| **Diarization** | Off | Huge performance impact on CPU |

### GPU Mode

| Setting | Recommended | Description |
|---------|-------------|-------------|
| **Max Parallel** | 2-4 | Based on GPU memory (4GB → 2, 8GB → 4) |
| **Model** | medium/large | GPU can handle larger models |
| **Skip Segmentation** | Off | GPU can handle segmentation |
| **Compute Type** | float16 | Faster with minimal quality loss |
| **Diarization** | On | GPU can handle diarization well |

---

## Diagnostic Commands

### Check FFmpeg
```bash
ffmpeg -version
ffprobe -version
where ffmpeg      # Windows
which ffmpeg      # macOS/Linux
```

### Check Python/WhisperX
```bash
python --version
pip list | grep whisperx
which python       # macOS/Linux
where python       # Windows
```

### Check Python Version Compatibility
```bash
python -c "import sys; print(f'Python {sys.version_info.major}.{sys.version_info.minor}')"
# If you see 3.13 or higher, you need to install Python 3.12
```

### Check TorchCodec
```bash
python -c "import torchcodec; print('TorchCodec OK')"
# If this fails, see ERR-013 above
```

### Check GPU
```bash
nvidia-smi
nvcc --version
```

### Check System Resources
```bash
# Windows
systeminfo
wmic os get TotalVisibleMemorySize,FreePhysicalMemory

# macOS
system_profiler SPHardwareDataType
vm_stat

# Linux
lscpu
free -h
df -h
```

---

## Log File Locations

| Platform | Location |
|----------|----------|
| **Windows** | `%USERPROFILE%\.audiomanager\logs\` |
| **macOS** | `~/.audiomanager/logs/` |
| **Linux** | `~/.audiomanager/logs/` |

### Log Levels

| Level | Purpose |
|-------|---------|
| **ERROR** | Critical failures |
| **WARN** | Warnings that may affect processing |
| **INFO** | Normal operation events |
| **DEBUG** | Detailed diagnostic information |
| **TRACE** | Very detailed (development) |

### Changing Log Level

1. Go to `Configuration Panel → Interface`
2. Select desired log level from dropdown
3. Changes apply immediately

---

## Getting Help

### 1. Check Logs
- Review terminal output
- Check log files in `.audiomanager/logs/`
- Look for ERROR or WARN messages

### 2. Run the Setup Wizard
- Go to `Help → Run Setup Wizard` (or press `Ctrl+Shift+S`)
- The wizard will check all dependencies and guide you through fixes

### 3. Check Dependencies
- Go to `Help → Check Dependencies` (or press `F5`)
- Verify all required components are installed

### 4. Search the Web
- Include error message in search
- Check GitHub issues

### 5. Contact Support
- Email: support@audiomanager.app
- Include: Version, OS, Python Version, Error Message, Logs

### 6. Report Issues
- GitHub: [github.com/gregjava/Aburime-Sound-Manager/issues](https://github.com/gregjava/Aburime-Sound-Manager/issues)
- Include steps to reproduce
- Attach logs (anonymized)

### When Reporting Issues

Please include:
1. The exact error message
2. Your model, language, and diarization settings
3. Whether the file is under or over 100 MB (determines which processing path is used)
4. Output of `Help → Check Dependencies`
5. Output of `python --version`
6. System information (OS, RAM, CPU)
7. Any relevant log entries

---

*Troubleshooting Guide v1.0 - Aburime Sound Manager v4.0.0 (Phoenix)*
