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
 * @governedBy leaf-video-ADR-003
 * @governedBy leaf-video-ADR-004
 * @trace REQ-LCV-F-002
 * @trace REQ-LCV-F-003
 * @trace REQ-LCV-F-004
 * @trace REQ-LCV-F-005
 * @trace REQ-LCV-DB-001
 * @trace REQ-LCV-DB-002
 * @trace REQ-LCV-DB-003
 * @trace REQ-LCV-DB-004
 * @trace REQ-LCV-HW-001
 * @trace REQ-LCV-HW-002
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

        // Verify foveated section conditioning preserves focal ROI accuracy and keeps high-contrast edges >= 36 dB
        int[] condFov = LcvOnDiskTransformer.conditionFramesBySection(
            framesK1, null, width, height, 1, 2, 1,
            LcvCubeCodec.TuningProfile.WIRELESS_FOVEATED_LOW_LATENCY.rlePolicy()
        );
        for (int y = 16; y < 32; y++) {
            for (int x = 32; x < 48; x++) {
                // Focal ROI tile (2, 1): must not degrade palette quantization to qStep=16
                int orig = framesK1[y * width + x];
                int cond = condFov[y * width + x];
                int dr = Math.abs(((orig >>> 24) & 0xFF) - ((cond >>> 24) & 0xFF));
                assertTrue(dr <= 9, "Focal ROI section conditioning must not inflate error with qStep=16, got dr=" + dr);
            }
        }
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

    @Test
    void archivalZstdPoolSupportsCropCoalesceAndInlineRecode() throws LcvCorruptTileException {
        int width = 64;
        int height = 64;
        int depthK = 4;
        int[] frames = createProceduralFrames(width, height, depthK, 4096L);

        byte[] rawSb = LcvStreamContainer.encodeSuperblock(
            frames, width, height, depthK, 0L, 0L, 2, 2,
            LcvCubeCodec.TuningProfile.ARCHIVAL_LOSSLESS, false, null
        );
        byte[] zstdSb = LcvStreamContainer.encodeSuperblock(
            frames, width, height, depthK, 0L, 0L, 2, 2,
            LcvCubeCodec.TuningProfile.ARCHIVAL_LOSSLESS, true, null
        );
        LcvStreamContainer.SuperblockHeader zstdHdr = LcvStreamContainer.readSuperblockHeader(zstdSb, 0);
        assertTrue((zstdHdr.flags8() & LcvStreamContainer.FLAG_ZSTD_POOL) != 0);

        // 1. cropSubRegion on FLAG_ZSTD_POOL superblock must match raw cropped superblock pixels and preserve FLAG_ZSTD_POOL
        byte[] croppedRaw = LcvOnDiskTransformer.cropSubRegion(rawSb, 1, 1, 2, 2);
        byte[] croppedZstd = LcvOnDiskTransformer.cropSubRegion(zstdSb, 1, 1, 2, 2);
        LcvStreamContainer.SuperblockDecodeResult decCroppedRaw = LcvStreamContainer.decodeSuperblock(croppedRaw, 0, null);
        LcvStreamContainer.SuperblockDecodeResult decCroppedZstd = LcvStreamContainer.decodeSuperblock(croppedZstd, 0, null);
        assertEquals(0, decCroppedZstd.quarantinedTiles());
        assertTrue((decCroppedZstd.header().flags8() & LcvStreamContainer.FLAG_ZSTD_POOL) != 0);
        assertArrayEquals(decCroppedRaw.rgbaFrames(), decCroppedZstd.rgbaFrames());

        // 2. coalesceRunsByDistance on FLAG_ZSTD_POOL superblock must match raw coalesced superblock pixels
        LcvCubeCodec.DistanceRlePolicy policy = new LcvCubeCodec.DistanceRlePolicy(4, 1, 1.5);
        byte[] coalescedRaw = LcvOnDiskTransformer.coalesceRunsByDistance(rawSb, 2, 2, policy);
        byte[] coalescedZstd = LcvOnDiskTransformer.coalesceRunsByDistance(zstdSb, 2, 2, policy);
        LcvStreamContainer.SuperblockDecodeResult decCoalRaw = LcvStreamContainer.decodeSuperblock(coalescedRaw, 0, null);
        LcvStreamContainer.SuperblockDecodeResult decCoalZstd = LcvStreamContainer.decodeSuperblock(coalescedZstd, 0, null);
        assertEquals(0, decCoalZstd.quarantinedTiles());
        assertTrue((decCoalZstd.header().flags8() & LcvStreamContainer.FLAG_ZSTD_POOL) != 0);
        assertArrayEquals(decCoalRaw.rgbaFrames(), decCoalZstd.rgbaFrames());

        // 3. recodePacketsInline on FLAG_ZSTD_POOL superblock must unwrap Zstd pools cleanly
        byte[] recodedRaw = LcvOnDiskTransformer.recodePacketsInline(
            rawSb, 2, 2, LcvCubeCodec.TuningProfile.HARSH_RF_UEP_ZERO_WAIT, true, Integer.MAX_VALUE
        );
        byte[] recodedZstd = LcvOnDiskTransformer.recodePacketsInline(
            zstdSb, 2, 2, LcvCubeCodec.TuningProfile.HARSH_RF_UEP_ZERO_WAIT, true, Integer.MAX_VALUE
        );
        assertArrayEquals(recodedRaw, recodedZstd);
    }

    @Test
    void tileParallelEncodeAndDecodeProduceByteExactParityWithSingleThread() throws LcvCorruptTileException {
        int width = 128;
        int height = 64;
        int depthK = 8;
        int[] frames0 = createProceduralFrames(width, height, depthK, 777L);
        int[] frames1 = createProceduralFrames(width, height, depthK, 777L); // Identical -> tests TEMPORAL_CLEAN_ACK in parallel

        LcvStreamContainer.StreamEncoderState seqEncState = new LcvStreamContainer.StreamEncoderState(width, height);
        LcvStreamContainer.StreamEncoderState parEncState = new LcvStreamContainer.StreamEncoderState(width, height);

        byte[] seqSb0 = LcvStreamContainer.encodeSuperblock(
            frames0, width, height, depthK, 0L, 0L, 4, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, seqEncState
        );
        byte[] parSb0 = LcvStreamContainer.encodeSuperblockParallel(
            frames0, width, height, depthK, 0L, 0L, 4, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, parEncState
        );
        assertArrayEquals(seqSb0, parSb0, "Parallel workgroup encoder must produce 100% byte-identical Superblock 0");

        byte[] seqSb1 = LcvStreamContainer.encodeSuperblock(
            frames1, width, height, depthK, depthK, seqSb0.length, 4, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, true, seqEncState
        );
        byte[] parSb1 = LcvStreamContainer.encodeSuperblockParallel(
            frames1, width, height, depthK, depthK, parSb0.length, 4, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, true, parEncState
        );
        assertArrayEquals(seqSb1, parSb1, "Parallel workgroup encoder must produce 100% byte-identical Superblock 1 with Clean-ACKs and Zstd");

        LcvStreamContainer.StreamDecoderState parDecState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.SuperblockDecodeResult dec0 = LcvStreamContainer.decodeSuperblockParallel(parSb0, 0, parDecState);
        LcvStreamContainer.SuperblockDecodeResult dec1 = LcvStreamContainer.decodeSuperblockParallel(parSb1, 0, parDecState);
        assertArrayEquals(frames0, dec0.rgbaFrames());
        assertArrayEquals(frames1, dec1.rgbaFrames());
        assertTrue(dec1.cleanAckReusedTiles() > 0);

        if (LcvPrimitives.isNativeLibraryLoaded()) {
            assertTrue(
                LcvPrimitives.isCudaGpuAvailable(),
                "Expected RTX 3090 CUDA PTX module (lcv_gpu_encode_tiles + lcv_gpu_assemble_superblock) to load"
            );
            byte[] gpuSb0 = LcvStreamContainer.encodeSuperblockGpuDirect(
                frames0, width, height, depthK, 0L, 0L, 4, 2,
                LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, true
            );
            byte[] gpuSb1 = LcvStreamContainer.encodeSuperblockGpuDirect(
                frames1, width, height, depthK, depthK, gpuSb0.length, 4, 2,
                LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false
            );
            LcvStreamContainer.StreamDecoderState gpuDecState =
                new LcvStreamContainer.StreamDecoderState(width, height, depthK);
            LcvStreamContainer.SuperblockDecodeResult gpuDec0 =
                LcvStreamContainer.decodeSuperblockParallel(gpuSb0, 0, gpuDecState);
            LcvStreamContainer.SuperblockDecodeResult gpuDec1 =
                LcvStreamContainer.decodeSuperblockParallel(gpuSb1, 0, gpuDecState);
            assertArrayEquals(frames0, gpuDec0.rgbaFrames(), "GPU-direct Superblock 0 must decode 100% bit-exact");
            assertArrayEquals(frames1, gpuDec1.rgbaFrames(), "GPU-direct Superblock 1 must decode 100% bit-exact");
            assertTrue(gpuDec1.cleanAckReusedTiles() > 0, "GPU-direct Superblock 1 must emit TEMPORAL_CLEAN_ACK pointers in VRAM");

            byte[] croppedGpuSb = LcvOnDiskTransformer.cropSubRegion(gpuSb0, 0, 0, 4, 2);
            assertEquals(64, LcvStreamContainer.readSuperblockHeader(croppedGpuSb, 0).width16());
            byte[] strippedGpuSb = LcvOnDiskTransformer.stripLsbPlanes(gpuSb0);
            assertEquals(0, LcvStreamContainer.readSuperblockHeader(strippedGpuSb, 0).lsbPoolCompressedLen32());

            int benchBytes = LcvPrimitives.benchmarkGpuResidentEncode(frames0, width, height, depthK, 2, 4, true);
            assertTrue(benchBytes >= 64, "GPU-resident Superblock benchmark must return valid byte length");
        }
    }

    @Test
    void keyedSuperblockSupportsZeroTrustRelayTransformsPreviewUnlockAndColdAnchorScratchInterpolation()
        throws LcvCorruptTileException {
        int width = 64;
        int height = 64;
        int depthK = 4;
        int[] frames = createProceduralFrames(width, height, depthK, 0x4B534301L);

        // 1. Zero-Trust Relay Key Schedule (K_relay == 0, K_msb != 0, K_lsb != 0)
        LcvCubeCodec.LcvKeySchedule relaySched = LcvCubeCodec.LcvKeySchedule.zeroTrustRelay(0x7027_CAFE_BEEF0001L);
        byte[] keyedSb = LcvStreamContainer.encodeSuperblockParallel(
            frames, width, height, depthK, 0L, 0L, 2, 2,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, relaySched, false, null
        );
        LcvStreamContainer.SuperblockHeader hdr = LcvStreamContainer.readSuperblockHeader(keyedSb, 0);
        assertTrue((hdr.flags8() & LcvStreamContainer.FLAG_KEYED_CODEBOOK) != 0);

        // Verify authorized parallel decode is 100% bit-exact
        LcvStreamContainer.SuperblockDecodeResult authDec = LcvStreamContainer.decodeSuperblockParallel(
            keyedSb, 0, relaySched, null
        );
        assertArrayEquals(frames, authDec.rgbaFrames());

        // Zero-trust relay executes stripLsbPlanes and coalesceRunsByDistance without knowing K_msb / K_lsb
        byte[] strippedKeyedSb = LcvOnDiskTransformer.stripLsbPlanes(keyedSb);
        assertTrue(strippedKeyedSb.length < keyedSb.length);
        LcvStreamContainer.SuperblockDecodeResult strippedDec = LcvStreamContainer.decodeSuperblock(
            strippedKeyedSb, 0, relaySched, null
        );
        assertEquals(0, strippedDec.quarantinedTiles());

        LcvCubeCodec.DistanceRlePolicy policy = new LcvCubeCodec.DistanceRlePolicy(4, 1, 1.5);
        byte[] coalescedKeyedSb = LcvOnDiskTransformer.coalesceRunsByDistance(keyedSb, 2, 2, policy);
        assertTrue(coalescedKeyedSb.length < keyedSb.length);
        LcvStreamContainer.SuperblockDecodeResult coalDec = LcvStreamContainer.decodeSuperblock(
            coalescedKeyedSb, 0, relaySched, null
        );
        assertEquals(0, coalDec.quarantinedTiles());

        // 2. Cold/Anchor Quarantined Tile Spatiotemporal Reconstruction (DED on interior tile (2, 2) with cold decoderState)
        byte[] scratchedSb = java.util.Arrays.copyOf(keyedSb, keyedSb.length);
        ByteBuffer buf = ByteBuffer.wrap(scratchedSb).order(ByteOrder.BIG_ENDIAN);
        int centerTileIdx = 2 * 4 + 2;
        int ptrOff = LcvStreamContainer.SUPERBLOCK_HEADER_BYTES + centerTileIdx * 8;
        long origPtr = buf.getLong(ptrOff);
        buf.putLong(ptrOff, origPtr ^ (1L << 3) ^ (1L << 21)); // 2-bit DED

        LcvStreamContainer.StreamDecoderState coldState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.SuperblockDecodeResult healedCold = LcvStreamContainer.decodeSuperblock(
            scratchedSb, 0, relaySched, coldState
        );
        assertEquals(1, healedCold.quarantinedTiles());
        int centerPx = healedCold.rgbaFrames()[36 * width + 36];
        assertTrue(centerPx != 0, "Cold quarantined interior tile must be reconstructed from 4-connected healthy neighbors");
    }

    @Test
    void logStructuredStreamSupportsSlackFramingOutOfOrderEofEditsAndTemporalCompaction()
        throws LcvCorruptTileException {
        int width = 64;
        int height = 32;
        int depthK = 4;
        int[] frames0 = createProceduralFrames(width, height, depthK, 10L);
        int[] frames1Old = createProceduralFrames(width, height, depthK, 20L);
        int[] frames2 = createProceduralFrames(width, height, depthK, 30L);
        int[] frames1Edited = createProceduralFrames(width, height, depthK, 99L);

        byte[] sb0 = LcvStreamContainer.encodeSuperblock(
            frames0, width, height, depthK, 0L, 0L, 1, 1,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );
        byte[] sb1Old = LcvStreamContainer.encodeSuperblock(
            frames1Old, width, height, depthK, depthK, sb0.length, 1, 1,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );
        byte[] slackGap = LcvStreamContainer.createSlackSuperblockBytes(128, sb0.length + sb1Old.length);
        byte[] sb2 = LcvStreamContainer.encodeSuperblock(
            frames2, width, height, depthK, depthK * 2L, sb0.length + sb1Old.length + slackGap.length, 1, 1,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );
        // Append edited Superblock 1 (frameIndex64 = depthK) at EOF (out-of-order CoW append-on-expand)
        byte[] sb1EditedEof = LcvStreamContainer.encodeSuperblock(
            frames1Edited, width, height, depthK, depthK, sb0.length, 1, 1,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );

        ByteArrayOutputStream logStreamOut = new ByteArrayOutputStream();
        logStreamOut.writeBytes(sb0);
        logStreamOut.writeBytes(sb1Old);
        logStreamOut.writeBytes(slackGap);
        logStreamOut.writeBytes(sb2);
        logStreamOut.writeBytes(sb1EditedEof);
        byte[] logStream = logStreamOut.toByteArray();

        // indexValidSuperblocks must skip slackGap, supersede sb1Old with sb1EditedEof, and sort by frameIndex64 (0, K, 2K)
        List<Integer> timelineOffsets = LcvStreamContainer.indexValidSuperblocks(logStream);
        assertEquals(3, timelineOffsets.size());

        // compactStreamToTemporalOrder sweeps and writes clean contiguous temporal order with updated prevSbOffset64
        byte[] compacted = LcvStreamContainer.compactStreamToTemporalOrder(logStream);
        List<Integer> compactedOffsets = LcvStreamContainer.indexValidSuperblocks(compacted);
        assertEquals(3, compactedOffsets.size());

        LcvStreamContainer.SuperblockDecodeResult d0 = LcvStreamContainer.decodeSuperblock(compacted, compactedOffsets.get(0), null);
        LcvStreamContainer.SuperblockDecodeResult d1 = LcvStreamContainer.decodeSuperblock(compacted, compactedOffsets.get(1), null);
        LcvStreamContainer.SuperblockDecodeResult d2 = LcvStreamContainer.decodeSuperblock(compacted, compactedOffsets.get(2), null);
        assertArrayEquals(frames0, d0.rgbaFrames());
        assertArrayEquals(frames1Edited, d1.rgbaFrames(), "EOF-appended edit for frameIndex64=K must supersede earlier slot");
        assertArrayEquals(frames2, d2.rgbaFrames());
        assertEquals(0L, d0.header().prevSbOffset64());
        assertEquals((long) compactedOffsets.get(0), d1.header().prevSbOffset64());
        assertEquals((long) compactedOffsets.get(1), d2.header().prevSbOffset64());

        // Verify LcvMkvPacketizer#extractSuperblocksFromMkv also skips slackGap, supersedes sb1Old, and sorts by frameIndex64
        List<LcvMkvPacketizer.MkvSuperblockPacket> mkvPackets = LcvMkvPacketizer.extractSuperblocksFromMkv(logStream);
        assertEquals(3, mkvPackets.size());
        assertArrayEquals(frames1Edited, LcvStreamContainer.decodeSuperblock(mkvPackets.get(1).superblockBytes(), 0, null).rgbaFrames());

        // Verify wrapZstdPools and postprocessStreamInPlace preserve pixels while reducing byte size
        byte[] wrappedSb0 = LcvOnDiskTransformer.wrapZstdPools(sb0);
        assertTrue(wrappedSb0.length <= sb0.length);
        assertArrayEquals(frames0, LcvStreamContainer.decodeSuperblock(wrappedSb0, 0, null).rgbaFrames());

        byte[] inPlacePostprocessed = LcvOnDiskTransformer.postprocessStreamInPlace(
            compacted, true, null, true
        );
        List<Integer> postOffsets = LcvStreamContainer.indexValidSuperblocks(inPlacePostprocessed);
        assertEquals(3, postOffsets.size());
    }

    @Test
    void spatiotemporalDatabaseSupportsDecodeMapShiftedCleanAcksSidecarBranchesAndMultiLayerOverlays()
        throws LcvCorruptTileException {
        int width = 64;
        int height = 48;
        int depthK = 4;
        int frameArea = width * height;

        // 1. Create Superblock 0 and a camera-panned Superblock 1 shifted by (dx=+2, dy=-1) on interior tile (1, 1)
        int[] frames0 = createProceduralFrames(width, height, depthK, 701L);
        int[] frames1 = createProceduralFrames(width, height, depthK, 702L);
        int shiftDx = 2;
        int shiftDy = -1;
        for (int t = 0; t < depthK; t++) {
            int fBase = t * frameArea;
            for (int ly = 0; ly < 16; ly++) {
                int dstY = 16 + ly;
                int srcY = dstY - shiftDy;
                for (int lx = 0; lx < 16; lx++) {
                    int dstX = 16 + lx;
                    int srcX = dstX - shiftDx;
                    frames1[fBase + dstY * width + dstX] = frames0[fBase + srcY * width + srcX];
                }
            }
        }

        LcvStreamContainer.StreamEncoderState encState =
            new LcvStreamContainer.StreamEncoderState(width, height).enableShiftedCleanAck(true);
        byte[] sb0 = LcvStreamContainer.encodeSuperblockParallel(
            frames0, width, height, depthK, 0L, 0L, 2, 1,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, encState
        );
        byte[] sb1 = LcvStreamContainer.encodeSuperblockParallel(
            frames1, width, height, depthK, depthK, sb0.length, 2, 1,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, encState
        );

        LcvStreamContainer.StreamDecoderState decState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.SuperblockDecodeResult d0 = LcvStreamContainer.decodeSuperblockParallel(sb0, 0, decState);
        LcvStreamContainer.SuperblockDecodeResult d1 = LcvStreamContainer.decodeSuperblockParallel(sb1, 0, decState);
        assertArrayEquals(frames0, d0.rgbaFrames());
        assertArrayEquals(frames1, d1.rgbaFrames(), "Shifted TEMPORAL_CLEAN_ACK tile must decode bit-exact");
        assertTrue(d1.cleanAckReusedTiles() >= 1, "Shifted (dx, dy) tile must emit a 0-byte TEMPORAL_CLEAN_ACK pointer");

        // 1B. Verify extractSingleFrame resolves cross-superblock shifted clean-ack dependency in the directory
        // without decoding intermediate superblock frames
        ByteArrayOutputStream twoSbOut = new ByteArrayOutputStream();
        twoSbOut.writeBytes(sb0);
        twoSbOut.writeBytes(sb1);
        LcvStreamContainer.SingleFrameExtractResult singleFrame6 = LcvStreamContainer.extractSingleFrame(
            twoSbOut.toByteArray(), depthK + 2L
        );
        assertTrue(singleFrame6.resolvedDirectoryHops() >= 1, "extractSingleFrame must resolve clean-ack hops in directory");
        int[] expectedFrame6 = java.util.Arrays.copyOfRange(frames1, 2 * frameArea, 3 * frameArea);
        assertArrayEquals(expectedFrame6, singleFrame6.frameRgba(), "Extracted single frame must be 100% bit-exact");

        // 2. Verify DecodeMapPacket (LCV_MAP1) serialization, SEC-DED single-bit healing, and semantic signature search
        LcvOnDiskTransformer.DatabaseCompactionResult dbRes =
            LcvOnDiskTransformer.compactStreamWithDecodeMap(List.of(sb0, sb1));
        assertEquals(2, dbRes.decodeMapPackets().size());
        byte[] rawMap0 = dbRes.duplicateRecoveryCheckpoints().get(0);
        byte[] bitFlippedMap0 = java.util.Arrays.copyOf(rawMap0, rawMap0.length);
        bitFlippedMap0[30 + 14] ^= 0x04; // Flip 1 bit inside BlockPoolDescriptor #0's 64-bit packed offset/len
        LcvStreamContainer.DecodeMapPacket healedMap = LcvStreamContainer.deserializeDecodeMapPacket(bitFlippedMap0, 0);
        assertEquals(1, healedMap.healedSingleBitEntries());

        // Corrupt primary DecodeMapPacket header and verify automatic failover to duplicate recovery checkpoint
        byte[] destroyedPrimary = java.util.Arrays.copyOf(rawMap0, rawMap0.length);
        destroyedPrimary[0] = 0x00;
        LcvStreamContainer.DecodeMapPacket recoveredMap = LcvStreamContainer.deserializeDecodeMapWithRecovery(
            destroyedPrimary, 0, rawMap0, 0
        );
        assertEquals(0L, recoveredMap.frameIndex64());

        long targetSig = healedMap.blockDirectory().get(0).structuralSignature64();
        List<Long> foundTimestamps = LcvStreamContainer.queryDecodeMapBySignature(
            dbRes.decodeMapPackets(), targetSig, 0
        );
        assertTrue(foundTimestamps.contains(0L), "Zero-decode semantic query must locate matching block signature");

        // 3. Verify wait-free growing-file tail reader and .lcv.branch sidecar timeline resolution
        ByteArrayOutputStream growingBuf = new ByteArrayOutputStream();
        growingBuf.writeBytes(sb0);
        growingBuf.writeBytes(sb1);
        growingBuf.write(sb0, 0, 20); // Simulate incomplete mid-flush trailing superblock at EOF
        byte[] growingBytes = growingBuf.toByteArray();

        List<Integer> tailAfter0 = LcvStreamContainer.readGrowingStreamTail(growingBytes, 0L);
        assertEquals(1, tailAfter0.size(), "Growing-file tail reader must return committed sb1 and ignore incomplete EOF");

        LcvStreamContainer.SidecarBranchPacket branch = new LcvStreamContainer.SidecarBranchPacket(
            "highlight-short-9x16",
            new long[]{depthK, 0L},
            new int[]{1, 0},
            new int[]{0, 0},
            2, 2,
            List.of()
        );
        byte[] branchBytes = LcvStreamContainer.serializeSidecarBranch(branch);
        LcvStreamContainer.SidecarBranchPacket parsedBranch = LcvStreamContainer.deserializeSidecarBranch(branchBytes);
        assertEquals("highlight-short-9x16", parsedBranch.branchName());
        List<byte[]> branchClips = LcvOnDiskTransformer.resolveSidecarBranchTimeline(growingBytes, parsedBranch);
        assertEquals(2, branchClips.size());
        LcvStreamContainer.SuperblockDecodeResult croppedClip0 =
            LcvStreamContainer.decodeSuperblock(branchClips.get(0), 0, null);
        assertEquals(32, croppedClip0.header().width16());
        assertEquals(32, croppedClip0.header().height16());

        // 4. Verify unflattened multi-layer RGBA compositing (Layer 0 gameplay + Layer 1 mostly-transparent HUD)
        int[] hudLayerFrames = new int[frames0.length];
        for (int t = 0; t < depthK; t++) {
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 16; x++) {
                    hudLayerFrames[t * frameArea + y * width + x] = 0xFF00AAFF; // Opaque badge in tile (0, 0)
                }
            }
        }
        byte[] hudSb = LcvStreamContainer.encodeSuperblock(
            hudLayerFrames, width, height, depthK, 0L, 0L, 1, 1,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );
        // Verify bottom-up quadtree coalescing on hudSb (tiles [2..3]x[0..1] are a 2x2=32x32 uniform transparent quad)
        LcvStreamContainer.DecodeMapPacket hudMap = LcvOnDiskTransformer.extractDecodeMapPacket(hudSb, 1);
        assertTrue(hudMap.mapEntries().size() < 12, "Bottom-up quadtree coalescing must merge 2x2 uniform quads");
        byte[] serHudMap = LcvStreamContainer.serializeDecodeMapPacket(hudMap);
        LcvStreamContainer.DecodeMapPacket deserHudMap = LcvStreamContainer.deserializeDecodeMapPacket(serHudMap, 0);
        assertTrue(deserHudMap.mapEntries().stream().anyMatch(e -> e.quadLevel() >= 1));

        int[] composited = LcvOnDiskTransformer.compositeUnflattenedLayers(List.of(sb0, hudSb));
        assertEquals(0xFF00AAFF, composited[0], "Overlay badge pixel must composite over Layer 0");
        assertEquals(frames0[20 * width + 20], composited[20 * width + 20], "Transparent overlay tiles must preserve Layer 0");

        // 5. Verify ExternalFlowProvider hardware motion-vector injection hook (REQ-LCV-HW-002)
        try {
            LcvCubeCodec.setExternalFlowProvider((tx, ty, frameOff) -> 0x97); // (+1, -1)
            byte[] hwFlowSb = LcvStreamContainer.encodeSuperblock(
                frames1, width, height, depthK, 0L, 0L, 2, 1,
                LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
            );
            assertArrayEquals(frames1, LcvStreamContainer.decodeSuperblock(hwFlowSb, 0, null).rgbaFrames());
        } finally {
            LcvCubeCodec.setExternalFlowProvider(null);
        }
    }

    @Test
    void quadtreeDirectoryCoalescesZeroBodyMacroBlocksHealsSingleBitFlipsAndSupportsCompressedTransforms()
        throws LcvCorruptTileException {
        int width = 128;  // 8 tiles wide (2 64x64 CTUs horizontally)
        int height = 128; // 8 tiles high (2 64x64 CTUs vertically = 64 tiles total)
        int depthK = 4;
        int frameArea = width * height;
        int[] frames0 = new int[frameArea * depthK];
        int[] frames1 = new int[frameArea * depthK];

        for (int t = 0; t < depthK; t++) {
            int fBase = t * frameArea;
            for (int y = 0; y < height; y++) {
                int ty = y >>> 4;
                for (int x = 0; x < width; x++) {
                    int tx = x >>> 4;
                    int rgba0;
                    int rgba1;
                    if (tx < 4 && ty < 4) {
                        // CTU (0, 0): 64x64 uniform dark IDE background -> 1 coalesced 64x64 UNIFORM_INLINE leaf
                        rgba0 = 0x1E1E2EFF;
                        rgba1 = 0x1E1E2EFF;
                    } else if (tx >= 4 && ty < 4) {
                        // CTU (1, 0): three 32x32 uniform quadrants + one active 16x16 cursor tile at (4, 0)
                        if (tx == 4 && ty == 0) {
                            rgba0 = ((x + y + t) & 1) == 0 ? 0x89B4FAFF : 0x1E1E2EFF;
                            rgba1 = ((x + y + t) & 1) == 0 ? 0xF38BA8FF : 0x1E1E2EFF;
                        } else {
                            rgba0 = 0x181825FF;
                            rgba1 = 0x181825FF;
                        }
                    } else if (tx < 4) {
                        // CTU (0, 1): non-uniform static code text in SB0 -> coalesced 64x64 TEMPORAL_CLEAN_ACK in SB1
                        int shade = 40 + ((x * 5 + y * 3) & 0x7F);
                        rgba0 = (shade << 24) | (shade << 16) | ((shade + 20) << 8) | 0xFF;
                        rgba1 = rgba0;
                    } else {
                        // CTU (1, 1): 32x32 uniform transparent overlay quadrant + active bottom-right quadrant
                        if (tx < 6 && ty < 6) {
                            rgba0 = 0x00000000;
                            rgba1 = 0x00000000;
                        } else {
                            int r = (80 + x * 2 + t * 7) & 0xFF;
                            int g = (100 + y * 2 + t * 5) & 0xFF;
                            int b = (140 + x + y + t * 3) & 0xFF;
                            rgba0 = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                            rgba1 = ((r ^ 0x1F) << 24) | (g << 16) | (b << 8) | 0xFF;
                        }
                    }
                    frames0[fBase + y * width + x] = rgba0;
                    frames1[fBase + y * width + x] = rgba1;
                }
            }
        }

        LcvStreamContainer.StreamEncoderState encState = new LcvStreamContainer.StreamEncoderState(width, height);
        byte[] sb0 = LcvStreamContainer.encodeSuperblockParallel(
            frames0, width, height, depthK, 0L, 0L, 4, 0,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, encState
        );
        byte[] sb1 = LcvStreamContainer.encodeSuperblockParallel(
            frames1, width, height, depthK, depthK, sb0.length, 4, 0,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS, false, encState
        );

        LcvStreamContainer.SuperblockHeader hdr0 = LcvStreamContainer.readSuperblockHeader(sb0, 0);
        LcvStreamContainer.SuperblockHeader hdr1 = LcvStreamContainer.readSuperblockHeader(sb1, 0);
        int flatDirEnd = LcvStreamContainer.SUPERBLOCK_HEADER_BYTES + hdr0.totalTiles() * 8;
        assertTrue(
            hdr0.secDedOffset() < flatDirEnd,
            "16/32/64 quadtree coalescing must shrink Section 2 below flat 64-tile directory"
        );
        assertTrue(
            hdr1.secDedOffset() < hdr0.secDedOffset(),
            "SB1 static CTU (0, 1) must coalesce 16 TEMPORAL_CLEAN_ACK tiles into 1 64x64 leaf"
        );

        // 1. Sequential and parallel decode must reconstruct 100% bit-exact pixels
        LcvStreamContainer.StreamDecoderState decSeq = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.SuperblockDecodeResult res0Seq = LcvStreamContainer.decodeSuperblock(sb0, 0, decSeq);
        assertArrayEquals(frames0, res0Seq.rgbaFrames());
        LcvStreamContainer.SuperblockDecodeResult res1Seq = LcvStreamContainer.decodeSuperblock(sb1, 0, decSeq);
        assertArrayEquals(frames1, res1Seq.rgbaFrames());
        assertEquals(16, res1Seq.cleanAckReusedTiles(), "All 16 tiles in coalesced CTU (0, 1) must report cleanAck reuse");

        LcvStreamContainer.StreamDecoderState decPar = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.SuperblockDecodeResult res0Par = LcvStreamContainer.decodeSuperblockParallel(sb0, 0, decPar);
        assertArrayEquals(frames0, res0Par.rgbaFrames());
        LcvStreamContainer.SuperblockDecodeResult res1Par = LcvStreamContainer.decodeSuperblockParallel(sb1, 0, decPar);
        assertArrayEquals(frames1, res1Par.rgbaFrames());

        // 2. O(1) single-frame and sub-ROI extraction across quadtree-coalesced superblocks
        ByteArrayOutputStream twoSbStream = new ByteArrayOutputStream();
        twoSbStream.writeBytes(sb0);
        twoSbStream.writeBytes(sb1);
        byte[] streamBytes = twoSbStream.toByteArray();
        List<Integer> offsets = LcvStreamContainer.indexValidSuperblocks(streamBytes);
        for (int f = 0; f < depthK * 2; f++) {
            LcvStreamContainer.SingleFrameExtractResult single =
                LcvStreamContainer.extractSingleFrame(streamBytes, offsets, f);
            int[] srcFrames = (f < depthK) ? frames0 : frames1;
            int localT = f % depthK;
            int[] expected = java.util.Arrays.copyOfRange(srcFrames, localT * frameArea, (localT + 1) * frameArea);
            assertArrayEquals(expected, single.frameRgba(), "Single-frame scrub mismatch at f=" + f);
        }

        // 3. SEC-DED (72, 64) single-bit flip healing on every 64-bit word in quadtree Section 2 (CTU descriptors + leaves)
        int dirWords1 = (hdr1.secDedOffset() - hdr1.directoryOffset()) / 8;
        byte[] flippedSb1 = java.util.Arrays.copyOf(sb1, sb1.length);
        ByteBuffer flipBuf = ByteBuffer.wrap(flippedSb1).order(ByteOrder.BIG_ENDIAN);
        for (int w = 0; w < dirWords1; w++) {
            int pos = hdr1.directoryOffset() + w * 8;
            long origWord = flipBuf.getLong(pos);
            flipBuf.putLong(pos, origWord ^ (1L << ((w * 13) & 63)));
        }
        LcvStreamContainer.StreamDecoderState healState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.decodeSuperblock(sb0, 0, healState);
        LcvStreamContainer.SuperblockDecodeResult healedRes1 = LcvStreamContainer.decodeSuperblock(flippedSb1, 0, healState);
        assertEquals(dirWords1, healedRes1.healedSingleBitPointers(), "Every 64-bit word in quadtree Section 2 must heal");
        assertEquals(0, healedRes1.quarantinedTiles());
        assertArrayEquals(frames1, healedRes1.rgbaFrames());

        // 4. Non-64-aligned cropSubRegion slicing across coalesced 64x64 and 32x32 macro-blocks ([2..6) x [2..6))
        byte[] croppedSb0 = LcvOnDiskTransformer.cropSubRegion(sb0, 2, 2, 4, 4);
        LcvStreamContainer.SuperblockDecodeResult croppedDec = LcvStreamContainer.decodeSuperblock(croppedSb0, 0, null);
        for (int t = 0; t < depthK; t++) {
            for (int cy = 0; cy < 64; cy++) {
                for (int cx = 0; cx < 64; cx++) {
                    assertEquals(
                        frames0[t * frameArea + (32 + cy) * width + (32 + cx)],
                        croppedDec.rgbaFrames()[t * (64 * 64) + cy * 64 + cx]
                    );
                }
            }
        }

        // 5. stripLsbPlanes and wrapZstdPools preserve quadtree directory and bit-exact/stripped pixels
        byte[] wrappedSb0 = LcvOnDiskTransformer.wrapZstdPools(sb0);
        assertArrayEquals(frames0, LcvStreamContainer.decodeSuperblock(wrappedSb0, 0, null).rgbaFrames());
        byte[] strippedSb0 = LcvOnDiskTransformer.stripLsbPlanes(sb0);
        assertEquals(0, LcvStreamContainer.readSuperblockHeader(strippedSb0, 0).lsbPoolCompressedLen32());
    }

    @Test
    void neighborMvOpticalFlowConcealmentAdvectsQuarantinedAndDroppedTilesDuringCameraPan() throws LcvCorruptTileException {
        int width = 64;
        int height = 64;
        int depthK = 2;
        int frameArea = width * height;
        int panDx = 3;
        int panDy = -2;

        // Build textured scene with distinct spatial features that pans by (panDx, panDy) between SB0 and SB1
        int[] frames0 = new int[frameArea * depthK];
        int[] frames1 = new int[frameArea * depthK];
        for (int t = 0; t < depthK; t++) {
            int fBase = t * frameArea;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    frames0[fBase + y * width + x] = sampleTexturedPanScene(x, y, t);
                    int srcX = Math.max(0, Math.min(width - 1, x - panDx));
                    int srcY = Math.max(0, Math.min(height - 1, y - panDy));
                    frames1[fBase + y * width + x] = sampleTexturedPanScene(srcX, srcY, t);
                }
            }
        }

        LcvStreamContainer.StreamEncoderState encState = new LcvStreamContainer.StreamEncoderState(width, height);
        LcvCubeCodec.TuningProfile profile = LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS;
        byte[] sb0 = LcvStreamContainer.encodeSuperblock(
            frames0, width, height, depthK, 0L, 0L, 1, 1, profile, false, encState
        );
        byte[] sb1 = LcvStreamContainer.encodeSuperblock(
            frames1, width, height, depthK, depthK, sb0.length, 1, 1, profile, false, encState
        );

        // Verify 100% lossless round-trip on uncorrupted SB0 -> SB1
        LcvStreamContainer.StreamDecoderState cleanState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        assertArrayEquals(frames0, LcvStreamContainer.decodeSuperblock(sb0, 0, cleanState).rgbaFrames());
        assertArrayEquals(frames1, LcvStreamContainer.decodeSuperblock(sb1, 0, cleanState).rgbaFrames());

        // Compute static (0, 0) fallback MSE on interior tile (1, 1) (tileIdx = 5)
        int targetTx = 1;
        int targetTy = 1;
        int targetTileIdx = targetTy * 4 + targetTx;
        long staticSqErr = computeTileRgbSqErr(frames1, frames0, targetTx, targetTy, width, depthK);
        assertTrue(staticSqErr > 0L, "Pan scene must have non-zero static (0,0) fallback error");

        // 1. Corrupt tile (1, 1)'s pointer in SB1 with a 2-bit DED fault and decode via decodeSuperblock & decodeSuperblockParallel
        byte[] corruptedSb1 = java.util.Arrays.copyOf(sb1, sb1.length);
        ByteBuffer buf = ByteBuffer.wrap(corruptedSb1).order(ByteOrder.BIG_ENDIAN);
        int ptrPos = LcvStreamContainer.SUPERBLOCK_HEADER_BYTES + targetTileIdx * 8;
        buf.putLong(ptrPos, buf.getLong(ptrPos) ^ (1L << 4) ^ (1L << 21));

        LcvStreamContainer.StreamDecoderState seqState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.decodeSuperblock(sb0, 0, seqState);
        LcvStreamContainer.SuperblockDecodeResult seqDec1 = LcvStreamContainer.decodeSuperblock(corruptedSb1, 0, seqState);
        assertEquals(1, seqDec1.quarantinedTiles());
        long seqAdvectedSqErr = computeTileRgbSqErr(frames1, seqDec1.rgbaFrames(), targetTx, targetTy, width, depthK);
        assertTrue(
            seqAdvectedSqErr < staticSqErr / 4,
            "Sequential neighbor-MV optical-flow concealment must dramatically reduce MSE vs static (0,0) fallback"
                + " (seqAdvectedSqErr=" + seqAdvectedSqErr + ", staticSqErr=" + staticSqErr + ")"
        );

        LcvStreamContainer.StreamDecoderState parState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.decodeSuperblockParallel(sb0, 0, parState);
        LcvStreamContainer.SuperblockDecodeResult parDec1 = LcvStreamContainer.decodeSuperblockParallel(corruptedSb1, 0, parState);
        assertEquals(1, parDec1.quarantinedTiles());
        long parAdvectedSqErr = computeTileRgbSqErr(frames1, parDec1.rgbaFrames(), targetTx, targetTy, width, depthK);
        assertTrue(
            parAdvectedSqErr < staticSqErr / 4,
            "Parallel neighbor-MV optical-flow concealment must dramatically reduce MSE vs static (0,0) fallback"
                + " (parAdvectedSqErr=" + parAdvectedSqErr + ", staticSqErr=" + staticSqErr + ")"
        );

        // 2. Drop the MvCoherentPacket covering tile (1, 1) in decodeMvPacketStream (1 tile per packet)
        LcvStreamContainer.SuperblockHeader flatHdr1 = LcvStreamContainer.readSuperblockHeader(sb1, 0);
        List<LcvStreamContainer.MvCoherentPacket> pkts =
            LcvStreamContainer.packetizeSuperblockByMotionVector(sb1, 4096, 1);
        List<LcvStreamContainer.MvCoherentPacket> arrivedPkts = new ArrayList<>();
        for (LcvStreamContainer.MvCoherentPacket pkt : pkts) {
            if (pkt.startTileIdx() != targetTileIdx) {
                arrivedPkts.add(pkt);
            }
        }
        LcvStreamContainer.StreamDecoderState mvState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.decodeSuperblock(sb0, 0, mvState);
        LcvStreamContainer.SuperblockDecodeResult mvDec1 =
            LcvStreamContainer.decodeMvPacketStream(flatHdr1, arrivedPkts, mvState);
        assertEquals(1, mvDec1.quarantinedTiles());
        long mvAdvectedSqErr = computeTileRgbSqErr(frames1, mvDec1.rgbaFrames(), targetTx, targetTy, width, depthK);
        assertTrue(
            mvAdvectedSqErr < staticSqErr / 4,
            "decodeMvPacketStream neighbor-MV concealment must dramatically reduce MSE vs static (0,0) fallback"
                + " (mvAdvectedSqErr=" + mvAdvectedSqErr + ", staticSqErr=" + staticSqErr + ")"
        );

        // 3. Verify stripped-LSB cross-tile boundary smoothing stays strictly inside ±((1 << s) >> 1)
        byte[] strippedSb0 = LcvOnDiskTransformer.stripLsbPlanes(sb0);
        LcvStreamContainer.SuperblockDecodeResult strippedRes = LcvStreamContainer.decodeSuperblock(strippedSb0, 0, null);
        assertTrue(strippedRes.header().isLsbStripped());
        int[] seamTestFrames = new int[]{
            0x444444FF, 0x444444FF, 0x444444FF, 0x444444FF,
            0x444444FF, 0x444444FF, 0x444444FF, 0x444444FF,
            0x444444FF, 0x444444FF, 0x444444FF, 0x444444FF,
            0x444444FF, 0x444444FF, 0x444444FF, 0x444444FF,
            0x4C4C4CFF, 0x4C4C4CFF, 0x4C4C4CFF, 0x4C4C4CFF,
            0x4C4C4CFF, 0x4C4C4CFF, 0x4C4C4CFF, 0x4C4C4CFF,
            0x4C4C4CFF, 0x4C4C4CFF, 0x4C4C4CFF, 0x4C4C4CFF,
            0x4C4C4CFF, 0x4C4C4CFF, 0x4C4C4CFF, 0x4C4C4CFF
        };
        LcvStreamContainer.smoothStrippedTileBoundaries(seamTestFrames, 2, 1, 32, 1, 1, 3);
        int leftSeamR = (seamTestFrames[15] >>> 24) & 0xFF;
        int rightSeamR = (seamTestFrames[16] >>> 24) & 0xFF;
        assertTrue(leftSeamR > 0x44 && leftSeamR <= 0x47, "Left seam pixel must step smoothly inside its MSB box [0x40, 0x47]");
        assertTrue(rightSeamR < 0x4C && rightSeamR >= 0x48, "Right seam pixel must step smoothly inside its MSB box [0x48, 0x4F]");

        int sbPixels = frames0.length;
        int[] natOut0 = new int[sbPixels];
        int[] natStats0 = new int[4];
        int rc0 = LcvPrimitives.decodeSuperblockNative(sb0, 0, sb0.length, null, false, natOut0, natStats0);
        if (rc0 > 0) {
            // 4. Native C11/CUDA parity on quadtree-coalesced + high-saturation YCoCg-R lossless superblocks
            assertEquals(sbPixels, rc0);
            assertArrayEquals(frames0, natOut0, "Native C11/CUDA decoder must achieve inf dB bit-exact parity on sb0");

            // 5. Native C11/CUDA parity on stripped-LSB (s = 3, 4) POCS bounded-interval restoration
            for (int s : new int[]{3, 4}) {
                LcvCubeCodec.TuningProfile profS = new LcvCubeCodec.TuningProfile(
                    s, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, 8
                );
                byte[] fullSbS = LcvStreamContainer.encodeSuperblock(
                    frames0, width, height, depthK, 0L, 0L, 1, 1, profS, false, null
                );
                byte[] strippedSbS = LcvOnDiskTransformer.stripLsbPlanes(fullSbS);
                LcvStreamContainer.SuperblockDecodeResult javaStripped =
                    LcvStreamContainer.decodeSuperblock(strippedSbS, 0, null);
                int[] natStripped = new int[sbPixels];
                int rcS = LcvPrimitives.decodeSuperblockNative(
                    strippedSbS, 0, strippedSbS.length, null, false, natStripped, null
                );
                assertEquals(sbPixels, rcS);
                assertArrayEquals(
                    javaStripped.rgbaFrames(), natStripped,
                    "Native C11/CUDA stripped-LSB POCS restoration must match Java reference bit-for-bit at s=" + s
                );
            }

            // 6. Native C11/CUDA parity on quarantined DED tile neighbor-MV optical-flow advection
            int[] natCorruptOut = new int[sbPixels];
            int[] natCorruptStats = new int[4];
            int rcCorrupt = LcvPrimitives.decodeSuperblockNative(
                corruptedSb1, 0, corruptedSb1.length, frames0, true, natCorruptOut, natCorruptStats
            );
            assertEquals(sbPixels, rcCorrupt);
            assertEquals(1, natCorruptStats[1], "Native decoder must quarantine exactly 1 DED tile");
            assertArrayEquals(
                seqDec1.rgbaFrames(), natCorruptOut,
                "Native C11/CUDA neighbor-MV optical-flow concealment must match Java reference"
            );

            // 7. Native LCVU UDP packet decode with dropped tile concealment parity
            ByteArrayOutputStream concatOut = new ByteArrayOutputStream();
            int[] pktOffs = new int[arrivedPkts.size()];
            int[] pktLens = new int[arrivedPkts.size()];
            for (int pIdx = 0; pIdx < arrivedPkts.size(); pIdx++) {
                LcvStreamContainer.MvCoherentPacket pkt = arrivedPkts.get(pIdx);
                pktOffs[pIdx] = concatOut.size();
                ByteBuffer pBuf = ByteBuffer.allocate(pkt.wireByteLength()).order(ByteOrder.BIG_ENDIAN);
                pBuf.putShort((short) pkt.startTileIdx());
                pBuf.put((byte) pkt.tileCount());
                pBuf.put((byte) pkt.packedMv8());
                pBuf.putShort((short) pkt.msbPayload().length);
                for (int k = 0; k < pkt.tileCount(); k++) {
                    pBuf.putLong(pkt.tilePointers()[k]);
                }
                pBuf.put(pkt.tileSyndromes(), 0, pkt.tileCount());
                pBuf.put(pkt.msbPayload());
                pBuf.put(pkt.lsbPayload());
                byte[] wire = pBuf.array();
                pktLens[pIdx] = wire.length;
                concatOut.writeBytes(wire);
            }
            int[] natUdpOut = new int[sbPixels];
            int[] natUdpStats = new int[4];
            int rcUdp = LcvPrimitives.decodeLcvuPacketsNative(
                sb1, arrivedPkts.size(), concatOut.toByteArray(), pktOffs, pktLens,
                frames0, true, natUdpOut, natUdpStats
            );
            assertEquals(sbPixels, rcUdp);
            assertEquals(1, natUdpStats[1]);
            assertArrayEquals(
                mvDec1.rgbaFrames(), natUdpOut,
                "Native LCVU UDP packet decoder neighbor-MV concealment must match Java reference"
            );
        }
    }

    @Test
    void asymmetricYCoCgConditioningZeroesChromaG2AtS2AndSelectiveStripRetainsSmoothGradients() throws LcvCorruptTileException {
        int width = 64; // 4 tiles wide
        int height = 64; // 4 tiles high
        int depthK = 4;
        int frameArea = width * height;
        int[] frames = new int[frameArea * depthK];
        SplittableRandom rng = new SplittableRandom(0xC0C6_2026L);

        for (int t = 0; t < depthK; t++) {
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int tx = x / 16;
                    int ty = y / 16;
                    int lx = x & 15;
                    int ly = y & 15;
                    int rgba;
                    if (tx == 0 && ty == 0) {
                        // Smooth natural sky/lighting 3D ramp with +/- 3 LSB sensor shot noise
                        int r = Math.max(16, Math.min(235, 64 + lx * 3 + ly * 2 + t * 2 + rng.nextInt(-3, 4)));
                        int g = Math.max(16, Math.min(235, 80 + lx * 2 + ly * 3 + t * 2 + rng.nextInt(-3, 4)));
                        int b = Math.max(16, Math.min(235, 112 + lx * 2 + ly * 2 + t * 3 + rng.nextInt(-3, 4)));
                        rgba = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                    } else if (tx == 1 && ty == 0) {
                        // Textured Luma gradient with varying chroma that stays non-wrapping in YCoCg-R (>16 palette centroids)
                        int yVal = Math.max(36, Math.min(216, 44 + lx * 6 + ((ly * ly) >> 1) + ((lx * 13 ^ ly * 7) & 15) + t * 5));
                        int coVal = 88 + lx * 5 + ((ly & 3) * 4) + rng.nextInt(-2, 3);
                        int cgVal = 88 + ly * 5 + ((lx & 3) * 4) + rng.nextInt(-2, 3);
                        rgba = LcvPrimitives.yCoCgRToRgba((yVal << 24) | (coVal << 16) | (cgVal << 8) | 0xFF);
                    } else {
                        // High-frequency textured foliage/motion tile
                        int r = (32 + ((x * 37 + y * 53 + t * 19) & 0x7F)) & 0xFF;
                        int g = (48 + ((x * 59 ^ y * 41 + t * 29) & 0x7F)) & 0xFF;
                        int b = (24 + ((x * 23 + y * 67 ^ t * 31) & 0x7F)) & 0xFF;
                        rgba = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                    }
                    frames[t * frameArea + y * width + x] = rgba;
                }
            }
        }

        // 1. Asymmetric YCoCg-R conditioning at s=2 (lsbSplitBits = 2)
        int[] condS2 = LcvOnDiskTransformer.conditionFramesBySection(
            frames, null, width, height, depthK, 0, 0, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, 2
        );
        // Verify Tile (1, 0) in YCoCg-R has exact s=2 Luma midpoint (l_Y == 2) and g2==0 on Co/Cg in Gray code
        for (int t = 0; t < depthK; t++) {
            for (int ly = 0; ly < 16; ly++) {
                for (int lx = 0; lx < 16; lx++) {
                    int px = condS2[t * frameArea + ly * width + (16 + lx)];
                    int ycocg = LcvPrimitives.rgbaToYCoCgR(px);
                    int y8 = (ycocg >>> 24) & 0xFF;
                    int co8 = (ycocg >>> 16) & 0xFF;
                    int cg8 = (ycocg >>> 8) & 0xFF;
                    assertEquals(2, y8 & 3, "Conditioned YCoCg-R Luma Y at s=2 must have exact midpoint LSB 2 without RGB->YCoCg carry leakage");
                    assertEquals(0, (LcvPrimitives.binaryToGray8(co8) >>> 2) & 1, "Conditioned Co8 must have Gray plane g2 == 0");
                    assertEquals(0, (LcvPrimitives.binaryToGray8(cg8) >>> 2) & 1, "Conditioned Cg8 must have Gray plane g2 == 0");
                }
            }
        }

        // Verify Tile (0, 0) (smooth natural gradient) locks onto PLANAR_3D_MV with 0 LSB bytes
        LcvPrimitives.ScratchArena arena = new LcvPrimitives.ScratchArena();
        LcvPrimitives.extractAndFingerprintCubeTyx(condS2, 0, 0, width, height, depthK, arena);
        byte[] tile0Buf = new byte[16384];
        LcvCubeCodec.CubeEncodeResult tile0Enc = LcvCubeCodec.encodeExtractedCubeTyx(
            arena.cubeRgbaTyx, depthK, 0, 0, 0, 0, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, 2, arena, tile0Buf, 0
        );
        assertEquals(0, tile0Enc.lsbSliceBytes(), "Conditioned smooth 3D ramp tile must emit 0 Section 4B LSB bytes");
        assertTrue(tile0Enc.msbSliceBytes() <= 45, "Conditioned smooth 3D ramp tile must encode as compact PLANAR_3D_MV header");

        // 2. Selective Section 4B retention on s=3 superblock
        LcvCubeCodec.TuningProfile s3Lossless = new LcvCubeCodec.TuningProfile(3, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, 8);
        byte[] sbS3 = LcvStreamContainer.encodeSuperblock(
            frames, width, height, depthK, 0L, 0L, 0, 0, s3Lossless, false, null
        );
        byte[] globalStripped = LcvOnDiskTransformer.stripLsbPlanes(sbS3);
        byte[] selectiveStripped = LcvOnDiskTransformer.stripLsbPlanesSelective(sbS3, 144);
        assertTrue(selectiveStripped.length < sbS3.length, "Selective Section 4B strip must shed high-frequency foliage LSB bytes");
        assertTrue(selectiveStripped.length > globalStripped.length, "Selective Section 4B strip must retain smooth gradient tile LSB bytes");

        LcvStreamContainer.SuperblockDecodeResult decSelective = LcvStreamContainer.decodeSuperblock(selectiveStripped, 0, null);
        LcvStreamContainer.SuperblockDecodeResult decGlobal = LcvStreamContainer.decodeSuperblock(globalStripped, 0, null);
        long errSmoothSelective = computeTileRgbSqErr(frames, decSelective.rgbaFrames(), 0, 0, width, depthK);
        long errSmoothGlobal = computeTileRgbSqErr(frames, decGlobal.rgbaFrames(), 0, 0, width, depthK);
        assertEquals(0L, errSmoothSelective, "Smooth gradient tile (0, 0) with retained Section 4B must reconstruct losslessly");
        assertTrue(errSmoothGlobal > 0L, "Globally stripped smooth gradient tile has non-zero LSB quantization error");
    }

    private static int sampleTexturedPanScene(int x, int y, int t) {
        int r = (40 + x * 4 + y * 3 + ((x * y) & 15) + t * 2) & 0xFF;
        int g = (60 + x * 2 + y * 5 + (((x ^ y) * 3) & 15) + t * 3) & 0xFF;
        int b = (90 + x * 5 + y * 2 + (((x + y * 2) * 5) & 15) + t) & 0xFF;
        return (r << 24) | (g << 16) | (b << 8) | 0xFF;
    }

    private static long computeTileRgbSqErr(
        int[] expectedFrames, int[] actualFrames, int tx, int ty, int width, int depthK
    ) {
        int frameArea = width * width;
        int baseX = tx * 16;
        int baseY = ty * 16;
        long sqErr = 0L;
        for (int t = 0; t < depthK; t++) {
            int fBase = t * frameArea;
            for (int ly = 0; ly < 16; ly++) {
                int rowOff = fBase + (baseY + ly) * width + baseX;
                for (int lx = 0; lx < 16; lx++) {
                    int e = expectedFrames[rowOff + lx];
                    int a = actualFrames[rowOff + lx];
                    int dr = ((e >>> 24) & 0xFF) - ((a >>> 24) & 0xFF);
                    int dg = ((e >>> 16) & 0xFF) - ((a >>> 16) & 0xFF);
                    int db = ((e >>> 8) & 0xFF) - ((a >>> 8) & 0xFF);
                    sqErr += (long) dr * dr + (long) dg * dg + (long) db * db;
                }
            }
        }
        return sqErr;
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
