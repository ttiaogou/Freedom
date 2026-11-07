# Freedom

Freedom 是一个基于 Android AccessibilityService 的原生 Kotlin 应用。当前项目包含无障碍事件监控、节点树扫描、本地运行日志、可调整悬浮监控面板、手势轨迹可视化、设备内手势校准，以及针对陌陌动态页的自动浏览适配。

## 项目结构

```text
app/src/main/java/com/freedom/
├── MainActivity.kt                 # 主界面、运行启停、权限与系统文档导出入口
├── accessibility/
│   ├── FreedomAccessibilityService.kt  # Android 无障碍生命周期、事件入口和自动浏览状态机
│   ├── browse/                     # 陌陌页面/弹窗识别、卡片筛选、手势规划与导航动作（MomoPageInspector、MomoPopupInspector、MomoFeedGestureController、MomoNavigationController）
│   ├── event/                      # 无障碍事件描述格式化
│   ├── gesture/                    # 手势参数、设备采样计算与校准执行控制器
│   ├── scan/                        # 无障碍节点树扫描和快照
│   └── scheduling/                  # 自动浏览主流程单步调度
├── data/                            # 广播 Action 和本地设置/日志/快照存储
├── overlay/                         # 实时监控悬浮窗和手势轨迹悬浮层
├── stability/                       # 前台服务和开机授权检查
└── ui/
    ├── calibration/                 # 手势校准界面与 UI 生命周期
    ├── config/                      # 设置面板和监控目标编辑
    └── logs/                        # 运行记录展示与文本格式化
```

`FreedomAccessibilityService` 仍然持有自动浏览状态机、导航重试策略和页面结果验证；`MomoNavigationController` 只负责导航目标查找及低层无障碍点击/返回动作，不持有重试计数或阶段状态。其他拆出的页面识别、弹窗识别和手势规划组件也只返回识别/规划结果，不接管状态机。

## 构建

项目使用 JDK 17、Android Gradle Plugin 8.7.3、Kotlin 2.0.21、compileSdk 35。可使用 Android Studio 打开项目，或在配置好 Android SDK 的环境中运行：

```bash
gradle --no-daemon clean assembleDebug
```

GitHub Actions 工作流也会安装 Gradle 8.9 和 Android 35 SDK 后构建 Debug APK。

## 权限说明

Freedom 需要用户主动开启无障碍服务和悬浮窗权限。前台伴随服务仅用于用户明确启动的监控会话。开机接收器只检查授权状态并提示用户，不会直接在开机广播中启动监控。

自动浏览目前仅实现陌陌动态页面适配；修改监控目标包名不会自动使其他应用获得相同的页面识别或自动浏览能力。

## 本次结构整理

参阅 [ARCHITECTURE.md](ARCHITECTURE.md) 和 [SOURCE-CHANGES.md](SOURCE-CHANGES.md)。目前完成第一阶段职责拆分、第二阶段设备手势校准执行拆分、第三阶段陌陌页面识别拆分、第四阶段陌陌弹窗识别拆分、第五阶段动态列表手势规划拆分和第六阶段导航动作边界拆分；均不新增自动化功能。Android 编译和真机验证仍应通过 GitHub Actions 与实际设备完成。


第五阶段新增 `accessibility/browse/MomoFeedGestureController.kt`，负责动态列表目标发现、可见节点快照、手势参数读取和滑动路径规划；真实手势派发及自动浏览状态机仍由无障碍服务负责。
