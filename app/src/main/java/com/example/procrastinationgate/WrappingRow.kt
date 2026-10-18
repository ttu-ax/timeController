package com.example.procrastinationgate

import android.content.Context
import android.view.ViewGroup

/**
 * 会按可用宽度换行的行容器。
 *
 * 之前规则卡里的标签行是水平 LinearLayout:放不下时它不会换行,而是把每个标签
 * 压窄,结果文字在胶囊内部折成两行,行高忽高忽低,看起来像布局坏了。
 *
 * 换成这个容器后,标签放不下就整体换到下一行,单个标签永远保持一行。
 */
class WrappingRow(context: Context) : ViewGroup(context) {
    var horizontalGap = 0
    var verticalGap = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val available = if (widthMode == MeasureSpec.UNSPECIFIED) {
            Int.MAX_VALUE
        } else {
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        }

        var rowWidth = 0
        var rowHeight = 0
        var totalHeight = 0
        var widest = 0

        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            child.measure(
                MeasureSpec.makeMeasureSpec(available.coerceAtLeast(0), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            val needed = if (rowWidth == 0) childWidth else rowWidth + horizontalGap + childWidth
            if (rowWidth > 0 && needed > available) {
                widest = maxOf(widest, rowWidth)
                totalHeight += rowHeight + verticalGap
                rowWidth = childWidth
                rowHeight = childHeight
            } else {
                rowWidth = needed
                rowHeight = maxOf(rowHeight, childHeight)
            }
        }
        widest = maxOf(widest, rowWidth)
        totalHeight += rowHeight

        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(totalHeight + paddingTop + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val available = (r - l) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child.visibility == GONE) continue
            val childWidth = child.measuredWidth
            val childHeight = child.measuredHeight
            if (x > 0 && x + horizontalGap + childWidth > available) {
                x = 0
                y += rowHeight + verticalGap
                rowHeight = 0
            }
            if (x > 0) x += horizontalGap
            child.layout(x, y, x + childWidth, y + childHeight)
            x += childWidth
            rowHeight = maxOf(rowHeight, childHeight)
        }
    }
}
