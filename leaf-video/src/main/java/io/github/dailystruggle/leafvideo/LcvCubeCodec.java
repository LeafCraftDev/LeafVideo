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
 * @trace REQ-LCV-F-001
 * @trace REQ-LCV-F-002
 * @trace REQ-LCV-F-004
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

    public static final int PLANE_ALL_ZERO = 0;    // 00
    public static final int PLANE_ALL_ONE = 1;     // 01
    public static final int PLANE_NIBBLE_RLE = 2;  // 10
    public static final int PLANE_RAW_BITMAP = 3;  // 11

    public static final int DELTA_SUBMODE_HILBERT_1D_MSB = 0x00;
    public static final int DELTA_SUBMODE_PLANAR_3D_MV = 0x01;
    public static final int DELTA_FLAG_DIRECT_RGBA = 0x02;
    public static final int DELTA_FLAG_MAXU_CONDITIONED = 0x04;
    public static final int DELTA_FLAG_MOTION_SHEARED = 0x08;
    public static final int DELTA_SUBMODE_WAVELET_3D_HAAR = 0x10;
    public static final int DENSE_TAG_RAW_BITMAP = 0x00;
    public static final int DENSE_TAG_TRIPLET_ZERO_SKIP = 0x01;
    public static final int DENSE_TAG_TRIPLET_ONE_SKIP = 0x02;

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
        if (lsbSplitBits < 1 || lsbSplitBits > 4) {
            throw new IllegalArgumentException("lsbSplitBits must be in {1, 2, 3, 4}: " + lsbSplitBits);
        }
        if (arena == null) {
            arena = LcvPrimitives.threadArena();
        }
        if (rlePolicy == null) {
            rlePolicy = DistanceRlePolicy.LOSSLESS;
        }

        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        long digest64 = LcvPrimitives.computeCubeDigest64(cubeRgbaTyx, nSamples);

        // 1. Check UNIFORM_INLINE (0 body bytes, highest precedence per LCV-ICD-1.0 Section 4.1)
        int firstColor = cubeRgbaTyx[0];
        boolean allSame = true;
        for (int i = 1; i < nSamples; i++) {
            if (cubeRgbaTyx[i] != firstColor) {
                allSame = false;
                break;
            }
        }
        if (allSame) {
            return new CubeEncodeResult(
                EncodingVariation.UNIFORM_INLINE, LcvPrimitives.CURVE_TYX, 0, 0, 0, firstColor, digest64
            );
        }

        // 2. Select optimal 1-pass space-filling curve
        int curveId = LcvPrimitives.selectOptimalCurve1Pass(cubeRgbaTyx, depthK);
        short[] curveToTyx = LcvPrimitives.curveToTyxLut(depthK, curveId);
        int[] curveRgba = arena.curveRgba;
        for (int i = 0; i < nSamples; i++) {
            curveRgba[i] = cubeRgbaTyx[curveToTyx[i] & 0xFFFF];
        }

        double dx = tileX - roiTileX;
        double dy = tileY - roiTileY;
        double radialDist = Math.sqrt(dx * dx + dy * dy);

        // 3. Candidate A: UI_PALETTE_ROARING (always evaluated if <= 16 unique colors so discrete UI/HUD tiles stay crisp)
        int uiLen = tryEncodeUiPaletteRoaring(curveRgba, nSamples, curveId, arena, arena.candidateUiBuf, 0);

        // 4. Decompose into both reversible YCoCg-R + Alpha and direct RGBA along the selected curve
        int channelMask4 = 0;
        byte[] constVals = new byte[4];
        int rawChannelMask4 = 0;
        byte[] rawConstVals = new byte[4];
        for (int c = 0; c < 4; c++) {
            byte[] ch = arena.channelValues[c];
            byte[] rawCh = arena.rawChannelValues[c];
            int shift = (3 - c) * 8;
            int firstCh = (LcvPrimitives.rgbaToYCoCgR(curveRgba[0]) >>> shift) & 0xFF;
            int firstRaw = (curveRgba[0] >>> shift) & 0xFF;
            constVals[c] = (byte) firstCh;
            rawConstVals[c] = (byte) firstRaw;
            boolean chUniform = true;
            boolean rawUniform = true;
            for (int i = 0; i < nSamples; i++) {
                int rgba = curveRgba[i];
                int yCoCgA = LcvPrimitives.rgbaToYCoCgR(rgba);
                int v = (yCoCgA >>> shift) & 0xFF;
                int rv = (rgba >>> shift) & 0xFF;
                ch[i] = (byte) v;
                rawCh[i] = (byte) rv;
                if (v != firstCh) {
                    chUniform = false;
                }
                if (rv != firstRaw) {
                    rawUniform = false;
                }
            }
            if (!chUniform) {
                channelMask4 |= (1 << c);
            }
            if (!rawUniform) {
                rawChannelMask4 |= (1 << c);
            }
        }

        int packedMv8 = LcvPrimitives.estimateTileVelocity1Pass(cubeRgbaTyx, depthK);

        // 5. Evaluate GRAY_PLANES_RAW, 1D GRAY_PLANES_HILBERT_DELTA, and 3D Planar+MV Coupled Residual
        // on the lossless baseline first so variation choice is 100% invariant to d_merge!
        int[] rawBaseSplits = encodeGrayPlanesVariation(
            CUBE_MODE_GRAY_PLANES_RAW, curveId, channelMask4, constVals,
            nSamples, radialDist, DistanceRlePolicy.LOSSLESS, lsbSplitBits, arena, arena.candidateGrayRawBuf, 0
        );
        int[] deltaBaseSplits = encodeGrayPlanesVariation(
            CUBE_MODE_GRAY_PLANES_HILBERT_DELTA, curveId, channelMask4, constVals,
            nSamples, radialDist, DistanceRlePolicy.LOSSLESS, lsbSplitBits, arena, arena.candidateGrayDeltaBuf, 0
        );
        int[] planarMvBaseSplits = (uiLen < Integer.MAX_VALUE)
            ? new int[]{1 << 20, 1 << 20}
            : encodePlanarMvVariation(
                curveId, depthK, curveToTyx, packedMv8,
                channelMask4, constVals, rawChannelMask4, rawConstVals,
                nSamples, radialDist, DistanceRlePolicy.LOSSLESS, lsbSplitBits, false, arena, arena.candidatePlanarMvBuf, 0
            );
        int[] waveletHaarBaseSplits = (uiLen < Integer.MAX_VALUE)
            ? new int[]{1 << 20, 1 << 20}
            : encodePlanarMvVariation(
                curveId, depthK, curveToTyx, packedMv8,
                channelMask4, constVals, rawChannelMask4, rawConstVals,
                nSamples, radialDist, DistanceRlePolicy.LOSSLESS, lsbSplitBits, true, arena, arena.candidateWaveletHaarBuf, 0
            );

        int rawBaseTotal = rawBaseSplits[0] + rawBaseSplits[1];
        int deltaBaseTotal = deltaBaseSplits[0] + deltaBaseSplits[1];
        int planarMvBaseTotal = planarMvBaseSplits[0] + planarMvBaseSplits[1];
        int waveletHaarBaseTotal = waveletHaarBaseSplits[0] + waveletHaarBaseSplits[1];
        int bestCoupledBaseTotal = Math.min(planarMvBaseTotal, waveletHaarBaseTotal);
        int bestDeltaBaseTotal = Math.min(deltaBaseTotal, bestCoupledBaseTotal);
        int bestGrayBaseTotal = Math.min(rawBaseTotal, bestDeltaBaseTotal);

        // 6. Compute RAW_CEILING byte size (1B header + inactive consts + active raw channels)
        int activeChannels = Integer.bitCount(channelMask4);
        int inactiveChannels = 4 - activeChannels;
        int rawCeilingLen = 1 + inactiveChannels + activeChannels * nSamples;

        // 7. Pick best outcome on baseline so variation choice is 100% consistent with on-disk coalescing
        if (uiLen <= bestGrayBaseTotal && uiLen <= rawCeilingLen) {
            System.arraycopy(arena.candidateUiBuf, 0, dst, dstOff, uiLen);
            return new CubeEncodeResult(
                EncodingVariation.UI_PALETTE_ROARING, curveId, uiLen, 0, uiLen, 0, digest64, packedMv8
            );
        }

        boolean coupledDominatesMsbForUep = (lsbSplitBits >= 3)
            && (bestCoupledBaseTotal <= rawCeilingLen)
            && (planarMvBaseSplits[0] <= 52);
        boolean grayRawWinsOverDelta = !coupledDominatesMsbForUep && (rawBaseTotal <= bestDeltaBaseTotal);
        boolean coupledWinsOver1DDelta = coupledDominatesMsbForUep || (bestCoupledBaseTotal < deltaBaseTotal);
        boolean waveletWinsOverPlanar = waveletHaarBaseTotal < planarMvBaseTotal;
        int winningGrayMode = grayRawWinsOverDelta
            ? CUBE_MODE_GRAY_PLANES_RAW
            : CUBE_MODE_GRAY_PLANES_HILBERT_DELTA;
        byte[] winningGrayBuf = grayRawWinsOverDelta
            ? arena.candidateGrayRawBuf
            : (coupledWinsOver1DDelta
                ? (waveletWinsOverPlanar ? arena.candidateWaveletHaarBuf : arena.candidatePlanarMvBuf)
                : arena.candidateGrayDeltaBuf);
        int[] winningGraySplits = grayRawWinsOverDelta
            ? rawBaseSplits
            : (coupledWinsOver1DDelta
                ? (waveletWinsOverPlanar ? waveletHaarBaseSplits : planarMvBaseSplits)
                : deltaBaseSplits);

        if (bestGrayBaseTotal <= rawCeilingLen) {
            if (!rlePolicy.isLosslessAt(radialDist)) {
                if (grayRawWinsOverDelta) {
                    winningGraySplits = encodeGrayPlanesVariation(
                        CUBE_MODE_GRAY_PLANES_RAW, curveId, channelMask4, constVals,
                        nSamples, radialDist, rlePolicy, lsbSplitBits, arena, winningGrayBuf, 0
                    );
                } else if (coupledWinsOver1DDelta) {
                    winningGraySplits = encodePlanarMvVariation(
                        curveId, depthK, curveToTyx, packedMv8,
                        channelMask4, constVals, rawChannelMask4, rawConstVals,
                        nSamples, radialDist, rlePolicy, lsbSplitBits, waveletWinsOverPlanar,
                        arena, winningGrayBuf, 0
                    );
                } else {
                    winningGraySplits = encodeGrayPlanesVariation(
                        CUBE_MODE_GRAY_PLANES_HILBERT_DELTA, curveId, channelMask4, constVals,
                        nSamples, radialDist, rlePolicy, lsbSplitBits, arena, winningGrayBuf, 0
                    );
                }
            }
            int grayTotal = winningGraySplits[0] + winningGraySplits[1];
            System.arraycopy(winningGrayBuf, 0, dst, dstOff, grayTotal);
            EncodingVariation var = (winningGrayMode == CUBE_MODE_GRAY_PLANES_RAW)
                ? EncodingVariation.GRAY_PLANES_RAW
                : EncodingVariation.GRAY_PLANES_HILBERT_DELTA;
            return new CubeEncodeResult(
                var, curveId, winningGraySplits[0], winningGraySplits[1], grayTotal, 0, digest64, packedMv8
            );
        }

        // Fallback to RAW_CEILING (mode 11)
        int pos = dstOff;
        dst[pos++] = (byte) ((curveId << 6) | (CUBE_MODE_RAW_CEILING << 4) | (channelMask4 & 0x0F));
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                dst[pos++] = constVals[c];
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
        int cubeMode, int curveId, int channelMask4, byte[] constVals,
        int nSamples, double radialDist, DistanceRlePolicy rlePolicy, int lsbSplitBits,
        LcvPrimitives.ScratchArena arena, byte[] outBuf, int outOff
    ) {
        int msbBits = 8 - lsbSplitBits;
        int msbMask = (1 << msbBits) - 1;
        int lsbMask = (1 << lsbSplitBits) - 1;

        // Prepare Gray-coded values per active channel
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] srcCh = arena.channelValues[c];
            byte[] grayCh = arena.grayValues[c];
            if (cubeMode == CUBE_MODE_GRAY_PLANES_RAW) {
                for (int i = 0; i < nSamples; i++) {
                    grayCh[i] = (byte) LcvPrimitives.binaryToGray8(srcCh[i] & 0xFF);
                }
            } else {
                // Drift-free (8 - s)-bit MSB Hilbert-Delta (planes g7..g_s) + un-differenced s-bit LSB (planes g_{s-1}..g0)
                // Note: for i >= 1, zigZagEncodeN already maps small deltas to low bitplanes without carry cascades,
                // so we do not apply redundant Gray coding on top of ZigZag!
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
        }

        int pos = outOff;
        outBuf[pos++] = (byte) ((curveId << 6) | (cubeMode << 4) | (channelMask4 & 0x0F));

        // 1B per inactive channel
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                outBuf[pos++] = constVals[c];
            }
        }

        if (cubeMode == CUBE_MODE_GRAY_PLANES_HILBERT_DELTA) {
            outBuf[pos++] = (byte) DELTA_SUBMODE_HILBERT_1D_MSB;
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

        // Base MSB Slice: encode planes p = 7..lsbSplitBits for all active channels
        // Note: in GRAY_PLANES_HILBERT_DELTA (mode 10), planes 7..lsbSplitBits carry 1st-order MSB deltas
        // and must remain lossless (maxGap = 0) to prevent cumulative prefix-sum drift.
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] grayCh = arena.grayValues[c];
            for (int p = 7; p >= lsbSplitBits; p--) {
                int maxGap = (cubeMode == CUBE_MODE_GRAY_PLANES_HILBERT_DELTA)
                    ? 0
                    : rlePolicy.maxMergeGap(p, radialDist);
                int[] modeAndBytes = encodeSingleBitplane(grayCh, nSamples, p, maxGap, arena, outBuf, pos);
                int pMode = modeAndBytes[0];
                pos += modeAndBytes[1];
                planeModesWords[c] |= (pMode << (2 * p));
            }
        }
        int msbSliceBytes = pos - outOff;

        // Tail LSB Slice: encode planes p = (lsbSplitBits - 1)..0 for all active channels
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] grayCh = arena.grayValues[c];
            for (int p = lsbSplitBits - 1; p >= 0; p--) {
                int maxGap = rlePolicy.maxMergeGap(p, radialDist);
                int[] modeAndBytes = encodeSingleBitplane(grayCh, nSamples, p, maxGap, arena, outBuf, pos);
                int pMode = modeAndBytes[0];
                pos += modeAndBytes[1];
                planeModesWords[c] |= (pMode << (2 * p));
            }
        }
        int lsbSliceBytes = (pos - outOff) - msbSliceBytes;

        // Write completed planeModes16[c] words into Base MSB Slice header
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                int off = planeModesOffsets[c];
                int word = planeModesWords[c];
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
        int yCoCgMask4, byte[] yCoCgConsts, int rawRgbaMask4, byte[] rawRgbaConsts,
        int nSamples, double radialDist, DistanceRlePolicy rlePolicy, int lsbSplitBits,
        boolean useWaveletHaar, LcvPrimitives.ScratchArena arena, byte[] outBuf, int outOff
    ) {
        int vx = LcvPrimitives.unpackVelocityX(packedMv8);
        int vy = LcvPrimitives.unpackVelocityY(packedMv8);

        // Fast 1-pass least-squares span comparison between Direct RGBA and YCoCg-R
        // (avoids running refinement searches on both color spaces)
        long rawQuickSpan = quickEstimateColorSpaceSpan(
            arena.rawChannelValues, rawRgbaMask4, nSamples, curveToTyx, depthK
        );
        long yCoCgQuickSpan = quickEstimateColorSpaceSpan(
            arena.channelValues, yCoCgMask4, nSamples, curveToTyx, depthK
        );

        boolean useDirectRgba = rawQuickSpan <= yCoCgQuickSpan;
        int activeMask4 = useDirectRgba ? rawRgbaMask4 : yCoCgMask4;
        byte[] activeConsts = useDirectRgba ? rawRgbaConsts : yCoCgConsts;
        byte[][] srcChannels = useDirectRgba ? arena.rawChannelValues : arena.channelValues;

        int maxSpan = 0;
        boolean anyHaarActive = false;
        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) != 0) {
                long sc = fitPlanar3DChannel(
                    srcChannels[c], nSamples, curveToTyx, depthK, vx, vy, useWaveletHaar, arena,
                    arena.planarParams[c], arena.haarStep, arena.haarNibbles[c], c, arena.grayValues[c]
                );
                if (sc == Long.MAX_VALUE) {
                    return new int[]{1 << 20, 1 << 20};
                }
                maxSpan = Math.max(maxSpan, arena.planarParams[c][4]);
                if (useWaveletHaar && arena.haarStep[c] > 0) {
                    anyHaarActive = true;
                }
            }
        }
        if (!useWaveletHaar && maxSpan == 0) {
            return new int[]{1 << 20, 1 << 20};
        }
        if (useWaveletHaar && !anyHaarActive) {
            return new int[]{1 << 20, 1 << 20};
        }

        boolean hasMv = (vx != 0 || vy != 0);
        int deltaFlags8 = DELTA_SUBMODE_PLANAR_3D_MV | DELTA_FLAG_MAXU_CONDITIONED
            | (useWaveletHaar ? DELTA_SUBMODE_WAVELET_3D_HAAR : 0)
            | (useDirectRgba ? DELTA_FLAG_DIRECT_RGBA : 0)
            | (hasMv ? DELTA_FLAG_MOTION_SHEARED : 0);

        int pos = outOff;
        outBuf[pos++] = (byte) ((curveId << 6) | (CUBE_MODE_GRAY_PLANES_HILBERT_DELTA << 4) | (activeMask4 & 0x0F));

        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) == 0) {
                outBuf[pos++] = activeConsts[c];
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
                outBuf[pos++] = 0;
                outBuf[pos++] = 0;
                int[] p = arena.planarParams[c];
                outBuf[pos++] = (byte) (p[0] >>> 8);
                outBuf[pos++] = (byte) p[0];
                outBuf[pos++] = (byte) (p[1] >>> 8);
                outBuf[pos++] = (byte) p[1];
                outBuf[pos++] = (byte) (p[2] >>> 8);
                outBuf[pos++] = (byte) p[2];
                outBuf[pos++] = (byte) (p[3] >>> 8);
                outBuf[pos++] = (byte) p[3];
                outBuf[pos++] = (byte) p[4]; // maxU8 = eMax - eMin
                outBuf[pos++] = (byte) p[5]; // minErr8 (signed byte eMin)
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

        // Section 4A Base MSB Slice: encode planes p = 7..lsbSplitBits (maxGap = 0 for drift-free MSB)
        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] uCh = arena.grayValues[c];
            byte[] upper = arena.msbReconstructed[c];
            Arrays.fill(upper, 0, nSamples, (byte) 0);
            int maxU8 = arena.planarParams[c][4] & 0xFF;

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
            byte[] uCh = arena.grayValues[c];
            byte[] msbOnly = arena.msbReconstructed[c];
            int maxU8 = arena.planarParams[c][4] & 0xFF;

            for (int p = lsbSplitBits - 1; p >= 0; p--) {
                int bitVal = 1 << p;
                if (bitVal > maxU8) {
                    planeModesWords[c] |= (PLANE_ALL_ZERO << (2 * p));
                    continue;
                }
                int nActive = 0;
                for (int i = 0; i < nSamples; i++) {
                    int mVal = msbOnly[i] & 0xFF;
                    if (mVal + bitVal <= maxU8) {
                        arena.activeBitPlane[nActive++] = (byte) ((uCh[i] >>> p) & 1);
                    }
                }
                if (nActive == 0) {
                    planeModesWords[c] |= (PLANE_ALL_ZERO << (2 * p));
                    continue;
                }
                // Keep ceiling plane (bitVal * 2 > maxU8) gap-exact so lower conditioned plane counts remain invariant
                int maxGap = (bitVal * 2 > maxU8) ? 0 : rlePolicy.maxMergeGap(p, radialDist);
                int[] modeAndBytes = encodeConditionedBitplane(
                    arena.activeBitPlane, nActive, maxGap, arena, outBuf, pos
                );
                planeModesWords[c] |= (modeAndBytes[0] << (2 * p));
                pos += modeAndBytes[1];

                if (bitVal * 2 > maxU8) {
                    int k = 0;
                    for (int i = 0; i < nSamples; i++) {
                        int mVal = msbOnly[i] & 0xFF;
                        if (mVal + bitVal <= maxU8) {
                            if (arena.activeBitPlane[k++] != 0) {
                                msbOnly[i] = (byte) (mVal | bitVal);
                            }
                        }
                    }
                }
            }
        }
        int lsbSliceBytes = (pos - outOff) - msbSliceBytes;

        for (int c = 0; c < 4; c++) {
            if (((activeMask4 >>> c) & 1) != 0) {
                int off = planeModesOffsets[c];
                int word = planeModesWords[c];
                outBuf[off] = (byte) (word >>> 8);
                outBuf[off + 1] = (byte) word;
            }
        }

        return new int[]{msbSliceBytes, lsbSliceBytes};
    }

    private static long quickEstimateColorSpaceSpan(
        byte[][] channels, int mask4, int nSamples, short[] curveToTyx, int depthK
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
     * Fits a 3D spatiotemporal linear model {@code clamp(0, 255, (c0_q8 + sx_q8*lx' + sy_q8*ly' + st_q8*lt) >> 8)}
     * and maps residuals via monotonic offset-binary {@code u_i = (v_i - pred_i) - minErr in [0, maxU8]}.
     * Returns {@link Long#MAX_VALUE} if the residual span exceeds 63 (indicating a step edge or modular wrap).
     */
    private static long fitPlanar3DChannel(
        byte[] srcCh, int nSamples, short[] curveToTyx, int depthK, int vx, int vy,
        boolean useWaveletHaar, LcvPrimitives.ScratchArena arena,
        int[] outParams, int[] outHaarStep, byte[] outHaarNibbles, int chIdx, byte[] outResidualsU
    ) {
        long sumV = 0L;
        long sumVx = 0L;
        long sumVy = 0L;
        long sumVt = 0L;
        for (int i = 0; i < nSamples; i++) {
            int v = srcCh[i] & 0xFF;
            int tyx = curveToTyx[i] & 0xFFFF;
            int lx = tyx & 15;
            int ly = (tyx >>> 4) & 15;
            int lt = tyx >>> 8;
            sumV += v;
            sumVx += (long) v * lx;
            sumVy += (long) v * ly;
            sumVt += (long) v * lt;
        }

        int sxInit = clampShort((int) Math.round((1024.0 * (2.0 * sumVx - 15.0 * sumV)) / (10880.0 * depthK)));
        int syInit = clampShort((int) Math.round((1024.0 * (2.0 * sumVy - 15.0 * sumV)) / (10880.0 * depthK)));
        int stInit = (depthK > 1)
            ? clampShort((int) Math.round((1024.0 * (2.0 * sumVt - (depthK - 1.0) * sumV)) / (256.0 * depthK * (depthK * depthK - 1.0) / 6.0)))
            : 0;

        int sxSnap = clampShort((int) Math.round(sxInit / 256.0) * 256);
        int sySnap = clampShort((int) Math.round(syInit / 256.0) * 256);
        int stSnap = clampShort((int) Math.round(stInit / 512.0) * 512);
        int bestSt = (Math.abs(stInit - stSnap) <= 220) ? stSnap : stInit;

        // 1. Test fully snapped (sxSnap, sySnap, bestSt) first (solves dyadic ramps like R in 1 pass!)
        int bestSx = sxSnap;
        int bestSy = sySnap;
        long[] bestFull = evalPlanarCandidateScore(srcCh, nSamples, curveToTyx, bestSx, bestSy, bestSt, 1, arena);
        long bestFullScore = bestFull[0];
        int bestFrac8 = (int) bestFull[1];

        // 2. If span > 4 and |sxInit - syInit| <= 96, sweep symmetric diagonal (sDiag + d, sDiag + d, bestSt) (solves B!)
        if ((bestFullScore >>> 20) > 4 && Math.abs(sxInit - syInit) <= 96) {
            int sDiag = (sxInit + syInit + 1) >> 1;
            for (int d = -36; d <= 36; d++) {
                int cand = clampShort(sDiag + d);
                long[] sc = evalPlanarCandidateScore(srcCh, nSamples, curveToTyx, cand, cand, bestSt, 1, arena);
                if (sc[0] < bestFullScore) {
                    bestFullScore = sc[0];
                    bestSx = cand;
                    bestSy = cand;
                    bestFrac8 = (int) sc[1];
                }
            }
        }

        // 3. If span > 4, sweep vertical (sxSnap, syInit + dy, bestSt) (solves G!)
        if ((bestFullScore >>> 20) > 4 && Math.abs(sxInit - sxSnap) <= 96) {
            for (int dy = -40; dy <= 40; dy++) {
                int candSy = clampShort(syInit + dy);
                long[] sc = evalPlanarCandidateScore(srcCh, nSamples, curveToTyx, sxSnap, candSy, bestSt, 1, arena);
                if (sc[0] < bestFullScore) {
                    bestFullScore = sc[0];
                    bestSx = sxSnap;
                    bestSy = candSy;
                    bestFrac8 = (int) sc[1];
                }
            }
        }

        // 4. If span > 4, sweep horizontal (sxInit + dx, sySnap, bestSt)
        if ((bestFullScore >>> 20) > 4 && Math.abs(syInit - sySnap) <= 96) {
            for (int dx = -40; dx <= 40; dx++) {
                int candSx = clampShort(sxInit + dx);
                long[] sc = evalPlanarCandidateScore(srcCh, nSamples, curveToTyx, candSx, sySnap, bestSt, 1, arena);
                if (sc[0] < bestFullScore) {
                    bestFullScore = sc[0];
                    bestSx = candSx;
                    bestSy = sySnap;
                    bestFrac8 = (int) sc[1];
                }
            }
        }

        // 5. Fallback general 2D coordinate refinement around (sxInit, syInit, stInit)
        if ((bestFullScore >>> 20) > 4) {
            int curSx = sxInit;
            int curSy = syInit;
            int curSt = bestSt;
            for (int dx = -24; dx <= 24; dx += 2) {
                int candSx = clampShort(sxInit + dx);
                long[] sc = evalPlanarCandidateScore(srcCh, nSamples, curveToTyx, candSx, curSy, curSt, 1, arena);
                if (sc[0] < bestFullScore) {
                    bestFullScore = sc[0];
                    curSx = candSx;
                    bestSx = candSx;
                    bestSy = curSy;
                    bestSt = curSt;
                    bestFrac8 = (int) sc[1];
                }
            }
            for (int dy = -24; dy <= 24; dy += 2) {
                int candSy = clampShort(syInit + dy);
                long[] sc = evalPlanarCandidateScore(srcCh, nSamples, curveToTyx, bestSx, candSy, bestSt, 1, arena);
                if (sc[0] < bestFullScore) {
                    bestFullScore = sc[0];
                    bestSy = candSy;
                    bestFrac8 = (int) sc[1];
                }
            }
            if (depthK > 1) {
                for (int dt = -24; dt <= 24; dt += 4) {
                    int candSt = clampShort(stInit + dt);
                    long[] sc = evalPlanarCandidateScore(srcCh, nSamples, curveToTyx, bestSx, bestSy, candSt, 1, arena);
                    if (sc[0] < bestFullScore) {
                        bestFullScore = sc[0];
                        bestSt = candSt;
                        bestFrac8 = (int) sc[1];
                    }
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
                // Smooth channel inside a wavelet cube: set haarStep = 0 (1B header, 0 nibble bytes)
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

            long sumU = 0L;
            if (outResidualsU != null) {
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
                    int u = (v - pred) - minErr;
                    outResidualsU[i] = (byte) u;
                    sumU += u;
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
        for (int i = 0; i < nSamples; i++) {
            int v = srcCh[i] & 0xFF;
            int tyx = curveToTyx[i] & 0xFFFF;
            int lx = tyx & 15;
            int ly = (tyx >>> 4) & 15;
            int lt = tyx >>> 8;
            int raw = (bestSx * lx + bestSy * ly + bestSt * lt) >> 2;
            int pred = (bestFrac8 + raw) >> 8;
            int err = v - pred;
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
                int u = (v - pred) - minErr;
                outResidualsU[i] = (byte) u;
                sumU += u;
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
        byte[] srcCh, int nSamples, short[] curveToTyx,
        int sx, int sy, int st, int stride, LcvPrimitives.ScratchArena arena
    ) {
        int[] bMin = arena.bMin256;
        int[] bMax = arena.bMax256;
        int[] bCount = arena.bCount256;
        Arrays.fill(bMin, Integer.MAX_VALUE);
        Arrays.fill(bMax, Integer.MIN_VALUE);
        Arrays.fill(bCount, 0);
        long sumE0 = 0L;
        int count = 0;

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
            sumE0 += e0;
            count++;
        }

        int[] prefMin = arena.prefMin256;
        int[] prefMax = arena.prefMax256;
        int curMin = Integer.MAX_VALUE;
        int curMax = Integer.MIN_VALUE;
        for (int r = 0; r < 256; r++) {
            if (bMin[r] < curMin) {
                curMin = bMin[r];
            }
            if (bMax[r] > curMax) {
                curMax = bMax[r];
            }
            prefMin[r] = curMin;
            prefMax[r] = curMax;
        }

        int bestSpan = curMax - curMin;
        int bestEmin = curMin;
        int bestEmax = curMax;
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
            if (span < bestSpan || (span == bestSpan && imbalance < bestImbalance)) {
                bestSpan = span;
                bestEmin = eMin;
                bestEmax = eMax;
                bestFrac = 256 - t;
                bestImbalance = imbalance;
            }
        }

        return new long[]{((long) bestSpan << 20) + Math.min(0xFFFFFL, bestImbalance), bestFrac & 0xFF};
    }

    private static int clampShort(int val) {
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, val));
    }

    /**
     * Encodes an active conditioned bit sequence {@code activeBits[0..nActive-1]} with optional
     * distance-scaled run concatenation ({@code rawGap <= maxMergeGap}), competing {@code mode 10}
     * nibble RLE against {@code mode 11} raw bitpack and 3-sample triplet zero/one-skip prefix coding.
     */
    public static int[] encodeConditionedBitplane(
        byte[] activeBits, int nActive, int maxMergeGap,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        if (nActive <= 0) {
            return new int[]{PLANE_ALL_ZERO, 0};
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
        if (runCount == 1 && rawGaps[0] == 0 && runLens[0] == nActive) {
            return new int[]{PLANE_ALL_ONE, 0};
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
        if (runCount == 1 && rawGaps[0] == 0 && runLens[0] == nActive) {
            return new int[]{PLANE_ALL_ONE, 0};
        }

        int rleBytes = writeNibbleRunStream(rawGaps, runLens, runCount, arena.planeScratchBuf, 0);

        // Reconstruct bit sequence in arena.activeBitPlane and count 1s for dense candidate evaluation
        byte[] bits = arena.activeBitPlane;
        Arrays.fill(bits, 0, nActive, (byte) 0);
        int cursor = 0;
        int onesCount = 0;
        for (int r = 0; r < runCount; r++) {
            cursor += rawGaps[r];
            int len = runLens[r];
            onesCount += len;
            for (int k = 0; k < len; k++) {
                bits[cursor + k] = 1;
            }
            cursor += len;
        }

        int rawBitmapPayloadBytes = (nActive + 7) >>> 3;
        int bestDenseBytes = 1 + rawBitmapPayloadBytes;
        int bestDenseTag = DENSE_TAG_RAW_BITMAP;

        byte[] tripScratch = arena.tripletScratchBuf;
        if (onesCount * 100 <= nActive * 32) {
            int tripPayloadLen = encodeTripletStream(bits, nActive, false, tripScratch, 3);
            if (3 + tripPayloadLen < bestDenseBytes) {
                bestDenseBytes = 3 + tripPayloadLen;
                bestDenseTag = DENSE_TAG_TRIPLET_ZERO_SKIP;
                tripScratch[0] = (byte) DENSE_TAG_TRIPLET_ZERO_SKIP;
                tripScratch[1] = (byte) (tripPayloadLen >>> 8);
                tripScratch[2] = (byte) tripPayloadLen;
            }
        } else if (onesCount * 100 >= nActive * 68) {
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

        if (bestDenseTag != DENSE_TAG_RAW_BITMAP) {
            System.arraycopy(tripScratch, 0, dst, dstOff, bestDenseBytes);
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
        if (nActive <= 0 || mode2 == PLANE_ALL_ZERO) {
            Arrays.fill(outActiveBits, 0, Math.max(0, nActive), (byte) 0);
            return;
        }
        if (mode2 == PLANE_ALL_ONE) {
            Arrays.fill(outActiveBits, 0, nActive, (byte) 1);
            return;
        }
        if (mode2 == PLANE_NIBBLE_RLE) {
            Arrays.fill(outActiveBits, 0, nActive, (byte) 0);
            int runCount = readNibbleRunStream(src, posRef, limit, nActive, arena.rawGaps, arena.runLens);
            int cursor = 0;
            for (int r = 0; r < runCount; r++) {
                cursor += arena.rawGaps[r];
                int len = arena.runLens[r];
                for (int k = 0; k < len; k++) {
                    outActiveBits[cursor + k] = 1;
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
            for (int i = 0; i < nActive; i++) {
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
                src, pos, payloadLen, nActive,
                denseTag8 == DENSE_TAG_TRIPLET_ONE_SKIP, outActiveBits
            );
            posRef[0] = pos + payloadLen;
        } else {
            throw new LcvCorruptTileException("CBIT: unknown denseTag8 " + denseTag8 + " at plane " + planeIdx);
        }
    }

    /**
     * Encodes bitplane {@code planeIdx} ({@code 0..7}) with optional distance-scaled run concatenation
     * ({@code rawGap <= maxMergeGap}), returning {@code {planeMode2, bytesWritten}}.
     */
    private static int[] encodeSingleBitplane(
        byte[] grayCh, int nSamples, int planeIdx, int maxMergeGap,
        LcvPrimitives.ScratchArena arena, byte[] dst, int dstOff
    ) {
        int[] rawGaps = arena.rawGaps;
        int[] runLens = arena.runLens;
        int runCount = 0;
        int i = 0;
        int lastEnd = 0;

        while (i < nSamples) {
            while (i < nSamples && (((grayCh[i] >>> planeIdx) & 1) == 0)) {
                i++;
            }
            if (i >= nSamples) {
                break;
            }
            int start = i;
            while (i < nSamples && (((grayCh[i] >>> planeIdx) & 1) != 0)) {
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

        // Apply variable-lossy distance-based run-length concatenation (REQ-LCV-F-002)
        if (maxMergeGap > 0 && runCount > 1) {
            runCount = coalesceRunArraysInPlace(rawGaps, runLens, runCount, maxMergeGap);
        }

        if (runCount == 1 && rawGaps[0] == 0 && runLens[0] == nSamples) {
            return new int[]{PLANE_ALL_ONE, 0};
        }

        int rleBytes = writeNibbleRunStream(rawGaps, runLens, runCount, arena.planeScratchBuf, 0);
        int bitmapBytes = nSamples >>> 3;
        if (rleBytes < bitmapBytes) {
            System.arraycopy(arena.planeScratchBuf, 0, dst, dstOff, rleBytes);
            return new int[]{PLANE_NIBBLE_RLE, rleBytes};
        }

        // Hard mode 11 bitmap ceiling (never expands beyond N_samples / 8 bytes)
        Arrays.fill(dst, dstOff, dstOff + bitmapBytes, (byte) 0);
        int cursor = 0;
        for (int r = 0; r < runCount; r++) {
            cursor += rawGaps[r];
            int len = runLens[r];
            for (int k = 0; k < len; k++) {
                int bitPos = cursor + k;
                dst[dstOff + (bitPos >>> 3)] |= (byte) (1 << (bitPos & 7));
            }
            cursor += len;
        }
        return new int[]{PLANE_RAW_BITMAP, bitmapBytes};
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
        validateDepthK(depthK);
        if (lsbSplitBits < 1 || lsbSplitBits > 4) {
            throw new LcvCorruptTileException("CBIT: invalid lsbSplitBits " + lsbSplitBits);
        }
        if (arena == null) {
            arena = LcvPrimitives.threadArena();
        }
        int msbLimit = msbOff + msbLen;
        int lsbLimit = lsbOff + lsbLen;
        if (msbOff < 0 || msbLen <= 0 || msbLimit > msbPool.length) {
            throw new LcvCorruptTileException("CBIT: invalid MSB slice bounds");
        }
        if (!contiguousSingleBuffer && (lsbOff < 0 || lsbLen < 0 || lsbLimit > lsbPool.length)) {
            throw new LcvCorruptTileException("CBIT: invalid LSB slice bounds");
        }

        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        int pos = msbOff;
        int header8 = msbPool[pos++] & 0xFF;
        int curveId = (header8 >>> 6) & 0x03;
        int cubeMode = (header8 >>> 4) & 0x03;
        int low4 = header8 & 0x0F;

        short[] curveToTyx = LcvPrimitives.curveToTyxLut(depthK, curveId);

        if (cubeMode == CUBE_MODE_UI_PALETTE_ROARING) {
            decodeUiPaletteRoaring(msbPool, pos, msbLimit, low4 + 2, nSamples, curveToTyx, arena, dstRgbaFrames);
            return;
        }

        int channelMask4 = low4;
        byte[] constVals = new byte[4];
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                if (pos >= msbLimit) {
                    throw new LcvCorruptTileException("CBIT: truncated constVal8 for channel " + c);
                }
                constVals[c] = msbPool[pos++];
            }
        }

        if (cubeMode == CUBE_MODE_RAW_CEILING) {
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
        if (cubeMode == CUBE_MODE_GRAY_PLANES_HILBERT_DELTA) {
            if (pos >= msbLimit) {
                throw new LcvCorruptTileException("CBIT: truncated deltaFlags8 in GRAY_PLANES_HILBERT_DELTA");
            }
            deltaFlags8 = msbPool[pos++] & 0xFF;
            if ((deltaFlags8 & DELTA_FLAG_MOTION_SHEARED) != 0) {
                if (pos >= msbLimit) {
                    throw new LcvCorruptTileException("CBIT: truncated packedMv8 in GRAY_PLANES_HILBERT_DELTA");
                }
                packedMv8 = msbPool[pos++] & 0xFF;
            }
        }

        if ((deltaFlags8 & DELTA_SUBMODE_PLANAR_3D_MV) != 0) {
            decodePlanarMvCubeRgba(
                msbPool, pos, msbLimit,
                lsbPool, lsbOff, lsbLen, lsbLimit,
                contiguousSingleBuffer, channelMask4, constVals,
                deltaFlags8, packedMv8, nSamples, lsbSplitBits, curveToTyx, arena, dstRgbaFrames
            );
            return;
        }

        int[] planeModesWords = new int[4];
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                if (pos + 2 > msbLimit) {
                    throw new LcvCorruptTileException("CBIT: truncated planeModes16 for channel " + c);
                }
                planeModesWords[c] = ((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF);
                pos += 2;
            }
        }

        int[] posRef = arena.posCursor;
        posRef[0] = pos;

        // Clear grayValues for active channels
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                Arrays.fill(arena.grayValues[c], 0, nSamples, (byte) 0);
            }
        }

        // Decode Base MSB Planes p = 7..lsbSplitBits from msbPool
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] grayCh = arena.grayValues[c];
            int pModes = planeModesWords[c];
            for (int p = 7; p >= lsbSplitBits; p--) {
                int mode2 = (pModes >>> (2 * p)) & 0x03;
                decodeSingleBitplane(msbPool, posRef, msbLimit, nSamples, p, mode2, grayCh, arena);
            }
        }
        if (!contiguousSingleBuffer && posRef[0] != msbLimit) {
            throw new LcvCorruptTileException(
                "CBIT: MSB slice unconsumed trailing bytes (" + posRef[0] + " != " + msbLimit + ")"
            );
        }

        // Decode Tail LSB Planes p = (lsbSplitBits - 1)..0
        boolean lsbWasStripped = false;
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
            for (int p = lsbSplitBits - 1; p >= 0; p--) {
                int mode2 = (pModes >>> (2 * p)) & 0x03;
                if ((mode2 == PLANE_NIBBLE_RLE || mode2 == PLANE_RAW_BITMAP)
                    && !contiguousSingleBuffer && lsbLen == 0) {
                    lsbWasStripped = true;
                    continue;
                }
                decodeSingleBitplane(tailBuf, posRef, tailLimit, nSamples, p, mode2, grayCh, arena);
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

        // Invert Gray code (and (8 - s)-bit MSB Hilbert-Delta if mode 10)
        for (int c = 0; c < 4; c++) {
            byte[] ch = arena.channelValues[c];
            if (((channelMask4 >>> c) & 1) == 0) {
                Arrays.fill(ch, 0, nSamples, constVals[c]);
            } else {
                byte[] grayCh = arena.grayValues[c];
                if (cubeMode == CUBE_MODE_GRAY_PLANES_RAW) {
                    for (int i = 0; i < nSamples; i++) {
                        ch[i] = (byte) LcvPrimitives.grayToBinary8(grayCh[i] & 0xFF);
                    }
                } else {
                    int prevM = 0;
                    for (int i = 0; i < nSamples; i++) {
                        int g = grayCh[i] & 0xFF;
                        int gMsb = (g >>> lsbSplitBits) & msbMask;
                        int u = (i == 0) ? LcvPrimitives.grayToBinaryN(gMsb, msbBits) : gMsb;
                        int l = LcvPrimitives.grayToBinaryN(g & lsbMask, lsbSplitBits);
                        int m = (i == 0) ? u : ((prevM + LcvPrimitives.zigZagDecodeN(u, msbBits)) & msbMask);
                        prevM = m;
                        ch[i] = (byte) ((m << lsbSplitBits) | l);
                    }
                }
            }
        }

        reconstructRgbaFromYCoCgChannels(nSamples, curveToTyx, arena, dstRgbaFrames);
    }

    private static void decodePlanarMvCubeRgba(
        byte[] msbPool, int pos, int msbLimit,
        byte[] lsbPool, int lsbOff, int lsbLen, int lsbLimit,
        boolean contiguousSingleBuffer, int channelMask4, byte[] constVals,
        int deltaFlags8, int packedMv8, int nSamples, int lsbSplitBits,
        short[] curveToTyx, LcvPrimitives.ScratchArena arena, int[] dstRgbaFrames
    ) throws LcvCorruptTileException {
        int vx = LcvPrimitives.unpackVelocityX(packedMv8);
        int vy = LcvPrimitives.unpackVelocityY(packedMv8);
        boolean directRgba = (deltaFlags8 & DELTA_FLAG_DIRECT_RGBA) != 0;
        boolean hasWaveletHaar = (deltaFlags8 & DELTA_SUBMODE_WAVELET_3D_HAAR) != 0;

        int[] planeModesWords = new int[4];
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                if (pos + (hasWaveletHaar ? 13 : 12) > msbLimit) {
                    throw new LcvCorruptTileException("CBIT: truncated planar MV header for channel " + c);
                }
                planeModesWords[c] = ((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF);
                pos += 2;
                int[] p = arena.planarParams[c];
                p[0] = (short) (((msbPool[pos] & 0xFF) << 8) | (msbPool[pos + 1] & 0xFF));
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

        int[] posRef = arena.posCursor;
        posRef[0] = pos;

        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                Arrays.fill(arena.grayValues[c], 0, nSamples, (byte) 0);
            }
        }

        // Decode Section 4A Base MSB Planes p = 7..lsbSplitBits
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] uCh = arena.grayValues[c];
            int pModes = planeModesWords[c];
            int maxU8 = arena.planarParams[c][4] & 0xFF;

            for (int p = 7; p >= lsbSplitBits; p--) {
                int bitVal = 1 << p;
                int mode2 = (pModes >>> (2 * p)) & 0x03;
                if (bitVal > maxU8 || mode2 == PLANE_ALL_ZERO) {
                    continue;
                }
                int nActive = 0;
                for (int i = 0; i < nSamples; i++) {
                    if ((uCh[i] & 0xFF) + bitVal <= maxU8) {
                        nActive++;
                    }
                }
                decodeConditionedBitplane(
                    msbPool, posRef, msbLimit, nActive, p, mode2, arena.activeBitPlane, arena
                );
                int k = 0;
                for (int i = 0; i < nSamples; i++) {
                    if ((uCh[i] & 0xFF) + bitVal <= maxU8) {
                        if (arena.activeBitPlane[k++] != 0) {
                            uCh[i] |= (byte) bitVal;
                        }
                    }
                }
            }
            System.arraycopy(uCh, 0, arena.msbReconstructed[c], 0, nSamples);
        }
        if (!contiguousSingleBuffer && posRef[0] != msbLimit) {
            throw new LcvCorruptTileException(
                "CBIT: planar MV MSB slice unconsumed trailing bytes (" + posRef[0] + " != " + msbLimit + ")"
            );
        }

        // Decode Section 4B Tail LSB Planes p = (lsbSplitBits - 1)..0 conditioned strictly on Section 4A M_i
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
            byte[] uCh = arena.grayValues[c];
            byte[] msbOnly = arena.msbReconstructed[c];
            int pModes = planeModesWords[c];
            int maxU8 = arena.planarParams[c][4] & 0xFF;

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
                if ((mode2 == PLANE_NIBBLE_RLE || mode2 == PLANE_RAW_BITMAP)
                    && !contiguousSingleBuffer && lsbLen == 0) {
                    lsbWasStripped = true;
                    channelLsbStripped[c] = true;
                    continue;
                }
                decodeConditionedBitplane(
                    tailBuf, posRef, tailLimit, nActive, p, mode2, arena.activeBitPlane, arena
                );
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

        int lsbMaxAdd = (1 << lsbSplitBits) - 1;
        for (int c = 0; c < 4; c++) {
            byte[] ch = arena.channelValues[c];
            if (((channelMask4 >>> c) & 1) == 0) {
                Arrays.fill(ch, 0, nSamples, constVals[c]);
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
                for (int i = 0; i < nSamples; i++) {
                    int tyx = curveToTyx[i] & 0xFFFF;
                    int lx = tyx & 15;
                    int ly = (tyx >>> 4) & 15;
                    int lt = tyx >>> 8;
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
        }

        if (directRgba) {
            byte[] rCh = arena.channelValues[0];
            byte[] gCh = arena.channelValues[1];
            byte[] bCh = arena.channelValues[2];
            byte[] aCh = arena.channelValues[3];
            for (int i = 0; i < nSamples; i++) {
                int rgba = ((rCh[i] & 0xFF) << 24)
                    | ((gCh[i] & 0xFF) << 16)
                    | ((bCh[i] & 0xFF) << 8)
                    | (aCh[i] & 0xFF);
                dstRgbaFrames[curveToTyx[i] & 0xFFFF] = rgba;
            }
        } else {
            reconstructRgbaFromYCoCgChannels(nSamples, curveToTyx, arena, dstRgbaFrames);
        }
    }

    private static void decodeSingleBitplane(
        byte[] src, int[] posRef, int limit, int nSamples, int planeIdx, int mode2,
        byte[] grayCh, LcvPrimitives.ScratchArena arena
    ) throws LcvCorruptTileException {
        int bitMask = 1 << planeIdx;
        if (mode2 == PLANE_ALL_ZERO) {
            return;
        }
        if (mode2 == PLANE_ALL_ONE) {
            for (int i = 0; i < nSamples; i++) {
                grayCh[i] |= (byte) bitMask;
            }
            return;
        }
        if (mode2 == PLANE_NIBBLE_RLE) {
            int runCount = readNibbleRunStream(src, posRef, limit, nSamples, arena.rawGaps, arena.runLens);
            int cursor = 0;
            for (int r = 0; r < runCount; r++) {
                cursor += arena.rawGaps[r];
                int len = arena.runLens[r];
                for (int k = 0; k < len; k++) {
                    grayCh[cursor + k] |= (byte) bitMask;
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
        for (int i = 0; i < nSamples; i++) {
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
        if (paletteSize < 2 || paletteSize > 16) {
            throw new LcvCorruptTileException("CBIT: invalid UI palette size " + paletteSize);
        }
        if (pos + paletteSize * 4 > limit) {
            throw new LcvCorruptTileException("CBIT: truncated UI palette table");
        }
        int[] palette = arena.paletteColors;
        for (int p = 0; p < paletteSize; p++) {
            palette[p] = ((src[pos] & 0xFF) << 24)
                | ((src[pos + 1] & 0xFF) << 16)
                | ((src[pos + 2] & 0xFF) << 8)
                | (src[pos + 3] & 0xFF);
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
        Arrays.fill(curveRgba, 0, nSamples, palette[0]);

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
                curveRgba[idx] = color;
            } else if (subTag == ROARING_TAG_DELTA_RUN) {
                int runCount = readNibbleRunStream(src, posRef, limit, nSamples, arena.rawGaps, arena.runLens);
                int cursor = 0;
                for (int r = 0; r < runCount; r++) {
                    cursor += arena.rawGaps[r];
                    int len = arena.runLens[r];
                    for (int k = 0; k < len; k++) {
                        curveRgba[cursor + k] = color;
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
                    curveRgba[idx] = color;
                    prev = idx;
                }
            } else {
                // ROARING_TAG_BITMAP
                int bPos = posRef[0];
                if (bPos + bitmapBytes > limit) {
                    throw new LcvCorruptTileException("CBIT: truncated ROARING_BITMAP");
                }
                for (int i = 0; i < nSamples; i++) {
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

        for (int i = 0; i < nSamples; i++) {
            dstRgbaFrames[curveToTyx[i] & 0xFFFF] = curveRgba[i];
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

    /**
     * Extracts a {@code 16 x 16 x K} tile at tile grid coordinates {@code (tileX, tileY)} into
     * {@code dstCubeTyx} (`256 * K` ints), clamping boundary coordinates when {@code frameWidth}
     * or {@code frameHeight} is not a multiple of 16.
     */
    public static void extractTileToCubeTyx(
        int[] rgbaFrames, int tileX, int tileY, int frameWidth, int frameHeight, int depthK, int[] dstCubeTyx
    ) {
        int frameArea = frameWidth * frameHeight;
        int baseX = tileX * LcvPrimitives.TILE_DIM;
        int baseY = tileY * LcvPrimitives.TILE_DIM;

        for (int t = 0; t < depthK; t++) {
            int frameBase = t * frameArea;
            int cubeTBase = t << 8;
            for (int ly = 0; ly < LcvPrimitives.TILE_DIM; ly++) {
                int py = Math.min(frameHeight - 1, baseY + ly);
                int rowBase = frameBase + py * frameWidth;
                int cubeRowBase = cubeTBase | (ly << 4);
                for (int lx = 0; lx < LcvPrimitives.TILE_DIM; lx++) {
                    int px = Math.min(frameWidth - 1, baseX + lx);
                    dstCubeTyx[cubeRowBase | lx] = rgbaFrames[rowBase + px];
                }
            }
        }
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
