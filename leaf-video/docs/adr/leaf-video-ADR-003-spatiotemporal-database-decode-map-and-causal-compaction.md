# leaf-video-ADR-003: Content-Addressed Spatiotemporal Database (`(layer, x, y, t) -> blockId`), `DecodeMapPacket` Indirection, and Causal Live Compaction

- **Status:** Accepted
- **Date:** 2026-11-06
- **Scope:** `:leaf-video` (`io.github.dailystruggle.leafvideo`) and `resource/LeafVideo/`
- **Extends:** [`leaf-video-ADR-001`](leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md), [`leaf-video-ADR-002`](leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md)
- **Related ADRs:** [`leaf-video-ADR-004`](leaf-video-ADR-004-bitwise-simd-and-non-video-hardware-accelerator-offload.md), [`anvil-api-ADR-003`](../../../api/anvil-api/docs/adr/anvil-api-ADR-003-hilbert-roaring-gray-chunk-archive.md), [`leafanvil-ADR-002`](../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-002-in-memory-lca-chunk-representation-and-dirty-overlay-compaction.md), [`leafanvil-ADR-003`](../../../resource/LeafAnvil/docs/adr/leafanvil-ADR-003-negotiated-lca-chunk-network-streaming-and-delta-sync.md)
- **Normative Binary Specification:** [`LCV-ICD-1.0` (`../ICD.md`)](../ICD.md)

---

## Context

In [`leaf-video-ADR-001`](leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md), `LcvStreamContainer` and `LcvOnDiskTransformer` established independent $16 \times 16 \times K$ spatiotemporal cubes, compressed-domain spatial cropping (`cropSubRegion` in `0.238 ms`), $O(1)$ LSB bitplane stripping (`stripLsbPlanes`), and non-temporal `Superblock` append splicing (`spliceEditedSuperblockNonTemporal` in `0.189 ms`).

However, evaluating `.lcv` as a practical primary recording and editing format for **multi-hour streamer VODs** (`4 hours @ 1080p60` = `7.17 TB` raw uncompressed `RGBA`) across the real uncompressed Xiph/Derf corpus exposes two structural constraints in a pure 1:1 grid-indexed container:

### 1. The Multi-Hour VOD Disk Budget Constraint

| Content Class (`4 Hours @ 1080p60`, `7.17 TB` Raw `RGBA`) | OBS `h264_nvenc` (`CQP-18 4:2:0`) | `.lcv` Live (`OBS_REALTIME`) | `.lcv` Post-Processed Compact / Foveated | Operational Bottleneck |
| :--- | ---: | ---: | ---: | :--- |
| **Desktop / IDE / VTuber Overlay (`VOD-1`, `VOD-4`)** | `~24 GB` (drops alpha on `VOD-4`) | `~97 GB` (`74x`) | **`~31–40 GB` (`180x–230x`)** | Fits comfortably on NVMe while preserving `inf dB` / `50.5 dB` `RGBA` alpha. |
| **Voxel Gameplay — 1:1 Native (`VOD-2A` `MINECRAFT`)** | `~138 GB` (`52x`) | `~2,240 GB` (`3.2x`) | **`~211–330 GB` (`21.7x–33.9x`)** | Waiting until stream end to post-process requires `2.2 TB` of temporary disk staging. |
| **Scaled / High-Motion Foliage (`VOD-2B`, `VOD-3` `park_joy`)** | `~180–265 GB` (`27x–40x`) | `~2,560–3,580 GB` (`2.0x–2.8x`) | **`~645–1,490 GB` (`4.8x–11.1x`)** | Grid-boundary texture crossings during camera pans force redundant re-encoding. |

### 2. Why Fixed Grid Coupling Fights Natural Camera Motion

When every `Superblock` hardwires a 1:1 coupling between **where a tile is stored on disk** (`pointerDir[tileIdx]`) and **where/when it appears on screen** (`(tileX, tileY)` for frames $t_0 \dots t_0 + K - 1$):
1. **Cross-Tile Camera Pans (`VOD-2A/B` `MINECRAFT`, `VOD-3` `park_joy`):** When the camera pans by `(dx, dy)` pixels, a $16 \times 16$ texture patch that **already exists in decoder memory** at `(x - dx, y - dy)` crosses a $16 \times 16$ grid line and must be re-encoded from scratch in the adjacent tile's bitstream.
2. **Stationary Backgrounds & Returning UI States (`VOD-5` `KristenAndSara`, `MINECRAFT` Inventory/HUD):** Stationary office backgrounds (`75%` of `VOD-5`) and recurring UI panels re-anchor every $M_{\text{refresh}}$ superblocks instead of pinning a single immutable block reference across arbitrary time horizons.
3. **Flattened OBS Compositing:** Recording gameplay, webcam/VTuber rigs, and chat/alert overlays as a single flattened `RGBA` canvas permanently destroys the gameplay pixels behind the webcam and forces streamers using OBS `Source Record` to run 2–3x concurrent encoders.

---

## Decision

Architect `.lcv` as a **4D Content-Addressed Spatiotemporal Database (`(layer, x, y, t) -> blockId`)** by decoupling the **Data Plane** (immutable, self-contained $16 \times 16 \times K$ content blocks stored in any order on disk) from the **Control / Indirection Plane** (`DecodeMapPacket`, magic `LCV_MAP1`), paired with **Causal Dictionary Deduplication** and **Rolling Live Compaction**.

### 1. Separation of Data Plane and `DecodeMapPacket` (`LCV_MAP1`) Indirection Plane

1. **Independent Content Blocks (Data Plane):**
   - Every encoded $16 \times 16 \times K$ block (`LcvCubeCodec`) in the data pool shall remain 100% self-contained and independently decodable in any order, with zero recursive `P-frame` drift chains.
   - Each content block is assigned a compact `sourceBlockId` and indexed by a 64-bit structural/perceptual signature (`4x4` Haar `LL` average + directional edge orientation bits + `mode2` tag) computed in registers during the `YCoCg-R` / MSB pass.
2. **In-Memory Decode Mapping Packet (`DecodeMapPacket` — Control Plane):**
   - Instead of requiring a 1:1 per-frame disk pointer inside every fixed grid cell, `DecodeMapPacket` (`LCV_MAP1`) maps logical screen coordinates `(layer, tileX, tileY, t)` to:
     $$\text{MapEntry}(layer, tileX, tileY, t) \mapsto (\text{sourceBlockId}, \Delta x, \Delta y, \text{optionalResidualBlockId})$$
     alongside the block directory table mapping `sourceBlockId -> (diskByteOffset, byteLen, secDed8)`.
   - **Camera Pan Indirection:** When the viewport pans by $(\Delta x, \Delta y)$ across terrain or foliage (`VOD-2`, `VOD-3`), screen cells reference existing `sourceBlockId`s already resident in the decoder's in-memory block cache shifted by $(\Delta x, \Delta y)$, allocating new content or sparse residual blocks only for newly uncovered viewport borders or lighting changes.
   - **Flattened Star Dependency (`Anchor -> Frame t` via `LcvStreamContainer#extractSingleFrame`) vs. `H.264` Sequential `P-Frame` Chains:** In `H.264`/`HEVC`, Frame $t$ depends recursively on Frame $t-1 \to t-2 \to \dots \to 0$, forcing any random-access frame extraction to decode all $t$ preceding frames. By shoving the temporal dependency (`anchorSuperblock`, prefix-summed cumulative shift $(\sum \Delta x, \sum \Delta y)$, and anchor slice offset) into the `SEC-DED`-protected pointer directory and `DecodeMapPacket`, `LcvStreamContainer#extractSingleFrame(streamBytes, targetFrameIndex)` walks the pointer directory ($8\text{ B}$ per hop, zero intermediate pixel decoding) straight to each tile's anchor block and reconstructs any single frame $t$ from any part of the video in $O(1)$ payload work.
   - **Stationary & Recurring UI Pinning + Bottom-Up Quadtree Coalescing (`quadLevel`):** Stationary background regions (`VOD-5`) and recurring UI/HUD states (`VOD-1`, `VOD-2` inventory/hotbar) remain pinned to their `sourceBlockId` across arbitrary frame spans at `4 bytes` per mapping entry (`0` new payload bytes). Furthermore, aligned $2 \times 2$ (`32x32`, `quadLevel = 1`) and $4 \times 4$ (`64x64` CTU, `quadLevel = 2`) tile groups that share the same `(sourceBlockId, shiftDx, shiftDy)` coalesce bottom-up into a single `DecodeMapEntry` (reducing map metadata by up to `16x` at 1440p/4K while keeping `LcvCubeCodec` strictly $16 \times 16$).
3. **Crash Resilience & Wireless Resend Redundancy:**
   - Every `DecodeMapPacket` header and directory entry shall be protected by Extended Hamming `(72, 64)` `SEC-DED` (`LcvPrimitives#encodeSecDed72`).
   - Because a `DecodeMapPacket` represents `< 0.5%` of stream bytes, writers shall support duplicating `DecodeMapPacket` checkpoints at segment heads and tails (for 4 KiB disk sector fault recovery) and transmitting redundant `DecodeMapPacket` control frames over contested wireless links (`HARSH_RF_UEP`).

---

### 2. Causal Dictionary Deduplication & Rolling Live Compaction (Look *Back*, Not *Forward*)

To prevent multi-hour VODs from staging multi-terabyte raw intermediates on disk while preserving zero-lookahead ($K=1$) capability for embedded transmitters:

1. **Shared Causal Block Ring (`L1` / `L2` Dictionary):**
   - Compaction shall index **past** emitted blocks rather than buffering future lookahead frames:
     - **Embedded / Drone Tier (`L1` Causal Ring, $K=1$):** A fixed `1,024`-tile ring (`256 KiB` in L2 SRAM) matches incoming 16-pixel camera scanline strips against recently transmitted `sourceBlockId`s with `0 ms` future lookahead. Over lossy RF links, causal references shall only target `sourceBlockId`s confirmed by a duplicated control checkpoint or receiver `ACK`.
     - **Desktop Streamer VOD Tier (`L2` Rolling Dictionary + Live Section Conditioning):** A sliding `1–2` Superblock ring (`250–500 ms` RAM) paired with a `64–256 MiB` causal block dictionary executes `LcvOnDiskTransformer#conditionFramesBySection`, `coalesceRunsByDistance`, and `wrapZstdPools` continuously in memory *before* flushing to NVMe, ensuring disk writes occur directly at the compacted ratio (`20x–230x`).

---

### 3. Four Spatiotemporal Database Capabilities Unachievable in Linear Video Formats

Treating `.lcv` as a `(layer, x, y, t) -> blockId` database unlocks four workflow capabilities that flat `GoP` streams (`H.264`, `HEVC`, `AV1`, `ProRes`) cannot perform:

| Capability | Traditional Linear Video (`MP4` / `H.264` / `ProRes`) | `.lcv` Spatiotemporal Database (`DecodeMapPacket` + Block Pool) |
| :--- | :--- | :--- |
| **1. Multi-Layer Unflattened Recording (`(layer, x, y, t)`)** | OBS flattens Gameplay + Webcam + Chat into one frame (destroying background pixels) or runs 3 separate `Source Record` encoders (`2x–3x` disk & GPU load). | Stores `Layer 0` (Gameplay), `Layer 1` (Webcam/VTuber `RGBA`), and `Layer 2` (HUD/Chat `RGBA`) as independent planes in one `.lcv` file. Because `Alpha = 0` (`UNIFORM_INLINE`) costs `0` payload bytes, transparent overlay regions add near-zero size (`~5–10%` total overhead), and editors can move, resize, or hide overlays in post while revealing intact gameplay behind them. |
| **2. Semantic Spatiotemporal Indexing ("SQL for Video")** | Finding every inventory open, death banner, or action spike in a 6-hour VOD requires decoding `1.29M` frames (`15.5 TB` raw pixels) through CV/OCR. | Queries the `DecodeMapPacket` index (`mode2` tag, 64-bit structural/palette signature, radial $(\Delta x, \Delta y)$ velocity, and `Section 4A` byte density) to locate matching UI states or motion highlights across a 4-hour VOD in `< 5 ms` with zero pixel decoding. |
| **3. Branching Non-Destructive NLE Timelines ("`git` for Video")** | Exporting a highlight cut, censored cut, and `9:16` vertical short requires full generational decode + re-encode into separate multi-GB `.mp4` files. | An edit is a lightweight named `DecodeMapPacket` branch over the immutable block pool: cuts, loops, speed-ramps, and tracked `9:16` pans `(viewX(t), viewY(t))` write only new pointer maps (`0` content bytes), while localized paint/blur edits append only touched $16 \times 16$ blocks at `EOF` (`spliceEditedSuperblockNonTemporal` in `< 0.2 ms`). |
| **4. Content-Addressed Network Sync ("`rsync` for Video & Drones")** | Sending a 100 GB VOD to a remote editor requires uploading the entire file before editing; drones re-transmit identical terrain on repeated passes. | Streams `DecodeMapPacket` + `Section 4A` (`MSBs`, `~10–15%` size) first for instant remote proxy editing, lazily fetches `Section 4B` (`LSBs`) only for surviving timeline cuts/crops, and transmits `4-byte` `sourceBlockId` references when a drone revisits terrain or an editor returns a timeline diff. |

---

### 4. Wait-Free Growing-File Tail Reading & Sidecar Branching (Editing While Streaming)

To support live broadcast turnaround ("editing while streaming") without file-lock contention, unfinalized container tables (`MP4` `moov`), or mid-game GPU/CPU re-encode spikes:

1. **Append-Only Live Flush Granularity (`266 ms` at $K=16, 60\text{ fps}$):**
   - Live writers (`OBS_REALTIME` with rolling causal compaction) shall append self-contained batches of immutable `sourceBlockId` content blocks followed by a `SEC-DED`-protected `DecodeMapPacket` checkpoint at each $K$-frame boundary without seeking backward to mutate prior headers.
   - Concurrent NLE readers and clipping tools shall be able to open the growing `.lcv` stream in read-only tail mode (`O_RDONLY`) to scrub, query semantic indexes, and extract slices up to the latest committed `DecodeMapPacket` checkpoint (`266 ms` behind live) with zero writer lock contention.
2. **Zero-GPU Compressed-Domain Mid-Stream Turnaround:**
   - Extracting a live highlight clip, cropping a `9:16` vertical Short (`LcvOnDiskTransformer#cropSubRegion`), or recomposing unflattened `(layer, x, y, t)` planes while the broadcast is still active shall operate purely by slicing referenced `sourceBlockId` byte ranges and emitting a new `DecodeMapPacket` header (`< 5 ms`, `0%` `NVENC` load).
3. **Non-Destructive Sidecar Branches (`.lcv.branch`) & Live Remote Editor Tail-Streaming:**
   - While the live capture process owns the append cursor (`EOF`) of the growing `.lcv` container, NLE timeline cuts, speed-ramps, tracked `9:16` viewport pans `(viewX(t), viewY(t))`, and newly painted/title $16 \times 16$ blocks shall commit to a lightweight `.lcv.branch` sidecar file referencing the immutable `sourceBlockId` entries in the active `.lcv` recording (optionally merged into the primary container tail via `LcvOnDiskTransformer#spliceEditedSuperblockNonTemporal` after broadcast completion).
   - Remote editors connected during a live broadcast shall be able to tail-stream committed `DecodeMapPacket` checkpoints + `Section 4A` (`MSBs`) in real time to assemble highlight timelines during the stream and lazily fetch `Section 4B` (`LSBs`) only for surviving cuts.

---

## Requirements Traceability

| Requirement ID | Statement | Target Verification |
| :--- | :--- | :--- |
| `REQ-LCV-DB-001` | The container shall support a `DecodeMapPacket` (`LCV_MAP1`) indirection plane that maps `(layer, tileX, tileY, t)` coordinates to immutable `sourceBlockId` entries with sub-tile motion offsets $(\Delta x, \Delta y)$ and optional residual block IDs without requiring temporal ordering of content blocks on disk. | `LcvStreamContainer` & `LcvStreamAndTransformTest` |
| `REQ-LCV-DB-002` | Live capture pipelines shall support causal in-memory section conditioning and block deduplication prior to disk flush so multi-hour VOD recordings write directly at post-processed compact density without multi-terabyte raw staging. | `LcvOnDiskTransformer` & `LcvBenchmarkCli` |
| `REQ-LCV-DB-003` | Multi-layer `(layer, x, y, t)` streams shall represent transparent `Alpha == 0` overlay tiles via zero-body `UNIFORM_INLINE` / `DecodeMapPacket` references, preserving unflattened background layers for post-production recomposition. | `LcvCubeCodec` & `LcvOnDiskTransformer` |
| `REQ-LCV-DB-004` | Growing `.lcv` streams shall flush append-only `DecodeMapPacket` checkpoints at each superblock boundary so concurrent read-only NLE processes and `.lcv.branch` sidecars can scrub, query, crop, and branch timelines during active recording without writer lock contention or pixel re-encoding. | `LcvStreamContainer` & `LcvOnDiskTransformer` |

---

## Consequences

- **Positive:**
  - Directly addresses the multi-hour gameplay VOD disk size bottleneck by combining rolling in-memory section compaction with cross-tile $(\Delta x, \Delta y)$ `sourceBlockId` reuse.
  - Elevates `.lcv` from an intermediate codec into a multi-layer, searchable, branchable spatiotemporal database with workflow capabilities that fixed-function `H.264`/`HEVC` hardware encoders cannot replicate.
  - Preserves 100% independent block decodability and `SEC-DED` / `CBIT` fault isolation (`leaf-video-ADR-001`).
- **Trade-offs & Mitigations:**
  - *In-memory cache footprint:* Maintaining the causal block ring requires `256 KiB` on embedded `L1` targets and `64–256 MiB` on desktop `L2` targets, bounded by deterministic ring eviction (`sourceBlockId modulo ringCapacity`).
  - *Packet-loss reference safety on RF links:* Wireless transmitters in `HARSH_RF_UEP` mode must restrict `DecodeMapPacket` references to `sourceBlockId`s carried in duplicated control checkpoints or explicitly acknowledged by the receiver.
