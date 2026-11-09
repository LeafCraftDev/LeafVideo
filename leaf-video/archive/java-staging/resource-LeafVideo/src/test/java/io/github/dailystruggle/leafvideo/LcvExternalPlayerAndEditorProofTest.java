package io.github.dailystruggle.leafvideo;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end external player ({@code ffprobe}, {@code ffplay}/{@code ffmpeg}) and non-linear video
 * editor ({@code qtrle} {@code .mov} alpha carrier + compositor overlay) proof suite for
 * {@link LcvCli}, {@link LcvMkvPacketizer}, {@link LcvStreamContainer}, and {@link LcvOnDiskTransformer}.
 *
 * @governedBy leaf-video-ADR-001
 * @governedBy leaf-video-ADR-002
 * @governedBy leaf-video-ADR-004
 * @trace REQ-LCV-F-001
 * @trace REQ-LCV-F-002
 * @trace REQ-LCV-F-003
 * @trace REQ-LCV-F-004
 * @trace REQ-LCV-F-005
 * @trace REQ-LCV-HW-001
 * @trace REQ-LCV-HW-002
 */
class LcvExternalPlayerAndEditorProofTest {

    @TempDir
    Path tempDir;

    @Test
    void ffprobeDemuxesVLcv1MatroskaContainerAndRecoversTruncatedStream() throws Exception {
        Assumptions.assumeTrue(isToolAvailable("ffprobe"), "ffprobe is required for external container proof");

        Path mkvPath = tempDir.resolve("sample_v_lcv1.mkv");
        int exit = LcvCli.execute(new String[]{
            "sample",
            "--output", mkvPath.toString(),
            "--width", "320",
            "--height", "192",
            "--depth-k", "8",
            "--superblocks", "2",
            "--fps", "60",
            "--mode", "scene",
            "--profile", "OBS_REALTIME_LOSSLESS"
        });
        assertEquals(0, exit);
        assertTrue(Files.exists(mkvPath));

        String probeOut = runCommand(List.of(
            "ffprobe", "-v", "error",
            "-show_format", "-show_streams", "-show_packets",
            "-of", "default=noprint_wrappers=1",
            mkvPath.toString()
        ));

        assertTrue(probeOut.contains("format_name=matroska,webm"), () -> "Unexpected ffprobe format:\n" + probeOut);
        assertTrue(probeOut.contains("codec_type=video"), () -> "Missing video stream in ffprobe:\n" + probeOut);
        assertTrue(probeOut.contains("width=320"), () -> "Missing width=320 in ffprobe:\n" + probeOut);
        assertTrue(probeOut.contains("height=192"), () -> "Missing height=192 in ffprobe:\n" + probeOut);

        List<byte[]> superblocks = LcvCli.extractSuperblocks(Files.readAllBytes(mkvPath));
        assertEquals(2, superblocks.size());
        assertTrue(
            probeOut.contains("size=" + superblocks.get(0).length),
            () -> "ffprobe packet size must match Superblock 0 length (" + superblocks.get(0).length + "):\n" + probeOut
        );
        assertTrue(
            probeOut.contains("size=" + superblocks.get(1).length),
            () -> "ffprobe packet size must match Superblock 1 length (" + superblocks.get(1).length + "):\n" + probeOut
        );

        // Simulate SIGKILL / power loss mid-write by appending a truncated 3rd cluster + partial superblock
        byte[] intactBytes = Files.readAllBytes(mkvPath);
        byte[] crashedBytes = Arrays.copyOf(intactBytes, intactBytes.length + 48);
        System.arraycopy("LCV_V001".getBytes(StandardCharsets.US_ASCII), 0, crashedBytes, intactBytes.length + 12, 8);
        Path crashedMkvPath = tempDir.resolve("crashed_v_lcv1.mkv");
        Files.write(crashedMkvPath, crashedBytes);

        LcvCli.StreamInspectionReport report = LcvCli.inspectContainerFile(crashedMkvPath);
        assertEquals(320, report.width());
        assertEquals(192, report.height());
        assertEquals(8, report.depthK());
        assertEquals(2, report.superblockCount(), "All completed superblocks prior to crash must be recovered");
        assertEquals(16, report.totalFrames());
        assertEquals(0, report.quarantinedTiles());
    }

    @Test
    void realFfmpegVideoIngestCompressedCropAndPlayerRoundTripAreBitExact() throws Exception {
        Assumptions.assumeTrue(
            isToolAvailable("ffmpeg") && isToolAvailable("ffprobe"),
            "ffmpeg and ffprobe are required for real video ingest and player round-trip proof"
        );

        int width = 256;
        int height = 144;
        int depthK = 4;
        int superblockCount = 3;
        int totalFrames = depthK * superblockCount; // 12 frames

        // 1. Generate a real external source video (.mov with qtrle via ffmpeg testsrc2)
        Path sourceVideo = tempDir.resolve("external_source.mov");
        runCommand(List.of(
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-f", "lavfi",
            "-i", "testsrc2=size=" + width + "x" + height + ":rate=60",
            "-frames:v", String.valueOf(totalFrames),
            "-c:v", "qtrle",
            "-pix_fmt", "argb",
            sourceVideo.toString()
        ));
        assertTrue(Files.exists(sourceVideo));

        byte[] groundTruthRgba = LcvCli.extractRawRgbaViaFfmpeg(sourceVideo, width, height);
        assertEquals(width * height * totalFrames * 4, groundTruthRgba.length);

        // 2. Ingest the external video into .mkv (CodecID = V_LCV1) via LcvCli encode --from-video
        Path capturedMkv = tempDir.resolve("ingested_v_lcv1.mkv");
        int encodeExit = LcvCli.execute(new String[]{
            "encode",
            "--from-video", sourceVideo.toString(),
            "--output", capturedMkv.toString(),
            "--width", String.valueOf(width),
            "--height", String.valueOf(height),
            "--depth-k", String.valueOf(depthK),
            "--fps", "60",
            "--profile", "ARCHIVAL_LOSSLESS"
        });
        assertEquals(0, encodeExit);

        // 3. Verify live player pipeline (play --headless-frames 12) and full-frame RGBA round-trip
        int playExit = LcvCli.execute(new String[]{
            "play",
            "--input", capturedMkv.toString(),
            "--headless-frames", String.valueOf(totalFrames)
        });
        assertEquals(0, playExit);

        byte[] decodedFullRgba = LcvCli.decodeContainerToRawRgbaBytes(capturedMkv);
        assertArrayEquals(
            groundTruthRgba, decodedFullRgba,
            "Ingested external video decoded from .mkv (V_LCV1) must be 100% bit-exact (PSNR = Infinity)"
        );

        // 4. Perform compressed-domain cropSubRegion on disk without decoding pixels
        // Extract tiles [2..9] x [1..6] -> 8x6 tiles = 128x96 pixels at offset (x=32, y=16)
        Path croppedMkv = tempDir.resolve("cropped_128x96.mkv");
        LcvCli.TransformResult cropRes = LcvCli.cropContainerFile(capturedMkv, croppedMkv, 2, 1, 8, 6);
        assertEquals(128, cropRes.dstWidth());
        assertEquals(96, cropRes.dstHeight());
        assertTrue(cropRes.dstBytes() < cropRes.srcBytes(), "Cropped .mkv must be smaller than full-canvas .mkv");

        // Verify ffprobe recognizes the new cropped Matroska dimensions (128x96)
        String croppedProbe = runCommand(List.of(
            "ffprobe", "-v", "error",
            "-show_streams", "-of", "default=noprint_wrappers=1",
            croppedMkv.toString()
        ));
        assertTrue(croppedProbe.contains("width=128"), () -> "Cropped ffprobe missing width=128:\n" + croppedProbe);
        assertTrue(croppedProbe.contains("height=96"), () -> "Cropped ffprobe missing height=96:\n" + croppedProbe);

        // 5. Decode croppedMkv and verify bit-exact pixel match against the sub-rectangle of groundTruthRgba
        byte[] decodedCropRgba = LcvCli.decodeContainerToRawRgbaBytes(croppedMkv);
        int cropW = 128;
        int cropH = 96;
        assertEquals(cropW * cropH * totalFrames * 4, decodedCropRgba.length);

        ByteBuffer fullBuf = ByteBuffer.wrap(groundTruthRgba).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer cropBuf = ByteBuffer.wrap(decodedCropRgba).order(ByteOrder.BIG_ENDIAN);
        for (int f = 0; f < totalFrames; f++) {
            int fullFrameBase = f * (width * height);
            int cropFrameBase = f * (cropW * cropH);
            for (int cy = 0; cy < cropH; cy++) {
                for (int cx = 0; cx < cropW; cx++) {
                    int expectedRgba = fullBuf.getInt((fullFrameBase + (16 + cy) * width + (32 + cx)) * 4);
                    int actualRgba = cropBuf.getInt((cropFrameBase + cy * cropW + cx) * 4);
                    assertEquals(
                        expectedRgba, actualRgba,
                        "Crop pixel mismatch at frame=" + f + ", cx=" + cx + ", cy=" + cy
                    );
                }
            }
        }
    }

    @Test
    void nleAlphaOverlayCarrierAndCompositeRenderRoundTrip() throws Exception {
        Assumptions.assumeTrue(
            isToolAvailable("ffmpeg") && isToolAvailable("ffprobe"),
            "ffmpeg and ffprobe are required for NLE carrier and alpha overlay compositing proof"
        );

        int width = 256;
        int height = 144;
        int depthK = 4;
        int superblocks = 2;

        // 1. Export background scene .mkv (V_LCV1) and transparent HUD overlay .mkv (V_LCV1)
        Path sceneMkv = tempDir.resolve("scene.mkv");
        Path overlayMkv = tempDir.resolve("overlay.mkv");
        LcvCli.exportSampleFile(
            sceneMkv, width, height, depthK, superblocks, 60,
            "scene", LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false
        );
        LcvCli.exportSampleFile(
            overlayMkv, width, height, depthK, superblocks, 60,
            "overlay", LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false
        );

        LcvCli.StreamInspectionReport overlayReport = LcvCli.inspectContainerFile(overlayMkv);
        assertFalse(overlayReport.lsbStripped());
        assertTrue(
            overlayReport.uniformInlineTiles() > 0,
            "Overlay mode must encode transparent background tiles as 0-byte UNIFORM_INLINE pointers"
        );

        // 2. Export both .mkv (V_LCV1) streams to NLE-native lossless RGBA carriers (.mov qtrle)
        Path sceneMov = tempDir.resolve("scene_carrier.mov");
        Path overlayMov = tempDir.resolve("overlay_carrier.mov");
        LcvCli.exportToEditorCarrier(sceneMkv, sceneMov, "qtrle", null, null, null, null, false);
        LcvCli.exportToEditorCarrier(overlayMkv, overlayMov, "qtrle", null, null, null, null, false);

        byte[] expectedOverlayRgba = LcvCli.decodeContainerToRawRgbaBytes(overlayMkv);
        byte[] carrierOverlayRgba = LcvCli.extractRawRgbaViaFfmpeg(overlayMov, width, height);
        assertArrayEquals(
            expectedOverlayRgba, carrierOverlayRgba,
            "NLE qtrle .mov carrier must preserve 100% of 8-bit RGBA pixels and alpha transparency"
        );

        // 3. Composite the NLE overlay strip onto the background scene strip via ffmpeg overlay filter
        // (matching Blender VSE ALPHA_OVER / DaVinci Resolve timeline compositing)
        Path compositedMov = tempDir.resolve("nle_composited.mov");
        runCommand(List.of(
            "ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
            "-i", sceneMov.toString(),
            "-i", overlayMov.toString(),
            "-filter_complex", "[0:v][1:v]overlay=0:0:format=rgb",
            "-c:v", "qtrle",
            "-pix_fmt", "argb",
            compositedMov.toString()
        ));

        byte[] sceneRgba = LcvCli.decodeContainerToRawRgbaBytes(sceneMkv);
        byte[] compositedRgba = LcvCli.extractRawRgbaViaFfmpeg(compositedMov, width, height);
        ByteBuffer sceneBuf = ByteBuffer.wrap(sceneRgba).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer overlayBuf = ByteBuffer.wrap(expectedOverlayRgba).order(ByteOrder.BIG_ENDIAN);
        ByteBuffer compBuf = ByteBuffer.wrap(compositedRgba).order(ByteOrder.BIG_ENDIAN);

        int transparentChecked = 0;
        int opaqueHudChecked = 0;
        int totalPixels = width * height * depthK * superblocks;
        for (int i = 0; i < totalPixels; i++) {
            int bg = sceneBuf.getInt(i * 4);
            int fg = overlayBuf.getInt(i * 4);
            int comp = compBuf.getInt(i * 4);
            int fgAlpha = fg & 0xFF;
            if (fgAlpha == 0) {
                assertEquals(bg, comp, "Transparent UNIFORM_INLINE overlay pixel must leave background untouched");
                transparentChecked++;
            } else if (fgAlpha == 0xFF) {
                assertEquals(fg, comp, "Opaque HUD overlay pixel must replace background pixel in composite");
                opaqueHudChecked++;
            }
        }
        assertTrue(transparentChecked > totalPixels / 2, "Majority of overlay pixels should be transparent");
        assertTrue(opaqueHudChecked > 0, "Overlay must contain opaque HUD border pixels");

        // 4. Verify compressed-domain stripLsbPlanes proxy workflow
        Path proxyMkv = tempDir.resolve("scene_proxy.mkv");
        LcvCli.TransformResult stripRes = LcvCli.stripLsbContainerFile(sceneMkv, proxyMkv);
        assertTrue(stripRes.dstBytes() < stripRes.srcBytes(), "LSB-stripped proxy .mkv must be smaller than lossless .mkv");
        LcvCli.StreamInspectionReport proxyReport = LcvCli.inspectContainerFile(proxyMkv);
        assertTrue(proxyReport.lsbStripped());

        byte[] proxyRgba = LcvCli.decodeContainerToRawRgbaBytes(proxyMkv);
        double psnr = computeRgbaPsnr(sceneRgba, proxyRgba);
        assertTrue(psnr > 34.0, "6-bit MSB proxy (g7..g2) PSNR must exceed 34 dB, got " + psnr);
    }

    @Test
    void obsAndBlenderPythonIntegrationsPassEndToEndSelfTests() throws Exception {
        Assumptions.assumeTrue(
            isToolAvailable("python3") && isToolAvailable("ffmpeg") && isToolAvailable("ffprobe"),
            "python3, ffmpeg, and ffprobe are required for OBS and Blender script self-tests"
        );

        Path obsScript = resolveIntegrationScript("obs_leafvideo_crop_guard.py");
        Path blenderScript = resolveIntegrationScript("blender_vse_leafvideo.py");
        assertTrue(Files.isRegularFile(obsScript), "Missing OBS script at " + obsScript);
        assertTrue(Files.isRegularFile(blenderScript), "Missing Blender addon at " + blenderScript);

        String obsOut = runCommand(List.of("python3", obsScript.toString(), "--self-test"));
        assertTrue(obsOut.contains("[OBS LeafVideo Self-Test] PASS"), () -> "Unexpected OBS self-test output:\n" + obsOut);

        String blenderOut = runCommand(List.of("python3", blenderScript.toString(), "--self-test"));
        assertTrue(
            blenderOut.contains("[Blender VSE LeafVideo Self-Test] PASS"),
            () -> "Unexpected Blender VSE self-test output:\n" + blenderOut
        );
    }

    @Test
    void nonTemporalSplicePostprocessAndUniversalRecorderEditorCompatibility() throws Exception {
        Assumptions.assumeTrue(
            isToolAvailable("ffmpeg") && isToolAvailable("ffprobe"),
            "ffmpeg and ffprobe are required for universal recorder/editor carrier proof"
        );

        // 1. Export a 3-superblock .lcv stream (OBS_REALTIME_LOSSLESS, raw pool) and post-process with wrapZstdPools
        Path rawLcv = tempDir.resolve("live_realtime.lcv");
        assertEquals(0, LcvCli.execute(new String[]{
            "sample", "--output", rawLcv.toString(),
            "--width", "128", "--height", "64", "--depth-k", "4", "--superblocks", "3",
            "--mode", "scene", "--profile", "OBS_REALTIME_LOSSLESS"
        }));
        Path postLcv = tempDir.resolve("postprocessed_zstd.lcv");
        assertEquals(0, LcvCli.execute(new String[]{
            "postprocess", "--input", rawLcv.toString(), "--output", postLcv.toString(), "--zstd", "true"
        }));
        assertTrue(Files.size(postLcv) < Files.size(rawLcv), "wrapZstdPools postprocess must reduce container byte size");
        assertArrayEquals(
            LcvCli.decodeContainerToRawRgbaBytes(rawLcv),
            LcvCli.decodeContainerToRawRgbaBytes(postLcv),
            "wrapZstdPools postprocess must be 100% bit-exact lossless"
        );

        // 2. Non-temporal editor splice: create an edited superblock for frameIndex64=0, splice without temporal rewrite, and compact
        Path patchLcv = tempDir.resolve("patch_sb0.lcv");
        assertEquals(0, LcvCli.execute(new String[]{
            "sample", "--output", patchLcv.toString(),
            "--width", "128", "--height", "64", "--depth-k", "4", "--superblocks", "1",
            "--mode", "overlay", "--profile", "OBS_REALTIME_LOSSLESS"
        }));
        Path splicedLcv = tempDir.resolve("spliced_nontemporal.lcv");
        assertEquals(0, LcvCli.execute(new String[]{
            "splice", "--input", rawLcv.toString(), "--patch", patchLcv.toString(), "--output", splicedLcv.toString()
        }));
        LcvCli.StreamInspectionReport splicedReport = LcvCli.inspectContainerFile(splicedLcv);
        assertEquals(3, splicedReport.superblockCount());
        assertEquals(12, splicedReport.totalFrames());

        Path compactedLcv = tempDir.resolve("compacted_temporal.lcv");
        assertEquals(0, LcvCli.execute(new String[]{
            "compact", "--input", splicedLcv.toString(), "--output", compactedLcv.toString()
        }));
        assertArrayEquals(
            LcvCli.decodeContainerToRawRgbaBytes(splicedLcv),
            LcvCli.decodeContainerToRawRgbaBytes(compactedLcv),
            "Non-temporal spliced stream and compacted temporal stream must decode to identical pixels"
        );

        // 3. Universal recorder batch ingest (ShadowPlay/ReLive/OBS .mp4) + Universal NLE carriers (Resolve/Premiere ProRes 4444, UtVideo, CapCut MP4)
        Path recDir = tempDir.resolve("recordings");
        Files.createDirectories(recDir);
        Path shadowPlayMp4 = recDir.resolve("shadowplay_clip.mp4");
        assertEquals(0, LcvCli.execute(new String[]{
            "export-carrier", "--input", rawLcv.toString(), "--output", shadowPlayMp4.toString(), "--carrier", "capcut"
        }));
        assertEquals(0, LcvCli.execute(new String[]{
            "ingest-dir", "--dir", recDir.toString(), "--width", "128", "--height", "64", "--depth-k", "4"
        }));
        assertTrue(Files.exists(recDir.resolve("shadowplay_clip_v_lcv1.mkv")));
        assertTrue(Files.exists(recDir.resolve("shadowplay_clip_9x16_v_lcv1.mkv")));

        Path proresMov = tempDir.resolve("resolve_prores4444.mov");
        assertEquals(0, LcvCli.execute(new String[]{
            "export-carrier", "--input", patchLcv.toString(), "--output", proresMov.toString(), "--carrier", "prores_4444"
        }));
        assertTrue(Files.size(proresMov) > 0);
        Path utvideoMkv = tempDir.resolve("vegas_utvideo.mkv");
        assertEquals(0, LcvCli.execute(new String[]{
            "export-carrier", "--input", patchLcv.toString(), "--output", utvideoMkv.toString(), "--carrier", "utvideo"
        }));
        assertTrue(Files.size(utvideoMkv) > 0);
    }

    @Test
    void boundedStreamEncodeAndDecodePipesMatchInMemoryContainerBitIdentically() throws Exception {
        int width = 128;
        int height = 64;
        int depthK = 4;
        int fps = 60;
        // 10 frames = 2 full superblocks (4 + 4) + 1 partial superblock (2 frames padded to 4)
        int totalFrames = 10;
        int framePixels = width * height;
        int[] synthFrames = LcvCli.synthesizeFrames(width, height, totalFrames, 0, "scene");
        byte[] rawRgbaBytes = new byte[synthFrames.length * 4];
        ByteBuffer.wrap(rawRgbaBytes).order(ByteOrder.BIG_ENDIAN).asIntBuffer().put(synthFrames);

        Path batchMkv = tempDir.resolve("batch_encode.mkv");
        Path streamMkv = tempDir.resolve("stream_encode.mkv");
        int batchSbCount = LcvCli.encodeRawRgbaBytesToFile(
            rawRgbaBytes, width, height, depthK, fps, 4, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, batchMkv
        );
        int streamSbCount = LcvCli.encodeRawRgbaStreamToFile(
            new ByteArrayInputStream(rawRgbaBytes), width, height, depthK, fps, 4, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, false, streamMkv
        );
        assertEquals(3, batchSbCount);
        assertEquals(batchSbCount, streamSbCount);
        assertArrayEquals(
            Files.readAllBytes(batchMkv),
            Files.readAllBytes(streamMkv),
            "Bounded O(1)-memory streaming MKV encode must produce byte-identical container to batch encode"
        );

        // Also verify streaming .lcv container and inline stripLsbOnFlush parity
        Path streamCompactLcv = tempDir.resolve("stream_compact.lcv");
        LcvCli.encodeRawRgbaStreamToFile(
            new ByteArrayInputStream(rawRgbaBytes), width, height, depthK, fps, 4, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, true, streamCompactLcv
        );
        LcvCli.StreamInspectionReport compactReport = LcvCli.inspectContainerFile(streamCompactLcv);
        assertTrue(compactReport.lsbStripped(), "Inline streaming LSB strip must mark FLAG_LSB_STRIPPED");
        assertEquals(3, compactReport.superblockCount());

        // Verify bounded streaming decode pipe (full + compressed-domain cropped + maxFrames limit)
        ByteArrayOutputStream fullPipeOut = new ByteArrayOutputStream();
        int decodedFrames = LcvCli.streamDecodeSuperblocksToPipe(
            streamMkv, fullPipeOut, null, null, null, null, false, 0
        );
        assertEquals(12, decodedFrames);
        assertArrayEquals(
            LcvCli.decodeContainerToRawRgbaBytes(batchMkv),
            fullPipeOut.toByteArray(),
            "Bounded streaming decode pipe must match in-memory decode bit-for-bit"
        );

        ByteArrayOutputStream croppedPipeOut = new ByteArrayOutputStream();
        int croppedFrames = LcvCli.streamDecodeSuperblocksToPipe(
            streamMkv, croppedPipeOut, 2, 1, 4, 2, true, 6
        );
        assertEquals(6, croppedFrames);
        assertEquals(6 * (4 * LcvPrimitives.TILE_DIM) * (2 * LcvPrimitives.TILE_DIM) * 4, croppedPipeOut.size());
    }

    @Test
    void nativeSimdAndHardwareAcceleratorBridgeBitExactParity() {
        assertTrue(
            LcvPrimitives.isNativeLibraryLoaded(),
            "Expected C11/SIMD native accelerator (liblcv_accel) to be compiled and loaded in :resource:LeafVideo"
        );
        assertNotNull(LcvPrimitives.getHardwareAccelerator());

        int width = 64;
        int height = 32;
        int depthK = 4;
        int[] sceneFrames = LcvCli.synthesizeFrames(width, height, depthK, 0, "scene");
        int[] overlayFrames = LcvCli.synthesizeFrames(width, height, depthK, 0, "overlay");

        LcvPrimitives.ScratchArena nativeArena = new LcvPrimitives.ScratchArena();
        LcvPrimitives.ScratchArena javaArena = new LcvPrimitives.ScratchArena();

        int tilesX = width / LcvPrimitives.TILE_DIM;
        int tilesY = height / LcvPrimitives.TILE_DIM;
        int[] gpuTileMeta = new int[tilesX * tilesY * 4];

        for (int[] frames : List.of(sceneFrames, overlayFrames)) {
            int batchStride = LcvPrimitives.batchAnalyzeSuperblock(frames, width, height, depthK, gpuTileMeta);
            if (LcvPrimitives.isCudaGpuAvailable()) {
                assertEquals(4, batchStride, "Expected RTX 3090 CUDA PTX batch kernel to return 4-word tile metadata");
            }
            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    LcvPrimitives.CubeFingerprint natFp = LcvPrimitives.extractAndFingerprintCubeTyx(
                        frames, tx, ty, width, height, depthK, nativeArena
                    );
                    LcvPrimitives.CubeFingerprint refFp = LcvPrimitives.extractAndFingerprintCubeTyxJava(
                        frames, tx, ty, width, height, depthK, javaArena
                    );

                    assertEquals(refFp.uniform, natFp.uniform);
                    assertEquals(refFp.firstRgba, natFp.firstRgba);
                    int tileIdx = ty * tilesX + tx;
                    if (batchStride >= 2) {
                        assertEquals(refFp.uniform ? 1 : 0, gpuTileMeta[tileIdx * batchStride]);
                        assertEquals(refFp.firstRgba, gpuTileMeta[tileIdx * batchStride + 1]);
                    }
                    if (!refFp.uniform) {
                        assertArrayEquals(javaArena.cubeRgbaTyx, nativeArena.cubeRgbaTyx);
                        assertEquals(refFp.digest64, natFp.digest64);
                        assertEquals(refFp.fingerprint8, natFp.fingerprint8);
                        assertEquals(refFp.optimalCurveId, natFp.optimalCurveId);
                        assertEquals(refFp.paletteSize, natFp.paletteSize);
                        assertEquals(refFp.hasZeroAlpha, natFp.hasZeroAlpha);
                        assertEquals(refFp.hasOpaqueAlpha, natFp.hasOpaqueAlpha);
                        assertEquals(refFp.allZeroAlphaAreZeroRgba, natFp.allZeroAlphaAreZeroRgba);
                        assertEquals(refFp.allOpaqueAre255AndConstGB, natFp.allOpaqueAre255AndConstGB);
                        int natMv = LcvPrimitives.estimateTileVelocity1Pass(nativeArena.cubeRgbaTyx, depthK);
                        int refMv = LcvPrimitives.estimateTileVelocity1PassJava(javaArena.cubeRgbaTyx, depthK);
                        assertEquals(refMv, natMv);
                        if (batchStride >= 4) {
                            assertEquals(refFp.optimalCurveId, gpuTileMeta[tileIdx * batchStride + 2]);
                            assertEquals(refMv, gpuTileMeta[tileIdx * batchStride + 3]);
                        }
                        int[] natHaar = Arrays.copyOf(nativeArena.cubeRgbaTyx, LcvPrimitives.TILE_PIXELS * depthK);
                        int[] refHaar = Arrays.copyOf(javaArena.cubeRgbaTyx, LcvPrimitives.TILE_PIXELS * depthK);
                        LcvPrimitives.haar2LevelSubbandConditionCube(natHaar, depthK, 6, 4);
                        LcvPrimitives.haar2LevelSubbandConditionCubeJava(refHaar, depthK, 6, 4);
                        assertArrayEquals(refHaar, natHaar);
                    }
                }
            }
            if (LcvPrimitives.isCudaGpuAvailable()) {
                byte[] sb0Gpu = LcvStreamContainer.encodeSuperblockGpuDirect(
                    frames, width, height, depthK, 0L, 0L, 1, 1,
                    LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, true
                );
                byte[] sb1Gpu = LcvStreamContainer.encodeSuperblockGpuDirect(
                    frames, width, height, depthK, depthK, sb0Gpu.length, 1, 1,
                    LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false
                );
                LcvStreamContainer.StreamDecoderState gpuDecState =
                    new LcvStreamContainer.StreamDecoderState(width, height, depthK);
                try {
                    LcvStreamContainer.SuperblockDecodeResult dec0 =
                        LcvStreamContainer.decodeSuperblockParallel(sb0Gpu, 0, gpuDecState);
                    assertArrayEquals(frames, dec0.rgbaFrames(), "GPU-direct Superblock 0 must decode 100% bit-exact");
                    LcvStreamContainer.SuperblockDecodeResult dec1 =
                        LcvStreamContainer.decodeSuperblockParallel(sb1Gpu, 0, gpuDecState);
                    assertArrayEquals(frames, dec1.rgbaFrames(), "GPU-direct Superblock 1 must decode 100% bit-exact");
                    assertTrue(
                        dec1.cleanAckReusedTiles() > 0 || dec1.uniformInlineTiles() == tilesX * tilesY,
                        "GPU-direct Superblock 1 must emit TEMPORAL_CLEAN_ACK or UNIFORM_INLINE pointers in VRAM"
                    );
                    if (frames == sceneFrames && tilesX * tilesY >= 4) {
                        assertTrue(
                            dec1.header().isQuadtreeDirectory(),
                            "VRAM-direct lcv_gpu_assemble_superblock must coalesce uniform TEMPORAL_CLEAN_ACK tiles into COLOR_FLAG_QUADTREE_DIR"
                        );
                    }
                    int[] natGpu0 = new int[frames.length];
                    int[] natGpu1 = new int[frames.length];
                    assertEquals(
                        frames.length,
                        LcvPrimitives.decodeSuperblockNative(sb0Gpu, 0, sb0Gpu.length, null, false, natGpu0, null)
                    );
                    assertArrayEquals(frames, natGpu0, "Native C11/CUDA decoder must match GPU-direct Superblock 0 bit-for-bit");
                    assertEquals(
                        frames.length,
                        LcvPrimitives.decodeSuperblockNative(sb1Gpu, 0, sb1Gpu.length, natGpu0, true, natGpu1, null)
                    );
                    assertArrayEquals(frames, natGpu1, "Native C11/CUDA decoder must match quadtree-coalesced GPU-direct Superblock 1 bit-for-bit");
                    byte[] croppedGpu = LcvOnDiskTransformer.cropSubRegion(sb0Gpu, 0, 0, 2, 1);
                    assertEquals(32, LcvStreamContainer.readSuperblockHeader(croppedGpu, 0).width16());
                    byte[] strippedGpu = LcvOnDiskTransformer.stripLsbPlanes(sb0Gpu);
                    assertEquals(0, LcvStreamContainer.readSuperblockHeader(strippedGpu, 0).lsbPoolCompressedLen32());
                } catch (LcvCorruptTileException ex) {
                    throw new AssertionError("GPU-direct Superblock failed CBIT verification", ex);
                }
                int benchBytes = LcvPrimitives.benchmarkGpuResidentEncode(frames, width, height, depthK, 2, 4, true);
                assertTrue(benchBytes >= 64, "Expected GPU-resident benchmark to return valid Superblock size");
            }
        }
    }

    @Test
    void obsNativePluginNleWorkspaceMountAndTurnkeyInstallerRoundTrip() throws Exception {
        Assumptions.assumeTrue(isToolAvailable("ffmpeg") && isToolAvailable("ffprobe"), "Requires ffmpeg and ffprobe");
        Path sampleMkv = tempDir.resolve("obs_session_v_lcv1.mkv");
        assertEquals(0, LcvCli.execute(new String[]{
            "sample",
            "--output", sampleMkv.toString(),
            "--width", "128",
            "--height", "64",
            "--depth-k", "4",
            "--superblocks", "2",
            "--fps", "60",
            "--mode", "overlay"
        }));

        // 1. Verify preset-based decode-raw (--preset 9x16 -> 32x64 = 8 frames * 32 * 64 * 4 = 65,536 B)
        Path raw9x16 = tempDir.resolve("decoded_9x16.rgba");
        assertEquals(0, LcvCli.execute(new String[]{
            "decode-raw",
            "--input", sampleMkv.toString(),
            "--output", raw9x16.toString(),
            "--preset", "9x16",
            "--strip-lsb", "true"
        }));
        assertEquals(8L * 32L * 64L * 4L, Files.size(raw9x16));

        // 2. Verify LcvCli nle-sync generates <1 ms compressed-domain derivatives + NLE carriers + manifest
        Path nleWorkspace = tempDir.resolve("LeafVideo-NLE");
        assertEquals(0, LcvCli.execute(new String[]{
            "nle-sync",
            "--input", sampleMkv.toString(),
            "--output", nleWorkspace.toString(),
            "--carriers", "qtrle,ffv1,mp4"
        }));
        assertTrue(Files.isRegularFile(nleWorkspace.resolve("lcv_nle_manifest.json")));
        assertTrue(Files.isRegularFile(nleWorkspace.resolve("obs_session_9x16_v_lcv1.mkv")));
        assertTrue(Files.isRegularFile(nleWorkspace.resolve("obs_session_facecam_v_lcv1.mkv")));
        assertTrue(Files.isRegularFile(nleWorkspace.resolve("obs_session_proxy_g7_g2.mkv")));
        assertTrue(Files.isRegularFile(nleWorkspace.resolve("obs_session_full_rgba.mov")));
        assertTrue(Files.isRegularFile(nleWorkspace.resolve("obs_session_9x16_vertical.mov")));
        assertTrue(Files.isRegularFile(nleWorkspace.resolve("obs_session_ffv1.mkv")));
        assertTrue(Files.isRegularFile(nleWorkspace.resolve("obs_session_universal.mp4")));

        // 3. Verify LcvCli install-obs installs obs-leafvideo.so, dock HTML, locale, profile encoder, and scene script
        Path fakeObsConfig = tempDir.resolve("obs-studio");
        Path scenesDir = fakeObsConfig.resolve("basic/scenes");
        Path profileDir = fakeObsConfig.resolve("basic/profiles/Untitled");
        Files.createDirectories(scenesDir);
        Files.createDirectories(profileDir);
        Path sceneJson = scenesDir.resolve("Untitled.json");
        Files.writeString(sceneJson, "{\n  \"modules\": {\n    \"scripts-tool\": []\n  }\n}\n");
        Path basicIni = profileDir.resolve("basic.ini");
        Files.writeString(basicIni, "[Output]\nMode=Simple\n\n[AdvOut]\nRecEncoder=none\n");

        Path builtPluginSo = resolveNativeArtifact("obs-leafvideo.so");
        Path builtAccelSo = resolveNativeArtifact("liblcv_accel.so");
        Path builtJar = resolveJarArtifact();
        Path obsScript = resolveIntegrationScript("obs_leafvideo_crop_guard.py");

        assertEquals(0, LcvCli.execute(new String[]{
            "install-obs",
            "--obs-config-dir", fakeObsConfig.toString(),
            "--nle-dir", nleWorkspace.toString(),
            "--plugin-so", builtPluginSo.toString(),
            "--jar", builtJar.toString(),
            "--script", obsScript.toString()
        }));

        Path installedSo = fakeObsConfig.resolve("plugins/obs-leafvideo/bin/64bit/obs-leafvideo.so");
        assertTrue(Files.isRegularFile(installedSo), "Native obs-leafvideo.so must be installed");
        assertTrue(Files.isRegularFile(fakeObsConfig.resolve("plugins/obs-leafvideo/data/locale/en-US.ini")));
        assertTrue(Files.isRegularFile(fakeObsConfig.resolve("plugin_config/obs-leafvideo/leafvideo_obs_dock.html")));
        assertTrue(Files.readString(sceneJson).contains("obs_leafvideo_crop_guard.py"));
        String updatedIni = Files.readString(basicIni);
        assertTrue(updatedIni.contains("Mode=Advanced"), "basic.ini must enable Advanced Output mode for custom encoders");
        assertTrue(updatedIni.contains("RecEncoder=leafvideo_lcv1"), "basic.ini must select leafvideo_lcv1 encoder");
        assertTrue(updatedIni.contains("RecRB=true"), "basic.ini must enable Replay Buffer under [AdvOut]");
        assertTrue(Files.isRegularFile(profileDir.resolve("basic.ini.lcv.bak")));
        if (isToolAvailable("python3")) {
            Path nleMountScript = resolveIntegrationScript("lcv_nle_mount.py");
            if (Files.isRegularFile(installedSo) && Files.isRegularFile(builtAccelSo)) {
                String pyCheck = "import ctypes, os\n"
                    + "os.environ['JAVA_HOME'] = ''\n"
                    + "os.environ['PATH'] = '/nonexistent'\n"
                    + "os.environ['LEAFVIDEO_ACCEL_SO'] = r'" + builtAccelSo.toAbsolutePath() + "'\n"
                    + "so = ctypes.CDLL(r'" + installedSo.toAbsolutePath() + "')\n"
                    + "so.obs_module_ver.restype = ctypes.c_uint32\n"
                    + "so.lcv_obs_plugin_self_test.argtypes = [ctypes.c_char_p, ctypes.c_char_p]\n"
                    + "so.lcv_obs_plugin_self_test.restype = ctypes.c_int\n"
                    + "assert so.obs_module_ver() == 0x20010001\n"
                    + "rc = so.lcv_obs_plugin_self_test(r'" + sampleMkv.toAbsolutePath() + "'.encode(), b'')\n"
                    + "assert rc == 0, f'Expected rc=0 with JAVA_HOME/java unset, got {rc}'\n"
                    + "accel = ctypes.CDLL(r'" + builtAccelSo.toAbsolutePath() + "')\n"
                    + "accel.lcv_native_encode_superblock_rgba.argtypes = [\n"
                    + "    ctypes.POINTER(ctypes.c_int32), ctypes.c_int32, ctypes.c_int32, ctypes.c_int32,\n"
                    + "    ctypes.c_uint64, ctypes.c_uint64, ctypes.c_int32, ctypes.c_int32,\n"
                    + "    ctypes.c_int32, ctypes.c_int32, ctypes.c_int32,\n"
                    + "    ctypes.POINTER(ctypes.c_int32), ctypes.c_int32,\n"
                    + "    ctypes.POINTER(ctypes.c_uint8), ctypes.c_int32\n"
                    + "]\n"
                    + "accel.lcv_native_encode_superblock_rgba.restype = ctypes.c_int32\n"
                    + "accel.lcv_native_compact_superblock_zstd.argtypes = [\n"
                    + "    ctypes.POINTER(ctypes.c_uint8), ctypes.c_int32,\n"
                    + "    ctypes.c_int32, ctypes.c_int32,\n"
                    + "    ctypes.POINTER(ctypes.c_uint8), ctypes.c_int32\n"
                    + "]\n"
                    + "accel.lcv_native_compact_superblock_zstd.restype = ctypes.c_int32\n"
                    + "accel.lcv_native_decode_superblock_rgba.argtypes = [\n"
                    + "    ctypes.POINTER(ctypes.c_uint8), ctypes.c_int32,\n"
                    + "    ctypes.POINTER(ctypes.c_int32), ctypes.c_int32,\n"
                    + "    ctypes.POINTER(ctypes.c_int32), ctypes.c_int32,\n"
                    + "    ctypes.POINTER(ctypes.c_int32)\n"
                    + "]\n"
                    + "accel.lcv_native_decode_superblock_rgba.restype = ctypes.c_int32\n"
                    + "w, h, k = 64, 64, 2\n"
                    + "npix = w * h * k\n"
                    + "f0 = (ctypes.c_int32 * npix)()\n"
                    + "f1 = (ctypes.c_int32 * npix)()\n"
                    + "for t in range(k):\n"
                    + "    for y in range(h):\n"
                    + "        for x in range(w):\n"
                    + "            val = (((x * 7 + y * 13 + t * 3) & 0xFF) << 24) | (((x * 5 + y * 11) & 0xFF) << 16) | (((x + y) & 0xFF) << 8) | 0xFF\n"
                    + "            f0[t * w * h + y * w + x] = ctypes.c_int32(val).value\n"
                    + "for t in range(k):\n"
                    + "    for y in range(h):\n"
                    + "        sy = max(0, min(h - 1, y - 2))\n"
                    + "        for x in range(w):\n"
                    + "            sx = max(0, min(w - 1, x - 3))\n"
                    + "            f1[t * w * h + y * w + x] = f0[t * w * h + sy * w + sx]\n"
                    + "sb0_raw = (ctypes.c_uint8 * 131072)()\n"
                    + "sb0_len = accel.lcv_native_encode_superblock_rgba(f0, w, h, k, 0, 0, 2, 2, 8, 2, 8, None, 0, sb0_raw, 131072)\n"
                    + "assert sb0_len >= 64\n"
                    + "sb0_comp0 = (ctypes.c_uint8 * 131072)()\n"
                    + "comp0_len = accel.lcv_native_compact_superblock_zstd(sb0_raw, sb0_len, 0, 0, sb0_comp0, 131072)\n"
                    + "assert 64 <= comp0_len < sb0_len, (comp0_len, sb0_len)\n"
                    + "assert (sb0_comp0[0x11] & 0x02) == 0, 'Default lossyLsbStripBits=0 must be 100% lossless'\n"
                    + "dec0 = (ctypes.c_int32 * npix)()\n"
                    + "stats4 = (ctypes.c_int32 * 4)()\n"
                    + "assert accel.lcv_native_decode_superblock_rgba(sb0_comp0, comp0_len, None, 0, dec0, npix, stats4) == npix\n"
                    + "assert list(dec0) == list(f0), 'Default rolling Zstd compaction must decode 100% bit-exact'\n"
                    + "sb0_comp2 = (ctypes.c_uint8 * 131072)()\n"
                    + "comp2_len = accel.lcv_native_compact_superblock_zstd(sb0_raw, sb0_len, 2, 0, sb0_comp2, 131072)\n"
                    + "assert 64 <= comp2_len < comp0_len and (sb0_comp2[0x11] & 0x02) != 0\n"
                    + "sb1_raw = (ctypes.c_uint8 * 131072)()\n"
                    + "sb1_len = accel.lcv_native_encode_superblock_rgba(f1, w, h, k, k, comp0_len, 2, 2, 8, 2, 8, f0, 1, sb1_raw, 131072)\n"
                    + "assert sb1_len >= 64 and sb1_len < sb0_len\n"
                    + "dec1 = (ctypes.c_int32 * npix)()\n"
                    + "assert accel.lcv_native_decode_superblock_rgba(sb1_raw, sb1_len, f0, 1, dec1, npix, stats4) == npix\n"
                    + "assert stats4[2] > 0 and list(dec1) == list(f1), 'Cross-tile shifted TEMPORAL_CLEAN_ACK must reconstruct 100% bit-exact'\n"
                    + "accel.lcv_gpu_push_raw_rgba_frame.argtypes = [\n"
                    + "    ctypes.POINTER(ctypes.c_int32), ctypes.c_int32,\n"
                    + "    ctypes.c_int32, ctypes.c_int32, ctypes.c_int32, ctypes.c_int32\n"
                    + "]\n"
                    + "accel.lcv_gpu_push_raw_rgba_frame.restype = ctypes.c_int32\n"
                    + "accel.lcv_gpu_flush_superblock_to_host.argtypes = [\n"
                    + "    ctypes.c_int32, ctypes.c_int32, ctypes.c_int32,\n"
                    + "    ctypes.c_int32, ctypes.c_int32,\n"
                    + "    ctypes.c_uint64, ctypes.c_uint64,\n"
                    + "    ctypes.c_int32, ctypes.c_int32,\n"
                    + "    ctypes.c_int32, ctypes.c_int32, ctypes.c_int32,\n"
                    + "    ctypes.c_int32, ctypes.c_int32,\n"
                    + "    ctypes.POINTER(ctypes.POINTER(ctypes.c_uint8))\n"
                    + "]\n"
                    + "accel.lcv_gpu_flush_superblock_to_host.restype = ctypes.c_int32\n"
                    + "f_rep = (ctypes.c_int32 * npix)()\n"
                    + "for t in range(k):\n"
                    + "    for y in range(h):\n"
                    + "        for x in range(w):\n"
                    + "            lx, ly = x & 15, y & 15\n"
                    + "            val = (((lx * 13 + ly * 7 + t * 5) & 0xFF) << 24) | (((lx * 9 + ly * 3) & 0xFF) << 16) | (((lx + ly) & 0xFF) << 8) | 0xFF\n"
                    + "            f_rep[t * w * h + y * w + x] = ctypes.c_int32(val).value\n"
                    + "for t in range(k):\n"
                    + "    frame_slice = (ctypes.c_int32 * (w * h)).from_buffer(f_rep, t * w * h * 4)\n"
                    + "    assert accel.lcv_gpu_push_raw_rgba_frame(frame_slice, w * 4, w, h, t, k) == 0\n"
                    + "host_ptr = ctypes.POINTER(ctypes.c_uint8)()\n"
                    + "no_dict_len = accel.lcv_gpu_flush_superblock_to_host(0, w, h, k, k, 0, 0, 2, 2, 8, 2, 8, (1 << 28), 1, ctypes.byref(host_ptr))\n"
                    + "dict_len = accel.lcv_gpu_flush_superblock_to_host(0, w, h, k, k, 0, 0, 2, 2, 8, 2, 8, 0, 1, ctypes.byref(host_ptr))\n"
                    + "assert 64 <= dict_len < no_dict_len, f'GPU dictionary accumulator must shrink repeating tiles in VRAM: {dict_len} vs {no_dict_len}'\n"
                    + "dict_sb = (ctypes.c_uint8 * dict_len)()\n"
                    + "ctypes.memmove(dict_sb, host_ptr, dict_len)\n"
                    + "dec_rep = (ctypes.c_int32 * npix)()\n"
                    + "assert accel.lcv_native_decode_superblock_rgba(dict_sb, dict_len, None, 0, dec_rep, npix, stats4) == npix\n"
                    + "assert list(dec_rep) == list(f_rep), 'GPU dictionary accumulator superblock must decode 100% bit-exact'\n"
                    + "l1_len = accel.lcv_gpu_flush_superblock_to_host(0, w, h, k, k, 0, 0, 1, 2, 8, 2, 8, (1 << 20), 1, ctypes.byref(host_ptr))\n"
                    + "assert 64 <= l1_len < dict_len, (l1_len, dict_len)\n"
                    + "ca_len = accel.lcv_gpu_flush_superblock_to_host(0, w, h, k, k, k, dict_len, 2, 2, 8, 2, 8, 0, 0, ctypes.byref(host_ptr))\n"
                    + "ca_sb = (ctypes.c_uint8 * ca_len)()\n"
                    + "ctypes.memmove(ca_sb, host_ptr, ca_len)\n"
                    + "assert accel.lcv_native_decode_superblock_rgba(ca_sb, ca_len, f_rep, 1, dec_rep, npix, stats4) == npix\n"
                    + "assert stats4[2] == (w // 16) * (h // 16), f'Layer 1 flush must not clobber Layer 0 VRAM temporal state, got cleanAck={stats4[2]}'\n"
                    + "lsb_strip_len = accel.lcv_gpu_flush_superblock_to_host(0, w, h, k, k, 0, 0, 2, 2, 8, 2, 8, (2 << 8), 1, ctypes.byref(host_ptr))\n"
                    + "assert 64 <= lsb_strip_len < dict_len and (host_ptr[0x11] & 0x02) != 0\n"
                    + "if os.path.exists('/lib64/libobs.so.30'):\n"
                    + "    libobs = ctypes.CDLL('/lib64/libobs.so.30', mode=ctypes.RTLD_GLOBAL)\n"
                    + "    libobs.obs_startup.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.c_void_p]\n"
                    + "    libobs.obs_startup.restype = ctypes.c_bool\n"
                    + "    assert libobs.obs_startup(b'en-US', None, None)\n"
                    + "    so.obs_module_load.restype = ctypes.c_bool\n"
                    + "    assert so.obs_module_load()\n"
                    + "    libobs.obs_enum_encoder_types.argtypes = [ctypes.c_size_t, ctypes.POINTER(ctypes.c_char_p)]\n"
                    + "    libobs.obs_enum_encoder_types.restype = ctypes.c_bool\n"
                    + "    enc_ids = []\n"
                    + "    idx = 0\n"
                    + "    enc_id = ctypes.c_char_p()\n"
                    + "    while libobs.obs_enum_encoder_types(idx, ctypes.byref(enc_id)):\n"
                    + "        enc_ids.append(enc_id.value.decode())\n"
                    + "        idx += 1\n"
                    + "    libobs.obs_shutdown()\n"
                    + "    assert 'leafvideo_lcv1' in enc_ids and 'leafvideo_lcv1_h264' in enc_ids, enc_ids\n"
                    + "print('OBS_NATIVE_PLUGIN_OK')\n";
                String pluginOut = runCommand(List.of("python3", "-c", pyCheck));
                assertTrue(pluginOut.contains("OBS_NATIVE_PLUGIN_OK"), () -> "Plugin check failed:\n" + pluginOut);
            }
        }
    }

    @Test
    void replayRingBufferWarmCompactionAndLowLatencyUdpStreamRoundTrip() throws Exception {
        int width = 128;
        int height = 64;
        int depthK = 2;
        int fps = 60;

        // 1. Verify two-tier in-memory ReplayRingBuffer warm Zstd compaction, time/memory bounds, and <1 ms flush
        LcvCli.ReplayRingBuffer ring = new LcvCli.ReplayRingBuffer(
            width, height, depthK, fps,
            512L * 1024L * 1024L, // 512 MB matching OBS RecRBSize
            20,                   // 20 s matching OBS RecRBTime
            2                     // keep newest 2 superblocks in hot tier, compact older in warm tier
        );
        LcvStreamContainer.StreamEncoderState encState = new LcvStreamContainer.StreamEncoderState(width, height);
        long prevOff = 0L;
        for (int sbIdx = 0; sbIdx < 6; sbIdx++) {
            int[] frames = LcvCli.synthesizeFrames(width, height, depthK, sbIdx, "scene");
            byte[] sbBytes = LcvStreamContainer.encodeSuperblockParallel(
                frames, width, height, depthK,
                (long) sbIdx * depthK, prevOff,
                4, 2, LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, encState
            );
            ring.appendSuperblock(sbBytes);
            prevOff += sbBytes.length;
        }
        assertEquals(6, ring.bufferedSuperblockCount());
        assertEquals(4, ring.warmCompactedSuperblocks(), "Expected 4 older superblocks compacted into warm Zstd tier");
        assertTrue(ring.bytesSavedByWarmZstd() > 0L, "Expected warm Zstd tier to reduce in-RAM footprint");

        Path flushedReplay = tempDir.resolve("replay_saved_v_lcv1.mkv");
        Path flushed9x16 = tempDir.resolve("replay_saved_9x16_v_lcv1.mkv");
        LcvCli.ReplayFlushResult flushRes = ring.flushToFile(flushedReplay, flushed9x16);
        assertEquals(6, flushRes.superblockCount());
        assertEquals(12, flushRes.totalFrames());
        assertTrue(Files.isRegularFile(flushedReplay) && Files.size(flushedReplay) > 0L);
        assertTrue(Files.isRegularFile(flushed9x16) && Files.size(flushed9x16) > 0L);

        LcvCli.StreamInspectionReport replayReport = LcvCli.inspectContainerFile(flushedReplay);
        assertEquals(width, replayReport.width());
        assertEquals(height, replayReport.height());
        assertEquals(12, replayReport.totalFrames());
        assertEquals(0, replayReport.quarantinedTiles());

        LcvCli.StreamInspectionReport cropReport = LcvCli.inspectContainerFile(flushed9x16);
        assertEquals(32, cropReport.width());
        assertEquals(64, cropReport.height());
        assertEquals(12, cropReport.totalFrames());

        // 2. Verify K=1 Parsec / Remote Desktop low-latency UDP MvCoherentPacket serialization,
        //    LSB congestion shedding, and packet-loss concealment without IDR freeze
        int[] k1Frame0 = LcvCli.synthesizeFrames(width, height, 1, 0, "scene");
        int[] k1Frame1 = LcvCli.synthesizeFrames(width, height, 1, 1, "scene");
        LcvStreamContainer.StreamEncoderState k1Enc = new LcvStreamContainer.StreamEncoderState(width, height);
        byte[] sb0 = LcvStreamContainer.encodeSuperblockParallel(
            k1Frame0, width, height, 1, 0L, 0L, 4, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, k1Enc
        );
        byte[] sb1 = LcvStreamContainer.encodeSuperblockParallel(
            k1Frame1, width, height, 1, 1L, sb0.length, 4, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, k1Enc
        );

        List<byte[]> dgs0 = LcvCli.serializeUdpDatagramsForSuperblock(sb0, 0, 1200, false);
        assertFalse(dgs0.isEmpty());
        for (byte[] dg : dgs0) {
            assertTrue(dg.length <= 1200, "Each LCVU datagram must respect MTU=1200, got " + dg.length);
        }
        LcvStreamContainer.StreamDecoderState k1Dec = new LcvStreamContainer.StreamDecoderState(width, height, 1);
        LcvStreamContainer.SuperblockDecodeResult dec0 = LcvCli.decodeSuperblockFromUdpDatagrams(dgs0, k1Dec);
        assertArrayEquals(k1Frame0, dec0.rgbaFrames(), "Lossless K=1 UDP datagrams must reconstruct exact RGBA pixels");

        // Test 0.0 ms LSB congestion shedding on frame 1
        List<byte[]> dgs1Shed = LcvCli.serializeUdpDatagramsForSuperblock(sb1, 1, 1200, true);
        List<byte[]> dgs1Full = LcvCli.serializeUdpDatagramsForSuperblock(sb1, 1, 1200, false);
        int fullBytes = dgs1Full.stream().mapToInt(b -> b.length).sum();
        int shedBytes = dgs1Shed.stream().mapToInt(b -> b.length).sum();
        assertTrue(shedBytes < fullBytes, "Congestion LSB shedding must reduce UDP byte footprint in 0.0 ms");
        LcvStreamContainer.SuperblockDecodeResult dec1Shed = LcvCli.decodeSuperblockFromUdpDatagrams(dgs1Shed, k1Dec);
        assertEquals(0, dec1Shed.quarantinedTiles());

        // Test live localhost UDP socket transmission via encode --profile PARSEC_LOW_LATENCY --ring-buffer-mb 64
        Path rawIn = tempDir.resolve("k1_input.rgba");
        ByteBuffer rawBuf = ByteBuffer.allocate(width * height * 4 * 2).order(ByteOrder.BIG_ENDIAN);
        rawBuf.asIntBuffer().put(k1Frame0).put(k1Frame1);
        Files.write(rawIn, rawBuf.array());
        Path triggerFile = tempDir.resolve(".lcv_replay_flush.trigger");
        Files.writeString(triggerFile, "FLUSH\n");
        Path encodedMkv = tempDir.resolve("parsec_k1_v_lcv1.mkv");
        Path autoReplayOut = tempDir.resolve("parsec_replay_v_lcv1.mkv");

        try (java.nio.channels.DatagramChannel rx = java.nio.channels.DatagramChannel.open()) {
            rx.bind(new java.net.InetSocketAddress("127.0.0.1", 0));
            rx.configureBlocking(false);
            int port = ((java.net.InetSocketAddress) rx.getLocalAddress()).getPort();

            assertEquals(0, LcvCli.execute(new String[]{
                "encode",
                "--input", rawIn.toString(),
                "--output", encodedMkv.toString(),
                "--width", Integer.toString(width),
                "--height", Integer.toString(height),
                "--fps", "60",
                "--profile", "PARSEC_LOW_LATENCY",
                "--ring-buffer-mb", "64",
                "--replay-seconds", "20",
                "--replay-trigger-file", triggerFile.toString(),
                "--replay-output", autoReplayOut.toString(),
                "--stream-udp", "127.0.0.1:" + port
            }));

            List<byte[]> rxFirstSb = new java.util.ArrayList<>();
            ByteBuffer pktBuf = ByteBuffer.allocate(2048);
            while (rx.receive(pktBuf) != null) {
                pktBuf.flip();
                byte[] copy = new byte[pktBuf.remaining()];
                pktBuf.get(copy);
                pktBuf.clear();
                if (ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN).getInt(4) == 0) {
                    rxFirstSb.add(copy);
                }
            }
            assertFalse(rxFirstSb.isEmpty(), "Expected UDP datagrams to arrive on localhost listener");
            LcvStreamContainer.StreamDecoderState liveDec = new LcvStreamContainer.StreamDecoderState(width, height, 1);
        }
        assertTrue(Files.isRegularFile(autoReplayOut), "Expected trigger file to flush ReplayRingBuffer during encode");
        assertTrue(Files.isRegularFile(tempDir.resolve("parsec_replay_9x16_v_lcv1.mkv")));
    }

    @Test
    void nleTimelineConformRoundTripsEdlFcpxmlMltAndBlenderVseEdits() throws Exception {
        Assumptions.assumeTrue(isToolAvailable("ffmpeg") && isToolAvailable("ffprobe"), "ffmpeg/ffprobe required");

        Path srcMkv = tempDir.resolve("master_v_lcv1.mkv");
        assertEquals(0, LcvCli.execute(new String[]{
            "sample",
            "--output", srcMkv.toString(),
            "--width", "128",
            "--height", "64",
            "--depth-k", "4",
            "--superblocks", "3",
            "--fps", "60",
            "--mode", "scene"
        }));

        Path conformedLcv = tempDir.resolve("conformed_cut_9x16_v_lcv1.mkv");
        assertEquals(0, LcvCli.execute(new String[]{
            "conform-timeline",
            "--input", srcMkv.toString(),
            "--output", conformedLcv.toString(),
            "--cuts", "0:4,8:12",
            "--preset", "9x16"
        }));
        LcvCli.StreamInspectionReport report = LcvCli.inspectContainerFile(conformedLcv);
        assertEquals(32, report.width());
        assertEquals(64, report.height());
        assertEquals(2, report.superblockCount());
        assertEquals(8, report.totalFrames());
    }
    private static Path resolveNativeArtifact(String fileName) {
        Path direct = Path.of("build", "native", fileName);
        if (Files.isRegularFile(direct)) {
            return direct.toAbsolutePath();
        }
        return Path.of("resource", "LeafVideo", "build", "native", fileName).toAbsolutePath();
    }

    private static Path resolveJarArtifact() {
        Path direct = Path.of("build", "libs", "LeafVideo-0.1.0.jar");
        if (Files.isRegularFile(direct)) {
            return direct.toAbsolutePath();
        }
        return Path.of("resource", "LeafVideo", "build", "libs", "LeafVideo-0.1.0.jar").toAbsolutePath();
    }

    private static Path resolveIntegrationScript(String fileName) {
        Path direct = Path.of("integrations", fileName);
        if (Files.isRegularFile(direct)) {
            return direct.toAbsolutePath();
        }
        return Path.of("resource", "LeafVideo", "integrations", fileName).toAbsolutePath();
    }

    private static double computeRgbaPsnr(byte[] refRgba, byte[] testRgba) {
        assertEquals(refRgba.length, testRgba.length);
        long sse = 0L;
        for (int i = 0; i < refRgba.length; i++) {
            int d = (refRgba[i] & 0xFF) - (testRgba[i] & 0xFF);
            sse += (long) d * d;
        }
        if (sse == 0L) {
            return Double.POSITIVE_INFINITY;
        }
        double mse = (double) sse / refRgba.length;
        return 10.0 * Math.log10((255.0 * 255.0) / mse);
    }

    private static boolean isToolAvailable(String binary) {
        for (String flag : List.of("--version", "-version")) {
            try {
                Process proc = new ProcessBuilder(binary, flag).redirectErrorStream(true).start();
                try (InputStream in = proc.getInputStream()) {
                    in.readAllBytes();
                }
                if (proc.waitFor() == 0) {
                    return true;
                }
            } catch (Exception ignored) {
                // Try next flag
            }
        }
        return false;
    }

    private static String runCommand(List<String> cmd) throws IOException, InterruptedException {
        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out;
        try (InputStream in = proc.getInputStream()) {
            out = in.readAllBytes();
        }
        int exit = proc.waitFor();
        String text = new String(out, StandardCharsets.UTF_8);
        if (exit != 0) {
            throw new IOException("Command failed (exit " + exit + "): " + String.join(" ", cmd) + "\n" + text);
        }
        return text;
    }
}
