# LeafVideo — Single-Encode Multi-Crop, Instant NLE Scrub & Crash-Proof `.lcv` Video Suite

Record one lightweight `.lcv` / `.mkv` (`V_LCV1`) stream and extract 9:16 vertical shorts in `0.9 ms` (`160x-735x` faster than re-encoding), strip proxy previews in `0.1 ms`, scrub `RGBA` overlays at `240-402 MB/s`, and survive any OBS, NLE, or wireless link failure.

- **Open Standard & Reference Codec (`MIT`):** [`LCV-ICD-1.0` (`leaf-video/docs/ICD.md`)](../../../../leaf-video/docs/ICD.md) and [`:leaf-video` (`leaf-video/`)](../../../../leaf-video/README.md) are 100% Free & Open Source (**MIT License**) with zero vendor lock-in and zero patent-pool royalties.
- **GitHub Repository:** [`https://github.com/LeafCraftDev/Video`](https://github.com/LeafCraftDev/Video)

---

## Common Setups & Product Tiers

| Tier | Product | License / Model | Target Workflow |
| :--- | :--- | :--- | :--- |
| **Tier 1: Open Standard & Core Reference** | `LCV-ICD-1.0` + `:leaf-video` Java 17+ reference codec & CLI | **Free (`MIT`)** | Open `.lcv` / `.mkv` (`V_LCV1`) encoding, decoding, on-disk cropping, LSB stripping, and third-party integration |
| **Tier 2A: OBS Creator Studio** | **OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite** | **Dual Track (`MIT` Core / Pro GUI)** | Single-encode 4K canvas capture with 1-click 9:16 vertical crop, facecam isolation, proxy strip, and crash recovery |
| **Tier 2B: NLE Post-Production** | **NLE Instant-Scrub & Crash-Resumable Render Plugin** | **Dual Track (`MIT` Core / Pro Plugin)** | DaVinci Resolve, Premiere Pro, After Effects, and Blender native `.lcv` timeline reader and crash-resumable exporter |
| **Tier 3: Native & Wireless SDK** | **Native C++20 SIMD/GPU & `HARSH_RF_UEP` Wireless Telemetry SDK** | **Commercial (`LicenseRef-LeafCraft-Commercial`)** | `AVX2`/`NEON`/`Vulkan`/`CUDA` encoder and zero-lookahead ($K=1$) foveated UEP link daemon for FPV drones & robotics |

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
| **IDE / Desktop Capture Lossless Compression** | `493.9x` raw (`891.6x` with `Zstd`, `402.0 MB/s` decode) vs. `21.7x` `FFV1` and `7.8x` `UtVideo` | `LcvBenchmarkAndProofSuiteTest` |
| **Voxel Gameplay + HUD Lossless Compression** | `92.0x` raw (`828.8x` with `Zstd`, `178.0 MB/s` decode) vs. `15.7x` `FFV1` and `35.9x` `x264rgb -qp 0` | `LcvBenchmarkAndProofSuiteTest` |
| **3D Gradient + Motion Lossless Compression** | `4.4x` raw (`4.5x` with `Zstd`, `12.7x` foveated) vs. `3.4x` `FFV1`, `3.6x` `x265`, and `2.8x` `UtVideo` | `LcvBenchmarkAndProofSuiteTest` |
| **Transparent `RGBA` Overlay Compression** | `192.1x` lossless (`0.52%` of raw; `0.29%` stripped; `244.0 MB/s` decode) vs. `41.7x` `FFV1` | `LcvBenchmarkAndProofSuiteTest` |
| **Zero-Decode Sub-Region Crop (`cropSubRegion`)** | `1.182 ms` (`159.9x` faster than full decode + crop + re-encode at `188.950 ms`), `100%` bit-exact | `LcvStreamAndTransformTest` |
| **`.mkv (V_LCV1)` Crash & Bit-Rot Recovery** | `100 / 100` random `SIGKILL` truncations and `100%` of single-bit pointer flips healed in `< 5 ns` | `LcvRecoveryTortureTest` |

---

## Commands, Configuration, and FAQ

### Reference Codec & Benchmark CLI Commands

| Action | Command |
| :--- | :--- |
| **Run full `:leaf-video` verification suite** | `./gradlew :leaf-video:test --console=plain -q` |
| **Run multi-codec comparison & proof table** | `./gradlew :leaf-video:test --tests "*LcvBenchmarkAndProofSuiteTest*" --console=plain -q` |
| **Run `.mkv` truncation & bit-rot torture tests** | `./gradlew :leaf-video:test --tests "*LcvRecoveryTortureTest*" --console=plain -q` |

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
