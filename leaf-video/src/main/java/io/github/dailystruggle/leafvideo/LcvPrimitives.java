package io.github.dailystruggle.leafvideo;

import java.util.Arrays;

/**
 * Self-contained, zero-dependency primitives for {@code LCV-ICD-1.0}: 3D and column-Hilbert
 * space-filling curve LUTs, 1-pass directional anisotropy curve selection, Extended Hamming
 * {@code (72, 64)} {@code SEC-DED} self-healing, reversible {@code RGBA <-> YCoCg-R} lifting,
 * and thread-local {@link ScratchArena} buffers.
 *
 * @governedBy leaf-video-ADR-001
 * @trace REQ-LCV-F-001
 * @trace REQ-LCV-F-004
 */
public final class LcvPrimitives {

    public static final int TILE_DIM = 16;
    public static final int TILE_PIXELS = TILE_DIM * TILE_DIM; // 256
    public static final int MAX_DEPTH_K = 16;
    public static final int MAX_CUBE_SAMPLES = TILE_PIXELS * MAX_DEPTH_K; // 4096

    public static final int CURVE_TYX = 0;   // 00: t -> y -> x (horizontal scanline)
    public static final int CURVE_H3 = 1;    // 01: 3D Hilbert (4x4x4 octants)
    public static final int CURVE_T_H2 = 2;  // 10: 2D Hilbert (x, y) outer, temporal t inner
    public static final int CURVE_TXY = 3;   // 11: t -> x -> y (vertical scanline)

    /**
     * Reserved pointer value (bit 63 = 0, bit 62 = 1) returned by {@link #healSingleBitFlip64}
     * when a double-bit (uncorrectable) error is detected.
     */
    public static final long UNCORRECTABLE_DED_SENTINEL = 0x4000_0000_0000_0000L;

    private static final int[] H2_TO_XY_256 = new int[TILE_PIXELS];
    private static final int[] XY_TO_H2_256 = new int[TILE_PIXELS];

    private static final short[][] CURVE_TO_TYX_K1 = new short[4][TILE_PIXELS];
    private static final short[][] TYX_TO_CURVE_K1 = new short[4][TILE_PIXELS];
    private static final short[][] CURVE_TO_TYX_K2 = new short[4][TILE_PIXELS * 2];
    private static final short[][] TYX_TO_CURVE_K2 = new short[4][TILE_PIXELS * 2];
    private static final short[][] CURVE_TO_TYX_K4 = new short[4][TILE_PIXELS * 4];
    private static final short[][] TYX_TO_CURVE_K4 = new short[4][TILE_PIXELS * 4];
    private static final short[][] CURVE_TO_TYX_K8 = new short[4][TILE_PIXELS * 8];
    private static final short[][] TYX_TO_CURVE_K8 = new short[4][TILE_PIXELS * 8];
    private static final short[][] CURVE_TO_TYX_K16 = new short[4][TILE_PIXELS * 16];
    private static final short[][] TYX_TO_CURVE_K16 = new short[4][TILE_PIXELS * 16];

    private static final int[] DATA_BIT_TO_HAMMING_POS = new int[64];
    private static final int[] HAMMING_POS_TO_DATA_BIT = new int[128];

    private static final ThreadLocal<ScratchArena> THREAD_ARENA =
        ThreadLocal.withInitial(ScratchArena::new);

    static {
        initHilbert2D();
        initCurvesForDepth(1, CURVE_TO_TYX_K1, TYX_TO_CURVE_K1);
        initCurvesForDepth(2, CURVE_TO_TYX_K2, TYX_TO_CURVE_K2);
        initCurvesForDepth(4, CURVE_TO_TYX_K4, TYX_TO_CURVE_K4);
        initCurvesForDepth(8, CURVE_TO_TYX_K8, TYX_TO_CURVE_K8);
        initCurvesForDepth(16, CURVE_TO_TYX_K16, TYX_TO_CURVE_K16);
        initHammingTables();
    }

    private LcvPrimitives() {}

    public static ScratchArena threadArena() {
        return THREAD_ARENA.get();
    }

    private static void initHilbert2D() {
        for (int idx = 0; idx < TILE_PIXELS; idx++) {
            int x = 0;
            int y = 0;
            int t = idx;
            for (int s = 1; s < TILE_DIM; s <<= 1) {
                int rx = 1 & (t >>> 1);
                int ry = 1 & (t ^ rx);
                if (ry == 0) {
                    if (rx == 1) {
                        x = s - 1 - x;
                        y = s - 1 - y;
                    }
                    int tmp = x;
                    x = y;
                    y = tmp;
                }
                x += s * rx;
                y += s * ry;
                t >>>= 2;
            }
            int xy = (y << 4) | x;
            H2_TO_XY_256[idx] = xy;
            XY_TO_H2_256[xy] = idx;
        }
    }

    /**
     * Bijective 3D Gray-Hilbert coordinates inside a 4x4x4 sub-octant (64 points).
     */
    private static int hilbert3D4x4x4(int idx) {
        int oct1 = (idx >>> 3) & 7;
        int oct0 = idx & 7;
        int g1 = oct1 ^ (oct1 >>> 1);
        int fine = ((oct1 & 1) == 0) ? oct0 : (7 - oct0);
        int g0 = fine ^ (fine >>> 1);

        int x = (((g1 >>> 2) & 1) << 1) | ((g0 >>> 2) & 1);
        int y = (((g1 >>> 1) & 1) << 1) | ((g0 >>> 1) & 1);
        int z = ((g1 & 1) << 1) | (g0 & 1);
        return (z << 4) | (y << 2) | x;
    }

    private static void initCurvesForDepth(int depthK, short[][] curveToTyx, short[][] tyxToCurve) {
        int nSamples = TILE_PIXELS * depthK;
        // 00: CURVE_TYX
        for (int i = 0; i < nSamples; i++) {
            curveToTyx[CURVE_TYX][i] = (short) i;
            tyxToCurve[CURVE_TYX][i] = (short) i;
        }
        // 11: CURVE_TXY
        int cTxy = 0;
        for (int t = 0; t < depthK; t++) {
            for (int x = 0; x < TILE_DIM; x++) {
                for (int y = 0; y < TILE_DIM; y++) {
                    int tyx = (t << 8) | (y << 4) | x;
                    curveToTyx[CURVE_TXY][cTxy] = (short) tyx;
                    tyxToCurve[CURVE_TXY][tyx] = (short) cTxy;
                    cTxy++;
                }
            }
        }
        // 10: CURVE_T_H2 (2D Hilbert outer over 16x16, temporal column t = 0..K-1 inner)
        int cTH2 = 0;
        for (int h2 = 0; h2 < TILE_PIXELS; h2++) {
            int xy = H2_TO_XY_256[h2];
            for (int t = 0; t < depthK; t++) {
                int tyx = (t << 8) | xy;
                curveToTyx[CURVE_T_H2][cTH2] = (short) tyx;
                tyxToCurve[CURVE_T_H2][tyx] = (short) cTH2;
                cTH2++;
            }
        }
        // 01: CURVE_H3 (4x4x4 3D Hilbert sub-octants ordered along 2D Hilbert of 4x4 macro-grid and t/4)
        int[] macroOrderX = {0, 1, 1, 0, 0, 0, 1, 1, 2, 2, 3, 3, 3, 2, 2, 3};
        int[] macroOrderY = {0, 0, 1, 1, 2, 3, 3, 2, 2, 3, 3, 2, 1, 1, 0, 0};
        int cH3 = 0;
        int tBlocks = Math.max(1, depthK / 4);
        for (int tb = 0; tb < tBlocks; tb++) {
            for (int m = 0; m < 16; m++) {
                int mx = (tb & 1) == 0 ? macroOrderX[m] : macroOrderX[15 - m];
                int my = (tb & 1) == 0 ? macroOrderY[m] : macroOrderY[15 - m];
                for (int sub = 0; sub < 64; sub++) {
                    int zyx = hilbert3D4x4x4(sub);
                    int lx = zyx & 3;
                    int ly = (zyx >>> 2) & 3;
                    int lt = (zyx >>> 4) & 3;
                    int t = (tb << 2) | lt;
                    if (t >= depthK) {
                        continue;
                    }
                    int x = (mx << 2) | lx;
                    int y = (my << 2) | ly;
                    int tyx = (t << 8) | (y << 4) | x;
                    curveToTyx[CURVE_H3][cH3] = (short) tyx;
                    tyxToCurve[CURVE_H3][tyx] = (short) cH3;
                    cH3++;
                }
            }
        }
    }

    public static short[] curveToTyxLut(int depthK, int curveId) {
        if (curveId < 0 || curveId > 3) {
            throw new IllegalArgumentException("Invalid curveId: " + curveId);
        }
        return switch (depthK) {
            case 1 -> CURVE_TO_TYX_K1[curveId];
            case 2 -> CURVE_TO_TYX_K2[curveId];
            case 4 -> CURVE_TO_TYX_K4[curveId];
            case 8 -> CURVE_TO_TYX_K8[curveId];
            case 16 -> CURVE_TO_TYX_K16[curveId];
            default -> throw new IllegalArgumentException("Unsupported depthK (must be 1, 2, 4, 8, or 16): " + depthK);
        };
    }

    public static short[] tyxToCurveLut(int depthK, int curveId) {
        if (curveId < 0 || curveId > 3) {
            throw new IllegalArgumentException("Invalid curveId: " + curveId);
        }
        return switch (depthK) {
            case 1 -> TYX_TO_CURVE_K1[curveId];
            case 2 -> TYX_TO_CURVE_K2[curveId];
            case 4 -> TYX_TO_CURVE_K4[curveId];
            case 8 -> TYX_TO_CURVE_K8[curveId];
            case 16 -> TYX_TO_CURVE_K16[curveId];
            default -> throw new IllegalArgumentException("Unsupported depthK (must be 1, 2, 4, 8, or 16): " + depthK);
        };
    }

    /**
     * 1-pass directional anisotropy curve selector over a {@code 16 x 16 x K} RGBA cube.
     */
    public static int selectOptimalCurve1Pass(int[] cubeRgbaTyx, int depthK) {
        long diffX = 0L;
        long diffY = 0L;
        long diffT = 0L;

        for (int t = 0; t < depthK; t++) {
            int tBase = t << 8;
            for (int y = 0; y < TILE_DIM; y++) {
                int rowBase = tBase | (y << 4);
                for (int x = 0; x < TILE_DIM; x++) {
                    int idx = rowBase | x;
                    int cur = cubeRgbaTyx[idx];
                    if (x + 1 < TILE_DIM) {
                        diffX += colorL1Dist(cur, cubeRgbaTyx[idx + 1]);
                    }
                    if (y + 1 < TILE_DIM) {
                        diffY += colorL1Dist(cur, cubeRgbaTyx[idx + TILE_DIM]);
                    }
                    if (t + 1 < depthK) {
                        diffT += colorL1Dist(cur, cubeRgbaTyx[idx + TILE_PIXELS]);
                    }
                }
            }
        }

        if (depthK == 1) {
            if (diffX * 10 < diffY * 6) {
                return CURVE_TYX;
            }
            if (diffY * 10 < diffX * 6) {
                return CURVE_TXY;
            }
            return CURVE_T_H2;
        }

        long normX = diffX * (depthK - 1);
        long normY = diffY * (depthK - 1);
        long normT = diffT * (TILE_DIM - 1);

        if (normT * 10 <= Math.min(normX, normY) * 8) {
            return CURVE_T_H2;
        }
        if (normX * 10 < normY * 6) {
            return CURVE_TYX;
        }
        if (normY * 10 < normX * 6) {
            return CURVE_TXY;
        }
        return CURVE_H3;
    }

    private static int colorL1Dist(int rgbaA, int rgbaB) {
        if (rgbaA == rgbaB) {
            return 0;
        }
        int dr = Math.abs(((rgbaA >>> 24) & 0xFF) - ((rgbaB >>> 24) & 0xFF));
        int dg = Math.abs(((rgbaA >>> 16) & 0xFF) - ((rgbaB >>> 16) & 0xFF));
        int db = Math.abs(((rgbaA >>> 8) & 0xFF) - ((rgbaB >>> 8) & 0xFF));
        int da = Math.abs((rgbaA & 0xFF) - (rgbaB & 0xFF));
        return dr + dg + db + da;
    }

    /**
     * Estimates the dominant 2D integer velocity vector {@code (vx, vy) in [-8, +7]^2} (in pixels/frame)
     * across {@code depthK} frames of a {@code 16 x 16 x K} cube, returning the packed byte
     * {@code ((vx + 8) << 4) | ((vy + 8) & 0x0F)} ({@code 0x88} = stationary {@code (0, 0)}).
     */
    public static int estimateTileVelocity1Pass(int[] cubeRgbaTyx, int depthK) {
        if (depthK <= 1) {
            return 0x88;
        }
        int bestVx = 0;
        int bestVy = 0;
        long bestSad = computeTrajectorySad(cubeRgbaTyx, depthK, 0, 0);
        if (bestSad == 0L) {
            return 0x88;
        }

        // Coarse diamond/grid search over [-4, +4]^2
        int[] candidates = {-4, -2, -1, 0, 1, 2, 4};
        for (int dvx : candidates) {
            for (int dvy : candidates) {
                if (dvx == 0 && dvy == 0) {
                    continue;
                }
                long sad = computeTrajectorySad(cubeRgbaTyx, depthK, dvx, dvy);
                // Require at least 8% SAD reduction to prefer non-zero MV over stationary (0,0)
                if (sad * 100L < bestSad * 92L) {
                    bestSad = sad;
                    bestVx = dvx;
                    bestVy = dvy;
                }
            }
        }

        // Local +/-1 refinement around best candidate
        if (bestVx != 0 || bestVy != 0) {
            int centerVx = bestVx;
            int centerVy = bestVy;
            for (int dvx = -1; dvx <= 1; dvx++) {
                for (int dvy = -1; dvy <= 1; dvy++) {
                    int vx = Math.max(-8, Math.min(7, centerVx + dvx));
                    int vy = Math.max(-8, Math.min(7, centerVy + dvy));
                    long sad = computeTrajectorySad(cubeRgbaTyx, depthK, vx, vy);
                    if (sad < bestSad) {
                        bestSad = sad;
                        bestVx = vx;
                        bestVy = vy;
                    }
                }
            }
        }
        return ((bestVx + 8) << 4) | ((bestVy + 8) & 0x0F);
    }

    public static int unpackVelocityX(int packedMv8) {
        return ((packedMv8 >>> 4) & 0x0F) - 8;
    }

    public static int unpackVelocityY(int packedMv8) {
        return (packedMv8 & 0x0F) - 8;
    }

    private static long computeTrajectorySad(int[] cubeRgbaTyx, int depthK, int vx, int vy) {
        long sad = 0L;
        int count = 0;
        int tStepLimit = Math.min(depthK - 1, 4);
        for (int t = 0; t < tStepLimit; t++) {
            int tBase0 = t << 8;
            int tBase1 = (t + 1) << 8;
            for (int y = 2; y < 14; y += 2) {
                int yRef = y - vy;
                if (yRef < 0 || yRef >= 16) {
                    continue;
                }
                for (int x = 2; x < 14; x += 2) {
                    int xRef = x - vx;
                    if (xRef < 0 || xRef >= 16) {
                        continue;
                    }
                    int cur = cubeRgbaTyx[tBase1 | (y << 4) | x];
                    int prev = cubeRgbaTyx[tBase0 | (yRef << 4) | xRef];
                    sad += colorL1Dist(cur, prev);
                    count++;
                }
            }
        }
        return count > 0 ? (sad * 64L / count) : Long.MAX_VALUE;
    }

    /**
     * Reversible forward {@code RGBA -> YCoCg-R + Alpha} packed as {@code (Y8 << 24) | (Co8 << 16) | (Cg8 << 8) | A8}.
     * Uses monotonic offset-binary {@code +128} centering for {@code Co8} and {@code Cg8} so bitplanes {@code g0..g2}
     * represent true magnitude LSBs (preventing chroma sign inversion under LSB stripping or {@code d_merge}).
     */
    public static int rgbaToYCoCgR(int rgba) {
        int r = (rgba >>> 24) & 0xFF;
        int g = (rgba >>> 16) & 0xFF;
        int b = (rgba >>> 8) & 0xFF;
        int a = rgba & 0xFF;

        int co = (byte) (r - b);
        int t = (b + (co >> 1)) & 0xFF;
        int cg = (byte) (g - t);
        int y8 = (t + (cg >> 1)) & 0xFF;
        int co8 = (co + 128) & 0xFF;
        int cg8 = (cg + 128) & 0xFF;

        return (y8 << 24) | (co8 << 16) | (cg8 << 8) | a;
    }

    /**
     * Reversible inverse {@code YCoCg-R + Alpha -> RGBA} (100% bit-exact over all {@code 256^4} inputs).
     */
    public static int yCoCgRToRgba(int yCoCgA) {
        int y8 = (yCoCgA >>> 24) & 0xFF;
        int co8 = (yCoCgA >>> 16) & 0xFF;
        int cg8 = (yCoCgA >>> 8) & 0xFF;
        int a = yCoCgA & 0xFF;

        int co = (byte) (co8 - 128);
        int cg = (byte) (cg8 - 128);
        int t = (y8 - (cg >> 1)) & 0xFF;
        int g = (cg + t) & 0xFF;
        int b = (t - (co >> 1)) & 0xFF;
        int r = (b + co) & 0xFF;

        return (r << 24) | (g << 16) | (b << 8) | a;
    }

    public static int binaryToGray8(int v) {
        int u = v & 0xFF;
        return u ^ (u >>> 1);
    }

    public static int grayToBinary8(int g) {
        int v = g & 0xFF;
        v ^= (v >>> 4);
        v ^= (v >>> 2);
        v ^= (v >>> 1);
        return v & 0xFF;
    }

    public static int zigZagEncode8(byte delta) {
        return ((delta << 1) ^ (delta >> 7)) & 0xFF;
    }

    public static byte zigZagDecode8(int zz8) {
        return (byte) (((zz8 >>> 1) ^ -(zz8 & 1)) & 0xFF);
    }

    public static int binaryToGray6(int m6) {
        int u = m6 & 0x3F;
        return u ^ (u >>> 1);
    }

    public static int grayToBinary6(int g6) {
        int v = g6 & 0x3F;
        v ^= (v >>> 4);
        v ^= (v >>> 2);
        v ^= (v >>> 1);
        return v & 0x3F;
    }

    public static int zigZagEncode6(int delta6) {
        int d = ((delta6 + 32) & 0x3F) - 32;
        return ((d << 1) ^ (d >> 31)) & 0x3F;
    }

    public static int zigZagDecode6(int zz6) {
        int u = zz6 & 0x3F;
        return (u >>> 1) ^ -(u & 1);
    }

    public static int binaryToGray2(int l2) {
        int u = l2 & 0x03;
        return u ^ (u >>> 1);
    }

    public static int grayToBinary2(int g2) {
        int u = g2 & 0x03;
        return u ^ (u >>> 1);
    }

    public static int binaryToGrayN(int u, int bits) {
        int mask = (1 << bits) - 1;
        int val = u & mask;
        return val ^ (val >>> 1);
    }

    public static int grayToBinaryN(int g, int bits) {
        int mask = (1 << bits) - 1;
        int v = g & mask;
        v ^= (v >>> 4);
        v ^= (v >>> 2);
        v ^= (v >>> 1);
        return v & mask;
    }

    public static int zigZagEncodeN(int delta, int bits) {
        int mask = (1 << bits) - 1;
        int half = 1 << (bits - 1);
        int d = ((delta + half) & mask) - half;
        return ((d << 1) ^ (d >> 31)) & mask;
    }

    public static int zigZagDecodeN(int zz, int bits) {
        int mask = (1 << bits) - 1;
        int u = zz & mask;
        return (u >>> 1) ^ -(u & 1);
    }

    private static void initHammingTables() {
        Arrays.fill(HAMMING_POS_TO_DATA_BIT, -1);
        int bitIdx = 0;
        for (int pos = 1; pos <= 71 && bitIdx < 64; pos++) {
            if ((pos & (pos - 1)) == 0) {
                continue; // Skip power-of-two parity positions 1, 2, 4, 8, 16, 32, 64
            }
            DATA_BIT_TO_HAMMING_POS[bitIdx] = pos;
            HAMMING_POS_TO_DATA_BIT[pos] = bitIdx;
            bitIdx++;
        }
    }

    /**
     * Computes the 8-bit Extended Hamming {@code (72, 64)} {@code SEC-DED} syndrome byte for {@code data64}.
     * Bits 0..6 hold the 7 Hamming parity bits; bit 7 holds the overall even parity bit.
     */
    public static byte computeHammingSecDed8(long data64) {
        int syndrome7 = 0;
        long tmp = data64;
        while (tmp != 0L) {
            int bit = Long.numberOfTrailingZeros(tmp);
            syndrome7 ^= DATA_BIT_TO_HAMMING_POS[bit];
            tmp &= (tmp - 1L);
        }
        int overallParity = (Long.bitCount(data64) ^ Integer.bitCount(syndrome7)) & 1;
        return (byte) (syndrome7 | (overallParity << 7));
    }

    /**
     * Verifies and heals any single-bit flip in {@code data64} or {@code syndrome8} in {@code O(1)} time.
     * Returns {@link #UNCORRECTABLE_DED_SENTINEL} if a double-bit error is detected.
     */
    public static long healSingleBitFlip64(long data64, byte syndrome8) {
        int expected7 = 0;
        long tmp = data64;
        while (tmp != 0L) {
            int bit = Long.numberOfTrailingZeros(tmp);
            expected7 ^= DATA_BIT_TO_HAMMING_POS[bit];
            tmp &= (tmp - 1L);
        }
        int stored = syndrome8 & 0xFF;
        int s = expected7 ^ (stored & 0x7F);
        int overallParityError = (Long.bitCount(data64) ^ Integer.bitCount(stored)) & 1;

        if (s == 0 && overallParityError == 0) {
            return data64; // Error-free
        }
        if (overallParityError == 1) {
            if (s == 0 || (s & (s - 1)) == 0) {
                // Single-bit flip occurred inside the syndrome byte itself; data64 is intact
                return data64;
            }
            int bitToFlip = s < HAMMING_POS_TO_DATA_BIT.length ? HAMMING_POS_TO_DATA_BIT[s] : -1;
            if (bitToFlip >= 0) {
                return data64 ^ (1L << bitToFlip);
            }
            return UNCORRECTABLE_DED_SENTINEL;
        }
        // s != 0 && overallParityError == 0 -> Double-Bit Error Detected (DED)
        return UNCORRECTABLE_DED_SENTINEL;
    }

    /**
     * Fast 64-bit Mix64 content digest over a cube's RGBA samples for {@code Case A} temporal clean-acks.
     */
    public static long computeCubeDigest64(int[] cubeRgba, int nSamples) {
        long h = 0x9E3779B97F4A7C15L ^ nSamples;
        for (int i = 0; i < nSamples; i++) {
            long k = (cubeRgba[i] & 0xFFFFFFFFL) + 0xBF58476D1CE4E5B9L;
            k = (k ^ (k >>> 30)) * 0xBF58476D1CE4E5B9L;
            h ^= k;
            h = Long.rotateLeft(h, 27) * 0x94D049BB133111EBL + 0x517CC1B727220A95L;
        }
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return h;
    }

    /**
     * Writes an unsigned LEB128 {@code VarInt} into {@code dst[pos..]} and returns the new position.
     */
    public static int writeVarInt(byte[] dst, int pos, int value) {
        int v = value;
        while ((v & ~0x7F) != 0) {
            dst[pos++] = (byte) ((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        dst[pos++] = (byte) v;
        return pos;
    }

    /**
     * Reads an unsigned LEB128 {@code VarInt} from {@code src[pos[0]..limit)} with {@code CBIT} bounds checks.
     */
    public static int readVarInt(byte[] src, int[] posRef, int limit) throws LcvCorruptTileException {
        int pos = posRef[0];
        int result = 0;
        int shift = 0;
        while (true) {
            if (pos >= limit) {
                throw new LcvCorruptTileException("CBIT: truncated VarInt at offset " + pos + " (limit " + limit + ")");
            }
            int b = src[pos++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                posRef[0] = pos;
                return result;
            }
            shift += 7;
            if (shift >= 28) {
                throw new LcvCorruptTileException("CBIT: malformed VarInt overflow (> 28 bits)");
            }
        }
    }

    /**
     * Thread-local scratch memory arena eliminating per-cube heap allocations on hot encode/decode paths.
     */
    public static final class ScratchArena {
        public final int[] cubeRgbaTyx = new int[MAX_CUBE_SAMPLES];
        public final int[] curveRgba = new int[MAX_CUBE_SAMPLES];
        public final int[] paletteColors = new int[16];
        public final int[] paletteCounts = new int[16];
        public final int[] samplePaletteIdx = new int[MAX_CUBE_SAMPLES];

        public final byte[][] channelValues = new byte[4][MAX_CUBE_SAMPLES];
        public final byte[][] rawChannelValues = new byte[4][MAX_CUBE_SAMPLES];
        public final byte[][] grayValues = new byte[4][MAX_CUBE_SAMPLES];
        public final byte[][] msbReconstructed = new byte[4][MAX_CUBE_SAMPLES];
        public final byte[][] lsbShiftBase = new byte[4][MAX_CUBE_SAMPLES];
        public final byte[][] lsbWidth = new byte[4][MAX_CUBE_SAMPLES];
        public final byte[] activeBitPlane = new byte[MAX_CUBE_SAMPLES];
        public final int[][] planarParams = new int[4][7]; // {c0_q8, sx_q8, sy_q8, st_q8, maxUMsb8, minErr8, maxErr8}
        public final int[] haarStep = new int[4];
        public final byte[][] haarNibbles = new byte[4][16];
        public final int[] bMin256 = new int[256];
        public final int[] bMax256 = new int[256];
        public final int[] bCount256 = new int[256];
        public final int[] prefMin256 = new int[256];
        public final int[] prefMax256 = new int[256];

        public final int[] rawGaps = new int[MAX_CUBE_SAMPLES];
        public final int[] runLens = new int[MAX_CUBE_SAMPLES];

        public final byte[] candidateUiBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] candidateGrayRawBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] candidateGrayDeltaBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] candidatePlanarMvBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] candidateWaveletHaarBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] planeScratchBuf = new byte[MAX_CUBE_SAMPLES + 64];
        public final byte[] tripletScratchBuf = new byte[MAX_CUBE_SAMPLES + 64];
        public final byte[] roaringSubScratchA = new byte[MAX_CUBE_SAMPLES + 32];
        public final byte[] roaringSubScratchB = new byte[MAX_CUBE_SAMPLES + 32];
        public final byte[] tileOutScratch = new byte[MAX_CUBE_SAMPLES * 4 + 256];
        public final int[] posCursor = new int[1];
    }
}
