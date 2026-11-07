package com.freedom.accessibility

import android.content.Context
import android.os.Build
import android.view.WindowManager
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Local device calibration for the scroll-path micro offset.
 *
 * It evaluates MotionEvent timestamps received by Freedom's own calibration surface.
 * It does not sample or send gestures to the configured third-party app.
 */
object GestureTuning {
    private const val PREFS_NAME = "freedom_main_ui"
    private const val PREF_KEY_PREFIX = "account_browse_gesture_calibration"

    const val ACTION_START_CALIBRATION =
        "com.freedom.action.START_GESTURE_CALIBRATION"
    const val ACTION_CANCEL_CALIBRATION =
        "com.freedom.action.CANCEL_GESTURE_CALIBRATION"
    const val ACTION_CALIBRATION_TRIAL_COMPLETED =
        "com.freedom.action.GESTURE_CALIBRATION_TRIAL_COMPLETED"
    const val ACTION_CALIBRATION_FINISHED =
        "com.freedom.action.GESTURE_CALIBRATION_FINISHED"
    const val ACTION_CALIBRATION_FAILED =
        "com.freedom.action.GESTURE_CALIBRATION_FAILED"

    const val EXTRA_LEFT = "calibration_left"
    const val EXTRA_TOP = "calibration_top"
    const val EXTRA_WIDTH = "calibration_width"
    const val EXTRA_HEIGHT = "calibration_height"
    const val EXTRA_TRIAL_INDEX = "calibration_trial_index"
    const val EXTRA_SUCCESS = "calibration_success"
    const val EXTRA_MESSAGE = "calibration_message"

    const val CALIBRATION_TRIAL_COUNT = 4
    const val CALIBRATION_TIMEOUT_MS = 20_000L
    const val MIN_POINTS_PER_TRIAL = 5
    const val MIN_VALID_TRIALS = 3
    const val MIN_INTERVAL_COUNT = 12

    const val CALIBRATION_GESTURE_DURATION_MS = 700L
    const val CALIBRATION_TRIAL_GAP_MS = 350L
    private const val MIN_RECOMMENDED_FREQUENCY_HZ = 0.5f
    private const val MAX_RECOMMENDED_FREQUENCY_HZ = 10f
    private const val SAMPLES_PER_OFFSET_CYCLE = 4f
    private const val SAFETY_FACTOR = 0.80f

    data class CalibrationResult(
        val valid: Boolean,
        val message: String,
        val recommendedFrequencyHz: Float,
        val medianIntervalMs: Int,
        val p85IntervalMs: Int,
        val jitterPercent: Int,
        val intervalCount: Int,
        val completedTrialCount: Int
    )

    fun evaluateSamples(trials: List<List<Long>>): CalibrationResult {
        val usableTrials = trials
            .map { values -> values.distinct().sorted() }
            .filter { it.size >= MIN_POINTS_PER_TRIAL }

        if (usableTrials.size < MIN_VALID_TRIALS) {
            return invalidResult(
                "有效测试轨迹不足：只收到 ${usableTrials.size} 组完整轨迹，" +
                    "至少需要 $MIN_VALID_TRIALS 组。"
            )
        }

        val intervals = usableTrials.flatMap { times ->
            times.zipWithNext { first, second -> second - first }
        }.filter { it in 1L..250L }.map { it.toInt() }.sorted()

        if (intervals.size < MIN_INTERVAL_COUNT) {
            return invalidResult(
                "有效采样时间间隔不足：收到 ${intervals.size} 个，" +
                    "至少需要 $MIN_INTERVAL_COUNT 个。请重新采样。"
            )
        }

        val median = percentile(intervals, 0.50)
        val p85 = percentile(intervals, 0.85).coerceAtLeast(median)
        if (median <= 0 || p85 <= 0) {
            return invalidResult("采样时间戳无效，无法计算设备采样间隔。")
        }

        val jitterPercent = (((p85 - median).toFloat() / median.toFloat()) * 100f)
            .roundToInt()
            .coerceAtLeast(0)

        // Use the slower P85 interval, at least four received samples per wave cycle,
        // and an additional 20% margin. The result is intentionally capped at 10 Hz.
        val computed = (1_000f / (p85 * SAMPLES_PER_OFFSET_CYCLE)) * SAFETY_FACTOR
        val recommended = min(computed, MAX_RECOMMENDED_FREQUENCY_HZ)
            .coerceIn(MIN_RECOMMENDED_FREQUENCY_HZ, MAX_RECOMMENDED_FREQUENCY_HZ)

        return CalibrationResult(
            valid = true,
            message = "设备轨迹采样已完成。",
            recommendedFrequencyHz = recommended,
            medianIntervalMs = median,
            p85IntervalMs = p85,
            jitterPercent = jitterPercent,
            intervalCount = intervals.size,
            completedTrialCount = usableTrials.size
        )
    }

    fun saveCalibration(context: Context, result: CalibrationResult) {
        require(result.valid) { "Only a valid calibration result can be saved." }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = preferencePrefix(context)
        prefs.edit()
            .putBoolean("${key}_completed", true)
            .putFloat("${key}_frequency_hz", result.recommendedFrequencyHz)
            .putInt("${key}_median_interval_ms", result.medianIntervalMs)
            .putInt("${key}_p85_interval_ms", result.p85IntervalMs)
            .putInt("${key}_jitter_percent", result.jitterPercent)
            .putInt("${key}_sample_count", result.intervalCount)
            .putInt("${key}_trial_count", result.completedTrialCount)
            .putLong("${key}_completed_at", System.currentTimeMillis())
            .apply()
    }

    fun isCalibrated(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean("${preferencePrefix(context)}_completed", false)
    }

    fun currentFrequencyHz(context: Context): Float {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = preferencePrefix(context)
        return if (prefs.getBoolean("${key}_completed", false)) {
            prefs.getFloat("${key}_frequency_hz", fallbackFrequencyHz(context))
                .coerceIn(MIN_RECOMMENDED_FREQUENCY_HZ, MAX_RECOMMENDED_FREQUENCY_HZ)
        } else {
            fallbackFrequencyHz(context)
        }
    }

    fun configurationSubtitle(context: Context): String =
        if (isCalibrated(context)) {
            "本机已校准 · ${formatFrequency(currentFrequencyHz(context))}Hz · 只读"
        } else {
            "尚未采样 · 使用保守估算值 · 点击开始校准"
        }

    fun calibrationSummary(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val key = preferencePrefix(context)
        if (!prefs.getBoolean("${key}_completed", false)) {
            return "当前状态：尚未完成本机自动采样。\n" +
                "当前临时使用保守估算频率：${formatFrequency(fallbackFrequencyHz(context))}Hz。"
        }

        val frequency = prefs.getFloat("${key}_frequency_hz", fallbackFrequencyHz(context))
        val median = prefs.getInt("${key}_median_interval_ms", 0)
        val p85 = prefs.getInt("${key}_p85_interval_ms", 0)
        val jitter = prefs.getInt("${key}_jitter_percent", 0)
        val samples = prefs.getInt("${key}_sample_count", 0)
        val trials = prefs.getInt("${key}_trial_count", 0)
        return buildString {
            append("本机已完成自动校准\n")
            append("自动推荐频率：").append(formatFrequency(frequency)).append("Hz（只读）\n")
            append("完整测试组数：").append(trials).append("\n")
            append("有效轨迹间隔：").append(samples).append(" 个\n")
            append("中位采样间隔：").append(median).append("ms\n")
            append("P85采样间隔：").append(p85).append("ms\n")
            append("采样波动参考：").append(jitter).append("%\n")
            append("校准时间：")
            val completedAt = prefs.getLong("${key}_completed_at", 0L)
            if (completedAt > 0L) {
                append(
                    java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date(completedAt))
                )
            } else {
                append("未知")
            }
            append("\n\n需要重新评估时，点击“重新采样”。")
        }
    }

    fun formatFrequency(value: Float): String =
        String.format(java.util.Locale.US, "%.1f", value)

    /**
     * Before the first successful in-app sample, use a conservative estimate.
     * A successful local calibration always takes precedence over this estimate.
     */
    private fun fallbackFrequencyHz(context: Context): Float {
        val targetSdk = context.applicationInfo.targetSdkVersion
        val usesLegacySampling =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                targetSdk <= Build.VERSION_CODES.Q

        val estimatedSampleIntervalMs = if (usesLegacySampling) {
            100f
        } else {
            1_000f / readDisplayRefreshRate(context)
        }

        val estimate = (1_000f / (estimatedSampleIntervalMs * SAMPLES_PER_OFFSET_CYCLE)) *
            SAFETY_FACTOR
        return min(estimate, MAX_RECOMMENDED_FREQUENCY_HZ)
            .coerceIn(MIN_RECOMMENDED_FREQUENCY_HZ, MAX_RECOMMENDED_FREQUENCY_HZ)
    }

    @Suppress("DEPRECATION")
    private fun readDisplayRefreshRate(context: Context): Float {
        val refresh = runCatching {
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            manager?.defaultDisplay?.refreshRate
        }.getOrNull()

        return refresh
            ?.takeIf { it.isFinite() && it > 0f }
            ?: 60f
    }

    private fun preferencePrefix(context: Context): String {
        val model = "${Build.MANUFACTURER}_${Build.MODEL}"
            .lowercase(java.util.Locale.US)
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
        val targetSdk = context.applicationInfo.targetSdkVersion
        val refreshRate = readDisplayRefreshRate(context).roundToInt()
        return "${PREF_KEY_PREFIX}_${model}_api${Build.VERSION.SDK_INT}_target${targetSdk}_display${refreshRate}hz"
    }

    private fun percentile(sortedValues: List<Int>, fraction: Double): Int {
        if (sortedValues.isEmpty()) return 0
        val index = ceil((sortedValues.size - 1) * fraction).toInt()
            .coerceIn(0, sortedValues.lastIndex)
        return sortedValues[index]
    }

    private fun invalidResult(message: String) = CalibrationResult(
        valid = false,
        message = message,
        recommendedFrequencyHz = 0f,
        medianIntervalMs = 0,
        p85IntervalMs = 0,
        jitterPercent = 0,
        intervalCount = 0,
        completedTrialCount = 0
    )
}
