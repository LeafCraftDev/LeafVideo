package io.github.dailystruggle.leafvideo;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three-mode {@code .lcv} and Matroska {@code .mkv (V_LCV1)} stream recovery torture suite:
 * <ol>
 *   <li>{@code SIGKILL} random mid-write byte truncation across 100 random cut points;</li>
 *   <li>Random single-bit flips across every {@code uint64} directory pointer healed via {@code SEC-DED (72, 64)};</li>
 *   <li>Burst {@code 4 KiB} sector zeroing mid-superblock quarantined via {@code CBIT} + prior-tile fallback.</li>
 * </ol>
 *
 * @governedBy leaf-video-ADR-001
 * @governedBy leaf-video-ADR-004
 * @trace REQ-LCV-F-003
 * @trace REQ-LCV-F-004
 * @trace REQ-LCV-HW-003
 */
class LcvRecoveryTortureTest {

    @Test
    void mode1SigkillMidWriteRandomByteTruncationRecoversAllCompletedSuperblocks() throws LcvCorruptTileException {
        int width = 128;
        int height = 64;
        int depthK = 4;
        int numSuperblocks = 8;

        LcvStreamContainer.StreamEncoderState encState = new LcvStreamContainer.StreamEncoderState(width, height);
        List<int[]> sourceClips = new ArrayList<>();
        List<byte[]> sbPayloads = new ArrayList<>();
        long runningOffset = 0L;

        for (int s = 0; s < numSuperblocks; s++) {
            int[] frames = generateClipFrames(width, height, depthK, s);
            sourceClips.add(frames);
            byte[] sb = LcvStreamContainer.encodeSuperblock(
                frames, width, height, depthK,
                (long) s * depthK, runningOffset,
                width / 32, height / 32,
                LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, encState
            );
            sbPayloads.add(sb);
            runningOffset += sb.length;
        }

        byte[] mkvStream = LcvMkvPacketizer.muxSuperblocksToMkv(sbPayloads, width, height, 60);
        List<LcvMkvPacketizer.MkvSuperblockPacket> fullPackets = LcvMkvPacketizer.extractSuperblocksFromMkv(mkvStream);
        assertEquals(numSuperblocks, fullPackets.size());

        // Torture 100 random byte-offset truncations across the .mkv stream
        SplittableRandom rng = new SplittableRandom(0x516_4111L);
        int firstSbEnd = fullPackets.get(0).superblockFileOffset() + fullPackets.get(0).superblockByteLength();

        for (int trial = 0; trial < 100; trial++) {
            int cutOffset = rng.nextInt(firstSbEnd, mkvStream.length);
            byte[] truncatedMkv = Arrays.copyOf(mkvStream, cutOffset);

            // Count how many superblocks had their end offset <= cutOffset
            int expectedComplete = 0;
            for (LcvMkvPacketizer.MkvSuperblockPacket pkt : fullPackets) {
                if (pkt.superblockFileOffset() + pkt.superblockByteLength() <= cutOffset) {
                    expectedComplete++;
                }
            }

            List<LcvMkvPacketizer.MkvSuperblockPacket> recovered = LcvMkvPacketizer.extractSuperblocksFromMkv(truncatedMkv);
            assertEquals(
                expectedComplete, recovered.size(),
                "Trial " + trial + " at cutOffset=" + cutOffset + " must recover all completed superblocks"
            );

            LcvStreamContainer.StreamDecoderState decState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
            for (int s = 0; s < recovered.size(); s++) {
                LcvStreamContainer.SuperblockDecodeResult dec = LcvStreamContainer.decodeSuperblock(
                    recovered.get(s).superblockBytes(), 0, decState
                );
                assertArrayEquals(sourceClips.get(s), dec.rgbaFrames(), "Bit-exact frame mismatch after truncation at s=" + s);
            }
        }
    }

    @Test
    void mode2SingleBitFlipsAcrossAllTilePointersAreHealedWithZeroPixelError() throws LcvCorruptTileException {
        int width = 160;
        int height = 96;
        int depthK = 4;
        int[] frames = generateClipFrames(width, height, depthK, 3);

        byte[] cleanSb = LcvStreamContainer.encodeSuperblock(
            frames, width, height, depthK, 0L, 0L, 5, 3,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );
        LcvStreamContainer.SuperblockHeader header = LcvStreamContainer.readSuperblockHeader(cleanSb, 0);
        int nTiles = header.totalTiles();
        int dirBase = header.directoryOffset();

        SplittableRandom rng = new SplittableRandom(0x5EC_DED01L);
        byte[] corruptedSb = Arrays.copyOf(cleanSb, cleanSb.length);
        ByteBuffer buf = ByteBuffer.wrap(corruptedSb).order(ByteOrder.BIG_ENDIAN);

        // Inject a random 1-bit flip into every single tile pointer entry in the directory
        for (int tileIdx = 0; tileIdx < nTiles; tileIdx++) {
            int bitToFlip = rng.nextInt(64);
            long origPtr = buf.getLong(dirBase + tileIdx * 8);
            buf.putLong(dirBase + tileIdx * 8, origPtr ^ (1L << bitToFlip));
        }

        LcvStreamContainer.SuperblockDecodeResult dec = LcvStreamContainer.decodeSuperblock(corruptedSb, 0, null);
        assertEquals(nTiles, dec.healedSingleBitPointers(), "Every 1-bit flipped pointer must be healed");
        assertEquals(0, dec.quarantinedTiles(), "Zero tiles should be quarantined on single-bit flips");
        assertArrayEquals(frames, dec.rgbaFrames(), "Healed Superblock must reconstruct 100% bit-exact pixels");
    }

    @Test
    void mode3Burst4KiBSectorZeroingConfinesDamageToAffectedTilesAndFallsBackToPriorCache() throws LcvCorruptTileException {
        // Use a large 640x360 frame (40x23 = 920 tiles) so a 4 KiB sector hits only a tiny fraction of tiles
        int width = 640;
        int height = 368; // 40 x 23 = 920 tiles
        int depthK = 4;

        int[] framesSb0 = generateClipFrames(width, height, depthK, 0);
        int[] framesSb1 = generateClipFrames(width, height, depthK, 1);

        byte[] sb0 = LcvStreamContainer.encodeSuperblock(
            framesSb0, width, height, depthK, 0L, 0L, 20, 11,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );
        byte[] sb1 = LcvStreamContainer.encodeSuperblock(
            framesSb1, width, height, depthK, depthK, sb0.length, 20, 11,
            LcvCubeCodec.DistanceRlePolicy.LOSSLESS, false, null
        );

        LcvStreamContainer.SuperblockHeader hdr1 = LcvStreamContainer.readSuperblockHeader(sb1, 0);
        assertTrue(hdr1.msbPoolCompressedLen32() > 8192, "MSB pool should exceed 8 KiB for 4 KiB sector test");

        // Zero out a 4,096-byte (4 KiB) disk sector in the middle of sb1's Section 4A MSB pool
        byte[] damagedSb1 = Arrays.copyOf(sb1, sb1.length);
        int sectorStart = hdr1.msbPoolOffset() + hdr1.msbPoolCompressedLen32() / 2;
        int sectorEnd = Math.min(hdr1.lsbPoolOffset(), sectorStart + 4096);
        Arrays.fill(damagedSb1, sectorStart, sectorEnd, (byte) 0);

        LcvStreamContainer.StreamDecoderState decState = new LcvStreamContainer.StreamDecoderState(width, height, depthK);
        LcvStreamContainer.decodeSuperblock(sb0, 0, decState); // Prime prior-superblock tile cache
        LcvStreamContainer.SuperblockDecodeResult recoveredSb1 = LcvStreamContainer.decodeSuperblock(
            damagedSb1, 0, decState
        );

        int totalTiles = hdr1.totalTiles();
        assertTrue(recoveredSb1.quarantinedTiles() > 0, "CBIT must detect and quarantine zeroed sector tiles");
        assertTrue(
            recoveredSb1.quarantinedTiles() < totalTiles / 10,
            "Blast radius must be strictly confined (< 10% of tiles), got " + recoveredSb1.quarantinedTiles()
        );

        // Count how many tiles are 100% bit-exact vs framesSb1 (or cleanly healed from framesSb0)
        int intactPixels = 0;
        int fallbackHealedPixels = 0;
        int totalPixels = framesSb1.length;
        int[] outPx = recoveredSb1.rgbaFrames();
        for (int i = 0; i < totalPixels; i++) {
            if (outPx[i] == framesSb1[i]) {
                intactPixels++;
            } else if (outPx[i] == framesSb0[i]) {
                fallbackHealedPixels++;
            }
        }
        double intactRatio = (double) intactPixels / totalPixels;
        assertTrue(intactRatio > 0.94, "Expected > 94% of pixels bit-exact after 4 KiB sector loss, got " + intactRatio);
        assertTrue(fallbackHealedPixels > 0, "Quarantined tiles must fall back cleanly to prior superblock cache");
    }

    @Test
    void mode4SubFrameRollingStripK1SupportsImmediateScanlineDecodeLsbSheddingAndFaultIsolation() {
        int width = 128;
        int height = 64; // 4 horizontal 16px strips
        int[] frame0 = generateClipFrames(width, height, 1, 0);
        int[] frame1 = generateClipFrames(width, height, 1, 1);
        int[] reconstructedFrame1 = new int[width * height];

        LcvCubeCodec.TuningProfile losslessStripProfile =
            new LcvCubeCodec.TuningProfile(3, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, 1);

        for (int stripY = 0; stripY < height / 16; stripY++) {
            int rowStart = stripY * 16 * width;
            int[] stripScanlines = Arrays.copyOfRange(frame1, rowStart, rowStart + 16 * width);
            LcvStreamContainer.RollingStripPacket pkt = LcvStreamContainer.encodeRollingStripK1(
                stripScanlines, width, height, stripY, 4, 2, losslessStripProfile
            );
            int quarantined = LcvStreamContainer.decodeRollingStripK1(pkt, reconstructedFrame1, frame0);
            assertEquals(0, quarantined);

            // Verify in-flight Section 4B LSB shedding on the strip packet
            LcvStreamContainer.RollingStripPacket shedPkt = LcvStreamContainer.shedRollingStripLsbInFlight(pkt);
            assertTrue(shedPkt.isLsbStripped());
            int[] shedFrame = new int[width * height];
            assertEquals(0, LcvStreamContainer.decodeRollingStripK1(shedPkt, shedFrame, frame0));
        }
        assertArrayEquals(frame1, reconstructedFrame1, "Rolling K=1 16px strips must reconstruct full frame bit-exact");

        // Corrupt 1 tile pointer with a 2-bit DED in stripY=1 and verify single-tile fallback to frame0
        int[] strip1Scanlines = Arrays.copyOfRange(frame1, 16 * width, 32 * width);
        LcvStreamContainer.RollingStripPacket pkt1 = LcvStreamContainer.encodeRollingStripK1(
            strip1Scanlines, width, height, 1, 4, 2, losslessStripProfile
        );
        pkt1.tilePointers()[2] ^= (1L << 5) ^ (1L << 19);
        int[] damagedOut = Arrays.copyOf(frame1, frame1.length);
        int qCount = LcvStreamContainer.decodeRollingStripK1(pkt1, damagedOut, frame0);
        assertEquals(1, qCount, "2-bit pointer DED must quarantine only the single affected 16x16 strip tile");
        assertEquals(frame0[20 * width + 36], damagedOut[20 * width + 36], "Quarantined strip tile must fall back to prior frame");
        assertEquals(frame1[20 * width + 4], damagedOut[20 * width + 4], "Neighboring strip tiles must remain 100% intact");
    }

    private static int[] generateClipFrames(int width, int height, int depthK, int sbIndex) {
        SplittableRandom rng = new SplittableRandom(0x7027_0000L + sbIndex);
        int framePixels = width * height;
        int[] frames = new int[framePixels * depthK];

        for (int t = 0; t < depthK; t++) {
            int globalT = sbIndex * depthK + t;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int tx = x / 16;
                    int ty = y / 16;
                    int rgba;
                    if (ty == 0 && tx < 3) {
                        // Static HUD bar
                        rgba = ((x + y) % 4 == 0) ? 0xCDD6F4FF : 0x1E1E2EFF;
                    } else {
                        // Moving gradient + fine texture so each tile has active MSB & LSB planes
                        int r = (40 + x + globalT * 3 + rng.nextInt(0, 8)) & 0xFF;
                        int g = (70 + y + globalT * 2 + rng.nextInt(0, 8)) & 0xFF;
                        int b = (100 + ((x ^ y) & 0x3F) + globalT + rng.nextInt(0, 8)) & 0xFF;
                        rgba = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                    }
                    frames[t * framePixels + y * width + x] = rgba;
                }
            }
        }
        return frames;
    }
}
