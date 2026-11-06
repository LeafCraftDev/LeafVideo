package io.github.dailystruggle.leafvideo;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verification suite for {@link LcvStreamContainer}, {@link LcvMkvPacketizer}, and
 * {@link LcvOnDiskTransformer}: {@code O(1)} forward/backward superblock seeking, bounded
 * {@code TEMPORAL_CLEAN_ACK} refresh, zero {@code DED} contagion across Section 4B via
 * {@code lsbTileOffsets}, and compressed-domain {@code cropSubRegion}, {@code stripLsbPlanes},
 * and {@code coalesceRunsByDistance}.
 *
 * @governedBy leaf-video-ADR-001
 * @trace REQ-LCV-F-002
 * @trace REQ-LCV-F-003
 * @trace REQ-LCV-F-004
 * @trace REQ-LCV-F-005
 */
class LcvStreamAndTransformTest {

    @Test
    void superblockContainerSupportsBoundedCleanAcksAndReverseSeeking() throws LcvCorruptTileException {
        int width = 64;
        int height = 64;
        int depthK = 4;
        int framePixels = width * height;
        int sbSamples = framePixels * depthK;

        // Build a base frame with:
        // - tile (0, 0): uniform solid color (must always pick UNIFORM_INLINE, never TEMPORAL_CLEAN_ACK)
        // - tile (1, 0): static non-uniform UI/gradient tile across all superblocks (tests MAX_CLEAN_ACK_REFRESH = 8)
        // - remaining tiles: animated gradient
        LcvStreamContainer.StreamEncoderState encState = new LcvStreamContainer.StreamEncoderState(width, height);
        ByteArrayOutputStream streamOut = new ByteArrayOutputStream();
        List<int[]> expectedFramesPerSb = new ArrayList<>();
        long prevOffset = 0L;

        int totalSuperblocks = 11; // Exceeds MAX_CLEAN_ACK_REFRESH (8) to verify mandatory refresh
        for (int sbIdx = 0; sbIdx < totalSuperblocks; sbIdx++) {
            int[] frames = new int[sbSamples];
            for (int t = 0; t < depthK; t++) {
                for (int y = 0; y < height; y++) {
                    for (int x = 0; x < width; x++) {
                        int tx = x / 16;
                        int ty = y / 16;
                        int rgba;
                        if (tx == 0 && ty == 0) {
                            rgba = 0x112233FF; // Uniform static tile
                        } else if (tx == 1 && ty == 0) {
                            int v = (x * 7 + y * 5 + t * 3) & 0xFF; // Non-uniform static tile
                            rgba = (v << 24) | ((v ^ 0x55) << 16) | (v << 8) | 0xFF;
                        } else {
                            int v = (x * 3 + y * 5 + (sbIdx * depthK + t) * 9) & 0xFF;
                            rgba = (v << 24) | (v << 16) | (v << 8) | 0xFF;
                        }
                        frames[t * framePixels + y * width + x] = rgba;
                    }
                }
            }
            expectedFramesPerSb.add(frames);

            long curOffset = streamOut.size();
            byte[] sbBytes = LcvStreamContainer.encodeSuperblock(
                frames, width, height, depthK,
                (long) sbIdx * depthK, prevOffset,
                2, 2, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, encState
            );
            prevOffset = curOffset;
            streamOut.writeBytes(sbBytes);
        }

        byte[] fullStream = streamOut.toByteArray();
        List<Integer> offsets = LcvStreamContainer.indexValidSuperblocks(fullStream);
        assertEquals(totalSuperblocks, offsets.size());

        LcvStreamContainer.StreamDecoderState decState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        for (int sbIdx = 0; sbIdx < totalSuperblocks; sbIdx++) {
            LcvStreamContainer.SuperblockDecodeResult res = LcvStreamContainer.decodeSuperblock(
                fullStream, offsets.get(sbIdx), decState
            );
            assertArrayEquals(expectedFramesPerSb.get(sbIdx), res.rgbaFrames(), "Mismatch at superblock " + sbIdx);
            assertEquals(1, res.uniformInlineTiles(), "Tile (0,0) must always use UNIFORM_INLINE");

            if (sbIdx == 0 || sbIdx == LcvStreamContainer.MAX_CLEAN_ACK_REFRESH + 1) {
                // At sbIdx = 0 and sbIdx = 9 (after 8 clean-acks in sbIdx 1..8), tile (1,0) emits a fresh active CubeFrame
                assertEquals(0, res.cleanAckReusedTiles(), "Expected fresh active refresh at sbIdx=" + sbIdx);
            } else {
                assertEquals(1, res.cleanAckReusedTiles(), "Expected Case A clean-ack at sbIdx=" + sbIdx);
            }
        }

        // Verify backwards linked-list traversal via prevSbOffset64 from the final superblock back to 0
        int walkIdx = totalSuperblocks - 1;
        int curOff = offsets.get(walkIdx);
        while (walkIdx > 0) {
            LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(fullStream, curOff);
            curOff = (int) hdr.prevSbOffset64();
            walkIdx--;
            assertEquals(offsets.get(walkIdx).intValue(), curOff);
        }
    }

    @Test
    void section4bLsbOffsetTablePreventsDedContagionAcrossSubsequentTiles() throws LcvCorruptTileException {
        int width = 64;
        int height = 32;
        int depthK = 4;
        int[] frames = createProceduralFrames(width, height, depthK, 101L);

        byte[] sbBytes = LcvStreamContainer.encodeSuperblock(
            frames, width, height, depthK, 0L, 0L, 1, 1,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );

        // Inject a Double-Bit Error (DED) into tile 0's ptr64 in Section 2
        ByteBuffer buf = ByteBuffer.wrap(sbBytes).order(ByteOrder.BIG_ENDIAN);
        int dirOff = LcvStreamContainer.SUPERBLOCK_HEADER_BYTES;
        long origPtr0 = buf.getLong(dirOff);
        long dedPtr0 = origPtr0 ^ (1L << 5) ^ (1L << 19); // 2-bit flip -> DED
        buf.putLong(dirOff, dedPtr0);

        LcvStreamContainer.SuperblockDecodeResult dec = LcvStreamContainer.decodeSuperblock(sbBytes, 0, null);
        assertEquals(1, dec.quarantinedTiles(), "Only tile 0 should be quarantined");

        // Verify every pixel outside tile (0, 0) (i.e. x >= 16 or y >= 16) is 100% bit-exact,
        // proving tile 0's DED did not shift Section 4B LSB offsets for tiles 1..7!
        int framePixels = width * height;
        for (int t = 0; t < depthK; t++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (x < 16 && y < 16) {
                        continue; // quarantined tile (0, 0)
                    }
                    int idx = t * framePixels + y * width + x;
                    assertEquals(
                        frames[idx], dec.rgbaFrames()[idx],
                        "Zero DED contagion violated at (" + x + "," + y + ",t=" + t + ")"
                    );
                }
            }
        }
    }

    @Test
    void compressedDomainCropStripAndDistanceCoalesceMatchSpecification() throws LcvCorruptTileException {
        int width = 96;  // 6 tiles wide
        int height = 64; // 4 tiles high
        int depthK = 8;
        int[] frames = createProceduralFrames(width, height, depthK, 2026L);

        byte[] losslessSb = LcvStreamContainer.encodeSuperblock(
            frames, width, height, depthK, 0L, 0L, 2, 1,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );

        // 1. Compressed-domain cropSubRegion: extract tiles [1..3] x [1..2] (48x32 sub-canvas)
        long t0 = System.nanoTime();
        byte[] croppedSb = LcvOnDiskTransformer.cropSubRegion(losslessSb, 1, 1, 3, 2);
        long cropNs = System.nanoTime() - t0;
        assertTrue(cropNs < 50_000_000L);
        assertTrue(croppedSb.length < losslessSb.length);

        LcvStreamContainer.SuperblockDecodeResult croppedDec = LcvStreamContainer.decodeSuperblock(croppedSb, 0, null);
        assertEquals(48, croppedDec.header().width16());
        assertEquals(32, croppedDec.header().height16());

        for (int t = 0; t < depthK; t++) {
            for (int cy = 0; cy < 32; cy++) {
                for (int cx = 0; cx < 48; cx++) {
                    int srcIdx = t * (width * height) + (16 + cy) * width + (16 + cx);
                    int dstIdx = t * (48 * 32) + cy * 48 + cx;
                    assertEquals(frames[srcIdx], croppedDec.rgbaFrames()[dstIdx]);
                }
            }
        }

        // 2. Compressed-domain stripLsbPlanes (g7..g0 -> g7..g2) on both raw and Zstd-pooled superblocks
        byte[] strippedSb = LcvOnDiskTransformer.stripLsbPlanes(losslessSb);
        assertTrue(strippedSb.length < losslessSb.length, "Stripping Section 4B must reduce superblock byte size");
        LcvStreamContainer.SuperblockDecodeResult strippedDec = LcvStreamContainer.decodeSuperblock(strippedSb, 0, null);
        assertEquals(0, strippedDec.header().lsbPoolCompressedLen32());

        byte[] zstdSb = LcvStreamContainer.encodeSuperblock(
            frames, width, height, depthK, 0L, 0L, 2, 1,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, true, null
        );
        byte[] strippedZstdSb = LcvOnDiskTransformer.stripLsbPlanes(zstdSb);
        assertTrue(strippedZstdSb.length < zstdSb.length);
        LcvStreamContainer.SuperblockDecodeResult strippedZstdDec = LcvStreamContainer.decodeSuperblock(strippedZstdSb, 0, null);
        assertArrayEquals(strippedDec.rgbaFrames(), strippedZstdDec.rgbaFrames());

        // 3. Compressed-domain coalesceRunsByDistance on losslessSb must produce the EXACT same
        // bytes and decoded pixels as encoding from scratch with the same DistanceRlePolicy!
        LcvCubeCodec.DistanceRlePolicy policy = new LcvCubeCodec.DistanceRlePolicy(4, 1, 1.5);
        byte[] directPolicySb = LcvStreamContainer.encodeSuperblock(
            frames, width, height, depthK, 0L, 0L, 2, 1, policy, false, null
        );
        byte[] coalescedOnDiskSb = LcvOnDiskTransformer.coalesceRunsByDistance(losslessSb, 2, 1, policy);

        assertTrue(coalescedOnDiskSb.length < losslessSb.length, "Distance RLE coalescing must compact byte size");
        assertArrayEquals(
            directPolicySb, coalescedOnDiskSb,
            "Compressed-domain coalesceRunsByDistance must match direct DistanceRlePolicy encoding byte-for-byte"
        );

        LcvStreamContainer.SuperblockDecodeResult directDec = LcvStreamContainer.decodeSuperblock(directPolicySb, 0, null);
        LcvStreamContainer.SuperblockDecodeResult coalescedDec = LcvStreamContainer.decodeSuperblock(coalescedOnDiskSb, 0, null);
        assertArrayEquals(directDec.rgbaFrames(), coalescedDec.rgbaFrames());
    }

    @Test
    void mkvPacketizerMuxesAndDemuxesSuperblocksLosslessly() throws LcvCorruptTileException {
        int width = 64;
        int height = 48;
        int depthK = 4;
        int[] frames0 = createProceduralFrames(width, height, depthK, 11L);
        int[] frames1 = createProceduralFrames(width, height, depthK, 22L);

        byte[] sb0 = LcvStreamContainer.encodeSuperblock(
            frames0, width, height, depthK, 0L, 0L, 1, 1,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );
        byte[] sb1 = LcvStreamContainer.encodeSuperblock(
            frames1, width, height, depthK, depthK, sb0.length, 1, 1,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );

        byte[] mkvBytes = LcvMkvPacketizer.muxSuperblocksToMkv(List.of(sb0, sb1), width, height, 60);
        List<LcvMkvPacketizer.MkvSuperblockPacket> packets = LcvMkvPacketizer.extractSuperblocksFromMkv(mkvBytes);
        assertEquals(2, packets.size());

        LcvStreamContainer.SuperblockDecodeResult dec0 = LcvStreamContainer.decodeSuperblock(
            packets.get(0).superblockBytes(), 0, null
        );
        LcvStreamContainer.SuperblockDecodeResult dec1 = LcvStreamContainer.decodeSuperblock(
            packets.get(1).superblockBytes(), 0, null
        );
        assertArrayEquals(frames0, dec0.rgbaFrames());
        assertArrayEquals(frames1, dec1.rgbaFrames());
    }

    @Test
    void wirelessLowLatencyAndHarshRfUepProfilesSupportOnDiskTransforms() throws LcvCorruptTileException {
        int width = 64;
        int height = 48;

        // Test K = 1 (0-lookahead) with s = 3 LSB split bits (HARSH_RF_UEP_ZERO_WAIT lossless base)
        int[] framesK1 = createProceduralFrames(width, height, 1, 501L);
        LcvCubeCodec.TuningProfile uepLosslessBase =
            new LcvCubeCodec.TuningProfile(3, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, 1);
        byte[] sbK1 = LcvStreamContainer.encodeSuperblock(
            framesK1, width, height, 1, 0L, 0L, 2, 1, uepLosslessBase, false, null
        );
        LcvStreamContainer.SuperblockDecodeResult decK1 = LcvStreamContainer.decodeSuperblock(sbK1, 0, null);
        assertEquals(1, decK1.header().depthK8());
        assertEquals(3, decK1.header().tuningProfile().lsbSplitBits());
        assertArrayEquals(framesK1, decK1.rgbaFrames());

        // Compressed-domain coalesceRunsByDistance on s = 3 superblock must match direct HARSH_RF_UEP_ZERO_WAIT
        byte[] directUep = LcvStreamContainer.encodeSuperblock(
            framesK1, width, height, 1, 0L, 0L, 2, 1,
            LcvCubeCodec.TuningProfile.HARSH_RF_UEP_ZERO_WAIT, false, null
        );
        byte[] coalescedUep = LcvOnDiskTransformer.coalesceRunsByDistance(
            sbK1, 2, 1, LcvCubeCodec.TuningProfile.HARSH_RF_UEP_ZERO_WAIT.rlePolicy()
        );
        assertArrayEquals(directUep, coalescedUep);

        // Compressed-domain stripLsbPlanes on s = 3 superblock sheds 3 LSB planes (g2..g0) cleanly
        byte[] strippedUep = LcvOnDiskTransformer.stripLsbPlanes(sbK1);
        assertTrue(strippedUep.length < sbK1.length);
        LcvStreamContainer.SuperblockDecodeResult decStripped = LcvStreamContainer.decodeSuperblock(strippedUep, 0, null);
        assertEquals(0, decStripped.header().lsbPoolCompressedLen32());
    }

    @Test
    void variableSizeMotionVectorPacketizerGroupsCoherentMotionAndQuarantinesDroppedPacket() throws LcvCorruptTileException {
        int width = 128; // 8 tiles wide
        int height = 64; // 4 tiles high (32 tiles total)
        int depthK = 4;

        int[] frames0 = createProceduralFrames(width, height, depthK, 900L);
        int[] frames1 = createProceduralFrames(width, height, depthK, 901L);

        byte[] sb0 = LcvStreamContainer.encodeSuperblock(
            frames0, width, height, depthK, 0L, 0L, 4, 2,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );
        byte[] sb1 = LcvStreamContainer.encodeSuperblock(
            frames1, width, height, depthK, depthK, sb0.length, 4, 2,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );

        LcvStreamContainer.SuperblockHeader hdr1 = LcvStreamContainer.readSuperblockHeader(sb1, 0);
        List<LcvStreamContainer.MvCoherentPacket> mvPackets =
            LcvStreamContainer.packetizeSuperblockByMotionVector(sb1, 1400, 8);
        assertTrue(mvPackets.size() >= 2 && mvPackets.size() < hdr1.totalTiles(),
            "Coherent MV regions must coalesce multiple tiles per packet while respecting MTU bounds");

        // 1. Full arrival of all MV packets reconstructs 100% bit-exact frame
        LcvStreamContainer.StreamDecoderState decState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.decodeSuperblock(sb0, 0, decState);
        LcvStreamContainer.SuperblockDecodeResult fullDec =
            LcvStreamContainer.decodeMvPacketStream(hdr1, mvPackets, decState);
        assertArrayEquals(frames1, fullDec.rgbaFrames());
        // 2. Dropping one MV packet quarantines only that packet's tile span (falling back to sb0 cache)
        LcvStreamContainer.StreamDecoderState dropState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.decodeSuperblock(sb0, 0, dropState);
        LcvStreamContainer.MvCoherentPacket droppedPkt = mvPackets.get(mvPackets.size() - 1);
        List<LcvStreamContainer.MvCoherentPacket> partialPackets = mvPackets.subList(0, mvPackets.size() - 1);
        LcvStreamContainer.SuperblockDecodeResult partialDec =
            LcvStreamContainer.decodeMvPacketStream(hdr1, partialPackets, dropState);
        assertEquals(droppedPkt.tileCount(), partialDec.quarantinedTiles());
    }

    @Test
    void inlinePacketToPacketRecodeSurvivesMidRecodeCrashAndResumesByteIdentically() throws LcvCorruptTileException {
        int width = 64;  // 4 tiles wide
        int height = 64; // 4 tiles high (16 tiles total)
        int depthK = 4;

        int[] frames = createProceduralFrames(width, height, depthK, 2026L);
        byte[] losslessSb = LcvStreamContainer.encodeSuperblock(
            frames, width, height, depthK, 0L, 0L, 2, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, null
        );

        // Target profile changes both lsbSplitBits (2 -> 3) and rlePolicy (LOSSLESS -> HARSH_RF_UEP)
        LcvCubeCodec.TuningProfile targetProfile = LcvCubeCodec.TuningProfile.HARSH_RF_UEP_ZERO_WAIT;

        // 1. Reference uninterrupted single-pass inline recode across all 16 packets
        byte[] uninterruptedRecode = LcvOnDiskTransformer.recodePacketsInline(
            losslessSb, 2, 2, targetProfile, true, Integer.MAX_VALUE
        );
        LcvStreamContainer.SuperblockDecodeResult uninterruptedDec =
            LcvStreamContainer.decodeSuperblock(uninterruptedRecode, 0, null);
        assertEquals(0, uninterruptedDec.quarantinedTiles());

        // 2. Simulate mid-recode crash after recoding only 7 out of 16 packets!
        byte[] crashedMidRecode = LcvOnDiskTransformer.recodePacketsInline(
            losslessSb, 2, 2, targetProfile, true, 7
        );
        LcvStreamContainer.SuperblockHeader crashedHdr =
            LcvStreamContainer.readSuperblockHeader(crashedMidRecode, 0);
        assertTrue(
            (crashedHdr.flags8() & LcvStreamContainer.FLAG_INLINE_RECODE_PARTIAL) != 0,
            "Mid-recode crashed Superblock must carry FLAG_INLINE_RECODE_PARTIAL"
        );

        // 3. Verify the crashed hybrid Superblock (packets 0..6 in target codec, packets 7..15 in original lossless codec)
        // is immediately playable with 0 quarantined tiles:
        // - Packets 0..6 must match uninterruptedRecode's decoded pixels
        // - Packets 7..15 must still match the original lossless pixels bit-for-bit!
        LcvStreamContainer.SuperblockDecodeResult crashedDec =
            LcvStreamContainer.decodeSuperblock(crashedMidRecode, 0, null);
        assertEquals(0, crashedDec.quarantinedTiles());
        for (int t = 0; t < depthK; t++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int tileIdx = (y / 16) * 4 + (x / 16);
                    int pixIdx = t * (width * height) + y * width + x;
                    if (tileIdx < 7) {
                        assertEquals(
                            uninterruptedDec.rgbaFrames()[pixIdx],
                            crashedDec.rgbaFrames()[pixIdx],
                            "Recoded packet " + tileIdx + " mismatch in crashed stream"
                        );
                    } else {
                        assertEquals(
                            frames[pixIdx],
                            crashedDec.rgbaFrames()[pixIdx],
                            "Un-recoded packet " + tileIdx + " must remain bit-exact lossless in crashed stream"
                        );
                    }
                }
            }
        }

        // 4. Resume the crashed recode in two more steps (+5 packets, then +10 packets) and verify 100% byte-for-byte parity
        byte[] resumedStep1 = LcvOnDiskTransformer.resumeInlineRecode(crashedMidRecode, true, 5);
        LcvStreamContainer.SuperblockHeader step1Hdr = LcvStreamContainer.readSuperblockHeader(resumedStep1, 0);
        assertTrue((step1Hdr.flags8() & LcvStreamContainer.FLAG_INLINE_RECODE_PARTIAL) != 0);

        byte[] resumedFinal = LcvOnDiskTransformer.resumeInlineRecode(resumedStep1, true, 10);
        LcvStreamContainer.SuperblockHeader finalHdr = LcvStreamContainer.readSuperblockHeader(resumedFinal, 0);
        assertEquals(0, finalHdr.flags8() & LcvStreamContainer.FLAG_INLINE_RECODE_PARTIAL);
        assertArrayEquals(
            uninterruptedRecode,
            resumedFinal,
            "Resumed mid-crash recode must be 100% byte-for-byte identical to uninterrupted recode"
        );
    }
    private static int[] createProceduralFrames(int width, int height, int depthK, long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        int framePixels = width * height;
        int[] frames = new int[framePixels * depthK];
        for (int t = 0; t < depthK; t++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int tx = x / 16;
                    int ty = y / 16;
                    int rgba;
                    if (tx == 0 && ty == 0) {
                        // Solid transparent overlay tile
                        rgba = 0x00000000;
                    } else if (tx == 1 && ty == 0) {
                        // UI / HUD discrete palette tile
                        rgba = ((x + y) % 5 == 0) ? 0xF38BA8FF : 0x1E1E2EFF;
                    } else {
                        // Continuous shading gradient + fine sensor dither
                        int r = Math.max(0, Math.min(255, 60 + x * 2 + t * 2 + rng.nextInt(-2, 3)));
                        int g = Math.max(0, Math.min(255, 80 + y * 2 + t + rng.nextInt(-2, 3)));
                        int b = Math.max(0, Math.min(255, 110 + x + y + t * 3 + rng.nextInt(-2, 3)));
                        rgba = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                    }
                    frames[t * framePixels + y * width + x] = rgba;
                }
            }
        }
        return frames;
    }
}
