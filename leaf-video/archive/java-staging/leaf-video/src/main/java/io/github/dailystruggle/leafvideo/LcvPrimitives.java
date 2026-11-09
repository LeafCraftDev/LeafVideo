package io.github.dailystruggle.leafvideo;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;

/**
 * Self-contained, zero-dependency primitives for {@code LCV-ICD-1.0}: 3D and column-Hilbert
 * space-filling curve LUTs, 1-pass directional anisotropy curve selection, Extended Hamming
 * {@code (72, 64)} {@code SEC-DED} self-healing, reversible {@code RGBA <-> YCoCg-R} lifting,
 * thread-local {@link ScratchArena} buffers, and pluggable C11/SIMD/GPU {@link HardwareAccelerator}
 * offload ({@code leaf-video-ADR-004}).
 *
 * @governedBy leaf-video-ADR-001
 * @governedBy leaf-video-ADR-003
 * @governedBy leaf-video-ADR-004
 * @trace REQ-LCV-F-001
 * @trace REQ-LCV-F-004
 * @trace REQ-LCV-HW-001
 * @trace REQ-LCV-HW-002
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

    private static final byte[][] S4_FWD = new byte[24][4];
    private static final byte[][] S4_INV = new byte[24][4];

    private static final ThreadLocal<ScratchArena> THREAD_ARENA =
        ThreadLocal.withInitial(ScratchArena::new);

    private static final boolean NATIVE_LIB_LOADED;
    private static volatile HardwareAccelerator HARDWARE_ACCELERATOR;

    /**
     * Pluggable hardware/SIMD offload SPI ({@code leaf-video-ADR-004}: {@code REQ-LCV-HW-001},
     * {@code REQ-LCV-HW-002}) for {@code 16 x 16 x K} tile extraction, structural fingerprinting,
     * hardware optical-flow / SIMD velocity estimation, and 2-level 2D Haar wavelet conditioning.
     */
    public interface HardwareAccelerator {
        String backendName();

        default CubeFingerprint extractAndFingerprintCubeTyx(
            int[] rgbaFrames, int tileX, int tileY, int frameWidth, int frameHeight, int depthK, ScratchArena arena
        ) {
            return extractAndFingerprintCubeTyxJava(rgbaFrames, tileX, tileY, frameWidth, frameHeight, depthK, arena);
        }

        default int selectOptimalCurve1Pass(int[] cubeRgbaTyx, int depthK) {
            return selectOptimalCurve1PassJava(cubeRgbaTyx, depthK);
        }

        default int estimateTileVelocity1Pass(int[] cubeRgbaTyx, int depthK) {
            return estimateTileVelocity1PassJava(cubeRgbaTyx, depthK);
        }

        default void haar2LevelSubbandConditionCube(
            int[] cubeRgbaTyx, int depthK, int medSubbandTol, int hiSubbandTol
        ) {
            haar2LevelSubbandConditionCubeJava(cubeRgbaTyx, depthK, medSubbandTol, hiSubbandTol);
        }

        default boolean isGpuAvailable() {
            return false;
        }

        default int batchAnalyzeSuperblock(
            int[] rgbaFrames, int width, int height, int depthK, int[] outTileMeta
        ) {
            return 0;
        }

        default int computeMed2dAndPackPlanes(
            int[] cubeRgbaTyx, int depthK, int packedMv8, int lsbSplitBits,
            int activeMask4, int packedConsts32, boolean useDirectRgba, boolean alphaMasked,
            boolean reuseSteps, boolean writeGrayBytes,
            int[] packedSteps, long[] outPlaneWordsFlat, byte[] outGrayFlat
        ) {
            return -1;
        }

        default long fitPlanar3DChannel(
            byte[] srcCh, short[] curveToTyx, int nSamples, int depthK,
            boolean packBitplaneWords, boolean writeResidualsU,
            int[] outParams, long[] outPlaneWords, byte[] outResidualsU
        ) {
            return -1L;
        }

        default int encodeSuperblockGpuDirect(
            int[] rgbaFrames, int width, int height, int depthK,
            long frameIndex64, long prevSbOffset64, int roiTileX, int roiTileY,
            int tuningProfile16, int lsbSplitBits, int maxCleanAckRefresh,
            boolean resetTemporalState, byte[] outSuperblock
        ) {
            return -1;
        }

        default int benchmarkGpuResidentEncode(
            int[] rgbaFrames, int width, int height, int depthK,
            int lsbSplitBits, int iterations, boolean uploadFrames
        ) {
            return -1;
        }

        default int decodeSuperblockNative(
            byte[] sbBytes, int sbOffset, int sbLen,
            int[] prevFrames, boolean hasPrevFrames,
            int[] outRgbaFrames, int[] outStats4
        ) {
            return -1;
        }

        default int encodeSuperblockNative(
            int[] rgbaFrames, int width, int height, int depthK,
            long frameIndex64, long prevSbOffset64, int roiTileX, int roiTileY,
            int tuningProfile16, int lsbSplitBits, int maxCleanAckRefresh,
            int[] prevFrames, boolean hasPrevFrames, byte[] outSuperblock
        ) {
            return -1;
        }

        default int decodeLcvuPacketsNative(
            byte[] sbHeader64, int totalPackets,
            byte[] concatPackets, int[] packetOffsets, int[] packetLengths,
            int[] prevFrames, boolean hasPrevFrames,
            int[] outRgbaFrames, int[] outStats4
        ) {
            return -1;
        }

        default int cropOrStripSuperblockNative(
            byte[] sbBytes, int sbOffset, int sbLen,
            int tileX0, int tileY0, int cropTilesX, int cropTilesY,
            boolean stripLsb, byte[] outSbBytes
        ) {
            return -1;
        }
    }

    private static final HardwareAccelerator JAVA_FALLBACK_ACCELERATOR = new HardwareAccelerator() {
        @Override
        public String backendName() {
            return "java17-swar";
        }
    };

    private static final HardwareAccelerator NATIVE_SIMD_ACCELERATOR = new HardwareAccelerator() {
        private volatile String cachedBackendName;

        @Override
        public String backendName() {
            String name = cachedBackendName;
            if (name == null) {
                try {
                    name = nativeGetAcceleratorBackendName();
                } catch (UnsatisfiedLinkError ignored) {
                    name = "c11-simd-jni";
                }
                if (name == null || name.isBlank()) {
                    name = "c11-simd-jni";
                }
                cachedBackendName = name;
            }
            return name;
        }

        @Override
        public boolean isGpuAvailable() {
            try {
                return nativeIsCudaAvailable();
            } catch (UnsatisfiedLinkError ignored) {
                return false;
            }
        }

        @Override
        public int batchAnalyzeSuperblock(
            int[] rgbaFrames, int width, int height, int depthK, int[] outTileMeta
        ) {
            return nativeBatchAnalyzeSuperblock(rgbaFrames, width, height, depthK, outTileMeta);
        }

        @Override
        public int computeMed2dAndPackPlanes(
            int[] cubeRgbaTyx, int depthK, int packedMv8, int lsbSplitBits,
            int activeMask4, int packedConsts32, boolean useDirectRgba, boolean alphaMasked,
            boolean reuseSteps, boolean writeGrayBytes,
            int[] packedSteps, long[] outPlaneWordsFlat, byte[] outGrayFlat
        ) {
            return nativeComputeMed2dAndPackPlanes(
                cubeRgbaTyx, depthK, packedMv8, lsbSplitBits,
                activeMask4, packedConsts32, useDirectRgba, alphaMasked,
                reuseSteps, writeGrayBytes,
                packedSteps, outPlaneWordsFlat, outGrayFlat
            );
        }

        @Override
        public long fitPlanar3DChannel(
            byte[] srcCh, short[] curveToTyx, int nSamples, int depthK,
            boolean packBitplaneWords, boolean writeResidualsU,
            int[] outParams, long[] outPlaneWords, byte[] outResidualsU
        ) {
            return nativeFitPlanar3DChannel(
                srcCh, curveToTyx, nSamples, depthK,
                packBitplaneWords, writeResidualsU,
                outParams, outPlaneWords, outResidualsU
            );
        }

        @Override
        public int encodeSuperblockGpuDirect(
            int[] rgbaFrames, int width, int height, int depthK,
            long frameIndex64, long prevSbOffset64, int roiTileX, int roiTileY,
            int tuningProfile16, int lsbSplitBits, int maxCleanAckRefresh,
            boolean resetTemporalState, byte[] outSuperblock
        ) {
            return nativeEncodeSuperblockGpuDirect(
                rgbaFrames, width, height, depthK,
                frameIndex64, prevSbOffset64, roiTileX, roiTileY,
                tuningProfile16, lsbSplitBits, maxCleanAckRefresh,
                resetTemporalState, outSuperblock
            );
        }

        @Override
        public int benchmarkGpuResidentEncode(
            int[] rgbaFrames, int width, int height, int depthK,
            int lsbSplitBits, int iterations, boolean uploadFrames
        ) {
            return nativeBenchmarkGpuResidentEncode(
                rgbaFrames, width, height, depthK,
                lsbSplitBits, iterations, uploadFrames
            );
        }

        @Override
        public int decodeSuperblockNative(
            byte[] sbBytes, int sbOffset, int sbLen,
            int[] prevFrames, boolean hasPrevFrames,
            int[] outRgbaFrames, int[] outStats4
        ) {
            return nativeDecodeSuperblockRgba(
                sbBytes, sbOffset, sbLen, prevFrames, hasPrevFrames, outRgbaFrames, outStats4
            );
        }

        @Override
        public int encodeSuperblockNative(
            int[] rgbaFrames, int width, int height, int depthK,
            long frameIndex64, long prevSbOffset64, int roiTileX, int roiTileY,
            int tuningProfile16, int lsbSplitBits, int maxCleanAckRefresh,
            int[] prevFrames, boolean hasPrevFrames, byte[] outSuperblock
        ) {
            return nativeEncodeSuperblockC11(
                rgbaFrames, width, height, depthK,
                frameIndex64, prevSbOffset64, roiTileX, roiTileY,
                tuningProfile16, lsbSplitBits, maxCleanAckRefresh,
                prevFrames, hasPrevFrames, outSuperblock
            );
        }

        @Override
        public int decodeLcvuPacketsNative(
            byte[] sbHeader64, int totalPackets,
            byte[] concatPackets, int[] packetOffsets, int[] packetLengths,
            int[] prevFrames, boolean hasPrevFrames,
            int[] outRgbaFrames, int[] outStats4
        ) {
            return nativeDecodeLcvuPacketsRgba(
                sbHeader64, totalPackets, concatPackets, packetOffsets, packetLengths,
                prevFrames, hasPrevFrames, outRgbaFrames, outStats4
            );
        }

        @Override
        public int cropOrStripSuperblockNative(
            byte[] sbBytes, int sbOffset, int sbLen,
            int tileX0, int tileY0, int cropTilesX, int cropTilesY,
            boolean stripLsb, byte[] outSbBytes
        ) {
            return nativeCropOrStripSuperblock(
                sbBytes, sbOffset, sbLen, tileX0, tileY0, cropTilesX, cropTilesY, stripLsb, outSbBytes
            );
        }
        @Override
        public CubeFingerprint extractAndFingerprintCubeTyx(
            int[] rgbaFrames, int tileX, int tileY, int frameWidth, int frameHeight, int depthK, ScratchArena arena
        ) {
            if (arena == null) {
                arena = threadArena();
            }
            int preCurve = arena.precomputedCurveId;
            if (extractTileToCubeOrDetectUniform(rgbaFrames, tileX, tileY, frameWidth, frameHeight, depthK, arena)) {
                arena.knownNonUniformFromGpu = false;
                arena.precomputedCurveId = -1;
                return arena.fingerprint;
            }
            arena.knownNonUniformFromGpu = false;
            arena.precomputedCurveId = -1;
            long[] meta = arena.nativeMetaOut;
            int packedDepthAndCurve = (depthK & 0xFF)
                | ((preCurve >= 0 && preCurve <= 3) ? ((preCurve + 1) << 8) : 0);
            int status = nativeFingerprintExtractedCubeTyx(
                arena.cubeRgbaTyx, packedDepthAndCurve, arena.fingerprint.quickPalette, meta
            );
            if (status < 0) {
                return fingerprintExtractedCubeTyx(arena.cubeRgbaTyx, depthK, false, arena);
            }
            CubeFingerprint fp = arena.fingerprint;
            int firstRgba = (int) meta[1];
            if (status == 1 || meta[0] != 0L) {
                fp.resetUniform(firstRgba);
                return fp;
            }
            int packedInfo = (int) meta[3];
            int flagsMask = (packedInfo >>> 24) & 0xFF;
            int packedOpGb = (int) meta[6];
            fp.valid = true;
            fp.uniform = false;
            fp.firstRgba = firstRgba;
            fp.digest64 = meta[2];
            fp.fingerprint8 = packedInfo & 0xFF;
            fp.optimalCurveId = (packedInfo >>> 8) & 0xFF;
            fp.paletteSize = (packedInfo >>> 16) & 0xFF;
            fp.hasZeroAlpha = (flagsMask & 1) != 0;
            fp.hasOpaqueAlpha = (flagsMask & 2) != 0;
            fp.allZeroAlphaAreZeroRgba = (flagsMask & 4) != 0;
            fp.allOpaqueAre255AndConstGB = (flagsMask & 8) != 0;
            fp.firstOpaqueRgba = (int) meta[4];
            fp.firstOpaqueTyx = (int) meta[5];
            fp.opG = packedOpGb & 0xFFFF;
            fp.opB = (packedOpGb >>> 16) & 0xFFFF;
            return fp;
        }

        @Override
        public int estimateTileVelocity1Pass(int[] cubeRgbaTyx, int depthK) {
            if (depthK <= 1) {
                return 0x88;
            }
            if (computeTrajectorySad(cubeRgbaTyx, depthK, 0, 0, Long.MAX_VALUE) == 0L) {
                return 0x88;
            }
            return nativeEstimateTileVelocity1Pass(cubeRgbaTyx, depthK);
        }

        @Override
        public void haar2LevelSubbandConditionCube(
            int[] cubeRgbaTyx, int depthK, int medSubbandTol, int hiSubbandTol
        ) {
            nativeHaar2LevelSubbandConditionCube(cubeRgbaTyx, depthK, medSubbandTol, hiSubbandTol);
        }
    };

    static {
        initHilbert2D();
        initCurvesForDepth(1, CURVE_TO_TYX_K1, TYX_TO_CURVE_K1);
        initCurvesForDepth(2, CURVE_TO_TYX_K2, TYX_TO_CURVE_K2);
        initCurvesForDepth(4, CURVE_TO_TYX_K4, TYX_TO_CURVE_K4);
        initCurvesForDepth(8, CURVE_TO_TYX_K8, TYX_TO_CURVE_K8);
        initCurvesForDepth(16, CURVE_TO_TYX_K16, TYX_TO_CURVE_K16);
        initHammingTables();
        initS4Tables();
        NATIVE_LIB_LOADED = loadNativeAcceleratorLibrary();
        HARDWARE_ACCELERATOR = NATIVE_LIB_LOADED ? NATIVE_SIMD_ACCELERATOR : JAVA_FALLBACK_ACCELERATOR;
    }

    private LcvPrimitives() {}

    public static boolean isNativeLibraryLoaded() {
        return NATIVE_LIB_LOADED;
    }

    public static boolean isHardwareAccelerated() {
        return HARDWARE_ACCELERATOR != JAVA_FALLBACK_ACCELERATOR;
    }

    public static HardwareAccelerator getHardwareAccelerator() {
        return HARDWARE_ACCELERATOR;
    }

    public static void setHardwareAccelerator(HardwareAccelerator accelerator) {
        HARDWARE_ACCELERATOR = (accelerator != null)
            ? accelerator
            : (NATIVE_LIB_LOADED ? NATIVE_SIMD_ACCELERATOR : JAVA_FALLBACK_ACCELERATOR);
    }

    private static boolean loadNativeAcceleratorLibrary() {
        if (Boolean.getBoolean("lcv.native.disable")) {
            return false;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        String libFileName = os.contains("win") ? "lcv_accel.dll"
            : (os.contains("mac") ? "liblcv_accel.dylib" : "liblcv_accel.so");

        String explicitPath = System.getProperty("lcv.native.lib");
        if (explicitPath != null && !explicitPath.isBlank()) {
            Path p = Path.of(explicitPath).toAbsolutePath();
            if (Files.isRegularFile(p)) {
                try {
                    System.load(p.toString());
                    return true;
                } catch (UnsatisfiedLinkError ignored) {
                    // Fall through to other discovery paths
                }
            }
        }

        for (Path candidate : new Path[]{
            Path.of("build", "native", libFileName),
            Path.of("resource", "LeafVideo", "build", "native", libFileName),
            Path.of("..", "resource", "LeafVideo", "build", "native", libFileName)
        }) {
            Path abs = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(abs)) {
                try {
                    System.load(abs.toString());
                    return true;
                } catch (UnsatisfiedLinkError ignored) {
                    // Continue searching
                }
            }
        }

        try {
            System.loadLibrary("lcv_accel");
            return true;
        } catch (UnsatisfiedLinkError ignored) {
            // Try embedded classpath resource next
        }

        try (InputStream in = LcvPrimitives.class.getResourceAsStream("/META-INF/native/" + libFileName)) {
            if (in != null) {
                int dot = libFileName.lastIndexOf('.');
                String suffix = dot >= 0 ? libFileName.substring(dot) : ".so";
                Path tempLib = Files.createTempFile("liblcv_accel_", suffix);
                tempLib.toFile().deleteOnExit();
                Files.copy(in, tempLib, StandardCopyOption.REPLACE_EXISTING);
                System.load(tempLib.toAbsolutePath().toString());
                return true;
            }
        } catch (Throwable ignored) {
            // Fall back cleanly to pure-Java SWAR implementation
        }
        return false;
    }

    private static native int nativeSelectOptimalCurve1Pass(int[] cubeRgbaTyx, int depthK);

    private static native int nativeEstimateTileVelocity1Pass(int[] cubeRgbaTyx, int depthK);

    private static native void nativeHaar2LevelSubbandConditionCube(
        int[] cubeRgbaTyx, int depthK, int medSubbandTol, int hiSubbandTol
    );
    private static native int nativeFingerprintExtractedCubeTyx(
        int[] cubeRgbaTyx, int depthK, int[] quickPalette, long[] metaOut
    );

    private static native boolean nativeIsCudaAvailable();

    private static native String nativeGetAcceleratorBackendName();

    private static native int nativeBatchAnalyzeSuperblock(
        int[] rgbaFrames, int width, int height, int depthK, int[] outTileMeta
    );

    private static native int nativeComputeMed2dAndPackPlanes(
        int[] cubeRgbaTyx, int depthK, int packedMv8, int lsbSplitBits,
        int activeMask4, int packedConsts32, boolean useDirectRgba, boolean alphaMasked,
        boolean reuseSteps, boolean writeGrayBytes,
        int[] packedSteps, long[] outPlaneWordsFlat, byte[] outGrayFlat
    );

    private static native long nativeFitPlanar3DChannel(
        byte[] srcCh, short[] curveToTyx, int nSamples, int depthK,
        boolean packBitplaneWords, boolean writeResidualsU,
        int[] outParams, long[] outPlaneWords, byte[] outResidualsU
    );

    private static native int nativeEncodeSuperblockGpuDirect(
        int[] rgbaFrames, int width, int height, int depthK,
        long frameIndex64, long prevSbOffset64, int roiTileX, int roiTileY,
        int tuningProfile16, int lsbSplitBits, int maxCleanAckRefresh,
        boolean resetTemporalState, byte[] outSuperblock
    );

    private static native int nativeBenchmarkGpuResidentEncode(
        int[] rgbaFrames, int width, int height, int depthK,
        int lsbSplitBits, int iterations, boolean uploadFrames
    );

    private static native int nativeDecodeSuperblockRgba(
        byte[] sbBytes, int sbOffset, int sbLen,
        int[] prevFrames, boolean hasPrevFrames,
        int[] outRgbaFrames, int[] outStats4
    );

    private static native int nativeEncodeSuperblockC11(
        int[] rgbaFrames, int width, int height, int depthK,
        long frameIndex64, long prevSbOffset64, int roiTileX, int roiTileY,
        int tuningProfile16, int lsbSplitBits, int maxCleanAckRefresh,
        int[] prevFrames, boolean hasPrevFrames, byte[] outSuperblock
    );

    private static native int nativeDecodeLcvuPacketsRgba(
        byte[] sbHeader64, int totalPackets,
        byte[] concatPackets, int[] packetOffsets, int[] packetLengths,
        int[] prevFrames, boolean hasPrevFrames,
        int[] outRgbaFrames, int[] outStats4
    );

    private static native int nativeCropOrStripSuperblock(
        byte[] sbBytes, int sbOffset, int sbLen,
        int tileX0, int tileY0, int cropTilesX, int cropTilesY,
        boolean stripLsb, byte[] outSbBytes
    );

    public static int decodeSuperblockNative(
        byte[] sbBytes, int sbOffset, int sbLen,
        int[] prevFrames, boolean hasPrevFrames,
        int[] outRgbaFrames, int[] outStats4
    ) {
        return HARDWARE_ACCELERATOR.decodeSuperblockNative(
            sbBytes, sbOffset, sbLen, prevFrames, hasPrevFrames, outRgbaFrames, outStats4
        );
    }

    public static int encodeSuperblockNative(
        int[] rgbaFrames, int width, int height, int depthK,
        long frameIndex64, long prevSbOffset64, int roiTileX, int roiTileY,
        int tuningProfile16, int lsbSplitBits, int maxCleanAckRefresh,
        int[] prevFrames, boolean hasPrevFrames, byte[] outSuperblock
    ) {
        return HARDWARE_ACCELERATOR.encodeSuperblockNative(
            rgbaFrames, width, height, depthK,
            frameIndex64, prevSbOffset64, roiTileX, roiTileY,
            tuningProfile16, lsbSplitBits, maxCleanAckRefresh,
            prevFrames, hasPrevFrames, outSuperblock
        );
    }

    public static int decodeLcvuPacketsNative(
        byte[] sbHeader64, int totalPackets,
        byte[] concatPackets, int[] packetOffsets, int[] packetLengths,
        int[] prevFrames, boolean hasPrevFrames,
        int[] outRgbaFrames, int[] outStats4
    ) {
        return HARDWARE_ACCELERATOR.decodeLcvuPacketsNative(
            sbHeader64, totalPackets, concatPackets, packetOffsets, packetLengths,
            prevFrames, hasPrevFrames, outRgbaFrames, outStats4
        );
    }

    public static int cropOrStripSuperblockNative(
        byte[] sbBytes, int sbOffset, int sbLen,
        int tileX0, int tileY0, int cropTilesX, int cropTilesY,
        boolean stripLsb, byte[] outSbBytes
    ) {
        return HARDWARE_ACCELERATOR.cropOrStripSuperblockNative(
            sbBytes, sbOffset, sbLen, tileX0, tileY0, cropTilesX, cropTilesY, stripLsb, outSbBytes
        );
    }

    public static boolean isCudaGpuAvailable() {
        return HARDWARE_ACCELERATOR.isGpuAvailable();
    }

    public static int batchAnalyzeSuperblock(
        int[] rgbaFrames, int width, int height, int depthK, int[] outTileMeta
    ) {
        return HARDWARE_ACCELERATOR.batchAnalyzeSuperblock(rgbaFrames, width, height, depthK, outTileMeta);
    }

    public static int encodeSuperblockGpuDirect(
        int[] rgbaFrames, int width, int height, int depthK,
        long frameIndex64, long prevSbOffset64, int roiTileX, int roiTileY,
        int tuningProfile16, int lsbSplitBits, int maxCleanAckRefresh,
        boolean resetTemporalState, byte[] outSuperblock
    ) {
        return HARDWARE_ACCELERATOR.encodeSuperblockGpuDirect(
            rgbaFrames, width, height, depthK,
            frameIndex64, prevSbOffset64, roiTileX, roiTileY,
            tuningProfile16, lsbSplitBits, maxCleanAckRefresh,
            resetTemporalState, outSuperblock
        );
    }

    public static int benchmarkGpuResidentEncode(
        int[] rgbaFrames, int width, int height, int depthK,
        int lsbSplitBits, int iterations, boolean uploadFrames
    ) {
        return HARDWARE_ACCELERATOR.benchmarkGpuResidentEncode(
            rgbaFrames, width, height, depthK,
            lsbSplitBits, iterations, uploadFrames
        );
    }

    public static int computeMed2dAndPackPlanes(
        int[] cubeRgbaTyx, int depthK, int packedMv8, int lsbSplitBits,
        int activeMask4, int packedConsts32, boolean useDirectRgba, boolean alphaMasked,
        boolean reuseSteps, boolean writeGrayBytes,
        int[] packedSteps, long[] outPlaneWordsFlat, byte[] outGrayFlat
    ) {
        return HARDWARE_ACCELERATOR.computeMed2dAndPackPlanes(
            cubeRgbaTyx, depthK, packedMv8, lsbSplitBits,
            activeMask4, packedConsts32, useDirectRgba, alphaMasked,
            reuseSteps, writeGrayBytes,
            packedSteps, outPlaneWordsFlat, outGrayFlat
        );
    }

    public static long fitPlanar3DChannel(
        byte[] srcCh, short[] curveToTyx, int nSamples, int depthK,
        boolean packBitplaneWords, boolean writeResidualsU,
        int[] outParams, long[] outPlaneWords, byte[] outResidualsU
    ) {
        return HARDWARE_ACCELERATOR.fitPlanar3DChannel(
            srcCh, curveToTyx, nSamples, depthK,
            packBitplaneWords, writeResidualsU,
            outParams, outPlaneWords, outResidualsU
        );
    }

    /**
     * Transposes {@code grayCh[0..nSamples-1]} into eight packed 64-bit bitplane word arrays
     * {@code dstPlanes8[0..7][0..(nSamples >>> 6)-1]} in a single pass using a 3-stage SWAR
     * {@code 8x8} bit-matrix butterfly ({@code leaf-video-ADR-004} {@code REQ-LCV-HW-001}).
     */
    public static void transposeByteChannelToBitplanes8(
        byte[] grayCh, int nSamples, long[][] dstPlanes8
    ) {
        int nWords = nSamples >>> 6;
        long[] p0 = dstPlanes8[0];
        long[] p1 = dstPlanes8[1];
        long[] p2 = dstPlanes8[2];
        long[] p3 = dstPlanes8[3];
        long[] p4 = dstPlanes8[4];
        long[] p5 = dstPlanes8[5];
        long[] p6 = dstPlanes8[6];
        long[] p7 = dstPlanes8[7];
        for (int w = 0; w < nWords; w++) {
            int base64 = w << 6;
            long w0 = 0L;
            long w1 = 0L;
            long w2 = 0L;
            long w3 = 0L;
            long w4 = 0L;
            long w5 = 0L;
            long w6 = 0L;
            long w7 = 0L;
            for (int sub = 0; sub < 8; sub++) {
                int idx = base64 + (sub << 3);
                long x = (grayCh[idx] & 0xFFL)
                    | ((grayCh[idx + 1] & 0xFFL) << 8)
                    | ((grayCh[idx + 2] & 0xFFL) << 16)
                    | ((grayCh[idx + 3] & 0xFFL) << 24)
                    | ((grayCh[idx + 4] & 0xFFL) << 32)
                    | ((grayCh[idx + 5] & 0xFFL) << 40)
                    | ((grayCh[idx + 6] & 0xFFL) << 48)
                    | ((grayCh[idx + 7] & 0xFFL) << 56);
                long t = (x ^ (x >>> 7)) & 0x00AA00AA00AA00AAL;
                x = x ^ t ^ (t << 7);
                t = (x ^ (x >>> 14)) & 0x0000CCCC0000CCCCL;
                x = x ^ t ^ (t << 14);
                t = (x ^ (x >>> 28)) & 0x00000000F0F0F0F0L;
                x = x ^ t ^ (t << 28);
                int sh = sub << 3;
                w0 |= (x & 0xFFL) << sh;
                w1 |= ((x >>> 8) & 0xFFL) << sh;
                w2 |= ((x >>> 16) & 0xFFL) << sh;
                w3 |= ((x >>> 24) & 0xFFL) << sh;
                w4 |= ((x >>> 32) & 0xFFL) << sh;
                w5 |= ((x >>> 40) & 0xFFL) << sh;
                w6 |= ((x >>> 48) & 0xFFL) << sh;
                w7 |= ((x >>> 56) & 0xFFL) << sh;
            }
            p0[w] = w0;
            p1[w] = w1;
            p2[w] = w2;
            p3[w] = w3;
            p4[w] = w4;
            p5[w] = w5;
            p6[w] = w6;
            p7[w] = w7;
        }
    }

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
     * Resolves the {@code curve -> TYX} LUT for {@code (depthK, curveId)}, applying an
     * {@code L_1 = 1}-preserving dihedral + quadtree-reflection isometry when {@code curveKey16 != 0}.
     */
    public static short[] resolveCurveToTyxLut(int depthK, int curveId, int curveKey16, ScratchArena arena) {
        short[] baseLut = curveToTyxLut(depthK, curveId);
        int key = curveKey16 & 0xFFFF;
        if (key == 0) {
            return baseLut;
        }
        if (arena == null) {
            arena = threadArena();
        }
        int nSamples = TILE_PIXELS * depthK;
        short[] keyedLut = arena.keyedCurveToTyx;
        long mixed = mix64(key);
        int nonZeroDihedral = (int) (Long.remainderUnsigned(mixed, 7L) + 1L); // 1..7 (never identity 0)
        boolean flipX = (nonZeroDihedral & 1) != 0;
        boolean flipY = (nonZeroDihedral & 2) != 0;
        boolean swapXY = (nonZeroDihedral & 4) != 0;
        boolean flipT = (depthK > 1) && (((mixed >>> 8) & 1L) != 0L);
        boolean reverseOrder = ((mixed >>> 9) & 1L) != 0L;
        for (int i = 0; i < nSamples; i++) {
            int srcIdx = reverseOrder ? (nSamples - 1 - i) : i;
            int tyx = baseLut[srcIdx] & 0xFFFF;
            int lx = tyx & 15;
            int ly = (tyx >>> 4) & 15;
            int lt = tyx >>> 8;
            if (flipX) {
                lx = 15 - lx;
            }
            if (flipY) {
                ly = 15 - ly;
            }
            if (swapXY) {
                int tmp = lx;
                lx = ly;
                ly = tmp;
            }
            if (flipT) {
                lt = (depthK - 1) - lt;
            }
            int keyedTyx = (lt << 8) | (ly << 4) | lx;
            keyedLut[i] = (short) keyedTyx;
            arena.keyedTyxToCurve[keyedTyx] = (short) i;
        }
        return keyedLut;
    }

    /**
     * 1-pass directional anisotropy curve selector over a {@code 16 x 16 x K} RGBA cube.
     */
    public static int selectOptimalCurve1Pass(int[] cubeRgbaTyx, int depthK) {
        HardwareAccelerator accel = HARDWARE_ACCELERATOR;
        if (accel != JAVA_FALLBACK_ACCELERATOR) {
            return accel.selectOptimalCurve1Pass(cubeRgbaTyx, depthK);
        }
        return selectOptimalCurve1PassJava(cubeRgbaTyx, depthK);
    }

    public static int selectOptimalCurve1PassJava(int[] cubeRgbaTyx, int depthK) {
        long diffX = 0L;
        long diffY = 0L;
        long diffT = 0L;

        for (int t = 0; t < depthK; t++) {
            int tBase = t << 8;
            for (int y = 0; y < TILE_DIM; y += 2) {
                int rowBase = tBase | (y << 4);
                for (int x = 0; x < TILE_DIM; x += 2) {
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
        return selectOptimalCurveFromEnergies(diffX, diffY, diffT, depthK);
    }
    /**
     * Constant-time {@code O(1)} curve dispatch from directional {@code L_1} transition energies.
     */
    public static int selectOptimalCurveFromEnergies(long diffX, long diffY, long diffT, int depthK) {
        if (depthK == 1) {
            if (diffX == 0L || diffX * 10L < diffY * 6L) {
                return CURVE_TYX;
            }
            if (diffY * 10L < diffX * 6L) {
                return CURVE_TXY;
            }
            return CURVE_T_H2;
        }

        long normX = diffX * (depthK - 1);
        long normY = diffY * (depthK - 1);
        long normT = diffT * (TILE_DIM - 1);

        if (normT * 10L <= Math.min(normX, normY) * 8L) {
            return CURVE_T_H2;
        }
        if (normX == 0L || normX * 10L < normY * 6L) {
            return CURVE_TYX;
        }
        if (normY * 10L < normX * 6L) {
            return CURVE_TXY;
        }
        return CURVE_H3;
    }

    /**
     * Single-pass fused tile extractor and structural fingerprinter: copies the {@code 16 x 16 x K}
     * tile at {@code (tileX, tileY)} into {@code arena.cubeRgbaTyx} while simultaneously computing:
     * <ul>
     *   <li>{@code uniform} status and {@code firstRgba};</li>
     *   <li>64-bit Mix64 content digest and 8-bit structural fingerprint;</li>
     *   <li>Directional {@code L_1} transition energies {@code (E_x, E_y, E_t)} and {@code optimalCurveId};</li>
     *   <li>Inline {@code <= 16} unique color palette count and alpha/channel variance flags.</li>
     * </ul>
     */
    public static CubeFingerprint extractAndFingerprintCubeTyx(
        int[] rgbaFrames, int tileX, int tileY, int frameWidth, int frameHeight, int depthK, ScratchArena arena
    ) {
        HardwareAccelerator accel = HARDWARE_ACCELERATOR;
        if (accel != JAVA_FALLBACK_ACCELERATOR) {
            return accel.extractAndFingerprintCubeTyx(rgbaFrames, tileX, tileY, frameWidth, frameHeight, depthK, arena);
        }
        return extractAndFingerprintCubeTyxJava(rgbaFrames, tileX, tileY, frameWidth, frameHeight, depthK, arena);
    }

    public static CubeFingerprint extractAndFingerprintCubeTyxJava(
        int[] rgbaFrames, int tileX, int tileY, int frameWidth, int frameHeight, int depthK, ScratchArena arena
    ) {
        if (arena == null) {
            arena = threadArena();
        }
        if (extractTileToCubeOrDetectUniform(rgbaFrames, tileX, tileY, frameWidth, frameHeight, depthK, arena)) {
            return arena.fingerprint;
        }
        return fingerprintExtractedCubeTyx(arena.cubeRgbaTyx, depthK, false, arena);
    }

    private static boolean extractTileToCubeOrDetectUniform(
        int[] rgbaFrames, int tileX, int tileY, int frameWidth, int frameHeight, int depthK, ScratchArena arena
    ) {
        CubeFingerprint fp = arena.fingerprint;
        int frameArea = frameWidth * frameHeight;
        int baseX = tileX * TILE_DIM;
        int baseY = tileY * TILE_DIM;
        boolean fullInterior = (baseX + TILE_DIM <= frameWidth) && (baseY + TILE_DIM <= frameHeight);

        if (fullInterior) {
            if (!arena.knownNonUniformFromGpu) {
                int first = rgbaFrames[baseY * frameWidth + baseX];
                boolean allSame = true;
                outer:
                for (int t = 0; t < depthK; t++) {
                    int frameBase = t * frameArea + baseY * frameWidth + baseX;
                    for (int ly = 0; ly < TILE_DIM; ly++) {
                        int rowOff = frameBase + ly * frameWidth;
                        for (int lx = 0; lx < TILE_DIM; lx++) {
                            if (rgbaFrames[rowOff + lx] != first) {
                                allSame = false;
                                break outer;
                            }
                        }
                    }
                }
                if (allSame) {
                    arena.cubeRgbaTyx[0] = first;
                    fp.resetUniform(first);
                    return true;
                }
            }
            int dstIdx = 0;
            for (int t = 0; t < depthK; t++) {
                int frameBase = t * frameArea + baseY * frameWidth + baseX;
                for (int ly = 0; ly < TILE_DIM; ly++) {
                    System.arraycopy(rgbaFrames, frameBase + ly * frameWidth, arena.cubeRgbaTyx, dstIdx, TILE_DIM);
                    dstIdx += TILE_DIM;
                }
            }
        } else {
            int maxX = Math.max(0, frameWidth - 1);
            int maxY = Math.max(0, frameHeight - 1);
            int dstIdx = 0;
            for (int t = 0; t < depthK; t++) {
                int frameBase = t * frameArea;
                for (int ly = 0; ly < TILE_DIM; ly++) {
                    int py = Math.min(maxY, baseY + ly);
                    int rowOffset = frameBase + py * frameWidth;
                    for (int lx = 0; lx < TILE_DIM; lx++) {
                        int px = Math.min(maxX, baseX + lx);
                        arena.cubeRgbaTyx[dstIdx++] = rgbaFrames[rowOffset + px];
                    }
                }
            }
        }
        return false;
    }

    /**
     * Single-pass structural fingerprinter over an already-extracted {@code cubeRgbaTyx} buffer.
     */
    public static CubeFingerprint fingerprintExtractedCubeTyx(
        int[] cubeRgbaTyx, int depthK, boolean checkUniform, ScratchArena arena
    ) {
        if (arena == null) {
            arena = threadArena();
        }
        CubeFingerprint fp = arena.fingerprint;
        int nSamples = TILE_PIXELS * depthK;
        int first = cubeRgbaTyx[0];
        if (checkUniform) {
            boolean allSame = true;
            for (int i = 1; i < nSamples; i++) {
                if (cubeRgbaTyx[i] != first) {
                    allSame = false;
                    break;
                }
            }
            if (allSame) {
                fp.resetUniform(first);
                return fp;
            }
        }

        long h = 0x9E3779B97F4A7C15L ^ nSamples;
        boolean hasZeroAlpha = false;
        boolean hasOpaqueAlpha = false;
        boolean allZeroAlphaAreZeroRgba = true;
        boolean allOpaqueAre255AndConstGB = true;
        int firstOpaqueRgba = first;
        int firstOpaqueTyx = -1;
        int opG = 0;
        int opB = 0;

        int[] pal = fp.quickPalette;
        int palSize = 1;
        pal[0] = first;
        int lastColor = first;

        for (int i = 0; i < nSamples; i++) {
            int rgba = cubeRgbaTyx[i];
            long k = (rgba & 0xFFFFFFFFL) + 0xBF58476D1CE4E5B9L;
            k = (k ^ (k >>> 30)) * 0xBF58476D1CE4E5B9L;
            h ^= k;
            h = Long.rotateLeft(h, 27) * 0x94D049BB133111EBL + 0x517CC1B727220A95L;

            if (palSize <= 16 && rgba != lastColor) {
                lastColor = rgba;
                int found = -1;
                for (int p = 0; p < palSize; p++) {
                    if (pal[p] == rgba) {
                        found = p;
                        break;
                    }
                }
                if (found < 0) {
                    if (palSize < 16) {
                        pal[palSize++] = rgba;
                    } else {
                        palSize = 17;
                    }
                }
            }

            int a = rgba & 0xFF;
            if (a == 0) {
                hasZeroAlpha = true;
                if (rgba != 0) {
                    allZeroAlphaAreZeroRgba = false;
                }
            } else {
                int g = (rgba >>> 16) & 0xFF;
                int b = (rgba >>> 8) & 0xFF;
                if (!hasOpaqueAlpha) {
                    hasOpaqueAlpha = true;
                    firstOpaqueRgba = rgba;
                    firstOpaqueTyx = i;
                    opG = g;
                    opB = b;
                    if (a != 0xFF) {
                        allOpaqueAre255AndConstGB = false;
                    }
                } else if (a != 0xFF || g != opG || b != opB) {
                    allOpaqueAre255AndConstGB = false;
                }
            }
        }

        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;

        boolean canAlphaMaskRgb = hasZeroAlpha && hasOpaqueAlpha && allZeroAlphaAreZeroRgba;
        int optimalCurveId = canAlphaMaskRgb
            ? ((depthK > 1) ? CURVE_T_H2 : CURVE_TYX)
            : selectOptimalCurve1PassJava(cubeRgbaTyx, depthK);

        fp.uniform = false;
        fp.firstRgba = first;
        fp.digest64 = h;
        fp.fingerprint8 = (int) ((h ^ (h >>> 32) ^ (h >>> 16)) & 0xFF);
        fp.optimalCurveId = optimalCurveId;
        fp.paletteSize = palSize;
        fp.hasZeroAlpha = hasZeroAlpha;
        fp.hasOpaqueAlpha = hasOpaqueAlpha;
        fp.allZeroAlphaAreZeroRgba = allZeroAlphaAreZeroRgba;
        fp.allOpaqueAre255AndConstGB = allOpaqueAre255AndConstGB;
        fp.firstOpaqueRgba = firstOpaqueRgba;
        fp.firstOpaqueTyx = firstOpaqueTyx;
        fp.opG = opG;
        fp.opB = opB;
        fp.valid = true;
        return fp;
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
        HardwareAccelerator accel = HARDWARE_ACCELERATOR;
        if (accel != JAVA_FALLBACK_ACCELERATOR) {
            return accel.estimateTileVelocity1Pass(cubeRgbaTyx, depthK);
        }
        return estimateTileVelocity1PassJava(cubeRgbaTyx, depthK);
    }

    public static int estimateTileVelocity1PassJava(int[] cubeRgbaTyx, int depthK) {
        if (depthK <= 1) {
            return 0x88;
        }
        int bestVx = 0;
        int bestVy = 0;
        long bestSad = computeTrajectorySad(cubeRgbaTyx, depthK, 0, 0, Long.MAX_VALUE);
        if (bestSad == 0L) {
            return 0x88;
        }

        // Coarse diamond/grid search over [-4, +4]^2 with early-cutoff SAD termination
        int[] candidates = {-4, -2, -1, 0, 1, 2, 4};
        for (int dvx : candidates) {
            for (int dvy : candidates) {
                if (dvx == 0 && dvy == 0) {
                    continue;
                }
                long cutoff = (bestSad * 92L) / 100L;
                long sad = computeTrajectorySad(cubeRgbaTyx, depthK, dvx, dvy, cutoff);
                // Require at least 8% SAD reduction to prefer non-zero MV over stationary (0,0)
                if (sad != Long.MAX_VALUE && sad * 100L < bestSad * 92L) {
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
                    long sad = computeTrajectorySad(cubeRgbaTyx, depthK, vx, vy, bestSad);
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

    /**
     * Branchless 3-instruction SWAR rounded average {@code (a + b + 1) >> 1} across all four 8-bit
     * RGBA channels simultaneously without inter-byte carry overflow ({@code leaf-video-ADR-004} {@code REQ-LCV-HW-001}).
     */
    public static int swarBilinearAvg2(int rgbaA, int rgbaB) {
        return (rgbaA | rgbaB) - (((rgbaA ^ rgbaB) >>> 1) & 0x7F7F7F7F);
    }

    /**
     * Branchless SWAR 2x2 bilinear quarter-pel/half-pel rounded average across four 8-bit RGBA pixels.
     */
    public static int swarBilinearAvg4(int c00, int c10, int c01, int c11) {
        int top = swarBilinearAvg2(c00, c10);
        int bot = swarBilinearAvg2(c01, c11);
        return swarBilinearAvg2(top, bot);
    }

    /**
     * 2-Level 2D Haar wavelet sub-band conditioner for continuous/scaled/foliage {@code 16 x 16 x K}
    /**
     * 2-Level 2D Haar wavelet sub-band conditioner for continuous/scaled/foliage {@code 16 x 16 x K}
     * sections: decomposes each {@code 4x4} block into {@code LL2} (16 DC structural averages),
     * {@code HL2/LH2/HH2} (48 {@code 2x2} medium-frequency sub-bands), and {@code HL1/LH1/HH1}
     * (192 fine pixel-to-pixel high-frequency sub-bands), deadzoning invisible high-frequency
     * chatter while preserving {@code LL2} DC color accuracy and sharp macro-edges.
     */
    public static void haar2LevelSubbandConditionCube(
        int[] cubeRgbaTyx, int depthK, int medSubbandTol, int hiSubbandTol
    ) {
        HardwareAccelerator accel = HARDWARE_ACCELERATOR;
        if (accel != JAVA_FALLBACK_ACCELERATOR) {
            accel.haar2LevelSubbandConditionCube(cubeRgbaTyx, depthK, medSubbandTol, hiSubbandTol);
            return;
        }
        haar2LevelSubbandConditionCubeJava(cubeRgbaTyx, depthK, medSubbandTol, hiSubbandTol);
    }

    public static void haar2LevelSubbandConditionCubeJava(
        int[] cubeRgbaTyx, int depthK, int medSubbandTol, int hiSubbandTol
    ) {
        for (int t = 0; t < depthK; t++) {
            int fBase = t << 8;
            for (int by = 0; by < 4; by++) {
                int y0 = by << 2;
                for (int bx = 0; bx < 4; bx++) {
                    int x0 = bx << 2;
                    int[] qR = new int[4];
                    int[] qG = new int[4];
                    int[] qB = new int[4];
                    int[] qMaxRange = new int[4];
                    int sumBlockR = 0;
                    int sumBlockG = 0;
                    int sumBlockB = 0;

                    // Level 1 (2x2 quads): compute LL1 averages and HL1/LH1/HH1 high-frequency range
                    for (int qy = 0; qy < 2; qy++) {
                        for (int qx = 0; qx < 2; qx++) {
                            int qIdx = (qy << 1) | qx;
                            int py0 = y0 + (qy << 1);
                            int px0 = x0 + (qx << 1);
                            int i00 = fBase | (py0 << 4) | px0;
                            int i10 = i00 + 1;
                            int i01 = i00 + 16;
                            int i11 = i00 + 17;
                            int c00 = cubeRgbaTyx[i00];
                            int c10 = cubeRgbaTyx[i10];
                            int c01 = cubeRgbaTyx[i01];
                            int c11 = cubeRgbaTyx[i11];

                            int r00 = (c00 >>> 24) & 0xFF, r10 = (c10 >>> 24) & 0xFF, r01 = (c01 >>> 24) & 0xFF, r11 = (c11 >>> 24) & 0xFF;
                            int g00 = (c00 >>> 16) & 0xFF, g10 = (c10 >>> 16) & 0xFF, g01 = (c01 >>> 16) & 0xFF, g11 = (c11 >>> 16) & 0xFF;
                            int b00 = (c00 >>> 8) & 0xFF,  b10 = (c10 >>> 8) & 0xFF,  b01 = (c01 >>> 8) & 0xFF,  b11 = (c11 >>> 8) & 0xFF;

                            int avgR = (r00 + r10 + r01 + r11 + 2) >>> 2;
                            int avgG = (g00 + g10 + g01 + g11 + 2) >>> 2;
                            int avgB = (b00 + b10 + b01 + b11 + 2) >>> 2;
                            qR[qIdx] = avgR;
                            qG[qIdx] = avgG;
                            qB[qIdx] = avgB;
                            sumBlockR += avgR;
                            sumBlockG += avgG;
                            sumBlockB += avgB;

                            int maxD = 0;
                            maxD = Math.max(maxD, Math.max(Math.abs(r00 - avgR), Math.max(Math.abs(g00 - avgG), Math.abs(b00 - avgB))));
                            maxD = Math.max(maxD, Math.max(Math.abs(r10 - avgR), Math.max(Math.abs(g10 - avgG), Math.abs(b10 - avgB))));
                            maxD = Math.max(maxD, Math.max(Math.abs(r01 - avgR), Math.max(Math.abs(g01 - avgG), Math.abs(b01 - avgB))));
                            maxD = Math.max(maxD, Math.max(Math.abs(r11 - avgR), Math.max(Math.abs(g11 - avgG), Math.abs(b11 - avgB))));
                            qMaxRange[qIdx] = maxD;
                        }
                    }

                    // Level 2 (4x4 LL2 DC average): deadzone HL2/LH2/HH2 medium sub-bands if within medSubbandTol
                    int ll2R = (sumBlockR + 2) >>> 2;
                    int ll2G = (sumBlockG + 2) >>> 2;
                    int ll2B = (sumBlockB + 2) >>> 2;
                    int maxMedD = 0;
                    for (int q = 0; q < 4; q++) {
                        int d = Math.max(
                            Math.abs(qR[q] - ll2R),
                            Math.max(Math.abs(qG[q] - ll2G), Math.abs(qB[q] - ll2B))
                        );
                        if (d > maxMedD) {
                            maxMedD = d;
                        }
                    }
                    if (maxMedD <= medSubbandTol) {
                        for (int q = 0; q < 4; q++) {
                            qR[q] = ll2R;
                            qG[q] = ll2G;
                            qB[q] = ll2B;
                        }
                    }

                    // Reconstruct 2x2 quads: zero HL1/LH1/HH1 when high-frequency chatter <= hiSubbandTol
                    for (int qy = 0; qy < 2; qy++) {
                        for (int qx = 0; qx < 2; qx++) {
                            int qIdx = (qy << 1) | qx;
                            int py0 = y0 + (qy << 1);
                            int px0 = x0 + (qx << 1);
                            int i00 = fBase | (py0 << 4) | px0;
                            if (qMaxRange[qIdx] <= hiSubbandTol) {
                                int a = cubeRgbaTyx[i00] & 0xFF;
                                int flatRgba = (qR[qIdx] << 24) | (qG[qIdx] << 16) | (qB[qIdx] << 8) | a;
                                cubeRgbaTyx[i00] = flatRgba;
                                cubeRgbaTyx[i00 + 1] = flatRgba;
                                cubeRgbaTyx[i00 + 16] = flatRgba;
                                cubeRgbaTyx[i00 + 17] = flatRgba;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Multiplication-free {@code 4x4} Haar {@code LL} luma box-downsample for frame {@code frameIdx}
     * of a {@code 16 x 16 x K} cube, writing 16 coarse luma bytes ({@code 0..255}) into {@code dst16[0..15]}.
     */
    public static void haar4x4LumaGrid16(int[] cubeRgbaTyx, int frameIdx, byte[] dst16) {
        int fBase = frameIdx << 8;
        for (int cy = 0; cy < 4; cy++) {
            int y0 = cy << 2;
            for (int cx = 0; cx < 4; cx++) {
                int x0 = cx << 2;
                int sumLuma = 0;
                for (int dy = 0; dy < 4; dy++) {
                    int row = fBase | ((y0 + dy) << 4) | x0;
                    for (int dx = 0; dx < 4; dx++) {
                        int c = cubeRgbaTyx[row + dx];
                        int r = (c >>> 24) & 0xFF;
                        int g = (c >>> 16) & 0xFF;
                        int b = (c >>> 8) & 0xFF;
                        sumLuma += (r + (g << 1) + b) >>> 2;
                    }
                }
                dst16[(cy << 2) | cx] = (byte) ((sumLuma + 8) >>> 4);
            }
        }
    }

    /**
     * Computes a deterministic 64-bit structural/perceptual AST block signature over a {@code 16 x 16 x K}
     * cube by packing the 16 {@code 4x4} Haar {@code LL} 4-bit luma nibbles of frame {@code t = 0} and
     * mixing in the directional anisotropy curve and quantized chroma/alpha summary.
     */
    public static long computeStructuralSignature64(int[] cubeRgbaTyx, int depthK) {
        long haar64 = 0L;
        long chromaMix = 0L;
        for (int cy = 0; cy < 4; cy++) {
            int y0 = cy << 2;
            for (int cx = 0; cx < 4; cx++) {
                int x0 = cx << 2;
                int sumY = 0;
                int sumCo = 0;
                int sumCg = 0;
                for (int dy = 0; dy < 4; dy++) {
                    int row = ((y0 + dy) << 4) | x0;
                    for (int dx = 0; dx < 4; dx++) {
                        int c = cubeRgbaTyx[row + dx];
                        int r = (c >>> 24) & 0xFF;
                        int g = (c >>> 16) & 0xFF;
                        int b = (c >>> 8) & 0xFF;
                        sumY += (r + (g << 1) + b) >>> 2;
                        sumCo += (r - b + 256) >>> 1;
                        sumCg += (g - ((r + b) >>> 1) + 256) >>> 1;
                    }
                }
                int cellIdx = (cy << 2) | cx;
                long nibY = ((sumY + 8) >>> 8) & 0x0FL;
                haar64 |= (nibY << (cellIdx << 2));
                long nibC = ((((sumCo + 8) >>> 9) & 0x07L) << 3) | (((sumCg + 8) >>> 9) & 0x07L);
                chromaMix = Long.rotateLeft(chromaMix ^ nibC, 4);
            }
        }
        int curve2 = selectOptimalCurve1Pass(cubeRgbaTyx, Math.max(1, depthK)) & 0x03;
        return haar64 ^ mix64(chromaMix ^ ((long) curve2 << 60) ^ depthK);
    }

    private static long computeTrajectorySad(int[] cubeRgbaTyx, int depthK, int vx, int vy, long cutoffNormSad) {
        long sad = 0L;
        int count = 0;
        int tStepLimit = Math.min(depthK - 1, 4);
        int yStart = Math.max(2, 2 + vy);
        int yEnd = Math.min(14, 16 + vy);
        int xStart = Math.max(2, 2 + vx);
        int xEnd = Math.min(14, 16 + vx);
        int samplesPerStep = ((yEnd > yStart ? (yEnd - yStart + 1) >>> 1 : 0)
            * (xEnd > xStart ? (xEnd - xStart + 1) >>> 1 : 0));
        int totalExpectedCount = samplesPerStep * tStepLimit;
        if (totalExpectedCount <= 0) {
            return Long.MAX_VALUE;
        }
        long rawSadLimit = (cutoffNormSad == Long.MAX_VALUE)
            ? Long.MAX_VALUE
            : ((cutoffNormSad * totalExpectedCount) / 64L + 1L);

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
                if (sad > rawSadLimit) {
                    return Long.MAX_VALUE;
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

    private static void initS4Tables() {
        int idx = 0;
        for (int a = 0; a < 4; a++) {
            for (int b = 0; b < 4; b++) {
                if (b == a) {
                    continue;
                }
                for (int c = 0; c < 4; c++) {
                    if (c == a || c == b) {
                        continue;
                    }
                    int d = 6 - a - b - c;
                    S4_FWD[idx][0] = (byte) a;
                    S4_FWD[idx][1] = (byte) b;
                    S4_FWD[idx][2] = (byte) c;
                    S4_FWD[idx][3] = (byte) d;
                    for (int k = 0; k < 4; k++) {
                        S4_INV[idx][S4_FWD[idx][k]] = (byte) k;
                    }
                    idx++;
                }
            }
        }
    }

    /**
     * SplitMix64 64-bit bijective avalanche mixer ({@code mix64(0) == 0} for open MIT identity).
     */
    public static long mix64(long z) {
        if (z == 0L) {
            return 0L;
        }
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /**
     * Derives the per-tile 64-bit subkey from {@code (baseKey64, frameIndex64, tileIdx)}.
     * Returns {@code 0L} when {@code baseKey64 == 0L}.
     */
    public static long computeTileKey64(long baseKey64, long frameIndex64, int tileIdx) {
        if (baseKey64 == 0L) {
            return 0L;
        }
        long seed = baseKey64
            ^ (frameIndex64 * 0x9E3779B97F4A7C15L)
            ^ ((long) (tileIdx + 1) * 0xBF58476D1CE4E5B9L);
        long k = mix64(seed == 0L ? 0x517CC1B727220A95L : seed);
        return k == 0L ? 1L : k;
    }

    /**
     * Bijectively permutes each 2-bit plane mode selector in {@code logicalModes16} via {@code S4_FWD}
     * derived from {@code (relayTileKey64, channelIdx)}. Identity when {@code relayTileKey64 == 0L}.
     */
    public static int permutePlaneModes16(int logicalModes16, long relayTileKey64, int channelIdx) {
        if (relayTileKey64 == 0L) {
            return logicalModes16 & 0xFFFF;
        }
        long h = mix64(relayTileKey64 ^ ((channelIdx + 1) * 0x94D049BB133111EBL));
        int out = 0;
        for (int p = 0; p < 8; p++) {
            int m2 = (logicalModes16 >>> (2 * p)) & 0x03;
            int permIdx = (int) (Long.remainderUnsigned(h >>> (p * 7), 24L));
            int wired2 = S4_FWD[permIdx][m2] & 0x03;
            out |= (wired2 << (2 * p));
        }
        return out;
    }

    /**
     * Inverse of {@link #permutePlaneModes16} using {@code S4_INV}. Identity when {@code relayTileKey64 == 0L}.
     */
    public static int unpermutePlaneModes16(int wireModes16, long relayTileKey64, int channelIdx) {
        if (relayTileKey64 == 0L) {
            return wireModes16 & 0xFFFF;
        }
        long h = mix64(relayTileKey64 ^ ((channelIdx + 1) * 0x94D049BB133111EBL));
        int out = 0;
        for (int p = 0; p < 8; p++) {
            int wired2 = (wireModes16 >>> (2 * p)) & 0x03;
            int permIdx = (int) (Long.remainderUnsigned(h >>> (p * 7), 24L));
            int logical2 = S4_INV[permIdx][wired2] & 0x03;
            out |= (logical2 << (2 * p));
        }
        return out;
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
     * Reusable single-pass cube structural fingerprint and metadata summary.
     */
    public static final class CubeFingerprint {
        public boolean valid;
        public boolean uniform;
        public int firstRgba;
        public long digest64;
        public int fingerprint8;
        public int optimalCurveId;
        public int paletteSize;
        public boolean hasZeroAlpha;
        public boolean hasOpaqueAlpha;
        public boolean allZeroAlphaAreZeroRgba;
        public boolean allOpaqueAre255AndConstGB;
        public int firstOpaqueRgba;
        public int firstOpaqueTyx;
        public int opG;
        public int opB;
        public final int[] quickPalette = new int[16];

        public void resetUniform(int rgba) {
            this.valid = true;
            this.uniform = true;
            this.firstRgba = rgba;
            this.digest64 = 0L;
            this.fingerprint8 = ((rgba >>> 24) ^ (rgba >>> 16) ^ (rgba >>> 8) ^ rgba) & 0xFF;
            this.optimalCurveId = CURVE_TYX;
            this.paletteSize = 1;
            this.quickPalette[0] = rgba;
        }
    }

    /**
     * Thread-local scratch memory arena eliminating per-cube heap allocations on hot encode/decode paths.
     */
    public static final class ScratchArena {
        public final int[] cubeRgbaTyx = new int[MAX_CUBE_SAMPLES];
        public final int[] curveRgba = new int[MAX_CUBE_SAMPLES];
        public final short[] keyedCurveToTyx = new short[MAX_CUBE_SAMPLES];
        public final short[] keyedTyxToCurve = new short[MAX_CUBE_SAMPLES];
        public final CubeFingerprint fingerprint = new CubeFingerprint();
        public final long[] nativeMetaOut = new long[8];
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
        public final byte[] xorBitPlane = new byte[MAX_CUBE_SAMPLES];
        public final byte[] rotBitScratch = new byte[MAX_CUBE_SAMPLES];
        public long activeMsbTileKey64 = 0L;
        public long activeLsbTileKey64 = 0L;
        public long activeRelayTileKey64 = 0L;
        public int activeLsbSplitBits = 2;
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
        public final int[] xorRawGaps = new int[MAX_CUBE_SAMPLES];
        public final int[] xorRunLens = new int[MAX_CUBE_SAMPLES];
        public final long[] packedPlaneWords = new long[MAX_CUBE_SAMPLES >>> 6];
        public final long[] packedUpperPlaneWords = new long[MAX_CUBE_SAMPLES >>> 6];
        public final long[][] channelPlaneWords = new long[8][MAX_CUBE_SAMPLES >>> 6];
        public final long[][][] channelPlaneWords4 = new long[4][8][MAX_CUBE_SAMPLES >>> 6];
        public final long[] nativePlaneWordsFlat = new long[4 * 8 * (MAX_CUBE_SAMPLES >>> 6)];
        public final byte[] nativeGrayFlat = new byte[4 * MAX_CUBE_SAMPLES];
        public final int[] med2dPackedSteps = new int[MAX_DEPTH_K + 1];
        public boolean med2dStepsCached = false;
        public boolean knownNonUniformFromGpu = false;
        public int precomputedCurveId = -1;
        public int precomputedPackedMv8 = -1;
        public final boolean[] planarWordsPrepacked = new boolean[4];
        public final long[] evalScoreOut = new long[2];
        public final int[] channelMinV = new int[4];
        public final int[] channelMaxV = new int[4];
        public final short[] planarActiveIdx = new short[MAX_CUBE_SAMPLES];
        public final long[] planarActiveWords = new long[MAX_CUBE_SAMPLES >>> 6];
        public final int[] tyxChannelScratch = new int[MAX_CUBE_SAMPLES];

        public final byte[] candidateUiBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] candidateGrayRawBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] candidateGrayDeltaBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] candidateMed2dBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] candidateMed2dRgbBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] candidatePlanarMvBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] candidateWaveletHaarBuf = new byte[MAX_CUBE_SAMPLES * 4 + 128];
        public final byte[] planeScratchBuf = new byte[MAX_CUBE_SAMPLES + 64];
        public final byte[] xorPlaneScratchBuf = new byte[MAX_CUBE_SAMPLES + 64];
        public final byte[] tripletScratchBuf = new byte[MAX_CUBE_SAMPLES + 64];
        public final byte[] roaringSubScratchA = new byte[MAX_CUBE_SAMPLES + 32];
        public final byte[] roaringSubScratchB = new byte[MAX_CUBE_SAMPLES + 32];
        public final byte[] tileOutScratch = new byte[MAX_CUBE_SAMPLES * 4 + 256];
        public final byte[] haarGridScratch16 = new byte[16];
        public final int[] posCursor = new int[1];
    }
}
