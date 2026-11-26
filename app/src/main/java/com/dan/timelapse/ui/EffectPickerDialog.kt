package com.dan.timelapse.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.dan.timelapse.Effect
import com.dan.timelapse.R
import com.dan.timelapse.widgets.EffectPreviewView
import com.google.android.material.card.MaterialCardView

/**
 * Full-screen gallery for choosing a filter.
 *
 * Why not a dropdown: twelve animated previews need roughly 2x the width a popup row can
 * offer, and the popup also had to scroll - the last rows fell below the field it was
 * anchored to. Here every preview is on screen at once and nothing scrolls: the grid
 * adapts to the window instead of overflowing it.
 *
 * Selecting is a single tap, but it is not instant: the tapped tile locks (accent border,
 * check badge, siblings recede), the header confirms "X applied", and only then does the
 * sheet fade out. Without that beat the sheet vanished on touch-down and the user never
 * saw which tile they had hit.
 */
object EffectPickerDialog {

    private const val COLUMNS = 2

    /** How long the confirmed state stays on screen before the sheet closes. */
    private const val CONFIRM_HOLD_MS = 680L
    private const val EXIT_MS = 150L

    private class Cell(
        val card: MaterialCardView,
        val preview: EffectPreviewView,
        private val name: String,
        private val selectedDescription: String
    ) {

        fun setSelected(selected: Boolean, accent: Int, density: Float) {
            card.strokeWidth = if (selected) (2.5f * density).toInt() else (1f * density).toInt()
            card.strokeColor = if (selected) accent else 0x1FFFFFFF
            card.setCardBackgroundColor(
                if (selected) ColorUtils.setAlphaComponent(accent, 46) else Color.TRANSPARENT
            )
            preview.setSelected(selected, accent)
            card.contentDescription = if (selected) selectedDescription else name
        }
    }

    fun show(context: Context, selectedIndex: Int, onPick: (Int) -> Unit) {
        val dialog = Dialog(context)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val root = LayoutInflater.from(context).inflate(R.layout.dialog_effect_picker, null)
        dialog.setContentView(root)

        val density = context.resources.displayMetrics.density
        val surface = ContextCompat.getColor(context, R.color.colorSurface)
        val onSurface = ContextCompat.getColor(context, R.color.colorOnSurface)
        val accent = ContextCompat.getColor(context, R.color.colorPrimary)
        val names = context.resources.getStringArray(R.array.effect_names)

        // The window is fully transparent; the card below is what you see, so the sheet
        // reads as a floating panel rather than a second activity.
        val sheet = root.findViewById<View>(R.id.effectPickerCard)
        sheet.background = GradientDrawable().apply {
            cornerRadius = 26f * density
            setColor(surface)
            setStroke((1f * density).toInt(), 0x1FFFFFFF)
        }

        root.findViewById<TextView>(R.id.effectPickerTitle).setTextColor(onSurface)
        val hint = root.findViewById<TextView>(R.id.effectPickerHint)

        // Applied effect: fired from the dismiss listener so every path (confirm, back,
        // close) resolves in one place instead of racing a posted callback.
        var pendingIndex: Int? = null
        var confirmed = false
        dialog.setOnDismissListener {
            pendingIndex?.let(onPick)
            pendingIndex = null
        }

        val cells = ArrayList<Cell>()
        val handler = Handler(Looper.getMainLooper())

        fun confirm(index: Int) {
            if (confirmed) return
            confirmed = true
            pendingIndex = index

            cells.forEachIndexed { i, cell ->
                if (i == index) {
                    cell.setSelected(true, accent, density)
                    cell.card.animate().cancel()
                    cell.card.animate()
                        .scaleX(1.045f).scaleY(1.045f)
                        .setDuration(150)
                        .setInterpolator(DecelerateInterpolator())
                        .withEndAction {
                            cell.card.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(220)
                                .setInterpolator(OvershootInterpolator(1.1f))
                                .start()
                        }
                        .start()
                } else {
                    cell.card.animate().cancel()
                    cell.card.animate().alpha(0.30f).setDuration(200).start()
                }
            }

            hint.text = context.getString(R.string.effect_picker_applied, names.getOrElse(index) { "" })
            hint.setTextColor(accent)
            hint.alpha = 0.3f
            hint.animate().alpha(1f).setDuration(150).start()

            root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)

            handler.postDelayed({
                if (!dialog.isShowing) return@postDelayed
                sheet.animate()
                    .alpha(0f)
                    .scaleX(0.97f).scaleY(0.97f)
                    .setDuration(EXIT_MS)
                    .withEndAction { dialog.dismiss() }
                    .start()
            }, CONFIRM_HOLD_MS)
        }

        buildGrid(context, root.findViewById(R.id.effectGrid), selectedIndex, accent, cells) { index ->
            confirm(index)
        }

        root.findViewById<ImageButton>(R.id.effectPickerClose).setOnClickListener { dialog.dismiss() }

        dialog.window?.apply {
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.80f)
        }

        val basePad = (10f * density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(
                left = basePad + bars.left,
                top = basePad + bars.top,
                right = basePad + bars.right,
                bottom = basePad + bars.bottom
            )
            insets
        }
        root.requestApplyInsets()

        dialog.show()
    }

    private fun buildGrid(
        context: Context,
        grid: LinearLayout,
        selectedIndex: Int,
        accent: Int,
        cells: MutableList<Cell>,
        onPick: (Int) -> Unit
    ) {
        val density = context.resources.displayMetrics.density
        // 14 dp between tiles: at 10 dp they read as one continuous sheet, and each
        // preview has to look like an independent, tappable tile.
        val gap = (14f * density).toInt()
        val names = context.resources.getStringArray(R.array.effect_names)
        val rows = (Effect.UI_ORDER.size + COLUMNS - 1) / COLUMNS

        for (rowIndex in 0 until rows) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            grid.addView(
                row,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    if (rowIndex > 0) topMargin = gap
                }
            )

            for (column in 0 until COLUMNS) {
                val index = rowIndex * COLUMNS + column
                val cellParams = LinearLayout.LayoutParams(
                    0,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    1f
                ).apply {
                    if (column == 0) marginEnd = gap
                }
                if (index >= Effect.UI_ORDER.size) {
                    // Keeps the last row aligned with the one above it.
                    row.addView(View(context), cellParams)
                    continue
                }
                val cell = buildCell(
                    context = context,
                    index = index,
                    name = names.getOrElse(index) { "" },
                    selected = index == selectedIndex,
                    accent = accent,
                    onClick = { onPick(index) }
                )
                cells.add(cell)
                row.addView(cell.card, cellParams)
            }
        }
    }

    private fun buildCell(
        context: Context,
        index: Int,
        name: String,
        selected: Boolean,
        accent: Int,
        onClick: () -> Unit
    ): Cell {
        val density = context.resources.displayMetrics.density
        val radius = 14f * density
        val card = MaterialCardView(context).apply {
            this.radius = radius
            cardElevation = 0f
            isClickable = true
            isFocusable = true
            rippleColor = ColorStateList.valueOf(0x33FFFFFF)
            setOnClickListener { onClick() }
        }

        // No caption below the tile: the name is drawn inside the picture, so the card is
        // simply the preview and the aspect is never broken.
        val preview = EffectPreviewView(context).apply {
            cornerRadius = radius
            setEffect(Effect.UI_ORDER[index], (index * 0.137f) % 1f)
            setTitle(name)
        }
        card.addView(
            preview,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        val cell = Cell(
            card = card,
            preview = preview,
            name = name,
            selectedDescription = context.getString(R.string.effect_picker_selected, name)
        )
        cell.setSelected(selected, accent, density)
        return cell
    }
}
