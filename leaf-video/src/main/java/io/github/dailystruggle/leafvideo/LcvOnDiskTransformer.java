package io.github.dailystruggle.leafvideo;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Compressed-domain on-disk stream transformer ({@code LCV-ICD-1.0} Section 4.1 & 5.4) performing:
 * <ol>
 *   <li>{@link #cropSubRegion}: {@code O(1)} tile-aligned spatial sub-region extraction;</li>
 *   <li>{@link #stripLsbPlanes}: instant MSB-to-LSB bitplane proxy stripping ({@code g7..g0 -> g7..g2});</li>
 *   <li>{@link #coalesceRunsByDistance}: post-hoc compressed-domain {@code (adjGap, lenMinusOne)} run-length
 *       token coalescing ({@code rawGap <= d_merge(plane, dist)}).</li>
 * </ol>
 * None of these operations execute inverse Hilbert permutations, inverse Gray-code mapping, or
 * {@code YCoCg-R -> RGBA} pixel conversion.
 *
 * @governedBy leaf-video-ADR-001
 * @trace REQ-LCV-F-002
 * @trace REQ-LCV-F-005
 */
public final class LcvOnDiskTransformer {

    private LcvOnDiskTransformer() {}

    /**
     * Extracts a tile-aligned sub-region {@code [tileX0 .. tileX0 + cropTilesX - 1]} by
     * {@code [tileY0 .. tileY0 + cropTilesY - 1]} directly in the compressed domain by copying
     * only the intersecting {@code uint64} pointers and their Section 4A / Section 4B byte slices.
     */
    public static byte[] cropSubRegion(
        byte[] superblockBytes, int tileX0, int tileY0, int cropTilesX, int cropTilesY
    ) {
        try {
            LcvStreamContainer.SuperblockHeader header = LcvStreamContainer.readSuperblockHeader(superblockBytes, 0);
            int srcTilesX = header.tilesX16();
            int srcTilesY = header.tilesY16();
            if (tileX0 < 0 || tileY0 < 0 || cropTilesX <= 0 || cropTilesY <= 0
                || tileX0 + cropTilesX > srcTilesX || tileY0 + cropTilesY > srcTilesY) {
                throw new IllegalArgumentException("Invalid crop tile bounds");
            }

            int srcTotalTiles = header.totalTiles();
            int dstTotalTiles = cropTilesX * cropTilesY;
            long[] dstPointers = new long[dstTotalTiles];
            byte[] dstSyndromes = new byte[dstTotalTiles];

            ByteBuffer srcBuf = ByteBuffer.wrap(superblockBytes).order(ByteOrder.BIG_ENDIAN);
            int srcDirBase = header.directoryOffset();
            int srcSynBase = header.secDedOffset();
            int srcMsbPoolBase = header.msbPoolOffset();
            int srcLsbPoolBase = header.lsbPoolOffset();

            boolean lsbStripped = (header.flags8() & LcvStreamContainer.FLAG_LSB_STRIPPED) != 0
                || header.lsbPoolCompressedLen32() == 0;
            int srcLsbTableBytes = srcTotalTiles * 4;

            ByteArrayOutputStream dstMsbPoolOut = new ByteArrayOutputStream(dstTotalTiles * 128);
            ByteArrayOutputStream dstLsbPayloadOut = new ByteArrayOutputStream(dstTotalTiles * 64);
            int[] dstLsbTileRelOffsets = new int[dstTotalTiles];

            for (int cy = 0; cy < cropTilesY; cy++) {
                int sy = tileY0 + cy;
                for (int cx = 0; cx < cropTilesX; cx++) {
                    int sx = tileX0 + cx;
                    int srcTileIdx = sy * srcTilesX + sx;
                    int dstTileIdx = cy * cropTilesX + cx;
                    dstLsbTileRelOffsets[dstTileIdx] = dstLsbPayloadOut.size();

                    long rawPtr = srcBuf.getLong(srcDirBase + srcTileIdx * 8);
                    byte syn = superblockBytes[srcSynBase + srcTileIdx];
                    long ptr64 = LcvPrimitives.healSingleBitFlip64(rawPtr, syn);

                    if (ptr64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                        long zeroPtr = LcvStreamContainer.makeUniformInlinePointer(0);
                        dstPointers[dstTileIdx] = zeroPtr;
                        dstSyndromes[dstTileIdx] = LcvPrimitives.computeHammingSecDed8(zeroPtr);
                        continue;
                    }

                    if (LcvStreamContainer.isZeroBodyPointer(ptr64)) {
                        dstPointers[dstTileIdx] = ptr64;
                        dstSyndromes[dstTileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                        continue;
                    }

                    int srcRelMsbOff = LcvStreamContainer.extractRelByteOffset30(ptr64);
                    int msbLen = LcvStreamContainer.extractMsbSliceLen16(ptr64);
                    int lsbLen = lsbStripped ? 0 : LcvStreamContainer.extractLsbSliceLen16(ptr64);

                    int newRelMsbOff = dstMsbPoolOut.size();
                    dstMsbPoolOut.write(superblockBytes, srcMsbPoolBase + srcRelMsbOff, msbLen);

                    if (lsbLen > 0) {
                        int srcRelLsbOff = srcBuf.getInt(srcLsbPoolBase + srcTileIdx * 4);
                        dstLsbPayloadOut.write(
                            superblockBytes, srcLsbPoolBase + srcLsbTableBytes + srcRelLsbOff, lsbLen
                        );
                    }

                    long newPtr = LcvStreamContainer.makeActiveTilePointer(newRelMsbOff, msbLen, lsbLen);
                    dstPointers[dstTileIdx] = newPtr;
                    dstSyndromes[dstTileIdx] = LcvPrimitives.computeHammingSecDed8(newPtr);
                }
            }

            int newWidth = cropTilesX * LcvPrimitives.TILE_DIM;
            int newHeight = cropTilesY * LcvPrimitives.TILE_DIM;
            int newRoiX = Math.max(0, Math.min(cropTilesX - 1, header.roiTileX16() - tileX0));
            int newRoiY = Math.max(0, Math.min(cropTilesY - 1, header.roiTileY16() - tileY0));

            byte[] dstMsbBytes = dstMsbPoolOut.toByteArray();
            byte[] dstLsbBytes = LcvStreamContainer.buildIndexedLsbPool(
                dstTotalTiles, dstLsbTileRelOffsets, dstLsbPayloadOut.toByteArray()
            );

            return LcvStreamContainer.assembleSuperblockBytes(
                newWidth, newHeight, header.depthK8(), header.flags8(),
                cropTilesX, cropTilesY, newRoiX, newRoiY, header.tuningProfile16(),
                header.frameIndex64(), header.prevSbOffset64(),
                header.fpsNum32(), header.fpsDen32(),
                dstPointers, dstSyndromes, dstMsbBytes, dstLsbBytes
            );
        } catch (LcvCorruptTileException e) {
            throw new IllegalArgumentException("Corrupt source Superblock in cropSubRegion", e);
        }
    }

    /**
     * Strips the Section 4B Tail LSB Slice Pool ({@code g1..g0}) in {@code O(1)} on disk without
     * decoding pixels or bitplane runs, producing a valid lightweight proxy {@code Superblock}.
     */
    public static byte[] stripLsbPlanes(byte[] superblockBytes) {
        try {
            LcvStreamContainer.SuperblockHeader header = LcvStreamContainer.readSuperblockHeader(superblockBytes, 0);
            int nTiles = header.totalTiles();
            int strippedBytes = header.lsbPoolOffset();

            byte[] out = Arrays.copyOf(superblockBytes, strippedBytes);
            ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);

            int newFlags = header.flags8() | LcvStreamContainer.FLAG_LSB_STRIPPED;
            buf.put(0x11, (byte) newFlags);
            buf.putInt(0x20, 0); // lsbPoolCompressedLen32 = 0

            int dirBase = header.directoryOffset();
            int synBase = header.secDedOffset();
            int msbPoolBase = header.msbPoolOffset();
            boolean zstdPool = (header.flags8() & LcvStreamContainer.FLAG_ZSTD_POOL) != 0;

            for (int i = 0; i < nTiles; i++) {
                long rawPtr = buf.getLong(dirBase + i * 8);
                byte syn = out[synBase + i];
                long ptr64 = LcvPrimitives.healSingleBitFlip64(rawPtr, syn);
                if (ptr64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL || LcvStreamContainer.isZeroBodyPointer(ptr64)) {
                    continue;
                }
                int relMsbOff = LcvStreamContainer.extractRelByteOffset30(ptr64);
                int msbLen = LcvStreamContainer.extractMsbSliceLen16(ptr64);

                // Clear lsbSliceByteLen16 to 0 and recompute SEC-DED syndrome
                long newPtr = LcvStreamContainer.makeActiveTilePointer(relMsbOff, msbLen, 0);
                buf.putLong(dirBase + i * 8, newPtr);
                out[synBase + i] = LcvPrimitives.computeHammingSecDed8(newPtr);
            }

            int newCrc = LcvStreamContainer.computeHeaderCrc32(out, 0);
            buf.putInt(0x3C, newCrc);
            return out;
        } catch (LcvCorruptTileException e) {
            throw new IllegalArgumentException("Corrupt source Superblock in stripLsbPlanes", e);
        }
    }

    /**
     * Rewrites {@code mode 10} ({@code 4|4} nibble {@code adjGap | lenMinusOne}) and {@code mode 11}
     * bitplane streams directly in the compressed domain to merge consecutive {@code 1}-runs separated
     * by {@code rawGap <= policy.maxMergeGap(plane, dist)} without decoding pixels or curves.
     */
    public static byte[] coalesceRunsByDistance(
        byte[] superblockBytes, int roiTileX, int roiTileY, LcvCubeCodec.DistanceRlePolicy policy
    ) {
        try {
            LcvStreamContainer.SuperblockHeader header = LcvStreamContainer.readSuperblockHeader(superblockBytes, 0);
            if (policy == null) {
                policy = LcvCubeCodec.DistanceRlePolicy.LOSSLESS;
            }
            int tilesX = header.tilesX16();
            int tilesY = header.tilesY16();
            int nTiles = header.totalTiles();
            int depthK = header.depthK8();
            int nSamples = LcvPrimitives.TILE_PIXELS * depthK;

            ByteBuffer srcBuf = ByteBuffer.wrap(superblockBytes).order(ByteOrder.BIG_ENDIAN);
            int dirBase = header.directoryOffset();
            int synBase = header.secDedOffset();
            int srcMsbPoolBase = header.msbPoolOffset();
            int srcLsbPoolBase = header.lsbPoolOffset();
            boolean lsbStripped = (header.flags8() & LcvStreamContainer.FLAG_LSB_STRIPPED) != 0
                || header.lsbPoolCompressedLen32() == 0;
            int srcLsbTableBytes = nTiles * 4;

            long[] dstPointers = new long[nTiles];
            byte[] dstSyndromes = new byte[nTiles];
            ByteArrayOutputStream dstMsbPoolOut = new ByteArrayOutputStream(header.msbPoolCompressedLen32());
            ByteArrayOutputStream dstLsbPayloadOut = new ByteArrayOutputStream(
                Math.max(64, header.lsbPoolCompressedLen32())
            );
            int[] dstLsbTileRelOffsets = new int[nTiles];

            LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
            byte[] tileMsbScratch = new byte[nSamples * 4 + 128];
            byte[] tileLsbScratch = new byte[nSamples * 2 + 64];

            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    int tileIdx = ty * tilesX + tx;
                    dstLsbTileRelOffsets[tileIdx] = dstLsbPayloadOut.size();

                    long rawPtr = srcBuf.getLong(dirBase + tileIdx * 8);
                    byte syn = superblockBytes[synBase + tileIdx];
                    long ptr64 = LcvPrimitives.healSingleBitFlip64(rawPtr, syn);

                    if (ptr64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL
                        || LcvStreamContainer.isZeroBodyPointer(ptr64)) {
                        dstPointers[tileIdx] = ptr64;
                        dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                        continue;
                    }

                    int srcRelMsbOff = LcvStreamContainer.extractRelByteOffset30(ptr64);
                    int srcMsbLen = LcvStreamContainer.extractMsbSliceLen16(ptr64);
                    int srcLsbLen = lsbStripped ? 0 : LcvStreamContainer.extractLsbSliceLen16(ptr64);
                    int srcAbsMsbOff = srcMsbPoolBase + srcRelMsbOff;
                    int srcAbsLsbOff = 0;
                    if (srcLsbLen > 0) {
                        int srcRelLsbOff = srcBuf.getInt(srcLsbPoolBase + tileIdx * 4);
                        srcAbsLsbOff = srcLsbPoolBase + srcLsbTableBytes + srcRelLsbOff;
                    }

                    double dx = tx - roiTileX;
                    double dy = ty - roiTileY;
                    double radialDist = Math.sqrt(dx * dx + dy * dy);

                    int cubeHeader8 = superblockBytes[srcAbsMsbOff] & 0xFF;
                    int cubeMode = (cubeHeader8 >>> 4) & 0x03;

                    // If lossless at this radial distance or non-Gray mode, copy slices directly
                    if (policy.isLosslessAt(radialDist)
                        || (cubeMode != LcvCubeCodec.CUBE_MODE_GRAY_PLANES_RAW
                            && cubeMode != LcvCubeCodec.CUBE_MODE_GRAY_PLANES_HILBERT_DELTA)) {
                        int newRelMsbOff = dstMsbPoolOut.size();
                        dstMsbPoolOut.write(superblockBytes, srcAbsMsbOff, srcMsbLen);
                        if (srcLsbLen > 0) {
                            dstLsbPayloadOut.write(superblockBytes, srcAbsLsbOff, srcLsbLen);
                        }
                        long newPtr = LcvStreamContainer.makeActiveTilePointer(newRelMsbOff, srcMsbLen, srcLsbLen);
                        dstPointers[tileIdx] = newPtr;
                        dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(newPtr);
                        continue;
                    }

                    // Coalesce bitplane tokens directly in the compressed domain (zero pixel decode)
                    int lsbSplitBits = header.tuningProfile().lsbSplitBits();
                    int[] newLengths = coalesceTileGrayBitplanesCompressed(
                        superblockBytes, srcAbsMsbOff, srcMsbLen,
                        superblockBytes, srcAbsLsbOff, srcLsbLen,
                        nSamples, radialDist, policy, lsbSplitBits, arena,
                        tileMsbScratch, tileLsbScratch
                    );

                    int newRelMsbOff = dstMsbPoolOut.size();
                    int newMsbLen = newLengths[0];
                    int newLsbLen = newLengths[1];
                    dstMsbPoolOut.write(tileMsbScratch, 0, newMsbLen);
                    if (newLsbLen > 0) {
                        dstLsbPayloadOut.write(tileLsbScratch, 0, newLsbLen);
                    }

                    long newPtr = LcvStreamContainer.makeActiveTilePointer(newRelMsbOff, newMsbLen, newLsbLen);
                    dstPointers[tileIdx] = newPtr;
                    dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(newPtr);
                }
            }

            byte[] dstMsbBytes = dstMsbPoolOut.toByteArray();
            byte[] dstLsbBytes = LcvStreamContainer.buildIndexedLsbPool(
                nTiles, dstLsbTileRelOffsets, dstLsbPayloadOut.toByteArray()
            );

            LcvCubeCodec.TuningProfile oldProfile = header.tuningProfile();
            int updatedTuning16 = new LcvCubeCodec.TuningProfile(
                oldProfile.lsbSplitBits(), policy, oldProfile.cleanAckRefreshWindow()
            ).toPackedU16();

            return LcvStreamContainer.assembleSuperblockBytes(
                header.width16(), header.height16(), depthK, header.flags8(),
                tilesX, tilesY, roiTileX, roiTileY, updatedTuning16,
                header.frameIndex64(), header.prevSbOffset64(),
                header.fpsNum32(), header.fpsDen32(),
                dstPointers, dstSyndromes, dstMsbBytes, dstLsbBytes
            );
        } catch (LcvCorruptTileException e) {
            throw new IllegalArgumentException("Corrupt source Superblock in coalesceRunsByDistance", e);
        }
    }

    private static int[] coalesceTileGrayBitplanesCompressed(
        byte[] msbSrc, int msbOff, int msbLen,
        byte[] lsbSrc, int lsbOff, int lsbLen,
        int nSamples, double radialDist, LcvCubeCodec.DistanceRlePolicy policy, int lsbSplitBits,
        LcvPrimitives.ScratchArena arena,
        byte[] dstMsb, byte[] dstLsb
    ) throws LcvCorruptTileException {
        int msbLimit = msbOff + msbLen;
        int lsbLimit = lsbOff + lsbLen;

        int srcPos = msbOff;
        int dstMsbPos = 0;

        int cubeHeader8 = msbSrc[srcPos++] & 0xFF;
        dstMsb[dstMsbPos++] = (byte) cubeHeader8;
        int cubeMode = (cubeHeader8 >>> 4) & 0x03;
        int channelMask4 = cubeHeader8 & 0x0F;

        // Copy inactive channel constant bytes
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                dstMsb[dstMsbPos++] = msbSrc[srcPos++];
            }
        }

        int deltaFlags8 = LcvCubeCodec.DELTA_SUBMODE_HILBERT_1D_MSB;
        int packedMv8 = 0x88;
        if (cubeMode == LcvCubeCodec.CUBE_MODE_GRAY_PLANES_HILBERT_DELTA) {
            if (srcPos >= msbLimit) {
                throw new LcvCorruptTileException("CBIT: truncated deltaFlags8 in compressed transform");
            }
            deltaFlags8 = msbSrc[srcPos++] & 0xFF;
            dstMsb[dstMsbPos++] = (byte) deltaFlags8;
            if ((deltaFlags8 & LcvCubeCodec.DELTA_FLAG_MOTION_SHEARED) != 0) {
                if (srcPos >= msbLimit) {
                    throw new LcvCorruptTileException("CBIT: truncated packedMv8 in compressed transform");
                }
                packedMv8 = msbSrc[srcPos++] & 0xFF;
                dstMsb[dstMsbPos++] = (byte) packedMv8;
            }
        }

        if ((deltaFlags8 & LcvCubeCodec.DELTA_SUBMODE_PLANAR_3D_MV) != 0) {
            return coalescePlanarMvTileCompressed(
                msbSrc, srcPos, msbLimit,
                lsbSrc, lsbOff, lsbLen, lsbLimit,
                channelMask4, deltaFlags8, nSamples, radialDist, policy, lsbSplitBits,
                arena, dstMsb, dstMsbPos, dstLsb
            );
        }

        // Read existing planeModes16[c] and reserve space in dstMsb
        int[] srcPlaneModes = new int[4];
        int[] dstPlaneModesOffsets = new int[4];
        int[] dstPlaneModesWords = new int[4];
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                srcPlaneModes[c] = ((msbSrc[srcPos] & 0xFF) << 8) | (msbSrc[srcPos + 1] & 0xFF);
                srcPos += 2;
                dstPlaneModesOffsets[c] = dstMsbPos;
                dstMsb[dstMsbPos++] = 0;
                dstMsb[dstMsbPos++] = 0;
            }
        }

        int[] posRef = arena.posCursor;
        posRef[0] = srcPos;

        // 1. Process Base MSB Slice planes p = 7..lsbSplitBits
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            int pModes = srcPlaneModes[c];
            for (int p = 7; p >= lsbSplitBits; p--) {
                int oldMode = (pModes >>> (2 * p)) & 0x03;
                int maxGap = (cubeMode == LcvCubeCodec.CUBE_MODE_GRAY_PLANES_HILBERT_DELTA)
                    ? 0
                    : policy.maxMergeGap(p, radialDist);
                int[] newModeAndBytes = rewriteSingleCompressedPlane(
                    msbSrc, posRef, msbLimit, nSamples, oldMode, maxGap, arena, dstMsb, dstMsbPos
                );
                dstPlaneModesWords[c] |= (newModeAndBytes[0] << (2 * p));
                dstMsbPos += newModeAndBytes[1];
            }
        }

        // 2. Process Tail LSB Slice planes p = (lsbSplitBits - 1)..0
        int dstLsbPos = 0;
        if (lsbLen > 0) {
            posRef[0] = lsbOff;
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) == 0) {
                    continue;
                }
                int pModes = srcPlaneModes[c];
                for (int p = lsbSplitBits - 1; p >= 0; p--) {
                    int oldMode = (pModes >>> (2 * p)) & 0x03;
                    int maxGap = policy.maxMergeGap(p, radialDist);
                    int[] newModeAndBytes = rewriteSingleCompressedPlane(
                        lsbSrc, posRef, lsbLimit, nSamples, oldMode, maxGap, arena, dstLsb, dstLsbPos
                    );
                    dstPlaneModesWords[c] |= (newModeAndBytes[0] << (2 * p));
                    dstLsbPos += newModeAndBytes[1];
                }
            }
        }

        // Write updated planeModes16[c] words
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                int off = dstPlaneModesOffsets[c];
                int word = dstPlaneModesWords[c];
                dstMsb[off] = (byte) (word >>> 8);
                dstMsb[off + 1] = (byte) word;
            }
        }

        return new int[]{dstMsbPos, dstLsbPos};
    }

    private static int[] coalescePlanarMvTileCompressed(
        byte[] msbSrc, int srcPos, int msbLimit,
        byte[] lsbSrc, int lsbOff, int lsbLen, int lsbLimit,
        int channelMask4, int deltaFlags8, int nSamples, double radialDist,
        LcvCubeCodec.DistanceRlePolicy policy, int lsbSplitBits,
        LcvPrimitives.ScratchArena arena,
        byte[] dstMsb, int dstMsbPos, byte[] dstLsb
    ) throws LcvCorruptTileException {
        int[] srcPlaneModes = new int[4];
        int[] dstPlaneModesOffsets = new int[4];
        int[] dstPlaneModesWords = new int[4];
        int[] maxU8PerChannel = new int[4];
        boolean hasWaveletHaar = (deltaFlags8 & LcvCubeCodec.DELTA_SUBMODE_WAVELET_3D_HAAR) != 0;

        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                int baseNeed = hasWaveletHaar ? 13 : 12;
                if (srcPos + baseNeed > msbLimit) {
                    throw new LcvCorruptTileException("CBIT: truncated planar MV header in compressed transform");
                }
                srcPlaneModes[c] = ((msbSrc[srcPos] & 0xFF) << 8) | (msbSrc[srcPos + 1] & 0xFF);
                srcPos += 2;
                dstPlaneModesOffsets[c] = dstMsbPos;
                dstMsb[dstMsbPos++] = 0;
                dstMsb[dstMsbPos++] = 0;
                maxU8PerChannel[c] = msbSrc[srcPos + 8] & 0xFF;
                int paramBytes = 10;
                if (hasWaveletHaar) {
                    int hStep = msbSrc[srcPos + 10] & 0xFF;
                    paramBytes = (hStep > 0) ? 19 : 11;
                    if (srcPos + paramBytes > msbLimit) {
                        throw new LcvCorruptTileException("CBIT: truncated Haar nibbles in compressed transform");
                    }
                }
                System.arraycopy(msbSrc, srcPos, dstMsb, dstMsbPos, paramBytes);
                srcPos += paramBytes;
                dstMsbPos += paramBytes;
            }
        }

        int[] posRef = arena.posCursor;
        posRef[0] = srcPos;

        // 1. Copy Section 4A Base MSB Slice planes p = 7..lsbSplitBits and track M_i bitmasks
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            byte[] msbMaskArr = arena.msbReconstructed[c];
            Arrays.fill(msbMaskArr, 0, nSamples, (byte) 0);
            int pModes = srcPlaneModes[c];
            int maxU8 = maxU8PerChannel[c];

            for (int p = 7; p >= lsbSplitBits; p--) {
                int bitVal = 1 << p;
                int oldMode = (pModes >>> (2 * p)) & 0x03;
                if (bitVal > maxU8 || oldMode == LcvCubeCodec.PLANE_ALL_ZERO) {
                    dstPlaneModesWords[c] |= (LcvCubeCodec.PLANE_ALL_ZERO << (2 * p));
                    continue;
                }
                int nActive = 0;
                for (int i = 0; i < nSamples; i++) {
                    if ((msbMaskArr[i] & 0xFF) + bitVal <= maxU8) {
                        nActive++;
                    }
                }
                int startPos = posRef[0];
                LcvCubeCodec.decodeConditionedBitplane(
                    msbSrc, posRef, msbLimit, nActive, p, oldMode, arena.activeBitPlane, arena
                );
                int sliceBytes = posRef[0] - startPos;
                System.arraycopy(msbSrc, startPos, dstMsb, dstMsbPos, sliceBytes);
                dstMsbPos += sliceBytes;
                dstPlaneModesWords[c] |= (oldMode << (2 * p));

                int k = 0;
                for (int i = 0; i < nSamples; i++) {
                    if ((msbMaskArr[i] & 0xFF) + bitVal <= maxU8) {
                        if (arena.activeBitPlane[k++] != 0) {
                            msbMaskArr[i] |= (byte) bitVal;
                        }
                    }
                }
            }
        }

        // 2. Rewrite Section 4B Tail LSB Slice planes p = (lsbSplitBits - 1)..0 with d_merge(p, radialDist)
        int dstLsbPos = 0;
        if (lsbLen > 0) {
            posRef[0] = lsbOff;
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) == 0) {
                    continue;
                }
                byte[] msbOnly = arena.msbReconstructed[c];
                int pModes = srcPlaneModes[c];
                int maxU8 = maxU8PerChannel[c];

                for (int p = lsbSplitBits - 1; p >= 0; p--) {
                    int bitVal = 1 << p;
                    int oldMode = (pModes >>> (2 * p)) & 0x03;
                    if (bitVal > maxU8 || oldMode == LcvCubeCodec.PLANE_ALL_ZERO) {
                        dstPlaneModesWords[c] |= (LcvCubeCodec.PLANE_ALL_ZERO << (2 * p));
                        continue;
                    }
                    int nActive = 0;
                    for (int i = 0; i < nSamples; i++) {
                        if ((msbOnly[i] & 0xFF) + bitVal <= maxU8) {
                            nActive++;
                        }
                    }
                    if (nActive == 0) {
                        dstPlaneModesWords[c] |= (LcvCubeCodec.PLANE_ALL_ZERO << (2 * p));
                        continue;
                    }
                    int startPos = posRef[0];
                    LcvCubeCodec.decodeConditionedBitplane(
                        lsbSrc, posRef, lsbLimit, nActive, p, oldMode, arena.activeBitPlane, arena
                    );
                    int maxGap = (bitVal * 2 > maxU8) ? 0 : policy.maxMergeGap(p, radialDist);
                    if (maxGap <= 0) {
                        int sliceBytes = posRef[0] - startPos;
                        System.arraycopy(lsbSrc, startPos, dstLsb, dstLsbPos, sliceBytes);
                        dstLsbPos += sliceBytes;
                        dstPlaneModesWords[c] |= (oldMode << (2 * p));
                    } else {
                        int[] newModeAndBytes = LcvCubeCodec.encodeConditionedBitplane(
                            arena.activeBitPlane, nActive, maxGap, arena, dstLsb, dstLsbPos
                        );
                        dstPlaneModesWords[c] |= (newModeAndBytes[0] << (2 * p));
                        dstLsbPos += newModeAndBytes[1];
                    }
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
        }

        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                int off = dstPlaneModesOffsets[c];
                int word = dstPlaneModesWords[c];
                dstMsb[off] = (byte) (word >>> 8);
                dstMsb[off + 1] = (byte) word;
            }
        }

        return new int[]{dstMsbPos, dstLsbPos};
    }

    private static int[] rewriteSingleCompressedPlane(
        byte[] src, int[] posRef, int limit, int nSamples,
        int oldMode, int maxGap, LcvPrimitives.ScratchArena arena,
        byte[] dst, int dstOff
    ) throws LcvCorruptTileException {
        if (oldMode == LcvCubeCodec.PLANE_ALL_ZERO || oldMode == LcvCubeCodec.PLANE_ALL_ONE) {
            return new int[]{oldMode, 0};
        }
        int bitmapBytes = nSamples >>> 3;
        int[] rawGaps = arena.rawGaps;
        int[] runLens = arena.runLens;
        int runCount;

        if (oldMode == LcvCubeCodec.PLANE_NIBBLE_RLE) {
            int startPos = posRef[0];
            runCount = LcvCubeCodec.readNibbleRunStream(src, posRef, limit, nSamples, rawGaps, runLens);
            if (maxGap <= 0) {
                int rawLen = posRef[0] - startPos;
                System.arraycopy(src, startPos, dst, dstOff, rawLen);
                return new int[]{LcvCubeCodec.PLANE_NIBBLE_RLE, rawLen};
            }
        } else {
            // PLANE_RAW_BITMAP (mode 11)
            int startPos = posRef[0];
            if (startPos + bitmapBytes > limit) {
                throw new LcvCorruptTileException("CBIT: truncated raw bitmap in compressed transform");
            }
            posRef[0] = startPos + bitmapBytes;
            if (maxGap <= 0) {
                System.arraycopy(src, startPos, dst, dstOff, bitmapBytes);
                return new int[]{LcvCubeCodec.PLANE_RAW_BITMAP, bitmapBytes};
            }
            // Extract 1D curve runs from the bitmap without any curve/pixel decoding
            runCount = 0;
            int i = 0;
            int lastEnd = 0;
            while (i < nSamples) {
                while (i < nSamples && (((src[startPos + (i >>> 3)] >>> (i & 7)) & 1) == 0)) {
                    i++;
                }
                if (i >= nSamples) {
                    break;
                }
                int runStart = i;
                while (i < nSamples && (((src[startPos + (i >>> 3)] >>> (i & 7)) & 1) != 0)) {
                    i++;
                }
                rawGaps[runCount] = runStart - lastEnd;
                runLens[runCount] = i - runStart;
                runCount++;
                lastEnd = i;
            }
            if (runCount == 0) {
                return new int[]{LcvCubeCodec.PLANE_ALL_ZERO, 0};
            }
        }

        // Apply algebraic run-length gap concatenation (rawGap <= maxGap)
        runCount = LcvCubeCodec.coalesceRunArraysInPlace(rawGaps, runLens, runCount, maxGap);
        if (runCount == 1 && rawGaps[0] == 0 && runLens[0] == nSamples) {
            return new int[]{LcvCubeCodec.PLANE_ALL_ONE, 0};
        }

        int rleBytes = LcvCubeCodec.writeNibbleRunStream(rawGaps, runLens, runCount, arena.planeScratchBuf, 0);
        if (rleBytes < bitmapBytes) {
            System.arraycopy(arena.planeScratchBuf, 0, dst, dstOff, rleBytes);
            return new int[]{LcvCubeCodec.PLANE_NIBBLE_RLE, rleBytes};
        }

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
        return new int[]{LcvCubeCodec.PLANE_RAW_BITMAP, bitmapBytes};
    }

    /**
     * Recodes a {@code Superblock} inline packet-by-packet (tile-by-tile) into {@code targetProfile}
     * (with optional LSB noise stripping), stopping after {@code maxPacketsToRecode} packets.
     * If {@code maxPacketsToRecode < totalTiles} (e.g., simulating a mid-recode crash or bounded work slice),
     * the returned {@code Superblock} carries {@link LcvStreamContainer#FLAG_INLINE_RECODE_PARTIAL} and a
     * 4-byte Section 4B checkpoint {@code (recodedCursor16, priorTuningProfile16)} so it remains
     * immediately playable and trivially resumable via {@link #resumeInlineRecode}.
     */
    public static byte[] recodePacketsInline(
        byte[] superblockBytes, int roiTileX, int roiTileY,
        LcvCubeCodec.TuningProfile targetProfile, boolean stripTargetLsb, int maxPacketsToRecode
    ) {
        if (targetProfile == null) {
            targetProfile = LcvCubeCodec.TuningProfile.DEFAULT_LOSSLESS;
        }
        return executeInlinePacketRecodeSlice(
            superblockBytes, roiTileX, roiTileY, targetProfile, null, 0,
            Math.max(0, maxPacketsToRecode), stripTargetLsb
        );
    }

    /**
     * Resumes a mid-recode crashed or partially recoded {@code Superblock} from its inline
     * checkpoint in {@code O(1)} time, copying already-recoded packets {@code 0 .. cursor - 1}
     * verbatim without re-encoding and recoding up to {@code additionalPacketsToRecode} remaining packets.
     */
    public static byte[] resumeInlineRecode(
        byte[] partialSuperblockBytes, boolean stripTargetLsb, int additionalPacketsToRecode
    ) {
        try {
            LcvStreamContainer.SuperblockHeader header = LcvStreamContainer.readSuperblockHeader(partialSuperblockBytes, 0);
            if ((header.flags8() & LcvStreamContainer.FLAG_INLINE_RECODE_PARTIAL) == 0) {
                return partialSuperblockBytes;
            }
            int lsbPoolBase = header.lsbPoolOffset();
            ByteBuffer buf = ByteBuffer.wrap(partialSuperblockBytes).order(ByteOrder.BIG_ENDIAN);
            int startCursor = buf.getShort(lsbPoolBase) & 0xFFFF;
            int priorPacked16 = buf.getShort(lsbPoolBase + 2) & 0xFFFF;
            LcvCubeCodec.TuningProfile priorProfile = LcvCubeCodec.TuningProfile.fromPackedU16(priorPacked16);

            return executeInlinePacketRecodeSlice(
                partialSuperblockBytes, header.roiTileX16(), header.roiTileY16(),
                header.tuningProfile(), priorProfile, startCursor,
                Math.max(0, additionalPacketsToRecode), stripTargetLsb
            );
        } catch (LcvCorruptTileException e) {
            throw new IllegalArgumentException("Corrupt Superblock in resumeInlineRecode", e);
        }
    }

    private static byte[] executeInlinePacketRecodeSlice(
        byte[] superblockBytes, int roiTileX, int roiTileY,
        LcvCubeCodec.TuningProfile targetProfile, LcvCubeCodec.TuningProfile explicitPriorProfile,
        int startCursor, int packetsToProcess, boolean stripTargetLsb
    ) {
        try {
            LcvStreamContainer.SuperblockHeader header = LcvStreamContainer.readSuperblockHeader(superblockBytes, 0);
            int tilesX = header.tilesX16();
            int tilesY = header.tilesY16();
            int nTiles = header.totalTiles();
            int depthK = header.depthK8();
            int nSamples = LcvPrimitives.TILE_PIXELS * depthK;

            boolean wasPartial = (header.flags8() & LcvStreamContainer.FLAG_INLINE_RECODE_PARTIAL) != 0;
            ByteBuffer srcBuf = ByteBuffer.wrap(superblockBytes).order(ByteOrder.BIG_ENDIAN);
            int dirBase = header.directoryOffset();
            int synBase = header.secDedOffset();
            int srcMsbPoolBase = header.msbPoolOffset();
            int srcLsbPoolBase = header.lsbPoolOffset();
            boolean srcLsbStripped = (header.flags8() & LcvStreamContainer.FLAG_LSB_STRIPPED) != 0
                || header.lsbPoolCompressedLen32() == 0;

            int srcCheckpointBytes = wasPartial ? 4 : 0;
            int srcLsbTableBase = srcLsbPoolBase + srcCheckpointBytes;
            int srcLsbDataBase = srcLsbTableBase + nTiles * 4;

            LcvCubeCodec.TuningProfile priorProfile = (explicitPriorProfile != null)
                ? explicitPriorProfile
                : (wasPartial && !srcLsbStripped
                    ? LcvCubeCodec.TuningProfile.fromPackedU16(srcBuf.getShort(srcLsbPoolBase + 2) & 0xFFFF)
                    : header.tuningProfile());

            int safeStart = Math.max(0, Math.min(nTiles, startCursor));
            int endCursor = (packetsToProcess >= nTiles - safeStart)
                ? nTiles
                : (safeStart + packetsToProcess);
            boolean stillPartial = endCursor < nTiles;

            long[] dstPointers = new long[nTiles];
            byte[] dstSyndromes = new byte[nTiles];
            ByteArrayOutputStream dstMsbPoolOut = new ByteArrayOutputStream(header.msbPoolCompressedLen32());
            ByteArrayOutputStream dstLsbPayloadOut = new ByteArrayOutputStream(
                Math.max(64, header.lsbPoolCompressedLen32())
            );
            int[] dstLsbTileRelOffsets = new int[nTiles];

            LcvPrimitives.ScratchArena arena = LcvPrimitives.threadArena();
            byte[] tileMsbScratch = new byte[nSamples * 4 + 256];
            byte[] tileLsbScratch = new byte[nSamples * 2 + 256];

            int srcSplitBits = priorProfile.lsbSplitBits();
            int dstSplitBits = targetProfile.lsbSplitBits();

            for (int tileIdx = 0; tileIdx < nTiles; tileIdx++) {
                int tx = tileIdx % tilesX;
                int ty = tileIdx / tilesX;
                long rawPtr = srcBuf.getLong(dirBase + tileIdx * 8);
                byte syn = superblockBytes[synBase + tileIdx];
                long ptr64 = LcvPrimitives.healSingleBitFlip64(rawPtr, syn);

                if (ptr64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                    dstPointers[tileIdx] = rawPtr;
                    dstSyndromes[tileIdx] = syn;
                    dstLsbTileRelOffsets[tileIdx] = dstLsbPayloadOut.size();
                    continue;
                }

                if (LcvStreamContainer.isZeroBodyPointer(ptr64)) {
                    dstPointers[tileIdx] = ptr64;
                    dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                    dstLsbTileRelOffsets[tileIdx] = dstLsbPayloadOut.size();
                    continue;
                }

                int srcMsbOff = srcMsbPoolBase + LcvStreamContainer.extractRelByteOffset30(ptr64);
                int srcMsbLen = LcvStreamContainer.extractMsbSliceLen16(ptr64);
                int srcLsbLen = srcLsbStripped ? 0 : LcvStreamContainer.extractLsbSliceLen16(ptr64);
                int srcLsbOff = 0;
                if (srcLsbLen > 0) {
                    int relLsb = srcBuf.getInt(srcLsbTableBase + tileIdx * 4);
                    srcLsbOff = srcLsbDataBase + relLsb;
                }

                // Case 1: Already-recoded packet (tileIdx < safeStart) OR not-yet-reached packet (tileIdx >= endCursor)
                // -> Copy verbatim in O(1) without decoding or re-encoding!
                if (tileIdx < safeStart || tileIdx >= endCursor) {
                    int newRelMsbOff = dstMsbPoolOut.size();
                    dstMsbPoolOut.write(superblockBytes, srcMsbOff, srcMsbLen);
                    dstLsbTileRelOffsets[tileIdx] = dstLsbPayloadOut.size();
                    if (srcLsbLen > 0) {
                        dstLsbPayloadOut.write(superblockBytes, srcLsbOff, srcLsbLen);
                    }
                    long newPtr = LcvStreamContainer.makeActiveTilePointer(newRelMsbOff, srcMsbLen, srcLsbLen);
                    dstPointers[tileIdx] = newPtr;
                    dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(newPtr);
                    continue;
                }

                // Case 2: Packet in active recode window [safeStart .. endCursor - 1]
                int newMsbLen;
                int newLsbLen;
                if (srcSplitBits == dstSplitBits) {
                    // Same MSB/LSB split boundary -> rewrite directly in the compressed domain!
                    double dx = tx - roiTileX;
                    double dy = ty - roiTileY;
                    double radialDist = Math.sqrt(dx * dx + dy * dy);
                    int effectiveSrcLsbLen = stripTargetLsb ? 0 : srcLsbLen;
                    int[] newLengths = coalesceTileGrayBitplanesCompressed(
                        superblockBytes, srcMsbOff, srcMsbLen,
                        superblockBytes, srcLsbOff, effectiveSrcLsbLen,
                        nSamples, radialDist, targetProfile.rlePolicy(), dstSplitBits,
                        arena, tileMsbScratch, tileLsbScratch
                    );
                    newMsbLen = newLengths[0];
                    newLsbLen = stripTargetLsb ? 0 : newLengths[1];
                } else {
                    // Split boundary changed (e.g., s=2 -> s=3) -> inline single-packet transcode in ScratchArena
                    LcvCubeCodec.decodeSplitCubeRgba(
                        superblockBytes, srcMsbOff, srcMsbLen,
                        superblockBytes, srcLsbOff, srcLsbLen,
                        false, depthK, srcSplitBits, arena, arena.cubeRgbaTyx
                    );
                    LcvCubeCodec.CubeEncodeResult recoded = LcvCubeCodec.encodeExtractedCubeTyx(
                        arena.cubeRgbaTyx, depthK, tx, ty,
                        roiTileX, roiTileY, targetProfile.rlePolicy(), dstSplitBits, arena, tileMsbScratch, 0
                    );
                    newMsbLen = recoded.msbSliceBytes();
                    newLsbLen = stripTargetLsb ? 0 : recoded.lsbSliceBytes();
                    if (newLsbLen > 0) {
                        System.arraycopy(tileMsbScratch, newMsbLen, tileLsbScratch, 0, newLsbLen);
                    }
                }

                int newRelMsbOff = dstMsbPoolOut.size();
                dstMsbPoolOut.write(tileMsbScratch, 0, newMsbLen);
                dstLsbTileRelOffsets[tileIdx] = dstLsbPayloadOut.size();
                if (newLsbLen > 0) {
                    dstLsbPayloadOut.write(tileLsbScratch, 0, newLsbLen);
                }

                long newPtr = LcvStreamContainer.makeActiveTilePointer(newRelMsbOff, newMsbLen, newLsbLen);
                dstPointers[tileIdx] = newPtr;
                dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(newPtr);
            }

            byte[] newMsbPool = dstMsbPoolOut.toByteArray();
            byte[] newLsbPayload = dstLsbPayloadOut.toByteArray();
            byte[] newLsbPool;
            int newFlags8 = header.flags8() & ~(LcvStreamContainer.FLAG_ZSTD_POOL
                | LcvStreamContainer.FLAG_LSB_STRIPPED
                | LcvStreamContainer.FLAG_INLINE_RECODE_PARTIAL);

            if (stillPartial) {
                newFlags8 |= LcvStreamContainer.FLAG_INLINE_RECODE_PARTIAL;
                int lsbTableBytes = nTiles * 4;
                newLsbPool = new byte[4 + lsbTableBytes + newLsbPayload.length];
                ByteBuffer lsbOutBuf = ByteBuffer.wrap(newLsbPool).order(ByteOrder.BIG_ENDIAN);
                lsbOutBuf.putShort(0, (short) endCursor);
                lsbOutBuf.putShort(2, (short) priorProfile.toPackedU16());
                for (int i = 0; i < nTiles; i++) {
                    lsbOutBuf.putInt(4 + i * 4, dstLsbTileRelOffsets[i]);
                }
                System.arraycopy(newLsbPayload, 0, newLsbPool, 4 + lsbTableBytes, newLsbPayload.length);
            } else if (newLsbPayload.length > 0 && !stripTargetLsb) {
                int lsbTableBytes = nTiles * 4;
                newLsbPool = new byte[lsbTableBytes + newLsbPayload.length];
                ByteBuffer lsbOutBuf = ByteBuffer.wrap(newLsbPool).order(ByteOrder.BIG_ENDIAN);
                for (int i = 0; i < nTiles; i++) {
                    lsbOutBuf.putInt(i * 4, dstLsbTileRelOffsets[i]);
                }
                System.arraycopy(newLsbPayload, 0, newLsbPool, lsbTableBytes, newLsbPayload.length);
            } else {
                newLsbPool = new byte[0];
                if (stripTargetLsb) {
                    newFlags8 |= LcvStreamContainer.FLAG_LSB_STRIPPED;
                }
            }

            return LcvStreamContainer.assembleSuperblockBytes(
                header.width16(), header.height16(), depthK,
                newFlags8, tilesX, tilesY, roiTileX, roiTileY,
                targetProfile.toPackedU16(),
                header.frameIndex64(), header.prevSbOffset64(),
                header.fpsNum32(), header.fpsDen32(),
                dstPointers, dstSyndromes, newMsbPool, newLsbPool
            );
        } catch (LcvCorruptTileException e) {
            throw new IllegalArgumentException("Corrupt source Superblock in executeInlinePacketRecodeSlice", e);
        }
    }
}
