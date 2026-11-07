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


## Stage 2 — 拆分设备手势校准执行逻辑（2026-11-07）

- 新增 `app/src/main/java/com/freedom/accessibility/gesture/GestureCalibrationController.kt`，承接内部校准会话状态、广播分发、测试手势路径构建、派发回调、组间计时、成功/失败/取消处理。
- `FreedomAccessibilityService.kt` 保留校准广播接收器的注册/注销与服务销毁生命周期；通过控制器调用原有 AccessibilityService 手势派发能力。
- 保留原有校准 Action、Extra、包名限定、前台包名检查、屏幕边界校验、四组采样次数、路径控制点、350ms/700ms/350ms/250ms 时间参数和日志/广播顺序。
- 没有修改自动浏览状态机、自动浏览手势逻辑、页面识别、节点扫描、UI 校准采样器或 GestureTuning 的设备参数计算。
- 本阶段仅移动职责和建立回调边界，不新增用户功能或第三方依赖。
- 本地完成控制器独立 Kotlin 编译检查（使用最小 Android API stub）及静态引用/生命周期核对；未执行 Android `assembleDebug`，最终以 GitHub Actions 和真机测试为准。


## Stage 3 — Momo page inspection extraction (2026-11-07)

- Added `app/src/main/java/com/freedom/accessibility/browse/MomoPageInspector.kt`.
- Moved page-only evidence collection and readiness logic: Home bottom-navigation inspection, Dynamic top-tab inspection, feed readiness, visible feed-container lookup, and page fingerprint calculation.
- Kept `FreedomAccessibilityService` as the owner of state-machine decisions and preserved its private call points as delegates.
- Moved the read-only detail identity check into the inspector; Activity/session values are passed in by the service. Navigation actions, popup dismissal, and gesture execution remain in the service.
- Passed the exact existing package/label/threshold/node-limit values into the inspector; no behavior constants were tuned.
- No new features or dependencies. Full Android compilation still needs GitHub Actions validation.


## Stage 4 — 拆分陌陌弹窗识别（2026-11-07）

- 新增 `app/src/main/java/com/freedom/accessibility/browse/MomoPopupInspector.kt`。
- 迁移弹窗候选节点扫描与评分、文本和描述收集、危险内容识别、明确关闭控件查找，以及 `PopupInspection` 节点所有权回收。
- `FreedomAccessibilityService` 保留既有弹窗调用入口为委托方法；`handleMomoPopup()` 仍然负责安全关闭决策、执行 ACTION_DISMISS/ACTION_CLICK、重试次数、验证超时和后续调度。
- 保留原有弹窗关键词集合、节点扫描上限 500、候选上限 12、子树扫描上限 180、文本最多 24 条/每条 100 字、候选评分与屏幕几何阈值。
- 关闭控件的可点击祖先仍使用 Service 原有 `findClickableAncestor()` 约束；不改变可点击目标范围。
- 未修改陌陌页面导航、卡片过滤、自动浏览状态机、点击/返回/滑动顺序和计时参数。
- 本次完成静态结构/引用核对；容器未安装 Android SDK/Gradle，未执行 `assembleDebug`，需以 GitHub Actions 和真机测试为准。


## Stage 5 — 动态列表手势规划拆分（2026-11-07）

- 新增 `accessibility/browse/MomoFeedGestureController.kt`，集中管理动态列表手势目标定位、滚动列表容器查找、可见节点快照、手势参数读取和滑动路径规划。
- `FreedomAccessibilityService` 继续负责创建 `GestureDescription`、悬浮窗触摸穿透、`dispatchGesture()`、系统回调、暂停/重试和自动浏览阶段转换。
- 保留原有列表/输入框屏蔽阈值、手势时长范围、曲线偏移范围、32/100 个路径采样点、微扰频率和幅度、快照节点/文本上限及偏好设置键。
- 原有 Service 调用点通过薄代理转发，避免改动状态机中的调用顺序。


## Stage 6 — 拆分陌陌导航动作边界（2026-11-07）

- 新增 `app/src/main/java/com/freedom/accessibility/browse/MomoNavigationController.kt`，迁移既有的导航标签节点查找、可点击祖先校验、低层 `ACTION_CLICK` 和系统全局 Back 动作入口。
- `FreedomAccessibilityService` 保留 `submitNavigationClick()` / `submitNavigationBack()` 的业务协调：导航尝试计数、页面动作前快照、人工事件抑制窗口、超时、阶段切换、日志、验证轮询和重试均未迁移。
- `verifyNavigationAction()`、`beginDynamicNavigationRecovery()`、`handleMomoActivityTransition()` 保持在 Service；页面证据仍由原 `MomoPageInspector` 提供。
- 弹窗关闭仍由 `handleMomoPopup()` 决定和执行；Inspector 使用 Service 薄代理访问同一可点击祖先查找规则。
- 详情页返回动作复用控制器的底层 Back 回调；原有返回失败处理、详情返回阶段切换和列表位置验证保持在 Service。
- 保留节点扫描上限、标签中心偏差、可点击祖先宽高约束/深度、异常处理与节点回收语义。
- 未修改导航重试次数、超时/轮询/延迟参数、Activity/包名标识、页面验证条件或自动浏览状态机。
- 已完成静态引用与差异核对；完整 Android Gradle 编译及真机验证仍需 GitHub Actions / 设备确认。


## Stage 7 — AccountBrowseController 初步拆分（2026-11-07）

- 新增 `app/src/main/java/com/freedom/accessibility/browse/AccountBrowseController.kt`。
- 将当前 `AccountBrowsePhase` 所有权迁入 controller；Service 中原 `accountBrowsePhase` 变量改为同名 getter/setter 代理，因此原有阶段读取和赋值点不需要改写。
- 将 `performAccountBrowseStep()` 的运行/模式门控与阶段分派搬入 controller；Service 保留薄代理，原 Handler 调度入口、回调顺序和每个阶段处理方法不变。
- Controller 的 `Actions` 仅回调到 Service 既有的阶段处理函数。页面检查、弹窗、安全关闭、导航验证与重试、手势派发、暂停/恢复副作用、各类截止时间和会话证据继续由 Service 或既有专职组件管理。
- 保留原门控次序及全部 13 个阶段分支，不更改阶段枚举、阶段转换赋值、延迟、重试次数、扫描阈值或手势参数。
- 本次不新增功能、依赖、权限、广播或持久化键。完整 Android 构建与真机验证仍需通过 GitHub Actions。


## Stage 8 — 自动浏览会话状态归属整理（2026-11-07）

- 新增 `app/src/main/java/com/freedom/accessibility/browse/AccountBrowseSessionState.kt`。
- 将原 `FreedomAccessibilityService` 内 53 个自动浏览会话字段迁入 `AccountBrowseController.session`，涵盖暂停原因与恢复上下文、页面/Activity 指纹、稳定采样与列表返回验证、人工触摸接管、手势与滚动事件、详情停留、导航尝试上下文、弹窗关闭验证状态。
- 原字段默认值和类型保持一致；Service 的所有状态读写改为访问 `accountBrowseSession`。未改变原赋值/递增/清零顺序、条件、调度或日志文字含义。
- `accountBrowsePhase` 和阶段分派仍归 `AccountBrowseController`；各类系统事件、手势执行、延时、导航重试、弹窗处理和 reset 副作用仍由 Service 及既有专职组件执行。
- 将上传基线中漏掉的 `AccountBrowseController` import 一并补齐；同时添加 `AccountBrowseSessionState` import。
- 无新功能、权限、广播、持久化键、依赖或时间/阈值变更。完整 Android 编译及真机测试仍需 GitHub Actions 和设备验证。


## Stage 9 — 自动浏览暂停、恢复与会话生命周期协调（2026-11-07）

- 基线：本次上传的 `Freedom(10).zip`，仓库 HEAD `f5203d9`。
- `AccountBrowseController` 新增统一的会话 reset、pause、暂停期间人工接管快照、手动静默恢复以及导航恢复准备入口。
- `AccountBrowseSessionState.reset()` 按原 Service reset 方法的顺序清理 52 个显式重置字段；悬浮窗触摸穿透标记仍由原 WindowManager 恢复方法清除，若恢复失败则保留原标记。
- Service 保留平台副作用和时序边界：手势轨迹清理、悬浮窗状态恢复、Scheduler/Handler 回调取消、倒计时 UI 隐藏、日志和下一步调度通过原有调用点/回调执行。
- 手动恢复的分支判断顺序、暂停上下文读取、字段清理顺序、进入 `WAIT_FOR_DYNAMIC_TAB`、deadline 计算和轮询调度保持原顺序。
- 页面状态不确定时的恢复仍仅在没有暂停上下文时创建上下文；先执行原悬浮窗与调度清理，再重置导航状态，进入主动导航阶段并安排原轮询。
- 两处“已经处于 PAUSED 时记录人工接管上下文”的重复代码改为调用 Controller 的同一方法；保留第一次上下文快照不被覆盖的规则。
- 未更改状态枚举、各阶段处理动作、导航/弹窗重试参数、手势参数、延时常量、广播、持久化键或权限；无新功能和第三方依赖。
- 本地完成 reset 字段顺序与原 Service 逐项对照、Controller 独立 Kotlin 编译/行为顺序检查、引用和差异检查；当前环境没有 Android SDK/Gradle，未执行完整 `assembleDebug` 或真机回归，仍须通过 GitHub Actions 验证。


## Runtime performance — batched logs, coalesced overlay updates and selective scanning

- `MonitorStore.addLog()` now appends to a bounded 300-entry in-memory deque. A dedicated background `HandlerThread` persists a snapshot of the latest entries in batches (350ms delay) to the app-private `freedom_event_logs.log` file. Existing SharedPreferences JSON logs are migrated once; log clear and lifecycle flush paths are retained. Settings and small configuration values remain in SharedPreferences; no Room/database dependency was introduced.
- `MonitorOverlay.update()` coalesces concurrent refresh requests and caps rendering at approximately 5Hz (200ms minimum interval). `FreedomAccessibilityService.refreshViews()` similarly coalesces refresh broadcasts. Unchanged TextView text is not reassigned.
- Added `AccessibilityEventPolicy` to separate ordinary events, page metadata changes and structural page changes. Ordinary events continue to be logged and sent through the existing manual-interaction fallback but do not schedule full BFS scans.
- Window-state/window-list changes and structural subtree/pane changes can request scanning. Text-only metadata changes update the app/page hint without a full scan; click, text-input and scroll events do not independently request a tree traversal.
- `requestNodeScan()` debounces structural requests by 350ms and enforces a 900ms minimum interval between scan starts.
- `MonitorStore.updateAppPage()` writes only changed app/page values. `saveSnapshot()` compares the complete persisted snapshot and skips identical data; the scanner only logs and refreshes the overlay after a changed snapshot.
- The BFS implementation, `MAX_NODES_PER_SCAN` (3000), `MAX_DISPLAY_NODES` (140), target-window root selection and AccessibilityNodeInfo recycling remain unchanged.
- No Room, new Gradle dependency, permission, broadcast action or settings key was added.
