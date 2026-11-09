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
 * @governedBy leaf-video-ADR-003
 * @governedBy leaf-video-ADR-004
 * @trace REQ-LCV-F-001
 * @trace REQ-LCV-F-003
 * @trace REQ-LCV-F-004
 * @trace REQ-LCV-DB-001
 * @trace REQ-LCV-DB-004
 * @trace REQ-LCV-HW-003
 */
public final class LcvStreamContainer {

    public static final long MAGIC_LCV_V001 = 0x4C43565F_56303031L; // "LCV_V001"
    public static final long MAGIC_LCV_MAP1 = 0x4C43565F_4D415031L; // "LCV_MAP1"
    public static final long MAGIC_LCV_BRCH = 0x4C43565F_42524348L; // "LCV_BRCH"
    public static final int FORMAT_VERSION_1_0 = 0x0001;
    public static final int SUPERBLOCK_HEADER_BYTES = 64;

    public static final int FLAG_ZSTD_POOL = 0x01;
    public static final int FLAG_LSB_STRIPPED = 0x02;
    public static final int FLAG_INLINE_RECODE_PARTIAL = 0x04;
    public static final int FLAG_KEYED_CODEBOOK = 0x08;
    public static final int FLAG_SUPERSEDED_OR_SLACK = 0x10;
    public static final int FLAG_ZSTD_DICT_POOL = 0x20;
    public static final int FLAG_DECODE_MAP_INDIRECTION = 0x40;
    public static final int FLAG_MULTI_LAYER_PLANE = 0x80;

    /**
     * Bit 7 ({@code 0x80}) of {@code colorSpace8} ({@code 0x10}) signaling that Section 2 & 3 use
     * rank-indexed {@code 16/32/64} quadtree directory coalescing (with total Section 2 {@code uint64}
     * word count stored in the upper 16 bits of {@code fpsDen32} at {@code 0x38..0x39}).
     */
    public static final int COLOR_FLAG_QUADTREE_DIR = 0x80;
    /**
     * Mandatory upper bound on backward superblock hops for {@code TEMPORAL_CLEAN_ACK}
     * ({@code LCV-ICD-1.0} Section 4.1) so random seeking remains strictly {@code O(1)}.
     */
    public static final int MAX_CLEAN_ACK_REFRESH = 8;

    /**
     * Maximum 30-bit relative MSB byte offset ({@code 1 GiB - 1}) addressable in a 64-bit active tile pointer
     * ({@code LCV-ICD-1.0} Section 4.1).
     */
    public static final int MAX_REL_BYTE_OFFSET_30 = (1 << 30) - 1;

    /**
     * Maximum uncompressed Section 4A/4B Zstd pool frame size ({@code 512 MiB}) permitted during superblock
     * decode before {@code CBIT} corrupt-frame rejection, supporting high-entropy {@code 4K} ({@code K <= 16})
     * and {@code 8K} ({@code K <= 4}) superblocks while remaining below {@link #MAX_REL_BYTE_OFFSET_30}.
     */
    public static final int MAX_ZSTD_UNCOMPRESSED_POOL_BYTES = 512 * 1024 * 1024;

    private static final Method ZSTD_COMPRESS;
    private static final Method ZSTD_DECOMPRESS;
    private static final Method ZSTD_COMPRESS_DICT;
    private static final Method ZSTD_DECOMPRESS_DICT;

    static {
        Method comp = null;
        Method decomp = null;
        Method compDict = null;
        Method decompDict = null;
        try {
            Class<?> zstdClass = Class.forName("com.github.luben.zstd.Zstd");
            comp = zstdClass.getMethod("compress", byte[].class, int.class);
            decomp = zstdClass.getMethod("decompress", byte[].class, int.class);
            compDict = zstdClass.getMethod(
                "compressUsingDict",
                byte[].class, int.class, byte[].class, int.class, int.class, byte[].class, int.class
            );
            decompDict = zstdClass.getMethod(
                "decompress",
                byte[].class, int.class, byte[].class, int.class, int.class, byte[].class
            );
        } catch (Throwable ignored) {
            // Optional runtime dependency
        }
        ZSTD_COMPRESS = comp;
        ZSTD_DECOMPRESS = decomp;
        ZSTD_COMPRESS_DICT = compDict;
        ZSTD_DECOMPRESS_DICT = decompDict;
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
        @Override
        public int fpsDen32() {
            return this.fpsDen32 & 0xFFFF;
        }

        public LcvCubeCodec.TuningProfile tuningProfile() {
            return LcvCubeCodec.TuningProfile.fromPackedU16(tuningProfile16);
        }

        public int totalTiles() {
            return tilesX16 * tilesY16;
        }

        public boolean isLsbStripped() {
            return (flags8 & FLAG_LSB_STRIPPED) != 0;
        }

        public boolean isQuadtreeDirectory() {
            return (colorSpace8 & COLOR_FLAG_QUADTREE_DIR) != 0 && ((this.fpsDen32 >>> 16) & 0xFFFF) > 0;
        }

        public int directoryWords() {
            return isQuadtreeDirectory() ? ((this.fpsDen32 >>> 16) & 0xFFFF) : totalTiles();
        }

        public int ctuX() {
            return (tilesX16 + 3) >>> 2;
        }

        public int ctuY() {
            return (tilesY16 + 3) >>> 2;
        }

        public int ctuCount() {
            return ctuX() * ctuY();
        }

        public int ctuDescriptorWords() {
            return isQuadtreeDirectory() ? ((ctuCount() + 7) >>> 3) : 0;
        }

        public int leafPointerCount() {
            return directoryWords() - ctuDescriptorWords();
        }

        public int directoryOffset() {
            return SUPERBLOCK_HEADER_BYTES;
        }

        public int secDedOffset() {
            return SUPERBLOCK_HEADER_BYTES + directoryWords() * 8;
        }

        public int msbPoolOffset() {
            return secDedOffset() + directoryWords();
        }

        public int lsbPoolOffset() {
            return msbPoolOffset() + msbPoolCompressedLen32;
        }

        public int totalSuperblockBytes() {
            return lsbPoolOffset() + lsbPoolCompressedLen32;
        }
    }

    /**
     * Unpacked and {@code SEC-DED (72, 64)}-healed {@code tilesX * tilesY} directory view for a {@code Superblock}.
     */
    public record UnpackedDirectory(
        long[] rawPointers,
        byte[] syndromes,
        long[] healedPointers,
        int healedSingleBitPointers
    ) {}
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
        int[] lastEncodedFrames;
        byte[] zstdMsbDict;
        byte[] zstdLsbDict;
        boolean shiftedCleanAckEnabled = false;

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

        public StreamEncoderState enableShiftedCleanAck(boolean enabled) {
            this.shiftedCleanAckEnabled = enabled;
            if (!enabled) {
                this.lastEncodedFrames = null;
            }
            return this;
        }

        public void reset() {
            Arrays.fill(this.lastTileDigest64, 0L);
            Arrays.fill(this.cleanAckRunLength, 65);
            Arrays.fill(this.anchorRelOffset, 0);
            Arrays.fill(this.anchorTotalLen, 0);
            this.lastEncodedFrames = null;
            this.zstdMsbDict = null;
            this.zstdLsbDict = null;
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
        private final boolean[] isCachedUniform;
        private final int[] cachedUniformRgba;
        private final int[][] outputRing = new int[2][];
        private int ringIdx = 0;
        int[] lastDecodedFrames;
        byte[] zstdMsbDict;
        byte[] zstdLsbDict;

        public StreamDecoderState(int width, int height, int depthK) {
            this.width = width;
            this.height = height;
            this.depthK = depthK;
            int tilesX = (width + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
            int tilesY = (height + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
            int nTiles = tilesX * tilesY;
            this.cachedTileCubesTyx = new int[nTiles][];
            this.hasCachedTile = new boolean[nTiles];
            this.isCachedUniform = new boolean[nTiles];
            this.cachedUniformRgba = new int[nTiles];
        }

        public void reset() {
            Arrays.fill(hasCachedTile, false);
            Arrays.fill(isCachedUniform, false);
            ringIdx = 0;
            lastDecodedFrames = null;
            zstdMsbDict = null;
            zstdLsbDict = null;
        }

        int[] acquireOutputBuffer(int totalPixels) {
            int slot = ringIdx & 1;
            ringIdx++;
            int[] buf = outputRing[slot];
            if (buf == null || buf.length != totalPixels) {
                buf = new int[totalPixels];
                outputRing[slot] = buf;
            }
            return buf;
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
        return encodeSuperblock(
            rgbaFrames, width, height, depthK,
            frameIndex64, prevSbOffset64,
            roiTileX, roiTileY,
            tuningProfile,
            LcvCubeCodec.LcvKeySchedule.OPEN_MIT,
            useZstdPool,
            encoderState
        );
    }

    /**
     * Keyed Structural Codebook ({@code KSC}) overload encoding a {@code Superblock} under
     * {@link LcvCubeCodec.LcvKeySchedule} ({@code FLAG_KEYED_CODEBOOK = 0x08} when keyed).
     */
    public static byte[] encodeSuperblock(
        int[] rgbaFrames, int width, int height, int depthK,
        long frameIndex64, long prevSbOffset64,
        int roiTileX, int roiTileY,
        LcvCubeCodec.TuningProfile tuningProfile,
        LcvCubeCodec.LcvKeySchedule keySchedule,
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
        if (keySchedule == null) {
            keySchedule = LcvCubeCodec.LcvKeySchedule.OPEN_MIT;
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

        int[] gpuTileMeta = shouldOffloadSuperblockToGpu(rgbaFrames, width, height, depthK, tilesX, tilesY)
            ? new int[nTiles * 4]
            : null;
        int gpuStride = (gpuTileMeta != null)
            ? LcvPrimitives.batchAnalyzeSuperblock(rgbaFrames, width, height, depthK, gpuTileMeta)
            : 0;

        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tileIdx = ty * tilesX + tx;
                lsbTileRelOffsets[tileIdx] = lsbPayloadOut.size();
                if (gpuStride >= 4) {
                    int mOff = tileIdx * 4;
                    if (gpuTileMeta[mOff] != 0) {
                        long ptr64 = makeUniformInlinePointer(gpuTileMeta[mOff + 1]);
                        pointerDir[tileIdx] = ptr64;
                        syndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                        if (encoderState != null) {
                            encoderState.cleanAckRunLength[tileIdx] = 65;
                        }
                        continue;
                    }
                    arena.knownNonUniformFromGpu = true;
                    arena.precomputedCurveId = gpuTileMeta[mOff + 2];
                    arena.precomputedPackedMv8 = gpuTileMeta[mOff + 3];
                }
                LcvPrimitives.CubeFingerprint fp = LcvPrimitives.extractAndFingerprintCubeTyx(
                    rgbaFrames, tx, ty, width, height, depthK, arena
                );
                if (fp.uniform || fp.paletteSize == 1) {
                    arena.precomputedPackedMv8 = -1;
                    long ptr64 = makeUniformInlinePointer(fp.firstRgba);
                    pointerDir[tileIdx] = ptr64;
                    syndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                    if (encoderState != null) {
                        encoderState.cleanAckRunLength[tileIdx] = 65;
                    }
                    continue;
                }

                long digest64 = fp.digest64;
                if (encoderState != null
                    && maxCleanAckRefresh > 0
                    && encoderState.cleanAckRunLength[tileIdx] < maxCleanAckRefresh) {
                    if (encoderState.lastTileDigest64[tileIdx] == digest64) {
                        arena.precomputedPackedMv8 = -1;
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
                    int shift8 = encoderState.shiftedCleanAckEnabled
                        ? findShiftedCleanAckMatch8(
                            arena.cubeRgbaTyx, encoderState.lastEncodedFrames, tx, ty, width, height, depthK
                        )
                        : -1;
                    if (shift8 >= 0) {
                        arena.precomputedPackedMv8 = -1;
                        int sbDelta = encoderState.cleanAckRunLength[tileIdx] + 1;
                        encoderState.cleanAckRunLength[tileIdx] = sbDelta;
                        encoderState.lastTileDigest64[tileIdx] = digest64;
                        int sDx = LcvPrimitives.unpackVelocityX(shift8);
                        int sDy = LcvPrimitives.unpackVelocityY(shift8);
                        long ptr64 = makeShiftedTemporalCleanAckPointer(
                            sbDelta, sDx, sDy,
                            encoderState.anchorRelOffset[tileIdx],
                            encoderState.anchorTotalLen[tileIdx]
                        );
                        pointerDir[tileIdx] = ptr64;
                        syndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                        continue;
                    }
                }

                LcvCubeCodec.CubeEncodeResult enc = LcvCubeCodec.encodeExtractedCubeTyx(
                    arena.cubeRgbaTyx, depthK, tx, ty, roiTileX, roiTileY,
                    rlePolicy, lsbSplitBits, digest64, true,
                    keySchedule, frameIndex64, arena, tileScratch, 0
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

        int flags8 = keySchedule.isKeyed() ? FLAG_KEYED_CODEBOOK : 0;
        byte[] finalMsbPool = rawMsbPool;
        byte[] finalLsbPool = rawLsbPool;
        if (useZstdPool && ZSTD_COMPRESS != null) {
            finalMsbPool = compressZstdFrameWithSizeHeader(rawMsbPool);
            finalLsbPool = rawLsbPool.length > 0 ? compressZstdFrameWithSizeHeader(rawLsbPool) : rawLsbPool;
            flags8 |= FLAG_ZSTD_POOL;
        }

        if (encoderState != null) {
            if (encoderState.shiftedCleanAckEnabled) {
                if (encoderState.lastEncodedFrames == null || encoderState.lastEncodedFrames.length != rgbaFrames.length) {
                    encoderState.lastEncodedFrames = Arrays.copyOf(rgbaFrames, rgbaFrames.length);
                } else {
                    System.arraycopy(rgbaFrames, 0, encoderState.lastEncodedFrames, 0, rgbaFrames.length);
                }
            } else {
                encoderState.lastEncodedFrames = null;
            }
        }

        return assembleSuperblockBytes(
            width, height, depthK, flags8,
            tilesX, tilesY, roiTileX, roiTileY, tuningProfile.toPackedU16(),
            frameIndex64, prevSbOffset64, 60, 1,
            pointerDir, syndromes, finalMsbPool, finalLsbPool
        );
    }

    private static boolean shouldOffloadSuperblockToGpu(
        int[] rgbaFrames, int width, int height, int depthK, int tilesX, int tilesY
    ) {
        int nTiles = tilesX * tilesY;
        if (!LcvPrimitives.isCudaGpuAvailable() || depthK <= 1 || nTiles < 16 || width < 16 || height < 16) {
            return false;
        }
        if (Boolean.getBoolean("lcv.cuda.forceBatch")) {
            return true;
        }
        int frameArea = width * height;
        int activeOpaqueCount = 0;
        int transparentCount = 0;
        for (int s = 0; s < 16; s++) {
            int tileIdx = (s * nTiles) >>> 4;
            int ty = tileIdx / tilesX;
            int tx = tileIdx - ty * tilesX;
            int bx = Math.min(width - 4, tx * LcvPrimitives.TILE_DIM + 4);
            int by = Math.min(height - 4, ty * LcvPrimitives.TILE_DIM + 4);
            int idx = by * width + bx;
            int p0 = rgbaFrames[idx];
            if ((p0 & 0xFF) == 0) {
                transparentCount++;
                continue;
            }
            int p1 = rgbaFrames[idx + 1];
            int p2 = rgbaFrames[idx + 2];
            int pT = rgbaFrames[frameArea + idx];
            if (p0 == p1 && p0 == p2 && p0 == pT) {
                continue;
            }
            int r0 = (p0 >>> 24) & 0xFF;
            int r1 = (p1 >>> 24) & 0xFF;
            int r2 = (p2 >>> 24) & 0xFF;
            int g0 = (p0 >>> 16) & 0xFF;
            int g1 = (p1 >>> 16) & 0xFF;
            int g2 = (p2 >>> 16) & 0xFF;
            int curv = Math.abs((r0 - r1) - (r1 - r2)) + Math.abs((g0 - g1) - (g1 - g2));
            if (curv > 14 || Math.abs(r0 - ((pT >>> 24) & 0xFF)) > 12) {
                activeOpaqueCount++;
            }
        }
        return transparentCount < 6 && activeOpaqueCount >= 6;
    }

    /**
     * Zero-CPU-Callback GPU-Direct Superblock encoder ({@code leaf-video-ADR-004} {@code REQ-LCV-HW-001},
     * {@code REQ-LCV-HW-002}, {@code REQ-LCV-HW-003}): executes both tile compression
     * ({@code lcv_gpu_encode_tiles}) and prefix-sum {@code "LCV_V001"} packet assembly
     * ({@code lcv_gpu_assemble_superblock}: header + IEEE {@code CRC32} + {@code pointerDir} +
     * {@code SEC-DED (72, 64)} syndromes + Section 4A MSB pool + Section 4B LSB pool) entirely in
     * GPU VRAM so the compressed {@code Superblock} can stream directly to SSD/HDD or network sockets
     * (Parsec/WebRTC style) without per-tile CPU callbacks. Automatically falls back to
     * {@link #encodeSuperblockParallel} when no CUDA GPU is available.
     */
    public static byte[] encodeSuperblockGpuDirect(
        int[] rgbaFrames, int width, int height, int depthK,
        long frameIndex64, long prevSbOffset64,
        int roiTileX, int roiTileY,
        LcvCubeCodec.TuningProfile tuningProfile,
        boolean resetTemporalState
    ) {
        LcvCubeCodec.validateDepthK(depthK);
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw new IllegalArgumentException("Invalid frame dimensions: " + width + "x" + height);
        }
        LcvCubeCodec.TuningProfile activeProfile =
            (tuningProfile != null) ? tuningProfile : LcvCubeCodec.TuningProfile.DEFAULT_LOSSLESS;
        if (LcvPrimitives.isCudaGpuAvailable()) {
            int tilesX = (width + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
            int tilesY = (height + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
            int nTiles = tilesX * tilesY;
            int maxCap = SUPERBLOCK_HEADER_BYTES + nTiles * 13 + nTiles * (16896 + 8448);
            byte[] outBuf = new byte[maxCap];
            int written = LcvPrimitives.encodeSuperblockGpuDirect(
                rgbaFrames, width, height, depthK,
                frameIndex64, prevSbOffset64, roiTileX, roiTileY,
                activeProfile.toPackedU16(),
                activeProfile.lsbSplitBits(),
                activeProfile.cleanAckRefreshWindow(),
                resetTemporalState,
                outBuf
            );
            if (written >= SUPERBLOCK_HEADER_BYTES) {
                return Arrays.copyOf(outBuf, written);
            }
        }
        return encodeSuperblockParallel(
            rgbaFrames, width, height, depthK,
            frameIndex64, prevSbOffset64, roiTileX, roiTileY,
            activeProfile, false, null
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
        return encodeSuperblockParallel(
            rgbaFrames, width, height, depthK,
            frameIndex64, prevSbOffset64,
            roiTileX, roiTileY,
            tuningProfile,
            LcvCubeCodec.LcvKeySchedule.OPEN_MIT,
            useZstdPool,
            encoderState
        );
    }

    /**
     * Keyed Structural Codebook ({@code KSC}) overload for {@link #encodeSuperblockParallel}.
     */
    public static byte[] encodeSuperblockParallel(
        int[] rgbaFrames, int width, int height, int depthK,
        long frameIndex64, long prevSbOffset64,
        int roiTileX, int roiTileY,
        LcvCubeCodec.TuningProfile tuningProfile,
        LcvCubeCodec.LcvKeySchedule keySchedule,
        boolean useZstdPool,
        StreamEncoderState encoderState
    ) {
        LcvCubeCodec.validateDepthK(depthK);
        if (width <= 0 || height <= 0 || width > 65535 || height > 65535) {
            throw new IllegalArgumentException("Invalid frame dimensions: " + width + "x" + height);
        }
        final LcvCubeCodec.TuningProfile activeProfile =
            (tuningProfile != null) ? tuningProfile : LcvCubeCodec.TuningProfile.DEFAULT_LOSSLESS;
        final LcvCubeCodec.LcvKeySchedule activeKeySchedule =
            (keySchedule != null) ? keySchedule : LcvCubeCodec.LcvKeySchedule.OPEN_MIT;
        final LcvCubeCodec.DistanceRlePolicy rlePolicy = activeProfile.rlePolicy();
        final int lsbSplitBits = activeProfile.lsbSplitBits();
        final int maxCleanAckRefresh = activeProfile.cleanAckRefreshWindow();

        final int tilesX = (width + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
        final int tilesY = (height + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
        final int nTiles = tilesX * tilesY;

        final int[] gpuTileMeta = shouldOffloadSuperblockToGpu(rgbaFrames, width, height, depthK, tilesX, tilesY)
            ? new int[nTiles * 4]
            : null;
        final int gpuStride = (gpuTileMeta != null)
            ? LcvPrimitives.batchAnalyzeSuperblock(rgbaFrames, width, height, depthK, gpuTileMeta)
            : 0;

        // Tile-row workgroup parallel encoding (1 independent workgroup per tile row ty = 0..tilesY-1,
        // eliminating L1/L2 cache-line false sharing and per-tile heap allocations; leaf-video-ADR-004).
        final TileRowEncodeResult[] rowResults = new TileRowEncodeResult[tilesY];
        final int rowStride = (tilesY > 7 && (tilesY % 7) != 0) ? 7 : 1;
        java.util.stream.IntStream.range(0, tilesY).parallel().forEach(w -> {
            final int ty = (w * rowStride) % tilesY;
            LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
            byte[] tileScratch = arena.tileOutScratch;
            boolean[] rowUniform = new boolean[tilesX];
            int[] rowFirstRgba = new int[tilesX];
            boolean[] rowCleanAck = new boolean[tilesX];
            int[] rowCleanAckShift8 = new int[tilesX];
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
                if (gpuStride >= 4) {
                    int mOff = tileIdx * 4;
                    if (gpuTileMeta[mOff] != 0) {
                        rowUniform[tx] = true;
                        rowFirstRgba[tx] = gpuTileMeta[mOff + 1];
                        continue;
                    }
                    arena.knownNonUniformFromGpu = true;
                    arena.precomputedCurveId = gpuTileMeta[mOff + 2];
                    arena.precomputedPackedMv8 = gpuTileMeta[mOff + 3];
                }
                LcvPrimitives.CubeFingerprint fp = LcvPrimitives.extractAndFingerprintCubeTyx(
                    rgbaFrames, tx, ty, width, height, depthK, arena
                );
                if (fp.uniform || fp.paletteSize == 1) {
                    arena.precomputedPackedMv8 = -1;
                    rowUniform[tx] = true;
                    rowFirstRgba[tx] = fp.firstRgba;
                    continue;
                }

                long digest64 = fp.digest64;
                rowDigests[tx] = digest64;

                if (encoderState != null
                    && maxCleanAckRefresh > 0
                    && encoderState.cleanAckRunLength[tileIdx] < maxCleanAckRefresh) {
                    if (encoderState.lastTileDigest64[tileIdx] == digest64) {
                        arena.precomputedPackedMv8 = -1;
                        rowCleanAck[tx] = true;
                        rowCleanAckShift8[tx] = 0x88;
                        continue;
                    }
                    int shift8 = encoderState.shiftedCleanAckEnabled
                        ? findShiftedCleanAckMatch8(
                            arena.cubeRgbaTyx, encoderState.lastEncodedFrames, tx, ty, width, height, depthK
                        )
                        : -1;
                    if (shift8 >= 0) {
                        arena.precomputedPackedMv8 = -1;
                        rowCleanAck[tx] = true;
                        rowCleanAckShift8[tx] = shift8;
                        continue;
                    }
                }

                LcvCubeCodec.CubeEncodeResult enc = LcvCubeCodec.encodeExtractedCubeTyx(
                    arena.cubeRgbaTyx, depthK, tx, ty, roiTileX, roiTileY,
                    rlePolicy, lsbSplitBits, digest64, true,
                    activeKeySchedule, frameIndex64, arena, tileScratch, 0
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
                rowUniform, rowFirstRgba, rowCleanAck, rowCleanAckShift8, rowDigests,
                rowMsbRelOff, rowMsbLen, rowLsbRelOff, rowLsbLen,
                rowMsbOut.toByteArray(), rowLsbOut.toByteArray()
            );
        });

        // Deterministic linear prefix-sum pointer assembly (100% bit-exact parity with encodeSuperblock; REQ-LCV-F-001)
        int totalMsbBytes = 0;
        int totalLsbBytes = 0;
        for (int ty = 0; ty < tilesY; ty++) {
            totalMsbBytes += rowResults[ty].msbBytes.length;
            totalLsbBytes += rowResults[ty].lsbBytes.length;
        }
        long[] pointerDir = new long[nTiles];
        byte[] syndromes = new byte[nTiles];
        byte[] rawMsbPool = new byte[totalMsbBytes];
        byte[] rawLsbPayload = new byte[totalLsbBytes];
        int[] lsbTileRelOffsets = new int[nTiles];
        int msbCursor = 0;
        int lsbCursor = 0;

        for (int ty = 0; ty < tilesY; ty++) {
            TileRowEncodeResult row = rowResults[ty];
            int rowMsbBase = msbCursor;
            int rowLsbBase = lsbCursor;
            if (row.msbBytes.length > 0) {
                System.arraycopy(row.msbBytes, 0, rawMsbPool, msbCursor, row.msbBytes.length);
                msbCursor += row.msbBytes.length;
            }
            if (row.lsbBytes.length > 0) {
                System.arraycopy(row.lsbBytes, 0, rawLsbPayload, lsbCursor, row.lsbBytes.length);
                lsbCursor += row.lsbBytes.length;
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
                        encoderState.cleanAckRunLength[tileIdx] = 65;
                    }
                    continue;
                }

                if (row.cleanAck[tx] && encoderState != null) {
                    int sbDelta = encoderState.cleanAckRunLength[tileIdx] + 1;
                    encoderState.cleanAckRunLength[tileIdx] = sbDelta;
                    encoderState.lastTileDigest64[tileIdx] = digest64;
                    int shift8 = row.cleanAckShift8[tx];
                    long ptr64 = (shift8 == 0x88 || shift8 == 0)
                        ? makeTemporalCleanAckPointer(
                            sbDelta,
                            encoderState.anchorRelOffset[tileIdx],
                            encoderState.anchorTotalLen[tileIdx]
                        )
                        : makeShiftedTemporalCleanAckPointer(
                            sbDelta,
                            LcvPrimitives.unpackVelocityX(shift8),
                            LcvPrimitives.unpackVelocityY(shift8),
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

        byte[] rawLsbPool = buildIndexedLsbPool(nTiles, lsbTileRelOffsets, rawLsbPayload);

        int flags8 = activeKeySchedule.isKeyed() ? FLAG_KEYED_CODEBOOK : 0;
        byte[] finalMsbPool = rawMsbPool;
        byte[] finalLsbPool = rawLsbPool;
        if (useZstdPool && ZSTD_COMPRESS != null) {
            finalMsbPool = compressZstdFrameWithSizeHeader(rawMsbPool);
            finalLsbPool = rawLsbPool.length > 0 ? compressZstdFrameWithSizeHeader(rawLsbPool) : rawLsbPool;
            flags8 |= FLAG_ZSTD_POOL;
        }

        if (encoderState != null) {
            if (encoderState.shiftedCleanAckEnabled) {
                if (encoderState.lastEncodedFrames == null || encoderState.lastEncodedFrames.length != rgbaFrames.length) {
                    encoderState.lastEncodedFrames = Arrays.copyOf(rgbaFrames, rgbaFrames.length);
                } else {
                    System.arraycopy(rgbaFrames, 0, encoderState.lastEncodedFrames, 0, rgbaFrames.length);
                }
            } else {
                encoderState.lastEncodedFrames = null;
            }
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
        QuadtreeDirectoryPackResult qt = ((flags8 & FLAG_SUPERSEDED_OR_SLACK) == 0 && nTiles > 1 && pointerDir.length == nTiles)
            ? tryPackQuadtreeDirectory(pointerDir, syndromes, tilesX, tilesY)
            : null;
        long[] activeDirWords = (qt != null) ? qt.dirWords() : pointerDir;
        byte[] activeSyndromes = (qt != null) ? qt.dirSyndromes() : syndromes;
        int dirWordCount = activeDirWords.length;
        int colorSpaceByte = (qt != null) ? COLOR_FLAG_QUADTREE_DIR : 0;
        int encodedFpsDen32 = (qt != null)
            ? ((dirWordCount << 16) | (fpsDen & 0xFFFF))
            : (fpsDen & 0xFFFF);

        int totalBytes = SUPERBLOCK_HEADER_BYTES + dirWordCount * 9 + msbPoolBytes.length + lsbPoolBytes.length;
        byte[] out = new byte[totalBytes];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);

        buf.putLong(0x00, MAGIC_LCV_V001);
        buf.putShort(0x08, (short) FORMAT_VERSION_1_0);
        buf.putShort(0x0A, (short) width);
        buf.putShort(0x0C, (short) height);
        buf.put(0x0E, (byte) depthK);
        buf.put(0x0F, (byte) 8);
        buf.put(0x10, (byte) colorSpaceByte);
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
        buf.putInt(0x38, encodedFpsDen32);

        int crc32 = computeHeaderCrc32(out, 0);
        buf.putInt(0x3C, crc32);

        int dirOff = SUPERBLOCK_HEADER_BYTES;
        for (int i = 0; i < dirWordCount; i++) {
            buf.putLong(dirOff + i * 8, activeDirWords[i]);
        }
        int synOff = dirOff + dirWordCount * 8;
        System.arraycopy(activeSyndromes, 0, out, synOff, dirWordCount);

        int msbOff = synOff + dirWordCount;
        System.arraycopy(msbPoolBytes, 0, out, msbOff, msbPoolBytes.length);
        System.arraycopy(lsbPoolBytes, 0, out, msbOff + msbPoolBytes.length, lsbPoolBytes.length);
        return out;
    }

    private record QuadtreeDirectoryPackResult(long[] dirWords, byte[] dirSyndromes, int totalDirWords) {}

    private static QuadtreeDirectoryPackResult tryPackQuadtreeDirectory(
        long[] flatPointers, byte[] flatSyndromes, int tilesX, int tilesY
    ) {
        int nTiles = tilesX * tilesY;
        int ctuX = (tilesX + 3) >>> 2;
        int ctuY = (tilesY + 3) >>> 2;
        int ctuCount = ctuX * ctuY;
        int ctuWords = (ctuCount + 7) >>> 3;
        int[] ctuDesc6 = new int[ctuCount];
        int leafCount = 0;

        for (int cy = 0; cy < ctuY; cy++) {
            int ty0 = cy << 2;
            for (int cx = 0; cx < ctuX; cx++) {
                int tx0 = cx << 2;
                int ctuIdx = cy * ctuX + cx;
                if (canCoalesceZeroBodyRegion(flatPointers, tilesX, tilesY, tx0, ty0, 4)) {
                    ctuDesc6[ctuIdx] = 0;
                    leafCount++;
                } else {
                    int splitMask4 = 0;
                    for (int q = 0; q < 4; q++) {
                        int qx0 = tx0 + ((q & 1) << 1);
                        int qy0 = ty0 + (q & 2);
                        int inBounds = quadInBoundsTileCount(tilesX, tilesY, qx0, qy0);
                        if (inBounds == 0) {
                            continue;
                        }
                        if (inBounds > 1 && canCoalesceZeroBodyRegion(flatPointers, tilesX, tilesY, qx0, qy0, 2)) {
                            leafCount++;
                        } else {
                            splitMask4 |= (1 << q);
                            leafCount += inBounds;
                        }
                    }
                    ctuDesc6[ctuIdx] = 0x10 | splitMask4;
                }
            }
        }

        int totalDirWords = ctuWords + leafCount;
        if (totalDirWords >= nTiles || totalDirWords > 65535) {
            return null;
        }

        long[] dirWords = new long[totalDirWords];
        byte[] dirSyndromes = new byte[totalDirWords];
        int leafWritePos = 0;
        for (int w = 0; w < ctuWords; w++) {
            int groupLeafBase = leafWritePos;
            long packedDesc48 = 0L;
            for (int k = 0; k < 8; k++) {
                int ctuIdx = (w << 3) + k;
                if (ctuIdx >= ctuCount) {
                    break;
                }
                int desc6 = ctuDesc6[ctuIdx];
                packedDesc48 |= ((long) (desc6 & 0x3F)) << (k * 6);
                int cx = ctuIdx % ctuX;
                int cy = ctuIdx / ctuX;
                leafWritePos = emitCtuQuadtreeLeaves(
                    desc6, flatPointers, flatSyndromes, tilesX, tilesY,
                    cx << 2, cy << 2, dirWords, dirSyndromes, ctuWords + leafWritePos
                ) - ctuWords;
            }
            long ctuWord64 = (((long) (groupLeafBase & 0xFFFF)) << 48) | packedDesc48;
            dirWords[w] = ctuWord64;
            dirSyndromes[w] = LcvPrimitives.computeHammingSecDed8(ctuWord64);
        }
        return new QuadtreeDirectoryPackResult(dirWords, dirSyndromes, totalDirWords);
    }

    private static int emitCtuQuadtreeLeaves(
        int desc6, long[] flatPointers, byte[] flatSyndromes,
        int tilesX, int tilesY, int tx0, int ty0,
        long[] dstWords, byte[] dstSyndromes, int writePos
    ) {
        if (desc6 == 0) {
            int baseIdx = ty0 * tilesX + tx0;
            dstWords[writePos] = flatPointers[baseIdx];
            dstSyndromes[writePos] = flatSyndromes[baseIdx];
            return writePos + 1;
        }
        int splitMask4 = desc6 & 0x0F;
        int cursor = writePos;
        for (int q = 0; q < 4; q++) {
            int qx0 = tx0 + ((q & 1) << 1);
            int qy0 = ty0 + (q & 2);
            int qw = Math.max(0, Math.min(2, tilesX - qx0));
            int qh = Math.max(0, Math.min(2, tilesY - qy0));
            if (qw * qh == 0) {
                continue;
            }
            if (((splitMask4 >>> q) & 1) == 0) {
                int baseIdx = qy0 * tilesX + qx0;
                dstWords[cursor] = flatPointers[baseIdx];
                dstSyndromes[cursor] = flatSyndromes[baseIdx];
                cursor++;
            } else {
                for (int dy = 0; dy < qh; dy++) {
                    int rowOff = (qy0 + dy) * tilesX + qx0;
                    for (int dx = 0; dx < qw; dx++) {
                        dstWords[cursor] = flatPointers[rowOff + dx];
                        dstSyndromes[cursor] = flatSyndromes[rowOff + dx];
                        cursor++;
                    }
                }
            }
        }
        return cursor;
    }

    private static boolean canCoalesceZeroBodyRegion(
        long[] flatPointers, int tilesX, int tilesY, int tx0, int ty0, int span
    ) {
        int endX = Math.min(tilesX, tx0 + span);
        int endY = Math.min(tilesY, ty0 + span);
        if (endX <= tx0 || endY <= ty0) {
            return false;
        }
        long ref = flatPointers[ty0 * tilesX + tx0];
        if (!isZeroBodyPointer(ref)) {
            return false;
        }
        boolean refCleanAck = isTemporalCleanAckPointer(ref);
        long refHeader18 = ref >>> 46;
        for (int ty = ty0; ty < endY; ty++) {
            int rowOff = ty * tilesX;
            for (int tx = tx0; tx < endX; tx++) {
                long cand = flatPointers[rowOff + tx];
                if (!isZeroBodyPointer(cand)) {
                    return false;
                }
                if (refCleanAck) {
                    if (!isTemporalCleanAckPointer(cand) || (cand >>> 46) != refHeader18) {
                        return false;
                    }
                } else if (cand != ref) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int quadInBoundsTileCount(int tilesX, int tilesY, int qx0, int qy0) {
        int qw = Math.max(0, Math.min(2, tilesX - qx0));
        int qh = Math.max(0, Math.min(2, tilesY - qy0));
        return qw * qh;
    }

    private static int ctuLeafCount(int desc6, int tilesX, int tilesY, int cx, int cy) {
        if (desc6 == 0) {
            return 1;
        }
        int tx0 = cx << 2;
        int ty0 = cy << 2;
        int splitMask4 = desc6 & 0x0F;
        int count = 0;
        for (int q = 0; q < 4; q++) {
            int qx0 = tx0 + ((q & 1) << 1);
            int qy0 = ty0 + (q & 2);
            int inBounds = quadInBoundsTileCount(tilesX, tilesY, qx0, qy0);
            if (inBounds > 0) {
                count += ((splitMask4 >>> q) & 1) != 0 ? inBounds : 1;
            }
        }
        return count;
    }

    private static int ctuTileLeafOffset(int desc6, int tilesX, int tilesY, int cx, int cy, int tx, int ty) {
        if (desc6 == 0) {
            return 0;
        }
        int tx0 = cx << 2;
        int ty0 = cy << 2;
        int lx = tx - tx0;
        int ly = ty - ty0;
        int targetQ = ((ly >>> 1) << 1) | (lx >>> 1);
        int splitMask4 = desc6 & 0x0F;
        int rel = 0;
        for (int q = 0; q < targetQ; q++) {
            int qx0 = tx0 + ((q & 1) << 1);
            int qy0 = ty0 + (q & 2);
            int inBounds = quadInBoundsTileCount(tilesX, tilesY, qx0, qy0);
            if (inBounds > 0) {
                rel += ((splitMask4 >>> q) & 1) != 0 ? inBounds : 1;
            }
        }
        if (((splitMask4 >>> targetQ) & 1) != 0) {
            int qx0 = tx0 + ((targetQ & 1) << 1);
            int qw = Math.max(1, Math.min(2, tilesX - qx0));
            rel += (ly & 1) * qw + (lx & 1);
        }
        return rel;
    }

    /**
     * Unpacks and heals the Section 2 & 3 tile directory (supporting both flat {@code tilesX * tilesY}
     * and rank-indexed {@code 16/32/64} quadtree-coalesced directories) into a flat {@code nTiles} array.
     */
    public static UnpackedDirectory unpackSuperblockDirectory(
        byte[] streamBytes, int offset, SuperblockHeader header
    ) {
        int tilesX = header.tilesX16();
        int tilesY = header.tilesY16();
        int nTiles = header.totalTiles();
        int dirBase = offset + header.directoryOffset();
        int synBase = offset + header.secDedOffset();
        ByteBuffer buf = ByteBuffer.wrap(streamBytes).order(ByteOrder.BIG_ENDIAN);

        long[] rawPointers = new long[nTiles];
        byte[] syndromes = new byte[nTiles];
        long[] healedPointers = new long[nTiles];

        if (!header.isQuadtreeDirectory()) {
            int healedCount = 0;
            for (int i = 0; i < nTiles; i++) {
                long rawPtr = buf.getLong(dirBase + i * 8);
                byte syn = streamBytes[synBase + i];
                long healed = LcvPrimitives.healSingleBitFlip64(rawPtr, syn);
                if (healed != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL && healed != rawPtr) {
                    healedCount++;
                }
                rawPointers[i] = rawPtr;
                syndromes[i] = syn;
                healedPointers[i] = healed;
            }
            return new UnpackedDirectory(rawPointers, syndromes, healedPointers, healedCount);
        }

        int ctuX = header.ctuX();
        int ctuCount = header.ctuCount();
        int ctuWords = header.ctuDescriptorWords();
        int dirWords = header.directoryWords();
        int[] healedCounter = new int[1];

        for (int w = 0; w < ctuWords; w++) {
            long rawCtuWord = buf.getLong(dirBase + w * 8);
            byte ctuSyn = streamBytes[synBase + w];
            long healedCtuWord = LcvPrimitives.healSingleBitFlip64(rawCtuWord, ctuSyn);
            if (healedCtuWord == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                markCtuGroupQuarantined(
                    w, ctuCount, ctuX, tilesX, tilesY, rawCtuWord, ctuSyn, rawPointers, syndromes, healedPointers
                );
                continue;
            }
            if (healedCtuWord != rawCtuWord) {
                healedCounter[0]++;
            }
            int leafCursor = (int) ((healedCtuWord >>> 48) & 0xFFFFL);
            for (int k = 0; k < 8; k++) {
                int ctuIdx = (w << 3) + k;
                if (ctuIdx >= ctuCount) {
                    break;
                }
                int desc6 = (int) ((healedCtuWord >>> (k * 6)) & 0x3FL);
                int cx = ctuIdx % ctuX;
                int cy = ctuIdx / ctuX;
                leafCursor = unpackSingleCtuFromQuadtree(
                    desc6, leafCursor, ctuWords, dirWords, buf, streamBytes, dirBase, synBase,
                    tilesX, tilesY, cx << 2, cy << 2, rawPointers, syndromes, healedPointers, healedCounter
                );
            }
        }
        return new UnpackedDirectory(rawPointers, syndromes, healedPointers, healedCounter[0]);
    }

    private static void markCtuGroupQuarantined(
        int wordIdx, int ctuCount, int ctuX, int tilesX, int tilesY,
        long rawWord, byte syn, long[] rawPointers, byte[] syndromes, long[] healedPointers
    ) {
        for (int k = 0; k < 8; k++) {
            int ctuIdx = (wordIdx << 3) + k;
            if (ctuIdx >= ctuCount) {
                break;
            }
            int tx0 = (ctuIdx % ctuX) << 2;
            int ty0 = (ctuIdx / ctuX) << 2;
            fillQuadtreeBlockPointers(
                tilesX, tilesY, tx0, ty0, 4,
                rawWord, syn, LcvPrimitives.UNCORRECTABLE_DED_SENTINEL,
                rawPointers, syndromes, healedPointers
            );
        }
    }

    private static int unpackSingleCtuFromQuadtree(
        int desc6, int leafCursor, int ctuWords, int dirWords,
        ByteBuffer buf, byte[] streamBytes, int dirBase, int synBase,
        int tilesX, int tilesY, int tx0, int ty0,
        long[] rawPointers, byte[] syndromes, long[] healedPointers, int[] healedCounter
    ) {
        if (desc6 == 0) {
            readAndFillQuadtreeLeaf(
                ctuWords + leafCursor, dirWords, buf, streamBytes, dirBase, synBase,
                tilesX, tilesY, tx0, ty0, 4, rawPointers, syndromes, healedPointers, healedCounter
            );
            return leafCursor + 1;
        }
        int splitMask4 = desc6 & 0x0F;
        int cursor = leafCursor;
        for (int q = 0; q < 4; q++) {
            int qx0 = tx0 + ((q & 1) << 1);
            int qy0 = ty0 + (q & 2);
            int qw = Math.max(0, Math.min(2, tilesX - qx0));
            int qh = Math.max(0, Math.min(2, tilesY - qy0));
            if (qw * qh == 0) {
                continue;
            }
            if (((splitMask4 >>> q) & 1) == 0) {
                readAndFillQuadtreeLeaf(
                    ctuWords + cursor, dirWords, buf, streamBytes, dirBase, synBase,
                    tilesX, tilesY, qx0, qy0, 2, rawPointers, syndromes, healedPointers, healedCounter
                );
                cursor++;
            } else {
                for (int dy = 0; dy < qh; dy++) {
                    for (int dx = 0; dx < qw; dx++) {
                        readAndFillQuadtreeLeaf(
                            ctuWords + cursor, dirWords, buf, streamBytes, dirBase, synBase,
                            tilesX, tilesY, qx0 + dx, qy0 + dy, 1,
                            rawPointers, syndromes, healedPointers, healedCounter
                        );
                        cursor++;
                    }
                }
            }
        }
        return cursor;
    }

    private static void readAndFillQuadtreeLeaf(
        int wordIdx, int dirWords, ByteBuffer buf, byte[] streamBytes, int dirBase, int synBase,
        int tilesX, int tilesY, int tx0, int ty0, int span,
        long[] rawPointers, byte[] syndromes, long[] healedPointers, int[] healedCounter
    ) {
        if (wordIdx < 0 || wordIdx >= dirWords) {
            fillQuadtreeBlockPointers(
                tilesX, tilesY, tx0, ty0, span,
                LcvPrimitives.UNCORRECTABLE_DED_SENTINEL, (byte) 0, LcvPrimitives.UNCORRECTABLE_DED_SENTINEL,
                rawPointers, syndromes, healedPointers
            );
            return;
        }
        long rawLeaf = buf.getLong(dirBase + wordIdx * 8);
        byte synLeaf = streamBytes[synBase + wordIdx];
        long healedLeaf = LcvPrimitives.healSingleBitFlip64(rawLeaf, synLeaf);
        if (healedLeaf != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL && healedLeaf != rawLeaf) {
            healedCounter[0]++;
        }
        long effectiveRaw = (healedLeaf != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) ? healedLeaf : rawLeaf;
        byte effectiveSyn = (healedLeaf != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL)
            ? LcvPrimitives.computeHammingSecDed8(healedLeaf)
            : synLeaf;
        fillQuadtreeBlockPointers(
            tilesX, tilesY, tx0, ty0, span,
            effectiveRaw, effectiveSyn, healedLeaf,
            rawPointers, syndromes, healedPointers
        );
    }

    private static void fillQuadtreeBlockPointers(
        int tilesX, int tilesY, int tx0, int ty0, int span,
        long rawPtr, byte syn, long healedPtr,
        long[] rawPointers, byte[] syndromes, long[] healedPointers
    ) {
        int endX = Math.min(tilesX, tx0 + span);
        int endY = Math.min(tilesY, ty0 + span);
        for (int ty = ty0; ty < endY; ty++) {
            int rowOff = ty * tilesX;
            for (int tx = tx0; tx < endX; tx++) {
                int idx = rowOff + tx;
                rawPointers[idx] = rawPtr;
                syndromes[idx] = syn;
                healedPointers[idx] = healedPtr;
            }
        }
    }

    /**
     * Resolves and {@code SEC-DED (72, 64)}-heals a single tile pointer {@code (tx, ty)} in {@code O(1)}
     * time (2 {@code uint64} reads via {@code groupLeafBase16} rank indexing when quadtree-coalesced).
     */
    public static long readAndHealSingleTilePointer(
        byte[] streamBytes, ByteBuffer buf, int sbOffset, SuperblockHeader hdr, int tx, int ty
    ) {
        int tilesX = hdr.tilesX16();
        int dirBase = sbOffset + hdr.directoryOffset();
        int synBase = sbOffset + hdr.secDedOffset();
        if (!hdr.isQuadtreeDirectory()) {
            int tileIdx = ty * tilesX + tx;
            long rawPtr = buf.getLong(dirBase + tileIdx * 8);
            byte syn = streamBytes[synBase + tileIdx];
            return LcvPrimitives.healSingleBitFlip64(rawPtr, syn);
        }
        int tilesY = hdr.tilesY16();
        int ctuX = hdr.ctuX();
        int cx = tx >>> 2;
        int cy = ty >>> 2;
        int ctuIdx = cy * ctuX + cx;
        int w = ctuIdx >>> 3;
        int k = ctuIdx & 7;
        long rawCtuWord = buf.getLong(dirBase + w * 8);
        byte ctuSyn = streamBytes[synBase + w];
        long healedCtuWord = LcvPrimitives.healSingleBitFlip64(rawCtuWord, ctuSyn);
        if (healedCtuWord == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
            return LcvPrimitives.UNCORRECTABLE_DED_SENTINEL;
        }
        int leafIdx = (int) ((healedCtuWord >>> 48) & 0xFFFFL);
        for (int p = 0; p < k; p++) {
            int pCtuIdx = (w << 3) + p;
            int pDesc6 = (int) ((healedCtuWord >>> (p * 6)) & 0x3FL);
            leafIdx += ctuLeafCount(pDesc6, tilesX, tilesY, pCtuIdx % ctuX, pCtuIdx / ctuX);
        }
        int desc6 = (int) ((healedCtuWord >>> (k * 6)) & 0x3FL);
        leafIdx += ctuTileLeafOffset(desc6, tilesX, tilesY, cx, cy, tx, ty);
        int wordIdx = hdr.ctuDescriptorWords() + leafIdx;
        if (wordIdx < hdr.ctuDescriptorWords() || wordIdx >= hdr.directoryWords()) {
            return LcvPrimitives.UNCORRECTABLE_DED_SENTINEL;
        }
        long rawPtr = buf.getLong(dirBase + wordIdx * 8);
        byte syn = streamBytes[synBase + wordIdx];
        return LcvPrimitives.healSingleBitFlip64(rawPtr, syn);
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
        return decodeSuperblock(streamBytes, offset, LcvCubeCodec.LcvKeySchedule.OPEN_MIT, decoderState);
    }

    /**
     * Keyed Structural Codebook ({@code KSC}) overload for {@link #decodeSuperblock}.
     */
    public static SuperblockDecodeResult decodeSuperblock(
        byte[] streamBytes, int offset, LcvCubeCodec.LcvKeySchedule keySchedule, StreamDecoderState decoderState
    ) throws LcvCorruptTileException {
        SuperblockHeader header = readSuperblockHeader(streamBytes, offset);
        int width = header.width16();
        int height = header.height16();
        int depthK = header.depthK8();
        int tilesX = header.tilesX16();
        int tilesY = header.tilesY16();
        int roiTileX = header.roiTileX16();
        int roiTileY = header.roiTileY16();
        long frameIndex64 = header.frameIndex64();
        int nTiles = header.totalTiles();
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        LcvCubeCodec.LcvKeySchedule activeKeySchedule =
            (keySchedule != null) ? keySchedule : LcvCubeCodec.LcvKeySchedule.OPEN_MIT;

        int totalPixels = width * height * depthK;
        int[] rgbaFrames = (decoderState != null)
            ? decoderState.acquireOutputBuffer(totalPixels)
            : new int[totalPixels];
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
        boolean zstdDict = (header.flags8() & FLAG_ZSTD_DICT_POOL) != 0;
        boolean lsbStripped = (header.flags8() & FLAG_LSB_STRIPPED) != 0 || header.lsbPoolCompressedLen32() == 0;

        if (zstdCompressed) {
            byte[] activeMsbDict = (zstdDict && decoderState != null) ? decoderState.zstdMsbDict : null;
            byte[] activeLsbDict = (zstdDict && decoderState != null) ? decoderState.zstdLsbDict : null;
            msbPool = decompressZstdFrameWithDict(
                streamBytes, msbPoolFileOff, header.msbPoolCompressedLen32(), activeMsbDict
            );
            msbPoolBase = 0;
            if (!lsbStripped && header.lsbPoolCompressedLen32() > 0) {
                lsbPool = decompressZstdFrameWithDict(
                    streamBytes, lsbPoolFileOff, header.lsbPoolCompressedLen32(), activeLsbDict
                );
                lsbPoolBase = 0;
            } else {
                lsbPool = new byte[0];
                lsbPoolBase = 0;
            }
            if (decoderState != null && !zstdDict) {
                decoderState.zstdMsbDict = sliceDictTail(msbPool);
                if (lsbPool.length > 0) {
                    decoderState.zstdLsbDict = sliceDictTail(lsbPool);
                }
            }
        }
        UnpackedDirectory unpackedDir = unpackSuperblockDirectory(streamBytes, offset, header);
        long[] healedDirPointers = unpackedDir.healedPointers();
        int healedPointers = unpackedDir.healedSingleBitPointers();
        int quarantinedTiles = 0;
        int cleanAckTiles = 0;
        int uniformTiles = 0;
        boolean[] quarantinedMask = null;
        boolean[] coldQuarantinedMask = null;
        int[] tilePackedMv8 = new int[nTiles];
        Arrays.fill(tilePackedMv8, 0x88);
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
                long healedPtr64 = healedDirPointers[tileIdx];

                if (healedPtr64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                    // Multi-bit pointer corruption -> quarantine single tile (zero DED contagion to other tiles!)
                    quarantinedTiles++;
                    if (quarantinedMask == null) {
                        quarantinedMask = new boolean[nTiles];
                    }
                    quarantinedMask[tileIdx] = true;
                    if (decoderState == null || tileIdx >= decoderState.hasCachedTile.length || !decoderState.hasCachedTile[tileIdx]) {
                        if (coldQuarantinedMask == null) {
                            coldQuarantinedMask = new boolean[nTiles];
                        }
                        coldQuarantinedMask[tileIdx] = true;
                    }
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                    continue;
                }

                if (isZeroBodyPointer(healedPtr64)) {
                    if (isTemporalCleanAckPointer(healedPtr64)) {
                        cleanAckTiles++;
                        int shiftDx = extractCleanAckShiftDx(healedPtr64);
                        int shiftDy = extractCleanAckShiftDy(healedPtr64);
                        tilePackedMv8[tileIdx] = (((shiftDx + 8) & 0x0F) << 4) | ((shiftDy + 8) & 0x0F);
                        if (shiftDx != 0 || shiftDy != 0) {
                            applyShiftedCleanAckTile(
                                decoderState, tileIdx, tx, ty, shiftDx, shiftDy, width, height, depthK, rgbaFrames
                            );
                        } else {
                            applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                        }
                    } else {
                        uniformTiles++;
                        int rgba = extractUniformInlineRgba(healedPtr64);
                        scatterUniformTileToFrames(rgba, tx, ty, width, height, depthK, rgbaFrames);
                        updateDecoderCacheUniform(decoderState, tileIdx, rgba);
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

                int[] tileCubeBuf;
                if (decoderState != null && tileIdx < decoderState.cachedTileCubesTyx.length) {
                    tileCubeBuf = decoderState.cachedTileCubesTyx[tileIdx];
                    if (tileCubeBuf == null) {
                        tileCubeBuf = new int[nSamples];
                        decoderState.cachedTileCubesTyx[tileIdx] = tileCubeBuf;
                    }
                } else {
                    tileCubeBuf = arena.cubeRgbaTyx;
                }
                try {
                    LcvCubeCodec.decodeSplitCubeRgba(
                        msbPool, msbPoolBase + relMsbOff, msbLen,
                        lsbPool, tileLsbOff, lsbLen,
                        false, depthK, tileSplitBits,
                        tx, ty, roiTileX, roiTileY,
                        activeKeySchedule, frameIndex64,
                        arena, tileCubeBuf
                    );
                    tilePackedMv8[tileIdx] = peekTileMotionVector8(msbPool, msbPoolBase, healedPtr64);
                    LcvCubeCodec.scatterCubeTyxToFrames(tileCubeBuf, tx, ty, width, height, depthK, rgbaFrames);
                    if (decoderState != null && tileIdx < decoderState.hasCachedTile.length) {
                        decoderState.isCachedUniform[tileIdx] = false;
                        decoderState.hasCachedTile[tileIdx] = true;
                    }
                } catch (LcvCorruptTileException ex) {
                    // CBIT quarantine: confine damage to this single 16x16xK tile
                    quarantinedTiles++;
                    if (quarantinedMask == null) {
                        quarantinedMask = new boolean[nTiles];
                    }
                    quarantinedMask[tileIdx] = true;
                    if (decoderState == null || tileIdx >= decoderState.hasCachedTile.length || !decoderState.hasCachedTile[tileIdx]) {
                        if (coldQuarantinedMask == null) {
                            coldQuarantinedMask = new boolean[nTiles];
                        }
                        coldQuarantinedMask[tileIdx] = true;
                    }
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                }
            }
        }

        if (quarantinedMask != null) {
            concealQuarantinedTilesWithNeighborFlow(
                rgbaFrames, quarantinedMask, coldQuarantinedMask, tilePackedMv8,
                tilesX, tilesY, width, height, depthK, decoderState
            );
        }
        if (coldQuarantinedMask != null) {
            reconstructColdQuarantinedTilesSpatiotemporal(
                rgbaFrames, coldQuarantinedMask, tilesX, tilesY, width, height, depthK, decoderState, arena
            );
        }
        if (header.isLsbStripped() && !partialRecode) {
            smoothStrippedTileBoundaries(
                rgbaFrames, tilesX, tilesY, width, height, depthK, header.tuningProfile().lsbSplitBits()
            );
        }
        if (decoderState != null) {
            decoderState.lastDecodedFrames = rgbaFrames;
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
        return decodeSuperblockParallel(streamBytes, offset, LcvCubeCodec.LcvKeySchedule.OPEN_MIT, decoderState);
    }

    /**
     * Keyed Structural Codebook ({@code KSC}) overload for {@link #decodeSuperblockParallel}.
     */
    public static SuperblockDecodeResult decodeSuperblockParallel(
        byte[] streamBytes, int offset, LcvCubeCodec.LcvKeySchedule keySchedule, StreamDecoderState decoderState
    ) throws LcvCorruptTileException {
        SuperblockHeader header = readSuperblockHeader(streamBytes, offset);
        final int width = header.width16();
        final int height = header.height16();
        final int depthK = header.depthK8();
        final int tilesX = header.tilesX16();
        final int tilesY = header.tilesY16();
        final int roiTileX = header.roiTileX16();
        final int roiTileY = header.roiTileY16();
        final long frameIndex64 = header.frameIndex64();
        final int nTiles = header.totalTiles();
        final int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        final LcvCubeCodec.LcvKeySchedule activeKeySchedule =
            (keySchedule != null) ? keySchedule : LcvCubeCodec.LcvKeySchedule.OPEN_MIT;

        final int totalPixels = width * height * depthK;
        final int[] rgbaFrames = (decoderState != null)
            ? decoderState.acquireOutputBuffer(totalPixels)
            : new int[totalPixels];
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
        boolean zstdDict = (header.flags8() & FLAG_ZSTD_DICT_POOL) != 0;
        final boolean lsbStripped = (header.flags8() & FLAG_LSB_STRIPPED) != 0 || header.lsbPoolCompressedLen32() == 0;

        if (zstdCompressed) {
            byte[] activeMsbDict = (zstdDict && decoderState != null) ? decoderState.zstdMsbDict : null;
            byte[] activeLsbDict = (zstdDict && decoderState != null) ? decoderState.zstdLsbDict : null;
            msbPool = decompressZstdFrameWithDict(
                streamBytes, msbPoolFileOff, header.msbPoolCompressedLen32(), activeMsbDict
            );
            msbPoolBase = 0;
            if (!lsbStripped && header.lsbPoolCompressedLen32() > 0) {
                lsbPool = decompressZstdFrameWithDict(
                    streamBytes, lsbPoolFileOff, header.lsbPoolCompressedLen32(), activeLsbDict
                );
                lsbPoolBase = 0;
            } else {
                lsbPool = new byte[0];
                lsbPoolBase = 0;
            }
            if (decoderState != null && !zstdDict) {
                decoderState.zstdMsbDict = sliceDictTail(msbPool);
                if (lsbPool.length > 0) {
                    decoderState.zstdLsbDict = sliceDictTail(lsbPool);
                }
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

        final UnpackedDirectory unpackedDir = unpackSuperblockDirectory(streamBytes, offset, header);
        final long[] healedDirPointers = unpackedDir.healedPointers();
        final int[] perRowQuarantined = new int[tilesY];
        final int[] perRowCleanAck = new int[tilesY];
        final int[] perRowUniform = new int[tilesY];
        final boolean[] quarantinedMask = new boolean[nTiles];
        final boolean[] coldQuarantinedMask = new boolean[nTiles];
        final int[] tilePackedMv8 = new int[nTiles];
        Arrays.fill(tilePackedMv8, 0x88);

        final int rowStride = (tilesY > 7 && (tilesY % 7) != 0) ? 7 : 1;
        java.util.stream.IntStream.range(0, tilesY).parallel().forEach(w -> {
            final int ty = (w * rowStride) % tilesY;
            LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
            int rowQuarantined = 0;
            int rowCleanAck = 0;
            int rowUniform = 0;
            int rowBaseIdx = ty * tilesX;
            for (int tx = 0; tx < tilesX; tx++) {
                int tileIdx = rowBaseIdx + tx;
                long healedPtr64 = healedDirPointers[tileIdx];

                if (healedPtr64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                    rowQuarantined++;
                    quarantinedMask[tileIdx] = true;
                    if (decoderState == null || tileIdx >= decoderState.hasCachedTile.length || !decoderState.hasCachedTile[tileIdx]) {
                        coldQuarantinedMask[tileIdx] = true;
                    }
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                    continue;
                }

                if (isZeroBodyPointer(healedPtr64)) {
                    if (isTemporalCleanAckPointer(healedPtr64)) {
                        rowCleanAck++;
                        int shiftDx = extractCleanAckShiftDx(healedPtr64);
                        int shiftDy = extractCleanAckShiftDy(healedPtr64);
                        tilePackedMv8[tileIdx] = (((shiftDx + 8) & 0x0F) << 4) | ((shiftDy + 8) & 0x0F);
                        if (shiftDx != 0 || shiftDy != 0) {
                            applyShiftedCleanAckTile(
                                decoderState, tileIdx, tx, ty, shiftDx, shiftDy, width, height, depthK, rgbaFrames
                            );
                        } else {
                            applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                        }
                    } else {
                        rowUniform++;
                        int rgba = extractUniformInlineRgba(healedPtr64);
                        scatterUniformTileToFrames(rgba, tx, ty, width, height, depthK, rgbaFrames);
                        updateDecoderCacheUniform(decoderState, tileIdx, rgba);
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

                int[] tileCubeBuf;
                if (decoderState != null && tileIdx < decoderState.cachedTileCubesTyx.length) {
                    tileCubeBuf = decoderState.cachedTileCubesTyx[tileIdx];
                    if (tileCubeBuf == null) {
                        tileCubeBuf = new int[nSamples];
                        decoderState.cachedTileCubesTyx[tileIdx] = tileCubeBuf;
                    }
                } else {
                    tileCubeBuf = arena.cubeRgbaTyx;
                }
                try {
                    LcvCubeCodec.decodeSplitCubeRgba(
                        msbPool, msbPoolBase + relMsbOff, msbLen,
                        lsbPool, tileLsbOff, lsbLen,
                        false, depthK, tileSplitBits,
                        tx, ty, roiTileX, roiTileY,
                        activeKeySchedule, frameIndex64,
                        arena, tileCubeBuf
                    );
                    tilePackedMv8[tileIdx] = peekTileMotionVector8(msbPool, msbPoolBase, healedPtr64);
                    LcvCubeCodec.scatterCubeTyxToFrames(tileCubeBuf, tx, ty, width, height, depthK, rgbaFrames);
                    if (decoderState != null && tileIdx < decoderState.hasCachedTile.length) {
                        decoderState.isCachedUniform[tileIdx] = false;
                        decoderState.hasCachedTile[tileIdx] = true;
                    }
                } catch (LcvCorruptTileException ex) {
                    rowQuarantined++;
                    quarantinedMask[tileIdx] = true;
                    if (decoderState == null || tileIdx >= decoderState.hasCachedTile.length || !decoderState.hasCachedTile[tileIdx]) {
                        coldQuarantinedMask[tileIdx] = true;
                    }
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                }
            }
            perRowQuarantined[ty] = rowQuarantined;
            perRowCleanAck[ty] = rowCleanAck;
            perRowUniform[ty] = rowUniform;
        });

        int healedPointers = unpackedDir.healedSingleBitPointers();
        int quarantinedTiles = 0;
        int cleanAckTiles = 0;
        int uniformTiles = 0;
        for (int ty = 0; ty < tilesY; ty++) {
            quarantinedTiles += perRowQuarantined[ty];
            cleanAckTiles += perRowCleanAck[ty];
            uniformTiles += perRowUniform[ty];
        }

        if (quarantinedTiles > 0) {
            concealQuarantinedTilesWithNeighborFlow(
                rgbaFrames, quarantinedMask, coldQuarantinedMask, tilePackedMv8,
                tilesX, tilesY, width, height, depthK, decoderState
            );
            reconstructColdQuarantinedTilesSpatiotemporal(
                rgbaFrames, coldQuarantinedMask, tilesX, tilesY, width, height, depthK,
                decoderState, LcvPrimitives.threadArena()
            );
        }
        if (header.isLsbStripped() && !partialRecode) {
            smoothStrippedTileBoundaries(
                rgbaFrames, tilesX, tilesY, width, height, depthK, header.tuningProfile().lsbSplitBits()
            );
        }
        if (decoderState != null) {
            decoderState.lastDecodedFrames = rgbaFrames;
        }

        return new SuperblockDecodeResult(
            header, rgbaFrames, healedPointers, quarantinedTiles, cleanAckTiles, uniformTiles
        );
    }

    private record TileRowEncodeResult(
        boolean[] uniform,
        int[] firstRgba,
        boolean[] cleanAck,
        int[] cleanAckShift8,
        long[] digests,
        int[] msbRelOff,
        int[] msbLen,
        int[] lsbRelOff,
        int[] lsbLen,
        byte[] msbBytes,
        byte[] lsbBytes
    ) {}

    /**
     * Scans an append-only or log-structured {@code .lcv} stream (potentially truncated mid-write by
     * {@code SIGKILL}, containing in-place slack/superseded records {@link #FLAG_SUPERSEDED_OR_SLACK},
     * or containing out-of-order {@code EOF} append-on-expand updates) and returns the active byte
     * offsets ordered by presentation {@code frameIndex64} (with later writes superseding earlier slots
     * at the same {@code frameIndex64}).
     */
    public static List<Integer> indexValidSuperblocks(byte[] streamBytes) {
        List<Integer> offsets = new ArrayList<>();
        List<Long> frameIndices = new ArrayList<>();
        boolean outOfTemporalOrder = false;
        int pos = 0;
        while (pos + SUPERBLOCK_HEADER_BYTES <= streamBytes.length) {
            try {
                SuperblockHeader header = readSuperblockHeader(streamBytes, pos);
                int sbBytes = header.totalSuperblockBytes();
                if (pos + sbBytes <= streamBytes.length) {
                    if ((header.flags8() & FLAG_SUPERSEDED_OR_SLACK) == 0) {
                        long fIdx = header.frameIndex64();
                        int existing = -1;
                        for (int i = 0; i < frameIndices.size(); i++) {
                            if (frameIndices.get(i) == fIdx) {
                                existing = i;
                                break;
                            }
                        }
                        if (existing >= 0) {
                            offsets.set(existing, pos);
                        } else {
                            if (!frameIndices.isEmpty() && fIdx < frameIndices.get(frameIndices.size() - 1)) {
                                outOfTemporalOrder = true;
                            }
                            frameIndices.add(fIdx);
                            offsets.add(pos);
                        }
                    }
                    pos += sbBytes;
                } else {
                    break;
                }
            } catch (LcvCorruptTileException ex) {
                break;
            }
        }
        if (outOfTemporalOrder && offsets.size() > 1) {
            Integer[] idxOrder = new Integer[offsets.size()];
            for (int i = 0; i < idxOrder.length; i++) {
                idxOrder[i] = i;
            }
            Arrays.sort(idxOrder, (a, b) -> Long.compare(frameIndices.get(a), frameIndices.get(b)));
            List<Integer> sorted = new ArrayList<>(offsets.size());
            for (int idx : idxOrder) {
                sorted.add(offsets.get(idx));
            }
            return sorted;
        }
        return offsets;
    }

    /**
     * Creates a valid {@code LCV_V001} slack/padding {@code Superblock} of {@code totalSlackBytes}
     * ({@code >= 64}) marked with {@link #FLAG_SUPERSEDED_OR_SLACK} so forward address-order readers
     * jump across shrunk in-place Superblock gaps or sparse holes in {@code O(1)} without byte-scanning.
     */
    public static byte[] createSlackSuperblockBytes(int totalSlackBytes, long prevSbOffset64) {
        if (totalSlackBytes < SUPERBLOCK_HEADER_BYTES) {
            throw new IllegalArgumentException("Slack Superblock requires >= 64 bytes: " + totalSlackBytes);
        }
        byte[] out = new byte[totalSlackBytes];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(0x00, MAGIC_LCV_V001);
        buf.putShort(0x08, (short) FORMAT_VERSION_1_0);
        buf.putShort(0x0A, (short) 16);
        buf.putShort(0x0C, (short) 16);
        buf.put(0x0E, (byte) 1);
        buf.put(0x0F, (byte) 8);
        buf.put(0x10, (byte) 0);
        buf.put(0x11, (byte) FLAG_SUPERSEDED_OR_SLACK);
        buf.putShort(0x12, (short) 0);
        buf.putShort(0x14, (short) 0);
        buf.putShort(0x16, (short) 0);
        buf.putShort(0x18, (short) 0);
        buf.putShort(0x1A, (short) 0);
        buf.putInt(0x1C, totalSlackBytes - SUPERBLOCK_HEADER_BYTES);
        buf.putInt(0x20, 0);
        buf.putLong(0x24, 0L);
        buf.putLong(0x2C, prevSbOffset64);
        buf.putInt(0x34, 60);
        buf.putInt(0x38, 1);
        buf.putInt(0x3C, computeHeaderCrc32(out, 0));
        return out;
    }

    /**
     * Sweeps a fragmented or out-of-order {@code .lcv} stream in physical address order, drops
     * {@link #FLAG_SUPERSEDED_OR_SLACK} slack records and superseded {@code frameIndex64} slots,
     * and writes a compacted, temporally monotonic stream with refreshed {@code prevSbOffset64}
     * and {@code headerCrc32} links (the {@code HDD -> SSD} / RAM-window elevator compaction pass).
     */
    public static byte[] compactStreamToTemporalOrder(byte[] streamBytes) throws LcvCorruptTileException {
        List<Integer> activeOffsets = indexValidSuperblocks(streamBytes);
        ByteArrayOutputStream out = new ByteArrayOutputStream(streamBytes.length);
        long prevOff = 0L;
        for (int off : activeOffsets) {
            SuperblockHeader hdr = readSuperblockHeader(streamBytes, off);
            int sbBytes = hdr.totalSuperblockBytes();
            long curOff = out.size();
            byte[] slice = Arrays.copyOfRange(streamBytes, off, off + sbBytes);
            ByteBuffer buf = ByteBuffer.wrap(slice).order(ByteOrder.BIG_ENDIAN);
            buf.putLong(0x2C, prevOff);
            buf.putInt(0x3C, computeHeaderCrc32(slice, 0));
            out.writeBytes(slice);
            prevOff = curOff;
        }
        return out.toByteArray();
    }

    /**
     * Reconstructs cold/anchor quarantined tiles (where {@code decoderState} has no prior temporal cache)
     * using inverse-distance weighted boundary interpolation from healthy 4-connected spatial neighbor tiles
     * in the current {@code Superblock}, and seeds {@code decoderState} so subsequent {@code TEMPORAL_CLEAN_ACK}
     * frames inherit the reconstructed tile instead of black holes.
     */
    private static void reconstructColdQuarantinedTilesSpatiotemporal(
        int[] rgbaFrames, boolean[] coldQuarantinedMask,
        int tilesX, int tilesY, int width, int height, int depthK,
        StreamDecoderState decoderState, LcvPrimitives.ScratchArena arena
    ) {
        int frameArea = width * height;
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tileIdx = ty * tilesX + tx;
                if (!coldQuarantinedMask[tileIdx]) {
                    continue;
                }
                boolean hasLeft = (tx > 0) && !coldQuarantinedMask[tileIdx - 1];
                boolean hasRight = (tx + 1 < tilesX) && !coldQuarantinedMask[tileIdx + 1];
                boolean hasTop = (ty > 0) && !coldQuarantinedMask[tileIdx - tilesX];
                boolean hasBot = (ty + 1 < tilesY) && !coldQuarantinedMask[tileIdx + tilesX];
                if (!hasLeft && !hasRight && !hasTop && !hasBot) {
                    continue;
                }

                int baseX = tx * LcvPrimitives.TILE_DIM;
                int baseY = ty * LcvPrimitives.TILE_DIM;
                int endX = Math.min(width, baseX + LcvPrimitives.TILE_DIM);
                int endY = Math.min(height, baseY + LcvPrimitives.TILE_DIM);
                int leftBorderX = Math.max(0, baseX - 1);
                int rightBorderX = Math.min(width - 1, endX);
                int topBorderY = Math.max(0, baseY - 1);
                int botBorderY = Math.min(height - 1, endY);

                for (int t = 0; t < depthK; t++) {
                    int fBase = t * frameArea;
                    for (int py = baseY; py < endY; py++) {
                        int ly = py - baseY;
                        int rowOff = fBase + py * width;
                        for (int px = baseX; px < endX; px++) {
                            int lx = px - baseX;
                            int sumR = 0;
                            int sumG = 0;
                            int sumB = 0;
                            int sumA = 0;
                            int wTotal = 0;
                            if (hasLeft) {
                                int w = 16 - lx;
                                int c = rgbaFrames[rowOff + leftBorderX];
                                sumR += ((c >>> 24) & 0xFF) * w;
                                sumG += ((c >>> 16) & 0xFF) * w;
                                sumB += ((c >>> 8) & 0xFF) * w;
                                sumA += (c & 0xFF) * w;
                                wTotal += w;
                            }
                            if (hasRight) {
                                int w = lx + 1;
                                int c = rgbaFrames[rowOff + rightBorderX];
                                sumR += ((c >>> 24) & 0xFF) * w;
                                sumG += ((c >>> 16) & 0xFF) * w;
                                sumB += ((c >>> 8) & 0xFF) * w;
                                sumA += (c & 0xFF) * w;
                                wTotal += w;
                            }
                            if (hasTop) {
                                int w = 16 - ly;
                                int c = rgbaFrames[fBase + topBorderY * width + px];
                                sumR += ((c >>> 24) & 0xFF) * w;
                                sumG += ((c >>> 16) & 0xFF) * w;
                                sumB += ((c >>> 8) & 0xFF) * w;
                                sumA += (c & 0xFF) * w;
                                wTotal += w;
                            }
                            if (hasBot) {
                                int w = ly + 1;
                                int c = rgbaFrames[fBase + botBorderY * width + px];
                                sumR += ((c >>> 24) & 0xFF) * w;
                                sumG += ((c >>> 16) & 0xFF) * w;
                                sumB += ((c >>> 8) & 0xFF) * w;
                                sumA += (c & 0xFF) * w;
                                wTotal += w;
                            }
                            if (wTotal > 0) {
                                int r = (sumR + (wTotal >> 1)) / wTotal;
                                int g = (sumG + (wTotal >> 1)) / wTotal;
                                int b = (sumB + (wTotal >> 1)) / wTotal;
                                int a = (sumA + (wTotal >> 1)) / wTotal;
                                rgbaFrames[rowOff + px] = (r << 24) | (g << 16) | (b << 8) | a;
                            }
                        }
                    }
                }
                if (decoderState != null && arena != null) {
                    LcvCubeCodec.extractTileToCubeTyx(rgbaFrames, tx, ty, width, height, depthK, arena.cubeRgbaTyx);
                    updateDecoderCache(decoderState, tileIdx, arena.cubeRgbaTyx, nSamples);
                }
            }
        }
    }

    private static void scatterUniformTileToFrames(
        int rgba, int tileX, int tileY, int frameWidth, int frameHeight, int depthK, int[] dstRgbaFrames
    ) {
        int frameArea = frameWidth * frameHeight;
        int baseX = tileX * LcvPrimitives.TILE_DIM;
        int baseY = tileY * LcvPrimitives.TILE_DIM;
        int endX = Math.min(frameWidth, baseX + LcvPrimitives.TILE_DIM);
        int endY = Math.min(frameHeight, baseY + LcvPrimitives.TILE_DIM);
        if (baseX >= endX || baseY >= endY) {
            return;
        }
        for (int t = 0; t < depthK; t++) {
            int frameBase = t * frameArea;
            for (int py = baseY; py < endY; py++) {
                int rowStart = frameBase + py * frameWidth;
                Arrays.fill(dstRgbaFrames, rowStart + baseX, rowStart + endX, rgba);
            }
        }
    }

    private static void applyFallbackTile(
        StreamDecoderState decoderState, int tileIdx,
        int tx, int ty, int width, int height, int depthK, int[] dstFrames
    ) {
        if (decoderState != null && tileIdx < decoderState.hasCachedTile.length && decoderState.hasCachedTile[tileIdx]) {
            if (decoderState.isCachedUniform[tileIdx]) {
                scatterUniformTileToFrames(decoderState.cachedUniformRgba[tileIdx], tx, ty, width, height, depthK, dstFrames);
            } else {
                LcvCubeCodec.scatterCubeTyxToFrames(
                    decoderState.cachedTileCubesTyx[tileIdx], tx, ty, width, height, depthK, dstFrames
                );
            }
        } else {
            scatterUniformTileToFrames(0, tx, ty, width, height, depthK, dstFrames);
        }
    }

    private static void updateDecoderCacheUniform(StreamDecoderState decoderState, int tileIdx, int rgba) {
        if (decoderState != null && tileIdx < decoderState.hasCachedTile.length) {
            decoderState.cachedUniformRgba[tileIdx] = rgba;
            decoderState.isCachedUniform[tileIdx] = true;
            decoderState.hasCachedTile[tileIdx] = true;
        }
    }

    private static void updateDecoderCache(StreamDecoderState decoderState, int tileIdx, int[] cubeTyx, int nSamples) {
        if (decoderState != null && tileIdx < decoderState.hasCachedTile.length) {
            if (decoderState.cachedTileCubesTyx[tileIdx] == null) {
                decoderState.cachedTileCubesTyx[tileIdx] = new int[nSamples];
            }
            System.arraycopy(cubeTyx, 0, decoderState.cachedTileCubesTyx[tileIdx], 0, nSamples);
            decoderState.isCachedUniform[tileIdx] = false;
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

    /**
     * Constructs a motion-shifted {@code TEMPORAL_CLEAN_ACK} pointer that references the prior superblock's
     * decoded canvas shifted by {@code (shiftDx, shiftDy) in [-8, +7]^2} pixels (encoding the 8-bit shift
     * in the upper 8 bits of {@code anchorSbDelta16} XOR {@code 0x88} so {@code (0, 0)} is wire-identical
     * to {@link #makeTemporalCleanAckPointer}).
     */
    public static long makeShiftedTemporalCleanAckPointer(
        int anchorSbDelta6, int shiftDx, int shiftDy, int anchorRelOffset30, int anchorTotalLen16
    ) {
        int packedMv8 = (((shiftDx + 8) & 0x0F) << 4) | ((shiftDy + 8) & 0x0F);
        int encodedShift8 = (packedMv8 ^ 0x88) & 0xFF;
        int combinedDelta16 = ((encodedShift8 << 6) | (anchorSbDelta6 & 0x3F)) & 0x3FFF;
        return makeTemporalCleanAckPointer(combinedDelta16, anchorRelOffset30, anchorTotalLen16);
    }

    public static int extractCleanAckShiftDx(long ptr64) {
        int combined16 = (int) ((ptr64 >>> 46) & 0x3FFFL);
        int encodedShift8 = (combined16 >>> 6) & 0xFF;
        int packedMv8 = encodedShift8 ^ 0x88;
        return LcvPrimitives.unpackVelocityX(packedMv8);
    }

    public static int extractCleanAckShiftDy(long ptr64) {
        int combined16 = (int) ((ptr64 >>> 46) & 0x3FFFL);
        int encodedShift8 = (combined16 >>> 6) & 0xFF;
        int packedMv8 = encodedShift8 ^ 0x88;
        return LcvPrimitives.unpackVelocityY(packedMv8);
    }

    private static int findShiftedCleanAckMatch8(
        int[] cubeRgbaTyx, int[] lastEncodedFrames,
        int tx, int ty, int width, int height, int depthK
    ) {
        if (lastEncodedFrames == null || lastEncodedFrames.length < width * height * depthK) {
            return -1;
        }
        int baseX = tx * LcvPrimitives.TILE_DIM;
        int baseY = ty * LcvPrimitives.TILE_DIM;
        if (baseX + LcvPrimitives.TILE_DIM > width || baseY + LcvPrimitives.TILE_DIM > height) {
            return -1;
        }
        int frameArea = width * height;
        int c0 = cubeRgbaTyx[0];
        int cMid = cubeRgbaTyx[(8 << 4) | 8];
        int cLast = cubeRgbaTyx[((depthK - 1) << 8) | (15 << 4) | 15];

        for (int dy = -8; dy <= 7; dy++) {
            int srcBaseY = baseY - dy;
            if (srcBaseY < 0 || srcBaseY + LcvPrimitives.TILE_DIM > height) {
                continue;
            }
            for (int dx = -8; dx <= 7; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int srcBaseX = baseX - dx;
                if (srcBaseX < 0 || srcBaseX + LcvPrimitives.TILE_DIM > width) {
                    continue;
                }
                // 3-point O(1) sentinel filter before full 16x16xK check
                if (lastEncodedFrames[srcBaseY * width + srcBaseX] != c0
                    || lastEncodedFrames[(srcBaseY + 8) * width + (srcBaseX + 8)] != cMid
                    || lastEncodedFrames[(depthK - 1) * frameArea + (srcBaseY + 15) * width + (srcBaseX + 15)] != cLast) {
                    continue;
                }
                boolean exact = true;
                outer:
                for (int t = 0; t < depthK; t++) {
                    int fBase = t * frameArea;
                    int tBase = t << 8;
                    for (int ly = 0; ly < LcvPrimitives.TILE_DIM; ly++) {
                        int srcRow = fBase + (srcBaseY + ly) * width + srcBaseX;
                        int cRow = tBase | (ly << 4);
                        for (int lx = 0; lx < LcvPrimitives.TILE_DIM; lx++) {
                            if (lastEncodedFrames[srcRow + lx] != cubeRgbaTyx[cRow | lx]) {
                                exact = false;
                                break outer;
                            }
                        }
                    }
                }
                if (exact) {
                    return ((dx + 8) << 4) | ((dy + 8) & 0x0F);
                }
            }
        }
        return -1;
    }

    private static void applyShiftedCleanAckTile(
        StreamDecoderState decoderState, int tileIdx,
        int tx, int ty, int shiftDx, int shiftDy,
        int width, int height, int depthK, int[] dstFrames
    ) {
        if (decoderState == null || decoderState.lastDecodedFrames == null
            || decoderState.lastDecodedFrames.length < width * height * depthK) {
            applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, dstFrames);
            return;
        }
        int[] srcFrames = decoderState.lastDecodedFrames;
        int frameArea = width * height;
        int baseX = tx * LcvPrimitives.TILE_DIM;
        int baseY = ty * LcvPrimitives.TILE_DIM;
        int endX = Math.min(width, baseX + LcvPrimitives.TILE_DIM);
        int endY = Math.min(height, baseY + LcvPrimitives.TILE_DIM);
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        int[] cachedCube = null;
        if (tileIdx < decoderState.cachedTileCubesTyx.length) {
            cachedCube = decoderState.cachedTileCubesTyx[tileIdx];
            if (cachedCube == null) {
                cachedCube = new int[nSamples];
                decoderState.cachedTileCubesTyx[tileIdx] = cachedCube;
            }
        }
        for (int t = 0; t < depthK; t++) {
            int fBase = t * frameArea;
            int tBase = t << 8;
            for (int py = baseY; py < endY; py++) {
                int ly = py - baseY;
                int srcY = Math.max(0, Math.min(height - 1, py - shiftDy));
                int dstRow = fBase + py * width;
                int srcRow = fBase + srcY * width;
                int cRow = tBase | (ly << 4);
                for (int px = baseX; px < endX; px++) {
                    int lx = px - baseX;
                    int srcX = Math.max(0, Math.min(width - 1, px - shiftDx));
                    int rgba = srcFrames[srcRow + srcX];
                    dstFrames[dstRow + px] = rgba;
                    if (cachedCube != null) {
                        cachedCube[cRow | lx] = rgba;
                    }
                }
            }
        }
        if (tileIdx < decoderState.hasCachedTile.length) {
            decoderState.isCachedUniform[tileIdx] = false;
            decoderState.hasCachedTile[tileIdx] = true;
        }
    }

    public static long makeActiveTilePointer(int relByteOffset30, int msbSliceByteLen16, int lsbSliceByteLen16) {
        if (relByteOffset30 < 0 || relByteOffset30 > MAX_REL_BYTE_OFFSET_30) {
            throw new IllegalArgumentException(
                "relByteOffset30 out of 30-bit [0, 1 GiB) range (" + relByteOffset30
                    + "); use LcvOnDiskTransformer.clampDepthKFor4k8kPoolHeadroom for 4K/8K superblocks"
            );
        }
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
        UnpackedDirectory unpackedDir = unpackSuperblockDirectory(superblockBytes, 0, header);
        long[] rawPointers = unpackedDir.rawPointers();
        byte[] syndromes = unpackedDir.syndromes();
        long[] healedPointers = unpackedDir.healedPointers();
        int msbPoolBase = header.msbPoolOffset();
        int lsbPoolBase = header.lsbPoolOffset();
        boolean lsbStripped = (header.flags8() & FLAG_LSB_STRIPPED) != 0 || header.lsbPoolCompressedLen32() == 0;
        int lsbTableBytes = nTiles * 4;

        List<MvCoherentPacket> packets = new ArrayList<>();
        int idx = 0;

        while (idx < nTiles) {
            int startIdx = idx;
            int groupMv8 = peekTileMotionVector8(superblockBytes, msbPoolBase, healedPointers[idx]);

            ByteArrayOutputStream msbOut = new ByteArrayOutputStream(512);
            ByteArrayOutputStream lsbOut = new ByteArrayOutputStream(256);
            long[] ptrScratch = new long[effectiveMaxTiles];
            byte[] synScratch = new byte[effectiveMaxTiles];
            int count = 0;

            while (idx < nTiles && count < effectiveMaxTiles) {
                int candMv8 = peekTileMotionVector8(superblockBytes, msbPoolBase, healedPointers[idx]);
                if (count > 0 && candMv8 != groupMv8) {
                    break; // Motion vector diverged -> close current packet and start a new MV packet
                }

                long rawPtr = rawPointers[idx];
                byte syn = syndromes[idx];
                long healedPtr = healedPointers[idx];

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
        boolean[] quarantinedMask = null;
        boolean[] coldQuarantinedMask = null;
        int[] tilePackedMv8 = new int[nTiles];
        Arrays.fill(tilePackedMv8, 0x88);
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
            int pktMv8 = pkt.packedMv8() & 0xFF;
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
                    if (quarantinedMask == null) {
                        quarantinedMask = new boolean[nTiles];
                    }
                    quarantinedMask[tileIdx] = true;
                    if (decoderState == null || tileIdx >= decoderState.hasCachedTile.length || !decoderState.hasCachedTile[tileIdx]) {
                        if (coldQuarantinedMask == null) {
                            coldQuarantinedMask = new boolean[nTiles];
                        }
                        coldQuarantinedMask[tileIdx] = true;
                    }
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                    continue;
                }
                if (healedPtr != rawPtr) {
                    healedPointers++;
                }

                if (isZeroBodyPointer(healedPtr)) {
                    if (isTemporalCleanAckPointer(healedPtr)) {
                        cleanAckTiles++;
                        int shiftDx = extractCleanAckShiftDx(healedPtr);
                        int shiftDy = extractCleanAckShiftDy(healedPtr);
                        tilePackedMv8[tileIdx] = (shiftDx != 0 || shiftDy != 0)
                            ? ((((shiftDx + 8) & 0x0F) << 4) | ((shiftDy + 8) & 0x0F))
                            : pktMv8;
                        if (shiftDx != 0 || shiftDy != 0) {
                            applyShiftedCleanAckTile(
                                decoderState, tileIdx, tx, ty, shiftDx, shiftDy, width, height, depthK, rgbaFrames
                            );
                        } else {
                            applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                        }
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
                    int peekMv8 = peekTileMotionVector8(msbPool, 0, healedPtr);
                    tilePackedMv8[tileIdx] = (peekMv8 != 0x88) ? peekMv8 : pktMv8;
                    LcvCubeCodec.scatterCubeTyxToFrames(arena.cubeRgbaTyx, tx, ty, width, height, depthK, rgbaFrames);
                    updateDecoderCache(decoderState, tileIdx, arena.cubeRgbaTyx, nSamples);
                } catch (LcvCorruptTileException ex) {
                    quarantinedTiles++;
                    if (quarantinedMask == null) {
                        quarantinedMask = new boolean[nTiles];
                    }
                    quarantinedMask[tileIdx] = true;
                    if (decoderState == null || tileIdx >= decoderState.hasCachedTile.length || !decoderState.hasCachedTile[tileIdx]) {
                        if (coldQuarantinedMask == null) {
                            coldQuarantinedMask = new boolean[nTiles];
                        }
                        coldQuarantinedMask[tileIdx] = true;
                    }
                    applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
                }
            }
        }

        // Any missing/dropped MV packets quarantine cleanly to their missing tiles and fall back to cache
        for (int tileIdx = 0; tileIdx < nTiles; tileIdx++) {
            if (!decodedMask[tileIdx]) {
                quarantinedTiles++;
                if (quarantinedMask == null) {
                    quarantinedMask = new boolean[nTiles];
                }
                quarantinedMask[tileIdx] = true;
                if (decoderState == null || tileIdx >= decoderState.hasCachedTile.length || !decoderState.hasCachedTile[tileIdx]) {
                    if (coldQuarantinedMask == null) {
                        coldQuarantinedMask = new boolean[nTiles];
                    }
                    coldQuarantinedMask[tileIdx] = true;
                }
                int tx = tileIdx % tilesX;
                int ty = tileIdx / tilesX;
                applyFallbackTile(decoderState, tileIdx, tx, ty, width, height, depthK, rgbaFrames);
            }
        }

        if (quarantinedMask != null) {
            concealQuarantinedTilesWithNeighborFlow(
                rgbaFrames, quarantinedMask, coldQuarantinedMask, tilePackedMv8,
                tilesX, tilesY, width, height, depthK, decoderState
            );
        }
        if (coldQuarantinedMask != null) {
            reconstructColdQuarantinedTilesSpatiotemporal(
                rgbaFrames, coldQuarantinedMask, tilesX, tilesY, width, height, depthK, decoderState, arena
            );
        }
        if (header.isLsbStripped()) {
            smoothStrippedTileBoundaries(
                rgbaFrames, tilesX, tilesY, width, height, depthK, lsbSplitBits
            );
        }
        if (decoderState != null) {
            decoderState.lastDecodedFrames = rgbaFrames;
        }

        return new SuperblockDecodeResult(
            header, rgbaFrames, healedPointers, quarantinedTiles, cleanAckTiles, uniformTiles
        );
    }

    /**
     * Conceals quarantined or dropped tiles when {@code decoderState.lastDecodedFrames} is populated
     * by computing consensus/median motion vectors {@code (shiftDx, shiftDy)} across healthy
     * 4-connected/8-connected neighbor tiles and healthy perimeter optical flow, then advecting
     * the fallback {@code 16x16xK} cube via {@link #applyShiftedCleanAckTile}.
     */
    private static void concealQuarantinedTilesWithNeighborFlow(
        int[] rgbaFrames, boolean[] quarantinedMask, boolean[] coldQuarantinedMask, int[] tilePackedMv8,
        int tilesX, int tilesY, int width, int height, int depthK,
        StreamDecoderState decoderState
    ) {
        if (decoderState == null || decoderState.lastDecodedFrames == null
            || decoderState.lastDecodedFrames.length < width * height * depthK) {
            return;
        }
        int[] srcFrames = decoderState.lastDecodedFrames;
        int nTiles = tilesX * tilesY;
        int[] dxList = new int[8];
        int[] dyList = new int[8];
        int[] nzDxList = new int[8];
        int[] nzDyList = new int[8];

        for (int tileIdx = 0; tileIdx < nTiles; tileIdx++) {
            if (!quarantinedMask[tileIdx]) {
                continue;
            }
            if (coldQuarantinedMask != null && coldQuarantinedMask[tileIdx]) {
                continue;
            }
            int tx = tileIdx % tilesX;
            int ty = tileIdx / tilesX;

            int nCount = 0;
            int nzCount = 0;
            for (int oy = -1; oy <= 1; oy++) {
                int ny = ty + oy;
                if (ny < 0 || ny >= tilesY) {
                    continue;
                }
                for (int ox = -1; ox <= 1; ox++) {
                    if (ox == 0 && oy == 0) {
                        continue;
                    }
                    int nx = tx + ox;
                    if (nx < 0 || nx >= tilesX) {
                        continue;
                    }
                    int nIdx = ny * tilesX + nx;
                    if (quarantinedMask[nIdx]) {
                        continue;
                    }
                    int mv8 = tilePackedMv8[nIdx] & 0xFF;
                    int ndx = LcvPrimitives.unpackVelocityX(mv8);
                    int ndy = LcvPrimitives.unpackVelocityY(mv8);
                    dxList[nCount] = ndx;
                    dyList[nCount] = ndy;
                    nCount++;
                    if (ndx != 0 || ndy != 0) {
                        nzDxList[nzCount] = ndx;
                        nzDyList[nzCount] = ndy;
                        nzCount++;
                    }
                }
            }

            int consDx = 0;
            int consDy = 0;
            if (nzCount > 0) {
                Arrays.sort(nzDxList, 0, nzCount);
                Arrays.sort(nzDyList, 0, nzCount);
                consDx = nzDxList[nzCount >>> 1];
                consDy = nzDyList[nzCount >>> 1];
            } else if (nCount > 0) {
                Arrays.sort(dxList, 0, nCount);
                Arrays.sort(dyList, 0, nCount);
                consDx = dxList[nCount >>> 1];
                consDy = dyList[nCount >>> 1];
            }

            long zeroSad = computeQuarantinedTilePerimeterFlowSad(
                rgbaFrames, srcFrames, tx, ty, 0, 0, quarantinedMask, tilesX, tilesY, width, height, depthK
            );
            int bestDx = consDx;
            int bestDy = consDy;
            if (zeroSad < Long.MAX_VALUE) {
                long bestScore = zeroSad;
                bestDx = 0;
                bestDy = 0;
                long maxAllowedPanSad = Math.min(zeroSad >>> 1, 10L << 6);
                if (consDx != 0 || consDy != 0) {
                    long consSad = computeQuarantinedTilePerimeterFlowSad(
                        rgbaFrames, srcFrames, tx, ty, consDx, consDy,
                        quarantinedMask, tilesX, tilesY, width, height, depthK
                    );
                    if (consSad <= maxAllowedPanSad) {
                        bestScore = (consSad * 7L) >>> 3;
                        bestDx = consDx;
                        bestDy = consDy;
                    }
                    if (depthK > 1) {
                        int kDx = Math.max(-8, Math.min(7, consDx * depthK));
                        int kDy = Math.max(-8, Math.min(7, consDy * depthK));
                        if (kDx != consDx || kDy != consDy) {
                            long kSad = computeQuarantinedTilePerimeterFlowSad(
                                rgbaFrames, srcFrames, tx, ty, kDx, kDy,
                                quarantinedMask, tilesX, tilesY, width, height, depthK
                            );
                            if (kSad <= maxAllowedPanSad) {
                                long kWeighted = (kSad * 7L) >>> 3;
                                if (kWeighted < bestScore) {
                                    bestScore = kWeighted;
                                    bestDx = kDx;
                                    bestDy = kDy;
                                }
                            }
                        }
                    }
                }
                long maxSearchSad = (consDx != 0 || consDy != 0)
                    ? maxAllowedPanSad
                    : Math.min(zeroSad >>> 2, 6L << 6);
                for (int cy = -8; cy <= 7; cy++) {
                    for (int cx = -8; cx <= 7; cx++) {
                        if (cx == 0 && cy == 0) {
                            continue;
                        }
                        long sad = computeQuarantinedTilePerimeterFlowSad(
                            rgbaFrames, srcFrames, tx, ty, cx, cy,
                            quarantinedMask, tilesX, tilesY, width, height, depthK
                        );
                        if (sad < bestScore && sad <= maxSearchSad) {
                            bestScore = sad;
                            bestDx = cx;
                            bestDy = cy;
                        }
                    }
                }
            }

            if (bestDx != 0 || bestDy != 0) {
                applyShiftedCleanAckTile(
                    decoderState, tileIdx, tx, ty, bestDx, bestDy, width, height, depthK, rgbaFrames
                );
            }
        }
    }

    /**
     * Evaluates optical-flow consistency along the healthy 2-pixel perimeter ring immediately
     * outside quarantined tile {@code (tx, ty)} between current {@code rgbaFrames} and advected
     * {@code prevFrames(px - dx, py - dy)}.
     */
    private static long computeQuarantinedTilePerimeterFlowSad(
        int[] rgbaFrames, int[] prevFrames,
        int tx, int ty, int dx, int dy,
        boolean[] quarantinedMask, int tilesX, int tilesY,
        int width, int height, int depthK
    ) {
        int baseX = tx * LcvPrimitives.TILE_DIM;
        int baseY = ty * LcvPrimitives.TILE_DIM;
        int endX = Math.min(width, baseX + LcvPrimitives.TILE_DIM);
        int endY = Math.min(height, baseY + LcvPrimitives.TILE_DIM);
        int frameArea = width * height;
        long sad = 0L;
        int samples = 0;
        int tStep = Math.max(1, depthK - 1);

        for (int t = 0; t < depthK; t += tStep) {
            int fBase = t * frameArea;
            // North healthy neighbor strip (baseY - 2 .. baseY - 1)
            if (ty > 0 && !quarantinedMask[(ty - 1) * tilesX + tx]) {
                for (int py = Math.max(0, baseY - 2); py < baseY; py++) {
                    int sy = py - dy;
                    if (sy < 0 || sy >= height) {
                        continue;
                    }
                    int curRow = fBase + py * width;
                    int prvRow = fBase + sy * width;
                    for (int px = baseX; px < endX; px++) {
                        int sx = px - dx;
                        if (sx >= 0 && sx < width) {
                            sad += rgbaChannelL1(rgbaFrames[curRow + px], prevFrames[prvRow + sx]);
                            samples++;
                        }
                    }
                }
            }
            // South healthy neighbor strip (endY .. endY + 1)
            if (ty + 1 < tilesY && !quarantinedMask[(ty + 1) * tilesX + tx]) {
                for (int py = endY; py < Math.min(height, endY + 2); py++) {
                    int sy = py - dy;
                    if (sy < 0 || sy >= height) {
                        continue;
                    }
                    int curRow = fBase + py * width;
                    int prvRow = fBase + sy * width;
                    for (int px = baseX; px < endX; px++) {
                        int sx = px - dx;
                        if (sx >= 0 && sx < width) {
                            sad += rgbaChannelL1(rgbaFrames[curRow + px], prevFrames[prvRow + sx]);
                            samples++;
                        }
                    }
                }
            }
            // West healthy neighbor strip (baseX - 2 .. baseX - 1)
            if (tx > 0 && !quarantinedMask[ty * tilesX + (tx - 1)]) {
                for (int py = baseY; py < endY; py++) {
                    int sy = py - dy;
                    if (sy < 0 || sy >= height) {
                        continue;
                    }
                    int curRow = fBase + py * width;
                    int prvRow = fBase + sy * width;
                    for (int px = Math.max(0, baseX - 2); px < baseX; px++) {
                        int sx = px - dx;
                        if (sx >= 0 && sx < width) {
                            sad += rgbaChannelL1(rgbaFrames[curRow + px], prevFrames[prvRow + sx]);
                            samples++;
                        }
                    }
                }
            }
            // East healthy neighbor strip (endX .. endX + 1)
            if (tx + 1 < tilesX && !quarantinedMask[ty * tilesX + (tx + 1)]) {
                for (int py = baseY; py < endY; py++) {
                    int sy = py - dy;
                    if (sy < 0 || sy >= height) {
                        continue;
                    }
                    int curRow = fBase + py * width;
                    int prvRow = fBase + sy * width;
                    for (int px = endX; px < Math.min(width, endX + 2); px++) {
                        int sx = px - dx;
                        if (sx >= 0 && sx < width) {
                            sad += rgbaChannelL1(rgbaFrames[curRow + px], prevFrames[prvRow + sx]);
                            samples++;
                        }
                    }
                }
            }
        }
        return samples >= 8 ? ((sad << 6) / samples) : Long.MAX_VALUE;
    }

    private static int rgbaChannelL1(int a, int b) {
        int dr = Math.abs(((a >>> 24) & 0xFF) - ((b >>> 24) & 0xFF));
        int dg = Math.abs(((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF));
        int db = Math.abs(((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF));
        return dr + dg + db;
    }

    /**
     * Lightweight cross-tile boundary smoothing pass along {@code 16x16} tile seams when
     * {@code header.isLsbStripped()} is active, hard-clamping every restored channel value to its
     * exact MSB quantization interval {@code [v_min_box, v_max_box]} and maximum excursion
     * {@code ±((1 << s) >> 1)} while preserving sharp edges ({@code |grad| > (1 << (s + 1))}).
     */
    static void smoothStrippedTileBoundaries(
        int[] rgbaFrames, int tilesX, int tilesY, int width, int height, int depthK, int lsbSplitBits
    ) {
        if (lsbSplitBits <= 0 || lsbSplitBits > 4 || (tilesX <= 1 && tilesY <= 1)) {
            return;
        }
        int lsbMask = (1 << lsbSplitBits) - 1;
        int highMask = ~lsbMask & 0xFF;
        int maxExcursion = (1 << lsbSplitBits) >> 1;
        int maxGrad = 1 << (lsbSplitBits + 1);
        int frameArea = width * height;

        for (int t = 0; t < depthK; t++) {
            int fBase = t * frameArea;

            // 1. Vertical 16x16 tile seams (x = tx * 16)
            for (int tx = 1; tx < tilesX; tx++) {
                int seamX = tx * LcvPrimitives.TILE_DIM;
                if (seamX < 2 || seamX + 1 >= width) {
                    continue;
                }
                for (int y = 0; y < height; y++) {
                    int rowOff = fBase + y * width;
                    int idxM2 = rowOff + seamX - 2;
                    int idxM1 = rowOff + seamX - 1;
                    int idxP0 = rowOff + seamX;
                    int idxP1 = rowOff + seamX + 1;
                    int cM2 = rgbaFrames[idxM2];
                    int cM1 = rgbaFrames[idxM1];
                    int cP0 = rgbaFrames[idxP0];
                    int cP1 = rgbaFrames[idxP1];
                    int aM1 = cM1 & 0xFF;
                    int aP0 = cP0 & 0xFF;
                    if (aM1 == 0 || aP0 == 0 || aM1 != aP0) {
                        continue;
                    }
                    int outM1 = aM1;
                    int outP0 = aP0;
                    for (int shift = 24; shift >= 8; shift -= 8) {
                        int vM2 = (cM2 >>> shift) & 0xFF;
                        int vM1 = (cM1 >>> shift) & 0xFF;
                        int vP0 = (cP0 >>> shift) & 0xFF;
                        int vP1 = (cP1 >>> shift) & 0xFF;
                        int seamDiff = Math.abs(vP0 - vM1);
                        int nM1 = vM1;
                        int nP0 = vP0;
                        if (seamDiff > 0 && seamDiff <= maxGrad
                            && Math.abs(vM1 - vM2) <= maxGrad
                            && Math.abs(vP1 - vP0) <= maxGrad) {
                            int interpM1 = (vM2 + (vM1 << 1) + vP0 + 2) >> 2;
                            int interpP0 = (vM1 + (vP0 << 1) + vP1 + 2) >> 2;
                            int minM1 = Math.max(vM1 & highMask, vM1 - maxExcursion);
                            int maxM1 = Math.min((vM1 & highMask) | lsbMask, vM1 + maxExcursion);
                            int minP0 = Math.max(vP0 & highMask, vP0 - maxExcursion);
                            int maxP0 = Math.min((vP0 & highMask) | lsbMask, vP0 + maxExcursion);
                            nM1 = Math.max(minM1, Math.min(maxM1, interpM1));
                            nP0 = Math.max(minP0, Math.min(maxP0, interpP0));
                        }
                        outM1 |= (nM1 << shift);
                        outP0 |= (nP0 << shift);
                    }
                    rgbaFrames[idxM1] = outM1;
                    rgbaFrames[idxP0] = outP0;
                }
            }

            // 2. Horizontal 16x16 tile seams (y = ty * 16)
            for (int ty = 1; ty < tilesY; ty++) {
                int seamY = ty * LcvPrimitives.TILE_DIM;
                if (seamY < 2 || seamY + 1 >= height) {
                    continue;
                }
                int rowM2 = fBase + (seamY - 2) * width;
                int rowM1 = fBase + (seamY - 1) * width;
                int rowP0 = fBase + seamY * width;
                int rowP1 = fBase + (seamY + 1) * width;
                for (int x = 0; x < width; x++) {
                    int idxM2 = rowM2 + x;
                    int idxM1 = rowM1 + x;
                    int idxP0 = rowP0 + x;
                    int idxP1 = rowP1 + x;
                    int cM2 = rgbaFrames[idxM2];
                    int cM1 = rgbaFrames[idxM1];
                    int cP0 = rgbaFrames[idxP0];
                    int cP1 = rgbaFrames[idxP1];
                    int aM1 = cM1 & 0xFF;
                    int aP0 = cP0 & 0xFF;
                    if (aM1 == 0 || aP0 == 0 || aM1 != aP0) {
                        continue;
                    }
                    int outM1 = aM1;
                    int outP0 = aP0;
                    for (int shift = 24; shift >= 8; shift -= 8) {
                        int vM2 = (cM2 >>> shift) & 0xFF;
                        int vM1 = (cM1 >>> shift) & 0xFF;
                        int vP0 = (cP0 >>> shift) & 0xFF;
                        int vP1 = (cP1 >>> shift) & 0xFF;
                        int seamDiff = Math.abs(vP0 - vM1);
                        int nM1 = vM1;
                        int nP0 = vP0;
                        if (seamDiff > 0 && seamDiff <= maxGrad
                            && Math.abs(vM1 - vM2) <= maxGrad
                            && Math.abs(vP1 - vP0) <= maxGrad) {
                            int interpM1 = (vM2 + (vM1 << 1) + vP0 + 2) >> 2;
                            int interpP0 = (vM1 + (vP0 << 1) + vP1 + 2) >> 2;
                            int minM1 = Math.max(vM1 & highMask, vM1 - maxExcursion);
                            int maxM1 = Math.min((vM1 & highMask) | lsbMask, vM1 + maxExcursion);
                            int minP0 = Math.max(vP0 & highMask, vP0 - maxExcursion);
                            int maxP0 = Math.min((vP0 & highMask) | lsbMask, vP0 + maxExcursion);
                            nM1 = Math.max(minM1, Math.min(maxM1, interpM1));
                            nP0 = Math.max(minP0, Math.min(maxP0, interpP0));
                        }
                        outM1 |= (nM1 << shift);
                        outP0 |= (nP0 << shift);
                    }
                    rgbaFrames[idxM1] = outM1;
                    rgbaFrames[idxP0] = outP0;
                }
            }
        }
    }

    private static int peekTileMotionVector8(
        byte[] superblockBytes, int msbPoolBase, long healedPtr
    ) {
        if (healedPtr == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
            return 0x88; // Stationary (0, 0)
        }
        if (isZeroBodyPointer(healedPtr)) {
            if (isTemporalCleanAckPointer(healedPtr)) {
                int shiftDx = extractCleanAckShiftDx(healedPtr);
                int shiftDy = extractCleanAckShiftDy(healedPtr);
                return (((shiftDx + 8) & 0x0F) << 4) | ((shiftDy + 8) & 0x0F);
            }
            return 0x88;
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
            int rawMv8 = superblockBytes[deltaFlagsPos + 1] & 0xFF;
            if ((deltaFlags8 & LcvCubeCodec.DELTA_FLAG_SPATIAL_MED_2D) != 0
                && (deltaFlags8 & LcvCubeCodec.DELTA_FLAG_HALF_PEL_MV) != 0) {
                int dxQ1 = LcvPrimitives.unpackVelocityX(rawMv8);
                int dyQ1 = LcvPrimitives.unpackVelocityY(rawMv8);
                int dx = dxQ1 >> 1;
                int dy = dyQ1 >> 1;
                return (((dx + 8) & 0x0F) << 4) | ((dy + 8) & 0x0F);
            }
            return rawMv8;
        }
        return 0x88;
    }

    static boolean isZstdAvailable() {
        return ZSTD_COMPRESS != null && ZSTD_DECOMPRESS != null;
    }

    static boolean isZstdDictAvailable() {
        return ZSTD_COMPRESS_DICT != null && ZSTD_DECOMPRESS_DICT != null;
    }

    static byte[] compressZstdFrameWithSizeHeader(byte[] raw) {
        return compressZstdFrameWithSizeHeader(raw, 1);
    }

    static byte[] sliceDictTail(byte[] pool) {
        if (pool == null || pool.length == 0) {
            return null;
        }
        int maxDict = 65536;
        if (pool.length <= maxDict) {
            return Arrays.copyOf(pool, pool.length);
        }
        return Arrays.copyOfRange(pool, pool.length - maxDict, pool.length);
    }

    static byte[] compressZstdFrameWithSizeHeader(byte[] raw, int level) {
        try {
            byte[] compressed = (byte[]) ZSTD_COMPRESS.invoke(null, raw, level);
            byte[] framed = new byte[4 + compressed.length];
            ByteBuffer.wrap(framed).order(ByteOrder.BIG_ENDIAN).putInt(0, raw.length);
            System.arraycopy(compressed, 0, framed, 4, compressed.length);
            return framed;
        } catch (Exception e) {
            throw new IllegalStateException("Zstd compression failed", e);
        }
    }

    static byte[] compressZstdFrameWithDict(byte[] raw, byte[] dict, int level) {
        if (dict == null || dict.length == 0 || !isZstdDictAvailable()) {
            return compressZstdFrameWithSizeHeader(raw, level);
        }
        try {
            int maxBound = raw.length + (raw.length >>> 7) + 512;
            byte[] dstBuf = new byte[maxBound];
            long compLen = (long) ZSTD_COMPRESS_DICT.invoke(
                null, dstBuf, 0, raw, 0, raw.length, dict, level
            );
            if (compLen <= 0 || compLen > maxBound) {
                return compressZstdFrameWithSizeHeader(raw, level);
            }
            int cLen = (int) compLen;
            byte[] framed = new byte[4 + cLen];
            ByteBuffer.wrap(framed).order(ByteOrder.BIG_ENDIAN).putInt(0, raw.length);
            System.arraycopy(dstBuf, 0, framed, 4, cLen);
            return framed;
        } catch (Exception e) {
            return compressZstdFrameWithSizeHeader(raw, level);
        }
    }

    static byte[] decompressZstdFrameWithSizeHeader(byte[] src, int off, int len) throws LcvCorruptTileException {
        return decompressZstdFrameWithDict(src, off, len, null);
    }

    static byte[] decompressZstdFrameWithDict(byte[] src, int off, int len, byte[] dict) throws LcvCorruptTileException {
        if (ZSTD_DECOMPRESS == null || len < 4 || off + len > src.length) {
            throw new LcvCorruptTileException("CBIT: invalid Zstd pool frame");
        }
        try {
            int uncompressedLen = ByteBuffer.wrap(src, off, 4).order(ByteOrder.BIG_ENDIAN).getInt(off);
            if (uncompressedLen < 0 || uncompressedLen > MAX_ZSTD_UNCOMPRESSED_POOL_BYTES) {
                throw new LcvCorruptTileException("CBIT: invalid Zstd uncompressed length " + uncompressedLen);
            }
            if (dict != null && dict.length > 0 && isZstdDictAvailable()) {
                byte[] dst = new byte[uncompressedLen];
                long decLen = (long) ZSTD_DECOMPRESS_DICT.invoke(
                    null, dst, 0, src, off + 4, len - 4, dict
                );
                if (decLen == uncompressedLen) {
                    return dst;
                }
            }
            byte[] compSlice = Arrays.copyOfRange(src, off + 4, off + len);
            return (byte[]) ZSTD_DECOMPRESS.invoke(null, compSlice, uncompressedLen);
        } catch (LcvCorruptTileException e) {
            throw e;
        } catch (Exception e) {
            throw new LcvCorruptTileException("CBIT: Zstd pool decompression failed", e);
        }
    }

    /**
     * Descriptor for an immutable content block in the spatiotemporal database pool ({@code leaf-video-ADR-003}).
     */
    public record BlockPoolDescriptor(
        int blockId,
        long structuralSignature64,
        int diskByteOffset,
        int byteLength,
        byte secDedSyndrome8
    ) {}

    /**
     * Indirection entry mapping a logical screen coordinate {@code (layerId, tileX, tileY)} (at quadtree
     * scale {@code quadLevel}: {@code 0 = 16x16}, {@code 1 = 32x32}, {@code 2 = 64x64}) to an immutable
     * {@code sourceBlockId} with sub-tile motion shift {@code (shiftDx, shiftDy)} and optional residual block ID.
     */
    public record DecodeMapEntry(
        int layerId,
        int tileX,
        int tileY,
        int quadLevel,
        int sourceBlockId,
        int shiftDx,
        int shiftDy,
        int residualBlockId
    ) {
        public DecodeMapEntry(
            int layerId, int tileX, int tileY, int sourceBlockId, int shiftDx, int shiftDy, int residualBlockId
        ) {
            this(layerId, tileX, tileY, 0, sourceBlockId, shiftDx, shiftDy, residualBlockId);
        }

        public int spanTiles() {
            return 1 << Math.max(0, Math.min(2, quadLevel));
        }
    }

    /**
     * In-memory decode mapping packet ({@code LCV_MAP1}, {@code leaf-video-ADR-003} {@code REQ-LCV-DB-001})
     * decoupling on-disk block storage order from logical {@code (layer, tileX, tileY, t)} screen coordinates.
     */
    public record DecodeMapPacket(
        long frameIndex64,
        int width16,
        int height16,
        int depthK8,
        int layerCount8,
        List<BlockPoolDescriptor> blockDirectory,
        List<DecodeMapEntry> mapEntries,
        int healedSingleBitEntries
    ) {}

    /**
     * Serializes a {@link DecodeMapPacket} into a compact {@code LCV_MAP1} packet protected by per-entry
     * Extended Hamming {@code (72, 64)} {@code SEC-DED} syndromes and a trailing packet {@code CRC32}.
     */
    public static byte[] serializeDecodeMapPacket(DecodeMapPacket packet) {
        List<BlockPoolDescriptor> blocks = packet.blockDirectory() != null ? packet.blockDirectory() : List.of();
        List<DecodeMapEntry> entries = packet.mapEntries() != null ? packet.mapEntries() : List.of();
        // Header: 30 bytes (magic64[8] + frameIndex64[8] + width16[2] + height16[2] + depthK8[1] + layerCount8[1] + blockCount32[4] + entryCount32[4])
        // Per BlockPoolDescriptor: 21 bytes (blockId32[4] + sig64[8] + packedOffLen64[8] + secDed8[1])
        // Per DecodeMapEntry: 13 bytes (packedEntry64[8] + residualBlockId32[4] + secDed8[1])
        // Trailer: 4 bytes CRC32
        int totalBytes = 30 + blocks.size() * 21 + entries.size() * 13 + 4;
        byte[] out = new byte[totalBytes];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        buf.putLong(0, MAGIC_LCV_MAP1);
        buf.putLong(8, packet.frameIndex64());
        buf.putShort(16, (short) packet.width16());
        buf.putShort(18, (short) packet.height16());
        out[20] = (byte) packet.depthK8();
        out[21] = (byte) Math.max(1, packet.layerCount8());
        buf.putInt(22, blocks.size());
        buf.putInt(26, entries.size());

        int pos = 30;
        for (BlockPoolDescriptor b : blocks) {
            long packedOffLen64 = ((long) b.diskByteOffset() << 32) | (b.byteLength() & 0xFFFFFFFFL);
            byte syn8 = LcvPrimitives.computeHammingSecDed8(packedOffLen64);
            buf.putInt(pos, b.blockId());
            buf.putLong(pos + 4, b.structuralSignature64());
            buf.putLong(pos + 12, packedOffLen64);
            out[pos + 20] = syn8;
            pos += 21;
        }
        for (DecodeMapEntry e : entries) {
            int packedShift8 = (((e.shiftDx() + 8) & 0x0F) << 4) | ((e.shiftDy() + 8) & 0x0F);
            int headerByte = ((e.layerId() & 0x3F) << 2) | (e.quadLevel() & 0x03);
            long packedEntry64 = ((long) (headerByte & 0xFF) << 56)
                | ((long) (e.tileX() & 0xFFF) << 44)
                | ((long) (e.tileY() & 0xFFF) << 32)
                | ((long) (packedShift8 & 0xFF) << 24)
                | (e.sourceBlockId() & 0x00FFFFFFL);
            byte syn8 = LcvPrimitives.computeHammingSecDed8(packedEntry64);
            buf.putLong(pos, packedEntry64);
            buf.putInt(pos + 8, e.residualBlockId());
            out[pos + 12] = syn8;
            pos += 13;
        }
        CRC32 crc = new CRC32();
        crc.update(out, 0, pos);
        buf.putInt(pos, (int) crc.getValue());
        return out;
    }

    /**
     * Deserializes a {@link DecodeMapPacket} from {@code bytes[offset..]}, healing single-bit flips in
     * {@link BlockPoolDescriptor} and {@link DecodeMapEntry} records via {@code SEC-DED (72, 64)}.
     */
    public static DecodeMapPacket deserializeDecodeMapPacket(byte[] bytes, int offset) throws LcvCorruptTileException {
        if (bytes == null || offset < 0 || offset + 34 > bytes.length) {
            throw new LcvCorruptTileException("CBIT: truncated DecodeMapPacket header");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        long magic = buf.getLong(offset);
        if (magic != MAGIC_LCV_MAP1) {
            throw new LcvCorruptTileException("CBIT: invalid DecodeMapPacket magic");
        }
        long frameIndex64 = buf.getLong(offset + 8);
        int width16 = buf.getShort(offset + 16) & 0xFFFF;
        int height16 = buf.getShort(offset + 18) & 0xFFFF;
        int depthK8 = bytes[offset + 20] & 0xFF;
        int layerCount8 = bytes[offset + 21] & 0xFF;
        int blockCount = buf.getInt(offset + 22);
        int entryCount = buf.getInt(offset + 26);
        if (blockCount < 0 || entryCount < 0 || blockCount > 1_000_000 || entryCount > 1_000_000) {
            throw new LcvCorruptTileException("CBIT: invalid DecodeMapPacket counts");
        }
        int expectedLen = 30 + blockCount * 21 + entryCount * 13 + 4;
        if (offset + expectedLen > bytes.length) {
            throw new LcvCorruptTileException("CBIT: truncated DecodeMapPacket body");
        }

        int healedCount = 0;
        List<BlockPoolDescriptor> blocks = new ArrayList<>(blockCount);
        int pos = offset + 30;
        for (int i = 0; i < blockCount; i++) {
            int blockId = buf.getInt(pos);
            long sig64 = buf.getLong(pos + 4);
            long rawOffLen64 = buf.getLong(pos + 12);
            byte syn8 = bytes[pos + 20];
            long healedOffLen64 = LcvPrimitives.healSingleBitFlip64(rawOffLen64, syn8);
            if (healedOffLen64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                throw new LcvCorruptTileException("CBIT: uncorrectable DED in BlockPoolDescriptor " + i);
            }
            if (healedOffLen64 != rawOffLen64) {
                healedCount++;
            }
            int diskOff = (int) (healedOffLen64 >>> 32);
            int byteLen = (int) healedOffLen64;
            blocks.add(new BlockPoolDescriptor(blockId, sig64, diskOff, byteLen, syn8));
            pos += 21;
        }

        List<DecodeMapEntry> entries = new ArrayList<>(entryCount);
        for (int i = 0; i < entryCount; i++) {
            long rawEntry64 = buf.getLong(pos);
            int residualId = buf.getInt(pos + 8);
            byte syn8 = bytes[pos + 12];
            long healedEntry64 = LcvPrimitives.healSingleBitFlip64(rawEntry64, syn8);
            if (healedEntry64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                throw new LcvCorruptTileException("CBIT: uncorrectable DED in DecodeMapEntry " + i);
            }
            if (healedEntry64 != rawEntry64) {
                healedCount++;
            }
            int headerByte = (int) ((healedEntry64 >>> 56) & 0xFFL);
            int layerId = (headerByte >>> 2) & 0x3F;
            int quadLevel = headerByte & 0x03;
            int tileX = (int) ((healedEntry64 >>> 44) & 0xFFFL);
            int tileY = (int) ((healedEntry64 >>> 32) & 0xFFFL);
            int packedShift8 = (int) ((healedEntry64 >>> 24) & 0xFFL);
            int srcBlockId = (int) (healedEntry64 & 0x00FFFFFFL);
            int sDx = LcvPrimitives.unpackVelocityX(packedShift8);
            int sDy = LcvPrimitives.unpackVelocityY(packedShift8);
            entries.add(new DecodeMapEntry(layerId, tileX, tileY, quadLevel, srcBlockId, sDx, sDy, residualId));
            pos += 13;
        }

        return new DecodeMapPacket(
            frameIndex64, width16, height16, depthK8, layerCount8, blocks, entries, healedCount
        );
    }

    /**
     * Deserializes a {@link DecodeMapPacket} with automatic failover to a duplicated recovery checkpoint
     * packet if the primary packet suffered a multi-bit sector loss ({@code leaf-video-ADR-003}).
     */
    public static DecodeMapPacket deserializeDecodeMapWithRecovery(
        byte[] primaryBytes, int primaryOffset, byte[] duplicateCheckpointBytes, int duplicateOffset
    ) throws LcvCorruptTileException {
        try {
            return deserializeDecodeMapPacket(primaryBytes, primaryOffset);
        } catch (LcvCorruptTileException primaryEx) {
            if (duplicateCheckpointBytes != null) {
                return deserializeDecodeMapPacket(duplicateCheckpointBytes, duplicateOffset);
            }
            throw primaryEx;
        }
    }

    /**
     * Zero-decode semantic index query ({@code leaf-video-ADR-003} Capability 2): returns all presentation
     * {@code frameIndex64} timestamps across {@code timeline} where any block in the {@link DecodeMapPacket}
     * matches {@code targetSignature64} within {@code maxHammingDistanceBits} bits.
     */
    public static List<Long> queryDecodeMapBySignature(
        List<DecodeMapPacket> timeline, long targetSignature64, int maxHammingDistanceBits
    ) {
        List<Long> matches = new ArrayList<>();
        if (timeline == null) {
            return matches;
        }
        for (DecodeMapPacket pkt : timeline) {
            for (BlockPoolDescriptor b : pkt.blockDirectory()) {
                if (Long.bitCount(b.structuralSignature64() ^ targetSignature64) <= maxHammingDistanceBits) {
                    matches.add(pkt.frameIndex64());
                    break;
                }
            }
        }
        return matches;
    }

    /**
     * Wait-free growing-file tail reader ({@code leaf-video-ADR-003} {@code REQ-LCV-DB-004}): scans an
     * actively growing {@code .lcv} stream buffer (ignoring any incomplete trailing superblock still being
     * flushed at {@code EOF}) and returns the byte offsets of all committed superblocks with
     * {@code frameIndex64 > afterFrameIndex64}.
     */
    public static List<Integer> readGrowingStreamTail(byte[] growingStreamBytes, long afterFrameIndex64) {
        List<Integer> allValid = indexValidSuperblocks(growingStreamBytes);
        List<Integer> tailOffsets = new ArrayList<>();
        ByteBuffer buf = ByteBuffer.wrap(growingStreamBytes).order(ByteOrder.BIG_ENDIAN);
        for (int off : allValid) {
            if (off >= 0 && off + SUPERBLOCK_HEADER_BYTES <= growingStreamBytes.length) {
                long frameIndex64 = buf.getLong(off + 0x24);
                if (frameIndex64 > afterFrameIndex64) {
                    tailOffsets.add(off);
                }
            }
        }
        return tailOffsets;
    }

    /**
     * Non-destructive {@code .lcv.branch} sidecar timeline packet ({@code leaf-video-ADR-003} {@code REQ-LCV-DB-004})
     * enabling live NLE cuts, re-ordered timelines, tracked {@code (viewTileX, viewTileY)} crops, and painted
     * override superblocks while the primary {@code .lcv} capture file is still growing.
     */
    public record SidecarBranchPacket(
        String branchName,
        long[] timelineFrameIndices,
        int[] viewTileX,
        int[] viewTileY,
        int cropTilesX,
        int cropTilesY,
        List<byte[]> overrideSuperblocks
    ) {}

    public static byte[] serializeSidecarBranch(SidecarBranchPacket branch) {
        byte[] nameBytes = (branch.branchName() != null ? branch.branchName() : "default")
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long[] fIndices = branch.timelineFrameIndices() != null ? branch.timelineFrameIndices() : new long[0];
        int[] vx = branch.viewTileX() != null ? branch.viewTileX() : new int[fIndices.length];
        int[] vy = branch.viewTileY() != null ? branch.viewTileY() : new int[fIndices.length];
        List<byte[]> overrides = branch.overrideSuperblocks() != null ? branch.overrideSuperblocks() : List.of();

        int overrideBytes = 0;
        for (byte[] sb : overrides) {
            overrideBytes += 4 + sb.length;
        }
        int totalLen = 8 + 2 + nameBytes.length + 4 + 4 + 4 + fIndices.length * 12 + 4 + overrideBytes;
        byte[] out = new byte[totalLen];
        ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
        int pos = 0;
        buf.putLong(pos, MAGIC_LCV_BRCH);
        pos += 8;
        buf.putShort(pos, (short) nameBytes.length);
        pos += 2;
        System.arraycopy(nameBytes, 0, out, pos, nameBytes.length);
        pos += nameBytes.length;
        buf.putInt(pos, branch.cropTilesX());
        buf.putInt(pos + 4, branch.cropTilesY());
        buf.putInt(pos + 8, fIndices.length);
        pos += 12;
        for (int i = 0; i < fIndices.length; i++) {
            buf.putLong(pos, fIndices[i]);
            buf.putShort(pos + 8, (short) (i < vx.length ? vx[i] : 0));
            buf.putShort(pos + 10, (short) (i < vy.length ? vy[i] : 0));
            pos += 12;
        }
        buf.putInt(pos, overrides.size());
        pos += 4;
        for (byte[] sb : overrides) {
            buf.putInt(pos, sb.length);
            pos += 4;
            System.arraycopy(sb, 0, out, pos, sb.length);
            pos += sb.length;
        }
        return out;
    }

    public static SidecarBranchPacket deserializeSidecarBranch(byte[] bytes) throws LcvCorruptTileException {
        if (bytes == null || bytes.length < 26) {
            throw new LcvCorruptTileException("CBIT: truncated SidecarBranchPacket");
        }
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        if (buf.getLong(0) != MAGIC_LCV_BRCH) {
            throw new LcvCorruptTileException("CBIT: invalid SidecarBranchPacket magic");
        }
        int pos = 8;
        int nameLen = buf.getShort(pos) & 0xFFFF;
        pos += 2;
        if (pos + nameLen + 16 > bytes.length) {
            throw new LcvCorruptTileException("CBIT: truncated SidecarBranchPacket name");
        }
        String name = new String(bytes, pos, nameLen, java.nio.charset.StandardCharsets.UTF_8);
        pos += nameLen;
        int cropTilesX = buf.getInt(pos);
        int cropTilesY = buf.getInt(pos + 4);
        int count = buf.getInt(pos + 8);
        pos += 12;
        if (count < 0 || pos + count * 12 + 4 > bytes.length) {
            throw new LcvCorruptTileException("CBIT: invalid SidecarBranchPacket timeline count");
        }
        long[] fIndices = new long[count];
        int[] vx = new int[count];
        int[] vy = new int[count];
        for (int i = 0; i < count; i++) {
            fIndices[i] = buf.getLong(pos);
            vx[i] = buf.getShort(pos + 8) & 0xFFFF;
            vy[i] = buf.getShort(pos + 10) & 0xFFFF;
            pos += 12;
        }
        int ovCount = buf.getInt(pos);
        pos += 4;
        List<byte[]> overrides = new ArrayList<>(Math.max(0, ovCount));
        for (int i = 0; i < ovCount; i++) {
            if (pos + 4 > bytes.length) {
                throw new LcvCorruptTileException("CBIT: truncated SidecarBranchPacket override length");
            }
            int len = buf.getInt(pos);
            pos += 4;
            if (len < 0 || pos + len > bytes.length) {
                throw new LcvCorruptTileException("CBIT: truncated SidecarBranchPacket override payload");
            }
            overrides.add(Arrays.copyOfRange(bytes, pos, pos + len));
            pos += len;
        }
        return new SidecarBranchPacket(name, fIndices, vx, vy, cropTilesX, cropTilesY, overrides);
    }

    /**
     * Sub-frame {@code K=1} 16-pixel rolling horizontal strip packet ({@code leaf-video-ADR-004} {@code REQ-LCV-HW-003})
     * enabling {@code < 1 ms} algorithmic scanline latency and zero-freeze RF fault isolation on drones/links.
     */
    public record RollingStripPacket(
        int stripTileY,
        int width16,
        int height16,
        int roiTileX16,
        int roiTileY16,
        int tuningProfile16,
        long[] tilePointers,
        byte[] tileSyndromes,
        byte[] msbPayload,
        byte[] lsbPayload
    ) {
        public boolean isLsbStripped() {
            return lsbPayload == null || lsbPayload.length == 0;
        }
    }

    /**
     * Encodes a single 16-pixel-tall horizontal strip ({@code width * 16} RGBA pixels for tile row {@code stripTileY})
     * at {@code K = 1} with zero future-scanline lookahead ({@code leaf-video-ADR-004} {@code REQ-LCV-HW-003}).
     */
    public static RollingStripPacket encodeRollingStripK1(
        int[] stripRgbaScanlines16, int width, int fullFrameHeight, int stripTileY,
        int roiTileX, int roiTileY, LcvCubeCodec.TuningProfile profile
    ) {
        LcvCubeCodec.TuningProfile activeProfile =
            (profile != null) ? profile : LcvCubeCodec.TuningProfile.HARSH_RF_UEP_ZERO_WAIT;
        int tilesX = (width + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
        int stripHeight = Math.min(LcvPrimitives.TILE_DIM, Math.max(1, fullFrameHeight - stripTileY * LcvPrimitives.TILE_DIM));
        long[] ptrs = new long[tilesX];
        byte[] syns = new byte[tilesX];
        int[] lsbRelOffsets = new int[tilesX];
        ByteArrayOutputStream msbOut = new ByteArrayOutputStream(tilesX * 64);
        ByteArrayOutputStream lsbOut = new ByteArrayOutputStream(tilesX * 32);
        LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
        byte[] tileScratch = arena.tileOutScratch;

        for (int tx = 0; tx < tilesX; tx++) {
            lsbRelOffsets[tx] = lsbOut.size();
            LcvPrimitives.CubeFingerprint fp = LcvPrimitives.extractAndFingerprintCubeTyx(
                stripRgbaScanlines16, tx, 0, width, stripHeight, 1, arena
            );
            if (fp.uniform) {
                long ptr = makeUniformInlinePointer(fp.firstRgba);
                ptrs[tx] = ptr;
                syns[tx] = LcvPrimitives.computeHammingSecDed8(ptr);
                continue;
            }
            LcvCubeCodec.CubeEncodeResult enc = LcvCubeCodec.encodeExtractedCubeTyx(
                arena.cubeRgbaTyx, 1, tx, stripTileY, roiTileX, roiTileY,
                activeProfile.rlePolicy(), activeProfile.lsbSplitBits(), fp.digest64, true,
                LcvCubeCodec.LcvKeySchedule.OPEN_MIT, 0L, arena, tileScratch, 0
            );
            int relMsbOff = msbOut.size();
            int msbLen = enc.msbSliceBytes();
            int lsbLen = enc.lsbSliceBytes();
            msbOut.write(tileScratch, 0, msbLen);
            if (lsbLen > 0) {
                lsbOut.write(tileScratch, msbLen, lsbLen);
            }
            long ptr = makeActiveTilePointer(relMsbOff, msbLen, lsbLen);
            ptrs[tx] = ptr;
            syns[tx] = LcvPrimitives.computeHammingSecDed8(ptr);
        }
        byte[] indexedLsb = buildIndexedLsbPool(tilesX, lsbRelOffsets, lsbOut.toByteArray());
        return new RollingStripPacket(
            stripTileY, width, fullFrameHeight, roiTileX, roiTileY, activeProfile.toPackedU16(),
            ptrs, syns, msbOut.toByteArray(), indexedLsb
        );
    }

    /**
     * Sheds Section 4B ({@code lsbPayload}) from a {@link RollingStripPacket} in {@code O(1)} at the radio queue
     * during RF fade without touching Section 4A ({@code msbPayload}).
     */
    public static RollingStripPacket shedRollingStripLsbInFlight(RollingStripPacket packet) {
        int n = packet.tilePointers().length;
        long[] newPtrs = new long[n];
        byte[] newSyns = new byte[n];
        for (int i = 0; i < n; i++) {
            long healed = LcvPrimitives.healSingleBitFlip64(packet.tilePointers()[i], packet.tileSyndromes()[i]);
            if (healed != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL && !isZeroBodyPointer(healed)) {
                long stripped = makeActiveTilePointer(extractRelByteOffset30(healed), extractMsbSliceLen16(healed), 0);
                newPtrs[i] = stripped;
                newSyns[i] = LcvPrimitives.computeHammingSecDed8(stripped);
            } else {
                newPtrs[i] = packet.tilePointers()[i];
                newSyns[i] = packet.tileSyndromes()[i];
            }
        }
        return new RollingStripPacket(
            packet.stripTileY(), packet.width16(), packet.height16(),
            packet.roiTileX16(), packet.roiTileY16(), packet.tuningProfile16(),
            newPtrs, newSyns, packet.msbPayload(), new byte[0]
        );
    }

    /**
     * Decodes a {@link RollingStripPacket} directly into the corresponding 16-scanline row of {@code dstFrameRgba}
     * (or a standalone {@code width * 16} strip buffer), quarantining any corrupted tile to {@code fallbackFrameRgba}.
     */
    public static int decodeRollingStripK1(
        RollingStripPacket packet, int[] dstFrameRgba, int[] fallbackFrameRgba
    ) {
        int width = packet.width16();
        int fullHeight = packet.height16();
        int stripTileY = packet.stripTileY();
        int tilesX = (width + LcvPrimitives.TILE_DIM - 1) / LcvPrimitives.TILE_DIM;
        boolean standaloneStrip = (dstFrameRgba.length < width * fullHeight);
        int targetHeight = standaloneStrip
            ? Math.min(LcvPrimitives.TILE_DIM, Math.max(1, fullFrameHeightSpan(fullHeight, stripTileY)))
            : fullHeight;
        int targetTileY = standaloneStrip ? 0 : stripTileY;
        LcvCubeCodec.TuningProfile profile = LcvCubeCodec.TuningProfile.fromPackedU16(packet.tuningProfile16());
        int lsbSplitBits = profile.lsbSplitBits();
        boolean lsbStripped = packet.isLsbStripped();
        LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();

        int quarantined = 0;
        int lsbTableBytes = tilesX * 4;
        ByteBuffer lsbBuf = (!lsbStripped && packet.lsbPayload().length >= lsbTableBytes)
            ? ByteBuffer.wrap(packet.lsbPayload()).order(ByteOrder.BIG_ENDIAN)
            : null;
        for (int tx = 0; tx < tilesX; tx++) {
            long healed = LcvPrimitives.healSingleBitFlip64(packet.tilePointers()[tx], packet.tileSyndromes()[tx]);
            if (healed == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                quarantined++;
                copyFallbackStripTile(fallbackFrameRgba, dstFrameRgba, tx, targetTileY, width, targetHeight);
                continue;
            }
            if (isZeroBodyPointer(healed)) {
                int rgba = extractUniformInlineRgba(healed);
                scatterUniformTileToFrames(rgba, tx, targetTileY, width, targetHeight, 1, dstFrameRgba);
                continue;
            }
            int relMsbOff = extractRelByteOffset30(healed);
            int msbLen = extractMsbSliceLen16(healed);
            int lsbLen = (lsbStripped || lsbBuf == null) ? 0 : extractLsbSliceLen16(healed);
            int tileLsbOff = (lsbLen > 0 && lsbBuf != null)
                ? (lsbTableBytes + lsbBuf.getInt(tx * 4))
                : 0;
            try {
                LcvCubeCodec.decodeSplitCubeRgba(
                    packet.msbPayload(), relMsbOff, msbLen,
                    packet.lsbPayload(), tileLsbOff, lsbLen,
                    false, 1, lsbSplitBits,
                    tx, stripTileY, packet.roiTileX16(), packet.roiTileY16(),
                    LcvCubeCodec.LcvKeySchedule.OPEN_MIT, 0L,
                    arena, arena.cubeRgbaTyx
                );
                LcvCubeCodec.scatterCubeTyxToFrames(arena.cubeRgbaTyx, tx, targetTileY, width, targetHeight, 1, dstFrameRgba);
            } catch (LcvCorruptTileException ex) {
                quarantined++;
                copyFallbackStripTile(fallbackFrameRgba, dstFrameRgba, tx, targetTileY, width, targetHeight);
            }
        }
        return quarantined;
    }

    private static int fullFrameHeightSpan(int fullHeight, int stripTileY) {
        return Math.min(LcvPrimitives.TILE_DIM, Math.max(1, fullHeight - stripTileY * LcvPrimitives.TILE_DIM));
    }

    private static void copyFallbackStripTile(
        int[] fallbackRgba, int[] dstRgba, int tx, int ty, int width, int height
    ) {
        if (fallbackRgba == null || fallbackRgba.length < dstRgba.length) {
            return;
        }
        int baseX = tx * LcvPrimitives.TILE_DIM;
        int baseY = ty * LcvPrimitives.TILE_DIM;
        int endX = Math.min(width, baseX + LcvPrimitives.TILE_DIM);
        int endY = Math.min(height, baseY + LcvPrimitives.TILE_DIM);
        for (int py = baseY; py < endY; py++) {
            int row = py * width;
            System.arraycopy(fallbackRgba, row + baseX, dstRgba, row + baseX, endX - baseX);
        }
    }

    /**
     * Result of extracting a single random-access frame {@code targetFrameIndex} via pointer/map
     * dependency flattening ({@code leaf-video-ADR-003} {@code REQ-LCV-DB-001}).
     */
    public record SingleFrameExtractResult(
        long targetFrameIndex,
        int width,
        int height,
        int[] frameRgba,
        int resolvedDirectoryHops,
        int decodedTilePayloads
    ) {}

    /**
     * Extracts a single presentation frame {@code targetFrameIndex} from any position in {@code streamBytes}
     * without decoding intermediate superblocks: temporal dependencies ({@code TEMPORAL_CLEAN_ACK} and
     * motion-shifted {@code (shiftDx, shiftDy)} references) are resolved directly in the {@code SEC-DED}
     * pointer directory to jump straight to each tile's anchor payload ({@code leaf-video-ADR-003}).
     */
    public static SingleFrameExtractResult extractSingleFrame(
        byte[] streamBytes, long targetFrameIndex
    ) throws LcvCorruptTileException {
        return extractSingleFrame(
            streamBytes, indexValidSuperblocks(streamBytes), targetFrameIndex,
            0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE
        );
    }

    /**
     * Extracts a single presentation frame {@code targetFrameIndex} using a pre-indexed superblock offset
     * table ({@link #indexValidSuperblocks(byte[])}) to avoid rescanning the stream on every NLE timeline scrub.
     */
    public static SingleFrameExtractResult extractSingleFrame(
        byte[] streamBytes, List<Integer> sbOffsets, long targetFrameIndex
    ) throws LcvCorruptTileException {
        return extractSingleFrame(
            streamBytes, sbOffsets, targetFrameIndex,
            0, 0, Integer.MAX_VALUE, Integer.MAX_VALUE
        );
    }

    /**
     * Extracts a single presentation frame {@code targetFrameIndex} (or a zoomed NLE viewport / crop tile region
     * {@code [tileX0..tileX0+roiTilesX, tileY0..tileY0+roiTilesY)}) by resolving {@code SEC-DED} pointer-directory
     * hops once per tile and caching {@link SuperblockHeader} + {@code Zstd} pools once per referenced superblock.
     */
    public static SingleFrameExtractResult extractSingleFrame(
        byte[] streamBytes,
        List<Integer> sbOffsets,
        long targetFrameIndex,
        int tileX0,
        int tileY0,
        int roiTilesX,
        int roiTilesY
    ) throws LcvCorruptTileException {
        return extractSingleFrameInto(
            streamBytes, sbOffsets, targetFrameIndex,
            tileX0, tileY0, roiTilesX, roiTilesY, null
        );
    }

    /**
     * Zero-allocation NLE playhead overload extracting {@code targetFrameIndex} (or viewport ROI
     * {@code [tileX0..tileX0+roiTilesX, tileY0..tileY0+roiTilesY)}) directly into {@code reusableOutFrame}
     * when non-null and sized {@code >= width * height}.
     */
    public static SingleFrameExtractResult extractSingleFrameInto(
        byte[] streamBytes,
        List<Integer> sbOffsets,
        long targetFrameIndex,
        int tileX0,
        int tileY0,
        int roiTilesX,
        int roiTilesY,
        int[] reusableOutFrame
    ) throws LcvCorruptTileException {
        if (sbOffsets == null || sbOffsets.isEmpty()) {
            throw new LcvCorruptTileException("CBIT: empty stream in extractSingleFrame");
        }
        int sbCount = sbOffsets.size();
        SuperblockHeader[] headers = new SuperblockHeader[sbCount];
        int targetSbIdx = -1;
        SuperblockHeader targetHdr = null;
        for (int i = 0; i < sbCount; i++) {
            SuperblockHeader hdr = readSuperblockHeader(streamBytes, sbOffsets.get(i));
            headers[i] = hdr;
            long startF = hdr.frameIndex64();
            long endF = startF + hdr.depthK8();
            if (targetFrameIndex >= startF && targetFrameIndex < endF) {
                targetSbIdx = i;
                targetHdr = hdr;
                break;
            }
        }
        if (targetSbIdx < 0 || targetHdr == null) {
            throw new IllegalArgumentException("Frame index " + targetFrameIndex + " not found in stream");
        }

        int width = targetHdr.width16();
        int height = targetHdr.height16();
        int tilesX = targetHdr.tilesX16();
        int tilesY = targetHdr.tilesY16();
        int localT = (int) (targetFrameIndex - targetHdr.frameIndex64());
        int[] outFrame = (reusableOutFrame != null && reusableOutFrame.length >= width * height)
            ? reusableOutFrame
            : new int[width * height];
        final int startTx = Math.max(0, Math.min(tilesX, tileX0));
        final int endTx = roiTilesX <= 0 ? startTx : (int) Math.min(tilesX, (long) startTx + roiTilesX);
        final int startTy = Math.max(0, Math.min(tilesY, tileY0));
        final int endTy = roiTilesY <= 0 ? startTy : (int) Math.min(tilesY, (long) startTy + roiTilesY);
        final ByteBuffer buf = ByteBuffer.wrap(streamBytes).order(ByteOrder.BIG_ENDIAN);
        final ResolvedSuperblockPools[] resolvedPools = new ResolvedSuperblockPools[targetSbIdx + 1];
        final int targetSbFinal = targetSbIdx;
        final int targetSbOff = sbOffsets.get(targetSbIdx);
        final SuperblockHeader targetHdrFinal = targetHdr;
        final int rowCount = endTy - startTy;

        if (rowCount >= 4 && (endTx - startTx) >= 8) {
            resolveSuperblockPoolsOnce(streamBytes, targetSbOff, targetHdr, resolvedPools, targetSbIdx);
            final int[] rowHops = new int[rowCount];
            final int[] rowPayloads = new int[rowCount];
            final int rowStride = (rowCount > 7 && (rowCount % 7) != 0) ? 7 : 1;
            java.util.stream.IntStream.range(0, rowCount).parallel().forEach(w -> {
                final int ty = startTy + ((w * rowStride) % rowCount);
                try {
                    ByteBuffer rowBuf = buf.duplicate().order(ByteOrder.BIG_ENDIAN);
                    LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
                    int[] tileCubeScratch = arena.cubeRgbaTyx;
                    int[] shiftedScratch32x32 = null;
                    int localHops = 0;
                    int localPayloads = 0;
                    int tx = startTx;
                    while (tx < endTx) {
                        long healedPtr = readAndHealSingleTilePointer(
                            streamBytes, rowBuf, targetSbOff, targetHdrFinal, tx, ty
                        );
                        if (healedPtr != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL
                            && isZeroBodyPointer(healedPtr)
                            && !isTemporalCleanAckPointer(healedPtr)) {
                            int runEndTx = tx + 1;
                            while (runEndTx < endTx
                                && readAndHealSingleTilePointer(
                                    streamBytes, rowBuf, targetSbOff, targetHdrFinal, runEndTx, ty
                                ) == healedPtr) {
                                runEndTx++;
                            }
                            int rgba = extractUniformInlineRgba(healedPtr);
                            int baseX = tx * 16;
                            int endX = Math.min(width, runEndTx * 16);
                            int baseY = ty * 16;
                            int endY = Math.min(height, baseY + 16);
                            for (int py = baseY; py < endY; py++) {
                                Arrays.fill(outFrame, py * width + baseX, py * width + endX, rgba);
                            }
                            tx = runEndTx;
                            continue;
                        }
                        long stats = extractSingleTileIntoFrame(
                            streamBytes, rowBuf, sbOffsets, headers, resolvedPools,
                            targetSbFinal, tx, ty, localT, tilesX, tilesY, width, height,
                            arena, tileCubeScratch, shiftedScratch32x32, outFrame
                        );
                        localHops += (int) (stats >>> 32);
                        localPayloads += (int) stats;
                        tx++;
                    }
                    int rIdx = ty - startTy;
                    rowHops[rIdx] = localHops;
                    rowPayloads[rIdx] = localPayloads;
                } catch (LcvCorruptTileException e) {
                    throw new IllegalStateException(e);
                }
            });
            int directoryHops = 0;
            int decodedPayloads = 0;
            for (int i = 0; i < rowCount; i++) {
                directoryHops += rowHops[i];
                decodedPayloads += rowPayloads[i];
            }
            return new SingleFrameExtractResult(
                targetFrameIndex, width, height, outFrame, directoryHops, decodedPayloads
            );
        }

        LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
        int[] tileCubeScratch = arena.cubeRgbaTyx;
        int[] shiftedScratch32x32 = null;
        int directoryHops = 0;
        int decodedPayloads = 0;

        for (int ty = startTy; ty < endTy; ty++) {
            for (int tx = startTx; tx < endTx; tx++) {
                long stats = extractSingleTileIntoFrame(
                    streamBytes, buf, sbOffsets, headers, resolvedPools,
                    targetSbFinal, tx, ty, localT, tilesX, tilesY, width, height,
                    arena, tileCubeScratch, shiftedScratch32x32, outFrame
                );
                directoryHops += (int) (stats >>> 32);
                decodedPayloads += (int) stats;
            }
        }

        return new SingleFrameExtractResult(
            targetFrameIndex, width, height, outFrame, directoryHops, decodedPayloads
        );
    }

    private static long extractSingleTileIntoFrame(
        byte[] streamBytes,
        ByteBuffer buf,
        List<Integer> sbOffsets,
        SuperblockHeader[] headers,
        ResolvedSuperblockPools[] resolvedPools,
        int targetSbIdx,
        int tx,
        int ty,
        int localT,
        int tilesX,
        int tilesY,
        int width,
        int height,
        LcvPrimitives.ScratchArena arena,
        int[] tileCubeScratch,
        int[] shiftedScratch32x32,
        int[] outFrame
    ) throws LcvCorruptTileException {
        int curSbIdx = targetSbIdx;
        int curTx = tx;
        int curTy = ty;
        int cumShiftDx = 0;
        int cumShiftDy = 0;
        int directoryHops = 0;
        int decodedPayloads = 0;

        // Resolve temporal dependency chain in the pointer directory (O(1) per hop, 0 pixel decode!)
        while (curSbIdx > 0) {
            int sbOff = sbOffsets.get(curSbIdx);
            SuperblockHeader hdr = headers[curSbIdx];
            if (hdr == null) {
                hdr = readSuperblockHeader(streamBytes, sbOff);
                headers[curSbIdx] = hdr;
            }
            long ptr = readAndHealSingleTilePointer(streamBytes, buf, sbOff, hdr, curTx, curTy);
            if (ptr != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL
                && isZeroBodyPointer(ptr)
                && isTemporalCleanAckPointer(ptr)) {
                int sDx = extractCleanAckShiftDx(ptr);
                int sDy = extractCleanAckShiftDy(ptr);
                cumShiftDx += sDx;
                cumShiftDy += sDy;
                int step = (sDx == 0 && sDy == 0)
                    ? Math.max(1, (int) ((ptr >>> 46) & 0x3FL))
                    : 1;
                curSbIdx = Math.max(0, curSbIdx - step);
                directoryHops++;
            } else {
                break;
            }
        }

        if (cumShiftDx == 0 && cumShiftDy == 0) {
            decodedPayloads += decodeSingleTileFrameSlice(
                streamBytes, buf, sbOffsets, headers, resolvedPools,
                curSbIdx, curTx, curTy, localT,
                width, height, arena, tileCubeScratch, outFrame, tx, ty
            );
        } else {
            // Reconstruct shifted (baseX - cumShiftDx, baseY - cumShiftDy) from anchor superblock curSbIdx
            // into a 32x32 scratch window by decoding only the <= 4 intersecting 16x16 anchor tiles!
            int[] scratch32 = shiftedScratch32x32 != null ? shiftedScratch32x32 : new int[32 * 32];
            int baseX = tx * 16;
            int baseY = ty * 16;
            int endX = Math.min(width, baseX + 16);
            int endY = Math.min(height, baseY + 16);
            int minSrcTx = Math.max(0, Math.min(tilesX - 1, (baseX - cumShiftDx) / 16));
            int maxSrcTx = Math.max(0, Math.min(tilesX - 1, (endX - 1 - cumShiftDx) / 16));
            int minSrcTy = Math.max(0, Math.min(tilesY - 1, (baseY - cumShiftDy) / 16));
            int maxSrcTy = Math.max(0, Math.min(tilesY - 1, (endY - 1 - cumShiftDy) / 16));
            for (int sty = minSrcTy; sty <= maxSrcTy; sty++) {
                for (int stx = minSrcTx; stx <= maxSrcTx; stx++) {
                    decodedPayloads += decodeSingleTileFrameSlice(
                        streamBytes, buf, sbOffsets, headers, resolvedPools,
                        curSbIdx, stx, sty, localT,
                        32, 32, arena, tileCubeScratch, scratch32,
                        stx - minSrcTx, sty - minSrcTy
                    );
                }
            }
            int originX = minSrcTx * 16;
            int originY = minSrcTy * 16;
            for (int py = baseY; py < endY; py++) {
                int srcY = Math.max(0, Math.min(height - 1, py - cumShiftDy));
                int localY = Math.max(0, Math.min(31, srcY - originY));
                for (int px = baseX; px < endX; px++) {
                    int srcX = Math.max(0, Math.min(width - 1, px - cumShiftDx));
                    int localX = Math.max(0, Math.min(31, srcX - originX));
                    outFrame[py * width + px] = scratch32[localY * 32 + localX];
                }
            }
        }
        return ((long) directoryHops << 32) | (decodedPayloads & 0xFFFFFFFFL);
    }

    private record ResolvedSuperblockPools(
        byte[] msbPool,
        int msbBase,
        byte[] lsbPool,
        int lsbBase,
        ByteBuffer lsbBuf,
        boolean lsbStripped
    ) {}

    private static ResolvedSuperblockPools resolveSuperblockPoolsOnce(
        byte[] streamBytes,
        int sbOffset,
        SuperblockHeader hdr,
        ResolvedSuperblockPools[] resolvedPools,
        int sbIdx
    ) throws LcvCorruptTileException {
        ResolvedSuperblockPools fast = resolvedPools[sbIdx];
        if (fast != null) {
            return fast;
        }
        synchronized (resolvedPools) {
            ResolvedSuperblockPools cached = resolvedPools[sbIdx];
            if (cached != null) {
                return cached;
            }
            boolean zstd = (hdr.flags8() & FLAG_ZSTD_POOL) != 0;
            boolean lsbStripped = (hdr.flags8() & FLAG_LSB_STRIPPED) != 0 || hdr.lsbPoolCompressedLen32() == 0;
            int msbPoolFileOff = sbOffset + hdr.msbPoolOffset();
            int lsbPoolFileOff = sbOffset + hdr.lsbPoolOffset();
            byte[] msbPool = zstd
                ? decompressZstdFrameWithSizeHeader(streamBytes, msbPoolFileOff, hdr.msbPoolCompressedLen32())
                : streamBytes;
            int msbBase = zstd ? 0 : msbPoolFileOff;
            byte[] lsbPool = (!lsbStripped && hdr.lsbPoolCompressedLen32() > 0)
                ? (zstd
                    ? decompressZstdFrameWithSizeHeader(streamBytes, lsbPoolFileOff, hdr.lsbPoolCompressedLen32())
                    : streamBytes)
                : new byte[0];
            int lsbBase = zstd ? 0 : lsbPoolFileOff;
            ByteBuffer lsbBuf = lsbPool.length > 0
                ? ByteBuffer.wrap(lsbPool).order(ByteOrder.BIG_ENDIAN)
                : null;
            ResolvedSuperblockPools resolved = new ResolvedSuperblockPools(
                msbPool, msbBase, lsbPool, lsbBase, lsbBuf, lsbStripped
            );
            resolvedPools[sbIdx] = resolved;
            return resolved;
        }
    }

    private static int decodeSingleTileFrameSlice(
        byte[] streamBytes,
        ByteBuffer streamBuf,
        List<Integer> sbOffsets,
        SuperblockHeader[] headers,
        ResolvedSuperblockPools[] resolvedPools,
        int sbIdx,
        int srcTx,
        int srcTy,
        int localT,
        int width,
        int height,
        LcvPrimitives.ScratchArena arena,
        int[] tileCubeScratch,
        int[] dstSingleFrame,
        int dstTx,
        int dstTy
    ) throws LcvCorruptTileException {
        int sbOffset = sbOffsets.get(sbIdx);
        SuperblockHeader hdr = headers[sbIdx];
        if (hdr == null) {
            hdr = readSuperblockHeader(streamBytes, sbOffset);
            headers[sbIdx] = hdr;
        }
        int tilesX = hdr.tilesX16();
        int nTiles = hdr.totalTiles();
        int depthK = hdr.depthK8();
        int tileIdx = srcTy * tilesX + srcTx;
        long ptr = readAndHealSingleTilePointer(streamBytes, streamBuf, sbOffset, hdr, srcTx, srcTy);
        int baseX = dstTx * 16;
        int baseY = dstTy * 16;
        int endX = Math.min(width, baseX + 16);
        int endY = Math.min(height, baseY + 16);

        if (ptr == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
            return 0;
        }
        if (isZeroBodyPointer(ptr)) {
            int rgba = isTemporalCleanAckPointer(ptr) ? 0 : extractUniformInlineRgba(ptr);
            for (int py = baseY; py < endY; py++) {
                Arrays.fill(dstSingleFrame, py * width + baseX, py * width + endX, rgba);
            }
            return 0;
        }

        ResolvedSuperblockPools pools = resolveSuperblockPoolsOnce(
            streamBytes, sbOffset, hdr, resolvedPools, sbIdx
        );
        int relMsbOff = extractRelByteOffset30(ptr);
        int msbLen = extractMsbSliceLen16(ptr);
        int lsbLen = pools.lsbStripped() ? 0 : extractLsbSliceLen16(ptr);
        int tileLsbOff = 0;
        if (lsbLen > 0 && pools.lsbBuf() != null && pools.lsbPool().length >= pools.lsbBase() + nTiles * 4) {
            int relLsb = pools.lsbBuf().getInt(pools.lsbBase() + tileIdx * 4);
            tileLsbOff = pools.lsbBase() + nTiles * 4 + relLsb;
        }

        int clampedLocalT = Math.max(0, Math.min(depthK - 1, localT));
        LcvCubeCodec.decodeSplitCubeSingleSliceRgba(
            pools.msbPool(), pools.msbBase() + relMsbOff, msbLen,
            pools.lsbPool(), tileLsbOff, lsbLen,
            false, depthK, hdr.tuningProfile().lsbSplitBits(),
            srcTx, srcTy, hdr.roiTileX16(), hdr.roiTileY16(),
            LcvCubeCodec.LcvKeySchedule.OPEN_MIT, hdr.frameIndex64(),
            clampedLocalT,
            arena, tileCubeScratch
        );
        int tBase = clampedLocalT << 8;
        for (int py = baseY; py < endY; py++) {
            int ly = py - baseY;
            int rowOff = py * width;
            int cRow = tBase | (ly << 4);
            for (int px = baseX; px < endX; px++) {
                dstSingleFrame[rowOff + px] = tileCubeScratch[cRow | (px - baseX)];
            }
        }
        return 1;
    }
}
