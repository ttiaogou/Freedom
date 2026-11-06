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
import com.freedom.data.MonitorActions
import com.freedom.data.MonitorStore
import com.freedom.overlay.MonitorOverlay
import com.freedom.stability.MonitorKeepAliveService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var heading: TextView
    private lateinit var runButton: ImageButton
    private lateinit var keepAccountCheckBox: CheckBox
    private var receiverRegistered = false

    private val refreshReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            refreshScreen()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_LOGS || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val currentLogs = MonitorStore.logs(this)
        val exportText = buildString {
            appendLine("Freedom 运行日志")
            appendLine("导出时间：${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
            appendLine("记录数量：${currentLogs.size}")
            appendLine("保留范围：应用内最近记录（最多 300 条）")
            appendLine()
            append(formatLogLines(currentLogs, includeTechnicalDetails = true))
        }
        val saved = runCatching {
            val output = contentResolver.openOutputStream(uri)
                ?: error("无法打开所选文件")
            output.use { it.write(exportText.toByteArray(Charsets.UTF_8)) }
        }
        if (saved.isSuccess) {
            Toast.makeText(this, "日志已导出（${currentLogs.size} 条）", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "日志导出失败：${saved.exceptionOrNull()?.message ?: "未知错误"}", Toast.LENGTH_LONG).show()
        }
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

        // Preserve the latest version's permission validation and recovery behaviour.
        if (MonitorStore.isRunning(this) &&
            (!isAccessibilityEnabled() || !Settings.canDrawOverlays(this))
        ) {
            MonitorStore.setRunning(this, false)
            MonitorKeepAliveService.stop(this)
            MonitorOverlay.hide()
            MonitorStore.addLog(this, timestamp() + "  授权状态异常，监听已停止")
        } else if (MonitorStore.isRunning(this)) {
            if (MonitorStore.isOverlayEnabled(this)) {
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
            setOnClickListener { showConfig() }
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
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(8), dp(20), dp(14))
            setBackgroundColor(Color.WHITE)
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

        MonitorStore.setRunning(this, true)
        MonitorStore.addLog(this, timestamp() + "  监听已启动，等待目标应用事件")

        if (MonitorStore.isKeepAliveEnabled(this) && !MonitorKeepAliveService.start(this)) {
            MonitorStore.addLog(
                this,
                timestamp() + "  保活服务未能启动；无障碍监听仍会按系统服务状态工作"
            )
        }

        if (MonitorStore.isOverlayEnabled(this) && !MonitorOverlay.show(this)) {
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

    /** Left-side configuration panel, grouped like the supplied reference image. */
    private fun showConfig() {
        val dialog = Dialog(this)
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFFFFEFA.toInt())
        }

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(22), dp(14), dp(18))
            setBackgroundColor(0xFFFFFEFA.toInt())
        }
        val mark = TextView(this).apply {
            text = "F"
            textSize = 27f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = roundedBackground(0xFF16B889.toInt(), dp(12).toFloat())
        }
        top.addView(mark, LinearLayout.LayoutParams(dp(48), dp(48)))

        val titleBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
            addView(TextView(this@MainActivity).apply {
                text = "Freedom"
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF202938.toInt())
            })
            addView(TextView(this@MainActivity).apply {
                text = "运行与权限配置"
                textSize = 12f
                setTextColor(0xFF778195.toInt())
            })
        }
        top.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))

        val close = TextView(this).apply {
            text = "×"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(0xFF647084.toInt())
            setOnClickListener { dialog.dismiss() }
        }
        top.addView(close, LinearLayout.LayoutParams(dp(38), dp(44)))
        panel.addView(top)

        val scroll = ScrollView(this).apply { isFillViewport = true }
        val rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(24))
        }
        scroll.addView(rows)

        addConfigSection(rows, "获取权限")
        addConfigActionRow(
            rows, "⌘", "无障碍服务",
            if (isAccessibilityEnabled()) "已授权" else "未授权 · 点击前往设置",
            if (isAccessibilityEnabled()) "已授权" else "去授权"
        ) {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        addConfigActionRow(
            rows, "◉", "悬浮窗权限",
            if (Settings.canDrawOverlays(this)) "已允许在其他应用上层显示" else "未授权 · 点击前往设置",
            if (Settings.canDrawOverlays(this)) "已允许" else "去授权"
        ) {
            requestOverlayPermission()
        }
        addConfigSwitchRow(
            rows, "⛨", "保活服务", "监听运行期间显示持续通知并提高进程优先级",
            MonitorStore.isKeepAliveEnabled(this)
        ) { enabled ->
            MonitorStore.setKeepAliveEnabled(this, enabled)
            if (enabled && MonitorStore.isRunning(this)) {
                if (!MonitorKeepAliveService.start(this)) {
                    Toast.makeText(this, "保活服务启动失败，请查看运行记录", Toast.LENGTH_LONG).show()
                }
            } else if (!enabled) {
                MonitorKeepAliveService.stop(this)
            }
        }
        addConfigSwitchRow(
            rows, "↻", "自启动检测", "设备重启后检查授权状态并提示恢复",
            MonitorStore.isBootCheckEnabled(this)
        ) { enabled ->
            MonitorStore.setBootCheckEnabled(this, enabled)
            Toast.makeText(
                this,
                if (enabled) "已开启开机检查；厂商自启动限制仍需在系统设置中允许"
                else "已关闭开机检查提醒",
                Toast.LENGTH_LONG
            ).show()
        }
        addConfigActionRow(
            rows, "⚙", "后台运行设置", "查看系统电池和应用后台限制设置", "打开"
        ) {
            openAppDetailsSettings()
        }

        addConfigSection(rows, "脚本配置")
        addConfigSwitchRow(
            rows, "◌", "悬浮按钮", "控制实时监控悬浮窗的显示",
            MonitorStore.isOverlayEnabled(this)
        ) { enabled ->
            MonitorStore.setOverlayEnabled(this, enabled)
            if (enabled) {
                if (!Settings.canDrawOverlays(this)) {
                    requestOverlayPermission()
                } else if (MonitorStore.isRunning(this)) {
                    MonitorOverlay.show(this)
                }
            } else {
                MonitorOverlay.hide()
            }
        }
        addConfigSwitchRow(
            rows, "▤", "悬浮日志", "控制展开后是否显示实时事件日志",
            MonitorStore.isFloatingLogEnabled(this)
        ) { enabled ->
            MonitorStore.setFloatingLogEnabled(this, enabled)
            MonitorOverlay.update(this)
        }
        addConfigActionRow(
            rows, "◎", "监控目标", MonitorStore.targetPackage(this), "修改"
        ) {
            showTargetEditor()
        }

        addConfigSection(rows, "其他权限")
        addConfigActionRow(
            rows, "▧", "照片和视频访问（可选）", "当前监控功能不依赖此权限", "设置"
        ) {
            requestPhotoPermission()
        }
        addConfigActionRow(
            rows, "ⓘ", "权限用途说明", "查看 Freedom 各项权限的用途", "查看"
        ) {
            showPermissionExplanation()
        }

        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        dialog.setContentView(panel)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()

        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val params = attributes
            params.gravity = Gravity.START or Gravity.TOP
            params.width = (this@MainActivity.resources.displayMetrics.widthPixels * 0.82f).toInt()
            params.height = ViewGroup.LayoutParams.MATCH_PARENT
            params.dimAmount = 0.28f
            attributes = params
        }
    }

    private fun showTargetEditor() {
        val input = EditText(this).apply {
            setSingleLine(true)
            hint = "例如 com.immomo.momo"
            setText(MonitorStore.targetPackage(this@MainActivity))
            setSelection(text.length)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(6), dp(22), 0)
            addView(TextView(this@MainActivity).apply {
                text = "输入需要监控的 Android 应用包名："
                textSize = 13f
                setTextColor(0xFF596579.toInt())
            })
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("监控目标")
            .setView(container)
            .setPositiveButton("保存目标") { _, _ ->
                val value = input.text.toString().trim()
                if (!PACKAGE_PATTERN.matches(value)) {
                    Toast.makeText(
                        this,
                        "包名格式不正确，例如 com.immomo.momo",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    MonitorStore.setTargetPackage(this, value)
                    sendBroadcast(
                        Intent(MonitorActions.ACTION_TARGET_CHANGED).setPackage(packageName)
                    )
                    MonitorStore.updateAppPage(
                        this,
                        app = "尚未识别",
                        page = "等待页面信息"
                    )
                    MonitorStore.addLog(this, timestamp() + "  监控目标已设置为 $value")
                    refreshScreen()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addConfigSection(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(0xFF315EFB.toInt())
            setPadding(dp(4), dp(16), dp(4), dp(8))
        })
    }

    private fun addConfigActionRow(
        parent: LinearLayout,
        icon: String,
        title: String,
        subtitle: String,
        actionText: String,
        action: () -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(9), dp(6), dp(9))
            background = roundedBackground(Color.WHITE, dp(10).toFloat())
            setOnClickListener { action() }
        }
        row.addView(configRowIcon(icon), LinearLayout.LayoutParams(dp(38), dp(38)))
        row.addView(configRowTexts(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = actionText
            textSize = 11f
            setTextColor(0xFF315EFB.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(5), 0, dp(3), 0)
        }, LinearLayout.LayoutParams(-2, dp(38)))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(7)
        })
    }

    private fun addConfigSwitchRow(
        parent: LinearLayout,
        icon: String,
        title: String,
        subtitle: String,
        checked: Boolean,
        onChanged: (Boolean) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(7), dp(6), dp(7))
            background = roundedBackground(Color.WHITE, dp(10).toFloat())
        }
        row.addView(configRowIcon(icon), LinearLayout.LayoutParams(dp(38), dp(38)))
        row.addView(configRowTexts(title, subtitle), LinearLayout.LayoutParams(0, -2, 1f))
        val toggle = Switch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, value -> onChanged(value) }
        }
        row.addView(toggle, LinearLayout.LayoutParams(-2, dp(42)))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(7)
        })
    }

    private fun configRowIcon(value: String) = TextView(this).apply {
        text = value
        textSize = 21f
        gravity = Gravity.CENTER
        setTextColor(0xFF263244.toInt())
    }

    private fun configRowTexts(title: String, subtitle: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), 0, dp(4), 0)
        addView(TextView(this@MainActivity).apply {
            text = title
            textSize = 14f
            setTextColor(0xFF202938.toInt())
        })
        addView(TextView(this@MainActivity).apply {
            text = subtitle
            textSize = 10f
            setTextColor(0xFF7B8494.toInt())
            maxLines = 2
        })
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
            "照片和视频访问（可选）",
            "查看权限用途说明"
        )
        AlertDialog.Builder(this)
            .setTitle("系统权限")
            .setItems(options) { _, index ->
                when (index) {
                    0 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    1 -> requestOverlayPermission()
                    2 -> requestPhotoPermission()
                    3 -> showPermissionExplanation()
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
        if (requestCode == REQUEST_MEDIA_PERMISSION) {
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
        var showTechnicalDetails = false
        val view = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF263244.toInt())
            setTextIsSelectable(true)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            text = formatLogLines(records, false).ifBlank { "暂无运行记录" }
        }
        val detailsToggle = Button(this).apply {
            text = "查看技术详情"
            isAllCaps = false
            setOnClickListener {
                showTechnicalDetails = !showTechnicalDetails
                text = if (showTechnicalDetails) "收起技术详情" else "查看技术详情"
                view.text = formatLogLines(records, showTechnicalDetails)
                    .ifBlank { "暂无运行记录" }
            }
        }
        val exportButton = Button(this).apply {
            text = "导出日志"
            isAllCaps = false
            setOnClickListener { exportLogs() }
        }
        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(detailsToggle, LinearLayout.LayoutParams(0, -2, 1f))
            addView(exportButton, LinearLayout.LayoutParams(0, -2, 1f))
        }
        val scroll = ScrollView(this).apply {
            addView(view)
            setPadding(dp(4), 0, dp(4), 0)
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(actionRow, LinearLayout.LayoutParams(-1, -2))
            addView(scroll, LinearLayout.LayoutParams(-1, dp(420)))
        }
        AlertDialog.Builder(this)
            .setTitle("运行记录（最近 ${records.size} 条）")
            .setView(container)
            .setPositiveButton("关闭", null)
            .setNeutralButton("清空记录") { _, _ ->
                MonitorStore.clearLogs(this)
                refreshScreen()
            }
            .show()
    }

    private fun exportLogs() {
        val filenameTime = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "Freedom-运行日志-$filenameTime.txt")
        }
        runCatching {
            startActivityForResult(intent, REQUEST_EXPORT_LOGS)
        }.onFailure {
            Toast.makeText(this, "无法打开文件保存界面：${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun formatLogLines(
        lines: List<String>,
        includeTechnicalDetails: Boolean
    ): String = lines.joinToString("\n") { line ->
        formatLogLine(line, includeTechnicalDetails)
    }

    private fun formatLogLine(line: String, includeTechnicalDetails: Boolean): String {
        val separatorIndex = line.indexOf(LOG_DETAIL_SEPARATOR)
        if (separatorIndex >= 0) {
            val summary = line.substring(0, separatorIndex).trimEnd()
            val technical = line.substring(separatorIndex + 1).trim()
            return if (includeTechnicalDetails && technical.isNotBlank()) {
                "$summary\n  技术详情：$technical"
            } else {
                summary
            }
        }
        return if (includeTechnicalDetails) line else formatLegacyEventLine(line)
    }

    /** Convert previously stored v1 event strings too, so old history remains readable after upgrade. */
    private fun formatLegacyEventLine(line: String): String {
        val type = Regex("\\[(TYPE_[A-Z0-9_]+)\\]").find(line)?.groupValues?.get(1)
            ?: return line
        val className = Regex("原始类名=([^；]+)").find(line)?.groupValues?.get(1)?.trim().orEmpty()
        val simpleClass = className.substringAfterLast('.')
        val area = when (simpleClass) {
            "RecyclerView" -> "内容列表"
            "ScrollView", "HorizontalScrollView" -> "可滚动区域"
            "EditText" -> "文字输入框"
            "TextView" -> "文字区域"
            "Button", "ImageButton" -> "按钮区域"
            "ImageView" -> "图片区域"
            "WebView" -> "网页内容区域"
            "ViewPager", "ViewPager2" -> "分页区域"
            "CheckBox", "RadioButton", "Switch" -> "选项控件"
            else -> "界面区域"
        }
        val title = when (type) {
            "TYPE_WINDOW_CONTENT_CHANGED" -> "页面内容更新"
            "TYPE_VIEW_SCROLLED" -> if (simpleClass == "RecyclerView") "列表滚动" else "页面滚动"
            "TYPE_VIEW_CLICKED" -> "控件点击"
            "TYPE_VIEW_LONG_CLICKED" -> "控件长按"
            "TYPE_VIEW_TEXT_CHANGED" -> "文字内容变化"
            "TYPE_WINDOW_STATE_CHANGED" -> "页面或窗口切换"
            "TYPE_VIEW_SELECTED" -> "选项状态变化"
            "TYPE_VIEW_FOCUSED" -> "控件获得焦点"
            "TYPE_VIEW_TEXT_SELECTION_CHANGED" -> "文字选区变化"
            "TYPE_NOTIFICATION_STATE_CHANGED" -> "通知内容变化"
            "TYPE_WINDOWS_CHANGED" -> "应用窗口变化"
            else -> "界面事件"
        }
        val explanation = when (type) {
            "TYPE_WINDOW_CONTENT_CHANGED" -> "这个区域的内容或结构发生了变化，可能是列表刷新、内容加载或项目更新。"
            "TYPE_VIEW_SCROLLED" -> if (simpleClass == "RecyclerView") {
                "检测到内容列表的滚动位置或可见项目发生变化。"
            } else {
                "检测到可滚动区域的位置或可见内容发生变化。"
            }
            "TYPE_VIEW_CLICKED" -> "系统报告这个界面控件发生了一次点击事件。"
            "TYPE_VIEW_LONG_CLICKED" -> "系统报告这个界面控件发生了一次长按事件。"
            "TYPE_VIEW_TEXT_CHANGED" -> "一个文字控件中的内容发生了变化。"
            "TYPE_WINDOW_STATE_CHANGED" -> "当前应用的窗口或页面状态发生了变化。"
            else -> "系统检测到界面状态变化；详细类型可在技术详情中查看。"
        }
        val time = Regex("^\\d{2}:\\d{2}:\\d{2}\\.\\d{3}").find(line)?.value.orEmpty()
        val relatedText = Regex("(?:内容|描述)=([^；]+)").find(line)?.groupValues?.get(1)?.trim()
        return buildString {
            if (time.isNotBlank()) append(time).append("  ")
            append(title)
            append("\n操作区域：").append(area)
            append("\n说明：").append(explanation)
            if (!relatedText.isNullOrBlank()) {
                append("\n相关文字：").append(relatedText.take(100))
            }
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
        private const val LOG_DETAIL_SEPARATOR = "\u001F"
        private const val REQUEST_MEDIA_PERMISSION = 2001
        private const val REQUEST_EXPORT_LOGS = 2002
        private val PACKAGE_PATTERN = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    }
}
