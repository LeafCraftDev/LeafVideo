package io.github.dailystruggle.leafvideo;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

/**
 * Command-line interface and zero-copy pipe bridge for {@code :leaf-video} ({@code LCV-ICD-1.0})
 * enabling direct integration with external media players ({@code ffplay}, {@code mpv}, {@code GStreamer}),
 * capture suites ({@code OBS Studio}), and non-linear video editors ({@code Blender VSE},
 * {@code FFmpeg}, {@code DaVinci Resolve}).
 *
 * <p>Supports bounded {@code O(1)}-memory streaming raw {@code RGBA} and {@code ffmpeg} video ingest
 * into {@code .mkv} ({@code CodecID = "V_LCV1"}) or standalone {@code .lcv} streams, sub-millisecond
 * compressed-domain {@link LcvOnDiskTransformer#cropSubRegion} and {@link LcvOnDiskTransformer#stripLsbPlanes},
 * crash-tolerant {@code SEC-DED (72, 64)} stream inspection, raw {@code RGBA} decoding to {@code stdout}/files,
 * lossless NLE carrier export ({@code qtrle} {@code .mov}, {@code ffv1} {@code .mkv}, {@code h264} {@code .mp4}),
 * and live {@code ffplay} playback.
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
public final class LcvCli {

    private LcvCli() {}

    /**
     * Structured inspection report for a {@code .mkv} ({@code V_LCV1}) or {@code .lcv} file.
     */
    public record StreamInspectionReport(
        int width,
        int height,
        int depthK,
        int fps,
        int superblockCount,
        int totalFrames,
        long fileBytes,
        boolean lsbStripped,
        int healedSingleBitPointers,
        int quarantinedTiles,
        int cleanAckReusedTiles,
        int uniformInlineTiles
    ) {
        public String toJson() {
            return String.format(
                Locale.ROOT,
                "{\"width\":%d,\"height\":%d,\"depthK\":%d,\"fps\":%d,\"superblockCount\":%d,"
                    + "\"totalFrames\":%d,\"fileBytes\":%d,\"lsbStripped\":%s,"
                    + "\"healedSingleBitPointers\":%d,\"quarantinedTiles\":%d,"
                    + "\"cleanAckReusedTiles\":%d,\"uniformInlineTiles\":%d}",
                width, height, depthK, fps, superblockCount, totalFrames, fileBytes,
                lsbStripped, healedSingleBitPointers, quarantinedTiles,
                cleanAckReusedTiles, uniformInlineTiles
            );
        }
    }

    /**
     * Result of a compressed-domain on-disk transformation ({@code crop} or {@code strip-lsb}).
     */
    public record TransformResult(
        int srcWidth,
        int srcHeight,
        int dstWidth,
        int dstHeight,
        int superblockCount,
        int totalFrames,
        long srcBytes,
        long dstBytes,
        double transformElapsedMs
    ) {}

    /**
     * Result of flushing an in-memory {@link ReplayRingBuffer} to disk.
     */
    public record ReplayFlushResult(
        int superblockCount,
        int totalFrames,
        long ringMemoryBytes,
        long bytesSavedByWarmZstd,
        int warmCompactedSuperblocks,
        long outputFileBytes,
        double flushElapsedMs
    ) {}

    /**
     * Bounded two-tier in-memory circular Replay Buffer of encoded {@code LCV_V001} Superblocks.
     *
     * <p>Recent superblocks sit in the hot uncompressed-pool tier ({@code OBS_REALTIME} {@code 0} Zstd
     * overhead), while superblocks older than {@code hotTierSuperblocks} are automatically compacted
     * in place in RAM via {@link LcvOnDiskTransformer#wrapZstdPools(byte[], int)} without decoding pixels.
     * Enforces both OBS {@code Maximum Replay Time} ({@code RecRBTime} seconds) and {@code Maximum Memory}
     * ({@code RecRBSize} MB) limits, and flushes clean-ack-aligned replay clips in {@code < 1 ms}.
     */
    public static final class ReplayRingBuffer {
        private static final class RingEntry {
            final int sbIndex;
            byte[] sbBytes;
            boolean warmCompacted;

            RingEntry(int sbIndex, byte[] sbBytes, boolean warmCompacted) {
                this.sbIndex = sbIndex;
                this.sbBytes = sbBytes;
                this.warmCompacted = warmCompacted;
            }
        }

        private final int width;
        private final int height;
        private final int depthK;
        private final int fps;
        private final long maxMemoryBytes;
        private final int maxReplaySeconds;
        private final int hotTierSuperblocks;
        private final List<RingEntry> entries = new ArrayList<>();
        private long currentMemoryBytes;
        private long bytesSavedByWarmZstd;
        private int warmCompactedSuperblocks;
        private int totalAppendedSuperblocks;

        public ReplayRingBuffer(
            int width, int height, int depthK, int fps,
            long maxMemoryBytes, int maxReplaySeconds, int hotTierSuperblocks
        ) {
            this.width = width;
            this.height = height;
            this.depthK = Math.max(1, depthK);
            this.fps = Math.max(1, fps);
            this.maxMemoryBytes = Math.max(64L * 1024L, maxMemoryBytes);
            this.maxReplaySeconds = Math.max(1, maxReplaySeconds);
            this.hotTierSuperblocks = Math.max(1, hotTierSuperblocks);
        }
        public void appendSuperblock(byte[] sbBytes) {
            int idx = totalAppendedSuperblocks++;
            byte[] copy = sbBytes.clone();
            entries.add(new RingEntry(idx, copy, false));
            currentMemoryBytes += copy.length;

            int warmBoundary = entries.size() - hotTierSuperblocks;
            for (int i = 0; i < warmBoundary; i++) {
                RingEntry entry = entries.get(i);
                if (!entry.warmCompacted) {
                    byte[] wrapped = LcvOnDiskTransformer.wrapZstdPools(entry.sbBytes);
                    if (wrapped.length < entry.sbBytes.length) {
                        long saved = entry.sbBytes.length - wrapped.length;
                        currentMemoryBytes -= saved;
                        bytesSavedByWarmZstd += saved;
                        entry.sbBytes = wrapped;
                    }
                    entry.warmCompacted = true;
                    warmCompactedSuperblocks++;
                }
            }

            int maxSuperblocksByTime = Math.max(
                1,
                (int) Math.ceil((double) ((long) maxReplaySeconds * fps) / depthK)
            );
            while (entries.size() > 1
                && (currentMemoryBytes > maxMemoryBytes || entries.size() > maxSuperblocksByTime)) {
                RingEntry removed = entries.remove(0);
                currentMemoryBytes -= removed.sbBytes.length;
            }
        }

        public int bufferedSuperblockCount() {
            return entries.size();
        }

        public long currentMemoryBytes() {
            return currentMemoryBytes;
        }

        public long bytesSavedByWarmZstd() {
            return bytesSavedByWarmZstd;
        }

        public int warmCompactedSuperblocks() {
            return warmCompactedSuperblocks;
        }

        public ReplayFlushResult flushToFile(Path outputFile, Path optional9x16OutputFile)
            throws IOException {
            if (entries.isEmpty()) {
                return new ReplayFlushResult(0, 0, currentMemoryBytes, bytesSavedByWarmZstd, warmCompactedSuperblocks, 0L, 0.0);
            }
            int startIdx = 0;
            if (entries.get(0).sbIndex % LcvStreamContainer.MAX_CLEAN_ACK_REFRESH != 0) {
                for (int i = 1; i < entries.size(); i++) {
                    if (entries.get(i).sbIndex % LcvStreamContainer.MAX_CLEAN_ACK_REFRESH == 0) {
                        startIdx = i;
                        break;
                    }
                }
            }
            List<byte[]> snapshot = new ArrayList<>(entries.size() - startIdx);
            for (int i = startIdx; i < entries.size(); i++) {
                snapshot.add(entries.get(i).sbBytes);
            }

            long t0 = System.nanoTime();
            if (outputFile.getParent() != null) {
                Files.createDirectories(outputFile.getParent());
            }
            writeContainerOutput(outputFile, snapshot, width, height, fps);

            if (optional9x16OutputFile != null) {
                int srcTilesX = width / LcvPrimitives.TILE_DIM;
                int srcTilesY = height / LcvPrimitives.TILE_DIM;
                int[] bounds = resolveCropBounds(Map.of(), "9x16", srcTilesX, srcTilesY);
                List<byte[]> cropped = new ArrayList<>(snapshot.size());
                for (byte[] sb : snapshot) {
                    cropped.add(LcvOnDiskTransformer.cropSubRegion(sb, bounds[0], bounds[1], bounds[2], bounds[3]));
                }
                if (optional9x16OutputFile.getParent() != null) {
                    Files.createDirectories(optional9x16OutputFile.getParent());
                }
                writeContainerOutput(
                    optional9x16OutputFile,
                    cropped,
                    bounds[2] * LcvPrimitives.TILE_DIM,
                    bounds[3] * LcvPrimitives.TILE_DIM,
                    fps
                );
            }
            double elapsedMs = (System.nanoTime() - t0) / 1_000_000.0;
            return new ReplayFlushResult(
                snapshot.size(),
                snapshot.size() * depthK,
                currentMemoryBytes,
                bytesSavedByWarmZstd,
                warmCompactedSuperblocks,
                Files.size(outputFile),
                elapsedMs
            );
        }
    }

    public static void main(String[] args) {
        try {
            int code = execute(args);
            if (code != 0) {
                System.exit(code);
            }
        } catch (Exception e) {
            System.err.println("[LcvCli] ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    /**
     * Executes an {@code LcvCli} subcommand and returns an exit status code ({@code 0} on success).
     */
    public static int execute(String[] args) throws IOException, InterruptedException, LcvCorruptTileException {
        if (args == null || args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0]) || "help".equals(args[0])) {
            printUsage();
            return 0;
        }
        String subcmd = args[0].toLowerCase(Locale.ROOT);
        Map<String, String> opts = parseOptions(args, 1);

        return switch (subcmd) {
            case "sample", "export-sample" -> runSampleCommand(opts);
            case "encode", "ingest-raw" -> runEncodeCommand(opts);
            case "ingest-dir", "batch-ingest" -> runIngestDirCommand(opts);
            case "crop" -> runCropCommand(opts);
            case "strip-lsb", "proxy" -> runStripLsbCommand(opts);
            case "postprocess", "compact-zstd" -> runPostprocessCommand(opts);
            case "splice", "append-edit" -> runSpliceCommand(opts);
            case "compact", "compact-temporal" -> runCompactTemporalCommand(opts);
            case "inspect", "probe", "heal" -> runInspectCommand(opts);
            case "decode-raw", "decode" -> runDecodeRawCommand(opts);
            case "export-carrier" -> runExportCarrierCommand(opts);
            case "play" -> runPlayCommand(opts);
            case "nle-sync", "mount" -> runNleSyncCommand(opts);
            case "conform-timeline", "conform" -> runConformTimelineCommand(opts);
            case "install-obs" -> runInstallObsCommand(opts);
            case "stream-send", "udp-send" -> runStreamSendCommand(opts);
            case "stream-recv", "udp-recv" -> runStreamRecvCommand(opts);
            default -> {
                System.err.println("Unknown subcommand: " + subcmd);
                printUsage();
                yield 2;
            }
        };
    }

    private static int runSampleCommand(Map<String, String> opts) throws IOException, LcvCorruptTileException {
        Path output = Path.of(opts.getOrDefault("output", "build/lcv-samples/sample_v_lcv1.mkv"));
        int width = alignTileDim(parseIntOpt(opts, "width", 640));
        int height = alignTileDim(parseIntOpt(opts, "height", 368));
        int depthK = parseIntOpt(opts, "depth-k", 8);
        int superblocks = parseIntOpt(opts, "superblocks", 4);
        int fps = parseIntOpt(opts, "fps", 60);
        String mode = opts.getOrDefault("mode", "scene").toLowerCase(Locale.ROOT);
        String profileOpt = opts.getOrDefault("profile", "OBS_REALTIME_LOSSLESS");
        LcvCubeCodec.TuningProfile profile = resolveProfile(profileOpt);
        boolean useZstd = opts.containsKey("rolling-zstd")
            ? Boolean.parseBoolean(opts.get("rolling-zstd"))
            : (opts.containsKey("zstd")
                ? Boolean.parseBoolean(opts.get("zstd"))
                : isDefaultZstdProfile(profileOpt, profile));
        int lossyLsbBits = Math.max(0, Math.min(4, parseIntOpt(opts, "lossy-lsb-bits", 0)));
        boolean stripLsbOnFlush = isCompactMasterPreset(profileOpt) || lossyLsbBits >= 2;

        exportSampleFile(output, width, height, depthK, superblocks, fps, mode, profile, useZstd);
        if (stripLsbOnFlush) {
            stripLsbContainerFile(output, output);
        }
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Exported %s (%dx%d, K=%d, %d superblocks, %d frames @ %d fps, mode=%s, size=%,d B)%n",
            output.toAbsolutePath(), width, height, depthK, superblocks,
            superblocks * depthK, fps, mode, Files.size(output)
        );
        return 0;
    }

    private static int runEncodeCommand(Map<String, String> opts) throws IOException, InterruptedException {
        String outputStr = requireOpt(opts, "output");
        Path output = Path.of(outputStr);
        int width = alignTileDim(parseIntOpt(opts, "width", 640));
        int height = alignTileDim(parseIntOpt(opts, "height", 368));
        String profileOpt = opts.getOrDefault("profile", "OBS_REALTIME_LOSSLESS");
        int defaultDepthK = isLowLatencyParsecPreset(profileOpt) ? 1 : 8;
        int depthK = parseIntOpt(opts, "depth-k", defaultDepthK);
        int fps = parseIntOpt(opts, "fps", 60);
        int roiX = parseIntOpt(opts, "roi-x", (width / LcvPrimitives.TILE_DIM) / 2);
        int roiY = parseIntOpt(opts, "roi-y", (height / LcvPrimitives.TILE_DIM) / 2);
        LcvCubeCodec.TuningProfile profile = resolveProfile(profileOpt);
        boolean useZstd = opts.containsKey("rolling-zstd")
            ? Boolean.parseBoolean(opts.get("rolling-zstd"))
            : (opts.containsKey("zstd")
                ? Boolean.parseBoolean(opts.get("zstd"))
                : isDefaultZstdProfile(profileOpt, profile));
        int lossyLsbBits = Math.max(0, Math.min(4, parseIntOpt(opts, "lossy-lsb-bits", 0)));
        boolean stripLsbOnFlush = isCompactMasterPreset(profileOpt) || lossyLsbBits >= 2;

        int ringBufferMb = parseIntOpt(opts, "ring-buffer-mb", 0);
        int replaySeconds = parseIntOpt(opts, "replay-seconds", 20);
        int hotTierSbs = parseIntOpt(
            opts, "hot-tier-superblocks", Math.max(2, (fps * 3) / Math.max(1, depthK))
        );
        ReplayRingBuffer ringBuffer = ringBufferMb > 0
            ? new ReplayRingBuffer(
                width, height, depthK, fps,
                (long) ringBufferMb * 1024L * 1024L,
                replaySeconds,
                hotTierSbs
            )
            : null;
        Path replayTriggerFile = opts.containsKey("replay-trigger-file")
            ? Path.of(opts.get("replay-trigger-file"))
            : null;
        Path replayOutput = opts.containsKey("replay-output")
            ? Path.of(opts.get("replay-output"))
            : null;
        String streamUdpTarget = opts.getOrDefault("stream-udp", "").trim();
        int streamMtu = parseIntOpt(opts, "stream-mtu", 1200);
        boolean streamShedLsb = Boolean.parseBoolean(opts.getOrDefault("stream-shed-lsb", "false"));

        int sbCount;
        if (opts.containsKey("from-video")) {
            Path videoPath = Path.of(opts.get("from-video"));
            sbCount = streamIngestVideoToFile(
                videoPath, width, height, depthK, fps, roiX, roiY, profile, useZstd, stripLsbOnFlush, output
            );
        } else {
            String inputStr = requireOpt(opts, "input");
            if ("-".equals(inputStr)) {
                sbCount = encodeRawRgbaStreamToFile(
                    System.in, width, height, depthK, fps, roiX, roiY, profile,
                    useZstd, stripLsbOnFlush, output,
                    ringBuffer, replayTriggerFile, replayOutput,
                    streamUdpTarget, streamMtu, streamShedLsb
                );
            } else {
                try (InputStream fileIn = new BufferedInputStream(Files.newInputStream(Path.of(inputStr)), 1 << 20)) {
                    sbCount = encodeRawRgbaStreamToFile(
                        fileIn, width, height, depthK, fps, roiX, roiY, profile,
                        useZstd, stripLsbOnFlush, output,
                        ringBuffer, replayTriggerFile, replayOutput,
                        streamUdpTarget, streamMtu, streamShedLsb
                    );
                }
            }
        }
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Encoded %d superblocks (%dx%d, K=%d @ %d fps) -> %s (%,d B)%n",
            sbCount, width, height, depthK, fps, output.toAbsolutePath(), Files.size(output)
        );
        return 0;
    }

    private static int runStreamSendCommand(Map<String, String> opts) throws IOException {
        Path input = Path.of(requireOpt(opts, "input"));
        String targetStr = requireOpt(opts, "target");
        int mtu = parseIntOpt(opts, "mtu", 1200);
        boolean shedLsb = Boolean.parseBoolean(opts.getOrDefault("shed-lsb", "false"));
        java.net.InetSocketAddress target = parseUdpEndpoint(targetStr);
        if (target == null) {
            throw new IllegalArgumentException("Invalid UDP target endpoint: " + targetStr);
        }
        List<byte[]> sbs = extractSuperblocks(Files.readAllBytes(input));
        int totalDatagrams = 0;
        try (java.nio.channels.DatagramChannel ch = java.nio.channels.DatagramChannel.open()) {
            for (int i = 0; i < sbs.size(); i++) {
                totalDatagrams += transmitSuperblockUdp(ch, target, sbs.get(i), i, mtu, shedLsb);
            }
        }
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Transmitted %d superblocks (%d UDP datagrams, MTU=%d, shedLsb=%s) -> %s%n",
            sbs.size(), totalDatagrams, mtu, shedLsb, target
        );
        return 0;
    }

    private static int runStreamRecvCommand(Map<String, String> opts) throws IOException, LcvCorruptTileException {
        String listenStr = opts.getOrDefault("listen", "127.0.0.1:9999");
        Path output = Path.of(requireOpt(opts, "output"));
        int maxSuperblocks = parseIntOpt(opts, "max-superblocks", 1);
        int timeoutMs = parseIntOpt(opts, "timeout-ms", 5000);
        java.net.InetSocketAddress bindAddr = parseUdpEndpoint(listenStr);
        if (bindAddr == null) {
            throw new IllegalArgumentException("Invalid UDP listen address: " + listenStr);
        }

        Map<Integer, List<byte[]>> bySeq = new LinkedHashMap<>();
        try (java.nio.channels.DatagramChannel ch = java.nio.channels.DatagramChannel.open()) {
            ch.bind(bindAddr);
            ch.socket().setSoTimeout(Math.max(50, timeoutMs));
            byte[] recvBuf = new byte[65536];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                java.net.DatagramPacket pkt = new java.net.DatagramPacket(recvBuf, recvBuf.length);
                try {
                    ch.socket().receive(pkt);
                } catch (java.net.SocketTimeoutException ste) {
                    break;
                }
                int len = pkt.getLength();
                if (len < LCV_UDP_HEADER_BYTES) {
                    continue;
                }
                byte[] copy = java.util.Arrays.copyOfRange(pkt.getData(), pkt.getOffset(), pkt.getOffset() + len);
                ByteBuffer buf = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN);
                if (buf.getInt(0) != LCV_UDP_MAGIC) {
                    continue;
                }
                int seq = buf.getInt(4);
                int totalPkts = buf.getShort(10) & 0xFFFF;
                List<byte[]> bucket = bySeq.computeIfAbsent(seq, k -> new ArrayList<>());
                bucket.add(copy);
                if (bySeq.size() >= maxSuperblocks && bucket.size() >= totalPkts) {
                    break;
                }
            }
        }

        var bucketIt = bySeq.values().iterator();
        if (!bucketIt.hasNext()) {
            throw new IOException("No valid LCVU datagrams received on " + bindAddr);
        }
        byte[] firstDg = bucketIt.next().get(0);
        LcvStreamContainer.SuperblockHeader firstHdr = readEmbeddedSuperblockHeader64(firstDg, 12);
        int width = firstHdr.width16();
        int height = firstHdr.height16();
        int depthK = firstHdr.depthK8();
        int fps = Math.max(1, firstHdr.fpsNum32() / Math.max(1, firstHdr.fpsDen32()));

        LcvStreamContainer.StreamDecoderState decState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.StreamEncoderState encState = new LcvStreamContainer.StreamEncoderState(width, height);
        List<byte[]> reconstructedSbs = new ArrayList<>(bySeq.size());
        long prevOff = 0L;
        int idx = 0;
        for (List<byte[]> dgs : bySeq.values()) {
            LcvStreamContainer.SuperblockDecodeResult dec = decodeSuperblockFromUdpDatagrams(dgs, decState);
            byte[] reencoded = LcvStreamContainer.encodeSuperblockParallel(
                dec.rgbaFrames(), width, height, depthK,
                (long) idx * depthK, prevOff,
                (width / LcvPrimitives.TILE_DIM) / 2, (height / LcvPrimitives.TILE_DIM) / 2,
                firstHdr.tuningProfile(), false, encState
            );
            reconstructedSbs.add(reencoded);
            prevOff += reencoded.length;
            idx++;
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        writeContainerOutput(output, reconstructedSbs, width, height, fps);
        return 0;
    }

    private static int runCropCommand(Map<String, String> opts) throws IOException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        Path output = Path.of(requireOpt(opts, "output"));
        String preset = opts.getOrDefault("preset", "").toLowerCase(Locale.ROOT);

        byte[] inputBytes = Files.readAllBytes(input);
        List<byte[]> srcSuperblocks = extractSuperblocks(inputBytes);
        if (srcSuperblocks.isEmpty()) {
            throw new IllegalArgumentException("No valid LCV_V001 Superblocks found in " + input);
        }
        LcvStreamContainer.SuperblockHeader firstHdr = LcvStreamContainer.readSuperblockHeader(srcSuperblocks.get(0), 0);
        int srcTilesX = firstHdr.tilesX16();
        int srcTilesY = firstHdr.tilesY16();

        int[] bounds = resolveCropBounds(opts, preset, srcTilesX, srcTilesY);
        TransformResult res = cropContainerFile(input, output, bounds[0], bounds[1], bounds[2], bounds[3]);
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Compressed-domain cropSubRegion (%dx%d -> %dx%d, %d superblocks) completed in %.3f ms (%,d B -> %,d B) -> %s%n",
            res.srcWidth(), res.srcHeight(), res.dstWidth(), res.dstHeight(),
            res.superblockCount(), res.transformElapsedMs(), res.srcBytes(), res.dstBytes(),
            output.toAbsolutePath()
        );
        return 0;
    }

    private static int runStripLsbCommand(Map<String, String> opts) throws IOException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        Path output = Path.of(requireOpt(opts, "output"));
        TransformResult res = stripLsbContainerFile(input, output);
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Compressed-domain stripLsbPlanes (%dx%d, %d superblocks) completed in %.3f ms (%,d B -> %,d B) -> %s%n",
            res.srcWidth(), res.srcHeight(), res.superblockCount(),
            res.transformElapsedMs(), res.srcBytes(), res.dstBytes(), output.toAbsolutePath()
        );
        return 0;
    }

    private static int runPostprocessCommand(Map<String, String> opts) throws IOException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        Path output = Path.of(opts.getOrDefault("output", input.toString()));
        int lossyLsbBits = Math.max(0, Math.min(4, parseIntOpt(opts, "lossy-lsb-bits", 0)));
        int dMergeTol = Math.max(0, Math.min(32, parseIntOpt(opts, "d-merge", 0)));
        boolean stripLsb = Boolean.parseBoolean(opts.getOrDefault("strip-lsb", "false")) || lossyLsbBits >= 2;
        boolean wrapZstd = Boolean.parseBoolean(opts.getOrDefault("zstd", "true"));
        boolean inPlace = Boolean.parseBoolean(opts.getOrDefault("in-place", "false"));
        int effectiveDMerge = dMergeTol > 0 ? dMergeTol : (lossyLsbBits >= 4 ? 6 : (lossyLsbBits == 3 ? 4 : 0));
        LcvCubeCodec.DistanceRlePolicy coalescePolicy = (opts.containsKey("coalesce")
            && Boolean.parseBoolean(opts.get("coalesce"))) || effectiveDMerge > 0
            ? new LcvCubeCodec.DistanceRlePolicy(Math.max(2, effectiveDMerge), 0, 1.25)
            : null;
        TransformResult res = postprocessContainerFile(input, output, stripLsb, coalescePolicy, wrapZstd, inPlace);
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Compressed-domain postprocess (%dx%d, %d superblocks, zstd=%s, stripLsb=%s, inPlace=%s) completed in %.3f ms (%,d B -> %,d B) -> %s%n",
            res.srcWidth(), res.srcHeight(), res.superblockCount(),
            wrapZstd, stripLsb, inPlace,
            res.transformElapsedMs(), res.srcBytes(), res.dstBytes(), output.toAbsolutePath()
        );
        return 0;
    }

    private static int runSpliceCommand(Map<String, String> opts) throws IOException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        Path patch = Path.of(requireOpt(opts, "patch"));
        Path output = Path.of(opts.getOrDefault("output", input.toString()));
        TransformResult res = spliceContainerFileNonTemporal(input, patch, output);
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Non-temporal splice (%dx%d, %d active superblocks) completed in %.3f ms (%,d B -> %,d B) -> %s%n",
            res.srcWidth(), res.srcHeight(), res.superblockCount(),
            res.transformElapsedMs(), res.srcBytes(), res.dstBytes(), output.toAbsolutePath()
        );
        return 0;
    }

    private static int runCompactTemporalCommand(Map<String, String> opts) throws IOException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        Path output = Path.of(opts.getOrDefault("output", input.toString()));
        TransformResult res = compactContainerFileToTemporalOrder(input, output);
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Temporal stream compaction (%dx%d, %d superblocks) completed in %.3f ms (%,d B -> %,d B) -> %s%n",
            res.srcWidth(), res.srcHeight(), res.superblockCount(),
            res.transformElapsedMs(), res.srcBytes(), res.dstBytes(), output.toAbsolutePath()
        );
        return 0;
    }

    private static int runIngestDirCommand(Map<String, String> opts)
        throws IOException, InterruptedException, LcvCorruptTileException {
        Path dir = Path.of(requireOpt(opts, "dir"));
        Path outDir = Path.of(opts.getOrDefault("output-dir", dir.toString()));
        int width = alignTileDim(parseIntOpt(opts, "width", 640));
        int height = alignTileDim(parseIntOpt(opts, "height", 368));
        int depthK = parseIntOpt(opts, "depth-k", 8);
        int fps = parseIntOpt(opts, "fps", 60);
        String profileOpt = opts.getOrDefault("profile", "OBS_REALTIME_LOSSLESS");
        LcvCubeCodec.TuningProfile profile = resolveProfile(profileOpt);
        boolean postprocessZstd = Boolean.parseBoolean(opts.getOrDefault("postprocess-zstd", "true"));
        boolean autoCrop9x16 = Boolean.parseBoolean(opts.getOrDefault("auto-crop-9x16", "true"));
        List<Path> produced = batchIngestRecordingDirectory(
            dir, outDir, width, height, depthK, fps, profile, postprocessZstd, autoCrop9x16
        );
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Recorder-agnostic batch ingest processed %d recording(s) in %s -> %s%n",
            produced.size(), dir.toAbsolutePath(), outDir.toAbsolutePath()
        );
        return 0;
    }

    private static int runInspectCommand(Map<String, String> opts) throws IOException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        StreamInspectionReport report = inspectContainerFile(input);
        boolean json = Boolean.parseBoolean(opts.getOrDefault("json", "false"));
        if (json) {
            System.out.println(report.toJson());
        } else {
            System.out.printf(
                Locale.ROOT,
                "[LcvCli] Stream Inspection: %s%n"
                    + "  Dimensions : %dx%d (depthK=%d, fps=%d)%n"
                    + "  Superblocks: %d (%d total frames, %,d bytes)%n"
                    + "  LSB Strip  : %s%n"
                    + "  SEC-DED    : healed=%d, quarantined=%d, cleanAck=%d, uniformInline=%d%n",
                input.toAbsolutePath(),
                report.width(), report.height(), report.depthK(), report.fps(),
                report.superblockCount(), report.totalFrames(), report.fileBytes(),
                report.lsbStripped(),
                report.healedSingleBitPointers(), report.quarantinedTiles(),
                report.cleanAckReusedTiles(), report.uniformInlineTiles()
            );
        }
        return 0;
    }

    private static int[] resolveOptionalCropBounds(Map<String, String> opts, Path input)
        throws IOException, LcvCorruptTileException {
        boolean hasCropOpt = opts.containsKey("preset")
            || opts.containsKey("tile-x") || opts.containsKey("tile-y")
            || opts.containsKey("tiles-w") || opts.containsKey("tiles-h")
            || opts.containsKey("px-x") || opts.containsKey("px-y")
            || opts.containsKey("px-w") || opts.containsKey("px-h");
        if (!hasCropOpt) {
            return new int[0];
        }
        byte[] inputBytes = Files.readAllBytes(input);
        List<byte[]> srcSuperblocks = extractSuperblocks(inputBytes);
        if (srcSuperblocks.isEmpty()) {
            throw new IllegalArgumentException("No valid LCV_V001 Superblocks found in " + input);
        }
        LcvStreamContainer.SuperblockHeader firstHdr =
            LcvStreamContainer.readSuperblockHeader(srcSuperblocks.get(0), 0);
        String preset = opts.getOrDefault("preset", "").toLowerCase(Locale.ROOT);
        return resolveCropBounds(opts, preset, firstHdr.tilesX16(), firstHdr.tilesY16());
    }

    private static int runDecodeRawCommand(Map<String, String> opts) throws IOException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        String outputStr = opts.getOrDefault("output", "-");
        boolean stripLsb = Boolean.parseBoolean(opts.getOrDefault("strip-lsb", "false"));
        int maxFrames = parseIntOpt(opts, "max-frames", 0);
        int[] crop = resolveOptionalCropBounds(opts, input);
        Integer tileX = crop.length == 4 ? crop[0] : null;
        Integer tileY = crop.length == 4 ? crop[1] : null;
        Integer tilesW = crop.length == 4 ? crop[2] : null;
        Integer tilesH = crop.length == 4 ? crop[3] : null;

        if ("-".equals(outputStr)) {
            streamDecodeSuperblocksToPipe(input, System.out, tileX, tileY, tilesW, tilesH, stripLsb, maxFrames);
            System.out.flush();
        } else {
            Path outPath = Path.of(outputStr);
            if (outPath.getParent() != null) {
                Files.createDirectories(outPath.getParent());
            }
            try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(outPath), 1 << 20)) {
                streamDecodeSuperblocksToPipe(input, fileOut, tileX, tileY, tilesW, tilesH, stripLsb, maxFrames);
            }
            System.err.printf(Locale.ROOT, "[LcvCli] Decoded %,d raw RGBA bytes -> %s%n", Files.size(outPath), outPath.toAbsolutePath());
        }
        return 0;
    }

    private static int runExportCarrierCommand(Map<String, String> opts)
        throws IOException, InterruptedException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        Path output = Path.of(requireOpt(opts, "output"));
        String carrier = opts.getOrDefault("carrier", "qtrle").toLowerCase(Locale.ROOT);
        boolean stripLsb = Boolean.parseBoolean(opts.getOrDefault("strip-lsb", "false"));
        int[] crop = resolveOptionalCropBounds(opts, input);
        Integer tileX = crop.length == 4 ? crop[0] : null;
        Integer tileY = crop.length == 4 ? crop[1] : null;
        Integer tilesW = crop.length == 4 ? crop[2] : null;
        Integer tilesH = crop.length == 4 ? crop[3] : null;

        exportToEditorCarrier(input, output, carrier, tileX, tileY, tilesW, tilesH, stripLsb);
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Exported NLE carrier (%s) -> %s (%,d B)%n",
            carrier, output.toAbsolutePath(), Files.size(output)
        );
        return 0;
    }

    private static int runPlayCommand(Map<String, String> opts)
        throws IOException, InterruptedException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        boolean stripLsb = Boolean.parseBoolean(opts.getOrDefault("strip-lsb", "false"));
        int[] crop = resolveOptionalCropBounds(opts, input);
        Integer tileX = crop.length == 4 ? crop[0] : null;
        Integer tileY = crop.length == 4 ? crop[1] : null;
        Integer tilesW = crop.length == 4 ? crop[2] : null;
        Integer tilesH = crop.length == 4 ? crop[3] : null;
        int headlessFrames = parseIntOpt(opts, "headless-frames", 0);
        boolean headless = headlessFrames > 0 || Boolean.parseBoolean(opts.getOrDefault("headless", "false"));

        return playContainerInFfplay(input, tileX, tileY, tilesW, tilesH, stripLsb, headless, headlessFrames);
    }

    private static int runConformTimelineCommand(Map<String, String> opts)
        throws IOException, InterruptedException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        Path output = Path.of(requireOpt(opts, "output"));
        String carrier = opts.getOrDefault("carrier", "qtrle").toLowerCase(Locale.ROOT);
        boolean stripLsb = Boolean.parseBoolean(opts.getOrDefault("strip-lsb", "false"));
        int[] crop = resolveOptionalCropBounds(opts, input);
        Integer tileX = crop.length == 4 ? crop[0] : null;
        Integer tileY = crop.length == 4 ? crop[1] : null;
        Integer tilesW = crop.length == 4 ? crop[2] : null;
        Integer tilesH = crop.length == 4 ? crop[3] : null;
        String cutsOpt = opts.getOrDefault("cuts", "");

        List<byte[]> superblocks = loadAndTransformSuperblocks(input, tileX, tileY, tilesW, tilesH, stripLsb);
        if (!cutsOpt.isBlank()) {
            List<int[]> cuts = new ArrayList<>();
            for (String tok : cutsOpt.split(",")) {
                String[] parts = tok.trim().split(":");
                if (parts.length == 2) {
                    int s = Integer.parseInt(parts[0].trim());
                    int e = Integer.parseInt(parts[1].trim());
                    if (e > s) {
                        cuts.add(new int[]{s, e});
                    }
                }
            }
            if (!cuts.isEmpty()) {
                List<byte[]> filtered = new ArrayList<>();
                int curF = 0;
                for (byte[] sb : superblocks) {
                    LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(sb, 0);
                    int k = hdr.depthK8();
                    int sF = curF;
                    int eF = curF + k;
                    curF += k;
                    for (int[] c : cuts) {
                        if (sF < c[1] && eF > c[0]) {
                            filtered.add(sb);
                            break;
                        }
                    }
                }
                if (!filtered.isEmpty()) {
                    superblocks = filtered;
                }
            }
        }
        String outName = output.getFileName().toString().toLowerCase(Locale.ROOT);
        if (outName.endsWith(".lcv") || outName.endsWith("_v_lcv1.mkv")) {
            LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(superblocks.get(0), 0);
            int fps = Math.max(1, hdr.fpsNum32() / Math.max(1, hdr.fpsDen32()));
            writeContainerOutput(output, superblocks, hdr.width16(), hdr.height16(), fps);
        } else {
            Path tempLcv = Files.createTempFile("lcv_conform_", ".lcv");
            try {
                LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(superblocks.get(0), 0);
                int fps = Math.max(1, hdr.fpsNum32() / Math.max(1, hdr.fpsDen32()));
                writeContainerOutput(tempLcv, superblocks, hdr.width16(), hdr.height16(), fps);
                exportToEditorCarrier(tempLcv, output, carrier, null, null, null, null, false);
            } finally {
                Files.deleteIfExists(tempLcv);
            }
        }
        return 0;
    }

    private static int runNleSyncCommand(Map<String, String> opts)
        throws IOException, InterruptedException, LcvCorruptTileException {
        Path input = Path.of(requireOpt(opts, "input"));
        String defaultOut = Path.of(System.getProperty("user.home", "."), "LeafVideo-NLE").toString();
        Path outputDir = Path.of(opts.getOrDefault("output", defaultOut));
        String carriersOpt = opts.getOrDefault("carriers", "qtrle,mp4");
        List<String> carriers = new ArrayList<>();
        for (String c : carriersOpt.split(",")) {
            if (!c.isBlank()) {
                carriers.add(c.trim().toLowerCase(Locale.ROOT));
            }
        }
        Path manifestPath = syncNleWorkspace(input, outputDir, carriers);
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Synchronized NLE workspace -> %s (manifest=%s)%n",
            outputDir.toAbsolutePath(), manifestPath.toAbsolutePath()
        );
        return 0;
    }

    /**
     * Synchronizes a {@code .lcv}/{@code .mkv (V_LCV1)} stream (or standard video recording) into an
     * NLE workspace directory with {@code < 1 ms} compressed-domain {@code 9:16}, {@code facecam},
     * and {@code g7..g2} proxy derivatives plus timeline carriers ({@code qtrle .mov}, {@code prores_4444 .mov},
     * {@code ffv1 .mkv}, {@code h264 .mp4}).
     */
    public static Path syncNleWorkspace(Path input, Path outputDir, List<String> carriers)
        throws IOException, InterruptedException, LcvCorruptTileException {
        Files.createDirectories(outputDir);
        List<Path> lcvSources = collectLcvSources(input, outputDir);

        StringBuilder json = new StringBuilder(1024);
        json.append("{\n  \"workspaceDir\": \"").append(escapeJson(outputDir.toAbsolutePath().toString()))
            .append("\",\n  \"clipCount\": ").append(lcvSources.size())
            .append(",\n  \"clips\": [\n");

        boolean firstClip = true;
        for (Path src : lcvSources) {
            String clipJson = exportSingleClipNleDerivatives(src, outputDir, carriers);
            if (clipJson.isEmpty()) {
                continue;
            }
            if (!firstClip) {
                json.append(",\n");
            }
            firstClip = false;
            json.append(clipJson);
        }
        json.append("\n  ]\n}\n");
        Path manifestPath = outputDir.resolve("lcv_nle_manifest.json");
        Files.writeString(manifestPath, json.toString());
        return manifestPath;
    }

    private static List<Path> collectLcvSources(Path input, Path outputDir)
        throws IOException, InterruptedException {
        List<Path> lcvSources = new ArrayList<>();
        if (Files.isDirectory(input)) {
            try (var stream = Files.list(input)) {
                stream.filter(Files::isRegularFile)
                    .filter(LcvCli::isPrimaryLcvStreamFile)
                    .sorted()
                    .forEach(lcvSources::add);
            }
            return lcvSources;
        }
        if (!Files.isRegularFile(input)) {
            throw new IllegalArgumentException("Input path not found: " + input);
        }
        String n = input.getFileName().toString().toLowerCase(Locale.ROOT);
        boolean isLcv = n.endsWith(".lcv") || n.endsWith("_v_lcv1.mkv")
            || (n.endsWith(".mkv") && !extractSuperblocks(Files.readAllBytes(input)).isEmpty());
        if (isLcv) {
            lcvSources.add(input);
        } else {
            String stem = stripExtension(input.getFileName().toString());
            Path ingested = outputDir.resolve(stem + "_v_lcv1.mkv");
            streamIngestVideoToFile(
                input, 640, 368, 8, 60, 20, 11,
                LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, false, ingested
            );
            lcvSources.add(ingested);
        }
        return lcvSources;
    }

    private static boolean isPrimaryLcvStreamFile(Path path) {
        String n = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return (n.endsWith(".lcv") || n.endsWith("_v_lcv1.mkv"))
            && !n.endsWith("_9x16_v_lcv1.mkv")
            && !n.endsWith("_facecam_v_lcv1.mkv")
            && !n.endsWith("_proxy_g7_g2.mkv");
    }

    private static String exportSingleClipNleDerivatives(
        Path src, Path outputDir, List<String> carriers
    ) throws IOException, InterruptedException, LcvCorruptTileException {
        String fileName = src.getFileName().toString();
        String stem = fileName.toLowerCase(Locale.ROOT).endsWith("_v_lcv1.mkv")
            ? fileName.substring(0, fileName.length() - "_v_lcv1.mkv".length())
            : stripExtension(fileName);

        List<byte[]> sbs = extractSuperblocks(Files.readAllBytes(src));
        if (sbs.isEmpty()) {
            return "";
        }
        LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(sbs.get(0), 0);
        int[] crop9x16 = resolveCropBounds(Map.of(), "9x16", hdr.tilesX16(), hdr.tilesY16());
        int[] cropFace = resolveCropBounds(Map.of(), "facecam", hdr.tilesX16(), hdr.tilesY16());

        Path out9x16Lcv = outputDir.resolve(stem + "_9x16_v_lcv1.mkv");
        cropContainerFile(src, out9x16Lcv, crop9x16[0], crop9x16[1], crop9x16[2], crop9x16[3]);
        Path outFaceLcv = outputDir.resolve(stem + "_facecam_v_lcv1.mkv");
        cropContainerFile(src, outFaceLcv, cropFace[0], cropFace[1], cropFace[2], cropFace[3]);
        Path outProxyLcv = outputDir.resolve(stem + "_proxy_g7_g2.mkv");
        stripLsbContainerFile(src, outProxyLcv);

        Map<String, Path> derivs = new LinkedHashMap<>();
        derivs.put("_9x16_v_lcv1.mkv", out9x16Lcv);
        derivs.put("_facecam_v_lcv1.mkv", outFaceLcv);
        derivs.put("_proxy_g7_g2.mkv", outProxyLcv);

        if (carriers.contains("qtrle") || carriers.contains("mov")) {
            Path fullMov = outputDir.resolve(stem + "_full_rgba.mov");
            exportToEditorCarrier(src, fullMov, "qtrle", null, null, null, null, false);
            derivs.put("_full_rgba.mov", fullMov);
            Path vertMov = outputDir.resolve(stem + "_9x16_vertical.mov");
            exportToEditorCarrier(
                src, vertMov, "qtrle", crop9x16[0], crop9x16[1], crop9x16[2], crop9x16[3], false
            );
            derivs.put("_9x16_vertical.mov", vertMov);
        }
        if (carriers.contains("prores_4444") || carriers.contains("prores4444")) {
            Path proresMov = outputDir.resolve(stem + "_prores4444.mov");
            exportToEditorCarrier(src, proresMov, "prores_4444", null, null, null, null, false);
            derivs.put("_prores4444.mov", proresMov);
        }
        if (carriers.contains("ffv1")) {
            Path ffv1Mkv = outputDir.resolve(stem + "_ffv1.mkv");
            exportToEditorCarrier(src, ffv1Mkv, "ffv1", null, null, null, null, false);
            derivs.put("_ffv1.mkv", ffv1Mkv);
        }
        if (carriers.contains("mp4") || carriers.contains("h264")) {
            Path mp4 = outputDir.resolve(stem + "_universal.mp4");
            exportToEditorCarrier(src, mp4, "mp4", null, null, null, null, false);
            derivs.put("_universal.mp4", mp4);
        }

        StringBuilder sb = new StringBuilder(512);
        sb.append("    {\n      \"source\": \"").append(escapeJson(src.toAbsolutePath().toString()))
            .append("\",\n      \"stem\": \"").append(escapeJson(stem))
            .append("\",\n      \"derivatives\": {\n");
        int dIdx = 0;
        for (Map.Entry<String, Path> entry : derivs.entrySet()) {
            if (dIdx++ > 0) {
                sb.append(",\n");
            }
            Path p = entry.getValue();
            sb.append("        \"").append(escapeJson(entry.getKey())).append("\": {")
                .append("\"path\": \"").append(escapeJson(p.toAbsolutePath().toString()))
                .append("\", \"bytes\": ").append(Files.size(p)).append("}");
        }
        sb.append("\n      }\n    }");
        return sb.toString();
    }

    private static int runInstallObsCommand(Map<String, String> opts) throws IOException {
        String defaultObsDir = Path.of(System.getProperty("user.home", "."), ".config", "obs-studio").toString();
        String defaultNleDir = Path.of(System.getProperty("user.home", "."), "LeafVideo-NLE").toString();
        Path obsConfigDir = Path.of(opts.getOrDefault("obs-config-dir", defaultObsDir));
        Path nleDir = Path.of(opts.getOrDefault("nle-dir", defaultNleDir));
        Path pluginSo = opts.containsKey("plugin-so")
            ? Path.of(opts.get("plugin-so"))
            : Path.of("resource/LeafVideo/build/native/obs-leafvideo.so");
        Path jarPath = opts.containsKey("jar")
            ? Path.of(opts.get("jar"))
            : Path.of("resource/LeafVideo/build/libs/LeafVideo-0.1.0.jar");
        Path scriptPath = opts.containsKey("script")
            ? Path.of(opts.get("script"))
            : Path.of("resource/LeafVideo/integrations/obs_leafvideo_crop_guard.py");

        Path installedSo = installObsIntegration(obsConfigDir, nleDir, pluginSo, jarPath, scriptPath);
        System.out.printf(
            Locale.ROOT,
            "[LcvCli] Installed OBS Studio integration -> %s (NLE workspace: %s)%n",
            installedSo.toAbsolutePath(), nleDir.toAbsolutePath()
        );
        return 0;
    }

    /**
     * Installs the native {@code obs-leafvideo.so} plugin, locale bundle, codec JAR, Custom Browser Dock HTML,
     * and Python script registration into an OBS Studio configuration directory.
     */
    public static Path installObsIntegration(
        Path obsConfigDir, Path nleWorkspaceDir, Path pluginSo, Path jarPath, Path scriptPath
    ) throws IOException {
        Path binDir = obsConfigDir.resolve("plugins/obs-leafvideo/bin/64bit");
        Path dataDir = obsConfigDir.resolve("plugins/obs-leafvideo/data");
        Path localeDir = dataDir.resolve("locale");
        Path dockDir = obsConfigDir.resolve("plugin_config/obs-leafvideo");
        Files.createDirectories(binDir);
        Files.createDirectories(localeDir);
        Files.createDirectories(dockDir);
        Files.createDirectories(nleWorkspaceDir);

        Path targetSo = binDir.resolve("obs-leafvideo.so");
        if (Files.isRegularFile(pluginSo)) {
            Files.copy(pluginSo, targetSo, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Path siblingAccel = pluginSo.resolveSibling("liblcv_accel.so");
            if (Files.isRegularFile(siblingAccel)) {
                Files.copy(
                    siblingAccel,
                    binDir.resolve("liblcv_accel.so"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                );
            }
        }
        if (Files.isRegularFile(jarPath)) {
            Files.copy(
                jarPath,
                dataDir.resolve("LeafVideo-0.1.0.jar"),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            );
        }
        Files.writeString(
            localeDir.resolve("en-US.ini"),
            "LeafVideoRgbaSource=\"LeafVideo (.lcv / V_LCV1) RGBA Media Source\"\n"
                + "LeafVideoEncoder=\"LeafVideo (.lcv / V_LCV1) Lossless & Quadtree RGBA Encoder\"\n"
        );

        Path dockHtml = dockDir.resolve("leafvideo_obs_dock.html");
        Files.writeString(dockHtml, buildObsBrowserDockHtml(nleWorkspaceDir, scriptPath));

        configureObsProfilesForLeafVideoEncoder(obsConfigDir);
        registerObsSceneScripts(obsConfigDir, scriptPath);
        return targetSo;
    }

    private static void configureObsProfilesForLeafVideoEncoder(Path obsConfigDir) throws IOException {
        Path profilesDir = obsConfigDir.resolve("basic/profiles");
        if (!Files.isDirectory(profilesDir)) {
            return;
        }
        try (var profStream = Files.list(profilesDir)) {
            for (Path profDir : profStream.filter(Files::isDirectory).toList()) {
                Path basicIni = profDir.resolve("basic.ini");
                if (!Files.isRegularFile(basicIni)) {
                    continue;
                }
                String ini = Files.readString(basicIni);
                Path bak = basicIni.resolveSibling("basic.ini.lcv.bak");
                if (!Files.exists(bak)) {
                    Files.copy(basicIni, bak);
                }
                String updated = ini.replace("Mode=Simple", "Mode=Advanced");
                if (updated.contains("RecEncoder=none")) {
                    updated = updated.replace("RecEncoder=none", "RecEncoder=leafvideo_lcv1");
                } else if (!updated.contains("RecEncoder=leafvideo_lcv1") && updated.contains("[AdvOut]")) {
                    updated = updated.replace("[AdvOut]", "[AdvOut]\nRecEncoder=leafvideo_lcv1");
                }
                if (updated.contains("[AdvOut]")) {
                    if (!updated.contains("RecRB=")) {
                        updated = updated.replace("[AdvOut]", "[AdvOut]\nRecRB=true\nRecRBTime=20\nRecRBSize=512");
                    } else {
                        updated = updated.replace("RecRB=false", "RecRB=true");
                    }
                }
                if (!updated.equals(ini)) {
                    Files.writeString(basicIni, updated);
                }
            }
        }
    }

    private static void registerObsSceneScripts(Path obsConfigDir, Path scriptPath) throws IOException {
        Path scenesDir = obsConfigDir.resolve("basic/scenes");
        if (!Files.isDirectory(scenesDir) || !Files.isRegularFile(scriptPath)) {
            return;
        }
        String absScript = scriptPath.toAbsolutePath().toString();
        try (var scenes = Files.list(scenesDir)) {
            for (Path sceneJson : scenes.filter(p -> p.toString().endsWith(".json")).toList()) {
                String content = Files.readString(sceneJson);
                if (!content.contains("obs_leafvideo_crop_guard.py")
                    && content.contains("\"scripts-tool\": []")) {
                    Path bak = sceneJson.resolveSibling(sceneJson.getFileName() + ".lcv.bak");
                    if (!Files.exists(bak)) {
                        Files.copy(sceneJson, bak);
                    }
                    String scriptEntry = "\"scripts-tool\": [\n"
                        + "            {\n"
                        + "                \"path\": \"" + escapeJson(absScript) + "\",\n"
                        + "                \"settings\": {}\n"
                        + "            }\n"
                        + "        ]";
                    String updated = content.replace("\"scripts-tool\": []", scriptEntry);
                    Files.writeString(sceneJson, updated);
                }
            }
        }
    }

    private static String buildObsBrowserDockHtml(Path nleWorkspaceDir, Path scriptPath) {
        return "<!DOCTYPE html>\n"
            + "<html lang=\"en\"><head><meta charset=\"UTF-8\">\n"
            + "<title>LeafVideo (.lcv / V_LCV1) OBS & NLE Studio Dock</title>\n"
            + "<style>\n"
            + "body{background:#181a1f;color:#e6edf3;font-family:system-ui,sans-serif;margin:12px;font-size:13px}\n"
            + "h3{margin:0 0 8px;color:#58a6ff;font-size:15px}\n"
            + ".card{background:#21262d;border:1px solid #30363d;border-radius:6px;padding:10px;margin-bottom:10px}\n"
            + ".badge{display:inline-block;background:#238636;color:#fff;padding:2px 6px;border-radius:4px;font-size:11px}\n"
            + "code{background:#0d1117;padding:2px 5px;border-radius:4px;color:#79c0ff;display:block;margin-top:4px;word-break:break-all}\n"
            + "</style></head><body>\n"
            + "<h3>⚡ LeafVideo (.lcv / V_LCV1) Live Studio</h3>\n"
            + "<div class=\"card\"><span class=\"badge\">ACTIVE</span> <b>16/32/64 Quadtree + RGBA Alpha</b>\n"
            + "<p>NLE Drag-and-Drop Workspace:</p><code>"
            + escapeJson(nleWorkspaceDir.toAbsolutePath().toString()) + "</code></div>\n"
            + "<div class=\"card\"><b>OBS Native Source:</b> Add <i>LeafVideo (.lcv / V_LCV1) RGBA Media Source</i> in Sources (+).\n"
            + "<p><b>OBS Script Panel:</b> Tools &rarr; Scripts:</p><code>"
            + escapeJson(scriptPath.toAbsolutePath().toString()) + "</code></div>\n"
            + "</body></html>\n";
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Generates a multi-superblock sample {@code .mkv} ({@code V_LCV1}) or {@code .lcv} file on disk.
     */
    public static void exportSampleFile(
        Path output, int width, int height, int depthK, int superblockCount,
        int fps, String mode, LcvCubeCodec.TuningProfile profile, boolean useZstd
    ) throws IOException {
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        int tilesX = width / LcvPrimitives.TILE_DIM;
        int tilesY = height / LcvPrimitives.TILE_DIM;
        int roiX = tilesX / 2;
        int roiY = tilesY / 2;

        LcvStreamContainer.StreamEncoderState encState = new LcvStreamContainer.StreamEncoderState(width, height);
        List<byte[]> superblocks = new ArrayList<>(superblockCount);
        long prevOffset = 0L;
        long runningOffset = 0L;

        for (int sbIdx = 0; sbIdx < superblockCount; sbIdx++) {
            int[] frames = generateSampleFrames(width, height, depthK, sbIdx * depthK, mode);
            byte[] sbBytes = LcvStreamContainer.encodeSuperblockParallel(
                frames, width, height, depthK,
                (long) sbIdx * depthK, prevOffset,
                roiX, roiY, profile, useZstd, encState
            );
            ByteBuffer sbBuf = ByteBuffer.wrap(sbBytes).order(ByteOrder.BIG_ENDIAN);
            sbBuf.putInt(0x34, fps);
            sbBuf.putInt(0x38, (sbBuf.getInt(0x38) & 0xFFFF_0000) | 1);
            int crc = LcvStreamContainer.computeHeaderCrc32(sbBytes, 0);
            sbBuf.putInt(0x3C, crc);

            superblocks.add(sbBytes);
            prevOffset = runningOffset;
            runningOffset += sbBytes.length;
        }

        writeContainerOutput(output, superblocks, width, height, fps);
    }

    /**
     * Encodes raw big-endian {@code RGBA} bytes ({@code width * height * 4} bytes per frame) into a
     * {@code .mkv} ({@code V_LCV1}) or {@code .lcv} file.
     */
    public static int encodeRawRgbaBytesToFile(
        byte[] rawRgbaBytes, int width, int height, int depthK, int fps,
        int roiTileX, int roiTileY, LcvCubeCodec.TuningProfile profile, boolean useZstd, Path output
    ) throws IOException {
        return encodeRawRgbaStreamToFile(
            new java.io.ByteArrayInputStream(rawRgbaBytes),
            width, height, depthK, fps, roiTileX, roiTileY, profile, useZstd, false, output
        );
    }

    /**
     * Streams raw big-endian {@code RGBA} frames from {@code rawRgbaIn} in bounded {@code O(1)}
     * memory ({@code 1 Superblock = depthK} frames at a time), encoding each Superblock in parallel
     * and writing directly to a {@code .mkv} ({@code V_LCV1}) or {@code .lcv} output stream.
     */
    public static int encodeRawRgbaStreamToFile(
        InputStream rawRgbaIn, int width, int height, int depthK, int fps,
        int roiTileX, int roiTileY, LcvCubeCodec.TuningProfile profile,
        boolean useZstd, boolean stripLsbInline, Path output
    ) throws IOException {
        return encodeRawRgbaStreamToFile(
            rawRgbaIn, width, height, depthK, fps, roiTileX, roiTileY, profile,
            useZstd, stripLsbInline, output,
            null, null, null, "", 1200, false
        );
    }

    /**
     * Streams raw big-endian {@code RGBA} frames into a {@code .mkv} ({@code V_LCV1}) or {@code .lcv}
     * output stream while optionally maintaining a bounded two-tier in-memory {@link ReplayRingBuffer}
     * and/or transmitting low-latency {@code LCVU} {@link LcvStreamContainer.MvCoherentPacket} UDP datagrams.
     */
    public static int encodeRawRgbaStreamToFile(
        InputStream rawRgbaIn, int width, int height, int depthK, int fps,
        int roiTileX, int roiTileY, LcvCubeCodec.TuningProfile profile,
        boolean useZstd, boolean stripLsbInline, Path output,
        ReplayRingBuffer ringBuffer, Path replayTriggerFile, Path replayOutputOverride,
        String streamUdpTarget, int streamMtuBytes, boolean streamShedLsb
    ) throws IOException {
        int framePixels = width * height;
        int frameBytes = framePixels * 4;
        byte[] singleFrameBytes = new byte[frameBytes];
        ByteBuffer singleFrameBuf = ByteBuffer.wrap(singleFrameBytes).order(ByteOrder.BIG_ENDIAN);
        int[] sbFrames = new int[framePixels * depthK];

        LcvStreamContainer.StreamEncoderState encState = new LcvStreamContainer.StreamEncoderState(width, height);
        boolean isRawLcv = output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lcv");
        int safeFps = Math.max(1, fps);

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        java.nio.channels.DatagramChannel udpChannel = null;
        java.net.InetSocketAddress udpTargetAddr = parseUdpEndpoint(streamUdpTarget);
        if (udpTargetAddr != null) {
            udpChannel = java.nio.channels.DatagramChannel.open();
            udpChannel.configureBlocking(false);
        }

        int sbCount = 0;
        int elapsedFrames = 0;
        long prevOffset = 0L;
        long runningOffset = 0L;
        int replayFlushIndex = 0;
        ByteArrayOutputStream clusterScratch = isRawLcv ? null : new ByteArrayOutputStream(65536);

        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(output), 1 << 20)) {
            if (!isRawLcv) {
                byte[] mkvHeader = LcvMkvPacketizer.muxSuperblocksToMkv(List.of(), width, height, safeFps);
                fileOut.write(mkvHeader);
            }

            boolean eof = false;
            while (!eof) {
                int framesReadInSb = 0;
                for (int t = 0; t < depthK; t++) {
                    int nRead = rawRgbaIn.readNBytes(singleFrameBytes, 0, frameBytes);
                    if (nRead < frameBytes) {
                        if (sbCount == 0 && framesReadInSb == 0) {
                            throw new IllegalArgumentException(
                                "Input raw RGBA size (" + nRead + " B) is smaller than 1 frame (" + frameBytes + " B)"
                            );
                        }
                        eof = true;
                        break;
                    }
                    singleFrameBuf.position(0);
                    singleFrameBuf.asIntBuffer().get(sbFrames, t * framePixels, framePixels);
                    framesReadInSb++;
                }

                if (framesReadInSb == 0) {
                    break;
                }
                for (int t = framesReadInSb; t < depthK; t++) {
                    System.arraycopy(
                        sbFrames, (framesReadInSb - 1) * framePixels,
                        sbFrames, t * framePixels, framePixels
                    );
                }

                byte[] sbBytes = LcvStreamContainer.encodeSuperblockParallel(
                    sbFrames, width, height, depthK,
                    (long) sbCount * depthK, prevOffset,
                    roiTileX, roiTileY, profile, useZstd, encState
                );
                if (stripLsbInline) {
                    sbBytes = LcvOnDiskTransformer.stripLsbPlanes(sbBytes);
                }
                ByteBuffer sbHeaderBuf = ByteBuffer.wrap(sbBytes).order(ByteOrder.BIG_ENDIAN);
                sbHeaderBuf.putLong(0x2C, prevOffset);
                sbHeaderBuf.putInt(0x34, fps);
                sbHeaderBuf.putInt(0x38, (sbHeaderBuf.getInt(0x38) & 0xFFFF_0000) | 1);
                int crc = LcvStreamContainer.computeHeaderCrc32(sbBytes, 0);
                sbHeaderBuf.putInt(0x3C, crc);

                if (ringBuffer != null) {
                    ringBuffer.appendSuperblock(sbBytes);
                    if (replayTriggerFile != null && Files.exists(replayTriggerFile)) {
                        Files.deleteIfExists(replayTriggerFile);
                        Path baseDir = output.getParent() != null ? output.getParent() : Path.of(".");
                        Path flushOut = replayOutputOverride != null
                            ? replayOutputOverride
                            : baseDir.resolve(String.format(Locale.ROOT, "replay_%04d_v_lcv1.mkv", ++replayFlushIndex));
                        String fn = flushOut.getFileName().toString();
                        String stem = fn.toLowerCase(Locale.ROOT).endsWith("_v_lcv1.mkv")
                            ? fn.substring(0, fn.length() - "_v_lcv1.mkv".length())
                            : stripExtension(fn);
                        Path flush9x16 = baseDir.resolve(stem + "_9x16_v_lcv1.mkv");
                        ringBuffer.flushToFile(flushOut, flush9x16);
                    }
                }

                if (udpChannel != null && udpTargetAddr != null) {
                    transmitSuperblockUdp(udpChannel, udpTargetAddr, sbBytes, sbCount, streamMtuBytes, streamShedLsb);
                }

                if (isRawLcv) {
                    fileOut.write(sbBytes);
                } else {
                    int timestampMs = (elapsedFrames * 1000) / safeFps;
                    elapsedFrames += depthK;
                    clusterScratch.reset();
                    LcvMkvPacketizer.appendClusterSimpleBlock(clusterScratch, timestampMs, sbBytes);
                    clusterScratch.writeTo(fileOut);
                }

                prevOffset = runningOffset;
                runningOffset += sbBytes.length;
                sbCount++;
            }

            if (ringBuffer != null && replayOutputOverride != null && !Files.exists(replayOutputOverride)) {
                Path baseDir = replayOutputOverride.getParent() != null ? replayOutputOverride.getParent() : Path.of(".");
                String fn = replayOutputOverride.getFileName().toString();
                String stem = fn.toLowerCase(Locale.ROOT).endsWith("_v_lcv1.mkv")
                    ? fn.substring(0, fn.length() - "_v_lcv1.mkv".length())
                    : stripExtension(fn);
                Path flush9x16 = baseDir.resolve(stem + "_9x16_v_lcv1.mkv");
                ringBuffer.flushToFile(replayOutputOverride, flush9x16);
            }
        } finally {
            if (udpChannel != null) {
                udpChannel.close();
            }
        }
        return sbCount;
    }

    /** Magic header ({@code "LCVU"}) for low-latency UDP {@link LcvStreamContainer.MvCoherentPacket} datagrams. */
    public static final int LCV_UDP_MAGIC = 0x4C435655;

    /** Fixed byte overhead of an {@code LCVU} UDP datagram header (including embedded 64-byte {@code SuperblockHeader}). */
    public static final int LCV_UDP_HEADER_BYTES = 84;

    private static java.net.InetSocketAddress parseUdpEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        String clean = endpoint.trim();
        if (clean.startsWith("udp://")) {
            clean = clean.substring("udp://".length());
        }
        int colon = clean.lastIndexOf(':');
        if (colon <= 0 || colon + 1 >= clean.length()) {
            return null;
        }
        String host = clean.substring(0, colon).trim();
        int port = Integer.parseInt(clean.substring(colon + 1).trim());
        return new java.net.InetSocketAddress(host, port);
    }

    /**
     * Serializes a single {@code LCV_V001} Superblock into MTU-bounded {@code LCVU} UDP datagrams
     * using {@link LcvStreamContainer#packetizeSuperblockByMotionVector(byte[], int, int)}.
     *
     * <p>Every datagram embeds the 64-byte {@code SuperblockHeader} so the receiver can reconstruct
     * the frame via {@link LcvStreamContainer#decodeMvPacketStream} even if packet {@code 0} is dropped.
     * When {@code shedLsb == true}, {@code Section 4B} LSB payloads are shed in {@code 0.0 ms} for
     * instant congestion control without re-encoding.
     */
    public static List<byte[]> serializeUdpDatagramsForSuperblock(
        byte[] sbBytes, int superblockSeq, int mtuBytes, boolean shedLsb
    ) throws LcvCorruptTileException {
        byte[] workingSb = sbBytes;
        if ((workingSb[0x11] & LcvStreamContainer.FLAG_ZSTD_POOL) != 0) {
            LcvStreamContainer.SuperblockHeader zHdr = LcvStreamContainer.readSuperblockHeader(workingSb, 0);
            workingSb = LcvOnDiskTransformer.cropSubRegion(workingSb, 0, 0, zHdr.tilesX16(), zHdr.tilesY16());
        }
        int payloadBudget = Math.max(256, mtuBytes - LCV_UDP_HEADER_BYTES);
        List<LcvStreamContainer.MvCoherentPacket> mvPackets =
            LcvStreamContainer.packetizeSuperblockByMotionVector(workingSb, payloadBudget, 64);

        byte[] header64 = new byte[LcvStreamContainer.SUPERBLOCK_HEADER_BYTES];
        System.arraycopy(workingSb, 0, header64, 0, LcvStreamContainer.SUPERBLOCK_HEADER_BYTES);
        if (shedLsb) {
            header64[0x11] = (byte) (header64[0x11] | LcvStreamContainer.FLAG_LSB_STRIPPED);
            ByteBuffer hdrBuf = ByteBuffer.wrap(header64).order(ByteOrder.BIG_ENDIAN);
            hdrBuf.putInt(0x20, 0);
            int crc = LcvStreamContainer.computeHeaderCrc32(header64, 0);
            hdrBuf.putInt(0x3C, crc);
        }

        int totalPkts = mvPackets.size();
        List<byte[]> datagrams = new ArrayList<>(totalPkts);
        for (int pktIdx = 0; pktIdx < totalPkts; pktIdx++) {
            LcvStreamContainer.MvCoherentPacket pkt = mvPackets.get(pktIdx);
            int tileCount = pkt.tileCount();
            byte[] msb = pkt.msbPayload();
            byte[] lsb = shedLsb ? new byte[0] : pkt.lsbPayload();
            int dgLen = LCV_UDP_HEADER_BYTES + tileCount * 9 + msb.length + lsb.length;
            byte[] dg = new byte[dgLen];
            ByteBuffer buf = ByteBuffer.wrap(dg).order(ByteOrder.BIG_ENDIAN);
            buf.putInt(LCV_UDP_MAGIC);
            buf.putInt(superblockSeq);
            buf.putShort((short) pktIdx);
            buf.putShort((short) totalPkts);
            buf.put(header64);
            buf.putShort((short) pkt.startTileIdx());
            buf.put((byte) tileCount);
            buf.put((byte) pkt.packedMv8());
            buf.putShort((short) msb.length);
            buf.putShort((short) lsb.length);
            for (int i = 0; i < tileCount; i++) {
                long ptr = pkt.tilePointers()[i];
                byte syn = pkt.tileSyndromes()[i];
                if (shedLsb && !LcvStreamContainer.isZeroBodyPointer(ptr)
                    && ptr != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                    ptr = ptr & 0xFFFF_FFFF_FFFF_0000L;
                    syn = LcvPrimitives.computeHammingSecDed8(ptr);
                }
                buf.putLong(ptr);
                buf.put(syn);
            }
            buf.put(msb);
            buf.put(lsb);
            datagrams.add(dg);
        }
        return datagrams;
    }

    private static LcvStreamContainer.SuperblockHeader readEmbeddedSuperblockHeader64(byte[] src, int offset)
        throws LcvCorruptTileException {
        if (offset < 0 || offset + LcvStreamContainer.SUPERBLOCK_HEADER_BYTES > src.length) {
            throw new LcvCorruptTileException("CBIT: truncated embedded SuperblockHeader at offset " + offset);
        }
        ByteBuffer buf = ByteBuffer.wrap(src, offset, LcvStreamContainer.SUPERBLOCK_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        long magic64 = buf.getLong(offset + 0x00);
        if (magic64 != LcvStreamContainer.MAGIC_LCV_V001) {
            throw new LcvCorruptTileException(String.format("CBIT: invalid Superblock magic 0x%016X", magic64));
        }
        int version16 = buf.getShort(offset + 0x08) & 0xFFFF;
        int width16 = buf.getShort(offset + 0x0A) & 0xFFFF;
        int height16 = buf.getShort(offset + 0x0C) & 0xFFFF;
        int depthK8 = buf.get(offset + 0x0E) & 0xFF;
        int bitDepth8 = buf.get(offset + 0x0F) & 0xFF;
        int colorSpace8 = buf.get(offset + 0x10) & 0xFF;
        int flags8 = buf.get(offset + 0x11) & 0xFF;
        int tilesX16 = buf.getShort(offset + 0x12) & 0xFFFF;
        int tilesY16 = buf.getShort(offset + 0x14) & 0xFFFF;
        int roiTileX16 = buf.getShort(offset + 0x16) & 0xFFFF;
        int roiTileY16 = buf.getShort(offset + 0x18) & 0xFFFF;
        int tuningProfile16 = buf.getShort(offset + 0x1A) & 0xFFFF;
        int msbPoolLen32 = buf.getInt(offset + 0x1C);
        int lsbPoolLen32 = buf.getInt(offset + 0x20);
        long frameIndex64 = buf.getLong(offset + 0x24);
        long prevSbOffset64 = buf.getLong(offset + 0x2C);
        int fpsNum32 = buf.getInt(offset + 0x34);
        int fpsDen32 = buf.getInt(offset + 0x38);
        int headerCrc32 = buf.getInt(offset + 0x3C);
        int expectedCrc = LcvStreamContainer.computeHeaderCrc32(src, offset);
        if (headerCrc32 != expectedCrc) {
            throw new LcvCorruptTileException("CBIT: embedded SuperblockHeader CRC32 mismatch");
        }
        return new LcvStreamContainer.SuperblockHeader(
            magic64, version16, width16, height16, depthK8, bitDepth8, colorSpace8, flags8,
            tilesX16, tilesY16, roiTileX16, roiTileY16, tuningProfile16,
            msbPoolLen32, lsbPoolLen32, frameIndex64, prevSbOffset64, fpsNum32, fpsDen32, headerCrc32
        );
    }

    /**
     * Reconstructs a Superblock from an arbitrary subset of arrived {@code LCVU} UDP datagrams
     * (supporting out-of-order delivery, dropped UDP packets, and {@code SEC-DED (72, 64)} single-bit healing).
     */
    public static LcvStreamContainer.SuperblockDecodeResult decodeSuperblockFromUdpDatagrams(
        List<byte[]> arrivedDatagrams, LcvStreamContainer.StreamDecoderState decoderState
    ) throws LcvCorruptTileException {
        LcvStreamContainer.SuperblockHeader header = null;
        List<LcvStreamContainer.MvCoherentPacket> packets = new ArrayList<>(arrivedDatagrams.size());
        for (byte[] dg : arrivedDatagrams) {
            if (dg == null || dg.length < LCV_UDP_HEADER_BYTES) {
                continue;
            }
            ByteBuffer buf = ByteBuffer.wrap(dg).order(ByteOrder.BIG_ENDIAN);
            if (buf.getInt(0) != LCV_UDP_MAGIC) {
                continue;
            }
            if (header == null) {
                header = readEmbeddedSuperblockHeader64(dg, 12);
            }
            int startTileIdx = buf.getShort(76) & 0xFFFF;
            int tileCount = buf.get(78) & 0xFF;
            int packedMv8 = buf.get(79) & 0xFF;
            int msbLen = buf.getShort(80) & 0xFFFF;
            int lsbLen = buf.getShort(82) & 0xFFFF;
            int expectedLen = LCV_UDP_HEADER_BYTES + tileCount * 9 + msbLen + lsbLen;
            if (dg.length < expectedLen) {
                continue;
            }
            buf.position(LCV_UDP_HEADER_BYTES);
            long[] ptrs = new long[tileCount];
            byte[] syns = new byte[tileCount];
            for (int i = 0; i < tileCount; i++) {
                ptrs[i] = buf.getLong();
                syns[i] = buf.get();
            }
            byte[] msb = new byte[msbLen];
            buf.get(msb);
            byte[] lsb = new byte[lsbLen];
            buf.get(lsb);
            packets.add(new LcvStreamContainer.MvCoherentPacket(
                startTileIdx, tileCount, packedMv8, ptrs, syns, msb, lsb
            ));
        }
        if (header == null) {
            throw new IllegalArgumentException("No valid LCVU UDP datagrams arrived for superblock");
        }
        return LcvStreamContainer.decodeMvPacketStream(header, packets, decoderState);
    }

    /**
     * Packetizes and transmits a single Superblock as {@code LCVU} UDP datagrams over {@code channel}.
     */
    public static int transmitSuperblockUdp(
        java.nio.channels.DatagramChannel channel,
        java.net.SocketAddress target,
        byte[] sbBytes,
        int superblockSeq,
        int mtuBytes,
        boolean shedLsb
    ) throws IOException {
        try {
            List<byte[]> datagrams = serializeUdpDatagramsForSuperblock(sbBytes, superblockSeq, mtuBytes, shedLsb);
            int sent = 0;
            for (byte[] dg : datagrams) {
                channel.send(ByteBuffer.wrap(dg), target);
                sent++;
            }
            return sent;
        } catch (LcvCorruptTileException e) {
            throw new IOException("Failed to packetize superblock for UDP transmission", e);
        }
    }
    /**
     * Performs compressed-domain {@link LcvOnDiskTransformer#cropSubRegion} across all superblocks
     * in {@code input} and writes the resulting standalone {@code .mkv} ({@code V_LCV1}) or {@code .lcv}
     * stream to {@code output}.
     */
    public static TransformResult cropContainerFile(
        Path input, Path output, int tileX0, int tileY0, int cropTilesX, int cropTilesY
    ) throws IOException, LcvCorruptTileException {
        byte[] srcBytes = Files.readAllBytes(input);
        List<byte[]> srcSuperblocks = extractSuperblocks(srcBytes);
        if (srcSuperblocks.isEmpty()) {
            throw new IllegalArgumentException("No valid Superblocks found in " + input);
        }

        LcvStreamContainer.SuperblockHeader firstHdr = LcvStreamContainer.readSuperblockHeader(srcSuperblocks.get(0), 0);
        int srcWidth = firstHdr.width16();
        int srcHeight = firstHdr.height16();
        int fps = Math.max(1, firstHdr.fpsNum32() / Math.max(1, firstHdr.fpsDen32()));

        List<byte[]> croppedSuperblocks = new ArrayList<>(srcSuperblocks.size());
        long t0 = System.nanoTime();
        for (byte[] sb : srcSuperblocks) {
            croppedSuperblocks.add(LcvOnDiskTransformer.cropSubRegion(sb, tileX0, tileY0, cropTilesX, cropTilesY));
        }
        long elapsedNs = System.nanoTime() - t0;

        int dstWidth = cropTilesX * LcvPrimitives.TILE_DIM;
        int dstHeight = cropTilesY * LcvPrimitives.TILE_DIM;
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        writeContainerOutput(output, croppedSuperblocks, dstWidth, dstHeight, fps);

        return new TransformResult(
            srcWidth, srcHeight, dstWidth, dstHeight,
            croppedSuperblocks.size(), croppedSuperblocks.size() * firstHdr.depthK8(),
            srcBytes.length, Files.size(output), elapsedNs / 1_000_000.0
        );
    }

    /**
     * Performs compressed-domain {@link LcvOnDiskTransformer#stripLsbPlanes} across all superblocks
     * in {@code input} and writes the resulting proxy {@code .mkv} ({@code V_LCV1}) or {@code .lcv}
     * stream to {@code output}.
     */
    public static TransformResult stripLsbContainerFile(Path input, Path output)
        throws IOException, LcvCorruptTileException {
        byte[] srcBytes = Files.readAllBytes(input);
        List<byte[]> srcSuperblocks = extractSuperblocks(srcBytes);
        if (srcSuperblocks.isEmpty()) {
            throw new IllegalArgumentException("No valid Superblocks found in " + input);
        }

        LcvStreamContainer.SuperblockHeader firstHdr = LcvStreamContainer.readSuperblockHeader(srcSuperblocks.get(0), 0);
        int width = firstHdr.width16();
        int height = firstHdr.height16();
        int fps = Math.max(1, firstHdr.fpsNum32() / Math.max(1, firstHdr.fpsDen32()));

        List<byte[]> strippedSuperblocks = new ArrayList<>(srcSuperblocks.size());
        long t0 = System.nanoTime();
        for (byte[] sb : srcSuperblocks) {
            strippedSuperblocks.add(LcvOnDiskTransformer.stripLsbPlanes(sb));
        }
        long elapsedNs = System.nanoTime() - t0;

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        writeContainerOutput(output, strippedSuperblocks, width, height, fps);

        return new TransformResult(
            width, height, width, height,
            strippedSuperblocks.size(), strippedSuperblocks.size() * firstHdr.depthK8(),
            srcBytes.length, Files.size(output), elapsedNs / 1_000_000.0
        );
    }

    /**
     * Post-processes a {@code .mkv} ({@code V_LCV1}) or {@code .lcv} container in the compressed domain
     * (applying optional {@code coalesceRunsByDistance}, {@code stripLsbPlanes}, and {@code wrapZstdPools})
     * without decoding pixels or Hilbert curves. When {@code inPlaceNonTemporal == true} on a {@code .lcv}
     * stream, shrinks superblocks in place using {@code FLAG_SUPERSEDED_OR_SLACK} slack records.
     */
    public static TransformResult postprocessContainerFile(
        Path input, Path output, boolean stripLsb,
        LcvCubeCodec.DistanceRlePolicy coalescePolicy, boolean wrapZstd, boolean inPlaceNonTemporal
    ) throws IOException, LcvCorruptTileException {
        byte[] srcBytes = Files.readAllBytes(input);
        List<byte[]> srcSuperblocks = extractSuperblocks(srcBytes);
        if (srcSuperblocks.isEmpty()) {
            throw new IllegalArgumentException("No valid Superblocks found in " + input);
        }
        LcvStreamContainer.SuperblockHeader firstHdr = LcvStreamContainer.readSuperblockHeader(srcSuperblocks.get(0), 0);
        int width = firstHdr.width16();
        int height = firstHdr.height16();
        int fps = Math.max(1, firstHdr.fpsNum32() / Math.max(1, firstHdr.fpsDen32()));

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        long t0 = System.nanoTime();
        String outName = output.getFileName().toString().toLowerCase(Locale.ROOT);
        if (inPlaceNonTemporal && outName.endsWith(".lcv") && input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lcv")) {
            byte[] inPlaceBytes = LcvOnDiskTransformer.postprocessStreamInPlace(srcBytes, stripLsb, coalescePolicy, wrapZstd);
            long elapsedNs = System.nanoTime() - t0;
            Files.write(output, inPlaceBytes);
            List<Integer> active = LcvStreamContainer.indexValidSuperblocks(inPlaceBytes);
            return new TransformResult(
                width, height, width, height,
                active.size(), active.size() * firstHdr.depthK8(),
                srcBytes.length, Files.size(output), elapsedNs / 1_000_000.0
            );
        }

        List<byte[]> postSuperblocks = new ArrayList<>(srcSuperblocks.size());
        for (byte[] sb : srcSuperblocks) {
            byte[] cur = sb;
            if (coalescePolicy != null && coalescePolicy.losslessPlaneCutoff() > 0) {
                LcvStreamContainer.SuperblockHeader h = LcvStreamContainer.readSuperblockHeader(cur, 0);
                cur = LcvOnDiskTransformer.coalesceRunsByDistance(cur, h.roiTileX16(), h.roiTileY16(), coalescePolicy);
            }
            if (stripLsb) {
                cur = LcvOnDiskTransformer.stripLsbPlanes(cur);
            }
            if (wrapZstd) {
                cur = LcvOnDiskTransformer.wrapZstdPools(cur);
            }
            postSuperblocks.add(cur);
        }
        long elapsedNs = System.nanoTime() - t0;
        writeContainerOutput(output, postSuperblocks, width, height, fps);
        return new TransformResult(
            width, height, width, height,
            postSuperblocks.size(), postSuperblocks.size() * firstHdr.depthK8(),
            srcBytes.length, Files.size(output), elapsedNs / 1_000_000.0
        );
    }

    /**
     * Splices an edited superblock (or superblock stream) into {@code baseFile} without requiring
     * temporal order on disk (using in-place {@code FLAG_SUPERSEDED_OR_SLACK} or out-of-order {@code EOF}
     * append). Readers (`inspect`, `play`, `decode-raw`, `export-carrier`) automatically resolve active
     * superblocks in presentation {@code frameIndex64} order.
     */
    public static TransformResult spliceContainerFileNonTemporal(Path baseFile, Path patchFile, Path output)
        throws IOException, LcvCorruptTileException {
        byte[] baseBytes = Files.readAllBytes(baseFile);
        byte[] patchBytes = Files.readAllBytes(patchFile);
        List<byte[]> patchSuperblocks = extractSuperblocks(patchBytes);
        if (patchSuperblocks.isEmpty()) {
            throw new IllegalArgumentException("No valid Superblocks found in patch " + patchFile);
        }
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

        String outName = output.getFileName().toString().toLowerCase(Locale.ROOT);
        long t0 = System.nanoTime();
        if (outName.endsWith(".lcv") && baseFile.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".lcv")) {
            byte[] current = baseBytes;
            for (byte[] patchSb : patchSuperblocks) {
                current = LcvOnDiskTransformer.spliceEditedSuperblockNonTemporal(current, patchSb);
            }
            long elapsedNs = System.nanoTime() - t0;
            Files.write(output, current);
            List<byte[]> resolved = extractSuperblocks(current);
            LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(resolved.get(0), 0);
            return new TransformResult(
                hdr.width16(), hdr.height16(), hdr.width16(), hdr.height16(),
                resolved.size(), resolved.size() * hdr.depthK8(),
                baseBytes.length, Files.size(output), elapsedNs / 1_000_000.0
            );
        }

        // For .mkv (V_LCV1), append out-of-order Cluster/SimpleBlock packets directly at EOF without rewriting preceding clusters!
        ByteArrayOutputStream mkvAppendOut = new ByteArrayOutputStream(baseBytes.length + patchBytes.length + 128);
        mkvAppendOut.writeBytes(baseBytes);
        for (byte[] patchSb : patchSuperblocks) {
            LcvStreamContainer.SuperblockHeader h = LcvStreamContainer.readSuperblockHeader(patchSb, 0);
            int tsMs = (int) ((h.frameIndex64() * 1000L) / Math.max(1, h.fpsNum32()));
            LcvMkvPacketizer.appendClusterSimpleBlock(mkvAppendOut, tsMs, patchSb);
        }
        byte[] outBytes = mkvAppendOut.toByteArray();
        long elapsedNs = System.nanoTime() - t0;
        Files.write(output, outBytes);
        List<byte[]> resolved = extractSuperblocks(outBytes);
        LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(resolved.get(0), 0);
        return new TransformResult(
            hdr.width16(), hdr.height16(), hdr.width16(), hdr.height16(),
            resolved.size(), resolved.size() * hdr.depthK8(),
            baseBytes.length, Files.size(output), elapsedNs / 1_000_000.0
        );
    }

    /**
     * Sweeps a non-temporal or slack-padded {@code .lcv} or {@code .mkv (V_LCV1)} file and writes a
     * clean, contiguous temporally ordered container with refreshed {@code prevSbOffset64} and {@code headerCrc32}.
     */
    public static TransformResult compactContainerFileToTemporalOrder(Path input, Path output)
        throws IOException, LcvCorruptTileException {
        byte[] srcBytes = Files.readAllBytes(input);
        long t0 = System.nanoTime();
        List<byte[]> resolved = extractSuperblocks(srcBytes);
        if (resolved.isEmpty()) {
            throw new IllegalArgumentException("No valid Superblocks found in " + input);
        }
        LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(resolved.get(0), 0);
        int width = hdr.width16();
        int height = hdr.height16();
        int fps = Math.max(1, hdr.fpsNum32() / Math.max(1, hdr.fpsDen32()));
        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }
        writeContainerOutput(output, resolved, width, height, fps);
        long elapsedNs = System.nanoTime() - t0;
        return new TransformResult(
            width, height, width, height,
            resolved.size(), resolved.size() * hdr.depthK8(),
            srcBytes.length, Files.size(output), elapsedNs / 1_000_000.0
        );
    }

    /**
     * Recorder-agnostic directory ingest scanning {@code recordingDir} for standard capture files
     * ({@code .mp4}, {@code .mkv}, {@code .mov}, {@code .flv}, {@code .ts} from OBS Studio, Streamlabs,
     * Meld Studio, NVIDIA ShadowPlay, AMD ReLive, Windows Game Bar, etc.), encoding each into
     * {@code _v_lcv1.mkv}, optionally post-processing with {@code wrapZstdPools}, and optionally
     * extracting a {@code < 1.2 ms} 9:16 vertical short crop.
     */
    public static List<Path> batchIngestRecordingDirectory(
        Path recordingDir, Path outputDir,
        int width, int height, int depthK, int fps,
        LcvCubeCodec.TuningProfile profile, boolean postprocessZstd, boolean autoCrop9x16
    ) throws IOException, InterruptedException, LcvCorruptTileException {
        if (!Files.isDirectory(recordingDir)) {
            throw new IllegalArgumentException("Not a directory: " + recordingDir);
        }
        Files.createDirectories(outputDir);
        int alignedW = alignTileDim(width);
        int alignedH = alignTileDim(height);
        int roiX = (alignedW / LcvPrimitives.TILE_DIM) / 2;
        int roiY = (alignedH / LcvPrimitives.TILE_DIM) / 2;

        List<Path> produced = new ArrayList<>();
        try (var stream = Files.list(recordingDir)) {
            List<Path> candidates = stream
                .filter(Files::isRegularFile)
                .sorted()
                .toList();
            for (Path file : candidates) {
                String name = file.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (lower.contains("_v_lcv1") || lower.endsWith(".lcv")) {
                    continue;
                }
                if (!(lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".mov")
                    || lower.endsWith(".flv") || lower.endsWith(".ts"))) {
                    continue;
                }
                int dot = name.lastIndexOf('.');
                String stem = (dot > 0) ? name.substring(0, dot) : name;
                Path outMkv = outputDir.resolve(stem + "_v_lcv1.mkv");
                streamIngestVideoToFile(
                    file, alignedW, alignedH, depthK, fps, roiX, roiY, profile, false, false, outMkv
                );
                if (postprocessZstd) {
                    postprocessContainerFile(outMkv, outMkv, false, null, true, false);
                }
                produced.add(outMkv);
                if (autoCrop9x16) {
                    int srcTilesX = alignedW / LcvPrimitives.TILE_DIM;
                    int srcTilesY = alignedH / LcvPrimitives.TILE_DIM;
                    int[] bounds = resolveCropBounds(Map.of(), "9x16", srcTilesX, srcTilesY);
                    Path shortMkv = outputDir.resolve(stem + "_9x16_v_lcv1.mkv");
                    cropContainerFile(outMkv, shortMkv, bounds[0], bounds[1], bounds[2], bounds[3]);
                }
            }
        }
        return produced;
    }

    /**
     * Inspects and validates all recoverable {@code Superblock} packets in a {@code .mkv} ({@code V_LCV1})
     * or {@code .lcv} file, accumulating {@code SEC-DED (72, 64)} and tile telemetry.
     */
    public static StreamInspectionReport inspectContainerFile(Path input)
        throws IOException, LcvCorruptTileException {
        byte[] fileBytes = Files.readAllBytes(input);
        List<byte[]> superblocks = extractSuperblocks(fileBytes);
        if (superblocks.isEmpty()) {
            throw new IllegalArgumentException("No valid LCV_V001 Superblocks found in " + input);
        }

        LcvStreamContainer.SuperblockHeader first = LcvStreamContainer.readSuperblockHeader(superblocks.get(0), 0);
        int width = first.width16();
        int height = first.height16();
        int depthK = first.depthK8();
        int fps = Math.max(1, first.fpsNum32() / Math.max(1, first.fpsDen32()));
        boolean lsbStripped = (first.flags8() & LcvStreamContainer.FLAG_LSB_STRIPPED) != 0;

        LcvStreamContainer.StreamDecoderState decState =
            new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        int healed = 0;
        int quarantined = 0;
        int cleanAck = 0;
        int uniform = 0;

        for (byte[] sb : superblocks) {
            LcvStreamContainer.SuperblockDecodeResult dec = LcvStreamContainer.decodeSuperblock(sb, 0, decState);
            healed += dec.healedSingleBitPointers();
            quarantined += dec.quarantinedTiles();
            cleanAck += dec.cleanAckReusedTiles();
            uniform += dec.uniformInlineTiles();
        }

        return new StreamInspectionReport(
            width, height, depthK, fps,
            superblocks.size(), superblocks.size() * depthK,
            fileBytes.length, lsbStripped,
            healed, quarantined, cleanAck, uniform
        );
    }

    /**
     * Decodes a {@code .mkv} ({@code V_LCV1}) or {@code .lcv} file into contiguous raw big-endian
     * {@code RGBA} frame bytes ({@code ffmpeg -f rawvideo -pixel_format rgba}).
     */
    public static byte[] decodeContainerToRawRgbaBytes(Path input)
        throws IOException, LcvCorruptTileException {
        byte[] fileBytes = Files.readAllBytes(input);
        List<byte[]> superblocks = extractSuperblocks(fileBytes);
        return decodeSuperblocksToRawRgbaBytes(superblocks);
    }

    /**
     * Decodes a list of {@code Superblock} payloads into contiguous raw big-endian {@code RGBA} bytes.
     */
    public static byte[] decodeSuperblocksToRawRgbaBytes(List<byte[]> superblocks)
        throws LcvCorruptTileException {
        if (superblocks.isEmpty()) {
            return new byte[0];
        }
        LcvStreamContainer.SuperblockHeader first = LcvStreamContainer.readSuperblockHeader(superblocks.get(0), 0);
        int width = first.width16();
        int height = first.height16();
        int depthK = first.depthK8();
        int sbPixels = width * height * depthK;

        byte[] rawOut = new byte[superblocks.size() * sbPixels * 4];
        ByteBuffer outBuf = ByteBuffer.wrap(rawOut).order(ByteOrder.BIG_ENDIAN);
        LcvStreamContainer.StreamDecoderState decState =
            new LcvStreamContainer.StreamDecoderState(width, height, depthK);

        for (int i = 0; i < superblocks.size(); i++) {
            LcvStreamContainer.SuperblockDecodeResult dec =
                LcvStreamContainer.decodeSuperblockParallel(superblocks.get(i), 0, decState);
            outBuf.position(i * sbPixels * 4);
            outBuf.asIntBuffer().put(dec.rgbaFrames());
        }
        return rawOut;
    }

    /**
     * Streams Superblocks from {@code input}, applies optional compressed-domain
     * {@link LcvOnDiskTransformer#cropSubRegion} and/or {@link LcvOnDiskTransformer#stripLsbPlanes}
     * per Superblock before pixel decode, decodes each Superblock into a reusable {@code 1-Superblock}
     * ring buffer, and writes the raw big-endian {@code RGBA} frame bytes directly to {@code rawRgbaOut}.
     */
    public static int streamDecodeSuperblocksToPipe(
        Path input, OutputStream rawRgbaOut,
        Integer tileX, Integer tileY, Integer tilesW, Integer tilesH,
        boolean stripLsb, int maxFrames
    ) throws IOException, LcvCorruptTileException {
        byte[] fileBytes = Files.readAllBytes(input);
        List<byte[]> superblocks = extractSuperblocks(fileBytes);
        if (superblocks.isEmpty()) {
            throw new IllegalArgumentException("No valid Superblocks found in " + input);
        }

        boolean doCrop = tileX != null && tileY != null && tilesW != null && tilesH != null;
        byte[] firstTransformed = superblocks.get(0);
        if (doCrop) {
            firstTransformed = LcvOnDiskTransformer.cropSubRegion(firstTransformed, tileX, tileY, tilesW, tilesH);
        }
        if (stripLsb) {
            firstTransformed = LcvOnDiskTransformer.stripLsbPlanes(firstTransformed);
        }

        LcvStreamContainer.SuperblockHeader firstHdr = LcvStreamContainer.readSuperblockHeader(firstTransformed, 0);
        int width = firstHdr.width16();
        int height = firstHdr.height16();
        int depthK = firstHdr.depthK8();
        int frameBytes = width * height * 4;
        int sbPixels = width * height * depthK;

        byte[] sbRawBytes = new byte[sbPixels * 4];
        ByteBuffer sbRawBuf = ByteBuffer.wrap(sbRawBytes).order(ByteOrder.BIG_ENDIAN);
        LcvStreamContainer.StreamDecoderState decState =
            new LcvStreamContainer.StreamDecoderState(width, height, depthK);

        int framesWritten = 0;
        for (int i = 0; i < superblocks.size(); i++) {
            byte[] sb = (i == 0) ? firstTransformed : superblocks.get(i);
            if (i > 0) {
                if (doCrop) {
                    sb = LcvOnDiskTransformer.cropSubRegion(sb, tileX, tileY, tilesW, tilesH);
                }
                if (stripLsb) {
                    sb = LcvOnDiskTransformer.stripLsbPlanes(sb);
                }
            }
            LcvStreamContainer.SuperblockDecodeResult dec =
                LcvStreamContainer.decodeSuperblockParallel(sb, 0, decState);
            sbRawBuf.position(0);
            sbRawBuf.asIntBuffer().put(dec.rgbaFrames());

            int framesFromSb = depthK;
            if (maxFrames > 0) {
                framesFromSb = Math.min(depthK, maxFrames - framesWritten);
            }
            if (framesFromSb > 0) {
                rawRgbaOut.write(sbRawBytes, 0, framesFromSb * frameBytes);
                framesWritten += framesFromSb;
            }
            if (maxFrames > 0 && framesWritten >= maxFrames) {
                break;
            }
        }
        return framesWritten;
    }

    /**
     * Applies optional compressed-domain {@code cropSubRegion} and/or {@code stripLsbPlanes}, then
     * streams decoded {@code RGBA} frames Superblock-by-Superblock in bounded {@code O(1)} memory
     * through {@code ffmpeg} to produce an NLE-native video carrier ({@code qtrle} {@code .mov}
     * with full 8-bit alpha, {@code ffv1} {@code .mkv}, or {@code mp4}).
     */
    public static void exportToEditorCarrier(
        Path input, Path output, String carrier,
        Integer tileX, Integer tileY, Integer tilesW, Integer tilesH, boolean stripLsb
    ) throws IOException, InterruptedException, LcvCorruptTileException {
        byte[] fileBytes = Files.readAllBytes(input);
        List<byte[]> superblocks = extractSuperblocks(fileBytes);
        if (superblocks.isEmpty()) {
            throw new IllegalArgumentException("No valid Superblocks found in " + input);
        }
        byte[] firstSb = superblocks.get(0);
        if (tileX != null && tileY != null && tilesW != null && tilesH != null) {
            firstSb = LcvOnDiskTransformer.cropSubRegion(firstSb, tileX, tileY, tilesW, tilesH);
        }
        if (stripLsb) {
            firstSb = LcvOnDiskTransformer.stripLsbPlanes(firstSb);
        }
        LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(firstSb, 0);
        int width = hdr.width16();
        int height = hdr.height16();
        int fps = Math.max(1, hdr.fpsNum32() / Math.max(1, hdr.fpsDen32()));

        if (output.getParent() != null) {
            Files.createDirectories(output.getParent());
        }

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
        cmd.add(String.valueOf(fps));
        cmd.add("-i");
        cmd.add("pipe:0");

        switch (carrier.toLowerCase(Locale.ROOT)) {
            case "qtrle", "mov", "blender", "aftereffects" -> {
                cmd.add("-c:v");
                cmd.add("qtrle");
                cmd.add("-pix_fmt");
                cmd.add("argb");
            }
            case "prores_4444", "prores4444", "resolve_alpha", "premiere_alpha", "fcp_alpha" -> {
                cmd.add("-c:v");
                cmd.add("prores_ks");
                cmd.add("-profile:v");
                cmd.add("4444");
                cmd.add("-pix_fmt");
                cmd.add("yuva444p10le");
            }
            case "prores_422hq", "prores", "resolve", "premiere", "fcp" -> {
                cmd.add("-c:v");
                cmd.add("prores_ks");
                cmd.add("-profile:v");
                cmd.add("3");
                cmd.add("-pix_fmt");
                cmd.add("yuv422p10le");
            }
            case "utvideo", "vegas_alpha" -> {
                cmd.add("-c:v");
                cmd.add("utvideo");
                cmd.add("-pix_fmt");
                cmd.add("rgba");
            }
            case "png", "png-mov" -> {
                cmd.add("-c:v");
                cmd.add("png");
                cmd.add("-pix_fmt");
                cmd.add("rgba");
            }
            case "ffv1", "kdenlive", "shotcut" -> {
                cmd.add("-c:v");
                cmd.add("ffv1");
                cmd.add("-level");
                cmd.add("3");
                cmd.add("-pix_fmt");
                cmd.add("bgra");
            }
            case "mp4", "h264", "capcut", "vegas", "universal" -> {
                cmd.add("-c:v");
                cmd.add("libx264");
                cmd.add("-preset");
                cmd.add("ultrafast");
                cmd.add("-crf");
                cmd.add("18");
                cmd.add("-pix_fmt");
                cmd.add("yuv420p");
            }
            case "h264_nvenc", "nvenc" -> {
                cmd.add("-c:v");
                cmd.add("h264_nvenc");
                cmd.add("-rc");
                cmd.add("constqp");
                cmd.add("-qp");
                cmd.add("18");
                cmd.add("-preset");
                cmd.add("p5");
                cmd.add("-pix_fmt");
                cmd.add("yuv420p");
            }
            case "hevc_nvenc" -> {
                cmd.add("-c:v");
                cmd.add("hevc_nvenc");
                cmd.add("-rc");
                cmd.add("constqp");
                cmd.add("-qp");
                cmd.add("18");
                cmd.add("-preset");
                cmd.add("p5");
                cmd.add("-pix_fmt");
                cmd.add("yuv420p");
            }
            default -> throw new IllegalArgumentException("Unsupported carrier format: " + carrier);
        }
        cmd.add(output.toString());

        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (OutputStream stdin = new BufferedOutputStream(proc.getOutputStream(), 1 << 20)) {
            streamDecodeSuperblocksToPipe(input, stdin, tileX, tileY, tilesW, tilesH, stripLsb, 0);
        }
        byte[] outLog = proc.getInputStream().readAllBytes();
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("ffmpeg carrier export failed (exit " + exit + "): " + new String(outLog));
        }
    }

    /**
     * Streams decoded {@code RGBA} frames (after optional compressed-domain crop/strip) into
     * {@code ffplay} (or headless {@code ffmpeg -f null -} when {@code headless == true} or no GUI
     * display is attached).
     */
    public static int playContainerInFfplay(
        Path input, Integer tileX, Integer tileY, Integer tilesW, Integer tilesH,
        boolean stripLsb, boolean headless, int maxFrames
    ) throws IOException, InterruptedException, LcvCorruptTileException {
        byte[] fileBytes = Files.readAllBytes(input);
        List<byte[]> superblocks = extractSuperblocks(fileBytes);
        if (superblocks.isEmpty()) {
            throw new IllegalArgumentException("No valid Superblocks found in " + input);
        }
        byte[] firstSb = superblocks.get(0);
        if (tileX != null && tileY != null && tilesW != null && tilesH != null) {
            firstSb = LcvOnDiskTransformer.cropSubRegion(firstSb, tileX, tileY, tilesW, tilesH);
        }
        if (stripLsb) {
            firstSb = LcvOnDiskTransformer.stripLsbPlanes(firstSb);
        }
        LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(firstSb, 0);
        int width = hdr.width16();
        int height = hdr.height16();
        int fps = Math.max(1, hdr.fpsNum32() / Math.max(1, hdr.fpsDen32()));

        boolean hasDisplay = System.getenv("DISPLAY") != null || System.getenv("WAYLAND_DISPLAY") != null;
        boolean useHeadlessFfmpeg = headless || !hasDisplay;

        List<String> cmd = new ArrayList<>();
        if (useHeadlessFfmpeg) {
            cmd.add("ffmpeg");
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
            cmd.add(String.valueOf(fps));
            cmd.add("-i");
            cmd.add("pipe:0");
            if (maxFrames > 0) {
                cmd.add("-frames:v");
                cmd.add(String.valueOf(maxFrames));
            }
            cmd.add("-f");
            cmd.add("null");
            cmd.add("-");
        } else {
            cmd.add("ffplay");
            cmd.add("-hide_banner");
            cmd.add("-loglevel");
            cmd.add("error");
            cmd.add("-autoexit");
            cmd.add("-window_title");
            cmd.add("LeafVideo (V_LCV1) " + width + "x" + height + " @ " + fps + "fps");
            cmd.add("-f");
            cmd.add("rawvideo");
            cmd.add("-pixel_format");
            cmd.add("rgba");
            cmd.add("-video_size");
            cmd.add(width + "x" + height);
            cmd.add("-framerate");
            cmd.add(String.valueOf(fps));
            if (maxFrames > 0) {
                cmd.add("-frames:v");
                cmd.add(String.valueOf(maxFrames));
            }
            cmd.add("-i");
            cmd.add("pipe:0");
        }

        Process proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        try (OutputStream stdin = new BufferedOutputStream(proc.getOutputStream(), 1 << 20)) {
            streamDecodeSuperblocksToPipe(input, stdin, tileX, tileY, tilesW, tilesH, stripLsb, maxFrames);
        } catch (IOException ignored) {
            // Player window closed early after reading maxFrames
        }
        try (InputStream stdout = proc.getInputStream()) {
            stdout.readAllBytes();
        }
        return proc.waitFor();
    }

    /**
     * Extracts all intact {@code Superblock} byte arrays from either a Matroska {@code .mkv}
     * ({@code V_LCV1}) stream or a raw concatenated {@code .lcv} stream.
     */
    public static List<byte[]> extractSuperblocks(byte[] containerBytes) {
        List<LcvMkvPacketizer.MkvSuperblockPacket> packets =
            LcvMkvPacketizer.extractSuperblocksFromMkv(containerBytes);
        List<byte[]> superblocks = new ArrayList<>(packets.size());
        for (LcvMkvPacketizer.MkvSuperblockPacket pkt : packets) {
            superblocks.add(pkt.superblockBytes());
        }
        return superblocks;
    }

    /**
     * Streams any external video file ({@code .mp4}, {@code .mov}, {@code .mkv}) directly from
     * {@code ffmpeg -hwaccel auto} stdout into {@link #encodeRawRgbaStreamToFile} in bounded
     * {@code 1-Superblock} memory without materializing the full raw video in heap RAM.
     */
    public static int streamIngestVideoToFile(
        Path videoPath, int width, int height, int depthK, int fps,
        int roiTileX, int roiTileY, LcvCubeCodec.TuningProfile profile,
        boolean useZstd, boolean stripLsbInline, Path output
    ) throws IOException, InterruptedException {
        List<String> cmd = List.of(
            "ffmpeg", "-hide_banner", "-loglevel", "error",
            "-hwaccel", "auto",
            "-i", videoPath.toString(),
            "-vf", "scale=" + width + ":" + height + ":flags=neighbor",
            "-f", "rawvideo",
            "-pix_fmt", "rgba",
            "pipe:1"
        );
        Process proc = new ProcessBuilder(cmd).start();
        int sbCount;
        try (InputStream stdout = new BufferedInputStream(proc.getInputStream(), 1 << 20)) {
            sbCount = encodeRawRgbaStreamToFile(
                stdout, width, height, depthK, fps, roiTileX, roiTileY, profile, useZstd, stripLsbInline, output
            );
        }
        byte[] errBytes;
        try (InputStream stderr = proc.getErrorStream()) {
            errBytes = stderr.readAllBytes();
        }
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("ffmpeg streaming video ingest failed (exit " + exit + "): " + new String(errBytes));
        }
        return sbCount;
    }

    /**
     * Decodes any external video file ({@code .mp4}, {@code .mov}, {@code .mkv}) into raw big-endian
     * {@code RGBA} frame bytes via {@code ffmpeg}.
     */
    public static byte[] extractRawRgbaViaFfmpeg(Path videoPath, int width, int height)
        throws IOException, InterruptedException {
        List<String> cmd = List.of(
            "ffmpeg", "-hide_banner", "-loglevel", "error",
            "-hwaccel", "auto",
            "-i", videoPath.toString(),
            "-vf", "scale=" + width + ":" + height + ":flags=neighbor",
            "-f", "rawvideo",
            "-pix_fmt", "rgba",
            "pipe:1"
        );
        Process proc = new ProcessBuilder(cmd).start();
        byte[] rawBytes;
        try (InputStream stdout = proc.getInputStream()) {
            rawBytes = stdout.readAllBytes();
        }
        byte[] errBytes;
        try (InputStream stderr = proc.getErrorStream()) {
            errBytes = stderr.readAllBytes();
        }
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("ffmpeg video ingest failed (exit " + exit + "): " + new String(errBytes));
        }
        return rawBytes;
    }

    private static List<byte[]> loadAndTransformSuperblocks(
        Path input, Integer tileX, Integer tileY, Integer tilesW, Integer tilesH, boolean stripLsb
    ) throws IOException {
        byte[] fileBytes = Files.readAllBytes(input);
        List<byte[]> superblocks = extractSuperblocks(fileBytes);
        if (tileX != null && tileY != null && tilesW != null && tilesH != null) {
            List<byte[]> cropped = new ArrayList<>(superblocks.size());
            for (byte[] sb : superblocks) {
                cropped.add(LcvOnDiskTransformer.cropSubRegion(sb, tileX, tileY, tilesW, tilesH));
            }
            superblocks = cropped;
        }
        if (stripLsb) {
            List<byte[]> stripped = new ArrayList<>(superblocks.size());
            for (byte[] sb : superblocks) {
                stripped.add(LcvOnDiskTransformer.stripLsbPlanes(sb));
            }
            superblocks = stripped;
        }
        return superblocks;
    }

    private static void writeContainerOutput(
        Path output, List<byte[]> superblocks, int width, int height, int fps
    ) throws IOException {
        String name = output.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".lcv")) {
            ByteArrayOutputStream rawStream = new ByteArrayOutputStream(superblocks.size() * 4096);
            long prevOff = 0L;
            long runningOff = 0L;
            for (byte[] sb : superblocks) {
                byte[] copy = sb.clone();
                ByteBuffer buf = ByteBuffer.wrap(copy).order(ByteOrder.BIG_ENDIAN);
                buf.putLong(0x2C, prevOff);
                int crc = LcvStreamContainer.computeHeaderCrc32(copy, 0);
                buf.putInt(0x3C, crc);
                rawStream.write(copy);
                prevOff = runningOff;
                runningOff += copy.length;
            }
            Files.write(output, rawStream.toByteArray());
        } else {
            byte[] mkvBytes = LcvMkvPacketizer.muxSuperblocksToMkv(superblocks, width, height, fps);
            Files.write(output, mkvBytes);
        }
    }

    private static int[] resolveCropBounds(
        Map<String, String> opts, String preset, int srcTilesX, int srcTilesY
    ) {
        if ("9x16".equals(preset) || "vertical".equals(preset)) {
            int cropTilesY = srcTilesY;
            int cropTilesX = Math.max(1, Math.min(srcTilesX, Math.round(cropTilesY * 9.0f / 16.0f)));
            int tileX0 = (srcTilesX - cropTilesX) / 2;
            return new int[]{tileX0, 0, cropTilesX, cropTilesY};
        }
        if ("facecam".equals(preset)) {
            int cropTilesX = Math.max(1, Math.min(srcTilesX, srcTilesX / 4));
            int cropTilesY = Math.max(1, Math.min(srcTilesY, srcTilesY / 4));
            return new int[]{0, 0, cropTilesX, cropTilesY};
        }
        if (opts.containsKey("px-x") || opts.containsKey("px-y") || opts.containsKey("px-w") || opts.containsKey("px-h")) {
            int canvasW = srcTilesX * LcvPrimitives.TILE_DIM;
            int canvasH = srcTilesY * LcvPrimitives.TILE_DIM;
            int pxX = parseIntOpt(opts, "px-x", 0);
            int pxY = parseIntOpt(opts, "px-y", 0);
            int pxW = parseIntOpt(opts, "px-w", canvasW / 2);
            int pxH = parseIntOpt(opts, "px-h", canvasH / 2);
            return snapPixelRegionToTiles(pxX, pxY, pxW, pxH, srcTilesX, srcTilesY);
        }
        int tileX0 = parseIntOpt(opts, "tile-x", 0);
        int tileY0 = parseIntOpt(opts, "tile-y", 0);
        int cropTilesX = parseIntOpt(opts, "tiles-w", Math.max(1, srcTilesX / 2));
        int cropTilesY = parseIntOpt(opts, "tiles-h", Math.max(1, srcTilesY / 2));
        return new int[]{tileX0, tileY0, cropTilesX, cropTilesY};
    }

    /**
     * Magnetically snaps arbitrary pixel rectangle coordinates {@code (pxX, pxY, pxW, pxH)} onto the
     * nearest {@code 16 x 16} tile grid bounds {@code [tileX0, tileY0, cropTilesX, cropTilesY]}
     * within {@code [0..srcTilesX] x [0..srcTilesY]} so visual UI drag-boxes can invoke zero-decode
     * {@link LcvOnDiskTransformer#cropSubRegion} without exposing tile math to the user.
     */
    public static int[] snapPixelRegionToTiles(
        int pxX, int pxY, int pxW, int pxH, int srcTilesX, int srcTilesY
    ) {
        int tileX0 = Math.max(0, Math.min(srcTilesX - 1, Math.round(pxX / 16.0f)));
        int tileY0 = Math.max(0, Math.min(srcTilesY - 1, Math.round(pxY / 16.0f)));
        int cropTilesX = Math.max(1, Math.min(srcTilesX - tileX0, Math.round(Math.max(16, pxW) / 16.0f)));
        int cropTilesY = Math.max(1, Math.min(srcTilesY - tileY0, Math.round(Math.max(16, pxH) / 16.0f)));
        return new int[]{tileX0, tileY0, cropTilesX, cropTilesY};
    }

    public static int[] synthesizeFrames(
        int width, int height, int depthK, int startFrameIdx, String mode
    ) {
        return generateSampleFrames(width, height, depthK, startFrameIdx, mode);
    }

    /**
     * Generates deterministic visual sample frames (`scene` or `overlay` with 80% `Alpha = 0`
     * transparent background tiles + HUD/crosshair/facecam regions) for player and NLE verification.
     */
    public static int[] generateSampleFrames(
        int width, int height, int depthK, int startFrameIdx, String mode
    ) {
        int framePixels = width * height;
        int[] frames = new int[framePixels * depthK];
        boolean overlayMode = "overlay".equalsIgnoreCase(mode);

        for (int t = 0; t < depthK; t++) {
            int f = startFrameIdx + t;
            int base = t * framePixels;
            int crosshairX = width / 2 + (int) Math.round(Math.sin(f * 0.25) * (width * 0.12));
            int crosshairY = height / 2 + (int) Math.round(Math.cos(f * 0.25) * (height * 0.12));

            for (int y = 0; y < height; y++) {
                int rowOff = base + y * width;
                int ty = y / LcvPrimitives.TILE_DIM;
                for (int x = 0; x < width; x++) {
                    int tx = x / LcvPrimitives.TILE_DIM;
                    if (overlayMode) {
                        // Top-left facecam HUD border + bottom-right status badge; 80%+ tiles are 0x00000000
                        boolean facecamHud = (tx >= 1 && tx <= 4 && ty >= 1 && ty <= 3);
                        boolean badgeHud = (tx >= (width / 16) - 5 && tx < (width / 16) - 1
                            && ty >= (height / 16) - 3 && ty < (height / 16) - 1);
                        if (facecamHud) {
                            boolean border = (x % 16 < 2 || y % 16 < 2);
                            frames[rowOff + x] = border ? 0x00FFCCFF : 0x203850D8;
                        } else if (badgeHud) {
                            int pulse = (180 + (f * 8)) & 0xFF;
                            frames[rowOff + x] = (pulse << 24) | 0x40E0FF;
                        } else {
                            frames[rowOff + x] = 0x00000000;
                        }
                    } else {
                        // Full-canvas scene with distinct regions:
                        // 1. Top-left facecam box (tiles [0..3] x [0..2])
                        // 2. Center 9:16 vertical action corridor with moving focal crosshair
                        // 3. Animated horizon + sky/ground gradient
                        if (tx < 4 && ty < 3) {
                            int r = (80 + tx * 30 + f * 3) & 0xFF;
                            int g = (140 + ty * 30) & 0xFF;
                            int b = 220;
                            frames[rowOff + x] = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                        } else if (Math.abs(x - crosshairX) <= 3 || Math.abs(y - crosshairY) <= 3) {
                            frames[rowOff + x] = 0xFF3040FF;
                        } else {
                            int r = ((x * 220) / Math.max(1, width) + f * 2) & 0xFF;
                            int g = ((y * 220) / Math.max(1, height) + f) & 0xFF;
                            int b = (((x + y) * 180) / Math.max(1, width + height) + f * 3) & 0xFF;
                            frames[rowOff + x] = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                        }
                    }
                }
            }
        }
        return frames;
    }
    public static LcvCubeCodec.TuningProfile resolveProfile(String profileName) {
        if (profileName == null) {
            return LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS;
        }
        return switch (profileName.toUpperCase(Locale.ROOT)) {
            case "ARCHIVAL_LOSSLESS", "STUDIO_ARCHIVAL_LOSSLESS", "STUDIO_ARCHIVAL_ZSTD", "DEFAULT_LOSSLESS", "LOSSLESS" ->
                LcvCubeCodec.TuningProfile.ARCHIVAL_LOSSLESS;
            case "OBS_REALTIME_LOSSLESS", "INSTANT_EDIT_LOSSLESS", "HIGH_QUALITY_COMPACT", "COMPACT_MASTER",
                "PARSEC_LOW_LATENCY", "REMOTE_DESKTOP_K1", "OBS" ->
                LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS;
            case "WIRELESS_FOVEATED_LOW_LATENCY", "FOVEATED_CENTER_FOCUS", "WIRELESS" ->
                LcvCubeCodec.TuningProfile.WIRELESS_FOVEATED_LOW_LATENCY;
            case "HARSH_RF_UEP_ZERO_WAIT", "HARSH_RF", "UEP" ->
                LcvCubeCodec.TuningProfile.HARSH_RF_UEP_ZERO_WAIT;
            default -> LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS;
        };
    }

    public static boolean isLowLatencyParsecPreset(String profileName) {
        if (profileName == null) {
            return false;
        }
        String norm = profileName.toUpperCase(Locale.ROOT);
        return "PARSEC_LOW_LATENCY".equals(norm) || "REMOTE_DESKTOP_K1".equals(norm);
    }

    public static boolean isCompactMasterPreset(String profileName) {
        if (profileName == null) {
            return false;
        }
        String norm = profileName.toUpperCase(Locale.ROOT);
        return "HIGH_QUALITY_COMPACT".equals(norm) || "COMPACT_MASTER".equals(norm);
    }

    private static boolean isDefaultZstdProfile(String profileName, LcvCubeCodec.TuningProfile profile) {
        if (profileName == null) {
            return false;
        }
        return "STUDIO_ARCHIVAL_ZSTD".equalsIgnoreCase(profileName);
    }

    private static int alignTileDim(int dim) {
        int aligned = ((dim + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM) * LcvPrimitives.TILE_DIM;
        return Math.max(LcvPrimitives.TILE_DIM, aligned);
    }

    private static Map<String, String> parseOptions(String[] args, int startIdx) {
        Map<String, String> map = new LinkedHashMap<>();
        int i = startIdx;
        while (i < args.length) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String key = arg.substring(2);
                int eq = key.indexOf('=');
                if (eq >= 0) {
                    map.put(key.substring(0, eq), key.substring(eq + 1));
                    i++;
                } else if (i + 1 < args.length && (!args[i + 1].startsWith("--") || "-".equals(args[i + 1]))) {
                    map.put(key, args[i + 1]);
                    i += 2;
                } else {
                    map.put(key, "true");
                    i++;
                }
            } else {
                i++;
            }
        }
        return map;
    }

    private static String requireOpt(Map<String, String> opts, String key) {
        String val = opts.get(key);
        if (val == null || val.isBlank()) {
            throw new IllegalArgumentException("Missing required option --" + key);
        }
        return val;
    }

    private static int parseIntOpt(Map<String, String> opts, String key, int defaultValue) {
        String val = opts.get(key);
        return (val == null || val.isBlank()) ? defaultValue : Integer.parseInt(val.trim());
    }

    private static void printUsage() {
        System.out.println("""
            LeafVideo CLI (LCV-ICD-1.0 / CodecID = V_LCV1)
            Usage: java -jar leaf-video.jar <subcommand> [options]

            Subcommands:
              sample         Generate a sample .mkv (V_LCV1) or .lcv stream
                             [--output path.mkv] [--width 640] [--height 368] [--depth-k 8]
                             [--superblocks 4] [--fps 60] [--mode scene|overlay] [--profile OBS_REALTIME_LOSSLESS]
              encode         Ingest raw RGBA or an external video file (via ffmpeg) into .mkv (V_LCV1) / .lcv
                             [--from-video input.mp4 | --input raw.rgba|-] --output out.mkv
                             [--width 640] [--height 368] [--depth-k 8] [--fps 60] [--profile ARCHIVAL_LOSSLESS]
                             [--ring-buffer-mb 512] [--replay-seconds 20] [--stream-udp 127.0.0.1:9999]
              crop           Perform < 1.2 ms compressed-domain tile crop without decoding pixels
                             --input in.mkv --output cropped.mkv [--preset 9x16|facecam | --tile-x X --tile-y Y --tiles-w W --tiles-h H]
              strip-lsb      Perform < 1.2 ms compressed-domain LSB bitplane strip (g7..g2 proxy)
                             --input in.mkv --output proxy.mkv
              inspect        Verify SEC-DED (72, 64) pointers and inspect stream metadata
                             --input in.mkv [--json]
              decode-raw     Decode .mkv (V_LCV1) or .lcv to raw big-endian RGBA bytes (file or stdout '-')
                             --input in.mkv [--output out.rgba|-] [--preset 9x16|facecam] [--strip-lsb true]
              export-carrier Decode (with optional compressed-domain crop/strip) into an NLE carrier via ffmpeg
                             --input in.mkv --output strip.mov [--carrier qtrle|prores_4444|png-mov|ffv1|mp4] [--preset 9x16|facecam]
              nle-sync       Synchronize a recording or folder into an NLE workspace with <1 ms 9:16/facecam/proxy + carriers
                             --input in.mkv|dir [--output ~/LeafVideo-NLE] [--carriers qtrle,mp4]
              install-obs    Install native obs-leafvideo.so plugin, Custom Browser Dock, and Python script into OBS Studio
                             [--obs-config-dir ~/.config/obs-studio] [--nle-dir ~/LeafVideo-NLE]
              stream-send    Stream a .mkv (V_LCV1) / .lcv file over low-latency UDP MvCoherentPacket datagrams
                             --input in.mkv --target host:port [--mtu 1200] [--shed-lsb false]
              stream-recv    Receive low-latency UDP MvCoherentPacket datagrams and reconstruct RGBA / .mkv
                             --listen host:port --output out.mkv [--max-superblocks N] [--timeout-ms 5000]
              play           Stream decoded RGBA frames live into ffplay (or headless ffmpeg check)
                             --input in.mkv [--preset 9x16|facecam] [--headless] [--headless-frames N]""");
    }
}
