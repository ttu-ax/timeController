package com.example.procrastinationgate

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 摇晃计数:先用震动报出次数(每轮随机 2..上限),再把手机摇对应次数。
 *
 * 全程可以闭眼完成:报数是一串短震,每识别到一次有效摇晃回一个短震,
 * 数满后用双短震收尾。用户不需要看屏幕,也不会怀疑自己是不是没摇到位。
 *
 * 次数必须每轮随机,固定次数会很快变成肌肉记忆。
 */
class ShakeCountView(context: Context, maxShakes: Int) : SensorFrictionView(context) {
    var onCompleted: (() -> Unit)? = null

    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    override val sensors: List<Sensor> = listOfNotNull(accelerometer)

    private val cap = maxShakes.coerceIn(2, 8)
    private val target = Random.nextInt(2, cap + 1)

    private val handler = Handler(Looper.getMainLooper())
    private var shaking = false
    private var counted = 0
    private var completed = false
    private var lastShakeAt = 0L
    private var lastVibrateAt = 0L

    private val markedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.accent
        style = Paint.Style.FILL
    }
    private val restPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.line
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        textSize = dp(26).toFloat()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(14).toFloat()
        textAlign = Paint.Align.CENTER
    }

    init {
        contentDescription = "先听 $target 下震动，再把手机摇 $target 下"
        // 挂上窗口后再开始报数,避免还没显示就已经放过一遍
        handler.postDelayed({ playPrompt() }, 300L)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private fun playPrompt() {
        for (i in 0 until target) {
            handler.postDelayed({
                Haptics.tick(context)
                lastVibrateAt = SystemClock.uptimeMillis()
            }, i * 320L)
        }
        handler.postDelayed({
            // 长震 = 该你了
            Haptics.start(context)
            lastVibrateAt = SystemClock.uptimeMillis()
            shaking = true
            lastShakeAt = SystemClock.uptimeMillis()
            invalidate()
        }, target * 320L + 260L)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (completed || event.values.size < 3) return
        val magnitude = sqrt(
            event.values[0] * event.values[0] +
                event.values[1] * event.values[1] +
                event.values[2] * event.values[2]
        )
        val now = SystemClock.uptimeMillis()

        // 停下来太久就清零,别让零散晃动慢慢凑满次数
        if (counted > 0 && now - lastShakeAt > IDLE_RESET_MS) {
            counted = 0
            invalidate()
        }
        if (!shaking) {
            return
        }
        if (magnitude < SHAKE_THRESHOLD) return
        if (now - lastShakeAt < SHAKE_REFRACTORY_MS) return
        // 马达自己也会让加速度计抖一下,刚震过就别急着数
        if (now - lastVibrateAt < VIBRATION_GUARD_MS) return

        lastShakeAt = now
        counted++
        if (counted >= target) {
            completed = true
            Haptics.ready(context)
            announceForAccessibility("摇了 $counted 下，够了")
            onCompleted?.invoke()
        } else {
            Haptics.tick(context)
            invalidate()
        }
        lastVibrateAt = SystemClock.uptimeMillis()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 没有加速度计的机型:点一下兜底,不让用户卡在这扇门上
        if (accelerometer == null && !completed && event.actionMasked == MotionEvent.ACTION_UP) {
            completed = true
            onCompleted?.invoke()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val shown = if (accelerometer == null) 0 else target
        val label = if (accelerometer == null) "点一下继续" else "摇 $shown 下"
        canvas.drawText(label, cx, cy - dp(4), textPaint)

        if (accelerometer != null) {
            val radius = dp(7).toFloat()
            val gap = dp(22).toFloat()
            val totalWidth = (target - 1) * gap
            val startX = cx - totalWidth / 2f
            val dotY = cy + dp(38)
            repeat(target) { index ->
                val paint = if (index < counted) markedPaint else restPaint
                canvas.drawCircle(startX + index * gap, dotY, radius, paint)
            }
        }

        val hint = when {
            accelerometer == null -> "此设备没有加速度传感器"
            completed -> "够了"
            !shaking -> "先听震动，数清几下"
            else -> "已摇 $counted/$shown"
        }
        canvas.drawText(hint, cx, height - dp(16).toFloat(), hintPaint)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /**
         * 静止时约 9.8,轻晃一下就能到 15 以上。
         * 宁可定低一点(11 ≈ 1.12g):定高了会让手劲小的人推不开这扇门。
         */
        const val SHAKE_THRESHOLD = 11f
        /** 一次摇晃会有多次过冲,靠这个去重。 */
        const val SHAKE_REFRACTORY_MS = 320L
        /** 震动马达的余震窗口。 */
        const val VIBRATION_GUARD_MS = 150L
        const val IDLE_RESET_MS = 4000L
    }
}

/**
 * 转身一圈:把手机绕竖直轴转满一圈。
 *
 * 判定用陀螺仪:取重力向量的方向作为"世界的竖直轴",算角速度在该轴上的分量并积分。
 * 这样手机竖着拿、平放、斜着握都成立——写死成某个手机轴就会只在一种握法下有效。
 *
 * 积分带符号,正转反转互相抵消,所以来回晃手腕凑不满一圈。
 * 传感器分不清"人转身"还是"手腕把手机转一圈",两者在测量上是同一个信号,
 * 所以文案只说"把手机绕竖直方向转满一圈",两种做法都算通过。
 */
class TurnAroundView(context: Context) : SensorFrictionView(context) {
    var onCompleted: (() -> Unit)? = null

    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    /** 有重力传感器就用它定竖直轴,没有就用加速度计低通平滑出来。 */
    private val verticalSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    override val sensors: List<Sensor> = listOfNotNull(gyroscope, verticalSensor)

    private val supported = gyroscope != null && verticalSensor != null

    private val rawVertical = FloatArray(3)
    private val unitVertical = FloatArray(3)
    private var verticalReady = false
    private var lastGyroNs = 0L
    private var lastMoveAt = 0L
    private var turnedRadians = 0f
    private var ticksDone = 0
    private var completed = false

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.line
        style = Paint.Style.STROKE
        strokeWidth = dp(10).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.accent
        style = Paint.Style.STROKE
        strokeWidth = dp(10).toFloat()
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.ink
        textSize = dp(24).toFloat()
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = UiTheme.muted
        textSize = dp(13).toFloat()
        textAlign = Paint.Align.CENTER
    }

    init {
        contentDescription = if (supported) {
            "把手机绕竖直方向转满一圈，转满后会震动提醒"
        } else {
            "此设备没有陀螺仪，点一下即可继续"
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (completed) return
        when (event.sensor.type) {
            Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> feedVertical(event)
            Sensor.TYPE_GYROSCOPE -> integrate(event)
        }
        invalidate()
    }

    private fun feedVertical(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_GRAVITY) {
            rawVertical[0] = event.values[0]
            rawVertical[1] = event.values[1]
            rawVertical[2] = event.values[2]
        } else {
            // 加速度计带着运动分量,低通出重力部分
            val alpha = 0.2f
            for (i in 0..2) {
                rawVertical[i] = rawVertical[i] * (1f - alpha) + event.values[i] * alpha
            }
        }
        val length = sqrt(
            rawVertical[0] * rawVertical[0] +
                rawVertical[1] * rawVertical[1] +
                rawVertical[2] * rawVertical[2]
        )
        if (length < 1f) return
        unitVertical[0] = rawVertical[0] / length
        unitVertical[1] = rawVertical[1] / length
        unitVertical[2] = rawVertical[2] / length
        verticalReady = true
    }

    private fun integrate(event: SensorEvent) {
        val nowNs = event.timestamp
        val previousNs = lastGyroNs
        lastGyroNs = nowNs
        if (previousNs == 0L || !verticalReady) return
        val dt = (nowNs - previousNs) / 1_000_000_000f
        if (dt <= 0f || dt > 0.1f) return

        // 绕世界竖直轴的角速度:任意握持姿势都成立
        val yawRate = event.values[0] * unitVertical[0] +
            event.values[1] * unitVertical[1] +
            event.values[2] * unitVertical[2]

        if (abs(yawRate) < DEAD_BAND) {
            // 陀螺仪静止有零漂,死区挡住它;顺手处理"转了一半停手"
            if ((turnedRadians != 0f || ticksDone != 0) &&
                SystemClock.uptimeMillis() - lastMoveAt > IDLE_RESET_MS
            ) {
                turnedRadians = 0f
                ticksDone = 0
            }
            return
        }

        lastMoveAt = SystemClock.uptimeMillis()
        // 带符号累加:正转反转互相抵消,来回晃骗不过去
        turnedRadians += yawRate * dt

        val reached = (abs(turnedRadians) / QUARTER_TURN_RAD).toInt()
        if (reached > ticksDone) {
            ticksDone = min(reached, 3)
            Haptics.tick(context)
        }
        if (abs(turnedRadians) >= TARGET_RAD) {
            completed = true
            Haptics.ready(context)
            announceForAccessibility("转满一圈，可以推开")
            onCompleted?.invoke()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!supported && !completed && event.actionMasked == MotionEvent.ACTION_UP) {
            completed = true
            onCompleted?.invoke()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f - dp(10)
        val radius = min(width, height) * 0.26f
        val box = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        canvas.drawArc(box, 0f, 360f, false, trackPaint)
        if (supported) {
            // 起始角固定成 12 点方向,进度按实际累计角度画,转反了往回退
            val sweep = (turnedRadians * 180f / Math.PI.toFloat()).coerceIn(-359f, 359f)
            if (abs(sweep) > 1f) {
                canvas.drawArc(box, -90f, sweep, false, progressPaint)
            }
        }

        val degrees = (abs(turnedRadians) * 180f / Math.PI.toFloat()).toInt()
        val label = when {
            !supported -> "点一下继续"
            completed -> "门开了"
            else -> "$degrees°"
        }
        canvas.drawText(label, cx, cy + dp(8), textPaint)

        val hint = when {
            !supported -> "此设备没有陀螺仪"
            completed -> "转满了"
            else -> "绕竖直方向转满一圈"
        }
        canvas.drawText(hint, cx, height - dp(30).toFloat(), hintPaint)
        if (supported && !completed) {
            canvas.drawText("站着转身，或者手腕转一圈都行", cx, height - dp(12).toFloat(), hintPaint)
        }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        /** 零漂死区(约 8.6°/s);真人转身通常 90–170°/s,不会误伤。 */
        const val DEAD_BAND = 0.15f
        /** 标称一圈按 360°,判定留一点余量补偿死区吃掉的起止慢速段。 */
        const val TARGET_RAD = 340f * Math.PI.toFloat() / 180f
        const val QUARTER_TURN_RAD = 90f * Math.PI.toFloat() / 180f
        const val IDLE_RESET_MS = 4000L
    }
}
