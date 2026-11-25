# Changelog

All notable changes to this project are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-25

First public release.

### Added
- Live camera preview that stays open (and the screen stays on) for as long as the app is open; the app reconnects on its own
  when the camera is taken away and releases it when minimized.
- Fast cold start: the camera is opened during process start, `TotalTime` ≈ 260–450 ms on a Honor 10.
- Huawei-style zoom ring with a scale up to 10x, tap to focus with a brightness (EV) slider, flash as a work light.
- The real frame rate badge; resolution (with the highest frame rate for each), high-speed mode and camera selection in the settings panel.
- Full-size photos on a double tap, saved to `DCIM/Camera`.
- English and Ukrainian interface with an in-app language switch.
- Landscape support.
