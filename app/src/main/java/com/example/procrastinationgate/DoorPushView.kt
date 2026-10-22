package com.example.procrastinationgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.animation.ValueAnimator
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 推门:摩擦完成后不再是一个"点一下"的按钮,而是把这扇门交到用户手里。
 *
 * 门以上边缘为合页,按住往上推,门板绕上沿向远端转开——投影高度按 cos 缩短、
 * 底边略微收窄,所以看起来像真的往前推开,而不是一张卡片在缩放。
 * 底下露出的是门后的光(与出口 App 的名字),光随着角度越来越亮。
 *
 * 0–60% 线性跟手,60% 之后变"沉"(同样角度要更长的行程),模拟门锁脱扣前的阻力;
 * 越过 85% 门自行转完并带一点过冲。松手不到 85% 就弹簧关回去,不震、不提示、不记失败。
 *
 * 推门是仪式,不属于难度体系:没有计时,也没有失败。
 */
class DoorPushView(
    context: Context,
    private val label: String,
    private val targetLabel: String
) : View(context) {

    var onOpened: (() -> Unit)? = null

    private var progress = 0f
    private var flood = 0f
    private var dragging = false
    private var opened = false
    private var startProgress = 0f
    private var downY = 0f
    private var resistanceTicked = false
    private var hintAlpha = 0f
    private var hintCycle = 0
    private var running: ValueAnimator? = null

    private val handler = Handler(Looper.getMainLooper())

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.surface }
    private val panelEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.line
        style = Paint.Style.STROKE
        strokeWidth = dp(1).toFloat()
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textAlign = Paint.Align.CENTER
    }
    private val roomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.ink }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = UiTheme.accent }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(46, 17, 26, 20) }

    private var lightShader: RadialGradient? = null
    private var shaderW = 0
    private var shaderH = 0

    init {
        isClickable = true
        contentDescription = "门已经开了，按住往上推，推到底就会继续"
    }

    /** 摩擦完成时调用:门进入"在你手里"的状态。 */
    fun unlock() {
        visible()
        // 和摩擦控件的消失错开一点,像门被递到手里
        alpha = 0f
        animate().alpha(1f).setDuration(160L).start()
        progress = 0f
        flood = 0f
        opened = false
        dragging = false
        hintAlpha = 0f
        hintCycle = 0
        // 门到手了:一记极轻的震动,不是"完成"
        Haptics.tick(context)
        startHint()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        running?.cancel()
        super.onDetachedFromWindow()
    }

    // ------------------------------------------------------------------
    // 触摸:往上推
    // ------------------------------------------------------------------
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (opened) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = true
                downY = event.y
                startProgress = progress
                resistanceTicked = progress >= SOFT_ZONE
                claimGesture(true)
                stopHint()
                Haptics.tick(context)
            }

            MotionEvent.ACTION_MOVE -> {
                if (!dragging) return true
                val pushed = (downY - event.y) / travel()
                var next = startProgress + resistance(pushed)
                // 来回推要先抵消掉已经推开的部分
                if (pushed < 0f) next = startProgress
                progress = next.coerceIn(0f, 1f)
                if (!resistanceTicked && progress >= SOFT_ZONE) {
                    resistanceTicked = true
                    // 门锁脱扣那一下
                    Haptics.tick(context)
                }
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                claimGesture(false)
                if (progress >= RELEASE_POINT) openThrough() else springBack()
            }
        }
        return true
    }

    /** 前 60% 线性跟手,之后变沉:同样的角度需要更大的行程。 */
    private fun resistance(raw: Float): Float =
        if (raw <= SOFT_ZONE) raw else SOFT_ZONE + (raw - SOFT_ZONE) * STIFFNESS

    /** 推开到底需要的手指行程:门越高越远,但有个下限保证单手够用。 */
    private fun travel(): Float = max(height * 0.55f, dp(140).toFloat())

    private fun openThrough() {
        opened = true
        Haptics.ready(context)
        animateTo(progress, 1f, 180L, OvershootInterpolator(1.1f)) {
            // 门后留白:光铺满,门板退到近乎侧对
            animateValue(flood, 1f, HOLD_MS) { value ->
                flood = value
                invalidate()
            }
            handler.postDelayed({
                onOpened?.invoke()
            }, HOLD_MS)
        }
    }

    private fun springBack() {
        animateTo(progress, 0f, 220L, DecelerateInterpolator()) { startHint() }
    }

    private fun animateTo(from: Float, to: Float, duration: Long, interpolator: android.animation.TimeInterpolator, end: () -> Unit) {
        running?.cancel()
        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = duration
        animator.interpolator = interpolator
        animator.addUpdateListener {
            progress = it.animatedValue as Float
            invalidate()
        }
        animator.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) = end()
        })
        running = animator
        animator.start()
    }

    private fun animateValue(from: Float, to: Float, duration: Long, update: (Float) -> Unit) {
        val animator = ValueAnimator.ofFloat(from, to)
        animator.duration = duration
        animator.addUpdateListener { update(it.animatedValue as Float) }
        animator.start()
    }

    // ------------------------------------------------------------------
    // 「往上推」的示意:首次出现时放三次,用户一碰就停
    // ------------------------------------------------------------------
    private fun startHint() {
        hintAlpha = 1f
        hintCycle = 0
        stepHint()
    }

    private fun stepHint() {
        if (hintCycle >= 3) {
            hintAlpha = 0f
            invalidate()
            return
        }
        hintCycle++
        animateValue(0f, 1f, 700L) {
            hintAlpha = if (hintCycle >= 3) 1f - it * 0.9f else 1f - it * 0.6f
            hintProgress = it
            invalidate()
        }
        handler.postDelayed({ if (!dragging && !opened) stepHint() }, 760L)
    }

    private fun stopHint() {
        handler.removeCallbacksAndMessages(null)
        if (!opened) {
            animateValue(hintAlpha, 0f, 120L) { hintAlpha = it; invalidate() }
        }
    }

    private var hintProgress = 0f

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val w = width.toFloat()
        val h = height.toFloat()
        val p = progress.coerceIn(0f, 1f)

        ensureShaders()

        // 门后:光从门缝里漫出来,推得越多越亮
        drawBehind(canvas, w, h, p)

        // 门板:合页在上沿,向远端转开
        val angle = p * MAX_OPEN_ANGLE
        val bottom = h * cos(angle)
        val inset = w * 0.09f * p
        val src = floatArrayOf(0f, 0f, w, 0f, w, h, 0f, h)
        val dst = floatArrayOf(0f, 0f, w, 0f, w - inset, bottom, inset, bottom)
        val matrix = Matrix()
        if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            canvas.save()
            canvas.concat(matrix)
            val radius = dp(18).toFloat()
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, panelPaint)
            canvas.drawRoundRect(0f, 0f, w, h, radius, radius, panelEdgePaint)
            canvas.restore()
        }

        // 门板底边的落影,压在门后的光上
        if (p > 0.02f) {
            val shadowTop = bottom
            canvas.drawRect(
                0f, shadowTop, w, min(h, shadowTop + dp(18)),
                shadowPaint
            )
        }
        // 从缝隙里漏出来的那道亮线
        if (p > 0.02f) {
            lightPaint.alpha = (220 * p).toInt().coerceIn(0, 255)
            canvas.drawRect(inset, bottom - dp(2), w - inset, bottom, lightPaint)
        }

        // 门板上的字和把手,门转过去就淡出
        val contentAlpha = (1f - p / 0.45f).coerceIn(0f, 1f)
        if (contentAlpha > 0.01f) {
            labelPaint.alpha = (255 * contentAlpha).toInt()
            labelPaint.textSize = min(dp(19).toFloat(), bottom * 0.075f).coerceAtLeast(dp(13).toFloat())
            canvas.drawText(label, w / 2f, bottom * 0.62f, labelPaint)

            handlePaint.alpha = (255 * contentAlpha * 0.55f).toInt()
            val handleW = w * 0.16f
            val handleY = bottom * 0.86f
            val handleRect = RectF(w / 2f - handleW / 2f, handleY, w / 2f + handleW / 2f, handleY + dp(4))
            canvas.drawRoundRect(handleRect, dp(2).toFloat(), dp(2).toFloat(), handlePaint)

            hintPaint.alpha = (255 * contentAlpha * hintAlpha).toInt()
            hintPaint.textSize = dp(13).toFloat()
            val hintY = bottom * 0.86f + dp(22) - hintProgress * dp(18)
            canvas.drawText("往上推", w / 2f, hintY, hintPaint)
        }
    }

    private fun drawBehind(canvas: Canvas, w: Float, h: Float, p: Float) {
        // 门后是一间被光照亮的房间:越推越亮,门全开时铺满
        lightShader?.let { lightPaint.shader = it }
        lightPaint.alpha = (255 * (0.10f + 0.90f * p) * (0.75f + 0.25f * flood)).toInt().coerceIn(0, 255)
        canvas.drawRect(0f, 0f, w, h, lightPaint)
        lightPaint.shader = null

        // 门后的空间里写着要去哪,提示这是"推开之后"
        roomPaint.color = UiTheme.surface
        roomPaint.alpha = (90 * p).toInt().coerceIn(0, 255)
        roomPaint.textAlign = Paint.Align.CENTER
        roomPaint.isFakeBoldText = true
        roomPaint.textSize = dp(16).toFloat()
        canvas.drawText("进入 $targetLabel", w / 2f, h * 0.82f, roomPaint)
        roomPaint.alpha = 255
    }

    private fun ensureShaders() {
        if (shaderW == width && shaderH == height) return
        shaderW = width
        shaderH = height
        val w = width.toFloat()
        val h = height.toFloat()
        val accent = UiTheme.accent
        val r = Color.red(accent)
        val g = Color.green(accent)
        val b = Color.blue(accent)
        lightShader = RadialGradient(
            w / 2f, h * 0.98f, max(w, h) * 0.95f,
            intArrayOf(
                Color.argb(210, r, g, b),
                Color.argb(120, r, g, b),
                Color.argb(0, r, g, b)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    private fun visible() {
        visibility = View.VISIBLE
        alpha = 1f
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 门转到这个角度时基本侧对用户。 */
        val MAX_OPEN_ANGLE = 78f * Math.PI.toFloat() / 180f
        /** 前 60% 线性,之后变沉。 */
        const val SOFT_ZONE = 0.6f
        const val STIFFNESS = 0.45f
        /** 越过这个进度松手,门自行转完。 */
        const val RELEASE_POINT = 0.85f
        const val HOLD_MS = 260L
    }
}
