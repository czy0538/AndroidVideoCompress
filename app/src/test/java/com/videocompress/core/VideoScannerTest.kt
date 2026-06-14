package com.videocompress.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScannerTest {

    private val target8Mbps = 8_000_000L
    private val target1080p = 1080

    @Test
    fun `high bitrate and high resolution should compress`() {
        assertTrue(VideoScanner.shouldCompress(15_000_000L, 3840, 2160, target8Mbps, target1080p))
    }

    @Test
    fun `high bitrate but low resolution should compress`() {
        assertTrue(VideoScanner.shouldCompress(15_000_000L, 1920, 1080, target8Mbps, target1080p))
    }

    @Test
    fun `low bitrate but high resolution should compress`() {
        assertTrue(VideoScanner.shouldCompress(5_000_000L, 3840, 2160, target8Mbps, target1080p))
    }

    @Test
    fun `low bitrate and low resolution should not compress`() {
        assertFalse(VideoScanner.shouldCompress(5_000_000L, 1920, 1080, target8Mbps, target1080p))
    }

    @Test
    fun `exact target values should not compress`() {
        assertFalse(VideoScanner.shouldCompress(8_000_000L, 1920, 1080, target8Mbps, target1080p))
    }

    @Test
    fun `portrait video high resolution should compress`() {
        assertTrue(VideoScanner.shouldCompress(5_000_000L, 2160, 3840, target8Mbps, target1080p))
    }

    @Test
    fun `portrait video low resolution should not compress`() {
        assertFalse(VideoScanner.shouldCompress(5_000_000L, 1080, 1920, target8Mbps, target1080p))
    }

    @Test
    fun `original resolution target only checks bitrate`() {
        assertFalse(VideoScanner.shouldCompress(5_000_000L, 3840, 2160, target8Mbps, -1))
        assertTrue(VideoScanner.shouldCompress(15_000_000L, 3840, 2160, target8Mbps, -1))
    }
}
