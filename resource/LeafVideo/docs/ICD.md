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
| **Section 4** | Section 2 & 3 — 2D Tile Pointer Directory, `lsbTileOffsets[N_tiles]` & `SEC-DED (72, 64)` Syndrome Table | [`leaf-video/docs/ICD.md#4-section-2--3--2d-tile-pointer-directory--sec-ded-72-64-syndrome-table`](../../../leaf-video/docs/ICD.md#4-section-2--3--2d-tile-pointer-directory--sec-ded-72-64-syndrome-table) |
| **Section 5** | Section 4 — `CubeFrame` Bitstream Grammar (`UI_PALETTE_ROARING`, `YCoCg-R`, Split MSB/LSB Gray Planes, 3D Planar + Haar LL) | [`leaf-video/docs/ICD.md#5-section-4--cubeframe-bitstream-grammar`](../../../leaf-video/docs/ICD.md#5-section-4--cubeframe-bitstream-grammar) |
| **Section 6** | Matroska `.mkv` (`CodecID = V_LCV1`) Streaming Framing (`LcvMkvPacketizer`) | [`leaf-video/docs/ICD.md#6-matroska-mkv-codecid--v_lcv1-framing-lcvmkvpacketizer`](../../../leaf-video/docs/ICD.md#6-matroska-mkv-codecid--v_lcv1-framing-lcvmkvpacketizer) |
| **Section 7** | Normative Tunability & Operating Profiles (`tuningProfile16`: `ARCHIVAL_LOSSLESS`, `OBS_REALTIME_LOSSLESS`, `WIRELESS_FOVEATED_LOW_LATENCY`, `HARSH_RF_UEP_ZERO_WAIT`) | [`leaf-video/docs/ICD.md#7-normative-tunability--operating-profiles-tuningprofile16`](../../../leaf-video/docs/ICD.md#7-normative-tunability--operating-profiles-tuningprofile16) |
| **Section 8** | Crash & Bit-Rot Recovery Procedures (`SIGKILL`, `SEC-DED`, `CBIT`, `FLAG_INLINE_RECODE_PARTIAL`, `MvCoherentPacket`) | [`leaf-video/docs/ICD.md#8-crash--bit-rot-recovery-procedures`](../../../leaf-video/docs/ICD.md#8-crash--bit-rot-recovery-procedures) |
| **Section 9** | Compressed-Domain On-Disk Transform Procedures (`cropSubRegion`, `stripLsbPlanes`, `coalesceRunsByDistance`, `recodeInline` / `resumeInlineRecode`) | [`leaf-video/docs/ICD.md#9-compressed-domain-on-disk-transform-procedures-lcvondisktransformer`](../../../leaf-video/docs/ICD.md#9-compressed-domain-on-disk-transform-procedures-lcvondisktransformer) |
