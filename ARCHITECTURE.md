# Freedom — Stage 1 structure refactor

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
