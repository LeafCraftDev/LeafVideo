package io.github.dailystruggle.leafvideo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Automated comparative compression, speed, and compressed-domain transform proof suite
 * validating all {@code leaf-video-ADR-001} claims across the 4 canonical clip workloads.
 *
 * @governedBy leaf-video-ADR-001
 * @trace REQ-LCV-F-001
 * @trace REQ-LCV-F-002
 * @trace REQ-LCV-F-005
 */
class LcvBenchmarkAndProofSuiteTest {

    @Test
    void canonicalWorkloadsProveCompressionThroughputAndOnDiskTransformSpeedup() throws Exception {
        LcvBenchmarkCli.FullProofReport report = LcvBenchmarkCli.runProofSuite(640, 368, 8, 2);
        java.nio.file.Files.writeString(
            java.nio.file.Path.of("build/lcv-proof-report.md"),
            report.markdownTable()
        );
        assertEquals(4, report.rows().size());

        // 1. IDE / Desktop capture: must achieve > 25x lossless compression
        LcvBenchmarkCli.WorkloadBenchmarkRow ideRow = report.rows().get(0);
        assertTrue(ideRow.lcvLosslessRatio() > 25.0, "Expected > 25x lossless ratio on IDE capture, got " + ideRow.lcvLosslessRatio());

        // 2. Voxel gameplay + HUD: must achieve > 15x lossless compression
        LcvBenchmarkCli.WorkloadBenchmarkRow voxelRow = report.rows().get(1);
        assertTrue(voxelRow.lcvLosslessRatio() > 15.0, "Expected > 15x lossless ratio on voxel gameplay, got " + voxelRow.lcvLosslessRatio());

        // 3. 3D Continuous Gradient + Motion: distance-scaled RLE concatenation must compact further than lossless while keeping PSNR > 32 dB
        LcvBenchmarkCli.WorkloadBenchmarkRow gradRow = report.rows().get(2);
        assertTrue(gradRow.lcvLosslessRatio() > 2.5, "Expected > 2.5x lossless ratio on noisy 3D gradient, got " + gradRow.lcvLosslessRatio());
        assertTrue(gradRow.lcvDistanceRleBytes() < gradRow.lcvLosslessBytes(), "Distance RLE must reduce byte size vs lossless");
        assertTrue(gradRow.distanceRlePsnrDb() > 32.0, "Expected > 32 dB PSNR on foveated distance RLE, got " + gradRow.distanceRlePsnrDb());

        // 4. Transparent RGBA Overlay: must achieve > 10x lossless compression via 0B UNIFORM_INLINE pointers
        LcvBenchmarkCli.WorkloadBenchmarkRow alphaRow = report.rows().get(3);
        assertTrue(alphaRow.lcvLosslessRatio() > 10.0, "Expected > 10x lossless ratio on RGBA overlay, got " + alphaRow.lcvLosslessRatio());

        // 5. Compressed-domain cropSubRegion must be significantly faster than full-frame decode + re-encode
        LcvBenchmarkCli.TransformBenchmarkSummary tf = report.transformSummary();
        assertTrue(tf.cropSpeedupFactor() > 5.0, "Expected > 5x speedup for compressed-domain crop, got " + tf.cropSpeedupFactor());
        assertTrue(tf.compressedStripLsbMs() < 25.0, "Expected sub-25ms LSB strip, got " + tf.compressedStripLsbMs());
        assertTrue(tf.nonTemporalSpliceSpeedupFactor() > 2.0, "Expected > 2x speedup for non-temporal splice, got " + tf.nonTemporalSpliceSpeedupFactor());
        assertTrue(tf.scrubSpeedupFactor() > 1.2, "Expected > 1.2x speedup for O(1) extractSingleFrame timeline scrub, got " + tf.scrubSpeedupFactor());
        assertTrue(tf.compositeSpeedupFactor() > 1.05, "Expected speedup for UNIFORM_INLINE sparse RGBA compositing, got " + tf.compositeSpeedupFactor());
        assertTrue(tf.skippedTransparentOverlayTilePercent() > 70.0, "Expected > 70% transparent overlay tiles skipped in O(1), got " + tf.skippedTransparentOverlayTilePercent());

        // 5B. First-generation provenance & unflattened multi-layer RGBA broadcast proof
        LcvBenchmarkCli.MultiLayerBroadcastSummary mls = report.multiLayerSummary();
        for (int i = 0; i < 4; i++) {
            assertEquals(
                "FIRST_GEN_CLEAN",
                report.signalAudits().get(i).provenanceVerdict(),
                "Expected FIRST_GEN_CLEAN on canonical workload " + i
            );
        }
        assertEquals("FIRST_GEN_CLEAN", mls.cleanOverlayAudit().provenanceVerdict());
        assertTrue(mls.cleanOverlayAudit().transparentTilePercent() > 70.0);
        assertTrue(mls.cleanOverlayAudit().straightAlphaDirtyRgbPixels() > 0);
        assertEquals("POISONED_PRIOR_420_DCT", mls.poisonedOverlayAudit().provenanceVerdict());
        assertTrue(mls.poisonedInflationFactor() > 1.5, "Expected > 1.5x lossless size bloat from 4:2:0 DCT poisoning, got " + mls.poisonedInflationFactor());
        assertTrue(
            mls.unflattenedTotalLcvBytes() < mls.flattenedLcvLosslessBytes(),
            "Expected unflattened 3-layer .lcv (" + mls.unflattenedTotalLcvBytes()
                + " B) to be smaller than pre-flattened single canvas (" + mls.flattenedLcvLosslessBytes() + " B)"
        );

        // 5C. 4K / 8K x 4-Hour Architectural Boundaries (Pool headroom & CompactSignatureIndex)
        assertEquals(4, LcvOnDiskTransformer.clampDepthKFor4k8kPoolHeadroom(3840, 2160, 16));
        assertEquals(1, LcvOnDiskTransformer.clampDepthKFor4k8kPoolHeadroom(7680, 4320, 16));
        LcvOnDiskTransformer.CompactSignatureIndex sigIdx = new LcvOnDiskTransformer.CompactSignatureIndex(64);
        assertTrue(sigIdx.putIfAbsent(0x1234_5678_9ABC_DEF0L, 4096, 512));
        assertEquals((4096L << 32) | 512L, sigIdx.getPackedOffsetAndLen(0x1234_5678_9ABC_DEF0L));
        assertTrue(report.markdownTable().contains("4K60` / `8K60` × 4-Hour Architectural Boundaries"));

        if (!report.realCorpusRows().isEmpty()) {
            for (LcvBenchmarkCli.RealCorpusBenchmarkRow rc : report.realCorpusRows()) {
                assertTrue(rc.lcvPostprocessedArchivalBytes() <= rc.lcvLiveRealtimeBytes());
                assertTrue(rc.postprocessCompressionGainFactor() > 1.0);
            }
            assertTrue(report.markdownTable().contains("Real Uncompressed Content Benchmark Corpus"));
        }
        // 6. Verify tile-parallel lossless and compact throughput, apples-to-apples table split, and Table 8 NLE editor proof
        assertEquals(6, report.editorCompetitorRows().size());
        for (LcvBenchmarkCli.EditorCompetitorBenchmarkRow er : report.editorCompetitorRows()) {
            assertTrue(er.lcvTimeMs() > 0.0);
            assertTrue(er.speedupVsFfv1() > 1.0, "Expected .lcv to outperform FFV1 on NLE operation: " + er.operationName());
        }
        assertTrue(report.markdownTable().contains("Head-to-Head NLE Editor Timeline Performance Comparison"));
        assertTrue(report.markdownTable().contains("8B. Multi-Second `1080p60` Production Timeline"));

        for (LcvBenchmarkCli.WorkloadBenchmarkRow row : report.rows()) {
            assertTrue(row.parallelEncodeMbPerSec() > 0.0);
            assertTrue(row.parallelDecodeMbPerSec() > 0.0);
            assertTrue(row.parallelCompactEncodeMbPerSec() > 0.0);
            assertTrue(row.parallelCompactDecodeMbPerSec() > 0.0);
        }
        if (!ideRow.externalCodecMetrics().isEmpty()) {
            assertTrue(report.markdownTable().contains("Lossless Compression Ratio Comparison"));
            assertTrue(report.markdownTable().contains("Equivalent Lossy / Compact Recording Comparison"));
            assertTrue(report.markdownTable().contains("Multi-Codec Encode & Decode Throughput Comparison"));
            assertTrue(report.markdownTable().contains("drops `A`:"));
        }
    }

    @Test
    void preemptive4k8kPoolGuardsAndIngestProvenanceRecommendationsAreEnforced() throws Exception {
        // 1. 512 MiB Zstd uncompressed pool cap & 30-bit (1 GiB) pointer offset guard
        assertEquals(512 * 1024 * 1024, LcvStreamContainer.MAX_ZSTD_UNCOMPRESSED_POOL_BYTES);
        assertEquals((1 << 30) - 1, LcvStreamContainer.MAX_REL_BYTE_OFFSET_30);
        long maxValidPtr = LcvStreamContainer.makeActiveTilePointer(LcvStreamContainer.MAX_REL_BYTE_OFFSET_30, 128, 32);
        assertEquals(LcvStreamContainer.MAX_REL_BYTE_OFFSET_30, LcvStreamContainer.extractRelByteOffset30(maxValidPtr));
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> LcvStreamContainer.makeActiveTilePointer(1 << 30, 64, 0)
        );
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> LcvStreamContainer.makeActiveTilePointer(-1, 64, 0)
        );

        // 2. Conditional lastEncodedFrames retention (zero 530 MiB frame copy when shiftedCleanAckEnabled == false)
        int width = 64;
        int height = 64;
        int depthK = 4;
        int[] frames = LcvBenchmarkCli.generateWorkloadFrames(1, width, height, depthK, 0);
        LcvStreamContainer.StreamEncoderState encState = new LcvStreamContainer.StreamEncoderState(width, height);
        byte[] sb0 = LcvStreamContainer.encodeSuperblockParallel(
            frames, width, height, depthK, 0L, 0L, 2, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, encState
        );
        org.junit.jupiter.api.Assertions.assertNull(
            encState.lastEncodedFrames,
            "lastEncodedFrames must remain null when shiftedCleanAckEnabled is false"
        );
        encState.enableShiftedCleanAck(true);
        byte[] sb1 = LcvStreamContainer.encodeSuperblockParallel(
            frames, width, height, depthK, depthK, sb0.length, 2, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, encState
        );
        org.junit.jupiter.api.Assertions.assertNotNull(
            encState.lastEncodedFrames,
            "lastEncodedFrames must be populated when shiftedCleanAckEnabled is true"
        );
        encState.enableShiftedCleanAck(false);
        org.junit.jupiter.api.Assertions.assertNull(
            encState.lastEncodedFrames,
            "Disabling shiftedCleanAckEnabled must release lastEncodedFrames"
        );

        // 3. CompactSignatureIndex cross-superblock deduplication in compactStreamWithDecodeMap
        byte[] sb0Active = LcvStreamContainer.encodeSuperblockParallel(
            frames, width, height, depthK, 0L, 0L, 2, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, null
        );
        byte[] sb1Active = LcvStreamContainer.encodeSuperblockParallel(
            frames, width, height, depthK, depthK, sb0Active.length, 2, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, null
        );
        LcvOnDiskTransformer.DatabaseCompactionResult compRes =
            LcvOnDiskTransformer.compactStreamWithDecodeMap(java.util.List.of(sb0Active, sb1Active, sb1));
        assertTrue(
            compRes.deduplicatedBlockCount() >= 2,
            "Expected cross-superblock signature and pool deduplication hits, got " + compRes.deduplicatedBlockCount()
        );

        // 4. Ingest provenance profile recommendation (clean first-gen -> s=2 lossless, 4:2:0 DCT poisoned -> s=3 split)
        LcvCubeCodec.TuningProfile cleanProfile =
            LcvOnDiskTransformer.recommendProfileForIngest(frames, width, height, depthK);
        assertEquals(2, cleanProfile.lsbSplitBits());
        int[] cleanIdeFrames = LcvBenchmarkCli.generateWorkloadFrames(0, width, height, depthK, 0);
        int[] poisonedFrames = java.util.Arrays.copyOf(cleanIdeFrames, cleanIdeFrames.length);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int idx = y * width + x;
                int p = poisonedFrames[idx];
                int blockStep = (((x >>> 3) + (y >>> 3)) & 1) == 0 ? 14 : -14;
                int ring = ((x & 1) == 0) ? 2 : -2;
                int delta = blockStep + ring;
                int r = Math.max(0, Math.min(255, ((p >>> 24) & 0xFF) + delta));
                int g = Math.max(0, Math.min(255, ((p >>> 16) & 0xFF) + delta));
                int b = Math.max(0, Math.min(255, ((p >>> 8) & 0xFF) + delta));
                poisonedFrames[idx] = (r << 24) | (g << 16) | (b << 8) | 0xFF;
            }
        }
        LcvCubeCodec.TuningProfile poisonedProfile =
            LcvOnDiskTransformer.recommendProfileForIngest(poisonedFrames, width, height, depthK);
        assertEquals(3, poisonedProfile.lsbSplitBits());

        // 5. Hoisted SuperblockHeader/Zstd pool caching + viewport ROI + decodeSplitCubeSingleSliceRgba parity across all localT
        byte[] zstdSb0 = LcvOnDiskTransformer.wrapZstdPools(sb0Active, null);
        java.util.List<Integer> zstdOffsets = LcvStreamContainer.indexValidSuperblocks(zstdSb0);
        LcvStreamContainer.SuperblockDecodeResult seqDecoded =
            LcvStreamContainer.decodeSuperblockParallel(zstdSb0, zstdOffsets.get(0), null);
        int framePixels = width * height;
        for (int t = 0; t < depthK; t++) {
            LcvStreamContainer.SingleFrameExtractResult singleSliceFrame =
                LcvStreamContainer.extractSingleFrame(zstdSb0, zstdOffsets, t);
            for (int i = 0; i < framePixels; i++) {
                assertEquals(
                    seqDecoded.rgbaFrames()[t * framePixels + i],
                    singleSliceFrame.frameRgba()[i],
                    "Single-slice extractSingleFrame mismatch at localT=" + t + " pixel=" + i
                );
            }
        }
        LcvStreamContainer.SingleFrameExtractResult fullFrame =
            LcvStreamContainer.extractSingleFrame(zstdSb0, zstdOffsets, 2L);
        LcvStreamContainer.SingleFrameExtractResult roiFrame =
            LcvStreamContainer.extractSingleFrame(zstdSb0, zstdOffsets, 2L, 1, 1, 2, 2);
        assertTrue(roiFrame.decodedTilePayloads() < fullFrame.decodedTilePayloads());
        for (int py = 16; py < 48; py++) {
            for (int px = 16; px < 48; px++) {
                assertEquals(fullFrame.frameRgba()[py * width + px], roiFrame.frameRgba()[py * width + px]);
            }
        }
    }
}
