# Freedom 稳定性优化版本变更清单

基线：用户当前 Freedom main 分支对应的项目结构。原有应用 ID `com.freedom`、监控目标配置、事件监听、节点扫描和本地日志保留。

## 本次变更文件

- `app/src/main/AndroidManifest.xml`：声明 specialUse 前台服务、开机完成接收器、相应权限；原无障碍服务声明保留。
- `app/src/main/java/com/freedom/stability/MonitorKeepAliveService.kt`：新增用户主动启动的持续通知前台服务，返回 `START_STICKY`，并提供通知内停止操作。
- `app/src/main/java/com/freedom/stability/BootRecoveryReceiver.kt`：新增开机检查，核对无障碍和悬浮窗授权；只发送检查通知，不从开机广播直接启动前台服务。
- `app/src/main/java/com/freedom/accessibility/FreedomAccessibilityService.kt`：保留原有事件与节点扫描实现；系统重新绑定时根据已保存的运行意图恢复悬浮窗和前台服务。
- `app/src/main/java/com/freedom/data/MonitorStore.kt`：保留原有事件和节点数据结构；新增保活、开机检查、悬浮窗开关、悬浮日志开关和悬浮窗尺寸设置。
- `app/src/main/java/com/freedom/MainActivity.kt`：开始运行前检查无障碍和悬浮窗授权；左侧配置面板分组展示权限与设置；原监控目标编辑、记录、照片权限用途说明保留。
- `app/src/main/java/com/freedom/overlay/MonitorOverlay.kt`：默认收起；展开默认高度约为屏幕高度 34%；保留拖动、停止、事件和节点显示；增加右下角拖动尺寸把手，记忆宽高。
- `.github/workflows/android-build.yml`：使用 `android-actions/setup-android@v4`，先验证 XML，再构建 Debug APK；继续只上传 APK。
- `README.md`：更新功能和 Android 后台限制说明。

## 明确未改动

- 原有 AccessibilityEvent 分类、中文事件映射和节点 BFS 扫描主体。
- 节点扫描上限 3000、节点预览上限 140。
- `MonitorActions.kt`、无障碍服务 XML 配置、Gradle 插件版本、应用包名、图标与主题资源。
- Acb 仓库及其任何文件。

## 验证状态

本次已完成 XML 解析、工作流 YAML 解析、关键配置/引用检查和 Kotlin 源码语法扫描。当前环境没有 Android SDK 与 Gradle 构建环境，未执行 Android `assembleDebug`，也没有真机验证；最终编译结果需以 GitHub Actions 为准。


## Stage 1 — 按职责拆分结构（2026-11-07）

- 抽取自动浏览数据模型和主流程单步调度器；保留原有 Handler 单待执行、阶段门控和取消语义。
- 抽取无障碍事件格式化、目标窗口节点 BFS 扫描。
- 将设置面板、手势校准 UI/广播/超时，以及运行日志格式化/展示移入 `ui/`。
- `FreedomAccessibilityService` 继续负责 Android 无障碍服务生命周期、事件入口和既有自动浏览状态机；本阶段没有重写页面导航、手势执行或详情页返回逻辑。
- `MainActivity` 保留运行启停、权限系统交互、目标包名编辑和系统文档导出入口；设置面板、校准界面与日志显示改由独立 UI 组件承担。
- 新增 `ARCHITECTURE.md` 说明职责边界和调度约束。
- 未增加新功能，未修改目标应用的自动化行为规则。

- 将原 `accessibility/GestureTuning.kt` 移入 `accessibility/gesture/` 子包；API 名称和持久化键不变。
- 将监控目标包名编辑界面移入 `ui/config/TargetAppEditor.kt`；校验规则、保存顺序和广播 Action 不变。
