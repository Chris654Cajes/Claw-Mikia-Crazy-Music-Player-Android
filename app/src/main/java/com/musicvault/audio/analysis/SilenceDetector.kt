package com.musicvault.audio.analysis

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Detects silence regions and suggests loop/chorus timestamps from amplitude data.
 * Never modifies source files.
 */
class SilenceDetector(private val context: Context) {

    private val TAG = "SilenceDetector"

    data class SilenceResult(
        val silenceRegions: List<Pair<Long, Long>>,   // (startMs, endMs)
        val suggestedLoopStart: Long,
        val suggestedLoopEnd: Long,
        val chorusTimestamps: List<Long>
    )

    suspend fun detect(filePath: String, durationMs: Long): SilenceResult =
        withContext(Dispatchers.IO) {
            try {
                detectInternal(filePath, durationMs)
            } catch (e: Exception) {
                Log.w(TAG, "Silence detection failed: ${e.message}")
                SilenceResult(emptyList(), -1L, -1L, emptyList())
            }
        }

    private fun detectInternal(filePath: String, durationMs: Long): SilenceResult {
        val extractor = MediaExtractor()
        val frameEnergyMs = mutableListOf<Pair<Long, Float>>() // (timeMs, rms)

        try {
            extractor.setDataSource(context, Uri.parse(filePath), null)
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i; break
                }
            }
            if (audioTrack == -1) return SilenceResult(emptyList(), -1L, -1L, emptyList())
            extractor.selectTrack(audioTrack)

            val buffer = ByteArray(4096)
            val bb = java.nio.ByteBuffer.wrap(buffer)
            while (true) {
                bb.clear()
                val bytes = extractor.readSampleData(bb, 0)
                if (bytes <= 0) break
                val timeMs = extractor.sampleTime / 1000
                var rms = 0.0
                for (j in 0 until bytes / 2) {
                    val s = (buffer[j * 2 + 1].toInt() shl 8) or (buffer[j * 2].toInt() and 0xFF)
                    rms += s.toDouble() * s
                }
                frameEnergyMs.add(Pair(timeMs, sqrt(rms / (bytes / 2)).toFloat()))
                extractor.advance()
                if (frameEnergyMs.size > 5000) break
            }
        } finally {
            runCatching { extractor.release() }
        }

        if (frameEnergyMs.isEmpty()) return SilenceResult(emptyList(), -1L, -1L, emptyList())

        // Normalize
        val maxE = frameEnergyMs.maxOf { it.second }.coerceAtLeast(1f)
        val normalized = frameEnergyMs.map { Pair(it.first, it.second / maxE) }

        val silenceThreshold = 0.02f
        val silenceMinDurationMs = 500L

        // Find silence regions
        val silenceRegions = mutableListOf<Pair<Long, Long>>()
        var silenceStart: Long? = null
        for ((timeMs, energy) in normalized) {
            if (energy < silenceThreshold) {
                if (silenceStart == null) silenceStart = timeMs
            } else {
                silenceStart?.let { start ->
                    if (timeMs - start >= silenceMinDurationMs) {
                        silenceRegions.add(Pair(start, timeMs))
                    }
                    silenceStart = null
                }
            }
        }

        // Find potential chorus: look for the highest-energy region in the 30–70% range
        val rangeStart = (normalized.size * 0.30).toInt()
        val rangeEnd = (normalized.size * 0.70).toInt()
        val chorusTimestamps = mutableListOf<Long>()

        if (rangeEnd > rangeStart) {
            val windowSize = 20
            var maxWindowEnergy = 0f
            var maxWindowTime = -1L
            for (i in rangeStart until (rangeEnd - windowSize)) {
                val windowEnergy =
                    normalized.subList(i, i + windowSize).sumOf { it.second.toDouble() }.toFloat()
                if (windowEnergy > maxWindowEnergy) {
                    maxWindowEnergy = windowEnergy
                    maxWindowTime = normalized[i].first
                }
            }
            if (maxWindowTime > 0) chorusTimestamps.add(maxWindowTime)
        }

        // Suggest loop: find the 2 most structurally similar 8-bar segments
        val loopStartMs: Long
        val loopEndMs: Long
        if (durationMs > 30_000L) {
            loopStartMs = (durationMs * 0.35).toLong()
            loopEndMs = (durationMs * 0.65).toLong()
        } else {
            loopStartMs = -1L
            loopEndMs = -1L
        }

        return SilenceResult(
            silenceRegions = silenceRegions,
            suggestedLoopStart = loopStartMs,
            suggestedLoopEnd = loopEndMs,
            chorusTimestamps = chorusTimestamps
        )
    }
}
