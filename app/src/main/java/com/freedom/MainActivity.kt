package com.freedom

import android.app.Activity
import android.app.Dialog
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.freedom.accessibility.FreedomAccessibilityService
import com.freedom.accessibility.gesture.GestureTuning
import com.freedom.data.MonitorActions
import com.freedom.data.MonitorStore
import com.freedom.overlay.MonitorOverlay
import com.freedom.stability.MonitorKeepAliveService
import com.freedom.ui.calibration.GestureCalibrationUiController
import com.freedom.ui.config.ConfigPanel
import com.freedom.ui.config.TargetAppEditor
import com.freedom.ui.logs.LogRecordsDialog
import com.freedom.ui.logs.LogTextFormatter

class MainActivity : Activity() {

    private lateinit var heading: TextView
    private lateinit var runButton: ImageButton
    private lateinit var keepAccountCheckBox: CheckBox
    private var receiverRegistered = false
    private var pendingLogExportContent: String? = null
    private var pendingStartAfterNotificationPermission = false

    private val configPanel by lazy(LazyThreadSafetyMode.NONE) {
        ConfigPanel(
            activity = this,
            callbacks = ConfigPanel.Callbacks(
                isAccessibilityEnabled = ::isAccessibilityEnabled,
                dp = ::dp,
                roundedBackground = ::roundedBackground,
                timestamp = ::timestamp,
                openAppDetailsSettings = ::openAppDetailsSettings,
                requestOverlayPermission = ::requestOverlayPermission,
                notificationPermissionStatus = ::notificationPermissionStatus,
                requestNotificationPermission = ::requestNotificationPermissionFromSettings,
                showTargetEditor = ::showTargetEditor,
                requestPhotoPermission = ::requestPhotoPermission,
                showPermissionExplanation = ::showPermissionExplanation,
                showGestureCalibrationDialog = { gestureCalibrationUi.showDialog() }
            )
        )
    }

    private val gestureCalibrationUi by lazy(LazyThreadSafetyMode.NONE) {
        GestureCalibrationUiController(
            activity = this,
            isAccessibilityEnabled = { isAccessibilityEnabled() },
            dp = { value -> dp(value) },
            roundedBackground = { color, radius -> roundedBackground(color, radius) },
            timestamp = { timestamp() }
        )
    }

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    override fun onResume() {
        super.onResume()
        if (!receiverRegistered) {
            val filter = IntentFilter(MonitorActions.ACTION_REFRESH)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(refreshReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(refreshReceiver, filter)
            }
            receiverRegistered = true
        }

        // The 悬浮日志 switch is the sole user-facing overlay control. Mirror it to the
        // legacy internal flag so existing service-side checks remain compatible.
        val floatingLogEnabled = MonitorStore.isFloatingLogEnabled(this)
        MonitorStore.setOverlayEnabled(this, floatingLogEnabled)

        // Preserve the latest version's permission validation and recovery behaviour.
        if (MonitorStore.isRunning(this) &&
            (!isAccessibilityEnabled() || !Settings.canDrawOverlays(this))
        ) {
            MonitorStore.setRunning(this, false)
            MonitorKeepAliveService.stop(this)
            MonitorOverlay.hide()
            MonitorStore.addLog(this, timestamp() + "  授权状态异常，监听已停止")
        } else if (MonitorStore.isRunning(this)) {
            if (MonitorStore.isFloatingLogEnabled(this)) {
                MonitorOverlay.show(this)
            } else {
                MonitorOverlay.hide()
            }
            if (MonitorStore.isKeepAliveEnabled(this)) {
                MonitorKeepAliveService.start(this)
            }
        }
        refreshScreen()
    }

    override fun onPause() {
        gestureCalibrationUi.onPause()
        if (receiverRegistered) {
            runCatching { unregisterReceiver(refreshReceiver) }
            receiverRegistered = false
        }
        super.onPause()
    }

    /** Main screen: compact header, one opt-in checkbox, and the icon-only run control. */
    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF6F7FA.toInt())
            setOnApplyWindowInsetsListener { view, insets ->
                val topInset: Int
                val bottomInset: Int
                if (Build.VERSION.SDK_INT >= 30) {
                    val bars = insets.getInsets(
                        WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars()
                    )
                    topInset = bars.top
                    bottomInset = bars.bottom
                } else {
                    @Suppress("DEPRECATION")
                    val top = insets.systemWindowInsetTop
                    @Suppress("DEPRECATION")
                    val bottom = insets.systemWindowInsetBottom
                    topInset = top
                    bottomInset = bottom
                }
                view.setPadding(view.paddingLeft, topInset, view.paddingRight, bottomInset)
                insets
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setBackgroundColor(Color.WHITE)
        }

        val configButton = Button(this).apply {
            text = "☰ 配置"
            isAllCaps = false
            setOnClickListener { configPanel.show() }
        }

        heading = TextView(this).apply {
            text = "未运行"
            textSize = 23f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(0xFF172033.toInt())
            maxLines = 1
        }

        val recordsButton = Button(this).apply {
            text = "运行记录"
            isAllCaps = false
            setOnClickListener { showRecords() }
        }

        header.addView(configButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(heading, LinearLayout.LayoutParams(0, dp(48), 1.2f))
        header.addView(recordsButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        root.addView(header)

        // This option is stored locally, defaults to unchecked, and does not
        // trigger any automation until its behaviour is separately specified.
        val optionArea = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(10))
            setBackgroundColor(Color.WHITE)
        }

        keepAccountCheckBox = CheckBox(this).apply {
            text = "开启养号模式"
            textSize = 15f
            setTextColor(0xFF263244.toInt())
            isChecked = getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                .getBoolean(KEY_KEEP_ACCOUNT_MODE, false)
            setOnCheckedChangeListener { _, checked ->
                getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                    .edit()
                    .putBoolean(KEY_KEEP_ACCOUNT_MODE, checked)
                    .apply()
            }
        }
        optionArea.addView(keepAccountCheckBox, LinearLayout.LayoutParams(-1, dp(44)))
        root.addView(optionArea)

        // Keep the remaining work area empty; the run control stays at the bottom.
        root.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(20), dp(14))
            setBackgroundColor(Color.WHITE)
        }

        if (BuildConfig.DEBUG) {
            val debugRecoveryButton = Button(this).apply {
                text = "模拟返回位置验证失败"
                textSize = 12f
                isAllCaps = false
                setOnClickListener { confirmDebugReturnRecoveryTest() }
            }
            bottom.addView(
                debugRecoveryButton,
                LinearLayout.LayoutParams(0, dp(52), 1f).apply {
                    marginEnd = dp(12)
                }
            )
        }

        runButton = ImageButton(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
            contentDescription = "开始运行"
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setOnClickListener { toggleMonitoring() }
        }
        bottom.addView(runButton, LinearLayout.LayoutParams(dp(68), dp(68)))
        root.addView(bottom)

        setContentView(root)
        refreshScreen()
    }

    /** Debug-only entry that drives the real feed-refresh recovery path on demand. */
    private fun confirmDebugReturnRecoveryTest() {
        if (!BuildConfig.DEBUG) return

        if (!MonitorStore.isRunning(this) || !keepAccountCheckBox.isChecked) {
            Toast.makeText(
                this,
                "请先开启监听并选择养号模式，再运行恢复专项测试",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        if (!isAccessibilityEnabled()) {
            Toast.makeText(this, "无障碍服务尚未连接，不能触发恢复测试", Toast.LENGTH_LONG).show()
            return
        }

        val targetPackage = MonitorStore.targetPackage(this)
        if (targetPackage != DEBUG_MOMO_PACKAGE) {
            Toast.makeText(
                this,
                "本专项测试仅适用于目标应用设置为陌陌",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val momoLaunchIntent = packageManager.getLaunchIntentForPackage(DEBUG_MOMO_PACKAGE)
        if (momoLaunchIntent == null) {
            Toast.makeText(this, "没有找到已安装的陌陌应用", Toast.LENGTH_LONG).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Debug 恢复专项测试")
            .setMessage(
                "此操作会中断当前自动浏览，并模拟一次‘返回位置验证失败’。\n\n" +
                    "Freedom 将打开陌陌，主动识别首页和动态标签，执行首页连续双击刷新，" +
                    "验证刷新后列表稳定，再尝试恢复自动浏览。\n\n" +
                    "测试开始后请不要触摸手机屏幕；刷新成功后程序可能继续自动浏览，" +
                    "请在看到恢复成功日志后手动停止监听。"
            )
            .setNegativeButton("取消", null)
            .setPositiveButton("打开陌陌并测试") { _, _ ->
                MonitorStore.addLog(
                    this,
                    timestamp() + "  Debug 恢复专项测试已请求；准备打开陌陌并模拟返回位置验证失败"
                )
                runCatching {
                    startActivity(momoLaunchIntent)
                }.onSuccess {
                    Handler(Looper.getMainLooper()).postDelayed({
                        sendBroadcast(
                            Intent(FreedomAccessibilityService.ACTION_DEBUG_SIMULATE_RETURN_POSITION_FAILURE)
                                .setPackage(packageName)
                        )
                        Toast.makeText(
                            this,
                            "已发送恢复测试指令；请保持屏幕不触摸并查看运行日志",
                            Toast.LENGTH_LONG
                        ).show()
                    }, DEBUG_RECOVERY_TRIGGER_DELAY_MS)
                }.onFailure {
                    Toast.makeText(this, "无法打开陌陌：${it.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun toggleMonitoring() {
        if (MonitorStore.isRunning(this)) {
            MonitorStore.setRunning(this, false)
            MonitorStore.addLog(this, timestamp() + "  监听已停止")
            MonitorKeepAliveService.stop(this)
            MonitorOverlay.hide()
            sendRefresh()
            refreshScreen()
            return
        }

        if (!keepAccountCheckBox.isChecked) {
            AlertDialog.Builder(this)
                .setTitle("错误警告")
                .setMessage("请先选择模式")
                .setPositiveButton("确定", null)
                .show()
            return
        }

        if (!isAccessibilityEnabled()) {
            AlertDialog.Builder(this)
                .setTitle("需要开启无障碍服务")
                .setMessage(
                    "Freedom 需要通过系统无障碍服务接收目标应用的界面事件，并读取系统公开的节点信息。请在系统设置中手动开启 Freedom。"
                )
                .setPositiveButton("前往无障碍设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }

        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        if (!hasNotificationPermission()) {
            showNotificationPermissionPromptBeforeStart()
            return
        }

        startMonitoringAfterPermissionChecks(notificationPermissionGranted = true)
    }

    private fun showTargetEditor() {
        TargetAppEditor.show(
            activity = this,
            dp = { value -> dp(value) },
            packagePattern = PACKAGE_PATTERN,
            timestamp = { timestamp() },
            refreshScreen = { refreshScreen() }
        )
    }

    private fun openAppDetailsSettings() {
        runCatching {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }.onFailure {
            Toast.makeText(
                this,
                "无法打开应用详情设置，请手动进入系统应用设置",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showPermissionSettings() {
        val options = arrayOf(
            "无障碍服务设置",
            "悬浮窗权限",
            "通知权限（可选）",
            "照片和视频访问（可选）",
            "查看权限用途说明"
        )
        AlertDialog.Builder(this)
            .setTitle("系统权限")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    1 -> requestOverlayPermission()
                    2 -> requestNotificationPermissionFromSettings()
                    3 -> requestPhotoPermission()
                    4 -> showPermissionExplanation()
                }
            }
            .show()
    }

    private fun showPermissionExplanation() {
        AlertDialog.Builder(this)
            .setTitle("权限用途")
            .setMessage(
                "无障碍服务：接收目标应用的界面事件，并读取系统公开的节点信息。\n\n" +
                    "悬浮窗：在其他应用上方显示实时监控面板。\n\n" +
                    "通知：在 Android 13 及以上版本，用于显示持续运行通知、运行状态和停止入口，也用于设备重启后的恢复提醒。拒绝通知权限不会阻止无障碍监控，但通知可能无法正常显示。\n\n" +
                    "照片和视频：作为可选权限预留给后续本地媒体功能；当前事件监控与节点读取不依赖相册权限。拒绝该权限不会影响监控。\n\n" +
                    "Freedom 不会把监控记录上传到服务器。"
            )
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } else {
            Toast.makeText(this, "悬浮窗权限已允许", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun notificationPermissionStatus(): String = when {
        Build.VERSION.SDK_INT < 33 -> "当前 Android 版本无需单独授权"
        hasNotificationPermission() -> "已授权显示运行通知和恢复提醒"
        else -> "未授权 · 监控仍可运行，但通知可能无法显示"
    }

    private fun showNotificationPermissionPromptBeforeStart() {
        AlertDialog.Builder(this)
            .setTitle("允许 Freedom 显示运行通知")
            .setMessage(
                "通知权限用于在通知栏显示 Freedom 的持续运行状态和停止入口，也用于设备重启后的恢复提醒。\n\n" +
                    "它不是无障碍服务权限。即使不授权，仍然可以启动实时监控；只是运行通知和恢复提醒可能无法正常显示。"
            )
            .setPositiveButton("申请通知权限") { _, _ ->
                pendingStartAfterNotificationPermission = true
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
            .setNegativeButton("暂不授权，继续运行") { _, _ ->
                startMonitoringAfterPermissionChecks(notificationPermissionGranted = false)
            }
            .setOnCancelListener {
                pendingStartAfterNotificationPermission = false
            }
            .show()
    }

    private fun requestNotificationPermissionFromSettings() {
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "当前 Android 版本无需单独申请通知权限", Toast.LENGTH_LONG).show()
            return
        }
        if (hasNotificationPermission()) {
            Toast.makeText(this, "通知权限已允许", Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("通知权限用途")
            .setMessage(
                "允许后，Freedom 可以在通知栏显示持续运行状态、停止入口和重启后的恢复提醒。拒绝不会影响无障碍监控本身。"
            )
            .setPositiveButton("申请权限") { _, _ ->
                pendingStartAfterNotificationPermission = false
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    REQUEST_NOTIFICATION_PERMISSION
                )
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun startMonitoringAfterPermissionChecks(notificationPermissionGranted: Boolean) {
        MonitorStore.setRunning(this, true)
        MonitorStore.addLog(
            this,
            timestamp() + if (notificationPermissionGranted) {
                "  监听已启动，通知显示能力已满足，等待目标应用事件"
            } else {
                "  监听已启动；通知权限未授权，仍允许监控运行，但运行通知和恢复提醒可能无法显示"
            }
        )
        if (!notificationPermissionGranted && Build.VERSION.SDK_INT >= 33) {
            Toast.makeText(
                this,
                "通知权限未授权，监控仍会运行；运行通知和恢复提醒可能无法显示",
                Toast.LENGTH_LONG
            ).show()
        }

        if (MonitorStore.isKeepAliveEnabled(this) && !MonitorKeepAliveService.start(this)) {
            MonitorStore.addLog(
                this,
                timestamp() + "  保活服务未能启动；无障碍监听仍会按系统服务状态工作"
            )
        }

        if (MonitorStore.isFloatingLogEnabled(this) && !MonitorOverlay.show(this)) {
            MonitorStore.setRunning(this, false)
            MonitorKeepAliveService.stop(this)
            Toast.makeText(
                this,
                "悬浮窗创建失败，请检查系统悬浮窗权限",
                Toast.LENGTH_LONG
            ).show()
            refreshScreen()
            return
        }
        sendRefresh()
        refreshScreen()
    }

    private fun requestPhotoPermission() {
        val permissions = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                android.Manifest.permission.READ_MEDIA_IMAGES,
                android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        val needed = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            Toast.makeText(
                this,
                "照片访问权限已满足（可能为用户选择的部分照片）",
                Toast.LENGTH_SHORT
            ).show()
        } else {
            requestPermissions(needed.toTypedArray(), REQUEST_MEDIA_PERMISSION)
        }
    }

    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            val granted = hasNotificationPermission()
            val shouldStart = pendingStartAfterNotificationPermission
            pendingStartAfterNotificationPermission = false
            if (shouldStart) {
                startMonitoringAfterPermissionChecks(notificationPermissionGranted = granted)
                if (granted) {
                    Toast.makeText(this, "通知权限已授予，正在启动监听", Toast.LENGTH_SHORT).show()
                }
            } else {
                MonitorStore.addLog(
                    this,
                    timestamp() + if (granted) {
                        "  通知权限已授予"
                    } else {
                        "  通知权限未授予；不影响无障碍监控，但运行通知和恢复提醒可能无法显示"
                    }
                )
                Toast.makeText(
                    this,
                    if (granted) "通知权限已授予"
                    else "通知权限未授予；仍可使用监控功能，通知可能无法显示",
                    Toast.LENGTH_LONG
                ).show()
            }
        } else if (requestCode == REQUEST_MEDIA_PERMISSION) {
            val granted = grantResults.count { it == PackageManager.PERMISSION_GRANTED }
            Toast.makeText(
                this,
                if (granted > 0) "照片访问权限已授予"
                else "未授予照片访问权限；不影响事件监控",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, FreedomAccessibilityService::class.java)
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ).orEmpty()
        return enabled.split(':').any { flattened ->
            val component = ComponentName.unflattenFromString(flattened)
            component != null &&
                component.packageName == expected.packageName &&
                component.className == expected.className
        }
    }

    private fun refreshScreen() {
        if (!::heading.isInitialized) return

        val running = MonitorStore.isRunning(this)
        val accessibilityEnabled = isAccessibilityEnabled()
        val overlayPermission = Settings.canDrawOverlays(this)

        heading.text = when {
            !accessibilityEnabled || !overlayPermission -> "异常"
            running -> "运行中"
            else -> "未运行"
        }
        heading.setTextColor(
            when {
                !accessibilityEnabled || !overlayPermission -> 0xFFD14343.toInt()
                running -> 0xFF16A765.toInt()
                else -> 0xFF172033.toInt()
            }
        )

        runButton.setImageResource(
            if (running) R.drawable.ic_run_stop else R.drawable.ic_run_start
        )
        runButton.contentDescription = if (running) "停止运行" else "开始运行"
    }

    private fun showRecords() {
        val records = MonitorStore.logs(this)
        LogRecordsDialog.show(
            activity = this,
            records = records,
            dp = { value -> dp(value) },
            onExport = ::launchLogExport,
            onCleared = {
                MonitorStore.clearLogs(this)
                refreshScreen()
            }
        )
    }

    /** Open Android's system document picker; no storage permission is required. */
    private fun launchLogExport(records: List<String>) {
        val content = buildLogExportContent(records)
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, createLogExportFileName())
        }
        pendingLogExportContent = content
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_EXPORT_LOGS)
        } catch (error: Exception) {
            pendingLogExportContent = null
            MonitorStore.addLog(
                this,
                timestamp() + "  启动日志导出失败：${error.javaClass.simpleName}: " +
                    (error.message ?: "无详细信息")
            )
            Toast.makeText(this, "无法打开文件保存窗口，请稍后重试", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildLogExportContent(records: List<String>): String = buildString {
        append("Freedom 运行日志\n")
        append("导出时间：")
        append(
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
        )
        append("\n记录数量：").append(records.size)
        append("\n导出内容：包含完整技术详情\n")
        append("=".repeat(72)).append("\n\n")
        append(LogTextFormatter.formatLogLines(records, includeTechnicalDetails = true))
        if (records.isNotEmpty()) append('\n')
    }

    private fun createLogExportFileName(): String {
        val time = java.text.SimpleDateFormat(
            "yyyyMMdd-HHmmss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        return "Freedom-运行日志-$time.txt"
    }

    @Deprecated("Use the document picker result to write the selected URI")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_LOGS) return

        val content = pendingLogExportContent
        pendingLogExportContent = null
        if (resultCode != RESULT_OK || data?.data == null) {
            Toast.makeText(this, "已取消日志导出", Toast.LENGTH_SHORT).show()
            return
        }
        if (content == null) {
            Toast.makeText(this, "导出内容已失效，请重新导出", Toast.LENGTH_LONG).show()
            return
        }

        try {
            val uri = data.data!!
            val output = contentResolver.openOutputStream(uri, "w")
                ?: throw java.io.IOException("无法打开所选文件")
            output.use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.flush()
            }
            Toast.makeText(this, "运行记录已导出", Toast.LENGTH_LONG).show()
        } catch (error: Exception) {
            MonitorStore.addLog(
                this,
                timestamp() + "  写入日志文件失败：${error.javaClass.simpleName}: " +
                    (error.message ?: "无详细信息")
            )
            Toast.makeText(
                this,
                "日志导出失败：${error.message ?: error.javaClass.simpleName}",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    // Retained utility views from the existing Activity implementation.
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(13), dp(14), dp(13))
        background = roundedBackground(Color.WHITE, dp(12).toFloat())
        elevation = dp(1).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(12)
        }
    }

    private fun sectionTitle(text: String) = TextView(this).apply {
        this.text = text
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(0xFF202B3C.toInt())
    }

    private fun roundedBackground(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        setStroke(1, 0xFFE7EAF0.toInt())
    }

    private fun sendRefresh() {
        sendBroadcast(Intent(MonitorActions.ACTION_REFRESH).setPackage(packageName))
    }

    private fun timestamp(): String =
        java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
            .format(java.util.Date())

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        private const val PREFS_UI = "freedom_main_ui"
        private const val KEY_KEEP_ACCOUNT_MODE = "keep_account_mode_enabled"
        private const val REQUEST_MEDIA_PERMISSION = 2001
        private const val REQUEST_EXPORT_LOGS = 2002
        private const val REQUEST_NOTIFICATION_PERMISSION = 2003
        private const val DEBUG_MOMO_PACKAGE = "com.immomo.momo"
        private const val DEBUG_RECOVERY_TRIGGER_DELAY_MS = 1_000L
        private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
