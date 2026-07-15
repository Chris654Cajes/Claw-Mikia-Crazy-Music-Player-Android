package com.mochimochi.clawmikiacrazy.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.mochimochi.clawmikiacrazy.R
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class CircularSeekBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress = 0f
    private var max = 100f
    private var strokeWidth = 20f
    private var progressColor = ContextCompat.getColor(context, R.color.neon_pink)
    private var trackColor = ContextCompat.getColor(context, R.color.seekbar_bg)
    private var thumbColor = ContextCompat.getColor(context, R.color.text_primary)
    private var thumbRadius = 15f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val rectF = RectF()
    private var listener: OnCircularSeekBarChangeListener? = null

    interface OnCircularSeekBarChangeListener {
        fun onProgressChanged(seekBar: CircularSeekBar, progress: Float, fromUser: Boolean)
        fun onStartTrackingTouch(seekBar: CircularSeekBar)
        fun onStopTrackingTouch(seekBar: CircularSeekBar)
    }

    fun setOnSeekBarChangeListener(l: OnCircularSeekBarChangeListener?) {
        listener = l
    }

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, max)
        invalidate()
    }

    fun getProgress() = progress

    fun setMax(m: Float) {
        max = m
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        val radius = (minOf(w, h) / 2f) - (maxOf(strokeWidth, thumbRadius * 2) / 2f)
        val cx = w / 2f
        val cy = h / 2f

        rectF.set(cx - radius, cy - radius, cx + radius, cy + radius)

        // Draw track
        paint.color = trackColor
        paint.strokeWidth = strokeWidth
        canvas.drawCircle(cx, cy, radius, paint)

        // Draw progress
        paint.color = progressColor
        val sweepAngle = (progress / max) * 360f
        canvas.drawArc(rectF, -90f, sweepAngle, false, paint)

        // Draw thumb
        val angle = Math.toRadians((sweepAngle - 90).toDouble())
        val tx = cx + radius * cos(angle).toFloat()
        val ty = cy + radius * sin(angle).toFloat()

        paint.style = Paint.Style.FILL
        paint.color = thumbColor
        canvas.drawCircle(tx, ty, thumbRadius, paint)
        paint.style = Paint.Style.STROKE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                listener?.onStartTrackingTouch(this)
                updateProgress(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                updateProgress(event.x, event.y)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                listener?.onStopTrackingTouch(this)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateProgress(x: Float, y: Float) {
        val cx = width / 2f
        val cy = height / 2f
        var angle = Math.toDegrees(atan2((y - cy).toDouble(), (x - cx).toDouble())).toFloat()
        angle += 90f
        if (angle < 0) angle += 360f

        progress = (angle / 360f) * max
        listener?.onProgressChanged(this, progress, true)
        invalidate()
    }

    fun setProgressColor(color: Int) {
        progressColor = color
        invalidate()
    }
}
