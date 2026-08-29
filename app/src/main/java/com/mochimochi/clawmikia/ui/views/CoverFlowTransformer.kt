package com.mochimochi.clawmikiacrazy.ui.views

import android.view.View
import androidx.viewpager2.widget.ViewPager2
import com.mochimochi.clawmikiacrazy.R
import kotlin.math.abs

class CoverFlowTransformer : ViewPager2.PageTransformer {
    override fun transformPage(page: View, position: Float) {
        val absPos = abs(position)

        // Main container scaling
        val scale = 0.85f + (1 - 0.85f) * (1 - absPos.coerceIn(0f, 1f))
        page.scaleX = scale
        page.scaleY = scale

        // Rotation for 3D effect
        page.rotationY = position * -35f

        // Translation for overlap
        page.translationX = -position * (page.width / 2.5f)

        // Elevation management
        page.elevation = if (absPos < 0.5f) 20f else 0f

        // Depth alpha
        page.alpha = 0.4f + (1 - 0.4f) * (1 - absPos.coerceIn(0f, 1f))
    }
}
