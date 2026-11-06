package io.github.dailystruggle.leafvideo;

import com.github.luben.zstd.Zstd;

import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Standalone benchmark and empirical proof runner evaluating {@code LCV-ICD-1.0} across the
 * four canonical video workloads (IDE/desktop capture, voxel gameplay + HUD, 3D lighting
 * gradients with lossless and radial {@code d_merge} RLE concatenation, and transparent
 * {@code RGBA} overlays) as well as compressed-domain on-disk transform throughput.
 *
 * @governedBy leaf-video-ADR-001
 * @trace REQ-LCV-F-001
 * @trace REQ-LCV-F-002
 * @trace REQ-LCV-F-005
 */
public final class LcvBenchmarkCli {

    public record WorkloadBenchmarkRow(
        String workloadName,
        int width,
        int height,
        int totalFrames,
        int rawBytes,
        int flatZstdBytes,
        int lcvLosslessBytes,
        int lcvZstdBytes,
        int lcvDistanceRleBytes,
        double lcvLosslessRatio,
        double lcvZstdRatio,
        double lcvDistanceRleRatio,
        double encodeMbPerSec,
        double decodeMbPerSec,
        double distanceRlePsnrDb,
        int lcvHarshRfUepBytes,
        double lcvHarshRfUepRatio,
        double harshRfUepPsnrDb,
        int lcvStrippedK8RawBytes,
        int lcvStrippedK8ZstdBytes,
        double strippedK8PsnrDb,
        Map<String, Integer> externalCodecBytes
    ) {}

    public record TransformBenchmarkSummary(
        double fullDecodeReencodeCropMs,
        double compressedCropMs,
        double cropSpeedupFactor,
        double compressedStripLsbMs,
        double compressedCoalesceRunsMs
    ) {}

    public record FullProofReport(
        List<WorkloadBenchmarkRow> rows,
        TransformBenchmarkSummary transformSummary,
        String markdownTable
    ) {}

    private LcvBenchmarkCli() {}

    public static void main(String[] args) throws Exception {
        FullProofReport report = runProofSuite(640, 368, 8, 2);
        System.out.println(report.markdownTable());
    }

    public static FullProofReport runProofSuite(
        int width, int height, int depthK, int superblockCount
    ) throws LcvCorruptTileException {
        String[] names = {
            "1. IDE / Desktop Screen Capture",
            "2. Voxel Gameplay + Static HUD",
            "3. 3D Continuous Gradient + Motion",
            "4. Transparent RGBA VTuber/HUD Overlay"
        };

        List<WorkloadBenchmarkRow> rows = new ArrayList<>();
        int totalFrames = depthK * superblockCount;
        int rawBytesPerSb = width * height * depthK * 4;
        int totalRawBytes = rawBytesPerSb * superblockCount;

        LcvCubeCodec.DistanceRlePolicy distPolicy = new LcvCubeCodec.DistanceRlePolicy(4, 0, 1.25);
        int roiX = (width / 16) / 2;
        int roiY = (height / 16) / 2;

        for (int wIdx = 0; wIdx < names.length; wIdx++) {
            List<int[]> sbFramesList = new ArrayList<>(superblockCount);
            int flatZstdTotal = 0;
            for (int s = 0; s < superblockCount; s++) {
                int[] frames = generateWorkloadFrames(wIdx, width, height, depthK, s);
                sbFramesList.add(frames);
                ByteBuffer rawBuf = ByteBuffer.allocate(rawBytesPerSb);
                rawBuf.asIntBuffer().put(frames);
                flatZstdTotal += Zstd.compress(rawBuf.array(), 1).length;
            }

            // Warmup & measure LCV Lossless
            LcvStreamContainer.StreamEncoderState encState = new LcvStreamContainer.StreamEncoderState(width, height);
            List<byte[]> losslessSbs = new ArrayList<>(superblockCount);
            long encStartNs = System.nanoTime();
            long runningOff = 0L;
            int losslessTotal = 0;
            for (int s = 0; s < superblockCount; s++) {
                byte[] sb = LcvStreamContainer.encodeSuperblock(
                    sbFramesList.get(s), width, height, depthK,
                    (long) s * depthK, runningOff, roiX, roiY,
                    LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, encState
                );
                losslessSbs.add(sb);
                runningOff += sb.length;
                losslessTotal += sb.length;
            }
            long encElapsedNs = Math.max(1L, System.nanoTime() - encStartNs);

            // Measure LCV Decode + verify 100% lossless bit-exactness
            LcvStreamContainer.StreamDecoderState decState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
            long decStartNs = System.nanoTime();
            for (int s = 0; s < superblockCount; s++) {
                LcvStreamContainer.SuperblockDecodeResult dec = LcvStreamContainer.decodeSuperblock(
                    losslessSbs.get(s), 0, decState
                );
                if (!java.util.Arrays.equals(sbFramesList.get(s), dec.rgbaFrames())) {
                    throw new IllegalStateException("Lossless parity violation on workload " + names[wIdx]);
                }
            }
            long decElapsedNs = Math.max(1L, System.nanoTime() - decStartNs);

            // Measure LCV + Zstd Pool
            LcvStreamContainer.StreamEncoderState zstdEncState = new LcvStreamContainer.StreamEncoderState(width, height);
            int lcvZstdTotal = 0;
            long zOff = 0L;
            for (int s = 0; s < superblockCount; s++) {
                byte[] sbZstd = LcvStreamContainer.encodeSuperblock(
                    sbFramesList.get(s), width, height, depthK,
                    (long) s * depthK, zOff, roiX, roiY,
                    LcvCubeCodec.DistanceRlePolicy.LOSSLESS, true, zstdEncState
                );
                zOff += sbZstd.length;
                lcvZstdTotal += sbZstd.length;
            }

            // Measure LCV Distance-Scaled RLE Concatenation (compressed-domain coalesceRunsByDistance)
            int distRleTotal = 0;
            double mseSum = 0.0;
            long sampleCount = 0L;
            LcvStreamContainer.StreamDecoderState distDecState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
            for (int s = 0; s < superblockCount; s++) {
                byte[] coalesced = LcvOnDiskTransformer.coalesceRunsByDistance(
                    losslessSbs.get(s), roiX, roiY, distPolicy
                );
                distRleTotal += coalesced.length;
                LcvStreamContainer.SuperblockDecodeResult dec = LcvStreamContainer.decodeSuperblock(
                    coalesced, 0, distDecState
                );
                int[] orig = sbFramesList.get(s);
                int[] rec = dec.rgbaFrames();
                for (int i = 0; i < orig.length; i++) {
                    int dr = ((orig[i] >>> 24) & 0xFF) - ((rec[i] >>> 24) & 0xFF);
                    int dg = ((orig[i] >>> 16) & 0xFF) - ((rec[i] >>> 16) & 0xFF);
                    int db = ((orig[i] >>> 8) & 0xFF) - ((rec[i] >>> 8) & 0xFF);
                    mseSum += (dr * dr + dg * dg + db * db) / 3.0;
                }
                sampleCount += orig.length;
            }
            double mse = mseSum / Math.max(1L, sampleCount);
            double psnr = (mse == 0.0) ? Double.POSITIVE_INFINITY : (10.0 * Math.log10((255.0 * 255.0) / mse));

            double rawMiB = totalRawBytes / (1024.0 * 1024.0);
            double encMbPerSec = rawMiB / (encElapsedNs / 1_000_000_000.0);
            double decMbPerSec = rawMiB / (decElapsedNs / 1_000_000_000.0);

            // Measure Harsh-RF UEP Zero-Wait Profile (K = 1, s = 3, d_merge + Section 4B UEP stripped)
            int harshRfBytes = 0;
            double uepMseSum = 0.0;
            long uepSampleCount = 0L;
            LcvStreamContainer.StreamEncoderState uepEncState = new LcvStreamContainer.StreamEncoderState(width, height);
            LcvStreamContainer.StreamDecoderState uepDecState = new LcvStreamContainer.StreamDecoderState(width, height, 1);
            long uepOff = 0L;
            int framePixels = width * height;
            for (int s = 0; s < superblockCount; s++) {
                int[] sbFrames = sbFramesList.get(s);
                for (int f = 0; f < depthK; f++) {
                    int[] singleFrame = new int[framePixels];
                    System.arraycopy(sbFrames, f * framePixels, singleFrame, 0, framePixels);
                    byte[] k1Sb = LcvStreamContainer.encodeSuperblock(
                        singleFrame, width, height, 1,
                        (long) s * depthK + f, uepOff, roiX, roiY,
                        LcvCubeCodec.TuningProfile.HARSH_RF_UEP_ZERO_WAIT, false, uepEncState
                    );
                    byte[] uepShed = LcvOnDiskTransformer.stripLsbPlanes(k1Sb);
                    uepOff += uepShed.length;
                    harshRfBytes += uepShed.length;
                    LcvStreamContainer.SuperblockDecodeResult uepDec = LcvStreamContainer.decodeSuperblock(
                        uepShed, 0, uepDecState
                    );
                    int[] rec = uepDec.rgbaFrames();
                    for (int i = 0; i < singleFrame.length; i++) {
                        int dr = ((singleFrame[i] >>> 24) & 0xFF) - ((rec[i] >>> 24) & 0xFF);
                        int dg = ((singleFrame[i] >>> 16) & 0xFF) - ((rec[i] >>> 16) & 0xFF);
                        int db = ((singleFrame[i] >>> 8) & 0xFF) - ((rec[i] >>> 8) & 0xFF);
                        uepMseSum += (dr * dr + dg * dg + db * db) / 3.0;
                    }
                    uepSampleCount += singleFrame.length;
                }
            }
            double uepMse = uepMseSum / Math.max(1L, uepSampleCount);
            double uepPsnr = (uepMse == 0.0) ? Double.POSITIVE_INFINITY : (10.0 * Math.log10((255.0 * 255.0) / uepMse));

            // Measure K = 8, s = 3 Noise-Stripped Egress (Raw Pool and + Zstd Pool)
            LcvCubeCodec.TuningProfile s3Profile = new LcvCubeCodec.TuningProfile(
                3, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, 8
            );
            LcvStreamContainer.StreamEncoderState s3RawEnc = new LcvStreamContainer.StreamEncoderState(width, height);
            LcvStreamContainer.StreamEncoderState s3ZstdEnc = new LcvStreamContainer.StreamEncoderState(width, height);
            LcvStreamContainer.StreamDecoderState s3DecState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
            int strippedK8RawBytes = 0;
            int strippedK8ZstdBytes = 0;
            double s3MseSum = 0.0;
            long s3SampleCount = 0L;
            long s3RawOff = 0L;
            long s3ZstdOff = 0L;
            for (int s = 0; s < superblockCount; s++) {
                int[] sbFrames = sbFramesList.get(s);
                byte[] sbS3Raw = LcvStreamContainer.encodeSuperblock(
                    sbFrames, width, height, depthK,
                    (long) s * depthK, s3RawOff, roiX, roiY, s3Profile, false, s3RawEnc
                );
                byte[] strippedRaw = LcvOnDiskTransformer.stripLsbPlanes(sbS3Raw);
                s3RawOff += strippedRaw.length;
                strippedK8RawBytes += strippedRaw.length;

                byte[] sbS3Zstd = LcvStreamContainer.encodeSuperblock(
                    sbFrames, width, height, depthK,
                    (long) s * depthK, s3ZstdOff, roiX, roiY, s3Profile, true, s3ZstdEnc
                );
                byte[] strippedZstd = LcvOnDiskTransformer.stripLsbPlanes(sbS3Zstd);
                s3ZstdOff += strippedZstd.length;
                strippedK8ZstdBytes += strippedZstd.length;

                LcvStreamContainer.SuperblockDecodeResult decS3 = LcvStreamContainer.decodeSuperblock(
                    strippedRaw, 0, s3DecState
                );
                int[] rec = decS3.rgbaFrames();
                for (int i = 0; i < sbFrames.length; i++) {
                    int dr = ((sbFrames[i] >>> 24) & 0xFF) - ((rec[i] >>> 24) & 0xFF);
                    int dg = ((sbFrames[i] >>> 16) & 0xFF) - ((rec[i] >>> 16) & 0xFF);
                    int db = ((sbFrames[i] >>> 8) & 0xFF) - ((rec[i] >>> 8) & 0xFF);
                    s3MseSum += (dr * dr + dg * dg + db * db) / 3.0;
                }
                s3SampleCount += sbFrames.length;
            }
            double s3Mse = s3MseSum / Math.max(1L, s3SampleCount);
            double s3Psnr = (s3Mse == 0.0) ? Double.POSITIVE_INFINITY : (10.0 * Math.log10((255.0 * 255.0) / s3Mse));

            Map<String, Integer> extBytes = benchmarkExternalCodecs(sbFramesList, width, height, totalFrames);

            rows.add(new WorkloadBenchmarkRow(
                names[wIdx], width, height, totalFrames,
                totalRawBytes, flatZstdTotal, losslessTotal, lcvZstdTotal, distRleTotal,
                (double) totalRawBytes / losslessTotal,
                (double) totalRawBytes / lcvZstdTotal,
                (double) totalRawBytes / distRleTotal,
                encMbPerSec, decMbPerSec, psnr,
                harshRfBytes, (double) totalRawBytes / harshRfBytes, uepPsnr,
                strippedK8RawBytes, strippedK8ZstdBytes, s3Psnr,
                extBytes
            ));
        }

        // Benchmark compressed-domain transforms vs full-frame decode + crop + re-encode
        int[] gradFrames = generateWorkloadFrames(2, width, height, depthK, 0);
        byte[] baseSb = LcvStreamContainer.encodeSuperblock(
            gradFrames, width, height, depthK, 0L, 0L, roiX, roiY,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );
        int cropTilesX = Math.max(2, (width / 16) / 3);
        int cropTilesY = Math.max(2, (height / 16) / 2);

        long fullStartNs = System.nanoTime();
        LcvStreamContainer.SuperblockDecodeResult fullDec = LcvStreamContainer.decodeSuperblock(baseSb, 0, null);
        int cropW = cropTilesX * 16;
        int cropH = cropTilesY * 16;
        int[] croppedPixels = new int[cropW * cropH * depthK];
        for (int t = 0; t < depthK; t++) {
            for (int y = 0; y < cropH; y++) {
                System.arraycopy(
                    fullDec.rgbaFrames(), t * (width * height) + (16 + y) * width + 16,
                    croppedPixels, t * (cropW * cropH) + y * cropW, cropW
                );
            }
        }
        LcvStreamContainer.encodeSuperblock(
            croppedPixels, cropW, cropH, depthK, 0L, 0L, cropTilesX / 2, cropTilesY / 2,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );
        double fullCropMs = (System.nanoTime() - fullStartNs) / 1_000_000.0;

        long compCropStart = System.nanoTime();
        LcvOnDiskTransformer.cropSubRegion(baseSb, 1, 1, cropTilesX, cropTilesY);
        double compCropMs = (System.nanoTime() - compCropStart) / 1_000_000.0;

        long stripStart = System.nanoTime();
        LcvOnDiskTransformer.stripLsbPlanes(baseSb);
        double stripMs = (System.nanoTime() - stripStart) / 1_000_000.0;

        long coalesceStart = System.nanoTime();
        LcvOnDiskTransformer.coalesceRunsByDistance(baseSb, roiX, roiY, distPolicy);
        double coalesceMs = (System.nanoTime() - coalesceStart) / 1_000_000.0;

        TransformBenchmarkSummary tSummary = new TransformBenchmarkSummary(
            fullCropMs, compCropMs, fullCropMs / Math.max(0.001, compCropMs), stripMs, coalesceMs
        );

        StringBuilder md = new StringBuilder();
        md.append("### 1. `.lcv` Operating Profiles (`LCV-ICD-1.0` Section 7)\n\n");
        md.append("| Workload (`").append(width).append("x").append(height).append("`, ").append(totalFrames).append("f) | Raw Size | `.lcv` (`OBS_REALTIME`) | `.lcv + Zstd` (`ARCHIVAL`) | `.lcv` (`WIRELESS_FOVEATED`) | `.lcv` (`HARSH_RF_UEP` K=1) | Encode (`MB/s`) | Decode (`MB/s`) | `d_merge` / `UEP` PSNR |\n");
        md.append("| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
        for (WorkloadBenchmarkRow r : rows) {
            String psnrStr = Double.isInfinite(r.distanceRlePsnrDb())
                ? "`inf`"
                : String.format(Locale.ROOT, "`%.2f dB`", r.distanceRlePsnrDb());
            String uepPsnrStr = Double.isInfinite(r.harshRfUepPsnrDb())
                ? "`inf`"
                : String.format(Locale.ROOT, "`%.2f dB`", r.harshRfUepPsnrDb());
            md.append(String.format(
                Locale.ROOT,
                "| %s | `%,d B` | `%,d B` (`%.1fx`) | `%,d B` (`%.1fx`) | `%,d B` (`%.1fx`) | `%,d B` (`%.1fx`) | `%.1f MB/s` | `%.1f MB/s` | %s / %s |\n",
                r.workloadName(), r.rawBytes(),
                r.lcvLosslessBytes(), r.lcvLosslessRatio(),
                r.lcvZstdBytes(), r.lcvZstdRatio(),
                r.lcvDistanceRleBytes(), r.lcvDistanceRleRatio(),
                r.lcvHarshRfUepBytes(), r.lcvHarshRfUepRatio(),
                r.encodeMbPerSec(), r.decodeMbPerSec(), psnrStr, uepPsnrStr
            ));
        }

        if (!rows.isEmpty() && !rows.get(0).externalCodecBytes().isEmpty()) {
            md.append("\n### 2. Multi-Codec Compression Ratio Comparison (`ffmpeg 8.1` Lossless Baselines vs. `.lcv`)\n\n");
            md.append("| Workload (`640x368`, 16f) | `HuffYUV` | `UtVideo` | `QTRLE` | `FFV1 (lvl 3)` | `x265 Lossless` | `VP9 Lossless` | `x264rgb (-qp 0)` | Flat `Zstd-1` | **`.lcv` Lossless** | **`.lcv + Zstd`** | **`.lcv Foveated`** |\n");
            md.append("| :--- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
            for (WorkloadBenchmarkRow r : rows) {
                Map<String, Integer> ext = r.externalCodecBytes();
                int raw = r.rawBytes();
                md.append(String.format(
                    Locale.ROOT,
                    "| %s | %s | %s | %s | %s | %s | %s | %s | `%.1fx` | **`%.1fx`** | **`%.1fx`** | **`%.1fx`** |\n",
                    r.workloadName(),
                    fmtRatio(raw, ext.get("huffyuv")),
                    fmtRatio(raw, ext.get("utvideo")),
                    fmtRatio(raw, ext.get("qtrle")),
                    fmtRatio(raw, ext.get("ffv1")),
                    fmtRatio(raw, ext.get("x265")),
                    fmtRatio(raw, ext.get("vp9")),
                    fmtRatio(raw, ext.get("x264rgb")),
                    (double) raw / r.flatZstdBytes(),
                    r.lcvLosslessRatio(),
                    r.lcvZstdRatio(),
                    r.lcvDistanceRleRatio()
                ));
            }
            md.append("\n### 3. Lossy Egress Comparison (`x265 -crf 12` vs. `.lcv` Zero-Decode `stripLsbPlanes` `K=8, s=3`)\n\n");
            md.append("| Workload (`640x368`, 16f) | `x265 -crf 12` (`% of Raw`) | `.lcv` `s=3` Stripped Raw (`% of Raw`) | `.lcv` `s=3` Stripped + Zstd (`% of Raw`) | `.lcv` Stripped PSNR |\n");
            md.append("| :--- | ---: | ---: | ---: | ---: |\n");
            for (WorkloadBenchmarkRow r : rows) {
                Map<String, Integer> ext = r.externalCodecBytes();
                int raw = r.rawBytes();
                Integer crf12 = ext.get("x265_crf12");
                String crf12Str = (crf12 == null)
                    ? "`n/a`"
                    : String.format(Locale.ROOT, "`%,d B` (`%.2f%%`)", crf12, 100.0 * crf12 / raw);
                String s3PsnrStr = Double.isInfinite(r.strippedK8PsnrDb())
                    ? "`inf`"
                    : String.format(Locale.ROOT, "`%.2f dB`", r.strippedK8PsnrDb());
                md.append(String.format(
                    Locale.ROOT,
                    "| %s | %s | **`%,d B` (`%.2f%%`)** | **`%,d B` (`%.2f%%`)** | %s |\n",
                    r.workloadName(),
                    crf12Str,
                    r.lcvStrippedK8RawBytes(), 100.0 * r.lcvStrippedK8RawBytes() / raw,
                    r.lcvStrippedK8ZstdBytes(), 100.0 * r.lcvStrippedK8ZstdBytes() / raw,
                    s3PsnrStr
                ));
            }
        }

        md.append(String.format(
            Locale.ROOT,
            "\n- **Compressed-Domain On-Disk Transforms (`LcvOnDiskTransformer`):** `cropSubRegion` = `%.3f ms` (`%.1fx` faster than full decode+re-encode `%.3f ms`), `stripLsbPlanes` = `%.3f ms`, `coalesceRunsByDistance` = `%.3f ms`.\n",
            tSummary.compressedCropMs(), tSummary.cropSpeedupFactor(),
            tSummary.fullDecodeReencodeCropMs(), tSummary.compressedStripLsbMs(),
            tSummary.compressedCoalesceRunsMs()
        ));

        return new FullProofReport(rows, tSummary, md.toString());
    }

    private static String fmtRatio(int rawBytes, Integer compressedBytes) {
        if (compressedBytes == null || compressedBytes <= 0) {
            return "`n/a`";
        }
        return String.format(Locale.ROOT, "`%.1fx` (`%,d B`)", (double) rawBytes / compressedBytes, compressedBytes);
    }

    private static Map<String, Integer> benchmarkExternalCodecs(
        List<int[]> sbFramesList, int width, int height, int totalFrames
    ) {
        Map<String, Integer> results = new LinkedHashMap<>();
        try {
            int totalPixels = width * height * totalFrames;
            byte[] rawRgbaBytes = new byte[totalPixels * 4];
            ByteBuffer bb = ByteBuffer.wrap(rawRgbaBytes).order(ByteOrder.BIG_ENDIAN);
            for (int[] sb : sbFramesList) {
                bb.asIntBuffer().put(sb);
                bb.position(bb.position() + sb.length * 4);
            }

            String[][] specs = {
                {"huffyuv", ".mkv", "-c:v", "huffyuv", "-pix_fmt", "bgra"},
                {"utvideo", ".mkv", "-c:v", "utvideo", "-pix_fmt", "rgba"},
                {"qtrle", ".mov", "-c:v", "qtrle", "-pix_fmt", "argb"},
                {"ffv1", ".mkv", "-c:v", "ffv1", "-level", "3", "-pix_fmt", "bgra"},
                {"x265", ".mkv", "-c:v", "libx265", "-x265-params", "lossless=1:log-level=none", "-preset", "ultrafast", "-pix_fmt", "gbrp"},
                {"x265_crf12", ".mkv", "-c:v", "libx265", "-crf", "12", "-x265-params", "log-level=none", "-preset", "ultrafast", "-pix_fmt", "gbrp"},
                {"vp9", ".mkv", "-c:v", "libvpx-vp9", "-lossless", "1", "-deadline", "realtime", "-cpu-used", "8", "-pix_fmt", "gbrp"},
                {"x264rgb", ".mkv", "-c:v", "libx264rgb", "-qp", "0", "-preset", "ultrafast", "-pix_fmt", "rgb24"}
            };

            for (String[] spec : specs) {
                String key = spec[0];
                String ext = spec[1];
                Path tmpOut = Files.createTempFile("lcv-bench-" + key + "-", ext);
                try {
                    List<String> cmd = new ArrayList<>();
                    cmd.add("ffmpeg");
                    cmd.add("-y");
                    cmd.add("-hide_banner");
                    cmd.add("-loglevel");
                    cmd.add("error");
                    cmd.add("-f");
                    cmd.add("rawvideo");
                    cmd.add("-pixel_format");
                    cmd.add("rgba");
                    cmd.add("-video_size");
                    cmd.add(width + "x" + height);
                    cmd.add("-framerate");
                    cmd.add("60");
                    cmd.add("-i");
                    cmd.add("pipe:0");
                    for (int i = 2; i < spec.length; i++) {
                        cmd.add(spec[i]);
                    }
                    cmd.add(tmpOut.toString());

                    Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                    try (OutputStream stdin = proc.getOutputStream()) {
                        stdin.write(rawRgbaBytes);
                    }
                    proc.getInputStream().readAllBytes();
                    boolean finished = proc.waitFor(15, TimeUnit.SECONDS);
                    if (finished && proc.exitValue() == 0 && Files.exists(tmpOut)) {
                        long size = Files.size(tmpOut);
                        if (size > 0) {
                            results.put(key, (int) size);
                        }
                    } else if (!finished) {
                        proc.destroyForcibly();
                    }
                } finally {
                    Files.deleteIfExists(tmpOut);
                }
            }
        } catch (Exception ignored) {
            // External ffmpeg optional
        }
        return results;
    }

    public static int[] generateWorkloadFrames(int workloadType, int width, int height, int depthK, int sbIdx) {
        SplittableRandom rng = new SplittableRandom(0xBEEF_0000L + workloadType * 100L + sbIdx);
        int framePixels = width * height;
        int[] frames = new int[framePixels * depthK];

        for (int t = 0; t < depthK; t++) {
            int globalT = sbIdx * depthK + t;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int tx = x / 16;
                    int ty = y / 16;
                    int rgba;
                    switch (workloadType) {
                        case 0 -> {
                            // 1. IDE / Desktop Screen Capture: 70% static dark background, 20% syntax text, 10% moving cursor/scroll
                            if (tx < 3) {
                                rgba = 0x181825FF; // Static sidebar
                            } else if ((tx + ty) % 5 == 0) {
                                rgba = ((x & 7) < 5 && (y & 3) == 1) ? 0x89B4FAFF : 0x1E1E2EFF;
                            } else if (tx == (4 + (globalT % 6)) && ty == 4) {
                                rgba = (x % 3 == 0) ? 0xF38BA8FF : 0x1E1E2EFF;
                            } else {
                                rgba = 0x1E1E2EFF;
                            }
                        }
                        case 1 -> {
                            // 2. Voxel Gameplay + Static HUD
                            if (ty < 2 || ty >= (height / 16) - 2) {
                                rgba = ((x + y) % 6 == 0) ? 0xA6E3A1FF : 0x11111BFF; // Static HUD
                            } else if (y < height / 2) {
                                int sky = (140 + (y / 4)) & 0xFF;
                                rgba = (100 << 24) | (160 << 16) | (sky << 8) | 0xFF;
                            } else {
                                int blockId = ((tx + (globalT / 2)) ^ ty) & 3;
                                int[] voxelPalette = {0x6C7086FF, 0x74C7ECFF, 0x94E2D5FF, 0xFAB387FF};
                                rgba = voxelPalette[blockId];
                            }
                        }
                        case 2 -> {
                            // 3. Continuous 3D Lighting Gradient + Fine Sensor Dither
                            int r = Math.max(0, Math.min(255, 50 + (x * 160 / width) + globalT * 2 + rng.nextInt(-2, 3)));
                            int g = Math.max(0, Math.min(255, 60 + (y * 160 / height) + globalT + rng.nextInt(-2, 3)));
                            int b = Math.max(0, Math.min(255, 90 + ((x + y) * 80 / (width + height)) + globalT * 2 + rng.nextInt(-2, 3)));
                            rgba = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                        }
                        default -> {
                            // 4. Transparent RGBA VTuber / HUD Overlay (80% Alpha = 0)
                            int cx = width / 2;
                            int cy = height / 2;
                            int dx = x - cx;
                            int dy = y - cy;
                            if (dx * dx + dy * dy > (height / 3) * (height / 3)) {
                                rgba = 0x00000000; // Transparent background -> 0B UNIFORM_INLINE
                            } else {
                                int shade = (180 + ((dx + dy + globalT * 3) & 0x3F)) & 0xFF;
                                rgba = (shade << 24) | (140 << 16) | (220 << 8) | 0xFF;
                            }
                        }
                    }
                    frames[t * framePixels + y * width + x] = rgba;
                }
            }
        }
        return frames;
    }
}
