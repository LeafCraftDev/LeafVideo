# LeafVideo — Single-Encode Multi-Crop, Instant NLE Scrub & Crash-Proof `.lcv` Video Suite

Record one lightweight `.lcv` / `.mkv` (`V_LCV1`) stream and extract 9:16 vertical shorts in `0.9 ms` (`160x-735x` faster than re-encoding), strip proxy previews in `0.1 ms`, scrub `RGBA` overlays at `240-402 MB/s`, and survive any OBS, NLE, or wireless link failure.

- **Open Standard (`MIT`):** [`LCV-ICD-1.0` (`leaf-video/docs/ICD.md`)](../../../../leaf-video/docs/ICD.md) and [`:leaf-video` (`leaf-video/`)](../../../../leaf-video/README.md) are 100% Free & Open Source (**MIT License**) with zero vendor lock-in and zero patent-pool royalties.
- **GitHub Repository:** [`https://github.com/LeafCraftDev/LeafVideo`](https://github.com/LeafCraftDev/LeafVideo)

---

## Common Setups & Product Tiers

| Tier | Product | License / Model | Target Workflow |
| :--- | :--- | :--- | :--- |
| **Tier 1: Open Standard & Core Specification** | `LCV-ICD-1.0` + `:leaf-video` specification & verification suite | **Free (`MIT`)** | Open `.lcv` / `.mkv` (`V_LCV1`) encoding, decoding, on-disk cropping, LSB stripping, and third-party integration |
| **Tier 2A: OBS Creator Studio** | **OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite** | **Dual Track (`MIT` Spec / Pro GUI)** | Single-encode 4K canvas capture (`obs_leafvideo_plugin.c`) with 1-click 9:16 vertical crop, facecam isolation, proxy strip, and crash recovery |
| **Tier 2B: NLE Post-Production** | **NLE Instant-Scrub & Crash-Resumable Render Plugin** | **Dual Track (`MIT` Spec / Pro Plugin)** | DaVinci Resolve, Premiere Pro, After Effects, and Blender native `.lcv` timeline reader (`lcv_codec_system_plugins.c`) and crash-resumable exporter |
| **Tier 3: Native & Wireless SDK** | **Native C11 SIMD/GPU & `HARSH_RF_UEP` Wireless Telemetry SDK** | **Commercial (`LicenseRef-LeafCraft-Commercial`)** | `lcv_accel.c` (`AVX2`/`NEON`/`Vulkan`/`CUDA`) encoder and zero-lookahead ($K=1$) foveated `LCVU` UDP link SDK for VR headsets, FPV drones & robotics |

---

## Features & Workflow Capabilities

### 1. OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite
- **One Encoder Instead of Four:** Stop running 3 to 4 simultaneous OBS `Source Record` encoders for your 4K canvas, 9:16 vertical short, facecam, and `RGBA` VTuber overlay. Record one `.lcv` stream (`OBS_REALTIME_LOSSLESS`).
- **Sub-Millisecond Compressed-Domain Cropping (`cropSubRegion`):** Extract any tile-aligned sub-region (such as a `1080x1920` vertical short or webcam box) directly from the `.lcv` file in `0.9-1.2 ms` without decoding or re-encoding pixels.
- **Instant Proxy Stripping (`stripLsbPlanes`):** Drop the Section 4B tail LSB pool on disk in `0.1-1.1 ms` to produce a lightweight editing proxy with zero cumulative drift.
- **Crash-Proof Recording (`Superblock` + `SEC-DED`):** Append-only `64 B` `"LCV_V001"` superblocks require no end-of-file index; if OBS or the PC crashes mid-stream, 100% of completed superblocks remain playable and $O(1)$ seekable.

### 2. NLE Instant-Scrub & Crash-Resumable Render Plugin
- **Compact `RGBA` Overlays (`0.52%` of Raw):** Compresses transparent `RGBA` VTuber and motion-graphics overlays by `192.1x` losslessly (`0.52%` of raw, or `0.29%` stripped)—up to `30x-75x` smaller than ProRes 4444 or UtVideo.
- **$O(1)$ Viewport Tile Scrubbing (`240-402 MB/s`):** Independent spatiotemporal tile pointers (`uint64[tilesX * tilesY]`) and Section 4B direct offsets (`lsbTileOffsets[N_tiles]`) allow timeline scrubbing to decode only the visible viewport tiles.
- **Crash-Resumable Exports (`resumeInlineRecode`):** In-place packet-to-packet recoding writes a 4-byte `FLAG_INLINE_RECODE_PARTIAL` checkpoint (`recodedCursor16`, `priorTuningProfile16`), allowing a crashed multi-hour render to play immediately or resume from the exact interrupted tile.

### 3. Wireless Live Video & Foveated UEP Telemetry SDK
- **100% Lossless Crosshair ROI:** `WIRELESS_FOVEATED_LOW_LATENCY` and `HARSH_RF_UEP_ZERO_WAIT` ($K=1$, `0 ms` lookahead) keep the focal center crosshair bit-exact (`PSNR = infinity`) while smoothly coalescing peripheral LSB runs (`d_merge(plane, dist)`).
- **Sub-Millisecond Radio Queue Shedding (`UEP`):** During RF fade or congestion, transmitters drop Section 4B (`g2..g0`) at the socket queue in `0.1 ms` without touching Section 4A (`g7..g3`).
- **Single-Tile Packet Loss Quarantine (`MvCoherentPacket`):** Lost UDP/RTP packets are isolated to individual $16 \times 16$ tiles and motion-compensated from the prior superblock cache instead of smearing across an entire `GoP`.

---

## Testing and Quality Gates

Validated by the automated JUnit 5 verification suite in `:leaf-video` (`./gradlew :leaf-video:test --console=plain -q`):

| Benchmark / Verification Gate | Measured Result | Test Suite |
| :--- | :--- | :--- |
| **IDE / Desktop Capture (Lossless & Compact)** | `493.9x` lossless (`891.6x` `+ Zstd`, `inf dB` PSNR) vs. `155.4x` `h264_nvenc` lossless, `21.7x` `FFV1`, and `400.1x` (`34.46 dB`) OBS Default `h264_nvenc CQP-18` | `LcvBenchmarkAndProofSuiteTest` |
| **Voxel Gameplay + HUD (Lossless & Compact)** | `91.3x` lossless (`875.9x` `+ Zstd`, `inf dB` PSNR) vs. `43.3x` `h264_nvenc` lossless and `261.9x` (`34.07 dB`) OBS Default `h264_nvenc CQP-18` | `LcvBenchmarkAndProofSuiteTest` |
| **3D Gradient + Motion (Lossless & Compact)** | `4.1x` lossless (`219.6x` raw compact / `545.4x` `+ Zstd` at `44.50 dB` PSNR) vs. `2.5x` `h264_nvenc` lossless and `637.2x` (`40.28 dB`) OBS Default `h264_nvenc CQP-18` | `LcvBenchmarkAndProofSuiteTest` |
| **Transparent `RGBA` Overlay (Lossless & Compact)** | `635.2x` raw lossless (`806.6x` `+ Zstd`, `inf dB` full `RGBA`) vs. `41.7x` `FFV1`, `97.5x` `VP9`, and `29.7x` `h264_nvenc` lossless (`NVENC CQP-18` drops alpha: `7.0 dB` `RGBA`) | `LcvBenchmarkAndProofSuiteTest` |
| **Multi-Codec Encode / Decode Throughput** | `.lcv` parallel reaches `8,791.1 / 13,413.8 MB/s` (IDE), `1,597.3 / 9,783.3 MB/s` (Voxel), `229.2 / 943.3 MB/s` (Gradient), and `12,435.5 / 14,020.1 MB/s` (`RGBA` Overlay) vs. `1,147–1,238 MB/s` `h264_nvenc` | `LcvBenchmarkAndProofSuiteTest` |
| **Zero-Decode Sub-Region Crop (`cropSubRegion`)** | `0.257 ms` (`2,384.3x` faster than full decode + crop + re-encode at `613.090 ms`), `100%` bit-exact on both raw and `FLAG_ZSTD_POOL` streams | `LcvStreamAndTransformTest` |
| **`.mkv (V_LCV1)` Crash & Bit-Rot Recovery** | `100 / 100` random `SIGKILL` truncations and `100%` of single-bit pointer flips healed in `< 5 ns` | `LcvRecoveryTortureTest` |

---

## Commands, Configuration, and FAQ

### Reference Codec & Verification CLI Commands

| Action | Command |
| :--- | :--- |
| **Compile & run native C11 Gate 1 verification suite** | `./gradlew :leaf-video:test --console=plain -q` |

### Operating Profile Reference (`tuningProfile16`)

| Profile | `depthK8` ($K$) | `lsbSplitBits` ($s$) | `FLAG_ZSTD_POOL` | Primary Use Case |
| :--- | :---: | :---: | :---: | :--- |
| `ARCHIVAL_LOSSLESS` | `16` | `2` | `1` | Studio master archival & NLE post-production |
| `OBS_REALTIME_LOSSLESS` | `8` | `2` | `0` | Real-time `1440p60`/`4K60` OBS capture & instant multi-crop |
| `WIRELESS_FOVEATED_LOW_LATENCY` | `2` / `4` | `2` | `0` | Low-latency wireless video with 100% lossless crosshair ROI |
| `HARSH_RF_UEP_ZERO_WAIT` | `1` | `3` | `0` | Zero-lookahead (`0 ms` buffer) RF link with Section 4B UEP shedding |

### Frequently Asked Questions

- **Are my `.lcv` and `.mkv (V_LCV1)` recordings locked to a commercial license?**
  No. You retain 100% ownership of all recorded media, and both the `LCV-ICD-1.0` binary specification ([`leaf-video/docs/ICD.md`](../../../../leaf-video/docs/ICD.md)) and the `:leaf-video` reference codec ([`leaf-video/LICENSE`](../../../../leaf-video/LICENSE)) are permanently licensed under the **MIT License**.
- **Does `LCV-ICD-1.0` require any patent-pool royalties?**
  No. All underlying mathematical primitives (1909 Haar lifting, 1891/1993 Hilbert curves, 1950 Hamming `SEC-DED`, 1966 Reflected Gray Code, 2003 `YCoCg-R`, 2016 Roaring containers) are open, expired foundational algorithms.
