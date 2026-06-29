package com.mochimochi.clawmikiacrazy.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.mochimochi.clawmikiacrazy.R
import com.mochimochi.clawmikiacrazy.data.model.SkipRegion

class SkipRegionsOverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.neon_yellow)
        alpha = 100 // Semi-transparent
        style = Paint.Style.FILL
    }

    private var regions: List<SkipRegion> = emptyList()
    private var maxDuration: Long = 0

    fun setRegions(regions: List<SkipRegion>, maxDuration: Long) {
        this.regions = regions
        this.maxDuration = maxDuration
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (maxDuration <= 0) return

        val w = width.toFloat()
        val h = height.toFloat()

        regions.forEach { region ->
            if (region.isEnabled) {
                val left = (region.startMs.toFloat() / maxDuration) * w
                val right = (region.endMs.toFloat() / maxDuration) * w
                canvas.drawRect(left, 0f, right, h, paint)
            }
        }
    }
}
