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
