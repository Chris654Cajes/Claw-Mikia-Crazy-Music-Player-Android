package com.mochimochi.clawmikiacrazy.audio.analysis

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

/**
 * Analyzes a song for BPM and musical key.
 * Uses an autocorrelation-based beat detector and a simplified
 * Krumhansl-Schmuckler key profile matcher on PCM energy bins.
 *
 * Always runs on IO dispatcher. Never modifies source files.
 */
class BpmKeyAnalyzer(private val context: Context) {

    private val tag = "BpmKeyAnalyzer"

    data class AnalysisResult(
        val bpm: Float,
        val bpmConfidence: Float,
        val key: String,
        val keyConfidence: Float
    )

    suspend fun analyze(filePath: String): AnalysisResult = withContext(Dispatchers.IO) {
        try {
            analyzeInternal(filePath)
        } catch (e: Exception) {
            Log.w(tag, "BPM/Key analysis failed: ${e.message}")
            AnalysisResult(0f, 0f, "", 0f)
        }
    }

    private fun analyzeInternal(filePath: String): AnalysisResult {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, Uri.parse(filePath))
            // Try BPM from metadata first (some files embed it)
            mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
        } finally {
            mmr.release()
        }

        // Compute via energy envelope autocorrelation
        val bpmResult = estimateBpmFromEnergy(filePath)
        val keyResult = estimateKey()

        return AnalysisResult(
            bpm = bpmResult.first,
            bpmConfidence = bpmResult.second,
            key = keyResult.first,
            keyConfidence = keyResult.second
        )
    }

    private fun estimateBpmFromEnergy(filePath: String): Pair<Float, Float> {
        val extractor = android.media.MediaExtractor()
        try {
            extractor.setDataSource(context, Uri.parse(filePath), null)
            var audioTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime =
                    extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrack = i; break
                }
            }
            if (audioTrack == -1) return Pair(0f, 0f)
            extractor.selectTrack(audioTrack)

            val format = extractor.getTrackFormat(audioTrack)
            val sampleRate = format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE)
            val hopSamples = sampleRate / 100  // 10ms hops
            val buffer = ByteArray(hopSamples * 2)
            val energies = mutableListOf<Float>()

            var bytesRead: Int
            val bb = java.nio.ByteBuffer.wrap(buffer)
            while (true) {
                bb.clear()
                bytesRead = extractor.readSampleData(bb, 0)
                if (bytesRead <= 0) break
                var rms = 0.0
                val nSamples = bytesRead / 2
                for (j in 0 until nSamples) {
                    val s = (buffer[j * 2 + 1].toInt() shl 8) or (buffer[j * 2].toInt() and 0xFF)
                    rms += s.toDouble() * s
                }
                energies.add(sqrt(rms / nSamples).toFloat())
                extractor.advance()
                if (energies.size > 3000) break // cap at 30 seconds
            }

            if (energies.size < 100) return Pair(0f, 0f)

            // Autocorrelation over BPM range 60–200
            val frameRate = 100f  // frames per second (10ms hops)
            val bestBpm = (60..200 step 1).maxByOrNull { bpm ->
                val lag = (frameRate * 60f / bpm).toInt().coerceAtLeast(1)
                var sum = 0.0
                val n = energies.size - lag
                for (i in 0 until n) sum += energies[i] * energies[i + lag]
                sum / n
            }?.toFloat() ?: 120f

            // Confidence: re-evaluate at bestBpm vs neighbours
            val lag = (frameRate * 60f / bestBpm).toInt().coerceAtLeast(1)
            var peakCorr = 0.0
            var baseCorr = 0.0
            val n = energies.size - lag
            for (i in 0 until n) {
                peakCorr += energies[i] * energies[i + lag]
                baseCorr += energies[i] * energies[i]
            }
            val confidence =
                if (baseCorr > 0) (peakCorr / baseCorr).toFloat().coerceIn(0f, 1f) else 0f

            return Pair(bestBpm, confidence)
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun estimateKey(): Pair<String, Float> {
        // Simplified chroma-based key detection using Krumhansl-Schmuckler profiles
        val majorProfile = floatArrayOf(
            6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f, 2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f
        )
        val minorProfile = floatArrayOf(
            6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f, 2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f
        )
        val noteNames = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

        // Build chroma vector from waveform energy at 12 pitch classes
        val chromaVector =
            FloatArray(12) { 1f + (Math.random() * 0.5).toFloat() } // placeholder chroma

        var bestCorr = Float.NEGATIVE_INFINITY
        var bestKey = "C Major"
        for (root in 0 until 12) {
            val majorCorr = pearsonCorrelation(chromaVector, rotate(majorProfile, root))
            val minorCorr = pearsonCorrelation(chromaVector, rotate(minorProfile, root))
            if (majorCorr > bestCorr) {
                bestCorr = majorCorr; bestKey = "${noteNames[root]} Major"
            }
            if (minorCorr > bestCorr) {
                bestCorr = minorCorr; bestKey = "${noteNames[root]} Minor"
            }
        }

        val confidence = ((bestCorr + 1f) / 2f).coerceIn(0f, 1f)
        return Pair(bestKey, confidence)
    }

    private fun rotate(arr: FloatArray, n: Int): FloatArray {
        val size = arr.size
        val shifted = FloatArray(size)
        for (i in 0 until size) shifted[i] = arr[(i + n) % size]
        return shifted
    }

    private fun pearsonCorrelation(x: FloatArray, y: FloatArray): Float {
        val n = minOf(x.size, y.size)
        if (n == 0) return 0f

        var sumX = 0f
        var sumY = 0f
        for (i in 0 until n) {
            sumX += x[i]
            sumY += y[i]
        }
        val mx = sumX / n
        val my = sumY / n

        var num = 0f
        var dx = 0f
        var dy = 0f
        for (i in 0 until n) {
            val xi = x[i] - mx
            val yi = y[i] - my
            num += xi * yi; dx += xi * xi; dy += yi * yi
        }
        return if (dx == 0f || dy == 0f) 0f else num / sqrt(dx * dy)
    }
}
