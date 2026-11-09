# leaf-video-ADR-001: Spatiotemporal Gray-Hilbert & Adaptive Roaring Video Codec, Distance-Scaled RLE Concatenation, `.mkv` (`V_LCV1`) Crash Recovery & Compressed-Domain On-Disk Transforms

- **Status:** Accepted
- **Date:** 2026-11-05
- **Scope:** `:leaf-video` (`leaf-video/`) and `resource/LeafVideo/native/` (`leafvideo.h`, `lcv_accel.c`, `lcv_codec_system_plugins.c`, `lcv_cli_and_test.c`)
- **Normative Binary Specification:** [`LCV-ICD-1.0` (`../ICD.md`)](../ICD.md)
- **Related ADRs:** [`anvil-api-ADR-003`](../../../api/anvil-api/docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md), [`leafanvil-ADR-002`](../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-002-in-memory-lca-chunk-representation-and-dirty-overlay-compaction.md), [`leafanvil-ADR-003`](../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-003-negotiated-lca-chunk-network-streaming-and-delta-sync.md), [`leafrtp-ADR-066`](../../../resource/LeafRTP/docs/adr/leafrtp-ADR-066-spiral-addressed-hilbert-key-space.md), [`leafrtp-ADR-072`](../../../resource/LeafRTP/docs/adr/leafrtp-ADR-072-hybrid-bit-packed-and-16-bit-run-container-hazard-table.md), [`ADR-126`](../../../docs/adr/ADR-126-multi-product-monorepo-release-architecture.md)

---

## Context

In `anvil-api-ADR-003` (`LcaSectionCodec`) and `leafanvil-ADR-003`, we established that a 3D lattice of $16 \times 16 \times 16 = 4,096$ samples can be compressed at L1-cache speeds with $O(1)$ random access and sub-packet fault isolation by combining:
1. **1-Pass Directional Anisotropy Curve Selection** (`00:TYX`, `01:H_3` 3D Hilbert, `10:T+H_2` column-Hilbert, `11:TXY`);
2. **Implicit Majority-State (`palette[0]`) + Adaptive `Delta-Roaring` Containers** for discrete/categorical structures;
3. **Reflected Binary Gray-Code Bitplanes** ($G = V \oplus (V \gg 1)$) for continuous scalar fields (`LIGHT_MODE_GRAY_PLANES`); and
4. **Extended Hamming `(72, 64)` `SEC-DED` + `CBIT` Bounds Quarantine + `Case A` Temporal Clean-Acks**.

When recording live screen capture, stylized/3D gameplay, or transparent `RGBA` overlays (e.g., in OBS Studio into `.mkv`), existing lossless and near-lossless video codecs force a harsh three-way compromise:
- **`UtVideo` / `MagicYUV`:** Fast SIMD Huffman coding, but relatively low compression on static UI/HUDs (`2.0–3.0:1`), zero single-bit self-healing (`1` bit flip corrupts a slice), and full-frame decoding required to crop a viewport region.
- **`FFV1` (`Level 3` in `.mkv`):** High compression via context-adaptive range coding, but high CPU cost (`CABAC`/range coder drops frames at `1440p60`/`4K60`), slice-wide range-coder desynchronization on bit flips, and slow timeline scrubbing.
- **`x264` (`-qp 0`):** Strong inter-frame compression, but stateful `GoP` dependency chains (`I -> P -> P`) cause a single torn `.mkv` `Cluster` or sector error to invalidate the rest of the Group of Pictures, and any spatial crop requires decoding the full preceding `GoP` chain at full canvas resolution.

By extending `.lca`'s 3D voxel codec to $16 \times 16 \times K$ ($K \in \{4, 8, 16\}$) spatiotemporal video cubes (`(X, Y, T)`), treating `Roaring` and `Gray-Hilbert` representations as an **enumerated family of encoding variations selected by best compressed byte outcome for the content**, and introducing **distance-scaled run-length concatenation (`d_merge(plane, dist)`)**, we obtain a video format (`.lcv` / Matroska `CodecID = V_LCV1`) that:
1. Compresses discrete UI/HUD/overlay and continuous gradient video losslessly or with continuous foveated/LSB variable lossiness;
2. Survives mid-write `SIGKILL` truncation, heals single-bit pointer flips in `< 5 ns`, and quarantines `4 KiB` torn disk sectors to a single $16 \times 16$ tile; and
3. Supports **compressed-domain on-disk transformations** ($O(1)$ sub-region cropping, instant MSB-to-LSB proxy stripping, and post-hoc RLE token compaction) without decoding pixels.

---

## Decision

Establish `:leaf-video` (`leaf-video/` at repository root) and its canonical native C11 implementation (`resource/LeafVideo/native/lcv_accel.c`, `leafvideo.h`, `lcv_codec_system_plugins.c`, `lcv_cli_and_test.c`) as the `.lcv` / `.mkv` (`V_LCV1`) codec, container framing, and compressed-domain stream transformer governed by this ADR and the normative [`LCV-ICD-1.0`](../ICD.md) specification.

### 1. Spatiotemporal Cube Partitioning & 1-Pass Anisotropy Curve Selection (`lcv_accel.c` / `LcvPrimitives`)

Each `Superblock` groups $K \in \{4, 8, 16\}$ consecutive video frames of dimensions $W \times H$ into a 2D grid of $\lceil W / 16 \rceil \times \lceil H / 16 \rceil$ independent spatiotemporal cubes of size $16 \times 16 \times K$ ($N_{\text{samples}} = 256 \cdot K$, i.e., `1,024`, `2,048`, or `4,096` spacetime voxels).

For every non-uniform cube, `lcv_select_optimal_curve_1pass` (`LcvPrimitives#selectOptimalCurve1Pass`) measures directional L1 transition energy along `(X, Y, T)` in a single pass and selects one of four space-filling curves (`curveId` `bits 7..6`):
- **`00` (`CURVE_TYX`):** Temporal-outer, horizontal scanline inner ($t \to y \to x$). Optimal for horizontal UI bars, text lines, and scanline ramps.
- **`01` (`CURVE_H3`):** Isotropic 3D Hilbert curve over $16 \times 16 \times K$ ($4 \times 4 \times 4$ spacetime sub-octants). Optimal for localized 3D motion, moving sprites, and isotropic shading gradients.
- **`10` (`CURVE_T_H2`):** Spatial 2D Hilbert outer over $(x, y) \in 16 \times 16$, temporal column inner along $t = 0 \dots K-1$. Collapses every pixel that remains stationary across $K$ frames into a contiguous 1D run of length $K$ without motion-vector search.
- **`11` (`CURVE_TXY`):** Temporal-outer, vertical scanline inner ($t \to x \to y$). Optimal for vertical window borders, pillarboxes, and IDE sidebars.

---

### 2. Outcome-Driven Enumerated Encoding Variations (`lcv_accel.c` / `LcvCubeCodec.EncodingVariation`)

Rather than hardcoding a single transform pipeline, `LcvCubeCodec` models Roaring containers, Gray-code bitplanes, and predictive delta modes as an **extensible enumerated set of encoding variations (`EncodingVariation`)**. For each $16 \times 16 \times K$ cube (and for each minority palette state or bitplane), the encoder evaluates candidate variations in thread-local `ScratchArena` memory and selects the variation that produces the **minimum byte length (best outcome for the content)**:

| `EncodingVariation` Enum | Wire Carrier (`Pointer` / `cubeMode` / `subTag`) | Target Content Regime | Selection Criterion |
| :--- | :--- | :--- | :--- |
| **`UNIFORM_INLINE`** | Directory pointer `bit 63 = 1, bit 62 = 0` (`0 B` body) | Solid-color tiles or 100% transparent (`Alpha = 0`) overlay tiles | Selected whenever all $256 \cdot K$ samples in the cube are identical (`0` body bytes). |
| **`TEMPORAL_CLEAN_ACK`** | Directory pointer `bit 63 = 1, bit 62 = 1` (`0 B` body) | Unchanged tile across consecutive superblocks (`Case A` `secDedSyndrome64` + sample equality) | Selected when the cube matches the prior superblock's intact tile (`0` body bytes, zero `P-frame` drift). |
| **`UI_PALETTE_ROARING`** | `cubeMode = 00` (`\|P\| \le 16` unique `RGBA` colors, implicit `palette[0]` omitted) | IDE/desktop capture, text, HUDs, pixel art, segmentation masks, and sharp overlays | Evaluated when unique color count $\|P\| \le 16$; chosen if total byte size beats continuous Gray-plane variations. |
| **`ROARING_SINGLETON`** *(Sub-variation `00`)* | Roaring state container `tag = 00` (`1` sample index, `2 B`) | Isolated cursor pixel, sparkle, or single-voxel highlight | Chosen for a palette state $p \ge 1$ when cardinality $c_p = 1$. |
| **`ROARING_DELTA_RUN`** *(Sub-variation `01`)* | Roaring state container `tag = 01` (`4\|4` nibble `adjGap \| lenMinusOne` + `VarInt` overflow) | Contiguous glyphs, UI rectangles, and static pixel columns along `10:T+H_2` | Chosen for state $p \ge 1$ when run-encoded byte size $<$ `ROARING_DELTA_ARRAY` and `ROARING_BITMAP`. |
| **`ROARING_DELTA_ARRAY`** *(Sub-variation `10`)* | Roaring state container `tag = 10` (`VarInt` delta-gap array of `1`-positions) | Sparse dither dots or scattered text anti-aliasing pixels | Chosen for state $p \ge 1$ when delta-array size $<$ `ROARING_DELTA_RUN` and `ROARING_BITMAP`. |
| **`ROARING_BITMAP`** *(Sub-variation `11`)* | Roaring state container `tag = 11` (`(256 * K) / 8` raw bitset ceiling) | High-density checkerboard / halftone pattern | Chosen as hard upper bound when sparse containers exceed `(256 * K) / 8` bytes. |
| **`GRAY_PLANES_RAW`** | `cubeMode = 01` (`YCoCg-R` + $G = V \oplus (V \gg 1)$ bitplanes `g7..g0`) | Smooth shading gradients, natural video, 3D lighting, and soft shadows | Evaluated on reversible `YCoCg-R + Alpha` channels; chosen when raw Gray bitplanes yield the smallest payload. |
| **`GRAY_PLANES_HILBERT_DELTA`** | `cubeMode = 10` (`YCoCg-R` + 1st-order curve delta + ZigZag + Gray bitplanes `g7..g0`) | Steep linear shading ramps, moving gradients, and textured 3D surfaces | Evaluated alongside `GRAY_PLANES_RAW` in `ScratchArena`; chosen whenever curve-delta + Gray produces fewer bytes than `GRAY_PLANES_RAW`. |
| **`RAW_CEILING`** | `cubeMode = 11` (uncompressed channel bytes) | Incompressible white noise / cryptographic entropy | Hard safety ceiling so a cube never expands beyond its raw active channel bytes plus `1 B` header. |

The `cubeMode` and Roaring `subVariation` code points are designed as extensible tables (`LCV-ICD-1.0` Section 5.2) so future variations (such as octant Intra-Block Copy `IBC` or 2D wavelet residual containers) can be registered and competed in the same best-outcome selector without breaking container framing.

---

### 3. Reversible `YCoCg-R` Color Decorrelation & 8-Bit Reflected Binary Gray-Code Bitplanes

For continuous cubes (`GRAY_PLANES_RAW` and `GRAY_PLANES_HILBERT_DELTA`), each `RGBA` sample is mapped via the reversible lifting transform `YCoCg-R` (`LCV-ICD-1.0` Section 5.3) into four unsigned 8-bit channels (`Y`, `Co`, `Cg`, `Alpha`, with `Co` and `Cg` monotonic offset-binary centered at `+128` so neutral gray has `(Co8, Cg8) = (128, 128)` and `g0..g2` represent true magnitude LSBs rather than sign bits):
- Inactive/constant channels (e.g., opaque `Alpha = 255` or monochrome `Co8 = Cg8 = 128`) have their bit cleared in `channelMask4` (`bits 3..0` of `cubeHeader8`), writing only `1 B` `constVal8[c]` in the Base MSB Slice instead of bitplanes.
- Active channels in `GRAY_PLANES_RAW` map each 8-bit value $v \in [0, 255]$ along the selected curve into Reflected Binary Gray Code $g = v \oplus (v \ggg 1)$. In `GRAY_PLANES_HILBERT_DELTA`, 1st-order curve differencing + 6-bit ZigZag + Gray coding operates on the top 6 MSB bits ($m_i = v_i \ggg 2$, stored in `g7..g2`) while the bottom 2 LSB bits ($l_i = v_i \ \& \ 3$, stored in `g1..g0`) are Gray-coded without differencing so zero-decode `stripLsbPlanes` never incurs cumulative prefix-sum drift.
- Because unit-step transitions ($\Delta v = \pm 1$) flip **exactly 1 Gray bitplane** and interior toggle periods double ($2^{p+2}$), smooth gradients collapse `g7..g4` into `0 B` (`planeMode = 00` all-`0` or `01` all-`1`) or $2\text{–}8$ byte `4|4` nibble run streams (`planeMode = 10`), while noisy bottom planes are capped by `planeMode = 11` (`(256 * K) / 8` raw bitmap ceiling).

---

### 4. Variable Lossiness via Distance-Scaled Run-Length Concatenation (`d_merge(plane, dist)`)

In `planeMode = 10` (`4|4` nibble `adjGap | lenMinusOne`), a bitplane is a 1D alternating sequence of `0`-gaps and `1`-runs along the space-filling curve (`01:H_3` or `10:T+H_2`):
$$(g_0, L_0), (g_1, L_1), \dots, (g_{m-1}, L_{m-1})$$
where $g_k$ is the number of `0` bits preceding run $k$ ($g_0 \ge 0$, $g_k \ge 1$ for $k \ge 1$) and $L_k \ge 1$ is the number of contiguous `1` bits in run $k$.

By `leafrtp-ADR-066` Section 3, small index gaps along a Hilbert traversal correspond to spatially/temporally adjacent samples inside the same $4 \times 4 \times 4$ sub-octant. We define a **distance-scaled run-merge threshold** $d_{\text{merge}}(p, r)$ as a function of bitplane index $p \in \{0 \dots 7\}$ (`0 = LSB g0`, `7 = MSB g7`) and radial Euclidean tile distance $r = \sqrt{(\text{tileX} - \text{roiTileX})^2 + (\text{tileY} - \text{roiTileY})^2}$ from the focal Region of Interest (`ROI`):
$$d_{\text{merge}}(p, r) = \begin{cases} 0 & \text{if } p \ge p_{\text{cutoff}} \\ \left\lfloor (\text{baseMaxGap} + r \cdot \text{radialScale}) \cdot 2^{(p_{\text{cutoff}} - 1 - p)} \right\rfloor & \text{if } 0 \le p < p_{\text{cutoff}} \end{cases}$$

When encoding (or compacting on disk), whenever two consecutive `1`-runs $(g_k, L_k)$ and $(g_{k+1}, L_{k+1})$ are separated by a zero-gap $g_{k+1} \le d_{\text{merge}}(p, r)$, they are **concatenated into a single run**:
$$(g_k, L_k + g_{k+1} + L_{k+1})$$

This provides three architectural invariants (`REQ-LCV-F-002`):
1. **100% Lossless Decoder Compatibility:** Concatenated runs use the **exact same `mode 10` (`4|4` nibble `adjGap | lenMinusOne`) bitstream grammar** as lossless runs. The decoder (`lcv_decode_split_cube_rgba` in `lcv_accel.c` / `LcvCubeCodec#decodeCubeRgba`) has zero special cases, extra flags, or branches for lossily-concatenated streams.
2. **Zero Contour Banding & Foveated Fidelity:** Because $d_{\text{merge}}(p, r) = 0$ for all MSB planes $p \ge p_{\text{cutoff}}$ (e.g., `g7..g4`) and at the focal center when $\text{baseMaxGap} = 0$, structural edges and focal regions remain **bit-exact (`PSNR = infinity`)**, while peripheral tiles and fine LSB planes (`g3..g0`) smoothly coalesce micro-runs without full-plane posterization (`Mach bands`).
3. **Post-Hoc Compressed-Domain Compaction:** Because run concatenation is an algebraic rewrite on `(adjGap, lenMinusOne)` tokens ($L_k \leftarrow L_k + (\text{adjGap}_{k+1} + 1) + (\text{lenMinusOne}_{k+1} + 1)$), `lcv_native_compact_superblock_zstd` (`LcvOnDiskTransformer#coalesceRunsByDistance`) can compact an already-recorded lossless `.lcv` / `.mkv` file **directly on disk without decoding pixels**.

---

### 5. Crash & Bit-Rot Recovery (`Superblock` + `SEC-DED (72, 64)` + `CBIT` + `.mkv` `V_LCV1`)

1. **Append-Only Self-Indexing `Superblock` (`REQ-LCV-F-003`):**
   - Every `Superblock` starts with a `64 B` Big-Endian header (`magic64 = 0x4C43565F56303031L` `"LCV_V001"`, `prevSbOffset64`, `checksum64`) followed immediately by the 2D tile pointer directory `uint64[tilesX * tilesY]` and the `1 B/tile` Extended Hamming `(72, 64)` `SEC-DED` syndrome table.
   - If recording terminates uncleanly (`SIGKILL` / power loss) mid-write inside superblock $S$, all preceding superblocks $0 \dots S-1$ are 100% intact and $O(1)$ seekable without an end-of-file index table.
2. **`SEC-DED (72, 64)` Single-Bit Pointer Self-Healing (`REQ-LCV-F-004`):**
   - Every 64-bit tile pointer entry `ptr64` is protected by an 8-bit Extended Hamming `(72, 64)` syndrome byte (`lcv_compute_hamming_secded8` / `LcvPrimitives#computeHammingSecDed8`). Any single-bit flip in `ptr64` or the syndrome byte is mathematically healed in `< 5 ns` (`lcv_heal_single_bit_flip64` / `LcvPrimitives#healSingleBitFlip64`).
3. **`CBIT` Structural Bounds Quarantine & Concealment vs. `LCV_FLAG_ZSTD_POOL` Payload Checksums (`REQ-LCV-F-004`):**
   - Multi-bit pointer corruption (`lcv_heal_single_bit_flip64 == LCV_UNCORRECTABLE_DED_SENTINEL` / `Long.MIN_VALUE`) or structural payload corruption detected by `CBIT` grammar/cursor guards (`msbSliceByteLen16 == 0`, `cursor + rawGap + runLen > N_samples`, palette count overflow, or slice bounds overrun) quarantines strictly that single $16 \times 16 \times K$ tile (preventing bit-stream desynchronization across neighboring tiles) and triggers neighbor-MV optical-flow or cold-anchor fallback (`lcv_native_conceal_quarantined_tiles_flow` / `lcv_reconstruct_cold_quarantined_tiles_spatiotemporal`), confining `4 KiB` torn-sector damage to $\le 1\text{–}2$ tiles (`< 0.1%` of a `1080p`/`4K` frame). Note that on raw uncompressed superblocks (`LCV_FLAG_ZSTD_POOL == 0`), `CBIT` enforces structural bounds quarantine rather than a per-tile cryptographic hash; full bit-exact payload checksum verification relies on RFC 8878 Zstd frame checksums when `LCV_FLAG_ZSTD_POOL` (`0x01`) is enabled.
4. **Matroska `.mkv` (`CodecID = V_LCV1`) Packetization (`lcv_codec_system_plugins.c` / `LcvMkvPacketizer`):**
   - In `.mkv` streams, each `Superblock` is encapsulated inside a Matroska EBML `Cluster` (`0x1F43B675`) / `SimpleBlock` (`0xA3`) packet with `CodecID = "V_LCV1"`. Stream recovery scans for `"LCV_V001"` / `Cluster` sync boundaries and recovers every valid `Superblock` and intact tile.

---

### 6. Compressed-Domain On-Disk Stream Transforms (`lcv_accel.c` / `LcvOnDiskTransformer`)

To enable GB/s post-recording operations without pixel decoding (`REQ-LCV-F-005`), each active `CubeFrame` splits its payload into two contiguous byte slices tracked in the 64-bit tile pointer entry (`bits 31..16 = msbSliceByteLen16`, `bits 15..0 = lsbSliceByteLen16`):
- **Base MSB Slice:** `cubeHeader8` + UI Roaring payload (if `cubeMode = 00`) or `planeModes16` directory + compressed bitplanes `g7..g2` for all active channels.
- **Truncatable Tail LSB Slice:** Compressed bitplanes `g1..g0` for all active channels (`0 B` when `cubeMode != GRAY_PLANES_*`).

`lcv_accel.c` (`LcvOnDiskTransformer`) provides three zero-decode operations on `Superblock` streams:
1. **`lcv_native_crop_or_strip_superblock` (`cropSubRegion(superblockBytes, tileX0, tileY0, cropTilesX, cropTilesY)`):** Rebuilds a cropped `Superblock` of dimensions `(cropTilesX * 16) x (cropTilesY * 16)` in $O(\text{cropTilesX} \cdot \text{cropTilesY})$ by copying only the intersecting `uint64` pointer entries and their raw `CubeFrame` byte slices, adjusting `relByteOffset30` and recomputing `SEC-DED` syndromes.
2. **`lcv_native_crop_or_strip_superblock(..., stripLsb=1)` & `lcv_native_strip_lsb_outside_radius` (`stripLsbPlanes(superblockBytes)`):** Strips noisy `g1..g0` (or `g2..g0`) Section 4B bitplanes to create a lightweight proxy/archive stream by copying only `msbSliceByteLen16` bytes per tile, setting `lsbSliceByteLen16 = 0`, and clearing stripped mode bits to `00` (all-`0`) in the copied `planeModes16` headers—zero pixel or run decoding.
3. **`lcv_native_compact_superblock_zstd` (`coalesceRunsByDistance(superblockBytes, roiTileX, roiTileY, policy)`):** Walks only the `mode 10` (`4|4` nibble `adjGap | lenMinusOne`) token streams in `GRAY_PLANES_*` tiles, coalescing consecutive run tokens where `rawGap <= policy.maxMergeGap(plane, dist)` directly in the compressed bitstream and updating `msbSliceByteLen16` / `lsbSliceByteLen16` + `SEC-DED`.

---

## Requirements Traceability

| Requirement ID | Requirement Statement | Governing Implementation & Verification |
| :--- | :--- | :--- |
| **`REQ-LCV-F-001`** | When `d_merge == 0` and all bitplanes `g7..g0` are retained, the codec shall reconstruct 100% bit-exact `RGBA` pixel cubes (`PSNR = infinity`) across all enumerated `EncodingVariation` modes. | `lcv_accel.c`, `lcv_cli_and_test.c` (`LcvPrimitives`, `LcvCubeCodec`, `LcvCubeCodecTest`) |
| **`REQ-LCV-F-002`** | When `d_merge(plane, dist) > 0`, the encoder and compressed-domain compactor shall concatenate adjacent `1`-runs separated by `rawGap <= d_merge(plane, dist)` into a single valid `mode 10` (`4\|4` nibble `adjGap \| lenMinusOne`) run without requiring decoder modifications. | `lcv_accel.c`, `lcv_cli_and_test.c` (`LcvCubeCodec`, `LcvOnDiskTransformer`, `LcvCubeCodecTest`, `LcvStreamAndTransformTest`) |
| **`REQ-LCV-F-003`** | Truncating a `.lcv` or `.mkv (V_LCV1)` stream at any byte offset inside superblock $S$ shall leave all completed superblocks $0 \dots S-1$ immediately playable and $O(1)$ seekable without an end-of-file index table. | `lcv_accel.c`, `lcv_codec_system_plugins.c`, `lcv_cli_and_test.c` (`LcvStreamContainer`, `LcvMkvPacketizer`, `LcvRecoveryTortureTest`) |
| **`REQ-LCV-F-004`** | Any single-bit flip in a `Superblock` directory pointer shall be healed automatically via its Extended Hamming `(72, 64)` `SEC-DED` syndrome, and any structural corruption inside a tile payload shall be quarantined to that single $16 \times 16 \times K$ tile via `CBIT` bounds guards. | `lcv_accel.c`, `lcv_cli_and_test.c` (`LcvPrimitives`, `LcvStreamContainer`, `LcvRecoveryTortureTest`) |
| **`REQ-LCV-F-005`** | The stream transformer shall perform tile-aligned spatial cropping, LSB bitplane stripping, and distance-based `(adjGap, len)` run coalescing in the compressed domain without executing inverse Hilbert permutations or `YCoCg-R -> RGBA` pixel conversion. | `lcv_accel.c`, `lcv_cli_and_test.c` (`LcvOnDiskTransformer`, `LcvStreamAndTransformTest`, `LcvBenchmarkAndProofSuiteTest`) |

---

## Empirical Proof & Benchmark Summary

Validated by `resource/LeafVideo/native/lcv_cli_and_test.c` (`--self-test`) and the archived Java reference test suites (`LcvBenchmarkAndProofSuiteTest`, `LcvRecoveryTortureTest`, `LcvStreamAndTransformTest`, `LcvCubeCodecTest` via `./gradlew :leaf-video:test --console=plain -q`):

### 1. `.lcv` Operating Profiles (`LCV-ICD-1.0` Section 7)

| Workload (`640x368`, 16f) | Raw Size | `.lcv` (`OBS_REALTIME`) | `.lcv + Zstd` (`ARCHIVAL`) | `.lcv` (`WIRELESS_FOVEATED`) | `.lcv` (`HARSH_RF_UEP` K=1) | `.lcv` Compact (`s=3` + Zstd) | `d_merge` / `UEP` / `s=3` PSNR |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1. IDE / Desktop Screen Capture | `15,073,280 B` | `30,520 B` (`493.9x`) | `16,906 B` (`891.6x`) | `30,520 B` (`493.9x`) | `165,310 B` (`91.2x`) | `16,906 B` (`891.6x`) | `inf` / `inf` / `inf` |
| 2. Voxel Gameplay + Static HUD | `15,073,280 B` | **`66,020 B` (`228.3x`)** | **`17,162 B` (`878.3x`)** | **`66,020 B` (`228.3x`)** | `283,264 B` (`53.2x`) | **`17,162 B` (`878.3x`)** | `inf` / `inf` / `inf` |
| 3. 3D Continuous Gradient + Motion | `15,073,280 B` | **`3,650,160 B` (`4.1x`)** | **`3,639,522 B` (`4.1x`)** | `1,327,912 B` (`11.4x`) | **`531,274 B` (`28.4x`)** | **`24,321 B` (`619.8x`)** | `44.47 dB` / `44.50 dB` / `44.46 dB` |
| 4. Transparent RGBA VTuber/HUD Overlay | `15,073,280 B` | **`23,720 B` (`635.5x`)** | **`18,691 B` (`806.4x`)** | **`23,720 B` (`635.5x`)** | `184,992 B` (`81.5x`) | **`18,694 B` (`806.3x`)** | `inf` / `inf` / `inf` |

### 1B. Lossless Compression Ratio Comparison (CPU Software & GPU `NVENC` Lossless vs. `.lcv` Lossless)

| Workload (`640x368`, 16f) | `HuffYUV` | `UtVideo` | `QTRLE` | `FFV1 (lvl 3)` | `x265 Lossless` | `VP9 Lossless` | `x264rgb (-qp 0)` | `h264_nvenc` Lossless (`gbrp`) | `hevc_nvenc` Lossless (`gbrp`) | Flat `Zstd-1` | **`.lcv` (`OBS_REALTIME`)** | **`.lcv + Zstd` (`ARCHIVAL`)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1. IDE / Desktop Screen Capture | `3.8x` (`3,976,113 B`) | `7.8x` (`1,941,206 B`) | `259.2x` (`58,150 B`) | `21.7x` (`694,482 B`) | `212.6x` (`70,896 B`) | `161.4x` (`93,389 B`) | `261.9x` (`57,559 B`) | `155.4x` (`96,984 B`) | `244.9x` (`61,549 B`) | `227.3x` | **`493.9x` (`30,520 B`)** | **`891.6x` (`16,906 B`)** |
| 2. Voxel Gameplay + Static HUD | `3.0x` (`5,075,029 B`) | `6.8x` (`2,213,258 B`) | `38.5x` (`391,970 B`) | `15.7x` (`961,163 B`) | `35.1x` (`429,039 B`) | `108.8x` (`138,597 B`) | `35.9x` (`420,308 B`) | `43.3x` (`348,492 B`) | `79.5x` (`189,687 B`) | `613.0x` | **`228.3x` (`66,020 B`)** | **`878.3x` (`17,162 B`)** |
| 3. 3D Continuous Gradient + Motion | `2.4x` (`6,370,177 B`) | `2.8x` (`5,324,442 B`) | `1.0x` (`15,040,351 B`) | `3.4x` (`4,407,259 B`) | `3.6x` (`4,224,654 B`) | `3.4x` (`4,422,452 B`) | `3.0x` (`5,033,419 B`) | `2.5x` (`5,975,423 B`) | `3.1x` (`4,886,388 B`) | `1.4x` | **`4.1x` (`3,650,160 B`)** | **`4.1x` (`3,639,522 B`)** |
| 4. Transparent RGBA VTuber/HUD Overlay | `3.9x` (`3,822,297 B`) | `7.5x` (`2,009,822 B`) | `5.0x` (`3,031,988 B`) | `41.7x` (`361,289 B`) | `50.8x` (`296,609 B`) | `97.5x` (`154,664 B`) | `46.8x` (`322,098 B`) | `29.7x` (`506,853 B`) | `38.7x` (`389,325 B`) | `887.2x` | **`635.5x` (`23,720 B`)** | **`806.4x` (`18,691 B`)** |

### 1C. Equivalent Lossy / Compact Recording Comparison (OBS Default `NVENC CQP-18` & `x265 -crf 12` vs. `.lcv` Compact & Foveated, with `RGBA` PSNR)

| Workload (`640x368`, 16f) | OBS Default `h264_nvenc` (`P5 CQP-18 4:2:0`) | OBS `hevc_nvenc` (`P5 CQP-18 4:2:0`) | `x265 -crf 12` (`gbrp`) | **`.lcv` Foveated (`d_merge`, `K=8`)** | **`.lcv` Compact (`s=3` Stripped Raw)** | **`.lcv` Compact + `Zstd` (`s=3` Stripped)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1. IDE / Desktop Screen Capture | `400.1x` (`37,675 B`, `34.46 dB`) | `518.8x` (`29,052 B`, `34.39 dB`) | `261.7x` (`57,608 B`, `51.24 dB`) | **`493.9x` (`30,520 B`, `inf`)** | **`493.9x` (`30,520 B`, `inf`)** | **`891.6x` (`16,906 B`, `inf`)** |
| 2. Voxel Gameplay + Static HUD | `261.9x` (`57,543 B`, `34.07 dB`) | `402.9x` (`37,413 B`, `34.03 dB`) | `212.6x` (`70,913 B`, `49.31 dB`) | **`228.3x` (`66,020 B`, `inf`)** | **`228.3x` (`66,020 B`, `inf`)** | **`878.3x` (`17,162 B`, `inf`)** |
| 3. 3D Continuous Gradient + Motion | `637.2x` (`23,655 B`, `40.28 dB`) | `469.3x` (`32,120 B`, `42.37 dB`) | `916.5x` (`16,447 B`, `45.61 dB`) | **`11.4x` (`1,327,912 B`, `44.47 dB`)** | **`233.5x` (`64,544 B`, `44.46 dB`)** | **`619.8x` (`24,321 B`, `44.46 dB`)** |
| 4. Transparent RGBA VTuber/HUD Overlay | `923.0x` (`16,331 B`, `7.0 dB` RGBA / `42.7 dB` RGB, drops alpha) | `936.9x` (`16,088 B`, `7.0 dB` RGBA / `42.7 dB` RGB, drops alpha) | `220.1x` (`68,486 B`, `7.0 dB` RGBA / `47.4 dB` RGB, drops alpha) | **`635.5x` (`23,720 B`, `inf`)** | **`635.5x` (`23,720 B`, `inf`)** | **`806.3x` (`18,694 B`, `inf`)** |

### 1D. Multi-Codec Encode & Decode Throughput Comparison (`MB/s` — `Encode / Decode`)

| Workload (`640x368`, 16f) | `UtVideo` (`RGBA`) | `FFV1 (lvl 3)` | `x265` Lossless | `x264rgb (-qp 0)` | `h264_nvenc` Lossless (GPU) | `hevc_nvenc` Lossless (GPU) | OBS `h264_nvenc` `CQP-18` (GPU) | **`.lcv` `1T` (Lossless)** | **`.lcv` Parallel (Lossless)** | **`.lcv` Parallel (Compact `s=3`)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1. IDE / Desktop Screen Capture | `1242.5 / 1854.1` | `645.2 / 1784.8` | `980.5 / 2065.6` | `1121.9 / 1970.5` | `1604.0 / 1850.6` | `1345.0 / 2025.6` | `1464.2 / 2048.0` | `1656.8 / 3000.0` | **`8686.9 / 13763.6 MB/s`** | **`8784.4 / 14412.5 MB/s`** |
| 2. Voxel Gameplay + Static HUD | `1270.6 / 1981.2` | `573.5 / 1753.2` | `291.6 / 1981.7` | `1142.3 / 2187.1` | `1585.0 / 2224.4` | `1518.0 / 2014.7` | `1373.3 / 1600.4` | `473.4 / 2888.1` | **`3968.0 / 10431.7 MB/s`** | **`3372.7 / 11149.2 MB/s`** |
| 3. 3D Continuous Gradient + Motion | `1198.7 / 1586.7` | `298.5 / 463.6` | `52.1 / 511.0` | `1214.3 / 1625.4` | `1196.6 / 845.9` | `647.1 / 121.3` | `1357.2 / 2040.8` | `24.6 / 66.8` | **`311.2 / 750.6 MB/s`** | **`249.8 / 163.8 MB/s`** |
| 4. Transparent RGBA VTuber/HUD Overlay | `1310.0 / 1917.3` | `592.2 / 1709.6` | `699.9 / 2060.7` | `1303.1 / 1996.7` | `1473.0 / 2137.3` | `1609.0 / 1122.8` | `1419.1 / 1921.6` | `1620.1 / 2496.4` | **`9459.6 / 12529.7 MB/s`** | **`10220.4 / 13330.0 MB/s`** |

### 1E. Real Uncompressed Content Benchmark Corpus — `100%` Lossless Compression Comparison (CPU & GPU `NVENC` Lossless vs. `.lcv` Live & Post-Processed Lossless, `inf dB`)

| Clip ID & Uncompressed Source (`640x368`, 16f) | `UtVideo` | `FFV1 (lvl 3)` | `x265 Lossless` | `x264rgb (-qp 0)` | `h264_nvenc` Lossless (`gbrp`) | `hevc_nvenc` Lossless (`gbrp`) | **`.lcv` Live (`OBS_REALTIME`)** | **`.lcv` Post-Processed (`wrapZstdPools`)** | **`.lcv` Parallel (`Enc / Dec MB/s`)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| VOD-1: Native IDE / Terminal Scroll (`RGBA`) | `5.2x` (`2,924,290 B`) | `12.6x` (`1,192,540 B`) | `85.4x` (`176,593 B`) | `75.0x` (`201,087 B`) | `65.4x` (`230,638 B`) | `91.0x` (`165,691 B`) | **`73.1x` (`206,231 B`, `inf`)** | **`112.6x` (`133,827 B`, `inf`)** | **`109.6 / 2126.5 MB/s`** |
| VOD-2A: Xiph Derf `MINECRAFT` (`QT RGB24` Native 1:1 Crop) | `3.3x` (`4,552,502 B`) | `4.2x` (`3,552,336 B`) | `3.7x` (`4,111,822 B`) | `4.0x` (`3,727,100 B`) | `3.9x` (`3,821,938 B`) | `4.3x` (`3,489,626 B`) | **`3.2x` (`4,673,977 B`, `inf`)** | **`3.9x` (`3,837,772 B`, `inf`)** | **`65.4 / 418.9 MB/s`** |
| VOD-2B: Xiph Derf `MINECRAFT` (`QT RGB24` Non-Integer Scaled) | `2.5x` (`6,101,850 B`) | `3.5x` (`4,367,204 B`) | `3.1x` (`4,794,436 B`) | `2.9x` (`5,177,505 B`) | `3.2x` (`4,744,193 B`) | `3.4x` (`4,394,057 B`) | **`2.8x` (`5,414,459 B`, `inf`)** | **`3.0x` (`4,963,336 B`, `inf`)** | **`70.7 / 249.1 MB/s`** |
| VOD-3: Xiph Derf `park_joy` (`4:4:4`) | `1.8x` (`8,303,342 B`) | `2.2x` (`6,875,343 B`) | `2.2x` (`6,800,557 B`) | `1.9x` (`7,910,046 B`) | `2.0x` (`7,588,127 B`) | `2.3x` (`6,644,179 B`) | **`2.0x` (`7,657,758 B`, `inf`)** | **`2.0x` (`7,454,504 B`, `inf`)** | **`76.2 / 324.6 MB/s`** |
| VOD-4: `ffmpeg lavfi` VTuber/HUD Overlay (`RGBA`) | `7.4x` (`2,027,826 B`) | `123.5x` (`122,011 B`) | `195.0x` (`77,311 B`) | `247.1x` (`61,013 B`) | `312.2x` (`48,274 B`) | `240.2x` (`62,747 B`) | **`74.4x` (`202,673 B`, `inf`)** | **`110.7x` (`136,178 B`, `inf`)** | **`506.2 / 2652.5 MB/s`** |
| VOD-5: Xiph Derf `KristenAndSara` (`720p60` ROI) | `3.0x` (`5,048,366 B`) | `5.3x` (`2,836,220 B`) | `3.1x` (`4,796,416 B`) | `2.9x` (`5,203,621 B`) | `2.9x` (`5,221,921 B`) | `3.1x` (`4,898,517 B`) | **`3.1x` (`4,822,378 B`, `inf`)** | **`3.6x` (`4,180,216 B`, `inf`)** | **`71.9 / 453.1 MB/s`** |

### 1F. Real Uncompressed Content Benchmark Corpus — Equivalent Lossy / Compact Post-Processed Comparison (OBS Default `NVENC CQP-18` & `x265 -crf 12` vs. `.lcv` Post-Processed Compact & Foveated, with `RGBA` PSNR)

| Clip ID & Uncompressed Source (`640x368`, 16f) | OBS Default `h264_nvenc` (`P5 CQP-18 4:2:0`) | OBS `hevc_nvenc` (`P5 CQP-18 4:2:0`) | `x265 -crf 12` (`gbrp`) | **`.lcv` Post-Processed Compact (`s=3` + Zstd)** | **`.lcv` Post-Processed Foveated (`d_merge` + `s=3` + Zstd)** | **Post-Process Ratio Gain (vs. Live `.lcv`)** |
| :--- | ---: | ---: | ---: | ---: | ---: | ---: |
| VOD-1: Native IDE / Terminal Scroll (`RGBA`) | `298.2x` (`50,550 B`, `37.51 dB`) | `241.5x` (`62,419 B`, `37.48 dB`) | `78.9x` (`191,123 B`, `44.22 dB`) | **`233.8x` (`64,458 B`, `40.97 dB`)** | **`255.9x` (`58,912 B`, `34.01 dB`)** | **`+470.3%` (`5.70x`)** |
| VOD-2A: Xiph Derf `MINECRAFT` (`QT RGB24` Native 1:1 Crop) | `52.0x` (`289,808 B`, `39.10 dB`) | `53.4x` (`282,033 B`, `39.08 dB`) | `24.5x` (`616,224 B`, `40.23 dB`) | **`30.7x` (`491,469 B`, `36.64 dB`)** | **`61.8x` (`244,061 B`, `29.48 dB`)** | **`+1858.1%` (`19.58x`)** |
| VOD-2B: Xiph Derf `MINECRAFT` (`QT RGB24` Non-Integer Scaled) | `39.7x` (`379,664 B`, `34.50 dB`) | `41.9x` (`359,969 B`, `34.54 dB`) | `19.4x` (`777,079 B`, `38.78 dB`) | **`13.9x` (`1,080,542 B`, `34.22 dB`)** | **`40.3x` (`374,246 B`, `27.90 dB`)** | **`+1413.9%` (`15.14x`)** |
| VOD-3: Xiph Derf `park_joy` (`4:4:4`) | `27.1x` (`557,174 B`, `32.58 dB`) | `28.7x` (`525,354 B`, `32.57 dB`) | `12.6x` (`1,200,472 B`, `37.25 dB`) | **`6.7x` (`2,233,810 B`, `32.26 dB`)** | **`13.1x` (`1,149,497 B`, `27.15 dB`)** | **`+584.4%` (`6.84x`)** |
| VOD-4: `ffmpeg lavfi` VTuber/HUD Overlay (`RGBA`) | `1119.9x` (`13,459 B`, `6.1 dB` RGBA / `44.6 dB` RGB, drops alpha) | `1094.8x` (`13,768 B`, `6.1 dB` RGBA / `44.8 dB` RGB, drops alpha) | `166.7x` (`90,402 B`, `6.1 dB` RGBA / `48.8 dB` RGB, drops alpha) | **`224.8x` (`67,059 B`, `50.38 dB`)** | **`232.9x` (`64,727 B`, `49.45 dB`)** | **`+226.9%` (`3.27x`)** |
| VOD-5: Xiph Derf `KristenAndSara` (`720p60` ROI) | `99.2x` (`151,923 B`, `40.72 dB`) | `112.2x` (`134,310 B`, `40.67 dB`) | `59.1x` (`255,009 B`, `40.92 dB`) | **`46.4x` (`324,871 B`, `33.20 dB`)** | **`122.0x` (`123,577 B`, `29.22 dB`)** | **`+3992.4%` (`40.92x`)** |

### 2. Compressed-Domain On-Disk Transform & Non-Temporal Splice Speed (`lcv_accel.c` / `LcvOnDiskTransformer`)

- **`cropSubRegion` (Zero-Decode Tile Sub-Region Extraction):** `0.238 ms` (**`688.5x` faster** than full-frame decode + crop + re-encode at `164.061 ms`), with `100%` bit-exact pixel parity.
- **`stripLsbPlanes` (`g7..g0 -> g7..g2` Section 4B Proxy Truncation):** `0.213 ms` (`0` pixel decode; works on both uncompressed and `FLAG_ZSTD_POOL` superblocks with zero cumulative drift across `GRAY_PLANES_HILBERT_DELTA`).
- **`wrapZstdPools` & `postprocessStreamInPlace` (Compressed-Domain Pool Zstd Compaction):** `2.987 ms` (`0` pixel or Hilbert decode; delivers `2.94x–14.84x` post-processed compression gains on real uncompressed content when paired with `conditionFramesBySection` + `s=3`).
- **`spliceEditedSuperblockNonTemporal` + `indexValidSuperblocks` (Non-Temporal Editor Splice):** `0.174 ms` (**`297.8x` faster** than full-stream temporal rewrite at `51.782 ms`).
- **`coalesceRunsByDistance` (Compressed-Domain `(adjGap, lenMinusOne)` Token Coalescer):** `117.488 ms`, producing a **100% byte-for-byte identical** payload to direct `DistanceRlePolicy` encoding from raw pixels.

### 3. `.mkv (V_LCV1)` Crash & Bit-Rot Recovery Torture Matrix (`lcv_cli_and_test.c` / `LcvRecoveryTortureTest`)

- **Mode 1 (`SIGKILL` Mid-Write Random Byte Truncation):** `100 / 100` random byte-offset truncations across a multi-superblock `.mkv (V_LCV1)` stream recovered `100%` of completed preceding superblocks with `0 ms` linear frame decoding and `100%` bit-exact reconstruction.
- **Mode 2 (`SEC-DED (72, 64)` Single-Bit Pointer Flip Healing):** `100%` of random single-bit flips injected into every `uint64` tile pointer entry across the directory were healed in place with `0` quarantined tiles and `0` pixel errors.
- **Mode 3 (Burst `4 KiB` Disk Sector Zeroing + `Section 4B` `lsbTileOffsets` Isolation):** Zeroing a `4,096 B` sector mid-superblock quarantined only the affected tiles via `CBIT` (falling back cleanly to the prior superblock's cached tiles) while keeping `> 94%` of the frame `100%` bit-exact with zero `DED` offset contagion across `Section 4B`.
