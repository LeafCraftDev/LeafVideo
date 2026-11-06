# leaf-video-ADR-002: Three-Tier Open Standard (`MIT`) and Commercial Workflow Product Architecture (`LeafCraftDev/Video`)

- **Status:** Accepted
- **Date:** 2026-11-06
- **Scope:** `:leaf-video` (`leaf-video/`), `resource/LeafVideo/`, and public projection target `LeafCraftDev/Video` (`--target video`)
- **Extends:** [`leaf-video-ADR-001`](leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md), [`ADR-108`](../../../docs/adr/ADR-108-all-mit-licensing-pro-as-support.md), [`ADR-126`](../../../docs/adr/ADR-126-multi-product-monorepo-release-architecture.md)
- **Normative Binary Specification:** [`LCV-ICD-1.0` (`../ICD.md`)](../ICD.md)

---

## Context

When a streamer, video editor, VFX compositor, or robotics engineer chooses a recording or streaming format, their primary operational risk is **format lock-in**:
> *"If I record 500 GB of footage in `.lcv` / `.mkv` (`V_LCV1`), can `ffmpeg`, VLC, my teammates, or my future self open, crop, or transcode it in 5 years without a license server?"*

Simultaneously, `LCV-ICD-1.0` is built exclusively on expired foundational mathematics:
- **1909:** Haar wavelet lifting;
- **1891 / 1993:** Hilbert space-filling curve traversal;
- **1950:** Extended Hamming `(72, 64)` `SEC-DED` error-correcting codes;
- **1966:** Reflected Binary Gray Code bitplane decomposition;
- **2003:** Reversible `YCoCg-R` integer lifting color decorrelation; and
- **2016:** Adaptive Roaring bitmap / run containers.

Because the underlying mathematical primitives carry zero patent-pool encumbrance, keeping the `.lcv` / `V_LCV1` specification (`LCV-ICD-1.0`) and the Java 17+ reference codec (`:leaf-video`) 100% Free & Open Source (`MIT`) eliminates vendor lock-in anxiety and turns every `.lcv` file into organic adoption for higher-level creator, post-production, and embedded wireless workflow tools.

Conversely, end users and enterprise teams gladly pay for **workflow superpowers, time saved, GPU/CPU relief, and crash insurance** that have no equivalent in `FFV1`, `UtVideo`, `ProRes`, or `x264`/`x265`:
1. **Single-Encode Multi-Crop & Crash Guard in OBS Studio:** Replacing 3–4 concurrent `Source Record` encoders (4K canvas + 9:16 vertical short + facecam + RGBA overlay) with a single lightweight `.lcv` capture that extracts a 9:16 vertical crop on disk in `< 1.2 ms` (`LcvOnDiskTransformer#cropSubRegion`, `159.9x–735x` faster than full decode + re-encode), strips a proxy preview in `< 1.2 ms` (`LcvOnDiskTransformer#stripLsbPlanes`), and survives any PC/OBS crash (`SEC-DED` + `FLAG_INLINE_RECODE_PARTIAL`).
2. **NLE Instant-Scrub & Crash-Resumable Render Plugins:** Compressing `RGBA` overlays down to `0.52%` lossless (`0.29%` stripped), decoding single viewport tiles in $O(1)$ at `240–402 MB/s`, and resuming crashed multi-hour renders (`LcvOnDiskTransformer#resumeInlineRecode`) in DaVinci Resolve, Premiere Pro, After Effects, and Blender.
3. **Wireless Live Video & Foveated UEP Telemetry SDK:** Delivering zero-lookahead ($K=1$, `0 ms` buffer) and ultra-low-latency ($K=2$, `16.6 ms` at `120 fps`) links for FPV drones, robotics teleoperation, and field broadcast where the focal crosshair stays 100% lossless (`PSNR = infinity`), Section 4B (`g2..g0`) sheds at the radio queue in `0.1 ms` during RF fade, and lost packets quarantine to single $16 \times 16$ tiles (`LcvStreamContainer.MvCoherentPacket`).

---

## Decision

Establish a **Three-Tier Open Standard & Commercial Product Split** across `leaf-video/` and `resource/LeafVideo/`, following the repository licensing architecture in [`docs/dev/LICENSING.md`](../../../docs/dev/LICENSING.md) (`ADR-108`, `ADR-126`, and the `anvil-api` / `resource/LeafAnvil` open-spec + commercial carrier pattern), and project the public repository to **[`https://github.com/LeafCraftDev/Video`](https://github.com/LeafCraftDev/Video)** (`--target video`).

### 1. Three-Tier Licensing & Component Matrix

| Tier | Component & Path | License / Distribution Model | Purpose |
| :--- | :--- | :--- | :--- |
| **Tier 1: Open Standard & Core Reference (`leaf-video/`)** | - `LCV-ICD-1.0` ([`leaf-video/docs/ICD.md`](../ICD.md))<br>- [`leaf-video-ADR-001`](leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md) & `leaf-video-ADR-002`<br>- `:leaf-video` Java 17+ reference codec (`LcvPrimitives`, `LcvCubeCodec`, `LcvStreamContainer`, `LcvMkvPacketizer`, `LcvOnDiskTransformer`, `LcvCorruptTileException`) | **Free & Open Source (`MIT`)** ([`leaf-video/LICENSE`](../../LICENSE)) | Guarantees zero vendor lock-in, zero patent/royalty friction, universal playback, academic/developer auditability, and community contributions in [`LeafCraftDev/Video`](https://github.com/LeafCraftDev/Video). |
| **Tier 2: Creator & Editor Binaries (`resource/LeafVideo/` — OBS / NLE GUI Addons)** | - **OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite** (1-click 9:16 visual crop selector, auto-proxy stripper, crash-recovery doctor, background `.mp4` exporter)<br>- **NLE Instant-Scrub & Crash-Resumable Render Plugin** (DaVinci Resolve / Premiere Pro / After Effects / Blender native `.lcv` media reader & resumable exporter) | **Dual Track (`ADR-108` Style + Commercial Studio Binary):**<br>- CLI / Core reference library (`:leaf-video`) remains free `MIT`<br>- Pre-built GUI Studio Installer + Auto-Updater + Priority Support sold as Pro (`$15–$99`) | Monetizes creator convenience, native GUI integration, workflow automation, and priority support without restricting the open `.lcv` format. |
| **Tier 3: High-Performance Native / Hardware / B2B SDK (`resource/LeafVideo/`)** | - Zero-copy `AVX2` / `NEON` / GPU Compute (`Vulkan` / `CUDA`) C++20 encoder<br>- Embedded `WIRELESS_FOVEATED_LOW_LATENCY` & `HARSH_RF_UEP_ZERO_WAIT` wireless transmitter/receiver link daemon with FEC/UEP socket queue shedding | **LeafCraft Commercial Software License (`LicenseRef-LeafCraft-Commercial`)** ([`resource/LeafVideo/LICENSE`](../../../resource/LeafVideo/LICENSE)) | Monetizes commercial hardware, FPV drone/robotics OEM integration, and enterprise broadcast deployments with SLA guarantees. |

---

### 2. Marketable Product Suites Enabled by `LCV-ICD-1.0`

| Marketable Product | Target Customer | The Pain Point It Solves | Why `.lcv` (`LCV-ICD-1.0`) Makes It Uniquely Possible |
| :--- | :--- | :--- | :--- |
| **1. OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite** | Streamers, VTubers, YouTubers, Tutorial / IDE Creators (`$19–$39` one-time or Patreon / BuiltByBit Pro) | Running 3–4 concurrent OBS `Source Record` encoders (4K canvas, 9:16 vertical TikTok/Shorts crop, facecam isolation, RGBA VTuber overlay) tanks game FPS, and an OBS/PC crash ruins the `.mp4`. | Record one lightweight `.lcv` stream (`OBS_REALTIME_LOSSLESS`). Click one button to extract a 9:16 vertical short (`LcvOnDiskTransformer#cropSubRegion` in `0.9–1.2 ms`, `160x–735x` faster than re-encoding), strip a tiny proxy (`LcvOnDiskTransformer#stripLsbPlanes`), and survive any crash (`SEC-DED` + `FLAG_INLINE_RECODE_PARTIAL`). |
| **2. NLE Instant-Scrub & Crash-Resumable Render Plugin (DaVinci Resolve / Premiere / After Effects / Blender)** | Video Editors, VFX Compositors, Motion Designers (`$49–$99` studio plugin, similar to AfterCodecs or BRAW Studio) | ProRes 4444 / UtVideo RGBA files are massive (`15–40%` of raw), FFV1 scrubs slowly on the timeline, and a crash at 95% during a 4-hour NLE export ruins the output file. | `.lcv` compresses RGBA overlays down to `0.52%` lossless (`0.29%` stripped), decodes single viewport tiles in $O(1)$ at `240–402 MB/s`, and supports mid-recode crash resumption (`LcvOnDiskTransformer#resumeInlineRecode`) without restarting the render. |
| **3. Wireless Live Video & Foveated UEP Telemetry SDK** | FPV Drone Builders, Robotics / Rover Teleoperation, Field Broadcast (B2B Commercial License / SDK SLA) | Standard H.264/HEVC links smear into unflyable grey sludge on packet loss, suffer bufferbloat during RF fade, and blur the center crosshair when bitrate drops. | `WIRELESS_FOVEATED_LOW_LATENCY` and `HARSH_RF_UEP_ZERO_WAIT` ($K=1$, `0 ms` lookahead) keep the center crosshair 100% lossless ($\infty\text{ dB}$), shed Section 4B (`g2..g0`) at the radio queue in `0.1 ms`, and quarantine lost packets to single $16 \times 16$ tiles (`LcvStreamContainer.MvCoherentPacket`). |

---

### 3. Repository Layout & Public Projection (`https://github.com/LeafCraftDev/Video`)

1. **`leaf-video/` (`MIT`):**
   - Houses the canonical `LCV-ICD-1.0` specification (`leaf-video/docs/ICD.md`), `leaf-video-ADR-001`, `leaf-video-ADR-002`, `leaf-video/LICENSE`, `leaf-video/CHANGELOG.md`, and the complete `:leaf-video` Java 17+ reference codec and JUnit 5 proof suite.
2. **`resource/LeafVideo/` (`LicenseRef-LeafCraft-Commercial` with Open `.lcv` MIT Carve-Out):**
   - Mirrors `resource/LeafAnvil/`: public documentation (`resource/LeafVideo/README.md`, `resource/LeafVideo/CHANGELOG.md`, `resource/LeafVideo/LICENSE`, `resource/LeafVideo/docs/ICD.md`, `resource/LeafVideo/docs/adr/README.md`, `resource/LeafVideo/docs/publishing/FRONT_PAGE.md`) is projected to `LeafCraftDev/Video` alongside `leaf-video/`, while any proprietary Tier 2 GUI installer or Tier 3 native C++20 SIMD/GPU/UEP source paths under `resource/LeafVideo/` remain private to `LeafCraftDev/MC` (`scripts/pre_push_guard.py`).
3. **Deterministic Sync (`scripts/release/sync_public_oss_pr.py --target video`):**
   - Exports a self-contained Gradle Java 17+ project (`rootProject.name = 'LeafVideo'`, `include 'leaf-video'`) to `LeafCraftDev/Video` (`video` remote, branch `main`, version branch `0.1.0`) with `resource/LeafVideo/README.md` promoted to root `README.md` and `leaf-video/LICENSE` (`MIT`) at root `LICENSE`.

---

## Consequences

- **Positive:**
  - Zero format lock-in: `LCV-ICD-1.0` and `:leaf-video` are permanently MIT-licensed, encouraging third-party decoders, `ffmpeg` filters, and community adoption.
  - Clear commercial boundary: `resource/LeafVideo/LICENSE` (`LicenseRef-LeafCraft-Commercial`) protects the proprietary Tier 2 Studio GUI installers and Tier 3 Native SIMD/GPU & Wireless UEP SDK binaries while explicitly carving out 100% user ownership of `.lcv` / `.mkv` media files and unrestricted MIT usage of `LCV-ICD-1.0` and `:leaf-video`.
  - Enforced by `scripts/pre_push_guard.py` and verified by `scripts/tests/test_sync_public_oss_pr.py`.
- **Negative / Trade-offs:**
  - Maintaining both the Java 17+ reference implementation (`:leaf-video`) and native C++20 SIMD/GPU encoders requires cross-validating bit-exact `LCV-ICD-1.0` conformance against `LcvCubeCodecTest` and `LcvRecoveryTortureTest` test vectors.
