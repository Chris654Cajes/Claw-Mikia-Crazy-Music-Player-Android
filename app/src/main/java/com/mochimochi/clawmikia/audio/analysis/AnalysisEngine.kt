package com.mochimochi.clawmikiacrazy.audio.analysis

import android.content.Context
import android.util.Log
import com.mochimochi.clawmikiacrazy.data.db.MusicDatabase
import com.mochimochi.clawmikiacrazy.data.model.SongAnalysis
import com.mochimochi.clawmikiacrazy.data.model.WaveformCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates waveform analysis, BPM/key detection, and silence detection.
 * Results are persisted to Room so analysis only runs once per song.
 * Long-running analysis is dispatched on IO via coroutines to avoid ANRs.
 */
class AnalysisEngine(context: Context) {

    private val tag = "AnalysisEngine"
    private val db = MusicDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _analysisProgress = MutableStateFlow<AnalysisProgress>(AnalysisProgress.Idle)

    private val waveformAnalyzer = WaveformAnalyzer(context)
    private val bpmKeyAnalyzer = BpmKeyAnalyzer(context)
    private val silenceDetector = SilenceDetector(context)

    sealed class AnalysisProgress {
        object Idle : AnalysisProgress()
        data class Analyzing(val songId: Long, val phase: String) : AnalysisProgress()
        data class Done(val songId: Long) : AnalysisProgress()
        data class Failed(val songId: Long, val error: String) : AnalysisProgress()
    }

    /** Analyzes a song only if not already cached. */
    fun analyzeIfNeeded(songId: Long, filePath: String, durationMs: Long) {
        scope.launch {
            try {
                val existing = db.songAnalysisDao().getForSong(songId)
                if (existing != null && (existing.analysisVersion >= CURRENT_VERSION)) {
                    return@launch
                }
                runFullAnalysis(songId, filePath, durationMs)
            } catch (e: Exception) {
                Log.e(tag, "Analysis failed for $songId: ${e.message}")
                _analysisProgress.value = AnalysisProgress.Failed(songId, e.message ?: "unknown")
            }
        }
    }

    private suspend fun runFullAnalysis(songId: Long, filePath: String, durationMs: Long) {
        _analysisProgress.value = AnalysisProgress.Analyzing(songId, "Waveform")
        val amplitudes = waveformAnalyzer.analyze(filePath)
        val waveCache = WaveformCache(
            songId = songId,
            amplitudes = amplitudes.joinToString(",") { "%.4f".format(it) },
            sampleCount = amplitudes.size
        )
        db.waveformCacheDao().insert(waveCache)

        _analysisProgress.value = AnalysisProgress.Analyzing(songId, "BPM & Key")
        val bpmKeyResult = bpmKeyAnalyzer.analyze(filePath)

        _analysisProgress.value = AnalysisProgress.Analyzing(songId, "Structure")
        val silenceResult = silenceDetector.detect(filePath, durationMs)

        val analysis = SongAnalysis(
            songId = songId,
            bpm = bpmKeyResult.bpm,
            bpmConfidence = bpmKeyResult.bpmConfidence,
            key = bpmKeyResult.key,
            keyConfidence = bpmKeyResult.keyConfidence,
            chorusTimestamps = silenceResult.chorusTimestamps.joinToString(","),
            silenceRegions = silenceResult.silenceRegions.joinToString(",") { "${it.first}:${it.second}" },
            suggestedLoopStart = silenceResult.suggestedLoopStart,
            suggestedLoopEnd = silenceResult.suggestedLoopEnd,
            analysisVersion = CURRENT_VERSION
        )
        db.songAnalysisDao().insert(analysis)

        _analysisProgress.value = AnalysisProgress.Done(songId)
        Log.d(
            tag,
            "Analysis complete for $songId: BPM=${bpmKeyResult.bpm}, Key=${bpmKeyResult.key}"
        )
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
