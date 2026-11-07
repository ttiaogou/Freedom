package com.freedom.ui.calibration

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.freedom.accessibility.gesture.GestureTuning
import com.freedom.data.MonitorStore

/** Owns the gesture calibration dialog, its local receiver, timeout and MotionEvent samples. */
internal class GestureCalibrationUiController(
    private val activity: Activity,
    private val isAccessibilityEnabled: () -> Boolean,
    private val dp: (Int) -> Int,
    private val roundedBackground: (Int, Float) -> GradientDrawable,
    private val timestamp: () -> String
) {
    private val gestureCalibrationHandler = Handler(Looper.getMainLooper())

    private var gestureCalibrationReceiverRegistered = false
    private var gestureCalibrationRunning = false
    private val gestureCalibrationTrials = mutableListOf<List<Long>>()
    private val gestureCalibrationCurrentTimes = ArrayList<Long>()
    private var gestureCalibrationDialog: AlertDialog? = null
    private var gestureCalibrationStatusView: TextView? = null
    private var gestureCalibrationPad: View? = null
    private var gestureCalibrationStartButton: Button? = null

    private val gestureCalibrationTimeoutRunnable = Runnable {
        if (gestureCalibrationRunning) {
            gestureCalibrationRunning = false
            sendGestureCalibrationCancel()
            gestureCalibrationCurrentTimes.clear()
            gestureCalibrationStatusView?.text =
                "采样超时：没有在规定时间内收到完整测试轨迹。请确认无障碍服务已开启，然后重新采样。"
            gestureCalibrationStartButton?.isEnabled = true
            gestureCalibrationStartButton?.text = "重新采样"
            gestureCalibrationDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
        }
    }

    private val gestureCalibrationReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GestureTuning.ACTION_CALIBRATION_TRIAL_COMPLETED -> {
                    val completed = intent.getIntExtra(GestureTuning.EXTRA_TRIAL_INDEX, 0)
                    gestureCalibrationStatusView?.text =
                        "正在采样：已完成 ${completed}/${GestureTuning.CALIBRATION_TRIAL_COUNT} 组。请保持页面前台，不要触摸校准区域。"
                }

                GestureTuning.ACTION_CALIBRATION_FINISHED -> {
                    gestureCalibrationHandler.removeCallbacks(gestureCalibrationTimeoutRunnable)
                    gestureCalibrationRunning = false
                    val serviceSuccess = intent.getBooleanExtra(GestureTuning.EXTRA_SUCCESS, false)
                    if (!serviceSuccess) {
                        val reason = intent.getStringExtra(GestureTuning.EXTRA_MESSAGE)
                            ?: "系统未能完成测试手势。"
                        showGestureCalibrationFailure(reason)
                        return
                    }

                    val result = GestureTuning.evaluateSamples(gestureCalibrationTrials)
                    if (!result.valid) {
                        showGestureCalibrationFailure(result.message)
                        return
                    }

                    GestureTuning.saveCalibration(activity, result)
                    MonitorStore.addLog(
                        activity,
                        timestamp() + "  手势路径自动采样完成：推荐频率=" +
                            GestureTuning.formatFrequency(result.recommendedFrequencyHz) +
                            "Hz，P85采样间隔=${result.p85IntervalMs}ms，" +
                            "有效时间间隔=${result.intervalCount}"
                    )
                    gestureCalibrationStatusView?.text = buildString {
                        append("采样成功\n")
                        append("自动推荐频率：")
                        append(GestureTuning.formatFrequency(result.recommendedFrequencyHz))
                        append("Hz\n")
                        append("有效轨迹间隔：").append(result.intervalCount).append(" 个\n")
                        append("中位采样间隔：").append(result.medianIntervalMs).append("ms\n")
                        append("P85采样间隔：").append(result.p85IntervalMs).append("ms\n")
                        append("采样波动参考：").append(result.jitterPercent).append("%\n\n")
                        append("该频率已自动保存并应用。频率不可手动编辑；需要重新评估时点击下方按钮。")
                    }
                    gestureCalibrationStartButton?.isEnabled = true
                    gestureCalibrationStartButton?.text = "重新采样"
                    gestureCalibrationDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
                }

                GestureTuning.ACTION_CALIBRATION_FAILED -> {
                    gestureCalibrationHandler.removeCallbacks(gestureCalibrationTimeoutRunnable)
                    gestureCalibrationRunning = false
                    val reason = intent.getStringExtra(GestureTuning.EXTRA_MESSAGE)
                        ?: "采样过程被中断。"
                    showGestureCalibrationFailure(reason)
                }
            }
        }
    }

    fun showDialog() {
        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(activity)
                .setTitle("需要开启无障碍服务")
                .setMessage("自动采样需要通过 Freedom 无障碍服务发送内部测试手势。请先开启 Freedom 无障碍服务，再回来开始采样。")
                .setPositiveButton("前往无障碍设置") { _, _ ->
                    activity.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("关闭", null)
                .show()
            return
        }

        registerGestureCalibrationReceiver()

        val details = TextView(activity).apply {
            text = buildString {
                append("采样目的\n")
                append("测量当前设备通过 Android 无障碍手势接口实际送达应用界面的轨迹时间间隔，")
                append("据此计算适合本机的路径偏移频率。\n\n")
                append("操作方法\n")
                append("1. 先停止 Freedom 监听，再点击下方“开始自动采样”。\n")
                append("2. Freedom 会在本页面的校准区域内自动执行 ")
                append(GestureTuning.CALIBRATION_TRIAL_COUNT)
                append(" 组测试手势。\n")
                append("3. 采样期间保持本页面在前台，不要触摸校准区域，也不要切换应用。\n")
                append("4. 完成后自动计算、保存并应用推荐频率。\n\n")
                append("安全说明\n")
                append("测试手势只发送到 Freedom 自己的校准区域，不会在陌陌页面滑动、点击或打开内容。")
                append("不会修改陌陌数据。采样失败时不会覆盖之前成功保存的结果。\n\n")
                append("频率由系统采样时间间隔自动计算，不提供手动输入或修改入口。")
            }
            textSize = 12f
            setTextColor(0xFF455064.toInt())
            setPadding(0, 0, 0, dp(12))
        }

        val padLabel = TextView(activity).apply {
            text = "自动校准区域\n采样时请勿触摸"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(0xFF536174.toInt())
        }
        val pad = android.widget.FrameLayout(activity).apply {
            isClickable = true
            background = roundedBackground(0xFFF0F3F8.toInt(), dp(12).toFloat())
            addView(
                padLabel,
                android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }

        val status = TextView(activity).apply {
            text = if (GestureTuning.isCalibrated(activity)) {
                GestureTuning.calibrationSummary(activity)
            } else {
                "当前状态：尚未完成本机自动采样。\n未采样前将使用保守的系统估算频率。"
            }
            textSize = 12f
            setTextColor(0xFF596579.toInt())
            setPadding(0, dp(12), 0, dp(8))
        }

        val startButton = Button(activity).apply {
            text = if (GestureTuning.isCalibrated(activity)) "重新采样" else "开始自动采样"
            isAllCaps = false
            setOnClickListener {
                startGestureCalibration(pad)
            }
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(4), dp(20), dp(8))
            addView(details)
            addView(
                pad,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(210)
                )
            )
            addView(status)
            addView(startButton, LinearLayout.LayoutParams(-1, -2))
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle("自动采样路径偏移频率")
            .setView(container)
            .setNegativeButton("关闭", null)
            .create()

        gestureCalibrationDialog = dialog
        gestureCalibrationStatusView = status
        gestureCalibrationPad = pad
        gestureCalibrationStartButton = startButton

        pad.setOnTouchListener { _, event ->
            collectGestureCalibrationTouch(event)
        }

        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            if (gestureCalibrationRunning) {
                gestureCalibrationRunning = false
                gestureCalibrationHandler.removeCallbacks(gestureCalibrationTimeoutRunnable)
                sendGestureCalibrationCancel()
            }
            unregisterGestureCalibrationReceiver()
            gestureCalibrationDialog = null
            gestureCalibrationStatusView = null
            gestureCalibrationPad = null
            gestureCalibrationStartButton = null
        }

        dialog.show()
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.90f).toInt(),
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun registerGestureCalibrationReceiver() {
        if (gestureCalibrationReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(GestureTuning.ACTION_CALIBRATION_TRIAL_COMPLETED)
            addAction(GestureTuning.ACTION_CALIBRATION_FINISHED)
            addAction(GestureTuning.ACTION_CALIBRATION_FAILED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            activity.registerReceiver(
                gestureCalibrationReceiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            activity.registerReceiver(gestureCalibrationReceiver, filter)
        }
        gestureCalibrationReceiverRegistered = true
    }

    private fun unregisterGestureCalibrationReceiver() {
        if (!gestureCalibrationReceiverRegistered) return
        runCatching { activity.unregisterReceiver(gestureCalibrationReceiver) }
        gestureCalibrationReceiverRegistered = false
    }

    private fun startGestureCalibration(pad: View) {
        if (MonitorStore.isRunning(activity)) {
            Toast.makeText(
                activity,
                "请先停止 Freedom 监听，再进行设备手势采样。",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(activity, "请先开启 Freedom 无障碍服务", Toast.LENGTH_LONG).show()
            return
        }
        if (gestureCalibrationRunning) return

        gestureCalibrationTrials.clear()
        gestureCalibrationCurrentTimes.clear()
        gestureCalibrationRunning = true
        gestureCalibrationStartButton?.isEnabled = false
        gestureCalibrationDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = false
        gestureCalibrationStatusView?.text =
            "正在准备校准区域……\n即将执行 ${GestureTuning.CALIBRATION_TRIAL_COUNT} 组内部测试手势，请勿触摸屏幕。"

        gestureCalibrationHandler.removeCallbacks(gestureCalibrationTimeoutRunnable)
        gestureCalibrationHandler.postDelayed(
            gestureCalibrationTimeoutRunnable,
            GestureTuning.CALIBRATION_TIMEOUT_MS
        )

        pad.post {
            if (!gestureCalibrationRunning) return@post
            val location = IntArray(2)
            pad.getLocationOnScreen(location)
            val width = pad.width
            val height = pad.height
            if (width < dp(100) || height < dp(100)) {
                showGestureCalibrationFailure("校准区域尺寸不足，无法安全执行内部测试手势。")
                return@post
            }

            val request = Intent(GestureTuning.ACTION_START_CALIBRATION)
                .setPackage(activity.packageName)
                .putExtra(GestureTuning.EXTRA_LEFT, location[0])
                .putExtra(GestureTuning.EXTRA_TOP, location[1])
                .putExtra(GestureTuning.EXTRA_WIDTH, width)
                .putExtra(GestureTuning.EXTRA_HEIGHT, height)
            activity.sendBroadcast(request)
        }
    }

    private fun collectGestureCalibrationTouch(event: android.view.MotionEvent): Boolean {
        if (!gestureCalibrationRunning) return false

        fun addTimestamp(time: Long) {
            if (gestureCalibrationCurrentTimes.isEmpty() ||
                time > gestureCalibrationCurrentTimes.last()
            ) {
                gestureCalibrationCurrentTimes.add(time)
            }
        }

        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                gestureCalibrationCurrentTimes.clear()
                addTimestamp(event.eventTime)
                return true
            }

            android.view.MotionEvent.ACTION_MOVE,
            android.view.MotionEvent.ACTION_UP -> {
                for (historyIndex in 0 until event.historySize) {
                    addTimestamp(event.getHistoricalEventTime(historyIndex))
                }
                addTimestamp(event.eventTime)

                if (event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                    if (gestureCalibrationCurrentTimes.size >= GestureTuning.MIN_POINTS_PER_TRIAL) {
                        gestureCalibrationTrials.add(gestureCalibrationCurrentTimes.toList())
                    }
                    gestureCalibrationCurrentTimes.clear()
                }
                return true
            }

            android.view.MotionEvent.ACTION_CANCEL -> {
                gestureCalibrationCurrentTimes.clear()
                return true
            }

            else -> return true
        }
    }

    private fun showGestureCalibrationFailure(reason: String) {
        gestureCalibrationHandler.removeCallbacks(gestureCalibrationTimeoutRunnable)
        gestureCalibrationRunning = false
        gestureCalibrationCurrentTimes.clear()
        gestureCalibrationStartButton?.isEnabled = true
        gestureCalibrationStartButton?.text = "重新采样"
        gestureCalibrationDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
        gestureCalibrationStatusView?.text =
            "本次采样未成功\n$reason\n\n之前已保存的有效校准结果不会被覆盖。请检查无障碍服务和页面状态后重新采样。"
        MonitorStore.addLog(activity, timestamp() + "  手势路径自动采样失败：$reason")
    }

    fun onPause() {
        if (gestureCalibrationRunning) {
            gestureCalibrationRunning = false
            gestureCalibrationHandler.removeCallbacks(gestureCalibrationTimeoutRunnable)
            sendGestureCalibrationCancel()
            gestureCalibrationCurrentTimes.clear()
            gestureCalibrationStatusView?.text =
                "采样已中止：Freedom 离开前台。请回到本页面后重新采样。"
            gestureCalibrationStartButton?.isEnabled = true
            gestureCalibrationStartButton?.text = "重新采样"
            gestureCalibrationDialog?.getButton(AlertDialog.BUTTON_NEGATIVE)?.isEnabled = true
        }
    }

    private fun sendGestureCalibrationCancel() {
        activity.sendBroadcast(
            Intent(GestureTuning.ACTION_CANCEL_CALIBRATION).setPackage(activity.packageName)
        )
    }
}
