package com.exciteddev.nosleepcam.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.LinearLayout

/** LinearLayout that is no wider than [maxWidthPx] (the equivalent of MaximumWidthRequest). */
internal class MaxWidthLinearLayout
    @JvmOverloads
    constructor(
        context: Context,
        attrs: AttributeSet? = null,
    ) : LinearLayout(context, attrs) {
        var maxWidthPx: Int = Int.MAX_VALUE

        override fun onMeasure(
            widthMeasureSpec: Int,
            heightMeasureSpec: Int,
        ) {
            val mode = MeasureSpec.getMode(widthMeasureSpec)
            val size = MeasureSpec.getSize(widthMeasureSpec)
            if (mode == MeasureSpec.UNSPECIFIED || size <= maxWidthPx) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }

            super.onMeasure(MeasureSpec.makeMeasureSpec(maxWidthPx, mode), heightMeasureSpec)
        }
    }
