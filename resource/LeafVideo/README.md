# LeafVideo (`.lcv` / `V_LCV1` Open Video Specification & Creator / Wireless Workflow Suite)

**LeafVideo** is a 3D Spatiotemporal Gray-Hilbert & Adaptive Roaring video ecosystem built on the open, royalty-free **[`LCV-ICD-1.0` (`leaf-video/docs/ICD.md`)](../../leaf-video/docs/ICD.md)** specification (**MIT License**), paired with the canonical native C11/SIMD/GPU codec, OBS/NLE studio plugins, and low-latency wireless UEP SDKs (**`resource/LeafVideo/native/`**).

---

## 1. Why the Spec (`LCV-ICD-1.0`) Is 100% Free (`MIT`)

When a streamer, video editor, or robotics engineer chooses a recording or streaming format, their #1 operational risk is **file lock-in**:
> *"If I record 500 GB of footage in `.lcv` / `.mkv` (`V_LCV1`), can `ffmpeg`, VLC, my teammates, or my future self open it in 5 years without a license server?"*

Keeping the core binary format specification 100% Free & Open (**MIT**, [`leaf-video/LICENSE`](../../leaf-video/LICENSE)) guarantees three things:
1. **Eliminates Lock-In Anxiety:** Anyone can inspect [`leaf-video/docs/ICD.md`](../../leaf-video/docs/ICD.md) and implement conformant `.lcv` / `.mkv` (`V_LCV1`) decoders, demuxers, and compressed-domain transformers for free forever.
2. **Zero Patent / Royalty Friction:** Unlike HEVC (`MPEG-LA` royalty pools), `LCV-ICD-1.0` is built strictly on expired foundational mathematics: 1950 Extended Hamming `(72, 64)` `SEC-DED`, 1966 Reflected Binary Gray Code, 1891/1993 Hilbert space-filling curves, 2003 reversible `YCoCg-R` lifting, 1909 Haar wavelet lifting, and 2016 adaptive Roaring containers.
3. **Universal Interoperability Ecosystem:** Every `.lcv` / `V_LCV1` file is immediately compatible with the creator cropping, NLE timeline scrubbing, and wireless UEP tools below.

---

## 2. Product Suite Overview (Where `.lcv` Delivers Workflow Superpowers)

`.lcv` (`LCV-ICD-1.0`) enables three concrete workflow products that have no equivalent in `FFV1`, `UtVideo`, `ProRes`, or `x264`/`x265`:

| Marketable Product | Target Customer | The Pain Point It Solves | Why `.lcv` Makes It Uniquely Possible |
| :--- | :--- | :--- | :--- |
| **1. OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite** | Streamers, VTubers, YouTubers, Tutorial / IDE Creators (`$19–$39` one-time or Patreon / BuiltByBit Pro) | Today, creators use OBS `Source Record` to simultaneously record a 4K canvas, a 9:16 vertical TikTok/Shorts crop, a facecam isolation, and an `RGBA` VTuber overlay—running 3 to 4 separate encoders at once, tanking game FPS, and losing the whole `.mp4` on an OBS crash. | Record one lightweight `.lcv` stream (`OBS_REALTIME_LOSSLESS` via `obs_leafvideo_plugin.c`). After the stream (or live in the background), click one button to extract the 9:16 vertical short (`lcv_native_crop_or_strip_superblock` in `< 1 ms`), strip a tiny proxy (`stripLsb == 1` in `< 0.2 ms`), and survive any PC crash (`SEC-DED` + `LCV_FLAG_INLINE_RECODE_PARTIAL`). |
| **2. NLE Instant-Scrub & Crash-Resumable Render Plugin (DaVinci Resolve / Premiere / After Effects / Blender)** | Video Editors, VFX Compositors, Motion Designers (`$49–$99` studio plugin, similar to AfterCodecs or BRAW Studio) | ProRes 4444 / UtVideo `RGBA` files are massive (`15–40%` of raw), `FFV1` scrubs too slowly on the timeline, and a crash at 95% during a 4-hour NLE export ruins the output file. | `.lcv` compresses `RGBA` overlays down to `0.52%` lossless (`0.29%` stripped), decodes single viewport tiles or `Section 4A` 8-bit proxies (`lcv_native_decode_superblock_rgba8_preview`) in $O(1)$, and supports mid-recode crash checkpoints (`LCV_FLAG_INLINE_RECODE_PARTIAL`). |
| **3. Wireless Live Video & Foveated UEP Telemetry SDK** | FPV Drone Builders, Robotics / Rover Teleoperation, VR Headsets, Field Broadcast (B2B Commercial License / SDK SLA) | Standard H.264/HEVC links smear into unflyable grey sludge on packet loss, suffer bufferbloat during RF fade, and blur the center crosshair when bitrate drops. | `WIRELESS_FOVEATED_LOW_LATENCY`, `HARSH_RF_UEP_ZERO_WAIT` ($K=1$, `0 ms` lookahead), and `VR_STEREO_FOVEATED_DIRECT` keep the focal ROI 100% lossless (`PSNR = infinity`), shed Section 4B (`lcv_native_strip_lsb_outside_radius`) in `< 0.1 ms`, and quarantine lost `LCVU` UDP datagrams (`lcv_native_decode_lcvu_udp_rgba`) to single $16 \times 16$ tiles. |

---

## 3. Three-Tier Licensing & Product Split ([`leaf-video-ADR-002`](../../leaf-video/docs/adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md))

Following [`docs/dev/LICENSING.md`](../../docs/dev/LICENSING.md) (`ADR-108` & `ADR-126`), `LeafVideo` separates the open specification from commercial workflow binaries and native hardware SDKs:

| Tier | Component | License / Model | Purpose |
| :--- | :--- | :--- | :--- |
| **Tier 1: Open Standard & Core Specification ([`leaf-video/`](../../leaf-video/README.md))** | - `LCV-ICD-1.0` ([`leaf-video/docs/ICD.md`](../../leaf-video/docs/ICD.md))<br>- [`leaf-video-ADR-001`](../../leaf-video/docs/adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md) .. [`leaf-video-ADR-004`](../../leaf-video/docs/adr/leaf-video-ADR-004-bitwise-simd-and-non-video-hardware-accelerator-offload.md) | **Free & Open Source (`MIT`)** ([`leaf-video/LICENSE`](../../leaf-video/LICENSE)) | Guarantees zero vendor lock-in, deterministic decoder interoperability, academic/developer trust, and community contributions. |
| **Tier 2: Creator & Editor Binaries (`resource/LeafVideo/` — OBS / NLE GUI Addons)** | - Turnkey OBS Studio Plugin (`obs_leafvideo_plugin.c`: 1-click 9:16 visual crop selector, auto-proxy stripper, crash-recovery doctor, background `.mp4` exporter)<br>- DaVinci Resolve / Premiere / Blender native `.lcv` media reader (`lcv_codec_system_plugins.c`) | **Dual Track (`ADR-108` Style or Commercial Binary):**<br>- Format spec (`LCV-ICD-1.0`) remains free `MIT`<br>- Pre-built GUI Studio Installer + Auto-Updater + Priority Support sold as Pro (`$15–$99`) | Monetizes convenience, UI polish, and creator workflow automation without crippling the open format. |
| **Tier 3: High-Performance Native / Hardware / B2B SDK (`resource/LeafVideo/native/`)** | - Zero-copy C11 + `AVX2` / `NEON` / GPU Compute (`Vulkan` `lcv_cube_kernel.comp` & `CUDA PTX` in `lcv_accel.c`) encoder/decoder<br>- Embedded `HARSH_RF_UEP` & `VR_STEREO_FOVEATED_DIRECT` `LCVU` UDP transmitter/receiver link SDK | **Commercial License (`LicenseRef-LeafCraft-Commercial`)** ([`LICENSE`](LICENSE), mirroring `resource/LeafAnvil`) | Monetizes commercial hardware, VR headset streaming, drone/robotics OEM integration, and enterprise broadcast deployments. |

---

## 4. Normative Operating Profiles & Empirical Benchmarks ([`leaf-video-ADR-001`](../../leaf-video/docs/adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md))

### 4.1 Operating Profiles (`LCV-ICD-1.0` Section 7.2)

| Profile Name | `depthK8` ($K$) | `lsbSplitBits` ($s$) | `(p_cutoff, g_base, alpha)` | $M_{\text{refresh}}$ | `FLAG_ZSTD_POOL` | Target Application |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **`ARCHIVAL_LOSSLESS`** | `16` | `2` (`g1..g0` in 4B) | `(0, 0, 0.00)` | `16` | `1` (Enabled) | Studio archive, desktop/IDE master recording, NLE post-production |
| **`OBS_REALTIME_LOSSLESS`** | `8` | `2` (`g1..g0` in 4B) | `(0, 0, 0.00)` | `8` | `0` (Raw fast) | Real-time `1440p60`/`4K60` OBS capture with `< 1.2 ms` on-disk crop/strip |
| **`WIRELESS_FOVEATED_LOW_LATENCY`** | `2` or `4` | `2` (`g1..g0` in 4B) | `(4, 0, 1.25)` | `2` | `0` (Raw MTU) | FPV drone, robotics teleoperation, wireless monitor (`100%` lossless crosshair ROI) |
| **`HARSH_RF_UEP_ZERO_WAIT`** | `1` | `3` (`g2..g0` in 4B) | `(4, 1, 2.00)` | `1` | `0` (Raw MTU) | Zero-lookahead (`0 ms` buffer) contested RF link with `UEP` Section 4B queue shedding |

### 4.2 Lossless Compression Ratio Comparison (`640x368`, 16 Frames, `15,073,280 B` Raw — `100%` Lossless)

| Workload | `UtVideo` | `FFV1 (lvl 3)` | `x265 Lossless` | `x264rgb (-qp 0)` | `h264_nvenc` Lossless | `hevc_nvenc` Lossless | **`.lcv` (`OBS_REALTIME`)** | **`.lcv + Zstd` (`ARCHIVAL`)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **1. IDE / Desktop Screen Capture** | `7.8x` | `21.7x` | `212.6x` | `261.9x` | `155.4x` | `244.9x` | **`493.9x` (`30,520 B`)** | **`891.6x` (`16,906 B`)** |
| **2. Voxel Gameplay + Static HUD** | `6.8x` | `15.7x` | `35.1x` | `35.9x` | `43.3x` | `79.5x` | **`228.3x` (`66,020 B`)** | **`878.3x` (`17,162 B`)** |
| **3. 3D Continuous Gradient + Motion** | `2.8x` | `3.4x` | `3.6x` | `3.0x` | `2.5x` | `3.1x` | **`4.1x` (`3,650,160 B`)** | **`4.1x` (`3,639,522 B`)** |
| **4. Transparent `RGBA` VTuber Overlay** | `7.5x` | `41.7x` | `50.8x` | `46.8x` | `29.7x` | `38.7x` | **`635.5x` (`23,720 B`)** | **`806.4x` (`18,691 B`)** |

### 4.3 Equivalent Lossy / Compact Recording Comparison (OBS Default `NVENC CQP-18` & `x265 -crf 12` vs. `.lcv` Compact & Foveated, with `RGBA` PSNR)

| Workload | OBS Default `h264_nvenc` (`P5 CQP-18 4:2:0`) | OBS `hevc_nvenc` (`P5 CQP-18 4:2:0`) | `x265 -crf 12` (`gbrp`) | **`.lcv` Foveated (`d_merge`, `K=8`)** | **`.lcv` Compact (`s=3` Stripped Raw)** | **`.lcv` Compact + `Zstd` (`s=3` Stripped)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| **1. IDE / Desktop Screen Capture** | `400.1x` (`37,675 B`, `34.46 dB`) | `518.8x` (`29,052 B`, `34.39 dB`) | `261.7x` (`57,608 B`, `51.24 dB`) | **`493.9x` (`30,520 B`, `inf`)** | **`493.9x` (`30,520 B`, `inf`)** | **`891.6x` (`16,906 B`, `inf`)** |
| **2. Voxel Gameplay + Static HUD** | `261.9x` (`57,543 B`, `34.07 dB`) | `402.9x` (`37,413 B`, `34.03 dB`) | `212.6x` (`70,913 B`, `49.31 dB`) | **`228.3x` (`66,020 B`, `inf`)** | **`228.3x` (`66,020 B`, `inf`)** | **`878.3x` (`17,162 B`, `inf`)** |
| **3. 3D Continuous Gradient + Motion** | `637.2x` (`23,655 B`, `40.28 dB`) | `469.3x` (`32,120 B`, `42.37 dB`) | `916.5x` (`16,447 B`, `45.61 dB`) | **`11.4x` (`1,327,912 B`, `44.47 dB`)** | **`233.5x` (`64,544 B`, `44.46 dB`)** | **`619.8x` (`24,321 B`, `44.46 dB`)** |
| **4. Transparent `RGBA` VTuber Overlay** | `923.0x` (`16,331 B`, `7.0 dB` RGBA / `42.7 dB` RGB, drops alpha) | `936.9x` (`16,088 B`, `7.0 dB` RGBA / `42.7 dB` RGB, drops alpha) | `220.1x` (`68,486 B`, `7.0 dB` RGBA / `47.4 dB` RGB, drops alpha) | **`635.5x` (`23,720 B`, `inf`)** | **`635.5x` (`23,720 B`, `inf`)** | **`806.3x` (`18,694 B`, `inf`)** |

### 4.4 Multi-Codec Encode & Decode Throughput Comparison (`MB/s` — `Encode / Decode`)

| Workload | `UtVideo` (`RGBA`) | `FFV1 (lvl 3)` | `x265` Lossless | `x264rgb (-qp 0)` | `h264_nvenc` Lossless (GPU) | `hevc_nvenc` Lossless (GPU) | OBS `h264_nvenc` `CQP-18` (GPU) | **`.lcv` `1T` (Lossless)** | **`.lcv` Parallel (Lossless)** | **`.lcv` Parallel (Compact `s=3`)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **1. IDE / Desktop Screen Capture** | `1242.5 / 1854.1` | `645.2 / 1784.8` | `980.5 / 2065.6` | `1121.9 / 1970.5` | `1604.0 / 1850.6` | `1345.0 / 2025.6` | `1464.2 / 2048.0` | `1656.8 / 3000.0` | **`8686.9 / 13763.6 MB/s`** | **`8784.4 / 14412.5 MB/s`** |
| **2. Voxel Gameplay + Static HUD** | `1270.6 / 1981.2` | `573.5 / 1753.2` | `291.6 / 1981.7` | `1142.3 / 2187.1` | `1585.0 / 2224.4` | `1518.0 / 2014.7` | `1373.3 / 1600.4` | `473.4 / 2888.1` | **`3968.0 / 10431.7 MB/s`** | **`3372.7 / 11149.2 MB/s`** |
| **3. 3D Continuous Gradient + Motion** | `1198.7 / 1586.7` | `298.5 / 463.6` | `52.1 / 511.0` | `1214.3 / 1625.4` | `1196.6 / 845.9` | `647.1 / 121.3` | `1357.2 / 2040.8` | `24.6 / 66.8` | **`311.2 / 750.6 MB/s`** | **`249.8 / 163.8 MB/s`** |
| **4. Transparent `RGBA` VTuber Overlay** | `1310.0 / 1917.3` | `592.2 / 1709.6` | `699.9 / 2060.7` | `1303.1 / 1996.7` | `1473.0 / 2137.3` | `1609.0 / 1122.8` | `1419.1 / 1921.6` | `1620.1 / 2496.4` | **`9459.6 / 12529.7 MB/s`** | **`10220.4 / 13330.0 MB/s`** |

### 4.5A Real Uncompressed Content Benchmark Corpus — `100%` Lossless Compression Comparison (CPU & GPU `NVENC` Lossless vs. `.lcv` Live & Post-Processed Lossless, `inf dB`)

| Clip ID & Uncompressed Source (`640x368`, 16f) | `UtVideo` | `FFV1 (lvl 3)` | `x265 Lossless` | `x264rgb (-qp 0)` | `h264_nvenc` Lossless (`gbrp`) | `hevc_nvenc` Lossless (`gbrp`) | **`.lcv` Live (`OBS_REALTIME`)** | **`.lcv` Post-Processed (`wrapZstdPools`)** | **`.lcv` Parallel (`Enc / Dec MB/s`)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **VOD-1: Native IDE / Terminal Scroll (`RGBA`)** | `5.2x` (`2,924,290 B`) | `12.6x` (`1,192,540 B`) | `85.4x` (`176,593 B`) | `75.0x` (`201,087 B`) | `65.4x` (`230,638 B`) | `91.0x` (`165,691 B`) | **`73.1x` (`206,231 B`, `inf`)** | **`112.6x` (`133,827 B`, `inf`)** | **`109.6 / 2126.5 MB/s`** |
| **VOD-2A: Xiph Derf `MINECRAFT` (`QT RGB24` Native 1:1 Crop)** | `3.3x` (`4,552,502 B`) | `4.2x` (`3,552,336 B`) | `3.7x` (`4,111,822 B`) | `4.0x` (`3,727,100 B`) | `3.9x` (`3,821,938 B`) | `4.3x` (`3,489,626 B`) | **`3.2x` (`4,673,977 B`, `inf`)** | **`3.9x` (`3,837,772 B`, `inf`)** | **`65.4 / 418.9 MB/s`** |
| **VOD-2B: Xiph Derf `MINECRAFT` (`QT RGB24` Non-Integer Scaled)** | `2.5x` (`6,101,850 B`) | `3.5x` (`4,367,204 B`) | `3.1x` (`4,794,436 B`) | `2.9x` (`5,177,505 B`) | `3.2x` (`4,744,193 B`) | `3.4x` (`4,394,057 B`) | **`2.8x` (`5,414,459 B`, `inf`)** | **`3.0x` (`4,963,336 B`, `inf`)** | **`70.7 / 249.1 MB/s`** |
| **VOD-3: Xiph Derf `park_joy` (`4:4:4`)** | `1.8x` (`8,303,342 B`) | `2.2x` (`6,875,343 B`) | `2.2x` (`6,800,557 B`) | `1.9x` (`7,910,046 B`) | `2.0x` (`7,588,127 B`) | `2.3x` (`6,644,179 B`) | **`2.0x` (`7,657,758 B`, `inf`)** | **`2.0x` (`7,454,504 B`, `inf`)** | **`76.2 / 324.6 MB/s`** |
| **VOD-4: `ffmpeg lavfi` VTuber/HUD Overlay (`RGBA`)** | `7.4x` (`2,027,826 B`) | `123.5x` (`122,011 B`) | `195.0x` (`77,311 B`) | `247.1x` (`61,013 B`) | `312.2x` (`48,274 B`) | `240.2x` (`62,747 B`) | **`74.4x` (`202,673 B`, `inf`)** | **`110.7x` (`136,178 B`, `inf`)** | **`506.2 / 2652.5 MB/s`** |
| **VOD-5: Xiph Derf `KristenAndSara` (`720p60` ROI)** | `3.0x` (`5,048,366 B`) | `5.3x` (`2,836,220 B`) | `3.1x` (`4,796,416 B`) | `2.9x` (`5,203,621 B`) | `2.9x` (`5,221,921 B`) | `3.1x` (`4,898,517 B`) | **`3.1x` (`4,822,378 B`, `inf`)** | **`3.6x` (`4,180,216 B`, `inf`)** | **`71.9 / 453.1 MB/s`** |

### 4.5B Real Uncompressed Content Benchmark Corpus — Equivalent Lossy / Compact Post-Processed Comparison (OBS Default `NVENC CQP-18` & `x265 -crf 12` vs. `.lcv` Post-Processed Compact & Foveated, with `RGBA` PSNR)

| Clip ID & Uncompressed Source (`640x368`, 16f) | OBS Default `h264_nvenc` (`P5 CQP-18 4:2:0`) | OBS `hevc_nvenc` (`P5 CQP-18 4:2:0`) | `x265 -crf 12` (`gbrp`) | **`.lcv` Post-Processed Compact (`s=3` + Zstd)** | **`.lcv` Post-Processed Foveated (`d_merge` + `s=3` + Zstd)** | **Post-Process Ratio Gain (vs. Live `.lcv`)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| **VOD-1: Native IDE / Terminal Scroll (`RGBA`)** | `298.2x` (`50,550 B`, `37.51 dB`) | `241.5x` (`62,419 B`, `37.48 dB`) | `78.9x` (`191,123 B`, `44.22 dB`) | **`233.8x` (`64,458 B`, `40.97 dB`)** | **`255.9x` (`58,912 B`, `34.01 dB`)** | **`+470.3%` (`5.70x`)** |
| **VOD-2A: Xiph Derf `MINECRAFT` (`QT RGB24` Native 1:1 Crop)** | `52.0x` (`289,808 B`, `39.10 dB`) | `53.4x` (`282,033 B`, `39.08 dB`) | `24.5x` (`616,224 B`, `40.23 dB`) | **`30.7x` (`491,469 B`, `36.64 dB`)** | **`61.8x` (`244,061 B`, `29.48 dB`)** | **`+1858.1%` (`19.58x`)** |
| **VOD-2B: Xiph Derf `MINECRAFT` (`QT RGB24` Non-Integer Scaled)** | `39.7x` (`379,664 B`, `34.50 dB`) | `41.9x` (`359,969 B`, `34.54 dB`) | `19.4x` (`777,079 B`, `38.78 dB`) | **`13.9x` (`1,080,542 B`, `34.22 dB`)** | **`40.3x` (`374,246 B`, `27.90 dB`)** | **`+1413.9%` (`15.14x`)** |
| **VOD-3: Xiph Derf `park_joy` (`4:4:4`)** | `27.1x` (`557,174 B`, `32.58 dB`) | `28.7x` (`525,354 B`, `32.57 dB`) | `12.6x` (`1,200,472 B`, `37.25 dB`) | **`6.7x` (`2,233,810 B`, `32.26 dB`)** | **`13.1x` (`1,149,497 B`, `27.15 dB`)** | **`+584.4%` (`6.84x`)** |
| **VOD-4: `ffmpeg lavfi` VTuber/HUD Overlay (`RGBA`)** | `1119.9x` (`13,459 B`, `6.1 dB` RGBA / `44.6 dB` RGB, drops alpha) | `1094.8x` (`13,768 B`, `6.1 dB` RGBA / `44.8 dB` RGB, drops alpha) | `166.7x` (`90,402 B`, `6.1 dB` RGBA / `48.8 dB` RGB, drops alpha) | **`224.8x` (`67,059 B`, `50.38 dB`)** | **`232.9x` (`64,727 B`, `49.45 dB`)** | **`+226.9%` (`3.27x`)** |
| **VOD-5: Xiph Derf `KristenAndSara` (`720p60` ROI)** | `99.2x` (`151,923 B`, `40.72 dB`) | `112.2x` (`134,310 B`, `40.67 dB`) | `59.1x` (`255,009 B`, `40.92 dB`) | **`46.4x` (`324,871 B`, `33.20 dB`)** | **`122.0x` (`123,577 B`, `29.22 dB`)** | **`+3992.4%` (`40.92x`)** |
### 4.6 Zero-Lock-In Recorder & NLE Editor Compatibility (`lcv_cli_and_test.c` & `obs_leafvideo_plugin.c`)

- **Any Default Recording Software (`ingest-dir` & `obs_leafvideo_plugin.c`):** Works directly inside OBS Studio (`libobs v30` native plugin) and alongside Streamlabs Desktop, Meld Studio, NVIDIA ShadowPlay, AMD ReLive, and Windows Game Bar.
- **Any Default Editing Software (`export-carrier`):**
  - **DaVinci Resolve / Premiere Pro / After Effects / Final Cut Pro:** `--carrier prores_4444` (`.mov` 10-bit 4:4:4:4 with alpha) or `--carrier prores_422hq` (`.mov` mezzanine) or `--carrier qtrle` (`.mov` 8-bit `ARGB`).
  - **CapCut / Vegas Pro / Universal Fast Timeline:** `--carrier capcut` / `mp4` (`H.264` `.mp4`) or `--carrier utvideo` (`.mkv` lossless `RGBA`).
  - **Blender VSE / Kdenlive / Shotcut / FFmpeg:** `blender_vse_leafvideo.py` addon, `--carrier ffv1` (`.mkv`), or direct `decode-raw` pipe.

---

## 5. Build & Verification (`:leaf-video` Native C11 Gate 1 Suite)

```bash
# Compile resource/LeafVideo/native/{lcv_accel.c,lcv_codec_system_plugins.c,lcv_cli_and_test.c}
# and run the canonical native C11 Gate 1 verification suite (--self-test)
./gradlew :leaf-video:test --console=plain -q
```

- [**`.lcv` / `V_LCV1` Binary Interface Control Document (`leaf-video/docs/ICD.md`, MIT)**](../../leaf-video/docs/ICD.md) *(single canonical specification, routed from [`docs/ICD.md`](docs/ICD.md) and packaged in `leaf-video.jar` under `META-INF/leaf-video/docs/ICD.md`)*
- [**`:leaf-video` Specification & Verification Guide (`leaf-video/README.md`, MIT)**](../../leaf-video/README.md)
- [**`leaf-video-ADR-001`: Spatiotemporal Gray-Hilbert Video Codec & `.mkv` Transform**](../../leaf-video/docs/adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md)
- [**`leaf-video-ADR-002`: Three-Tier Open Standard (`MIT`) and Commercial Product Architecture**](../../leaf-video/docs/adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md)
- [**Product Suite & Storefront Overview (`FRONT_PAGE.md`)**](docs/publishing/FRONT_PAGE.md)
- [**Architecture Decision Records Index (`docs/adr/README.md`)**](docs/adr/README.md)
- [**Changelog (`CHANGELOG.md`)**](CHANGELOG.md)

---

## 7. Licensing (`LeafVideo` Commercial Workflow Suite & Open `.lcv` Format)

- **Tier 1 Open `.lcv` / `V_LCV1` Format Specification (`MIT`):** The `.lcv` binary format Interface Control Document ([`leaf-video/docs/ICD.md`](../../leaf-video/docs/ICD.md), routed from [`docs/ICD.md`](docs/ICD.md), `LCV-ICD-1.0`) and [`leaf-video/`](../../leaf-video/README.md) are licensed under the **[MIT License](../../leaf-video/LICENSE)**. Anyone may freely inspect, implement, embed, or interoperate with `.lcv` / `V_LCV1` streams without royalty or lock-in.
- **Tier 2 Studio GUI Binaries & Tier 3 Native SIMD/GPU & Wireless UEP SDK (`resource/LeafVideo/`):** Licensed under the **[LeafCraft Commercial Software License (`LicenseRef-LeafCraft-Commercial`)](LICENSE)**, with an explicit Section 3 carve-out guaranteeing 100% user ownership of all recorded media and unrestricted MIT interoperability with `LCV-ICD-1.0`.
