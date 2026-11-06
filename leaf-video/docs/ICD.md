# Interface Control Document: `.lcv` / `V_LCV1` (LeafCraft Video) Binary Specification

- **Document ID:** `LCV-ICD-1.0`
- **Format Version:** `0x0001` (`"LCV_V001"`)
- **License:** `MIT` ([`leaf-video/LICENSE`](../LICENSE) — Open Standard & Reference Codec; zero lock-in, royalty-free interoperability)
- **Governing ADRs:** [`leaf-video-ADR-001`](adr/leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md), [`leaf-video-ADR-002`](adr/leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md)
- **Reference Implementation:** `:leaf-video` (`io.github.dailystruggle.leafvideo.LcvPrimitives`, `LcvCubeCodec`, `LcvStreamContainer`, `LcvMkvPacketizer`, `LcvOnDiskTransformer`, MIT License)
- **Endianness:** All multi-byte fixed-width integers (`uint16`, `uint32`, `uint64`) are **Big-Endian (Network Byte Order)** unless explicitly marked as `VarInt` (unsigned LEB128).

---

## 1. Scope & Design Invariants

This Interface Control Document (ICD) defines the normative byte- and bit-level layout of `.lcv` standalone stream files and Matroska `.mkv` (`CodecID = "V_LCV1"`) packet payloads.

> **Open Format & Interoperability License (`MIT`):** While the commercial Tier 2 Studio GUI installers and Tier 3 Native SIMD/GPU & Wireless UEP SDK binaries (`resource/LeafVideo`) are distributed under the **LeafCraft Commercial Software License** (`LicenseRef-LeafCraft-Commercial`, [`resource/LeafVideo/LICENSE`](../../resource/LeafVideo/LICENSE)), this `.lcv` / `V_LCV1` binary specification (`LCV-ICD-1.0`) and the `:leaf-video` Java 17+ reference codec (`io.github.dailystruggle.leafvideo.*`) are openly licensed under the **MIT License** ([`leaf-video/LICENSE`](../LICENSE)). Streamers, video editors, NLE plugins, `ffmpeg`/VLC demuxers, and hardware integrations may freely inspect, implement, or reverse-engineer `.lcv` / `V_LCV1` readers, writers, and compressed-domain transformers without royalty or license-server lock-in.

1. **Spatiotemporal Grid Partitioning:** Video frames of width $W$ and height $H$ are grouped in temporal windows of $K \in \{1, 2, 4, 8, 16\}$ frames (`Superblock`) and partitioned spatially into $\text{tilesX} = \lceil W / 16 \rceil$ by $\text{tilesY} = \lceil H / 16 \rceil$ tiles. Each tile forms an independent $16 \times 16 \times K$ spatiotemporal cube ($N_{\text{samples}} = 256 \cdot K$). Edge tiles when $W$ or $H$ is not a multiple of $16$ pad out-of-frame pixels by repeating the boundary pixel.
2. **Outcome-Driven Enumerated Variations:** Each cube dynamically selects the smallest byte-size representation from an extensible enumerated set of Roaring and Gray-Hilbert variations (`UNIFORM_INLINE`, `TEMPORAL_CLEAN_ACK`, `UI_PALETTE_ROARING` with sub-variations `ROARING_SINGLETON` / `ROARING_DELTA_RUN` / `ROARING_DELTA_ARRAY` / `ROARING_BITMAP`, `GRAY_PLANES_RAW`, `GRAY_PLANES_HILBERT_DELTA`, and `RAW_CEILING`).
3. **Wire-Compatible Distance-Scaled RLE Concatenation:** Variable lossy run concatenation (`rawGap <= d_merge(plane, dist)`) emits standard `mode 10` (`4|4` nibble `adjGap | lenMinusOne`) tokens and requires zero special decoder flags.
4. **Tunable Split MSB / LSB Slice Framing:** Active `GRAY_PLANES_*` cubes split their encoded bitplanes at a configurable bit boundary $s = \text{lsbSplitBits} \in \{1, 2, 3, 4\}$ (default $s = 2$) into a **Base MSB Slice (`g7..g_s`)** in Section 4A and a **Truncatable Tail LSB Slice (`g_{s-1}..g0`)** in Section 4B whose exact byte lengths are recorded in the 64-bit tile pointer entry, enabling $O(1)$ zero-decode LSB proxy stripping, Unequal Error Protection (`UEP`) shedding, and spatial cropping on disk.

---

## 2. File & Stream Layout Overview (`Superblock`)

A `.lcv` stream is an append-only sequence of self-contained `Superblock` records. There is no mandatory end-of-file footer; truncating the stream mid-write leaves all completed `Superblock` records valid and $O(1)$ seekable.

```
+-----------------------------------------------------------------------+ Offset 0x0000
| 1. Superblock Header: "LCV_V001" (64 bytes, Big-Endian)               |
+-----------------------------------------------------------------------+ Offset 0x0040
| 2. 2D Tile Pointer Directory: uint64[tilesX * tilesY] (8 B / tile)    |
+-----------------------------------------------------------------------+ Offset 0x0040 + 8 * N_tiles
| 3. Extended Hamming (72, 64) SEC-DED Syndrome Table (1 B / tile)      |
+-----------------------------------------------------------------------+ Offset 0x0040 + 9 * N_tiles
| 4A. Base MSB Slice Pool (msbPoolCompressedLen32 bytes; Raw or Zstd)   |
|     - Tile (tx, ty) Base MSB Slice (msbSliceByteLen16 bytes)          |
+-----------------------------------------------------------------------+ Offset 0x0040 + 9 * N_tiles + msbPoolCompressedLen32
| 4B. Tail LSB Slice Pool (lsbPoolCompressedLen32 bytes; Raw or Zstd)   |
|     - Tile (tx, ty) Tail LSB Slice (lsbSliceByteLen16 bytes)          |
+-----------------------------------------------------------------------+ Offset superblockByteSize
```

---

## 3. Section 1 — `64-Byte` Superblock Header (`0x0000..0x003F`)

| Byte Offset | Type | Field Name | Description |
| :--- | :--- | :--- | :--- |
| `0x00..0x07` | `uint64` | `magic64` | ASCII `"LCV_V001"` (`0x4C43565F56303031L`). Sync marker for stream recovery. |
| `0x08..0x09` | `uint16` | `formatVersion16` | `0x0001` (`LCV-ICD-1.0`). |
| `0x0A..0x0B` | `uint16` | `width16` | Frame width in pixels ($1 \le W \le 65535$). |
| `0x0C..0x0D` | `uint16` | `height16` | Frame height in pixels ($1 \le H \le 65535$). |
| `0x0E` | `uint8` | `depthK8` | Temporal depth $K \in \{1, 2, 4, 8, 16\}$ frames in this superblock ($K = 1$ selects zero-lookahead 2D `H_2` intra/clean-ack coding; $K = 2$ selects `16.6 ms` ultra-low-latency 3D coding at `120 fps`). Decoders MUST reject any Superblock where `depthK8` $\notin \{1, 2, 4, 8, 16\}$. |
| `0x0F` | `uint8` | `bitDepth8` | Channel bit depth (`8` standard; `10` reserved). |
| `0x10` | `uint8` | `colorSpace8` | `0x00 = YCoCg_R_RGBA`, `0x01 = DIRECT_RGBA`. |
| `0x11` | `uint8` | `flags8` | Bit 0 (`0x01`): `FLAG_ZSTD_POOL` (Section 4A and Section 4B are compressed as two independent Zstd frames). Bit 1 (`0x02`): `FLAG_LSB_STRIPPED` (Section 4B `g_{s-1}..g0` tail pool stripped; `lsbPoolCompressedLen32 == 0`). Bits 2..7: reserved (`0`). |
| `0x12..0x13` | `uint16` | `tilesX16` | Horizontal tile count $\lceil W / 16 \rceil$. |
| `0x14..0x15` | `uint16` | `tilesY16` | Vertical tile count $\lceil H / 16 \rceil$. |
| `0x16..0x17` | `uint16` | `roiTileX16` | Focal ROI center tile X coordinate for distance-scaled RLE. |
| `0x18..0x19` | `uint16` | `roiTileY16` | Focal ROI center tile Y coordinate for distance-scaled RLE. |
| `0x1A..0x1B` | `uint16` | `tuningProfile16` | Normative packed rate-latency-resilience descriptor (`Section 7.1`; `0x0000` = default lossless with $s = 2$ LSB split bits and $M_{\text{refresh}} = 8$). |
| `0x1C..0x1F` | `uint32` | `msbPoolCompressedLen32` | On-disk byte length of Section 4A (Base MSB Slice Pool). |
| `0x20..0x23` | `uint32` | `lsbPoolCompressedLen32` | On-disk byte length of Section 4B (Tail LSB Slice Pool; `0` when `FLAG_LSB_STRIPPED == 1`). |
| `0x24..0x2B` | `uint64` | `frameIndex64` | Starting presentation frame index ($0, K, 2K, \dots$). |
| `0x2C..0x33` | `uint64` | `prevSbOffset64` | Absolute stream byte offset of the immediately preceding `Superblock` (`0` for the first superblock), enabling $O(1)$ reverse seeking without an EOF index. |
| `0x34..0x37` | `uint32` | `fpsNum32` | Frame rate numerator (e.g., `60`). |
| `0x38..0x3B` | `uint32` | `fpsDen32` | Frame rate denominator (e.g., `1`). |
| `0x3C..0x3F` | `uint32` | `headerCrc32` | Mix64/CRC32 integrity digest over header bytes `0x00..0x3B`. |

---

## 4. Section 2 & 3 — 2D Tile Pointer Directory & `SEC-DED (72, 64)` Syndrome Table

For tile coordinates $(\text{tx}, \text{ty})$ with $0 \le \text{tx} < \text{tilesX}$ and $0 \le \text{ty} < \text{tilesY}$, the linear tile index is:
$$\text{tileIdx} = \text{ty} \cdot \text{tilesX} + \text{tx}$$

### 4.1 `uint64` Tile Pointer Entry (`ptr64`)

Each entry at byte offset `0x0040 + 8 * tileIdx` is a 64-bit word with two layouts selected by `bit 63`:

#### Case 1: Inline `0-Byte` Body (`bit 63 == 1`)
- **`bit 63 = 1`, `bit 62 = 0` (`UNIFORM_INLINE` — Precedence Rule):** All $256 \cdot K$ samples in the cube have the exact same `RGBA` color. `UNIFORM_INLINE` MUST always take precedence over `TEMPORAL_CLEAN_ACK` so uniform static tiles require zero backward seeks.
  - `bits 61..32` (`30 bits`): Reserved (`0`).
  - `bits 31..0` (`32 bits`): `exactRgba32` (exact 32-bit `0xRRGGBBAA` pixel value). Body size is **`0 bytes`**.
- **`bit 63 = 1`, `bit 62 = 1` (`TEMPORAL_CLEAN_ACK` — Bounded `Case A`):** The non-uniform cube is 100% identical to tile `(tx, ty)` (`tileIdx`) in an anchor `Superblock` within the bounded refresh horizon $1 \le \Delta_{\text{sb}} \le M_{\text{refresh}}$ configured in `tuningProfile16` ($M_{\text{refresh}} \in \{0, 1, 2, 4, 8, 16, 32, 64\}$, default `8`). Encoders MUST emit a fresh active `CubeFrame` whenever $M_{\text{refresh}} == 0$ or when a static non-uniform tile reaches $\Delta_{\text{sb}} = M_{\text{refresh}}$ superblocks, bounding random access to $O(1)$.
  - `bits 61..46` (`16 bits`): `anchorSbDelta16` ($1 \le \Delta_{\text{sb}} \le M_{\text{refresh}}$, number of superblocks back to the anchor superblock that holds the active `CubeFrame` at the same `tileIdx`).
  - `bits 45..16` (`30 bits`): `anchorRelMsbOffset30` (relative byte offset of the tile's Base MSB Slice in Section 4A of the anchor superblock).
  - `bits 15..0` (`16 bits`): `anchorMsbByteLen16` (byte length of the tile's Base MSB Slice in Section 4A of the anchor superblock; full Section 4A + Section 4B slices are resolved via `tileIdx` in the anchor superblock's `SEC-DED`-protected directory or the decoder's in-RAM tile cache). Body size in the current superblock is **`0 bytes`**.

#### Case 2: Active `CubeFrame` Payload (`bit 63 == 0`)
| Bits | Width | Field Name | Description |
| :--- | :--- | :--- | :--- |
| `63` | `1 bit` | `isZeroBody` | `0` (active payload in Section 4A / 4B). |
| `62` | `1 bit` | `reservedZero` | `0` (note `0x4000_0000_0000_0000L` is reserved as `UNCORRECTABLE_DED_SENTINEL`). |
| `61..32` | `30 bits` | `relByteOffset30` | Relative byte offset ($0 \dots 2^{30}-1$) of this tile's Base MSB Slice from the start of the uncompressed Section 4A pool. |
| `31..16` | `16 bits` | `msbSliceByteLen16` | Byte length of the **Base MSB Slice** in Section 4A (`cubeHeader8` + UI Roaring body or `planeModes16` + `g7..g2` planes). |
| `15..0` | `16 bits` | `lsbSliceByteLen16` | Byte length of the **Truncatable Tail LSB Slice** in Section 4B (`g1..g0` planes for `GRAY_PLANES_*`; `0` for `UI_PALETTE_ROARING` and `RAW_CEILING`). |

**Section 4B Direct `O(1)` Offset Index (`uint32 lsbTileOffsets[N_tiles]`):**
Whenever Section 4B is present (`lsbPoolCompressedLen32 > 0` and `FLAG_LSB_STRIPPED == 0`), the uncompressed Section 4B pool begins with a `uint32[N_tiles]` Big-Endian offset table (`4 * N_tiles` bytes) where `lsbTileOffsets[tileIdx]` stores the exact byte offset of tile `tileIdx`'s Tail LSB Slice relative to the start of the Section 4B payload area (`4 * N_tiles`). This guarantees:
1. **$O(1)$ Single-Tile & ROI Seeking:** Decoding a single tile or cropping a sub-region (`cropSubRegion`) looks up `lsbTileOffsets[tileIdx]` directly without scanning preceding tiles `0 .. tileIdx - 1`.
2. **Zero `DED` Contagion Across Section 4B:** If tile $j < \text{tileIdx}$ suffers an uncorrectable Double-Bit Error (`DED`) in its `ptr64` (`UNCORRECTABLE_DED_SENTINEL`), subsequent healthy tiles $i > j$ still locate their exact Section 4B slices via `lsbTileOffsets[i]` with zero offset shift.
3. **$O(1)$ Zero-Decode LSB Stripping:** `LcvOnDiskTransformer#stripLsbPlanes` truncates the entire Section 4B pool (`lsbPoolCompressedLen32 = 0`) in $O(1)$ on disk even when `FLAG_ZSTD_POOL == 1`.

### 4.2 Extended Hamming `(72, 64)` `SEC-DED` Syndrome Table (`1 B / tile`)

At byte offset `0x0040 + 8 * N_tiles + tileIdx`, a single byte `syndrome8` stores the 7-bit Hamming parity (`bits 6..0`) and 1-bit overall even parity (`bit 7`) of `ptr64`:
- On read, `LcvPrimitives#healSingleBitFlip64(ptr64, syndrome8)` computes `diff = computeHammingSecDed8(ptr64) ^ (syndrome8 & 0xFF)`.
- `diff == 0`: `ptr64` is error-free.
- `(diff & 0x80) != 0`: Single-bit flip; healed in place if `1 <= (diff & 0x7F) <= 64` (data bit flip) or ignored if parity-bit flip.
- `(diff & 0x80) == 0 && diff != 0`: Double-bit error detected (`DED`); returns `UNCORRECTABLE_DED_SENTINEL` (`0x4000_0000_0000_0000L`) and triggers `CBIT` single-tile quarantine.

---

## 5. Section 4 — `CubeFrame` Bitstream Grammar

Each active tile (`bit 63 == 0`) begins at `poolOffset + relByteOffset30` with a 1-byte `cubeHeader8`:

### 5.1 `cubeHeader8` (`1 Byte`)

| Bits | Field | Values |
| :--- | :--- | :--- |
| `7..6` | `curveId` | `00`: `TYX` ($t \to y \to x$)<br>`01`: `H_3` (3D Hilbert $16 \times 16 \times K$)<br>`10`: `T+H_2` (2D Hilbert $(x, y)$ outer, $t = 0..K-1$ inner)<br>`11`: `TXY` ($t \to x \to y$) |
| `5..4` | `cubeMode` | `00`: `UI_PALETTE_ROARING` (Discrete palette $\le 16$ colors + adaptive Roaring sub-variations)<br>`01`: `GRAY_PLANES_RAW` (`YCoCg-R` + Reflected Gray-Code bitplanes `g7..g0`)<br>`10`: `GRAY_PLANES_HILBERT_DELTA` (`YCoCg-R` + 1st-order curve delta + ZigZag + Gray bitplanes `g7..g0`)<br>`11`: `RAW_CEILING` (Uncompressed active channels) |
| `3..0` | `channelMask4` | For `cubeMode != 00`: Bitmask of non-constant channels (`bit 0 = Y`, `bit 1 = Co`, `bit 2 = Cg`, `bit 3 = Alpha`).<br>For `cubeMode == 00` (`UI_PALETTE_ROARING`): `paletteCountMinusTwo` ($|P| - 2 \in [0, 14]$, so $|P| \in [2, 16]$). |

---

### 5.2 Mode `00`: `UI_PALETTE_ROARING` (Enumerated Roaring Sub-Variations)

Used when the cube has $2 \le |P| \le 16$ unique 32-bit `RGBA` colors and the resulting Roaring payload is smaller than `GRAY_PLANES_*`:
1. **Palette Table (`4 * |P|` bytes):**
   - `int32[|P|]` exact `RGBA` colors ordered by descending frequency in the cube.
   - **`palette[0]` (the majority color) is implicit:** all curve positions not claimed by states $p = 1 \dots |P|-1$ default to `palette[0]`.
2. **Sub-Variation Directory (`ceil((|P| - 1) / 4)` bytes):**
   - 2 bits per minority state $p \in \{1 \dots |P|-1\}$ specifying the winning Roaring container variation for state $p$:
     - `00` (`ROARING_SINGLETON`): Followed by `uint16 pos16` (`2 B`) giving the single curve index where state $p$ occurs.
     - `01` (`ROARING_DELTA_RUN`): Followed by a `mode 10` `4|4` Nibble Gap/Run Token Stream (Section 5.4) encoding the `1`-runs of state $p$'s membership bitmap along `curveId`.
     - `10` (`ROARING_DELTA_ARRAY`): Followed by `VarInt count` and `count` `VarInt` delta-gaps (`pos_0`, `pos_1 - pos_0 - 1`, $\dots$).
     - `11` (`ROARING_BITMAP`): Followed by `(256 * K) / 8` raw bitmap bytes (`1` bit per curve sample).

---

### 5.3 Reversible `YCoCg-R + Alpha` Mapping (Modes `01`, `10`, `11`)

Given 8-bit unsigned `R, G, B, A` $\in [0, 255]$, forward decorrelation computes modular 8-bit unsigned channels `(Y8, Co8, Cg8, A8)` using signed modular lifting on $\mathbb{Z}_{256}$ plus monotonic `+128` offset-binary chroma centering (`Co8 = 128, Cg8 = 128` for neutral gray `R = G = B`) so bitplanes `g0..g2` represent true magnitude LSBs (preventing chroma sign inversion when `g1..g0` are stripped or coalesced by `d_merge`):
```java
int co = (byte) (r - b);
int t  = (b + (co >> 1)) & 0xFF;
int cg = (byte) (g - t);
int y8 = (t + (cg >> 1)) & 0xFF;
int co8 = (co + 128) & 0xFF;
int cg8 = (cg + 128) & 0xFF;
```
Inverse reconstruction is 100% bit-exact across all $256^4$ `RGBA` tuples (`REQ-LCV-F-001`):
```java
int co = (byte) (co8 - 128);
int cg = (byte) (cg8 - 128);
int t  = (y8 - (cg >> 1)) & 0xFF;
int g  = (cg + t) & 0xFF;
int b  = (t - (co >> 1)) & 0xFF;
int r  = (b + co) & 0xFF;
```
**Normative Signed-Byte (`int8_t`) Arithmetic Shift Invariant:** In both the forward and inverse equations, `co` and `cg` MUST be interpreted as signed 8-bit integers (`byte` in Java, `int8_t` in C/C++/Rust) sign-extended to signed integers in $[-128, 127]$ **before** the arithmetic right-shift `co >> 1` and `cg >> 1`. Applying an unsigned logical shift (`>>> 1` on `uint8_t`) without `int8_t` sign-extension is non-compliant and will diverge by `128` whenever `(r - b) & 0x80 != 0`.

#### Inactive / Constant Channel Folding (`channelMask4`)
For each channel $c \in \{0:\text{Y}, 1:\text{Co}, 2:\text{Cg}, 3:\text{A}\}$:
- Under `+128` offset-binary chroma centering, neutral gray (`R = G = B`) has `Co8 = 128, Cg8 = 128` and opaque alpha has `A8 = 255`.
- Right after `cubeHeader8`, each inactive channel $c \in \{0..3\}$ with `((channelMask4 >>> c) & 1) == 0` writes **1 explicit byte** `constVal8[c]` in the Base MSB Slice, costing `1 B` for a constant channel instead of bitplanes.
- For every active channel $c \in \{0..3\}$ with `((channelMask4 >>> c) & 1) == 1`:
  - In `cubeMode = 01` (`GRAY_PLANES_RAW`) or `10` (`GRAY_PLANES_HILBERT_DELTA`), a `uint16` `planeModes16[c]` word (`2 B`) is stored in the Base MSB Slice header.

---

### 5.4 Modes `01` & `10`: Split MSB (`g7..g_s`) & LSB (`g_{s-1}..g0`) Gray-Code Bitplanes

Let $s = \text{lsbSplitBits} \in \{1, 2, 3, 4\}$ (decoded from `tuningProfile16` bits `15..14`, default $s = 2$) and $B_{\text{msb}} = 8 - s \in \{4, 5, 6, 7\}$ with mask $M_{\text{msb}} = 2^{B_{\text{msb}}} - 1$ and half-modulus $H_{\text{msb}} = 2^{B_{\text{msb}} - 1}$.

For each active channel $c$ along the selected curve $i \in [0, N_{\text{samples}} - 1]$:
- **If `cubeMode == 01` (`GRAY_PLANES_RAW`):**
  - Each 8-bit sample $v_i \in [0, 255]$ is mapped directly to Reflected Binary Gray Code:
    $$G_i = v_i \oplus (v_i \ggg 1)$$
- **If `cubeMode == 10` (`GRAY_PLANES_HILBERT_DELTA` — Sub-Mode `0x00` 1D Hilbert MSB Delta OR Sub-Mode `0x01` 3D Spatiotemporal Planar + Motion-Sheared Coupled Residual):**
  - Right after `constVal8[c]`, a 1-byte `deltaFlags8` header selects the delta sub-mode:
    - **`deltaFlags8 == 0x00` (`DELTA_SUBMODE_HILBERT_1D_MSB`):**
      - To guarantee that zero-decode `stripLsbPlanes` (`g_{s-1}..g0` truncation) never causes cumulative prefix-sum drift across the curve, the 1st-order Hilbert delta operates **strictly on the top $B_{\text{msb}} = 8 - s$ MSB bits** $m_i = (v_i \ggg s) \ \& \ M_{\text{msb}}$, while the bottom $s$ LSB bits $l_i = v_i \ \& \ (2^s - 1)$ are Gray-coded independently without differencing:
        - For $i = 0$: $g_{\text{msb}, 0} = m_0 \oplus (m_0 \ggg 1) \in [0, M_{\text{msb}}]$.
        - For $i \ge 1$: signed $B_{\text{msb}}$-bit modular step $d_i = ((m_i - m_{i-1} + H_{\text{msb}}) \ \& \ M_{\text{msb}}) - H_{\text{msb}} \in [-H_{\text{msb}}, H_{\text{msb}} - 1]$ is mapped directly via $B_{\text{msb}}$-bit unsigned ZigZag (without redundant second Gray coding):
          $$g_{\text{msb}, i} = ((d_i \ll 1) \oplus (d_i \gg 31)) \ \& \ M_{\text{msb}}$$
        - The $s$-bit LSB word is Gray-coded into planes `g_{s-1}..g0` (`G_lsb = l_i ^ (l_i >>> 1)`):
          $$G_i = (g_{\text{msb}, i} \ll s) \mid G_{\text{lsb}}$$
    - **`(deltaFlags8 & 0x01) != 0` (`DELTA_SUBMODE_PLANAR_3D_MV` and `DELTA_SUBMODE_WAVELET_3D_HAAR`):**
      - Bit `0` (`0x01`): `DELTA_SUBMODE_PLANAR_3D_MV` enabled.
      - Bit `1` (`0x02`): `DELTA_FLAG_DIRECT_RGBA` (`1` = channels are direct `R, G, B, A` to avoid convolving independent RGB sensor noise; `0` = `YCoCg-R + Alpha`).
      - Bit `2` (`0x04`): `DELTA_FLAG_MAXU_CONDITIONED` (`1` = bitplanes are upper-bound conditioned on `maxU8`).
      - Bit `3` (`0x08`): `DELTA_FLAG_MOTION_SHEARED` (`1` = a packed 1-byte velocity vector `packedMv8 = ((vx + 8) << 4) | ((vy + 8) & 0x0F)` for $v_x, v_y \in [-8, +7]$ pixels/frame immediately follows `deltaFlags8`).
      - Bit `4` (`0x10`): `DELTA_SUBMODE_WAVELET_3D_HAAR` (`1` = each active channel appends a $4 \times 4$ spatial Haar LL subband header: `uint8 haarStep8` followed, when `haarStep8 > 0`, by `8 bytes` of `16` 4-bit unsigned sub-block nibbles $h_b \in [0, 15]$ for $b = ((ly \ggg 2) \ll 2) \mid (lx \ggg 2)$).
      - Each active channel $c$ stores `planeModes16[c]` (`2 B`) followed by a `10-byte` 3D planar header in Section 4A: `int16 minErr16`, `int16 sx_q10`, `int16 sy_q10`, `int16 st_q10`, `uint8 maxU8`, and `uint8 bestFrac8` (plus `1 B` or `9 B` Haar LL subband header when `DELTA_SUBMODE_WAVELET_3D_HAAR` is set).
      - For each sample $i$ at tile coordinates $(lx, ly, lt)$, the closed-form 3D planar + Haar LL prediction is:
        $$\hat{v}_i = \left(\left(\text{bestFrac8} + ((s_{x,\text{q10}} \cdot lx + s_{y,\text{q10}} \cdot ly + s_{t,\text{q10}} \cdot lt) \gg 2)\right) \gg 8\right) + h_{b(i)} \cdot \text{haarStep8}$$
        and the non-negative monotonic offset-binary residual is $u_i = (v_i - \hat{v}_i) - \text{minErr16} \in [0, \text{maxU8}]$.
      - **Upper-Bound Bitplane Conditioning (`maxU8`):** In Section 4A (`p = 7..s`), sample $i$ is eligible on plane $p$ iff $U^{(p+1)}_i + 2^p \le \text{maxU8}$. In Section 4B (`p = s-1..0`), sample $i$ is eligible on plane $p$ iff $M_i + 2^p \le \text{maxU8}$. When Section 4B is stripped (`stripLsbPlanes`), the decoder reconstructs the unbiased clamped interval midpoint $\hat{u}_i = (u_{\text{msb},i} + \min(\text{maxU8}, u_{\text{msb},i} + 2^s - 1)) \ggg 1$.
      - **Inline Packet-to-Packet Recode Checkpoint (`FLAG_INLINE_RECODE_PARTIAL = 0x04`):** When bit `2` of `flags8` is `1`, Section 4B begins with a 4-byte inline recode checkpoint `(uint16 recodedCursor16, uint16 priorTuningProfile16)` before `lsbTileOffsets[N_tiles]`. Tiles $0 \dots \text{recodedCursor16} - 1$ decode under `header.tuningProfile()` while tiles $\text{recodedCursor16} \dots N_{\text{tiles}} - 1$ decode under `priorTuningProfile16`, making mid-recode crashed files immediately playable and $O(1)$ resumable (`LcvOnDiskTransformer#resumeInlineRecode`).
      - **Dense Mode `11` Triplet Prefix Coding:** In `DELTA_SUBMODE_PLANAR_3D_MV`, `mode 11` (`PLANE_RAW_BITMAP`) begins with `denseTag8`: `0x00` = raw bitpack (`(nActive + 7) >>> 3` bytes), `0x01` = 3-sample Zero-Skip Huffman Triplet code (`000 -> 0` `1b`, weight-1 `-> 3b`, weight-2/3 `-> 5b`), `0x02` = 3-sample One-Skip Huffman Triplet code.
  - When all 8 planes `g7..g0` are decoded, $v_i$ reconstructs with **100% bit-exact lossless accuracy (`REQ-LCV-F-001`)**. When `g_{s-1}..g0` are stripped on disk or dropped over a wireless link (`stripLsbPlanes`), $v_i$ reconstructs with **zero cumulative drift** and error $\le 2^s - 1$ across the entire cube!
- Bitplane $p \in \{7, 6, 5, 4, 3, 2, 1, 0\}$ extracts bit $p$ of $G_i$: $b_{p,i} = (G_i \ggg p) \ \& \ 1$.

#### `planeModes16[c]` Directory (`2 Bytes`, Big-Endian)
Bits `(2 * p + 1) .. (2 * p)` encode the 2-bit mode for bitplane $p \in \{7 \dots 0\}$:
- **`00` (`PLANE_ALL_ZERO`):** All $N_{\text{samples}}$ bits in plane $p$ are `0` (**`0 bytes`**).
- **`01` (`PLANE_ALL_ONE`):** All $N_{\text{samples}}$ bits in plane $p$ are `1` (**`0 bytes`**).
- **`10` (`PLANE_NIBBLE_RLE`):** `4|4` Nibble Gap/Run Token Stream (detailed below).
- **`11` (`PLANE_RAW_BITMAP`):** Raw packed bitplane ceiling of `N_samples / 8` bytes (`32 B` for $K=1$, `64 B` for $K=2$, `128 B` for $K=4$, `256 B` for $K=8$, `512 B` for $K=16$).

#### Base MSB Slice vs. Tail LSB Slice Ordering
To allow $O(1)$ LSB truncation (`stripLsbPlanes`) without parsing MSB bitplanes:
1. **Base MSB Slice (`msbSliceByteLen16` bytes in Section 4A):**
   - `cubeHeader8` (`1 B`)
   - `constVal8[c]` (`1 B` per inactive channel where `((channelMask4 >>> c) & 1) == 0`)
   - `planeModes16[c]` (`2 B` per active channel where `((channelMask4 >>> c) & 1) == 1`)
   - For each active channel $c$, the encoded payloads for **planes $p = 7, \dots, s$** in descending order.
2. **Truncatable Tail LSB Slice (`lsbSliceByteLen16` bytes in Section 4B):**
   - Stored in the Section 4B pool at `lsbTileOffsets[tileIdx]`.
   - For each active channel $c$, the encoded payloads for **planes $p = s - 1, \dots, 0$** in descending order.

#### `mode 10` (`PLANE_NIBBLE_RLE`) `4|4` Nibble Gap/Run Token Stream
Identical to `LCA-ICD-1.0` Section 7.4:
1. `VarInt runCount`: Number of `1`-runs in the bitplane ($1 \le \text{runCount} \le N_{\text{samples}}$).
2. For each run $r = 0 \dots \text{runCount} - 1$:
   - Let `rawGap` be the count of `0` bits before this run (`rawGap >= 0` for $r = 0$; `rawGap >= 1` for $r > 0$).
   - Define `adjGap = (r == 0) ? rawGap : (rawGap - 1)`.
   - Define `lenMinusOne = runLen - 1` (where `runLen >= 1` is the count of contiguous `1` bits).
   - `gNib = Math.min(adjGap, 15)` (`4 bits`, high nibble) and `lNib = Math.min(lenMinusOne, 15)` (`4 bits`, low nibble).
   - Emit `1 byte`: `token8 = (gNib << 4) | lNib`.
   - If `gNib == 15`, emit `VarInt (adjGap - 15)`.
   - If `lNib == 15`, emit `VarInt (lenMinusOne - 15)`.
3. Any trailing `0` bits after the final run `runCount - 1` up to $N_{\text{samples}}$ are implicit (`0 bytes`).

#### Compressed-Domain Token Coalescing Rule (`REQ-LCV-F-002` & `REQ-LCV-F-005`)
Given a token stream $\{(g_r, L_r)\}_{r=0}^{M-1}$ in plane $p$ at tile radial distance $\text{dist}$, if `rawGap_{r+1} <= d_merge(p, dist)`:
- Run $r$ and run $r+1$ coalesce in place into $(g_r, L_r + \text{rawGap}_{r+1} + L_{r+1})$, decrementing `runCount` by `1` and eliminating the token byte(s) of run $r+1$ without altering the decoder syntax.

---

## 6. Matroska `.mkv` (`CodecID = V_LCV1`) Framing (`LcvMkvPacketizer`)

When muxed into a Matroska (`.mkv`) stream:
- **EBML Header (`0x1A45DFA3`):** `DocType = "matroska"`, `DocTypeVersion = 4`, `DocTypeReadVersion = 2`.
- **Segment (`0x18538067`):** Written with unknown/streaming size (`0x01FFFFFFFFFFFFFF`) so live recording never requires seeking back to the file header on `SIGKILL`.
- **Tracks (`0x1654AE6B`) $\to$ TrackEntry (`0xAE`):**
  - `TrackNumber = 1`, `TrackType = 1` (video), `CodecID = "V_LCV1"`, `Video` (`PixelWidth = W`, `PixelHeight = H`).
- **Cluster (`0x1F43B675`) $\to$ SimpleBlock (`0xA3`):**
  - Each `Superblock` (`"LCV_V001"`) is emitted as the payload of a keyframe `SimpleBlock` inside an independent `Cluster`.
  - Stream recovery scans for EBML `Cluster` ID (`0x1F43B675`) or `Superblock` magic (`0x4C43565F56303031L` `"LCV_V001"`), guaranteeing full recovery of all preceding superblocks and tile-level quarantine inside damaged clusters.

---

## 7. Normative Tunability & Operating Profiles (`tuningProfile16`)

### 7.1 `tuningProfile16` Bitfield (`0x1A..0x1B`, `uint16` Big-Endian)

The 16-bit `tuningProfile16` header word records the active MSB/LSB split boundary, the distance-scaled RLE concatenation policy `d_merge(p, dist)`, and the `TEMPORAL_CLEAN_ACK` refresh horizon so decoders, in-flight wireless relays, and `LcvOnDiskTransformer` can inspect or adapt stream parameters in-band. A value of `0x0000` encodes the canonical default (`lsbSplitBits = 2`, `LOSSLESS` $d_{\text{merge}} = 0$, $M_{\text{refresh}} = 8$):

| Bits | Width | Field Name | Encoding & Semantics |
| :--- | :--- | :--- | :--- |
| `15..14` | `2 bits` | `lsbSplitCode2` | Number of LSB bitplanes $s = \text{lsbSplitBits}$ placed in Section 4B (while top $8 - s$ MSB bitplanes reside in Section 4A):<br>`00` $\to s = 2$ (Default: `g7..g2` in 4A, `g1..g0` in 4B)<br>`01` $\to s = 1$ (Light: `g7..g1` in 4A, `g0` in 4B)<br>`10` $\to s = 3$ (Medium UEP: `g7..g3` in 4A, `g2..g0` in 4B)<br>`11` $\to s = 4$ (Deep UEP: `g7..g4` in 4A, `g3..g0` in 4B) |
| `13..11` | `3 bits` | `losslessPlaneCutoff3` | Bitplane index $p_{\text{cutoff}} \in \{0 \dots 7\}$ above or equal to which $d_{\text{merge}}(p, \text{dist}) = 0$ (`0` = 100% lossless across all planes). |
| `10..7` | `4 bits` | `baseMaxGap4` | Base bridged zero-gap $g_{\text{base}} \in \{0 \dots 15\}$ at the focal ROI center ($\text{dist} = 0$) on plane $p_{\text{cutoff}} - 1$. |
| `6..3` | `4 bits` | `radialScaleQ2_4` | Radial gap growth per Euclidean tile distance in unsigned `UQ2.2` fixed-point ($\alpha_{\text{radial}} = \text{radialScaleQ2\_4} / 4.0 \in [0.00, 3.75]$ in steps of `0.25`). |
| `2..0` | `3 bits` | `cleanAckRefreshCode3` | Maximum `TEMPORAL_CLEAN_ACK` lookback horizon $M_{\text{refresh}}$ in superblocks:<br>`000` $\to 8$ (Default), `001` $\to 0$ (Pure intra-superblock), `010` $\to 1$, `011` $\to 2$, `100` $\to 4$, `101` $\to 16$, `110` $\to 32$, `111` $\to 64$. |

Given $(p_{\text{cutoff}}, g_{\text{base}}, \alpha_{\text{radial}})$ unpacked from `tuningProfile16` and Euclidean tile distance $r = \sqrt{(\text{tx} - \text{roiTileX16})^2 + (\text{ty} - \text{roiTileY16})^2}$, the normative maximum bridged gap on plane $p \in \{0 \dots 7\}$ is:
$$d_{\text{merge}}(p, r) = \begin{cases} 0 & \text{if } p \ge p_{\text{cutoff}} \text{ or } p_{\text{cutoff}} == 0 \\ \min\!\left(N_{\text{samples}}, \left\lfloor (g_{\text{base}} + r \cdot \alpha_{\text{radial}}) \cdot 2^{\min(12,\, p_{\text{cutoff}} - 1 - p)} \right\rfloor\right) & \text{if } p < p_{\text{cutoff}} \end{cases}$$

### 7.2 Standard Normative Operating Profiles

| Profile Name | `depthK8` ($K$) | `lsbSplitBits` ($s$) | `(p_cutoff, g_base, alpha)` | $M_{\text{refresh}}$ | `FLAG_ZSTD_POOL` | Target Application |
| :--- | :---: | :---: | :---: | :---: | :---: | :--- |
| **`ARCHIVAL_LOSSLESS`** | `16` | `2` (`g1..g0` in 4B) | `(0, 0, 0.00)` | `16` | `1` (Enabled) | Studio archive, desktop/IDE master recording, NLE post-production |
| **`OBS_REALTIME_LOSSLESS`** | `8` | `2` (`g1..g0` in 4B) | `(0, 0, 0.00)` | `8` | `0` (Raw fast) | Real-time `1440p60`/`4K60` OBS capture with `< 1.2 ms` on-disk crop/strip |
| **`WIRELESS_FOVEATED_LOW_LATENCY`** | `2` or `4` | `2` (`g1..g0` in 4B) | `(4, 0, 1.25)` | `2` | `0` (Raw MTU) | FPV drone, robotics teleoperation, wireless monitor (`100%` lossless crosshair ROI) |
| **`HARSH_RF_UEP_ZERO_WAIT`** | `1` | `3` (`g2..g0` in 4B) | `(4, 1, 2.00)` | `1` | `0` (Raw MTU) | Zero-lookahead (`0 ms` buffer) contested RF link with `UEP` Section 4B queue shedding |
