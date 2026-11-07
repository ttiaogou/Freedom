package com.freedom.accessibility.gesture

import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import com.freedom.accessibility.FreedomAccessibilityService

/**
 * Owns the device-only gesture calibration session.
 *
 * The controller dispatches test gestures only after verifying that Freedom's own
 * calibration screen is the active window. The AccessibilityService remains the
 * Android lifecycle and gesture-dispatch host; account-browse gestures are not
 * routed through this controller.
 */
internal class GestureCalibrationController(
    private val service: FreedomAccessibilityService,
    private val handler: Handler,
    private val isBrowseGestureBusy: () -> Boolean,
    private val dp: (Int) -> Int,
    private val onLog: (String) -> Unit
) {
    private var inProgress = false
    private var trialIndex = 0
    private var bounds: Rect? = null

    private val calibrationRunnable = Runnable {
        dispatchNextTrial()
    }

    /**
     * The service registers this receiver when it connects and unregisters it
     * during destruction, preserving the original receiver lifecycle.
     */
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                GestureTuning.ACTION_START_CALIBRATION -> start(intent)
                GestureTuning.ACTION_CANCEL_CALIBRATION -> {
                    cancel("用户关闭了校准页面")
                }
            }
        }
    }

    fun onServiceDestroyed() {
        inProgress = false
        bounds = null
        handler.removeCallbacks(calibrationRunnable)
    }

    private fun start(intent: Intent) {
        if (inProgress) {
            sendFailure("已有一组设备采样正在进行。")
            return
        }
        if (isBrowseGestureBusy()) {
            sendFailure("当前自动浏览手势尚未结束，请停止当前操作后再采样。")
            return
        }

        val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
        if (activeRoot == null) {
            sendFailure("无法读取 Freedom 校准页面；请确认校准窗口仍在前台。")
            return
        }
        val activePackage = try {
            activeRoot.packageName?.toString().orEmpty()
        } finally {
            runCatching { activeRoot.recycle() }
        }
        if (activePackage != service.packageName) {
            sendFailure(
                "当前前台不是 Freedom 校准页面。采样不会在陌陌或其他应用页面执行。"
            )
            return
        }

        val left = intent.getIntExtra(GestureTuning.EXTRA_LEFT, Int.MIN_VALUE)
        val top = intent.getIntExtra(GestureTuning.EXTRA_TOP, Int.MIN_VALUE)
        val width = intent.getIntExtra(GestureTuning.EXTRA_WIDTH, 0)
        val height = intent.getIntExtra(GestureTuning.EXTRA_HEIGHT, 0)
        val display = service.resources.displayMetrics
        if (left == Int.MIN_VALUE || top == Int.MIN_VALUE ||
            width < dp(100) || height < dp(100) ||
            left < 0 || top < 0 ||
            left + width > display.widthPixels ||
            top + height > display.heightPixels
        ) {
            sendFailure("校准区域坐标无效或超出屏幕范围，请重新打开采样页面。")
            return
        }

        bounds = Rect(left, top, left + width, top + height)
        trialIndex = 0
        inProgress = true
        onLog(
            "开始设备手势自动采样：仅在 Freedom 内部校准区域执行 " +
                "${GestureTuning.CALIBRATION_TRIAL_COUNT} 组测试手势"
        )
        handler.removeCallbacks(calibrationRunnable)
        handler.postDelayed(calibrationRunnable, 350L)
    }

    private fun dispatchNextTrial() {
        if (!inProgress) return
        val currentBounds = bounds
        if (currentBounds == null) {
            fail("校准区域信息已失效。")
            return
        }

        val activeRoot = runCatching { service.rootInActiveWindow }.getOrNull()
        if (activeRoot == null) {
            fail("校准期间无法读取 Freedom 页面；测试已停止。")
            return
        }
        val activePackage = try {
            activeRoot.packageName?.toString().orEmpty()
        } finally {
            runCatching { activeRoot.recycle() }
        }
        if (activePackage != service.packageName) {
            fail("校准页面已离开 Freedom；为避免触碰其他应用，测试已立即停止。")
            return
        }

        val width = currentBounds.width().toFloat()
        val height = currentBounds.height().toFloat()
        val centerX = currentBounds.exactCenterX()
        val startX = centerX - width * 0.12f
        val endX = centerX + width * 0.12f
        val startY = currentBounds.top + height * 0.76f
        val endY = currentBounds.top + height * 0.24f
        val control1X = centerX + width * 0.08f
        val control2X = centerX - width * 0.08f
        val control1Y = startY + (endY - startY) / 3f
        val control2Y = startY + (endY - startY) * 2f / 3f

        val path = Path().apply {
            moveTo(startX, startY)
            cubicTo(control1X, control1Y, control2X, control2Y, endX, endY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0L,
                    GestureTuning.CALIBRATION_GESTURE_DURATION_MS
                )
            )
            .build()

        val trialNumber = trialIndex + 1
        val accepted = runCatching {
            service.dispatchGesture(
                gesture,
                object : android.accessibilityservice.AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (!inProgress) return
                        sendProgress(trialNumber)
                        trialIndex++
                        if (trialIndex >= GestureTuning.CALIBRATION_TRIAL_COUNT) {
                            handler.postDelayed({
                                finish()
                            }, 250L)
                        } else {
                            handler.postDelayed(
                                calibrationRunnable,
                                GestureTuning.CALIBRATION_TRIAL_GAP_MS
                            )
                        }
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (inProgress) {
                            fail("Android 系统中断了第 ${trialNumber} 组内部测试手势。")
                        }
                    }
                },
                handler
            )
        }.getOrDefault(false)

        if (!accepted && inProgress) {
            fail("Android 未接受第 ${trialNumber} 组内部测试手势。")
        }
    }

    private fun sendProgress(trialNumber: Int) {
        service.sendBroadcast(
            Intent(GestureTuning.ACTION_CALIBRATION_TRIAL_COMPLETED)
                .setPackage(service.packageName)
                .putExtra(GestureTuning.EXTRA_TRIAL_INDEX, trialNumber)
        )
    }

    private fun finish() {
        if (!inProgress) return
        inProgress = false
        bounds = null
        onLog("Freedom 内部手势采样完成；正在由主界面分析收到的 MotionEvent 时间戳")
        service.sendBroadcast(
            Intent(GestureTuning.ACTION_CALIBRATION_FINISHED)
                .setPackage(service.packageName)
                .putExtra(GestureTuning.EXTRA_SUCCESS, true)
        )
    }

    private fun fail(reason: String) {
        if (!inProgress) {
            sendFailure(reason)
            return
        }
        inProgress = false
        bounds = null
        handler.removeCallbacks(calibrationRunnable)
        onLog("设备手势自动采样中止：$reason")
        sendFailure(reason)
    }

    private fun cancel(reason: String) {
        if (!inProgress) return
        inProgress = false
        bounds = null
        handler.removeCallbacks(calibrationRunnable)
        onLog("设备手势自动采样已取消：$reason")
    }

    private fun sendFailure(reason: String) {
        service.sendBroadcast(
            Intent(GestureTuning.ACTION_CALIBRATION_FAILED)
                .setPackage(service.packageName)
                .putExtra(GestureTuning.EXTRA_MESSAGE, reason)
        )
    }
}
