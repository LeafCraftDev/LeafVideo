# LeafVideo (`.lcv` / `V_LCV1` Open Video Codec & Creator / Wireless Workflow Suite)

**LeafVideo** is a 3D Spatiotemporal Gray-Hilbert & Adaptive Roaring video ecosystem built on the open, royalty-free **[`LCV-ICD-1.0` (`leaf-video/docs/ICD.md`)](leaf-video/docs/ICD.md)** specification and the zero-dependency Java 17+ **[`:leaf-video` (`leaf-video/`)](leaf-video/README.md)** reference codec (**MIT License**), paired with commercial creator/NLE workflow plugins and low-latency wireless UEP SDKs (**`resource/LeafVideo/`**).

---

## 1. Why the Spec (`LCV-ICD-1.0`) and Base Codec (`:leaf-video`) Are 100% Free (`MIT`)

When a streamer, video editor, or robotics engineer chooses a recording or streaming format, their #1 operational risk is **file lock-in**:
> *"If I record 500 GB of footage in `.lcv` / `.mkv` (`V_LCV1`), can `ffmpeg`, VLC, my teammates, or my future self open it in 5 years without a license server?"*

Keeping the core format and reference library 100% Free & Open (**MIT**, [`leaf-video/LICENSE`](leaf-video/LICENSE)) guarantees three things:
1. **Eliminates Lock-In Anxiety:** Anyone can inspect [`leaf-video/docs/ICD.md`](leaf-video/docs/ICD.md), compile [`:leaf-video`](leaf-video/README.md), or demux `.lcv` / `.mkv` (`V_LCV1`) streams for free forever.
2. **Zero Patent / Royalty Friction:** Unlike HEVC (`MPEG-LA` royalty pools), `LCV-ICD-1.0` is built strictly on expired foundational mathematics: 1950 Extended Hamming `(72, 64)` `SEC-DED`, 1966 Reflected Binary Gray Code, 1891/1993 Hilbert space-filling curves, 2003 reversible `YCoCg-R` lifting, 1909 Haar wavelet lifting, and 2016 adaptive Roaring containers.
3. **Universal Interoperability Ecosystem:** Every `.lcv` / `V_LCV1` file recorded or shared with the free MIT codec is immediately compatible with the creator cropping, NLE timeline scrubbing, and wireless UEP tools below.

---

## 2. Product Suite Overview (Where `.lcv` Delivers Workflow Superpowers)

`.lcv` (`LCV-ICD-1.0`) enables three concrete workflow products that have no equivalent in `FFV1`, `UtVideo`, `ProRes`, or `x264`/`x265`:

| Marketable Product | Target Customer | The Pain Point It Solves | Why `.lcv` Makes It Uniquely Possible |
| :--- | :--- | :--- | :--- |
| **1. OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite** | Streamers, VTubers, YouTubers, Tutorial / IDE Creators (`$19–$39` one-time or Patreon / BuiltByBit Pro) | Today, creators use OBS `Source Record` to simultaneously record a 4K canvas, a 9:16 vertical TikTok/Shorts crop, a facecam isolation, and an `RGBA` VTuber overlay—running 3 to 4 separate encoders at once, tanking game FPS, and losing the whole `.mp4` on an OBS crash. | Record one lightweight `.lcv` stream (`OBS_REALTIME_LOSSLESS`). After the stream (or live in the background), click one button to extract the 9:16 vertical short (`LcvOnDiskTransformer#cropSubRegion` in `0.9–1.2 ms`, `160x–735x` faster than re-encoding), strip a tiny proxy (`stripLsbPlanes` in `0.1–1.1 ms`), and survive any PC crash (`SEC-DED` + `FLAG_INLINE_RECODE_PARTIAL`). |
| **2. NLE Instant-Scrub & Crash-Resumable Render Plugin (DaVinci Resolve / Premiere / After Effects / Blender)** | Video Editors, VFX Compositors, Motion Designers (`$49–$99` studio plugin, similar to AfterCodecs or BRAW Studio) | ProRes 4444 / UtVideo `RGBA` files are massive (`15–40%` of raw), `FFV1` scrubs too slowly on the timeline, and a crash at 95% during a 4-hour NLE export ruins the output file. | `.lcv` compresses `RGBA` overlays down to `0.52%` lossless (`0.29%` stripped), decodes single viewport tiles in $O(1)$ at `240–402 MB/s`, and supports mid-recode crash resumption (`LcvOnDiskTransformer#resumeInlineRecode`) without restarting the render. |
| **3. Wireless Live Video & Foveated UEP Telemetry SDK** | FPV Drone Builders, Robotics / Rover Teleoperation, Field Broadcast (B2B Commercial License / SDK SLA) | Standard H.264/HEVC links smear into unflyable grey sludge on packet loss, suffer bufferbloat during RF fade, and blur the center crosshair when bitrate drops. | `WIRELESS_FOVEATED_LOW_LATENCY` and `HARSH_RF_UEP_ZERO_WAIT` ($K=1$, `0 ms` lookahead) keep the center crosshair 100% lossless (`PSNR = infinity`), shed Section 4B (`g2..g0`) at the radio queue in `0.1 ms`, and quarantine lost packets to single $16 \times 16$ tiles (`LcvStreamContainer.MvCoherentPacket`). |

---

## 3. Three-Tier Licensing & Product Split ([`leaf-video-ADR-002`](leaf-video/docs/adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md))

Following [`docs/dev/LICENSING.md`](docs/dev/LICENSING.md) (`ADR-108` & `ADR-126`), `LeafVideo` separates the open specification and reference library from commercial workflow binaries and native hardware SDKs:

| Tier | Component | License / Model | Purpose |
| :--- | :--- | :--- | :--- |
| **Tier 1: Open Standard & Core Reference ([`leaf-video/`](leaf-video/README.md))** | - `LCV-ICD-1.0` ([`leaf-video/docs/ICD.md`](leaf-video/docs/ICD.md))<br>- [`leaf-video-ADR-001`](leaf-video/docs/adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md) & [`leaf-video-ADR-002`](leaf-video/docs/adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md)<br>- `:leaf-video` Java 17+ reference codec (`LcvPrimitives`, `LcvCubeCodec`, `LcvStreamContainer`, `LcvMkvPacketizer`, `LcvOnDiskTransformer`) | **Free & Open Source (`MIT`)** ([`leaf-video/LICENSE`](leaf-video/LICENSE)) | Guarantees zero vendor lock-in, universal playback, academic/developer trust, and community contributions. |
| **Tier 2: Creator & Editor Binaries (`resource/LeafVideo/` — OBS / NLE GUI Addons)** | - Turnkey OBS Studio Dock/Plugin (1-click 9:16 visual crop selector, auto-proxy stripper, crash-recovery doctor, background `.mp4` exporter)<br>- DaVinci Resolve / Premiere native `.lcv` media reader & resumable exporter | **Dual Track (`ADR-108` Style or Commercial Binary):**<br>- CLI / Core remains free `MIT`<br>- Pre-built GUI Studio Installer + Auto-Updater + Priority Support sold as Pro (`$15–$99`) | Monetizes convenience, UI polish, and creator workflow automation without crippling the open format. |
| **Tier 3: High-Performance Native / Hardware / B2B SDK (`resource/LeafVideo/`)** | - Zero-copy `AVX2` / `NEON` / GPU Compute (`Vulkan` / `CUDA`) C++20 encoder<br>- Embedded `HARSH_RF_UEP` wireless transmitter/receiver link daemon with FEC/UEP socket scheduling | **Commercial License (`LicenseRef-LeafCraft-Commercial`)** ([`LICENSE`](resource/LeafVideo/LICENSE), mirroring `resource/LeafAnvil`) | Monetizes commercial hardware, drone/robotics OEM integration, and enterprise broadcast deployments. |

---

## 4. Normative Operating Profiles & Empirical Benchmarks ([`leaf-video-ADR-001`](leaf-video/docs/adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md))

### 4.1 Operating Profiles (`LCV-ICD-1.0` Section 7.2)

| Profile Name | `depthK8` ($K$) | `lsbSplitBits` ($s$) | `(p_cutoff, g_base, alpha)` | $M_{\text{refresh}}$ | `FLAG_ZSTD_POOL` | Target Application |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **`ARCHIVAL_LOSSLESS`** | `16` | `2` (`g1..g0` in 4B) | `(0, 0, 0.00)` | `16` | `1` (Enabled) | Studio archive, desktop/IDE master recording, NLE post-production |
| **`OBS_REALTIME_LOSSLESS`** | `8` | `2` (`g1..g0` in 4B) | `(0, 0, 0.00)` | `8` | `0` (Raw fast) | Real-time `1440p60`/`4K60` OBS capture with `< 1.2 ms` on-disk crop/strip |
| **`WIRELESS_FOVEATED_LOW_LATENCY`** | `2` or `4` | `2` (`g1..g0` in 4B) | `(4, 0, 1.25)` | `2` | `0` (Raw MTU) | FPV drone, robotics teleoperation, wireless monitor (`100%` lossless crosshair ROI) |
| **`HARSH_RF_UEP_ZERO_WAIT`** | `1` | `3` (`g2..g0` in 4B) | `(4, 1, 2.00)` | `1` | `0` (Raw MTU) | Zero-lookahead (`0 ms` buffer) contested RF link with `UEP` Section 4B queue shedding |

### 4.2 Multi-Codec Lossless & Foveated Compression Summary (`640x368`, 16 Frames, `15,073,280 B` Raw)

| Workload | `UtVideo` | `FFV1 (lvl 3)` | `x265 Lossless` | `x264rgb (-qp 0)` | **`.lcv` (`OBS_REALTIME`)** | **`.lcv + Zstd` (`ARCHIVAL`)** | **`.lcv Foveated`** | Decode Speed |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| **1. IDE / Desktop Screen Capture** | `7.8x` | `21.7x` | `212.6x` | `261.9x` | **`493.9x` (`30,520 B`)** | **`891.6x` (`16,906 B`)** | **`493.9x`** | `402.0 MB/s` |
| **2. Voxel Gameplay + Static HUD** | `6.8x` | `15.7x` | `35.1x` | `35.9x` | **`92.0x` (`163,900 B`)** | **`828.8x` (`18,186 B`)** | **`92.0x`** | `178.0 MB/s` |
| **3. 3D Continuous Gradient + Motion** | `2.8x` | `3.4x` | `3.6x` | `3.0x` | **`4.4x` (`3,422,831 B`)** | **`4.5x` (`3,372,300 B`)** | **`12.7x` (`1,182,712 B`)** | `27.7 MB/s` |
| **4. Transparent `RGBA` VTuber Overlay** | `7.5x` | `41.7x` | `50.8x` | `46.8x` | **`15.1x` (`1,000,078 B`)** | **`192.1x` (`78,480 B`, `0.52%`)** | **`40.0x` (`376,866 B`)** | `244.0 MB/s` |

---

## 5. Build & Verification (`:leaf-video` MIT Reference Suite)

```bash
# Run the full JUnit 5 lossless, foveated RLE, on-disk transform, and crash-recovery torture suite
./gradlew :leaf-video:test --console=plain -q

# Run the standalone benchmark CLI across all 4 workloads and operating profiles
./gradlew :leaf-video:test --tests "io.github.dailystruggle.leafvideo.LcvBenchmarkAndProofSuiteTest" --console=plain -q
```

---

## 6. Documentation (`leaf-video/` & `resource/LeafVideo/`)

- [**`.lcv` / `V_LCV1` Binary Interface Control Document (`leaf-video/docs/ICD.md`, MIT)**](leaf-video/docs/ICD.md) *(single canonical specification, routed from [`docs/ICD.md`](resource/LeafVideo/docs/ICD.md) and packaged in `leaf-video.jar` under `META-INF/leaf-video/docs/ICD.md`)*
- [**`:leaf-video` Reference Codec Guide (`leaf-video/README.md`, MIT)**](leaf-video/README.md)
- [**`leaf-video-ADR-001`: Spatiotemporal Gray-Hilbert Video Codec & `.mkv` Transform**](leaf-video/docs/adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md)
- [**`leaf-video-ADR-002`: Three-Tier Open Standard (`MIT`) and Commercial Product Architecture**](leaf-video/docs/adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md)
- [**Product Suite & Storefront Overview (`FRONT_PAGE.md`)**](resource/LeafVideo/docs/publishing/FRONT_PAGE.md)
- [**Architecture Decision Records Index (`docs/adr/README.md`)**](resource/LeafVideo/docs/adr/README.md)
- [**Changelog (`CHANGELOG.md`)**](resource/LeafVideo/CHANGELOG.md)

---

## 7. Licensing (`LeafVideo` Commercial Workflow Suite & Open `.lcv` Format)

- **Tier 1 Open `.lcv` / `V_LCV1` Format Specification & `:leaf-video` Reference Codec (`MIT`):** The `.lcv` binary format Interface Control Document ([`leaf-video/docs/ICD.md`](leaf-video/docs/ICD.md), routed from [`docs/ICD.md`](resource/LeafVideo/docs/ICD.md), `LCV-ICD-1.0`) and the `io.github.dailystruggle.leafvideo` Java 17+ reference codec ([`leaf-video/`](leaf-video/README.md)) are licensed under the **[MIT License](leaf-video/LICENSE)**. Anyone may freely inspect, compile, embed, or interoperate with `.lcv` / `V_LCV1` streams without royalty or lock-in.
- **Tier 2 Studio GUI Binaries & Tier 3 Native SIMD/GPU & Wireless UEP SDK (`resource/LeafVideo/`):** Licensed under the **[LeafCraft Commercial Software License (`LicenseRef-LeafCraft-Commercial`)](resource/LeafVideo/LICENSE)**, with an explicit Section 3 carve-out guaranteeing 100% user ownership of all recorded media and unrestricted MIT interoperability with `LCV-ICD-1.0` and `:leaf-video`.
