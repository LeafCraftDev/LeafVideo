# LeafVideo (`:leaf-video` — `.lcv` / `V_LCV1`)

Open binary specification (`LCV-ICD-1.0`), architecture decision records, and native C11 Gate 1 verification harness for **3D Spatiotemporal Gray-Hilbert & Adaptive Roaring Video (`.lcv` / Matroska `V_LCV1`)**.

- **Repository:** [`https://github.com/LeafCraftDev/LeafVideo`](https://github.com/LeafCraftDev/LeafVideo)
- **Specification (`LCV-ICD-1.0`):** [`docs/ICD.md`](docs/ICD.md) (**MIT License**)
- **Specification & Open Standard License:** [**MIT License (`LICENSE`)**](LICENSE) — zero vendor lock-in, zero patent/royalty friction, universal interoperability
- **Canonical Native C11 / SIMD / GPU Implementation:** `resource/LeafVideo/native/` (`leafvideo.h`, `lcv_accel.c`, `lcv_cube_kernel.comp`, `lcv_codec_system_plugins.c`, `obs_leafvideo_plugin.c`, `lcv_cli_and_test.c`)
- **Product Suite Overview (`resource/LeafVideo`):** [`../resource/LeafVideo/README.md`](../resource/LeafVideo/README.md)

---

## Core Capabilities

1. **Outcome-Driven Enumerated Encoding Variations (`lcv_accel.c` & `lcv_cube_kernel.comp`):**
   - Partitions video into $16 \times 16 \times K$ ($K \in \{1, 2, 4, 8, 16\}$) spatiotemporal cubes and selects the smallest compressed byte representation per cube/channel:
     - `UNIFORM_INLINE` (`0 B` body — folded into the 64-bit tile pointer entry for solid or `Alpha = 0` transparent tiles).
     - `TEMPORAL_CLEAN_ACK` (`0 B` body — bounded `Case A` match reusing an anchor superblock's intact or motion-shifted $(\Delta x, \Delta y) \in [-8, +7]^2$ tile within $M_{\text{refresh}}$ without recursive `P-frame` drift).
     - `UI_PALETTE_ROARING` (implicit `palette[0]` majority color omitted; minority states $p = 1 \dots |P|-1$ encoded using adaptive Roaring container sub-variants: `ROARING_SINGLETON`, `ROARING_DELTA_RUN`, `ROARING_DELTA_ARRAY`, or `ROARING_BITMAP`).
     - `GRAY_PLANES_RAW` (lossless $\mathbb{Z}_{2^B}$ `YCoCg-R` color decorrelation for $B \in \{8, 10, 12\}$ + 1-pass anisotropy curve selection `00:TYX`, `01:H_3`, `10:T+H_2`, `11:TXY` + Reflected Binary Gray-Code bitplanes).
     - `GRAY_PLANES_HILBERT_DELTA` (1st-order curve delta + ZigZag folding, causal 2D `MED-2D` + per-frame motion trajectory + `CCLM-MSB` + $4 \times 4$ sub-tile bilinear shear, or 3D spatiotemporal planar + $4 \times 4$ Haar LL residual prediction with `maxU8` bitplane conditioning and 3-sample Huffman triplet coding).
     - `RAW_CEILING` (hard uncompressed ceiling preventing expansion on pure white noise).
2. **Variable Lossiness via Distance-Scaled Run-Length Concatenation (`d_merge(plane, dist)`):**
   - Bridges `0`-gaps between adjacent `1`-runs along the locality-preserving Hilbert curve when `rawGap <= d_merge(plane, dist)`, replacing `(L_r, rawGap, L_{r+1})` with a single merged run `L_r + rawGap + L_{r+1}`.
   - Scales `d_merge` continuously by bitplane depth (`0` on MSB planes, progressive bridging on LSB planes) and spatial/multi-fovea distance from focal anchors, while remaining **100% wire-compatible with the lossless `mode 10` (`4|4` nibble `adjGap | lenMinusOne`) decoder**.
3. **Crash & Bit-Rot Recovery (`Superblock` + `SEC-DED (72, 64)` + `CBIT` + `.mkv V_LCV1`):**
   - Append-only self-indexing `64 B` `Superblock` header (`"LCV_V001"`, reflected IEEE 802.3 `CRC-32`) + 2D/quadtree tile pointer directory (`uint64[N_words]`) + `1 B/word` Extended Hamming `(72, 64)` `SEC-DED` syndrome table.
   - Single-bit pointer flips heal mathematically in `< 5 ns` (`lcv_heal_single_bit_flip64`); multi-bit sector corruption is quarantined to a single $16 \times 16$ tile (`LCV_UNCORRECTABLE_DED_SENTINEL`) and concealed via neighbor-MV optical flow (`lcv_native_conceal_quarantined_tiles_flow`) or 4-connected cold-anchor interpolation (`lcv_reconstruct_cold_quarantined_tiles_spatiotemporal`) with zero `DED` offset contagion across Section 4B (`lsbTileOffsets[N_tiles]`).
4. **Compressed-Domain On-Disk Stream Transforms (`lcv_accel.c` / `leafvideo.h`):**
   - **`lcv_native_crop_or_strip_superblock` (`cropSubRegion`):** $O(1)$ spatial sub-region extraction (e.g., `3840x2160` 4K canvas $\to$ `1080x1920` vertical crop in `< 1 ms`) by copying only intersecting tile slabs.
   - **`lcv_native_crop_or_strip_superblock` (`stripLsb == 1`) & `lcv_native_strip_lsb_outside_radius`:** Instant MSB-to-LSB bitplane truncation (Section 4B proxy stripping in `< 0.2 ms`) and single-file `Section 4A` 8-bit preview decoding (`lcv_native_decode_superblock_rgba8_preview`) with 2-pass integer bounded-interval POCS restoration (`lcv_restore_stripped_field_bounded_interval_tyx`).
   - **`lcv_native_compact_superblock_zstd`:** Compressed-domain Section 4A tile deduplication and size-prefixed RFC 8878 Zstd pool compaction (`LCV_FLAG_ZSTD_POOL = 0x01`) without decoding pixels.
   - **Mid-Recode Crash Checkpoint (`LCV_FLAG_INLINE_RECODE_PARTIAL = 0x04`):** Crash-resumable in-place recoding via the 4-byte Section 4B checkpoint `(recodedCursor16, priorTuningProfile16)`.

---

## Three-Tier Licensing & Product Architecture ([`leaf-video-ADR-002`](docs/adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md))

Following [`docs/dev/LICENSING.md`](../docs/dev/LICENSING.md), the `.lcv` specification (`LCV-ICD-1.0`) and `:leaf-video` module are 100% Free & Open Source (**MIT**), while commercial workflow automation, NLE studio plugins, and native SIMD/GPU wireless SDKs live in Tier 2 and Tier 3:

| Tier | Component | License / Model | Purpose |
| :--- | :--- | :--- | :--- |
| **Tier 1: Open Standard & Core Specification (`leaf-video/`)** | - `LCV-ICD-1.0` ([`docs/ICD.md`](docs/ICD.md))<br>- [`leaf-video-ADR-001`](docs/adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md) .. [`leaf-video-ADR-004`](docs/adr/leaf-video-ADR-004-bitwise-simd-and-non-video-hardware-accelerator-offload.md) | **Free & Open Source (`MIT`)** ([`LICENSE`](LICENSE)) | Guarantees zero vendor lock-in, zero patent/royalty friction, deterministic decoder interoperability, and community auditability. |
| **Tier 2: Creator & Editor Binaries (`resource/LeafVideo/` — OBS / NLE GUI Addons)** | - **OBS Studio "Single-Encode Multi-Crop & Crash Guard" Suite** (`obs_leafvideo_plugin.c` `libobs v30` plugin, 1-click 9:16 visual crop selector, auto-proxy stripper, crash-recovery doctor)<br>- **NLE Instant-Scrub & Crash-Resumable Render Plugin** (`lcv_codec_system_plugins.c` native `.lcv` reader & resumable exporter) | **Dual Track (`ADR-108` Style + Commercial Binary):**<br>- Format spec (`LCV-ICD-1.0`) remains free `MIT`<br>- Pre-built GUI Studio Installer + Auto-Updater + Priority Support sold as Pro (`$15–$99`) | Monetizes creator convenience, UI polish, and workflow automation without crippling the open `.lcv` format. |
| **Tier 3: High-Performance Native / Hardware / B2B SDK (`resource/LeafVideo/native/`)** | - Zero-copy C11 + `AVX2` / `NEON` / GPU Compute (`Vulkan` `lcv_cube_kernel.comp` & `CUDA PTX` in `lcv_accel.c`) encoder/decoder<br>- Embedded `HARSH_RF_UEP_ZERO_WAIT` & `VR_STEREO_FOVEATED_DIRECT` `LCVU` UDP streaming SDK | **Commercial License (`LicenseRef-LeafCraft-Commercial`)** ([`../resource/LeafVideo/LICENSE`](../resource/LeafVideo/LICENSE)) | Monetizes commercial hardware, VR headset streaming, FPV drone/robotics OEM integration, and enterprise broadcast deployments. |

---

## Documentation & Specifications

- **Interface Control Document (`LCV-ICD-1.0`):** [`docs/ICD.md`](docs/ICD.md)
- **Architecture Decision Records (`ADR`):** [`docs/adr/README.md`](docs/adr/README.md)
  - [`leaf-video-ADR-001`](docs/adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md): Spatiotemporal Gray-Hilbert & Adaptive Roaring Video Codec, Distance-Scaled RLE Concatenation, `.mkv` (`V_LCV1`) Crash Recovery & Compressed-Domain On-Disk Transforms
  - [`leaf-video-ADR-002`](docs/adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md): Three-Tier Open Standard (`MIT`) and Commercial Workflow Product Architecture (`LeafCraftDev/LeafVideo`)
  - [`leaf-video-ADR-003`](docs/adr/leaf-video-ADR-003-spatiotemporal-database-decode-map-and-causal-compaction.md): Content-Addressed Spatiotemporal Database, `DecodeMapPacket` Indirection, and Causal Live Compaction
  - [`leaf-video-ADR-004`](docs/adr/leaf-video-ADR-004-bitwise-simd-and-non-video-hardware-accelerator-offload.md): Hardware-Agnostic Bitwise SIMD, Non-Video Silicon Hijack, Multi-Fovea VR Streaming, and Canonical Native C11 Implementation
- **Changelog:** [`CHANGELOG.md`](CHANGELOG.md)
- **License (`MIT`):** [`LICENSE`](LICENSE)

---

## Verification & Native C11 Gate 1 Suite (`:leaf-video`)

```bash
# Compile resource/LeafVideo/native/{lcv_accel.c,lcv_codec_system_plugins.c,lcv_cli_and_test.c}
# and run the canonical native C11 Gate 1 self-test suite (--self-test)
./gradlew :leaf-video:test --console=plain -q
```
