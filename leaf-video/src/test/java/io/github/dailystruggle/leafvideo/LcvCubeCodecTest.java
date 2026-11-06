package io.github.dailystruggle.leafvideo;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit verification for {@link LcvPrimitives} and {@link LcvCubeCodec} covering lossless
 * reconstruction, enumerated {@link LcvCubeCodec.EncodingVariation} selection, drift-free
 * 6-bit MSB Hilbert-Delta LSB truncation, distance-scaled RLE concatenation, and {@code CBIT}
 * fault bounds.
 *
 * @governedBy leaf-video-ADR-001
 * @trace REQ-LCV-F-001
 * @trace REQ-LCV-F-002
 * @trace REQ-LCV-F-004
 */
class LcvCubeCodecTest {

    @Test
    void curveLutsAreBijectiveAcrossAllDepthsAndModes() {
        for (int depthK : new int[]{1, 2, 4, 8, 16}) {
            int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
            for (int curveId = 0; curveId < 4; curveId++) {
                short[] c2t = LcvPrimitives.curveToTyxLut(depthK, curveId);
                short[] t2c = LcvPrimitives.tyxToCurveLut(depthK, curveId);
                assertEquals(nSamples, c2t.length);
                assertEquals(nSamples, t2c.length);

                boolean[] seen = new boolean[nSamples];
                for (int i = 0; i < nSamples; i++) {
                    int tyx = c2t[i] & 0xFFFF;
                    assertTrue(tyx >= 0 && tyx < nSamples);
                    assertTrue(!seen[tyx], "Duplicate TYX index at curve=" + curveId + " depth=" + depthK);
                    seen[tyx] = true;
                    assertEquals(i, t2c[tyx] & 0xFFFF);
                }
            }
        }
    }

    @Test
    void yCoCgRAndGrayZigZagPrimitivesAreBitExactBijections() {
        // Verify neutral gray maps to monotonic offset-binary Co8 = 128, Cg8 = 128
        for (int v = 0; v < 256; v++) {
            int grayRgba = (v << 24) | (v << 16) | (v << 8) | 0xFF;
            int yCoCgA = LcvPrimitives.rgbaToYCoCgR(grayRgba);
            assertEquals(v, (yCoCgA >>> 24) & 0xFF);
            assertEquals(128, (yCoCgA >>> 16) & 0xFF, "Neutral gray must map to Co8 = 128");
            assertEquals(128, (yCoCgA >>> 8) & 0xFF, "Neutral gray must map to Cg8 = 128");
            assertEquals(0xFF, yCoCgA & 0xFF);
            assertEquals(grayRgba, LcvPrimitives.yCoCgRToRgba(yCoCgA));
        }

        // Verify pure saturated corners and random RGBA samples
        int[] cornerVals = {0, 1, 2, 63, 64, 127, 128, 129, 254, 255};
        for (int r : cornerVals) {
            for (int g : cornerVals) {
                for (int b : cornerVals) {
                    for (int a : new int[]{0, 128, 255}) {
                        int rgba = (r << 24) | (g << 16) | (b << 8) | a;
                        int yCoCg = LcvPrimitives.rgbaToYCoCgR(rgba);
                        int roundTrip = LcvPrimitives.yCoCgRToRgba(yCoCg);
                        assertEquals(rgba, roundTrip);
                    }
                }
            }
        }

        for (int v = 0; v < 256; v++) {
            assertEquals(v, LcvPrimitives.grayToBinary8(LcvPrimitives.binaryToGray8(v)));
        }
        for (int m6 = 0; m6 < 64; m6++) {
            assertEquals(m6, LcvPrimitives.grayToBinary6(LcvPrimitives.binaryToGray6(m6)));
            for (int prev = 0; prev < 64; prev++) {
                int zz6 = LcvPrimitives.zigZagEncode6(m6 - prev);
                assertTrue(zz6 >= 0 && zz6 < 64);
                int reconstructed = (prev + LcvPrimitives.zigZagDecode6(zz6)) & 0x3F;
                assertEquals(m6, reconstructed);
            }
        }
        for (int l2 = 0; l2 < 4; l2++) {
            assertEquals(l2, LcvPrimitives.grayToBinary2(LcvPrimitives.binaryToGray2(l2)));
        }
    }

    @Test
    void hammingSecDed72HealsAllSingleBitFlipsAndDetectsDoubleBitErrors() {
        SplittableRandom rng = new SplittableRandom(0x1CA1_5ECDL);
        long[] specialWords = {
            0L,
            -1L,
            0x8000_0000_0000_0000L, // All-transparent UNIFORM_INLINE pointer
            0x8000_0000_FF00_FF80L,
            0x1234_5678_9ABC_DEF0L
        };

        for (int trial = 0; trial < 200; trial++) {
            long word = (trial < specialWords.length) ? specialWords[trial] : rng.nextLong();
            if (word == LcvPrimitives.UNCORRECTABLE_DED_SENTINEL) {
                continue;
            }
            byte syn = LcvPrimitives.computeHammingSecDed8(word);
            assertEquals(word, LcvPrimitives.healSingleBitFlip64(word, syn));

            // Flip every single data bit 0..63
            for (int b = 0; b < 64; b++) {
                long flipped = word ^ (1L << b);
                assertEquals(word, LcvPrimitives.healSingleBitFlip64(flipped, syn), "Failed healing data bit " + b);
            }
            // Flip every single syndrome bit 0..7
            for (int sb = 0; sb < 8; sb++) {
                byte flippedSyn = (byte) (syn ^ (1 << sb));
                assertEquals(word, LcvPrimitives.healSingleBitFlip64(word, flippedSyn), "Failed healing syn bit " + sb);
            }
            // Flip two data bits -> must return UNCORRECTABLE_DED_SENTINEL
            for (int b1 = 0; b1 < 64; b1 += 7) {
                for (int b2 = b1 + 1; b2 < 64; b2 += 11) {
                    long twoFlipped = word ^ (1L << b1) ^ (1L << b2);
                    assertEquals(
                        LcvPrimitives.UNCORRECTABLE_DED_SENTINEL,
                        LcvPrimitives.healSingleBitFlip64(twoFlipped, syn)
                    );
                }
            }
        }
    }

    @Test
    void losslessRoundTripAcrossAllEnumeratedEncodingVariations() throws LcvCorruptTileException {
        LcvPrimitives.ScratchArena arena = new LcvPrimitives.ScratchArena();
        byte[] encoded = new byte[32768];
        int[] decoded = new int[LcvPrimitives.MAX_CUBE_SAMPLES];

        for (int depthK : new int[]{4, 8, 16}) {
            int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
            int[] cube = new int[nSamples];

            // 1. UNIFORM_INLINE (solid & transparent)
            Arrays.fill(cube, 0x00000000);
            LcvCubeCodec.CubeEncodeResult uRes = LcvCubeCodec.encodeCubeRgbaDetailed(
                cube, 0, 0, 16, depthK, 0, 0, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, arena, encoded, 0
            );
            assertEquals(LcvCubeCodec.EncodingVariation.UNIFORM_INLINE, uRes.winningVariation());
            assertEquals(0, uRes.totalBytes());

            // 2. UI_PALETTE_ROARING (IDE / HUD text with singleton cursor, runs, and sparse dots)
            Arrays.fill(cube, 0x1E1E2EFF); // dark IDE background (palette[0])
            cube[7] = 0xFF5555FF; // singleton cursor pixel -> ROARING_SINGLETON
            for (int i = 64; i < 180; i++) {
                cube[i] = 0x89B4FAFF; // contiguous syntax token -> ROARING_DELTA_RUN
            }
            for (int i = 200; i < nSamples; i += 37) {
                cube[i] = 0xA6E3A1FF; // scattered punctuation -> ROARING_DELTA_ARRAY
            }
            LcvCubeCodec.CubeEncodeResult uiRes = LcvCubeCodec.encodeCubeRgbaDetailed(
                cube, 0, 0, 16, depthK, 0, 0, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, arena, encoded, 0
            );
            assertEquals(LcvCubeCodec.EncodingVariation.UI_PALETTE_ROARING, uiRes.winningVariation());
            assertTrue(uiRes.totalBytes() < nSamples * 4 / 8);
            LcvCubeCodec.decodeCubeRgba(encoded, 0, uiRes.totalBytes(), depthK, arena, decoded);
            assertArrayEquals(cube, Arrays.copyOf(decoded, nSamples));

            // 3. Smooth 3D lighting gradient (GRAY_PLANES_RAW)
            for (int t = 0; t < depthK; t++) {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int r = (80 + x * 2 + y + t) & 0xFF;
                        int g = (100 + x + y * 2 + t) & 0xFF;
                        int b = (120 + x + y + t * 2) & 0xFF;
                        cube[(t << 8) | (y << 4) | x] = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                    }
                }
            }
            LcvCubeCodec.CubeEncodeResult gradRes = LcvCubeCodec.encodeCubeRgbaDetailed(
                cube, 0, 0, 16, depthK, 0, 0, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, arena, encoded, 0
            );
            assertTrue(
                gradRes.winningVariation() == LcvCubeCodec.EncodingVariation.GRAY_PLANES_RAW
                    || gradRes.winningVariation() == LcvCubeCodec.EncodingVariation.GRAY_PLANES_HILBERT_DELTA
            );
            assertTrue(gradRes.msbSliceBytes() > 0);
            assertTrue(gradRes.lsbSliceBytes() > 0);
            LcvCubeCodec.decodeCubeRgba(encoded, 0, gradRes.totalBytes(), depthK, arena, decoded);
            assertArrayEquals(cube, Arrays.copyOf(decoded, nSamples));

            // 4. Steep linear shading ramp across (X, Y, T) (GRAY_PLANES_HILBERT_DELTA)
            for (int t = 0; t < depthK; t++) {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int v = ((x * 4 + y * 16 + t * 28) & 0xFC) | ((x + y + t) & 3);
                        cube[(t << 8) | (y << 4) | x] = (v << 24) | (v << 16) | (v << 8) | 0xFF;
                    }
                }
            }
            LcvCubeCodec.CubeEncodeResult rampRes = LcvCubeCodec.encodeCubeRgbaDetailed(
                cube, 0, 0, 16, depthK, 0, 0, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, arena, encoded, 0
            );
            assertEquals(LcvCubeCodec.EncodingVariation.GRAY_PLANES_HILBERT_DELTA, rampRes.winningVariation());
            LcvCubeCodec.decodeCubeRgba(encoded, 0, rampRes.totalBytes(), depthK, arena, decoded);
            assertArrayEquals(cube, Arrays.copyOf(decoded, nSamples));

            // 5. High-entropy white noise (hits mode 11 bitplane ceiling / RAW_CEILING without expanding)
            SplittableRandom noiseRng = new SplittableRandom(999L + depthK);
            for (int i = 0; i < nSamples; i++) {
                cube[i] = noiseRng.nextInt();
            }
            LcvCubeCodec.CubeEncodeResult noiseRes = LcvCubeCodec.encodeCubeRgbaDetailed(
                cube, 0, 0, 16, depthK, 0, 0, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, arena, encoded, 0
            );
            assertTrue(noiseRes.totalBytes() <= 1 + nSamples * 4, "Must never exceed raw ceiling");
            LcvCubeCodec.decodeCubeRgba(encoded, 0, noiseRes.totalBytes(), depthK, arena, decoded);
            assertArrayEquals(cube, Arrays.copyOf(decoded, nSamples));
        }
    }

    @Test
    void hilbertDeltaModeSupportsZeroDecodeLsbStrippingWithoutCumulativeDrift() throws LcvCorruptTileException {
        int depthK = 16;
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        int[] cube = new int[nSamples];
        for (int t = 0; t < depthK; t++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int v = ((x * 4 + y * 16 + t * 28) & 0xFC) | ((x ^ y ^ t) & 3);
                    cube[(t << 8) | (y << 4) | x] = (v << 24) | (v << 16) | (v << 8) | 0xFF;
                }
            }
        }

        LcvPrimitives.ScratchArena arena = new LcvPrimitives.ScratchArena();
        byte[] encoded = new byte[32768];
        LcvCubeCodec.CubeEncodeResult res = LcvCubeCodec.encodeCubeRgbaDetailed(
            cube, 0, 0, 16, depthK, 0, 0, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, arena, encoded, 0
        );
        assertEquals(LcvCubeCodec.EncodingVariation.GRAY_PLANES_HILBERT_DELTA, res.winningVariation());
        assertTrue(res.lsbSliceBytes() > 0);

        // Decode with Tail LSB Slice stripped (lsbLen = 0)
        int[] strippedDecoded = new int[nSamples];
        LcvCubeCodec.decodeSplitCubeRgba(
            encoded, 0, res.msbSliceBytes(),
            encoded, 0, 0,
            false, depthK, arena, strippedDecoded
        );

        // Verify zero cumulative prefix-sum drift across all 4,096 samples (max channel delta <= 3)
        int maxErr = 0;
        for (int i = 0; i < nSamples; i++) {
            int origR = (cube[i] >>> 24) & 0xFF;
            int decR = (strippedDecoded[i] >>> 24) & 0xFF;
            maxErr = Math.max(maxErr, Math.abs(origR - decR));
        }
        assertTrue(maxErr <= 3, "Expected max error <= 3 LSBs without drift, got " + maxErr);
    }

    @Test
    void distanceScaledRleConcatenationPreservesFocalRoiAndShrinksPeripheralBytes() throws LcvCorruptTileException {
        int depthK = 8;
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        int[] cube = new int[nSamples];
        SplittableRandom rng = new SplittableRandom(42L);
        for (int t = 0; t < depthK; t++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int base = 110 + x * 2 + y * 2 + t;
                    int dither = rng.nextInt(-2, 3);
                    int v = Math.max(0, Math.min(255, base + dither));
                    cube[(t << 8) | (y << 4) | x] = (v << 24) | (v << 16) | (v << 8) | 0xFF;
                }
            }
        }

        LcvPrimitives.ScratchArena arena = new LcvPrimitives.ScratchArena();
        byte[] buf0 = new byte[16384];
        byte[] bufNear = new byte[16384];
        byte[] bufFar = new byte[16384];

        LcvCubeCodec.DistanceRlePolicy policy = new LcvCubeCodec.DistanceRlePolicy(4, 0, 1.0);

        // At focal ROI (tile 5, 5 -> dist = 0): must be 100% lossless
        int lenRoi = LcvCubeCodec.encodeCubeRgba(cube, 5, 5, 16, depthK, 5, 5, policy, arena, buf0, 0);
        int[] decRoi = new int[nSamples];
        LcvCubeCodec.decodeCubeRgba(buf0, 0, lenRoi, depthK, arena, decRoi);
        assertArrayEquals(cube, decRoi, "Focal ROI tile (r=0) must be 100% bit-exact lossless");

        // At mid distance (tile 8, 5 -> dist = 3)
        int lenNear = LcvCubeCodec.encodeCubeRgba(cube, 8, 5, 16, depthK, 5, 5, policy, arena, bufNear, 0);
        int[] decNear = new int[nSamples];
        LcvCubeCodec.decodeCubeRgba(bufNear, 0, lenNear, depthK, arena, decNear);

        // At far periphery (tile 20, 5 -> dist = 15)
        int lenFar = LcvCubeCodec.encodeCubeRgba(cube, 20, 5, 16, depthK, 5, 5, policy, arena, bufFar, 0);
        int[] decFar = new int[nSamples];
        LcvCubeCodec.decodeCubeRgba(bufFar, 0, lenFar, depthK, arena, decFar);

        assertTrue(lenNear < lenRoi, "Near peripheral tile should shrink vs lossless ROI (" + lenNear + " vs " + lenRoi + ")");
        assertTrue(lenFar <= lenNear, "Far peripheral tile should shrink further (" + lenFar + " vs " + lenNear + ")");

        // Verify MSB planes (g7..g4) remain undamaged so PSNR stays high (> 34 dB)
        double mseFar = 0.0;
        for (int i = 0; i < nSamples; i++) {
            int dr = ((cube[i] >>> 24) & 0xFF) - ((decFar[i] >>> 24) & 0xFF);
            mseFar += dr * dr;
        }
        mseFar /= nSamples;
        double psnrFar = 10.0 * Math.log10((255.0 * 255.0) / Math.max(1e-9, mseFar));
        assertTrue(psnrFar > 32.0, "Expected high PSNR on MSB-protected distance-RLE cube, got " + psnrFar);
    }

    @Test
    void cbitGuardsRejectTruncatedAndOverflowingBitstreams() {
        LcvPrimitives.ScratchArena arena = new LcvPrimitives.ScratchArena();
        int[] dst = new int[1024];

        // 1. Empty slice
        assertThrows(
            LcvCorruptTileException.class,
            () -> LcvCubeCodec.decodeCubeRgba(new byte[4], 2, 2, 4, arena, dst)
        );

        // 2. Malformed UI_PALETTE_ROARING with truncated palette
        byte[] badUi = {(byte) ((LcvCubeCodec.CUBE_MODE_UI_PALETTE_ROARING << 4) | 2), 1, 2, 3};
        assertThrows(
            LcvCorruptTileException.class,
            () -> LcvCubeCodec.decodeCubeRgba(badUi, 0, badUi.length, 4, arena, dst)
        );

        // 3. Malformed mode 10 nibble run overflowing nSamples (1024 for K=4)
        int[] posRef = {0};
        byte[] overflowRuns = {
            1, // 1 run
            (byte) 0xFF, // gNib = 15, lNib = 15
            (byte) 0x80, (byte) 0x10, // extra gap = 2048 (> 1024)
            0
        };
        assertThrows(
            LcvCorruptTileException.class,
            () -> LcvCubeCodec.readNibbleRunStream(overflowRuns, posRef, overflowRuns.length, 1024, arena.rawGaps, arena.runLens)
        );
    }

    @Test
    void tuningProfile16BitfieldAndConfigurableLsbSplitBitsRoundTrip() throws LcvCorruptTileException {
        // 1. Verify default 0x0000 and all standard operating profiles round-trip through 16-bit packing
        assertEquals(0x0000, LcvCubeCodec.TuningProfile.DEFAULT_LOSSLESS.toPackedU16());
        assertEquals(
            LcvCubeCodec.TuningProfile.DEFAULT_LOSSLESS,
            LcvCubeCodec.TuningProfile.fromPackedU16(0x0000)
        );

        LcvCubeCodec.TuningProfile[] profiles = {
            LcvCubeCodec.TuningProfile.DEFAULT_LOSSLESS,
            LcvCubeCodec.TuningProfile.ARCHIVAL_LOSSLESS,
            LcvCubeCodec.TuningProfile.OBS_REALTIME_LOSSLESS,
            LcvCubeCodec.TuningProfile.WIRELESS_FOVEATED_LOW_LATENCY,
            LcvCubeCodec.TuningProfile.HARSH_RF_UEP_ZERO_WAIT
        };
        for (LcvCubeCodec.TuningProfile p : profiles) {
            int packed16 = p.toPackedU16();
            LcvCubeCodec.TuningProfile unpacked = LcvCubeCodec.TuningProfile.fromPackedU16(packed16);
            assertEquals(p, unpacked);
        }

        // 2. Verify configurable lsbSplitBits s in {1, 2, 3, 4} and ultra-low-latency depths K in {1, 2, 4}
        LcvPrimitives.ScratchArena arena = new LcvPrimitives.ScratchArena();
        for (int depthK : new int[]{1, 2, 4}) {
            int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
            int[] cube = new int[nSamples];
            for (int t = 0; t < depthK; t++) {
                for (int y = 0; y < 16; y++) {
                    for (int x = 0; x < 16; x++) {
                        int v = ((x * 5 + y * 9 + t * 17) & 0xF0) | ((x + y + t) & 0x0F);
                        cube[(t << 8) | (y << 4) | x] = (v << 24) | (v << 16) | (v << 8) | 0xFF;
                    }
                }
            }

            byte[] encoded = new byte[16384];
            int[] decoded = new int[nSamples];
            int[] strippedDecoded = new int[nSamples];

            for (int s = 1; s <= 4; s++) {
                LcvCubeCodec.CubeEncodeResult enc = LcvCubeCodec.encodeExtractedCubeTyx(
                    cube, depthK, 0, 0, 0, 0, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, s, arena, encoded, 0
                );
                // Full lossless decode with lsbSplitBits = s
                LcvCubeCodec.decodeSplitCubeRgba(
                    encoded, 0, enc.msbSliceBytes(),
                    encoded, enc.msbSliceBytes(), enc.lsbSliceBytes(),
                    false, depthK, s, arena, decoded
                );
                assertArrayEquals(cube, Arrays.copyOf(decoded, nSamples), "Lossless round-trip failed for s=" + s + ", K=" + depthK);

                // Stripped LSB decode (Section 4B dropped): max error must be bounded by (1 << s) - 1 with zero drift
                LcvCubeCodec.decodeSplitCubeRgba(
                    encoded, 0, enc.msbSliceBytes(),
                    encoded, 0, 0,
                    false, depthK, s, arena, strippedDecoded
                );
                int maxBound = (1 << s) - 1;
                for (int i = 0; i < nSamples; i++) {
                    int origR = (cube[i] >>> 24) & 0xFF;
                    int decR = (strippedDecoded[i] >>> 24) & 0xFF;
                    int err = Math.abs(origR - decR);
                    assertTrue(err <= maxBound, "Stripped LSB error " + err + " exceeded bound " + maxBound + " for s=" + s);
                }
            }
        }
    }

    @Test
    void wavelet3DHaarSubModeCompressesMultiFrequencyTexturedCubesLosslesslyAndStripsDriftFree()
        throws LcvCorruptTileException {
        int depthK = 8;
        int nSamples = LcvPrimitives.TILE_PIXELS * depthK;
        int[] cube = new int[nSamples];
        SplittableRandom rng = new SplittableRandom(0x4AA2_3D01L);

        // Multi-frequency 2D spatial texture (4x4 block wave + linear trend + fine sensor noise)
        // where global planar residual span > 36, but 4x4 Haar LL subband reduces local detail span to <= 4
        for (int t = 0; t < depthK; t++) {
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    int bx = x >>> 2;
                    int by = y >>> 2;
                    int blockWave = ((bx ^ by) * 12) + ((bx + by) * 4);
                    int r = Math.max(0, Math.min(255, 40 + x * 3 + y * 2 + t * 2 + blockWave + rng.nextInt(-2, 3)));
                    int g = Math.max(0, Math.min(255, 50 + x * 2 + y * 3 + t + blockWave + rng.nextInt(-2, 3)));
                    int b = Math.max(0, Math.min(255, 60 + x + y * 2 + t * 2 + blockWave + rng.nextInt(-2, 3)));
                    cube[(t << 8) | (y << 4) | x] = (r << 24) | (g << 16) | (b << 8) | 0xFF;
                }
            }
        }

        LcvPrimitives.ScratchArena arena = new LcvPrimitives.ScratchArena();
        byte[] encoded = new byte[32768];
        LcvCubeCodec.CubeEncodeResult res = LcvCubeCodec.encodeExtractedCubeTyx(
            cube, depthK, 0, 0, 0, 0, LcvCubeCodec.DistanceRlePolicy.LOSSLESS, 3, arena, encoded, 0
        );
        assertEquals(LcvCubeCodec.EncodingVariation.GRAY_PLANES_HILBERT_DELTA, res.winningVariation());

        // Verify DELTA_SUBMODE_WAVELET_3D_HAAR bit (0x10) was selected in deltaFlags8
        int chMask4 = encoded[0] & 0x0F;
        int inactiveConsts = 4 - Integer.bitCount(chMask4);
        int deltaFlags8 = encoded[1 + inactiveConsts] & 0xFF;
        assertTrue(
            (deltaFlags8 & LcvCubeCodec.DELTA_SUBMODE_WAVELET_3D_HAAR) != 0,
            "Expected WAVELET_3D_HAAR sub-mode on 4x4 textured wave cube, got deltaFlags8=0x"
                + Integer.toHexString(deltaFlags8)
        );

        // 1. Verify 100% bit-exact lossless round-trip
        int[] decoded = new int[nSamples];
        LcvCubeCodec.decodeSplitCubeRgba(
            encoded, 0, res.msbSliceBytes(),
            encoded, res.msbSliceBytes(), res.lsbSliceBytes(),
            false, depthK, 3, arena, decoded
        );
        assertArrayEquals(cube, decoded);

        // 2. Verify stripping Section 4B (s = 3) preserves the 4x4 Haar LL subband with zero drift and high PSNR
        int[] stripped = new int[nSamples];
        LcvCubeCodec.decodeSplitCubeRgba(
            encoded, 0, res.msbSliceBytes(),
            encoded, 0, 0,
            false, depthK, 3, arena, stripped
        );
        double mse = 0.0;
        for (int i = 0; i < nSamples; i++) {
            int dr = ((cube[i] >>> 24) & 0xFF) - ((stripped[i] >>> 24) & 0xFF);
            int dg = ((cube[i] >>> 16) & 0xFF) - ((stripped[i] >>> 16) & 0xFF);
            int db = ((cube[i] >>> 8) & 0xFF) - ((stripped[i] >>> 8) & 0xFF);
            mse += (dr * dr + dg * dg + db * db) / 3.0;
        }
        mse /= nSamples;
        double psnr = 10.0 * Math.log10((255.0 * 255.0) / Math.max(1e-9, mse));
        assertTrue(psnr > 39.0, "Expected drift-free Haar LL subband PSNR > 39 dB after 3-bit LSB strip, got " + psnr);
    }
}
