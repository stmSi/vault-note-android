package com.vaultnote.feature.vault

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.vaultnote.R

internal class VaultSwipeCallback(
    context: Context,
    private val adapter: VaultItemAdapter,
    private val onSwipe: (itemId: String, change: VaultItemChange) -> Unit,
) : ItemTouchHelper.Callback() {
    private val archivePaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.vault_primary)
    }
    private val trashPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.vault_error)
    }
    private val archiveIcon = tintedIcon(context, R.drawable.ic_archive)
    private val trashIcon = tintedIcon(context, R.drawable.ic_delete)
    private val restoreIcon = tintedIcon(context, R.drawable.ic_restore)
    private val iconSize = context.resources.getDimensionPixelSize(R.dimen.swipe_action_icon)
    private val iconMargin = context.resources.getDimensionPixelSize(R.dimen.space_m)

    override fun getMovementFlags(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
    ): Int {
        val row = adapter.itemAt(viewHolder.bindingAdapterPosition)
            ?: return makeMovementFlags(0, 0)
        val swipeFlags = when (row.section) {
            VaultSection.ACTIVE, VaultSection.ARCHIVED ->
                ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT
            VaultSection.TRASH -> ItemTouchHelper.RIGHT
        }
        return makeMovementFlags(0, swipeFlags)
    }

    override fun onMove(
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        target: RecyclerView.ViewHolder,
    ): Boolean = false

    override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
        val row = adapter.itemAt(viewHolder.bindingAdapterPosition)
        if (row == null) {
            viewHolder.itemView.translationX = 0f
            return
        }
        val change = when {
            direction == ItemTouchHelper.LEFT -> VaultItemChange.TRASHED
            row.section == VaultSection.ACTIVE -> VaultItemChange.ARCHIVED
            row.section == VaultSection.ARCHIVED -> VaultItemChange.RESTORED_FROM_ARCHIVE
            else -> VaultItemChange.RESTORED_FROM_TRASH
        }
        onSwipe(row.note.id, change)
    }

    override fun onChildDraw(
        canvas: Canvas,
        recyclerView: RecyclerView,
        viewHolder: RecyclerView.ViewHolder,
        dX: Float,
        dY: Float,
        actionState: Int,
        isCurrentlyActive: Boolean,
    ) {
        if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE && dX != 0f) {
            val row = adapter.itemAt(viewHolder.bindingAdapterPosition)
            if (row != null) drawAction(canvas, viewHolder, row, dX)
        }
        super.onChildDraw(
            canvas,
            recyclerView,
            viewHolder,
            dX,
            dY,
            actionState,
            isCurrentlyActive,
        )
    }

    private fun drawAction(
        canvas: Canvas,
        viewHolder: RecyclerView.ViewHolder,
        row: VaultListItem,
        dX: Float,
    ) {
        val item = viewHolder.itemView
        val swipingRight = dX > 0f
        val isTrash = !swipingRight
        val paint = if (isTrash) trashPaint else archivePaint
        val left = if (swipingRight) item.left.toFloat() else item.right + dX
        val right = if (swipingRight) item.left + dX else item.right.toFloat()
        canvas.drawRect(left, item.top.toFloat(), right, item.bottom.toFloat(), paint)

        val icon = when {
            isTrash -> trashIcon
            row.section == VaultSection.ACTIVE -> archiveIcon
            else -> restoreIcon
        }
        val iconLeft = if (swipingRight) {
            item.left + iconMargin
        } else {
            item.right - iconMargin - iconSize
        }
        val iconTop = item.top + (item.height - iconSize) / 2
        icon.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize)
        icon.draw(canvas)
    }

    private companion object {
        fun tintedIcon(context: Context, drawableId: Int): Drawable {
            val drawable = requireNotNull(ContextCompat.getDrawable(context, drawableId)).mutate()
            DrawableCompat.setTint(drawable, Color.WHITE)
            return drawable
        }
    }
}
