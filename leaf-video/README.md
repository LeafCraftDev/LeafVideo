# LeafVideo (`:leaf-video` — `.lcv` / `V_LCV1`)

Standalone, zero-dependency Java 17+ reference codec, Matroska (`.mkv` `V_LCV1`) packetizer, and compressed-domain on-disk stream transformer for **3D Spatiotemporal Gray-Hilbert & Adaptive Roaring Video (`LCV-ICD-1.0`)**.

- **Repository:** [`https://github.com/LeafCraftDev/Video`](https://github.com/LeafCraftDev/Video)
- **Specification (`LCV-ICD-1.0`):** [`docs/ICD.md`](docs/ICD.md) (**MIT License**)
- **Reference Codec License:** [**MIT License (`LICENSE`)**](LICENSE) — zero vendor lock-in, zero patent/royalty friction, universal playback
- **Product Suite Overview (`resource/LeafVideo`):** [`../resource/LeafVideo/README.md`](../resource/LeafVideo/README.md)

---

## Core Capabilities

1. **Outcome-Driven Enumerated Encoding Variations (`LcvCubeCodec.EncodingVariation`):**
   - Partitions video into independent $16 \times 16 \times K$ ($K \in \{1, 2, 4, 8, 16\}$) spatiotemporal cubes and evaluates an extensible enumerated family of Roaring and Gray-Hilbert variations per cube/channel, picking the best compressed byte outcome for the content:
     - `UNIFORM_INLINE` (`0 B` body — folded into the 64-bit tile pointer entry for solid or `Alpha = 0` transparent tiles).
     - `TEMPORAL_CLEAN_ACK` (`0 B` body — bounded `Case A` match reusing an anchor superblock's intact tile within $M_{\text{refresh}}$ without recursive `P-frame` drift).
     - `UI_PALETTE_ROARING` (implicit `palette[0]` majority color omitted; minority states $p = 1 \dots |P|-1$ encoded using adaptive Roaring container sub-variants: `ROARING_SINGLETON`, `ROARING_DELTA_RUN`, `ROARING_DELTA_ARRAY`, or `ROARING_BITMAP`).
     - `GRAY_PLANES_RAW` (lossless `YCoCg-R` color decorrelation + 1-pass anisotropy curve selection `00:TYX`, `01:H_3`, `10:T+H_2`, `11:TXY` + 8-bit Reflected Binary Gray-Code bitplanes `g7..g0`).
     - `GRAY_PLANES_HILBERT_DELTA` (1st-order curve delta + ZigZag folding OR 3D spatiotemporal planar + motion-sheared + $4 \times 4$ Haar LL residual prediction with `maxU8` bitplane conditioning and 3-sample Huffman triplet coding).
     - `RAW_CEILING` (hard uncompressed ceiling preventing expansion on pure white noise).
2. **Variable Lossiness via Distance-Scaled Run-Length Concatenation (`d_merge(plane, dist)`):**
   - Bridges `0`-gaps between adjacent `1`-runs along the locality-preserving Hilbert curve when `rawGap <= d_merge(plane, dist)`, replacing `(L_r, rawGap, L_{r+1})` with a single merged run `L_r + rawGap + L_{r+1}`.
   - Scales `d_merge` continuously by bitplane depth (`0` on MSB planes `g7..g4`, progressive bridging on LSB planes `g3..g0`) and spatial/foveated distance from the focal Region of Interest (`ROI`), while remaining **100% wire-compatible with the lossless `mode 10` (`4|4` nibble `adjGap | lenMinusOne`) decoder**.
3. **Crash & Bit-Rot Recovery (`Superblock` + `SEC-DED (72, 64)` + `CBIT` + `.mkv V_LCV1`):**
   - Append-only self-indexing `64 B` `Superblock` header (`"LCV_V001"`) + 2D tile pointer directory (`uint64[tilesX * tilesY]`) + `1 B/tile` Extended Hamming `(72, 64)` `SEC-DED` syndrome table.
   - Single-bit pointer flips heal mathematically in `< 5 ns`; multi-bit sector corruption is quarantined to a single $16 \times 16$ tile via `CBIT` bounds guards (`LcvCorruptTileException`) and falls back to the prior superblock's cached tile with zero `DED` offset contagion across Section 4B (`lsbTileOffsets[N_tiles]`).
4. **Compressed-Domain On-Disk Stream Transforms (`LcvOnDiskTransformer`):**
   - **`cropSubRegion`:** $O(1)$ spatial sub-region extraction (e.g., `3840x2160` 4K canvas $\to$ `1080x1920` vertical crop in `0.9–1.2 ms`, `160x–735x` faster than full decode + re-encode) by copying only intersecting tile slabs.
   - **`stripLsbPlanes`:** Instant MSB-to-LSB bitplane truncation (`g7..g0` $\to$ `g7..g_s` Section 4B proxy stripping in `0.1–1.1 ms`) via byte-slice truncation.
   - **`coalesceRunsByDistance`:** Post-hoc compressed-domain RLE token compaction directly rewriting `(adjGap, lenMinusOne)` nibble run streams on disk without decoding pixels.
   - **`recodeInline` & `resumeInlineRecode`:** Crash-resumable in-place packet-to-packet recoding via the 4-byte `FLAG_INLINE_RECODE_PARTIAL` (`0x04`) checkpoint `(recodedCursor16, priorTuningProfile16)`.

---

## Three-Tier Licensing & Product Architecture ([`leaf-video-ADR-002`](docs/adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md))

Following [`docs/dev/LICENSING.md`](../docs/dev/LICENSING.md), the `.lcv` specification (`LCV-ICD-1.0`) and `:leaf-video` reference codec are 100% Free & Open Source (**MIT**), while commercial workflow automation, NLE studio plugins, and native SIMD/GPU wireless SDKs live in Tier 2 and Tier 3:

| Tier | Component | License / Model | Purpose |
| :--- | :--- | :--- | :--- |
| **Tier 1: Open Standard & Core Reference (`leaf-video/`)** | - `LCV-ICD-1.0` ([`docs/ICD.md`](docs/ICD.md))<br>- [`leaf-video-ADR-001`](docs/adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md) & [`leaf-video-ADR-002`](docs/adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md)<br>- `:leaf-video` Java 17+ reference codec (`LcvPrimitives`, `LcvCubeCodec`, `LcvStreamContainer`, `LcvMkvPacketizer`, `LcvOnDiskTransformer`) | **Free & Open Source (`MIT`)** ([`LICENSE`](LICENSE)) | Guarantees zero vendor lock-in, zero patent/royalty friction, universal playback, and community contributions. |
| **Tier 2: Creator & Editor Binaries (`resource/LeafVideo/` — OBS / NLE GUI Addons)** | - **OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite** (1-click 9:16 visual crop selector, auto-proxy stripper, crash-recovery doctor, background `.mp4` exporter)<br>- **NLE Instant-Scrub & Crash-Resumable Render Plugin** (DaVinci Resolve / Premiere / After Effects / Blender native `.lcv` reader & resumable exporter) | **Dual Track (`ADR-108` Style + Commercial Binary):**<br>- CLI / Core remains free `MIT`<br>- Pre-built GUI Studio Installer + Auto-Updater + Priority Support sold as Pro (`$15–$99`) | Monetizes creator convenience, UI polish, and workflow automation without crippling the open `.lcv` format. |
| **Tier 3: High-Performance Native / Hardware / B2B SDK (`resource/LeafVideo/`)** | - Zero-copy `AVX2` / `NEON` / GPU Compute (`Vulkan` / `CUDA`) C++20 encoder<br>- Embedded `HARSH_RF_UEP_ZERO_WAIT` wireless transmitter/receiver link daemon with FEC/UEP socket scheduling | **Commercial License (`LicenseRef-LeafCraft-Commercial`)** ([`../resource/LeafVideo/LICENSE`](../resource/LeafVideo/LICENSE)) | Monetizes commercial hardware, FPV drone/robotics OEM integration, and enterprise broadcast deployments. |

---

## Documentation & Specifications

- **Interface Control Document (`LCV-ICD-1.0`):** [`docs/ICD.md`](docs/ICD.md)
- **Architecture Decision Records (`ADR`):** [`docs/adr/README.md`](docs/adr/README.md)
  - [`leaf-video-ADR-001`](docs/adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md): Spatiotemporal Gray-Hilbert & Adaptive Roaring Video Codec, Distance-Scaled RLE Concatenation, `.mkv` (`V_LCV1`) Crash Recovery & Compressed-Domain On-Disk Transforms
  - [`leaf-video-ADR-002`](docs/adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md): Three-Tier Open Standard (`MIT`) and Commercial Workflow Product Architecture (`LeafCraftDev/Video`)
- **Changelog:** [`CHANGELOG.md`](CHANGELOG.md)
- **License (`MIT`):** [`LICENSE`](LICENSE)

---

## Verification & Proof Suite

```bash
# Run the JUnit 5 lossless, distance-RLE, compressed-domain transform, and crash-recovery torture suite
./gradlew :leaf-video:test --console=plain -q
```
