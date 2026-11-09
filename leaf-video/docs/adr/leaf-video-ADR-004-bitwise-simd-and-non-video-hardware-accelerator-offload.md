# leaf-video-ADR-004: Hardware-Agnostic Bitwise SIMD, Non-Video Silicon Hijack (Optical Flow, NPUs, Compute Shaders), and Sub-Frame Strip Streaming

- **Status:** Accepted
- **Date:** 2026-11-06
- **Scope:** `:leaf-video` (`io.github.dailystruggle.leafvideo`) and `resource/LeafVideo/`
- **Extends:** [`leaf-video-ADR-001`](leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md), [`leaf-video-ADR-002`](leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md), [`leaf-video-ADR-003`](leaf-video-ADR-003-spatiotemporal-database-decode-map-and-causal-compaction.md)
- **Normative Binary Specification:** [`LCV-ICD-1.0` (`../ICD.md`)](../ICD.md)

---

## Context

Competing with `H.264`, `HEVC`, and `AV1` as a live capture and low-latency streaming format requires confronting why those codecs rely on dedicated fixed-function silicon (`NVENC`, `AMF`, `QuickSync`, or embedded IP-camera ASICs):

1. **Why Software `x265` Fails for Streaming While `Parsec` Uses Hardware `HEVC`:**
   - CPU software encoders (`x265`, `SVT-AV1`) achieve high compression by buffering `20–40` frames in a lookahead queue (`330–660 ms` latency) and evaluating recursive variable-size coding tree units (`CTUs` up to $64 \times 64$), 8-tap fractional-pel interpolation filters, 2D Cosine Transforms (`DCT`), and bit-serial context-adaptive arithmetic coding (`CABAC`).
   - Low-latency desktop streaming (`Parsec`, `Moonlight`) avoids CPU `x265` entirely and instead drives the GPU's fixed-function `NVENC`/`AMF` silicon block in zero-reorder mode (`0 B-frames`, `1-frame` lookahead, `2–4 ms` encode time) directly from VRAM.
   - However, on a streamer's gaming PC, `NVENC` sessions are already saturated by the live Twitch/YouTube broadcast, locked to lossy `4:2:0` chroma without `Alpha` transparency (`VOD-4`), and incapable of compressed-domain editing.

2. **Why Battery-Powered Drones, Model Planes, and Robotics Struggle with `H.264` / `HEVC`:**
   - Small aerial vehicles and mobile robots (`1–3 W` power envelope) either carry a tiny IP-camera SoC with a rigid `4:2:0` `H.264`/`HEVC` ASIC or an embedded general-purpose processor (`ARM Cortex-A` with `NEON`, Raspberry Pi CM4/Zero, or a small FPGA) that lacks a video encoder ASIC.
   - Even when a hardware `H.264`/`HEVC` ASIC is present onboard, it exhibits two critical failure modes for high-speed teleoperation:
     - **Whole-Frame Scanout Latency (`25–45 ms` glass-to-glass):** Frame-based encoders wait for all `1080` scanlines to roll off the CMOS sensor (`16.6 ms` at `60 fps`) before encoding and packetizing, inducing pilot oscillation during fast flight.
     - **The "Digital FPV Freeze" on RF Multipath:** Because `P-frames` depend recursively on prior frames, dropping a burst of packets behind an obstacle invalidates the decoder's reference buffer and freezes or smears the entire display for `100–500 ms` until a full `I-frame` arrives.

---

## Decision

Establish a **Dual Hardware Execution Architecture** for `.lcv` that (1) executes at real-time line rate on general-purpose CPUs/FPGAs without a video encoder ASIC via pure bitwise SIMD, (2) hijacks **idle non-video hardware accelerators** (`NVOF` Optical Flow, GPU Compute Shaders, Embedded NPUs/Tensor Cores, and Texture Units) when available, and (3) streams sub-frame 16-pixel rolling scanline strips ($K=1$, `< 1 ms` algorithmic latency) with zero-freeze RF fault isolation.

### 1. Hardware-Agnostic Bitwise SIMD ("You Don't Need a Hardware Video Encoder")

Unlike `DCT + CABAC` codecs that are intentionally serial and multiplier-heavy, the core `.lcv` transform (`LcvPrimitives`, `LcvCubeCodec`) shall maintain a **zero-floating-point, multiplication-free inner loop**:
- **Color Decorrelation (`YCoCg-R`):** Exact integer lifting via register additions and bit-shifts (`LcvPrimitives#packYCoCgR`).
- **Bitplane Decomposition (Reflected Binary Gray Code):** Single-cycle bitwise $G = V \oplus (V \ggg 1)$ (`LcvPrimitives#binaryToGray8`) and causal `MED_2D` clamp prediction (`Math.min`, `Math.max`, add/subtract).
- **Run & Residual Coding (`Roaring` + `Hilbert` + `SWAR` Half-Pel Bilinear):** Register-width `XOR`, `SAD` (`vabdq_u8` on `ARM NEON`, `_mm256_sad_epu8` on `AVX2`), branchless 3-instruction SWAR half-pixel bilinear averaging `(a | b) - (((a ^ b) >>> 1) & 0x7F7F7F7F)` (`LcvPrimitives#swarBilinearAvg2`, `DELTA_FLAG_HALF_PEL_MV`), 2-level 2D Haar wavelet sub-band conditioning (`LcvPrimitives#haar2LevelSubbandConditionCube`), `LZCNT`/`TZCNT`, and hardware `POPCNT`.

Because there are no bit-serial arithmetic-coding carry chains across tiles, a `1 W` embedded `ARM Cortex-A` core (`NEON`), a laptop CPU (`AVX2`), or a small FPGA (`< 5,000` LUTs) can encode and decode $16 \times 16$ tiles at real-time line rate on hardware that possesses **no dedicated video encoder silicon**.

---

### 2. Repurposing Idle Non-Video Hardware Accelerators ("The Hardware Hijack")

Modern desktop GPUs and embedded robotics SoCs contain specialized fixed-function accelerators that sit **100% idle** during video capture and gaming. Because every $16 \times 16 \times K$ `.lcv` cube is independent, `.lcv` maps directly onto four non-video silicon blocks:

| Idle Hardware Block | Target Silicon Examples | `.lcv` Stage Offloaded | Operational Advantage |
| :--- | :--- | :--- | :--- |
| **1. Hardware Optical Flow Engine** | Nvidia Optical Flow (`NVOF` on Turing/Ampere/Ada/Blackwell), Intel `FEI`, VAAPI Motion Vectors, MIPI CSI-2 ISP coarse MV output | Per-frame trajectory estimation `(dx_t, dy_t)` and `DecodeMapPacket` (`leaf-video-ADR-003`) block shift search (`LcvCubeCodec`, `LcvOnDiskTransformer`). | Dedicated fixed-function silicon (separate from both `NVENC` and shader cores) computes a full frame of $4 \times 4$ / $16 \times 16$ motion vectors and `SAD` cost maps in **`< 0.5 ms`** at `0%` CPU load and `0%` `NVENC` contention. |
| **2. GPU Compute Shaders (`1 Tile = 1 Workgroup`)** | `Vulkan Compute`, `OpenCL`, `CUDA`, `DirectCompute` | Parallel $16 \times 16 \times K$ `YCoCg-R`, `MED_2D`, Gray-plane extraction, and `UI_PALETTE_ROARING` / `WAVELET_3D_HAAR` encoding. | Maps one $16 \times 16$ tile to one 256-thread GPU workgroup operating entirely in L1 shared memory (`< 0.3 ms` per superblock), keeping frame pixels on-GPU with `0%` CPU overhead and leaving `NVENC` 100% free for live Twitch streaming. |
| **3. Embedded NPUs & Tensor Cores (`INT8` / `1-Bit` Matrix Units)** | Rockchip `RK3588` NPU (`6 TOPS`), `Hailo-8L`, Snapdragon `Hexagon`, Apple Neural Engine, GPU Tensor Cores | Causal dictionary deduplication (`DecodeMapPacket` `sourceBlockId` matching and 2D $(\Delta x, \Delta y)$ cross-correlation). | Expresses $16 \times 16$ tile matching against the causal `sourceBlockId` ring (`leaf-video-ADR-003`) as a batched `INT8` / `1-bit XNOR-POPCNT` 2D convolution, matching thousands of candidate blocks per millisecond at **`< 1 W`**. |
| **4. GPU Texture Mapping Units (`TMUs`)** | Hardware `Gather4`, bilinear/box mipmap generators (`generateMipmap`) | `WAVELET_3D_HAAR` (`4x4 LL` averages) and motion-shifted `(x + dx, y + dy)` reference tile fetches. | Uses fixed-function texture fetch and box-filter silicon to build multi-scale Haar pyramids and shifted reference patches at hundreds of gigapixels per second. |

---

### 3. Sub-Frame Rolling Strip Streaming ($K=1$) & Zero-Freeze RF Multipath Quarantine

For battery-powered drones, RC model aircraft, and wireless remote desktops (`HARSH_RF_UEP` and `WIRELESS_FOVEATED` profiles):

1. **Sub-Frame Rolling Strip Ingestion (`< 1 ms` Algorithmic Latency at $K=1$):**
   - Instead of waiting `16.6 ms` for an entire `1080p` camera frame to finish scanning out, the encoder shall ingest each **16-pixel-tall horizontal strip (`1 row of 16x16 tiles`)** immediately as those 16 scanlines exit the camera CSI-2 / capture ring buffer.
   - Each strip is matched against the `256 KiB` causal `L1` block ring (`leaf-video-ADR-003`), split into `Section 4A` (`MSBs`) and `Section 4B` (`LSBs`), and transmitted over the RF link within `< 1 ms` of photon capture.
2. **Eliminating the "Digital FPV Freeze" Under RF Breakup:**
   - Every transmitted tile (`LcvStreamContainer.MvCoherentPacket`) is self-contained (`CBIT` bounds quarantine) or references an ACK-verified `sourceBlockId` in the causal ring.
   - During RF multipath fade or foliage obstruction, the radio queue drops `Section 4B` (`g2..g0` peripheral LSBs) in `0.1 ms` while prioritizing center-fovea `Section 4A` (`MSBs`) and redundant `DecodeMapPacket` control packets.
   - Unrecoverable packet loss degrades only the affected $16 \times 16$ tile for a single frame (`16.6 ms`)—the center pilot crosshair never freezes, and the next strip recovers instantaneously without waiting for an `I-frame`.

3. **Zero-`Malloc` Embedded Microcontroller (`< 3 KiB` SRAM) & Raspberry Pi Runtime EIS Strip Path (`lcv_native_encode_mcu_strip_k1_rgba8`):**
   - For bare-metal microcontrollers (`RP2040` `133 MHz Cortex-M0+` with `264 KB` SRAM, `RP2350` `150 MHz Cortex-M33` with `520 KB` SRAM, `ESP32-S3` `240 MHz` with `512 KB` SRAM, `STM32H7` `480 MHz Cortex-M7` with `1 MB` SRAM) and single-board computers (`Raspberry Pi Zero 2 W` `1.0 GHz Cortex-A53`, `Raspberry Pi 4 / CM4` `1.5 GHz Cortex-A72`), `lcv_native_encode_mcu_strip_k1_rgba8` executes entirely inside a caller-allocated **`3,040-byte` (`< 3 KiB`)** `lcv_mcu_strip_scratch_t` workspace with **zero heap allocations (`0` `malloc`/`free`)** and **zero floating-point instructions**.
   - An optional runtime per-block `uint8_t tileQualityMap[tilesX]` driven by the onboard IMU/gyro Electronic Image Stabilization (EIS) loop controls each $16 \times 16$ block independently on the fly:
     - `0xFF`: Dead EIS sensor overscan border $\to$ `0-byte` `UNIFORM_INLINE` / `TEMPORAL_CLEAN_ACK` pointer (`~16 cycles/tile`).
     - `0x00`: Stabilized focal horizon / target window $\to$ `100%` mathematically lossless `Section 4A + Section 4B` (`inf dB`).
     - `bit 7 | (lossyLsb << 4) | dMerge`: High-retinal-slip periphery $\to$ strips `Section 4B` (`lsbSliceByteLen16 = 0`), pre-masks `lossyLsb` bits, and widens Hilbert run-concatenation deadband `dMerge` while remaining 100% bit-exact compatible with the standard `LCV-ICD-1.0` decoder.

---

### 4. Direct `GPU -> Wireless Adapter -> Headset -> Display` Generalized Capsule / Barrel Multi-Fovea & Motion-Salience Pipeline (`Steam Frame` / `OpenXR`)

For high-refresh stereo VR headsets (`4320x2160` combined dual `2160x2160` displays at `90–144 Hz` over a dedicated `6 GHz` Wi-Fi 6E / Wi-Fi 7 adapter), routing frames through CPU system RAM or a JVM introduces unacceptable PCIe copy latency and memory-bandwidth pressure. Furthermore, human visual acuity in VR is neither static nor strictly circular: a fovea can be a **point circle**, a **velocity-swept line/capsule** ($\mathbf{A} \to \mathbf{A} + \vec{v}_{\text{gaze}}\Delta t$ during saccades or smooth pursuit), a **position-to-position sightline corridor or dual-lobe multi-fovea** ($\mathbf{A} = \text{Eye Gaze}, \mathbf{B} = \text{Reticle / Hand Controller / HUD}$), or an **anisotropic barrel / horizon band** ($r_x > r_y$ with quartic barrel curvature $\kappa$). The `.lcv` VR streaming pipeline shall execute end-to-end across four zero-CPU-staging hardware stages to stay within `1/2` to `2/3` (`~170–355 Mbps`) of the wireless adapter's sustainable UDP goodput ceiling:

1. **Stage 1 — Host GPU VRAM-Resident Compute Encode (`lcv_cube_kernel.comp` & `lcv_gpu_encode_tiles`, `< 0.3 ms`):**
   - Left and right eye `VkImage` swapchain textures remain in GPU VRAM. The headset's primary anchor $\mathbf{A} = (\text{roi1TileX8}, \text{roi1TileY8})$, secondary/swept anchor $\mathbf{B} = (\text{roi2TileX8}, \text{roi2TileY8})$, core lossless keep radius $R_{\text{fovea}}$, circular lens stencil radius $R_{\text{lens}}$, shape mode (`CIRCLE`, `CAPSULE_LINE`, `DUAL_LOBE`, `BARREL_HORIZON`, `CAPSULE_BARREL`), horizontal anisotropy ratio $\text{anisoRxQ2} \in [4..60]$, barrel curvature $\kappa \in [0..15]$, motion-salience enable bit, radial $d_{\text{merge}}$ bit, and side-by-side stereo flag are bound directly as a **64-bit Packed GPU Kernel Parameter (`foveaDescriptor64`) / Push Constant**.
   - **Generalized Capsule / Line / Barrel Signed Distance Field (`SDF` in GPU Registers, `REQ-LCV-VR-001`, `REQ-LCV-VR-002`):**
     - Each `256`-thread workgroup (`1 tile = 1 workgroup`) maps its tile coordinate $(tx, ty)$ into local eye coordinates $\mathbf{p} = (p_x, p_y)$ (taking $\min(d_{\text{left}}^2, d_{\text{right}}^2)$ across `eyeTilesX = tilesX / 2` when `sideBySideStereo == 1`) and evaluates the geometric displacement $(\Delta x, \Delta y)$ in integer registers:
       - **Capsule / Swept Line Mode (`CAPSULE_LINE` / `CAPSULE_BARREL`):** Given segment $\mathbf{u} = \mathbf{B} - \mathbf{A}$, fixed-point projection $t_{\text{Q8}} = \text{clamp}(\lfloor 256 \cdot ((\mathbf{p} - \mathbf{A}) \cdot \mathbf{u}) / \max(1, \|\mathbf{u}\|^2) \rfloor, 0, 256)$ locates the closest point $\mathbf{c} = \mathbf{A} + (t_{\text{Q8}} / 256)\mathbf{u}$ on segment $\overline{\mathbf{A}\mathbf{B}}$, yielding $\Delta x = p_x - c_x, \Delta y = p_y - c_y$.
       - **Dual-Lobe Multi-Fovea Mode (`DUAL_LOBE`):** Evaluates both anchor lobes independently and takes $d_{\text{geom}}^2 = \min(d_{\text{aniso}}^2(\mathbf{p} - \mathbf{A}),\, d_{\text{aniso}}^2(\mathbf{p} - \mathbf{B}))$.
       - **Anisotropic Barrel / Horizon Metric (`BARREL_HORIZON` / `CAPSULE_BARREL`):**
         $$d_{\text{geom}}^2(\Delta x, \Delta y) = \left\lfloor \frac{4 \cdot \Delta x^2}{\max(1, \text{anisoRxQ2})} \right\rfloor + \Delta y^2 + \left\lfloor \frac{\kappa \cdot \Delta x^2 \cdot \Delta y^2}{256} \right\rfloor$$
   - **Per-Tile Motion-Vector Salience vs. Retinal-Slip Modulation (`REQ-LCV-VR-003`):**
     - Using the tile's in-VRAM `[-7, +7]^2` shift motion vector $\vec{v}_{\text{tile}} = (\Delta x_{\text{tile}}, \Delta y_{\text{tile}})$ (`enc_s_candShift` / `groupMv8`) and focal pursuit vector $\vec{v}_{\text{gaze}} = \text{clamp}(\mathbf{B} - \mathbf{A}, -7, +7)$:
       - **Target Pursuit / Motion Salience Boost:** When $|\vec{v}_{\text{tile}}| \ge 2$ and $\|\vec{v}_{\text{tile}} - \vec{v}_{\text{gaze}}\|_1 \le 2$, subtract a salience bonus $\Delta R_{\text{salience}}^2$ from $d_{\text{geom}}^2$ so tracked or salient moving foreground objects retain `100%` lossless `Section 4B` LSBs even near/just outside the nominal boundary.
       - **Retinal-Slip Peripheral Shedding:** When $d_{\text{geom}}^2 > R_{\text{fovea}}^2$ and retinal slip $S = \|\vec{v}_{\text{tile}} - \vec{v}_{\text{gaze}}\|_1 > 2$, add a positive slip penalty to $d_{\text{eff}}^2$ and scale the tile's radial $d_{\text{merge,tile}}$ deadband proportionally to $(d_{\text{eff}}^2 - R_{\text{fovea}}^2)(1 + S) / \max(1, R_{\text{fovea}}^2)$, while keeping $d_{\text{merge,tile}} = 0$ strictly inside $d_{\text{eff}}^2 \le R_{\text{fovea}}^2$.
     - The resulting effective distance $d_{\text{eff}}^2$ drives four compression zones without touching the CPU:
       - **Zone 0 — Circular VR Lens Stencil Corners ($d_{\text{eye}}^2 > R_{\text{lens}}^2$):** Short-circuits in `< 10` shader cycles to a `0-byte` `UNIFORM_INLINE` pointer, which `lcv_gpu_assemble_superblock` coalesces into $64 \times 64$ CTU quadtree leaves (`COLOR_FLAG_QUADTREE_DIR = 0x80`).
       - **Zone 1 — Foveal Core / Swept Corridor ($0 \le d_{\text{eff}}^2 \le R_{\text{fovea}}^2$):** Retains both `Section 4A` (`MSBs`) and `Section 4B` (`LSBs`) with $d_{\text{merge}} = 0$ (`100%` mathematically lossless `4:4:4:4` clarity along the gaze point, swept saccade/pursuit line, dual Gaze+Reticle anchors, or horizon barrel).
       - **Zone 2 — Parafovea ($R_{\text{fovea}}^2 < d_{\text{eff}}^2 \le R_{\text{outer}}^2$):** Emits `Section 4A` (`MSBs` `g7..g_s`) with radial/retinal-slip $d_{\text{merge}}(p, d_{\text{eff}}, S)$ and skips `Section 4B` global memory writes entirely on the GPU (`tileLsbLen = 0` $\to$ `lsbSliceByteLen16 = 0` in `ptr64`).
       - **Zone 3/4 — Mid/Far Periphery ($d_{\text{eff}}^2 > R_{\text{outer}}^2$):** Applies a radial + retinal-slip LSB deadband $\epsilon(d_{\text{eff}}, S)$ during `TEMPORAL_CLEAN_ACK` matching so high-slip peripheral background tiles collapse to `0-byte` pointers, and folds low-variance rod-dominated `Co`/`Cg` chroma into `1-byte` `constVal8` headers (`channelMask4`).
2. **Stage 2 — Sub-Frame GPU $\to$ `6 GHz` Wireless Adapter (`Pinned BAR1 Ring` + `LCVU` UDP `MSG_ZEROCOPY`, `< 0.2 ms`):**
   - Because `.lcv` tiles have zero cross-tile `CABAC` dependencies, `lcv_native_packetize_superblock_lcvu_multifovea` (and backward-compatible `lcv_native_packetize_superblock_lcvu_foveated`) packetizes completed tile strips into MTU-bounded `LCVU` UDP datagrams directly from pinned host-visible memory (`MSG_ZEROCOPY` / `io_uring`), evaluating the identical `foveaDescriptor64` SDF + tile `candMv8` salience in `0 ms` without re-encoding `Section 4A`.
3. **Stage 3 & 4 — Headset Unified-Memory Decode $\to$ Late Timewarp $\to$ Display Scanout (`< 0.5 ms`):**
   - On the headset's ARM/SteamOS SoC (unified LPDDR5X memory), incoming `LCVU` UDP datagrams land directly in a mapped `VkBuffer`. Because each tile's `ptr64` explicitly records `lsbSliceByteLen16`, any standard `LCV_V001` decoder reconstructs circles, swept lines, dual lobes, and barrel bands **with zero changes to the wire grammar**: `Section 4A + 4B` decodes bit-exact where `lsbSliceByteLen16 > 0`, and `pocsRestoreStrippedChannelSample` restores smooth gradients where `lsbSliceByteLen16 == 0`.

---

### 5. Canonical Native C11 / GPU Shader Source of Truth

To eliminate cross-language maintenance drift, JNI array-copy overhead, and subtle translation bugs:
1. **Canonical Implementation (`resource/LeafVideo/native/` + `leaf-video/docs/ICD.md`):**
   - `resource/LeafVideo/native/lcv_accel.c` (C11, `AVX2`/`NEON` SIMD, and inline CUDA Driver API PTX kernels `lcv_batch_analyze_superblock`, `lcv_gpu_encode_tiles`, `lcv_gpu_assemble_superblock`), `resource/LeafVideo/native/lcv_cube_kernel.comp` (`Vulkan` `GLSL 4.50` compute shader), `resource/LeafVideo/native/lcv_codec_system_plugins.c`, `resource/LeafVideo/native/obs_leafvideo_plugin.c` (`libobs v30` native plugin), and `resource/LeafVideo/native/lcv_cli_and_test.c` are the **sole canonical implementation** of `LCV-ICD-1.0`.
   - All codec, compressed-domain transform (`lcv_native_crop_or_strip_superblock`, `lcv_native_compact_superblock_zstd`), `10-bit` & `12-bit` `4:4:4:4` `RGBA64LE` studio mezzanine (`bitDepth8 ∈ {8, 10, 12}`), single-file `Section 4A`-only `8-bit` proxy scrubbing (`lcv_native_decode_superblock_rgba8_preview`), GPU-direct compute, and `LCVU` UDP streaming capabilities are implemented and verified directly in `leaf-video/docs/ICD.md` and `resource/LeafVideo/native/`.

---

## Requirements Traceability

| Requirement ID | Statement | Target Verification |
| :--- | :--- | :--- |
| `REQ-LCV-HW-001` | Core cube decorrelation, Gray-code bitplane decomposition, and `MED_2D` residual coding shall execute using integer and bitwise operations with zero floating-point instructions so the pipeline maps deterministically to `AVX2`, `ARM NEON`, FPGA, and GPU compute targets. | `lcv_accel.c`, `lcv_cube_kernel.comp`, `LcvPrimitives` |
| `REQ-LCV-HW-002` | Motion trajectory and `DecodeMapPacket` encoders shall accept externally supplied per-tile $(\Delta x, \Delta y)$ motion vectors (such as hardware optical flow `NVOF` / ISP outputs) to bypass software block-matching search when hardware vectors are present. | `lcv_accel.c`, `LcvCubeCodec`, `LcvOnDiskTransformer` |
| `REQ-LCV-HW-003` | In $K=1$ (`HARSH_RF_UEP`) streaming mode, each 16-pixel horizontal tile row shall be independently encodable, transmittable, and decodable upon scanline completion without waiting for full-frame scanout or subsequent `GoP` reference frames. | `lcv_accel.c`, `LcvStreamContainer`, `LcvRecoveryTortureTest` |
| `REQ-LCV-HW-004` | In GPU-direct foveated VR streaming mode (`VR_STEREO_FOVEATED_DIRECT`), GPU compute encoders and `LCVU` packetizers shall evaluate concentric radial distance $r$ (including side-by-side stereo $\min(r_{\text{left}}, r_{\text{right}})$) in-register, retaining `Section 4B` LSB slices within $r \le R_{\text{fovea}}$ while setting `lsbSliceByteLen16 = 0` for peripheral tiles ($r > R_{\text{fovea}}$) without CPU staging or re-encoding. | `lcv_cube_kernel.comp` & `lcv_accel.c` |
| `REQ-LCV-VR-001` | The GPU encoder (`lcv_gpu_encode_tiles`), Vulkan shader (`lcv_cube_kernel.comp`), and `LCVU` UDP packetizer shall evaluate effective tile distance $d_{\text{eff}}^2(tx, ty)$ against a generalized primitive parameterized by primary anchor $\mathbf{A}$, secondary/swept anchor $\mathbf{B}$, shape mode (`CIRCLE`, `CAPSULE_LINE`, `DUAL_LOBE`, `BARREL_HORIZON`, `CAPSULE_BARREL`), anisotropy ratio, barrel curvature, and stereo mirroring (`sideBySideStereo`). | `lcv_accel.c`, `lcv_cube_kernel.comp`, `LcvExternalPlayerAndEditorProofTest` |
| `REQ-LCV-VR-002` | When given a velocity vector $\vec{v} = (\Delta v_x, \Delta v_y)$ or secondary position $\mathbf{B}$ (such as VR hand controller, aim reticle, or HUD anchor), `100%` mathematically lossless `Section 4A + 4B` (`lossy_lsb_bits = 0, d_merge = 0`) shall be preserved along the swept line segment $\overline{\mathbf{A}\mathbf{B}}$ (or around both lobes $\mathbf{A}$ and $\mathbf{B}$) within radius $R_{\text{fovea}}$. | `lcv_accel.c`, `LcvExternalPlayerAndEditorProofTest` |
| `REQ-LCV-VR-003` | Inside `lcv_gpu_encode_tiles` and `LCVU` UDP packetization, each tile's shift motion vector $\vec{v}_{\text{tile}} = (\Delta x, \Delta y)$ shall modulate $d_{\text{eff}}^2$ by boosting acuity on co-moving/salient foreground targets and scaling peripheral `Section 4B` shedding and `d_merge(r)` deadbands on high retinal-slip background tiles. | `lcv_accel.c`, `LcvExternalPlayerAndEditorProofTest` |

---

## Consequences

- **Positive:**
  - Gives desktop streamers and VR headsets (`Steam Frame` / `OpenXR`) a direct `GPU -> Wireless Adapter -> Headset -> Display` pipeline at `< 1 ms` algorithmic latency while staying within `1/2` to `2/3` of `6 GHz` adapter bandwidth limits.
  - Gives embedded drones, model planes, and robotics sub-millisecond strip latency ($K=1$), zero-freeze RF packet-loss resilience, and sub-watt execution via `ARM NEON` SIMD or idle NPU matrix engines.
  - Eliminates double-implementation drift ("lost in translation") by making `resource/LeafVideo/native/` (`C11` + `CUDA PTX` + `Vulkan GLSL`) the sole canonical codebase for new codec and streaming work.
- **Trade-offs & Mitigations:**
  - *Single Native Codebase:* All codec, compressed-domain transform, GPU-direct, foveated VR, and `LCVU` streaming capabilities live exclusively in `resource/LeafVideo/native/` and `leaf-video/docs/ICD.md`, verified by `compileAndRunNativeGate1` (`./gradlew :leaf-video:test`).
