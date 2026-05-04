package com.musicvault.audio.analysis

import android.content.Context
import android.util.Log
import com.musicvault.data.db.MusicDatabase
import com.musicvault.data.model.SongAnalysis
import com.musicvault.data.model.WaveformCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Orchestrates waveform analysis, BPM/key detection, and silence detection.
 * Results are persisted to Room so analysis only runs once per song.
 * Long-running analysis is dispatched on IO via coroutines to avoid ANRs.
 */
class AnalysisEngine(private val context: Context) {

    private val TAG = "AnalysisEngine"
    private val db = MusicDatabase.getDatabase(context)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _analysisProgress = MutableStateFlow<AnalysisProgress>(AnalysisProgress.Idle)
    val analysisProgress: StateFlow<AnalysisProgress> = _analysisProgress

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
                if (existing != null && existing.analysisVersion >= CURRENT_VERSION) {
                    return@launch
                }
                runFullAnalysis(songId, filePath, durationMs)
            } catch (e: Exception) {
                Log.e(TAG, "Analysis failed for $songId: ${e.message}")
                _analysisProgress.value = AnalysisProgress.Failed(songId, e.message ?: "unknown")
            }
        }
    }

    /** Force re-analysis even if cached. */
    fun forceAnalyze(songId: Long, filePath: String, durationMs: Long) {
        scope.launch {
            runFullAnalysis(songId, filePath, durationMs)
        }
    }

    /** Generates and caches waveform data. */
    fun generateWaveform(songId: Long, filePath: String) {
        scope.launch {
            try {
                val existing = db.waveformCacheDao().getForSong(songId)
                if (existing != null) return@launch

                _analysisProgress.value = AnalysisProgress.Analyzing(songId, "Waveform")
                val amplitudes = waveformAnalyzer.analyze(filePath)
                val cache = WaveformCache(
                    songId = songId,
                    amplitudes = amplitudes.joinToString(",") { "%.4f".format(it) },
                    sampleCount = amplitudes.size
                )
                db.waveformCacheDao().insert(cache)
                _analysisProgress.value = AnalysisProgress.Done(songId)
            } catch (e: Exception) {
                Log.e(TAG, "Waveform generation failed: ${e.message}")
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
            TAG,
            "Analysis complete for $songId: BPM=${bpmKeyResult.bpm}, Key=${bpmKeyResult.key}"
        )
    }

    fun cancelAll() {
        // Jobs in scope are cancelled when the scope is cancelled
    }

    companion object {
        const val CURRENT_VERSION = 1
    }
}
