package io.github.dailystruggle.leafvideo;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
 * @governedBy leaf-video-ADR-003
 * @governedBy leaf-video-ADR-004
 * @trace REQ-LCV-F-002
 * @trace REQ-LCV-F-005
 * @trace REQ-LCV-DB-001
 * @trace REQ-LCV-DB-002
 * @trace REQ-LCV-DB-003
 * @trace REQ-LCV-DB-004
 * @trace REQ-LCV-HW-002
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

            LcvStreamContainer.UnpackedDirectory srcDir =
                LcvStreamContainer.unpackSuperblockDirectory(superblockBytes, 0, header);
            long[] srcHealedPointers = srcDir.healedPointers();
            int srcMsbPoolFileOff = header.msbPoolOffset();
            int srcLsbPoolFileOff = header.lsbPoolOffset();

            boolean zstdPool = (header.flags8() & LcvStreamContainer.FLAG_ZSTD_POOL) != 0;
            boolean lsbStripped = (header.flags8() & LcvStreamContainer.FLAG_LSB_STRIPPED) != 0
                || header.lsbPoolCompressedLen32() == 0;
            int srcLsbTableBytes = srcTotalTiles * 4;

            byte[] srcMsbPool = superblockBytes;
            int srcMsbPoolBase = srcMsbPoolFileOff;
            byte[] srcLsbPool = superblockBytes;
            int srcLsbPoolBase = srcLsbPoolFileOff;
            if (zstdPool) {
                srcMsbPool = LcvStreamContainer.decompressZstdFrameWithSizeHeader(
                    superblockBytes, srcMsbPoolFileOff, header.msbPoolCompressedLen32()
                );
                srcMsbPoolBase = 0;
                if (!lsbStripped && header.lsbPoolCompressedLen32() > 0) {
                    srcLsbPool = LcvStreamContainer.decompressZstdFrameWithSizeHeader(
                        superblockBytes, srcLsbPoolFileOff, header.lsbPoolCompressedLen32()
                    );
                    srcLsbPoolBase = 0;
                } else {
                    srcLsbPool = new byte[0];
                    srcLsbPoolBase = 0;
                }
            }
            ByteBuffer srcLsbBuf = (!lsbStripped && srcLsbPool.length >= srcLsbPoolBase + srcLsbTableBytes)
                ? ByteBuffer.wrap(srcLsbPool).order(ByteOrder.BIG_ENDIAN)
                : null;

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

                    long ptr64 = srcHealedPointers[srcTileIdx];
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
                    int lsbLen = (lsbStripped || srcLsbBuf == null)
                        ? 0
                        : LcvStreamContainer.extractLsbSliceLen16(ptr64);

                    int newRelMsbOff = dstMsbPoolOut.size();
                    dstMsbPoolOut.write(srcMsbPool, srcMsbPoolBase + srcRelMsbOff, msbLen);

                    if (lsbLen > 0 && srcLsbBuf != null) {
                        int srcRelLsbOff = srcLsbBuf.getInt(srcLsbPoolBase + srcTileIdx * 4);
                        dstLsbPayloadOut.write(
                            srcLsbPool, srcLsbPoolBase + srcLsbTableBytes + srcRelLsbOff, lsbLen
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
            int outFlags8 = header.flags8();
            if (zstdPool && LcvStreamContainer.isZstdAvailable()) {
                dstMsbBytes = LcvStreamContainer.compressZstdFrameWithSizeHeader(dstMsbBytes);
                dstLsbBytes = dstLsbBytes.length > 0
                    ? LcvStreamContainer.compressZstdFrameWithSizeHeader(dstLsbBytes)
                    : dstLsbBytes;
            } else {
                outFlags8 &= ~LcvStreamContainer.FLAG_ZSTD_POOL;
            }

            return LcvStreamContainer.assembleSuperblockBytes(
                newWidth, newHeight, header.depthK8(), outFlags8,
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
            int dirWords = header.directoryWords();
            int startWord = header.ctuDescriptorWords();
            int strippedBytes = header.lsbPoolOffset();

            byte[] out = Arrays.copyOf(superblockBytes, strippedBytes);
            ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);

            int newFlags = header.flags8() | LcvStreamContainer.FLAG_LSB_STRIPPED;
            buf.put(0x11, (byte) newFlags);
            buf.putInt(0x20, 0); // lsbPoolCompressedLen32 = 0

            int dirBase = header.directoryOffset();
            int synBase = header.secDedOffset();

            for (int i = startWord; i < dirWords; i++) {
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
     * Selectively strips Section 4B Tail LSB slices on high-frequency foliage/texture tiles and
     * {@code DELTA_SUBMODE_PLANAR_3D_MV} tiles while retaining Section 4B ({@code lsbSliceByteLen16 > 0})
     * on smooth low-variance non-planar gradient tiles so {@code MED-2D} gradients remain free of
     * contour banding.
     */
    public static byte[] stripLsbPlanesSelective(byte[] superblockBytes) {
        return stripLsbPlanesSelective(superblockBytes, 144);
    }

    /**
     * Selectively retains Section 4B Tail LSB slices for non-planar tiles whose Section 4A MSB slice
     * is at most {@code maxSmoothMsbBytesPerFrame * depthK} bytes, stripping Section 4B
     * ({@code lsbSliceByteLen16 = 0}) on all high-frequency textured tiles and {@code PLANAR_3D_MV} tiles.
     */
    public static byte[] stripLsbPlanesSelective(byte[] superblockBytes, int maxSmoothMsbBytesPerFrame) {
        try {
            LcvStreamContainer.SuperblockHeader header = LcvStreamContainer.readSuperblockHeader(superblockBytes, 0);
            boolean zstdPool = (header.flags8() & LcvStreamContainer.FLAG_ZSTD_POOL) != 0;
            boolean lsbStripped = (header.flags8() & LcvStreamContainer.FLAG_LSB_STRIPPED) != 0
                || header.lsbPoolCompressedLen32() == 0;
            if (lsbStripped) {
                return Arrays.copyOf(superblockBytes, superblockBytes.length);
            }

            int totalTiles = header.totalTiles();
            int srcMsbPoolFileOff = header.msbPoolOffset();
            int srcLsbPoolFileOff = header.lsbPoolOffset();
            int srcLsbTableBytes = totalTiles * 4;

            byte[] srcMsbPool = superblockBytes;
            int srcMsbPoolBase = srcMsbPoolFileOff;
            byte[] srcLsbPool = superblockBytes;
            int srcLsbPoolBase = srcLsbPoolFileOff;
            if (zstdPool) {
                srcMsbPool = LcvStreamContainer.decompressZstdFrameWithSizeHeader(
                    superblockBytes, srcMsbPoolFileOff, header.msbPoolCompressedLen32()
                );
                srcMsbPoolBase = 0;
                srcLsbPool = LcvStreamContainer.decompressZstdFrameWithSizeHeader(
                    superblockBytes, srcLsbPoolFileOff, header.lsbPoolCompressedLen32()
                );
                srcLsbPoolBase = 0;
            }
            if (srcLsbPool.length < srcLsbPoolBase + srcLsbTableBytes) {
                return stripLsbPlanes(superblockBytes);
            }
            ByteBuffer srcLsbBuf = ByteBuffer.wrap(srcLsbPool).order(ByteOrder.BIG_ENDIAN);
            LcvStreamContainer.UnpackedDirectory srcDir =
                LcvStreamContainer.unpackSuperblockDirectory(superblockBytes, 0, header);
            long[] srcHealedPointers = srcDir.healedPointers();

            long[] dstPointers = new long[totalTiles];
            byte[] dstSyndromes = new byte[totalTiles];
            int[] dstLsbTileRelOffsets = new int[totalTiles];
            ByteArrayOutputStream dstLsbPayloadOut = new ByteArrayOutputStream(totalTiles * 32);
            int maxSmoothMsbBytes = Math.max(8, maxSmoothMsbBytesPerFrame) * Math.max(1, header.depthK8());

            for (int tileIdx = 0; tileIdx < totalTiles; tileIdx++) {
                dstLsbTileRelOffsets[tileIdx] = dstLsbPayloadOut.size();
                long ptr64 = srcHealedPointers[tileIdx];
                if (ptr64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                    long zeroPtr = LcvStreamContainer.makeUniformInlinePointer(0);
                    dstPointers[tileIdx] = zeroPtr;
                    dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(zeroPtr);
                    continue;
                }
                if (LcvStreamContainer.isZeroBodyPointer(ptr64)) {
                    dstPointers[tileIdx] = ptr64;
                    dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                    continue;
                }

                int relMsbOff = LcvStreamContainer.extractRelByteOffset30(ptr64);
                int msbLen = LcvStreamContainer.extractMsbSliceLen16(ptr64);
                int lsbLen = LcvStreamContainer.extractLsbSliceLen16(ptr64);

                boolean retainLsb = false;
                if (lsbLen > 0 && msbLen > 0 && msbLen <= maxSmoothMsbBytes
                    && srcMsbPoolBase + relMsbOff + msbLen <= srcMsbPool.length) {
                    int mOff = srcMsbPoolBase + relMsbOff;
                    int hdr8 = srcMsbPool[mOff] & 0xFF;
                    int cubeMode = (hdr8 >>> 4) & 0x03;
                    if (cubeMode == LcvCubeCodec.CUBE_MODE_GRAY_PLANES_RAW
                        || cubeMode == LcvCubeCodec.CUBE_MODE_GRAY_PLANES_HILBERT_DELTA) {
                        retainLsb = true;
                    }
                }

                int outLsbLen = 0;
                if (retainLsb) {
                    int srcRelLsbOff = srcLsbBuf.getInt(srcLsbPoolBase + tileIdx * 4);
                    if (srcRelLsbOff >= 0
                        && srcLsbPoolBase + srcLsbTableBytes + srcRelLsbOff + lsbLen <= srcLsbPool.length) {
                        dstLsbPayloadOut.write(
                            srcLsbPool, srcLsbPoolBase + srcLsbTableBytes + srcRelLsbOff, lsbLen
                        );
                        outLsbLen = lsbLen;
                    }
                }

                long newPtr = LcvStreamContainer.makeActiveTilePointer(relMsbOff, msbLen, outLsbLen);
                dstPointers[tileIdx] = newPtr;
                dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(newPtr);
            }

            if (dstLsbPayloadOut.size() == 0) {
                return stripLsbPlanes(superblockBytes);
            }

            byte[] dstMsbBytes = (srcMsbPoolBase == 0 && srcMsbPool.length == header.msbPoolCompressedLen32())
                ? srcMsbPool
                : Arrays.copyOfRange(srcMsbPool, srcMsbPoolBase, srcMsbPoolBase + header.msbPoolCompressedLen32());
            byte[] dstLsbBytes = LcvStreamContainer.buildIndexedLsbPool(
                totalTiles, dstLsbTileRelOffsets, dstLsbPayloadOut.toByteArray()
            );
            int outFlags8 = header.flags8() & ~LcvStreamContainer.FLAG_LSB_STRIPPED;
            if (zstdPool && LcvStreamContainer.isZstdAvailable()) {
                dstMsbBytes = LcvStreamContainer.compressZstdFrameWithSizeHeader(dstMsbBytes);
                dstLsbBytes = LcvStreamContainer.compressZstdFrameWithSizeHeader(dstLsbBytes);
            } else {
                outFlags8 &= ~LcvStreamContainer.FLAG_ZSTD_POOL;
            }

            return LcvStreamContainer.assembleSuperblockBytes(
                header.width16(), header.height16(), header.depthK8(), outFlags8,
                header.tilesX16(), header.tilesY16(), header.roiTileX16(), header.roiTileY16(),
                header.tuningProfile16(), header.frameIndex64(), header.prevSbOffset64(),
                header.fpsNum32(), header.fpsDen32(),
                dstPointers, dstSyndromes, dstMsbBytes, dstLsbBytes
            );
        } catch (LcvCorruptTileException e) {
            throw new IllegalArgumentException("Corrupt source Superblock in stripLsbPlanesSelective", e);
        }
    }

    /**
     * Post-processes a raw ({@code FLAG_ZSTD_POOL == 0}) {@code Superblock} in the compressed domain
     * by wrapping its Section 4A (Base MSB) and Section 4B (Tail LSB) slice pools into independent
     * Zstd frames ({@link LcvStreamContainer#FLAG_ZSTD_POOL}) without decoding pixels, Hilbert curves,
     * or Gray-code bitplanes.
     */
    public static byte[] wrapZstdPools(byte[] superblockBytes) {
        return wrapZstdPools(superblockBytes, null);
    }

    /**
     * Stateful epoch-dictionary overload of {@link #wrapZstdPools} ({@code leaf-video-ADR-003}):
     * deduplicates identical active tile MSB slices in {@code O(1)} and compresses against the anchor
     * superblock's epoch dictionary ({@link LcvStreamContainer#FLAG_ZSTD_DICT_POOL}) when {@code encoderState}
     * is provided.
     */
    public static byte[] wrapZstdPools(
        byte[] superblockBytes, LcvStreamContainer.StreamEncoderState encoderState
    ) {
        try {
            LcvStreamContainer.SuperblockHeader header = LcvStreamContainer.readSuperblockHeader(superblockBytes, 0);
            if ((header.flags8() & LcvStreamContainer.FLAG_ZSTD_POOL) != 0 || !LcvStreamContainer.isZstdAvailable()) {
                return Arrays.copyOf(superblockBytes, superblockBytes.length);
            }
            int msbPoolOff = header.msbPoolOffset();
            int msbPoolLen = header.msbPoolCompressedLen32();
            int lsbPoolOff = header.lsbPoolOffset();
            int lsbPoolLen = header.lsbPoolCompressedLen32();
            boolean lsbStripped = (header.flags8() & LcvStreamContainer.FLAG_LSB_STRIPPED) != 0 || lsbPoolLen == 0;

            int dirWords = header.directoryWords();
            int ctuWords = header.ctuDescriptorWords();
            int dirBase = header.directoryOffset();
            int synBase = header.secDedOffset();
            ByteBuffer srcBuf = ByteBuffer.wrap(superblockBytes).order(ByteOrder.BIG_ENDIAN);
            long[] newPointers = new long[dirWords];
            byte[] newSyndromes = new byte[dirWords];
            byte[] dedupPool = new byte[msbPoolLen];
            int dedupPos = 0;
            int hashMask = 2047;
            int[] hashHead = new int[hashMask + 1];
            Arrays.fill(hashHead, -1);
            int[] nextChain = new int[dirWords];
            int[] seenRelOff = new int[dirWords];
            int[] seenLen = new int[dirWords];
            int[] seenHash = new int[dirWords];
            int seenCount = 0;

            for (int i = 0; i < dirWords; i++) {
                long rawPtr = srcBuf.getLong(dirBase + i * 8);
                byte syn = superblockBytes[synBase + i];
                if (i < ctuWords) {
                    newPointers[i] = rawPtr;
                    newSyndromes[i] = syn;
                    continue;
                }
                long healedPtr = LcvPrimitives.healSingleBitFlip64(rawPtr, syn);
                if (healedPtr == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL || LcvStreamContainer.isZeroBodyPointer(healedPtr)) {
                    newPointers[i] = rawPtr;
                    newSyndromes[i] = syn;
                    continue;
                }
                int srcRelOff = LcvStreamContainer.extractRelByteOffset30(healedPtr);
                int msbLen = LcvStreamContainer.extractMsbSliceLen16(healedPtr);
                int lsbLen = lsbStripped ? 0 : LcvStreamContainer.extractLsbSliceLen16(healedPtr);
                int absOff = msbPoolOff + srcRelOff;
                if (absOff < msbPoolOff || msbLen < 0 || absOff + msbLen > msbPoolOff + msbPoolLen) {
                    newPointers[i] = rawPtr;
                    newSyndromes[i] = syn;
                    continue;
                }
                int h = 0x811C9DC5;
                for (int k = 0; k < msbLen; k++) {
                    h = (h ^ (superblockBytes[absOff + k] & 0xFF)) * 0x01000193;
                }
                int bucket = (h ^ (h >>> 16)) & hashMask;
                int matchedRelOff = -1;
                for (int s = hashHead[bucket]; s >= 0; s = nextChain[s]) {
                    if (seenLen[s] == msbLen && seenHash[s] == h) {
                        int candOff = seenRelOff[s];
                        if (Arrays.equals(
                            superblockBytes, absOff, absOff + msbLen,
                            dedupPool, candOff, candOff + msbLen
                        )) {
                            matchedRelOff = candOff;
                            break;
                        }
                    }
                }
                int dstRelOff;
                if (matchedRelOff >= 0) {
                    dstRelOff = matchedRelOff;
                } else {
                    dstRelOff = dedupPos;
                    System.arraycopy(superblockBytes, absOff, dedupPool, dedupPos, msbLen);
                    dedupPos += msbLen;
                    seenRelOff[seenCount] = dstRelOff;
                    seenLen[seenCount] = msbLen;
                    seenHash[seenCount] = h;
                    nextChain[seenCount] = hashHead[bucket];
                    hashHead[bucket] = seenCount;
                    seenCount++;
                }
                long ptr = LcvStreamContainer.makeActiveTilePointer(dstRelOff, msbLen, lsbLen);
                newPointers[i] = ptr;
                newSyndromes[i] = LcvPrimitives.computeHammingSecDed8(ptr);
            }

            byte[] rawMsb = Arrays.copyOf(dedupPool, dedupPos);
            boolean useDictFlag = false;
            byte[] compMsb = LcvStreamContainer.compressZstdFrameWithSizeHeader(rawMsb, 4);
            if (encoderState != null && LcvStreamContainer.isZstdDictAvailable()) {
                if (encoderState.zstdMsbDict == null) {
                    encoderState.zstdMsbDict = LcvStreamContainer.sliceDictTail(rawMsb);
                } else {
                    byte[] dictCand = LcvStreamContainer.compressZstdFrameWithDict(rawMsb, encoderState.zstdMsbDict, 4);
                    if (dictCand.length < compMsb.length) {
                        compMsb = dictCand;
                        useDictFlag = true;
                    }
                }
            }
            byte[] compLsb;
            if (!lsbStripped && lsbPoolLen > 0) {
                byte[] rawLsb = Arrays.copyOfRange(superblockBytes, lsbPoolOff, lsbPoolOff + lsbPoolLen);
                if (useDictFlag && encoderState != null && encoderState.zstdLsbDict != null) {
                    compLsb = LcvStreamContainer.compressZstdFrameWithDict(rawLsb, encoderState.zstdLsbDict, 4);
                } else {
                    compLsb = LcvStreamContainer.compressZstdFrameWithSizeHeader(rawLsb, 4);
                    if (encoderState != null && encoderState.zstdLsbDict == null) {
                        encoderState.zstdLsbDict = LcvStreamContainer.sliceDictTail(rawLsb);
                    }
                }
            } else {
                compLsb = new byte[0];
            }

            int prefixLen = msbPoolOff; // 64B header + pointer directory + SEC-DED syndromes
            byte[] out = new byte[prefixLen + compMsb.length + compLsb.length];
            System.arraycopy(superblockBytes, 0, out, 0, LcvStreamContainer.SUPERBLOCK_HEADER_BYTES);
            ByteBuffer buf = ByteBuffer.wrap(out).order(ByteOrder.BIG_ENDIAN);
            for (int i = 0; i < dirWords; i++) {
                buf.putLong(dirBase + i * 8, newPointers[i]);
                out[synBase + i] = newSyndromes[i];
            }
            System.arraycopy(compMsb, 0, out, prefixLen, compMsb.length);
            if (compLsb.length > 0) {
                System.arraycopy(compLsb, 0, out, prefixLen + compMsb.length, compLsb.length);
            }

            int newFlags = header.flags8() | LcvStreamContainer.FLAG_ZSTD_POOL
                | (useDictFlag ? LcvStreamContainer.FLAG_ZSTD_DICT_POOL : 0);
            buf.put(0x11, (byte) newFlags);
            buf.putInt(0x1C, compMsb.length);
            buf.putInt(0x20, compLsb.length);
            buf.putInt(0x3C, LcvStreamContainer.computeHeaderCrc32(out, 0));
            return out;
        } catch (LcvCorruptTileException e) {
            throw new IllegalArgumentException("Corrupt source Superblock in wrapZstdPools", e);
        }
    }

    /**
     * Applies an out-of-order or in-place editor splice for {@code editedSuperblockBytes} into
     * {@code streamBytes} without shifting downstream disk blocks:
     * <ul>
     *   <li>If an existing slot with matching {@code frameIndex64} can fit {@code editedSuperblockBytes}
     *       exactly or with {@code delta >= 64} bytes of slack, overwrites in-place and frames the freed
     *       gap via {@link LcvStreamContainer#createSlackSuperblockBytes}.</li>
     *   <li>Otherwise marks the old slot {@link LcvStreamContainer#FLAG_SUPERSEDED_OR_SLACK} in-place
     *       (64-byte header update only) and appends {@code editedSuperblockBytes} at {@code EOF}.</li>
     * </ul>
     */
    public static byte[] spliceEditedSuperblockNonTemporal(byte[] streamBytes, byte[] editedSuperblockBytes) {
        try {
            LcvStreamContainer.SuperblockHeader editedHdr =
                LcvStreamContainer.readSuperblockHeader(editedSuperblockBytes, 0);
            long targetFrameIdx = editedHdr.frameIndex64();
            java.util.List<Integer> activeOffsets = LcvStreamContainer.indexValidSuperblocks(streamBytes);
            int matchOffset = -1;
            int matchOldLen = 0;
            long matchPrevOff = 0L;
            for (int off : activeOffsets) {
                LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(streamBytes, off);
                if (hdr.frameIndex64() == targetFrameIdx) {
                    matchOffset = off;
                    matchOldLen = hdr.totalSuperblockBytes();
                    matchPrevOff = hdr.prevSbOffset64();
                    break;
                }
            }

            int newLen = editedSuperblockBytes.length;
            if (matchOffset >= 0) {
                int delta = matchOldLen - newLen;
                if (delta == 0 || delta >= LcvStreamContainer.SUPERBLOCK_HEADER_BYTES) {
                    byte[] out = Arrays.copyOf(streamBytes, streamBytes.length);
                    byte[] patchedNew = Arrays.copyOf(editedSuperblockBytes, newLen);
                    ByteBuffer newBuf = ByteBuffer.wrap(patchedNew).order(ByteOrder.BIG_ENDIAN);
                    newBuf.putLong(0x2C, matchPrevOff);
                    newBuf.putInt(0x3C, LcvStreamContainer.computeHeaderCrc32(patchedNew, 0));
                    System.arraycopy(patchedNew, 0, out, matchOffset, newLen);
                    if (delta >= LcvStreamContainer.SUPERBLOCK_HEADER_BYTES) {
                        byte[] slack = LcvStreamContainer.createSlackSuperblockBytes(delta, matchOffset);
                        System.arraycopy(slack, 0, out, matchOffset + newLen, delta);
                    }
                    return out;
                }
                // Expanded or delta in (0, 64): mark old slot superseded in-place + append at EOF
                byte[] out = Arrays.copyOf(streamBytes, streamBytes.length + newLen);
                ByteBuffer oldBuf = ByteBuffer.wrap(out, matchOffset, LcvStreamContainer.SUPERBLOCK_HEADER_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
                int oldFlags = out[matchOffset + 0x11] & 0xFF;
                out[matchOffset + 0x11] = (byte) (oldFlags | LcvStreamContainer.FLAG_SUPERSEDED_OR_SLACK);
                oldBuf.putInt(matchOffset + 0x3C, LcvStreamContainer.computeHeaderCrc32(out, matchOffset));

                byte[] eofCopy = Arrays.copyOf(editedSuperblockBytes, newLen);
                ByteBuffer eofBuf = ByteBuffer.wrap(eofCopy).order(ByteOrder.BIG_ENDIAN);
                eofBuf.putLong(0x2C, matchOffset);
                eofBuf.putInt(0x3C, LcvStreamContainer.computeHeaderCrc32(eofCopy, 0));
                System.arraycopy(eofCopy, 0, out, streamBytes.length, newLen);
                return out;
            }

            // New frameIndex64 slot: append directly at EOF
            byte[] out = Arrays.copyOf(streamBytes, streamBytes.length + newLen);
            System.arraycopy(editedSuperblockBytes, 0, out, streamBytes.length, newLen);
            return out;
        } catch (LcvCorruptTileException e) {
            throw new IllegalArgumentException("Corrupt Superblock in spliceEditedSuperblockNonTemporal", e);
        }
    }

    /**
     * Conditions each {@code 16 x 16 x K} section of {@code rgbaFrames} in-place for section-adaptive
     * compact/foveated encoding so stationary sections lock onto {@code TEMPORAL_CLEAN_ACK} ({@code 0 B}),
     * flat sections lock onto {@code UNIFORM_INLINE} ({@code 0 B}), discrete UI/HUD/voxel sections lock
     * onto {@code UI_PALETTE_ROARING} ({@code <= 16} centroids), and textured motion sections lock onto
     * causal {@code MED_2D + Temporal} with temporal/spatial noise suppression.
     */
    public static int[] conditionFramesBySection(
        int[] rgbaFrames, int[] prevSbFrames, int width, int height, int depthK,
        int roiTileX, int roiTileY, LcvCubeCodec.DistanceRlePolicy policy
    ) {
        return conditionFramesBySection(
            rgbaFrames, prevSbFrames, width, height, depthK, roiTileX, roiTileY, policy, 3
        );
    }

    /**
     * Split-depth-aware overload of {@link #conditionFramesBySection} that conditions smooth 3D
     * planar ramps to {@code maxU8 == 0} ({@code 0} bitplane bytes) and runs closed-loop {@code MED-2D}
     * conditioning in non-wrapping {@code YCoCg-R} space with asymmetric Luma ({@code s = lsbSplitBits})
     * and Chroma ({@code Co/Cg} {@code g2 == 0} at {@code s = 2}) precision.
     */
    public static int[] conditionFramesBySection(
        int[] rgbaFrames, int[] prevSbFrames, int width, int height, int depthK,
        int roiTileX, int roiTileY, LcvCubeCodec.DistanceRlePolicy policy, int lsbSplitBits
    ) {
        int effSplitBits = (lsbSplitBits >= 1 && lsbSplitBits <= 4) ? lsbSplitBits : 3;
        int[] out = Arrays.copyOf(rgbaFrames, rgbaFrames.length);
        int tilesX = (width + 15) / 16;
        int tilesY = (height + 15) / 16;
        int frameArea = width * height;
        int nSamples = 256 * depthK;
        boolean foveated = (policy != null && policy.losslessPlaneCutoff() > 0);

        int[] cube = new int[nSamples];
        int[] origCube = new int[nSamples];
        int[] prevCube = new int[nSamples];
        int[] snappedCube = new int[nSamples];
        int[] uColors = new int[nSamples];
        int[] uCounts = new int[nSamples];

        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int baseX = tx * 16;
                int baseY = ty * 16;
                int wLim = Math.min(16, width - baseX);
                int hLim = Math.min(16, height - baseY);
                if (wLim <= 0 || hLim <= 0) {
                    continue;
                }

                double dx = tx - roiTileX;
                double dy = ty - roiTileY;
                double radialDist = Math.sqrt(dx * dx + dy * dy);
                boolean isFocalRoi = foveated && policy.isLosslessAt(radialDist);
                int mergeGap = foveated ? policy.maxMergeGap(2, radialDist) : 0;
                int boost = (foveated && mergeGap > 0) ? Math.min(10, 3 + mergeGap * 2) : 0;

                int unifTol = isFocalRoi ? 7 : (9 + (boost >>> 1));
                int ackMeanTol = isFocalRoi ? 8 : (12 + (boost >>> 2));
                int ackMaxTol = isFocalRoi ? 20 : (30 + (boost >>> 1));
                int tempSnapTol = isFocalRoi ? 7 : (10 + (boost >>> 1));
                int spatialSnapTol = isFocalRoi ? 4 : (6 + (boost >>> 1));
                int qStep = 8;
                int palMaxTol = isFocalRoi ? 8 : (20 + boost);
                int palMeanTol = isFocalRoi ? 4 : (7 + (boost >>> 1));

                long sumR = 0L;
                long sumG = 0L;
                long sumB = 0L;
                long sumA = 0L;
                int count = 0;
                boolean hasTranslucentAlpha = false;
                for (int t = 0; t < depthK; t++) {
                    int fBase = t * frameArea;
                    int cBase = t << 8;
                    for (int ly = 0; ly < hLim; ly++) {
                        int rowOff = fBase + (baseY + ly) * width + baseX;
                        int cRow = cBase | (ly << 4);
                        for (int lx = 0; lx < wLim; lx++) {
                            int c = out[rowOff + lx];
                            cube[cRow | lx] = c;
                            origCube[cRow | lx] = c;
                            if (prevSbFrames != null && prevSbFrames.length == out.length) {
                                prevCube[cRow | lx] = prevSbFrames[rowOff + lx];
                            }
                            int a = c & 0xFF;
                            if ((a > 0 && a < 255) || (a == 0 && c != 0)) {
                                hasTranslucentAlpha = true;
                            }
                            sumR += (c >>> 24) & 0xFF;
                            sumG += (c >>> 16) & 0xFF;
                            sumB += (c >>> 8) & 0xFF;
                            sumA += a;
                            count++;
                        }
                    }
                }
                if (hasTranslucentAlpha) {
                    continue;
                }

                // 1. Check UNIFORM_INLINE section candidate (0 bytes body)
                int meanR = (int) Math.round((double) sumR / count);
                int meanG = (int) Math.round((double) sumG / count);
                int meanB = (int) Math.round((double) sumB / count);
                int meanA = (int) Math.round((double) sumA / count);
                int maxUnifDiff = 0;
                for (int t = 0; t < depthK; t++) {
                    int cBase = t << 8;
                    for (int ly = 0; ly < hLim; ly++) {
                        int cRow = cBase | (ly << 4);
                        for (int lx = 0; lx < wLim; lx++) {
                            int c = cube[cRow | lx];
                            int d = Math.max(
                                Math.max(Math.abs(((c >>> 24) & 0xFF) - meanR), Math.abs(((c >>> 16) & 0xFF) - meanG)),
                                Math.max(Math.abs(((c >>> 8) & 0xFF) - meanB), Math.abs((c & 0xFF) - meanA))
                            );
                            if (d > maxUnifDiff) {
                                maxUnifDiff = d;
                            }
                        }
                    }
                }
                if (maxUnifDiff <= unifTol) {
                    int meanRgba = (meanR << 24) | (meanG << 16) | (meanB << 8) | meanA;
                    for (int t = 0; t < depthK; t++) {
                        int fBase = t * frameArea;
                        for (int ly = 0; ly < hLim; ly++) {
                            int rowOff = fBase + (baseY + ly) * width + baseX;
                            for (int lx = 0; lx < wLim; lx++) {
                                out[rowOff + lx] = meanRgba;
                            }
                        }
                    }
                    continue;
                }

                // 2. Check TEMPORAL_CLEAN_ACK section candidate vs previous superblock (0 bytes body, including (dx, dy) pans)
                if (prevSbFrames != null && prevSbFrames.length == out.length) {
                    int maxOutliers = Math.max(2, count / 48);
                    long diffSum = 0L;
                    int maxAckDiff = 0;
                    int outliers = 0;
                    for (int t = 0; t < depthK; t++) {
                        int cBase = t << 8;
                        for (int ly = 0; ly < hLim; ly++) {
                            int cRow = cBase | (ly << 4);
                            for (int lx = 0; lx < wLim; lx++) {
                                int c = cube[cRow | lx];
                                int p = prevCube[cRow | lx];
                                int dr = Math.abs(((c >>> 24) & 0xFF) - ((p >>> 24) & 0xFF));
                                int dg = Math.abs(((c >>> 16) & 0xFF) - ((p >>> 16) & 0xFF));
                                int db = Math.abs(((c >>> 8) & 0xFF) - ((p >>> 8) & 0xFF));
                                int da = Math.abs((c & 0xFF) - (p & 0xFF));
                                diffSum += (dr + dg + db + da);
                                int md = Math.max(Math.max(dr, dg), Math.max(db, da));
                                if (md > ackMaxTol) {
                                    outliers++;
                                }
                                if (md > maxAckDiff) {
                                    maxAckDiff = md;
                                }
                            }
                        }
                    }
                    if (outliers <= maxOutliers && maxAckDiff <= ackMaxTol + 16 && diffSum <= (long) count * 4L * ackMeanTol) {
                        for (int t = 0; t < depthK; t++) {
                            int fBase = t * frameArea;
                            int cBase = t << 8;
                            for (int ly = 0; ly < hLim; ly++) {
                                int rowOff = fBase + (baseY + ly) * width + baseX;
                                int cRow = cBase | (ly << 4);
                                for (int lx = 0; lx < wLim; lx++) {
                                    out[rowOff + lx] = prevCube[cRow | lx];
                                }
                            }
                        }
                        continue;
                    }

                    // 2B. Motion-shifted cross-tile clean-ack search (sDx, sDy in [-8, +7]^2) over prevSbFrames
                    if (wLim == 16 && hLim == 16) {
                        int bestShiftDx = 0;
                        int bestShiftDy = 0;
                        long bestShiftSum = (long) count * 4L * ackMeanTol + 1L;
                        int[] shiftGrid = {-6, -4, -2, -1, 0, 1, 2, 4, 6};
                        for (int sDy : shiftGrid) {
                            int srcBaseY = baseY - sDy;
                            if (srcBaseY < 0 || srcBaseY + 16 > height) {
                                continue;
                            }
                            for (int sDx : shiftGrid) {
                                if (sDx == 0 && sDy == 0) {
                                    continue;
                                }
                                int srcBaseX = baseX - sDx;
                                if (srcBaseX < 0 || srcBaseX + 16 > width) {
                                    continue;
                                }
                                long sSum = 0L;
                                int sOutliers = 0;
                                int sMax = 0;
                                outerShift:
                                for (int t = 0; t < depthK; t++) {
                                    int fBase = t * frameArea;
                                    int cBase = t << 8;
                                    for (int ly = 0; ly < 16; ly++) {
                                        int srcRow = fBase + (srcBaseY + ly) * width + srcBaseX;
                                        int cRow = cBase | (ly << 4);
                                        for (int lx = 0; lx < 16; lx++) {
                                            int c = cube[cRow | lx];
                                            int p = prevSbFrames[srcRow + lx];
                                            int dr = Math.abs(((c >>> 24) & 0xFF) - ((p >>> 24) & 0xFF));
                                            int dg = Math.abs(((c >>> 16) & 0xFF) - ((p >>> 16) & 0xFF));
                                            int db = Math.abs(((c >>> 8) & 0xFF) - ((p >>> 8) & 0xFF));
                                            int da = Math.abs((c & 0xFF) - (p & 0xFF));
                                            sSum += (dr + dg + db + da);
                                            int md = Math.max(Math.max(dr, dg), Math.max(db, da));
                                            if (md > ackMaxTol) {
                                                sOutliers++;
                                                if (sOutliers > maxOutliers) {
                                                    sSum = Long.MAX_VALUE;
                                                    break outerShift;
                                                }
                                            }
                                            if (md > sMax) {
                                                sMax = md;
                                            }
                                        }
                                        if (sSum >= bestShiftSum) {
                                            break outerShift;
                                        }
                                    }
                                }
                                if (sSum < bestShiftSum && sMax <= ackMaxTol + 16) {
                                    bestShiftSum = sSum;
                                    bestShiftDx = sDx;
                                    bestShiftDy = sDy;
                                }
                            }
                        }
                        if (bestShiftDx != 0 || bestShiftDy != 0) {
                            int srcBaseX = baseX - bestShiftDx;
                            int srcBaseY = baseY - bestShiftDy;
                            for (int t = 0; t < depthK; t++) {
                                int fBase = t * frameArea;
                                for (int ly = 0; ly < 16; ly++) {
                                    int dstRow = fBase + (baseY + ly) * width + baseX;
                                    int srcRow = fBase + (srcBaseY + ly) * width + srcBaseX;
                                    System.arraycopy(prevSbFrames, srcRow, out, dstRow, 16);
                                }
                            }
                            continue;
                        }
                    }
                }

                // 2C. Intra-cube stationary frame lock (if all t=1..K-1 stay within ackMeanTol of t=0, lock to t=0)
                if (depthK > 1) {
                    long t0DiffSum = 0L;
                    int t0Outliers = 0;
                    int t0MaxDiff = 0;
                    int maxT0Outliers = Math.max(2, count / 40);
                    for (int t = 1; t < depthK; t++) {
                        int cBase = t << 8;
                        for (int ly = 0; ly < hLim; ly++) {
                            int cRow = cBase | (ly << 4);
                            int r0 = ly << 4;
                            for (int lx = 0; lx < wLim; lx++) {
                                int c = cube[cRow | lx];
                                int p0 = cube[r0 | lx];
                                int dr = Math.abs(((c >>> 24) & 0xFF) - ((p0 >>> 24) & 0xFF));
                                int dg = Math.abs(((c >>> 16) & 0xFF) - ((p0 >>> 16) & 0xFF));
                                int db = Math.abs(((c >>> 8) & 0xFF) - ((p0 >>> 8) & 0xFF));
                                int da = Math.abs((c & 0xFF) - (p0 & 0xFF));
                                t0DiffSum += (dr + dg + db + da);
                                int md = Math.max(Math.max(dr, dg), Math.max(db, da));
                                if (md > ackMaxTol) {
                                    t0Outliers++;
                                }
                                if (md > t0MaxDiff) {
                                    t0MaxDiff = md;
                                }
                            }
                        }
                    }
                    if (t0Outliers <= maxT0Outliers && t0MaxDiff <= ackMaxTol + 16
                        && t0DiffSum <= (long) (count - 256) * 4L * ackMeanTol) {
                        if (foveated) {
                            for (int ly = 0; ly < hLim; ly++) {
                                int r0 = ly << 4;
                                for (int lx = 0; lx < wLim; lx++) {
                                    int idx0 = r0 | lx;
                                    int sr = 0;
                                    int sg = 0;
                                    int sb = 0;
                                    int sa = 0;
                                    for (int t = 0; t < depthK; t++) {
                                        int ct = origCube[(t << 8) | idx0];
                                        sr += (ct >>> 24) & 0xFF;
                                        sg += (ct >>> 16) & 0xFF;
                                        sb += (ct >>> 8) & 0xFF;
                                        sa += ct & 0xFF;
                                    }
                                    int mr = (sr + (depthK >>> 1)) / depthK;
                                    int mg = (sg + (depthK >>> 1)) / depthK;
                                    int mb = (sb + (depthK >>> 1)) / depthK;
                                    int ma = (sa + (depthK >>> 1)) / depthK;
                                    int avgC = (mr << 24) | (mg << 16) | (mb << 8) | ma;
                                    for (int t = 0; t < depthK; t++) {
                                        cube[(t << 8) | idx0] = avgC;
                                    }
                                }
                            }
                        } else {
                            for (int t = 1; t < depthK; t++) {
                                System.arraycopy(cube, 0, cube, t << 8, 256);
                            }
                        }
                    }
                }

                // 2.5. Smooth 3D Planar Natural Ramp Conditioning (locks onto PLANAR_3D_MV with maxU8 == 0)
                if (!hasTranslucentAlpha && wLim == 16 && hLim == 16
                    && tryConditionSmoothPlanarRampCube(origCube, cube, depthK, isFocalRoi ? 4 : (6 + (boost >>> 1)))) {
                    for (int t = 0; t < depthK; t++) {
                        int fBase = t * frameArea;
                        int cBase = t << 8;
                        for (int ly = 0; ly < hLim; ly++) {
                            int rowOff = fBase + (baseY + ly) * width + baseX;
                            int cRow = cBase | (ly << 4);
                            for (int lx = 0; lx < wLim; lx++) {
                                out[rowOff + lx] = cube[cRow | lx];
                            }
                        }
                    }
                    continue;
                }

                // 3. Per-frame trajectory (dx_t, dy_t) + causal MED_2D hysteretic deadzone
                for (int t = 0; t < depthK; t++) {
                    int cBase = t << 8;
                    int pBase = (t - 1) << 8;
                    int bestDx = 0;
                    int bestDy = 0;
                    if (t >= 1) {
                        long bestSad = Long.MAX_VALUE;
                        int[] candSteps = {-4, -2, -1, 0, 1, 2, 4};
                        for (int gx : candSteps) {
                            for (int gy : candSteps) {
                                long sad = 0L;
                                int cnt = 0;
                                for (int ly = 2; ly < hLim - 2; ly += 2) {
                                    int yRef = ly - gy;
                                    if (yRef < 0 || yRef >= hLim) {
                                        continue;
                                    }
                                    for (int lx = 2; lx < wLim - 2; lx += 2) {
                                        int xRef = lx - gx;
                                        if (xRef < 0 || xRef >= wLim) {
                                            continue;
                                        }
                                        int c0 = cube[cBase | (ly << 4) | lx];
                                        int p0 = cube[pBase | (yRef << 4) | xRef];
                                        sad += Math.abs(((c0 >>> 24) & 0xFF) - ((p0 >>> 24) & 0xFF))
                                            + Math.abs(((c0 >>> 16) & 0xFF) - ((p0 >>> 16) & 0xFF))
                                            + Math.abs(((c0 >>> 8) & 0xFF) - ((p0 >>> 8) & 0xFF));
                                        cnt++;
                                    }
                                }
                                long normSad = cnt > 0 ? (sad * 36L / cnt) : Long.MAX_VALUE;
                                if ((gx == 0 && gy == 0 && normSad < bestSad) || (normSad * 100L < bestSad * 93L)) {
                                    bestSad = normSad;
                                    bestDx = gx;
                                    bestDy = gy;
                                }
                            }
                        }
                    }

                    int prevSbLastBase = (depthK - 1) << 8;
                    for (int ly = 0; ly < hLim; ly++) {
                        int cRow = cBase | (ly << 4);
                        for (int lx = 0; lx < wLim; lx++) {
                            int c = cube[cRow | lx];
                            if (t == 0 && prevSbFrames != null && prevSbFrames.length == out.length) {
                                int pLast = prevCube[prevSbLastBase | (ly << 4) | lx];
                                if (maxChannelDiff(c, pLast) <= tempSnapTol) {
                                    cube[cRow | lx] = pLast;
                                    continue;
                                }
                            }
                            if (t >= 1) {
                                int p0 = cube[pBase | (ly << 4) | lx];
                                if (maxChannelDiff(c, p0) <= tempSnapTol) {
                                    cube[cRow | lx] = p0;
                                    continue;
                                }
                                int xRef = lx - bestDx;
                                int yRef = ly - bestDy;
                                if ((bestDx != 0 || bestDy != 0) && xRef >= 0 && xRef < wLim && yRef >= 0 && yRef < hLim) {
                                    int pMv = cube[pBase | (yRef << 4) | xRef];
                                    if (maxChannelDiff(c, pMv) <= tempSnapTol) {
                                        cube[cRow | lx] = pMv;
                                        continue;
                                    }
                                }
                            }
                            if (lx >= 1) {
                                int left = cube[cRow | (lx - 1)];
                                if (maxChannelDiff(c, left) <= spatialSnapTol) {
                                    cube[cRow | lx] = left;
                                    continue;
                                }
                            }
                            if (ly >= 1) {
                                int up = cube[cBase | ((ly - 1) << 4) | lx];
                                if (maxChannelDiff(c, up) <= spatialSnapTol) {
                                    cube[cRow | lx] = up;
                                }
                            }
                        }
                    }
                }

                // 3B. 2-Level 2D Haar wavelet sub-band conditioning on continuous/scaled/foliage sections before palette/MED fit
                if (!hasTranslucentAlpha && wLim == 16 && hLim == 16 && depthK > 1) {
                    int prelimUSize = 0;
                    outerPrelim:
                    for (int i = 0; i < nSamples; i++) {
                        int c = cube[i];
                        for (int u = 0; u < prelimUSize; u++) {
                            if (uColors[u] == c) {
                                continue outerPrelim;
                            }
                        }
                        if (prelimUSize < 17) {
                            uColors[prelimUSize++] = c;
                        } else {
                            break;
                        }
                    }
                    if (prelimUSize > 16) {
                        LcvPrimitives.haar2LevelSubbandConditionCube(
                            cube, depthK,
                            isFocalRoi ? 6 : (12 + (boost >>> 1)),
                            isFocalRoi ? 8 : (18 + boost)
                        );
                    }
                }

                // 4. Top-16 UI_PALETTE_ROARING dyadic-aligned candidate
                int uSize = 0;
                int stepShift = (qStep == 16) ? 4 : 3;
                int stepMid = 1 << (stepShift - 1);
                for (int t = 0; t < depthK; t++) {
                    int cBase = t << 8;
                    for (int ly = 0; ly < hLim; ly++) {
                        int cRow = cBase | (ly << 4);
                        for (int lx = 0; lx < wLim; lx++) {
                            int idx = cRow | lx;
                            int c = cube[idx];
                            int qr = (((c >>> 24) & 0xFF) & ~(qStep - 1)) | stepMid;
                            int qg = (((c >>> 16) & 0xFF) & ~(qStep - 1)) | stepMid;
                            int qb = (((c >>> 8) & 0xFF) & ~(qStep - 1)) | stepMid;
                            int qa = c & 0xFF;
                            int qc = (c == 0) ? 0 : ((qr << 24) | (qg << 16) | (qb << 8) | qa);
                            snappedCube[idx] = qc;
                            int found = -1;
                            for (int u = 0; u < uSize; u++) {
                                if (uColors[u] == qc) {
                                    found = u;
                                    break;
                                }
                            }
                            if (found >= 0) {
                                uCounts[found]++;
                            } else {
                                uColors[uSize] = qc;
                                uCounts[uSize] = 1;
                                uSize++;
                            }
                        }
                    }
                }

                boolean usePaletteSnap = false;
                if (!hasTranslucentAlpha && uSize <= 16) {
                    usePaletteSnap = true;
                } else if (!hasTranslucentAlpha) {
                    if (foveated && !isFocalRoi) {
                        uSize = 0;
                        for (int t = 0; t < depthK; t++) {
                            int cBase = t << 8;
                            for (int ly = 0; ly < hLim; ly++) {
                                int cRow = cBase | (ly << 4);
                                for (int lx = 0; lx < wLim; lx++) {
                                    int idx = cRow | lx;
                                    int c = cube[idx];
                                    int qr = (((c >>> 24) & 0xFF) & ~15) | 8;
                                    int qg = (((c >>> 16) & 0xFF) & ~15) | 8;
                                    int qb = (((c >>> 8) & 0xFF) & ~15) | 8;
                                    int qa = c & 0xFF;
                                    int qc = (c == 0) ? 0 : ((qr << 24) | (qg << 16) | (qb << 8) | qa);
                                    int found = -1;
                                    for (int u = 0; u < uSize; u++) {
                                        if (uColors[u] == qc) {
                                            found = u;
                                            break;
                                        }
                                    }
                                    if (found >= 0) {
                                        uCounts[found]++;
                                    } else {
                                        uColors[uSize] = qc;
                                        uCounts[uSize] = 1;
                                        uSize++;
                                    }
                                }
                            }
                        }
                    }
                    // Sort all unique quantized colors by descending frequency
                    for (int i = 0; i < uSize - 1; i++) {
                        int bestIdx = i;
                        for (int j = i + 1; j < uSize; j++) {
                            if (uCounts[j] > uCounts[bestIdx]) {
                                bestIdx = j;
                            }
                        }
                        int tc = uColors[i];
                        uColors[i] = uColors[bestIdx];
                        uColors[bestIdx] = tc;
                        int tn = uCounts[i];
                        uCounts[i] = uCounts[bestIdx];
                        uCounts[bestIdx] = tn;
                    }
                    int[] pal16 = new int[16];
                    int palCount = 0;
                    int minSep = 12 + (boost >>> 1);
                    for (int i = 0; i < uSize && palCount < 16; i++) {
                        int cand = uColors[i];
                        int cr = (cand >>> 24) & 0xFF;
                        int cg = (cand >>> 16) & 0xFF;
                        int cb = (cand >>> 8) & 0xFF;
                        boolean tooClose = false;
                        for (int p = 0; p < palCount; p++) {
                            int pc = pal16[p];
                            int d = Math.max(
                                Math.abs(cr - ((pc >>> 24) & 0xFF)),
                                Math.max(Math.abs(cg - ((pc >>> 16) & 0xFF)), Math.abs(cb - ((pc >>> 8) & 0xFF)))
                            );
                            if (d <= minSep) {
                                tooClose = true;
                                break;
                            }
                        }
                        if (!tooClose) {
                            pal16[palCount++] = cand;
                        }
                    }
                    for (int i = 0; i < uSize && palCount < 16; i++) {
                        int cand = uColors[i];
                        boolean exists = false;
                        for (int p = 0; p < palCount; p++) {
                            if (pal16[p] == cand) {
                                exists = true;
                                break;
                            }
                        }
                        if (!exists) {
                            pal16[palCount++] = cand;
                        }
                    }

                    int maxPalErr = 0;
                    int palOutliers = 0;
                    int maxPalOutliers = Math.max(2, count / 32);
                    long sumPalErr = 0L;
                    for (int t = 0; t < depthK; t++) {
                        int cBase = t << 8;
                        for (int ly = 0; ly < hLim; ly++) {
                            int cRow = cBase | (ly << 4);
                            for (int lx = 0; lx < wLim; lx++) {
                                int idx = cRow | lx;
                                int origC = cube[idx];
                                int or = (origC >>> 24) & 0xFF;
                                int og = (origC >>> 16) & 0xFF;
                                int ob = (origC >>> 8) & 0xFF;
                                int oa = origC & 0xFF;
                                int bestCol = pal16[0];
                                int bestDist = Integer.MAX_VALUE;
                                for (int p = 0; p < palCount; p++) {
                                    int pc = pal16[p];
                                    int d = Math.max(
                                        Math.max(Math.abs(or - ((pc >>> 24) & 0xFF)), Math.abs(og - ((pc >>> 16) & 0xFF))),
                                        Math.max(Math.abs(ob - ((pc >>> 8) & 0xFF)), Math.abs(oa - (pc & 0xFF)))
                                    );
                                    if (d < bestDist) {
                                        bestDist = d;
                                        bestCol = pc;
                                        if (d == 0) {
                                            break;
                                        }
                                    }
                                }
                                snappedCube[idx] = bestCol;
                                if (bestDist > palMaxTol) {
                                    palOutliers++;
                                }
                                if (bestDist > maxPalErr) {
                                    maxPalErr = bestDist;
                                }
                                sumPalErr += bestDist;
                            }
                        }
                    }
                    if (palOutliers <= maxPalOutliers && maxPalErr <= palMaxTol + 16
                        && sumPalErr <= (long) count * palMeanTol) {
                        usePaletteSnap = true;
                        if (foveated && !isFocalRoi && palCount > 0) {
                            int[] cSumR = new int[16];
                            int[] cSumG = new int[16];
                            int[] cSumB = new int[16];
                            int[] cSumA = new int[16];
                            int[] cCnt = new int[16];
                            for (int t = 0; t < depthK; t++) {
                                int cBase = t << 8;
                                for (int ly = 0; ly < hLim; ly++) {
                                    int cRow = cBase | (ly << 4);
                                    for (int lx = 0; lx < wLim; lx++) {
                                        int idx = cRow | lx;
                                        int sc = snappedCube[idx];
                                        for (int p = 0; p < palCount; p++) {
                                            if (pal16[p] == sc) {
                                                int tc = origCube[idx];
                                                cSumR[p] += (tc >>> 24) & 0xFF;
                                                cSumG[p] += (tc >>> 16) & 0xFF;
                                                cSumB[p] += (tc >>> 8) & 0xFF;
                                                cSumA[p] += tc & 0xFF;
                                                cCnt[p]++;
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                            int[] refinedPal = new int[16];
                            for (int p = 0; p < palCount; p++) {
                                if (cCnt[p] > 0) {
                                    int mr = (((cSumR[p] + (cCnt[p] >>> 1)) / cCnt[p]) & ~7) | 4;
                                    int mg = (((cSumG[p] + (cCnt[p] >>> 1)) / cCnt[p]) & ~7) | 4;
                                    int mb = (((cSumB[p] + (cCnt[p] >>> 1)) / cCnt[p]) & ~7) | 4;
                                    int ma = (cSumA[p] + (cCnt[p] >>> 1)) / cCnt[p];
                                    refinedPal[p] = (mr << 24) | (mg << 16) | (mb << 8) | ma;
                                } else {
                                    refinedPal[p] = pal16[p];
                                }
                            }
                            for (int t = 0; t < depthK; t++) {
                                int cBase = t << 8;
                                for (int ly = 0; ly < hLim; ly++) {
                                    int cRow = cBase | (ly << 4);
                                    for (int lx = 0; lx < wLim; lx++) {
                                        int idx = cRow | lx;
                                        int sc = snappedCube[idx];
                                        for (int p = 0; p < palCount; p++) {
                                            if (pal16[p] == sc) {
                                                snappedCube[idx] = refinedPal[p];
                                                break;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (isFocalRoi && usePaletteSnap) {
                    outerPalDrift:
                    for (int t = 0; t < depthK; t++) {
                        int fBase = t * frameArea;
                        int cBase = t << 8;
                        for (int ly = 0; ly < hLim; ly++) {
                            int rowOff = fBase + (baseY + ly) * width + baseX;
                            int cRow = cBase | (ly << 4);
                            for (int lx = 0; lx < wLim; lx++) {
                                if (maxChannelDiff(out[rowOff + lx], snappedCube[cRow | lx]) > 8) {
                                    usePaletteSnap = false;
                                    break outerPalDrift;
                                }
                            }
                        }
                    }
                }
                if (!usePaletteSnap && wLim == 16 && hLim == 16 && depthK > 1) {
                    conditionCubeClosedLoopMed2d(cube, origCube, depthK, boost, foveated, effSplitBits);
                }

                int[] chosen = usePaletteSnap ? snappedCube : cube;
                for (int t = 0; t < depthK; t++) {
                    int fBase = t * frameArea;
                    int cBase = t << 8;
                    for (int ly = 0; ly < hLim; ly++) {
                        int rowOff = fBase + (baseY + ly) * width + baseX;
                        int cRow = cBase | (ly << 4);
                        for (int lx = 0; lx < wLim; lx++) {
                            int origRgba = out[rowOff + lx];
                            int candRgba = chosen[cRow | lx];
                            if (isFocalRoi && !usePaletteSnap && maxChannelDiff(origRgba, candRgba) > 8) {
                                candRgba = ((origRgba & 0xF8F8F800) | 0x04040400) | (origRgba & 0xFF);
                            }
                            out[rowOff + lx] = candRgba;
                        }
                    }
                }
            }
        }
        return out;
    }

    private static int maxChannelDiff(int rgbaA, int rgbaB) {
        int dr = Math.abs(((rgbaA >>> 24) & 0xFF) - ((rgbaB >>> 24) & 0xFF));
        int dg = Math.abs(((rgbaA >>> 16) & 0xFF) - ((rgbaB >>> 16) & 0xFF));
        int db = Math.abs(((rgbaA >>> 8) & 0xFF) - ((rgbaB >>> 8) & 0xFF));
        int da = Math.abs((rgbaA & 0xFF) - (rgbaB & 0xFF));
        return Math.max(Math.max(dr, dg), Math.max(db, da));
    }

    /**
     * Checks whether a {@code 16 x 16 x K} opaque tile fits a smooth 3D linear planar ramp across
     * {@code R, G, B} with residual error at most {@code maxResidualTol}, and if so replaces {@code dstCube}
     * with the exact noise-free 8-bit planar ramp so {@code DELTA_SUBMODE_PLANAR_3D_MV} encodes with
     * {@code maxU8 == 0} ({@code 0} bitplane bytes) and reconstructs without stair-stepping.
     */
    private static boolean tryConditionSmoothPlanarRampCube(
        int[] origCube, int[] dstCube, int depthK, int maxResidualTol
    ) {
        int nSamples = 256 * depthK;
        int[] sxArr = new int[3];
        int[] syArr = new int[3];
        int[] stArr = new int[3];
        int[] baseArr = new int[3];
        boolean anyNonTrivialSpan = false;

        for (int c = 0; c < 3; c++) {
            int shift = (3 - c) * 8;
            long sumV = 0L;
            long sumVx = 0L;
            long sumVy = 0L;
            long sumVt = 0L;
            int vMin = 255;
            int vMax = 0;
            for (int lt = 0; lt < depthK; lt++) {
                int tBase = lt << 8;
                for (int ly = 0; ly < 16; ly++) {
                    int rowBase = tBase | (ly << 4);
                    for (int lx = 0; lx < 16; lx++) {
                        int v = (origCube[rowBase | lx] >>> shift) & 0xFF;
                        sumV += v;
                        sumVx += (long) v * lx;
                        sumVy += (long) v * ly;
                        sumVt += (long) v * lt;
                        if (v < vMin) {
                            vMin = v;
                        }
                        if (v > vMax) {
                            vMax = v;
                        }
                    }
                }
            }
            if (vMax - vMin >= 16) {
                anyNonTrivialSpan = true;
            }
            int sxInit = (int) Math.round((1024.0 * (2.0 * sumVx - 15.0 * sumV)) / (10880.0 * depthK));
            int syInit = (int) Math.round((1024.0 * (2.0 * sumVy - 15.0 * sumV)) / (10880.0 * depthK));
            int stInit = (depthK > 1)
                ? (int) Math.round((1024.0 * (2.0 * sumVt - (depthK - 1.0) * sumV)) / (256.0 * depthK * (depthK * depthK - 1.0) / 6.0))
                : 0;
            int sx = Math.max(-16384, Math.min(16384, (int) Math.round(sxInit / 256.0) * 256));
            int sy = Math.max(-16384, Math.min(16384, (int) Math.round(syInit / 256.0) * 256));
            int st = Math.max(-16384, Math.min(16384, (int) Math.round(stInit / 512.0) * 512));

            int minErr = Integer.MAX_VALUE;
            int maxErr = Integer.MIN_VALUE;
            long sumErr = 0L;
            for (int lt = 0; lt < depthK; lt++) {
                int tBase = lt << 8;
                for (int ly = 0; ly < 16; ly++) {
                    int rowBase = tBase | (ly << 4);
                    for (int lx = 0; lx < 16; lx++) {
                        int v = (origCube[rowBase | lx] >>> shift) & 0xFF;
                        int pred = ((sx * lx + sy * ly + st * lt) >> 2) >> 8;
                        int err = v - pred;
                        sumErr += err;
                        if (err < minErr) {
                            minErr = err;
                        }
                        if (err > maxErr) {
                            maxErr = err;
                        }
                    }
                }
            }
            if (maxErr - minErr > maxResidualTol * 2) {
                return false;
            }
            int meanErr = (int) Math.round((double) sumErr / nSamples);
            if (meanErr < -64 || meanErr > 191) {
                return false;
            }
            for (int lt = 0; lt < depthK; lt++) {
                int tBase = lt << 8;
                for (int ly = 0; ly < 16; ly++) {
                    int rowBase = tBase | (ly << 4);
                    for (int lx = 0; lx < 16; lx++) {
                        int v = (origCube[rowBase | lx] >>> shift) & 0xFF;
                        int pred = (((sx * lx + sy * ly + st * lt) >> 2) >> 8) + meanErr;
                        if (pred < 0 || pred > 255 || Math.abs(v - pred) > maxResidualTol) {
                            return false;
                        }
                    }
                }
            }
            sxArr[c] = sx;
            syArr[c] = sy;
            stArr[c] = st;
            baseArr[c] = meanErr;
        }
        if (!anyNonTrivialSpan) {
            return false;
        }
        for (int lt = 0; lt < depthK; lt++) {
            int tBase = lt << 8;
            for (int ly = 0; ly < 16; ly++) {
                int rowBase = tBase | (ly << 4);
                for (int lx = 0; lx < 16; lx++) {
                    int idx = rowBase | lx;
                    int r = (((sxArr[0] * lx + syArr[0] * ly + stArr[0] * lt) >> 2) >> 8) + baseArr[0];
                    int g = (((sxArr[1] * lx + syArr[1] * ly + stArr[1] * lt) >> 2) >> 8) + baseArr[1];
                    int b = (((sxArr[2] * lx + syArr[2] * ly + stArr[2] * lt) >> 2) >> 8) + baseArr[2];
                    int a = origCube[idx] & 0xFF;
                    dstCube[idx] = (r << 24) | (g << 16) | (b << 8) | a;
                }
            }
        }
        return true;
    }

    private static boolean isTileYCoCgNonWrappingSafe(int[] cube, int[] origCube, int nSamples) {
        for (int i = 0; i < nSamples; i++) {
            for (int px : new int[]{cube[i], origCube[i]}) {
                int r = (px >>> 24) & 0xFF;
                int g = (px >>> 16) & 0xFF;
                int b = (px >>> 8) & 0xFF;
                int co = r - b;
                int t = b + (co >> 1);
                int cg = g - t;
                if (r < 8 || r > 247 || g < 8 || g > 247 || b < 8 || b > 247
                    || Math.abs(co) > 112 || Math.abs(cg) > 112) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Runs a closed-loop {@code MED_2D + Temporal} MSB deadzone pass over {@code cube}
     * using the exact trajectory estimator ({@link LcvCubeCodec#estimateFrameStepVelocity}) and causal
     * predictor ({@link LcvCubeCodec#predictMed2dSample}) of {@link LcvCubeCodec}. When the tile is
     * non-wrapping in {@code YCoCg-R}, operates directly in {@code YCoCg-R} space with asymmetric
     * Luma ({@code s = lsbSplitBits}) and Chroma ({@code Co/Cg} {@code s_chroma = min(4, lsbSplitBits + 1)}
     * with Gray plane {@code g2 == 0}) precision to eliminate {@code RGB -> YCoCg-R} carry leakage.
     */
    private static void conditionCubeClosedLoopMed2d(
        int[] cube, int[] origCube, int depthK, int boost, boolean foveated, int lsbSplitBits
    ) {
        int nSamples = 256 * depthK;
        int[] condBackup = Arrays.copyOf(cube, nSamples);
        int packedMv8 = LcvPrimitives.estimateTileVelocity1Pass(cube, depthK);
        int candVx = LcvPrimitives.unpackVelocityX(packedMv8);
        int candVy = LcvPrimitives.unpackVelocityY(packedMv8);
        int[] packedSteps = new int[depthK];
        boolean canUseHalfPel = true;
        for (int t = 1; t < depthK; t++) {
            int stepMv = LcvCubeCodec.estimateFrameStepVelocity(cube, t, candVx, candVy);
            packedSteps[t] = stepMv;
            int dx = LcvPrimitives.unpackVelocityX(stepMv);
            int dy = LcvPrimitives.unpackVelocityY(stepMv);
            if (dx < -4 || dx > 3 || dy < -4 || dy > 3) {
                canUseHalfPel = false;
            }
        }
        boolean useHalfPelMv = false;
        if (canUseHalfPel && depthK > 1) {
            int[] halfSteps = new int[depthK];
            boolean anyFractional = false;
            for (int t = 1; t < depthK; t++) {
                int hMv = LcvCubeCodec.estimateFrameStepHalfPelVelocity(cube, t, packedSteps[t]);
                halfSteps[t] = hMv;
                if ((LcvPrimitives.unpackVelocityX(hMv) & 1) != 0
                    || (LcvPrimitives.unpackVelocityY(hMv) & 1) != 0) {
                    anyFractional = true;
                }
            }
            if (anyFractional) {
                useHalfPelMv = true;
                System.arraycopy(halfSteps, 0, packedSteps, 0, depthK);
            }
        }

        boolean useYCoCg = isTileYCoCgNonWrappingSafe(cube, origCube, nSamples);
        byte[] mArr = new byte[nSamples];
        byte[] lArr = new byte[nSamples];
        int spatialTol = 9 + (boost >>> 1);
        int temporalTol = 12 + boost;
        int maxOrigTol = 12 + boost;

        for (int pass = 0; pass < 2; pass++) {
            if (pass == 1) {
                System.arraycopy(condBackup, 0, cube, 0, nSamples);
            }
            boolean hasMv = false;
            for (int t = 1; t < depthK; t++) {
                if (packedSteps[t] != 0x88) {
                    hasMv = true;
                    break;
                }
            }
            if (useYCoCg) {
                int[] ycocgCurr = new int[nSamples];
                int[] ycocgOrig = new int[nSamples];
                for (int i = 0; i < nSamples; i++) {
                    ycocgCurr[i] = LcvPrimitives.rgbaToYCoCgR(cube[i]);
                    ycocgOrig[i] = LcvPrimitives.rgbaToYCoCgR(origCube[i]);
                }
                for (int c = 0; c < 3; c++) {
                    int shift = (3 - c) * 8;
                    int clearMask = ~(0xFF << shift);
                    int sCh = (c == 0) ? lsbSplitBits : Math.min(4, lsbSplitBits + 1);
                    int msbMask = (1 << (8 - sCh)) - 1;
                    int lsbMid = 1 << (sCh - 1);
                    int chSpatialTol = (c == 0) ? spatialTol : (spatialTol + 4);
                    int chTemporalTol = (c == 0) ? temporalTol : (temporalTol + 4);
                    int chMaxOrigTol = (c == 0) ? maxOrigTol : (maxOrigTol + 4);

                    if (c > 0) {
                        long sumC = 0L;
                        int minC = 255;
                        int maxC = 0;
                        for (int i = 0; i < nSamples; i++) {
                            int v = (ycocgOrig[i] >>> shift) & 0xFF;
                            sumC += v;
                            if (v < minC) {
                                minC = v;
                            }
                            if (v > maxC) {
                                maxC = v;
                            }
                        }
                        if (maxC - minC <= 6 + (boost >>> 1)) {
                            int meanC = (int) Math.round((double) sumC / nSamples);
                            int mConst = (meanC >>> sCh) & msbMask;
                            int fillConst = (c > 0 && lsbSplitBits == 2)
                                ? (((mConst & 1) != 0) ? ((1 << sCh) - 1) : 0)
                                : lsbMid;
                            int constV = (mConst << sCh) | fillConst;
                            for (int i = 0; i < nSamples; i++) {
                                ycocgCurr[i] = (ycocgCurr[i] & clearMask) | (constV << shift);
                            }
                            continue;
                        }
                    }

                    for (int tyx = 0; tyx < nSamples; tyx++) {
                        int word = ycocgCurr[tyx];
                        int vCurr = (word >>> shift) & 0xFF;
                        int vOrig = (ycocgOrig[tyx] >>> shift) & 0xFF;
                        int lt = tyx >>> 8;
                        int stepMv = (lt >= 1 && hasMv) ? packedSteps[lt] : 0x88;
                        int vx = LcvPrimitives.unpackVelocityX(stepMv);
                        int vy = LcvPrimitives.unpackVelocityY(stepMv);

                        int predWord = LcvCubeCodec.predictMed2dSample(tyx, vx, vy, useHalfPelMv, mArr, lArr);
                        int m;
                        if (predWord < 0) {
                            m = (vCurr >>> sCh) & msbMask;
                        } else {
                            int predM = (predWord >>> 8) & 0xFF;
                            int predV = (predM << sCh) | lsbMid;
                            int tol = (lt == 0) ? chSpatialTol : chTemporalTol;
                            if (Math.abs(vCurr - predV) <= tol
                                && (!foveated || Math.abs(vOrig - predV) <= chMaxOrigTol)) {
                                m = predM;
                            } else {
                                m = ((foveated ? vOrig : vCurr) >>> sCh) & msbMask;
                            }
                        }
                        int fill = (c > 0 && lsbSplitBits == 2)
                            ? (((m & 1) != 0) ? ((1 << sCh) - 1) : 0)
                            : lsbMid;
                        mArr[tyx] = (byte) m;
                        lArr[tyx] = (byte) fill;
                        int newV = (m << sCh) | fill;
                        ycocgCurr[tyx] = (word & clearMask) | (newV << shift);
                    }
                }
                for (int i = 0; i < nSamples; i++) {
                    cube[i] = LcvPrimitives.yCoCgRToRgba(ycocgCurr[i]);
                }
            } else {
                int sCh = lsbSplitBits;
                int msbMask = (1 << (8 - sCh)) - 1;
                int lsbMid = 1 << (sCh - 1);
                for (int c = 0; c < 3; c++) {
                    int shift = (3 - c) * 8;
                    int clearMask = ~(0xFF << shift);
                    for (int tyx = 0; tyx < nSamples; tyx++) {
                        int rgba = cube[tyx];
                        int vCurr = (rgba >>> shift) & 0xFF;
                        int vOrig = (origCube[tyx] >>> shift) & 0xFF;
                        int lt = tyx >>> 8;
                        int stepMv = (lt >= 1 && hasMv) ? packedSteps[lt] : 0x88;
                        int vx = LcvPrimitives.unpackVelocityX(stepMv);
                        int vy = LcvPrimitives.unpackVelocityY(stepMv);

                        int predWord = LcvCubeCodec.predictMed2dSample(tyx, vx, vy, useHalfPelMv, mArr, lArr);
                        int m;
                        if (predWord < 0) {
                            m = (vCurr >>> sCh) & msbMask;
                        } else {
                            int predM = (predWord >>> 8) & 0xFF;
                            int predV = (predM << sCh) | lsbMid;
                            int tol = (lt == 0) ? spatialTol : temporalTol;
                            if (Math.abs(vCurr - predV) <= tol
                                && (!foveated || Math.abs(vOrig - predV) <= maxOrigTol)) {
                                m = predM;
                            } else {
                                m = ((foveated ? vOrig : vCurr) >>> sCh) & msbMask;
                            }
                        }
                        mArr[tyx] = (byte) m;
                        lArr[tyx] = (byte) lsbMid;
                        int newV = (m << sCh) | lsbMid;
                        cube[tyx] = (rgba & clearMask) | (newV << shift);
                    }
                }
            }
            if (pass == 0) {
                int newMv8 = LcvPrimitives.estimateTileVelocity1Pass(cube, depthK);
                int nVx = LcvPrimitives.unpackVelocityX(newMv8);
                int nVy = LcvPrimitives.unpackVelocityY(newMv8);
                boolean trajectoryChanged = false;
                for (int t = 1; t < depthK; t++) {
                    int fullSt = LcvCubeCodec.estimateFrameStepVelocity(cube, t, nVx, nVy);
                    int st = useHalfPelMv
                        ? LcvCubeCodec.estimateFrameStepHalfPelVelocity(cube, t, fullSt)
                        : fullSt;
                    if (st != packedSteps[t]) {
                        packedSteps[t] = st;
                        trajectoryChanged = true;
                    }
                }
                if (!trajectoryChanged) {
                    break;
                }
            }
        }
    }

    /**
     * Post-processes an entire {@code .lcv} stream in-place (using {@link LcvStreamContainer#FLAG_SUPERSEDED_OR_SLACK}
     * slack framing when superblocks shrink by {@code >= 64 B}, or {@code EOF} append when a superblock expands)
     * without requiring a temporal full-file rewrite.
     */
    public static byte[] postprocessStreamInPlace(
        byte[] streamBytes, boolean stripLsb, LcvCubeCodec.DistanceRlePolicy coalescePolicy, boolean wrapZstd
    ) {
        byte[] current = Arrays.copyOf(streamBytes, streamBytes.length);
        java.util.List<Integer> activeOffsets = LcvStreamContainer.indexValidSuperblocks(current);
        for (int off : activeOffsets) {
            try {
                LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(current, off);
                byte[] sb = Arrays.copyOfRange(current, off, off + hdr.totalSuperblockBytes());
                if (coalescePolicy != null && coalescePolicy.losslessPlaneCutoff() > 0) {
                    sb = coalesceRunsByDistance(sb, hdr.roiTileX16(), hdr.roiTileY16(), coalescePolicy);
                }
                if (stripLsb) {
                    sb = stripLsbPlanes(sb);
                }
                if (wrapZstd) {
                    sb = wrapZstdPools(sb);
                }
                current = spliceEditedSuperblockNonTemporal(current, sb);
            } catch (LcvCorruptTileException e) {
                throw new IllegalArgumentException("Corrupt Superblock at offset " + off, e);
            }
        }
        return current;
    }

    /**
     * Rewrites {@code mode 10} ({@code 4|4} nibble {@code adjGap | lenMinusOne}) and {@code mode 11}
     * bitplane streams directly in the compressed domain to merge consecutive {@code 1}-runs separated
     * by {@code rawGap <= policy.maxMergeGap(plane, dist)} without decoding pixels or curves.
     */
    public static byte[] coalesceRunsByDistance(
        byte[] superblockBytes, int roiTileX, int roiTileY, LcvCubeCodec.DistanceRlePolicy policy
    ) {
        return coalesceRunsByDistance(superblockBytes, roiTileX, roiTileY, policy, 0L);
    }

    /**
     * Keyed Structural Codebook ({@code KSC} Layer C) overload for {@link #coalesceRunsByDistance}
     * accepting an optional {@code relayKey64} ({@code 0L} for unkeyed or zero-trust relay streams)
     * without requiring {@code msbKey64} or {@code lsbKey64}.
     */
    public static byte[] coalesceRunsByDistance(
        byte[] superblockBytes, int roiTileX, int roiTileY, LcvCubeCodec.DistanceRlePolicy policy, long relayKey64
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
            long frameIndex64 = header.frameIndex64();

            LcvStreamContainer.UnpackedDirectory srcDir =
                LcvStreamContainer.unpackSuperblockDirectory(superblockBytes, 0, header);
            long[] srcHealedPointers = srcDir.healedPointers();
            int srcMsbPoolFileOff = header.msbPoolOffset();
            int srcLsbPoolFileOff = header.lsbPoolOffset();
            boolean zstdPool = (header.flags8() & LcvStreamContainer.FLAG_ZSTD_POOL) != 0;
            boolean lsbStripped = (header.flags8() & LcvStreamContainer.FLAG_LSB_STRIPPED) != 0
                || header.lsbPoolCompressedLen32() == 0;
            int srcLsbTableBytes = nTiles * 4;

            byte[] srcMsbPool = superblockBytes;
            int srcMsbPoolBase = srcMsbPoolFileOff;
            byte[] srcLsbPool = superblockBytes;
            int srcLsbPoolBase = srcLsbPoolFileOff;
            if (zstdPool) {
                srcMsbPool = LcvStreamContainer.decompressZstdFrameWithSizeHeader(
                    superblockBytes, srcMsbPoolFileOff, header.msbPoolCompressedLen32()
                );
                srcMsbPoolBase = 0;
                if (!lsbStripped && header.lsbPoolCompressedLen32() > 0) {
                    srcLsbPool = LcvStreamContainer.decompressZstdFrameWithSizeHeader(
                        superblockBytes, srcLsbPoolFileOff, header.lsbPoolCompressedLen32()
                    );
                    srcLsbPoolBase = 0;
                } else {
                    srcLsbPool = new byte[0];
                    srcLsbPoolBase = 0;
                }
            }
            ByteBuffer srcLsbBuf = (!lsbStripped && srcLsbPool.length >= srcLsbPoolBase + srcLsbTableBytes)
                ? ByteBuffer.wrap(srcLsbPool).order(ByteOrder.BIG_ENDIAN)
                : null;

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

            try {
                for (int ty = 0; ty < tilesY; ty++) {
                    for (int tx = 0; tx < tilesX; tx++) {
                        int tileIdx = ty * tilesX + tx;
                        dstLsbTileRelOffsets[tileIdx] = dstLsbPayloadOut.size();

                        long ptr64 = srcHealedPointers[tileIdx];
                        if (ptr64 == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL
                            || LcvStreamContainer.isZeroBodyPointer(ptr64)) {
                            dstPointers[tileIdx] = ptr64;
                            dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(ptr64);
                            continue;
                        }

                        int srcRelMsbOff = LcvStreamContainer.extractRelByteOffset30(ptr64);
                        int srcMsbLen = LcvStreamContainer.extractMsbSliceLen16(ptr64);
                        int srcLsbLen = (lsbStripped || srcLsbBuf == null)
                            ? 0
                            : LcvStreamContainer.extractLsbSliceLen16(ptr64);
                        int srcAbsMsbOff = srcMsbPoolBase + srcRelMsbOff;
                        int srcAbsLsbOff = 0;
                        if (srcLsbLen > 0 && srcLsbBuf != null) {
                            int srcRelLsbOff = srcLsbBuf.getInt(srcLsbPoolBase + tileIdx * 4);
                            srcAbsLsbOff = srcLsbPoolBase + srcLsbTableBytes + srcRelLsbOff;
                        }

                        double dx = tx - roiTileX;
                        double dy = ty - roiTileY;
                        double radialDist = Math.sqrt(dx * dx + dy * dy);

                        int cubeHeader8 = srcMsbPool[srcAbsMsbOff] & 0xFF;
                        int cubeMode = (cubeHeader8 >>> 4) & 0x03;

                        // If lossless at this radial distance or non-Gray mode, copy slices directly
                        if (policy.isLosslessAt(radialDist)
                            || (cubeMode != LcvCubeCodec.CUBE_MODE_GRAY_PLANES_RAW
                                && cubeMode != LcvCubeCodec.CUBE_MODE_GRAY_PLANES_HILBERT_DELTA)) {
                            int newRelMsbOff = dstMsbPoolOut.size();
                            dstMsbPoolOut.write(srcMsbPool, srcAbsMsbOff, srcMsbLen);
                            if (srcLsbLen > 0) {
                                dstLsbPayloadOut.write(srcLsbPool, srcAbsLsbOff, srcLsbLen);
                            }
                            long newPtr = LcvStreamContainer.makeActiveTilePointer(newRelMsbOff, srcMsbLen, srcLsbLen);
                            dstPointers[tileIdx] = newPtr;
                            dstSyndromes[tileIdx] = LcvPrimitives.computeHammingSecDed8(newPtr);
                            continue;
                        }

                        int tileCoordId = ((ty & 0xFFFF) << 16) | (tx & 0xFFFF);
                        arena.activeRelayTileKey64 = LcvPrimitives.computeTileKey64(relayKey64, frameIndex64, tileCoordId);

                        // Coalesce bitplane tokens directly in the compressed domain (zero pixel decode)
                        int lsbSplitBits = header.tuningProfile().lsbSplitBits();
                        int[] newLengths = coalesceTileGrayBitplanesCompressed(
                            srcMsbPool, srcAbsMsbOff, srcMsbLen,
                            srcLsbPool, srcAbsLsbOff, srcLsbLen,
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
            } finally {
                arena.activeRelayTileKey64 = 0L;
            }

            byte[] dstMsbBytes = dstMsbPoolOut.toByteArray();
            byte[] dstLsbBytes = LcvStreamContainer.buildIndexedLsbPool(
                nTiles, dstLsbTileRelOffsets, dstLsbPayloadOut.toByteArray()
            );
            int outFlags8 = header.flags8();
            if (zstdPool && LcvStreamContainer.isZstdAvailable()) {
                dstMsbBytes = LcvStreamContainer.compressZstdFrameWithSizeHeader(dstMsbBytes);
                dstLsbBytes = dstLsbBytes.length > 0
                    ? LcvStreamContainer.compressZstdFrameWithSizeHeader(dstLsbBytes)
                    : dstLsbBytes;
            } else {
                outFlags8 &= ~LcvStreamContainer.FLAG_ZSTD_POOL;
            }

            LcvCubeCodec.TuningProfile oldProfile = header.tuningProfile();
            int updatedTuning16 = new LcvCubeCodec.TuningProfile(
                oldProfile.lsbSplitBits(), policy, oldProfile.cleanAckRefreshWindow()
            ).toPackedU16();

            return LcvStreamContainer.assembleSuperblockBytes(
                header.width16(), header.height16(), depthK, outFlags8,
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
                boolean med2dPerFrameMv = ((deltaFlags8 & LcvCubeCodec.DELTA_SUBMODE_PLANAR_3D_MV) == 0)
                    && ((deltaFlags8 & LcvCubeCodec.DELTA_FLAG_SPATIAL_MED_2D) != 0)
                    && ((deltaFlags8 & LcvCubeCodec.DELTA_FLAG_COMPACT_PLANAR_HDR) != 0)
                    && ((nSamples >>> 8) > 1);
                int mvBytes = med2dPerFrameMv ? ((nSamples >>> 8) - 1) : 1;
                if (srcPos + mvBytes > msbLimit) {
                    throw new LcvCorruptTileException("CBIT: truncated packedMv8 in compressed transform");
                }
                packedMv8 = msbSrc[srcPos] & 0xFF;
                for (int m = 0; m < mvBytes; m++) {
                    dstMsb[dstMsbPos++] = msbSrc[srcPos++];
                }
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
                int rawWord16 = ((msbSrc[srcPos] & 0xFF) << 8) | (msbSrc[srcPos + 1] & 0xFF);
                srcPlaneModes[c] = LcvPrimitives.unpermutePlaneModes16(rawWord16, arena.activeRelayTileKey64, c);
                srcPos += 2;
                dstPlaneModesOffsets[c] = dstMsbPos;
                dstMsb[dstMsbPos++] = 0;
                dstMsb[dstMsbPos++] = 0;
            }
        }

        int[] posRef = arena.posCursor;
        posRef[0] = srcPos;
        arena.activeLsbSplitBits = lsbSplitBits;
        boolean med2dConditionedMsb = ((deltaFlags8 & LcvCubeCodec.DELTA_FLAG_SPATIAL_MED_2D) != 0)
            && ((deltaFlags8 & LcvCubeCodec.DELTA_FLAG_MAXU_CONDITIONED) != 0);

        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                Arrays.fill(arena.grayValues[c], 0, nSamples, (byte) 0);
            }
        }

        // 1. Decode Base MSB Slice planes p = 7..lsbSplitBits into arena.grayValues[c]
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) == 0) {
                continue;
            }
            int pModes = srcPlaneModes[c];
            byte[] grayCh = arena.grayValues[c];
            for (int p = 7; p >= lsbSplitBits; p--) {
                int oldMode = (pModes >>> (2 * p)) & 0x03;
                if (med2dConditionedMsb) {
                    int startPos = posRef[0];
                    LcvCubeCodec.decodeConditionedBitplane(
                        msbSrc, posRef, msbLimit, nSamples, p, oldMode, arena.activeBitPlane, arena
                    );
                    int sliceBytes = posRef[0] - startPos;
                    if (sliceBytes > 0) {
                        System.arraycopy(msbSrc, startPos, dstMsb, dstMsbPos, sliceBytes);
                        dstMsbPos += sliceBytes;
                    }
                    dstPlaneModesWords[c] |= (oldMode << (2 * p));
                    int bitMask = 1 << p;
                    for (int i = 0; i < nSamples; i++) {
                        if (arena.activeBitPlane[i] != 0) {
                            grayCh[i] |= (byte) bitMask;
                        }
                    }
                } else {
                    LcvCubeCodec.decodeSingleBitplane(
                        msbSrc, posRef, msbLimit, nSamples, p, oldMode, grayCh, arena
                    );
                }
            }
        }

        // 2. Decode Tail LSB Slice planes p = (lsbSplitBits - 1)..0 into arena.grayValues[c]
        if (lsbLen > 0) {
            posRef[0] = lsbOff;
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) == 0) {
                    continue;
                }
                int pModes = srcPlaneModes[c];
                byte[] grayCh = arena.grayValues[c];
                for (int p = lsbSplitBits - 1; p >= 0; p--) {
                    int oldMode = (pModes >>> (2 * p)) & 0x03;
                    LcvCubeCodec.decodeSingleBitplane(
                        lsbSrc, posRef, lsbLimit, nSamples, p, oldMode, grayCh, arena
                    );
                }
            }
        }

        // 3. Re-encode Base MSB Slice planes p = 7..lsbSplitBits with color-proximity-gated d_merge
        if (!med2dConditionedMsb) {
            boolean hasLowerPlanes = lsbLen > 0;
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) == 0) {
                    continue;
                }
                byte[] grayCh = arena.grayValues[c];
                for (int p = 7; p >= lsbSplitBits; p--) {
                    int maxGap = (cubeMode == LcvCubeCodec.CUBE_MODE_GRAY_PLANES_HILBERT_DELTA)
                        ? 0
                        : policy.maxMergeGap(p, radialDist);
                    int[] newModeAndBytes = LcvCubeCodec.encodeSingleBitplane(
                        grayCh, nSamples, p, maxGap, hasLowerPlanes, arena, dstMsb, dstMsbPos
                    );
                    dstPlaneModesWords[c] |= (newModeAndBytes[0] << (2 * p));
                    dstMsbPos += newModeAndBytes[1];
                }
            }
        }

        // 4. Re-encode Tail LSB Slice planes p = (lsbSplitBits - 1)..0 with color-proximity-gated d_merge
        int dstLsbPos = 0;
        if (lsbLen > 0) {
            boolean isPureGray = !med2dConditionedMsb;
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) == 0) {
                    continue;
                }
                byte[] grayCh = arena.grayValues[c];
                for (int p = lsbSplitBits - 1; p >= 0; p--) {
                    int maxGap = policy.maxMergeGap(p, radialDist);
                    int[] newModeAndBytes = LcvCubeCodec.encodeSingleBitplane(
                        grayCh, nSamples, p, maxGap, isPureGray, arena, dstLsb, dstLsbPos
                    );
                    dstPlaneModesWords[c] |= (newModeAndBytes[0] << (2 * p));
                    dstLsbPos += newModeAndBytes[1];
                }
            }
        } else {
            int lsbModeMask = (1 << (2 * lsbSplitBits)) - 1;
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) != 0) {
                    dstPlaneModesWords[c] |= (srcPlaneModes[c] & lsbModeMask);
                }
            }
        }

        // Write updated planeModes16[c] words
        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                int off = dstPlaneModesOffsets[c];
                int word = LcvPrimitives.permutePlaneModes16(dstPlaneModesWords[c], arena.activeRelayTileKey64, c);
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
        boolean compactHdr = (deltaFlags8 & LcvCubeCodec.DELTA_FLAG_COMPACT_PLANAR_HDR) != 0;

        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                if (compactHdr) {
                    if (srcPos + 6 > msbLimit) {
                        throw new LcvCorruptTileException("CBIT: truncated compact planar MV header in compressed transform");
                    }
                    srcPlaneModes[c] = msbSrc[srcPos++] & 0xFF;
                    dstPlaneModesOffsets[c] = dstMsbPos;
                    dstMsb[dstMsbPos++] = 0;
                    int b1 = msbSrc[srcPos] & 0xFF;
                    boolean isAligned16 = (b1 & 0x80) != 0;
                    boolean isMod64 = (b1 & 0x40) != 0;
                    maxU8PerChannel[c] = isMod64 ? 0 : (b1 & 0x0F);
                    int paramBytes = (isMod64 || isAligned16) ? 5 : 9;
                    if (srcPos + paramBytes > msbLimit) {
                        throw new LcvCorruptTileException("CBIT: truncated compact planar params in compressed transform");
                    }
                    System.arraycopy(msbSrc, srcPos, dstMsb, dstMsbPos, paramBytes);
                    srcPos += paramBytes;
                    dstMsbPos += paramBytes;
                } else {
                    int baseNeed = hasWaveletHaar ? 13 : 12;
                    if (srcPos + baseNeed > msbLimit) {
                        throw new LcvCorruptTileException("CBIT: truncated planar MV header in compressed transform");
                    }
                    int rawWord16 = ((msbSrc[srcPos] & 0xFF) << 8) | (msbSrc[srcPos + 1] & 0xFF);
                    srcPlaneModes[c] = LcvPrimitives.unpermutePlaneModes16(rawWord16, arena.activeRelayTileKey64, c);
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
                byte[] upperU = arena.lsbShiftBase[c];
                byte[] clampedZero = arena.lsbWidth[c];
                System.arraycopy(msbOnly, 0, upperU, 0, nSamples);
                Arrays.fill(clampedZero, 0, nSamples, (byte) 0);
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
                            arena.xorBitPlane[nActive] = upperU[i];
                            arena.rotBitScratch[nActive] = clampedZero[i];
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
                    boolean hadClamped = false;
                    for (int k = 0; k < nActive; k++) {
                        if (arena.rotBitScratch[k] != 0) {
                            arena.activeBitPlane[k] = 0;
                            hadClamped = true;
                        }
                    }
                    int maxGap = (bitVal * 2 > maxU8) ? 0 : policy.maxMergeGap(p, radialDist);
                    if (maxGap > 0 && p >= 1) {
                        LcvCubeCodec.bridgePlanarConditionedBitplaneInPlace(
                            arena.activeBitPlane, arena.xorBitPlane, arena.rotBitScratch, nActive, maxGap
                        );
                    }
                    if (maxGap <= 0 && !hadClamped) {
                        int sliceBytes = posRef[0] - startPos;
                        System.arraycopy(lsbSrc, startPos, dstLsb, dstLsbPos, sliceBytes);
                        dstLsbPos += sliceBytes;
                        dstPlaneModesWords[c] |= (oldMode << (2 * p));
                    } else {
                        int effectiveCondGap = (p == 0) ? maxGap : 0;
                        int[] newModeAndBytes = LcvCubeCodec.encodeConditionedBitplane(
                            arena.activeBitPlane, nActive, effectiveCondGap, arena, dstLsb, dstLsbPos
                        );
                        dstPlaneModesWords[c] |= (newModeAndBytes[0] << (2 * p));
                        dstLsbPos += newModeAndBytes[1];
                    }
                    int k = 0;
                    for (int i = 0; i < nSamples; i++) {
                        int mVal = msbOnly[i] & 0xFF;
                        if (mVal + bitVal <= maxU8) {
                            clampedZero[i] = arena.rotBitScratch[k];
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
        } else {
            int lsbModeMask = (1 << (2 * lsbSplitBits)) - 1;
            for (int c = 0; c < 4; c++) {
                if (((channelMask4 >>> c) & 1) != 0) {
                    dstPlaneModesWords[c] |= (srcPlaneModes[c] & lsbModeMask);
                }
            }
        }

        for (int c = 0; c < 4; c++) {
            if (((channelMask4 >>> c) & 1) != 0) {
                int off = dstPlaneModesOffsets[c];
                int word = LcvPrimitives.permutePlaneModes16(dstPlaneModesWords[c], arena.activeRelayTileKey64, c);
                if (compactHdr) {
                    dstMsb[off] = (byte) (word & 0xFF);
                } else {
                    dstMsb[off] = (byte) (word >>> 8);
                    dstMsb[off + 1] = (byte) word;
                }
            }
        }

        return new int[]{dstMsbPos, dstLsbPos};
    }

    private static int[] rewriteSingleCompressedPlane(
        byte[] src, int[] posRef, int limit, int nSamples,
        boolean isMsbTier, int oldMode, int maxGap, LcvPrimitives.ScratchArena arena,
        byte[] dst, int dstOff
    ) throws LcvCorruptTileException {
        if (oldMode == LcvCubeCodec.PLANE_ALL_ZERO) {
            return new int[]{oldMode, 0};
        }
        int bitmapBytes = nSamples >>> 3;
        int[] rawGaps = arena.rawGaps;
        int[] runLens = arena.runLens;
        int runCount;

        if (oldMode == LcvCubeCodec.PLANE_SINGLE_RUN_INLINE) {
            if (isMsbTier) {
                int startPos = posRef[0];
                LcvCubeCodec.readSingleRunInline(src, posRef, limit, nSamples, rawGaps, runLens);
                System.arraycopy(src, startPos, dst, dstOff, 2);
                return new int[]{LcvCubeCodec.PLANE_SINGLE_RUN_INLINE, 2};
            }
            // LSB Tier mode 01: PLANE_XOR_UPPER_RLE
            int startPos = posRef[0];
            if (startPos >= limit) {
                throw new LcvCorruptTileException("CBIT: truncated PLANE_XOR_UPPER_RLE in compressed transform");
            }
            if ((src[startPos] & 0xFF) == 0) {
                if (startPos + 2 > limit) {
                    throw new LcvCorruptTileException("CBIT: truncated PLANE_XOR_UPPER_RLE sentinel in compressed transform");
                }
                posRef[0] = startPos + 2;
                dst[dstOff] = src[startPos];
                dst[dstOff + 1] = src[startPos + 1];
                return new int[]{LcvCubeCodec.PLANE_XOR_UPPER_RLE, 2};
            }
            runCount = LcvCubeCodec.readNibbleRunStream(src, posRef, limit, nSamples, rawGaps, runLens);
            if (maxGap <= 0) {
                int rawLen = posRef[0] - startPos;
                System.arraycopy(src, startPos, dst, dstOff, rawLen);
                return new int[]{LcvCubeCodec.PLANE_XOR_UPPER_RLE, rawLen};
            }
            runCount = LcvCubeCodec.coalesceRunArraysInPlace(rawGaps, runLens, runCount, maxGap);
            int xorRleBytes = LcvCubeCodec.writeNibbleRunStream(rawGaps, runLens, runCount, dst, dstOff);
            return new int[]{LcvCubeCodec.PLANE_XOR_UPPER_RLE, xorRleBytes};
        }

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
        if (isMsbTier && runCount == 1
            && LcvCubeCodec.tryWriteSingleRunInline(rawGaps[0], runLens[0], nSamples, dst, dstOff) == 2) {
            return new int[]{LcvCubeCodec.PLANE_SINGLE_RUN_INLINE, 2};
        }
        if (!isMsbTier && runCount == 1 && rawGaps[0] == 0 && runLens[0] == nSamples) {
            dst[dstOff] = 0x00;
            dst[dstOff + 1] = 0x00;
            return new int[]{LcvCubeCodec.PLANE_XOR_UPPER_RLE, 2};
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
            boolean zstdPool = (header.flags8() & LcvStreamContainer.FLAG_ZSTD_POOL) != 0;
            LcvStreamContainer.UnpackedDirectory srcDir =
                LcvStreamContainer.unpackSuperblockDirectory(superblockBytes, 0, header);
            long[] srcRawPointers = srcDir.rawPointers();
            byte[] srcSyndromes = srcDir.syndromes();
            long[] srcHealedPointers = srcDir.healedPointers();
            int srcMsbPoolFileOff = header.msbPoolOffset();
            int srcLsbPoolFileOff = header.lsbPoolOffset();
            boolean srcLsbStripped = (header.flags8() & LcvStreamContainer.FLAG_LSB_STRIPPED) != 0
                || header.lsbPoolCompressedLen32() == 0;

            byte[] srcMsbPool = superblockBytes;
            int srcMsbPoolBase = srcMsbPoolFileOff;
            byte[] srcLsbPool = superblockBytes;
            int srcLsbPoolBase = srcLsbPoolFileOff;
            if (zstdPool) {
                srcMsbPool = LcvStreamContainer.decompressZstdFrameWithSizeHeader(
                    superblockBytes, srcMsbPoolFileOff, header.msbPoolCompressedLen32()
                );
                srcMsbPoolBase = 0;
                if (!srcLsbStripped && header.lsbPoolCompressedLen32() > 0) {
                    srcLsbPool = LcvStreamContainer.decompressZstdFrameWithSizeHeader(
                        superblockBytes, srcLsbPoolFileOff, header.lsbPoolCompressedLen32()
                    );
                    srcLsbPoolBase = 0;
                } else {
                    srcLsbPool = new byte[0];
                    srcLsbPoolBase = 0;
                }
            }
            ByteBuffer srcLsbBuf = (!srcLsbStripped && srcLsbPool.length > srcLsbPoolBase)
                ? ByteBuffer.wrap(srcLsbPool).order(ByteOrder.BIG_ENDIAN)
                : null;

            int srcCheckpointBytes = wasPartial ? 4 : 0;
            int srcLsbTableBase = srcLsbPoolBase + srcCheckpointBytes;
            int srcLsbDataBase = srcLsbTableBase + nTiles * 4;

            LcvCubeCodec.TuningProfile priorProfile = (explicitPriorProfile != null)
                ? explicitPriorProfile
                : (wasPartial && !srcLsbStripped && srcLsbBuf != null
                    ? LcvCubeCodec.TuningProfile.fromPackedU16(srcLsbBuf.getShort(srcLsbPoolBase + 2) & 0xFFFF)
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
                long rawPtr = srcRawPointers[tileIdx];
                byte syn = srcSyndromes[tileIdx];
                long ptr64 = srcHealedPointers[tileIdx];
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
                int srcLsbLen = (srcLsbStripped || srcLsbBuf == null)
                    ? 0
                    : LcvStreamContainer.extractLsbSliceLen16(ptr64);
                int srcLsbOff = 0;
                if (srcLsbLen > 0 && srcLsbBuf != null) {
                    int relLsb = srcLsbBuf.getInt(srcLsbTableBase + tileIdx * 4);
                    srcLsbOff = srcLsbDataBase + relLsb;
                }

                // Case 1: Already-recoded packet (tileIdx < safeStart) OR not-yet-reached packet (tileIdx >= endCursor)
                // -> Copy verbatim in O(1) without decoding or re-encoding!
                if (tileIdx < safeStart || tileIdx >= endCursor) {
                    int newRelMsbOff = dstMsbPoolOut.size();
                    dstMsbPoolOut.write(srcMsbPool, srcMsbOff, srcMsbLen);
                    dstLsbTileRelOffsets[tileIdx] = dstLsbPayloadOut.size();
                    if (srcLsbLen > 0) {
                        dstLsbPayloadOut.write(srcLsbPool, srcLsbOff, srcLsbLen);
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
                        srcMsbPool, srcMsbOff, srcMsbLen,
                        srcLsbPool, srcLsbOff, effectiveSrcLsbLen,
                        nSamples, radialDist, targetProfile.rlePolicy(), dstSplitBits,
                        arena, tileMsbScratch, tileLsbScratch
                    );
                    newMsbLen = newLengths[0];
                    newLsbLen = stripTargetLsb ? 0 : newLengths[1];
                } else {
                    // Split boundary changed (e.g., s=2 -> s=3) -> inline single-packet transcode in ScratchArena
                    LcvCubeCodec.decodeSplitCubeRgba(
                        srcMsbPool, srcMsbOff, srcMsbLen,
                        srcLsbPool, srcLsbOff, srcLsbLen,
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

    /**
     * Summary of cross-superblock spatiotemporal database compaction ({@code leaf-video-ADR-003}).
     */
    public record DatabaseCompactionResult(
        byte[] compactedStreamBytes,
        List<LcvStreamContainer.DecodeMapPacket> decodeMapPackets,
        List<byte[]> duplicateRecoveryCheckpoints,
        int deduplicatedBlockCount
    ) {}

    /**
     * Extracts a {@link LcvStreamContainer.DecodeMapPacket} ({@code LCV_MAP1}) from a {@code Superblock}
     * without pixel decoding by indexing active MSB block slices and motion-shifted {@code TEMPORAL_CLEAN_ACK}
     * references ({@code leaf-video-ADR-003} {@code REQ-LCV-DB-001}).
     */
    public static LcvStreamContainer.DecodeMapPacket extractDecodeMapPacket(byte[] superblockBytes, int layerId) {
        try {
            LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(superblockBytes, 0);
            int tilesX = hdr.tilesX16();
            int tilesY = hdr.tilesY16();
            int nTiles = hdr.totalTiles();
            int msbPoolOff = hdr.msbPoolOffset();
            LcvStreamContainer.UnpackedDirectory unpackedDir =
                LcvStreamContainer.unpackSuperblockDirectory(superblockBytes, 0, hdr);
            long[] healedPointers = unpackedDir.healedPointers();

            List<LcvStreamContainer.BlockPoolDescriptor> blocks = new ArrayList<>();
            LcvStreamContainer.DecodeMapEntry[] gridEntries = new LcvStreamContainer.DecodeMapEntry[nTiles];
            int healed = unpackedDir.healedSingleBitPointers();

            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    int tileIdx = ty * tilesX + tx;
                    long ptr = healedPointers[tileIdx];
                    if (ptr == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                        gridEntries[tileIdx] = new LcvStreamContainer.DecodeMapEntry(layerId, tx, ty, 0, 0, 0, -1);
                        continue;
                    }
                    if (LcvStreamContainer.isZeroBodyPointer(ptr)) {
                        if (LcvStreamContainer.isTemporalCleanAckPointer(ptr)) {
                            int sDx = LcvStreamContainer.extractCleanAckShiftDx(ptr);
                            int sDy = LcvStreamContainer.extractCleanAckShiftDy(ptr);
                            int anchorOff = LcvStreamContainer.extractRelByteOffset30(ptr);
                            gridEntries[tileIdx] = new LcvStreamContainer.DecodeMapEntry(layerId, tx, ty, anchorOff, sDx, sDy, -1);
                        } else {
                            int rgba = LcvStreamContainer.extractUniformInlineRgba(ptr);
                            gridEntries[tileIdx] = new LcvStreamContainer.DecodeMapEntry(layerId, tx, ty, rgba & 0x00FFFFFF, 0, 0, -1);
                        }
                        continue;
                    }
                    int relOff = LcvStreamContainer.extractRelByteOffset30(ptr);
                    int msbLen = LcvStreamContainer.extractMsbSliceLen16(ptr);
                    int diskOff = msbPoolOff + relOff;
                    long sig64 = LcvPrimitives.mix64((((long) relOff) << 32) | (msbLen & 0xFFFFFFFFL));
                    int blockId = blocks.size() + 1;
                    long packedOffLen = ((long) diskOff << 32) | (msbLen & 0xFFFFFFFFL);
                    byte blockSyn = LcvPrimitives.computeHammingSecDed8(packedOffLen);
                    blocks.add(new LcvStreamContainer.BlockPoolDescriptor(blockId, sig64, diskOff, msbLen, blockSyn));
                    gridEntries[tileIdx] = new LcvStreamContainer.DecodeMapEntry(layerId, tx, ty, blockId, 0, 0, -1);
                }
            }

            // Bottom-up quadtree coalescing (quadLevel: 2=64x64 CTU, 1=32x32 Quad, 0=16x16 Leaf)
            boolean[] consumed = new boolean[nTiles];
            List<LcvStreamContainer.DecodeMapEntry> entries = new ArrayList<>(nTiles);
            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    int baseIdx = ty * tilesX + tx;
                    if (consumed[baseIdx]) {
                        continue;
                    }
                    LcvStreamContainer.DecodeMapEntry e0 = gridEntries[baseIdx];
                    if ((tx & 3) == 0 && (ty & 3) == 0 && tx + 4 <= tilesX && ty + 4 <= tilesY
                        && canCoalesceQuadGroup(gridEntries, consumed, tilesX, tx, ty, 4, e0)) {
                        markQuadConsumed(consumed, tilesX, tx, ty, 4);
                        entries.add(new LcvStreamContainer.DecodeMapEntry(
                            layerId, tx, ty, 2, e0.sourceBlockId(), e0.shiftDx(), e0.shiftDy(), e0.residualBlockId()
                        ));
                        continue;
                    }
                    if ((tx & 1) == 0 && (ty & 1) == 0 && tx + 2 <= tilesX && ty + 2 <= tilesY
                        && canCoalesceQuadGroup(gridEntries, consumed, tilesX, tx, ty, 2, e0)) {
                        markQuadConsumed(consumed, tilesX, tx, ty, 2);
                        entries.add(new LcvStreamContainer.DecodeMapEntry(
                            layerId, tx, ty, 1, e0.sourceBlockId(), e0.shiftDx(), e0.shiftDy(), e0.residualBlockId()
                        ));
                        continue;
                    }
                    consumed[baseIdx] = true;
                    entries.add(e0);
                }
            }
            return new LcvStreamContainer.DecodeMapPacket(
                hdr.frameIndex64(), hdr.width16(), hdr.height16(), hdr.depthK8(), 1, blocks, entries, healed
            );
        } catch (LcvCorruptTileException e) {
            throw new IllegalArgumentException("Corrupt Superblock in extractDecodeMapPacket", e);
        }
    }

    private static boolean canCoalesceQuadGroup(
        LcvStreamContainer.DecodeMapEntry[] grid, boolean[] consumed,
        int tilesX, int tx0, int ty0, int span, LcvStreamContainer.DecodeMapEntry ref
    ) {
        if (ref.residualBlockId() != -1) {
            return false;
        }
        for (int dy = 0; dy < span; dy++) {
            for (int dx = 0; dx < span; dx++) {
                int idx = (ty0 + dy) * tilesX + (tx0 + dx);
                if (consumed[idx]) {
                    return false;
                }
                LcvStreamContainer.DecodeMapEntry cand = grid[idx];
                if (cand.sourceBlockId() != ref.sourceBlockId()
                    || cand.shiftDx() != ref.shiftDx()
                    || cand.shiftDy() != ref.shiftDy()
                    || cand.residualBlockId() != -1) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void markQuadConsumed(boolean[] consumed, int tilesX, int tx0, int ty0, int span) {
        for (int dy = 0; dy < span; dy++) {
            for (int dx = 0; dx < span; dx++) {
                consumed[(ty0 + dy) * tilesX + (tx0 + dx)] = true;
            }
        }
    }

    /**
     * Compacts a sequence of {@code Superblock}s using {@link #wrapZstdPools} (with intra-superblock AST
     * slice deduplication) and emits a timeline of {@link LcvStreamContainer.DecodeMapPacket}s plus duplicated
     * recovery checkpoints ({@code leaf-video-ADR-003} {@code REQ-LCV-DB-001}, {@code REQ-LCV-DB-002}).
     */
    public static DatabaseCompactionResult compactStreamWithDecodeMap(List<byte[]> superblocks) {
        ByteArrayOutputStream streamOut = new ByteArrayOutputStream();
        List<LcvStreamContainer.DecodeMapPacket> packets = new ArrayList<>(superblocks.size());
        List<byte[]> checkpoints = new ArrayList<>(superblocks.size());
        CompactSignatureIndex sigIndex = new CompactSignatureIndex(Math.max(64, superblocks.size() * 256));
        int dedupCount = 0;
        long prevOff = 0L;

        LcvStreamContainer.StreamEncoderState dictState = new LcvStreamContainer.StreamEncoderState(64, 64);
        for (int i = 0; i < superblocks.size(); i++) {
            byte[] rawSb = superblocks.get(i);
            LcvStreamContainer.DecodeMapPacket pkt = extractDecodeMapPacket(rawSb, 0);
            packets.add(pkt);
            checkpoints.add(LcvStreamContainer.serializeDecodeMapPacket(pkt));

            long curOff = streamOut.size();
            for (LcvStreamContainer.BlockPoolDescriptor b : pkt.blockDirectory()) {
                if (!sigIndex.putIfAbsent(b.structuralSignature64(), (int) curOff + b.diskByteOffset(), b.byteLength())) {
                    dedupCount++;
                }
            }

            byte[] wrapped = wrapZstdPools(rawSb, dictState);
            if (wrapped.length < rawSb.length) {
                dedupCount++;
            }
            ByteBuffer buf = ByteBuffer.wrap(wrapped).order(ByteOrder.BIG_ENDIAN);
            buf.putLong(0x2C, prevOff);
            buf.putInt(0x3C, LcvStreamContainer.computeHeaderCrc32(wrapped, 0));
            streamOut.writeBytes(wrapped);
            prevOff = curOff;
        }
        return new DatabaseCompactionResult(streamOut.toByteArray(), packets, checkpoints, dedupCount);
    }

    /**
     * Resolves a non-destructive {@link LcvStreamContainer.SidecarBranchPacket} ({@code .lcv.branch}) timeline
     * against a growing {@code .lcv} stream in the compressed domain (applying {@link #cropSubRegion} for
     * tracked viewport coordinates and substituting override superblocks without pixel re-encoding —
     * {@code leaf-video-ADR-003} {@code REQ-LCV-DB-004}).
     */
    public static List<byte[]> resolveSidecarBranchTimeline(
        byte[] growingStreamBytes, LcvStreamContainer.SidecarBranchPacket branch
    ) throws LcvCorruptTileException {
        List<Integer> validOffsets = LcvStreamContainer.indexValidSuperblocks(growingStreamBytes);
        long[] timelineIndices = branch.timelineFrameIndices();
        int[] vx = branch.viewTileX();
        int[] vy = branch.viewTileY();
        int cropW = branch.cropTilesX();
        int cropH = branch.cropTilesY();
        List<byte[]> overrides = branch.overrideSuperblocks() != null ? branch.overrideSuperblocks() : List.of();

        List<byte[]> resolved = new ArrayList<>(timelineIndices.length);
        for (int i = 0; i < timelineIndices.length; i++) {
            long targetFrameIdx = timelineIndices[i];
            byte[] matchedSb = null;
            for (byte[] ov : overrides) {
                LcvStreamContainer.SuperblockHeader ovHdr = LcvStreamContainer.readSuperblockHeader(ov, 0);
                if (ovHdr.frameIndex64() == targetFrameIdx) {
                    matchedSb = ov;
                    break;
                }
            }
            if (matchedSb == null) {
                for (int off : validOffsets) {
                    LcvStreamContainer.SuperblockHeader hdr =
                        LcvStreamContainer.readSuperblockHeader(growingStreamBytes, off);
                    if (hdr.frameIndex64() == targetFrameIdx) {
                        int len = hdr.totalSuperblockBytes();
                        matchedSb = Arrays.copyOfRange(growingStreamBytes, off, off + len);
                        break;
                    }
                }
            }
            if (matchedSb == null) {
                continue;
            }
            LcvStreamContainer.SuperblockHeader mHdr = LcvStreamContainer.readSuperblockHeader(matchedSb, 0);
            if (cropW > 0 && cropH > 0 && (cropW < mHdr.tilesX16() || cropH < mHdr.tilesY16())) {
                int tx0 = (vx != null && i < vx.length) ? vx[i] : 0;
                int ty0 = (vy != null && i < vy.length) ? vy[i] : 0;
                matchedSb = cropSubRegion(matchedSb, tx0, ty0, cropW, cropH);
            }
            resolved.add(matchedSb);
        }
        return resolved;
    }

    /**
     * Composites unflattened multi-layer {@code (layer, x, y, t)} {@code Superblock}s ({@code Layer 0} background
     * + {@code Layer 1..N} transparent {@code RGBA} overlays), skipping {@code Alpha == 0} {@code UNIFORM_INLINE}
     * overlay tiles in {@code O(1)} without decoding them ({@code leaf-video-ADR-003} {@code REQ-LCV-DB-003}).
     */
    public static int[] compositeUnflattenedLayers(List<byte[]> layerSuperblocks) throws LcvCorruptTileException {
        if (layerSuperblocks == null || layerSuperblocks.isEmpty()) {
            throw new IllegalArgumentException("layerSuperblocks must not be empty");
        }
        LcvStreamContainer.SuperblockDecodeResult baseDec =
            LcvStreamContainer.decodeSuperblock(layerSuperblocks.get(0), 0, null);
        int[] composited = Arrays.copyOf(baseDec.rgbaFrames(), baseDec.rgbaFrames().length);
        int width = baseDec.header().width16();
        int height = baseDec.header().height16();
        int depthK = baseDec.header().depthK8();
        int tilesX = baseDec.header().tilesX16();
        int tilesY = baseDec.header().tilesY16();
        int frameArea = width * height;

        for (int layerIdx = 1; layerIdx < layerSuperblocks.size(); layerIdx++) {
            byte[] overlaySb = layerSuperblocks.get(layerIdx);
            LcvStreamContainer.SuperblockHeader ovHdr = LcvStreamContainer.readSuperblockHeader(overlaySb, 0);
            LcvStreamContainer.UnpackedDirectory ovDir =
                LcvStreamContainer.unpackSuperblockDirectory(overlaySb, 0, ovHdr);
            long[] healedPointers = ovDir.healedPointers();

            // Check if all tiles are transparent UNIFORM_INLINE -> skip layer in O(1)
            boolean anyVisibleTile = false;
            boolean[] transparentTileMask = new boolean[tilesX * tilesY];
            for (int tileIdx = 0; tileIdx < tilesX * tilesY; tileIdx++) {
                long ptr = healedPointers[tileIdx];
                if (ptr != LcvPrimitives.UNCORRECTABLE_DED_SENTINEL
                    && LcvStreamContainer.isZeroBodyPointer(ptr)
                    && !LcvStreamContainer.isTemporalCleanAckPointer(ptr)
                    && (LcvStreamContainer.extractUniformInlineRgba(ptr) & 0xFF) == 0) {
                    transparentTileMask[tileIdx] = true;
                } else {
                    anyVisibleTile = true;
                }
            }
            if (!anyVisibleTile) {
                continue;
            }
            LcvStreamContainer.SuperblockDecodeResult ovDec =
                LcvStreamContainer.decodeSuperblock(overlaySb, 0, null);
            int[] ovFrames = ovDec.rgbaFrames();
            for (int ty = 0; ty < tilesY; ty++) {
                for (int tx = 0; tx < tilesX; tx++) {
                    int tileIdx = ty * tilesX + tx;
                    if (transparentTileMask[tileIdx]) {
                        continue; // O(1) skip for Alpha==0 UNIFORM_INLINE overlay tile
                    }
                    int baseX = tx * 16;
                    int baseY = ty * 16;
                    int endX = Math.min(width, baseX + 16);
                    int endY = Math.min(height, baseY + 16);
                    for (int t = 0; t < depthK; t++) {
                        int fBase = t * frameArea;
                        for (int py = baseY; py < endY; py++) {
                            int row = fBase + py * width;
                            for (int px = baseX; px < endX; px++) {
                                int top = ovFrames[row + px];
                                int aTop = top & 0xFF;
                                if (aTop == 255) {
                                    composited[row + px] = top;
                                } else if (aTop > 0) {
                                    int bot = composited[row + px];
                                    int invA = 255 - aTop;
                                    int r = (((top >>> 24) & 0xFF) * aTop + ((bot >>> 24) & 0xFF) * invA + 127) / 255;
                                    int g = (((top >>> 16) & 0xFF) * aTop + ((bot >>> 16) & 0xFF) * invA + 127) / 255;
                                    int b = (((top >>> 8) & 0xFF) * aTop + ((bot >>> 8) & 0xFF) * invA + 127) / 255;
                                    int a = Math.min(255, aTop + (((bot & 0xFF) * invA + 127) / 255));
                                    composited[row + px] = (r << 24) | (g << 16) | (b << 8) | a;
                                }
                            }
                        }
                    }
                }
            }
        }
        return composited;
    }

    /**
     * Descriptor for live Hot-NVMe / Cold-HDD tiered superblock offloading
     * ({@code leaf-video-ADR-003} {@code REQ-LCV-DB-004}).
     */
    public record TieredSuperblockSplit(
        byte[] hotNvmeSuperblockBytes,
        byte[] coldHddLsbPoolBytes,
        long frameIndex64,
        long coldHddByteOffset,
        int coldHddByteLen,
        int originalFlags8
    ) {}

    /**
     * Splits a live-captured superblock in compressed domain (`O(1)` byte slice, zero pixel decode)
     * into a compact Hot-NVMe superblock (containing the 64-bit pointer directory, `(72, 64)` `SEC-DED`
     * syndromes, and `Section 3 + 4A` MSBs for zero-seek NLE scrubbing/cropping) and a sequential
     * Cold-HDD `Section 4B` LSB payload slice that can be flushed asynchronously to spinning disk or NAS
     * while streaming.
     */
    public static TieredSuperblockSplit offloadColdSection4BLive(
        byte[] superblockBytes, long coldHddByteOffset
    ) throws LcvCorruptTileException {
        LcvStreamContainer.SuperblockHeader h = LcvStreamContainer.readSuperblockHeader(superblockBytes, 0);
        int lsbOff = h.lsbPoolOffset();
        int lsbLen = h.lsbPoolCompressedLen32();
        if (lsbLen <= 0 || lsbOff + lsbLen > superblockBytes.length) {
            return new TieredSuperblockSplit(
                Arrays.copyOf(superblockBytes, superblockBytes.length),
                new byte[0],
                h.frameIndex64(),
                coldHddByteOffset,
                0,
                h.flags8()
            );
        }
        byte[] coldLsb = Arrays.copyOfRange(superblockBytes, lsbOff, lsbOff + lsbLen);
        byte[] hotNvme = stripLsbPlanes(superblockBytes);
        return new TieredSuperblockSplit(
            hotNvme,
            coldLsb,
            h.frameIndex64(),
            coldHddByteOffset,
            coldLsb.length,
            h.flags8()
        );
    }

    /**
     * Rehydrates a Hot-NVMe superblock with its Cold-HDD `Section 4B` LSB payload slice in `O(1)`
     * byte-concatenation time without re-encoding, restoring 100% bit-exact (`inf dB`) lossless
     * fidelity for the selected timeline range.
     */
    public static byte[] rehydrateSuperblockFromColdTier(
        byte[] hotNvmeSuperblockBytes,
        byte[] coldHddBuffer,
        int coldOffset,
        int coldLength,
        int originalFlags8
    ) throws LcvCorruptTileException {
        if (coldLength <= 0) {
            return Arrays.copyOf(hotNvmeSuperblockBytes, hotNvmeSuperblockBytes.length);
        }
        if (coldHddBuffer == null || coldOffset < 0 || coldOffset + coldLength > coldHddBuffer.length) {
            throw new LcvCorruptTileException("CBIT: invalid cold-HDD Section 4B slice bounds");
        }
        LcvStreamContainer.SuperblockHeader h = LcvStreamContainer.readSuperblockHeader(hotNvmeSuperblockBytes, 0);
        int hotHeaderAndMsbLen = h.lsbPoolOffset();
        byte[] restored = new byte[hotHeaderAndMsbLen + coldLength];
        System.arraycopy(hotNvmeSuperblockBytes, 0, restored, 0, hotHeaderAndMsbLen);
        System.arraycopy(coldHddBuffer, coldOffset, restored, hotHeaderAndMsbLen, coldLength);
        ByteBuffer bb = ByteBuffer.wrap(restored).order(ByteOrder.BIG_ENDIAN);
        bb.put(20, (byte) (originalFlags8 & ~LcvStreamContainer.FLAG_LSB_STRIPPED));
        bb.putInt(38, coldLength);
        return restored;
    }

    /**
     * Clamps {@code requestedDepthK} for {@code 4K} ({@code 3840x2160 -> K <= 4}) and {@code 8K}
     * ({@code 7680x4320 -> K = 1}) frames so each uncompressed Section 4A / Section 4B slice pool
     * stays safely below {@link LcvStreamContainer#MAX_ZSTD_UNCOMPRESSED_POOL_BYTES} ({@code 512 MiB})
     * and {@link LcvStreamContainer#MAX_REL_BYTE_OFFSET_30} ({@code 1 GiB - 1}) even on high-entropy scenes.
     */
    public static int clampDepthKFor4k8kPoolHeadroom(int width, int height, int requestedDepthK) {
        long framePixels = (long) Math.max(1, width) * Math.max(1, height);
        int maxK = requestedDepthK;
        if (framePixels >= 7680L * 4320L) {
            maxK = Math.min(maxK, 1);
        } else if (framePixels >= 3840L * 2160L) {
            maxK = Math.min(maxK, 4);
        } else if (framePixels >= 2560L * 1440L) {
            maxK = Math.min(maxK, 8);
        }
        if (maxK >= 16) {
            return 16;
        }
        if (maxK >= 8) {
            return 8;
        }
        if (maxK >= 4) {
            return 4;
        }
        if (maxK >= 2) {
            return 2;
        }
        return 1;
    }

    /**
     * Inspects an incoming frame buffer for prior {@code 4:2:0} {@code 8x8} DCT mosquito-noise
     * poisoning and recommends {@link LcvCubeCodec.TuningProfile#OBS_REALTIME_LOSSLESS} for clean
     * first-generation sources or an {@code s = 3} LSB-split profile when prior DCT block ringing
     * is detected so noisy bottom bitplanes are isolated in Section 4B ({@code leaf-video-ADR-001}).
     */
    public static LcvCubeCodec.TuningProfile recommendProfileForIngest(
        int[] rgbaFrames, int width, int height, int depthK
    ) {
        if (rgbaFrames == null || width <= 8 || height <= 0 || depthK <= 0) {
            return LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS;
        }
        double boundaryStepSum = 0.0;
        long boundaryStepCount = 0L;
        double interiorStepSum = 0.0;
        long interiorStepCount = 0L;
        long mosquitoInteriorSteps = 0L;
        for (int y = 0; y < height; y++) {
            int rowOff = y * width;
            for (int x = 1; x < width; x++) {
                int p0 = rgbaFrames[rowOff + x - 1];
                int p1 = rgbaFrames[rowOff + x];
                int y0 = (((p0 >>> 24) & 0xFF) + (((p0 >>> 16) & 0xFF) << 1) + ((p0 >>> 8) & 0xFF)) >>> 2;
                int y1 = (((p1 >>> 24) & 0xFF) + (((p1 >>> 16) & 0xFF) << 1) + ((p1 >>> 8) & 0xFF)) >>> 2;
                int dy = Math.abs(y1 - y0);
                if ((x & 7) == 0) {
                    boundaryStepSum += dy;
                    boundaryStepCount++;
                } else {
                    interiorStepSum += dy;
                    interiorStepCount++;
                    if (dy >= 1 && dy <= 10) {
                        mosquitoInteriorSteps++;
                    }
                }
            }
        }
        double meanBoundary = boundaryStepCount > 0 ? boundaryStepSum / boundaryStepCount : 0.0;
        double meanInterior = interiorStepCount > 0 ? interiorStepSum / interiorStepCount : 0.0;
        double dct8x8Ratio = (meanInterior > 0.05) ? (meanBoundary / meanInterior) : 1.0;
        double mosquitoRingFraction = interiorStepCount > 0
            ? (double) mosquitoInteriorSteps / interiorStepCount
            : 0.0;
        if (dct8x8Ratio > 1.35 && mosquitoRingFraction > 0.08) {
            return new LcvCubeCodec.TuningProfile(3, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, 8);
        }
        return LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS;
    }

    /**
     * Compact zero-boxing primitive hash index ({@code 16 B} per slot: {@code 8 B structuralSignature64}
     * + {@code 8 B (diskByteOffset32 << 32 | byteLen32)}) for long-horizon {@code 4K/8K} causal block
     * deduplication without retaining historical pixel buffers in RAM ({@code leaf-video-ADR-003}).
     */
    public static final class CompactSignatureIndex {
        private final int mask;
        private final long[] signatures;
        private final long[] packedOffsetAndLen;
        private int size;

        public CompactSignatureIndex(int capacityPowerOfTwo) {
            int cap = Integer.highestOneBit(Math.max(16, capacityPowerOfTwo - 1)) << 1;
            this.mask = cap - 1;
            this.signatures = new long[cap];
            this.packedOffsetAndLen = new long[cap];
        }

        public boolean putIfAbsent(long signature64, int diskByteOffset, int byteLength) {
            long key = (signature64 == 0L) ? 1L : signature64;
            int slot = (int) LcvPrimitives.mix64(key) & mask;
            while (true) {
                long cur = signatures[slot];
                if (cur == 0L) {
                    signatures[slot] = key;
                    packedOffsetAndLen[slot] = ((long) diskByteOffset << 32) | (byteLength & 0xFFFFFFFFL);
                    size++;
                    return true;
                }
                if (cur == key) {
                    return false;
                }
                slot = (slot + 1) & mask;
            }
        }

        public long getPackedOffsetAndLen(long signature64) {
            long key = (signature64 == 0L) ? 1L : signature64;
            int slot = (int) LcvPrimitives.mix64(key) & mask;
            while (true) {
                long cur = signatures[slot];
                if (cur == 0L) {
                    return -1L;
                }
                if (cur == key) {
                    return packedOffsetAndLen[slot];
                }
                slot = (slot + 1) & mask;
            }
        }

        public int size() {
            return size;
        }

        public long memoryBytes() {
            return (long) signatures.length * 16L;
        }
    }
}
