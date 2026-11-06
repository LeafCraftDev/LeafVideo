# Changelog

All notable changes to `LeafVideo` are documented in this file.

## [0.1.0] - 2026-11-06

### Added
- **Three-Tier Open Standard (`MIT`) and Commercial Workflow Product Architecture (`leaf-video-ADR-002`):**
  - **Tier 1 (`leaf-video/`, MIT):** Normative `LCV-ICD-1.0` specification (`leaf-video/docs/ICD.md`), `leaf-video-ADR-001`, `leaf-video-ADR-002`, and zero-dependency Java 17+ reference codec (`:leaf-video`: `LcvPrimitives`, `LcvCubeCodec`, `LcvStreamContainer`, `LcvMkvPacketizer`, `LcvOnDiskTransformer`, `LcvCorruptTileException`).
  - **Tier 2 (`resource/LeafVideo/`, Dual Track MIT CLI/Core + Commercial Pro GUI Studio):** Product architecture for the OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite and NLE Instant-Scrub & Crash-Resumable Render Plugin (DaVinci Resolve, Premiere Pro, After Effects, Blender).
  - **Tier 3 (`resource/LeafVideo/`, `LicenseRef-LeafCraft-Commercial`):** Product architecture and commercial license (`resource/LeafVideo/LICENSE`) with open `.lcv` / `V_LCV1` MIT interoperability carve-out for the Native C++20 SIMD/GPU (`AVX2`/`NEON`/`Vulkan`/`CUDA`) encoder and `WIRELESS_FOVEATED_LOW_LATENCY` / `HARSH_RF_UEP_ZERO_WAIT` wireless telemetry SDK.
  - **Public Repository Projection (`LeafCraftDev/LeafVideo`):** Wired `--target video` in `scripts/release/sync_public_oss_pr.py` and `scripts/pre_push_guard.py`.
