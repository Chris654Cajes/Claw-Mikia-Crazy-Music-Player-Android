package com.mochimochi.clawmikiacrazy.audio.analysis

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Analyzes audio files to produce normalized amplitude data for waveform rendering.
 * Always runs on IO dispatcher. Does not modify source files.
 */
class WaveformAnalyzer(private val context: Context) {

    private val TAG = "WaveformAnalyzer"
    private val TARGET_SAMPLES = 1000

    /**
     * Returns a FloatArray of [TARGET_SAMPLES] normalized amplitudes (0.0–1.0).
     * Uses MediaExtractor to decode raw PCM from the file.
     * Falls back to a synthetic waveform if extraction fails.
     */
    suspend fun analyze(filePath: String): FloatArray = withContext(Dispatchers.IO) {
        try {
            analyzeInternal(filePath)
        } catch (e: Exception) {
            Log.w(
                TAG,
                "Waveform analysis failed for $filePath: ${e.message}, using synthetic fallback"
            )
            syntheticFallback()
        }
    }

    private fun analyzeInternal(filePath: String): FloatArray {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(filePath), null)

            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i
                    break
                }
            }

            if (audioTrack == -1) return syntheticFallback()
            extractor.selectTrack(audioTrack)

            val format = extractor.getTrackFormat(audioTrack)
            val totalDurationUs = format.getLong(MediaFormat.KEY_DURATION)

            // Sample at evenly spaced intervals
            val amplitudes = FloatArray(TARGET_SAMPLES)
            val stepUs = totalDurationUs / TARGET_SAMPLES

            val sampleBuffer = ByteArray(65536)

            for (i in 0 until TARGET_SAMPLES) {
                val targetUs = i * stepUs
                extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

                val bytesRead = extractor.readSampleData(java.nio.ByteBuffer.wrap(sampleBuffer), 0)
                if (bytesRead <= 0) {
                    amplitudes[i] = 0f
                    continue
                }

                // Convert bytes to amplitude: treat as 16-bit PCM pairs
                var sumSquares = 0.0
                val shorts = bytesRead / 2
                if (shorts > 0) {
                    for (j in 0 until minOf(shorts, 512)) {
                        val idx = j * 2
                        val sample =
                            (sampleBuffer[idx + 1].toInt() shl 8) or (sampleBuffer[idx].toInt() and 0xFF)
                        sumSquares += (sample.toDouble() * sample.toDouble())
                    }
                    amplitudes[i] = (sqrt(sumSquares / shorts) / 32768.0).toFloat().coerceIn(0f, 1f)
                }
            }

            // Normalize to max=1.0
            val max = amplitudes.max().coerceAtLeast(0.001f)
            return FloatArray(TARGET_SAMPLES) { (amplitudes[it] / max).coerceIn(0f, 1f) }

        } finally {
            runCatching { extractor.release() }
        }
    }

    /** Synthetic waveform for when extraction fails — produces a plausible-looking shape. */
    private fun syntheticFallback(): FloatArray {
        return FloatArray(TARGET_SAMPLES) { i ->
            val t = i.toFloat() / TARGET_SAMPLES
            val base = (0.3f + 0.5f * kotlin.math.sin(t * Math.PI.toFloat() * 2)).coerceIn(0f, 1f)
            (base + (Math.random().toFloat() - 0.5f) * 0.2f).coerceIn(0.05f, 0.95f)
        }
    }
}
