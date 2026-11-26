package com.dan.timelapse.widgets

/**
 * Pure geometry for [ZoomableFrameLayout]. No Android types, so it runs on the JVM.
 *
 * Coordinate model (must match [ZoomableFrameLayout]):
 *
 *  - The container is never transformed. Each child is scaled about the container centre and
 *    then translated by `(offsetX, offsetY)`.
 *  - A content point `x` in container coordinates therefore maps to
 *      `X = viewHalf + offset + (x - viewHalf) * scale`
 *  - `offset` is the distance between the container centre and the content centre,
 *    positive = content moved RIGHT / DOWN.
 */
object ZoomMath {

    /**
     * Translation after the finger moved by [fingerDeltaPx] (screen pixels, positive = the
     * finger moved right/down). The content follows the finger 1:1.
     *
     * Note: `GestureDetector.onScroll` reports `distanceX` as *previous minus current*, so
     * callers pass `-distanceX`. Never pass `e2.rawX - e1.rawX` here: `e1` is the gesture's
     * DOWN event, so that difference is the whole travel since the finger went down and
     * re-applying it on every callback makes the pan run away.
     */
    fun panOffset(offset: Float, fingerDeltaPx: Float): Float = offset + fingerDeltaPx

    /**
     * Translation that keeps the content point currently under [focus] pinned to [focus]
     * while the scale changes by [scaleChange].
     *
     * Derivation: the point under the focus is `u = (focus - viewHalf - offset) / oldScale`.
     * After the change it must still land on the focus:
     * `focus = viewHalf + newOffset + newScale * u`, which solves to the expression below.
     */
    fun focalOffset(offset: Float, focus: Float, viewHalf: Float, scaleChange: Float): Float =
        (focus - viewHalf) * (1f - scaleChange) + offset * scaleChange

    /**
     * Scale after one pinch event. The finger ratio is applied EXACTLY once: `scaleFactor` is
     * already the raw ratio between the current and the previous finger span.
     *
     * There used to be a 1.5x "sensitivity boost" here (`1 + (f - 1) * 1.5`). It compounded
     * multiplicatively on every MotionEvent, so a normal pinch overshot by ~50% and a few
     * quick pinches slammed into the cap.
     *
     * Non-finite and non-positive ratios are ignored instead of poisoning the state.
     */
    fun scaleAfterPinch(
        scale: Float,
        scaleFactor: Float,
        minScale: Float,
        maxScale: Float
    ): Float {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f) return scale
        return (scale * scaleFactor).coerceIn(minScale, maxScale)
    }

    /**
     * Pivot for `View.setPivotX/Y`. A View pivot lives in the CHILD's own coordinate space,
     * but the zoom has to rotate and scale every child about the *container* centre. Passing
     * the container's half-size straight into `setPivot` (the old bug) is only correct when
     * the child fills the container; for a letterboxed child it puts the pivot outside the
     * child and flings it off screen on the first pinch.
     */
    fun childLocalContainerCenter(containerCenter: Float, childStart: Float): Float =
        containerCenter - childStart

    /**
     * Clamps a translation so the media can never be lost, while still letting the user inspect
     * its edges.
     *
     * The window is the **padding box** `(viewLeft, viewTop) .. (viewRight, viewBottom)`, not
     * the raw View bounds: the container is padded by the status-bar inset, and children are
     * laid out inside the padding. Measuring the raw box would centre the media half an inset
     * too high at 1x.
     *
     * Two rules, per axis, with the SAME overscroll cap on both:
     *  - Content larger than the window: the classic "no gaps" limit **plus an allowance** so
     *    an edge can be pulled inside the window.
     *  - Content smaller than the window: the edge may be walked in from its resting position
     *    once zoomed.
     *  - In both cases the edge stops at the **75 % mark**: the allowance is capped at 25 % of
     *    the window (half of the corner -> centre distance), so the user never sees more than
     *    a quarter of the screen as background on one side. A 1x fit still cannot be dragged
     *    at all. Because the cap is well under half the window the media always still covers
     *    the window centre - it can never be lost.
     *
     * @param out receives `[offsetX, offsetY]`. Reused to keep the per-event path allocation free.
     */
    fun clampOffset(
        viewLeft: Float,
        viewTop: Float,
        viewRight: Float,
        viewBottom: Float,
        contentL: Float,
        contentT: Float,
        contentR: Float,
        contentB: Float,
        scale: Float,
        offX: Float,
        offY: Float,
        out: FloatArray
    ) {
        if (viewRight <= viewLeft || viewBottom <= viewTop) {
            out[0] = offX
            out[1] = offY
            return
        }

        val viewCentreX = (viewLeft + viewRight) / 2f
        val viewCentreY = (viewTop + viewBottom) / 2f
        val viewHalfW = (viewRight - viewLeft) / 2f
        val viewHalfH = (viewBottom - viewTop) / 2f

        val contentW = contentR - contentL
        val contentH = contentB - contentT

        // Displacement of the content centre caused by scaling about the window centre.
        val contentShiftX = ((contentL + contentR) * 0.5f - viewCentreX) * scale
        val contentShiftY = ((contentT + contentB) * 0.5f - viewCentreY) * scale

        val halfContentW = contentW * scale / 2f
        val halfContentH = contentH * scale / 2f

        // The most background the user may ever see on ONE side: 25 % of the window. That is
        // half of the corner -> centre distance, so the media edge stops at the 75 % mark and
        // never travels further toward the centre. Identical on both axes.
        val maxOverX = viewHalfW * 0.5f
        val maxOverY = viewHalfH * 0.5f

        // Zero at a 1x fit (a fit cannot be dragged past its edge), full by 1.5x so a zoomed
        // edge can reach the 75 % mark on BOTH axes regardless of how much it overflows.
        val zoomT = ((scale - 1f) / 0.5f).coerceIn(0f, 1f)

        // Axis where the media overflows the window: the no-gap pan is free; this adds up to
        // 25 % of background on the side being inspected.
        val overscrollX = maxOverX * zoomT
        val overscrollY = maxOverY * zoomT

        // Axis where the media is SMALLER than the window: there is no no-gap range, so walk
        // the edge in from its resting position, stopping when the near side shows 25 % of
        // background.
        val walkX = ((halfContentW - maxOverX).coerceAtLeast(0f)) * zoomT
        val walkY = ((halfContentH - maxOverY).coerceAtLeast(0f)) * zoomT

        out[0] = if (halfContentW <= viewHalfW) {
            offX.coerceIn(-contentShiftX - walkX, -contentShiftX + walkX)
        } else {
            offX.coerceIn(
                (viewRight - viewCentreX) - contentShiftX - halfContentW - overscrollX,
                (viewLeft - viewCentreX) - contentShiftX + halfContentW + overscrollX
            )
        }
        out[1] = if (halfContentH <= viewHalfH) {
            offY.coerceIn(-contentShiftY - walkY, -contentShiftY + walkY)
        } else {
            offY.coerceIn(
                (viewBottom - viewCentreY) - contentShiftY - halfContentH - overscrollY,
                (viewTop - viewCentreY) - contentShiftY + halfContentH + overscrollY
            )
        }
    }
}
