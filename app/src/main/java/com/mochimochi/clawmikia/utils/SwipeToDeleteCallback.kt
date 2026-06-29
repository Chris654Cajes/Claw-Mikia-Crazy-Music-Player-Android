package com.mochimochi.clawmikiacrazy.utils

import android.content.Context
import android.graphics.*
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.mochimochi.clawmikiacrazy.R

abstract class SwipeToDeleteCallback(context: Context) :
    ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

    private val deleteIcon = ContextCompat.getDrawable(context, R.drawable.ic_delete)
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#f44336")
    }
    private val density = context.resources.displayMetrics.density
    private val cornerRadius = 12f * density
    private val buttonWidth = 70f * density
    private val margin = 8f * density
    private val iconSize = 24f * density

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder
    ): Boolean = false

    override fun onChildDraw(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val itemView = viewHolder.itemView

        if (dX < 0) {
            val right = itemView.right.toFloat()
            val foregroundRight = right + dX

            // The button "slides" by staying attached to the right of the moving foreground
            val btnLeft = foregroundRight + margin
            val btnRight = foregroundRight + margin + buttonWidth

            // Only draw if the button is at least partially on screen
            if (btnLeft < right) {
                val rect = RectF(
                    btnLeft,
                    itemView.top + margin,
                    btnRight.coerceAtMost(right - margin),
                    itemView.bottom - margin
                )

                if (rect.width() > 0) {
                    // Draw the red rounded button background
                    c.drawRoundRect(rect, cornerRadius, cornerRadius, buttonPaint)

                    // Draw the delete icon centered in the button area
                    deleteIcon?.let { icon ->
                        val centerX = (rect.left + rect.right) / 2
                        val centerY = (rect.top + rect.bottom) / 2

                        // Only draw icon if there is enough width revealed
                        if (rect.width() > iconSize) {
                            val iconLeft = (centerX - iconSize / 2).toInt()
                            val iconTop = (centerY - iconSize / 2).toInt()
                            val iconRight = (centerX + iconSize / 2).toInt()
                            val iconBottom = (centerY + iconSize / 2).toInt()

                            icon.setBounds(iconLeft, iconTop, iconRight, iconBottom)
                            icon.setTint(Color.WHITE)
                            icon.draw(c)
                        }
                    }
                }
            }
        }

        // Standard foreground translation
        val foregroundView =
            viewHolder.itemView.findViewById<android.view.View>(R.id.viewForeground)
        if (foregroundView != null) {
            getDefaultUIUtil().onDraw(
                c,
                recyclerView,
                foregroundView,
                dX,
                dY,
                actionState,
                isCurrentlyActive
            )
        } else {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
        }
    }

    override fun onChildDrawOver(
        c: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder?,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean
    ) {
        val foregroundView =
            viewHolder?.itemView?.findViewById<android.view.View>(R.id.viewForeground)
        if (foregroundView != null) {
            getDefaultUIUtil().onDrawOver(
                c,
                recyclerView,
                foregroundView,
                dX,
                dY,
                actionState,
                isCurrentlyActive
            )
        } else {
            super.onChildDrawOver(
                c,
                recyclerView,
                viewHolder,
                dX,
                dY,
                actionState,
                isCurrentlyActive
            )
        }
    }

    override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
        val foregroundView =
            viewHolder.itemView.findViewById<android.view.View>(R.id.viewForeground)
        if (foregroundView != null) {
            getDefaultUIUtil().clearView(foregroundView)
        } else {
            super.clearView(recyclerView, viewHolder)
        }
    }

    override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
        if (viewHolder != null) {
            val foregroundView =
                viewHolder.itemView.findViewById<android.view.View>(R.id.viewForeground)
            if (foregroundView != null) {
                getDefaultUIUtil().onSelected(foregroundView)
            } else {
                super.onSelectedChanged(viewHolder, actionState)
            }
        }
    }
}
