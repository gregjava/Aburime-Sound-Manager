# Changelog

## [4.0.0] - 2026-08-19 - Phoenix

### Added
- Adaptive concurrency scaling based on system resource monitoring
- Performance reporting dialog with per-file stage timing
- ID3 tagging (sidecar .meta files) for audio files
- First-run onboarding wizard
- Batch scheduling for off-hours processing
- Opt-in error reporting with anonymized crash data
- REST API for headless automation
- GPU detection and CUDA support (when available)

### Changed
- Upgraded WhisperX integration for better accuracy
- Improved parallel processing performance (40% faster)
- Enhanced UI with dark mode support
- Better memory management with resource-aware pooling

### Fixed
- Fixed NPE on Linux/macOS due to LOCALAPPDATA
- Fixed output file generation in parallel batches
- Fixed time estimation for segmented files
- Fixed cancellation for parallel batches
- Fixed auto-remove completed files

### Security
- EULA display on first run
- Improved token handling (HF_TOKEN only from environment)
- Code signing support for Windows builds

## [3.9.0] - 2025-10-11
- Initial public release
- Basic batch processing with WhisperX
- Audio format conversion with FFmpeg
- Audio splitting and text file combining tools
```

