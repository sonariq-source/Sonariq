package com.music.sonariq.playback.smart

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The MDX chunk geometry against values computed by hand from the constants:
 * chunkSize 261120 (1024 * 255), trim 3072, genSize 254976 (chunk - 2 * trim),
 * step 195840 (75% of chunk), pad = genSize + trim - (length % genSize),
 * mixture = trim + length + pad, chunks = ceil(mixture / step).
 */
class MdxChunkGeometryTest {

    @Test
    fun constantsMatchTheReferenceGeometry() {
        assertEquals(1024 * 255, MdxSeparator.CHUNK_SIZE)
        assertEquals(3_072, MdxSeparator.TRIM)
        assertEquals(MdxSeparator.CHUNK_SIZE - 2 * MdxSeparator.TRIM, MdxSeparator.GEN_SIZE)
        assertEquals(254_976, MdxSeparator.GEN_SIZE)
        assertEquals(195_840, MdxSeparator.STEP)
    }

    @Test
    fun shortMixPadsUpToExactlyOneChunk() {
        // length 1000: pad = 254976 + 3072 - 1000 = 257048;
        // mixture = 3072 + 1000 + 257048 = 261120 = one chunk; starts at 0 and 195840 -> 2.
        val geometry = mdxChunkGeometry(1_000)
        assertEquals(257_048, geometry.pad)
        assertEquals(261_120, geometry.mixtureLength)
        assertEquals(2, geometry.chunkCount)
    }

    @Test
    fun emptyMixStillPadsToOneChunk() {
        // length 0: remainder 0, so pad = 254976 + 3072 = 258048; mixture = 261120; 2 chunks.
        val geometry = mdxChunkGeometry(0)
        assertEquals(258_048, geometry.pad)
        assertEquals(261_120, geometry.mixtureLength)
        assertEquals(2, geometry.chunkCount)
    }

    @Test
    fun oneSampleShortOfGenSizeNeedsAlmostNoPad() {
        // length 254975 = genSize - 1: pad = 254976 + 3072 - 254975 = 3073;
        // mixture = 3072 + 254975 + 3073 = 261120; 2 chunks.
        val geometry = mdxChunkGeometry(254_975)
        assertEquals(3_073, geometry.pad)
        assertEquals(261_120, geometry.mixtureLength)
        assertEquals(2, geometry.chunkCount)
    }

    @Test
    fun exactlyGenSizeKeepsAFullGenerationOfPad() {
        // length 254976: remainder 0, so pad stays 258048 rather than collapsing to trim;
        // mixture = 3072 + 254976 + 258048 = 516096; starts 0, 195840, 391680 -> 3 chunks.
        val geometry = mdxChunkGeometry(254_976)
        assertEquals(258_048, geometry.pad)
        assertEquals(516_096, geometry.mixtureLength)
        assertEquals(3, geometry.chunkCount)
    }

    @Test
    fun genSizePlusOneRoundsTheSameMixtureLength() {
        // length 254977: pad = 254976 + 3072 - 1 = 258047;
        // mixture = 3072 + 254977 + 258047 = 516096; 3 chunks.
        val geometry = mdxChunkGeometry(254_977)
        assertEquals(258_047, geometry.pad)
        assertEquals(516_096, geometry.mixtureLength)
        assertEquals(3, geometry.chunkCount)
    }

    @Test
    fun twoGenerationsSpanFourChunks() {
        // length 509952 = 2 * genSize: pad 258048;
        // mixture = 3072 + 509952 + 258048 = 771072; starts 0, 195840, 391680, 587520 -> 4.
        val geometry = mdxChunkGeometry(509_952)
        assertEquals(258_048, geometry.pad)
        assertEquals(771_072, geometry.mixtureLength)
        assertEquals(4, geometry.chunkCount)
    }
}
