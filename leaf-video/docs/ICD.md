# Interface Control Document: `.lcv` / `V_LCV1` (LeafCraft Video) Binary Specification

- **Document ID:** `LCV-ICD-1.0`
- **Format Version:** `0x0001` (`"LCV_V001"`)
- **License:** `MIT` ([`leaf-video/LICENSE`](../LICENSE) — Open Standard & Reference Specification; zero lock-in, royalty-free interoperability)
- **Governing ADRs:** [`leaf-video-ADR-001`](adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md), [`leaf-video-ADR-002`](adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md), [`leaf-video-ADR-003`](adr/leaf-video-ADR-003-spatiotemporal-database-decode-map-and-causal-compaction.md), [`leaf-video-ADR-004`](adr/leaf-video-ADR-004-bitwise-simd-and-non-video-hardware-accelerator-offload.md)
- **Canonical Reference Implementation:** `resource/LeafVideo/native/` (`leafvideo.h` public C99/C11 SDK header; `lcv_accel.c` C11 reference decoder, encoder, compressed-domain transformer, `AVX2`/`NEON` SIMD kernels, and inline CUDA Driver API PTX kernels `lcv_batch_analyze_superblock`, `lcv_gpu_encode_tiles`, `lcv_gpu_assemble_superblock`; `lcv_cube_kernel.comp` `Vulkan` `GLSL 4.50` compute shader; `lcv_codec_system_plugins.c` container/frame probe & thumbnail SDK; `obs_leafvideo_plugin.c` `libobs v30` plugin; `lcv_cli_and_test.c` native CLI & Gate 1 verification suite).
- **Endianness & `VarInt` Convention:** All multi-byte fixed-width integers (`uint16`, `uint24`, `uint32`, `uint48`, `uint64`) are **Big-Endian (Network Byte Order)**. Unsigned `VarInt` fields use 7-bit little-endian base-128 (`LEB128`) continuation encoding bounded to at most `4 bytes` (`shift < 28`, range $0 \dots 2^{28}-1$): each byte contributes `(byte & 0x7F) << shift` while `(byte & 0x80) != 0`; decoders MUST reject any `VarInt` whose 4th byte has bit `7` set (`shift >= 28`) or that overruns the slice limit (`lcv_read_varint`).

---

## 1. Scope & Design Invariants

This Interface Control Document (ICD) defines the **all-or-none normative wire grammar** and deterministic integer reconstruction rules of `.lcv` standalone stream files, Matroska `.mkv` (`CodecID = "V_LCV1"`) packet payloads, and `LCVU` UDP datagrams. Sections 1–6, 7.1, 8, and 9.1 are **Normative** (every conformant decoder MUST implement them bit-for-bit with zero undefined behavior). Sections 7.2–7.4 and 9.2 are **Informative** (encoder decision heuristics, operating presets, multi-fovea SDF shapes, and hardware workgroup dispatch that emit standard `0x0001` wire syntax without changing decoder state).

> **Open Format & Interoperability License (`MIT`):** While the commercial Tier 2 Studio GUI installers and Tier 3 Native SIMD/GPU & Wireless UEP SDK binaries (`resource/LeafVideo`) are distributed under the **LeafCraft Commercial Software License** (`LicenseRef-LeafCraft-Commercial`, [`resource/LeafVideo/LICENSE`](../../resource/LeafVideo/LICENSE)), this `.lcv` / `V_LCV1` binary specification (`LCV-ICD-1.0`) and the `:leaf-video` module (`leaf-video/`) are openly licensed under the **MIT License** ([`leaf-video/LICENSE`](../LICENSE)). Streamers, video editors, NLE plugins, `ffmpeg`/VLC demuxers, and hardware integrations may freely inspect, implement, or reverse-engineer `.lcv` / `V_LCV1` readers, writers, and compressed-domain transformers without royalty or license-server lock-in.

1. **Spatiotemporal Grid Partitioning & Frame Bounds:** Video frames of width $W \in [1, 65535]$ and height $H \in [1, 65535]$ are grouped in temporal windows of $K \in \{1, 2, 4, 8, 16\}$ frames (`Superblock`) and partitioned spatially into $\text{tilesX} = \lceil W / 16 \rceil$ by $\text{tilesY} = \lceil H / 16 \rceil$ tiles, subject to the 16-bit directory bound $1 \le \text{tilesX} \cdot \text{tilesY} \le 65535$. Each tile forms an $16 \times 16 \times K$ spatiotemporal cube ($N_{\text{samples}} = 256 \cdot K$, linear index $\text{tyx} = (lt \ll 8) \mid (ly \ll 4) \mid lx$ for $lt \in [0, K-1]$ and $lx, ly \in [0, 15]$). When $W$ or $H$ is not a multiple of $16$, encoders pad out-of-frame sample coordinates $(x \ge W \text{ or } y \ge H)$ by repeating the boundary pixel $(x = \min(px, W-1), y = \min(py, H-1))$, and decoders MUST crop scattered tile pixels strictly to $[0, W-1] \times [0, H-1]$ (`lcv_scatter_cube_tyx_to_frames`).
2. **Outcome-Driven Enumerated Variations:** Each cube encodes using one of the normative variations in Sections 4 and 5 (`UNIFORM_INLINE`, `TEMPORAL_CLEAN_ACK`, `UI_PALETTE_ROARING` with sub-variations `ROARING_SINGLETON` / `ROARING_DELTA_RUN` / `ROARING_DELTA_ARRAY` / `ROARING_BITMAP`, `GRAY_PLANES_RAW`, `GRAY_PLANES_HILBERT_DELTA`, and `RAW_CEILING`).
3. **Wire-Compatible Distance-Scaled RLE Concatenation:** Variable lossy run concatenation (`rawGap <= d_merge(plane, dist)`) emits standard `mode 10` (`4|4` nibble `adjGap | lenMinusOne`) tokens and requires zero special decoder flags.
4. **Tunable Split MSB / LSB Slice Framing & Single-File Studio Proxy Invariant:** For channel bit depth $B = \text{bitDepth8} \in \{8, 10, 12\}$ and configurable split parameter $s = \text{lsbSplitBits} \in \{1, 2, 3, 4\}$ (default $s = 2$), active `GRAY_PLANES_*` cubes split their $B$ encoded bitplanes at bit boundary $B_{\text{lsb}} = (B - 8) + s$:
   - **Section 4A (Base MSB Slice):** Always holds the top $B_{\text{msb}} = 8 - s \in \{4, 5, 6, 7\}$ bitplanes (`g_{B-1} .. g_{B_{\text{lsb}}}`).
   - **Section 4B (Truncatable Tail LSB Slice):** Holds the bottom $B_{\text{lsb}} = (B - 8) + s \in \{1 \dots 8\}$ bitplanes (`g_{B_{\text{lsb}}-1} .. g_0`), combining the $(B - 8)$ deep-color studio mezzanine planes and the $s$ UEP split planes.
   - **Single-File Studio Proxy Invariant (`lcv_native_decode_superblock_rgba8_preview`):** Decoding only `Section 4A` (`forceSection4APreviewOnly = true` or `lsbLen == 0`) reconstructs each channel's $B_{\text{msb}}$-bit MSB $m \in [0, M_{\text{msb}}]$ ($M_{\text{msb}} = 2^{B_{\text{msb}}} - 1$), fills the omitted $B_{\text{lsb}}$ LSB bits with the clamped midpoint $\text{fill} = (m == 0) ? 0 : ((m == M_{\text{msb}}) ? (2^{B_{\text{lsb}}} - 1) : 2^{B_{\text{lsb}} - 1})$, applies inverse `YCoCg-R` at bit depth $B$, and right-shifts each $B$-bit channel by $(B - 8)$ bits (`(ch16 >> (B - 8)) & 0xFF`) to yield an exact 8-bit `RGBA32` preview without reading `Section 4B`.

---

## 2. File & Stream Layout Overview (`Superblock`)

A `.lcv` stream is an append-only sequence of self-contained `Superblock` records. There is no mandatory end-of-file footer; truncating the stream mid-write leaves all completed `Superblock` records valid and $O(1)$ seekable. Let $N_{\text{tiles}} = \text{tilesX16} \cdot \text{tilesY16}$ and let $N_{\text{words}}$ be the directory word count ($N_{\text{words}} = N_{\text{tiles}}$ when `COLOR_FLAG_QUADTREE_DIR == 0`, or $N_{\text{words}} = \text{directoryWords16}$ when `COLOR_FLAG_QUADTREE_DIR == 1`):

```
+-----------------------------------------------------------------------+ Offset 0x0000
| 1. Superblock Header: "LCV_V001" (64 bytes, Big-Endian)               |
+-----------------------------------------------------------------------+ Offset 0x0040
| 2. 2D Tile Pointer Directory: uint64[N_words] (8 B / word)            |
+-----------------------------------------------------------------------+ Offset 0x0040 + 8 * N_words
| 3. Extended Hamming (72, 64) SEC-DED Syndrome Table (1 B / word)      |
+-----------------------------------------------------------------------+ Offset 0x0040 + 9 * N_words
| 4A. Base MSB Slice Pool (msbPoolCompressedLen32 bytes; Raw or Zstd)   |
|     - Tile (tx, ty) Base MSB Slice (msbSliceByteLen16 bytes:          |
|       top B_msb = 8 - s planes g_{B-1}..g_{B_lsb}; 8-bit preview)     |
+-----------------------------------------------------------------------+ Offset 0x0040 + 9 * N_words + msbPoolCompressedLen32
| 4B. Tail LSB Slice Pool (lsbPoolCompressedLen32 bytes; Raw or Zstd)   |
|     - [Optional 4 B Checkpoint when FLAG_INLINE_RECODE_PARTIAL == 1]  |
|     - uint32 lsbTileOffsets[N_tiles] (4 * N_tiles bytes, Big-Endian)  |
|     - Tile (tx, ty) Tail LSB Slice (lsbSliceByteLen16 bytes:          |
|       bottom B_lsb = (B - 8) + s planes g_{B_lsb - 1}..g_0)           |
+-----------------------------------------------------------------------+ Offset superblockByteSize
```

---

## 3. Section 1 — `64-Byte` Superblock Header (`0x0000..0x003F`)

Decoders (`lcv_parse_superblock_header_ex`) MUST validate all fields below and reject the `Superblock` if any invariant fails:

| Byte Offset | Type | Field Name | Normative Definition & Validation Rules |
| :--- | :--- | :--- | :--- |
| `0x00..0x07` | `uint64` | `magic64` | ASCII `"LCV_V001"` (`0x4C43565F56303031ULL`). Decoders MUST reject any Superblock where `magic64 != 0x4C43565F56303031ULL`. |
| `0x08..0x09` | `uint16` | `formatVersion16` | `0x0001` (`LCV-ICD-1.0`). Decoders MUST reject any Superblock where `formatVersion16 != 0x0001`. |
| `0x0A..0x0B` | `uint16` | `width16` | Frame width in pixels ($1 \le W \le 65535$). Decoders MUST reject `width16 == 0`. |
| `0x0C..0x0D` | `uint16` | `height16` | Frame height in pixels ($1 \le H \le 65535$). Decoders MUST reject `height16 == 0`. |
| `0x0E` | `uint8` | `depthK8` | Temporal depth $K \in \{1, 2, 4, 8, 16\}$ frames in this superblock. Decoders MUST reject any Superblock where `depthK8` $\notin \{1, 2, 4, 8, 16\}$. |
| `0x0F` | `uint8` | `bitDepth8` | Channel bit depth $B \in \{8, 10, 12\}$ (`8` = 8-bit `RGBA32`; `10` = 10-bit studio mezzanine; `12` = 12-bit DI/mastering). Decoders MUST reject any Superblock where `bitDepth8` $\notin \{8, 10, 12\}$. |
| `0x10` | `uint8` | `colorSpace8` | Bits `6..0`: `0x00 = YCoCg_R_RGBA`, `0x01 = DIRECT_RGBA` (values `0x02..0x7F` are reserved and MUST be `0`; per-cube `LCV_DELTA_FLAG_DIRECT_RGBA` overrides `0x00` to direct `RGBA` for that cube). Bit `7` (`0x80`): `LCV_COLOR_FLAG_QUADTREE_DIR` (`1` = Section 2 & 3 use `16/32/64` quadtree directory coalescing). |
| `0x11` | `uint8` | `flags8` | Bit 0 (`0x01`): `LCV_FLAG_ZSTD_POOL` (Section 4A and Section 4B are compressed as independent size-prefixed Zstd frames; Section 9.1).<br>Bit 1 (`0x02`): `LCV_FLAG_LSB_STRIPPED` (Section 4B tail pool stripped; `lsbPoolCompressedLen32 == 0`).<br>Bit 2 (`0x04`): `LCV_FLAG_INLINE_RECODE_PARTIAL` (uncompressed Section 4B begins with 4-byte checkpoint `(uint16 recodedCursor16, uint16 priorTuningProfile16)` before `lsbTileOffsets[N_tiles]`).<br>Bit 3 (`0x08`): `LCV_FLAG_KEYED_CODEBOOK` (reserved for keyed structural codebook; `0` in unkeyed streams).<br>Bit 4 (`0x10`): `LCV_FLAG_SUPERSEDED_OR_SLACK` (in-place slack gap or superseded record skipped by sequential/Matroska readers).<br>Bit 5 (`0x20`): `LCV_FLAG_ZSTD_DICT_POOL` (reserved dictionary flag; `0` in standalone superblocks).<br>Bit 6 (`0x40`): `LCV_FLAG_DECODE_MAP_INDIRECTION` (reserved indirection flag).<br>Bit 7 (`0x80`): Reserved (`0`). |
| `0x12..0x13` | `uint16` | `tilesX16` | Horizontal tile count. MUST equal $\lceil W / 16 \rceil = (W + 15) \gg 4$, and $N_{\text{tiles}} = \text{tilesX16} \cdot \text{tilesY16}$ MUST satisfy $1 \le N_{\text{tiles}} \le 65535$. |
| `0x14..0x15` | `uint16` | `tilesY16` | Vertical tile count. MUST equal $\lceil H / 16 \rceil = (H + 15) \gg 4$. |
| `0x16..0x17` | `uint16` | `roiTileX16` | Focal ROI center tile X coordinate for encoder/transformer distance-scaled RLE (informative to decoders). |
| `0x18..0x19` | `uint16` | `roiTileY16` | Focal ROI center tile Y coordinate for encoder/transformer distance-scaled RLE (informative to decoders). |
| `0x1A..0x1B` | `uint16` | `tuningProfile16` | Packed rate-latency-resilience descriptor (`Section 7.1`; `0x0000` = default lossless with $s = 2$ LSB split bits and $M_{\text{refresh}} = 8$). |
| `0x1C..0x1F` | `uint32` | `msbPoolCompressedLen32` | On-disk byte length of Section 4A (Base MSB Slice Pool; MUST satisfy `0x0040 + 9 * N_words + msbPoolCompressedLen32 <= sbLen`). |
| `0x20..0x23` | `uint32` | `lsbPoolCompressedLen32` | On-disk byte length of Section 4B (Tail LSB Slice Pool; `0` when `LCV_FLAG_LSB_STRIPPED` is set). |
| `0x24..0x2B` | `uint64` | `frameIndex64` | Starting presentation frame index ($0, K, 2K, \dots$). |
| `0x2C..0x33` | `uint64` | `prevSbOffset64` | Absolute stream byte offset of the immediately preceding physical `Superblock` (`0` for the first superblock), enabling $O(1)$ reverse seeking without an EOF index. |
| `0x34..0x37` | `uint32` | `fpsNum32` | Frame rate numerator (`> 0`, e.g., `60`). |
| `0x38..0x3B` | `uint32` | `fpsDen32` | Bits `15..0`: Frame rate denominator (`uint16`, `> 0`, e.g., `1`).<br>Bits `31..16`: `directoryWords16` when `LCV_COLOR_FLAG_QUADTREE_DIR` (`0x80`) is set in `colorSpace8` (MUST satisfy `ctuWords <= directoryWords16 <= N_tiles`; `0` when flat). |
| `0x3C..0x3F` | `uint32` | `headerCrc32` | Standard reflected **IEEE 802.3 CRC-32** digest (polynomial `0xEDB88320`, initial value `0xFFFFFFFF`, final XOR `0xFFFFFFFF`) computed over header bytes `0x00..0x3B` (`lcv_compute_header_crc32`). Decoders MUST reject any Superblock whose computed CRC-32 does not equal `headerCrc32`. |

---

## 4. Section 2 & 3 — 2D Tile Pointer Directory & `SEC-DED (72, 64)` Syndrome Table

For tile coordinates $(\text{tx}, \text{ty})$ with $0 \le \text{tx} < \text{tilesX}$ and $0 \le \text{ty} < \text{tilesY}$, the linear tile index is:
$$\text{tileIdx} = \text{ty} \cdot \text{tilesX} + \text{tx}$$

- **Flat Directory Mode (`(colorSpace8 & 0x80) == 0`):** Section 2 stores $N_{\text{words}} = N_{\text{tiles}} = \text{tilesX} \cdot \text{tilesY}$ flat `uint64` pointer entries (`8 B` each at `0x0040 + 8 * tileIdx`) and Section 3 stores $N_{\text{words}}$ `SEC-DED (72, 64)` syndrome bytes (`1 B` each at `0x0040 + 8 * N_words + tileIdx`).
- **Quadtree Coalesced Directory Mode (`(colorSpace8 & 0x80) != 0`, `lcv_try_pack_quadtree_directory` & `lcv_unpack_superblock_directory`):** The tile grid is partitioned into $\text{ctuX} = \lceil \text{tilesX} / 4 \rceil$ by $\text{ctuY} = \lceil \text{tilesY} / 4 \rceil$ $64 \times 64$ Coding Tree Units ($N_{\text{ctu}} = \text{ctuX} \cdot \text{ctuY}$ in row-major order $\text{ctuIdx} = \text{cy} \cdot \text{ctuX} + \text{cx}$, packed `8` CTUs per word across $N_{\text{ctuWords}} = \lceil N_{\text{ctu}} / 8 \rceil$ words, total directory words $N_{\text{words}} = \text{directoryWords16} = N_{\text{ctuWords}} + N_{\text{leaves}} \le 65535$):
  - **Words `0 .. N_ctuWords - 1` (`ctuWord64[w]`):** Each `SEC-DED (72, 64)`-protected `uint64` word packs `uint16 groupLeafBase16` in `bits 63..48` (the cumulative starting leaf index in `leafPtr64[]` for CTU `8 * w`) and up to eight `6-bit` descriptors `desc6` in `bits (6 * j + 5) .. (6 * j)` for $j \in \{0 \dots 7\}$ ($\text{ctuIdx} = 8w + j < N_{\text{ctu}}$, with unused trailing slots $8w + j \ge N_{\text{ctu}}$ set to `0`):
    - `desc6 == 0x00`: The entire in-bounds region of the $4 \times 4$-tile ($64 \times 64$ px) CTU at $(\text{tx}_0 = 4\text{cx}, \text{ty}_0 = 4\text{cy})$ is coalesced into **`1` zero-body leaf pointer** in `leafPtr64[]` (`UNIFORM_INLINE` or `TEMPORAL_CLEAN_ACK` with `bit 63 == 1`), which fills all in-bounds tiles $(\text{tx}_0 + dx, \text{ty}_0 + dy)$ for $0 \le dx < \min(4, \text{tilesX} - \text{tx}_0)$ and $0 \le dy < \min(4, \text{tilesY} - \text{ty}_0)$.
    - `desc6 = 0x10 | splitMask4` (`(desc6 & 0x30) == 0x10`, `splitMask4 = desc6 & 0x0F`): The CTU is partitioned into four $2 \times 2$-tile ($32 \times 32$ px) quadrants $q \in \{0, 1, 2, 3\}$ in raster order $\text{qx}_0 = \text{tx}_0 + 2(q \ \& \ 1)$, $\text{qy}_0 = \text{ty}_0 + 2(q \gg 1)$ with clipped dimensions $qw = \min(2, \text{tilesX} - \text{qx}_0)$ and $qh = \min(2, \text{tilesY} - \text{qy}_0)$:
      - If $qw \le 0$ or $qh \le 0$ (quadrant lies completely outside the frame boundary), quadrant $q$ consumes **`0` leaves**.
      - Otherwise, if `((splitMask4 >> q) & 1) == 0`, quadrant $q$ consumes **`1` zero-body leaf pointer** (`bit 63 == 1`), filling all $qw \times qh$ in-bounds tiles of quadrant $q$.
      - Otherwise (`((splitMask4 >> q) & 1) == 1`), quadrant $q$ consumes **$qw \cdot qh$ individual $16 \times 16$ leaf pointers** in row-major order ($dy = 0 \dots qh - 1$ outer, $dx = 0 \dots qw - 1$ inner).
    - Any other `desc6` bit pattern (`(desc6 & 0x30) != 0x10 && desc6 != 0`) or a `ctuWord64[w]` that fails `SEC-DED` with `LCV_UNCORRECTABLE_DED_SENTINEL` quarantines all tiles covered by that CTU (`LCV_UNCORRECTABLE_DED_SENTINEL`).
  - **Words `N_ctuWords .. N_words - 1` (`leafPtr64[leafIdx]`):** The $N_{\text{leaves}}$ coalesced and per-tile `ptr64` entries, each paired with its `SEC-DED (72, 64)` syndrome byte at `0x0040 + 8 * N_words + N_ctuWords + leafIdx`.

### 4.1 `uint64` Tile Pointer Entry (`ptr64`)

Each unpacked tile pointer `ptr64` is a 64-bit word with two layouts selected by `bit 63`:

#### Case 1: Inline `0-Byte` Body (`bit 63 == 1`)
- **`bit 63 = 1`, `bit 62 = 0` (`UNIFORM_INLINE` — Precedence Rule):** All $256 \cdot K$ samples in the cube have the exact same `RGBA` color. `UNIFORM_INLINE` takes precedence over `TEMPORAL_CLEAN_ACK` so uniform static tiles require zero backward references.
  - When `bitDepth8 == 8`: `bits 61..32` (`30 bits`) = Reserved (`0`), `bits 31..0` (`32 bits`) = `exactRgba32` (exact 32-bit `0xRRGGBBAA` pixel value). Body size is **`0 bytes`**.
  - When `bitDepth8 ∈ {10, 12}`: `bits 61..48` (`14 bits`) = Reserved (`0`), `bits 47..0` (`48 bits`) = `exactRgba48` packing four 12-bit unsigned channels `((uint64_t)R << 36) | ((uint64_t)G << 24) | ((uint64_t)B << 12) | (uint64_t)A` where each channel is masked to `maskB = (1 << bitDepth8) - 1` (`lcv_unpack_rgba64_from_rgba48`). Body size is **`0 bytes`**.
- **`bit 63 = 1`, `bit 62 = 1` (`TEMPORAL_CLEAN_ACK` — Bounded `Case A` & Motion-Shifted Reference):** The non-uniform cube copies its $16 \times 16 \times K$ samples from the reconstructed prior superblock canvas shifted by $(\Delta x, \Delta y) \in [-8, +7]^2$ pixels (`lcv_apply_shifted_or_fallback_tile`) within the bounded refresh horizon $1 \le \Delta_{\text{sb}} \le \min(63, M_{\text{refresh}})$ configured in `tuningProfile16` ($M_{\text{refresh}} \in \{0, 1, 2, 4, 8, 16, 32, 64\}$, default `8`).
  - `bits 61..46` (`16 bits`): `anchorSbDelta16`:
    - `bits 5..0` (`6 bits`): $\Delta_{\text{sb}} \in [1, 63]$.
    - `bits 13..6` (`8 bits`): `encodedShift8 = (((dx + 8) << 4) | ((dy + 8) & 0x0F)) ^ 0x88`, so $(\Delta x, \Delta y) = (0, 0)$ encodes as `0x00`, and on decode `rawShift8 = encodedShift8 ^ 0x88`, $\Delta x = ((\text{rawShift8} \gg 4) \ \& \ 0x0F) - 8 \in [-8, +7]$, $\Delta y = (\text{rawShift8} \ \& \ 0x0F) - 8 \in [-8, +7]$ (`lcv_extract_clean_ack_shift_dx` / `dy`).
    - `bits 15..14` (`2 bits`): Reserved (`0`).
  - `bits 45..16` (`30 bits`): `anchorRelMsbOffset30` (advisory relative byte offset in the anchor superblock's Section 4A pool).
  - `bits 15..0` (`16 bits`): `anchorMsbByteLen16` (advisory byte length in the anchor superblock's Section 4A pool).
  - **Shifted Copy & Boundary Clamping Rule (`lcv_apply_shifted_or_fallback_tile`):** For each frame $t \in [0, K-1]$ and each in-bounds pixel $(px = 16\text{tx} + lx, py = 16\text{ty} + ly)$ with $px < W, py < H$, the decoder reads from the prior superblock's reconstructed frame $t$ at clamped source coordinates $sx = \text{clamp}(px + \Delta x, 0, W - 1)$ and $sy = \text{clamp}(py + \Delta y, 0, H - 1)$ (or fills `0x000000FF` opaque black if no prior frame buffer is provided). Body size in the current superblock is **`0 bytes`**.

#### Case 2: Active `CubeFrame` Payload (`bit 63 == 0`)
| Bits | Width | Field Name | Normative Definition & Bounds Check |
| :--- | :--- | :--- | :--- |
| `63` | `1 bit` | `isZeroBody` | `0` (active payload in Section 4A / 4B). |
| `62` | `1 bit` | `reservedZero` | MUST be `0` for valid active pointers. A word equal to `0x4000000000000000ULL` (`bit 63 = 0, bit 62 = 1`) is the `LCV_UNCORRECTABLE_DED_SENTINEL` returned when `SEC-DED` detects an uncorrectable corruption. |
| `61..32` | `30 bits` | `relByteOffset30` | Relative byte offset ($0 \dots 2^{30}-1$, `1 GiB` pool ceiling) of this tile's Base MSB Slice from the start of the uncompressed Section 4A pool. |
| `31..16` | `16 bits` | `msbSliceByteLen16` | Byte length ($1 \dots 65535$) of the **Base MSB Slice** in Section 4A. Decoders MUST quarantine the tile if `msbSliceByteLen16 == 0` or `relByteOffset30 + msbSliceByteLen16 > msbPoolUncompressedLen`. |
| `15..0` | `16 bits` | `lsbSliceByteLen16` | Byte length ($0 \dots 65535$) of the **Truncatable Tail LSB Slice** in Section 4B (`0` when `UI_PALETTE_ROARING`, `RAW_CEILING`, all LSB planes are `PLANE_ALL_ZERO`, or the tile's Section 4B slice was stripped). |

**Section 4B Direct `O(1)` Offset Index (`uint32 lsbTileOffsets[N_tiles]`):**
Whenever Section 4B is present (`lsbPoolCompressedLen32 > 0` and `LCV_FLAG_LSB_STRIPPED == 0`), let `checkpointBytes = ((flags8 & LCV_FLAG_INLINE_RECODE_PARTIAL) != 0) ? 4 : 0`. The uncompressed Section 4B pool begins with the optional 4-byte inline recode checkpoint at offset `0..3` followed at `lsbTableBase = checkpointBytes` by a flat `uint32[N_tiles]` Big-Endian offset table (`4 * N_tiles` bytes, always indexed by linear `tileIdx = ty * tilesX + tx` even when `LCV_COLOR_FLAG_QUADTREE_DIR == 1`). The Section 4B slice data area starts at `lsbDataBase = checkpointBytes + 4 * N_tiles`:
1. When `lsbSliceByteLen16 > 0`, tile `tileIdx`'s Tail LSB Slice is located at `[lsbDataBase + lsbTileOffsets[tileIdx], lsbDataBase + lsbTileOffsets[tileIdx] + lsbSliceByteLen16)`. Decoders MUST quarantine the tile if `lsbDataBase + lsbTileOffsets[tileIdx] + lsbSliceByteLen16 > lsbPoolUncompressedLen`.
2. When `lsbSliceByteLen16 == 0`, the tile consumes `0` bytes in the Section 4B payload area (encoders write the current `lsbCursor` into `lsbTileOffsets[tileIdx]`, and decoders do not dereference Section 4B for that tile).
3. If tile $j < \text{tileIdx}$ suffers an uncorrectable `DED` in its `ptr64`, subsequent healthy tiles $i > j$ still locate their exact Section 4B slices via `lsbTileOffsets[i]` with zero offset contagion.

### 4.2 Extended Hamming `(72, 64)` `SEC-DED` Syndrome Table (`1 B / word`)

Every 64-bit directory word `word64` in Section 2 (`ctuWord64[w]` and `leafPtr64[leafIdx]` in quadtree mode, or `ptr64[tileIdx]` in flat mode) is paired with a 1-byte `syndrome8` at `0x0040 + 8 * N_words + wordIdx` (`lcv_ensure_tables_initialized`, `lcv_compute_hamming_secded8`, `lcv_heal_single_bit_flip64`):
1. **Bit-to-Position Mapping:** Iterate 1-indexed code positions $\text{pos} \in \{1 \dots 71\}$. Skip every power-of-two parity position ($\text{pos} \in \{1, 2, 4, 8, 16, 32, 64\}$ where $(\text{pos} \ \& \ (\text{pos} - 1)) == 0$). Assign the remaining `64` non-power-of-two positions $\text{pos}_0 = 3, \text{pos}_1 = 5, \text{pos}_2 = 6, \text{pos}_3 = 7, \text{pos}_4 = 9, \dots, \text{pos}_{63} = 71$ in strictly ascending order to data bits $b = 0 \dots 63$ of `word64` (`d_b = (word64 >> b) & 1`).
2. **Syndrome Encoding (`syndrome8`):**
   - For each Hamming parity bit $p \in \{0 \dots 6\}$, bit $p$ of `syndrome8` is the XOR of all data bits $b \in \{0 \dots 63\}$ whose code position $\text{pos}_b$ has bit $p$ set (`(pos_b & (1 << p)) != 0`).
   - Bit `7` (`0x80`) of `syndrome8` is the overall even parity across all 64 data bits and 7 Hamming parity bits:
     $$\text{bit}_7 = \left(\text{popcount64}(\text{word64}) \oplus \text{popcount8}(\text{syndrome8} \ \& \ 0\text{x7F})\right) \& \ 1$$
3. **Syndrome Verification & Single-Bit Healing:** On read, compute `diff = (lcv_compute_hamming_secded8(word64) ^ syndrome8) & 0xFF`:
   - `diff == 0`: `word64` is error-free; return `word64`.
   - `(diff & 0x80) != 0`: Single-bit error detected. Let `syn7 = diff & 0x7F`. If `syn7` matches a data code position $\text{pos}_b$ ($b \in \{0 \dots 63\}$), return `word64 ^ (1ULL << b)` (healed in place). Otherwise (`syn7 == 0` or `syn7` is a power of two), the single-bit flip occurred inside `syndrome8` itself; return `word64` unchanged.
   - `(diff & 0x80) == 0 && diff != 0`: Uncorrectable double-bit error (`DED`) detected; return `LCV_UNCORRECTABLE_DED_SENTINEL` (`0x4000000000000000ULL`) and quarantine the affected tile(s).

---

## 5. Section 4 — `CubeFrame` Bitstream Grammar

Each active tile (`bit 63 == 0`) begins at `msbPool + relByteOffset30` with a 1-byte `cubeHeader8`:

### 5.1 `cubeHeader8` (`1 Byte`) & Normative Space-Filling Curve LUTs (`curveToTyx`)

| Bits | Field | Values |
| :--- | :--- | :--- |
| `7..6` | `curveId` | `00`: `LCV_CURVE_TYX` ($t \to y \to x$)<br>`01`: `LCV_CURVE_H3` (3D Hilbert $16 \times 16 \times K$)<br>`10`: `LCV_CURVE_T_H2` (2D Hilbert $(x, y)$ outer, $t = 0..K-1$ inner)<br>`11`: `LCV_CURVE_TXY` ($t \to x \to y$) |
| `5..4` | `cubeMode` | `00`: `LCV_CUBE_MODE_UI_PALETTE_ROARING` (Discrete palette $2 \le |P| \le 16$ colors + Roaring sub-variations)<br>`01`: `LCV_CUBE_MODE_GRAY_PLANES_RAW` (`YCoCg-R` + Reflected Gray-Code bitplanes)<br>`10`: `LCV_CUBE_MODE_GRAY_PLANES_HILBERT_DELTA` (Predictive delta + Gray/conditioned bitplanes)<br>`11`: `LCV_CUBE_MODE_RAW_CEILING` (Uncompressed active channels) |
| `3..0` | `low4` | For `cubeMode != 00`: `channelMask4` bitmask of active (non-constant) channels (`bit 0 = Y/R`, `bit 1 = Co/G`, `bit 2 = Cg/B`, `bit 3 = Alpha`).<br>For `cubeMode == 00` (`UI_PALETTE_ROARING`): `paletteCountMinusTwo` ($|P| - 2 \in [0, 14]$, so $|P| = \text{low4} + 2 \in [2, 16]$). |

#### Exact Space-Filling Curve Traversal Order (`lcv_init_curves_for_depth`)
Every sample index $i \in [0, N_{\text{samples}} - 1]$ ($N_{\text{samples}} = 256 \cdot K$) maps to a packed coordinate $\text{tyx} = \text{curveToTyx}[i] = (lt \ll 8) \mid (ly \ll 4) \mid lx$ with $lx, ly \in [0, 15]$ and $lt \in [0, K-1]$:
1. **Base $4 \times 4 \times 4$ 3D Hilbert Sub-Cube (`lcv_hilbert3d_4x4x4(s)` for $s \in [0, 63]$):**
   ```c
   int32_t x = 0, y = 0, z = 0;
   for (int32_t shift = 0; shift < 2; shift++) {
       int32_t oct = (s >> (3 * shift)) & 7;
       int32_t gray = oct ^ (oct >> 1);
       int32_t rx = (gray >> 2) & 1, ry = (gray >> 1) & 1, rz = gray & 1;
       int32_t n = 1 << shift;
       if (rx == 0) {
           if (ry == 0) {
               if (rz == 0) { int32_t t = y; y = z; z = t; }
               else         { int32_t t = x; x = y; y = t; }
           } else {
               x = n - 1 - x; z = n - 1 - z;
               if (rz == 0) { int32_t t = x; x = y; y = t; }
               else         { int32_t t = y; y = z; z = t; }
           }
       } else {
           if (ry == 0) {
               y = n - 1 - y; z = n - 1 - z;
               if (rz == 0) { int32_t t = x; x = z; z = t; }
           } else {
               x = n - 1 - x; y = n - 1 - y;
               if (rz == 1) { int32_t t = x; x = z; z = t; }
           }
       }
       x += rx * n; y += ry * n; z += rz * n;
   }
   ```
   The 2D $4 \times 4$ traversal (`h2x[0..15]`, `h2y[0..15]`) is extracted by filtering the 64 points of `lcv_hilbert3d_4x4x4(s)` where $z == 0$ in ascending $s$ order, and the 16-entry macro-block order over the $4 \times 4$ grid of sub-blocks is:
   $$\text{macroOrderX}[16] = \{0, 1, 1, 0, 0, 0, 1, 1, 2, 2, 3, 3, 3, 2, 2, 3\}$$
   $$\text{macroOrderY}[16] = \{0, 0, 1, 1, 2, 3, 3, 2, 2, 3, 3, 2, 1, 1, 0, 0\}$$
2. **Curve `00` (`LCV_CURVE_TYX`):** $lt = 0 \dots K-1$ outer, $ly = 0 \dots 15$ middle, $lx = 0 \dots 15$ inner ($\text{curveToTyx}[i] = i$).
3. **Curve `01` (`LCV_CURVE_H3`):** Let $t_{\text{step}} = \min(4, K)$ and $t_{\text{blocks}} = K / t_{\text{step}}$. For $tb = 0 \dots t_{\text{blocks}} - 1$ outer, $m = 0 \dots 15$ middle (with odd-block reversal $\text{effM} = (tb \ \& \ 1) ? (15 - m) : m$, $mx = \text{macroOrderX}[\text{effM}]$, $my = \text{macroOrderY}[\text{effM}]$), and $s = 0 \dots 63$ inner: whenever the 3D sub-cube point $(sx, sy, sz) = \text{lcv\_hilbert3d\_4x4x4}(s)$ satisfies $sz < t_{\text{step}}$, append $(lx = 4mx + sx, ly = 4my + sy, lt = tb \cdot t_{\text{step}} + sz)$.
4. **Curve `10` (`LCV_CURVE_T_H2`):** For $idx2D = 0 \dots 255$ outer ($m = idx2D \gg 4$, $s = idx2D \ \& \ 15$, $x = 4 \cdot \text{macroOrderX}[m] + \text{h2x}[s]$, $y = 4 \cdot \text{macroOrderY}[m] + \text{h2y}[s]$) and $lt = 0 \dots K-1$ inner, append $(x, y, lt)$.
5. **Curve `11` (`LCV_CURVE_TXY`):** $lt = 0 \dots K-1$ outer, $lx = 0 \dots 15$ middle, $ly = 0 \dots 15$ inner.

---

### 5.2 Mode `00`: `UI_PALETTE_ROARING` (`lcv_decode_ui_palette_roaring` / `16`)

Used when the cube has $|P| = \text{low4} + 2 \in [2, 16]$ unique `RGBA` colors (residing entirely in Section 4A, `lsbSliceByteLen16 == 0`):
1. **Palette Table (`4 * |P|` bytes when `bitDepth8 == 8`; `6 * |P|` bytes when `bitDepth8 ∈ {10, 12}`):**
   - When `bitDepth8 == 8`: $|P|$ Big-Endian `uint32` (`0xRRGGBBAA`) colors.
   - When `bitDepth8 ∈ {10, 12}`: $|P|$ Big-Endian `uint48` (`6 B`) entries packing `((uint64_t)R << 36) | ((uint64_t)G << 24) | ((uint64_t)B << 12) | (uint64_t)A` (`lcv_read_be48`).
   - **`palette[0]` Initialization:** All $N_{\text{samples}}$ curve positions $i \in [0, N_{\text{samples}} - 1]$ are initialized to `palette[0]`.
2. **Sub-Variation Directory (`ceil((|P| - 1) / 4)` bytes):**
   - For each minority state $p \in \{1 \dots |P|-1\}$, let $mIdx = p - 1$. Its 2-bit container selector `subTag` is packed MSB-first within byte `subDir[mIdx >> 2]`:
     $$\text{subTag} = \left(\text{subDir}[mIdx \gg 2] \gg \left((3 - (mIdx \ \& \ 3)) \cdot 2\right)\right) \& \ 0\text{x03}$$
3. **Minority State Containers (evaluated sequentially for $p = 1 \dots |P|-1$):**
   - `00` (`LCV_ROARING_TAG_SINGLETON`): Big-Endian `uint16 idx16` (`2 B`). MUST satisfy $0 \le \text{idx16} < N_{\text{samples}}$; sets `curveRgba[idx16] = palette[p]`.
   - `01` (`LCV_ROARING_TAG_DELTA_RUN`): A `mode 10` `4|4` Nibble Gap/Run Token Stream (`lcv_read_nibble_run_stream`, Section 5.4) setting `curveRgba[k] = palette[p]` across each decoded run.
   - `10` (`LCV_ROARING_TAG_DELTA_ARRAY`): `VarInt card` ($1 \le \text{card} \le N_{\text{samples}}$) followed by `card` `VarInt` delta-gaps (`delta_0`, `delta_1`, $\dots$) where `idx_0 = delta_0` and `idx_k = idx_{k-1} + 1 + delta_k`. Decoders MUST reject if `delta_k < 0` or `idx_k >= N_samples`.
   - `11` (`LCV_ROARING_TAG_BITMAP`): `N_samples / 8` raw bitmap bytes where curve index $i$ corresponds to bit `i & 7` (LSB-first) of byte `bitmap[i >> 3]`: if `((bitmap[i >> 3] >> (i & 7)) & 1) != 0`, set `curveRgba[i] = palette[p]`.
4. **Slice Completeness & Stripped-LSB Plateau Smoothing:** After state $p = |P| - 1$, the byte cursor MUST equal `msbOff + msbSliceByteLen16` (otherwise quarantine). Samples are scattered via `dstRgbaTyx[curveToTyx[i]] = curveRgba[i]`. When `bitDepth8 == 8`, `lsbSliceByteLen16 == 0`, `lsbSplitBits >= 3`, and $|P| \ge 3$, the decoder applies the integer $5 \times 5$ plateau boundary smoothing pass `lcv_smooth_conditioned_plateau_cube_if_applicable` (`maxStep = (1 << (lsbSplitBits + 1)) + 4`, `maxShift = (1 << (lsbSplitBits - 1)) - 1`, weight `2` for $|dx|,|dy| \le 1$ and `1` for $|dx|,|dy| = 2$ when `hasGradStep && !hasSharpEdge`).

---

### 5.3 Mode `11`: `RAW_CEILING` & Reversible `YCoCg-R + Alpha` Mapping (Modes `01`, `10`, `11`)

For channel bit depth $B = \text{bitDepth8} \in \{8, 10, 12\}$ with mask $M = 2^B - 1$, chroma offset bias $H = 2^{B-1}$ (`128` for $B=8$, `512` for $B=10$, `2048` for $B=12$), and signed $B$-bit sign-extension $\text{sext}_B(x) = ((x \ \& \ M) \oplus H) - H \in [-H, H - 1]$, forward decorrelation maps $B$-bit unsigned `R, G, B, A` $\in [0, M]$ to modular $B$-bit unsigned channels $(Y_B, \text{Co}_B, \text{Cg}_B, A_B)$ using two's-complement arithmetic right-shifts (`>> 1`, rounding toward $-\infty$):
```c
int32_t co  = (((r - b) & M) ^ H) - H;         // sext_B(r - b)
int32_t t   = (b + (co >> 1)) & M;
int32_t cg  = (((g - t) & M) ^ H) - H;         // sext_B(g - t)
int32_t yB  = (t + (cg >> 1)) & M;
int32_t coB = (co + H) & M;
int32_t cgB = (cg + H) & M;
```
Inverse reconstruction is 100% bit-exact across all $(2^B)^4$ `RGBA` tuples (`REQ-LCV-F-001`):
```c
int32_t co = (((coB - H) & M) ^ H) - H;        // sext_B(Co_B - H)
int32_t cg = (((cgB - H) & M) ^ H) - H;        // sext_B(Cg_B - H)
int32_t t  = (yB - (cg >> 1)) & M;
int32_t g  = (cg + t) & M;
int32_t b  = (t - (co >> 1)) & M;
int32_t r  = (b + co) & M;
```
- **Near-Gray Quantized Overshoot Clamp (`lcv_reconstruct_rgba_from_ycocg_channels` / `lcv_ycocgr_b_to_rgba16_clamped`):** When `coB, cgB` lie within $\pm (8 \ll (B - 8))$ of neutral gray $H$ (or when `lsbWasStripped` is true on $B=8$ cubes so `tRaw, gRaw, bRaw, rRaw` fall in $[-96, 351]$), intermediate `t, g, b, r` values that slightly underflow/overflow $[0, M]$ by at most $8 \ll (B - 8)$ (or $[-96, 351]$ when stripped) clamp to $[0, M]$ instead of wrapping modulo $2^B$.
- **Inactive Channel Constants (`channelMask4`):** Immediately after `cubeHeader8`, each inactive channel $c \in \{0..3\}$ where `((channelMask4 >> c) & 1) == 0` writes **`1 byte` `constVal8[c]`** when $B = 8$, or **`2 bytes` Big-Endian `constVal16[c]`** (masked to $M$) when $B \in \{10, 12\}$.
- **Mode `11` (`LCV_CUBE_MODE_RAW_CEILING`) Wire Layout:** Immediately after the inactive `constVal` fields in Section 4A (`lsbSliceByteLen16 == 0`), each active channel $c \in \{0..3\}$ where `((channelMask4 >> c) & 1) == 1` writes $N_{\text{samples}}$ uncompressed channel values in `curveId` order (`1 byte` per sample when $B = 8$; `2 bytes` Big-Endian `uint16` masked to $M$ per sample when $B \in \{10, 12\}$). The cursor MUST equal `msbOff + msbSliceByteLen16`, and the 4 channels reconstruct `RGBA` via inverse `YCoCg-R`.

---

### 5.4 Modes `01` & `10`: Split MSB/LSB Bitplanes, `deltaFlags8` Truth Table & Sub-Modes

Let $B = \text{bitDepth8} \in \{8, 10, 12\}$, $s = \text{lsbSplitBits} \in \{1, 2, 3, 4\}$ (from `tuningProfile16` bits `15..14`), $B_{\text{lsb}} = (B - 8) + s \in \{1 \dots 8\}$, and $B_{\text{msb}} = B - B_{\text{lsb}} = 8 - s \in \{4, 5, 6, 7\}$ with mask $M_{\text{msb}} = 2^{B_{\text{msb}}} - 1$ and half-modulus $H_{\text{msb}} = 2^{B_{\text{msb}} - 1}$.

#### Exact Section 4A Byte Sequence (`lcv_decode_split_cube_rgba` / `16`)
1. `cubeHeader8` (`1 B`: `curveId:2 | cubeMode:2 | channelMask4:4`).
2. `constVal[c]` for each inactive channel $c \in \{0..3\}$ where `((channelMask4 >> c) & 1) == 0` (`1 B` when $B = 8$; `2 B` Big-Endian when $B \in \{10, 12\}$).
3. **If `cubeMode == 10` (`LCV_CUBE_MODE_GRAY_PLANES_HILBERT_DELTA`):**
   - `deltaFlags8` (`1 B` once per cube).
   - **Motion Vector Bytes (present iff `(deltaFlags8 & 0x08) != 0`):**
     - If `(deltaFlags8 & 0x01) == 0 && (deltaFlags8 & 0x40) != 0 && (deltaFlags8 & 0x80) != 0 && K > 1` (`med2dPerFrameMv`): $K - 1$ bytes `packedSteps[1 .. K-1]`.
     - Otherwise: `1 byte` `packedMv8` (broadcast to `packedSteps[0 .. K-1]`).
   - **CCLM Slope Byte (present iff `hasCclmMsb`):** Where `hasCclmMsb = ((deltaFlags8 & 0x01) == 0) && ((deltaFlags8 & 0x40) != 0) && ((deltaFlags8 & 0x02) == 0) && ((deltaFlags8 & 0x04) != 0) && ((channelMask4 & 0x01) != 0) && ((channelMask4 & 0x06) != 0)`, `1 byte` `cclmSlopes8`.
4. **Channel Headers & Section 4A MSB Bitplanes:**
   - **When `(deltaFlags8 & 0x01) == 0` (or `cubeMode == 01`):**
     - For each active channel $c \in \{0..3\}$: `planeModes16[c]` (`2 B` Big-Endian when $B = 8$) or `planeModes24[c]` (`3 B` Big-Endian packing $2B$ bits in `bits (2B-1)..0` when $B \in \{10, 12\}$).
     - For each active channel $c \in \{0..3\}$, the encoded bitplane payloads for $p = B - 1$ down to $B_{\text{lsb}}$ in descending order.
   - **When `cubeMode == 10` and `(deltaFlags8 & 0x01) != 0` (`LCV_DELTA_SUBMODE_PLANAR_3D_MV`, $B = 8$):**
     - For each active channel $c \in \{0..3\}$: the per-channel planar/Haar header (`compactHdr` when `(deltaFlags8 & 0x80) != 0`, or the full 12/21-byte header when `(deltaFlags8 & 0x80) == 0`; detailed below).
     - For each active channel $c \in \{0..3\}$ with `maxU8 > 0`: the `maxU8`-conditioned bitplane payloads for $p = 7$ down to $s$ in descending order.
5. **Section 4A Boundary Invariant:** After reading the last Section 4A bitplane, the byte cursor MUST equal `msbOff + msbSliceByteLen16` (otherwise quarantine).

#### Normative `deltaFlags8` Truth Table (`cubeMode == 10`)
The primary sub-mode bits `(bit 6 = 0x40, bit 0 = 0x01)` partition the meaning of all 8 bits of `deltaFlags8` without ambiguity:

| Sub-Mode Selector `(bit 6, bit 0)` | Bit `7` (`0x80`) | Bit `6` (`0x40`) | Bit `5` (`0x20`) | Bit `4` (`0x10`) | Bit `3` (`0x08`) | Bit `2` (`0x04`) | Bit `1` (`0x02`) | Bit `0` (`0x01`) |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **`(0, 0)` Curve Delta / Intra-Temporal** | `INTRA_CUBE_TEMPORAL` (`1` = frame $t \ge 1$ predicts from $t-1$) | `0` | `ALPHA_MASKED_RGB` (when `bit 1 == 1`) | Reserved (`0`) | `MOTION_SHEARED` (`1` = `1 B` `packedMv8` present) | Reserved (`0`) | `DIRECT_RGBA` (`1` = direct `RGBA`, `0` = `YCoCg-R`) | `0` |
| **`(1, 0)` Causal 2D `MED-2D`** | Per-frame `packedSteps[1..K-1]` when `bit 3 == 1 && K > 1` | `SPATIAL_MED_2D` (`1`) | `ALPHA_MASKED_RGB` (when `bit 1 == 1`) | `HALF_PEL_MV` ($B=8$ half-pel + $4 \times 4$ shear; $B \in \{10,12\}$ $4 \times 4$ shear) | `MOTION_SHEARED` (`1` = motion bytes present) | `CCLM_MSB` (if `hasCclmMsb`), else `MAXU_CONDITIONED` ($B=8$) | `DIRECT_RGBA` (`1` = direct `RGBA`, `0` = `YCoCg-R`) | `0` |
| **`(*, 1)` 3D Planar + Haar (`B = 8`)** | `COMPACT_PLANAR_HDR` (`1` = 6..10 B compact header) | Reserved (`0`) | `ALPHA_MASKED_RGB` (when `bit 1 == 1`) | `WAVELET_3D_HAAR` (`1` = `hStep` + $4 \times 4$ Haar LL nibbles) | `MOTION_SHEARED` (`1` = `1 B` `packedMv8` present) | Reserved (`0`) | `DIRECT_RGBA` (`1` = direct `RGBA`, `0` = `YCoCg-R`) | `PLANAR_3D_MV` (`1`) |

#### Two-Tier LSB Gray Coding for $B \in \{8, 10, 12\}$ (`lcv_binary_to_tiered_lsb_gray` / `lcv_tiered_lsb_gray_to_binary`)
In `cubeMode == 10` (`HILBERT_1D_MSB`, `INTRA_CUBE_TEMPORAL`, and `SPATIAL_MED_2D`), let $\text{deepBits} = B - 8 \in \{0, 2, 4\}$ and $B_{\text{lsb}} = \text{deepBits} + s$. The $B_{\text{lsb}}$-bit LSB word $l \in [0, 2^{B_{\text{lsb}}} - 1]$ is Gray-coded in two independent tiers—the upper $s$ UEP bits $l_{\text{uep}} = (l \gg \text{deepBits}) \ \& \ (2^s - 1)$ and the lower $\text{deepBits}$ mezzanine bits $l_{\text{deep}} = l \ \& \ (2^{\text{deepBits}} - 1)$:
$$G_{\text{TieredLSB}}(l) = \left((l_{\text{uep}} \oplus (l_{\text{uep}} \gg 1)) \ll \text{deepBits}\right) \mid \left(l_{\text{deep}} \oplus (l_{\text{deep}} \gg 1)\right)$$
(When $B = 8$, $\text{deepBits} = 0$ and $G_{\text{TieredLSB}}(l) = l \oplus (l \gg 1)$.)

#### Sub-Mode Rules for `cubeMode == 01` and `cubeMode == 10`
- **Mode `01` (`LCV_CUBE_MODE_GRAY_PLANES_RAW`):**
  - Each $B$-bit sample $v_i \in [0, 2^B - 1]$ along `curveId` is mapped to $B$-bit Reflected Binary Gray Code $G_i = v_i \oplus (v_i \gg 1)$.
- **Mode `10`, Row 1: `(deltaFlags8 & 0xC1) == 0x00` (`LCV_DELTA_SUBMODE_HILBERT_1D_MSB`):**
  - Along `curveId`, the 1st-order delta operates strictly on the top $B_{\text{msb}} = 8 - s$ MSB bits $m_i = (v_i \gg B_{\text{lsb}}) \ \& \ M_{\text{msb}}$, while the bottom $B_{\text{lsb}}$ LSB bits $l_i = v_i \ \& \ (2^{B_{\text{lsb}}} - 1)$ are encoded via $G_{\text{TieredLSB}}(l_i)$ without differencing:
    - For $i = 0$: $g_{\text{msb}, 0} = m_0 \oplus (m_0 \gg 1)$, so on decode $m_0 = G^{-1}_{B_{\text{msb}}}(g_{\text{msb}, 0})$.
    - For $i \ge 1$: signed $B_{\text{msb}}$-bit modular step $d_i = ((m_i - m_{i-1} + H_{\text{msb}}) \ \& \ M_{\text{msb}}) - H_{\text{msb}} \in [-H_{\text{msb}}, H_{\text{msb}} - 1]$ is ZigZag-encoded as $g_{\text{msb}, i} = ((d_i \ll 1) \oplus (d_i \gg 31)) \ \& \ M_{\text{msb}}$, so on decode $\text{ZigZag}^{-1}(u) = ((u \gg 1) \oplus -(u \ \& \ 1))$ and $m_i = (m_{i-1} + \text{ZigZag}^{-1}(g_{\text{msb}, i})) \ \& \ M_{\text{msb}}$.
    - $G_i = (g_{\text{msb}, i} \ll B_{\text{lsb}}) \mid G_{\text{TieredLSB}}(l_i)$.
- **Mode `10`, Row 1 with `bit 7 == 1`: `(deltaFlags8 & 0xC1) == 0x80` (`LCV_DELTA_FLAG_INTRA_CUBE_TEMPORAL`):**
  - **Anchor Frame $t = 0$ ($i \in [0, 255]$):** Identical to `LCV_DELTA_SUBMODE_HILBERT_1D_MSB` along $i = 0 \dots 255$.
  - **Temporal Frames $t = 1 \dots K - 1$ ($i = 256t + p$ for $p \in [0, 255]$):** Predicts both Section 4A MSBs and Section 4B LSBs from the co-located sample $i - 256$ in frame $t - 1$:
    - When `lsbWasStripped == false` and $G_i == 0$: $v_i = v_{i-256}$.
    - Otherwise: $m_i = (m_{i-256} + \text{ZigZag}^{-1}(g_{\text{msb}, i})) \ \& \ M_{\text{msb}}$, and when `lsbWasStripped == false`:
      - For $B = 8$: $l_i = l_{i-256} \oplus G^{-1}_s(g_{\text{lsb}, i})$.
      - For $B \in \{10, 12\}$: $l_i = G_{\text{TieredLSB}}^{-1}\!\left(g_{\text{lsb}, i} \oplus G_{\text{TieredLSB}}(l_{i-256})\right)$.
- **Mode `10`, Row 2: `(deltaFlags8 & 0x41) == 0x40` (`LCV_DELTA_FLAG_SPATIAL_MED_2D`, `lcv_predict_med2d_sample_ex` / `16_ex`):**
  - Operates in linear `TYX` order ($\text{tyx} = 0 \dots N_{\text{samples}} - 1$, where $lx = \text{tyx} \ \& \ 15$, $ly = (\text{tyx} \gg 4) \ \& \ 15$, $lt = \text{tyx} \gg 8$).
  - **Per-Frame Motion Vector Unpacking:** For frame $lt \ge 1$ when `(deltaFlags8 & 0x08) != 0`, let $\text{stepMv} = \text{packedSteps}[lt]$ (`0x88` when $lt == 0$ or `(deltaFlags8 & 0x08) == 0`), giving signed components $vx = ((\text{stepMv} \gg 4) \ \& \ 0\text{x0F}) - 8 \in [-8, +7]$ and $vy = (\text{stepMv} \ \& \ 0\text{x0F}) - 8 \in [-8, +7]$.
  - **Causal Neighbor $4 \times 4$ Sub-Tile Bilinear Motion Shear (`REQ-LCV-VVC-002`, `lcv_interpolate_subblock_mv_q1`):**
    - On $B = 8$, `isHalfPel = ((deltaFlags8 & 0x10) != 0)` and `enableShear = hasMv && !isHalfPel && ((deltaFlags8 & 0x10) != 0)` (note: on $B \in \{10, 12\}$, `isHalfPel = false` and `enableShear = hasMv && ((deltaFlags8 & 0x10) != 0)`).
    - When $lt \ge 1$ and `enableShear` is true, convert tile and causal neighbor motion vectors $\vec{v}_W = \vec{v}(\text{tx}-1, \text{ty})$ (`0x88` at $\text{tx} == 0$) and $\vec{v}_N = \vec{v}(\text{tx}, \text{ty}-1)$ (`0x88` at $\text{ty} == 0$) to `Q3.1` half-pel units ($cVx_{\text{Q1}} = vx \ll 1$, etc.), and compute full-pel L1 differences $\text{diff}_W = (|cVx_{\text{Q1}} - wVx_{\text{Q1}}| + |cVy_{\text{Q1}} - wVy_{\text{Q1}}|) \gg 1$ and $\text{diff}_N = (|cVx_{\text{Q1}} - nVx_{\text{Q1}}| + |cVy_{\text{Q1}} - nVy_{\text{Q1}}|) \gg 1$.
    - Let $\text{valid}_W = (1 \le \text{diff}_W \le 6)$ and $\text{valid}_N = (1 \le \text{diff}_N \le 6)$. If $\text{valid}_W \lor \text{valid}_N$, replace any invalid neighbor vector with $(cVx_{\text{Q1}}, cVy_{\text{Q1}})$ and interpolate at $4 \times 4$ sub-block $bx = (lx \gg 2) \ \& \ 3$, $by = (ly \gg 2) \ \& \ 3$ with weights $w_x = 3 - bx$, $w_y = 3 - by$, $w_c = 8 - w_x - w_y$:
      $$\text{effVx}_{\text{Q1}} = (w_c \cdot cVx_{\text{Q1}} + w_x \cdot wVx_{\text{Q1}} + w_y \cdot nVx_{\text{Q1}} + 4) \gg 3$$
      $$\text{effVy}_{\text{Q1}} = (w_c \cdot cVy_{\text{Q1}} + w_x \cdot wVy_{\text{Q1}} + w_y \cdot nVy_{\text{Q1}} + 4) \gg 3, \quad \text{effHalfPel} = \text{true}$$
  - **Temporal vs. Causal 2D `MED` Predictor Selection (`lcv_predict_med2d_sample_ex`):**
    - At $(lx, ly, lt) = (0, 0, 0)$: no predictor (`predWord = -1`); $m_0 = G^{-1}_{B_{\text{msb}}}(g_{\text{msb}, 0})$, $l_0 = G^{-1}_{\text{TieredLSB}}(g_{\text{lsb}, 0})$, and $\Delta m_{Y, 0} = 0$.
    - For all other samples:
      - If `effHalfPel` is true: $dx_{\text{floor}} = \text{effVx} \gg 1$, $h_x = \text{effVx} \ \& \ 1$, $dy_{\text{floor}} = \text{effVy} \gg 1$, $h_y = \text{effVy} \ \& \ 1$, $x_0 = lx - dx_{\text{floor}} - h_x$, $x_1 = lx - dx_{\text{floor}}$, $y_0 = ly - dy_{\text{floor}} - h_y$, $y_1 = ly - dy_{\text{floor}}$. Otherwise: $x_0 = x_1 = lx - \text{effVx}$, $y_0 = y_1 = ly - \text{effVy}$.
      - Let $\text{hasTemporal} = (lt \ge 1) \land (x_0 \ge 0 \land x_1 < 16 \land y_0 \ge 0 \land y_1 < 16)$. When $\text{hasTemporal}$ is true, the four reference indices in frame $lt - 1$ ($\text{basePrev} = (lt - 1) \ll 8$) are $\text{ref}_{00} = (y_0 \ll 4) \mid x_0$, $\text{ref}_{10} = (y_0 \ll 4) \mid x_1$, $\text{ref}_{01} = (y_1 \ll 4) \mid x_0$, $\text{ref}_{11} = (y_1 \ll 4) \mid x_1$, and the rounded bilinear temporal prediction is:
        $$\text{tempM} = (m[\text{ref}_{00}] + m[\text{ref}_{10}] + m[\text{ref}_{01}] + m[\text{ref}_{11}] + 2) \gg 2$$
        $$\text{tempL} = (l[\text{ref}_{00}] + l[\text{ref}_{10}] + l[\text{ref}_{01}] + l[\text{ref}_{11}] + 2) \gg 2$$
      - At $(lx, ly) = (0, 0)$ for $lt \ge 1$: if $\text{hasTemporal}$ is false, $\text{ref}_{00} = \text{tyx} - 256$, $\text{tempM} = m[\text{ref}_{00}]$, $\text{tempL} = l[\text{ref}_{00}]$; the predictor returns $(\text{tempM}, \text{tempL})$.
      - For $(lx, ly) \ne (0, 0)$: compute the 2D spatial `MED` predictor $(\text{spatialM}, \text{spatialL})$:
        - If $ly == 0$: West neighbor $\text{tyx} - 1$.
        - Else if $lx == 0$: North neighbor $\text{tyx} - 16$.
        - Else: $\text{med3}(W, N, W + N - NW)$ where $\text{med3}(a, b, c) = (c \ge \max(a,b)) ? \min(a,b) : ((c \le \min(a,b)) ? \max(a,b) : (a + b - c))$ applied independently to $m$ and $l$ at $W = \text{tyx} - 1$, $N = \text{tyx} - 16$, $NW = \text{tyx} - 17$.
      - If $\text{hasTemporal}$ is false, use $(\text{spatialM}, \text{spatialL})$. Otherwise, select temporal iff `useTemporal` holds:
        - If $lx > 0 \land ly > 0 \land x_0 > 0 \land y_0 > 0$: `useTemporal = (|m_W - m[ref00 - 1]| + |m_N - m[ref00 - 16]| <= |m_W - m_NW| + |m_N - m_NW|)`.
        - Else if $lx > 0 \land x_0 > 0$: `useTemporal = (m_W == m[ref00 - 1])`.
        - Else if $ly > 0 \land y_0 > 0$: `useTemporal = (m_N == m[ref00 - 16])`.
        - Else: `useTemporal = true`.
  - **Drift-Free Section 4A Cross-Component Linear Model (`CCLM-MSB`, `REQ-LCV-VVC-001`):**
    - Let $(\hat{m}_{c,\text{tyx}}^{\text{base}}, \hat{l}_{c,\text{tyx}})$ be the predictor selected above. When `hasCclmMsb` is true, unpack two 4-bit signed two's-complement Q3 slopes $\alpha_{\text{Co},\text{Q3}} = (\text{int8\_t})(\text{cclmSlopes8} \ \& \ 0\text{xF0}) \gg 4 \in [-8, +7]$ and $\alpha_{\text{Cg},\text{Q3}} = (\text{int8\_t})((\text{cclmSlopes8} \ \& \ 0\text{x0F}) \ll 4) \gg 4 \in [-8, +7]$.
    - For channel $c = 0$ (`Y`), $\hat{m}_{0,\text{tyx}} = \hat{m}_{0,\text{tyx}}^{\text{base}}$, $m_{0,\text{tyx}} = (\hat{m}_{0,\text{tyx}} + \text{ZigZag}^{-1}(g_{\text{msb}})) \ \& \ M_{\text{msb}}$, and the decoder records $\Delta m_{Y,\text{tyx}} = m_{0,\text{tyx}} - \hat{m}_{0,\text{tyx}}^{\text{base}}$.
    - For chroma channels $c \in \{1:\text{Co}, 2:\text{Cg}\}$ when $\alpha_{c,\text{Q3}} \ne 0$:
      $$\hat{m}_{c,\text{tyx}} = \text{clamp}_{[0, M_{\text{msb}}]}\!\left(\hat{m}_{c,\text{tyx}}^{\text{base}} + \left((\alpha_{c,\text{Q3}} \cdot \Delta m_{Y,\text{tyx}} + 4) \gg 3\right)\right)$$
      $$m_{c,\text{tyx}} = (\hat{m}_{c,\text{tyx}} + \text{ZigZag}^{-1}(g_{\text{msb}})) \ \& \ M_{\text{msb}}$$
    - For Section 4B LSBs: when `lsbWasStripped == false`, $l_{c,\text{tyx}} = G^{-1}_{\text{TieredLSB}}\!\left(g_{\text{lsb}} \oplus G_{\text{TieredLSB}}(\hat{l}_{c,\text{tyx}})\right)$. Because $\Delta m_{Y,\text{tyx}}$ depends strictly on Section 4A MSBs, truncating Section 4B produces zero cross-channel or spatial drift.
  - **Alpha-Masked Direct RGBA (`LCV_DELTA_FLAG_ALPHA_MASKED_RGB = 0x20`):** When `(deltaFlags8 & 0x22) == 0x22` (`DIRECT_RGBA` + `ALPHA_MASKED_RGB`), any sample whose reconstructed alpha channel is $A == 0$ outputs `0x00000000` (`R = G = B = A = 0`).
- **Mode `10`, Row 3: `(deltaFlags8 & 0x01) != 0` (`LCV_DELTA_SUBMODE_PLANAR_3D_MV` & `LCV_DELTA_SUBMODE_WAVELET_3D_HAAR`, $B = 8$, `lcv_decode_planar_mv_cube_rgba`):**
  1. **Per-Channel Header Layout (for each active channel $c \in \{0..3\}$ where `((channelMask4 >> c) & 1) == 1`):**
     - **Case A — Full Header (`(deltaFlags8 & 0x80) == 0`):**
       - `planeModes16[c]` (`2 B` Big-Endian), `minErr16` (`int16` Big-Endian), `sxQ8` (`int16` Big-Endian), `syQ8` (`int16` Big-Endian), `stQ8` (`int16` Big-Endian), `maxU8` (`uint8`), `bestFrac8` (`uint8`) — `12 bytes` total.
       - If `(deltaFlags8 & 0x10) != 0` (`LCV_DELTA_SUBMODE_WAVELET_3D_HAAR`): read `uint8 hStep` (`1 B`). If `hStep > 0`, read `8 bytes` packing sixteen 4-bit unsigned Haar LL nibbles `hNibs[0..15]` (`hNibs[2*k] = (byte >> 4) & 0x0F`, `hNibs[2*k + 1] = byte & 0x0F`); if `hStep == 0`, `hNibs[0..15] = 0`.
     - **Case B — Compact Planar Header (`(deltaFlags8 & 0x80) != 0`, `compactHdr`):**
       - Byte 0: `uint8 planeModesLow8` (`planeModesWords[c] = planeModesLow8`, so planes $p = 7 \dots 4$ have mode `00` and planes $p = 3 \dots 0$ use the four 2-bit pairs in `planeModesLow8`).
       - Byte 1: `uint8 b1` with mode flags `isAligned16 = (b1 & 0x80) != 0`, `isMod64 = (b1 & 0x40) != 0`, `isBin255 = !isMod64 && ((b1 & 0x20) != 0)`:
         - **Branch B1 (`isMod64 == true`, `6 bytes` total):** Bytes 2..5 are `uint8 vMin`, `int8 sxDiv16`, `int8 syDiv16`, `int8 stDiv32`. Sets `c0Mod64 = b1 & 0x3F`, `sxQ8 = sxDiv16 << 4`, `syQ8 = syDiv16 << 4`, `stQ8 = stDiv32 << 5`, `maxU8 = 0`, `bestFrac8 = 0` (modular-64 linear ramp: $\text{pred}_i = (\text{c0Mod64} + ((sx_{\text{Q8}} \cdot lx + sy_{\text{Q8}} \cdot ly + st_{\text{Q8}} \cdot lt) \gg 10)) \ \& \ 0\text{x3F}$, $v_i = \text{clamp}_{[0, 255]}(\text{vMin} + ((\text{pred}_i + u_i) \ \& \ 0\text{x3F}))$).
         - **Branch B2 (`isBin255 == true`, `6 bytes` total):** Skip 4 reserved padding bytes (`pos += 4`), `maxU8 = b1 & 0x0F` (binary $\{0, 255\}$ mask: $v_i = (u_i \ne 0) ? 255 : 0$).
         - **Branch B3 (`isAligned16 == true && !isMod64 && !isBin255`, `6 bytes` total):** Bytes 2..5 are `uint8 minErrBiased`, `int8 sxDiv16`, `int8 syDiv16`, `int8 stDiv32`. Sets `minErr16 = minErrBiased - 64`, `sxQ8 = sxDiv16 << 4`, `syQ8 = syDiv16 << 4`, `stQ8 = stDiv32 << 5`, `maxU8 = b1 & 0x0F`, `bestFrac8 = 0`.
         - **Branch B4 (`!isAligned16 && !isMod64 && !isBin255`, `10 bytes` total):** Byte 2 is `uint8 minErrBiased` (`minErr16 = minErrBiased - 64`), Bytes 3..8 are Big-Endian `int16 sxQ8`, `int16 syQ8`, `int16 stQ8`, Byte 9 is `uint8 bestFrac8`, and `maxU8 = b1 & 0x0F`.
  2. **Closed-Form 3D Planar + $4 \times 4$ Haar LL Prediction (`L8086-L8101`):**
     For Branches B3, B4, and Case A, at sample $i$ with $\text{tyx} = \text{curveToTyx}[i]$ ($lx = \text{tyx} \ \& \ 15$, $ly = (\text{tyx} \gg 4) \ \& \ 15$, $lt = \text{tyx} \gg 8$):
     $$h_{\text{off}} = \text{hasWaveletHaar} \ ? \ \left(\text{hNibs}[((ly \gg 2) \ll 2) \mid (lx \gg 2)] \cdot \text{hStep}\right) : 0$$
     $$\hat{v}_i = \left(\left(\text{bestFrac8} + \left((sx_{\text{Q8}} \cdot lx + sy_{\text{Q8}} \cdot ly + st_{\text{Q8}} \cdot lt) \gg 2\right)\right) \gg 8\right) + h_{\text{off}}$$
     $$v_i = \text{clamp}_{[0, 255]}\!\left(\hat{v}_i + \text{minErr16} + u_i\right)$$
  3. **Upper-Bound Bitplane Conditioning (`maxU8` Pruning, `L7880-L8015`):**
     - Initialize $u_i = 0$ for all $i \in [0, N_{\text{samples}} - 1]$. If $\text{maxU8} == 0$, no bitplanes are read.
     - Otherwise, let $p_{\text{ceil}} = \lfloor \log_2(\text{maxU8}) \rfloor \in [0, 7]$ be the highest bit index with $2^{p_{\text{ceil}}} \le \text{maxU8}$.
     - **Section 4A (`p = 7` down to `s`):** If $2^p > \text{maxU8}$ or $\text{mode2} == 00$, skip plane $p$. If $p == p_{\text{ceil}}$ or $(\text{maxU8} \ \& \ 2^p) \ne 0$, decode a direct conditioned bitplane over all $N_{\text{active}} = N_{\text{samples}}$ samples (`lcv_decode_conditioned_bitplane_direct`). Otherwise, build the active index list $\text{activeIdx}[0 \dots N_{\text{active}} - 1]$ of all $i \in [0, N_{\text{samples}} - 1]$ in ascending $i$ order satisfying $u_i + 2^p \le \text{maxU8}$, and if $N_{\text{active}} > 0$, decode an indexed conditioned bitplane (`lcv_decode_conditioned_bitplane_indexed`).
     - **Section 4B (`p = s - 1` down to `0`, when `lsbLen > 0`):** Let $\text{msbThresh} = 2^s$:
       - If $\text{maxU8} < \text{msbThresh}$: for each $p = s - 1$ down to $0$ with $2^p \le \text{maxU8}$ and $\text{mode2} \ne 00$, if $p == p_{\text{ceil}}$ or $2^{p_{\text{ceil}}} + 2^p \le \text{maxU8}$, decode direct over $N_{\text{samples}}$; otherwise decode indexed over $\{i : (u_i \ \& \ 2^{p_{\text{ceil}}}) == 0\}$.
       - If $\text{maxU8} \ge \text{msbThresh}$: let $\text{lsbRem} = \text{maxU8} \ \& \ (\text{msbThresh} - 1)$ and $\text{msbCeil} = \text{maxU8} \ \& \ \sim(\text{msbThresh} - 1)$. For each $p = s - 1$ down to $0$ with $\text{mode2} \ne 00$, if $2^p \le \text{lsbRem}$, decode direct over $N_{\text{samples}}$; otherwise decode indexed over $\{i : (u_i \ \& \ \sim(\text{msbThresh} - 1)) < \text{msbCeil}\}$.

#### Normative Stripped-LSB Reconstruction Pipeline (`lsbSliceByteLen16 == 0` or `LCV_FLAG_LSB_STRIPPED`)
When a channel has non-zero LSB bitplane modes (`mode2 != 00` for at least one plane $p < B_{\text{lsb}}$) and Section 4B is omitted (`lsbLen == 0`):
1. **Planar Residual Cubes (`lcv_restore_stripped_planar_residual_tyx`):** For each sample $i$ at $\text{tyx} = \text{curveToTyx}[i]$, set $u_{\text{low}} = u_i \ \& \ \sim(2^s - 1)$, $u_{\text{high}} = \min(\text{maxU8}, u_{\text{low}} + 2^s - 1)$, $u_{\text{mid}} = (u_{\text{low}} + u_{\text{high}}) \gg 1$, $\text{minClamp}[\text{tyx}] = \max(u_{\text{low}}, u_{\text{mid}} - 2^{s-1})$, and $\text{maxClamp}[\text{tyx}] = \min(u_{\text{high}}, u_{\text{mid}} + 2^{s-1})$. If not all $u_{\text{mid}}$ are equal, run the 2-pass integer bounded-interval POCS filter (`lcv_restore_stripped_field_bounded_interval_tyx`, below).
2. **Non-Planar `GRAY_PLANES_*` Cubes (`L8312-L8453`):**
   - First reconstruct every sample's LSB bits using the clamped midpoint fill: $\text{fill}(m) = (m == 0) ? 0 : ((m == M_{\text{msb}}) ? (2^{B_{\text{lsb}}} - 1) : 2^{B_{\text{lsb}} - 1})$.
   - On $B = 8$ cubes where `!alphaMasked && (s >= 3 || K <= 4)`, for each channel with stripped LSBs, set $\text{halfStep} = 2^{s-1}$, $\text{minClamp}[\text{tyx}] = \max(0, v - \text{halfStep})$, $\text{maxClamp}[\text{tyx}] = \min(255, v + \text{halfStep})$, and run `lcv_restore_stripped_field_bounded_interval_tyx`.
3. **2-Pass Integer Bounded-Interval POCS Filter (`lcv_restore_stripped_field_bounded_interval_tyx`, `L7326-L7481`):**
   Let $\text{maxGrad} = 1 \ll (s + 1)$:
   - **Pass 1 (`valTyx -> passScratch`):** For each $(lt, ly, lx)$, if any $3 \times 3$ spatial neighbor in frame $lt$ exceeds $|v_{\text{nbr}} - c_0| > \text{maxGrad}$, lock $\text{minClamp} = \text{maxClamp} = \text{passScratch} = c_0$. Otherwise, accumulate over $nt \in [\max(0, lt-1), \min(K-1, lt+1)]$ and spatial window $\pm 3$ (when $nt == lt$, weight $w = 4 - \max(|nx - lx|, |ny - ly|)$) or $\pm 2$ (when $nt \ne lt$, weight $w = 3 - \max(|nx - lx|, |ny - ly|)$) for neighbors satisfying $|v_{\text{nbr}} - c_0| \le \text{maxGrad}$; if at least one neighbor has $0 < |v_{\text{nbr}} - c_0| \le \text{maxGrad}$, set $\text{passScratch} = \text{clamp}_{[\text{minClamp}, \text{maxClamp}]}((sum + (wSum \gg 1)) / wSum)$, else $c_0$.
   - **Pass 2 (`passScratch -> valTyx`):** For each $(lt, ly, lx)$ with $\text{minClamp} < \text{maxClamp}$, accumulate over $nt \in [\max(0, lt-1), \min(K-1, lt+1)]$ and spatial window $\pm 2$ with weight $w = (nt == lt) ? (3 - d_{xy}) : \max(1, 2 - d_{xy})$ for neighbors satisfying $|\text{passScratch}_{\text{nbr}} - c_1| \le \text{maxGrad}$, setting $\text{valTyx} = \text{clamp}_{[\text{minClamp}, \text{maxClamp}]}((sum + (wSum \gg 1)) / wSum)$.
4. **Superblock-Level Stripped Tile-Boundary Deblocking (`lcv_smooth_stripped_tile_boundaries`):** When `LCV_FLAG_LSB_STRIPPED` (`0x02`) is set in `flags8` ($B=8$), apply the 1-pixel integer boundary smoothing pass across interior $16 \times 16$ tile seams where $|\Delta_{\text{seam}}| \le (1 \ll s) + 2$ and interior gradients are $\le (1 \ll (s - 1)) + 1$.

#### `planeModes16[c]` / `planeModes24[c]` Bitplane Modes & `denseTag8` Triplet Huffman Coding
Bits `(2 * p + 1) .. (2 * p)` of `planeModes16[c]` ($B = 8$) or `planeModes24[c]` ($B \in \{10, 12\}$) specify the 2-bit mode `mode2` for bitplane $p \in \{B - 1 \dots 0\}$:
- **`00` (`LCV_PLANE_ALL_ZERO`):** All bits in plane $p$ are `0` (**`0 bytes`**).
- **`01` (`LCV_PLANE_SINGLE_RUN_INLINE` in MSB Tier $p \ge B_{\text{lsb}}$ or Conditioned Planes / `LCV_PLANE_XOR_UPPER_RLE` in Unconditioned LSB Tier $p < B_{\text{lsb}}$):**
  - **MSB Tier ($p \ge B_{\text{lsb}}$) and Conditioned Planes (`lcv_read_single_run_inline`):** Reads `1` Big-Endian `uint16 word16` (`2 bytes`):
    - `word16 == 0xFFFF`: `start = 0`, `len = N` (all-ones across all $N$ active samples).
    - `(word16 & 0x8000) != 0 && word16 != 0xFFFF`: `start = word16 & 0x3FFF`, `len = N - start` (tail run to end of plane).
    - `(word16 & 0x8000) == 0`: `start = (word16 >> 5) & 0x03FF`, `len = (word16 & 0x1F) + 1` (short run of length $1 \dots 32$ starting at $0 \dots 1023$). Decoders MUST reject if `start + len > N`.
  - **Unconditioned LSB Tier ($p < B_{\text{lsb}}$, `lcv_decode_single_bitplane` / `16`):** Encodes plane $p$ relative to upper plane $p_{\text{up}} = \min(B - 1, p + 1)$:
    - Peek the first 2 bytes `peek16` (if the first byte is `0x00`, a 2-byte sentinel is present because a valid `mode 10` `VarInt runCount` is never `0`):
      - `0x0000`: All $N_{\text{samples}}$ bits in plane $p$ are `1`.
      - `0x0001`: Plane $p$ is identical to upper plane $p_{\text{up}}$ ($b_{p,i} = b_{p_{\text{up}},i}$).
      - `0x0002` ($B = 8$): Plane $p$ is the bitwise complement of upper plane $p_{\text{up}}$ ($b_{p,i} = b_{p_{\text{up}},i} \oplus 1$).
    - Otherwise (`firstByte != 0x00`): initialize $b_{p,i} = b_{p_{\text{up}},i}$ and decode a `mode 10` Nibble RLE stream (`lcv_read_nibble_run_stream`) that XOR-flips $b_{p,k} \leftarrow b_{p,k} \oplus 1$ across each decoded run.
- **`10` (`LCV_PLANE_NIBBLE_RLE` — `lcv_read_nibble_run_stream`):**
  1. `VarInt runCount` ($1 \le \text{runCount} \le N$; reject if $\le 0$ or $> N$).
  2. Initialize `cursor = 0`. For each run $r = 0 \dots \text{runCount} - 1$:
     - Read `uint8 token8`: `gNib = (token8 >> 4) & 0x0F`, `lNib = token8 & 0x0F`.
     - If `gNib == 15`, read `VarInt extraGap` and set `adjGap = 15 + extraGap`; else `adjGap = gNib`.
     - If `lNib == 15`, read `VarInt extraLen` and set `lenMinusOne = 15 + extraLen`; else `lenMinusOne = lNib`.
     - `rawGap = (r == 0) ? adjGap : (adjGap + 1)`, `runLen = lenMinusOne + 1`.
     - Advance `cursor += rawGap`; MUST satisfy `cursor + runLen <= N` (otherwise quarantine). Set bits `[cursor, cursor + runLen)` to `1`, then advance `cursor += runLen`.
  3. Remaining bits `[cursor, N)` are `0`.
- **`11` (`LCV_PLANE_RAW_BITMAP` / Conditioned `denseTag8` Stream):**
  - **Unconditioned Planes:** `N_samples / 8` raw bitmap bytes where sample $i$ is bit `i & 7` (LSB-first) of `src[i >> 3]`.
  - **Conditioned Planes (`PLANAR_3D_MV` or `MED_2D` with `MAXU_CONDITIONED`):** Begins with `1 byte` `denseTag8` over the $N = N_{\text{active}}$ eligible samples:
    - `0x00` (`LCV_DENSE_TAG_RAW_BITMAP`): `(N + 7) >> 3` raw bitmap bytes (sample $k \in [0, N-1]$ at bit `k & 7` of byte `k >> 3`).
    - `0x01` (`LCV_DENSE_TAG_TRIPLET_ZERO_SKIP`) & `0x02` (`LCV_DENSE_TAG_TRIPLET_ONE_SKIP`): Followed by Big-Endian `uint16 payloadLen` (`2 B`) and `payloadLen` bitstream bytes consumed LSB-first (`bitAcc |= (uint64_t)byte << bitCount`, `peek5 = bitAcc & 31`). Each prefix codeword decodes 3 consecutive active samples $(b_0, b_1, b_2)$ (`t = b0 | (b1 << 1) | (b2 << 2)`, XORed with `0b111` when `denseTag8 == 0x02`):
      | LSB-First Codeword Bits (`peek5` low bits) | Code Length | Decoded 3-Bit Triplet `t` (`0x01` Zero-Skip) | Decoded 3-Bit Triplet `t ^ 7` (`0x02` One-Skip) |
      | :--- | :---: | :---: | :---: |
      | `0` (`(peek5 & 1) == 0`) | `1 bit` | `0b000` (`0`) | `0b111` (`7`) |
      | `001` (`(peek5 & 7) == 1`) | `3 bits` | `0b001` (`1`) | `0b110` (`6`) |
      | `011` (`(peek5 & 7) == 3`) | `3 bits` | `0b010` (`2`) | `0b101` (`5`) |
      | `101` (`(peek5 & 7) == 5`) | `3 bits` | `0b100` (`4`) | `0b011` (`3`) |
      | `00111` (`peek5 == 0x07`) | `5 bits` | `0b011` (`3`) | `0b100` (`4`) |
      | `01111` (`peek5 == 0x0F`) | `5 bits` | `0b101` (`5`) | `0b010` (`2`) |
      | `10111` (`peek5 == 0x17`) | `5 bits` | `0b110` (`6`) | `0b001` (`1`) |
      | `11111` (`peek5 == 0x1F`) | `5 bits` | `0b111` (`7`) | `0b000` (`0`) |
      After decoding all $\lceil N / 3 \rceil$ triplets, the byte cursor advances to `pos + payloadLen`.
    - `0x03` (`LCV_DENSE_TAG_TRIMMED_BITMAP`): Followed by Big-Endian `uint16 trimmedBytes` (`0 <= trimmedBytes <= (N + 7) >> 3`) and `trimmedBytes` raw LSB-first bitmap bytes; all trailing bits from `8 * trimmedBytes` up to $N - 1$ are implicitly `0`.

---

## 6. Matroska `.mkv` (`CodecID = V_LCV1`) & `LCVU` UDP Datagram Framing

### 6.1 Matroska `.mkv` (`CodecID = "V_LCV1"`) Container Framing (`lcv_codec_system_plugins.c`)
When muxed into a Matroska (`.mkv`) stream:
- **EBML Header (`0x1A45DFA3`):** `DocType = "matroska"`, `DocTypeVersion = 4`, `DocTypeReadVersion = 2`.
- **Segment (`0x18538067`):** Written with unknown/streaming size (`0x01FFFFFFFFFFFFFF`) so live recording never requires seeking back to the file header on `SIGKILL`.
- **Tracks (`0x1654AE6B`) $\to$ TrackEntry (`0xAE`):** `TrackNumber = 1`, `TrackType = 1` (video), `CodecID = "V_LCV1"`, `Video` (`PixelWidth = W`, `PixelHeight = H`).
- **Cluster (`0x1F43B675`) $\to$ SimpleBlock (`0xA3`):** Each `Superblock` (`"LCV_V001"`) is emitted as the payload of a keyframe `SimpleBlock` inside an independent `Cluster`. Stream readers scan for `"LCV_V001"` (`0x4C43565F56303031ULL`) with a valid `headerCrc32`, skip `LCV_FLAG_SUPERSEDED_OR_SLACK` (`0x10`) slack records, and apply last-writer-wins superseding when a later physical `Superblock` carries the same `frameIndex64`.

### 6.2 `LCVU` Motion-Coherent UDP Datagram Framing (`lcv_native_packetize_superblock_lcvu_foveated` & `lcv_native_decode_lcvu_udp_rgba`)
For low-latency wireless and VR streaming, a `Superblock` is partitioned into self-contained, MTU-bounded `LCVU` UDP datagrams (`maxTilesPerPacket in [1, 255]`), each grouping contiguous tiles `startTileIdx16 .. startTileIdx16 + tileCount8 - 1` that share motion vector `pktMv8`:

| Byte Offset | Type | Field Name | Description |
| :--- | :--- | :--- | :--- |
| `0x00..0x01` | `uint16` | `startTileIdx16` | Starting linear tile index ($\text{ty} \cdot \text{tilesX} + \text{tx}$). |
| `0x02` | `uint8` | `tileCount8` | Number of contiguous tiles $C \in [1, 255]$ carried in this datagram. |
| `0x03` | `uint8` | `pktMv8` | Shared motion vector byte `(((dx + 8) & 0x0F) << 4) | ((dy + 8) & 0x0F)` (`0x88` = zero motion) used for optical-flow concealment if neighboring packets are lost. |
| `0x04..0x05` | `uint16` | `msbPayloadLen16` | Total byte length of the concatenated Section 4A MSB slices in this datagram. |
| `0x06 .. 0x06 + 8C - 1` | `uint64[C]` | `pktPtr64[0..C-1]` | Big-Endian 64-bit tile pointers where `relByteOffset30` is relative to the start of this datagram's `msbPayload` (`0x06 + 9C`). |
| `0x06 + 8C .. 0x06 + 9C - 1` | `uint8[C]` | `pktSyndrome8[0..C-1]` | Extended Hamming `(72, 64)` `SEC-DED` syndrome byte for each `pktPtr64[k]`. |
| `0x06 + 9C .. +msbPayloadLen16` | `uint8[]` | `msbPayload` | Concatenated Section 4A Base MSB Slices for active tiles in `0 .. C-1`. |
| `+msbPayloadLen16 .. pLen` | `uint8[]` | `lsbPayload` | Concatenated Section 4B Tail LSB Slices for active tiles in `0 .. C-1` (advanced sequentially by `lsbLen = pktPtr64[k] & 0xFFFF`). |

### 6.3 Superblock-Synchronized Multi-Track Audio Stems (`magic64 = "LCV_A001"`, `.lca` & VLC `AUDIO_ES`)
During live OBS Studio capture (`obs_leafvideo_plugin.c` hooking `obs_get_audio` / `audio_output_connect` across mixers `0..5` per `lcv_audio_tracks_mask`) and native C11 muxing (`lcv_mux_superblocks_and_audio_to_mkv` / `lcv_vlc_demux_open` in `lcv_codec_system_plugins.c`), each `Superblock` (`"LCV_V001"`) of temporal depth $K$ is paired with a self-contained `"LCV_A001"` multi-track audio stem block (written to companion `.lca` sidecar or inline after the video superblock and indexed via `audioOffset64` / `audioBytes` in `.tracks.jsonl`):

| Byte Offset | Type | Field Name | Description |
| :--- | :--- | :--- | :--- |
| `0x00..0x07` | `char[8]` | `magic64` | ASCII `"LCV_A001"` (`0x4C43565F41303031`). |
| `0x08..0x09` | `uint16` | `version16` | Format version `0x0001` (Big-Endian). |
| `0x0A` | `uint8` | `audioTracksMask8` | Active OBS audio mixer bitmask (`bits 0..5`, up to `6` independent stereo stems). |
| `0x0B` | `uint8` | `trackCount8` | Popcount of `audioTracksMask8` ($T \in [1, 6]$). |
| `0x0C` | `uint8` | `channels8` | Channels per track (`2` = stereo `SPEAKERS_STEREO`). |
| `0x0D` | `uint8` | `codecId8` | `0x00` = `PCM_S16LE` (`AUDIO_FORMAT_16BIT` little-endian signed 16-bit interleaved stereo). |
| `0x0E..0x0F` | `uint16` | `depthK16` | Video superblock temporal depth $K$ synchronized with this audio window. |
| `0x10..0x13` | `uint32` | `sampleRateHz32` | Audio sampling rate in Hz (default `48000`). |
| `0x14..0x17` | `uint32` | `samplesPerTrack32` | Exact sample frames per track for this superblock window ($\lfloor (48000 \cdot K \cdot \text{fpsDen} + \lfloor\text{fpsNum}/2\rfloor) / \text{fpsNum} \rfloor$). |
| `0x18..0x1B` | `uint32` | `payloadByteLen32` | Total byte length following the 32-byte header ($8T + \sum_{k=0}^{T-1} \text{trackByteLen}_{k}$). |
| `0x1C..0x1F` | `uint32` | `frameIndex32` | Starting video `frameIndex` of the paired `LCV_V001` superblock. |
| `0x20 .. 0x20 + 8T - 1` | `dirEntry[T]` | `trackDir[0..T-1]` | `8-byte` directory entry per active track in ascending mixer bit order: `trackIdx8` (`0..5`), `channels8` (`2`), `bitDepth8` (`16`), `reserved8` (`0`), and Big-Endian `uint32 trackByteLen32` ($4 \cdot \text{samplesPerTrack32}$). |
| `0x20 + 8T ..` | `int16_le[]` | `trackPcmPayloads` | Concatenated `PCM_S16LE` stereo sample buffers for each active track $0 \dots T-1$. |

- **VLC Native Demuxer Playback (`lcv_vlc_demux_open` / `lcv_vlc_demux_step`):** Alongside the `VIDEO_ES` (`'RGBA'`) elementary stream, the VLC demuxer registers one `AUDIO_ES` (`'s16l'`, `48000 Hz`, `2 ch`, `i_physical_channels = 0x6`) elementary stream per active bit in `audioTracksMask8` (`i_id = 1 + trackIdx`), and emits each track's `block_Alloc` `PCM_S16LE` buffer with exact `i_pts`/`i_dts`/`i_length` (`VLC_TICK_0 + us`) at the start of every superblock (`cur_f_in_sb == 0`).
- **Matroska `.mkv` Multi-Track Muxing (`lcv_mux_superblocks_and_audio_to_mkv`):** Emits `TrackNumber = 1` (`TrackType = 1`, `CodecID = "V_LCV1"`) plus distinct `TrackType = 2` audio `TrackEntry` elements (`TrackNumber = 2 .. 1 + T`, `CodecID = "A_PCM/INT/LIT"`, `SamplingFrequency = 48000.0`, `Channels = 2`, `BitDepth = 16`, `Name = "Track 1 - Desktop / Full Mix"`, etc.) and interleaves per-track `SimpleBlock` packets in each superblock `Cluster`.

---

## 7. Tunability (`tuningProfile16`) & Informative Operating Profiles

### 7.1 Normative `tuningProfile16` Bitfield (`0x1A..0x1B`, `uint16` Big-Endian)

The 16-bit `tuningProfile16` header word records the active MSB/LSB split boundary $s$ (normative for decoders), the `TEMPORAL_CLEAN_ACK` refresh horizon $M_{\text{refresh}}$ (normative bound), and the encoder/transformer distance-scaled RLE policy $(p_{\text{cutoff}}, g_{\text{base}}, \alpha_{\text{radial}})$ (informative to decoders, since `d_merge` emits standard `mode 10` nibble RLE tokens). A value of `0x0000` encodes the default (`lsbSplitBits = 2`, `LOSSLESS` $d_{\text{merge}} = 0$, $M_{\text{refresh}} = 8$):

| Bits | Width | Field Name | Encoding & Semantics |
| :--- | :--- | :--- | :--- |
| `15..14` | `2 bits` | `lsbSplitCode2` | **Normative:** Split parameter $s = \text{lsbSplitBits} \in \{1, 2, 3, 4\}$ (`lcv_extract_lsb_split_bits`) selecting top $B_{\text{msb}} = 8 - s$ MSB bitplanes in Section 4A and bottom $B_{\text{lsb}} = (B - 8) + s$ LSB bitplanes in Section 4B:<br>`00` $\to s = 2$ (Default: top `6` MSB planes in 4A, bottom `(B - 8) + 2` planes in 4B)<br>`01` $\to s = 1$ (Light: top `7` MSB planes in 4A, bottom `(B - 8) + 1` planes in 4B)<br>`10` $\to s = 3$ (Medium UEP: top `5` MSB planes in 4A, bottom `(B - 8) + 3` planes in 4B)<br>`11` $\to s = 4$ (Deep UEP: top `4` MSB planes in 4A, bottom `(B - 8) + 4` planes in 4B) |
| `13..11` | `3 bits` | `losslessPlaneCutoff3` | **Informative (Encoder/Transformer):** Bitplane index $p_{\text{cutoff}} \in \{0 \dots 7\}$ above or equal to which $d_{\text{merge}}(p, \text{dist}) = 0$ (`0` = lossless across all planes). |
| `10..7` | `4 bits` | `baseMaxGap4` | **Informative (Encoder/Transformer):** Base bridged zero-gap $g_{\text{base}} \in \{0 \dots 15\}$ at the focal ROI center ($\text{dist} = 0$) on plane $p_{\text{cutoff}} - 1$. |
| `6..3` | `4 bits` | `radialScaleQ2_4` | **Informative (Encoder/Transformer):** Radial gap growth per Euclidean tile distance in unsigned `UQ2.2` fixed-point ($\alpha_{\text{radial}} = \text{radialScaleQ2\_4} / 4.0 \in [0.00, 3.75]$). |
| `2..0` | `3 bits` | `cleanAckRefreshCode3` | **Normative:** Maximum `TEMPORAL_CLEAN_ACK` lookback horizon $M_{\text{refresh}}$ in superblocks:<br>`000` $\to 8$ (Default), `001` $\to 0$ (Pure intra-superblock), `010` $\to 1$, `011` $\to 2$, `100` $\to 4$, `101` $\to 16$, `110` $\to 32$, `111` $\to 64$. |

Given $(p_{\text{cutoff}}, g_{\text{base}}, \alpha_{\text{radial}})$ and Euclidean tile distance $r = \sqrt{(\text{tx} - \text{roiTileX16})^2 + (\text{ty} - \text{roiTileY16})^2}$, the encoder/transformer run-merge threshold on plane $p \in \{0 \dots 7\}$ is:
$$d_{\text{merge}}(p, r) = \begin{cases} 0 & \text{if } p \ge p_{\text{cutoff}} \text{ or } p_{\text{cutoff}} == 0 \\ \min\!\left(N_{\text{samples}}, \left\lfloor (g_{\text{base}} + r \cdot \alpha_{\text{radial}}) \cdot 2^{\min(12,\, p_{\text{cutoff}} - 1 - p)} \right\rfloor\right) & \text{if } p < p_{\text{cutoff}} \end{cases}$$

### 7.2 Informative Standard Operating Profiles

| Profile Name | `depthK8` ($K$) | `lsbSplitBits` ($s$) | `(p_cutoff, g_base, alpha)` | $M_{\text{refresh}}$ | `LCV_FLAG_ZSTD_POOL` | Target Application |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **`ARCHIVAL_LOSSLESS`** | `16` | `2` (`g1..g0` in 4B) | `(0, 0, 0.00)` | `16` | `1` (Enabled) | Studio archive, desktop/IDE master recording, NLE post-production |
| **`OBS_REALTIME_LOSSLESS`** | `8` | `2` (`g1..g0` in 4B) | `(0, 0, 0.00)` | `8` | `0` (Raw fast) | Real-time `1440p60`/`4K60` OBS capture with `< 1.2 ms` on-disk crop/strip |
| **`WIRELESS_FOVEATED_LOW_LATENCY`** | `2` or `4` | `2` (`g1..g0` in 4B) | `(4, 0, 1.25)` | `2` | `0` (Raw MTU) | FPV drone, robotics teleoperation, wireless monitor (`100%` lossless crosshair ROI) |
| **`HARSH_RF_UEP_ZERO_WAIT`** | `1` | `3` (`g2..g0` in 4B) | `(4, 1, 2.00)` | `1` | `0` (Raw MTU) | Zero-lookahead (`0 ms` buffer) contested RF link with `UEP` Section 4B queue shedding |
| **`VR_STEREO_FOVEATED_DIRECT`** | `1` or `2` | `3` (`g2..g0` in 4B) | `(4, 0, 1.50)` | `2` | `0` (Raw MTU) | Direct `GPU -> 6 GHz Adapter -> Headset -> Display` stereo VR streaming (`4320x2160 @ 90–144 Hz`) |
| **`STUDIO_MEZZANINE_12BIT`** | `4` | `2` (`6` MSB planes in `4A` + `6` LSB planes in `4B`, `bitDepth8 = 12`) | `(0, 0, 0.00)` | `8` | `0` or `1` | `10-bit` / `12-bit` `4:4:4:4` DI/mastering mezzanine (`RGBA64LE`) with single-file `Section 4A`-only `8-bit` real-time NLE proxy scrubbing |

### 7.3 Informative User-Facing Creator & OBS Studio Preset Mapping (`lcv_cli_and_test.c` & `obs_leafvideo_plugin.c`)

User-facing `Recording Quality` presets in `obs_leafvideo_plugin.c` and `lcv_cli_and_test.c` map onto `tuningProfile16` and container flags:

| User-Facing UI Preset | Canonical CLI / OBS Alias | Underlying `TuningProfile` & Transform Action |
| :--- | :--- | :--- |
| **`Instant-Edit Lossless (Fastest Crop & Scrub)`** *(Default)* | `INSTANT_EDIT_LOSSLESS` | `OBS_REALTIME_LOSSLESS` (`K = 8, s = 2, LCV_FLAG_ZSTD_POOL = 0`): `100%` bit-exact `RGBA`, `< 0.5 ms` zero-decode 9:16 crop and proxy strip. |
| **`High Quality, Compact Master (Smart Noise-Strip)`** | `HIGH_QUALITY_COMPACT` / `COMPACT_MASTER` | `OBS_REALTIME_LOSSLESS` (`K = 8, s = 2`) + automatic `lcv_native_crop_or_strip_superblock` (`g7..g2` retained, `LCV_FLAG_LSB_STRIPPED = 1`) on flush. |
| **`Studio Archival Lossless (Smallest Lossless File)`** | `STUDIO_ARCHIVAL_LOSSLESS` / `STUDIO_ARCHIVAL_ZSTD` | `ARCHIVAL_LOSSLESS` (`K = 16, s = 2`, `lcv_native_compact_superblock_zstd` with `LCV_FLAG_ZSTD_POOL = 1`). |
| **`Foveated / Center-Focus (Ultra-Low Bandwidth)`** | `FOVEATED_CENTER_FOCUS` | `WIRELESS_FOVEATED_LOW_LATENCY` (`K = 2, p_cutoff = 4, g_base = 0, alpha = 1.25`): `100%` lossless focal ROI with peripheral `d_merge` run coalescing. |
| **`VR Stereo Foveated Direct (GPU -> Adapter -> Headset)`** | `VR_STEREO_FOVEATED_DIRECT` | `VR_STEREO_FOVEATED_DIRECT` (`K = 1..2, s = 3, p_cutoff = 4, g_base = 0, alpha = 1.50`): Concentric 4-zone GPU-direct foveation with stereo $\min(r_{\text{left}}, r_{\text{right}})$ gaze evaluation and peripheral `Section 4B` skipping (`lsbSliceByteLen16 = 0` for $r > R_{\text{fovea}}$). |

### 7.4 Informative Encoder Guidance: Multi-Fovea SDF, Pre-Conditioning & Direct `GPU -> Adapter -> Headset -> Display` Pipeline (`REQ-LCV-HW-004`, `REQ-LCV-VR-001`..`003`, `REQ-LCV-VVC-003`..`004`)

Because every $16 \times 16$ tile carries an independent 64-bit pointer `ptr64` (`Section 4.1`) with its own `lsbSliceByteLen16` (`bits 15..0`), `TEMPORAL_CLEAN_ACK` velocity shift `(shiftDx, shiftDy)`, and `cubeHeader8` `channelMask4` (`Section 5.1`), GPU compute encoders (`resource/LeafVideo/native/lcv_cube_kernel.comp` and `lcv_gpu_encode_tiles` in `lcv_accel.c`) and zero-copy `LCVU` packetizers (`lcv_native_packetize_superblock_lcvu_multifovea` / `lcv_native_packetize_superblock_lcvu_foveated`) evaluate non-circular foveal primitives, motion salience, directional curve selection (`lcv_select_optimal_curve_1pass`), and closed-loop lossy reference tracking **entirely inside the encoder with zero changes to the `0x0001` wire grammar**:

1. **Encoder 64-Bit Packed Multi-Fovea & Shape Descriptor (`foveaDescriptor64`):**
   - Passed as an API/kernel parameter into `lcv_gpu_encode_tiles` (`enc_p_foveaDesc64`), `lcv_native_encode_superblock_gpu_direct_multifovea`, and `lcv_native_packetize_superblock_lcvu_multifovea` (never written into the `.lcv` wire header):

   | Bits | Field | Description |
   | :--- | :--- | :--- |
   | `7..0` | `roi1TileX8` | Primary anchor $\mathbf{A}$ tile X (`0..255` in local eye half when `sideBySideStereoBit == 1`). |
   | `15..8` | `roi1TileY8` | Primary anchor $\mathbf{A}$ tile Y (`0..255`). |
   | `23..16` | `roi2TileX8` | Secondary anchor $\mathbf{B}$ tile X (or $\mathbf{A}_x + \Delta v_x$ for velocity-swept saccade/pursuit line). |
   | `31..24` | `roi2TileY8` | Secondary anchor $\mathbf{B}$ tile Y (or $\mathbf{A}_y + \Delta v_y$ for velocity-swept saccade/pursuit line). |
   | `39..32` | `foveaRadiusTiles8` | Core `100%` lossless `Section 4B` keep radius $R_{\text{fovea}}$ (`0 = lossless everywhere`). |
   | `47..40` | `lensStencilRadius8` | Zone 0 circular VR lens corner stencil radius $R_{\text{lens}}$ (`0 = disabled`). |
   | `50..48` | `shapeMode3` | `0 = CIRCLE` ($\mathbf{A}$ only), `1 = CAPSULE_LINE` (swept segment $\overline{\mathbf{A}\mathbf{B}}$), `2 = DUAL_LOBE` ($\min(d_A^2, d_B^2)$), `3 = BARREL_HORIZON` (anisotropic horizon barrel), `4 = CAPSULE_BARREL` (swept segment + barrel anisotropy). |
   | `54..51` | `anisoRxQ2_4` | Horizontal-to-vertical aspect multiplier in `UQ2.2` (`4 = 1.0x` isotropic, `8 = 1.41x`, `16 = 2.0x` wide horizon barrel/line; `0` defaults to `4`). |
   | `58..55` | `barrelKappa4` | Quartic barrel curvature coefficient $\kappa \in [0..15]$. |
   | `59` | `motionSalienceBit` | `1` = enable per-tile `[-7, +7]^2` motion-vector salience boost & retinal-slip peripheral `d_merge` scaling (`REQ-LCV-VR-003`). |
   | `60` | `radialDMergeBit` | `1` = force $d_{\text{merge}} = 0$ (`100%` lossless) inside $d_{\text{eff}}^2 \le R_{\text{fovea}}^2$ and scale $d_{\text{merge}}$ radially outside. |
   | `61` | `sideBySideStereoBit` | `1` = mirror anchors $\mathbf{A}, \mathbf{B}$ across left and right eye halves (`eyeTilesX = tilesX >> 1`). |
   | `62` | `psychoAqGradientBit` | `1` = general lossy psychovisual Adaptive Quantization (`AQ`) gradient mode: smoothly ramps per-tile `lossy_lsb_bits` (`0..4`) and `d_merge` along $d_{\text{eff}}^2 / \max(1, R_{\text{fovea}}^2)$ while preserving high-contrast UI/text edge tiles as local micro-foveas. |
   | `63` | `reserved1` | Reserved (`0`). |

2. **Generalized Capsule / Line / Barrel Signed Distance Field ($d_{\text{eff}}^2(\text{tx}, \text{ty})$) & Pre-Conditioning (`lcv_sanitize_poisoned_prior_dct_ringing`, `lcv_condition_cube_rgba16_haar2`):**
   - For side-by-side stereo (`eyeTilesX = tilesX / 2`), each tile $(\text{tx}, \text{ty})$ maps to local eye coordinate $\mathbf{p} = (\text{tx} \bmod \text{eyeTilesX}, \text{ty})$ and evaluates `CAPSULE_LINE` ($t_{\text{Q8}} = \text{clamp}(\lfloor 256 \cdot ((\mathbf{p} - \mathbf{A}) \cdot \mathbf{u}) / \max(1, \|\mathbf{u}\|^2) \rfloor, 0, 256)$), `DUAL_LOBE` ($\min(d_{\text{aniso}}^2(\mathbf{p} - \mathbf{A}), d_{\text{aniso}}^2(\mathbf{p} - \mathbf{B}))$), and `BARREL_HORIZON` ($d_{\text{geom}}^2(\Delta x, \Delta y) = \lfloor 4\Delta x^2 / \max(1, \text{anisoRxQ2}) \rfloor + \Delta y^2 + \lfloor \kappa \Delta x^2 \Delta y^2 / 256 \rfloor$) modulated by motion salience (`motionSalienceBit`).
   - When encoding lossy peripheral tiles, temporal slices $t = 1 \dots K - 1$ predict from the post-quantized reference frame $t - 1$ (`REQ-LCV-VVC-004`). Optional pre-encoding filters (`lcv_sanitize_poisoned_prior_dct_ringing` when `lcv_audit_dct8x8_boundary_ratio_q8 >= 640`, and `lcv_condition_cube_rgba16_haar2` for 10/12-bit sensor shot noise) run prior to cube extraction and leave lossless `FIRST_GEN_CLEAN` streams 100% bit-exact.

3. **Concentric 4-Zone Wire Actions & Standard Decoder Behavior:**

| Gaze / Corridor Zone | SDF Condition ($16 \times 16$ tiles) | Share of Stereo Canvas | Encoder Wire Action (`lcv_gpu_encode_tiles`) | Standard Decoder Behavior (Section 4 & 5.4) |
| :--- | :---: | :---: | :--- | :--- |
| **Zone 0: VR Circular Lens Stencil** | Outside optical eye circle ($d_{\text{eye}}^2 > R_{\text{lens}}^2$) | `~20.0%` | Emits `0-byte` `UNIFORM_INLINE` (`0x8000000000000000ULL`) coalesced into $64 \times 64$ CTU quadtree leaves (`LCV_COLOR_FLAG_QUADTREE_DIR = 0x80`). | $O(1)$ uniform scatter (`0` payload bytes). |
| **Zone 1: Foveal Core / Swept Corridor** | $0 \le d_{\text{eff}}^2 \le R_{\text{fovea}}^2$ | `~1.5%–8.0%` | Retains **both `Section 4A` (`MSBs`) and `Section 4B` (`LSBs`)** with $d_{\text{merge}} = 0$ (`g_base = 0`). | **100% mathematically lossless (`PSNR = inf dB`, `4:4:4:4`)** along the gaze point, swept line, dual anchors, or horizon band. |
| **Zone 2: Parafovea** | $R_{\text{fovea}}^2 < d_{\text{eff}}^2 \le R_{\text{outer}}^2$ | `~8.5%` | Emits `Section 4A` (`g7..g_s`) with radial/slip $d_{\text{merge}}(p, d_{\text{eff}}, S)$ and sets **`lsbSliceByteLen16 = 0` in `ptr64`**. | Sees `lsbSliceByteLen16 == 0` and runs the normative stripped-LSB reconstruction pipeline (Section 5.4). |
| **Zone 3/4: Mid/Far Periphery & High Retinal Slip** | $d_{\text{eff}}^2 > R_{\text{outer}}^2$ | `~65%–70%` | Applies radial + retinal-slip LSB deadband for motion-shifted `TEMPORAL_CLEAN_ACK` (`0-byte` body), sets `lsbSliceByteLen16 = 0`, and folds low-variance `Co`/`Cg` chroma into `constVal8` (`channelMask4`). | Zero-body shifted temporal copy or MSB + stripped-LSB reconstruction with constant chroma. |

---

## 8. Crash & Bit-Rot Recovery Procedures (`lcv_accel.c` & `lcv_codec_system_plugins.c`)

1. **Mid-Write `SIGKILL` / Power-Loss Truncation (`lcv_probe_stream_file` & `lcv_parse_superblock_header_ex`):**
   - Because `.lcv` and `.mkv (V_LCV1)` streams are append-only sequences of self-indexing `Superblock` records (`magic64 = "LCV_V001"`, `headerCrc32`), an abrupt crash mid-write requires zero EOF index table reconstruction. Readers scan forward (or backward via `prevSbOffset64`) and decode 100% of completed `Superblock` records (`srcLen >= totalSbBytes` and valid `headerCrc32`) immediately.
2. **Single-Bit Directory Pointer Self-Healing (`SEC-DED (72, 64)`, `lcv_heal_single_bit_flip64`):**
   - Before dereferencing any directory word in Section 2, decoders and compressed-domain transformers pass `(word64, syndrome8)` through `lcv_heal_single_bit_flip64` (Section 4.2), healing any single-bit flip in `< 5 ns`.
3. **Double-Bit (`DED`) & Payload Corruption Quarantine (`CBIT` + Neighbor-MV Optical Flow & Cold-Anchor Spatiotemporal Concealment):**
   - If `lcv_heal_single_bit_flip64` returns `LCV_UNCORRECTABLE_DED_SENTINEL` (`0x4000000000000000ULL`) or a tile slice fails any bounds/cursor check (`lcv_decode_split_cube_rgba` returns `false`), damage is quarantined strictly to that single $16 \times 16 \times K$ tile (`quarantinedMask[tileIdx] = 1`):
     - **With Prior Superblock Reference (`hasPrevFrames == 1`, `lcv_native_conceal_quarantined_tiles_flow`):** The decoder computes the median motion vector $(dx, dy)$ from up to 4 healthy 4-connected neighbor tiles (`tx-1, tx+1, ty-1, ty+1`) and copies the motion-shifted tile from `prevFrames` (`lcv_apply_shifted_or_fallback_tile`), blending the 2-pixel tile border with healthy adjacent tiles (`(3 * edge + 1 * inside + 2) >> 2` at distance 0, `(1 * edge + 3 * inside + 2) >> 2` at distance 1).
     - **Cold / Anchor Superblock Without Prior Frame Cache (`hasPrevFrames == 0`, `lcv_reconstruct_cold_quarantined_tiles_spatiotemporal`):** The decoder interpolates each pixel $(lx, ly)$ in the quarantined tile from the border pixels of available 4-connected healthy neighbor tiles (`West` at $x = 16\text{tx}-1$, `East` at $x = 16\text{tx}+16$, `North` at $y = 16\text{ty}-1$, `South` at $y = 16\text{ty}+16$) using inverse-distance integer weights $w_W = 16 - lx, w_E = lx + 1, w_N = 16 - ly, w_S = ly + 1$ (`(sum + (wSum >> 1)) / wSum`).
   - Because Section 4B starts with the explicit `uint32 lsbTileOffsets[N_tiles]` table, a corrupted pointer in tile $j$ never shifts the Section 4B byte offsets of subsequent tiles $i > j$.
4. **Mid-Recode Crash Checkpoint (`LCV_FLAG_INLINE_RECODE_PARTIAL = 0x04`):**
   - When `flags8` bit `2` (`0x04`) is set and Section 4B is present (`lsbPoolLen >= 4 + 4 * N_tiles`), the first 4 bytes of the uncompressed Section 4B pool store Big-Endian `(uint16 recodedCursor16, uint16 priorTuningProfile16)` before `lsbTileOffsets[N_tiles]` (`lsbTableBase = 4`). Tiles $\text{tileIdx} < \text{recodedCursor16}$ decode using $s = \text{lsbSplitBits}(\text{tuningProfile16})$, while tiles $\text{tileIdx} \ge \text{recodedCursor16}$ decode using $s_{\text{prior}} = \text{lsbSplitBits}(\text{priorTuningProfile16})$ (`L14049-L14118`).
5. **Log-Structured Slack Framing & Out-of-Order `EOF` Append-on-Expand (`LCV_FLAG_SUPERSEDED_OR_SLACK = 0x10`):**
   - When an in-place transform shrinks a `Superblock` on disk by $\Delta \ge 64\text{ B}$, the freed tail gap is framed as a valid `"LCV_V001"` header with `flags8 = LCV_FLAG_SUPERSEDED_OR_SLACK` (`0x10`) and `msbPoolCompressedLen32 = Delta - 64` (with `tilesX16 = tilesY16 = 0` or `directoryWords16 = 0` so `totalSbBytes == Delta` and valid `headerCrc32`), allowing sequential readers to skip the gap in $O(1)$. When an edited `Superblock` expands, marking the old slot `LCV_FLAG_SUPERSEDED_OR_SLACK` in place and appending the updated `Superblock` at `EOF` with the same `frameIndex64` supersedes the earlier slot without shifting downstream disk blocks.

---

## 9. Compressed-Domain Transform Procedures & GPU-Direct Acceleration (`lcv_accel.c`)

### 9.1 Normative `LCV_FLAG_ZSTD_POOL` Framing & Compressed-Domain Operations
1. **Size-Prefixed Zstd Pool Framing (`LCV_FLAG_ZSTD_POOL = 0x01`, `lcv_zstd_compress_framed` & `lcv_inflate_zstd_superblock_to_raw`):**
   - When bit `0` (`0x01`) of `flags8` is `1`, Section 4A (`msbPoolCompressedLen32` bytes) and—when `lsbPoolCompressedLen32 > 0` and `LCV_FLAG_LSB_STRIPPED == 0`—Section 4B (`lsbPoolCompressedLen32` bytes) are compressed as **two independent size-prefixed Zstd blocks**:
     - Bytes `0..3` of each compressed pool: Big-Endian `uint32 uncompressedByteLen32` (`> 0`).
     - Bytes `4..poolCompressedLen32 - 1`: A single standard RFC 8878 Zstd frame (`ZSTD_compress` / `ZSTD_decompress`) whose decompressed byte length MUST equal `uncompressedByteLen32` (otherwise reject/quarantine).
   - Within `ptr64` and `lsbTileOffsets[N_tiles]`, `relByteOffset30`, `msbSliceByteLen16`, `lsbTileOffsets[tileIdx]`, and `lsbSliceByteLen16` always index the **uncompressed** Section 4A and Section 4B pools.
2. **`lcv_native_crop_or_strip_superblock` & `lcv_native_strip_lsb_outside_radius` — $O(1)$ Zero-Decode Stripping & Sub-Region Cropping:**
   - **Global LSB Stripping (`stripLsb == 1`):** Truncates the `Superblock` at `lsbPoolOffset`, sets `flags8 |= LCV_FLAG_LSB_STRIPPED` (`0x02`) and `lsbPoolCompressedLen32 = 0`, zeroes `lsbSliceByteLen16` in active `ptr64` entries, and recomputes `SEC-DED` syndromes and `headerCrc32` in $O(1)$ without decompressing Section 4A.
   - **Radial / Multi-Fovea LSB Stripping (`lcv_native_strip_lsb_outside_radius`):** Retains Section 4B slices for tiles inside the foveal radius ($r_{\text{eff}} \le R_{\text{fovea}}$), sets `lsbSliceByteLen16 = 0` for peripheral tiles ($r_{\text{eff}} > R_{\text{fovea}}$), rewrites `lsbTileOffsets[N_tiles]`, and compacts Section 4B without decoding pixels or re-encoding Section 4A.
   - **Tile-Aligned Sub-Region Cropping (`cropTilesX x cropTilesY` at `(tileX0, tileY0)`):** Copies only intersecting tile pointers and their Section 4A/4B byte slices into a new `Superblock` of dimensions `(cropTilesX * 16) x (cropTilesY * 16)` and recomputes `relByteOffset30`, `lsbTileOffsets`, `SEC-DED` syndromes, and `headerCrc32`.
3. **`lcv_native_compact_superblock_zstd` — Compressed-Domain Slice Deduplication & Zstd Pool Compaction (`L11300-L11536`):**
   - Deduplicates identical (or within-`dMergeTolerance`) Section 4A tile slices inside the `Superblock` by pointing matching tiles' `relByteOffset30` to the first emitted slice in `dedupPool`, optionally strips or attenuates Section 4B, compresses Section 4A and Section 4B via `lcv_zstd_compress_framed`, sets `LCV_FLAG_ZSTD_POOL = 0x01`, and updates `msbPoolCompressedLen32`, `lsbPoolCompressedLen32`, `SEC-DED` syndromes, and `headerCrc32`.

### 9.2 Informative Tile-Parallel CPU Workgroup & GPU Compute Acceleration (`lcv_accel.c` & `lcv_cube_kernel.comp`)
1. **Parallel Tile-Row / Workgroup Dispatch & Zero-CPU-Callback GPU-Direct Superblock Assembly:**
   - `lcv_native_encode_superblock_rgba` / `16` and `lcv_native_decode_superblock_rgba` / `16` dispatch independent tile workgroups across CPU threads or GPU compute blocks (`1` workgroup of `256` threads per $16 \times 16$ tile in `Vulkan` `GLSL 4.50` `lcv_cube_kernel.comp` and dynamic `CUDA` Driver API `libcuda.so.1` PTX `lcv_batch_analyze_superblock`).
   - `lcv_native_encode_superblock_gpu_direct` executes a 2-kernel zero-CPU-callback GPU-direct pipeline in VRAM (`lcv_gpu_encode_tiles` + `lcv_gpu_assemble_superblock`): Kernel 1 encodes all `tilesX * tilesY` $16 \times 16 \times K$ cubes in parallel across GPU thread blocks, and Kernel 2 performs device-side prefix-sum offset calculation, writes the 64-byte `"LCV_V001"` header + IEEE 802.3 `CRC-32`, populates the directory + `(72, 64)` `SEC-DED` syndromes, and packs Section 4A and Section 4B (`lsbTileOffsets[N_tiles]`) directly into a contiguous `Superblock` buffer.
2. **SWAR & Native `AVX2`/`NEON` Bit-Matrix Transposition (`lcv_accel.c`):**
   - `lcv_transpose_byte_channel_to_bitplanes8` transposes $N_{\text{samples}}$ channel bytes into eight 64-bit bitplane word arrays `uint64_t[8][N_samples >> 6]` in a single pass via a 3-stage SWAR `8x8` bit-matrix butterfly (or `AVX2` `_mm256_movemask_epi8`), feeding hardware `POPCNT`/`TZCNT` run extraction.
   - `lcv_fit_planar_3d_channel` and `lcv_compute_med2d_and_pack_planes` vectorize 3D planar candidate scoring (`_mm256_min_epi32` / `_mm256_max_epi32`), per-frame full-pel/half-pel trajectory `SAD` search (`_mm256_sad_epu8`), 4-channel causal `MED_2D` prediction, and `_mm256_movemask_epi8` 8-bitplane word packing.
3. **Deterministic Prefix-Sum Pointer Assembly:** Encoded tile slices are concatenated in strict linear `tileIdx = 0 .. N_tiles - 1` order to compute `relByteOffset30`, `lsbTileOffsets[N_tiles]`, and `SEC-DED (72, 64)` syndromes, guaranteeing **100% byte-for-byte identical `.lcv` output** across single-threaded and parallel encoders.
