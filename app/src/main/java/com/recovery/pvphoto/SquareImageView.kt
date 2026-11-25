package com.recovery.pvphoto

import android.content.Context
import android.widget.ImageView

/** ImageView that always stays square (width == height) for the 3-column grids. */
class SquareImageView @JvmOverloads constructor(
    context: Context,
    attrs: android.util.AttributeSet? = null
) : androidx.appcompat.widget.AppCompatImageView(context, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
