# Changelog

All notable changes to `LeafVideo` (`:leaf-video` & `LCV-ICD-1.0`) are documented in this file.

## [0.1.0] - 2026-11-06

### Added
- **Normative Binary Interface Control Document (`LCV-ICD-1.0`, `leaf-video/docs/ICD.md`, MIT):** Defines the self-indexing `64-byte` `"LCV_V001"` `Superblock` header, 2D `uint64` tile pointer directory, Extended Hamming `(72, 64)` `SEC-DED` pointer syndrome table (`1 B/tile`), split Section 4A (Base MSB Slice Pool) and Section 4B (Tail LSB Slice Pool with direct $O(1)$ `lsbTileOffsets[N_tiles]`), Matroska `.mkv` (`CodecID = "V_LCV1"`) streaming `Cluster`/`SimpleBlock` framing, and `tuningProfile16` operating profiles (`ARCHIVAL_LOSSLESS`, `OBS_REALTIME_LOSSLESS`, `WIRELESS_FOVEATED_LOW_LATENCY`, `HARSH_RF_UEP_ZERO_WAIT`).
- **Zero-Dependency Java 17+ Reference Codec (`:leaf-video`, `leaf-video-ADR-001`, MIT):**
  - `LcvPrimitives`: 3D (`H_3`) and column-Hilbert (`T+H_2`) LUTs, 1-pass directional anisotropy curve selection, `(72, 64)` `SEC-DED` pointer healing, and reversible `RGBA <-> YCoCg-R` lifting.
  - `LcvCubeCodec`: Outcome-driven $16 \times 16 \times K$ ($K \in \{1, 2, 4, 8, 16\}$) spatiotemporal cube encoder/decoder (`UNIFORM_INLINE`, `TEMPORAL_CLEAN_ACK`, `UI_PALETTE_ROARING`, `GRAY_PLANES_RAW`, `GRAY_PLANES_HILBERT_DELTA` including 3D planar + motion-sheared + $4 \times 4$ Haar LL residual sub-modes, and `RAW_CEILING`) with wire-compatible distance-scaled RLE concatenation (`d_merge(plane, dist)`).
  - `LcvStreamContainer` & `LcvMkvPacketizer`: Self-indexing `.lcv` and `.mkv` (`V_LCV1`) stream muxer/demuxer with `CBIT` single-tile fault quarantine (`LcvCorruptTileException`) and `Case A` temporal tile cache fallback.
  - `LcvOnDiskTransformer`: Compressed-domain on-disk transforms (`cropSubRegion`, `stripLsbPlanes`, `coalesceRunsByDistance`, and crash-resumable `recodeInline` / `resumeInlineRecode` via `FLAG_INLINE_RECODE_PARTIAL`).
- **Three-Tier Open Standard & Commercial Product Split (`leaf-video-ADR-002` & `resource/LeafVideo/`):** Establishes Tier 1 (`LCV-ICD-1.0` & `:leaf-video` under MIT), Tier 2 Creator & Editor Binaries (OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite and NLE Instant-Scrub & Crash-Resumable Render Plugin under Dual-Track MIT CLI/Core + Pro GUI Studio), and Tier 3 High-Performance Native SIMD/GPU & Wireless Foveated UEP SDK (`LicenseRef-LeafCraft-Commercial`), projected to `https://github.com/LeafCraftDev/LeafVideo`.
