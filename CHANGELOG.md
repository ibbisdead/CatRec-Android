# Changelog

All notable changes to CatRec Screen Recorder are documented here.

---

## [1.0.6] — 2026-04-26

### Changed
- Rebuilt the recording overlay UI and underlying logic from the ground up for improved reliability and responsiveness.
- Redesigned all overlay and notification icons for a cleaner, more consistent visual language.
- Reworked recording failure handling to gracefully recover from unsupported resolutions and codecs on certain devices, with automatic fallback to compatible configurations.
- Improved overall application performance.

### Fixed
- Resolved OEM-specific compatibility issues affecting Xiaomi, Samsung, and Nothing devices.
- Completed missing localization strings across all supported languages.
- Fixed incorrect image cropping behavior and improved GIF recording quality.

### Added
- Performance-adaptive recording mode that automatically adjusts quality settings on low-performance devices to prevent dropped frames and encoder errors.
- Extended the countdown timer range up to 30 seconds.

---

## [1.0.0] — Initial release

### Added
- Screen recording with configurable resolution, frame rate, bitrate, and encoder settings.
- Optional countdown timer before recording starts.
- Rolling "Clipper" buffer mode — capture the last stretch of action and save a clip without recording ahead.
- GIF mode — record and export directly as GIF with quality presets.
- Audio recording: microphone, internal audio, or a combined mix; optional separate microphone track.
- Floating overlay bubble with start/stop, pause, screenshot, brush markup, face cam, and watermark controls.
- Quick Settings tiles for Record and Clipper modes.
- In-app library with thumbnails for recordings and screenshots.
- In-app video player with trim, compress, merge clips, and video-to-GIF tools.
- Image editor with crop helpers.
- Light, dark, and system theme with accent colour and gradient options.
- Localisation for multiple languages.
- Optional usage and crash analytics (opt-out available in Settings).
