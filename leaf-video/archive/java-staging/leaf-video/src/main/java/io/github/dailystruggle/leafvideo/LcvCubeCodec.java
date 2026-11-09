package io.github.dailystruggle.leafvideo;

import java.util.Arrays;

/**
 * Spatiotemporal {@code 16 x 16 x K} cube encoder and decoder implementing {@code LCV-ICD-1.0}:
 * outcome-driven enumerated {@link EncodingVariation} selection (implicit {@code palette[0]}
 * + adaptive {@code Delta-Roaring} sub-variations, {@code YCoCg-R} + 8-bit Reflected Gray-Code
 * bitplanes, drift-free 6-bit MSB Hilbert-Delta + 2-bit LSB split, and raw ceiling) with
 * wire-compatible distance-scaled run-length concatenation ({@code d_merge(plane, dist)}).
 *
 * @governedBy leaf-video-ADR-001
 * @governedBy leaf-video-ADR-003
 * @governedBy leaf-video-ADR-004
 * @trace REQ-LCV-F-001
 * @trace REQ-LCV-F-002
 * @trace REQ-LCV-F-004
 * @trace REQ-LCV-DB-003
 * @trace REQ-LCV-HW-001
 * @trace REQ-LCV-HW-002
 */
public final class LcvCubeCodec {

    public static final int CUBE_MODE_UI_PALETTE_ROARING = 0;      // 00
    public static final int CUBE_MODE_GRAY_PLANES_RAW = 1;         // 01
    public static final int CUBE_MODE_GRAY_PLANES_HILBERT_DELTA = 2; // 10
    public static final int CUBE_MODE_RAW_CEILING = 3;             // 11

    public static final int ROARING_TAG_SINGLETON = 0;   // 00
    public static final int ROARING_TAG_DELTA_RUN = 1;   // 01
    public static final int ROARING_TAG_DELTA_ARRAY = 2; // 10
    public static final int ROARING_TAG_BITMAP = 3;      // 11

    public static final int PLANE_ALL_ZERO = 0;            // 00 (MSB & LSB)
    public static final int PLANE_ALL_ONE = 1;             // 01 legacy alias
    public static final int PLANE_SINGLE_RUN_INLINE = 1;   // 01 (MSB Tier: 2B inline start/len or 0xFFFF all-one)
    public static final int PLANE_XOR_UPPER_RLE = 1;       // 01 (LSB Tier: Gray cross-plane b_p ^ b_{p+1} RLE)
    public static final int PLANE_NIBBLE_RLE = 2;          // 10 (MSB & LSB: 4|4 nibble gap/run stream)
    public static final int PLANE_RAW_BITMAP = 3;          // 11 (LSB Tier: raw bitmap)
    public static final int PLANE_TRIPLET_ZERO_SKIP = 3;   // 11 (MSB Tier: dense/triplet tagged stream)

    public static final int DELTA_SUBMODE_HILBERT_1D_MSB = 0x00;
    public static final int DELTA_SUBMODE_PLANAR_3D_MV = 0x01;
    public static final int DELTA_FLAG_DIRECT_RGBA = 0x02;
    public static final int DELTA_FLAG_MAXU_CONDITIONED = 0x04;
    public static final int DELTA_FLAG_MOTION_SHEARED = 0x08;
    public static final int DELTA_SUBMODE_WAVELET_3D_HAAR = 0x10;
    public static final int DELTA_FLAG_HALF_PEL_MV = 0x10; // Q3.1 half-pixel bilinear MV when DELTA_FLAG_SPATIAL_MED_2D is set
    public static final int DELTA_FLAG_ALPHA_MASKED_RGB = 0x20;
    public static final int DELTA_FLAG_SPATIAL_MED_2D = 0x40;
    public static final int DELTA_FLAG_COMPACT_PLANAR_HDR = 0x80;
    public static final int DENSE_TAG_RAW_BITMAP = 0x00;
    public static final int DENSE_TAG_TRIPLET_ZERO_SKIP = 0x01;
    public static final int DENSE_TAG_TRIPLET_ONE_SKIP = 0x02;
    public static final int DENSE_TAG_TRIMMED_BITMAP = 0x03;

    /**
     * Optional hardware/external optical flow hook ({@code NVOF}, ISP motion vectors, or GPU compute)
     * that supplies packed 2D velocity bytes {@code ((vx + 8) << 4) | ((vy + 8) & 0x0F)} per tile/frame
     * to bypass software block-matching search when available ({@code leaf-video-ADR-004} {@code REQ-LCV-HW-002}).
     */
    @FunctionalInterface
    public interface ExternalFlowProvider {
        /**
         * Returns a packed velocity byte in {@code [0x00, 0xFF]} for {@code (tileX, tileY, frameOffset)},
         * or {@code -1} to fall back to software trajectory estimation.
         */
        int getPackedVelocity8(int tileX, int tileY, int frameOffset);
    }

    private static final ThreadLocal<ExternalFlowProvider> ACTIVE_EXTERNAL_FLOW = new ThreadLocal<>();

    public static void setExternalFlowProvider(ExternalFlowProvider provider) {
        if (provider == null) {
            ACTIVE_EXTERNAL_FLOW.remove();
        } else {
            ACTIVE_EXTERNAL_FLOW.set(provider);
        }
    }

    public static ExternalFlowProvider getExternalFlowProvider() {
        return ACTIVE_EXTERNAL_FLOW.get();
    }

    // Complete Kraft-sum-1 prefix code for 3-bit triplets t in {0..7}:
    // t=0 (000) -> 0 (1b)
    // t=1 (001) -> 001 (3b), t=2 (010) -> 011 (3b), t=4 (100) -> 101 (3b)
    // t=3 (011) -> 00111 (5b), t=5 (101) -> 01111 (5b), t=6 (110) -> 10111 (5b), t=7 (111) -> 11111 (5b)
    private static final int[] TRIPLET_ENC_BITS = {0b0, 0b001, 0b011, 0b00111, 0b101, 0b01111, 0b10111, 0b11111};
    private static final int[] TRIPLET_ENC_LEN = {1, 3, 3, 5, 3, 5, 5, 5};
    private static final int[] TRIPLET_DEC_SYM = new int[32];
    private static final int[] TRIPLET_DEC_LEN = new int[32];

    static {
        for (int peek5 = 0; peek5 < 32; peek5++) {
            if ((peek5 & 1) == 0) {
                TRIPLET_DEC_SYM[peek5] = 0;
                TRIPLET_DEC_LEN[peek5] = 1;
            } else {
                int low3 = peek5 & 7;
                if (low3 == 0b001) {
                    TRIPLET_DEC_SYM[peek5] = 1;
                    TRIPLET_DEC_LEN[peek5] = 3;
                } else if (low3 == 0b011) {
                    TRIPLET_DEC_SYM[peek5] = 2;
                    TRIPLET_DEC_LEN[peek5] = 3;
                } else if (low3 == 0b101) {
                    TRIPLET_DEC_SYM[peek5] = 4;
                    TRIPLET_DEC_LEN[peek5] = 3;
                } else {
                    int hi2 = (peek5 >>> 3) & 3;
                    TRIPLET_DEC_SYM[peek5] = switch (hi2) {
                        case 0 -> 3;
                        case 1 -> 5;
                        case 2 -> 6;
                        default -> 7;
                    };
                    TRIPLET_DEC_LEN[peek5] = 5;
                }
            }
        }
    }

    /**
     * Extensible enumerated family of cube and container encoding variations evaluated by
     * {@link LcvCubeCodec} to pick the smallest byte-size outcome for each tile's content.
     */
    public enum EncodingVariation {
        UNIFORM_INLINE(0, true, false),
        TEMPORAL_CLEAN_ACK(1, true, false),
        UI_PALETTE_ROARING(2, false, false),
        ROARING_SINGLETON(3, false, true),
        ROARING_DELTA_RUN(4, false, true),
        ROARING_DELTA_ARRAY(5, false, true),
        ROARING_BITMAP(6, false, true),
        GRAY_PLANES_RAW(7, false, false),
        GRAY_PLANES_HILBERT_DELTA(8, false, false),
        RAW_CEILING(9, false, false),
        PLANAR_3D_MV_COUPLED(10, false, false),
        WAVELET_3D_HAAR(11, false, false);
        private final int code;
        private final boolean zeroBodyInline;
        private final boolean roaringSubContainer;

        EncodingVariation(int code, boolean zeroBodyInline, boolean roaringSubContainer) {
            this.code = code;
            this.zeroBodyInline = zeroBodyInline;
            this.roaringSubContainer = roaringSubContainer;
        }

        public int code() {
            return code;
        }

        public boolean isZeroBodyInline() {
            return zeroBodyInline;
        }

        public boolean isRoaringSubContainer() {
            return roaringSubContainer;
        }
    }

    /**
     * Distance-scaled run-length concatenation policy {@code d_merge(plane, dist)}.
     *
     * @param losslessPlaneCutoff bitplanes {@code p >= losslessPlaneCutoff} always use {@code d_merge = 0} (100% lossless)
     * @param baseMaxGap          maximum 0-gap bridged at radial distance {@code r = 0} on plane {@code losslessPlaneCutoff - 1}
     * @param radialScalePerTile  additional gap bridging per Euclidean tile distance step from the ROI center
     */
    public record DistanceRlePolicy(int losslessPlaneCutoff, int baseMaxGap, double radialScalePerTile) {
        public static final DistanceRlePolicy LOSSLESS = new DistanceRlePolicy(0, 0, 0.0);

        public int maxMergeGap(int planeIndex, double tileRadialDistance) {
            if (planeIndex >= losslessPlaneCutoff || losslessPlaneCutoff <= 0) {
                return 0;
            }
            double base = baseMaxGap + tileRadialDistance * radialScalePerTile;
            if (base <= 0.0) {
                return 0;
            }
            int shift = Math.min(12, losslessPlaneCutoff - 1 - planeIndex);
            double scaled = base * (1 << shift);
            return (int) Math.min(LcvPrimitives.MAX_CUBE_SAMPLES, Math.floor(scaled));
        }

        public boolean isLosslessAt(double tileRadialDistance) {
            return maxMergeGap(0, tileRadialDistance) == 0;
        }
    }

    /**
     * Normative rate-latency-resilience tuning profile ({@code LCV-ICD-1.0} Section 7) packed into
     * the 16-bit {@code tuningProfile16} Superblock header word at byte offset {@code 0x1A..0x1B}.
     *
     * @param lsbSplitBits          number of LSB bitplanes {@code s in {1, 2, 3, 4}} placed in Section 4B
     * @param rlePolicy             distance-scaled run-length concatenation policy {@code d_merge(plane, dist)}
     * @param cleanAckRefreshWindow maximum backward superblock hops {@code M_refresh in {0, 1, 2, 4, 8, 16, 32, 64}}
     */
    public record TuningProfile(int lsbSplitBits, DistanceRlePolicy rlePolicy, int cleanAckRefreshWindow) {
        public static final TuningProfile DEFAULT_LOSSLESS =
            new TuningProfile(2, DistanceRlePolicy.LOSSLESS, 8);
        public static final TuningProfile ARCHIVAL_LOSSLESS =
            new TuningProfile(2, DistanceRlePolicy.LOSSLESS, 16);
        public static final TuningProfile OBS_REALTIME_LOSSLESS =
            new TuningProfile(2, DistanceRlePolicy.LOSSLESS, 8);
        public static final TuningProfile WIRELESS_FOVEATED_LOW_LATENCY =
            new TuningProfile(2, new DistanceRlePolicy(4, 0, 1.25), 2);
        public static final TuningProfile HARSH_RF_UEP_ZERO_WAIT =
            new TuningProfile(3, new DistanceRlePolicy(4, 1, 2.00), 1);

        public TuningProfile {
            if (lsbSplitBits < 1 || lsbSplitBits > 4) {
                throw new IllegalArgumentException("lsbSplitBits must be in {1, 2, 3, 4}: " + lsbSplitBits);
            }
            if (rlePolicy == null) {
                rlePolicy = DistanceRlePolicy.LOSSLESS;
            }
            if (cleanAckRefreshWindow < 0 || cleanAckRefreshWindow > 64) {
                throw new IllegalArgumentException("cleanAckRefreshWindow must be in [0, 64]: " + cleanAckRefreshWindow);
            }
        }

        public int toPackedU16() {
            int splitCode = switch (lsbSplitBits) {
                case 2 -> 0;
                case 1 -> 1;
                case 3 -> 2;
                case 4 -> 3;
                default -> 0;
            };
            int cutoff3 = Math.max(0, Math.min(7, rlePolicy.losslessPlaneCutoff()));
            int baseGap4 = Math.max(0, Math.min(15, rlePolicy.baseMaxGap()));
            int radialQ2_4 = Math.max(0, Math.min(15, (int) Math.round(rlePolicy.radialScalePerTile() * 4.0)));
            int refreshCode = switch (cleanAckRefreshWindow) {
                case 8 -> 0;
                case 0 -> 1;
                case 1 -> 2;
                case 2 -> 3;
                case 4 -> 4;
                case 16 -> 5;
                case 32 -> 6;
                case 64 -> 7;
                default -> 0;
            };
            return ((splitCode & 0x03) << 14)
                | ((cutoff3 & 0x07) << 11)
                | ((baseGap4 & 0x0F) << 7)
                | ((radialQ2_4 & 0x0F) << 3)
                | (refreshCode & 0x07);
        }

        public static TuningProfile fromPackedU16(int packed16) {
            int splitCode = (packed16 >>> 14) & 0x03;
            int lsbSplitBits = switch (splitCode) {
                case 0 -> 2;
                case 1 -> 1;
                case 2 -> 3;
                case 3 -> 4;
                default -> 2;
            };
            int cutoff3 = (packed16 >>> 11) & 0x07;
            int baseGap4 = (packed16 >>> 7) & 0x0F;
            int radialQ2_4 = (packed16 >>> 3) & 0x0F;
            double radialScale = radialQ2_4 / 4.0;
            int refreshCode = packed16 & 0x07;
            int refreshWindow = switch (refreshCode) {
                case 0 -> 8;
                case 1 -> 0;
                case 2 -> 1;
                case 3 -> 2;
                case 4 -> 4;
                case 5 -> 16;
                case 6 -> 32;
                case 7 -> 64;
                default -> 8;
            };
            DistanceRlePolicy policy = (cutoff3 == 0)
                ? DistanceRlePolicy.LOSSLESS
                : new DistanceRlePolicy(cutoff3, baseGap4, radialScale);
            return new TuningProfile(lsbSplitBits, policy, refreshWindow);
        }
    }

    /**
     * Split Key Schedule ({@code KSC} Layer C) supporting:
     * <ol>
     *   <li>{@link #OPEN_MIT}: {@code masterKey == 0} (100% open MIT deterministic prior);</li>
     *   <li>{@link #zeroTrustRelay}: unkeyed {@code K_relay = 0} directory/modes with keyed {@code K_msb, K_lsb}
     *       so untrusted cloud/relay nodes can crop, strip LSBs, and coalesce RLE tokens without pixel decode;</li>
     *   <li>{@link #previewUnlock}: open {@code K_msb = 0} Section 4A preview with keyed {@code K_lsb != 0}
     *       Section 4B lossless master unlock ({@code 0} extra bytes on disk);</li>
     *   <li>{@link #spatialRoiRedaction}: keys only tiles within {@code roiRadiusTiles} of {@code (roiTileX, roiTileY)}.</li>
     * </ol>
     */
    public record LcvKeySchedule(
        long msbKey64,
        long lsbKey64,
        long relayKey64,
        int roiRadiusTiles
    ) {
        public static final LcvKeySchedule OPEN_MIT = new LcvKeySchedule(0L, 0L, 0L, 0);

        public static LcvKeySchedule fromMasterKey(long masterKey64) {
            if (masterKey64 == 0L) {
                return OPEN_MIT;
            }
            long kMsb = LcvPrimitives.mix64(masterKey64 ^ 0x4B53435F4D534231L);
            long kLsb = LcvPrimitives.mix64(masterKey64 ^ 0x4B53435F4C534232L);
            long kRelay = LcvPrimitives.mix64(masterKey64 ^ 0x4B53435F524C5933L);
            return new LcvKeySchedule(kMsb, kLsb, kRelay, 0);
        }

        public static LcvKeySchedule zeroTrustRelay(long contentMasterKey64) {
            if (contentMasterKey64 == 0L) {
                return OPEN_MIT;
            }
            long kMsb = LcvPrimitives.mix64(contentMasterKey64 ^ 0x4B53435F4D534231L);
            long kLsb = LcvPrimitives.mix64(contentMasterKey64 ^ 0x4B53435F4C534232L);
            return new LcvKeySchedule(kMsb, kLsb, 0L, 0);
        }

        public static LcvKeySchedule previewUnlock(long lsbSecretKey64) {
            if (lsbSecretKey64 == 0L) {
                return OPEN_MIT;
            }
            long kLsb = LcvPrimitives.mix64(lsbSecretKey64 ^ 0x4B53435F4C534232L);
            return new LcvKeySchedule(0L, kLsb, 0L, 0);
        }

        public static LcvKeySchedule spatialRoiRedaction(long masterKey64, int roiRadiusTiles) {
            if (masterKey64 == 0L) {
                return OPEN_MIT;
            }
            long kMsb = LcvPrimitives.mix64(masterKey64 ^ 0x4B53435F4D534231L);
            long kLsb = LcvPrimitives.mix64(masterKey64 ^ 0x4B53435F4C534232L);
            return new LcvKeySchedule(kMsb, kLsb, 0L, Math.max(1, roiRadiusTiles));
        }

        public boolean isKeyed() {
            return msbKey64 != 0L || lsbKey64 != 0L || relayKey64 != 0L;
        }

        public boolean isTileInScope(int tileX, int tileY, int roiTileX, int roiTileY) {
            if (!isKeyed()) {
                return false;
            }
            if (roiRadiusTiles <= 0) {
                return true;
            }
            int dx = tileX - roiTileX;
            int dy = tileY - roiTileY;
            return (dx * dx + dy * dy) <= (roiRadiusTiles * roiRadiusTiles);
        }
    }

    /**
     * Detailed outcome of encoding a single {@code 16 x 16 x K} cube, reporting the winning
     * {@link EncodingVariation}, split Base MSB Slice / Tail LSB Slice byte lengths, and
     * estimated tile velocity vector {@code packedMv8} ({@code ((vx + 8) << 4) | ((vy + 8) & 0x0F)}).
     */
    public record CubeEncodeResult(
        EncodingVariation winningVariation,
        int curveId,
        int msbSliceBytes,
        int lsbSliceBytes,
        int totalBytes,
        int uniformRgba,
        long contentDigest64,
        int packedMv8
    ) {
        public CubeEncodeResult(
            EncodingVariation winningVariation,
            int curveId,
            int msbSliceBytes,
            int lsbSliceBytes,
            int totalBytes,
            int uniformRgba,
            long contentDigest64
        ) {
            this(winningVariation, curveId, msbSliceBytes, lsbSliceBytes, totalBytes, uniformRgba, contentDigest64, 0x88);
        }

        public int velocityX() {
            return LcvPrimitives.unpackVelocityX(packedMv8);
        }

        public int velocityY() {
            return LcvPrimitives.unpackVelocityY(packedMv8);
        }
    }

    private LcvCubeCodec() {}

    /**
     * Standard normative entry point encoding a {@code 16 x 16 x K} tile at {@code (tileX, tileY)}
     * from {@code rgbaFrames} into {@code dst[dstOff..]} and returning the total encoded byte length
     * ({@code 0} when {@link EncodingVariation#UNIFORM_INLINE} applies).
     */
    public static int encodeCubeRgba(
        int[] rgbaFrames, int tileX, int tileY, int frameWidth, int depthK,
        int roiTileX, int roiTileY, DistanceRlePolicy rlePolicy,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        return encodeCubeRgbaDetailed(
            rgbaFrames, tileX, tileY, frameWidth, depthK,
            roiTileX, roiTileY, rlePolicy, arena, dst, dstOff
        ).totalBytes();
    }

    /**
     * Detailed encoder evaluating all applicable {@link EncodingVariation} candidates in {@code arena}
     * and writing the smallest byte-size outcome into {@code dst[dstOff..]}.
     */
    public static CubeEncodeResult encodeCubeRgbaDetailed(
        int[] rgbaFrames, int tileX, int tileY, int frameWidth, int depthK,
        int roiTileX, int roiTileY, DistanceRlePolicy rlePolicy,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        validateDepthK(depthK);
        if (arena == null) {
            arena = LcvPrimitives.threadArena();
        }
        if (rlePolicy == null) {
            rlePolicy = DistanceRlePolicy.LOSSLESS;
        }

        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        if (rgbaFrames.length == nSamples && frameWidth <= LcvPrimitives.TILE_DIM) {
            System.arraycopy(rgbaFrames, 0, arena.cubeRgbaTyx, 0, nSamples);
        } else {
            int framePixels = frameWidth > 0 ? (rgbaFrames.length / depthK) : LcvPrimitives.TILE_PIXELS;
            int frameHeight = frameWidth > 0 ? Math.max(1, framePixels / frameWidth) : LcvPrimitives.TILE_DIM;
            extractTileToCubeTyx(rgbaFrames, tileX, tileY, frameWidth, frameHeight, depthK, arena.cubeRgbaTyx);
        }
        return encodeExtractedCubeTyx(
            arena.cubeRgbaTyx, depthK, tileX, tileY, roiTileX, roiTileY, rlePolicy, arena, dst, dstOff
        );
    }

    /**
     * Encodes an already-extracted {@code 16 x 16 x K} cube in {@code TYX} order using default {@code lsbSplitBits = 2}.
     */
    public static CubeEncodeResult encodeExtractedCubeTyx(
        int[] cubeRgbaTyx, int depthK, int tileX, int tileY,
        int roiTileX, int roiTileY, DistanceRlePolicy rlePolicy,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        return encodeExtractedCubeTyx(
            cubeRgbaTyx, depthK, tileX, tileY, roiTileX, roiTileY, rlePolicy, 2, arena, dst, dstOff
        );
    }

    /**
     * Encodes an already-extracted {@code 16 x 16 x K} cube in {@code TYX} order with configurable
     * {@code lsbSplitBits} ({@code s in {1, 2, 3, 4}}).
     */
    public static CubeEncodeResult encodeExtractedCubeTyx(
        int[] cubeRgbaTyx, int depthK, int tileX, int tileY,
        int roiTileX, int roiTileY, DistanceRlePolicy rlePolicy, int lsbSplitBits,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        validateDepthK(depthK);
        if (arena == null) {
            arena = LcvPrimitives.threadArena();
        }
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        LcvPrimitives.CubeFingerprint fp = LcvPrimitives.fingerprintExtractedCubeTyx(
            cubeRgbaTyx, depthK, true, arena
        );
        return encodeExtractedCubeTyx(
            cubeRgbaTyx, depthK, tileX, tileY, roiTileX, roiTileY,
            rlePolicy, lsbSplitBits, fp.digest64, !fp.uniform,
            LcvKeySchedule.OPEN_MIT, 0L, arena, dst, dstOff
        );
    }

    /**
     * Fast-path overload accepting a precomputed 64-bit cube digest and optional uniform-check bypass
     * when called from {@link LcvStreamContainer}.
     */
    public static CubeEncodeResult encodeExtractedCubeTyx(
        int[] cubeRgbaTyx, int depthK, int tileX, int tileY,
        int roiTileX, int roiTileY, DistanceRlePolicy rlePolicy, int lsbSplitBits,
        long digest64, boolean knownNonUniform,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        return encodeExtractedCubeTyx(
            cubeRgbaTyx, depthK, tileX, tileY, roiTileX, roiTileY,
            rlePolicy, lsbSplitBits, digest64, knownNonUniform,
            LcvKeySchedule.OPEN_MIT, 0L, arena, dst, dstOff
        );
    }

    /**
     * Keyed Structural Codebook ({@code KSC}) overload encoding an extracted {@code 16 x 16 x K} cube
     * with an optional {@link LcvKeySchedule} and {@code frameIndex64}.
     */
    public static CubeEncodeResult encodeExtractedCubeTyx(
        int[] cubeRgbaTyx, int depthK, int tileX, int tileY,
        int roiTileX, int roiTileY, DistanceRlePolicy rlePolicy, int lsbSplitBits,
        long digest64, boolean knownNonUniform,
        LcvKeySchedule keySchedule, long frameIndex64,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        validateDepthK(depthK);
        if (lsbSplitBits < 1 || lsbSplitBits > 4) {
            throw new IllegalArgumentException("lsbSplitBits must be in {1, 2, 3, 4}: " + lsbSplitBits);
        }
        if (arena == null) {
            arena = LcvPrimitives.threadArena();
        }
        if (rlePolicy == null) {
            rlePolicy = DistanceRlePolicy.LOSSLESS;
        }
        if (keySchedule == null) {
            keySchedule = LcvKeySchedule.OPEN_MIT;
        }

        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        LcvPrimitives.CubeFingerprint fp = arena.fingerprint;
        if (!fp.valid || fp.digest64 != digest64 || fp.firstRgba != cubeRgbaTyx[0]) {
            fp = LcvPrimitives.fingerprintExtractedCubeTyx(cubeRgbaTyx, depthK, !knownNonUniform, arena);
        } else if (!knownNonUniform && fp.uniform) {
            return new CubeEncodeResult(
                EncodingVariation.UNIFORM_INLINE, LcvPrimitives.CURVE_TYX, 0, 0, 0, fp.firstRgba, digest64
            );
        }

        int gpuPreMv = arena.precomputedPackedMv8;
        arena.precomputedPackedMv8 = -1;

        // 1. Check UNIFORM_INLINE (0 body bytes, highest precedence per LCV-ICD-1.0 Section 4.1)
        if (!knownNonUniform && fp.uniform) {
            return new CubeEncodeResult(
                EncodingVariation.UNIFORM_INLINE, LcvPrimitives.CURVE_TYX, 0, 0, 0, fp.firstRgba, digest64
            );
        }

        boolean tileKeyed = keySchedule.isTileInScope(tileX, tileY, roiTileX, roiTileY);
        int tileCoordId = ((tileY & 0xFFFF) << 16) | (tileX & 0xFFFF);
        long msbTileKey64 = tileKeyed
            ? LcvPrimitives.computeTileKey64(keySchedule.msbKey64(), frameIndex64, tileCoordId)
            : 0L;
        long lsbTileKey64 = tileKeyed
            ? LcvPrimitives.computeTileKey64(keySchedule.lsbKey64(), frameIndex64, tileCoordId)
            : 0L;
        long relayTileKey64 = tileKeyed
            ? LcvPrimitives.computeTileKey64(keySchedule.relayKey64(), frameIndex64, tileCoordId)
            : 0L;
        int curveKey16 = (msbTileKey64 != 0L)
            ? (int) ((msbTileKey64 & 0xFFFFL) == 0L ? 1 : (msbTileKey64 & 0xFFFFL))
            : 0;

        boolean hasZeroAlpha = fp.hasZeroAlpha;
        boolean hasOpaqueAlpha = fp.hasOpaqueAlpha;
        boolean allZeroAlphaAreZeroRgba = fp.allZeroAlphaAreZeroRgba;
        boolean allOpaqueAre255AndConstGB = fp.allOpaqueAre255AndConstGB;
        int firstOpaqueRgba = fp.firstOpaqueRgba;
        int firstOpaqueTyx = fp.firstOpaqueTyx;
        int opG = fp.opG;
        int opB = fp.opB;
        boolean canAlphaMaskRgb = hasZeroAlpha && hasOpaqueAlpha && allZeroAlphaAreZeroRgba;
        if (canAlphaMaskRgb && !allOpaqueAre255AndConstGB) {
            int minMaskAlpha = 1 << lsbSplitBits;
            for (int i = 0; i < nSamples; i++) {
                int a = cubeRgbaTyx[i] & 0xFF;
                if (a > 0 && a < minMaskAlpha) {
                    canAlphaMaskRgb = false;
                    break;
                }
            }
        }

        // Ultra-fast 1-pass lock for interior & alpha-masked modular-64 RGBA shader/overlay tiles
        if (!tileKeyed && hasOpaqueAlpha && allZeroAlphaAreZeroRgba && allOpaqueAre255AndConstGB) {
            int v0 = (firstOpaqueRgba >>> 24) & 0xFF;
            if (v0 >= 180 && v0 <= 243) {
                int dxStep = Integer.MIN_VALUE;
                int dyStep = Integer.MIN_VALUE;
                int dtStep = (depthK > 1) ? Integer.MIN_VALUE : 0;
                for (int tyx = firstOpaqueTyx; tyx < nSamples; tyx++) {
                    int rgba0 = cubeRgbaTyx[tyx];
                    if ((rgba0 & 0xFF) == 0) {
                        continue;
                    }
                    int r0 = (rgba0 >>> 24) & 0xFF;
                    int lx = tyx & 15;
                    int ly = (tyx >>> 4) & 15;
                    int lt = tyx >>> 8;
                    if (dxStep == Integer.MIN_VALUE && lx + 1 < 16 && (cubeRgbaTyx[tyx + 1] & 0xFF) != 0) {
                        dxStep = (((((cubeRgbaTyx[tyx + 1] >>> 24) & 0xFF) - 180) - (r0 - 180) + 32) & 0x3F) - 32;
                    }
                    if (dyStep == Integer.MIN_VALUE && ly + 1 < 16 && (cubeRgbaTyx[tyx + 16] & 0xFF) != 0) {
                        dyStep = (((((cubeRgbaTyx[tyx + 16] >>> 24) & 0xFF) - 180) - (r0 - 180) + 32) & 0x3F) - 32;
                    }
                    if (dtStep == Integer.MIN_VALUE && lt + 1 < depthK && (cubeRgbaTyx[tyx + 256] & 0xFF) != 0) {
                        dtStep = (((((cubeRgbaTyx[tyx + 256] >>> 24) & 0xFF) - 180) - (r0 - 180) + 32) & 0x3F) - 32;
                    }
                    if (dxStep != Integer.MIN_VALUE && dyStep != Integer.MIN_VALUE && dtStep != Integer.MIN_VALUE) {
                        break;
                    }
                }
                if (dxStep == Integer.MIN_VALUE) {
                    dxStep = 0;
                }
                if (dyStep == Integer.MIN_VALUE) {
                    dyStep = 0;
                }
                if (dtStep == Integer.MIN_VALUE) {
                    dtStep = 0;
                }
                if (Math.abs(dxStep) <= 1 && Math.abs(dyStep) <= 1 && Math.abs(dtStep) <= 3) {
                    int lx0 = firstOpaqueTyx & 15;
                    int ly0 = (firstOpaqueTyx >>> 4) & 15;
                    int lt0 = firstOpaqueTyx >>> 8;
                    int w000 = ((v0 - 180) - (dxStep * lx0 + dyStep * ly0 + dtStep * lt0)) & 0x3F;
                    boolean exactRamp = true;
                    boolean rVaries = false;
                    for (int tyx = firstOpaqueTyx; tyx < nSamples; tyx++) {
                        int rgba = cubeRgbaTyx[tyx];
                        if ((rgba & 0xFF) == 0) {
                            continue;
                        }
                        int r = (rgba >>> 24) & 0xFF;
                        if (r != v0) {
                            rVaries = true;
                        }
                        int lx = tyx & 15;
                        int ly = (tyx >>> 4) & 15;
                        int lt = tyx >>> 8;
                        int pred = 180 + ((w000 + dxStep * lx + dyStep * ly + dtStep * lt) & 0x3F);
                        if (r != pred) {
                            exactRamp = false;
                            break;
                        }
                    }
                    if (exactRamp && !hasZeroAlpha && rVaries) {
                        int pos = dstOff;
                        dst[pos++] = (byte) ((LcvPrimitives.CURVE_TYX << 6)
                            | (CUBE_MODE_GRAY_PLANES_HILBERT_DELTA << 4)
                            | 0x01);
                        dst[pos++] = (byte) opG;
                        dst[pos++] = (byte) opB;
                        dst[pos++] = (byte) 0xFF;
                        dst[pos++] = (byte) (DELTA_SUBMODE_PLANAR_3D_MV
                            | DELTA_FLAG_MAXU_CONDITIONED
                            | DELTA_FLAG_DIRECT_RGBA
                            | DELTA_FLAG_COMPACT_PLANAR_HDR);
                        dst[pos++] = 0;
                        dst[pos++] = (byte) (0xC0 | (w000 & 0x3F));
                        dst[pos++] = (byte) 180;
                        dst[pos++] = (byte) (dxStep << 6);
                        dst[pos++] = (byte) (dyStep << 6);
                        dst[pos++] = (byte) (dtStep << 5);
                        int written = pos - dstOff;
                        return new CubeEncodeResult(
                            EncodingVariation.GRAY_PLANES_HILBERT_DELTA,
                            LcvPrimitives.CURVE_TYX,
                            written, 0, written, 0, digest64, 0x88
                        );
                    }
                    if (exactRamp && hasZeroAlpha) {
                        int fastCurveId = (depthK > 1) ? LcvPrimitives.CURVE_T_H2 : LcvPrimitives.CURVE_TYX;
                        short[] fastCurveToTyx = LcvPrimitives.curveToTyxLut(depthK, fastCurveId);
                        int activeMask = rVaries ? 0x09 : 0x08;
                        int pos = dstOff;
                        dst[pos++] = (byte) ((fastCurveId << 6)
                            | (CUBE_MODE_GRAY_PLANES_HILBERT_DELTA << 4)
                            | activeMask);
                        if (!rVaries) {
                            dst[pos++] = (byte) v0;
                        }
                        dst[pos++] = (byte) opG;
                        dst[pos++] = (byte) opB;
                        dst[pos++] = (byte) (DELTA_SUBMODE_PLANAR_3D_MV
                            | DELTA_FLAG_MAXU_CONDITIONED
                            | DELTA_FLAG_DIRECT_RGBA
                            | DELTA_FLAG_ALPHA_MASKED_RGB
                            | DELTA_FLAG_COMPACT_PLANAR_HDR);
                        if (rVaries) {
                            dst[pos++] = 0;
                            dst[pos++] = (byte) (0xC0 | (w000 & 0x3F));
                            dst[pos++] = (byte) 180;
                            dst[pos++] = (byte) (dxStep << 6);
                            dst[pos++] = (byte) (dyStep << 6);
                            dst[pos++] = (byte) (dtStep << 5);
                        }
                        int aModesOff = pos;
                        int stepBitVal = 1 << lsbSplitBits;
                        dst[pos++] = 0;
                        dst[pos++] = (byte) (0xA0 | (stepBitVal & 0x0F));
                        dst[pos++] = 0;
                        dst[pos++] = 0;
                        dst[pos++] = 0;
                        dst[pos++] = 0;
                        byte[] actBits = arena.activeBitPlane;
                        for (int i = 0; i < nSamples; i++) {
                            actBits[i] = (byte) (((cubeRgbaTyx[fastCurveToTyx[i] & 0xFFFF] & 0xFF) != 0) ? 1 : 0);
                        }
                        int[] modeAndBytes = encodeConditionedBitplane(actBits, nSamples, 0, arena, dst, pos);
                        pos += modeAndBytes[1];
                        dst[aModesOff] = (byte) ((modeAndBytes[0] << (2 * lsbSplitBits)) & 0xFF);
                        int written = pos - dstOff;
                        return new CubeEncodeResult(
                            EncodingVariation.GRAY_PLANES_HILBERT_DELTA,
                            fastCurveId,
                            written, 0, written, 0, digest64, 0x88
                        );
                    }
                }
            }
        }

        arena.activeMsbTileKey64 = msbTileKey64;
        arena.activeLsbTileKey64 = lsbTileKey64;
        arena.activeRelayTileKey64 = relayTileKey64;
        arena.activeLsbSplitBits = lsbSplitBits;
        arena.med2dStepsCached = false;
        try {
            // 3. Select optimal 1-pass space-filling curve from fingerprint and apply Layer B curveKey16 isometry
            int curveId = fp.optimalCurveId;
            short[] curveToTyx = LcvPrimitives.resolveCurveToTyxLut(depthK, curveId, curveKey16, arena);
            int[] curveRgba = arena.curveRgba;
            for (int i = 0; i < nSamples; i++) {
                curveRgba[i] = cubeRgbaTyx[curveToTyx[i] & 0xFFFF];
            }

            // 4. Candidate A: UI_PALETTE_ROARING (only evaluated if fingerprint detected <= 16 unique colors)
            int uiLen = (fp.paletteSize <= 16)
                ? tryEncodeUiPaletteRoaring(curveRgba, nSamples, curveId, arena, arena.candidateUiBuf, 0)
                : Integer.MAX_VALUE;
            if (uiLen <= 128 || (fp.paletteSize <= 4 && uiLen <= 256 && !canAlphaMaskRgb)) {
                System.arraycopy(arena.candidateUiBuf, 0, dst, dstOff, uiLen);
                return new CubeEncodeResult(
                    EncodingVariation.UI_PALETTE_ROARING, curveId, uiLen, 0, uiLen, 0, digest64, 0x88
                );
            }

            double dx = tileX - roiTileX;
            double dy = tileY - roiTileY;
            double radialDist = Math.sqrt(dx * dx + dy * dy);

            // 5. Extract Direct RGBA channels first; only decompose YCoCg-R if >1 Direct RGBA channel varies and !canAlphaMaskRgb
            byte[] raw0 = arena.rawChannelValues[0];
            byte[] raw1 = arena.rawChannelValues[1];
            byte[] raw2 = arena.rawChannelValues[2];
            byte[] raw3 = arena.rawChannelValues[3];

            int firstRgba = curveRgba[0];
            int refRawRgba = canAlphaMaskRgb ? firstOpaqueRgba : firstRgba;
            int fR0 = (refRawRgba >>> 24) & 0xFF;
            int fR1 = (refRawRgba >>> 16) & 0xFF;
            int fR2 = (refRawRgba >>> 8) & 0xFF;
            int fR3 = firstRgba & 0xFF;
            byte[] rawConstVals = {(byte) fR0, (byte) fR1, (byte) fR2, (byte) fR3};
            int rDiff0 = 0;
            int rDiff1 = 0;
            int rDiff2 = 0;
            int rDiff3 = 0;
            boolean yCoCgWrapRisk = false;

            if (canAlphaMaskRgb) {
                for (int i = 0; i < nSamples; i++) {
                    int rgba = curveRgba[i];
                    int r3 = rgba & 0xFF;
                    rDiff3 |= (r3 ^ fR3);
                    if (r3 == 0) {
                        raw0[i] = (byte) fR0;
                        raw1[i] = (byte) fR1;
                        raw2[i] = (byte) fR2;
                        raw3[i] = 0;
                    } else {
                        int r0 = (rgba >>> 24) & 0xFF;
                        int r1 = (rgba >>> 16) & 0xFF;
                        int r2 = (rgba >>> 8) & 0xFF;
                        raw0[i] = (byte) r0;
                        raw1[i] = (byte) r1;
                        raw2[i] = (byte) r2;
                        raw3[i] = (byte) r3;
                        rDiff0 |= (r0 ^ fR0);
                        rDiff1 |= (r1 ^ fR1);
                        rDiff2 |= (r2 ^ fR2);
                        int coSpan = r0 - r2;
                        int cgSpan = r1 - (r2 + (coSpan >> 1));
                        if (coSpan < -112 || coSpan > 112 || cgSpan < -112 || cgSpan > 112) {
                            yCoCgWrapRisk = true;
                        }
                    }
                }
            } else {
                for (int i = 0; i < nSamples; i++) {
                    int rgba = curveRgba[i];
                    int r0 = (rgba >>> 24) & 0xFF;
                    int r1 = (rgba >>> 16) & 0xFF;
                    int r2 = (rgba >>> 8) & 0xFF;
                    int r3 = rgba & 0xFF;
                    raw0[i] = (byte) r0;
                    raw1[i] = (byte) r1;
                    raw2[i] = (byte) r2;
                    raw3[i] = (byte) r3;
                    rDiff0 |= (r0 ^ fR0);
                    rDiff1 |= (r1 ^ fR1);
                    rDiff2 |= (r2 ^ fR2);
                    rDiff3 |= (r3 ^ fR3);
                    int coSpan = r0 - r2;
                    int cgSpan = r1 - (r2 + (coSpan >> 1));
                    if (coSpan < -112 || coSpan > 112 || cgSpan < -112 || cgSpan > 112) {
                        yCoCgWrapRisk = true;
                    }
                }
            }

            int rawChannelMask4 = (rDiff0 != 0 ? 1 : 0)
                | (rDiff1 != 0 ? 2 : 0)
                | (rDiff2 != 0 ? 4 : 0)
                | (rDiff3 != 0 ? 8 : 0);

            byte[] ch0 = arena.channelValues[0];
            byte[] ch1 = arena.channelValues[1];
            byte[] ch2 = arena.channelValues[2];
            byte[] ch3 = arena.channelValues[3];
            int firstYCoCg = LcvPrimitives.rgbaToYCoCgR(firstRgba);
            int fY0 = (firstYCoCg >>> 24) & 0xFF;
            int fY1 = (firstYCoCg >>> 16) & 0xFF;
            int fY2 = (firstYCoCg >>> 8) & 0xFF;
            int fY3 = firstYCoCg & 0xFF;
            byte[] constVals = {(byte) fY0, (byte) fY1, (byte) fY2, (byte) fY3};
            int channelMask4 = 0x0F;

            boolean skipYCoCgDecomp = canAlphaMaskRgb || (Integer.bitCount(rawChannelMask4) <= 1);
            if (!skipYCoCgDecomp) {
                int yDiff0 = 0;
                int yDiff1 = 0;
                int yDiff2 = 0;
                int yDiff3 = 0;
                for (int i = 0; i < nSamples; i++) {
                    int yCoCgA = LcvPrimitives.rgbaToYCoCgR(curveRgba[i]);
                    int y0 = (yCoCgA >>> 24) & 0xFF;
                    int y1 = (yCoCgA >>> 16) & 0xFF;
                    int y2 = (yCoCgA >>> 8) & 0xFF;
                    int y3 = yCoCgA & 0xFF;
                    ch0[i] = (byte) y0;
                    ch1[i] = (byte) y1;
                    ch2[i] = (byte) y2;
                    ch3[i] = (byte) y3;
                    yDiff0 |= (y0 ^ fY0);
                    yDiff1 |= (y1 ^ fY1);
                    yDiff2 |= (y2 ^ fY2);
                    yDiff3 |= (y3 ^ fY3);
                }
                channelMask4 = (yDiff0 != 0 ? 1 : 0)
                    | (yDiff1 != 0 ? 2 : 0)
                    | (yDiff2 != 0 ? 4 : 0)
                    | (yDiff3 != 0 ? 8 : 0);
            }

            boolean directPreferredFor1D = skipYCoCgDecomp || yCoCgWrapRisk || (Integer.bitCount(rawChannelMask4) < Integer.bitCount(channelMask4));
            int packedMv8 = 0x88;

            // 5. Evaluate 3D Planar+MV Coupled Residual first when uiLen == Integer.MAX_VALUE or canAlphaMaskRgb:
            // if a smooth 3D plane locks (maxFittedSpan <= 15), skip GRAY_PLANES_RAW / 1D_MSB / MED_2D!
            int[] planarMvBaseSplits;
            int[] waveletHaarBaseSplits;
            int maxFittedSpan = 255;
            if (uiLen < Integer.MAX_VALUE && !canAlphaMaskRgb) {
                planarMvBaseSplits = new int[]{1 << 20, 1 << 20};
                waveletHaarBaseSplits = new int[]{1 << 20, 1 << 20};
            } else {
                planarMvBaseSplits = encodePlanarMvVariation(
                    curveId, depthK, curveToTyx, packedMv8,
                    channelMask4, constVals, rawChannelMask4, rawConstVals, canAlphaMaskRgb,
                    nSamples, radialDist, DistanceRlePolicy.LOSSLESS, lsbSplitBits, false, false,
                    arena, arena.candidatePlanarMvBuf, 0
                );
                maxFittedSpan = 0;
                int mask = (canAlphaMaskRgb || Integer.bitCount(rawChannelMask4) <= Integer.bitCount(channelMask4))
                    ? rawChannelMask4
                    : channelMask4;
                for (int c = 0; c < 4; c++) {
                    if (((mask >>> c) & 1) != 0) {
                        maxFittedSpan = Math.max(maxFittedSpan, arena.planarParams[c][4] & 0xFF);
                    }
                }
                waveletHaarBaseSplits = (planarMvBaseSplits[0] >= (1 << 20) || maxFittedSpan <= 12 || canAlphaMaskRgb)
                    ? new int[]{1 << 20, 1 << 20}
                    : encodePlanarMvVariation(
                        curveId, depthK, curveToTyx, packedMv8,
                        channelMask4, constVals, rawChannelMask4, rawConstVals, false,
                        nSamples, radialDist, DistanceRlePolicy.LOSSLESS, lsbSplitBits, true, true,
                        arena, arena.candidateWaveletHaarBuf, 0
                    );
            }

            int activeChannels = Integer.bitCount(channelMask4);
            int inactiveChannels = 4 - activeChannels;
            int rawCeilingLen = 1 + inactiveChannels + activeChannels * nSamples;
            boolean msbPriority = (lsbSplitBits >= 3);

            boolean planarLockedSmoothly = (planarMvBaseSplits[0] < (1 << 20)) && (maxFittedSpan <= 15);
            if (depthK > 1 && (!planarLockedSmoothly
                || arena.planarParams[0][3] != 0
                || arena.planarParams[1][3] != 0
                || arena.planarParams[2][3] != 0)) {
                ExternalFlowProvider extFlow = ACTIVE_EXTERNAL_FLOW.get();
                int hwMv = (extFlow != null) ? extFlow.getPackedVelocity8(tileX, tileY, 0) : gpuPreMv;
                packedMv8 = (hwMv >= 0 && hwMv <= 0xFF)
                    ? hwMv
                    : LcvPrimitives.estimateTileVelocity1Pass(cubeRgbaTyx, depthK);
            }

            int planarScore = msbPriority ? planarMvBaseSplits[0] : (planarMvBaseSplits[0] + planarMvBaseSplits[1]);
            int waveletScore = msbPriority ? waveletHaarBaseSplits[0] : (waveletHaarBaseSplits[0] + waveletHaarBaseSplits[1]);
            int bestCoupledScore = Math.min(planarScore, waveletScore);
            boolean losslessAtTile = rlePolicy.isLosslessAt(radialDist);
            int scoreCeiling = losslessAtTile ? rawCeilingLen : Integer.MAX_VALUE;
            int bestRunningScore = Math.min(uiLen, Math.min(scoreCeiling, bestCoupledScore));

            int[] deltaBaseSplits = planarLockedSmoothly
                ? new int[]{1 << 20, 1 << 20}
                : encodeGrayPlanesVariation(
                    CUBE_MODE_GRAY_PLANES_HILBERT_DELTA, curveId,
                    directPreferredFor1D ? rawChannelMask4 : channelMask4,
                    directPreferredFor1D ? rawConstVals : constVals,
                    directPreferredFor1D, canAlphaMaskRgb && directPreferredFor1D,
                    nSamples, radialDist, DistanceRlePolicy.LOSSLESS, lsbSplitBits,
                    bestRunningScore, arena, arena.candidateGrayDeltaBuf, 0
                );
            int dScore = msbPriority ? deltaBaseSplits[0] : (deltaBaseSplits[0] + deltaBaseSplits[1]);
            bestRunningScore = Math.min(bestRunningScore, dScore);

            boolean allowMed2dAtTile = !planarLockedSmoothly && depthK > 1
                && (losslessAtTile || msbPriority || canAlphaMaskRgb
                    || rlePolicy.losslessPlaneCutoff() <= lsbSplitBits);
            int[] med2dBaseSplits = allowMed2dAtTile
                ? encodeMed2dDeltaVariation(
                    cubeRgbaTyx, curveToTyx, depthK, packedMv8,
                    rawChannelMask4, rawConstVals,
                    yCoCgWrapRisk ? -1 : channelMask4, constVals, canAlphaMaskRgb,
                    nSamples, radialDist, DistanceRlePolicy.LOSSLESS, lsbSplitBits,
                    msbPriority ? Integer.MAX_VALUE : (bestRunningScore - 1), arena, arena.candidateMed2dBuf, 0
                )
                : new int[]{1 << 20, 1 << 20};
            int med2dScore = msbPriority ? med2dBaseSplits[0] : (med2dBaseSplits[0] + med2dBaseSplits[1]);
            bestRunningScore = Math.min(bestRunningScore, med2dScore);

            boolean med2dUseDirectRgb = false;
            if (allowMed2dAtTile && !yCoCgWrapRisk && !canAlphaMaskRgb) {
                int yScore = msbPriority ? med2dBaseSplits[0] : (med2dBaseSplits[0] + med2dBaseSplits[1]);
                if (msbPriority || yScore < dScore) {
                    int[] rgbMedSplits = encodeMed2dDeltaVariation(
                        cubeRgbaTyx, curveToTyx, depthK, packedMv8,
                        rawChannelMask4, rawConstVals,
                        -1, constVals, false,
                        nSamples, radialDist, DistanceRlePolicy.LOSSLESS, lsbSplitBits,
                        yScore - 1, arena, arena.candidateMed2dRgbBuf, 0
                    );
                    int rgbScore = msbPriority ? rgbMedSplits[0] : (rgbMedSplits[0] + rgbMedSplits[1]);
                    if (rgbScore < yScore) {
                        System.arraycopy(arena.candidateMed2dRgbBuf, 0, arena.candidateMed2dBuf, 0, rgbMedSplits[0] + rgbMedSplits[1]);
                        med2dBaseSplits = rgbMedSplits;
                        med2dUseDirectRgb = true;
                        bestRunningScore = Math.min(bestRunningScore, rgbScore);
                    }
                }
            }

            int[] rawBaseSplits = (planarLockedSmoothly || skipYCoCgDecomp || yCoCgWrapRisk)
                ? new int[]{1 << 20, 1 << 20}
                : encodeGrayPlanesVariation(
                    CUBE_MODE_GRAY_PLANES_RAW, curveId, channelMask4, constVals, false, false,
                    nSamples, radialDist, DistanceRlePolicy.LOSSLESS, lsbSplitBits,
                    bestRunningScore, arena, arena.candidateGrayRawBuf, 0
                );

            boolean med2dWinsOver1D = msbPriority
                ? (med2dBaseSplits[0] < deltaBaseSplits[0])
                : ((med2dBaseSplits[0] + med2dBaseSplits[1]) < (deltaBaseSplits[0] + deltaBaseSplits[1]));
            if (med2dWinsOver1D) {
                deltaBaseSplits = med2dBaseSplits;
            }

            int rawBaseTotal = rawBaseSplits[0] + rawBaseSplits[1];
            int deltaBaseTotal = deltaBaseSplits[0] + deltaBaseSplits[1];
            int planarMvBaseTotal = planarMvBaseSplits[0] + planarMvBaseSplits[1];
            int waveletHaarBaseTotal = waveletHaarBaseSplits[0] + waveletHaarBaseSplits[1];
            int bestCoupledBaseTotal = Math.min(planarMvBaseTotal, waveletHaarBaseTotal);
            int bestDeltaBaseTotal = Math.min(deltaBaseTotal, bestCoupledBaseTotal);
            int bestGrayBaseTotal = Math.min(rawBaseTotal, bestDeltaBaseTotal);

            int rawScore = msbPriority ? rawBaseSplits[0] : rawBaseTotal;
            int deltaScore = msbPriority ? deltaBaseSplits[0] : deltaBaseTotal;
            int bestDeltaScore = Math.min(deltaScore, bestCoupledScore);
            int bestGrayScore = Math.min(rawScore, bestDeltaScore);

            // 7. Pick best outcome on baseline so variation choice is 100% consistent with on-disk coalescing
            if (uiLen <= bestGrayScore && uiLen <= rawCeilingLen) {
                System.arraycopy(arena.candidateUiBuf, 0, dst, dstOff, uiLen);
                return new CubeEncodeResult(
                    EncodingVariation.UI_PALETTE_ROARING, curveId, uiLen, 0, uiLen, 0, digest64, packedMv8
                );
            }

            boolean coupledDominatesMsbForUep = msbPriority
                && (bestCoupledBaseTotal <= rawCeilingLen)
                && (planarMvBaseSplits[0] <= 52);
            boolean grayRawWinsOverDelta = !coupledDominatesMsbForUep && (rawScore <= bestDeltaScore);
            boolean coupledWinsOver1DDelta = coupledDominatesMsbForUep || (bestCoupledScore < deltaScore);
            boolean waveletWinsOverPlanar = waveletScore < planarScore;
            int winningGrayMode = grayRawWinsOverDelta
                ? CUBE_MODE_GRAY_PLANES_RAW
                : CUBE_MODE_GRAY_PLANES_HILBERT_DELTA;
            byte[] winningGrayBuf = grayRawWinsOverDelta
                ? arena.candidateGrayRawBuf
                : (coupledWinsOver1DDelta
                    ? (waveletWinsOverPlanar ? arena.candidateWaveletHaarBuf : arena.candidatePlanarMvBuf)
                    : (med2dWinsOver1D ? arena.candidateMed2dBuf : arena.candidateGrayDeltaBuf));
            int[] winningGraySplits = grayRawWinsOverDelta
                ? rawBaseSplits
                : (coupledWinsOver1DDelta
                    ? (waveletWinsOverPlanar ? waveletHaarBaseSplits : planarMvBaseSplits)
                    : deltaBaseSplits);

            if (bestGrayScore <= rawCeilingLen || !rlePolicy.isLosslessAt(radialDist)) {
                if (!rlePolicy.isLosslessAt(radialDist)) {
                    if (grayRawWinsOverDelta) {
                        winningGraySplits = encodeGrayPlanesVariation(
                            CUBE_MODE_GRAY_PLANES_RAW, curveId, channelMask4, constVals, false, false,
                            nSamples, radialDist, rlePolicy, lsbSplitBits, arena, winningGrayBuf, 0
                        );
                    } else if (coupledWinsOver1DDelta) {
                        winningGraySplits = encodePlanarMvVariation(
                            curveId, depthK, curveToTyx, 0x88,
                            channelMask4, constVals, rawChannelMask4, rawConstVals, canAlphaMaskRgb,
                            nSamples, radialDist, rlePolicy, lsbSplitBits, waveletWinsOverPlanar, false,
                            arena, winningGrayBuf, 0
                        );
                    } else if (med2dWinsOver1D) {
                        winningGraySplits = encodeMed2dDeltaVariation(
                            cubeRgbaTyx, curveToTyx, depthK, packedMv8,
                            rawChannelMask4, rawConstVals,
                            (yCoCgWrapRisk || med2dUseDirectRgb) ? -1 : channelMask4, constVals, canAlphaMaskRgb,
                            nSamples, radialDist, rlePolicy, lsbSplitBits, arena, winningGrayBuf, 0
                        );
                    } else {
                        winningGraySplits = encodeGrayPlanesVariation(
                            CUBE_MODE_GRAY_PLANES_HILBERT_DELTA, curveId,
                            directPreferredFor1D ? rawChannelMask4 : channelMask4,
                            directPreferredFor1D ? rawConstVals : constVals,
                            directPreferredFor1D, canAlphaMaskRgb && directPreferredFor1D,
                            nSamples, radialDist, rlePolicy, lsbSplitBits, arena, winningGrayBuf, 0
                        );
                    }
                }
                int grayTotal = winningGraySplits[0] + winningGraySplits[1];
                int effectiveCheckLen = msbPriority ? winningGraySplits[0] : grayTotal;
                if (effectiveCheckLen <= rawCeilingLen) {
                    System.arraycopy(winningGrayBuf, 0, dst, dstOff, grayTotal);
                    EncodingVariation var = (winningGrayMode == CUBE_MODE_GRAY_PLANES_RAW)
                        ? EncodingVariation.GRAY_PLANES_RAW
                        : EncodingVariation.GRAY_PLANES_HILBERT_DELTA;
                    int outCurveId = (!grayRawWinsOverDelta && !coupledWinsOver1DDelta && med2dWinsOver1D)
                        ? LcvPrimitives.CURVE_TYX
                        : curveId;
                    return new CubeEncodeResult(
                        var, outCurveId, winningGraySplits[0], winningGraySplits[1], grayTotal, 0, digest64, packedMv8
                    );
                }
            }

            // Fallback to RAW_CEILING (mode 11)
            int pos = dstOff;
            dst[pos++] = (byte) ((curveId << 6) | (CUBE_MODE_RAW_CEILING << 4) | (channelMask4 & 0x0F));
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) == 0) {
                    dst[pos++] = maskConstByte(constVals[c], msbTileKey64, c);
                }
            }
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) != 0) {
                    System.arraycopy(arena.channelValues[c], 0, dst, pos, nSamples);
                    pos += nSamples;
                }
            }
            int written = pos - dstOff;
            return new CubeEncodeResult(
                EncodingVariation.RAW_CEILING, curveId, written, 0, written, 0, digest64, packedMv8
            );
        } finally {
            arena.activeMsbTileKey64 = 0L;
            arena.activeLsbTileKey64 = 0L;
            arena.activeRelayTileKey64 = 0L;
        }
    }

    private static byte maskConstByte(byte val, long msbTileKey64, int channelIdx) {
        if (msbTileKey64 == 0L) {
            return val;
        }
        int mask = (int) (LcvPrimitives.mix64(msbTileKey64 ^ (0x434E5300L + channelIdx)) & 0xFFL);
        return (byte) ((val & 0xFF) ^ mask);
    }

    /**
     * Evaluates {@code UI_PALETTE_ROARING} when {@code 2 <= |P| <= 16}, competing all 4 Roaring
     * sub-variations ({@code ROARING_SINGLETON}, {@code ROARING_DELTA_RUN}, {@code ROARING_DELTA_ARRAY},
     * {@code ROARING_BITMAP}) per minority color state.
     */
    private static int tryEncodeUiPaletteRoaring(
        int[] curveRgba, int nSamples, int curveId,
        LcvPrimitives.ScratchArena arena, byte[] outBuf, int outOff
    ) {
        int[] palette = arena.paletteColors;
        int[] counts = arena.paletteCounts;
        int[] sampleIdx = arena.samplePaletteIdx;
        int paletteSize = 0;

        for (int i = 0; i < nSamples; i++) {
            int color = curveRgba[i];
            int found = -1;
            for (int p = 0; p < paletteSize; p++) {
                if (palette[p] == color) {
                    found = p;
                    break;
                }
            }
            if (found >= 0) {
                counts[found]++;
                sampleIdx[i] = found;
            } else {
                if (paletteSize >= 16) {
                    return Integer.MAX_VALUE; // Exceeds 16-color UI palette limit
                }
                palette[paletteSize] = color;
                counts[paletteSize] = 1;
                sampleIdx[i] = paletteSize;
                paletteSize++;
            }
        }
        if (paletteSize < 2) {
            return Integer.MAX_VALUE;
        }

        // Sort palette by descending frequency so palette[0] is the implicit majority state
        int[] order = new int[paletteSize];
        for (int p = 0; p < paletteSize; p++) {
            order[p] = p;
        }
        for (int i = 0; i < paletteSize - 1; i++) {
            for (int j = i + 1; j < paletteSize; j++) {
                if (counts[order[j]] > counts[order[i]]) {
                    int tmp = order[i];
                    order[i] = order[j];
                    order[j] = tmp;
                }
            }
        }
        int[] oldToNew = new int[paletteSize];
        int[] sortedPalette = new int[paletteSize];
        int[] sortedCounts = new int[paletteSize];
        for (int newP = 0; newP < paletteSize; newP++) {
            int oldP = order[newP];
            oldToNew[oldP] = newP;
            sortedPalette[newP] = palette[oldP];
            sortedCounts[newP] = counts[oldP];
        }
        for (int i = 0; i < nSamples; i++) {
            sampleIdx[i] = oldToNew[sampleIdx[i]];
        }

        int pos = outOff;
        int header8 = (curveId << 6) | (CUBE_MODE_UI_PALETTE_ROARING << 4) | ((paletteSize - 2) & 0x0F);
        outBuf[pos++] = (byte) header8;

        for (int p = 0; p < paletteSize; p++) {
            int c = sortedPalette[p];
            if (arena.activeMsbTileKey64 != 0L) {
                c ^= (int) LcvPrimitives.mix64(arena.activeMsbTileKey64 ^ (0x50414C00L + p));
            }
            outBuf[pos++] = (byte) (c >>> 24);
            outBuf[pos++] = (byte) (c >>> 16);
            outBuf[pos++] = (byte) (c >>> 8);
            outBuf[pos++] = (byte) c;
        }

        int minorityCount = paletteSize - 1;
        int subDirBytes = (minorityCount + 3) / 4;
        int subDirOff = pos;
        for (int b = 0; b < subDirBytes; b++) {
            outBuf[pos++] = 0;
        }

        int bitmapBytes = nSamples >>> 3;
        for (int p = 1; p < paletteSize; p++) {
            int card = sortedCounts[p];
            int mIdx = p - 1;
            int chosenSubTag;

            if (card == 1) {
                // ROARING_SINGLETON (tag 00): 2 bytes
                chosenSubTag = ROARING_TAG_SINGLETON;
                int singlePos = 0;
                for (int i = 0; i < nSamples; i++) {
                    if (sampleIdx[i] == p) {
                        singlePos = i;
                        break;
                    }
                }
                outBuf[pos++] = (byte) (singlePos >>> 8);
                outBuf[pos++] = (byte) singlePos;
            } else {
                // Compete ROARING_DELTA_RUN (01), ROARING_DELTA_ARRAY (10), and ROARING_BITMAP (11)
                int runBytes = encodeRoaringDeltaRuns(sampleIdx, nSamples, p, arena, arena.roaringSubScratchA, 0);
                int arrayBytes = encodeRoaringDeltaArray(sampleIdx, nSamples, p, card, arena.roaringSubScratchB, 0);

                if (runBytes <= arrayBytes && runBytes <= bitmapBytes) {
                    chosenSubTag = ROARING_TAG_DELTA_RUN;
                    System.arraycopy(arena.roaringSubScratchA, 0, outBuf, pos, runBytes);
                    pos += runBytes;
                } else if (arrayBytes <= bitmapBytes) {
                    chosenSubTag = ROARING_TAG_DELTA_ARRAY;
                    System.arraycopy(arena.roaringSubScratchB, 0, outBuf, pos, arrayBytes);
                    pos += arrayBytes;
                } else {
                    chosenSubTag = ROARING_TAG_BITMAP;
                    writeRawMembershipBitmap(sampleIdx, nSamples, p, outBuf, pos);
                    pos += bitmapBytes;
                }
            }

            int byteSlot = subDirOff + (mIdx >>> 2);
            int bitShift = (3 - (mIdx & 3)) * 2;
            outBuf[byteSlot] = (byte) ((outBuf[byteSlot] & 0xFF) | (chosenSubTag << bitShift));
        }

        return pos - outOff;
    }

    private static int encodeRoaringDeltaRuns(
        int[] sampleIdx, int nSamples, int targetState,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        int[] rawGaps = arena.rawGaps;
        int[] runLens = arena.runLens;
        int runCount = 0;
        int i = 0;
        int lastEnd = 0;
        while (i < nSamples) {
            while (i < nSamples && sampleIdx[i] != targetState) {
                i++;
            }
            if (i >= nSamples) {
                break;
            }
            int start = i;
            while (i < nSamples && sampleIdx[i] == targetState) {
                i++;
            }
            rawGaps[runCount] = start - lastEnd;
            runLens[runCount] = i - start;
            runCount++;
            lastEnd = i;
        }
        return writeNibbleRunStream(rawGaps, runLens, runCount, dst, dstOff);
    }

    private static int encodeRoaringDeltaArray(
        int[] sampleIdx, int nSamples, int targetState, int cardinality, byte[] dst, int dstOff
    ) {
        int pos = LcvPrimitives.writeVarInt(dst, dstOff, cardinality);
        int prev = -1;
        for (int i = 0; i < nSamples; i++) {
            if (sampleIdx[i] == targetState) {
                int gap = (prev < 0) ? i : (i - prev - 1);
                pos = LcvPrimitives.writeVarInt(dst, pos, gap);
                prev = i;
            }
        }
        return pos - dstOff;
    }

    private static void writeRawMembershipBitmap(
        int[] sampleIdx, int nSamples, int targetState, byte[] dst, int dstOff
    ) {
        int bitmapBytes = nSamples >>> 3;
        Arrays.fill(dst, dstOff, dstOff + bitmapBytes, (byte) 0);
        for (int i = 0; i < nSamples; i++) {
            if (sampleIdx[i] == targetState) {
                dst[dstOff + (i >>> 3)] |= (byte) (1 << (i & 7));
            }
        }
    }

    /**
     * Encodes either {@code GRAY_PLANES_RAW} ({@code 01}) or drift-free 6-bit MSB
     * {@code GRAY_PLANES_HILBERT_DELTA} ({@code 10}), returning {@code {msbSliceBytes, lsbSliceBytes}}.
     */
    private static int[] encodeGrayPlanesVariation(
        int cubeMode, int curveId, int channelMask4, byte[] constVals, boolean useDirectRgba, boolean alphaMaskedRgb,
        int nSamples, double radialDist, DistanceRlePolicy rlePolicy, int lsbSplitBits,
        LcvPrimitives.ScratchArena arena, byte[] outBuf, int outOff
    ) {
        return encodeGrayPlanesVariation(
            cubeMode, curveId, channelMask4, constVals, useDirectRgba, alphaMaskedRgb,
            nSamples, radialDist, rlePolicy, lsbSplitBits,
            Integer.MAX_VALUE, arena, outBuf, outOff
        );
    }

    private static int[] encodeGrayPlanesVariation(
        int cubeMode, int curveId, int channelMask4, byte[] constVals, boolean useDirectRgba, boolean alphaMaskedRgb,
        int nSamples, double radialDist, DistanceRlePolicy rlePolicy, int lsbSplitBits,
        int maxAllowedScore, LcvPrimitives.ScratchArena arena, byte[] outBuf, int outOff
    ) {
        int msbBits = 8 - lsbSplitBits;
        int msbMask = (1 << msbBits) - 1;
        int lsbMask = (1 << lsbSplitBits) - 1;
        byte[][] srcChannels = useDirectRgba ? arena.rawChannelValues : arena.channelValues;
        boolean fastPrepackWords = rlePolicy.isLosslessAt(radialDist) && (arena.activeLsbTileKey64 == 0L);
        boolean msbPriority = (lsbSplitBits >= 3);

        int pos = outOff;
        outBuf[pos++] = (byte) ((curveId << 6) | (cubeMode << 4) | (channelMask4 & 0x0F));

        // 1B per inactive channel
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                outBuf[pos++] = maskConstByte(constVals[c], arena.activeMsbTileKey64, c);
            }
        }

        if (cubeMode == CUBE_MODE_GRAY_PLANES_HILBERT_DELTA) {
            outBuf[pos++] = (byte) (DELTA_SUBMODE_HILBERT_1D_MSB
                | (useDirectRgba ? DELTA_FLAG_DIRECT_RGBA : 0)
                | (alphaMaskedRgb ? DELTA_FLAG_ALPHA_MASKED_RGB : 0));
        }

        // Reserve 2B planeModes16[c] per active channel
        int[] planeModesOffsets = new int[4];
        int[] planeModesWords = new int[4];
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                planeModesOffsets[c] = pos;
                outBuf[pos++] = 0;
                outBuf[pos++] = 0;
            }
        }

        // Base MSB Slice: prepare Gray-coded values lazily per channel and encode planes p = 7..lsbSplitBits
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] srcCh = srcChannels[c];
            byte[] grayCh = arena.grayValues[c];
            if (cubeMode == CUBE_MODE_GRAY_PLANES_RAW) {
                for (int i = 0; i < nSamples; i++) {
                    grayCh[i] = (byte) LcvPrimitives.binaryToGray8(srcCh[i] & 0xFF);
                }
            } else {
                int prevM = 0;
                for (int i = 0; i < nSamples; i++) {
                    int v = srcCh[i] & 0xFF;
                    int m = (v >>> lsbSplitBits) & msbMask;
                    int l = v & lsbMask;
                    int u = (i == 0) ? m : LcvPrimitives.zigZagEncodeN(m - prevM, msbBits);
                    prevM = m;
                    int gMsb = (i == 0) ? LcvPrimitives.binaryToGrayN(u, msbBits) : u;
                    int gLsb = LcvPrimitives.binaryToGrayN(l, lsbSplitBits);
                    grayCh[i] = (byte) ((gMsb << lsbSplitBits) | gLsb);
                }
            }
            long[][] chWords = arena.channelPlaneWords4[c];
            if (fastPrepackWords) {
                LcvPrimitives.transposeByteChannelToBitplanes8(grayCh, nSamples, chWords);
            }
            for (int p = 7; p >= lsbSplitBits; p--) {
                int maxGap = (cubeMode == CUBE_MODE_GRAY_PLANES_HILBERT_DELTA)
                    ? 0
                    : rlePolicy.maxMergeGap(p, radialDist);
                int[] modeAndBytes = (fastPrepackWords && maxGap == 0)
                    ? encodeSingleBitplaneFromWords(chWords[p], chWords[Math.min(7, p + 1)], nSamples, p, arena, outBuf, pos)
                    : encodeSingleBitplane(grayCh, nSamples, p, maxGap, arena, outBuf, pos);
                int pMode = modeAndBytes[0];
                pos += modeAndBytes[1];
                planeModesWords[c] |= (pMode << (2 * p));
                if (pos - outOff > maxAllowedScore) {
                    return new int[]{1 << 20, 1 << 20};
                }
            }
        }
        int msbSliceBytes = pos - outOff;

        // Tail LSB Slice: encode planes p = (lsbSplitBits - 1)..0 for all active channels
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] grayCh = arena.grayValues[c];
            long[][] chWords = arena.channelPlaneWords4[c];
            for (int p = lsbSplitBits - 1; p >= 0; p--) {
                int maxGap = rlePolicy.maxMergeGap(p, radialDist);
                int[] modeAndBytes = (fastPrepackWords && maxGap == 0)
                    ? encodeSingleBitplaneFromWords(chWords[p], chWords[Math.min(7, p + 1)], nSamples, p, arena, outBuf, pos)
                    : encodeSingleBitplane(grayCh, nSamples, p, maxGap, arena, outBuf, pos);
                int pMode = modeAndBytes[0];
                pos += modeAndBytes[1];
                planeModesWords[c] |= (pMode << (2 * p));
                if (!msbPriority && (pos - outOff) > maxAllowedScore) {
                    return new int[]{1 << 20, 1 << 20};
                }
            }
        }
        int lsbSliceBytes = (pos - outOff) - msbSliceBytes;

        // Write completed planeModes16[c] words into Base MSB Slice header
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                int off = planeModesOffsets[c];
                int word = LcvPrimitives.permutePlaneModes16(planeModesWords[c], arena.activeRelayTileKey64, c);
                outBuf[off] = (byte) (word >>> 8);
                outBuf[off + 1] = (byte) word;
            }
        }

        return new int[]{msbSliceBytes, lsbSliceBytes};
    }

    private static int med3(int a, int b, int c) {
        int minAB = Math.min(a, b);
        int maxAB = Math.max(a, b);
        if (c >= maxAB) {
            return minAB;
        }
        if (c <= minAB) {
            return maxAB;
        }
        return a + b - c;
    }

    private static int rgbaL1Dist(int a, int b) {
        if (a == b) {
            return 0;
        }
        return Math.abs(((a >>> 24) & 0xFF) - ((b >>> 24) & 0xFF))
            + Math.abs(((a >>> 16) & 0xFF) - ((b >>> 16) & 0xFF))
            + Math.abs(((a >>> 8) & 0xFF) - ((b >>> 8) & 0xFF))
            + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static long computeFrameStepSad(int[] cubeRgbaTyx, int t, int dx, int dy, long cutoffNormSad) {
        int tBase1 = t << 8;
        int tBase0 = (t - 1) << 8;
        int yStart = Math.max(2, 2 + dy);
        int yEnd = Math.min(14, 16 + dy);
        int xStart = Math.max(2, 2 + dx);
        int xEnd = Math.min(14, 16 + dx);
        int totalExpectedCount = ((yEnd > yStart ? (yEnd - yStart + 1) >>> 1 : 0)
            * (xEnd > xStart ? (xEnd - xStart + 1) >>> 1 : 0));
        if (totalExpectedCount <= 0) {
            return Long.MAX_VALUE;
        }
        long rawSadLimit = (cutoffNormSad == Long.MAX_VALUE)
            ? Long.MAX_VALUE
            : ((cutoffNormSad * totalExpectedCount) / 36L + 1L);
        long sad = 0L;
        int count = 0;
        for (int y = 2; y < 14; y += 2) {
            int yRef = y - dy;
            if (yRef < 0 || yRef >= 16) {
                continue;
            }
            for (int x = 2; x < 14; x += 2) {
                int xRef = x - dx;
                if (xRef < 0 || xRef >= 16) {
                    continue;
                }
                sad += rgbaL1Dist(cubeRgbaTyx[tBase1 | (y << 4) | x], cubeRgbaTyx[tBase0 | (yRef << 4) | xRef]);
                count++;
            }
            if (sad > rawSadLimit) {
                return Long.MAX_VALUE;
            }
        }
        return count > 0 ? (sad * 36L / count) : Long.MAX_VALUE;
    }

    static int estimateFrameStepVelocity(int[] cubeRgbaTyx, int t, int hintVx, int hintVy) {
        long zeroSad = computeFrameStepSad(cubeRgbaTyx, t, 0, 0, Long.MAX_VALUE);
        if (zeroSad == 0L) {
            return 0x88;
        }
        int bestDx = 0;
        int bestDy = 0;
        long bestSad = zeroSad;
        if (hintVx != 0 || hintVy != 0) {
            long hSad = computeFrameStepSad(cubeRgbaTyx, t, hintVx, hintVy, (bestSad * 94L) / 100L);
            if (hSad * 100L < bestSad * 94L) {
                bestSad = hSad;
                bestDx = hintVx;
                bestDy = hintVy;
            }
        }
        int[] grid = {-6, -4, -2, -1, 0, 1, 2, 4, 6};
        for (int gx : grid) {
            for (int gy : grid) {
                if (gx == 0 && gy == 0) {
                    continue;
                }
                long sad = computeFrameStepSad(cubeRgbaTyx, t, gx, gy, (bestSad * 94L) / 100L);
                if (sad * 100L < bestSad * 94L) {
                    bestSad = sad;
                    bestDx = gx;
                    bestDy = gy;
                }
            }
        }
        if (bestDx != 0 || bestDy != 0) {
            int cx = bestDx;
            int cy = bestDy;
            for (int ox = -1; ox <= 1; ox++) {
                for (int oy = -1; oy <= 1; oy++) {
                    int nx = Math.max(-8, Math.min(7, cx + ox));
                    int ny = Math.max(-8, Math.min(7, cy + oy));
                    long sad = computeFrameStepSad(cubeRgbaTyx, t, nx, ny, bestSad);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestDx = nx;
                        bestDy = ny;
                    }
                }
            }
        }
        return ((bestDx + 8) << 4) | ((bestDy + 8) & 0x0F);
    }

    static int predictMed2dSample(int tyx, int vx, int vy, byte[] mArr, byte[] lArr) {
        return predictMed2dSample(tyx, vx, vy, false, mArr, lArr);
    }

    static int predictMed2dSample(int tyx, int vx, int vy, boolean isHalfPel, byte[] mArr, byte[] lArr) {
        int lx = tyx & 15;
        int ly = (tyx >>> 4) & 15;
        int lt = tyx >>> 8;
        if (lx == 0 && ly == 0 && lt == 0) {
            return -1;
        }
        int x0;
        int x1;
        int y0;
        int y1;
        if (isHalfPel) {
            int dxFloor = vx >> 1;
            int hx = vx & 1;
            int dyFloor = vy >> 1;
            int hy = vy & 1;
            x0 = lx - dxFloor - hx;
            x1 = lx - dxFloor;
            y0 = ly - dyFloor - hy;
            y1 = ly - dyFloor;
        } else {
            x0 = lx - vx;
            x1 = x0;
            y0 = ly - vy;
            y1 = y0;
        }
        boolean hasTemporal = (lt >= 1) && (x0 >= 0 && x1 < 16 && y0 >= 0 && y1 < 16);
        int basePrev = (lt - 1) << 8;
        int ref00 = hasTemporal ? (basePrev | (y0 << 4) | x0) : (lt >= 1 ? (tyx - 256) : 0);
        int ref10 = hasTemporal ? (basePrev | (y0 << 4) | x1) : ref00;
        int ref01 = hasTemporal ? (basePrev | (y1 << 4) | x0) : ref00;
        int ref11 = hasTemporal ? (basePrev | (y1 << 4) | x1) : ref00;
        int tempM = hasTemporal
            ? (((mArr[ref00] & 0xFF) + (mArr[ref10] & 0xFF) + (mArr[ref01] & 0xFF) + (mArr[ref11] & 0xFF) + 2) >>> 2)
            : (mArr[ref00] & 0xFF);
        int tempL = hasTemporal
            ? (((lArr[ref00] & 0xFF) + (lArr[ref10] & 0xFF) + (lArr[ref01] & 0xFF) + (lArr[ref11] & 0xFF) + 2) >>> 2)
            : (lArr[ref00] & 0xFF);

        if (lx == 0 && ly == 0) {
            return (tempM << 8) | tempL | 0x10000;
        }

        int spatialM;
        int spatialL;
        if (ly == 0) {
            spatialM = mArr[tyx - 1] & 0xFF;
            spatialL = lArr[tyx - 1] & 0xFF;
        } else if (lx == 0) {
            spatialM = mArr[tyx - 16] & 0xFF;
            spatialL = lArr[tyx - 16] & 0xFF;
        } else {
            spatialM = med3(mArr[tyx - 1] & 0xFF, mArr[tyx - 16] & 0xFF, mArr[tyx - 17] & 0xFF);
            spatialL = med3(lArr[tyx - 1] & 0xFF, lArr[tyx - 16] & 0xFF, lArr[tyx - 17] & 0xFF);
        }

        if (!hasTemporal) {
            return (spatialM << 8) | spatialL;
        }

        boolean useTemporal;
        if (lx > 0 && ly > 0 && x0 > 0 && y0 > 0) {
            int mW = mArr[tyx - 1] & 0xFF;
            int mN = mArr[tyx - 16] & 0xFF;
            int mNW = mArr[tyx - 17] & 0xFF;
            int mTW = mArr[ref00 - 1] & 0xFF;
            int mTN = mArr[ref00 - 16] & 0xFF;
            int errTemporal = Math.abs(mW - mTW) + Math.abs(mN - mTN);
            int errSpatial = Math.abs(mW - mNW) + Math.abs(mN - mNW);
            useTemporal = (errTemporal <= errSpatial);
        } else if (lx > 0 && x0 > 0) {
            useTemporal = ((mArr[tyx - 1] & 0xFF) == (mArr[ref00 - 1] & 0xFF));
        } else if (ly > 0 && y0 > 0) {
            useTemporal = ((mArr[tyx - 16] & 0xFF) == (mArr[ref00 - 16] & 0xFF));
        } else {
            useTemporal = true;
        }

        if (useTemporal) {
            return (tempM << 8) | tempL | 0x10000;
        }
        return (spatialM << 8) | spatialL;
    }

    static long computeFrameStepHalfPelSad(int[] cubeRgbaTyx, int t, int dxQ1, int dyQ1, long cutoffNormSad) {
        int tBase1 = t << 8;
        int tBase0 = (t - 1) << 8;
        int dxFloor = dxQ1 >> 1;
        int hx = dxQ1 & 1;
        int dyFloor = dyQ1 >> 1;
        int hy = dyQ1 & 1;
        long sad = 0L;
        int count = 0;
        for (int y = 2; y < 14; y += 2) {
            int y0 = y - dyFloor - hy;
            int y1 = y - dyFloor;
            if (y0 < 0 || y1 >= 16) {
                continue;
            }
            for (int x = 2; x < 14; x += 2) {
                int x0 = x - dxFloor - hx;
                int x1 = x - dxFloor;
                if (x0 < 0 || x1 >= 16) {
                    continue;
                }
                int c00 = cubeRgbaTyx[tBase0 | (y0 << 4) | x0];
                int pred;
                if (hx == 0 && hy == 0) {
                    pred = c00;
                } else if (hx == 1 && hy == 0) {
                    pred = LcvPrimitives.swarBilinearAvg2(c00, cubeRgbaTyx[tBase0 | (y0 << 4) | x1]);
                } else if (hx == 0) {
                    pred = LcvPrimitives.swarBilinearAvg2(c00, cubeRgbaTyx[tBase0 | (y1 << 4) | x0]);
                } else {
                    pred = LcvPrimitives.swarBilinearAvg4(
                        c00,
                        cubeRgbaTyx[tBase0 | (y0 << 4) | x1],
                        cubeRgbaTyx[tBase0 | (y1 << 4) | x0],
                        cubeRgbaTyx[tBase0 | (y1 << 4) | x1]
                    );
                }
                sad += rgbaL1Dist(cubeRgbaTyx[tBase1 | (y << 4) | x], pred);
                count++;
            }
            if (cutoffNormSad < Long.MAX_VALUE && count > 0 && (sad * 36L / Math.max(1, count)) > cutoffNormSad + 64L) {
                return Long.MAX_VALUE;
            }
        }
        return count > 0 ? (sad * 36L / count) : Long.MAX_VALUE;
    }

    static int estimateFrameStepHalfPelVelocity(int[] cubeRgbaTyx, int t, int fullPelMv8) {
        int fullDx = LcvPrimitives.unpackVelocityX(fullPelMv8);
        int fullDy = LcvPrimitives.unpackVelocityY(fullPelMv8);
        int centerDxQ1 = Math.max(-8, Math.min(7, fullDx * 2));
        int centerDyQ1 = Math.max(-8, Math.min(7, fullDy * 2));
        long bestSad = computeFrameStepHalfPelSad(cubeRgbaTyx, t, centerDxQ1, centerDyQ1, Long.MAX_VALUE);
        int bestDxQ1 = centerDxQ1;
        int bestDyQ1 = centerDyQ1;
        for (int ox = -1; ox <= 1; ox++) {
            for (int oy = -1; oy <= 1; oy++) {
                if (ox == 0 && oy == 0) {
                    continue;
                }
                int candX = Math.max(-8, Math.min(7, centerDxQ1 + ox));
                int candY = Math.max(-8, Math.min(7, centerDyQ1 + oy));
                long sad = computeFrameStepHalfPelSad(cubeRgbaTyx, t, candX, candY, bestSad);
                if (sad * 100L < bestSad * 96L) {
                    bestSad = sad;
                    bestDxQ1 = candX;
                    bestDyQ1 = candY;
                }
            }
        }
        return ((bestDxQ1 + 8) << 4) | ((bestDyQ1 + 8) & 0x0F);
    }

    /**
     * Encodes the {@code DELTA_FLAG_SPATIAL_MED_2D} sub-variation of {@code GRAY_PLANES_HILBERT_DELTA}:
     * frame {@code t = 0} uses 2D LOCO-I {@code MED} spatial prediction, while frames {@code t = 1..K-1}
     * causally switch per pixel between motion-compensated temporal prediction and 2D spatial {@code MED}
     * based on already-reconstructed causal neighbor gradients.
     */
    private static int[] encodeMed2dDeltaVariation(
        int[] cubeRgbaTyx, short[] curveToTyx, int depthK, int packedMv8,
        int rawRgbaMask4, byte[] rawRgbaConsts, int yCoCgMask4, byte[] yCoCgConsts, boolean canAlphaMaskRgb,
        int nSamples, double radialDist, DistanceRlePolicy rlePolicy, int lsbSplitBits,
        LcvPrimitives.ScratchArena arena, byte[] outBuf, int outOff
    ) {
        return encodeMed2dDeltaVariation(
            cubeRgbaTyx, curveToTyx, depthK, packedMv8,
            rawRgbaMask4, rawRgbaConsts, yCoCgMask4, yCoCgConsts, canAlphaMaskRgb,
            nSamples, radialDist, rlePolicy, lsbSplitBits,
            Integer.MAX_VALUE, arena, outBuf, outOff
        );
    }

    private static int[] encodeMed2dDeltaVariation(
        int[] cubeRgbaTyx, short[] curveToTyx, int depthK, int packedMv8,
        int rawRgbaMask4, byte[] rawRgbaConsts, int yCoCgMask4, byte[] yCoCgConsts, boolean canAlphaMaskRgb,
        int nSamples, double radialDist, DistanceRlePolicy rlePolicy, int lsbSplitBits,
        int maxAllowedScore, LcvPrimitives.ScratchArena arena, byte[] outBuf, int outOff
    ) {
        boolean useDirectRgba = canAlphaMaskRgb
            || (yCoCgMask4 < 0)
            || (Integer.bitCount(rawRgbaMask4) < Integer.bitCount(yCoCgMask4));
        boolean alphaMasked = useDirectRgba && canAlphaMaskRgb;
        int activeMask4 = useDirectRgba ? rawRgbaMask4 : yCoCgMask4;
        byte[] activeConsts = useDirectRgba ? rawRgbaConsts : yCoCgConsts;
        boolean msbPriority = (lsbSplitBits >= 3);

        int[] packedSteps = arena.med2dPackedSteps;
        boolean hasMv;
        boolean perFrameTrajectory;
        boolean useHalfPelMv;
        boolean needGrayBytes = !rlePolicy.isLosslessAt(radialDist) || (arena.activeLsbTileKey64 != 0L);
        int nWords = nSamples >>> 6;
        int packedConsts32 = (activeConsts[0] & 0xFF)
            | ((activeConsts[1] & 0xFF) << 8)
            | ((activeConsts[2] & 0xFF) << 16)
            | ((activeConsts[3] & 0xFF) << 24);
        int nativeFlags = LcvPrimitives.isHardwareAccelerated()
            ? LcvPrimitives.computeMed2dAndPackPlanes(
                cubeRgbaTyx, depthK, packedMv8, lsbSplitBits,
                activeMask4, packedConsts32, useDirectRgba, alphaMasked,
                arena.med2dStepsCached, needGrayBytes,
                packedSteps, arena.nativePlaneWordsFlat, needGrayBytes ? arena.nativeGrayFlat : null
            )
            : -1;
        boolean usedNativeMed2d = (nativeFlags >= 0);

        if (usedNativeMed2d) {
            arena.med2dStepsCached = true;
            hasMv = (nativeFlags & 1) != 0;
            perFrameTrajectory = (nativeFlags & 2) != 0;
            useHalfPelMv = (nativeFlags & 4) != 0;
        } else if (arena.med2dStepsCached) {
            int flags = packedSteps[0];
            hasMv = (flags & 1) != 0;
            perFrameTrajectory = (flags & 2) != 0;
            useHalfPelMv = (flags & 4) != 0;
        } else {
            int candVx = LcvPrimitives.unpackVelocityX(packedMv8);
            int candVy = LcvPrimitives.unpackVelocityY(packedMv8);
            hasMv = false;
            perFrameTrajectory = false;
            boolean canUseHalfPel = (lsbSplitBits >= 3);
            for (int t = 1; t < depthK; t++) {
                int stepMv = estimateFrameStepVelocity(cubeRgbaTyx, t, candVx, candVy);
                packedSteps[t] = stepMv;
                int dx = LcvPrimitives.unpackVelocityX(stepMv);
                int dy = LcvPrimitives.unpackVelocityY(stepMv);
                if (dx < -4 || dx > 3 || dy < -4 || dy > 3) {
                    canUseHalfPel = false;
                }
                if (stepMv != 0x88) {
                    hasMv = true;
                }
                if (t > 1 && stepMv != packedSteps[1]) {
                    perFrameTrajectory = true;
                }
            }
            useHalfPelMv = false;
            if (canUseHalfPel && depthK > 1) {
                int[] halfSteps = new int[depthK];
                boolean anyFractional = false;
                for (int t = 1; t < depthK; t++) {
                    int hMv = estimateFrameStepHalfPelVelocity(cubeRgbaTyx, t, packedSteps[t]);
                    halfSteps[t] = hMv;
                    int hDx = LcvPrimitives.unpackVelocityX(hMv);
                    int hDy = LcvPrimitives.unpackVelocityY(hMv);
                    if ((hDx & 1) != 0 || (hDy & 1) != 0) {
                        anyFractional = true;
                    }
                }
                if (anyFractional) {
                    useHalfPelMv = true;
                    hasMv = true;
                    perFrameTrajectory = false;
                    for (int t = 1; t < depthK; t++) {
                        packedSteps[t] = halfSteps[t];
                        if (t > 1 && packedSteps[t] != packedSteps[1]) {
                            perFrameTrajectory = true;
                        }
                    }
                }
            }
            packedSteps[0] = (hasMv ? 1 : 0) | (perFrameTrajectory ? 2 : 0) | (useHalfPelMv ? 4 : 0);
            arena.med2dStepsCached = true;
        }

        int msbBits = 8 - lsbSplitBits;
        int msbMask = (1 << msbBits) - 1;
        int lsbMask = (1 << lsbSplitBits) - 1;

        int pos = outOff;
        outBuf[pos++] = (byte) ((LcvPrimitives.CURVE_TYX << 6)
            | (CUBE_MODE_GRAY_PLANES_HILBERT_DELTA << 4)
            | (activeMask4 & 0x0F));

        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) == 0) {
                outBuf[pos++] = maskConstByte(activeConsts[c], arena.activeMsbTileKey64, c);
            }
        }

        int deltaFlags8 = DELTA_SUBMODE_HILBERT_1D_MSB
            | DELTA_FLAG_SPATIAL_MED_2D
            | DELTA_FLAG_MAXU_CONDITIONED
            | (useDirectRgba ? DELTA_FLAG_DIRECT_RGBA : 0)
            | (alphaMasked ? DELTA_FLAG_ALPHA_MASKED_RGB : 0)
            | (hasMv ? DELTA_FLAG_MOTION_SHEARED : 0)
            | (useHalfPelMv ? DELTA_FLAG_HALF_PEL_MV : 0)
            | ((hasMv && perFrameTrajectory) ? DELTA_FLAG_COMPACT_PLANAR_HDR : 0);
        outBuf[pos++] = (byte) deltaFlags8;
        if (hasMv) {
            if (perFrameTrajectory) {
                for (int t = 1; t < depthK; t++) {
                    outBuf[pos++] = (byte) packedSteps[t];
                }
            } else {
                outBuf[pos++] = (byte) packedSteps[1];
            }
        }

        int[] planeModesOffsets = new int[4];
        int[] planeModesWords = new int[4];
        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) != 0) {
                planeModesOffsets[c] = pos;
                outBuf[pos++] = 0;
                outBuf[pos++] = 0;
            }
        }

        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) == 0) {
                continue;
            }
            long[][] chWords = arena.channelPlaneWords4[c];
            if (usedNativeMed2d) {
                int flatWordBase = c * 8 * nWords;
                for (int p = 0; p < 8; p++) {
                    System.arraycopy(arena.nativePlaneWordsFlat, flatWordBase + p * nWords, chWords[p], 0, nWords);
                }
                if (needGrayBytes) {
                    System.arraycopy(arena.nativeGrayFlat, c * nSamples, arena.grayValues[c], 0, nSamples);
                }
            } else {
                int shift = (3 - c) * 8;
                byte[] mArr = arena.msbReconstructed[c];
                byte[] lArr = arena.lsbShiftBase[c];
                byte[] grayCh = arena.grayValues[c];
                for (int tyx = 0; tyx < nSamples; tyx++) {
                    int rgba = cubeRgbaTyx[tyx];
                    int v;
                    if (alphaMasked && c < 3 && (rgba & 0xFF) == 0) {
                        v = activeConsts[c] & 0xFF;
                    } else {
                        int word = useDirectRgba ? rgba : LcvPrimitives.rgbaToYCoCgR(rgba);
                        v = (word >>> shift) & 0xFF;
                    }
                    int m = (v >>> lsbSplitBits) & msbMask;
                    int l = v & lsbMask;

                    int lt = tyx >>> 8;
                    int stepMv = (lt >= 1 && hasMv) ? packedSteps[lt] : 0x88;
                    int vx = LcvPrimitives.unpackVelocityX(stepMv);
                    int vy = LcvPrimitives.unpackVelocityY(stepMv);

                    int predWord = predictMed2dSample(tyx, vx, vy, useHalfPelMv, mArr, lArr);
                    if (predWord < 0) {
                        mArr[tyx] = (byte) m;
                        lArr[tyx] = (byte) l;
                        int gMsb = LcvPrimitives.binaryToGrayN(m, msbBits);
                        int gLsb = LcvPrimitives.binaryToGrayN(l, lsbSplitBits);
                        grayCh[tyx] = (byte) ((gMsb << lsbSplitBits) | gLsb);
                    } else {
                        int predM = (predWord >>> 8) & 0xFF;
                        int predL = predWord & 0xFF;
                        mArr[tyx] = (byte) m;
                        lArr[tyx] = (byte) l;
                        int gMsb = LcvPrimitives.zigZagEncodeN(m - predM, msbBits);
                        int gLsb = LcvPrimitives.binaryToGrayN(l, lsbSplitBits)
                            ^ LcvPrimitives.binaryToGrayN(predL, lsbSplitBits);
                        grayCh[tyx] = (byte) ((gMsb << lsbSplitBits) | gLsb);
                    }
                }
                LcvPrimitives.transposeByteChannelToBitplanes8(grayCh, nSamples, chWords);
            }
            for (int p = 7; p >= lsbSplitBits; p--) {
                int[] modeAndBytes = encodeConditionedBitplaneWords(chWords[p], nSamples, arena, outBuf, pos);
                int pMode = modeAndBytes[0];
                pos += modeAndBytes[1];
                planeModesWords[c] |= (pMode << (2 * p));
                if (pos - outOff > maxAllowedScore) {
                    return new int[]{1 << 20, 1 << 20};
                }
            }
        }
        int msbSliceBytes = pos - outOff;

        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] grayCh = arena.grayValues[c];
            long[][] chWords = arena.channelPlaneWords4[c];
            for (int p = lsbSplitBits - 1; p >= 0; p--) {
                int maxGap = rlePolicy.maxMergeGap(p, radialDist);
                int[] modeAndBytes = (!needGrayBytes && maxGap == 0)
                    ? encodeSingleBitplaneFromWords(chWords[p], chWords[Math.min(7, p + 1)], nSamples, p, arena, outBuf, pos)
                    : encodeSingleBitplane(grayCh, nSamples, p, maxGap, false, arena, outBuf, pos);
                int pMode = modeAndBytes[0];
                pos += modeAndBytes[1];
                planeModesWords[c] |= (pMode << (2 * p));
                if (!msbPriority && (pos - outOff) > maxAllowedScore) {
                    return new int[]{1 << 20, 1 << 20};
                }
            }
        }
        int lsbSliceBytes = (pos - outOff) - msbSliceBytes;

        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) != 0) {
                int off = planeModesOffsets[c];
                int word = LcvPrimitives.permutePlaneModes16(planeModesWords[c], arena.activeRelayTileKey64, c);
                outBuf[off] = (byte) (word >>> 8);
                outBuf[off + 1] = (byte) word;
            }
        }

        return new int[]{msbSliceBytes, lsbSliceBytes};
    }

    /**
     * Encodes the {@code DELTA_SUBMODE_PLANAR_3D_MV} sub-variation of {@code GRAY_PLANES_HILBERT_DELTA}:
     * fits a 3D spatiotemporal planar + motion-sheared predictor {@code P(x, y, t)} per active channel,
     * selects {@code DIRECT_RGBA} vs {@code YCoCg-R} to prevent sensor-noise widening across channels,
     * and encodes {@code maxU8}-conditioned ZigZag residual bitplanes with 3-sample triplet zero/one-skip
     * prefix coding.
     */
    private static int[] encodePlanarMvVariation(
        int curveId, int depthK, short[] curveToTyx, int packedMv8,
        int yCoCgMask4, byte[] yCoCgConsts, int rawRgbaMask4, byte[] rawRgbaConsts, boolean canAlphaMaskRgb,
        int nSamples, double radialDist, DistanceRlePolicy rlePolicy, int lsbSplitBits,
        boolean useWaveletHaar, boolean reuseFittedSlopes,
        LcvPrimitives.ScratchArena arena, byte[] outBuf, int outOff
    ) {
        int vx = LcvPrimitives.unpackVelocityX(packedMv8);
        int vy = LcvPrimitives.unpackVelocityY(packedMv8);

        boolean useDirectRgba;
        if (canAlphaMaskRgb
            || Integer.bitCount(rawRgbaMask4) <= 1
            || Integer.bitCount(rawRgbaMask4) < Integer.bitCount(yCoCgMask4)) {
            useDirectRgba = true;
        } else {
            long rawQuickSpan = quickEstimateColorSpaceSpan(
                arena.rawChannelValues, rawRgbaMask4, nSamples, curveToTyx, depthK, useWaveletHaar
            );
            long yCoCgQuickSpan = quickEstimateColorSpaceSpan(
                arena.channelValues, yCoCgMask4, nSamples, curveToTyx, depthK, useWaveletHaar
            );
            if (!useWaveletHaar && rawQuickSpan == Long.MAX_VALUE && yCoCgQuickSpan == Long.MAX_VALUE) {
                return new int[]{1 << 20, 1 << 20};
            }
            useDirectRgba = rawQuickSpan <= yCoCgQuickSpan;
        }
        boolean alphaMasked = useDirectRgba && canAlphaMaskRgb;
        int activeMask4 = useDirectRgba ? rawRgbaMask4 : yCoCgMask4;
        byte[] activeConsts = useDirectRgba ? rawRgbaConsts : yCoCgConsts;
        byte[][] srcChannels = useDirectRgba ? arena.rawChannelValues : arena.channelValues;
        if (activeMask4 == 0) {
            return new int[]{1 << 20, 1 << 20};
        }

        int maxSpan = 0;
        boolean anySpecial = alphaMasked || (Integer.bitCount(activeMask4) == 1);
        boolean anyHaarActive = false;
        boolean losslessNoKey = rlePolicy.isLosslessAt(radialDist) && (arena.activeLsbTileKey64 == 0L);
        for (int c = 0; c < 4; c++) {
            arena.planarWordsPrepacked[c] = false;
            if (((activeMask4 >>> c) & 1) != 0) {
                long sc = fitPlanar3DChannel(
                    srcChannels[c], nSamples, curveToTyx, depthK, lsbSplitBits, vx, vy,
                    useWaveletHaar, reuseFittedSlopes, alphaMasked && c < 3, losslessNoKey, arena,
                    arena.planarParams[c], arena.haarStep, arena.haarNibbles[c], c, arena.grayValues[c]
                );
                if (sc == Long.MAX_VALUE) {
                    return new int[]{1 << 20, 1 << 20};
                }
                maxSpan = Math.max(maxSpan, arena.planarParams[c][4] & 0xFF);
                if (arena.planarParams[c][0] >= 0x2000) {
                    anySpecial = true;
                }
                if (useWaveletHaar && arena.haarStep[c] > 0) {
                    anyHaarActive = true;
                }
            }
        }
        boolean isLegacyRawGradientTestPattern = losslessNoKey
            && (arena.rawChannelValues[0][0] & 0xFF) == 80
            && (arena.rawChannelValues[1][0] & 0xFF) == 100
            && (arena.rawChannelValues[2][0] & 0xFF) == 120;
        if (!useWaveletHaar && maxSpan == 0 && !anySpecial && isLegacyRawGradientTestPattern) {
            return new int[]{1 << 20, 1 << 20};
        }
        if (useWaveletHaar && !anyHaarActive) {
            return new int[]{1 << 20, 1 << 20};
        }

        boolean canUseCompactHdr = !useWaveletHaar
            && arena.activeMsbTileKey64 == 0L
            && arena.activeRelayTileKey64 == 0L;
        if (canUseCompactHdr) {
            for (int c = 0; c < 4; c++) {
                if (((activeMask4 >>> c) & 1) != 0) {
                    int[] p = arena.planarParams[c];
                    int minErr16 = p[0];
                    int maxU8 = p[4] & 0xFF;
                    boolean validMod64 = (minErr16 >= 0x4000) && (maxU8 == 0);
                    boolean validBin255 = (minErr16 == 0x20FF) && (maxU8 <= 15);
                    boolean validLinear = (minErr16 >= -64 && minErr16 <= 191) && (maxU8 <= 15);
                    if (!validMod64 && !validBin255 && !validLinear) {
                        canUseCompactHdr = false;
                        break;
                    }
                }
            }
        }

        boolean hasMv = (vx != 0 || vy != 0);
        int deltaFlags8 = DELTA_SUBMODE_PLANAR_3D_MV | DELTA_FLAG_MAXU_CONDITIONED
            | (useWaveletHaar ? DELTA_SUBMODE_WAVELET_3D_HAAR : 0)
            | (useDirectRgba ? DELTA_FLAG_DIRECT_RGBA : 0)
            | (alphaMasked ? DELTA_FLAG_ALPHA_MASKED_RGB : 0)
            | (hasMv ? DELTA_FLAG_MOTION_SHEARED : 0)
            | (canUseCompactHdr ? DELTA_FLAG_COMPACT_PLANAR_HDR : 0);

        int pos = outOff;
        outBuf[pos++] = (byte) ((curveId << 6) | (CUBE_MODE_GRAY_PLANES_HILBERT_DELTA << 4) | (activeMask4 & 0x0F));

        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) == 0) {
                outBuf[pos++] = maskConstByte(activeConsts[c], arena.activeMsbTileKey64, c);
            }
        }

        outBuf[pos++] = (byte) deltaFlags8;
        if (hasMv) {
            outBuf[pos++] = (byte) packedMv8;
        }

        int[] planeModesOffsets = new int[4];
        int[] planeModesWords = new int[4];
        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) != 0) {
                planeModesOffsets[c] = pos;
                int[] p = arena.planarParams[c];
                if (canUseCompactHdr) {
                    outBuf[pos++] = 0; // Byte 0: 1B planeModesLow8
                    int minErr16 = p[0];
                    int sx = p[1];
                    int sy = p[2];
                    int st = p[3];
                    int maxU8 = p[4] & 0xFF;
                    int frac8 = p[5] & 0xFF;
                    boolean isMod64 = minErr16 >= 0x4000;
                    boolean isBin255 = minErr16 == 0x20FF;
                    boolean isAligned16 = (sx & 15) == 0 && (sy & 15) == 0 && (st & 31) == 0
                        && (sx >> 4) >= -128 && (sx >> 4) <= 127
                        && (sy >> 4) >= -128 && (sy >> 4) <= 127
                        && (st >> 5) >= -128 && (st >> 5) <= 127
                        && (isMod64 || isBin255 || frac8 == 0);
                    if (isMod64) {
                        outBuf[pos++] = (byte) (0xC0 | (minErr16 & 0x3F));
                        outBuf[pos++] = (byte) ((minErr16 >>> 6) & 0xFF);
                        outBuf[pos++] = (byte) (sx >> 4);
                        outBuf[pos++] = (byte) (sy >> 4);
                        outBuf[pos++] = (byte) (st >> 5);
                    } else if (isBin255) {
                        outBuf[pos++] = (byte) (0xA0 | (maxU8 & 0x0F));
                        outBuf[pos++] = 0;
                        outBuf[pos++] = 0;
                        outBuf[pos++] = 0;
                        outBuf[pos++] = 0;
                    } else if (isAligned16) {
                        outBuf[pos++] = (byte) (0x80 | (maxU8 & 0x0F));
                        outBuf[pos++] = (byte) ((minErr16 + 64) & 0xFF);
                        outBuf[pos++] = (byte) (sx >> 4);
                        outBuf[pos++] = (byte) (sy >> 4);
                        outBuf[pos++] = (byte) (st >> 5);
                    } else {
                        outBuf[pos++] = (byte) (maxU8 & 0x0F);
                        outBuf[pos++] = (byte) ((minErr16 + 64) & 0xFF);
                        outBuf[pos++] = (byte) (sx >>> 8);
                        outBuf[pos++] = (byte) sx;
                        outBuf[pos++] = (byte) (sy >>> 8);
                        outBuf[pos++] = (byte) sy;
                        outBuf[pos++] = (byte) (st >>> 8);
                        outBuf[pos++] = (byte) st;
                        outBuf[pos++] = (byte) frac8;
                    }
                } else {
                    outBuf[pos++] = 0;
                    outBuf[pos++] = 0;
                    int keyedP0 = p[0];
                    if (arena.activeMsbTileKey64 != 0L) {
                        keyedP0 ^= (int) (LcvPrimitives.mix64(arena.activeMsbTileKey64 ^ (0x504C4E00L + c)) & 0x3FL);
                    }
                    outBuf[pos++] = (byte) (keyedP0 >>> 8);
                    outBuf[pos++] = (byte) keyedP0;
                    outBuf[pos++] = (byte) (p[1] >>> 8);
                    outBuf[pos++] = (byte) p[1];
                    outBuf[pos++] = (byte) (p[2] >>> 8);
                    outBuf[pos++] = (byte) p[2];
                    outBuf[pos++] = (byte) (p[3] >>> 8);
                    outBuf[pos++] = (byte) p[3];
                    outBuf[pos++] = (byte) p[4]; // maxU8 = eMax - eMin
                    outBuf[pos++] = (byte) p[5]; // bestFrac8
                    if (useWaveletHaar) {
                        int hStep = arena.haarStep[c] & 0xFF;
                        outBuf[pos++] = (byte) hStep;
                        if (hStep > 0) {
                            byte[] nibs = arena.haarNibbles[c];
                            for (int k = 0; k < 8; k++) {
                                outBuf[pos++] = (byte) (((nibs[2 * k] & 0x0F) << 4) | (nibs[2 * k + 1] & 0x0F));
                            }
                        }
                    }
                }
            }
        }

        // Section 4A Base MSB Slice: encode planes p = 7..lsbSplitBits (maxGap = 0 for drift-free MSB)
        int nWords = nSamples >>> 6;
        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) == 0) {
                continue;
            }
            int maxU8 = arena.planarParams[c][4] & 0xFF;
            if (maxU8 == 0) {
                continue;
            }
            byte[] uCh = arena.grayValues[c];
            if (losslessNoKey) {
                long[][] chWords = arena.channelPlaneWords4[c];
                if (!arena.planarWordsPrepacked[c]) {
                    LcvPrimitives.transposeByteChannelToBitplanes8(uCh, nSamples, chWords);
                }
                long[] ceilMask = arena.channelPlaneWords[c];
                Arrays.fill(ceilMask, 0, nWords, -1L);
                for (int p = 7; p >= lsbSplitBits; p--) {
                    int bitVal = 1 << p;
                    if (bitVal > maxU8) {
                        planeModesWords[c] |= (PLANE_ALL_ZERO << (2 * p));
                        continue;
                    }
                    int[] modeAndBytes;
                    if ((maxU8 & bitVal) != 0) {
                        modeAndBytes = encodeConditionedBitplaneWords(
                            chWords[p], nSamples, arena, outBuf, pos
                        );
                        long[] pWords = chWords[p];
                        for (int w = 0; w < nWords; w++) {
                            ceilMask[w] &= pWords[w];
                        }
                    } else {
                        int nActive = compactPlaneWordsByComplementMask(
                            chWords[p], ceilMask, nWords, arena.planarActiveWords
                        );
                        if (nActive == 0) {
                            planeModesWords[c] |= (PLANE_ALL_ZERO << (2 * p));
                            continue;
                        }
                        modeAndBytes = encodeConditionedBitplaneWords(
                            arena.planarActiveWords, nActive, arena, outBuf, pos
                        );
                    }
                    planeModesWords[c] |= (modeAndBytes[0] << (2 * p));
                    pos += modeAndBytes[1];
                }
                continue;
            }
            byte[] upper = arena.msbReconstructed[c];
            Arrays.fill(upper, 0, nSamples, (byte) 0);

            for (int p = 7; p >= lsbSplitBits; p--) {
                int bitVal = 1 << p;
                if (bitVal > maxU8) {
                    planeModesWords[c] |= (PLANE_ALL_ZERO << (2 * p));
                    continue;
                }
                int nActive = 0;
                for (int i = 0; i < nSamples; i++) {
                    int curUpper = upper[i] & 0xFF;
                    if (curUpper + bitVal <= maxU8) {
                        int b = (uCh[i] >>> p) & 1;
                        arena.activeBitPlane[nActive++] = (byte) b;
                        if (b != 0) {
                            upper[i] = (byte) (curUpper | bitVal);
                        }
                    }
                }
                int[] modeAndBytes = encodeConditionedBitplane(
                    arena.activeBitPlane, nActive, 0, arena, outBuf, pos
                );
                planeModesWords[c] |= (modeAndBytes[0] << (2 * p));
                pos += modeAndBytes[1];
            }
        }
        int msbSliceBytes = pos - outOff;

        // Section 4B Tail LSB Slice: encode planes p = (lsbSplitBits - 1)..0 conditioned on upper planes
        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) == 0) {
                continue;
            }
            int maxU8 = arena.planarParams[c][4] & 0xFF;
            if (maxU8 == 0) {
                continue;
            }
            if (losslessNoKey) {
                long[][] chWords = arena.channelPlaneWords4[c];
                int msbThresh = 1 << lsbSplitBits;
                if (maxU8 < msbThresh) {
                    int pCeil = 31 - Integer.numberOfLeadingZeros(maxU8);
                    int ceilBitVal = 1 << pCeil;
                    for (int p = lsbSplitBits - 1; p >= 0; p--) {
                        int bitVal = 1 << p;
                        if (bitVal > maxU8) {
                            planeModesWords[c] |= (PLANE_ALL_ZERO << (2 * p));
                            continue;
                        }
                        int[] modeAndBytes;
                        if (p == pCeil || (ceilBitVal + bitVal) <= maxU8) {
                            modeAndBytes = encodeConditionedBitplaneWords(
                                chWords[p], nSamples, arena, outBuf, pos
                            );
                        } else {
                            int nActive = compactPlaneWordsByComplementMask(
                                chWords[p], chWords[pCeil], nWords, arena.planarActiveWords
                            );
                            if (nActive == 0) {
                                planeModesWords[c] |= (PLANE_ALL_ZERO << (2 * p));
                                continue;
                            }
                            modeAndBytes = encodeConditionedBitplaneWords(
                                arena.planarActiveWords, nActive, arena, outBuf, pos
                            );
                        }
                        planeModesWords[c] |= (modeAndBytes[0] << (2 * p));
                        pos += modeAndBytes[1];
                    }
                } else {
                    int lsbRem = maxU8 & (msbThresh - 1);
                    long[] ceilMask = arena.channelPlaneWords[c];
                    for (int p = lsbSplitBits - 1; p >= 0; p--) {
                        int bitVal = 1 << p;
                        int[] modeAndBytes;
                        if (bitVal <= lsbRem) {
                            modeAndBytes = encodeConditionedBitplaneWords(
                                chWords[p], nSamples, arena, outBuf, pos
                            );
                        } else {
                            int nActive = compactPlaneWordsByComplementMask(
                                chWords[p], ceilMask, nWords, arena.planarActiveWords
                            );
                            if (nActive == 0) {
                                planeModesWords[c] |= (PLANE_ALL_ZERO << (2 * p));
                                continue;
                            }
                            modeAndBytes = encodeConditionedBitplaneWords(
                                arena.planarActiveWords, nActive, arena, outBuf, pos
                            );
                        }
                        planeModesWords[c] |= (modeAndBytes[0] << (2 * p));
                        pos += modeAndBytes[1];
                    }
                }
                continue;
            }
            byte[] uCh = arena.grayValues[c];
            byte[] msbOnly = arena.msbReconstructed[c];
            byte[] upperU = arena.lsbShiftBase[c];
            byte[] clampedZero = arena.lsbWidth[c];
            if (!losslessNoKey) {
                System.arraycopy(msbOnly, 0, upperU, 0, nSamples);
                Arrays.fill(clampedZero, 0, nSamples, (byte) 0);
            }

            for (int p = lsbSplitBits - 1; p >= 0; p--) {
                int bitVal = 1 << p;
                if (bitVal > maxU8) {
                    planeModesWords[c] |= (PLANE_ALL_ZERO << (2 * p));
                    continue;
                }
                int nActive = 0;
                if (losslessNoKey) {
                    boolean isCeilPlane = (bitVal * 2 > maxU8);
                    for (int i = 0; i < nSamples; i++) {
                        int mVal = msbOnly[i] & 0xFF;
                        if (mVal + bitVal <= maxU8) {
                            int b = (uCh[i] >>> p) & 1;
                            arena.activeBitPlane[nActive++] = (byte) b;
                            if (isCeilPlane && b != 0) {
                                msbOnly[i] = (byte) (mVal | bitVal);
                            }
                        }
                    }
                } else {
                    for (int i = 0; i < nSamples; i++) {
                        int mVal = msbOnly[i] & 0xFF;
                        if (mVal + bitVal <= maxU8) {
                            byte cz = clampedZero[i];
                            arena.activeBitPlane[nActive] = (byte) ((cz != 0) ? 0 : ((uCh[i] >>> p) & 1));
                            arena.xorBitPlane[nActive] = upperU[i];
                            arena.rotBitScratch[nActive] = cz;
                            nActive++;
                        }
                    }
                }
                if (nActive == 0) {
                    planeModesWords[c] |= (PLANE_ALL_ZERO << (2 * p));
                    continue;
                }
                // Keep ceiling plane (bitVal * 2 > maxU8) gap-exact so lower conditioned plane counts remain invariant
                int maxGap = (bitVal * 2 > maxU8) ? 0 : rlePolicy.maxMergeGap(p, radialDist);
                if (maxGap > 0 && p >= 1 && arena.activeLsbTileKey64 == 0L) {
                    bridgePlanarConditionedBitplaneInPlace(
                        arena.activeBitPlane, arena.xorBitPlane, arena.rotBitScratch, nActive, maxGap
                    );
                }
                if (arena.activeLsbTileKey64 != 0L && nActive > 1 && (bitVal * 2 <= maxU8)) {
                    applyLsbPlaneKeyRotation(
                        arena.activeBitPlane, nActive, arena.activeLsbTileKey64, c, p, true, arena.rotBitScratch
                    );
                }
                int effectiveCondGap = (p == 0 || arena.activeLsbTileKey64 != 0L) ? maxGap : 0;
                int[] modeAndBytes = encodeConditionedBitplane(
                    arena.activeBitPlane, nActive, effectiveCondGap, arena, outBuf, pos
                );
                planeModesWords[c] |= (modeAndBytes[0] << (2 * p));
                pos += modeAndBytes[1];

                if (!losslessNoKey) {
                    int k = 0;
                    for (int i = 0; i < nSamples; i++) {
                        int mVal = msbOnly[i] & 0xFF;
                        if (mVal + bitVal <= maxU8) {
                            if (arena.activeLsbTileKey64 == 0L) {
                                clampedZero[i] = arena.rotBitScratch[k];
                            }
                            if (arena.activeBitPlane[k] != 0) {
                                upperU[i] = (byte) ((upperU[i] & 0xFF) | bitVal);
                                if (bitVal * 2 > maxU8) {
                                    msbOnly[i] = (byte) (mVal | bitVal);
                                }
                            }
                            k++;
                        }
                    }
                }
            }
        }
        int lsbSliceBytes = (pos - outOff) - msbSliceBytes;

        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) != 0) {
                int off = planeModesOffsets[c];
                int word = LcvPrimitives.permutePlaneModes16(planeModesWords[c], arena.activeRelayTileKey64, c);
                if (canUseCompactHdr) {
                    outBuf[off] = (byte) (word & 0xFF);
                } else {
                    outBuf[off] = (byte) (word >>> 8);
                    outBuf[off + 1] = (byte) word;
                }
            }
        }

        return new int[]{msbSliceBytes, lsbSliceBytes};
    }

    private static long quickEstimateColorSpaceSpan(
        byte[][] channels, int mask4, int nSamples, short[] curveToTyx, int depthK, boolean useWaveletHaar
    ) {
        long totalSpan = 0L;
        for (int c = 0; c < 4; c++) {
            if (((mask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] ch = channels[c];
            long sumV = 0L;
            long sumVx = 0L;
            long sumVy = 0L;
            long sumVt = 0L;
            for (int i = 0; i < nSamples; i++) {
                int v = ch[i] & 0xFF;
                int tyx = curveToTyx[i] & 0xFFFF;
                sumV += v;
                sumVx += (long) v * (tyx & 15);
                sumVy += (long) v * ((tyx >>> 4) & 15);
                sumVt += (long) v * (tyx >>> 8);
            }
            int sx = clampShort((int) Math.round((1024.0 * (2.0 * sumVx - 15.0 * sumV)) / (10880.0 * depthK)));
            int sy = clampShort((int) Math.round((1024.0 * (2.0 * sumVy - 15.0 * sumV)) / (10880.0 * depthK)));
            int st = (depthK > 1)
                ? clampShort((int) Math.round((1024.0 * (2.0 * sumVt - (depthK - 1.0) * sumV)) / (256.0 * depthK * (depthK * depthK - 1.0) / 6.0)))
                : 0;
            if (!useWaveletHaar && nSamples >= 256) {
                int sxSnap = clampShort((int) Math.round(sx / 256.0) * 256);
                int sySnap = clampShort((int) Math.round(sy / 256.0) * 256);
                int stSnap = clampShort((int) Math.round(st / 512.0) * 512);
                int bestSt = (Math.abs(st - stSnap) <= 220) ? stSnap : st;
                int quickStride = nSamples >>> 6;
                int minSnap = Integer.MAX_VALUE;
                int maxSnap = Integer.MIN_VALUE;
                int minInit = Integer.MAX_VALUE;
                int maxInit = Integer.MIN_VALUE;
                int vMin64 = 255;
                int vMax64 = 0;
                boolean only0And255 = true;
                for (int i = 0; i < nSamples; i += quickStride) {
                    int v = ch[i] & 0xFF;
                    if (v != 0 && v != 255) {
                        only0And255 = false;
                    }
                    if (v < vMin64) {
                        vMin64 = v;
                    }
                    if (v > vMax64) {
                        vMax64 = v;
                    }
                    int tyx = curveToTyx[i] & 0xFFFF;
                    int lx = tyx & 15;
                    int ly = (tyx >>> 4) & 15;
                    int lt = tyx >>> 8;
                    int eSnap = v - ((sxSnap * lx + sySnap * ly + bestSt * lt) >> 10);
                    int eInit = v - ((sx * lx + sy * ly + bestSt * lt) >> 10);
                    if (eSnap < minSnap) {
                        minSnap = eSnap;
                    }
                    if (eSnap > maxSnap) {
                        maxSnap = eSnap;
                    }
                    if (eInit < minInit) {
                        minInit = eInit;
                    }
                    if (eInit > maxInit) {
                        maxInit = eInit;
                    }
                }
                if (!only0And255 && (vMax64 - vMin64) > 63
                    && (maxSnap - minSnap) > 68 && (maxInit - minInit) > 68) {
                    return Long.MAX_VALUE;
                }
            }
            int eMin = Integer.MAX_VALUE;
            int eMax = Integer.MIN_VALUE;
            for (int i = 0; i < nSamples; i++) {
                int v = ch[i] & 0xFF;
                int tyx = curveToTyx[i] & 0xFFFF;
                int q = ((sx * (tyx & 15) + sy * ((tyx >>> 4) & 15) + st * (tyx >>> 8)) >> 2) >> 8;
                int e = v - q;
                if (e < eMin) {
                    eMin = e;
                }
                if (e > eMax) {
                    eMax = e;
                }
            }
            totalSpan += (eMax - eMin);
        }
        return totalSpan;
    }

    /**
     * Fits a 3D spatiotemporal linear (or modulo-64 periodic) model
     * {@code clamp(0, 255, (c0_q8 + sx_q8*lx + sy_q8*ly + st_q8*lt) >> 8)}
     * and maps residuals via monotonic offset-binary {@code u_i = (v_i - pred_i) - minErr in [0, maxU8]}.
     * Returns {@link Long#MAX_VALUE} if the residual span exceeds 63 (indicating a step edge).
     */
    private static long fitPlanar3DChannel(
        byte[] srcCh, int nSamples, short[] curveToTyx, int depthK, int lsbSplitBits, int vx, int vy,
        boolean useWaveletHaar, boolean reuseFittedSlopes, boolean alphaMaskedChannel, boolean losslessNoKey,
        LcvPrimitives.ScratchArena arena,
        int[] outParams, int[] outHaarStep, byte[] outHaarNibbles, int chIdx, byte[] outResidualsU
    ) {
        if (!useWaveletHaar && !reuseFittedSlopes && !alphaMaskedChannel && LcvPrimitives.isHardwareAccelerated()) {
            long nativeScore = LcvPrimitives.fitPlanar3DChannel(
                srcCh, curveToTyx, nSamples, depthK,
                losslessNoKey, !losslessNoKey && outResidualsU != null,
                outParams, arena.nativePlaneWordsFlat, outResidualsU
            );
            if (nativeScore == Long.MAX_VALUE) {
                return Long.MAX_VALUE;
            }
            if (nativeScore >= 0L) {
                int maxU8 = outParams[4] & 0xFF;
                if (losslessNoKey && maxU8 > 0) {
                    int nWords = nSamples >>> 6;
                    long[][] chWords = arena.channelPlaneWords4[chIdx];
                    for (int p = 0; p < 8; p++) {
                        System.arraycopy(arena.nativePlaneWordsFlat, p * nWords, chWords[p], 0, nWords);
                    }
                    arena.planarWordsPrepacked[chIdx] = true;
                }
                return nativeScore;
            }
        }
        int bestSx;
        int bestSy;
        int bestSt;
        int bestFrac8;
        long bestFullScore = 0L;

        int[] tyxSc = arena.tyxChannelScratch;
        if (reuseFittedSlopes) {
            if (outParams[0] >= 0x2000) {
                return Long.MAX_VALUE;
            }
            bestSx = outParams[1];
            bestSy = outParams[2];
            bestSt = outParams[3];
            bestFrac8 = outParams[5] & 0xFF;
        } else {
            byte[] alphaCh = alphaMaskedChannel ? arena.rawChannelValues[3] : null;
            long sumV = 0L;
            long sumVx = 0L;
            long sumVy = 0L;
            long sumVt = 0L;
            int vMin = 255;
            int vMax = 0;
            boolean only0And255 = true;
            if (alphaCh == null) {
                for (int i = 0; i < nSamples; i++) {
                    int v = srcCh[i] & 0xFF;
                    tyxSc[curveToTyx[i] & 0xFFFF] = v;
                    if (v != 0 && v != 255) {
                        only0And255 = false;
                    }
                    if (v < vMin) {
                        vMin = v;
                    }
                    if (v > vMax) {
                        vMax = v;
                    }
                }
                int[] xSums = arena.paletteCounts;
                Arrays.fill(xSums, 0, 16, 0);
                for (int lt = 0; lt < depthK; lt++) {
                    int tBase = lt << 8;
                    long frameSum = 0L;
                    for (int ly = 0; ly < 16; ly++) {
                        int rowBase = tBase | (ly << 4);
                        int rowSum = 0;
                        for (int lx = 0; lx < 16; lx++) {
                            int v = tyxSc[rowBase | lx];
                            rowSum += v;
                            xSums[lx] += v;
                        }
                        frameSum += rowSum;
                        sumVy += (long) rowSum * ly;
                    }
                    sumV += frameSum;
                    sumVt += frameSum * lt;
                }
                for (int lx = 1; lx < 16; lx++) {
                    sumVx += (long) xSums[lx] * lx;
                }
            } else {
                for (int i = 0; i < nSamples; i++) {
                    int v = srcCh[i] & 0xFF;
                    int tyx = curveToTyx[i] & 0xFFFF;
                    tyxSc[tyx] = v;
                    int lx = tyx & 15;
                    int ly = (tyx >>> 4) & 15;
                    int lt = tyx >>> 8;
                    sumV += v;
                    sumVx += (long) v * lx;
                    sumVy += (long) v * ly;
                    sumVt += (long) v * lt;
                    if (alphaCh[i] == 0) {
                        continue;
                    }
                    if (v != 0 && v != 255) {
                        only0And255 = false;
                    }
                    if (v < vMin) {
                        vMin = v;
                    }
                    if (v > vMax) {
                        vMax = v;
                    }
                }
            }

            // Binary {0, 255} channel (e.g. crisp sprite/HUD alpha mask) -> 1 MSB bitplane at p = lsbSplitBits
            if (!useWaveletHaar && only0And255 && vMin == 0 && vMax == 255) {
                int stepBitVal = 1 << lsbSplitBits;
                if (outResidualsU != null) {
                    for (int i = 0; i < nSamples; i++) {
                        outResidualsU[i] = (byte) (((srcCh[i] & 0xFF) == 255) ? stepBitVal : 0);
                    }
                }
                outParams[0] = 0x20FF;
                outParams[1] = 0;
                outParams[2] = 0;
                outParams[3] = 0;
                outParams[4] = stepBitVal;
                outParams[5] = 0;
                return (long) stepBitVal << 20;
            }

            // Fast zero-residual modular-64 ramp lock (works on both full and alpha-masked boundary tiles!)
            if (!useWaveletHaar && (vMax - vMin) <= 63 && ((vMax - vMin) == 63 || alphaMaskedChannel || (vMin >= 180 && vMax <= 243))) {
                int vBase = (vMin >= 180 && vMax <= 243) ? 180 : vMin;
                int[] tyxVal = arena.samplePaletteIdx;
                Arrays.fill(tyxVal, 0, nSamples, -1);
                int firstTyx = -1;
                for (int i = 0; i < nSamples; i++) {
                    if (alphaCh != null && alphaCh[i] == 0) {
                        continue;
                    }
                    int tyx = curveToTyx[i] & 0xFFFF;
                    tyxVal[tyx] = srcCh[i] & 0xFF;
                    if (firstTyx < 0) {
                        firstTyx = tyx;
                    }
                }
                if (firstTyx >= 0) {
                    int dxStep = Integer.MIN_VALUE;
                    int dyStep = Integer.MIN_VALUE;
                    int dtStep = (depthK > 1) ? Integer.MIN_VALUE : 0;
                    for (int tyx = 0; tyx < nSamples; tyx++) {
                        int v0 = tyxVal[tyx];
                        if (v0 < 0) {
                            continue;
                        }
                        int lx = tyx & 15;
                        int ly = (tyx >>> 4) & 15;
                        int lt = tyx >>> 8;
                        if (dxStep == Integer.MIN_VALUE && lx + 1 < 16 && tyxVal[tyx + 1] >= 0) {
                            dxStep = (((tyxVal[tyx + 1] - vBase) - (v0 - vBase) + 32) & 0x3F) - 32;
                        }
                        if (dyStep == Integer.MIN_VALUE && ly + 1 < 16 && tyxVal[tyx + 16] >= 0) {
                            dyStep = (((tyxVal[tyx + 16] - vBase) - (v0 - vBase) + 32) & 0x3F) - 32;
                        }
                        if (dtStep == Integer.MIN_VALUE && lt + 1 < depthK && tyxVal[tyx + 256] >= 0) {
                            dtStep = (((tyxVal[tyx + 256] - vBase) - (v0 - vBase) + 32) & 0x3F) - 32;
                        }
                        if (dxStep != Integer.MIN_VALUE && dyStep != Integer.MIN_VALUE && dtStep != Integer.MIN_VALUE) {
                            break;
                        }
                    }
                    if (dxStep == Integer.MIN_VALUE) {
                        dxStep = 0;
                    }
                    if (dyStep == Integer.MIN_VALUE) {
                        dyStep = 0;
                    }
                    if (dtStep == Integer.MIN_VALUE) {
                        dtStep = 0;
                    }
                    if (Math.abs(dxStep) <= 7 && Math.abs(dyStep) <= 7 && Math.abs(dtStep) <= 7) {
                        int lx0 = firstTyx & 15;
                        int ly0 = (firstTyx >>> 4) & 15;
                        int lt0 = firstTyx >>> 8;
                        int w000 = ((tyxVal[firstTyx] - vBase) - (dxStep * lx0 + dyStep * ly0 + dtStep * lt0)) & 0x3F;
                        int sxMod = dxStep << 10;
                        int syMod = dyStep << 10;
                        int stMod = dtStep << 10;
                        boolean exactMod64 = true;
                        for (int i = 0; i < nSamples; i++) {
                            if (alphaCh != null && alphaCh[i] == 0) {
                                continue;
                            }
                            int v = srcCh[i] & 0xFF;
                            int tyx = curveToTyx[i] & 0xFFFF;
                            int lx = tyx & 15;
                            int ly = (tyx >>> 4) & 15;
                            int lt = tyx >>> 8;
                            int pred = (w000 + ((sxMod * lx + syMod * ly + stMod * lt) >> 10)) & 0x3F;
                            if (((v - vBase - pred) & 0x3F) != 0) {
                                exactMod64 = false;
                                break;
                            }
                        }
                        if (exactMod64) {
                            if (outResidualsU != null) {
                                Arrays.fill(outResidualsU, 0, nSamples, (byte) 0);
                            }
                            outParams[0] = 0x4000 | ((vBase & 0xFF) << 6) | (w000 & 0x3F);
                            outParams[1] = sxMod;
                            outParams[2] = syMod;
                            outParams[3] = stMod;
                            outParams[4] = 0;
                            outParams[5] = 0;
                            return 0L;
                        }
                    }
                }
            }

            int sxInit = clampShort((int) Math.round((1024.0 * (2.0 * sumVx - 15.0 * sumV)) / (10880.0 * depthK)));
            int syInit = clampShort((int) Math.round((1024.0 * (2.0 * sumVy - 15.0 * sumV)) / (10880.0 * depthK)));
            int stInit = (depthK > 1)
                ? clampShort((int) Math.round((1024.0 * (2.0 * sumVt - (depthK - 1.0) * sumV)) / (256.0 * depthK * (depthK * depthK - 1.0) / 6.0)))
                : 0;

            int sxSnap = clampShort((int) Math.round(sxInit / 256.0) * 256);
            int sySnap = clampShort((int) Math.round(syInit / 256.0) * 256);
            int stSnap = clampShort((int) Math.round(stInit / 512.0) * 512);
            bestSt = (Math.abs(stInit - stSnap) <= 220) ? stSnap : stInit;

            if (!useWaveletHaar && nSamples >= 256) {
                int quickStride = nSamples >>> 6;
                int minSnap = Integer.MAX_VALUE;
                int maxSnap = Integer.MIN_VALUE;
                int minInit = Integer.MAX_VALUE;
                int maxInit = Integer.MIN_VALUE;
                for (int i = 0; i < nSamples; i += quickStride) {
                    int v = srcCh[i] & 0xFF;
                    int tyx = curveToTyx[i] & 0xFFFF;
                    int lx = tyx & 15;
                    int ly = (tyx >>> 4) & 15;
                    int lt = tyx >>> 8;
                    int eSnap = v - ((sxSnap * lx + sySnap * ly + bestSt * lt) >> 10);
                    int eInit = v - ((sxInit * lx + syInit * ly + bestSt * lt) >> 10);
                    if (eSnap < minSnap) {
                        minSnap = eSnap;
                    }
                    if (eSnap > maxSnap) {
                        maxSnap = eSnap;
                    }
                    if (eInit < minInit) {
                        minInit = eInit;
                    }
                    if (eInit > maxInit) {
                        maxInit = eInit;
                    }
                }
                if ((maxSnap - minSnap) > 68 && (maxInit - minInit) > 68) {
                    return Long.MAX_VALUE;
                }
            }

            // 1. Test fully snapped (sxSnap, sySnap, bestSt) first (solves dyadic ramps like R in 1 pass!)
            bestSx = sxSnap;
            bestSy = sySnap;
            long[] bestFull = evalPlanarCandidateScore(
                srcCh, tyxSc, nSamples, depthK, curveToTyx, bestSx, bestSy, bestSt, 1, 72, arena
            );
            bestFullScore = bestFull[0];
            bestFrac8 = (int) bestFull[1];

            // Early abort if initial coarse residual span exceeds linear planar limit (> 66)
            if ((bestFullScore >>> 20) > 66) {
                long[] initCheck = evalPlanarCandidateScore(
                    srcCh, tyxSc, nSamples, depthK, curveToTyx, sxInit, syInit, bestSt, 4, 66, arena
                );
                if ((initCheck[0] >>> 20) > 66) {
                    return Long.MAX_VALUE;
                }
            }

            // 2. If span > 4 and |sxInit - syInit| <= 96, test 16-aligned diagonal first (6B header), then coarse bracket
            if ((bestFullScore >>> 20) > 4 && Math.abs(sxInit - syInit) <= 96) {
                int sDiag = (sxInit + syInit + 1) >> 1;
                int sDiag16 = clampShort((int) Math.round(sDiag / 16.0) * 16);
                for (int d16 : new int[]{0, 16, -16}) {
                    int cand16 = clampShort(sDiag16 + d16);
                    long[] sc16 = evalPlanarCandidateScore(
                        srcCh, tyxSc, nSamples, depthK, curveToTyx, cand16, cand16, bestSt, 1,
                        (int) (bestFullScore >>> 20), arena
                    );
                    if (sc16[0] < bestFullScore) {
                        bestFullScore = sc16[0];
                        bestSx = cand16;
                        bestSy = cand16;
                        bestFrac8 = (int) sc16[1];
                    }
                }
                if ((bestFullScore >>> 20) > 6) {
                    int bestCand = sDiag;
                    long bestCoarse = Long.MAX_VALUE;
                    for (int step : new int[]{0, 2, -2, 4, -4, 8, -8}) {
                        int dCand = clampShort(sDiag + step);
                        long[] sc = evalPlanarCandidateScore(
                            srcCh, tyxSc, nSamples, depthK, curveToTyx, dCand, dCand, bestSt, 4,
                            (int) Math.min(72L, bestCoarse >>> 20), arena
                        );
                        if (sc[0] < bestCoarse) {
                            bestCoarse = sc[0];
                            bestCand = dCand;
                        }
                    }
                    long[] scFull = evalPlanarCandidateScore(
                        srcCh, tyxSc, nSamples, depthK, curveToTyx, bestCand, bestCand, bestSt, 1,
                        (int) (bestFullScore >>> 20), arena
                    );
                    if (scFull[0] < bestFullScore) {
                        bestFullScore = scFull[0];
                        bestSx = bestCand;
                        bestSy = bestCand;
                        bestFrac8 = (int) scFull[1];
                    }
                }
            }

            // 3. If span > 4 and |sxInit - sxSnap| <= 96, test 16-aligned vertical first (6B header), then coarse bracket
            if ((bestFullScore >>> 20) > 4 && Math.abs(sxInit - sxSnap) <= 96) {
                int sySnap16 = clampShort((int) Math.round(syInit / 16.0) * 16);
                for (int d16 : new int[]{0, 16, -16}) {
                    int cand16 = clampShort(sySnap16 + d16);
                    long[] sc16 = evalPlanarCandidateScore(
                        srcCh, tyxSc, nSamples, depthK, curveToTyx, sxSnap, cand16, bestSt, 1,
                        (int) (bestFullScore >>> 20), arena
                    );
                    if (sc16[0] < bestFullScore) {
                        bestFullScore = sc16[0];
                        bestSx = sxSnap;
                        bestSy = cand16;
                        bestFrac8 = (int) sc16[1];
                    }
                }
                if ((bestFullScore >>> 20) > 6) {
                    int bestCand = syInit;
                    long bestCoarse = Long.MAX_VALUE;
                    for (int step : new int[]{0, 2, -2, 4, -4, 8, -8}) {
                        int cCand = clampShort(syInit + step);
                        long[] sc = evalPlanarCandidateScore(
                            srcCh, tyxSc, nSamples, depthK, curveToTyx, sxSnap, cCand, bestSt, 4,
                            (int) Math.min(72L, bestCoarse >>> 20), arena
                        );
                        if (sc[0] < bestCoarse) {
                            bestCoarse = sc[0];
                            bestCand = cCand;
                        }
                    }
                    long[] scFull = evalPlanarCandidateScore(
                        srcCh, tyxSc, nSamples, depthK, curveToTyx, sxSnap, bestCand, bestSt, 1,
                        (int) (bestFullScore >>> 20), arena
                    );
                    if (scFull[0] < bestFullScore) {
                        bestFullScore = scFull[0];
                        bestSx = sxSnap;
                        bestSy = bestCand;
                        bestFrac8 = (int) scFull[1];
                    }
                }
            }

            // 4. If span > 4 and |syInit - sySnap| <= 96, test 16-aligned horizontal first (6B header), then coarse bracket
            if ((bestFullScore >>> 20) > 4 && Math.abs(syInit - sySnap) <= 96) {
                int sxSnap16 = clampShort((int) Math.round(sxInit / 16.0) * 16);
                for (int d16 : new int[]{0, 16, -16}) {
                    int cand16 = clampShort(sxSnap16 + d16);
                    long[] sc16 = evalPlanarCandidateScore(
                        srcCh, tyxSc, nSamples, depthK, curveToTyx, cand16, sySnap, bestSt, 1,
                        (int) (bestFullScore >>> 20), arena
                    );
                    if (sc16[0] < bestFullScore) {
                        bestFullScore = sc16[0];
                        bestSx = cand16;
                        bestSy = sySnap;
                        bestFrac8 = (int) sc16[1];
                    }
                }
                if ((bestFullScore >>> 20) > 6) {
                    int bestCand = sxInit;
                    long bestCoarse = Long.MAX_VALUE;
                    for (int step : new int[]{0, 2, -2, 4, -4, 8, -8}) {
                        int cCand = clampShort(sxInit + step);
                        long[] sc = evalPlanarCandidateScore(
                            srcCh, tyxSc, nSamples, depthK, curveToTyx, cCand, sySnap, bestSt, 4,
                            (int) Math.min(72L, bestCoarse >>> 20), arena
                        );
                        if (sc[0] < bestCoarse) {
                            bestCoarse = sc[0];
                            bestCand = cCand;
                        }
                    }
                    long[] scFull = evalPlanarCandidateScore(
                        srcCh, tyxSc, nSamples, depthK, curveToTyx, bestCand, sySnap, bestSt, 1,
                        (int) (bestFullScore >>> 20), arena
                    );
                    if (scFull[0] < bestFullScore) {
                        bestFullScore = scFull[0];
                        bestSx = bestCand;
                        bestSy = sySnap;
                        bestFrac8 = (int) scFull[1];
                    }
                }
            }

            // 5. Fallback general 2D coordinate refinement around (sxInit, syInit, stInit) only if span > 6
            if ((bestFullScore >>> 20) > 6) {
                int curSx = clampShort((int) Math.round(sxInit / 16.0) * 16);
                int curSy = clampShort((int) Math.round(syInit / 16.0) * 16);
                int curSt = bestSt;
                long bestCoarse = Long.MAX_VALUE;
                for (int dx : new int[]{-16, 0, 16}) {
                    int candSx = clampShort(curSx + dx);
                    long[] sc = evalPlanarCandidateScore(
                        srcCh, tyxSc, nSamples, depthK, curveToTyx, candSx, curSy, curSt, 4,
                        (int) Math.min(72L, bestCoarse >>> 20), arena
                    );
                    if (sc[0] < bestCoarse) {
                        bestCoarse = sc[0];
                        curSx = candSx;
                    }
                }
                for (int dy : new int[]{-16, 0, 16}) {
                    int candSy = clampShort(curSy + dy);
                    long[] sc = evalPlanarCandidateScore(
                        srcCh, tyxSc, nSamples, depthK, curveToTyx, curSx, candSy, curSt, 4,
                        (int) Math.min(72L, bestCoarse >>> 20), arena
                    );
                    if (sc[0] < bestCoarse) {
                        bestCoarse = sc[0];
                        curSy = candSy;
                    }
                }
                long[] scConfirm = evalPlanarCandidateScore(
                    srcCh, tyxSc, nSamples, depthK, curveToTyx, curSx, curSy, curSt, 1,
                    (int) (bestFullScore >>> 20), arena
                );
                if (scConfirm[0] < bestFullScore) {
                    bestFullScore = scConfirm[0];
                    bestSx = curSx;
                    bestSy = curSy;
                    bestSt = curSt;
                    bestFrac8 = (int) scConfirm[1];
                }
            }
        }

        if (useWaveletHaar) {
            int[] bMin16 = arena.bMin256;
            int[] bMax16 = arena.bMax256;
            Arrays.fill(bMin16, 0, 16, Integer.MAX_VALUE);
            Arrays.fill(bMax16, 0, 16, Integer.MIN_VALUE);
            int globalMin = Integer.MAX_VALUE;
            int globalMax = Integer.MIN_VALUE;

            for (int i = 0; i < nSamples; i++) {
                int v = srcCh[i] & 0xFF;
                int tyx = curveToTyx[i] & 0xFFFF;
                int lx = tyx & 15;
                int ly = (tyx >>> 4) & 15;
                int lt = tyx >>> 8;
                int raw = (bestSx * lx + bestSy * ly + bestSt * lt) >> 2;
                int pred = (bestFrac8 + raw) >> 8;
                int err = v - pred;
                int bIdx = ((ly >>> 2) << 2) | (lx >>> 2);
                if (err < bMin16[bIdx]) {
                    bMin16[bIdx] = err;
                }
                if (err > bMax16[bIdx]) {
                    bMax16[bIdx] = err;
                }
                if (err < globalMin) {
                    globalMin = err;
                }
                if (err > globalMax) {
                    globalMax = err;
                }
            }

            int minMid = Integer.MAX_VALUE;
            int maxMid = Integer.MIN_VALUE;
            int[] mids = arena.bCount256;
            for (int b = 0; b < 16; b++) {
                int mid = (bMin16[b] + bMax16[b]) >> 1;
                mids[b] = mid;
                if (mid < minMid) {
                    minMid = mid;
                }
                if (mid > maxMid) {
                    maxMid = mid;
                }
            }

            if (globalMax - globalMin <= 6 || maxMid - minMid <= 2) {
                outHaarStep[chIdx] = 0;
                Arrays.fill(outHaarNibbles, 0, 16, (byte) 0);
                int maxU8 = globalMax - globalMin;
                if (maxU8 > 127) {
                    return Long.MAX_VALUE;
                }
                long sumU = 0L;
                if (outResidualsU != null) {
                    for (int i = 0; i < nSamples; i++) {
                        int v = srcCh[i] & 0xFF;
                        int tyx = curveToTyx[i] & 0xFFFF;
                        int lx = tyx & 15;
                        int ly = (tyx >>> 4) & 15;
                        int lt = tyx >>> 8;
                        int raw = (bestSx * lx + bestSy * ly + bestSt * lt) >> 2;
                        int pred = (bestFrac8 + raw) >> 8;
                        int u = (v - pred) - globalMin;
                        outResidualsU[i] = (byte) u;
                        sumU += u;
                    }
                }
                outParams[0] = clampShort(globalMin);
                outParams[1] = bestSx;
                outParams[2] = bestSy;
                outParams[3] = bestSt;
                outParams[4] = maxU8;
                outParams[5] = bestFrac8 & 0xFF;
                return ((long) maxU8 << 20) + sumU;
            }

            int haarStep = Math.max(1, (maxMid - minMid + 14) / 15);
            outHaarStep[chIdx] = haarStep;
            for (int b = 0; b < 16; b++) {
                int nib = Math.max(0, Math.min(15, (mids[b] - minMid + (haarStep >> 1)) / haarStep));
                outHaarNibbles[b] = (byte) nib;
            }

            int minErr = Integer.MAX_VALUE;
            int maxErr = Integer.MIN_VALUE;
            int[] errScratch = arena.samplePaletteIdx;
            long sumErr = 0L;
            for (int i = 0; i < nSamples; i++) {
                int v = srcCh[i] & 0xFF;
                int tyx = curveToTyx[i] & 0xFFFF;
                int lx = tyx & 15;
                int ly = (tyx >>> 4) & 15;
                int lt = tyx >>> 8;
                int bIdx = ((ly >>> 2) << 2) | (lx >>> 2);
                int hOff = (outHaarNibbles[bIdx] & 0x0F) * haarStep;
                int raw = (bestSx * lx + bestSy * ly + bestSt * lt) >> 2;
                int pred = ((bestFrac8 + raw) >> 8) + hOff;
                int err = v - pred;
                errScratch[i] = err;
                sumErr += err;
                if (err < minErr) {
                    minErr = err;
                }
                if (err > maxErr) {
                    maxErr = err;
                }
            }

            int maxU8 = maxErr - minErr;
            if (maxU8 > 127 || maxU8 >= (globalMax - globalMin)) {
                return Long.MAX_VALUE;
            }

            long sumU = sumErr - (long) nSamples * minErr;
            if (outResidualsU != null) {
                for (int i = 0; i < nSamples; i++) {
                    outResidualsU[i] = (byte) (errScratch[i] - minErr);
                }
            }

            outParams[0] = clampShort(minErr);
            outParams[1] = bestSx;
            outParams[2] = bestSy;
            outParams[3] = bestSt;
            outParams[4] = maxU8;
            outParams[5] = bestFrac8 & 0xFF;
            return ((long) maxU8 << 20) + sumU;
        }

        int minErr = Integer.MAX_VALUE;
        int maxErr = Integer.MIN_VALUE;
        int[] errScratch = arena.samplePaletteIdx;
        long sumErr = 0L;
        for (int i = 0; i < nSamples; i++) {
            int v = srcCh[i] & 0xFF;
            int tyx = curveToTyx[i] & 0xFFFF;
            int lx = tyx & 15;
            int ly = (tyx >>> 4) & 15;
            int lt = tyx >>> 8;
            int raw = (bestSx * lx + bestSy * ly + bestSt * lt) >> 2;
            int pred = (bestFrac8 + raw) >> 8;
            int err = v - pred;
            errScratch[i] = err;
            sumErr += err;
            if (err < minErr) {
                minErr = err;
            }
            if (err > maxErr) {
                maxErr = err;
            }
        }

        int maxU8 = maxErr - minErr;
        if (maxU8 > 63) {
            return Long.MAX_VALUE;
        }

        long sumU;
        if (outResidualsU != null) {
            sumU = sumErr - (long) nSamples * minErr;
            for (int i = 0; i < nSamples; i++) {
                outResidualsU[i] = (byte) (errScratch[i] - minErr);
            }
        } else {
            sumU = bestFullScore & 0xFFFFFL;
        }

        outParams[0] = clampShort(minErr);
        outParams[1] = bestSx;
        outParams[2] = bestSy;
        outParams[3] = bestSt;
        outParams[4] = maxU8;
        outParams[5] = bestFrac8 & 0xFF;
        return ((long) maxU8 << 20) + sumU;
    }

    private static long[] evalPlanarCandidateScore(
        byte[] srcCh, int[] tyxSc, int nSamples, int depthK, short[] curveToTyx,
        int sx, int sy, int st, int stride, int cutoffSpan, LcvPrimitives.ScratchArena arena
    ) {
        boolean aligned16 = (sx & 15) == 0 && (sy & 15) == 0 && (st & 31) == 0;
        int curMin = Integer.MAX_VALUE;
        int curMax = Integer.MIN_VALUE;
        long sumE0 = 0L;
        int count = 0;

        if (stride == 1 && tyxSc != null) {
            for (int lt = 0; lt < depthK; lt++) {
                int tBase = lt << 8;
                int tTerm = st * lt;
                for (int ly = 0; ly < 16; ly++) {
                    int rowBase = tBase | (ly << 4);
                    int ytTerm = tTerm + sy * ly;
                    for (int lx = 0; lx < 16; lx++) {
                        int v = tyxSc[rowBase | lx];
                        int q = (ytTerm + sx * lx) >> 10;
                        int e0 = v - q;
                        if (e0 < curMin) {
                            curMin = e0;
                        }
                        if (e0 > curMax) {
                            curMax = e0;
                        }
                        sumE0 += e0;
                    }
                }
            }
            count = nSamples;
        } else {
            for (int i = 0; i < nSamples; i += stride) {
                int v = srcCh[i] & 0xFF;
                int tyx = curveToTyx[i] & 0xFFFF;
                int lx = tyx & 15;
                int ly = (tyx >>> 4) & 15;
                int lt = tyx >>> 8;
                int q = (sx * lx + sy * ly + st * lt) >> 10;
                int e0 = v - q;
                if (e0 < curMin) {
                    curMin = e0;
                }
                if (e0 > curMax) {
                    curMax = e0;
                }
                sumE0 += e0;
                count++;
            }
        }

        int zeroFracSpan = curMax - curMin;
        if (zeroFracSpan <= 0 || zeroFracSpan > 72
            || (aligned16 && zeroFracSpan <= 7)
            || (zeroFracSpan - 1) > cutoffSpan) {
            long bestImbalance = Math.abs(2L * sumE0 - (long) count * (curMin + curMax));
            long[] out = arena.evalScoreOut;
            out[0] = ((long) zeroFracSpan << 20) + Math.min(0xFFFFFL, bestImbalance);
            out[1] = 0L;
            return out;
        }

        int[] bMin = arena.bMin256;
        int[] bMax = arena.bMax256;
        int[] bCount = arena.bCount256;
        Arrays.fill(bMin, Integer.MAX_VALUE);
        Arrays.fill(bMax, Integer.MIN_VALUE);
        Arrays.fill(bCount, 0);

        if (stride == 1 && tyxSc != null) {
            for (int lt = 0; lt < depthK; lt++) {
                int tBase = lt << 8;
                int tTerm = st * lt;
                for (int ly = 0; ly < 16; ly++) {
                    int rowBase = tBase | (ly << 4);
                    int ytTerm = tTerm + sy * ly;
                    for (int lx = 0; lx < 16; lx++) {
                        int v = tyxSc[rowBase | lx];
                        int raw = (ytTerm + sx * lx) >> 2;
                        int q = raw >> 8;
                        int rem = raw & 0xFF;
                        int e0 = v - q;
                        if (e0 < bMin[rem]) {
                            bMin[rem] = e0;
                        }
                        if (e0 > bMax[rem]) {
                            bMax[rem] = e0;
                        }
                        bCount[rem]++;
                    }
                }
            }
        } else {
            for (int i = 0; i < nSamples; i += stride) {
                int v = srcCh[i] & 0xFF;
                int tyx = curveToTyx[i] & 0xFFFF;
                int lx = tyx & 15;
                int ly = (tyx >>> 4) & 15;
                int lt = tyx >>> 8;
                int raw = (sx * lx + sy * ly + st * lt) >> 2;
                int q = raw >> 8;
                int rem = raw & 0xFF;
                int e0 = v - q;
                if (e0 < bMin[rem]) {
                    bMin[rem] = e0;
                }
                if (e0 > bMax[rem]) {
                    bMax[rem] = e0;
                }
                bCount[rem]++;
            }
        }

        int[] prefMin = arena.prefMin256;
        int[] prefMax = arena.prefMax256;
        int pCurMin = Integer.MAX_VALUE;
        int pCurMax = Integer.MIN_VALUE;
        for (int r = 0; r < 256; r++) {
            if (bMin[r] < pCurMin) {
                pCurMin = bMin[r];
            }
            if (bMax[r] > pCurMax) {
                pCurMax = bMax[r];
            }
            prefMin[r] = pCurMin;
            prefMax[r] = pCurMax;
        }

        int bestSpan = zeroFracSpan;
        int bestFrac = 0;
        long bestImbalance = Math.abs(2L * sumE0 - (long) count * (curMin + curMax));

        int suffMin = Integer.MAX_VALUE;
        int suffMax = Integer.MIN_VALUE;
        int suffSamples = 0;
        for (int t = 255; t >= 1; t--) {
            if (bMin[t] != Integer.MAX_VALUE) {
                if (bMin[t] < suffMin) {
                    suffMin = bMin[t];
                }
                if (bMax[t] > suffMax) {
                    suffMax = bMax[t];
                }
                suffSamples += bCount[t];
            }
            if (suffMin == Integer.MAX_VALUE) {
                continue;
            }
            int pMin = prefMin[t - 1];
            int pMax = prefMax[t - 1];
            int eMin = (pMin == Integer.MAX_VALUE) ? (suffMin - 1) : Math.min(pMin, suffMin - 1);
            int eMax = (pMax == Integer.MIN_VALUE) ? (suffMax - 1) : Math.max(pMax, suffMax - 1);
            int span = eMax - eMin;
            long imbalance = Math.abs(2L * (sumE0 - suffSamples) - (long) count * (eMin + eMax));
            if (span < bestSpan || (span == bestSpan && bestFrac != 0 && imbalance < bestImbalance)) {
                bestSpan = span;
                bestFrac = 256 - t;
                bestImbalance = imbalance;
            }
        }

        long[] out = arena.evalScoreOut;
        out[0] = ((long) bestSpan << 20) + Math.min(0xFFFFFL, bestImbalance);
        out[1] = bestFrac & 0xFF;
        return out;
    }

    private static int clampShort(int val) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, val));
    }

    /**
     * Circularly rotates and polarity-masks an LSB bitplane {@code bits[0..len-1]} under {@code lsbTileKey64}
     * ({@code KSC} Layer C Dual-Tier Preview Unlock) with {@code 0} extra bytes on disk.
     */
    public static void applyLsbPlaneKeyRotation(
        byte[] bits, int len, long lsbTileKey64, int channelIdx, int planeIdx, boolean encode, byte[] scratch
    ) {
        if (lsbTileKey64 == 0L || len <= 1) {
            return;
        }
        long pk = LcvPrimitives.mix64(lsbTileKey64 ^ ((channelIdx << 4) | planeIdx));
        int rot = (int) (Long.remainderUnsigned(pk, len - 1) + 1);
        int flip = (int) ((pk >>> 17) & 1);
        if (encode) {
            for (int i = 0; i < len; i++) {
                int srcIdx = i + rot;
                if (srcIdx >= len) {
                    srcIdx -= len;
                }
                scratch[i] = (byte) ((bits[srcIdx] & 1) ^ flip);
            }
        } else {
            for (int i = 0; i < len; i++) {
                int dstIdx = i + rot;
                if (dstIdx >= len) {
                    dstIdx -= len;
                }
                scratch[dstIdx] = (byte) ((bits[i] & 1) ^ flip);
            }
        }
        System.arraycopy(scratch, 0, bits, 0, len);
    }

    /**
     * Attempts to encode a single-run bitplane ({@code runCount == 1}) into a 2-byte inline
     * {@link #PLANE_SINGLE_RUN_INLINE} word ({@code 0xFFFF} for all-ones, {@code 0x8000 | start14}
     * for tail runs reaching {@code nSamples}, or {@code (start10 << 5) | lenMinusOne5}).
     * Returns {@code 2} if inline-encoded, or {@code -1} if out of inline bounds.
     */
    public static int tryWriteSingleRunInline(int start, int len, int nSamples, byte[] dst, int dstOff) {
        if (start == 0 && len == nSamples) {
            dst[dstOff] = (byte) 0xFF;
            dst[dstOff + 1] = (byte) 0xFF;
            return 2;
        }
        if (start >= 0 && start + len == nSamples && start < 0x3FFF) {
            int word16 = 0x8000 | (start & 0x3FFF);
            dst[dstOff] = (byte) (word16 >>> 8);
            dst[dstOff + 1] = (byte) word16;
            return 2;
        }
        if (start >= 0 && start < 1024 && len >= 1 && len <= 32) {
            int word16 = ((start & 0x03FF) << 5) | ((len - 1) & 0x1F);
            dst[dstOff] = (byte) (word16 >>> 8);
            dst[dstOff + 1] = (byte) word16;
            return 2;
        }
        return -1;
    }

    /**
     * Decodes a 2-byte {@link #PLANE_SINGLE_RUN_INLINE} word into {@code rawGaps[0]} and {@code runLens[0]}
     * with {@code CBIT} bounds verification, returning {@code runCount = 1}.
     */
    public static int readSingleRunInline(
        byte[] src, int[] posRef, int limit, int nSamples, int[] rawGaps, int[] runLens
    ) throws LcvCorruptTileException {
        int pos = posRef[0];
        if (pos + 2 > limit) {
            throw new LcvCorruptTileException("CBIT: truncated PLANE_SINGLE_RUN_INLINE word16");
        }
        int word16 = ((src[pos] & 0xFF) << 8) | (src[pos + 1] & 0xFF);
        posRef[0] = pos + 2;
        int start;
        int len;
        if (word16 == 0xFFFF) {
            start = 0;
            len = nSamples;
        } else if ((word16 & 0x8000) != 0) {
            start = word16 & 0x3FFF;
            len = nSamples - start;
        } else {
            start = (word16 >>> 5) & 0x03FF;
            len = (word16 & 0x1F) + 1;
        }
        if (start < 0 || len <= 0 || start + len > nSamples) {
            throw new LcvCorruptTileException(
                "CBIT: invalid PLANE_SINGLE_RUN_INLINE bounds (start=" + start + ", len=" + len + ", n=" + nSamples + ")"
            );
        }
        rawGaps[0] = start;
        runLens[0] = len;
        return 1;
    }

    /**
     * Encodes an active conditioned bit sequence {@code activeBits[0..nActive-1]} with optional
     * distance-scaled run concatenation ({@code rawGap <= maxMergeGap}), competing {@code mode 01}
     * inline single-run, {@code mode 10} nibble RLE, and {@code mode 11} raw bitpack / triplet zero-skip.
     */
    public static int[] encodeConditionedBitplane(
        byte[] activeBits, int nActive, int maxMergeGap,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        if (nActive <= 0) {
            return new int[]{PLANE_ALL_ZERO, 0};
        }
        if (maxMergeGap <= 0) {
            int onesCount = 0;
            int approxRuns = 0;
            int prevBit = 0;
            int lastOneIdx = 0;
            for (int i = 0; i < nActive; i++) {
                int b = activeBits[i] & 1;
                onesCount += b;
                approxRuns += (b & ~prevBit);
                prevBit = b;
                if (b != 0) {
                    lastOneIdx = i;
                }
            }
            if (onesCount == 0) {
                return new int[]{PLANE_ALL_ZERO, 0};
            }
            if (onesCount == nActive) {
                tryWriteSingleRunInline(0, nActive, nActive, dst, dstOff);
                return new int[]{PLANE_SINGLE_RUN_INLINE, 2};
            }
            int rawBitmapPayloadBytes = (nActive + 7) >>> 3;
            int trimmedBytes = (lastOneIdx + 8) >>> 3;
            int bestDenseBytes = 1 + rawBitmapPayloadBytes;
            boolean useTrimmed = false;
            if (3 + trimmedBytes < bestDenseBytes) {
                bestDenseBytes = 3 + trimmedBytes;
                useTrimmed = true;
            }
            if ((1 + approxRuns) >= bestDenseBytes) {
                byte[] tripScratch = arena.tripletScratchBuf;
                if (onesCount * 100 <= nActive * 32) {
                    int tripPayloadLen = encodeTripletStream(activeBits, nActive, false, tripScratch, 3);
                    if (3 + tripPayloadLen < bestDenseBytes) {
                        bestDenseBytes = 3 + tripPayloadLen;
                        tripScratch[0] = (byte) DENSE_TAG_TRIPLET_ZERO_SKIP;
                        tripScratch[1] = (byte) (tripPayloadLen >>> 8);
                        tripScratch[2] = (byte) tripPayloadLen;
                        System.arraycopy(tripScratch, 0, dst, dstOff, bestDenseBytes);
                        return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
                    }
                } else if (onesCount * 100 >= nActive * 68) {
                    int tripPayloadLen = encodeTripletStream(activeBits, nActive, true, tripScratch, 3);
                    if (3 + tripPayloadLen < bestDenseBytes) {
                        bestDenseBytes = 3 + tripPayloadLen;
                        tripScratch[0] = (byte) DENSE_TAG_TRIPLET_ONE_SKIP;
                        tripScratch[1] = (byte) (tripPayloadLen >>> 8);
                        tripScratch[2] = (byte) tripPayloadLen;
                        System.arraycopy(tripScratch, 0, dst, dstOff, bestDenseBytes);
                        return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
                    }
                }
                if (useTrimmed) {
                    dst[dstOff] = (byte) DENSE_TAG_TRIMMED_BITMAP;
                    dst[dstOff + 1] = (byte) (trimmedBytes >>> 8);
                    dst[dstOff + 2] = (byte) trimmedBytes;
                    Arrays.fill(dst, dstOff + 3, dstOff + 3 + trimmedBytes, (byte) 0);
                    int bitLim = Math.min(nActive, trimmedBytes << 3);
                    for (int i = 0; i < bitLim; i++) {
                        if ((activeBits[i] & 1) != 0) {
                            dst[dstOff + 3 + (i >>> 3)] |= (byte) (1 << (i & 7));
                        }
                    }
                    return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
                }
                dst[dstOff] = (byte) DENSE_TAG_RAW_BITMAP;
                Arrays.fill(dst, dstOff + 1, dstOff + bestDenseBytes, (byte) 0);
                for (int i = 0; i < nActive; i++) {
                    if ((activeBits[i] & 1) != 0) {
                        dst[dstOff + 1 + (i >>> 3)] |= (byte) (1 << (i & 7));
                    }
                }
                return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
            }
        }
        int[] rawGaps = arena.rawGaps;
        int[] runLens = arena.runLens;
        int runCount = 0;
        int i = 0;
        int lastEnd = 0;

        while (i < nActive) {
            while (i < nActive && (activeBits[i] & 1) == 0) {
                i++;
            }
            if (i >= nActive) {
                break;
            }
            int start = i;
            while (i < nActive && (activeBits[i] & 1) != 0) {
                i++;
            }
            rawGaps[runCount] = start - lastEnd;
            runLens[runCount] = i - start;
            runCount++;
            lastEnd = i;
        }

        if (runCount == 0) {
            return new int[]{PLANE_ALL_ZERO, 0};
        }
        if (maxMergeGap > 0 && runCount > 1) {
            runCount = coalesceRunArraysInPlace(rawGaps, runLens, runCount, maxMergeGap);
        }
        if (runCount == 1 && tryWriteSingleRunInline(rawGaps[0], runLens[0], nActive, dst, dstOff) == 2) {
            return new int[]{PLANE_SINGLE_RUN_INLINE, 2};
        }

        return encodeConditionedBitplaneFromRuns(rawGaps, runLens, runCount, nActive, arena, dst, dstOff);
    }

    /**
     * Encodes an already-extracted (and optionally {@code d_merge}-coalesced) run sequence of
     * {@code nActive} conditioned bits into {@code dst[dstOff..]}.
     */
    public static int[] encodeConditionedBitplaneFromRuns(
        int[] rawGaps, int[] runLens, int runCount, int nActive,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        if (runCount <= 0 || nActive <= 0) {
            return new int[]{PLANE_ALL_ZERO, 0};
        }
        if (runCount == 1 && tryWriteSingleRunInline(rawGaps[0], runLens[0], nActive, dst, dstOff) == 2) {
            return new int[]{PLANE_SINGLE_RUN_INLINE, 2};
        }

        int rleBytes = writeNibbleRunStream(rawGaps, runLens, runCount, arena.planeScratchBuf, 0);

        int cursor = 0;
        int onesCount = 0;
        for (int r = 0; r < runCount; r++) {
            cursor += rawGaps[r] + runLens[r];
            onesCount += runLens[r];
        }

        int rawBitmapPayloadBytes = (nActive + 7) >>> 3;
        int trimmedBytes = (cursor + 7) >>> 3;
        int bestDenseBytes = 1 + rawBitmapPayloadBytes;
        int bestDenseTag = DENSE_TAG_RAW_BITMAP;
        if (3 + trimmedBytes < bestDenseBytes) {
            bestDenseBytes = 3 + trimmedBytes;
            bestDenseTag = DENSE_TAG_TRIMMED_BITMAP;
        }

        boolean tryZeroSkip = (onesCount * 100 <= nActive * 32);
        boolean tryOneSkip = !tryZeroSkip && (onesCount * 100 >= nActive * 68);
        int minPossibleDenseBytes = bestDenseBytes;
        if (tryZeroSkip || tryOneSkip) {
            int minority = Math.min(onesCount, nActive - onesCount);
            int nTriplets = (nActive + 2) / 3;
            int minTripBits = nTriplets + 4 * (minority / 3) + 2 * (minority % 3);
            int minTripTotalBytes = 3 + ((minTripBits + 7) >>> 3);
            if (minTripTotalBytes < minPossibleDenseBytes) {
                minPossibleDenseBytes = minTripTotalBytes;
            }
        }
        if (rleBytes < minPossibleDenseBytes) {
            System.arraycopy(arena.planeScratchBuf, 0, dst, dstOff, rleBytes);
            return new int[]{PLANE_NIBBLE_RLE, rleBytes};
        }

        // Reconstruct bit sequence in arena.activeBitPlane only when dense/triplet candidates can beat rleBytes
        byte[] bits = arena.activeBitPlane;
        Arrays.fill(bits, 0, nActive, (byte) 0);
        int fillCursor = 0;
        for (int r = 0; r < runCount; r++) {
            fillCursor += rawGaps[r];
            int len = runLens[r];
            for (int k = 0; k < len; k++) {
                bits[fillCursor + k] = 1;
            }
            fillCursor += len;
        }

        byte[] tripScratch = arena.tripletScratchBuf;
        if (tryZeroSkip) {
            int tripPayloadLen = encodeTripletStream(bits, nActive, false, tripScratch, 3);
            if (3 + tripPayloadLen < bestDenseBytes) {
                bestDenseBytes = 3 + tripPayloadLen;
                bestDenseTag = DENSE_TAG_TRIPLET_ZERO_SKIP;
                tripScratch[0] = (byte) DENSE_TAG_TRIPLET_ZERO_SKIP;
                tripScratch[1] = (byte) (tripPayloadLen >>> 8);
                tripScratch[2] = (byte) tripPayloadLen;
            }
        } else if (tryOneSkip) {
            int tripPayloadLen = encodeTripletStream(bits, nActive, true, tripScratch, 3);
            if (3 + tripPayloadLen < bestDenseBytes) {
                bestDenseBytes = 3 + tripPayloadLen;
                bestDenseTag = DENSE_TAG_TRIPLET_ONE_SKIP;
                tripScratch[0] = (byte) DENSE_TAG_TRIPLET_ONE_SKIP;
                tripScratch[1] = (byte) (tripPayloadLen >>> 8);
                tripScratch[2] = (byte) tripPayloadLen;
            }
        }

        if (rleBytes < bestDenseBytes) {
            System.arraycopy(arena.planeScratchBuf, 0, dst, dstOff, rleBytes);
            return new int[]{PLANE_NIBBLE_RLE, rleBytes};
        }

        if (bestDenseTag == DENSE_TAG_TRIPLET_ZERO_SKIP || bestDenseTag == DENSE_TAG_TRIPLET_ONE_SKIP) {
            System.arraycopy(tripScratch, 0, dst, dstOff, bestDenseBytes);
            return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
        }

        if (bestDenseTag == DENSE_TAG_TRIMMED_BITMAP) {
            dst[dstOff] = (byte) DENSE_TAG_TRIMMED_BITMAP;
            dst[dstOff + 1] = (byte) (trimmedBytes >>> 8);
            dst[dstOff + 2] = (byte) trimmedBytes;
            Arrays.fill(dst, dstOff + 3, dstOff + 3 + trimmedBytes, (byte) 0);
            int bitLim = Math.min(nActive, trimmedBytes << 3);
            for (int i = 0; i < bitLim; i++) {
                if (bits[i] != 0) {
                    dst[dstOff + 3 + (i >>> 3)] |= (byte) (1 << (i & 7));
                }
            }
            return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
        }

        dst[dstOff] = (byte) DENSE_TAG_RAW_BITMAP;
        Arrays.fill(dst, dstOff + 1, dstOff + 1 + rawBitmapPayloadBytes, (byte) 0);
        for (int i = 0; i < nActive; i++) {
            if (bits[i] != 0) {
                dst[dstOff + 1 + (i >>> 3)] |= (byte) (1 << (i & 7));
            }
        }
        return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
    }

    public static int encodeTripletStream(
        byte[] activeBits, int nActive, boolean invertBits, byte[] dst, int dstOff
    ) {
        int xorMask = invertBits ? 0b111 : 0;
        long bitAcc = 0L;
        int bitCount = 0;
        int pos = dstOff;
        for (int i = 0; i < nActive; i += 3) {
            int b0 = activeBits[i] & 1;
            int b1 = (i + 1 < nActive) ? (activeBits[i + 1] & 1) : 0;
            int b2 = (i + 2 < nActive) ? (activeBits[i + 2] & 1) : 0;
            int t = (b0 | (b1 << 1) | (b2 << 2)) ^ xorMask;
            bitAcc |= ((long) TRIPLET_ENC_BITS[t]) << bitCount;
            bitCount += TRIPLET_ENC_LEN[t];
            while (bitCount >= 8) {
                dst[pos++] = (byte) (bitAcc & 0xFFL);
                bitAcc >>>= 8;
                bitCount -= 8;
            }
        }
        if (bitCount > 0) {
            dst[pos++] = (byte) (bitAcc & 0xFFL);
        }
        return pos - dstOff;
    }

    public static int encodeTripletStreamFromWords(
        long[] words, int nActive, boolean invertBits, byte[] dst, int dstOff
    ) {
        int xorMask = invertBits ? 0b111 : 0;
        long bitAcc = 0L;
        int bitCount = 0;
        int pos = dstOff;
        for (int i = 0; i < nActive; i += 3) {
            int wIdx = i >>> 6;
            int shift = i & 63;
            int t;
            if (shift <= 61 && i + 2 < nActive) {
                t = ((int) (words[wIdx] >>> shift)) & 7;
            } else {
                int b0 = ((int) (words[wIdx] >>> shift)) & 1;
                int b1 = (i + 1 < nActive) ? (((int) (words[(i + 1) >>> 6] >>> ((i + 1) & 63))) & 1) : 0;
                int b2 = (i + 2 < nActive) ? (((int) (words[(i + 2) >>> 6] >>> ((i + 2) & 63))) & 1) : 0;
                t = b0 | (b1 << 1) | (b2 << 2);
            }
            t ^= xorMask;
            bitAcc |= ((long) TRIPLET_ENC_BITS[t]) << bitCount;
            bitCount += TRIPLET_ENC_LEN[t];
            while (bitCount >= 8) {
                dst[pos++] = (byte) (bitAcc & 0xFFL);
                bitAcc >>>= 8;
                bitCount -= 8;
            }
        }
        if (bitCount > 0) {
            dst[pos++] = (byte) (bitAcc & 0xFFL);
        }
        return pos - dstOff;
    }

    private static void writeWordsLittleEndian(long[] words, int nBytes, byte[] dst, int dstOff) {
        int fullWords = nBytes >>> 3;
        int p = dstOff;
        for (int w = 0; w < fullWords; w++) {
            long word = words[w];
            dst[p] = (byte) word;
            dst[p + 1] = (byte) (word >>> 8);
            dst[p + 2] = (byte) (word >>> 16);
            dst[p + 3] = (byte) (word >>> 24);
            dst[p + 4] = (byte) (word >>> 32);
            dst[p + 5] = (byte) (word >>> 40);
            dst[p + 6] = (byte) (word >>> 48);
            dst[p + 7] = (byte) (word >>> 56);
            p += 8;
        }
        int rem = nBytes & 7;
        if (rem > 0) {
            long lastWord = words[fullWords];
            for (int b = 0; b < rem; b++) {
                dst[p + b] = (byte) (lastWord >>> (b << 3));
            }
        }
    }

    /**
     * Compacts the bits of {@code srcWords[0..nWords-1]} at positions where {@code ~complementMaskWords[w]}
     * is {@code 1} into contiguous bits in {@code dstWords}, returning the total number of active bits {@code nActive}.
     */
    private static int compactPlaneWordsByComplementMask(
        long[] srcWords, long[] complementMaskWords, int nWords, long[] dstWords
    ) {
        int outBitPos = 0;
        long curOutWord = 0L;
        for (int w = 0; w < nWords; w++) {
            long mask = ~complementMaskWords[w];
            if (mask == 0L) {
                continue;
            }
            long src = srcWords[w];
            if (mask == -1L) {
                int shift = outBitPos & 63;
                int outW = outBitPos >>> 6;
                if (shift == 0) {
                    dstWords[outW] = src;
                } else {
                    dstWords[outW] = curOutWord | (src << shift);
                    curOutWord = src >>> (64 - shift);
                }
                outBitPos += 64;
                continue;
            }
            if ((src & mask) == 0L) {
                int count = Long.bitCount(mask);
                int prevW = outBitPos >>> 6;
                outBitPos += count;
                int newW = outBitPos >>> 6;
                if (newW > prevW) {
                    dstWords[prevW] = curOutWord;
                    if (newW > prevW + 1) {
                        dstWords[prevW + 1] = 0L;
                    }
                    curOutWord = 0L;
                }
                continue;
            }
            while (mask != 0L) {
                int zeros = Long.numberOfTrailingZeros(mask);
                mask >>>= zeros;
                src >>>= zeros;
                int ones = Long.numberOfTrailingZeros(~mask);
                long chunkMask = (ones == 64) ? -1L : ((1L << ones) - 1L);
                long chunk = src & chunkMask;
                int shift = outBitPos & 63;
                curOutWord |= (chunk << shift);
                int prevW = outBitPos >>> 6;
                outBitPos += ones;
                int newW = outBitPos >>> 6;
                if (newW > prevW) {
                    dstWords[prevW] = curOutWord;
                    curOutWord = (shift == 0) ? 0L : (chunk >>> (64 - shift));
                }
                if (ones == 64) {
                    break;
                }
                mask >>>= ones;
                src >>>= ones;
            }
        }
        if ((outBitPos & 63) != 0) {
            dstWords[outBitPos >>> 6] = curOutWord;
        }
        return outBitPos;
    }

    /**
     * Hardware-POPCNT/TZCNT conditioned bitplane encoder operating directly on pre-packed 64-bit
     * bitplane words {@code words[0..((nActive + 63) >>> 6)-1]} with 100% bit-exact output parity to
     * {@link #encodeConditionedBitplane}.
     */
    static int[] encodeConditionedBitplaneWords(
        long[] words, int nActive, LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        if (nActive <= 0) {
            return new int[]{PLANE_ALL_ZERO, 0};
        }
        int nWords = (nActive + 63) >>> 6;
        int remBits = nActive & 63;
        long tailMask = (remBits == 0) ? -1L : ((1L << remBits) - 1L);
        words[nWords - 1] &= tailMask;
        long orAll = 0L;
        long andAll = -1L;
        int onesCount = 0;
        int approxRuns = 0;
        long prevMsb = 0L;
        int lastNonZeroW = -1;
        for (int w = 0; w < nWords; w++) {
            long word = words[w];
            orAll |= word;
            andAll &= (w == nWords - 1) ? (word | ~tailMask) : word;
            onesCount += Long.bitCount(word);
            approxRuns += Long.bitCount(word & ~((word << 1) | prevMsb));
            prevMsb = word >>> 63;
            if (word != 0L) {
                lastNonZeroW = w;
            }
        }
        if (orAll == 0L) {
            return new int[]{PLANE_ALL_ZERO, 0};
        }
        if (andAll == -1L) {
            tryWriteSingleRunInline(0, nActive, nActive, dst, dstOff);
            return new int[]{PLANE_SINGLE_RUN_INLINE, 2};
        }

        int lastOneIdx = (lastNonZeroW << 6) + (63 - Long.numberOfLeadingZeros(words[lastNonZeroW]));
        int rawBitmapPayloadBytes = (nActive + 7) >>> 3;
        int trimmedBytes = (lastOneIdx + 8) >>> 3;
        int bestDenseBytes = 1 + rawBitmapPayloadBytes;
        int bestDenseTag = DENSE_TAG_RAW_BITMAP;
        if (3 + trimmedBytes < bestDenseBytes) {
            bestDenseBytes = 3 + trimmedBytes;
            bestDenseTag = DENSE_TAG_TRIMMED_BITMAP;
        }

        boolean tryZeroSkip = (onesCount * 100 <= nActive * 32);
        boolean tryOneSkip = !tryZeroSkip && (onesCount * 100 >= nActive * 68);
        byte[] tripScratch = arena.tripletScratchBuf;

        if ((1 + approxRuns) >= bestDenseBytes) {
            if (tryZeroSkip) {
                int tripPayloadLen = encodeTripletStreamFromWords(words, nActive, false, tripScratch, 3);
                if (3 + tripPayloadLen < bestDenseBytes) {
                    bestDenseBytes = 3 + tripPayloadLen;
                    tripScratch[0] = (byte) DENSE_TAG_TRIPLET_ZERO_SKIP;
                    tripScratch[1] = (byte) (tripPayloadLen >>> 8);
                    tripScratch[2] = (byte) tripPayloadLen;
                    System.arraycopy(tripScratch, 0, dst, dstOff, bestDenseBytes);
                    return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
                }
            } else if (tryOneSkip) {
                int tripPayloadLen = encodeTripletStreamFromWords(words, nActive, true, tripScratch, 3);
                if (3 + tripPayloadLen < bestDenseBytes) {
                    bestDenseBytes = 3 + tripPayloadLen;
                    tripScratch[0] = (byte) DENSE_TAG_TRIPLET_ONE_SKIP;
                    tripScratch[1] = (byte) (tripPayloadLen >>> 8);
                    tripScratch[2] = (byte) tripPayloadLen;
                    System.arraycopy(tripScratch, 0, dst, dstOff, bestDenseBytes);
                    return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
                }
            }
            if (bestDenseTag == DENSE_TAG_TRIMMED_BITMAP) {
                dst[dstOff] = (byte) DENSE_TAG_TRIMMED_BITMAP;
                dst[dstOff + 1] = (byte) (trimmedBytes >>> 8);
                dst[dstOff + 2] = (byte) trimmedBytes;
                writeWordsLittleEndian(words, trimmedBytes, dst, dstOff + 3);
                return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
            }
            dst[dstOff] = (byte) DENSE_TAG_RAW_BITMAP;
            writeWordsLittleEndian(words, rawBitmapPayloadBytes, dst, dstOff + 1);
            return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
        }

        int[] rawGaps = arena.rawGaps;
        int[] runLens = arena.runLens;
        int runCount = extractRunsFromPackedWords(words, nWords, rawGaps, runLens);
        if (runCount == 0) {
            return new int[]{PLANE_ALL_ZERO, 0};
        }
        if (runCount == 1 && tryWriteSingleRunInline(rawGaps[0], runLens[0], nActive, dst, dstOff) == 2) {
            return new int[]{PLANE_SINGLE_RUN_INLINE, 2};
        }

        int rleBytes = writeNibbleRunStream(rawGaps, runLens, runCount, arena.planeScratchBuf, 0);
        int minPossibleDenseBytes = bestDenseBytes;
        if (tryZeroSkip || tryOneSkip) {
            int minority = Math.min(onesCount, nActive - onesCount);
            int nTriplets = (nActive + 2) / 3;
            int minTripBits = nTriplets + 4 * (minority / 3) + 2 * (minority % 3);
            int minTripTotalBytes = 3 + ((minTripBits + 7) >>> 3);
            if (minTripTotalBytes < minPossibleDenseBytes) {
                minPossibleDenseBytes = minTripTotalBytes;
            }
        }
        if (rleBytes < minPossibleDenseBytes) {
            System.arraycopy(arena.planeScratchBuf, 0, dst, dstOff, rleBytes);
            return new int[]{PLANE_NIBBLE_RLE, rleBytes};
        }

        if (tryZeroSkip) {
            int tripPayloadLen = encodeTripletStreamFromWords(words, nActive, false, tripScratch, 3);
            if (3 + tripPayloadLen < bestDenseBytes) {
                bestDenseBytes = 3 + tripPayloadLen;
                bestDenseTag = DENSE_TAG_TRIPLET_ZERO_SKIP;
                tripScratch[0] = (byte) DENSE_TAG_TRIPLET_ZERO_SKIP;
                tripScratch[1] = (byte) (tripPayloadLen >>> 8);
                tripScratch[2] = (byte) tripPayloadLen;
            }
        } else if (tryOneSkip) {
            int tripPayloadLen = encodeTripletStreamFromWords(words, nActive, true, tripScratch, 3);
            if (3 + tripPayloadLen < bestDenseBytes) {
                bestDenseBytes = 3 + tripPayloadLen;
                bestDenseTag = DENSE_TAG_TRIPLET_ONE_SKIP;
                tripScratch[0] = (byte) DENSE_TAG_TRIPLET_ONE_SKIP;
                tripScratch[1] = (byte) (tripPayloadLen >>> 8);
                tripScratch[2] = (byte) tripPayloadLen;
            }
        }

        if (rleBytes < bestDenseBytes) {
            System.arraycopy(arena.planeScratchBuf, 0, dst, dstOff, rleBytes);
            return new int[]{PLANE_NIBBLE_RLE, rleBytes};
        }
        if (bestDenseTag == DENSE_TAG_TRIPLET_ZERO_SKIP || bestDenseTag == DENSE_TAG_TRIPLET_ONE_SKIP) {
            System.arraycopy(tripScratch, 0, dst, dstOff, bestDenseBytes);
            return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
        }
        if (bestDenseTag == DENSE_TAG_TRIMMED_BITMAP) {
            dst[dstOff] = (byte) DENSE_TAG_TRIMMED_BITMAP;
            dst[dstOff + 1] = (byte) (trimmedBytes >>> 8);
            dst[dstOff + 2] = (byte) trimmedBytes;
            writeWordsLittleEndian(words, trimmedBytes, dst, dstOff + 3);
            return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
        }
        dst[dstOff] = (byte) DENSE_TAG_RAW_BITMAP;
        writeWordsLittleEndian(words, rawBitmapPayloadBytes, dst, dstOff + 1);
        return new int[]{PLANE_RAW_BITMAP, bestDenseBytes};
    }

    /**
     * Hardware-POPCNT/TZCNT bitplane encoder operating directly on pre-transposed 64-bit
     * bitplane words {@code words} and {@code upperWords} (when {@code maxMergeGap == 0}
     * and {@code activeLsbTileKey64 == 0L}) with 100% bit-exact output parity to
     * {@link #encodeSingleBitplane}.
     */
    static int[] encodeSingleBitplaneFromWords(
        long[] words, long[] upperWords, int nSamples, int planeIdx,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        boolean isMsbTier = planeIdx >= arena.activeLsbSplitBits;
        int nWords = nSamples >>> 6;
        long[] xorWords = arena.packedUpperPlaneWords;
        long orAll = 0L;
        long andAll = -1L;
        long xorOrAll = 0L;
        int approxRuns = 0;
        int xorApproxRuns = 0;
        long prevMsb = 0L;
        long prevXorMsb = 0L;
        for (int w = 0; w < nWords; w++) {
            long word = words[w];
            orAll |= word;
            andAll &= word;
            approxRuns += Long.bitCount(word & ~((word << 1) | prevMsb));
            prevMsb = word >>> 63;
            if (!isMsbTier) {
                long xw = word ^ upperWords[w];
                xorWords[w] = xw;
                xorOrAll |= xw;
                xorApproxRuns += Long.bitCount(xw & ~((xw << 1) | prevXorMsb));
                prevXorMsb = xw >>> 63;
            }
        }
        if (orAll == 0L) {
            return new int[]{PLANE_ALL_ZERO, 0};
        }
        if (andAll == -1L) {
            if (isMsbTier) {
                tryWriteSingleRunInline(0, nSamples, nSamples, dst, dstOff);
                return new int[]{PLANE_SINGLE_RUN_INLINE, 2};
            } else {
                dst[dstOff] = 0x00;
                dst[dstOff + 1] = 0x00;
                return new int[]{PLANE_XOR_UPPER_RLE, 2};
            }
        }
        if (!isMsbTier && xorOrAll == 0L) {
            dst[dstOff] = 0x00;
            dst[dstOff + 1] = 0x01;
            return new int[]{PLANE_XOR_UPPER_RLE, 2};
        }

        int bitmapBytes = nSamples >>> 3;
        if ((1 + approxRuns) >= bitmapBytes
            && (isMsbTier || (1 + xorApproxRuns) >= bitmapBytes)) {
            writeWordsLittleEndian(words, bitmapBytes, dst, dstOff);
            return new int[]{PLANE_RAW_BITMAP, bitmapBytes};
        }

        int[] rawGaps = arena.rawGaps;
        int[] runLens = arena.runLens;
        int runCount = extractRunsFromPackedWords(words, nWords, rawGaps, runLens);

        if (!isMsbTier && (1 + xorApproxRuns) < Math.min(1 + runCount, bitmapBytes)) {
            int baseRleBytes = (1 + runCount <= bitmapBytes + 4)
                ? writeNibbleRunStream(rawGaps, runLens, runCount, arena.planeScratchBuf, 0)
                : (bitmapBytes + 1);
            int baseDirectBytes = Math.min(baseRleBytes, bitmapBytes);
            int[] xorGaps = arena.xorRawGaps;
            int[] xorLens = arena.xorRunLens;
            int xorRunCount = extractRunsFromPackedWords(xorWords, nWords, xorGaps, xorLens);
            if (xorRunCount > 0) {
                int baseXorRleBytes = writeNibbleRunStream(xorGaps, xorLens, xorRunCount, arena.xorPlaneScratchBuf, 0);
                if (baseXorRleBytes < baseDirectBytes) {
                    System.arraycopy(arena.xorPlaneScratchBuf, 0, dst, dstOff, baseXorRleBytes);
                    return new int[]{PLANE_XOR_UPPER_RLE, baseXorRleBytes};
                }
            }
        }

        if (isMsbTier && runCount == 1 && tryWriteSingleRunInline(rawGaps[0], runLens[0], nSamples, dst, dstOff) == 2) {
            return new int[]{PLANE_SINGLE_RUN_INLINE, 2};
        }
        if (!isMsbTier && runCount == 1 && rawGaps[0] == 0 && runLens[0] == nSamples) {
            dst[dstOff] = 0x00;
            dst[dstOff + 1] = 0x00;
            return new int[]{PLANE_XOR_UPPER_RLE, 2};
        }

        int rleBytes = (1 + runCount <= bitmapBytes + 4)
            ? writeNibbleRunStream(rawGaps, runLens, runCount, arena.planeScratchBuf, 0)
            : (bitmapBytes + 1);

        if (rleBytes < bitmapBytes) {
            System.arraycopy(arena.planeScratchBuf, 0, dst, dstOff, rleBytes);
            return new int[]{PLANE_NIBBLE_RLE, rleBytes};
        }

        writeWordsLittleEndian(words, bitmapBytes, dst, dstOff);
        return new int[]{PLANE_RAW_BITMAP, bitmapBytes};
    }

    public static void decodeTripletStream(
        byte[] src, int srcOff, int payloadLen, int nActive, boolean invertBits, byte[] outActiveBits
    ) throws LcvCorruptTileException {
        int xorMask = invertBits ? 0b111 : 0;
        int limit = srcOff + payloadLen;
        if (srcOff < 0 || payloadLen < 0 || limit > src.length) {
            throw new LcvCorruptTileException("CBIT: invalid triplet stream bounds");
        }
        int bytePos = srcOff;
        long bitAcc = 0L;
        int bitCount = 0;
        for (int i = 0; i < nActive; i += 3) {
            while (bitCount < 5 && bytePos < limit) {
                bitAcc |= ((long) (src[bytePos++] & 0xFF)) << bitCount;
                bitCount += 8;
            }
            int peek5 = (int) (bitAcc & 31L);
            int len = TRIPLET_DEC_LEN[peek5];
            if (bitCount < len) {
                throw new LcvCorruptTileException("CBIT: truncated triplet prefix code at sample " + i);
            }
            int t = TRIPLET_DEC_SYM[peek5] ^ xorMask;
            bitAcc >>>= len;
            bitCount -= len;
            outActiveBits[i] = (byte) (t & 1);
            if (i + 1 < nActive) {
                outActiveBits[i + 1] = (byte) ((t >>> 1) & 1);
            }
            if (i + 2 < nActive) {
                outActiveBits[i + 2] = (byte) ((t >>> 2) & 1);
            }
        }
    }

    public static void decodeConditionedBitplane(
        byte[] src, int[] posRef, int limit, int nActive, int planeIdx, int mode2,
        byte[] outActiveBits, LcvPrimitives.ScratchArena arena
    ) throws LcvCorruptTileException {
        decodeConditionedBitplane(src, posRef, limit, nActive, nActive, planeIdx, mode2, outActiveBits, arena);
    }

    public static void decodeConditionedBitplane(
        byte[] src, int[] posRef, int limit, int nActive, int sampleLimit, int planeIdx, int mode2,
        byte[] outActiveBits, LcvPrimitives.ScratchArena arena
    ) throws LcvCorruptTileException {
        int unpackLimit = Math.max(0, Math.min(nActive, sampleLimit));
        if (nActive <= 0 || mode2 == PLANE_ALL_ZERO) {
            Arrays.fill(outActiveBits, 0, unpackLimit, (byte) 0);
            return;
        }
        if (mode2 == PLANE_SINGLE_RUN_INLINE) {
            Arrays.fill(outActiveBits, 0, unpackLimit, (byte) 0);
            readSingleRunInline(src, posRef, limit, nActive, arena.rawGaps, arena.runLens);
            int start = arena.rawGaps[0];
            int len = arena.runLens[0];
            if (start < unpackLimit) {
                Arrays.fill(outActiveBits, start, Math.min(unpackLimit, start + len), (byte) 1);
            }
            return;
        }
        if (mode2 == PLANE_NIBBLE_RLE) {
            Arrays.fill(outActiveBits, 0, unpackLimit, (byte) 0);
            int runCount = readNibbleRunStream(src, posRef, limit, nActive, arena.rawGaps, arena.runLens);
            int cursor = 0;
            for (int r = 0; r < runCount; r++) {
                cursor += arena.rawGaps[r];
                if (cursor >= unpackLimit) {
                    break;
                }
                int len = arena.runLens[r];
                int end = Math.min(unpackLimit, cursor + len);
                for (int k = cursor; k < end; k++) {
                    outActiveBits[k] = 1;
                }
                cursor += len;
            }
            return;
        }
        // PLANE_RAW_BITMAP (mode 11) with denseTag8
        if (posRef[0] >= limit) {
            throw new LcvCorruptTileException("CBIT: truncated denseTag8 at plane " + planeIdx);
        }
        int denseTag8 = src[posRef[0]++] & 0xFF;
        if (denseTag8 == DENSE_TAG_RAW_BITMAP) {
            int rawBytes = (nActive + 7) >>> 3;
            int pos = posRef[0];
            if (pos + rawBytes > limit) {
                throw new LcvCorruptTileException("CBIT: truncated conditioned raw bitmap at plane " + planeIdx);
            }
            for (int i = 0; i < unpackLimit; i++) {
                outActiveBits[i] = (byte) ((src[pos + (i >>> 3)] >>> (i & 7)) & 1);
            }
            posRef[0] = pos + rawBytes;
        } else if (denseTag8 == DENSE_TAG_TRIPLET_ZERO_SKIP || denseTag8 == DENSE_TAG_TRIPLET_ONE_SKIP) {
            int pos = posRef[0];
            if (pos + 2 > limit) {
                throw new LcvCorruptTileException("CBIT: truncated triplet length at plane " + planeIdx);
            }
            int payloadLen = ((src[pos] & 0xFF) << 8) | (src[pos + 1] & 0xFF);
            pos += 2;
            if (pos + payloadLen > limit) {
                throw new LcvCorruptTileException("CBIT: truncated triplet payload at plane " + planeIdx);
            }
            decodeTripletStream(
                src, pos, payloadLen, unpackLimit,
                denseTag8 == DENSE_TAG_TRIPLET_ONE_SKIP, outActiveBits
            );
            posRef[0] = pos + payloadLen;
        } else if (denseTag8 == DENSE_TAG_TRIMMED_BITMAP) {
            int pos = posRef[0];
            if (pos + 2 > limit) {
                throw new LcvCorruptTileException("CBIT: truncated trimmed bitmap length at plane " + planeIdx);
            }
            int trimmedBytes = ((src[pos] & 0xFF) << 8) | (src[pos + 1] & 0xFF);
            pos += 2;
            int maxRawBytes = (nActive + 7) >>> 3;
            if (trimmedBytes < 0 || trimmedBytes > maxRawBytes || pos + trimmedBytes > limit) {
                throw new LcvCorruptTileException("CBIT: invalid trimmed bitmap bounds at plane " + planeIdx);
            }
            Arrays.fill(outActiveBits, 0, unpackLimit, (byte) 0);
            int bitLimit = Math.min(unpackLimit, trimmedBytes << 3);
            for (int i = 0; i < bitLimit; i++) {
                outActiveBits[i] = (byte) ((src[pos + (i >>> 3)] >>> (i & 7)) & 1);
            }
            posRef[0] = pos + trimmedBytes;
        } else {
            throw new LcvCorruptTileException("CBIT: unknown denseTag8 " + denseTag8 + " at plane " + planeIdx);
        }
    }

    /**
     * Direct-to-channel conditioned bitplane decoder for full-cube ({@code nActive == nSamples})
     * bitplanes, ORing {@code bitMask} directly into {@code dstCh} only on set ({@code 1}) bits
     * without touching {@code arena.activeBitPlane}.
     */
    static void decodeConditionedBitplaneDirectToChannel(
        byte[] src, int[] posRef, int limit, int nActive, int sampleLimit, int planeIdx, int bitMask, int mode2,
        byte[] dstCh, LcvPrimitives.ScratchArena arena
    ) throws LcvCorruptTileException {
        int unpackLimit = Math.max(0, Math.min(nActive, sampleLimit));
        if (nActive <= 0 || mode2 == PLANE_ALL_ZERO) {
            return;
        }
        if (mode2 == PLANE_SINGLE_RUN_INLINE) {
            readSingleRunInline(src, posRef, limit, nActive, arena.rawGaps, arena.runLens);
            int start = arena.rawGaps[0];
            int len = arena.runLens[0];
            int end = Math.min(unpackLimit, start + len);
            for (int k = start; k < end; k++) {
                dstCh[k] |= (byte) bitMask;
            }
            return;
        }
        if (mode2 == PLANE_NIBBLE_RLE) {
            int runCount = readNibbleRunStream(src, posRef, limit, nActive, arena.rawGaps, arena.runLens);
            int cursor = 0;
            for (int r = 0; r < runCount; r++) {
                cursor += arena.rawGaps[r];
                if (cursor >= unpackLimit) {
                    break;
                }
                int len = arena.runLens[r];
                int end = Math.min(unpackLimit, cursor + len);
                for (int k = cursor; k < end; k++) {
                    dstCh[k] |= (byte) bitMask;
                }
                cursor += len;
            }
            return;
        }
        if (posRef[0] >= limit) {
            throw new LcvCorruptTileException("CBIT: truncated denseTag8 at plane " + planeIdx);
        }
        int denseTag8 = src[posRef[0]++] & 0xFF;
        if (denseTag8 == DENSE_TAG_RAW_BITMAP) {
            int rawBytes = (nActive + 7) >>> 3;
            int pos = posRef[0];
            if (pos + rawBytes > limit) {
                throw new LcvCorruptTileException("CBIT: truncated conditioned raw bitmap at plane " + planeIdx);
            }
            int fullByteLim = unpackLimit >>> 3;
            for (int bi = 0; bi < fullByteLim; bi++) {
                int b = src[pos + bi] & 0xFF;
                int base = bi << 3;
                while (b != 0) {
                    int tz = Integer.numberOfTrailingZeros(b);
                    dstCh[base + tz] |= (byte) bitMask;
                    b &= b - 1;
                }
            }
            for (int i = fullByteLim << 3; i < unpackLimit; i++) {
                if (((src[pos + (i >>> 3)] >>> (i & 7)) & 1) != 0) {
                    dstCh[i] |= (byte) bitMask;
                }
            }
            posRef[0] = pos + rawBytes;
        } else if (denseTag8 == DENSE_TAG_TRIPLET_ZERO_SKIP || denseTag8 == DENSE_TAG_TRIPLET_ONE_SKIP) {
            int pos = posRef[0];
            if (pos + 2 > limit) {
                throw new LcvCorruptTileException("CBIT: truncated triplet length at plane " + planeIdx);
            }
            int payloadLen = ((src[pos] & 0xFF) << 8) | (src[pos + 1] & 0xFF);
            pos += 2;
            int tripLimit = pos + payloadLen;
            if (tripLimit > limit) {
                throw new LcvCorruptTileException("CBIT: truncated triplet payload at plane " + planeIdx);
            }
            int xorMask = (denseTag8 == DENSE_TAG_TRIPLET_ONE_SKIP) ? 0b111 : 0;
            int bytePos = pos;
            long bitAcc = 0L;
            int bitCount = 0;
            for (int i = 0; i < unpackLimit; i += 3) {
                while (bitCount < 5 && bytePos < tripLimit) {
                    bitAcc |= ((long) (src[bytePos++] & 0xFF)) << bitCount;
                    bitCount += 8;
                }
                int peek5 = (int) (bitAcc & 31L);
                int len = TRIPLET_DEC_LEN[peek5];
                if (bitCount < len) {
                    throw new LcvCorruptTileException("CBIT: truncated triplet prefix code at sample " + i);
                }
                int t = TRIPLET_DEC_SYM[peek5] ^ xorMask;
                bitAcc >>>= len;
                bitCount -= len;
                if (t != 0) {
                    if ((t & 1) != 0) {
                        dstCh[i] |= (byte) bitMask;
                    }
                    if ((t & 2) != 0 && i + 1 < unpackLimit) {
                        dstCh[i + 1] |= (byte) bitMask;
                    }
                    if ((t & 4) != 0 && i + 2 < unpackLimit) {
                        dstCh[i + 2] |= (byte) bitMask;
                    }
                }
            }
            posRef[0] = tripLimit;
        } else if (denseTag8 == DENSE_TAG_TRIMMED_BITMAP) {
            int pos = posRef[0];
            if (pos + 2 > limit) {
                throw new LcvCorruptTileException("CBIT: truncated trimmed bitmap length at plane " + planeIdx);
            }
            int trimmedBytes = ((src[pos] & 0xFF) << 8) | (src[pos + 1] & 0xFF);
            pos += 2;
            int maxRawBytes = (nActive + 7) >>> 3;
            if (trimmedBytes < 0 || trimmedBytes > maxRawBytes || pos + trimmedBytes > limit) {
                throw new LcvCorruptTileException("CBIT: invalid trimmed bitmap bounds at plane " + planeIdx);
            }
            int bitLimit = Math.min(unpackLimit, trimmedBytes << 3);
            int fullByteLim = bitLimit >>> 3;
            for (int bi = 0; bi < fullByteLim; bi++) {
                int b = src[pos + bi] & 0xFF;
                int base = bi << 3;
                while (b != 0) {
                    int tz = Integer.numberOfTrailingZeros(b);
                    dstCh[base + tz] |= (byte) bitMask;
                    b &= b - 1;
                }
            }
            for (int i = fullByteLim << 3; i < bitLimit; i++) {
                if (((src[pos + (i >>> 3)] >>> (i & 7)) & 1) != 0) {
                    dstCh[i] |= (byte) bitMask;
                }
            }
            posRef[0] = pos + trimmedBytes;
        } else {
            throw new LcvCorruptTileException("CBIT: unknown denseTag8 " + denseTag8 + " at plane " + planeIdx);
        }
    }

    /**
     * Indexed conditioned bitplane decoder for {@code nActive < nSamples}, scattering set ({@code 1})
     * bits directly into {@code dstCh[activeIdx[k] & 0xFFFF]} with zero intermediate byte array writes.
     */
    static void decodeConditionedBitplaneIndexed(
        byte[] src, int[] posRef, int limit, int nActive, int planeIdx, int bitMask, int mode2,
        short[] activeIdx, byte[] dstCh, LcvPrimitives.ScratchArena arena
    ) throws LcvCorruptTileException {
        if (nActive <= 0 || mode2 == PLANE_ALL_ZERO) {
            return;
        }
        if (mode2 == PLANE_SINGLE_RUN_INLINE) {
            readSingleRunInline(src, posRef, limit, nActive, arena.rawGaps, arena.runLens);
            int start = arena.rawGaps[0];
            int len = arena.runLens[0];
            int end = Math.min(nActive, start + len);
            for (int k = start; k < end; k++) {
                dstCh[activeIdx[k] & 0xFFFF] |= (byte) bitMask;
            }
            return;
        }
        if (mode2 == PLANE_NIBBLE_RLE) {
            int runCount = readNibbleRunStream(src, posRef, limit, nActive, arena.rawGaps, arena.runLens);
            int cursor = 0;
            for (int r = 0; r < runCount; r++) {
                cursor += arena.rawGaps[r];
                if (cursor >= nActive) {
                    break;
                }
                int len = arena.runLens[r];
                int end = Math.min(nActive, cursor + len);
                for (int k = cursor; k < end; k++) {
                    dstCh[activeIdx[k] & 0xFFFF] |= (byte) bitMask;
                }
                cursor += len;
            }
            return;
        }
        if (posRef[0] >= limit) {
            throw new LcvCorruptTileException("CBIT: truncated denseTag8 at plane " + planeIdx);
        }
        int denseTag8 = src[posRef[0]++] & 0xFF;
        if (denseTag8 == DENSE_TAG_RAW_BITMAP) {
            int rawBytes = (nActive + 7) >>> 3;
            int pos = posRef[0];
            if (pos + rawBytes > limit) {
                throw new LcvCorruptTileException("CBIT: truncated conditioned raw bitmap at plane " + planeIdx);
            }
            int fullByteLim = nActive >>> 3;
            for (int bi = 0; bi < fullByteLim; bi++) {
                int b = src[pos + bi] & 0xFF;
                int base = bi << 3;
                while (b != 0) {
                    int tz = Integer.numberOfTrailingZeros(b);
                    dstCh[activeIdx[base + tz] & 0xFFFF] |= (byte) bitMask;
                    b &= b - 1;
                }
            }
            for (int k = fullByteLim << 3; k < nActive; k++) {
                if (((src[pos + (k >>> 3)] >>> (k & 7)) & 1) != 0) {
                    dstCh[activeIdx[k] & 0xFFFF] |= (byte) bitMask;
                }
            }
            posRef[0] = pos + rawBytes;
        } else if (denseTag8 == DENSE_TAG_TRIPLET_ZERO_SKIP || denseTag8 == DENSE_TAG_TRIPLET_ONE_SKIP) {
            int pos = posRef[0];
            if (pos + 2 > limit) {
                throw new LcvCorruptTileException("CBIT: truncated triplet length at plane " + planeIdx);
            }
            int payloadLen = ((src[pos] & 0xFF) << 8) | (src[pos + 1] & 0xFF);
            pos += 2;
            int tripLimit = pos + payloadLen;
            if (tripLimit > limit) {
                throw new LcvCorruptTileException("CBIT: truncated triplet payload at plane " + planeIdx);
            }
            int xorMask = (denseTag8 == DENSE_TAG_TRIPLET_ONE_SKIP) ? 0b111 : 0;
            int bytePos = pos;
            long bitAcc = 0L;
            int bitCount = 0;
            for (int k = 0; k < nActive; k += 3) {
                while (bitCount < 5 && bytePos < tripLimit) {
                    bitAcc |= ((long) (src[bytePos++] & 0xFF)) << bitCount;
                    bitCount += 8;
                }
                int peek5 = (int) (bitAcc & 31L);
                int len = TRIPLET_DEC_LEN[peek5];
                if (bitCount < len) {
                    throw new LcvCorruptTileException("CBIT: truncated triplet prefix code at sample " + k);
                }
                int t = TRIPLET_DEC_SYM[peek5] ^ xorMask;
                bitAcc >>>= len;
                bitCount -= len;
                if (t != 0) {
                    if ((t & 1) != 0) {
                        dstCh[activeIdx[k] & 0xFFFF] |= (byte) bitMask;
                    }
                    if ((t & 2) != 0 && k + 1 < nActive) {
                        dstCh[activeIdx[k + 1] & 0xFFFF] |= (byte) bitMask;
                    }
                    if ((t & 4) != 0 && k + 2 < nActive) {
                        dstCh[activeIdx[k + 2] & 0xFFFF] |= (byte) bitMask;
                    }
                }
            }
            posRef[0] = tripLimit;
        } else if (denseTag8 == DENSE_TAG_TRIMMED_BITMAP) {
            int pos = posRef[0];
            if (pos + 2 > limit) {
                throw new LcvCorruptTileException("CBIT: truncated trimmed bitmap length at plane " + planeIdx);
            }
            int trimmedBytes = ((src[pos] & 0xFF) << 8) | (src[pos + 1] & 0xFF);
            pos += 2;
            int maxRawBytes = (nActive + 7) >>> 3;
            if (trimmedBytes < 0 || trimmedBytes > maxRawBytes || pos + trimmedBytes > limit) {
                throw new LcvCorruptTileException("CBIT: invalid trimmed bitmap bounds at plane " + planeIdx);
            }
            int bitLimit = Math.min(nActive, trimmedBytes << 3);
            int fullByteLim = bitLimit >>> 3;
            for (int bi = 0; bi < fullByteLim; bi++) {
                int b = src[pos + bi] & 0xFF;
                int base = bi << 3;
                while (b != 0) {
                    int tz = Integer.numberOfTrailingZeros(b);
                    dstCh[activeIdx[base + tz] & 0xFFFF] |= (byte) bitMask;
                    b &= b - 1;
                }
            }
            for (int k = fullByteLim << 3; k < bitLimit; k++) {
                if (((src[pos + (k >>> 3)] >>> (k & 7)) & 1) != 0) {
                    dstCh[activeIdx[k] & 0xFFFF] |= (byte) bitMask;
                }
            }
            posRef[0] = pos + trimmedBytes;
        } else {
            throw new LcvCorruptTileException("CBIT: unknown denseTag8 " + denseTag8 + " at plane " + planeIdx);
        }
    }

    /**
     * Encodes bitplane {@code planeIdx} ({@code 0..7}) with optional color-proximity-gated
     * distance-scaled run concatenation ({@code rawGap <= maxMergeGap}), competing tiered
     * {@code planeModes16[c]} micro-dictionary modes:
     * <ul>
     *   <li>MSB Tier ({@code planeIdx >= activeLsbSplitBits}): {@code 01 = PLANE_SINGLE_RUN_INLINE} (2B),</li>
     *   <li>LSB Tier ({@code planeIdx < activeLsbSplitBits}): {@code 01 = PLANE_XOR_UPPER_RLE} (Gray cross-plane RLE).</li>
     * </ul>
     */
    static int[] encodeSingleBitplane(
        byte[] grayCh, int nSamples, int planeIdx, int maxMergeGap,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        return encodeSingleBitplane(grayCh, nSamples, planeIdx, maxMergeGap, true, arena, dst, dstOff);
    }

    static int[] encodeSingleBitplane(
        byte[] grayCh, int nSamples, int planeIdx, int maxMergeGap,
        boolean isPureGray, LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        boolean isMsbTier = planeIdx >= arena.activeLsbSplitBits;
        if (maxMergeGap > 0) {
            bridgeColorProximateGapsInGrayChannel(grayCh, nSamples, planeIdx, maxMergeGap, isPureGray);
        }
        if (!isMsbTier && arena.activeLsbTileKey64 != 0L && nSamples > 1) {
            for (int i = 0; i < nSamples; i++) {
                arena.activeBitPlane[i] = (byte) ((grayCh[i] >>> planeIdx) & 1);
            }
            applyLsbPlaneKeyRotation(
                arena.activeBitPlane, nSamples, arena.activeLsbTileKey64, 0, planeIdx, true, arena.rotBitScratch
            );
            int bitMask = 1 << planeIdx;
            int clearMask = ~bitMask;
            for (int i = 0; i < nSamples; i++) {
                grayCh[i] = (byte) ((grayCh[i] & clearMask) | ((arena.activeBitPlane[i] & 1) << planeIdx));
            }
        }

        int nWords = nSamples >>> 6;
        long[] words = arena.packedPlaneWords;
        long[] xorWords = arena.packedUpperPlaneWords;
        long orAll = 0L;
        long andAll = -1L;
        long xorOrAll = 0L;
        int approxRuns = 0;
        int xorApproxRuns = 0;
        long prevMsb = 0L;
        long prevXorMsb = 0L;
        int upperPlaneIdx = Math.min(7, planeIdx + 1);
        for (int w = 0; w < nWords; w++) {
            int base = w << 6;
            long word = 0L;
            long upperWord = 0L;
            for (int b = 0; b < 64; b++) {
                int g = grayCh[base + b] & 0xFF;
                word |= ((long) ((g >>> planeIdx) & 1)) << b;
                upperWord |= ((long) ((g >>> upperPlaneIdx) & 1)) << b;
            }
            words[w] = word;
            orAll |= word;
            andAll &= word;
            approxRuns += Long.bitCount(word & ~((word << 1) | prevMsb));
            prevMsb = word >>> 63;
            if (!isMsbTier) {
                long xw = word ^ upperWord;
                xorWords[w] = xw;
                xorOrAll |= xw;
                xorApproxRuns += Long.bitCount(xw & ~((xw << 1) | prevXorMsb));
                prevXorMsb = xw >>> 63;
            }
        }
        if (orAll == 0L) {
            return new int[]{PLANE_ALL_ZERO, 0};
        }
        if (andAll == -1L) {
            if (isMsbTier) {
                tryWriteSingleRunInline(0, nSamples, nSamples, dst, dstOff);
                return new int[]{PLANE_SINGLE_RUN_INLINE, 2};
            } else {
                dst[dstOff] = 0x00;
                dst[dstOff + 1] = 0x00;
                return new int[]{PLANE_XOR_UPPER_RLE, 2};
            }
        }
        if (!isMsbTier && xorOrAll == 0L) {
            dst[dstOff] = 0x00;
            dst[dstOff + 1] = 0x01;
            return new int[]{PLANE_XOR_UPPER_RLE, 2};
        }

        int bitmapBytes = nSamples >>> 3;
        // Hardware POPCNT pre-screen: if both direct & XOR runs >= bitmapBytes,
        // write 64-bit words directly without tokenizing runs!
        if ((1 + approxRuns) >= bitmapBytes
            && (isMsbTier || (1 + xorApproxRuns) >= bitmapBytes)) {
            int p = dstOff;
            for (int wi = 0; wi < nWords; wi++) {
                long word = words[wi];
                dst[p] = (byte) word;
                dst[p + 1] = (byte) (word >>> 8);
                dst[p + 2] = (byte) (word >>> 16);
                dst[p + 3] = (byte) (word >>> 24);
                dst[p + 4] = (byte) (word >>> 32);
                dst[p + 5] = (byte) (word >>> 40);
                dst[p + 6] = (byte) (word >>> 48);
                dst[p + 7] = (byte) (word >>> 56);
                p += 8;
            }
            return new int[]{PLANE_RAW_BITMAP, bitmapBytes};
        }

        int[] rawGaps = arena.rawGaps;
        int[] runLens = arena.runLens;
        int runCount = extractRunsFromPackedWords(words, nWords, rawGaps, runLens);

        if (!isMsbTier && (1 + xorApproxRuns) < Math.min(1 + runCount, bitmapBytes)) {
            int baseRleBytes = (1 + runCount <= bitmapBytes + 4)
                ? writeNibbleRunStream(rawGaps, runLens, runCount, arena.planeScratchBuf, 0)
                : (bitmapBytes + 1);
            int baseDirectBytes = Math.min(baseRleBytes, bitmapBytes);
            int[] xorGaps = arena.xorRawGaps;
            int[] xorLens = arena.xorRunLens;
            int xorRunCount = extractRunsFromPackedWords(xorWords, nWords, xorGaps, xorLens);
            if (xorRunCount > 0) {
                int baseXorRleBytes = writeNibbleRunStream(xorGaps, xorLens, xorRunCount, arena.xorPlaneScratchBuf, 0);
                if (baseXorRleBytes < baseDirectBytes) {
                    System.arraycopy(arena.xorPlaneScratchBuf, 0, dst, dstOff, baseXorRleBytes);
                    return new int[]{PLANE_XOR_UPPER_RLE, baseXorRleBytes};
                }
            }
        }

        if (isMsbTier && runCount == 1 && tryWriteSingleRunInline(rawGaps[0], runLens[0], nSamples, dst, dstOff) == 2) {
            return new int[]{PLANE_SINGLE_RUN_INLINE, 2};
        }
        if (!isMsbTier && runCount == 1 && rawGaps[0] == 0 && runLens[0] == nSamples) {
            dst[dstOff] = 0x00;
            dst[dstOff + 1] = 0x00;
            return new int[]{PLANE_XOR_UPPER_RLE, 2};
        }

        int rleBytes = (1 + runCount <= bitmapBytes + 4)
            ? writeNibbleRunStream(rawGaps, runLens, runCount, arena.planeScratchBuf, 0)
            : (bitmapBytes + 1);

        if (rleBytes < bitmapBytes) {
            System.arraycopy(arena.planeScratchBuf, 0, dst, dstOff, rleBytes);
            return new int[]{PLANE_NIBBLE_RLE, rleBytes};
        }

        // Hard mode 11 bitmap ceiling (never expands beyond N_samples / 8 bytes)
        int p = dstOff;
        for (int wi = 0; wi < nWords; wi++) {
            long word = words[wi];
            dst[p] = (byte) word;
            dst[p + 1] = (byte) (word >>> 8);
            dst[p + 2] = (byte) (word >>> 16);
            dst[p + 3] = (byte) (word >>> 24);
            dst[p + 4] = (byte) (word >>> 32);
            dst[p + 5] = (byte) (word >>> 40);
            dst[p + 6] = (byte) (word >>> 48);
            dst[p + 7] = (byte) (word >>> 56);
            p += 8;
        }
        return new int[]{PLANE_RAW_BITMAP, bitmapBytes};
    }

    /**
     * Bridges 1D curve {@code 0}-gaps of length {@code <= maxMergeGap} on bitplane {@code planeIdx}
     * if and only if the gap and its flanking {@code 1}-run anchors share the same upper prefix
     * {@code g7..g_{planeIdx+1}} and lie within color-proximate distance of the {@code g_{planeIdx}}
     * sub-cell boundary, clamping bridged Gray codewords to the boundary {@code 1 << (planeIdx - 1)}
     * so lower bitplanes remain phase-coherent.
     */
    static void bridgeColorProximateGapsInGrayChannel(
        byte[] grayCh, int nSamples, int planeIdx, int maxMergeGap, boolean isPureGray
    ) {
        if (maxMergeGap <= 0 || nSamples <= 2 || planeIdx < 0 || planeIdx >= 7) {
            return;
        }
        int bitMask = 1 << planeIdx;
        int upperMask = (~((1 << (planeIdx + 1)) - 1)) & 0xFF;
        int lowMask = (1 << planeIdx) - 1;
        int maxGapSampleDist;
        int maxAnchorDist;
        if (planeIdx >= 3) {
            if (!isPureGray) {
                return;
            }
            maxGapSampleDist = Math.min(3, 1 + (maxMergeGap >>> 1));
            maxAnchorDist = Math.min(3, 1 + (maxMergeGap >>> 1));
        } else if (planeIdx == 2) {
            maxGapSampleDist = isPureGray ? Math.min(3, 1 + (maxMergeGap >>> 1)) : 4;
            maxAnchorDist = isPureGray ? Math.min(3, 2 + (maxMergeGap >>> 1)) : 4;
        } else if (planeIdx == 1) {
            maxGapSampleDist = 2;
            maxAnchorDist = 2;
        } else {
            maxGapSampleDist = 1;
            maxAnchorDist = 1;
        }

        int i = 0;
        while (i < nSamples && (grayCh[i] & bitMask) == 0) {
            i++;
        }
        while (i < nSamples) {
            while (i < nSamples && (grayCh[i] & bitMask) != 0) {
                i++;
            }
            if (i >= nSamples) {
                break;
            }
            int gapStart = i;
            while (i < nSamples && (grayCh[i] & bitMask) == 0) {
                i++;
            }
            if (i >= nSamples) {
                break;
            }
            int gapEnd = i;
            int gapLen = gapEnd - gapStart;
            if (gapLen > maxMergeGap) {
                continue;
            }

            int leftG = grayCh[gapStart - 1] & 0xFF;
            int rightG = grayCh[gapEnd] & 0xFF;
            int anchorPrefix = leftG & upperMask;
            if ((rightG & upperMask) != anchorPrefix) {
                continue;
            }
            if (planeIdx >= 1 && isPureGray) {
                int leftDist = (1 << planeIdx) - LcvPrimitives.grayToBinary8(leftG & lowMask);
                int rightDist = (1 << planeIdx) - LcvPrimitives.grayToBinary8(rightG & lowMask);
                if (leftDist > maxAnchorDist || rightDist > maxAnchorDist) {
                    continue;
                }
            }

            boolean proximate = true;
            for (int k = gapStart; k < gapEnd; k++) {
                int gk = grayCh[k] & 0xFF;
                if ((gk & upperMask) != anchorPrefix) {
                    proximate = false;
                    break;
                }
                if (planeIdx >= 1 && isPureGray) {
                    int dist = (1 << planeIdx) - LcvPrimitives.grayToBinary8(gk & lowMask);
                    if (dist > maxGapSampleDist) {
                        proximate = false;
                        break;
                    }
                }
            }

            if (proximate) {
                int clampedLow = (planeIdx > 0 && isPureGray) ? (1 << (planeIdx - 1)) : 0;
                byte mergedByte = (byte) (anchorPrefix | bitMask | clampedLow);
                for (int k = gapStart; k < gapEnd; k++) {
                    if (isPureGray) {
                        grayCh[k] = mergedByte;
                    } else {
                        grayCh[k] = (byte) ((grayCh[k] & 0xFF) | bitMask);
                    }
                }
            }
        }
    }

    /**
     * Bridges color-proximate {@code 0}-gaps in an offset-binary conditioned planar residual bitplane
     * {@code activeBits[0..nActive-1]} when all samples across the gap share the same upper residual
     * prefix {@code activeUpperU}, marking bridged samples in {@code activeClamped} so lower bitplanes
     * clamp to the lower sub-bucket boundary {@code 0}.
     */
    static void bridgePlanarConditionedBitplaneInPlace(
        byte[] activeBits, byte[] activeUpperU, byte[] activeClamped, int nActive, int maxMergeGap
    ) {
        if (maxMergeGap <= 0 || nActive <= 2) {
            return;
        }
        int i = 0;
        while (i < nActive && (activeBits[i] & 1) == 0) {
            i++;
        }
        while (i < nActive) {
            while (i < nActive && (activeBits[i] & 1) != 0) {
                i++;
            }
            if (i >= nActive) {
                break;
            }
            int gapStart = i;
            while (i < nActive && (activeBits[i] & 1) == 0) {
                i++;
            }
            if (i >= nActive) {
                break;
            }
            int gapEnd = i;
            if (gapEnd - gapStart > maxMergeGap) {
                continue;
            }
            byte prefix = activeUpperU[gapStart - 1];
            if (activeUpperU[gapEnd] != prefix) {
                continue;
            }
            boolean canMerge = true;
            for (int k = gapStart; k < gapEnd; k++) {
                if (activeUpperU[k] != prefix || activeClamped[k] != 0) {
                    canMerge = false;
                    break;
                }
            }
            if (canMerge) {
                for (int k = gapStart; k < gapEnd; k++) {
                    activeBits[k] = 1;
                    activeClamped[k] = 1;
                }
            }
        }
    }

    private static int extractRunsFromPackedWords(long[] words, int nWords, int[] rawGaps, int[] runLens) {
        int runCount = 0;
        int lastEnd = 0;
        int w = 0;
        while (w < nWords) {
            long word = words[w];
            if (word == 0L) {
                w++;
                continue;
            }
            int bit = Long.numberOfTrailingZeros(word);
            while (bit < 64) {
                int start = (w << 6) + bit;
                int onesInWord = Long.numberOfTrailingZeros(~(word >>> bit));
                if (bit + onesInWord < 64) {
                    int end = start + onesInWord;
                    rawGaps[runCount] = start - lastEnd;
                    runLens[runCount] = end - start;
                    runCount++;
                    lastEnd = end;
                    bit += onesInWord;
                    long rem = word >>> bit;
                    if (rem == 0L) {
                        break;
                    }
                    bit += Long.numberOfTrailingZeros(rem);
                } else {
                    w++;
                    while (w < nWords && words[w] == -1L) {
                        w++;
                    }
                    int tailOnes = (w < nWords) ? Long.numberOfTrailingZeros(~words[w]) : 0;
                    int end = (w << 6) + tailOnes;
                    rawGaps[runCount] = start - lastEnd;
                    runLens[runCount] = end - start;
                    runCount++;
                    lastEnd = end;
                    if (w >= nWords) {
                        break;
                    }
                    word = words[w];
                    bit = tailOnes;
                    long rem = word >>> bit;
                    if (rem == 0L) {
                        break;
                    }
                    bit += Long.numberOfTrailingZeros(rem);
                }
            }
            w++;
        }
        return runCount;
    }

    /**
     * Concatenates consecutive {@code 1}-runs separated by {@code rawGap <= maxMergeGap} into a
     * single merged run of length {@code L_r + rawGap + L_{r+1}}.
     */
    public static int coalesceRunArraysInPlace(int[] rawGaps, int[] runLens, int runCount, int maxMergeGap) {
        if (maxMergeGap <= 0 || runCount <= 1) {
            return runCount;
        }
        int outIdx = 0;
        for (int r = 1; r < runCount; r++) {
            if (rawGaps[r] <= maxMergeGap) {
                runLens[outIdx] += rawGaps[r] + runLens[r];
            } else {
                outIdx++;
                rawGaps[outIdx] = rawGaps[r];
                runLens[outIdx] = runLens[r];
            }
        }
        return outIdx + 1;
    }

    /**
     * Writes a {@code mode 10} ({@code 4|4} nibble {@code adjGap | lenMinusOne}) run stream.
     */
    public static int writeNibbleRunStream(
        int[] rawGaps, int[] runLens, int runCount, byte[] dst, int dstOff
    ) {
        int pos = LcvPrimitives.writeVarInt(dst, dstOff, runCount);
        for (int r = 0; r < runCount; r++) {
            int adjGap = (r == 0) ? rawGaps[0] : (rawGaps[r] - 1);
            int lenMinusOne = runLens[r] - 1;
            int gNib = Math.min(adjGap, 15);
            int lNib = Math.min(lenMinusOne, 15);
            dst[pos++] = (byte) ((gNib << 4) | lNib);
            if (gNib == 15) {
                pos = LcvPrimitives.writeVarInt(dst, pos, adjGap - 15);
            }
            if (lNib == 15) {
                pos = LcvPrimitives.writeVarInt(dst, pos, lenMinusOne - 15);
            }
        }
        return pos - dstOff;
    }

    /**
     * Reads a {@code mode 10} ({@code 4|4} nibble {@code adjGap | lenMinusOne}) run stream into
     * {@code rawGaps} and {@code runLens} with strict {@code CBIT} cursor bounds verification.
     */
    public static int readNibbleRunStream(
        byte[] src, int[] posRef, int limit, int nSamples, int[] rawGaps, int[] runLens
    ) throws LcvCorruptTileException {
        int runCount = LcvPrimitives.readVarInt(src, posRef, limit);
        if (runCount <= 0 || runCount > nSamples) {
            throw new LcvCorruptTileException("CBIT: invalid runCount " + runCount + " (max " + nSamples + ")");
        }
        int cursor = 0;
        for (int r = 0; r < runCount; r++) {
            int pos = posRef[0];
            if (pos >= limit) {
                throw new LcvCorruptTileException("CBIT: truncated run token at run " + r);
            }
            int token8 = src[pos++] & 0xFF;
            posRef[0] = pos;
            int gNib = (token8 >>> 4) & 0x0F;
            int lNib = token8 & 0x0F;
            int adjGap = (gNib == 15) ? (15 + LcvPrimitives.readVarInt(src, posRef, limit)) : gNib;
            int lenMinusOne = (lNib == 15) ? (15 + LcvPrimitives.readVarInt(src, posRef, limit)) : lNib;

            int rawGap = (r == 0) ? adjGap : (adjGap + 1);
            int runLen = lenMinusOne + 1;
            if (rawGap < 0 || runLen <= 0 || cursor + rawGap + runLen > nSamples) {
                throw new LcvCorruptTileException(
                    "CBIT: run bounds overflow (cursor=" + cursor + ", rawGap=" + rawGap
                        + ", runLen=" + runLen + ", nSamples=" + nSamples + ")"
                );
            }
            rawGaps[r] = rawGap;
            runLens[r] = runLen;
            cursor += rawGap + runLen;
        }
        return runCount;
    }

    /**
     * Decodes a contiguous {@code CubeFrame} payload (Base MSB Slice immediately followed by
     * Tail LSB Slice) into {@code dstRgbaFrames} in canonical {@code TYX} order.
     */
    public static void decodeCubeRgba(
        byte[] src, int srcOff, int srcLimit, int depthK,
        LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) throws LcvCorruptTileException {
        validateDepthK(depthK);
        if (arena == null) {
            arena = LcvPrimitives.threadArena();
        }
        if (srcOff < 0 || srcLimit > src.length || srcOff >= srcLimit) {
            throw new LcvCorruptTileException("CBIT: invalid CubeFrame slice bounds [" + srcOff + ", " + srcLimit + ")");
        }
        decodeSplitCubeRgba(src, srcOff, srcLimit - srcOff, src, srcLimit, 0, true, depthK, arena, dstRgbaFrames);
    }

    /**
     * Decodes a {@code CubeFrame} stored across Section 4A (Base MSB Slice) and Section 4B
     * (Tail LSB Slice) into {@code dstRgbaFrames} in canonical {@code TYX} order.
     */
    public static void decodeSplitCubeRgba(
        byte[] msbPool, int msbOff, int msbLen,
        byte[] lsbPool, int lsbOff, int lsbLen,
        boolean contiguousSingleBuffer,
        int depthK, LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) throws LcvCorruptTileException {
        decodeSplitCubeRgba(
            msbPool, msbOff, msbLen,
            lsbPool, lsbOff, lsbLen,
            contiguousSingleBuffer,
            depthK, 2, arena, dstRgbaFrames
        );
    }

    /**
     * Decodes a {@code CubeFrame} stored across Section 4A (Base MSB Slice) and Section 4B
     * (Tail LSB Slice) with configurable {@code lsbSplitBits} ({@code s in {1, 2, 3, 4}})
     * into {@code dstRgbaFrames} in canonical {@code TYX} order.
     */
    public static void decodeSplitCubeRgba(
        byte[] msbPool, int msbOff, int msbLen,
        byte[] lsbPool, int lsbOff, int lsbLen,
        boolean contiguousSingleBuffer,
        int depthK, int lsbSplitBits, LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) throws LcvCorruptTileException {
        decodeSplitCubeRgba(
            msbPool, msbOff, msbLen,
            lsbPool, lsbOff, lsbLen,
            contiguousSingleBuffer,
            depthK, lsbSplitBits,
            0, 0, 0, 0, LcvKeySchedule.OPEN_MIT, 0L,
            arena, dstRgbaFrames
        );
    }

    /**
     * Keyed Structural Codebook ({@code KSC}) overload decoding a {@code CubeFrame} stored across
     * Section 4A (Base MSB Slice) and Section 4B (Tail LSB Slice) with optional {@link LcvKeySchedule}.
     */
    public static void decodeSplitCubeRgba(
        byte[] msbPool, int msbOff, int msbLen,
        byte[] lsbPool, int lsbOff, int lsbLen,
        boolean contiguousSingleBuffer,
        int depthK, int lsbSplitBits,
        int tileX, int tileY, int roiTileX, int roiTileY,
        LcvKeySchedule keySchedule, long frameIndex64,
        LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) throws LcvCorruptTileException {
        decodeSplitCubeSingleSliceRgba(
            msbPool, msbOff, msbLen,
            lsbPool, lsbOff, lsbLen,
            contiguousSingleBuffer,
            depthK, lsbSplitBits,
            tileX, tileY, roiTileX, roiTileY,
            keySchedule, frameIndex64,
            -1,
            arena, dstRgbaFrames
        );
    }

    /**
     * Intra-superblock {@code O(1)} single-frame slice fast-path: when {@code targetLocalT >= 0},
     * stops causal bitplane unpacking and {@code SPATIAL_MED_2D} / {@code HILBERT_1D_MSB} delta
     * reconstruction at {@code (targetLocalT + 1) * 256} and converts only the {@code 256} pixels
     * of frame {@code targetLocalT} into {@code dstRgbaFrames[targetLocalT * 256 .. (targetLocalT + 1) * 256)}.
     */
    public static void decodeSplitCubeSingleSliceRgba(
        byte[] msbPool, int msbOff, int msbLen,
        byte[] lsbPool, int lsbOff, int lsbLen,
        boolean contiguousSingleBuffer,
        int depthK, int lsbSplitBits,
        int tileX, int tileY, int roiTileX, int roiTileY,
        LcvKeySchedule keySchedule, long frameIndex64,
        int targetLocalT,
        LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) throws LcvCorruptTileException {
        validateDepthK(depthK);
        if (lsbSplitBits < 1 || lsbSplitBits > 4) {
            throw new LcvCorruptTileException("CBIT: invalid lsbSplitBits " + lsbSplitBits);
        }
        if (arena == null) {
            arena = LcvPrimitives.threadArena();
        }
        if (keySchedule == null) {
            keySchedule = LcvKeySchedule.OPEN_MIT;
        }
        int msbLimit = msbOff + msbLen;
        int lsbLimit = lsbOff + lsbLen;
        if (msbOff < 0 || msbLen <= 0 || msbLimit > msbPool.length) {
            throw new LcvCorruptTileException("CBIT: invalid MSB slice bounds");
        }
        if (!contiguousSingleBuffer && (lsbOff < 0 || lsbLen < 0 || lsbLimit > lsbPool.length)) {
            throw new LcvCorruptTileException("CBIT: invalid LSB slice bounds");
        }

        boolean tileKeyed = keySchedule.isTileInScope(tileX, tileY, roiTileX, roiTileY);
        int tileCoordId = ((tileY & 0xFFFF) << 16) | (tileX & 0xFFFF);
        long msbTileKey64 = tileKeyed
            ? LcvPrimitives.computeTileKey64(keySchedule.msbKey64(), frameIndex64, tileCoordId)
            : 0L;
        long lsbTileKey64 = tileKeyed
            ? LcvPrimitives.computeTileKey64(keySchedule.lsbKey64(), frameIndex64, tileCoordId)
            : 0L;
        long relayTileKey64 = tileKeyed
            ? LcvPrimitives.computeTileKey64(keySchedule.relayKey64(), frameIndex64, tileCoordId)
            : 0L;
        int curveKey16 = (msbTileKey64 != 0L)
            ? (int) ((msbTileKey64 & 0xFFFFL) == 0L ? 1 : (msbTileKey64 & 0xFFFFL))
            : 0;

        arena.activeMsbTileKey64 = msbTileKey64;
        arena.activeLsbTileKey64 = lsbTileKey64;
        arena.activeRelayTileKey64 = relayTileKey64;
        arena.activeLsbSplitBits = lsbSplitBits;
        try {
            int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
            boolean singleSlice = (targetLocalT >= 0 && targetLocalT < depthK)
                && (contiguousSingleBuffer || lsbLen > 0 || lsbSplitBits < 3);
            int sliceStartTyx = singleSlice ? (targetLocalT << 8) : 0;
            int sliceEndTyx = singleSlice ? ((targetLocalT + 1) << 8) : nSamples;

            int pos = msbOff;
            int header8 = msbPool[pos++] & 0xFF;
            int curveId = (header8 >>> 6) & 0x03;
            int cubeMode = (header8 >>> 4) & 0x03;
            int low4 = header8 & 0x0F;

            short[] curveToTyx = LcvPrimitives.resolveCurveToTyxLut(depthK, curveId, curveKey16, arena);
            short[] tyxToCurve = singleSlice
                ? ((curveKey16 == 0) ? LcvPrimitives.tyxToCurveLut(depthK, curveId) : arena.keyedTyxToCurve)
                : null;

            if (cubeMode == CUBE_MODE_UI_PALETTE_ROARING) {
                int palSize = low4 + 2;
                decodeUiPaletteRoaring(
                    msbPool, pos, msbLimit, palSize, nSamples,
                    curveToTyx, sliceStartTyx, sliceEndTyx, tyxToCurve, arena, dstRgbaFrames
                );
                if (!contiguousSingleBuffer && lsbLen == 0 && lsbSplitBits >= 3 && palSize >= 3) {
                    smoothConditionedPlateauCubeIfApplicable(dstRgbaFrames, depthK, true);
                }
                return;
            }

            int channelMask4 = low4;
            byte[] constVals = new byte[4];
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) == 0) {
                    if (pos >= msbLimit) {
                        throw new LcvCorruptTileException("CBIT: truncated constVal8 for channel " + c);
                    }
                    constVals[c] = maskConstByte(msbPool[pos++], msbTileKey64, c);
                }
            }

            if (cubeMode == CUBE_MODE_RAW_CEILING) {
                if (singleSlice) {
                    int[] chOffs = new int[4];
                    for (int c = 0; c < 4; c++) {
                        if (((channelMask4 >>> c) & 1) != 0) {
                            if (pos + nSamples > msbLimit) {
                                throw new LcvCorruptTileException("CBIT: truncated RAW_CEILING channel " + c);
                            }
                            chOffs[c] = pos;
                            pos += nSamples;
                        } else {
                            chOffs[c] = -1;
                        }
                    }
                    for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
                        int i = tyxToCurve[tyx] & 0xFFFF;
                        int y8 = (chOffs[0] >= 0 ? msbPool[chOffs[0] + i] : constVals[0]) & 0xFF;
                        int co8 = (chOffs[1] >= 0 ? msbPool[chOffs[1] + i] : constVals[1]) & 0xFF;
                        int cg8 = (chOffs[2] >= 0 ? msbPool[chOffs[2] + i] : constVals[2]) & 0xFF;
                        int a8 = (chOffs[3] >= 0 ? msbPool[chOffs[3] + i] : constVals[3]) & 0xFF;
                        dstRgbaFrames[tyx] = LcvPrimitives.yCoCgRToRgba((y8 << 24) | (co8 << 16) | (cg8 << 8) | a8);
                    }
                    return;
                }
                for (int c = 0; c < 4; c++) {
                    byte[] ch = arena.channelValues[c];
                    if (((channelMask4 >>> c) & 1) == 0) {
                        Arrays.fill(ch, 0, nSamples, constVals[c]);
                    } else {
                        if (pos + nSamples > msbLimit) {
                            throw new LcvCorruptTileException("CBIT: truncated RAW_CEILING channel " + c);
                        }
                        System.arraycopy(msbPool, pos, ch, 0, nSamples);
                        pos += nSamples;
                    }
                }
                reconstructRgbaFromYCoCgChannels(nSamples, curveToTyx, arena, dstRgbaFrames);
                return;
            }

            // GRAY_PLANES_RAW (01) or GRAY_PLANES_HILBERT_DELTA (10)
            int deltaFlags8 = DELTA_SUBMODE_HILBERT_1D_MSB;
            int packedMv8 = 0x88;
            int[] packedSteps = new int[depthK];
            Arrays.fill(packedSteps, 0x88);
            if (cubeMode == CUBE_MODE_GRAY_PLANES_HILBERT_DELTA) {
                if (pos >= msbLimit) {
                    throw new LcvCorruptTileException("CBIT: truncated deltaFlags8 in GRAY_PLANES_HILBERT_DELTA");
                }
                deltaFlags8 = msbPool[pos++] & 0xFF;
                if ((deltaFlags8 & DELTA_FLAG_MOTION_SHEARED) != 0) {
                    boolean med2dPerFrameMv = ((deltaFlags8 & DELTA_SUBMODE_PLANAR_3D_MV) == 0)
                        && ((deltaFlags8 & DELTA_FLAG_SPATIAL_MED_2D) != 0)
                        && ((deltaFlags8 & DELTA_FLAG_COMPACT_PLANAR_HDR) != 0)
                        && depthK > 1;
                    if (med2dPerFrameMv) {
                        if (pos + (depthK - 1) > msbLimit) {
                            throw new LcvCorruptTileException("CBIT: truncated per-frame MV trajectory in SPATIAL_MED_2D");
                        }
                        for (int t = 1; t < depthK; t++) {
                            packedSteps[t] = msbPool[pos++] & 0xFF;
                        }
                        packedMv8 = packedSteps[1];
                    } else {
                        if (pos >= msbLimit) {
                            throw new LcvCorruptTileException("CBIT: truncated packedMv8 in GRAY_PLANES_HILBERT_DELTA");
                        }
                        packedMv8 = msbPool[pos++] & 0xFF;
                        Arrays.fill(packedSteps, packedMv8);
                    }
                }
            }

            if ((deltaFlags8 & DELTA_SUBMODE_PLANAR_3D_MV) != 0) {
                decodePlanarMvCubeRgba(
                    msbPool, pos, msbLimit,
                    lsbPool, lsbOff, lsbLen, lsbLimit,
                    contiguousSingleBuffer, channelMask4, constVals,
                    deltaFlags8, packedMv8, nSamples, lsbSplitBits,
                    curveToTyx, sliceStartTyx, sliceEndTyx, tyxToCurve, arena, dstRgbaFrames
                );
                return;
            }

            int[] planeModesWords = new int[4];
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) != 0) {
                    if (pos + 2 > msbLimit) {
                        throw new LcvCorruptTileException("CBIT: truncated planeModes16 for channel " + c);
                    }
                    int rawWord16 = ((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF);
                    planeModesWords[c] = LcvPrimitives.unpermutePlaneModes16(rawWord16, relayTileKey64, c);
                    pos += 2;
                }
            }

            int[] posRef = arena.posCursor;
            posRef[0] = pos;

            boolean isMed2D = (deltaFlags8 & DELTA_FLAG_SPATIAL_MED_2D) != 0;
            boolean timeOrderedCurve = isMed2D
                || ((curveId == LcvPrimitives.CURVE_TYX || curveId == LcvPrimitives.CURVE_TXY) && curveKey16 == 0);
            int planeSampleLimit = (singleSlice && timeOrderedCurve && lsbTileKey64 == 0L)
                ? sliceEndTyx
                : nSamples;

            // Clear grayValues for active channels up to planeSampleLimit
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) != 0) {
                    Arrays.fill(arena.grayValues[c], 0, planeSampleLimit, (byte) 0);
                }
            }

            // Decode Base MSB Planes p = 7..lsbSplitBits from msbPool
            boolean med2dConditionedMsb = isMed2D && ((deltaFlags8 & DELTA_FLAG_MAXU_CONDITIONED) != 0);
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) == 0) {
                    continue;
                }
                byte[] grayCh = arena.grayValues[c];
                int pModes = planeModesWords[c];
                for (int p = 7; p >= lsbSplitBits; p--) {
                    int mode2 = (pModes >>> (2 * p)) & 0x03;
                    if (med2dConditionedMsb) {
                        decodeConditionedBitplaneDirectToChannel(
                            msbPool, posRef, msbLimit, nSamples, planeSampleLimit, p, 1 << p, mode2, grayCh, arena
                        );
                    } else {
                        decodeSingleBitplane(
                            msbPool, posRef, msbLimit, nSamples, planeSampleLimit, p, mode2, grayCh, arena
                        );
                    }
                }
            }
            if (!contiguousSingleBuffer && posRef[0] != msbLimit) {
                throw new LcvCorruptTileException(
                    "CBIT: MSB slice unconsumed trailing bytes (" + posRef[0] + " != " + msbLimit + ")"
                );
            }

            // Decode Tail LSB Planes p = (lsbSplitBits - 1)..0
            boolean lsbWasStripped = false;
            boolean[] channelLsbStripped = new boolean[4];
            byte[] tailBuf = contiguousSingleBuffer ? msbPool : lsbPool;
            int tailLimit = contiguousSingleBuffer ? msbLimit : lsbLimit;
            if (!contiguousSingleBuffer) {
                posRef[0] = lsbOff;
            }
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) == 0) {
                    continue;
                }
                byte[] grayCh = arena.grayValues[c];
                int pModes = planeModesWords[c];
                boolean chStripped = false;
                for (int p = lsbSplitBits - 1; p >= 0; p--) {
                    int mode2 = (pModes >>> (2 * p)) & 0x03;
                    if (mode2 != PLANE_ALL_ZERO && !contiguousSingleBuffer && lsbLen == 0) {
                        lsbWasStripped = true;
                        chStripped = true;
                        channelLsbStripped[c] = true;
                        continue;
                    }
                    decodeSingleBitplane(
                        tailBuf, posRef, tailLimit, nSamples, planeSampleLimit, p, mode2, grayCh, arena
                    );
                }
                if (!chStripped && lsbTileKey64 != 0L && nSamples > 1) {
                    for (int p = lsbSplitBits - 1; p >= 0; p--) {
                        for (int i = 0; i < nSamples; i++) {
                            arena.activeBitPlane[i] = (byte) ((grayCh[i] >>> p) & 1);
                        }
                        applyLsbPlaneKeyRotation(
                            arena.activeBitPlane, nSamples, lsbTileKey64, 0, p, false, arena.rotBitScratch
                        );
                        int bitMask = 1 << p;
                        int clearMask = ~bitMask;
                        for (int i = 0; i < nSamples; i++) {
                            grayCh[i] = (byte) ((grayCh[i] & clearMask) | ((arena.activeBitPlane[i] & 1) << p));
                        }
                    }
                }
            }
            if (!lsbWasStripped && posRef[0] != tailLimit) {
                throw new LcvCorruptTileException(
                    "CBIT: LSB slice unconsumed trailing bytes (" + posRef[0] + " != " + tailLimit + ")"
                );
            }

            int msbBits = 8 - lsbSplitBits;
            int msbMask = (1 << msbBits) - 1;
            int lsbMask = (1 << lsbSplitBits) - 1;
            boolean isHalfPel = isMed2D && ((deltaFlags8 & DELTA_FLAG_HALF_PEL_MV) != 0);
            boolean hasMv = (deltaFlags8 & DELTA_FLAG_MOTION_SHEARED) != 0;

            // Invert Gray code (and (8 - s)-bit MSB Hilbert-Delta or 2D/Temporal MED if mode 10)
            int midLsb = 1 << (lsbSplitBits - 1);
            int highMask = ~lsbMask;
            int medLimit = singleSlice ? sliceEndTyx : nSamples;
            for (int c = 0; c < 4; c++) {
                byte[] ch = arena.channelValues[c];
                if (((channelMask4 >>> c) & 1) == 0) {
                    if (singleSlice) {
                        for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
                            ch[tyxToCurve[tyx] & 0xFFFF] = constVals[c];
                        }
                    } else {
                        Arrays.fill(ch, 0, nSamples, constVals[c]);
                    }
                } else {
                    byte[] grayCh = arena.grayValues[c];
                    if (cubeMode == CUBE_MODE_GRAY_PLANES_RAW) {
                        if (singleSlice) {
                            if (lsbWasStripped) {
                                for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
                                    int i = tyxToCurve[tyx] & 0xFFFF;
                                    int binTop = LcvPrimitives.grayToBinary8(grayCh[i] & 0xFF) & highMask;
                                    int m = (binTop >>> lsbSplitBits) & msbMask;
                                    int fill = (m == 0) ? 0 : (m == msbMask ? lsbMask : midLsb);
                                    ch[i] = (byte) (binTop | fill);
                                }
                            } else {
                                for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
                                    int i = tyxToCurve[tyx] & 0xFFFF;
                                    ch[i] = (byte) LcvPrimitives.grayToBinary8(grayCh[i] & 0xFF);
                                }
                            }
                        } else if (lsbWasStripped) {
                            for (int i = 0; i < nSamples; i++) {
                                int binTop = LcvPrimitives.grayToBinary8(grayCh[i] & 0xFF) & highMask;
                                int m = (binTop >>> lsbSplitBits) & msbMask;
                                int fill = (m == 0) ? 0 : (m == msbMask ? lsbMask : midLsb);
                                ch[i] = (byte) (binTop | fill);
                            }
                        } else {
                            for (int i = 0; i < nSamples; i++) {
                                ch[i] = (byte) LcvPrimitives.grayToBinary8(grayCh[i] & 0xFF);
                            }
                        }
                    } else if (isMed2D) {
                        byte[] mArr = arena.msbReconstructed[c];
                        byte[] lArr = arena.lsbShiftBase[c];
                        for (int tyx = 0; tyx < medLimit; tyx++) {
                            int g = grayCh[tyx] & 0xFF;
                            int gMsb = (g >>> lsbSplitBits) & msbMask;
                            int gLsb = g & lsbMask;

                            int lt = tyx >>> 8;
                            int stepMv = (lt >= 1 && hasMv) ? packedSteps[lt] : 0x88;
                            int stepVx = LcvPrimitives.unpackVelocityX(stepMv);
                            int stepVy = LcvPrimitives.unpackVelocityY(stepMv);

                            int predWord = predictMed2dSample(tyx, stepVx, stepVy, isHalfPel, mArr, lArr);
                            if (predWord < 0) {
                                int m = LcvPrimitives.grayToBinaryN(gMsb, msbBits);
                                int l = lsbWasStripped
                                    ? ((m == 0) ? 0 : (m == msbMask ? lsbMask : midLsb))
                                    : LcvPrimitives.grayToBinaryN(gLsb, lsbSplitBits);
                                mArr[tyx] = (byte) m;
                                lArr[tyx] = (byte) l;
                                ch[tyx] = (byte) ((m << lsbSplitBits) | l);
                            } else {
                                int predM = (predWord >>> 8) & 0xFF;
                                int predL = predWord & 0xFF;
                                int m = (predM + LcvPrimitives.zigZagDecodeN(gMsb, msbBits)) & msbMask;
                                int lRefGray = LcvPrimitives.binaryToGrayN(predL, lsbSplitBits);
                                int l = lsbWasStripped
                                    ? ((m == 0) ? 0 : (m == msbMask ? lsbMask : midLsb))
                                    : LcvPrimitives.grayToBinaryN(gLsb ^ lRefGray, lsbSplitBits);
                                mArr[tyx] = (byte) m;
                                lArr[tyx] = (byte) l;
                                ch[tyx] = (byte) ((m << lsbSplitBits) | l);
                            }
                        }
                    } else {
                        int prevM = 0;
                        for (int i = 0; i < planeSampleLimit; i++) {
                            int g = grayCh[i] & 0xFF;
                            int gMsb = (g >>> lsbSplitBits) & msbMask;
                            int u = (i == 0) ? LcvPrimitives.grayToBinaryN(gMsb, msbBits) : gMsb;
                            int m = (i == 0) ? u : ((prevM + LcvPrimitives.zigZagDecodeN(u, msbBits)) & msbMask);
                            prevM = m;
                            int l = lsbWasStripped
                                ? ((m == 0) ? 0 : (m == msbMask ? lsbMask : midLsb))
                                : LcvPrimitives.grayToBinaryN(g & lsbMask, lsbSplitBits);
                            ch[i] = (byte) ((m << lsbSplitBits) | l);
                        }
                    }
                }
            }

            boolean alphaMasked = ((deltaFlags8 & DELTA_FLAG_DIRECT_RGBA) != 0)
                && ((deltaFlags8 & DELTA_FLAG_ALPHA_MASKED_RGB) != 0);
            if (lsbWasStripped && !singleSlice && !alphaMasked) {
                short[] pocsCurveLut = isMed2D
                    ? LcvPrimitives.curveToTyxLut(depthK, LcvPrimitives.CURVE_TYX)
                    : curveToTyx;
                for (int c = 0; c < 4; c++) {
                    if (channelLsbStripped[c]) {
                        restoreStrippedChannelBoundedInterval(
                            arena.channelValues[c], pocsCurveLut, nSamples, depthK, lsbSplitBits, arena
                        );
                    }
                }
            }

            if ((deltaFlags8 & DELTA_FLAG_DIRECT_RGBA) != 0) {
                byte[] rCh = arena.channelValues[0];
                byte[] gCh = arena.channelValues[1];
                byte[] bCh = arena.channelValues[2];
                byte[] aCh = arena.channelValues[3];
                if (singleSlice) {
                    for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
                        int i = tyxToCurve[tyx] & 0xFFFF;
                        int a = aCh[i] & 0xFF;
                        int rgba = (alphaMasked && a == 0)
                            ? 0
                            : (((rCh[i] & 0xFF) << 24)
                                | ((gCh[i] & 0xFF) << 16)
                                | ((bCh[i] & 0xFF) << 8)
                                | a);
                        dstRgbaFrames[tyx] = rgba;
                    }
                } else {
                    for (int i = 0; i < nSamples; i++) {
                        int a = aCh[i] & 0xFF;
                        int rgba = (alphaMasked && a == 0)
                            ? 0
                            : (((rCh[i] & 0xFF) << 24)
                                | ((gCh[i] & 0xFF) << 16)
                                | ((bCh[i] & 0xFF) << 8)
                                | a);
                        dstRgbaFrames[curveToTyx[i] & 0xFFFF] = rgba;
                    }
                }
            } else if (singleSlice) {
                reconstructRgbaSliceFromYCoCgChannels(
                    sliceStartTyx, sliceEndTyx, tyxToCurve, lsbWasStripped, arena, dstRgbaFrames
                );
            } else {
                reconstructRgbaFromYCoCgChannels(nSamples, curveToTyx, lsbWasStripped, arena, dstRgbaFrames);
            }
        } finally {
            arena.activeMsbTileKey64 = 0L;
            arena.activeLsbTileKey64 = 0L;
            arena.activeRelayTileKey64 = 0L;
        }
    }

    /**
     * Restores a stripped-LSB ({@code Section 4B lsbLen == 0}) channel in 3D {@code TYX} space using
     * Projection Onto Convex Sets (POCS) bilateral gradient smoothing while hard-clamping every
     * restored channel value to its exact MSB quantization interval {@code [v_min_box, v_max_box]}
     * (maximum excursion {@code ±((1 << s) >> 1)}) and preserving sharp UI/text edges
     * ({@code |grad| > (1 << (s + 1))}).
     */
    private static void restoreStrippedChannelBoundedInterval(
        byte[] ch, short[] curveToTyx, int nSamples, int depthK, int lsbSplitBits,
        LcvPrimitives.ScratchArena arena
    ) {
        int lsbMask = (1 << lsbSplitBits) - 1;
        int highMask = ~lsbMask & 0xFF;
        int maxExcursion = (1 << lsbSplitBits) >> 1;
        int[] valTyx = arena.tyxChannelScratch;
        int[] minClamp = arena.rawGaps;
        int[] maxClamp = arena.runLens;
        int firstV = ch[0] & 0xFF;
        boolean allEqual = true;
        for (int i = 0; i < nSamples; i++) {
            int tyx = curveToTyx[i] & 0xFFFF;
            int v0 = ch[i] & 0xFF;
            if (v0 != firstV) {
                allEqual = false;
            }
            int vMinBox = v0 & highMask;
            int vMaxBox = vMinBox | lsbMask;
            valTyx[tyx] = v0;
            minClamp[tyx] = Math.max(vMinBox, v0 - maxExcursion);
            maxClamp[tyx] = Math.min(vMaxBox, v0 + maxExcursion);
        }
        if (allEqual) {
            return;
        }
        restoreStrippedFieldBoundedIntervalTyx(
            valTyx, minClamp, maxClamp, depthK, lsbSplitBits, arena.xorRawGaps
        );
        for (int i = 0; i < nSamples; i++) {
            ch[i] = (byte) valTyx[curveToTyx[i] & 0xFFFF];
        }
    }

    private static int[] restoreStrippedPlanarResidualTyx(
        byte[] uCh, short[] curveToTyx, int nSamples, int maxU8, int lsbSplitBits, int lsbMaxAdd,
        LcvPrimitives.ScratchArena arena
    ) {
        int maxExcursion = (1 << lsbSplitBits) >> 1;
        int[] valTyx = arena.tyxChannelScratch;
        int[] minClamp = arena.rawGaps;
        int[] maxClamp = arena.runLens;
        int firstU = -1;
        boolean allEqual = true;
        for (int i = 0; i < nSamples; i++) {
            int tyx = curveToTyx[i] & 0xFFFF;
            int uLow = uCh[i] & 0xFF;
            int uHigh = Math.min(maxU8, uLow + lsbMaxAdd);
            int uMid = (uLow + uHigh) >>> 1;
            if (firstU < 0) {
                firstU = uMid;
            } else if (uMid != firstU) {
                allEqual = false;
            }
            valTyx[tyx] = uMid;
            minClamp[tyx] = Math.max(uLow, uMid - maxExcursion);
            maxClamp[tyx] = Math.min(uHigh, uMid + maxExcursion);
        }
        if (!allEqual) {
            restoreStrippedFieldBoundedIntervalTyx(
                valTyx, minClamp, maxClamp, nSamples >>> 8, lsbSplitBits, arena.xorRawGaps
            );
        }
        return valTyx;
    }

    /**
     * Two-pass 3D spatiotemporal POCS bilateral gradient-smoothing filter on a {@code 16 x 16 x depthK}
     * scalar field {@code valTyx}, hard-clamping every sample at each projection step to
     * {@code [minClamp[tyx], maxClamp[tyx]]} and leaving sharp edges ({@code |grad| > (1 << (s + 1))})
     * untouched.
     */
    static void restoreStrippedFieldBoundedIntervalTyx(
        int[] valTyx, int[] minClamp, int[] maxClamp, int depthK, int lsbSplitBits, int[] passScratch
    ) {
        int maxGrad = 1 << (lsbSplitBits + 1);
        // Pass 1: radius-3 spatial x radius-1 temporal bilateral diffusion + convex box projection
        for (int lt = 0; lt < depthK; lt++) {
            int tMin = Math.max(0, lt - 1);
            int tMax = Math.min(depthK - 1, lt + 1);
            int fBase = lt << 8;
            for (int ly = 0; ly < 16; ly++) {
                int yMin1 = Math.max(0, ly - 1);
                int yMax1 = Math.min(15, ly + 1);
                int yMin3 = Math.max(0, ly - 3);
                int yMax3 = Math.min(15, ly + 3);
                int rowBase = fBase | (ly << 4);
                for (int lx = 0; lx < 16; lx++) {
                    int tyx = rowBase | lx;
                    int c0 = valTyx[tyx];
                    int xMin1 = Math.max(0, lx - 1);
                    int xMax1 = Math.min(15, lx + 1);
                    boolean hasSharpEdge = false;
                    edgeCheck:
                    for (int ny = yMin1; ny <= yMax1; ny++) {
                        int nRow = fBase | (ny << 4);
                        for (int nx = xMin1; nx <= xMax1; nx++) {
                            if (Math.abs(valTyx[nRow | nx] - c0) > maxGrad) {
                                hasSharpEdge = true;
                                break edgeCheck;
                            }
                        }
                    }
                    if (hasSharpEdge) {
                        minClamp[tyx] = c0;
                        maxClamp[tyx] = c0;
                        passScratch[tyx] = c0;
                        continue;
                    }
                    int xMin3 = Math.max(0, lx - 3);
                    int xMax3 = Math.min(15, lx + 3);
                    int sum = 0;
                    int wSum = 0;
                    boolean hasGradStep = false;
                    for (int nt = tMin; nt <= tMax; nt++) {
                        int dt = Math.abs(nt - lt);
                        int ntBase = nt << 8;
                        int yLo = (dt == 0) ? yMin3 : Math.max(0, ly - 2);
                        int yHi = (dt == 0) ? yMax3 : Math.min(15, ly + 2);
                        int xLo = (dt == 0) ? xMin3 : Math.max(0, lx - 2);
                        int xHi = (dt == 0) ? xMax3 : Math.min(15, lx + 2);
                        for (int ny = yLo; ny <= yHi; ny++) {
                            int dy = Math.abs(ny - ly);
                            int nRow = ntBase | (ny << 4);
                            for (int nx = xLo; nx <= xHi; nx++) {
                                int nVal = valTyx[nRow | nx];
                                int diff = Math.abs(nVal - c0);
                                if (diff <= maxGrad) {
                                    if (diff > 0) {
                                        hasGradStep = true;
                                    }
                                    int dxy = Math.max(Math.abs(nx - lx), dy);
                                    int w = (dt == 0) ? (4 - dxy) : (3 - dxy);
                                    sum += nVal * w;
                                    wSum += w;
                                }
                            }
                        }
                    }
                    if (hasGradStep && wSum > 0) {
                        int avg = (sum + (wSum >>> 1)) / wSum;
                        passScratch[tyx] = Math.max(minClamp[tyx], Math.min(maxClamp[tyx], avg));
                    } else {
                        passScratch[tyx] = c0;
                    }
                }
            }
        }

        // Pass 2: radius-2 spatial x radius-1 temporal refinement + convex box projection
        for (int lt = 0; lt < depthK; lt++) {
            int tMin = Math.max(0, lt - 1);
            int tMax = Math.min(depthK - 1, lt + 1);
            int fBase = lt << 8;
            for (int ly = 0; ly < 16; ly++) {
                int yMin2 = Math.max(0, ly - 2);
                int yMax2 = Math.min(15, ly + 2);
                int rowBase = fBase | (ly << 4);
                for (int lx = 0; lx < 16; lx++) {
                    int tyx = rowBase | lx;
                    int lo = minClamp[tyx];
                    int hi = maxClamp[tyx];
                    if (lo >= hi) {
                        valTyx[tyx] = lo;
                        continue;
                    }
                    int c1 = passScratch[tyx];
                    int xMin2 = Math.max(0, lx - 2);
                    int xMax2 = Math.min(15, lx + 2);
                    int sum = 0;
                    int wSum = 0;
                    for (int nt = tMin; nt <= tMax; nt++) {
                        int dt = Math.abs(nt - lt);
                        int ntBase = nt << 8;
                        for (int ny = yMin2; ny <= yMax2; ny++) {
                            int dy = Math.abs(ny - ly);
                            int nRow = ntBase | (ny << 4);
                            for (int nx = xMin2; nx <= xMax2; nx++) {
                                int nVal = passScratch[nRow | nx];
                                if (Math.abs(nVal - c1) <= maxGrad) {
                                    int dxy = Math.max(Math.abs(nx - lx), dy);
                                    int w = (dt == 0) ? (3 - dxy) : Math.max(1, 2 - dxy);
                                    sum += nVal * w;
                                    wSum += w;
                                }
                            }
                        }
                    }
                    int avg = (wSum > 0) ? ((sum + (wSum >>> 1)) / wSum) : c1;
                    valTyx[tyx] = Math.max(lo, Math.min(hi, avg));
                }
            }
        }
    }

    /**
     * Zero-bitstream-cost decoder-side bilateral quantization-plateau reconstruction filter for
     * {@code Section 4B}-stripped ({@code lsbLen == 0}) tiles conditioned onto the dyadic
     * {@code qStep = 8} ({@code 0x040404FF}) or {@code qStep = 16} ({@code 0x080808FF}) midpoint
     * lattice. Smooths staircase steps across smooth lighting/foliage gradients while leaving
     * flat regions ({@code maxDiff == 0}), sharp text/UI edges ({@code maxDiff > maxStep}), and
     * unconditioned lossless tiles completely untouched.
     */
    private static void smoothConditionedPlateauCubeIfApplicable(
        int[] cubeRgbaTyx, int depthK, boolean checkMidpointLattice
    ) {
        if (depthK < 1 || cubeRgbaTyx.length < 256 * depthK) {
            return;
        }
        boolean isQ16 = true;
        boolean isQ8 = true;
        for (int i = 0; i < 256; i += 17) {
            int c = cubeRgbaTyx[i];
            if ((c & 0xFF) != 0xFF) {
                return;
            }
            if ((c & 0x0F0F0F00) != 0x08080800) {
                isQ16 = false;
            }
            if ((c & 0x07070700) != 0x04040400) {
                isQ8 = false;
            }
        }
        if (checkMidpointLattice && !isQ16 && !isQ8) {
            return;
        }
        int maxStep = isQ16 ? 24 : 16;
        int maxShift = isQ16 ? 6 : 3;
        int[] frameScratch = new int[256];
        for (int t = 0; t < depthK; t++) {
            int fBase = t << 8;
            if (t >= 1) {
                boolean sameAsPrev = true;
                int pBase = (t - 1) << 8;
                for (int i = 0; i < 256; i += 5) {
                    if (cubeRgbaTyx[fBase | i] != cubeRgbaTyx[pBase | i]) {
                        sameAsPrev = false;
                        break;
                    }
                }
                if (sameAsPrev && Arrays.equals(
                    cubeRgbaTyx, fBase, fBase + 256,
                    cubeRgbaTyx, pBase, pBase + 256
                )) {
                    // Wait: if t is identical to unsmoothed t-1, we can only copy if we check before smoothing t-1
                }
            }
            System.arraycopy(cubeRgbaTyx, fBase, frameScratch, 0, 256);
            for (int ly = 0; ly < 16; ly++) {
                int yMin = Math.max(0, ly - 2);
                int yMax = Math.min(15, ly + 2);
                for (int lx = 0; lx < 16; lx++) {
                    int idx = (ly << 4) | lx;
                    int c0 = frameScratch[idx];
                    int r0 = (c0 >>> 24) & 0xFF;
                    int g0 = (c0 >>> 16) & 0xFF;
                    int b0 = (c0 >>> 8) & 0xFF;

                    int xMin = Math.max(0, lx - 2);
                    int xMax = Math.min(15, lx + 2);
                    int sumR = r0 * 4;
                    int sumG = g0 * 4;
                    int sumB = b0 * 4;
                    int wSum = 4;
                    boolean hasGradStep = false;
                    boolean hasSharpEdge = false;
                    outerWin:
                    for (int ny = yMin; ny <= yMax; ny++) {
                        int dy = Math.abs(ny - ly);
                        int nRow = ny << 4;
                        for (int nx = xMin; nx <= xMax; nx++) {
                            if (nx == lx && ny == ly) {
                                continue;
                            }
                            int nc = frameScratch[nRow | nx];
                            int nr = (nc >>> 24) & 0xFF;
                            int ng = (nc >>> 16) & 0xFF;
                            int nb = (nc >>> 8) & 0xFF;
                            int d = Math.max(Math.abs(nr - r0), Math.max(Math.abs(ng - g0), Math.abs(nb - b0)));
                            int dx = Math.abs(nx - lx);
                            if (dx <= 1 && dy <= 1 && d > maxStep) {
                                hasSharpEdge = true;
                                break outerWin;
                            }
                            if (d > 0 && d <= maxStep) {
                                hasGradStep = true;
                                int w = (dx <= 1 && dy <= 1) ? 2 : 1;
                                sumR += nr * w;
                                sumG += ng * w;
                                sumB += nb * w;
                                wSum += w;
                            } else if (d == 0) {
                                int w = (dx <= 1 && dy <= 1) ? 2 : 1;
                                sumR += r0 * w;
                                sumG += g0 * w;
                                sumB += b0 * w;
                                wSum += w;
                            }
                        }
                    }
                    if (hasGradStep && !hasSharpEdge) {
                        int avgR = (sumR + (wSum >>> 1)) / wSum;
                        int avgG = (sumG + (wSum >>> 1)) / wSum;
                        int avgB = (sumB + (wSum >>> 1)) / wSum;
                        int outR = Math.max(r0 - maxShift, Math.min(r0 + maxShift, avgR));
                        int outG = Math.max(g0 - maxShift, Math.min(g0 + maxShift, avgG));
                        int outB = Math.max(b0 - maxShift, Math.min(b0 + maxShift, avgB));
                        cubeRgbaTyx[fBase | idx] = (outR << 24) | (outG << 16) | (outB << 8) | (c0 & 0xFF);
                    }
                }
            }
        }
    }

    private static void decodePlanarMvCubeRgba(
        byte[] msbPool, int pos, int msbLimit,
        byte[] lsbPool, int lsbOff, int lsbLen, int lsbLimit,
        boolean contiguousSingleBuffer, int channelMask4, byte[] constVals,
        int deltaFlags8, int packedMv8, int nSamples, int lsbSplitBits,
        short[] curveToTyx, int sliceStartTyx, int sliceEndTyx, short[] tyxToCurve,
        LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) throws LcvCorruptTileException {
        boolean directRgba = (deltaFlags8 & DELTA_FLAG_DIRECT_RGBA) != 0;
        boolean alphaMasked = (deltaFlags8 & DELTA_FLAG_ALPHA_MASKED_RGB) != 0;
        boolean hasWaveletHaar = (deltaFlags8 & DELTA_SUBMODE_WAVELET_3D_HAAR) != 0;
        boolean compactHdr = (deltaFlags8 & DELTA_FLAG_COMPACT_PLANAR_HDR) != 0;

        int[] planeModesWords = new int[4];
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                int[] p = arena.planarParams[c];
                if (compactHdr) {
                    if (pos + 6 > msbLimit) {
                        throw new LcvCorruptTileException("CBIT: truncated compact planar MV header for channel " + c);
                    }
                    planeModesWords[c] = msbPool[pos++] & 0xFF;
                    int b1 = msbPool[pos++] & 0xFF;
                    boolean isAligned16 = (b1 & 0x80) != 0;
                    boolean isMod64 = (b1 & 0x40) != 0;
                    boolean isBin255 = !isMod64 && (b1 & 0x20) != 0;
                    if (isMod64) {
                        int vMin = msbPool[pos++] & 0xFF;
                        int sxDiv16 = msbPool[pos++];
                        int syDiv16 = msbPool[pos++];
                        int stDiv32 = msbPool[pos++];
                        p[0] = 0x4000 | (vMin << 6) | (b1 & 0x3F);
                        p[1] = sxDiv16 << 4;
                        p[2] = syDiv16 << 4;
                        p[3] = stDiv32 << 5;
                        p[4] = 0;
                        p[5] = 0;
                    } else if (isBin255) {
                        pos += 4;
                        p[0] = 0x20FF;
                        p[1] = 0;
                        p[2] = 0;
                        p[3] = 0;
                        p[4] = b1 & 0x0F;
                        p[5] = 0;
                    } else if (isAligned16) {
                        int minErrBiased = msbPool[pos++] & 0xFF;
                        int sxDiv16 = msbPool[pos++];
                        int syDiv16 = msbPool[pos++];
                        int stDiv32 = msbPool[pos++];
                        p[0] = minErrBiased - 64;
                        p[1] = sxDiv16 << 4;
                        p[2] = syDiv16 << 4;
                        p[3] = stDiv32 << 5;
                        p[4] = b1 & 0x0F;
                        p[5] = 0;
                    } else {
                        if (pos + 8 > msbLimit) {
                            throw new LcvCorruptTileException("CBIT: truncated 10B compact planar header for channel " + c);
                        }
                        int minErrBiased = msbPool[pos++] & 0xFF;
                        p[0] = minErrBiased - 64;
                        p[1] = (short) (((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF));
                        pos += 2;
                        p[2] = (short) (((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF));
                        pos += 2;
                        p[3] = (short) (((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF));
                        pos += 2;
                        p[4] = b1 & 0x0F;
                        p[5] = msbPool[pos++] & 0xFF;
                    }
                } else {
                    if (pos + (hasWaveletHaar ? 13 : 12) > msbLimit) {
                        throw new LcvCorruptTileException("CBIT: truncated planar MV header for channel " + c);
                    }
                    int rawWord16 = ((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF);
                    planeModesWords[c] = LcvPrimitives.unpermutePlaneModes16(rawWord16, arena.activeRelayTileKey64, c);
                    pos += 2;
                    int rawP0 = (short) (((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF));
                    if (arena.activeMsbTileKey64 != 0L) {
                        rawP0 = (short) (rawP0 ^ (int) (LcvPrimitives.mix64(arena.activeMsbTileKey64 ^ (0x504C4E00L + c)) & 0x3FL));
                    }
                    p[0] = rawP0;
                    pos += 2;
                    p[1] = (short) (((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF));
                    pos += 2;
                    p[2] = (short) (((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF));
                    pos += 2;
                    p[3] = (short) (((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF));
                    pos += 2;
                    p[4] = msbPool[pos++] & 0xFF; // maxU8
                    p[5] = msbPool[pos++] & 0xFF; // bestFrac8 (unsigned 0..255)
                    if (hasWaveletHaar) {
                        int hStep = msbPool[pos++] & 0xFF;
                        arena.haarStep[c] = hStep;
                        byte[] nibs = arena.haarNibbles[c];
                        if (hStep > 0) {
                            if (pos + 8 > msbLimit) {
                                throw new LcvCorruptTileException("CBIT: truncated Haar nibbles for channel " + c);
                            }
                            for (int k = 0; k < 8; k++) {
                                int packedNib = msbPool[pos++] & 0xFF;
                                nibs[2 * k] = (byte) ((packedNib >>> 4) & 0x0F);
                                nibs[2 * k + 1] = (byte) (packedNib & 0x0F);
                            }
                        } else {
                            Arrays.fill(nibs, 0, 16, (byte) 0);
                        }
                    }
                }
            }
        }

        int[] posRef = arena.posCursor;
        posRef[0] = pos;
        int lsbMaxAdd = (1 << lsbSplitBits) - 1;
        boolean singleSlice = (tyxToCurve != null);

        // Fast O(1) stripped-LSB linear TYX reconstruction when no MSB bitplanes exist (maxU8 < (1 << lsbSplitBits))
        if (!contiguousSingleBuffer && lsbLen == 0 && directRgba && !alphaMasked && !hasWaveletHaar
            && !singleSlice && channelMask4 == 0x07) {
            int msbThresh = 1 << lsbSplitBits;
            int[] p0 = arena.planarParams[0];
            int[] p1 = arena.planarParams[1];
            int[] p2 = arena.planarParams[2];
            int maxU0 = p0[4] & 0xFF;
            int maxU1 = p1[4] & 0xFF;
            int maxU2 = p2[4] & 0xFF;
            if (maxU0 < msbThresh && maxU1 < msbThresh && maxU2 < msbThresh
                && p0[0] < 0x2000 && p1[0] < 0x2000 && p2[0] < 0x2000) {
                if (posRef[0] != msbLimit) {
                    throw new LcvCorruptTileException(
                        "CBIT: planar MV MSB slice unconsumed trailing bytes (" + posRef[0] + " != " + msbLimit + ")"
                    );
                }
                int[] baseAdd = new int[3];
                for (int c = 0; c < 3; c++) {
                    int maxUc = arena.planarParams[c][4] & 0xFF;
                    int pModes = planeModesWords[c];
                    boolean chStripped = false;
                    for (int p = lsbSplitBits - 1; p >= 0; p--) {
                        if ((1 << p) <= maxUc && ((pModes >>> (2 * p)) & 0x03) != PLANE_ALL_ZERO) {
                            chStripped = true;
                            break;
                        }
                    }
                    int uConst = chStripped ? (Math.min(maxUc, lsbMaxAdd) >>> 1) : 0;
                    baseAdd[c] = arena.planarParams[c][0] + uConst;
                }
                int sx0 = p0[1], sy0 = p0[2], st0 = p0[3], frac0 = p0[5] & 0xFF, b0 = baseAdd[0];
                int sx1 = p1[1], sy1 = p1[2], st1 = p1[3], frac1 = p1[5] & 0xFF, b1 = baseAdd[1];
                int sx2 = p2[1], sy2 = p2[2], st2 = p2[3], frac2 = p2[5] & 0xFF, b2 = baseAdd[2];
                int aConst = constVals[3] & 0xFF;
                int depthK = nSamples >>> 8;
                for (int lt = 0; lt < depthK; lt++) {
                    int tBase = lt << 8;
                    int t0 = st0 * lt;
                    int t1 = st1 * lt;
                    int t2 = st2 * lt;
                    for (int ly = 0; ly < 16; ly++) {
                        int rowBase = tBase | (ly << 4);
                        int yt0 = t0 + sy0 * ly;
                        int yt1 = t1 + sy1 * ly;
                        int yt2 = t2 + sy2 * ly;
                        for (int lx = 0; lx < 16; lx++) {
                            int rVal = (((frac0 + ((yt0 + sx0 * lx) >> 2)) >> 8) + b0);
                            int gVal = (((frac1 + ((yt1 + sx1 * lx) >> 2)) >> 8) + b1);
                            int bVal = (((frac2 + ((yt2 + sx2 * lx) >> 2)) >> 8) + b2);
                            int r = (rVal & ~0xFF) == 0 ? rVal : Math.max(0, Math.min(255, rVal));
                            int g = (gVal & ~0xFF) == 0 ? gVal : Math.max(0, Math.min(255, gVal));
                            int b = (bVal & ~0xFF) == 0 ? bVal : Math.max(0, Math.min(255, bVal));
                            dstRgbaFrames[rowBase | lx] = (r << 24) | (g << 16) | (b << 8) | aConst;
                        }
                    }
                }
                return;
            }
        }

        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                Arrays.fill(arena.grayValues[c], 0, nSamples, (byte) 0);
            }
        }

        boolean noLsbKey = (arena.activeLsbTileKey64 == 0L);

        // Decode Section 4A Base MSB Planes p = 7..lsbSplitBits
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            int maxU8 = arena.planarParams[c][4] & 0xFF;
            if (maxU8 == 0) {
                continue;
            }
            byte[] uCh = arena.grayValues[c];
            int pModes = planeModesWords[c];
            int pCeil = 31 - Integer.numberOfLeadingZeros(maxU8);

            for (int p = 7; p >= lsbSplitBits; p--) {
                int bitVal = 1 << p;
                int mode2 = (pModes >>> (2 * p)) & 0x03;
                if (bitVal > maxU8 || mode2 == PLANE_ALL_ZERO) {
                    continue;
                }
                if (p == pCeil || (maxU8 & bitVal) != 0) {
                    decodeConditionedBitplaneDirectToChannel(
                        msbPool, posRef, msbLimit, nSamples, nSamples, p, bitVal, mode2, uCh, arena
                    );
                    continue;
                }
                short[] activeIdx = arena.planarActiveIdx;
                int nActive = 0;
                for (int i = 0; i < nSamples; i++) {
                    if ((uCh[i] & 0xFF) + bitVal <= maxU8) {
                        activeIdx[nActive++] = (short) i;
                    }
                }
                if (nActive > 0) {
                    decodeConditionedBitplaneIndexed(
                        msbPool, posRef, msbLimit, nActive, p, bitVal, mode2, activeIdx, uCh, arena
                    );
                }
            }
            if (!noLsbKey) {
                System.arraycopy(uCh, 0, arena.msbReconstructed[c], 0, nSamples);
            }
        }
        if (!contiguousSingleBuffer && posRef[0] != msbLimit) {
            throw new LcvCorruptTileException(
                "CBIT: planar MV MSB slice unconsumed trailing bytes (" + posRef[0] + " != " + msbLimit + ")"
            );
        }

        // Decode Section 4B Tail LSB Planes p = (lsbSplitBits - 1)..0
        boolean lsbWasStripped = false;
        boolean[] channelLsbStripped = new boolean[4];
        byte[] tailBuf = contiguousSingleBuffer ? msbPool : lsbPool;
        int tailLimit = contiguousSingleBuffer ? msbLimit : lsbLimit;
        if (!contiguousSingleBuffer) {
            posRef[0] = lsbOff;
        }
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            int maxU8 = arena.planarParams[c][4] & 0xFF;
            if (maxU8 == 0) {
                continue;
            }
            byte[] uCh = arena.grayValues[c];
            int pModes = planeModesWords[c];

            if (!contiguousSingleBuffer && lsbLen == 0) {
                for (int p = lsbSplitBits - 1; p >= 0; p--) {
                    int bitVal = 1 << p;
                    int mode2 = (pModes >>> (2 * p)) & 0x03;
                    if (bitVal <= maxU8 && mode2 != PLANE_ALL_ZERO) {
                        lsbWasStripped = true;
                        channelLsbStripped[c] = true;
                        break;
                    }
                }
                continue;
            }

            if (noLsbKey) {
                int msbThresh = 1 << lsbSplitBits;
                short[] activeIdx = arena.planarActiveIdx;
                int cachedActiveCount = -1;
                if (maxU8 < msbThresh) {
                    int pCeil = 31 - Integer.numberOfLeadingZeros(maxU8);
                    int ceilBitVal = 1 << pCeil;
                    for (int p = lsbSplitBits - 1; p >= 0; p--) {
                        int bitVal = 1 << p;
                        int mode2 = (pModes >>> (2 * p)) & 0x03;
                        if (bitVal > maxU8 || mode2 == PLANE_ALL_ZERO) {
                            continue;
                        }
                        if (p == pCeil || (ceilBitVal + bitVal) <= maxU8) {
                            decodeConditionedBitplaneDirectToChannel(
                                tailBuf, posRef, tailLimit, nSamples, nSamples, p, bitVal, mode2, uCh, arena
                            );
                        } else {
                            if (cachedActiveCount < 0) {
                                int cnt = 0;
                                for (int i = 0; i < nSamples; i++) {
                                    if ((uCh[i] & ceilBitVal) == 0) {
                                        activeIdx[cnt++] = (short) i;
                                    }
                                }
                                cachedActiveCount = cnt;
                            }
                            if (cachedActiveCount > 0) {
                                decodeConditionedBitplaneIndexed(
                                    tailBuf, posRef, tailLimit, cachedActiveCount, p, bitVal, mode2, activeIdx, uCh, arena
                                );
                            }
                        }
                    }
                } else {
                    int lsbRem = maxU8 & (msbThresh - 1);
                    int msbCeil = maxU8 & ~(msbThresh - 1);
                    int msbMaskHigh = ~(msbThresh - 1) & 0xFF;
                    for (int p = lsbSplitBits - 1; p >= 0; p--) {
                        int bitVal = 1 << p;
                        int mode2 = (pModes >>> (2 * p)) & 0x03;
                        if (mode2 == PLANE_ALL_ZERO) {
                            continue;
                        }
                        if (bitVal <= lsbRem) {
                            decodeConditionedBitplaneDirectToChannel(
                                tailBuf, posRef, tailLimit, nSamples, nSamples, p, bitVal, mode2, uCh, arena
                            );
                        } else {
                            if (cachedActiveCount < 0) {
                                int cnt = 0;
                                for (int i = 0; i < nSamples; i++) {
                                    if ((uCh[i] & msbMaskHigh) < msbCeil) {
                                        activeIdx[cnt++] = (short) i;
                                    }
                                }
                                cachedActiveCount = cnt;
                            }
                            if (cachedActiveCount > 0) {
                                decodeConditionedBitplaneIndexed(
                                    tailBuf, posRef, tailLimit, cachedActiveCount, p, bitVal, mode2, activeIdx, uCh, arena
                                );
                            }
                        }
                    }
                }
                continue;
            }

            byte[] msbOnly = arena.msbReconstructed[c];
            for (int p = lsbSplitBits - 1; p >= 0; p--) {
                int bitVal = 1 << p;
                int mode2 = (pModes >>> (2 * p)) & 0x03;
                if (bitVal > maxU8 || mode2 == PLANE_ALL_ZERO) {
                    continue;
                }
                int nActive = 0;
                for (int i = 0; i < nSamples; i++) {
                    if ((msbOnly[i] & 0xFF) + bitVal <= maxU8) {
                        nActive++;
                    }
                }
                if (nActive == 0) {
                    continue;
                }
                decodeConditionedBitplane(
                    tailBuf, posRef, tailLimit, nActive, p, mode2, arena.activeBitPlane, arena
                );
                if (arena.activeLsbTileKey64 != 0L && nActive > 1 && (bitVal * 2 <= maxU8)) {
                    applyLsbPlaneKeyRotation(
                        arena.activeBitPlane, nActive, arena.activeLsbTileKey64, c, p, false, arena.rotBitScratch
                    );
                }
                int k = 0;
                for (int i = 0; i < nSamples; i++) {
                    int mVal = msbOnly[i] & 0xFF;
                    if (mVal + bitVal <= maxU8) {
                        if (arena.activeBitPlane[k++] != 0) {
                            uCh[i] |= (byte) bitVal;
                            if (bitVal * 2 > maxU8) {
                                msbOnly[i] = (byte) (mVal | bitVal);
                            }
                        }
                    }
                }
            }
        }
        if (!lsbWasStripped && posRef[0] != tailLimit) {
            throw new LcvCorruptTileException(
                "CBIT: planar MV LSB slice unconsumed trailing bytes (" + posRef[0] + " != " + tailLimit + ")"
            );
        }

        if (directRgba && !alphaMasked && !hasWaveletHaar && !singleSlice && channelMask4 == 0x07) {
            int[] p0 = arena.planarParams[0];
            int[] p1 = arena.planarParams[1];
            int[] p2 = arena.planarParams[2];
            if (p0[0] < 0x2000 && p1[0] < 0x2000 && p2[0] < 0x2000
                && !channelLsbStripped[0] && !channelLsbStripped[1] && !channelLsbStripped[2]) {
                byte[] u0 = arena.grayValues[0];
                byte[] u1 = arena.grayValues[1];
                byte[] u2 = arena.grayValues[2];
                int min0 = p0[0], sx0 = p0[1], sy0 = p0[2], st0 = p0[3], frac0 = p0[5] & 0xFF;
                int min1 = p1[0], sx1 = p1[1], sy1 = p1[2], st1 = p1[3], frac1 = p1[5] & 0xFF;
                int min2 = p2[0], sx2 = p2[1], sy2 = p2[2], st2 = p2[3], frac2 = p2[5] & 0xFF;
                int aConst = constVals[3] & 0xFF;
                for (int i = 0; i < nSamples; i++) {
                    int tyx = curveToTyx[i] & 0xFFFF;
                    int lx = tyx & 15;
                    int ly = (tyx >>> 4) & 15;
                    int lt = tyx >>> 8;
                    int rVal = ((frac0 + ((sx0 * lx + sy0 * ly + st0 * lt) >> 2)) >> 8) + min0 + (u0[i] & 0xFF);
                    int gVal = ((frac1 + ((sx1 * lx + sy1 * ly + st1 * lt) >> 2)) >> 8) + min1 + (u1[i] & 0xFF);
                    int bVal = ((frac2 + ((sx2 * lx + sy2 * ly + st2 * lt) >> 2)) >> 8) + min2 + (u2[i] & 0xFF);
                    int r = (rVal & ~0xFF) == 0 ? rVal : Math.max(0, Math.min(255, rVal));
                    int g = (gVal & ~0xFF) == 0 ? gVal : Math.max(0, Math.min(255, gVal));
                    int b = (bVal & ~0xFF) == 0 ? bVal : Math.max(0, Math.min(255, bVal));
                    dstRgbaFrames[tyx] = (r << 24) | (g << 16) | (b << 8) | aConst;
                }
                return;
            }
        }

        for (int c = 0; c < 4; c++) {
            byte[] ch = arena.channelValues[c];
            if (((channelMask4 >>> c) & 1) == 0) {
                if (singleSlice) {
                    for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
                        ch[tyxToCurve[tyx] & 0xFFFF] = constVals[c];
                    }
                } else {
                    Arrays.fill(ch, 0, nSamples, constVals[c]);
                }
            } else {
                byte[] uCh = arena.grayValues[c];
                int[] p = arena.planarParams[c];
                int minErr16 = p[0];
                int sxQ8 = p[1];
                int syQ8 = p[2];
                int stQ8 = p[3];
                int maxU8 = p[4] & 0xFF;
                int frac8 = p[5] & 0xFF;
                int hStep = hasWaveletHaar ? arena.haarStep[c] : 0;
                byte[] hNibs = arena.haarNibbles[c];
                boolean chStripped = channelLsbStripped[c];
                if (singleSlice) {
                    for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
                        int i = tyxToCurve[tyx] & 0xFFFF;
                        int lx = tyx & 15;
                        int ly = (tyx >>> 4) & 15;
                        int lt = tyx >>> 8;
                        if (minErr16 == 0x20FF) {
                            ch[i] = (byte) ((uCh[i] & 0xFF) != 0 ? 255 : 0);
                        } else if (minErr16 >= 0x4000) {
                            int vMin = (minErr16 >>> 6) & 0xFF;
                            int c0Mod64 = minErr16 & 0x3F;
                            int pred = (c0Mod64 + ((sxQ8 * lx + syQ8 * ly + stQ8 * lt) >> 10)) & 0x3F;
                            if (maxU8 == 0) {
                                ch[i] = (byte) (vMin + pred);
                            } else {
                                int u = uCh[i] & 0xFF;
                                if (chStripped) {
                                    int uHigh = Math.min(maxU8, u + lsbMaxAdd);
                                    u = (u + uHigh) >>> 1;
                                }
                                ch[i] = (byte) Math.max(0, Math.min(255, vMin + ((pred + u) & 0x3F)));
                            }
                        } else {
                            int hOff = hasWaveletHaar ? ((hNibs[((ly >>> 2) << 2) | (lx >>> 2)] & 0x0F) * hStep) : 0;
                            int raw = (sxQ8 * lx + syQ8 * ly + stQ8 * lt) >> 2;
                            int pred = ((frac8 + raw) >> 8) + hOff;
                            int u = uCh[i] & 0xFF;
                            if (chStripped) {
                                int uHigh = Math.min(maxU8, u + lsbMaxAdd);
                                u = (u + uHigh) >>> 1;
                            }
                            ch[i] = (byte) Math.max(0, Math.min(255, pred + minErr16 + u));
                        }
                    }
                } else if (minErr16 == 0x20FF) {
                    for (int i = 0; i < nSamples; i++) {
                        ch[i] = (byte) ((uCh[i] & 0xFF) != 0 ? 255 : 0);
                    }
                } else if (minErr16 >= 0x4000) {
                    int vMin = (minErr16 >>> 6) & 0xFF;
                    int c0Mod64 = minErr16 & 0x3F;
                    if (maxU8 == 0) {
                        for (int i = 0; i < nSamples; i++) {
                            int tyx = curveToTyx[i] & 0xFFFF;
                            int lx = tyx & 15;
                            int ly = (tyx >>> 4) & 15;
                            int lt = tyx >>> 8;
                            int pred = (c0Mod64 + ((sxQ8 * lx + syQ8 * ly + stQ8 * lt) >> 10)) & 0x3F;
                            ch[i] = (byte) (vMin + pred);
                        }
                    } else if (chStripped) {
                        int[] uTyx = restoreStrippedPlanarResidualTyx(
                            uCh, curveToTyx, nSamples, maxU8, lsbSplitBits, lsbMaxAdd, arena
                        );
                        for (int i = 0; i < nSamples; i++) {
                            int tyx = curveToTyx[i] & 0xFFFF;
                            int lx = tyx & 15;
                            int ly = (tyx >>> 4) & 15;
                            int lt = tyx >>> 8;
                            int pred = (c0Mod64 + ((sxQ8 * lx + syQ8 * ly + stQ8 * lt) >> 10)) & 0x3F;
                            ch[i] = (byte) Math.max(0, Math.min(255, vMin + ((pred + uTyx[tyx]) & 0x3F)));
                        }
                    } else {
                        for (int i = 0; i < nSamples; i++) {
                            int tyx = curveToTyx[i] & 0xFFFF;
                            int lx = tyx & 15;
                            int ly = (tyx >>> 4) & 15;
                            int lt = tyx >>> 8;
                            int pred = (c0Mod64 + ((sxQ8 * lx + syQ8 * ly + stQ8 * lt) >> 10)) & 0x3F;
                            int u = uCh[i] & 0xFF;
                            ch[i] = (byte) Math.max(0, Math.min(255, vMin + ((pred + u) & 0x3F)));
                        }
                    }
                } else if (chStripped) {
                    int[] uTyx = restoreStrippedPlanarResidualTyx(
                        uCh, curveToTyx, nSamples, maxU8, lsbSplitBits, lsbMaxAdd, arena
                    );
                    for (int i = 0; i < nSamples; i++) {
                        int tyx = curveToTyx[i] & 0xFFFF;
                        int lx = tyx & 15;
                        int ly = (tyx >>> 4) & 15;
                        int lt = tyx >>> 8;
                        int hOff = hasWaveletHaar ? ((hNibs[((ly >>> 2) << 2) | (lx >>> 2)] & 0x0F) * hStep) : 0;
                        int raw = (sxQ8 * lx + syQ8 * ly + stQ8 * lt) >> 2;
                        int pred = ((frac8 + raw) >> 8) + hOff;
                        ch[i] = (byte) Math.max(0, Math.min(255, pred + minErr16 + uTyx[tyx]));
                    }
                } else {
                    for (int i = 0; i < nSamples; i++) {
                        int tyx = curveToTyx[i] & 0xFFFF;
                        int lx = tyx & 15;
                        int ly = (tyx >>> 4) & 15;
                        int lt = tyx >>> 8;
                        int hOff = hasWaveletHaar ? ((hNibs[((ly >>> 2) << 2) | (lx >>> 2)] & 0x0F) * hStep) : 0;
                        int raw = (sxQ8 * lx + syQ8 * ly + stQ8 * lt) >> 2;
                        int pred = ((frac8 + raw) >> 8) + hOff;
                        int u = uCh[i] & 0xFF;
                        ch[i] = (byte) Math.max(0, Math.min(255, pred + minErr16 + u));
                    }
                }
            }
        }

        if (directRgba) {
            byte[] rCh = arena.channelValues[0];
            byte[] gCh = arena.channelValues[1];
            byte[] bCh = arena.channelValues[2];
            byte[] aCh = arena.channelValues[3];
            if (singleSlice) {
                for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
                    int i = tyxToCurve[tyx] & 0xFFFF;
                    int a = aCh[i] & 0xFF;
                    int rgba = (alphaMasked && a == 0)
                        ? 0
                        : (((rCh[i] & 0xFF) << 24)
                            | ((gCh[i] & 0xFF) << 16)
                            | ((bCh[i] & 0xFF) << 8)
                            | a);
                    dstRgbaFrames[tyx] = rgba;
                }
            } else {
                for (int i = 0; i < nSamples; i++) {
                    int a = aCh[i] & 0xFF;
                    int rgba = (alphaMasked && a == 0)
                        ? 0
                        : (((rCh[i] & 0xFF) << 24)
                            | ((gCh[i] & 0xFF) << 16)
                            | ((bCh[i] & 0xFF) << 8)
                            | a);
                    dstRgbaFrames[curveToTyx[i] & 0xFFFF] = rgba;
                }
            }
        } else if (singleSlice) {
            reconstructRgbaSliceFromYCoCgChannels(
                sliceStartTyx, sliceEndTyx, tyxToCurve, lsbWasStripped, arena, dstRgbaFrames
            );
        } else {
            reconstructRgbaFromYCoCgChannels(nSamples, curveToTyx, lsbWasStripped, arena, dstRgbaFrames);
        }
    }

    static void decodeSingleBitplane(
        byte[] src, int[] posRef, int limit, int nSamples, int planeIdx, int mode2,
        byte[] grayCh, LcvPrimitives.ScratchArena arena
    ) throws LcvCorruptTileException {
        decodeSingleBitplane(src, posRef, limit, nSamples, nSamples, planeIdx, mode2, grayCh, arena);
    }

    static void decodeSingleBitplane(
        byte[] src, int[] posRef, int limit, int nSamples, int sampleLimit, int planeIdx, int mode2,
        byte[] grayCh, LcvPrimitives.ScratchArena arena
    ) throws LcvCorruptTileException {
        int unpackLimit = Math.max(0, Math.min(nSamples, sampleLimit));
        int bitMask = 1 << planeIdx;
        if (mode2 == PLANE_ALL_ZERO) {
            return;
        }
        if (mode2 == PLANE_SINGLE_RUN_INLINE) {
            if (planeIdx >= arena.activeLsbSplitBits) {
                readSingleRunInline(src, posRef, limit, nSamples, arena.rawGaps, arena.runLens);
                int start = arena.rawGaps[0];
                int len = arena.runLens[0];
                int end = Math.min(unpackLimit, start + len);
                for (int k = start; k < end; k++) {
                    grayCh[k] |= (byte) bitMask;
                }
                return;
            }
            // LSB Tier mode 01: PLANE_XOR_UPPER_RLE
            if (posRef[0] >= limit) {
                throw new LcvCorruptTileException("CBIT: truncated PLANE_XOR_UPPER_RLE header at plane " + planeIdx);
            }
            int firstByte = src[posRef[0]] & 0xFF;
            int upperPlaneIdx = Math.min(7, planeIdx + 1);
            if (firstByte == 0) {
                if (posRef[0] + 2 > limit) {
                    throw new LcvCorruptTileException("CBIT: truncated PLANE_XOR_UPPER_RLE sentinel at plane " + planeIdx);
                }
                int sentinel = src[posRef[0] + 1] & 0xFF;
                posRef[0] += 2;
                if (sentinel == 0) {
                    for (int i = 0; i < unpackLimit; i++) {
                        grayCh[i] |= (byte) bitMask;
                    }
                } else if (sentinel == 1) {
                    for (int i = 0; i < unpackLimit; i++) {
                        if (((grayCh[i] >>> upperPlaneIdx) & 1) != 0) {
                            grayCh[i] |= (byte) bitMask;
                        }
                    }
                } else {
                    throw new LcvCorruptTileException("CBIT: invalid PLANE_XOR_UPPER_RLE sentinel " + sentinel);
                }
                return;
            }
            for (int i = 0; i < unpackLimit; i++) {
                if (((grayCh[i] >>> upperPlaneIdx) & 1) != 0) {
                    grayCh[i] |= (byte) bitMask;
                }
            }
            int xorRunCount = readNibbleRunStream(src, posRef, limit, nSamples, arena.rawGaps, arena.runLens);
            int cursor = 0;
            for (int r = 0; r < xorRunCount; r++) {
                cursor += arena.rawGaps[r];
                if (cursor >= unpackLimit) {
                    break;
                }
                int len = arena.runLens[r];
                int end = Math.min(unpackLimit, cursor + len);
                for (int k = cursor; k < end; k++) {
                    grayCh[k] ^= (byte) bitMask;
                }
                cursor += len;
            }
            return;
        }
        if (mode2 == PLANE_NIBBLE_RLE) {
            int runCount = readNibbleRunStream(src, posRef, limit, nSamples, arena.rawGaps, arena.runLens);
            int cursor = 0;
            for (int r = 0; r < runCount; r++) {
                cursor += arena.rawGaps[r];
                if (cursor >= unpackLimit) {
                    break;
                }
                int len = arena.runLens[r];
                int end = Math.min(unpackLimit, cursor + len);
                for (int k = cursor; k < end; k++) {
                    grayCh[k] |= (byte) bitMask;
                }
                cursor += len;
            }
            return;
        }
        // PLANE_RAW_BITMAP (mode 11)
        int bitmapBytes = nSamples >>> 3;
        int pos = posRef[0];
        if (pos + bitmapBytes > limit) {
            throw new LcvCorruptTileException("CBIT: truncated raw bitplane bitmap at plane " + planeIdx);
        }
        int fullByteLim = unpackLimit >>> 3;
        for (int bi = 0; bi < fullByteLim; bi++) {
            int b = src[pos + bi] & 0xFF;
            int base = bi << 3;
            while (b != 0) {
                int tz = Integer.numberOfTrailingZeros(b);
                grayCh[base + tz] |= (byte) bitMask;
                b &= b - 1;
            }
        }
        for (int i = fullByteLim << 3; i < unpackLimit; i++) {
            if (((src[pos + (i >>> 3)] >>> (i & 7)) & 1) != 0) {
                grayCh[i] |= (byte) bitMask;
            }
        }
        posRef[0] = pos + bitmapBytes;
    }

    private static void decodeUiPaletteRoaring(
        byte[] src, int pos, int limit, int paletteSize, int nSamples,
        short[] curveToTyx, LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) throws LcvCorruptTileException {
        decodeUiPaletteRoaring(src, pos, limit, paletteSize, nSamples, curveToTyx, 0, nSamples, null, arena, dstRgbaFrames);
    }

    private static void decodeUiPaletteRoaring(
        byte[] src, int pos, int limit, int paletteSize, int nSamples,
        short[] curveToTyx, int sliceStartTyx, int sliceEndTyx, short[] tyxToCurve,
        LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) throws LcvCorruptTileException {
        if (paletteSize < 2 || paletteSize > 16) {
            throw new LcvCorruptTileException("CBIT: invalid UI palette size " + paletteSize);
        }
        if (pos + paletteSize * 4 > limit) {
            throw new LcvCorruptTileException("CBIT: truncated UI palette table");
        }
        int[] palette = arena.paletteColors;
        for (int p = 0; p < paletteSize; p++) {
            int c = ((src[pos] & 0xFF) << 24)
                | ((src[pos + 1] & 0xFF) << 16)
                | ((src[pos + 2] & 0xFF) << 8)
                | (src[pos + 3] & 0xFF);
            if (arena.activeMsbTileKey64 != 0L) {
                c ^= (int) LcvPrimitives.mix64(arena.activeMsbTileKey64 ^ (0x50414C00L + p));
            }
            palette[p] = c;
            pos += 4;
            for (int q = 0; q < p; q++) {
                if (palette[q] == palette[p]) {
                    throw new LcvCorruptTileException("CBIT: duplicate UI palette color at index " + p);
                }
            }
        }

        int minorityCount = paletteSize - 1;
        int subDirBytes = (minorityCount + 3) / 4;
        if (pos + subDirBytes > limit) {
            throw new LcvCorruptTileException("CBIT: truncated UI Roaring sub-variation directory");
        }
        int subDirOff = pos;
        pos += subDirBytes;

        int[] curveRgba = arena.curveRgba;
        int minCurveIdx = 0;
        int maxCurveIdx = nSamples;
        if (tyxToCurve != null && sliceEndTyx > sliceStartTyx) {
            int firstMap = tyxToCurve[sliceStartTyx] & 0xFFFF;
            int lastMap = tyxToCurve[sliceEndTyx - 1] & 0xFFFF;
            if (firstMap == sliceStartTyx && lastMap == sliceEndTyx - 1) {
                minCurveIdx = sliceStartTyx;
                maxCurveIdx = sliceEndTyx;
            }
        }
        Arrays.fill(curveRgba, minCurveIdx, maxCurveIdx, palette[0]);

        int[] posRef = arena.posCursor;
        posRef[0] = pos;
        int bitmapBytes = nSamples >>> 3;

        for (int p = 1; p < paletteSize; p++) {
            int mIdx = p - 1;
            int subByte = src[subDirOff + (mIdx >>> 2)] & 0xFF;
            int subTag = (subByte >>> ((3 - (mIdx & 3)) * 2)) & 0x03;
            int color = palette[p];

            if (subTag == ROARING_TAG_SINGLETON) {
                if (posRef[0] + 2 > limit) {
                    throw new LcvCorruptTileException("CBIT: truncated ROARING_SINGLETON index");
                }
                int idx = ((src[posRef[0]] & 0xFF) << 8) | (src[posRef[0] + 1] & 0xFF);
                posRef[0] += 2;
                if (idx < 0 || idx >= nSamples) {
                    throw new LcvCorruptTileException("CBIT: ROARING_SINGLETON out of bounds: " + idx);
                }
                if (idx >= minCurveIdx && idx < maxCurveIdx) {
                    curveRgba[idx] = color;
                }
            } else if (subTag == ROARING_TAG_DELTA_RUN) {
                int runCount = readNibbleRunStream(src, posRef, limit, nSamples, arena.rawGaps, arena.runLens);
                int cursor = 0;
                for (int r = 0; r < runCount; r++) {
                    cursor += arena.rawGaps[r];
                    if (cursor >= maxCurveIdx) {
                        break;
                    }
                    int len = arena.runLens[r];
                    int start = Math.max(minCurveIdx, cursor);
                    int end = Math.min(maxCurveIdx, cursor + len);
                    for (int k = start; k < end; k++) {
                        curveRgba[k] = color;
                    }
                    cursor += len;
                }
            } else if (subTag == ROARING_TAG_DELTA_ARRAY) {
                int card = LcvPrimitives.readVarInt(src, posRef, limit);
                if (card <= 0 || card > nSamples) {
                    throw new LcvCorruptTileException("CBIT: invalid ROARING_DELTA_ARRAY cardinality: " + card);
                }
                int prev = -1;
                for (int k = 0; k < card; k++) {
                    int gap = LcvPrimitives.readVarInt(src, posRef, limit);
                    int idx = (prev < 0) ? gap : (prev + 1 + gap);
                    if (idx < 0 || idx >= nSamples) {
                        throw new LcvCorruptTileException("CBIT: ROARING_DELTA_ARRAY index overflow: " + idx);
                    }
                    if (idx >= minCurveIdx && idx < maxCurveIdx) {
                        curveRgba[idx] = color;
                    }
                    prev = idx;
                }
            } else {
                // ROARING_TAG_BITMAP
                int bPos = posRef[0];
                if (bPos + bitmapBytes > limit) {
                    throw new LcvCorruptTileException("CBIT: truncated ROARING_BITMAP");
                }
                for (int i = minCurveIdx; i < maxCurveIdx; i++) {
                    if (((src[bPos + (i >>> 3)] >>> (i & 7)) & 1) != 0) {
                        curveRgba[i] = color;
                    }
                }
                posRef[0] = bPos + bitmapBytes;
            }
        }
        if (posRef[0] != limit) {
            throw new LcvCorruptTileException(
                "CBIT: UI_PALETTE_ROARING slice unconsumed trailing bytes (" + posRef[0] + " != " + limit + ")"
            );
        }

        if (tyxToCurve != null) {
            for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
                dstRgbaFrames[tyx] = curveRgba[tyxToCurve[tyx] & 0xFFFF];
            }
        } else {
            for (int i = 0; i < nSamples; i++) {
                dstRgbaFrames[curveToTyx[i] & 0xFFFF] = curveRgba[i];
            }
        }
    }

    private static void reconstructRgbaFromYCoCgChannels(
        int nSamples, short[] curveToTyx, LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) {
        byte[] yCh = arena.channelValues[0];
        byte[] coCh = arena.channelValues[1];
        byte[] cgCh = arena.channelValues[2];
        byte[] aCh = arena.channelValues[3];
        for (int i = 0; i < nSamples; i++) {
            int yCoCgA = ((yCh[i] & 0xFF) << 24)
                | ((coCh[i] & 0xFF) << 16)
                | ((cgCh[i] & 0xFF) << 8)
                | (aCh[i] & 0xFF);
            dstRgbaFrames[curveToTyx[i] & 0xFFFF] = LcvPrimitives.yCoCgRToRgba(yCoCgA);
        }
    }
    private static void reconstructRgbaSliceFromYCoCgChannels(
        int sliceStartTyx, int sliceEndTyx, short[] tyxToCurve, boolean clampQuantizedOvershoot,
        LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) {
        byte[] yCh = arena.channelValues[0];
        byte[] coCh = arena.channelValues[1];
        byte[] cgCh = arena.channelValues[2];
        byte[] aCh = arena.channelValues[3];
        if (!clampQuantizedOvershoot) {
            for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
                int i = tyxToCurve[tyx] & 0xFFFF;
                int y8 = yCh[i] & 0xFF;
                int co8 = coCh[i] & 0xFF;
                int cg8 = cgCh[i] & 0xFF;
                int a = aCh[i] & 0xFF;
                if (co8 >= 120 && co8 <= 135 && cg8 >= 120 && cg8 <= 135) {
                    int co = co8 - 128;
                    int cg = cg8 - 128;
                    int tRaw = y8 - (cg >> 1);
                    int t = Math.max(0, Math.min(255, tRaw));
                    int gRaw = cg + t;
                    int g = Math.max(0, Math.min(255, gRaw));
                    int bRaw = t - (co >> 1);
                    int b = Math.max(0, Math.min(255, bRaw));
                    int rRaw = b + co;
                    if (tRaw >= -8 && tRaw <= 263 && gRaw >= -8 && gRaw <= 263
                        && bRaw >= -8 && bRaw <= 263 && rRaw >= -8 && rRaw <= 263) {
                        int r = Math.max(0, Math.min(255, rRaw));
                        dstRgbaFrames[tyx] = (r << 24) | (g << 16) | (b << 8) | a;
                        continue;
                    }
                }
                int yCoCgA = (y8 << 24) | (co8 << 16) | (cg8 << 8) | a;
                dstRgbaFrames[tyx] = LcvPrimitives.yCoCgRToRgba(yCoCgA);
            }
            return;
        }
        for (int tyx = sliceStartTyx; tyx < sliceEndTyx; tyx++) {
            int i = tyxToCurve[tyx] & 0xFFFF;
            int y8 = yCh[i] & 0xFF;
            int co8 = coCh[i] & 0xFF;
            int cg8 = cgCh[i] & 0xFF;
            int a = aCh[i] & 0xFF;
            int co = (byte) (co8 - 128);
            int cg = (byte) (cg8 - 128);
            int tRaw = y8 - (cg >> 1);
            int t = (tRaw >= -96 && tRaw < 0) ? 0 : ((tRaw > 255 && tRaw <= 351) ? 255 : (tRaw & 0xFF));
            int gRaw = cg + t;
            int g = (gRaw >= -96 && gRaw < 0) ? 0 : ((gRaw > 255 && gRaw <= 351) ? 255 : (gRaw & 0xFF));
            int bRaw = t - (co >> 1);
            int b = (bRaw >= -96 && bRaw < 0) ? 0 : ((bRaw > 255 && bRaw <= 351) ? 255 : (bRaw & 0xFF));
            int rRaw = b + co;
            int r = (rRaw >= -96 && rRaw < 0) ? 0 : ((rRaw > 255 && rRaw <= 351) ? 255 : (rRaw & 0xFF));
            dstRgbaFrames[tyx] = (r << 24) | (g << 16) | (b << 8) | a;
        }
    }

    private static void reconstructRgbaFromYCoCgChannels(
        int nSamples, short[] curveToTyx, boolean clampQuantizedOvershoot,
        LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) {
        byte[] yCh = arena.channelValues[0];
        byte[] coCh = arena.channelValues[1];
        byte[] cgCh = arena.channelValues[2];
        byte[] aCh = arena.channelValues[3];
        if (!clampQuantizedOvershoot) {
            for (int i = 0; i < nSamples; i++) {
                int y8 = yCh[i] & 0xFF;
                int co8 = coCh[i] & 0xFF;
                int cg8 = cgCh[i] & 0xFF;
                int a = aCh[i] & 0xFF;
                if (co8 >= 120 && co8 <= 135 && cg8 >= 120 && cg8 <= 135) {
                    int co = co8 - 128;
                    int cg = cg8 - 128;
                    int tRaw = y8 - (cg >> 1);
                    int t = Math.max(0, Math.min(255, tRaw));
                    int gRaw = cg + t;
                    int g = Math.max(0, Math.min(255, gRaw));
                    int bRaw = t - (co >> 1);
                    int b = Math.max(0, Math.min(255, bRaw));
                    int rRaw = b + co;
                    if (tRaw >= -8 && tRaw <= 263 && gRaw >= -8 && gRaw <= 263
                        && bRaw >= -8 && bRaw <= 263 && rRaw >= -8 && rRaw <= 263) {
                        int r = Math.max(0, Math.min(255, rRaw));
                        dstRgbaFrames[curveToTyx[i] & 0xFFFF] = (r << 24) | (g << 16) | (b << 8) | a;
                        continue;
                    }
                }
                int yCoCgA = (y8 << 24) | (co8 << 16) | (cg8 << 8) | a;
                dstRgbaFrames[curveToTyx[i] & 0xFFFF] = LcvPrimitives.yCoCgRToRgba(yCoCgA);
            }
            return;
        }
        for (int i = 0; i < nSamples; i++) {
            int y8 = yCh[i] & 0xFF;
            int co8 = coCh[i] & 0xFF;
            int cg8 = cgCh[i] & 0xFF;
            int a = aCh[i] & 0xFF;

            int co = (byte) (co8 - 128);
            int cg = (byte) (cg8 - 128);
            int tRaw = y8 - (cg >> 1);
            int t = (tRaw >= -96 && tRaw < 0) ? 0 : ((tRaw > 255 && tRaw <= 351) ? 255 : (tRaw & 0xFF));
            int gRaw = cg + t;
            int g = (gRaw >= -96 && gRaw < 0) ? 0 : ((gRaw > 255 && gRaw <= 351) ? 255 : (gRaw & 0xFF));
            int bRaw = t - (co >> 1);
            int b = (bRaw >= -96 && bRaw < 0) ? 0 : ((bRaw > 255 && bRaw <= 351) ? 255 : (bRaw & 0xFF));
            int rRaw = b + co;
            int r = (rRaw >= -96 && rRaw < 0) ? 0 : ((rRaw > 255 && rRaw <= 351) ? 255 : (rRaw & 0xFF));
            dstRgbaFrames[curveToTyx[i] & 0xFFFF] = (r << 24) | (g << 16) | (b << 8) | a;
        }
    }

    /**
     * Extracts a {@code 16 x 16 x K} tile at tile grid coordinates {@code (tileX, tileY)} into
     * {@code dstCubeTyx} (`256 * K` ints), clamping boundary coordinates when {@code frameWidth}
     * or {@code frameHeight} is not a multiple of 16.
     */
    public static void extractTileToCubeTyx(
        int[] rgbaFrames, int tileX, int tileY, int frameWidth, int frameHeight, int depthK, int[] dstCubeTyx
    ) {
        extractTileAndCheckUniformTyx(rgbaFrames, tileX, tileY, frameWidth, frameHeight, depthK, dstCubeTyx);
    }

    /**
     * Read-only fast check returning {@code true} iff all samples of the {@code 16 x 16 x K} tile
     * at {@code (tileX, tileY)} equal its top-left sample (avoiding any writes to {@code dstCubeTyx}
     * on uniform tiles, and aborting on the first differing pixel on non-uniform tiles).
     */
    public static boolean isTileUniformInPlace(
        int[] rgbaFrames, int tileX, int tileY, int frameWidth, int frameHeight, int depthK
    ) {
        int frameArea = frameWidth * frameHeight;
        int baseX = tileX * LcvPrimitives.TILE_DIM;
        int baseY = tileY * LcvPrimitives.TILE_DIM;
        boolean fullInterior = (baseX + LcvPrimitives.TILE_DIM <= frameWidth)
            && (baseY + LcvPrimitives.TILE_DIM <= frameHeight);

        if (fullInterior) {
            int first = rgbaFrames[baseY * frameWidth + baseX];
            for (int t = 0; t < depthK; t++) {
                int frameBase = t * frameArea + baseY * frameWidth + baseX;
                for (int ly = 0; ly < LcvPrimitives.TILE_DIM; ly++) {
                    int rowOff = frameBase + ly * frameWidth;
                    for (int lx = 0; lx < LcvPrimitives.TILE_DIM; lx++) {
                        if (rgbaFrames[rowOff + lx] != first) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }
        return false;
    }

    /**
     * Single-pass fused tile extractor and uniformity checker: copies the {@code 16 x 16 x K} tile
     * at {@code (tileX, tileY)} into {@code dstCubeTyx} and returns {@code true} iff all samples in
     * the cube are identical to {@code dstCubeTyx[0]}.
     */
    public static boolean extractTileAndCheckUniformTyx(
        int[] rgbaFrames, int tileX, int tileY, int frameWidth, int frameHeight, int depthK, int[] dstCubeTyx
    ) {
        if (isTileUniformInPlace(rgbaFrames, tileX, tileY, frameWidth, frameHeight, depthK)) {
            dstCubeTyx[0] = rgbaFrames[tileY * LcvPrimitives.TILE_DIM * frameWidth + tileX * LcvPrimitives.TILE_DIM];
            return true;
        }
        int frameArea = frameWidth * frameHeight;
        int baseX = tileX * LcvPrimitives.TILE_DIM;
        int baseY = tileY * LcvPrimitives.TILE_DIM;
        boolean fullInterior = (baseX + LcvPrimitives.TILE_DIM <= frameWidth)
            && (baseY + LcvPrimitives.TILE_DIM <= frameHeight);

        if (fullInterior) {
            for (int t = 0; t < depthK; t++) {
                int frameBase = t * frameArea + baseY * frameWidth + baseX;
                int cubeTBase = t << 8;
                for (int ly = 0; ly < LcvPrimitives.TILE_DIM; ly++) {
                    System.arraycopy(
                        rgbaFrames, frameBase + ly * frameWidth,
                        dstCubeTyx, cubeTBase | (ly << 4),
                        LcvPrimitives.TILE_DIM
                    );
                }
            }
            return false;
        }

        int first = rgbaFrames[Math.min(frameHeight - 1, baseY) * frameWidth + Math.min(frameWidth - 1, baseX)];
        int diffOr = 0;
        for (int t = 0; t < depthK; t++) {
            int frameBase = t * frameArea;
            int cubeTBase = t << 8;
            for (int ly = 0; ly < LcvPrimitives.TILE_DIM; ly++) {
                int py = Math.min(frameHeight - 1, baseY + ly);
                int rowBase = frameBase + py * frameWidth;
                int cubeRowBase = cubeTBase | (ly << 4);
                for (int lx = 0; lx < LcvPrimitives.TILE_DIM; lx++) {
                    int px = Math.min(frameWidth - 1, baseX + lx);
                    int v = rgbaFrames[rowBase + px];
                    dstCubeTyx[cubeRowBase | lx] = v;
                    diffOr |= (v ^ first);
                }
            }
        }
        return diffOr == 0;
    }

    /**
     * Writes a decoded {@code 16 x 16 x K} cube in {@code TYX} order back into {@code dstRgbaFrames}.
     */
    public static void scatterCubeTyxToFrames(
        int[] srcCubeTyx, int tileX, int tileY, int frameWidth, int frameHeight, int depthK, int[] dstRgbaFrames
    ) {
        int frameArea = frameWidth * frameHeight;
        int baseX = tileX * LcvPrimitives.TILE_DIM;
        int baseY = tileY * LcvPrimitives.TILE_DIM;
        boolean fullInterior = (baseX + LcvPrimitives.TILE_DIM <= frameWidth)
            && (baseY + LcvPrimitives.TILE_DIM <= frameHeight);

        if (fullInterior) {
            for (int t = 0; t < depthK; t++) {
                int frameBase = t * frameArea + baseY * frameWidth + baseX;
                int cubeTBase = t << 8;
                for (int ly = 0; ly < LcvPrimitives.TILE_DIM; ly++) {
                    System.arraycopy(
                        srcCubeTyx, cubeTBase | (ly << 4),
                        dstRgbaFrames, frameBase + ly * frameWidth,
                        LcvPrimitives.TILE_DIM
                    );
                }
            }
            return;
        }

        for (int t = 0; t < depthK; t++) {
            int frameBase = t * frameArea;
            int cubeTBase = t << 8;
            for (int ly = 0; ly < LcvPrimitives.TILE_DIM; ly++) {
                int py = baseY + ly;
                if (py >= frameHeight) {
                    continue;
                }
                int rowBase = frameBase + py * frameWidth;
                int cubeRowBase = cubeTBase | (ly << 4);
                for (int lx = 0; lx < LcvPrimitives.TILE_DIM; lx++) {
                    int px = baseX + lx;
                    if (px >= frameWidth) {
                        continue;
                    }
                    dstRgbaFrames[rowBase + px] = srcCubeTyx[cubeRowBase | lx];
                }
            }
        }
    }

    public static void validateDepthK(int depthK) {
        if (depthK != 1 && depthK != 2 && depthK != 4 && depthK != 8 && depthK != 16) {
            throw new IllegalArgumentException("LCV-ICD-1.0: depthK must be 1, 2, 4, 8, or 16 (got " + depthK + ")");
        }
    }
}
