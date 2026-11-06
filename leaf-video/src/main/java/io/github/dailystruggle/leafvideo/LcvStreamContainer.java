package io.github.dailystruggle.leafvideo;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Append-only self-indexing {@code Superblock} stream container ({@code LCV-ICD-1.0} Sections 2-4)
 * with {@code 64 B} {@code "LCV_V001"} headers, {@code uint64[tilesX * tilesY]} 2D tile pointer
 * directories, Extended Hamming {@code (72, 64)} {@code SEC-DED} pointer self-healing, bounded
 * {@code Case A} ({@code TEMPORAL_CLEAN_ACK}) zero-drift deduplication, {@code CBIT} single-tile
 * fault quarantine, and split Section 4A (Base MSB) / Section 4B (Tail LSB) pools.
 *
 * @governedBy leaf-video-ADR-001
 * @trace REQ-LCV-F-001
 * @trace REQ-LCV-F-003
 * @trace REQ-LCV-F-004
 */
public final class LcvStreamContainer {

    public static final long MAGIC_LCV_V001 = 0x4C43565F_56303031L; // "LCV_V001"
    public static final int FORMAT_VERSION_1_0 = 0x0001;
    public static final int SUPERBLOCK_HEADER_BYTES = 64;

    public static final int FLAG_ZSTD_POOL = 0x01;
    public static final int FLAG_LSB_STRIPPED = 0x02;
    public static final int FLAG_INLINE_RECODE_PARTIAL = 0x04;

    /**
     * Mandatory upper bound on backward superblock hops for {@code TEMPORAL_CLEAN_ACK}
     * ({@code LCV-ICD-1.0} Section 4.1) so random seeking remains strictly {@code O(1)}.
     */
    public static final int MAX_CLEAN_ACK_REFRESH = 8;

    private static final Method ZSTD_COMPRESS;
    private static final Method ZSTD_DECOMPRESS;

    static {
        Method comp = null;
        Method decomp = null;
        try {
            Class<?> zstdClass = Class.forName("com.github.luben.zstd.Zstd");
            comp = zstdClass.getMethod("compress", byte[].class, int.class);
            decomp = zstdClass.getMethod("decompress", byte[].class, int.class);
        } catch (Throwable ignored) {
            // Optional runtime dependency
        }
        ZSTD_COMPRESS = comp;
        ZSTD_DECOMPRESS = decomp;
    }

    private LcvStreamContainer() {}

    /**
     * Parsed 64-byte {@code Superblock} header.
     */
    public record SuperblockHeader(
        long magic64,
        int formatVersion16,
        int width16,
        int height16,
        int depthK8,
        int bitDepth8,
        int colorSpace8,
        int flags8,
        int tilesX16,
        int tilesY16,
        int roiTileX16,
        int roiTileY16,
        int tuningProfile16,
        int msbPoolCompressedLen32,
        int lsbPoolCompressedLen32,
        long frameIndex64,
        long prevSbOffset64,
        int fpsNum32,
        int fpsDen32,
        int headerCrc32
    ) {
        public LcvCubeCodec.TuningProfile tuningProfile() {
            return LcvCubeCodec.TuningProfile.fromPackedU16(tuningProfile16);
        }

        public int totalTiles() {
            return tilesX16 * tilesY16;
        }

        public int directoryOffset() {
            return SUPERBLOCK_HEADER_BYTES;
        }

        public int secDedOffset() {
            return SUPERBLOCK_HEADER_BYTES + totalTiles() * 8;
        }

        public int msbPoolOffset() {
            return secDedOffset() + totalTiles();
        }

        public int lsbPoolOffset() {
            return msbPoolOffset() + msbPoolCompressedLen32;
        }

        public int totalSuperblockBytes() {
            return lsbPoolOffset() + lsbPoolCompressedLen32;
        }
    }

    /**
     * Telemetry and recovery report produced when decoding a {@code Superblock}.
     */
    public record SuperblockDecodeResult(
        SuperblockHeader header,
        int[] rgbaFrames,
        int healedSingleBitPointers,
        int quarantinedTiles,
        int cleanAckReusedTiles,
        int uniformInlineTiles
    ) {}

    /**
     * Stateful encoder state across consecutive superblocks for bounded {@code TEMPORAL_CLEAN_ACK}
     * deduplication.
     */
    public static final class StreamEncoderState {
        private final int tilesX;
        private final int tilesY;
        private final long[] lastTileDigest64;
        private final int[] cleanAckRunLength;
        private final int[] anchorRelOffset;
        private final int[] anchorTotalLen;

        public StreamEncoderState(int width, int height) {
            this.tilesX = (width + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
            this.tilesY = (height + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
            int n = tilesX * tilesY;
            this.lastTileDigest64 = new long[n];
            this.cleanAckRunLength = new int[n];
            this.anchorRelOffset = new int[n];
            this.anchorTotalLen = new int[n];
            Arrays.fill(this.cleanAckRunLength, 65);
        }
    }

    /**
     * Stateful decoder state caching the prior superblock's decoded RGBA cubes for {@code Case A}
     * clean-acks and {@code CBIT} corrupt-tile fallback.
     */
    public static final class StreamDecoderState {
        private final int width;
        private final int height;
        private final int depthK;
        private final int[][] cachedTileCubesTyx;
        private final boolean[] hasCachedTile;

        public StreamDecoderState(int width, int height, int depthK) {
            this.width = width;
            this.height = height;
            this.depthK = depthK;
            int tilesX = (width + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
            int tilesY = (height + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
            int nTiles = tilesX * tilesY;
            this.cachedTileCubesTyx = new int[nTiles][LcvPrimitives.TILE_PIXELS * depthK];
            this.hasCachedTile = new boolean[nTiles];
        }
    }

    /**
     * Encodes a single {@code Superblock} of {@code depthK} RGBA frames (`width * height * depthK` ints).
     */
    public static byte[] encodeSuperblock(
        int[] rgbaFrames, int width, int height, int depthK,
        long frameIndex64, long prevSbOffset64,
        int roiTileX, int roiTileY,
        LcvCubeCodec.DistanceRlePolicy rlePolicy,
        boolean useZstdPool,
        StreamEncoderState encoderState
    ) {
        LcvCubeCodec.DistanceRlePolicy effectiveRle =
            (rlePolicy != null) ? rlePolicy : LcvCubeCodec.DistanceRlePolicy.LOSSLESS;
        return encodeSuperblock(
            rgbaFrames, width, height, depthK,
            frameIndex64, prevSbOffset64,
            roiTileX, roiTileY,
            new LcvCubeCodec.TuningProfile(2, effectiveRle, MAX_CLEAN_ACK_REFRESH),
            useZstdPool,
            encoderState
        );
    }

    /**
     * Encodes a single {@code Superblock} using an explicit {@link LcvCubeCodec.TuningProfile}
     * ({@code LCV-ICD-1.0} Section 7).
     */
    public static byte[] encodeSuperblock(
        int[] rgbaFrames, int width, int height, int depthK,
        long frameIndex64, long prevSbOffset64,
        int roiTileX, int roiTileY,
        LcvCubeCodec.TuningProfile tuningProfile,
        boolean useZstdPool,
        StreamEncoderState encoderState
    ) {
        LcvCubeCodec.validateDepthK(depthK);
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw new IllegalArgumentException("Invalid frame dimensions: " + width + "x" + height);
        }
        if (tuningProfile == null) {
            tuningProfile = LcvCubeCodec.TuningProfile.DEFAULT_LOSSLESS;
        }
        LcvCubeCodec.DistanceRlePolicy rlePolicy = tuningProfile.rlePolicy();
        int lsbSplitBits = tuningProfile.lsbSplitBits();
        int maxCleanAckRefresh = tuningProfile.cleanAckRefreshWindow();

        int tilesX = (width + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
        int tilesY = (height + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
        int nTiles = tilesX * tilesY;
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;

        long[] pointerDir = new long[nTiles];
        byte[] syndromes = new byte[nTiles];

        ByteArrayOutputStream msbPoolOut = new ByteArrayOutputStream(nTiles * 128);
        ByteArrayOutputStream lsbPayloadOut = new ByteArrayOutputStream(nTiles * 64);
        int[] lsbTileRelOffsets = new int[nTiles];

        LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
        byte[] tileScratch = new byte[nSamples * 4 + 128];

        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tileIdx = ty * tilesX + tx;
                lsbTileRelOffsets[tileIdx] = lsbPayloadOut.size();
                LcvCubeCodec.extractTileToCubeTyx(rgbaFrames, tx, ty, width, height, depthK, arena.cubeRgbaTyx);

                // Check uniform first (UNIFORM_INLINE always takes precedence over TEMPORAL_CLEAN_ACK)
                int firstRgba = arena.cubeRgbaTyx[0];
                boolean uniform = true;
                for (int i = 1; i < nSamples; i++) {
                    if (arena.cubeRgbaTyx[i] != firstRgba) {
                        uniform = false;
                        break;
                    }
                }
                if (uniform) {
                    long ptr64 = makeUniformInlinePointer(firstRgba);
                    pointerDir[tileIdx] = ptr64;
                    syndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                    if (encoderState != null) {
                        encoderState.lastTileDigest64[tileIdx] = LcvPrimitives.computeCubeDigest64(arena.cubeRgbaTyx, nSamples);
                        encoderState.cleanAckRunLength[tileIdx] = 65;
                    }
                    continue;
                }

                long digest64 = LcvPrimitives.computeCubeDigest64(arena.cubeRgbaTyx, nSamples);
                if (encoderState != null
                    && maxCleanAckRefresh > 0
                    && encoderState.cleanAckRunLength[tileIdx] < maxCleanAckRefresh
                    && encoderState.lastTileDigest64[tileIdx] == digest64) {
                    int sbDelta = encoderState.cleanAckRunLength[tileIdx] + 1;
                    encoderState.cleanAckRunLength[tileIdx] = sbDelta;
                    long ptr64 = makeTemporalCleanAckPointer(
                        sbDelta,
                        encoderState.anchorRelOffset[tileIdx],
                        encoderState.anchorTotalLen[tileIdx]
                    );
                    pointerDir[tileIdx] = ptr64;
                    syndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                    continue;
                }

                LcvCubeCodec.CubeEncodeResult enc = LcvCubeCodec.encodeExtractedCubeTyx(
                    arena.cubeRgbaTyx, depthK, tx, ty, roiTileX, roiTileY,
                    rlePolicy, lsbSplitBits, arena, tileScratch, 0
                );

                int relMsbOff = msbPoolOut.size();
                int msbLen = enc.msbSliceBytes();
                int lsbLen = enc.lsbSliceBytes();
                msbPoolOut.write(tileScratch, 0, msbLen);
                if (lsbLen > 0) {
                    lsbPayloadOut.write(tileScratch, msbLen, lsbLen);
                }

                long ptr64 = makeActiveTilePointer(relMsbOff, msbLen, lsbLen);
                pointerDir[tileIdx] = ptr64;
                syndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);

                if (encoderState != null) {
                    encoderState.lastTileDigest64[tileIdx] = digest64;
                    encoderState.cleanAckRunLength[tileIdx] = 0;
                    encoderState.anchorRelOffset[tileIdx] = relMsbOff;
                    encoderState.anchorTotalLen[tileIdx] = msbLen;
                }
            }
        }

        byte[] rawMsbPool = msbPoolOut.toByteArray();
        byte[] rawLsbPool = buildIndexedLsbPool(nTiles, lsbTileRelOffsets, lsbPayloadOut.toByteArray());

        int flags8 = 0;
        byte[] finalMsbPool = rawMsbPool;
        byte[] finalLsbPool = rawLsbPool;
        if (useZstdPool && ZSTD_COMPRESS != null) {
            finalMsbPool = compressZstdFrameWithSizeHeader(rawMsbPool);
            finalLsbPool = rawLsbPool.length > 0 ? compressZstdFrameWithSizeHeader(rawLsbPool) : rawLsbPool;
            flags8 |= FLAG_ZSTD_POOL;
        }

        return assembleSuperblockBytes(
            width, height, depthK, flags8,
            tilesX, tilesY, roiTileX, roiTileY, tuningProfile.toPackedU16(),
            frameIndex64, prevSbOffset64, 60, 1,
            pointerDir, syndromes, finalMsbPool, finalLsbPool
        );
    }

    /**
     * Tile-parallel workgroup encoder that evaluates all {@code tilesX * tilesY} independent
     * {@code 16 x 16 x K} spatiotemporal cubes concurrently across worker threads (mirroring
     * GPU compute workgroup dispatch where 1 workgroup encodes 1 tile), followed by deterministic
     * prefix-sum pointer assembly so the resulting {@code Superblock} is 100% byte-for-byte
     * identical to {@link #encodeSuperblock}.
     */
    public static byte[] encodeSuperblockParallel(
        int[] rgbaFrames, int width, int height, int depthK,
        long frameIndex64, long prevSbOffset64,
        int roiTileX, int roiTileY,
        LcvCubeCodec.TuningProfile tuningProfile,
        boolean useZstdPool,
        StreamEncoderState encoderState
    ) {
        LcvCubeCodec.validateDepthK(depthK);
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw new IllegalArgumentException("Invalid frame dimensions: " + width + "x" + height);
        }
        final LcvCubeCodec.TuningProfile activeProfile =
            (tuningProfile != null) ? tuningProfile : LcvCubeCodec.TuningProfile.DEFAULT_LOSSLESS;
        final LcvCubeCodec.DistanceRlePolicy rlePolicy = activeProfile.rlePolicy();
        final int lsbSplitBits = activeProfile.lsbSplitBits();
        final int maxCleanAckRefresh = activeProfile.cleanAckRefreshWindow();

        final int tilesX = (width + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
        final int tilesY = (height + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
        final int nTiles = tilesX * tilesY;
        final int nSamples = LcvPrimitives.TILE_PIXELS * depthK;

        // Phase 1: Tile-row workgroup parallel encoding (1 independent workgroup per tile row ty = 0..tilesY-1,
        // eliminating L1/L2 cache-line false sharing and per-tile heap allocations).
        final TileRowEncodeResult[] rowResults = new TileRowEncodeResult[tilesY];
        java.util.stream.IntStream.range(0, tilesY).parallel().forEach(ty -> {
            LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
            byte[] tileScratch = arena.tileOutScratch;
            boolean[] rowUniform = new boolean[tilesX];
            int[] rowFirstRgba = new int[tilesX];
            boolean[] rowCleanAck = new boolean[tilesX];
            long[] rowDigests = new long[tilesX];
            int[] rowMsbRelOff = new int[tilesX];
            int[] rowMsbLen = new int[tilesX];
            int[] rowLsbRelOff = new int[tilesX];
            int[] rowLsbLen = new int[tilesX];
            ByteArrayOutputStream rowMsbOut = new ByteArrayOutputStream(tilesX * 128);
            ByteArrayOutputStream rowLsbOut = new ByteArrayOutputStream(tilesX * 64);

            int rowBaseTileIdx = ty * tilesX;
            for (int tx = 0; tx < tilesX; tx++) {
                int tileIdx = rowBaseTileIdx + tx;
                rowLsbRelOff[tx] = rowLsbOut.size();
                LcvCubeCodec.extractTileToCubeTyx(rgbaFrames, tx, ty, width, height, depthK, arena.cubeRgbaTyx);

                int firstRgba = arena.cubeRgbaTyx[0];
                boolean uniform = true;
                for (int i = 1; i < nSamples; i++) {
                    if (arena.cubeRgbaTyx[i] != firstRgba) {
                        uniform = false;
                        break;
                    }
                }
                if (uniform) {
                    rowUniform[tx] = true;
                    rowFirstRgba[tx] = firstRgba;
                    if (encoderState != null) {
                        rowDigests[tx] = LcvPrimitives.computeCubeDigest64(arena.cubeRgbaTyx, nSamples);
                    }
                    continue;
                }

                long digest64 = LcvPrimitives.computeCubeDigest64(arena.cubeRgbaTyx, nSamples);
                rowDigests[tx] = digest64;

                if (encoderState != null
                    && maxCleanAckRefresh > 0
                    && encoderState.cleanAckRunLength[tileIdx] < maxCleanAckRefresh
                    && encoderState.lastTileDigest64[tileIdx] == digest64) {
                    rowCleanAck[tx] = true;
                    continue;
                }

                LcvCubeCodec.CubeEncodeResult enc = LcvCubeCodec.encodeExtractedCubeTyx(
                    arena.cubeRgbaTyx, depthK, tx, ty, roiTileX, roiTileY,
                    rlePolicy, lsbSplitBits, arena, tileScratch, 0
                );
                int msbLen = enc.msbSliceBytes();
                int lsbLen = enc.lsbSliceBytes();
                rowMsbRelOff[tx] = rowMsbOut.size();
                rowMsbLen[tx] = msbLen;
                rowLsbLen[tx] = lsbLen;
                rowMsbOut.write(tileScratch, 0, msbLen);
                if (lsbLen > 0) {
                    rowLsbOut.write(tileScratch, msbLen, lsbLen);
                }
            }
            rowResults[ty] = new TileRowEncodeResult(
                rowUniform, rowFirstRgba, rowCleanAck, rowDigests,
                rowMsbRelOff, rowMsbLen, rowLsbRelOff, rowLsbLen,
                rowMsbOut.toByteArray(), rowLsbOut.toByteArray()
            );
        });

        // Phase 2: Deterministic linear prefix-sum pointer assembly (100% bit-exact parity with encodeSuperblock)
        long[] pointerDir = new long[nTiles];
        byte[] syndromes = new byte[nTiles];
        ByteArrayOutputStream msbPoolOut = new ByteArrayOutputStream(nTiles * 128);
        ByteArrayOutputStream lsbPayloadOut = new ByteArrayOutputStream(nTiles * 64);
        int[] lsbTileRelOffsets = new int[nTiles];

        for (int ty = 0; ty < tilesY; ty++) {
            TileRowEncodeResult row = rowResults[ty];
            int rowMsbBase = msbPoolOut.size();
            int rowLsbBase = lsbPayloadOut.size();
            if (row.msbBytes.length > 0) {
                msbPoolOut.write(row.msbBytes, 0, row.msbBytes.length);
            }
            if (row.lsbBytes.length > 0) {
                lsbPayloadOut.write(row.lsbBytes, 0, row.lsbBytes.length);
            }
            int rowBaseTileIdx = ty * tilesX;
            for (int tx = 0; tx < tilesX; tx++) {
                int tileIdx = rowBaseTileIdx + tx;
                lsbTileRelOffsets[tileIdx] = rowLsbBase + row.lsbRelOff[tx];
                long digest64 = row.digests[tx];

                if (row.uniform[tx]) {
                    long ptr64 = makeUniformInlinePointer(row.firstRgba[tx]);
                    pointerDir[tileIdx] = ptr64;
                    syndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                    if (encoderState != null) {
                        encoderState.lastTileDigest64[tileIdx] = digest64;
                        encoderState.cleanAckRunLength[tileIdx] = 65;
                    }
                    continue;
                }

                if (row.cleanAck[tx] && encoderState != null) {
                    int sbDelta = encoderState.cleanAckRunLength[tileIdx] + 1;
                    encoderState.cleanAckRunLength[tileIdx] = sbDelta;
                    long ptr64 = makeTemporalCleanAckPointer(
                        sbDelta,
                        encoderState.anchorRelOffset[tileIdx],
                        encoderState.anchorTotalLen[tileIdx]
                    );
                    pointerDir[tileIdx] = ptr64;
                    syndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                    continue;
                }

                int relMsbOff = rowMsbBase + row.msbRelOff[tx];
                int msbLen = row.msbLen[tx];
                int lsbLen = row.lsbLen[tx];
                long ptr64 = makeActiveTilePointer(relMsbOff, msbLen, lsbLen);
                pointerDir[tileIdx] = ptr64;
                syndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);

                if (encoderState != null) {
                    encoderState.lastTileDigest64[tileIdx] = digest64;
                    encoderState.cleanAckRunLength[tileIdx] = 0;
                    encoderState.anchorRelOffset[tileIdx] = relMsbOff;
                    encoderState.anchorTotalLen[tileIdx] = msbLen;
                }
            }
        }

        byte[] rawMsbPool = msbPoolOut.toByteArray();
        byte[] rawLsbPool = buildIndexedLsbPool(nTiles, lsbTileRelOffsets, lsbPayloadOut.toByteArray());

        int flags8 = 0;
        byte[] finalMsbPool = rawMsbPool;
        byte[] finalLsbPool = rawLsbPool;
        if (useZstdPool && ZSTD_COMPRESS != null) {
            finalMsbPool = compressZstdFrameWithSizeHeader(rawMsbPool);
            finalLsbPool = rawLsbPool.length > 0 ? compressZstdFrameWithSizeHeader(rawLsbPool) : rawLsbPool;
            flags8 |= FLAG_ZSTD_POOL;
        }

        return assembleSuperblockBytes(
            width, height, depthK, flags8,
            tilesX, tilesY, roiTileX, roiTileY, activeProfile.toPackedU16(),
            frameIndex64, prevSbOffset64, 60, 1,
            pointerDir, syndromes, finalMsbPool, finalLsbPool
        );
    }

    public static byte[] assembleSuperblockBytes(
        int width, int height, int depthK, int flags8,
        int tilesX, int tilesY, int roiTileX, int roiTileY,
        long frameIndex64, long prevSbOffset64, int fpsNum, int fpsDen,
        long[] pointerDir, byte[] syndromes,
        byte[] msbPoolBytes, byte[] lsbPoolBytes
    ) {
        return assembleSuperblockBytes(
            width, height, depthK, flags8,
            tilesX, tilesY, roiTileX, roiTileY, 0,
            frameIndex64, prevSbOffset64, fpsNum, fpsDen,
            pointerDir, syndromes, msbPoolBytes, lsbPoolBytes
        );
    }

    public static byte[] assembleSuperblockBytes(
        int width, int height, int depthK, int flags8,
        int tilesX, int tilesY, int roiTileX, int roiTileY, int tuningProfile16,
        long frameIndex64, long prevSbOffset64, int fpsNum, int fpsDen,
        long[] pointerDir, byte[] syndromes,
        byte[] msbPoolBytes, byte[] lsbPoolBytes
    ) {
        int nTiles = tilesX * tilesY;
        int totalBytes = SUPERBLOCK_HEADER_BYTES + nTiles * 9 + msbPoolBytes.length + lsbPoolBytes.length;
        byte[] out = new byte[totalBytes];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);

        buf.putLong(0x00, MAGIC_LCV_V001);
        buf.putShort(0x08, (short) FORMAT_VERSION_1_0);
        buf.putShort(0x0A, (short) width);
        buf.putShort(0x0C, (short) height);
        buf.put(0x0E, (byte) depthK);
        buf.put(0x0F, (byte) 8);
        buf.put(0x10, (byte) 0);
        buf.put(0x11, (byte) flags8);
        buf.putShort(0x12, (short) tilesX);
        buf.putShort(0x14, (short) tilesY);
        buf.putShort(0x16, (short) roiTileX);
        buf.putShort(0x18, (short) roiTileY);
        buf.putShort(0x1A, (short) tuningProfile16);
        buf.putInt(0x1C, msbPoolBytes.length);
        buf.putInt(0x20, lsbPoolBytes.length);
        buf.putLong(0x24, frameIndex64);
        buf.putLong(0x2C, prevSbOffset64);
        buf.putInt(0x34, fpsNum);
        buf.putInt(0x38, fpsDen);

        int crc32 = computeHeaderCrc32(out, 0);
        buf.putInt(0x3C, crc32);

        int dirOff = SUPERBLOCK_HEADER_BYTES;
        for (int i = 0; i < nTiles; i++) {
            buf.putLong(dirOff + i * 8, pointerDir[i]);
        }
        int synOff = dirOff + nTiles * 8;
        System.arraycopy(syndromes, 0, out, synOff, nTiles);

        int msbOff = synOff + nTiles;
        System.arraycopy(msbPoolBytes, 0, out, msbOff, msbPoolBytes.length);
        System.arraycopy(lsbPoolBytes, 0, out, msbOff + msbPoolBytes.length, lsbPoolBytes.length);
        return out;
    }

    public static int computeHeaderCrc32(byte[] src, int headerOffset) {
        CRC32 crc = new CRC32();
        crc.update(src, headerOffset, 0x3C);
        return (int) crc.getValue();
    }

    public static SuperblockHeader readSuperblockHeader(byte[] src, int offset) throws LcvCorruptTileException {
        if (offset < 0 || offset + SUPERBLOCK_HEADER_BYTES > src.length) {
            throw new LcvCorruptTileException("CBIT: truncated Superblock header at offset " + offset);
        }
        ByteBuffer buf = ByteBuffer.wrap(src, offset, SUPERBLOCK_HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        long magic64 = buf.getLong(offset + 0x00);
        if (magic64 != MAGIC_LCV_V001) {
            throw new LcvCorruptTileException(String.format("CBIT: invalid Superblock magic 0x%016X", magic64));
        }
        int version16 = buf.getShort(offset + 0x08) & 0xFFFF;
        if (version16 != FORMAT_VERSION_1_0) {
            throw new LcvCorruptTileException("CBIT: unsupported format version " + version16);
        }
        int width16 = buf.getShort(offset + 0x0A) & 0xFFFF;
        int height16 = buf.getShort(offset + 0x0C) & 0xFFFF;
        int depthK8 = buf.get(offset + 0x0E) & 0xFF;
        // Normative LCV-ICD-1.0 Section 3: reject any depthK8 not in {1, 2, 4, 8, 16}
        if (depthK8 != 1 && depthK8 != 2 && depthK8 != 4 && depthK8 != 8 && depthK8 != 16) {
            throw new LcvCorruptTileException("CBIT: invalid depthK8 " + depthK8 + " (must be 1, 2, 4, 8, or 16)");
        }
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

        int expectedCrc = computeHeaderCrc32(src, offset);
        if (headerCrc32 != expectedCrc) {
            throw new LcvCorruptTileException("CBIT: Superblock header CRC32 mismatch");
        }
        if (msbPoolLen32 < 0 || lsbPoolLen32 < 0) {
            throw new LcvCorruptTileException("CBIT: negative pool length in Superblock header");
        }
        SuperblockHeader header = new SuperblockHeader(
            magic64, version16, width16, height16, depthK8, bitDepth8, colorSpace8, flags8,
            tilesX16, tilesY16, roiTileX16, roiTileY16, tuningProfile16,
            msbPoolLen32, lsbPoolLen32, frameIndex64, prevSbOffset64, fpsNum32, fpsDen32, headerCrc32
        );
        if (offset + header.totalSuperblockBytes() > src.length) {
            throw new LcvCorruptTileException("CBIT: truncated Superblock payload at offset " + offset);
        }
        return header;
    }

    /**
     * Decodes a {@code Superblock} at {@code offset} inside {@code streamBytes}, healing any
     * single-bit pointer flips via {@code SEC-DED (72, 64)} and quarantining any corrupted tile
     * payloads via {@code CBIT} (falling back to {@code decoderState} cached tiles).
     */
    public static SuperblockDecodeResult decodeSuperblock(
        byte[] streamBytes, int offset, StreamDecoderState decoderState
    ) throws LcvCorruptTileException {
        SuperblockHeader header = readSuperblockHeader(streamBytes, offset);
        int width = header.width16();
        int height = header.height16();
        int depthK = header.depthK8();
        int tilesX = header.tilesX16();
        int tilesY = header.tilesY16();
        int nTiles = header.totalTiles();
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;

        int[] rgbaFrames = new int[width * height * depthK];
        ByteBuffer buf = ByteBuffer.wrap(streamBytes).order(ByteOrder.BIG_ENDIAN);

        int dirBase = offset + header.directoryOffset();
        int synBase = offset + header.secDedOffset();
        int msbPoolFileOff = offset + header.msbPoolOffset();
        int lsbPoolFileOff = offset + header.lsbPoolOffset();

        byte[] msbPool = streamBytes;
        int msbPoolBase = msbPoolFileOff;
        byte[] lsbPool = streamBytes;
        int lsbPoolBase = lsbPoolFileOff;

        boolean zstdCompressed = (header.flags8() & FLAG_ZSTD_POOL) != 0;
        boolean lsbStripped = (header.flags8() & FLAG_LSB_STRIPPED) != 0 || header.lsbPoolCompressedLen32() == 0;

        if (zstdCompressed) {
            msbPool = decompressZstdFrameWithSizeHeader(streamBytes, msbPoolFileOff, header.msbPoolCompressedLen32());
            msbPoolBase = 0;
            if (!lsbStripped && header.lsbPoolCompressedLen32() > 0) {
                lsbPool = decompressZstdFrameWithSizeHeader(streamBytes, lsbPoolFileOff, header.lsbPoolCompressedLen32());
                lsbPoolBase = 0;
            } else {
                lsbPool = new byte[0];
                lsbPoolBase = 0;
            }
        }

        int healedPointers = 0;
        int quarantinedTiles = 0;
        int cleanAckTiles = 0;
        int uniformTiles = 0;
        LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
        boolean partialRecode = (header.flags8() & FLAG_INLINE_RECODE_PARTIAL) != 0;
        int checkpointBytes = partialRecode ? 4 : 0;
        int lsbHeaderTableBytes = nTiles * 4;
        ByteBuffer lsbBuf = (!lsbStripped && lsbPool.length >= lsbPoolBase + checkpointBytes + lsbHeaderTableBytes)
            ? ByteBuffer.wrap(lsbPool).order(ByteOrder.BIG_ENDIAN)
            : null;
        int recodedCursor = nTiles;
        int priorLsbSplitBits = header.tuningProfile().lsbSplitBits();
        if (partialRecode && lsbBuf != null) {
            recodedCursor = lsbBuf.getShort(lsbPoolBase) & 0xFFFF;
            int priorPacked16 = lsbBuf.getShort(lsbPoolBase + 2) & 0xFFFF;
            priorLsbSplitBits = LcvCubeCodec.TuningProfile.fromPackedU16(priorPacked16).lsbSplitBits();
        }
        int lsbTableBase = lsbPoolBase + checkpointBytes;
        int lsbDataBase = lsbTableBase + lsbHeaderTableBytes;

        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tileIdx = ty * tilesX + tx;
                long rawPtr64 = buf.getLong(dirBase + tileIdx * 8);
                byte syn8 = streamBytes[synBase + tileIdx];
                long healedPtr64 = LcvPrimitives.healSingleBitFlip64(rawPtr64, syn8);

                if (healedPtr64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                    // Multi-bit pointer corruption -> quarantine single tile (zero DED contagion to other tiles!)
                    quarantinedTiles++;
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                    continue;
                }
                if (healedPtr64 != rawPtr64) {
                    healedPointers++;
                }

                if (isZeroBodyPointer(healedPtr64)) {
                    if (isTemporalCleanAckPointer(healedPtr64)) {
                        cleanAckTiles++;
                        applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                    } else {
                        uniformTiles++;
                        int rgba = extractUniformInlineRgba(healedPtr64);
                        Arrays.fill(arena.cubeRgbaTyx, 0, nSamples, rgba);
                        LcvCubeCodec.scatterCubeTyxToFrames(arena.cubeRgbaTyx, tx, ty, width, height, depthK, rgbaFrames);
                        updateDecoderCache(decoderState, tileIdx, arena.cubeRgbaTyx, nSamples);
                    }
                    continue;
                }

                int relMsbOff = extractRelByteOffset30(healedPtr64);
                int msbLen = extractMsbSliceLen16(healedPtr64);
                int lsbLen = (lsbStripped || lsbBuf == null) ? 0 : extractLsbSliceLen16(healedPtr64);
                int tileLsbOff = 0;
                if (lsbLen > 0 && lsbBuf != null) {
                    int relLsbOff = lsbBuf.getInt(lsbTableBase + tileIdx * 4);
                    tileLsbOff = lsbDataBase + relLsbOff;
                }
                int tileSplitBits = (tileIdx < recodedCursor)
                    ? header.tuningProfile().lsbSplitBits()
                    : priorLsbSplitBits;

                try {
                    LcvCubeCodec.decodeSplitCubeRgba(
                        msbPool, msbPoolBase + relMsbOff, msbLen,
                        lsbPool, tileLsbOff, lsbLen,
                        false, depthK, tileSplitBits, arena, arena.cubeRgbaTyx
                    );
                    LcvCubeCodec.scatterCubeTyxToFrames(arena.cubeRgbaTyx, tx, ty, width, height, depthK, rgbaFrames);
                    updateDecoderCache(decoderState, tileIdx, arena.cubeRgbaTyx, nSamples);
                } catch (LcvCorruptTileException ex) {
                    // CBIT quarantine: confine damage to this single 16x16xK tile
                    quarantinedTiles++;
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                }
            }
        }

        return new SuperblockDecodeResult(
            header, rgbaFrames, healedPointers, quarantinedTiles, cleanAckTiles, uniformTiles
        );
    }

    /**
     * Tile-parallel workgroup decoder that decodes all {@code tilesX * tilesY} independent
     * {@code 16 x 16 x K} spatiotemporal cubes concurrently across worker threads (mirroring
     * GPU compute workgroup dispatch) into disjoint tile coordinates of {@code rgbaFrames}.
     */
    public static SuperblockDecodeResult decodeSuperblockParallel(
        byte[] streamBytes, int offset, StreamDecoderState decoderState
    ) throws LcvCorruptTileException {
        SuperblockHeader header = readSuperblockHeader(streamBytes, offset);
        final int width = header.width16();
        final int height = header.height16();
        final int depthK = header.depthK8();
        final int tilesX = header.tilesX16();
        final int tilesY = header.tilesY16();
        final int nTiles = header.totalTiles();
        final int nSamples = LcvPrimitives.TILE_PIXELS * depthK;

        final int[] rgbaFrames = new int[width * height * depthK];
        final ByteBuffer buf = ByteBuffer.wrap(streamBytes).order(ByteOrder.BIG_ENDIAN);

        final int dirBase = offset + header.directoryOffset();
        final int synBase = offset + header.secDedOffset();
        final int msbPoolFileOff = offset + header.msbPoolOffset();
        final int lsbPoolFileOff = offset + header.lsbPoolOffset();

        final byte[] msbPool;
        final int msbPoolBase;
        final byte[] lsbPool;
        final int lsbPoolBase;

        boolean zstdCompressed = (header.flags8() & FLAG_ZSTD_POOL) != 0;
        final boolean lsbStripped = (header.flags8() & FLAG_LSB_STRIPPED) != 0 || header.lsbPoolCompressedLen32() == 0;

        if (zstdCompressed) {
            msbPool = decompressZstdFrameWithSizeHeader(streamBytes, msbPoolFileOff, header.msbPoolCompressedLen32());
            msbPoolBase = 0;
            if (!lsbStripped && header.lsbPoolCompressedLen32() > 0) {
                lsbPool = decompressZstdFrameWithSizeHeader(streamBytes, lsbPoolFileOff, header.lsbPoolCompressedLen32());
                lsbPoolBase = 0;
            } else {
                lsbPool = new byte[0];
                lsbPoolBase = 0;
            }
        } else {
            msbPool = streamBytes;
            msbPoolBase = msbPoolFileOff;
            lsbPool = streamBytes;
            lsbPoolBase = lsbPoolFileOff;
        }

        boolean partialRecode = (header.flags8() & FLAG_INLINE_RECODE_PARTIAL) != 0;
        int checkpointBytes = partialRecode ? 4 : 0;
        int lsbHeaderTableBytes = nTiles * 4;
        final ByteBuffer lsbBuf = (!lsbStripped && lsbPool.length >= lsbPoolBase + checkpointBytes + lsbHeaderTableBytes)
            ? ByteBuffer.wrap(lsbPool).order(ByteOrder.BIG_ENDIAN)
            : null;
        final int recodedCursor = (partialRecode && lsbBuf != null)
            ? (lsbBuf.getShort(lsbPoolBase) & 0xFFFF)
            : nTiles;
        final int priorLsbSplitBits = (partialRecode && lsbBuf != null)
            ? LcvCubeCodec.TuningProfile.fromPackedU16(lsbBuf.getShort(lsbPoolBase + 2) & 0xFFFF).lsbSplitBits()
            : header.tuningProfile().lsbSplitBits();
        final int lsbTableBase = lsbPoolBase + checkpointBytes;
        final int lsbDataBase = lsbTableBase + lsbHeaderTableBytes;

        final int[] perRowHealed = new int[tilesY];
        final int[] perRowQuarantined = new int[tilesY];
        final int[] perRowCleanAck = new int[tilesY];
        final int[] perRowUniform = new int[tilesY];

        java.util.stream.IntStream.range(0, tilesY).parallel().forEach(ty -> {
            LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
            int rowHealed = 0;
            int rowQuarantined = 0;
            int rowCleanAck = 0;
            int rowUniform = 0;
            int rowBaseIdx = ty * tilesX;
            for (int tx = 0; tx < tilesX; tx++) {
                int tileIdx = rowBaseIdx + tx;
                long rawPtr64 = buf.getLong(dirBase + tileIdx * 8);
                byte syn8 = streamBytes[synBase + tileIdx];
                long healedPtr64 = LcvPrimitives.healSingleBitFlip64(rawPtr64, syn8);

                if (healedPtr64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                    rowQuarantined++;
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                    continue;
                }
                if (healedPtr64 != rawPtr64) {
                    rowHealed++;
                }

                if (isZeroBodyPointer(healedPtr64)) {
                    if (isTemporalCleanAckPointer(healedPtr64)) {
                        rowCleanAck++;
                        applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                    } else {
                        rowUniform++;
                        int rgba = extractUniformInlineRgba(healedPtr64);
                        Arrays.fill(arena.cubeRgbaTyx, 0, nSamples, rgba);
                        LcvCubeCodec.scatterCubeTyxToFrames(arena.cubeRgbaTyx, tx, ty, width, height, depthK, rgbaFrames);
                        updateDecoderCache(decoderState, tileIdx, arena.cubeRgbaTyx, nSamples);
                    }
                    continue;
                }

                int relMsbOff = extractRelByteOffset30(healedPtr64);
                int msbLen = extractMsbSliceLen16(healedPtr64);
                int lsbLen = (lsbStripped || lsbBuf == null) ? 0 : extractLsbSliceLen16(healedPtr64);
                int tileLsbOff = 0;
                if (lsbLen > 0 && lsbBuf != null) {
                    int relLsbOff = lsbBuf.getInt(lsbTableBase + tileIdx * 4);
                    tileLsbOff = lsbDataBase + relLsbOff;
                }
                int tileSplitBits = (tileIdx < recodedCursor)
                    ? header.tuningProfile().lsbSplitBits()
                    : priorLsbSplitBits;

                try {
                    LcvCubeCodec.decodeSplitCubeRgba(
                        msbPool, msbPoolBase + relMsbOff, msbLen,
                        lsbPool, tileLsbOff, lsbLen,
                        false, depthK, tileSplitBits, arena, arena.cubeRgbaTyx
                    );
                    LcvCubeCodec.scatterCubeTyxToFrames(arena.cubeRgbaTyx, tx, ty, width, height, depthK, rgbaFrames);
                    updateDecoderCache(decoderState, tileIdx, arena.cubeRgbaTyx, nSamples);
                } catch (LcvCorruptTileException ex) {
                    rowQuarantined++;
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                }
            }
            perRowHealed[ty] = rowHealed;
            perRowQuarantined[ty] = rowQuarantined;
            perRowCleanAck[ty] = rowCleanAck;
            perRowUniform[ty] = rowUniform;
        });

        int healedPointers = 0;
        int quarantinedTiles = 0;
        int cleanAckTiles = 0;
        int uniformTiles = 0;
        for (int ty = 0; ty < tilesY; ty++) {
            healedPointers += perRowHealed[ty];
            quarantinedTiles += perRowQuarantined[ty];
            cleanAckTiles += perRowCleanAck[ty];
            uniformTiles += perRowUniform[ty];
        }

        return new SuperblockDecodeResult(
            header, rgbaFrames, healedPointers, quarantinedTiles, cleanAckTiles, uniformTiles
        );
    }

    private record TileRowEncodeResult(
        boolean[] uniform,
        int[] firstRgba,
        boolean[] cleanAck,
        long[] digests,
        int[] msbRelOff,
        int[] msbLen,
        int[] lsbRelOff,
        int[] lsbLen,
        byte[] msbBytes,
        byte[] lsbBytes
    ) {}

    /**
     * Scans an append-only {@code .lcv} stream (potentially truncated mid-write by {@code SIGKILL})
     * and returns the byte offsets of all intact completed {@code Superblock} records.
     */
    public static List<Integer> indexValidSuperblocks(byte[] streamBytes) {
        List<Integer> offsets = new ArrayList<>();
        int pos = 0;
        while (pos + SUPERBLOCK_HEADER_BYTES <= streamBytes.length) {
            try {
                SuperblockHeader header = readSuperblockHeader(streamBytes, pos);
                int sbBytes = header.totalSuperblockBytes();
                if (pos + sbBytes <= streamBytes.length) {
                    offsets.add(pos);
                    pos += sbBytes;
                } else {
                    break;
                }
            } catch (LcvCorruptTileException ex) {
                break;
            }
        }
        return offsets;
    }

    private static void applyFallbackTile(
        StreamDecoderState decoderState, int tileIdx,
        int tx, int ty, int width, int height, int depthK, int[] dstFrames
    ) {
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        if (decoderState != null && tileIdx < decoderState.hasCachedTile.length && decoderState.hasCachedTile[tileIdx]) {
            LcvCubeCodec.scatterCubeTyxToFrames(
                decoderState.cachedTileCubesTyx[tileIdx], tx, ty, width, height, depthK, dstFrames
            );
        } else {
            int[] zero = new int[nSamples];
            LcvCubeCodec.scatterCubeTyxToFrames(zero, tx, ty, width, height, depthK, dstFrames);
        }
    }

    private static void updateDecoderCache(StreamDecoderState decoderState, int tileIdx, int[] cubeTyx, int nSamples) {
        if (decoderState != null && tileIdx < decoderState.hasCachedTile.length) {
            System.arraycopy(cubeTyx, 0, decoderState.cachedTileCubesTyx[tileIdx], 0, nSamples);
            decoderState.hasCachedTile[tileIdx] = true;
        }
    }

    public static long makeUniformInlinePointer(int exactRgba32) {
        return (1L << 63) | (exactRgba32 & 0xFFFFFFFFL);
    }

    public static long makeTemporalCleanAckPointer(int anchorSbDelta16, int anchorRelOffset30, int anchorTotalLen16) {
        return (3L << 62)
            | ((long) (anchorSbDelta16 & 0xFFFF) << 46)
            | ((long) (anchorRelOffset30 & 0x3FFFFFFF) << 16)
            | (anchorTotalLen16 & 0xFFFFL);
    }

    public static long makeActiveTilePointer(int relByteOffset30, int msbSliceByteLen16, int lsbSliceByteLen16) {
        return ((long) (relByteOffset30 & 0x3FFFFFFF) << 32)
            | ((long) (msbSliceByteLen16 & 0xFFFF) << 16)
            | (lsbSliceByteLen16 & 0xFFFFL);
    }

    public static boolean isZeroBodyPointer(long ptr64) {
        return (ptr64 & (1L << 63)) != 0L;
    }

    public static boolean isTemporalCleanAckPointer(long ptr64) {
        return (ptr64 & (3L << 62)) == (3L << 62);
    }

    public static int extractUniformInlineRgba(long ptr64) {
        return (int) ptr64;
    }

    public static int extractRelByteOffset30(long ptr64) {
        return (int) ((ptr64 >>> 32) & 0x3FFFFFFFL);
    }

    public static int extractMsbSliceLen16(long ptr64) {
        return (int) ((ptr64 >>> 16) & 0xFFFFL);
    }

    public static byte[] buildIndexedLsbPool(int nTiles, int[] lsbTileRelOffsets, byte[] lsbPayloadBytes) {
        if (lsbPayloadBytes.length == 0) {
            return new byte[0];
        }
        int tableBytes = nTiles * 4;
        byte[] out = new byte[tableBytes + lsbPayloadBytes.length];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < nTiles; i++) {
            buf.putInt(i * 4, lsbTileRelOffsets[i]);
        }
        System.arraycopy(lsbPayloadBytes, 0, out, tableBytes, lsbPayloadBytes.length);
        return out;
    }

    public static int extractLsbSliceLen16(long ptr64) {
        return (int) (ptr64 & 0xFFFFL);
    }

    /**
     * Variable-size motion-vector-coherent packet grouping contiguous tiles that share the same
     * quantized 2D velocity vector {@code packedMv8} ({@code ((vx + 8) << 4) | ((vy + 8) & 0x0F)})
     * up to a target MTU/packet byte budget.
     */
    public record MvCoherentPacket(
        int startTileIdx,
        int tileCount,
        int packedMv8,
        long[] tilePointers,
        byte[] tileSyndromes,
        byte[] msbPayload,
        byte[] lsbPayload
    ) {
        public int velocityX() {
            return LcvPrimitives.unpackVelocityX(packedMv8);
        }

        public int velocityY() {
            return LcvPrimitives.unpackVelocityY(packedMv8);
        }

        public int wireByteLength() {
            // 6B packet header (uint16 startTileIdx, uint8 tileCount, uint8 packedMv8, uint16 msbPayloadLen)
            // + 9B per tile (ptr64 + syndrome8) + msbPayload + lsbPayload
            return 6 + tileCount * 9 + msbPayload.length + lsbPayload.length;
        }
    }

    /**
     * Partitions a {@code Superblock} into variable-size packets grouped by tile motion vector
     * {@code (vx, vy)}, coalescing calm/coherent-motion spans into multi-tile packets (up to
     * {@code maxPacketBytes} and {@code maxTilesPerPacket}) while splitting diverging motion
     * edges into fine-grained packets.
     */
    public static List<MvCoherentPacket> packetizeSuperblockByMotionVector(
        byte[] superblockBytes, int maxPacketBytes, int maxTilesPerPacket
    ) throws LcvCorruptTileException {
        SuperblockHeader header = readSuperblockHeader(superblockBytes, 0);
        int nTiles = header.totalTiles();
        int effectiveMaxTiles = Math.max(1, Math.min(255, maxTilesPerPacket));
        int effectiveMaxBytes = Math.max(256, maxPacketBytes);

        ByteBuffer buf = ByteBuffer.wrap(superblockBytes).order(ByteOrder.BIG_ENDIAN);
        int dirBase = header.directoryOffset();
        int synBase = header.secDedOffset();
        int msbPoolBase = header.msbPoolOffset();
        int lsbPoolBase = header.lsbPoolOffset();
        boolean lsbStripped = (header.flags8() & FLAG_LSB_STRIPPED) != 0 || header.lsbPoolCompressedLen32() == 0;
        int lsbTableBytes = nTiles * 4;

        List<MvCoherentPacket> packets = new ArrayList<>();
        int idx = 0;

        while (idx < nTiles) {
            int startIdx = idx;
            int groupMv8 = peekTileMotionVector8(superblockBytes, buf, dirBase, synBase, msbPoolBase, idx);

            ByteArrayOutputStream msbOut = new ByteArrayOutputStream(512);
            ByteArrayOutputStream lsbOut = new ByteArrayOutputStream(256);
            long[] ptrScratch = new long[effectiveMaxTiles];
            byte[] synScratch = new byte[effectiveMaxTiles];
            int count = 0;

            while (idx < nTiles && count < effectiveMaxTiles) {
                int candMv8 = peekTileMotionVector8(superblockBytes, buf, dirBase, synBase, msbPoolBase, idx);
                if (count > 0 && candMv8 != groupMv8) {
                    break; // Motion vector diverged -> close current packet and start a new MV packet
                }

                long rawPtr = buf.getLong(dirBase + idx * 8);
                byte syn = superblockBytes[synBase + idx];
                long healedPtr = LcvPrimitives.healSingleBitFlip64(rawPtr, syn);

                int msbLen = 0;
                int lsbLen = 0;
                int srcMsbOff = 0;
                int srcLsbOff = 0;
                if (healedPtr != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL && !isZeroBodyPointer(healedPtr)) {
                    srcMsbOff = msbPoolBase + extractRelByteOffset30(healedPtr);
                    msbLen = extractMsbSliceLen16(healedPtr);
                    lsbLen = lsbStripped ? 0 : extractLsbSliceLen16(healedPtr);
                    if (lsbLen > 0) {
                        srcLsbOff = lsbPoolBase + lsbTableBytes + buf.getInt(lsbPoolBase + idx * 4);
                    }
                }

                int projectedBytes = 6 + (count + 1) * 9 + msbOut.size() + msbLen + lsbOut.size() + lsbLen;
                if (count > 0 && projectedBytes > effectiveMaxBytes) {
                    break; // Exceeds MTU budget -> flush current packet
                }

                if (healedPtr != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL && !isZeroBodyPointer(healedPtr)) {
                    int localMsbOff = msbOut.size();
                    msbOut.write(superblockBytes, srcMsbOff, msbLen);
                    if (lsbLen > 0) {
                        lsbOut.write(superblockBytes, srcLsbOff, lsbLen);
                    }
                    long localPtr = makeActiveTilePointer(localMsbOff, msbLen, lsbLen);
                    ptrScratch[count] = localPtr;
                    synScratch[count] = LcvPrimitives.computeHammingSecDed8(localPtr);
                } else {
                    ptrScratch[count] = rawPtr;
                    synScratch[count] = syn;
                }

                count++;
                idx++;
            }

            packets.add(new MvCoherentPacket(
                startIdx,
                count,
                groupMv8,
                Arrays.copyOf(ptrScratch, count),
                Arrays.copyOf(synScratch, count),
                msbOut.toByteArray(),
                lsbOut.toByteArray()
            ));
        }

        return packets;
    }

    /**
     * Decodes a frame from an arbitrary subset of arrived variable-size {@link MvCoherentPacket}s
     * (supporting out-of-order delivery and wireless packet drops via {@code decoderState} tile fallback).
     */
    public static SuperblockDecodeResult decodeMvPacketStream(
        SuperblockHeader header, List<MvCoherentPacket> arrivedPackets, StreamDecoderState decoderState
    ) {
        int width = header.width16();
        int height = header.height16();
        int depthK = header.depthK8();
        int tilesX = header.tilesX16();
        int tilesY = header.tilesY16();
        int nTiles = header.totalTiles();
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        int lsbSplitBits = header.tuningProfile().lsbSplitBits();

        int[] rgbaFrames = new int[width * height * depthK];
        boolean[] decodedMask = new boolean[nTiles];
        int healedPointers = 0;
        int quarantinedTiles = 0;
        int cleanAckTiles = 0;
        int uniformTiles = 0;

        LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();

        for (MvCoherentPacket pkt : arrivedPackets) {
            if (pkt == null) {
                continue;
            }
            byte[] msbPool = pkt.msbPayload();
            byte[] lsbPool = pkt.lsbPayload();
            int lsbCursor = 0;

            for (int k = 0; k < pkt.tileCount(); k++) {
                int tileIdx = pkt.startTileIdx() + k;
                if (tileIdx < 0 || tileIdx >= nTiles) {
                    continue;
                }
                int tx = tileIdx % tilesX;
                int ty = tileIdx / tilesX;
                decodedMask[tileIdx] = true;

                long rawPtr = pkt.tilePointers()[k];
                byte syn = pkt.tileSyndromes()[k];
                long healedPtr = LcvPrimitives.healSingleBitFlip64(rawPtr, syn);
                if (healedPtr == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                    quarantinedTiles++;
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                    continue;
                }
                if (healedPtr != rawPtr) {
                    healedPointers++;
                }

                if (isZeroBodyPointer(healedPtr)) {
                    if (isTemporalCleanAckPointer(healedPtr)) {
                        cleanAckTiles++;
                        applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                    } else {
                        uniformTiles++;
                        int rgba = extractUniformInlineRgba(healedPtr);
                        Arrays.fill(arena.cubeRgbaTyx, 0, nSamples, rgba);
                        LcvCubeCodec.scatterCubeTyxToFrames(arena.cubeRgbaTyx, tx, ty, width, height, depthK, rgbaFrames);
                        updateDecoderCache(decoderState, tileIdx, arena.cubeRgbaTyx, nSamples);
                    }
                    continue;
                }

                int relMsbOff = extractRelByteOffset30(healedPtr);
                int msbLen = extractMsbSliceLen16(healedPtr);
                int lsbLen = extractLsbSliceLen16(healedPtr);
                int tileLsbOff = lsbCursor;
                lsbCursor += lsbLen;

                try {
                    LcvCubeCodec.decodeSplitCubeRgba(
                        msbPool, relMsbOff, msbLen,
                        lsbPool, tileLsbOff, lsbLen,
                        false, depthK, lsbSplitBits, arena, arena.cubeRgbaTyx
                    );
                    LcvCubeCodec.scatterCubeTyxToFrames(arena.cubeRgbaTyx, tx, ty, width, height, depthK, rgbaFrames);
                    updateDecoderCache(decoderState, tileIdx, arena.cubeRgbaTyx, nSamples);
                } catch (LcvCorruptTileException ex) {
                    quarantinedTiles++;
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                }
            }
        }

        // Any missing/dropped MV packets quarantine cleanly to their missing tiles and fall back to cache
        for (int tileIdx = 0; tileIdx < nTiles; tileIdx++) {
            if (!decodedMask[tileIdx]) {
                quarantinedTiles++;
                int tx = tileIdx % tilesX;
                int ty = tileIdx / tilesX;
                applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
            }
        }

        return new SuperblockDecodeResult(
            header, rgbaFrames, healedPointers, quarantinedTiles, cleanAckTiles, uniformTiles
        );
    }

    private static int peekTileMotionVector8(
        byte[] superblockBytes, ByteBuffer buf, int dirBase, int synBase, int msbPoolBase, int tileIdx
    ) {
        long rawPtr = buf.getLong(dirBase + tileIdx * 8);
        byte syn = superblockBytes[synBase + tileIdx];
        long healedPtr = LcvPrimitives.healSingleBitFlip64(rawPtr, syn);
        if (healedPtr == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL || isZeroBodyPointer(healedPtr)) {
            return 0x88; // Stationary (0, 0)
        }
        int msbOff = msbPoolBase + extractRelByteOffset30(healedPtr);
        int msbLen = extractMsbSliceLen16(healedPtr);
        if (msbOff < 0 || msbLen < 2 || msbOff + msbLen > superblockBytes.length) {
            return 0x88;
        }
        int header8 = superblockBytes[msbOff] & 0xFF;
        int cubeMode = (header8 >>> 4) & 0x03;
        if (cubeMode != LcvCubeCodec.CUBE_MODE_GRAY_PLANES_HILBERT_DELTA) {
            return 0x88;
        }
        int channelMask4 = header8 & 0x0F;
        int inactiveConsts = 4 - Integer.bitCount(channelMask4);
        int deltaFlagsPos = msbOff + 1 + inactiveConsts;
        if (deltaFlagsPos >= msbOff + msbLen) {
            return 0x88;
        }
        int deltaFlags8 = superblockBytes[deltaFlagsPos] & 0xFF;
        if ((deltaFlags8 & LcvCubeCodec.DELTA_FLAG_MOTION_SHEARED) != 0 && deltaFlagsPos + 1 < msbOff + msbLen) {
            return superblockBytes[deltaFlagsPos + 1] & 0xFF;
        }
        return 0x88;
    }

    static boolean isZstdAvailable() {
        return ZSTD_COMPRESS != null && ZSTD_DECOMPRESS != null;
    }

    static byte[] compressZstdFrameWithSizeHeader(byte[] raw) {
        try {
            byte[] compressed = (byte[]) ZSTD_COMPRESS.invoke(null, raw, 1);
            byte[] framed = new byte[4 + compressed.length];
            ByteBuffer.wrap(framed).order(ByteOrder.BIG_ENDIAN).putInt(0, raw.length);
            System.arraycopy(compressed, 0, framed, 4, compressed.length);
            return framed;
        } catch (Exception e) {
            throw new IllegalStateException("Zstd compression failed", e);
        }
    }

    static byte[] decompressZstdFrameWithSizeHeader(byte[] src, int off, int len) throws LcvCorruptTileException {
        if (ZSTD_DECOMPRESS == null || len < 4 || off + len > src.length) {
            throw new LcvCorruptTileException("CBIT: invalid Zstd pool frame");
        }
        try {
            int uncompressedLen = ByteBuffer.wrap(src, off, 4).order(ByteOrder.BIG_ENDIAN).getInt(off);
            if (uncompressedLen < 0 || uncompressedLen > 64 * 1024 * 1024) {
                throw new LcvCorruptTileException("CBIT: invalid Zstd uncompressed length " + uncompressedLen);
            }
            byte[] compSlice = Arrays.copyOfRange(src, off + 4, off + len);
            return (byte[]) ZSTD_DECOMPRESS.invoke(null, compSlice, uncompressedLen);
        } catch (LcvCorruptTileException e) {
            throw e;
        } catch (Exception e) {
            throw new LcvCorruptTileException("CBIT: Zstd pool decompression failed", e);
        }
    }
}
