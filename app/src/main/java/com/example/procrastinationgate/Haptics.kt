package com.example.procrastinationgate

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View

/**
 * 全应用统一的触感语言。离屏摩擦(翻转/摇晃/转身)里用户看不见画面,
 * 状态只能靠震动传达,所以节奏必须固定、可分辨:
 *
 * - [tick]  单次 30ms —— 记一次 / 一个单位
 * - [start] 一次 500ms —— 换阶段 / 现在该你了
 * - [ready] 双短震 90+120+90 —— 到点、完成、可以了
 *
 * 这三个词够用且不会混淆,读屏用户得到的等价信息也是这三档。
 */
object Haptics {
    fun tick(context: Context) = vibrate(context, 30L)

    fun start(context: Context) = vibrate(context, 500L)

    fun ready(context: Context) = waveform(context, longArrayOf(0L, 90L, 120L, 90L))

    private fun vibrate(context: Context, millis: Long) {
        val vibrator = vibrator(context) ?: return
        runCatching { vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun waveform(context: Context, pattern: LongArray) {
        val vibrator = vibrator(context) ?: return
        runCatching { vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    private fun vibrator(context: Context): Vibrator? =
        (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.takeIf { it.hasVibrator() }
}

/** 需要传感器输入的摩擦视图:挂到窗口就注册,离开就注销,避免后台持续耗电。 */
abstract class SensorFrictionView(context: Context) : View(context), SensorEventListener {
    protected val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** 子类列出自己需要的传感器;没有的机型给空表,由子类降级。 */
    protected abstract val sensors: List<Sensor>

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sensors.forEach { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onDetachedFromWindow() {
        sensorManager.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
