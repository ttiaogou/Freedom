# Freedom — staged structure refactor

基线：本地上传的完整 Freedom 项目源码（`main` 分支源码包）。

## 本阶段实际拆分

- `accessibility/browse/AccountBrowseModels.kt`：自动浏览状态枚举及页面、导航、弹窗、手势数据模型。
- `accessibility/scheduling/AccountBrowseScheduler.kt`：自动浏览状态机的单待执行步骤调度器。
- `accessibility/gesture/GestureTuning.kt`：原有设备手势校准数据计算和本机参数存储。
- `accessibility/event/AccessibilityEventFormatter.kt`：AccessibilityEvent 名称、用户可读事件描述和事件标签。
- `accessibility/scan/AccessibilityTreeScanner.kt`：活动目标窗口根节点查找、BFS 节点扫描、快照保存和控件中文名称。
- `ui/config/ConfigPanel.kt`：现有设置面板及其行布局；需要与 Activity 交互的动作通过回调回到 Activity。
- `ui/config/TargetAppEditor.kt`：目标应用包名编辑和保存流程。
- `ui/calibration/GestureCalibrationUiController.kt`：校准对话框、校准广播接收器、采样计时和本地 MotionEvent 采集。
- `ui/logs/LogTextFormatter.kt`、`LogRecordsDialog.kt`：日志文本兼容格式化和运行记录对话框。

## 调度关系核对

自动浏览的唯一主流程调度仍然由 Service 的主线程 Handler 驱动。抽取后的 `AccountBrowseScheduler` 保留原语义：

1. 入队前检查监听运行状态、自动浏览模式、触摸状态、`IDLE`/`PAUSED` 阶段。
2. 若已经有待执行步骤，不覆盖、不重置已有步骤。
3. 入队后不在回调前额外重新检查条件；回调开始时先清空 pending，再调用原 `performAccountBrowseStep()`。
4. 原先取消 `accountBrowseRunnable` 并清除 pending 的位置，改为调用同一个 scheduler 的 `cancel()`。
5. 手动触摸静默恢复、悬浮窗触摸穿透 watchdog、首次滑动倒计时和手势校准仍使用各自独立 Runnable，不合并到主调度器。

页面识别、导航重试、滑动派发与完成回调、详情停留、图片浏览返回、人工接管与恢复状态转换均保留在 `FreedomAccessibilityService`，本阶段只抽取无副作用的类型/格式化及职责边界明确的扫描器，避免改变调度流程。

## 明确未做

- 没有更换 Android View UI 技术。
- 没有引入新的第三方依赖或 Gradle Module。
- 没有修改配置键、Intent Action、目标包名、页面识别规则、手势参数、点击规则、延时时间和用户交互步骤。
- 没有主动改变日志内容、节点扫描上限或节点快照格式。

## 验证限制

本地容器没有 Android SDK、Gradle 可执行程序和 Gradle Wrapper，因此无法在此执行 `assembleDebug`。已执行源码结构/引用检查；最终 Android 编译和设备端行为仍需要 GitHub Actions 与真机验证。


## Stage 2 — 设备手势校准执行组件

新增 `accessibility/gesture/GestureCalibrationController.kt`，只接管设备内部手势校准会话：

- 持有校准进行状态、当前测试组序号、校准区域和专属 Runnable。
- 接收原有开始/取消广播；校准进度、成功、失败广播的 Action、Extra 与目标包名保持不变。
- 检查 Freedom 是否为当前前台窗口，并验证校准区域仍位于屏幕范围内。
- 保留原来的四组测试手势、350ms 初始等待、700ms 单次手势时长、350ms 组间等待和最后 250ms 完成等待。
- 继续使用原来的贝塞尔路径控制点比例、`dispatchGesture` 回调和主线程 Handler。
- 仍通过原有日志入口记录开始、取消、失败和完成信息。

`FreedomAccessibilityService` 继续负责 Android 无障碍服务生命周期，以及校准广播接收器的注册/注销；控制器通过 Service 执行平台手势派发和发送应用内广播。自动浏览的手势状态、调度器、页面识别与操作流程没有迁入该控制器。

本阶段没有新增权限、Manifest 声明、持久化键、Intent Action、第三方依赖或用户可见功能。

静态检查已确认旧校准状态字段/方法不再由 Service 持有，接收器在连接与销毁时仍由 Service 配对注册/注销；新控制器已使用 Kotlin 编译器和最小 Android API stub 做独立语法/类型形状检查。当前环境没有 Android SDK/Gradle，完整 `assembleDebug` 与真机校准仍需通过 GitHub Actions 和设备确认。


## Stage 3 — Momo page inspection extraction (2026-11-07)

- Extracted the existing Home bottom-navigation evidence, Dynamic-tab row inspection, feed-readiness check, visible feed-container lookup, and page fingerprint into `accessibility/browse/MomoPageInspector.kt`.
- The inspector returns the existing `HomeBottomNavEvidence`, `DynamicTabInspection`, `FeedReadinessInspection`, and `PageFingerprint` data types. Its thresholds, labels, scan cap, bounds calculations, child counting, and node recycling are supplied from the same existing service constants and retain their prior values.
- `FreedomAccessibilityService` keeps its existing private entry points as thin delegates so existing state-machine call sites and action ordering are unchanged.
- The detail-page identity check also lives in the inspector; the service passes its current/expected Activity values because those belong to the live navigation session. Navigation click-target lookup, popup handling, gestures, and all state transitions remain in the service.
- `MomoPageInspector` is read-only: it does not click, scroll, navigate, schedule callbacks, or alter account-browse state.
- No UI behavior, timing, gesture parameters, package filters, or third-party dependencies were changed.


## Stage 4 — Momo popup inspection extraction

- 新增 `accessibility/browse/MomoPopupInspector.kt`，迁移陌陌弹窗候选扫描、容器评分、弹窗文本收集、危险内容识别、明确关闭控件查找和识别结果节点回收。
- `MomoPopupInspector` 只返回既有 `PopupInspection` 识别证据；不执行 `ACTION_DISMISS` / `ACTION_CLICK`，不安排重试，不修改自动浏览阶段。
- `FreedomAccessibilityService.handleMomoPopup()` 继续独占弹窗安全决策、关闭动作、重试次数、900ms 重试间隔、350ms 验证轮询、2500ms 关闭验证超时和失败等待。
- 原有弹窗候选扫描上限、候选数量、节点文本数量/长度上限、识别分数、几何范围、关键词集合和节点回收顺序保持不变。可点击祖先查找仍调用 Service 的既有辅助函数，避免改变点击目标尺寸与深度约束。
- 页面导航、状态机、卡片过滤、点击、返回和手势执行均未迁入或改写。
- 本阶段无新权限、广播、持久化键、依赖或用户可见功能。完整 Android 构建仍需 GitHub Actions 验证。


## Stage 5 — Feed gesture planning

`MomoFeedGestureController` owns feed-list target discovery, scrollable-container selection, bounded visible-node snapshots, persisted gesture-profile loading, and path construction. It returns `FeedGestureTarget`, `ScrollGestureProfile`, and `ScrollGesturePlan` data only. It does not create `GestureDescription`, call `dispatchGesture`, toggle overlay touch passthrough, schedule work, or mutate browse phases. `FreedomAccessibilityService` retains the actual gesture dispatch and all completion/cancellation/recovery behavior.


## Stage 6 — Navigation action boundary extraction

- Added `accessibility/browse/MomoNavigationController.kt` for the existing bounded label-to-clickable-node lookup, clickable-ancestor validation, `ACTION_CLICK` submission/node release, and the low-level global Back callback.
- Kept `submitNavigationClick()` and `submitNavigationBack()` in `FreedomAccessibilityService`; attempt counters, before-page snapshots, event suppression windows, verification deadlines, logs, retry delays, stop conditions, and `VERIFY_NAVIGATION_ACTION` transitions remain service-owned.
- Kept `verifyNavigationAction()`, `beginDynamicNavigationRecovery()`, and `handleMomoActivityTransition()` in the service. Their evidence collection and page-state decisions remain in the service and `MomoPageInspector`.
- The popup inspector continues to use the same clickable-ancestor bounds/depth rule through a thin service delegate. Popup dismissal actions remain in `handleMomoPopup()`.
- The detail-page Back action uses the same low-level controller callback; its original direct Boolean handling and subsequent list-return transition remain in place.
- Preserved scan limit, target-center tolerance, maximum clickable-ancestor width/height, ancestor depth, action exception handling, and node ownership/recycling.
- No navigation retries, timing values, labels, package/activity identifiers, page verification rules, or automation state-machine behavior were intentionally changed.
- Full Android `assembleDebug` and device validation remain required through GitHub Actions.


## Stage 7 — 自动浏览阶段协调器初步拆分

- 新增 `accessibility/browse/AccountBrowseController.kt`。
- 将自动浏览当前阶段的存储迁入 controller；`FreedomAccessibilityService.accountBrowsePhase` 保留同名读写代理，现有业务判断与阶段赋值位置不变。
- 将原 `performAccountBrowseStep()` 的运行条件门控和 `when (phase)` 分派迁入 controller。Service 仍保留原方法作为兼容入口，调度器的回调关系不变。
- 阶段动作通过显式 `Actions` 回调回到 Service；节点访问、无障碍事件处理、页面识别、弹窗关闭、导航重试、手势派发、计时器及日志仍由 Service/既有组件负责。
- 保留执行门控顺序：监控或自动浏览模式关闭时先重置；触摸期间或已暂停时不执行；随后按当前阶段分派。
- 本阶段只建立控制器对当前阶段和阶段分派的所有权，不迁移会话其余字段，也不重写阶段转换、暂停恢复和任何阶段动作。
- 不新增权限、广播、持久化键、第三方依赖或用户功能。完整 Android `assembleDebug` 和真机行为仍需 GitHub Actions / 设备验证。


## Stage 8 — 自动浏览会话状态归属整理

- 新增 `accessibility/browse/AccountBrowseSessionState.kt`，集中承载每次自动浏览会话的可变证据：暂停原因/恢复上下文、前后台 Activity 和页面指纹、列表稳定采样与返回验证、触摸接管、手势执行/滚动事件、详情停留、导航尝试上下文及弹窗关闭验证状态。
- `AccountBrowseController` 创建并持有唯一的 `session` 实例；`FreedomAccessibilityService` 通过该实例读取/更新原有会话数据，仍保留 Android 无障碍事件、系统手势、日志、计时和状态副作用的执行责任。
- 53 个原 Service 会话字段的默认值与原类型保持不变；原有字段访问只改为访问 `accountBrowseSession`，原有赋值语句、递增操作、重置顺序和判断条件不调整。
- `accountBrowsePhase` 继续由 `AccountBrowseController` 持有；阶段变换、`performAccountBrowseStep()` 门控和 `when` 分派未改写。
- 本阶段不改变页面指纹算法、暂停/恢复时序、手动触摸判定、手势参数、导航/弹窗重试参数或任何自动操作。未增加持久化、广播、权限或第三方依赖。
- 基线 ZIP 带有上一轮 Stage 7 遗漏的 `AccountBrowseController` import；本次一并补齐该 import，避免将已确认的编译错误继续带入后续版本。
- 当前环境没有 Android SDK/Gradle，完整 Android `assembleDebug` 与真机回归仍须由 GitHub Actions 和设备完成。
