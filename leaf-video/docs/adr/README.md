# LeafVideo Architecture Decision Records

| ADR | Title | Status |
|-----|-------|--------|
| [`leaf-video-ADR-001`](leaf-video-ADR-001-spatiotemporal-gray-hilbert-video-codec-and-mkv-transform.md) | Spatiotemporal Gray-Hilbert & Adaptive Roaring Video Codec, Distance-Scaled RLE Concatenation, `.mkv` (`V_LCV1`) Crash Recovery & Compressed-Domain On-Disk Transforms | Accepted |
| [`leaf-video-ADR-002`](leaf-video-ADR-002-three-tier-open-standard-and-commercial-product-architecture.md) | Three-Tier Open Standard (`MIT`) and Commercial Workflow Product Architecture (`LeafCraftDev/LeafVideo`) | Accepted |
| [`leaf-video-ADR-003`](leaf-video-ADR-003-spatiotemporal-database-decode-map-and-causal-compaction.md) | Content-Addressed Spatiotemporal Database (`(layer, x, y, t) -> blockId`), `DecodeMapPacket` Indirection, and Causal Live Compaction | Accepted |
| [`leaf-video-ADR-004`](leaf-video-ADR-004-bitwise-simd-and-non-video-hardware-accelerator-offload.md) | Hardware-Agnostic Bitwise SIMD, Non-Video Silicon Hijack (Optical Flow, NPUs, Compute Shaders), and Sub-Frame Strip Streaming | Accepted |

## Normative Binary Specification

- [`LCV-ICD-1.0` (`../ICD.md`)](../ICD.md) — LeafCraft Video Container & Spatiotemporal Cube Bitstream Interface Control Document
