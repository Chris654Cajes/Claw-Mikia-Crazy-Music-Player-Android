package com.mochimochi.clawmikiacrazy.ui.player

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.mochimochi.clawmikiacrazy.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * A fully interactive waveform view that renders amplitude data and overlays:
 *  - Playback cursor (scrubbing)
 *  - Trim handles (draggable left/right)
 *  - Loop region (blue tint)
 *  - A-B repeat markers (green pins)
 *  - Skip regions (red overlays)
 *  - Chorus markers (yellow lines)
 *  - BPM grid (optional)
 *
 * Never modifies source audio.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ─── Data ─────────────────────────────────────────────────────────────────
    private var amplitudes: FloatArray = FloatArray(0)
    private var durationMs: Long = 0L
    private var currentPositionMs: Long = 0L

    // ─── Trim ─────────────────────────────────────────────────────────────────
    var trimStartMs: Long = 0L; private set
    var trimEndMs: Long = -1L; private set
    var trimEnabled: Boolean = false

    // ─── Loop ─────────────────────────────────────────────────────────────────
    var loopStartMs: Long = -1L
    var loopEndMs: Long = -1L
    var loopEnabled: Boolean = false

    // ─── A-B Repeat ──────────────────────────────────────────────────────────
    var abRepeatA: Long = -1L
    var abRepeatB: Long = -1L
    var abRepeatEnabled: Boolean = false

    // ─── Skip Regions ─────────────────────────────────────────────────────────
    var skipRegions: List<Pair<Long, Long>> = emptyList()

    // ─── Chorus ───────────────────────────────────────────────────────────────
    var chorusTimestamps: List<Long> = emptyList()

    // ─── Callbacks ────────────────────────────────────────────────────────────
    var onScrub: ((Long) -> Unit)? = null
    var onTrimChanged: ((Long, Long) -> Unit)? = null
    var onLoopChanged: ((Long, Long) -> Unit)? = null

    // ─── Paint ────────────────────────────────────────────────────────────────
    private val waveColorPlayed = Color.parseColor("#FA024D")      // neon pink
    private val waveColorUnplayed = Color.parseColor("#44445A")    // muted
    private val waveColorTrimmed = Color.parseColor("#22222A")     // very dim

    private val paintWavePlayed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = waveColorPlayed; style = Paint.Style.FILL
    }
    private val paintWaveUnplayed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = waveColorUnplayed; style = Paint.Style.FILL
    }
    private val paintWaveTrimmed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = waveColorTrimmed; style = Paint.Style.FILL
    }
    private val paintCursor = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 3f; style = Paint.Style.STROKE
    }
    private val paintTrimOverlay = Paint().apply {
        color = Color.parseColor("#66000000"); style = Paint.Style.FILL
    }
    private val paintTrimHandle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FF6F00"); style = Paint.Style.FILL
    }
    private val paintLoop = Paint().apply {
        color = Color.parseColor("#221E00FF"); style = Paint.Style.FILL
    }
    private val paintLoopBorder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#661E00FF"); strokeWidth = 2f; style = Paint.Style.STROKE
    }
    private val paintAbRepeat = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#8800FF00"); style = Paint.Style.FILL
    }
    private val paintSkip = Paint().apply {
        color = Color.parseColor("#55FF0000"); style = Paint.Style.FILL
    }
    private val paintChorus = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#AAEEFF00"); strokeWidth = 2f; style = Paint.Style.STROKE
    }

    // ─── Touch ────────────────────────────────────────────────────────────────
    private enum class DragTarget { NONE, CURSOR, TRIM_START, TRIM_END, LOOP_START, LOOP_END, AB_A, AB_B }

    private var dragTarget = DragTarget.NONE
    private val HANDLE_TOUCH_RADIUS = 24f

    fun setAmplitudes(data: FloatArray, durationMs: Long) {
        amplitudes = data
        this.durationMs = durationMs
        trimEndMs = if (trimEndMs < 0) durationMs else trimEndMs
        invalidate()
    }

    fun setPosition(posMs: Long) {
        currentPositionMs = posMs
        invalidate()
    }

    fun setTrim(startMs: Long, endMs: Long) {
        trimStartMs = startMs
        trimEndMs = if (endMs < 0) durationMs else endMs
        invalidate()
    }

    // ─── Drawing ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (amplitudes.isEmpty() || durationMs == 0L) {
            drawEmptyState(canvas)
            return
        }

        val w = width.toFloat()
        val h = height.toFloat()
        val midY = h / 2f
        val barWidth = w / amplitudes.size
        val cursorX = msToX(currentPositionMs)
        val trimStartX = msToX(trimStartMs)
        val trimEndX = if (trimEndMs > 0) msToX(trimEndMs) else w

        // Draw loop region background
        if (loopEnabled && loopStartMs >= 0 && loopEndMs >= 0) {
            canvas.drawRect(msToX(loopStartMs), 0f, msToX(loopEndMs), h, paintLoop)
            canvas.drawLine(msToX(loopStartMs), 0f, msToX(loopStartMs), h, paintLoopBorder)
            canvas.drawLine(msToX(loopEndMs), 0f, msToX(loopEndMs), h, paintLoopBorder)
        }

        // Draw A-B repeat region
        if (abRepeatEnabled && abRepeatA >= 0 && abRepeatB >= 0) {
            canvas.drawRect(msToX(abRepeatA), 0f, msToX(abRepeatB), h, paintAbRepeat)
        }

        // Draw skip regions
        skipRegions.forEach { (start, end) ->
            canvas.drawRect(msToX(start), 0f, msToX(end), h, paintSkip)
        }

        // Draw waveform bars
        amplitudes.forEachIndexed { i, amp ->
            val x = i * barWidth
            val barHeight = (amp * midY * 0.9f).coerceAtLeast(2f)
            val paint = when {
                x < trimStartX || x > trimEndX -> paintWaveTrimmed
                x < cursorX -> paintWavePlayed
                else -> paintWaveUnplayed
            }
            canvas.drawRect(x, midY - barHeight, x + barWidth - 1f, midY + barHeight, paint)
        }

        // Draw trim overlay (darken outside trim)
        if (trimEnabled) {
            if (trimStartX > 0) canvas.drawRect(0f, 0f, trimStartX, h, paintTrimOverlay)
            if (trimEndX < w) canvas.drawRect(trimEndX, 0f, w, h, paintTrimOverlay)

            // Trim handles
            canvas.drawRoundRect(trimStartX - 6f, 0f, trimStartX + 6f, h, 6f, 6f, paintTrimHandle)
            canvas.drawRoundRect(trimEndX - 6f, 0f, trimEndX + 6f, h, 6f, 6f, paintTrimHandle)
        }

        // Chorus markers
        chorusTimestamps.forEach { ts ->
            canvas.drawLine(msToX(ts), 0f, msToX(ts), h, paintChorus)
        }

        // Cursor
        canvas.drawLine(cursorX, 0f, cursorX, h, paintCursor)
        // Cursor circle
        canvas.drawCircle(
            cursorX,
            midY,
            8f,
            paintCursor.apply { style = Paint.Style.FILL; color = Color.WHITE })
        paintCursor.style = Paint.Style.STROKE
    }

    private fun drawEmptyState(canvas: Canvas) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#333344"); style = Paint.Style.FILL
        }
        val midY = height / 2f
        val w = width.toFloat()
        // Draw flat line pattern
        for (i in 0..100) {
            val x = i * (w / 100f)
            val h2 = (4f + (i % 7) * 2f)
            canvas.drawRect(x, midY - h2, x + (w / 100f) - 1f, midY + h2, paint)
        }
    }

    // ─── Coordinates ─────────────────────────────────────────────────────────

    private fun msToX(ms: Long): Float {
        if (durationMs == 0L) return 0f
        return (ms.toFloat() / durationMs.toFloat()) * width.toFloat()
    }

    private fun xToMs(x: Float): Long {
        if (width == 0) return 0L
        return ((x / width.toFloat()) * durationMs).toLong().coerceIn(0L, durationMs)
    }

    // ─── Touch Handling ──────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                dragTarget = determineDragTarget(x)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val ms = xToMs(x)
                when (dragTarget) {
                    DragTarget.CURSOR -> {
                        currentPositionMs = ms; onScrub?.invoke(ms); invalidate()
                    }

                    DragTarget.TRIM_START -> {
                        trimStartMs = ms.coerceAtMost((trimEndMs - 1000L).coerceAtLeast(0L))
                        onTrimChanged?.invoke(trimStartMs, trimEndMs); invalidate()
                    }

                    DragTarget.TRIM_END -> {
                        trimEndMs = ms.coerceAtLeast(trimStartMs + 1000L)
                        onTrimChanged?.invoke(trimStartMs, trimEndMs); invalidate()
                    }

                    DragTarget.LOOP_START -> {
                        loopStartMs =
                            ms.coerceAtMost((loopEndMs - 500L).coerceAtLeast(0L)); onLoopChanged?.invoke(
                            loopStartMs,
                            loopEndMs
                        ); invalidate()
                    }

                    DragTarget.LOOP_END -> {
                        loopEndMs = ms.coerceAtLeast(loopStartMs + 500L); onLoopChanged?.invoke(
                            loopStartMs,
                            loopEndMs
                        ); invalidate()
                    }

                    DragTarget.AB_A -> {
                        abRepeatA = ms; invalidate()
                    }

                    DragTarget.AB_B -> {
                        abRepeatB = ms; invalidate()
                    }

                    DragTarget.NONE -> {}
                }
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                if (dragTarget == DragTarget.CURSOR) onScrub?.invoke(xToMs(x))
                dragTarget = DragTarget.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun determineDragTarget(x: Float): DragTarget {
        val cursorX = msToX(currentPositionMs)
        if (abs(x - cursorX) < HANDLE_TOUCH_RADIUS) return DragTarget.CURSOR
        if (trimEnabled) {
            if (abs(x - msToX(trimStartMs)) < HANDLE_TOUCH_RADIUS) return DragTarget.TRIM_START
            if (abs(x - msToX(trimEndMs)) < HANDLE_TOUCH_RADIUS) return DragTarget.TRIM_END
        }
        if (loopEnabled && loopStartMs >= 0) {
            if (abs(x - msToX(loopStartMs)) < HANDLE_TOUCH_RADIUS) return DragTarget.LOOP_START
            if (abs(x - msToX(loopEndMs)) < HANDLE_TOUCH_RADIUS) return DragTarget.LOOP_END
        }
        if (abRepeatEnabled && abRepeatA >= 0) {
            if (abs(x - msToX(abRepeatA)) < HANDLE_TOUCH_RADIUS) return DragTarget.AB_A
            if (abs(x - msToX(abRepeatB)) < HANDLE_TOUCH_RADIUS) return DragTarget.AB_B
        }
        return DragTarget.CURSOR
    }
}
