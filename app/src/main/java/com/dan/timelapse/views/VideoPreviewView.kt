package com.dan.timelapse.views

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

class VideoPreviewView(context: Context, attrs: AttributeSet?) : VideoView(context, attrs) {
    var onSizeChanged: ((Int, Int) -> Unit)? = null

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        onSizeChanged?.invoke(measuredWidth, measuredHeight)
    }
}
