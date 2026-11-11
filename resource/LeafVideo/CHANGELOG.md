# Changelog

All notable changes to `LeafVideo` are documented in this file.

## [Unreleased]

## [0.1.0] - Unreleased

### Added
- **Three-Tier Open Standard (`MIT`) and Commercial Workflow Product Architecture (`leaf-video-ADR-002`):**
  - **Tier 1 (`leaf-video/`, MIT):** Normative `LCV-ICD-1.0` specification (`leaf-video/docs/ICD.md`) and `leaf-video-ADR-001`..`004`.
  - **Tier 2 (`resource/LeafVideo/`, Dual Track MIT Spec + Commercial Pro GUI Studio):** Product architecture for the OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite (`obs_leafvideo_plugin.c`, `integrations/obs_leafvideo_crop_guard.py`) and NLE Instant-Scrub & Crash-Resumable Render Plugin (`lcv_codec_system_plugins.c`: DaVinci Resolve, Premiere Pro, After Effects, Blender), including goal-oriented `Recording Quality` presets (`INSTANT_EDIT_LOSSLESS`, `HIGH_QUALITY_COMPACT`, `STUDIO_ARCHIVAL_LOSSLESS`, `FOVEATED_CENTER_FOCUS`, `VR_STEREO_FOVEATED_DIRECT`), magnetic `16x16` pixel-to-tile crop snapping, and carrier export.
  - **Tier 3 (`resource/LeafVideo/native/`, `LicenseRef-LeafCraft-Commercial`):** Canonical C11 + `AVX2`/`NEON` SIMD + GPU Compute (`Vulkan` `lcv_cube_kernel.comp` & `CUDA PTX` in `lcv_accel.c`) codec, compressed-domain transformer, and `WIRELESS_FOVEATED_LOW_LATENCY` / `HARSH_RF_UEP_ZERO_WAIT` / `VR_STEREO_FOVEATED_DIRECT` `LCVU` UDP streaming SDK.
  - **Public Repository Projection (`LeafCraftDev/LeafVideo`):** Wired `--target video` in `scripts/release/sync_public_oss_pr.py` and `scripts/pre_push_guard.py`.
