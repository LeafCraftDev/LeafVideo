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
    }
}
