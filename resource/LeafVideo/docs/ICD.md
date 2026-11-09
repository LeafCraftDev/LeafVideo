# `.lcv` / `V_LCV1` (LeafCraft Video) Binary Format Interface Control Document (ICD) — Router

> **Single Source of Truth:** The canonical, normative `.lcv` / Matroska `V_LCV1` Binary Format Interface Control Document (`LCV-ICD-1.0`, **MIT License**) is maintained exclusively in **[`leaf-video/docs/ICD.md`](../../../leaf-video/docs/ICD.md)**.
>
> Do not duplicate byte tables, bitfield definitions, or mathematical formulas in this file. During builds, `:leaf-video` packages [`leaf-video/docs/ICD.md`](../../../leaf-video/docs/ICD.md) and [`leaf-video/LICENSE`](../../../leaf-video/LICENSE) directly into `leaf-video-<version>.jar` under `META-INF/leaf-video/`.

---

## Canonical Specification & Section Index ([`leaf-video/docs/ICD.md`](../../../leaf-video/docs/ICD.md))

| Section | Topic | Canonical Link |
| :--- | :--- | :--- |
| **Section 1** | Scope, Design Invariants & Open Format Interoperability (`MIT`) | [`leaf-video/docs/ICD.md#1-scope--design-invariants`](../../../leaf-video/docs/ICD.md#1-scope--design-invariants) |
| **Section 2** | File & Stream Layout Overview (`Superblock`) | [`leaf-video/docs/ICD.md#2-file--stream-layout-overview-superblock`](../../../leaf-video/docs/ICD.md#2-file--stream-layout-overview-superblock) |
| **Section 3** | Section 1 — `64-Byte` Superblock Header (`0x0000..0x003F`) | [`leaf-video/docs/ICD.md#3-section-1--64-byte-superblock-header-0x00000x003f`](../../../leaf-video/docs/ICD.md#3-section-1--64-byte-superblock-header-0x00000x003f) |
| **Section 4** | Section 2 & 3 — 2D/Quadtree Tile Pointer Directory, `lsbTileOffsets[N_tiles]` & `SEC-DED (72, 64)` Syndrome Table | [`leaf-video/docs/ICD.md#4-section-2--3--2d-tile-pointer-directory--sec-ded-72-64-syndrome-table`](../../../leaf-video/docs/ICD.md#4-section-2--3--2d-tile-pointer-directory--sec-ded-72-64-syndrome-table) |
| **Section 5** | Section 4 — `CubeFrame` Bitstream Grammar (`UI_PALETTE_ROARING`, $\mathbb{Z}_{2^B}$ `YCoCg-R`, `deltaFlags8` Truth Table, `CCLM-MSB` (`cclmSlopes8`), $4 \times 4$ Sub-Tile Bilinear Shear, 3D Planar + Haar LL, 2-Pass Integer `POCS`) | [`leaf-video/docs/ICD.md#5-section-4--cubeframe-bitstream-grammar`](../../../leaf-video/docs/ICD.md#5-section-4--cubeframe-bitstream-grammar) |
| **Section 6** | Matroska `.mkv` (`CodecID = V_LCV1`) & `LCVU` UDP Datagram Framing | [`leaf-video/docs/ICD.md#6-matroska-mkv-codecid--v_lcv1--lcvu-udp-datagram-framing`](../../../leaf-video/docs/ICD.md#6-matroska-mkv-codecid--v_lcv1--lcvu-udp-datagram-framing) |
| **Section 7** | Tunability (`tuningProfile16`) & Informative Operating Profiles (`ARCHIVAL_LOSSLESS`, `OBS_REALTIME_LOSSLESS`, `WIRELESS_FOVEATED_LOW_LATENCY`, `HARSH_RF_UEP_ZERO_WAIT`, `VR_STEREO_FOVEATED_DIRECT`, `STUDIO_MEZZANINE_12BIT`) | [`leaf-video/docs/ICD.md#7-tunability-tuningprofile16--informative-operating-profiles`](../../../leaf-video/docs/ICD.md#7-tunability-tuningprofile16--informative-operating-profiles) |
| **Section 8** | Crash & Bit-Rot Recovery Procedures (`lcv_accel.c` & `lcv_codec_system_plugins.c`: `SIGKILL`, `SEC-DED`, `CBIT`, `LCV_FLAG_INLINE_RECODE_PARTIAL`, Optical-Flow & Cold-Anchor Concealment) | [`leaf-video/docs/ICD.md#8-crash--bit-rot-recovery-procedures-lcv_accelc--lcv_codec_system_pluginsc`](../../../leaf-video/docs/ICD.md#8-crash--bit-rot-recovery-procedures-lcv_accelc--lcv_codec_system_pluginsc) |
| **Section 9** | Compressed-Domain Transform Procedures & GPU-Direct Acceleration (`lcv_accel.c`: Size-Prefixed Zstd Pools, `lcv_native_crop_or_strip_superblock`, `lcv_native_strip_lsb_outside_radius`, `lcv_native_compact_superblock_zstd`) | [`leaf-video/docs/ICD.md#9-compressed-domain-transform-procedures--gpu-direct-acceleration-lcv_accelc`](../../../leaf-video/docs/ICD.md#9-compressed-domain-transform-procedures--gpu-direct-acceleration-lcv_accelc) |
