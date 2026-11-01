# 天梦安全护盾（Tianmeng Security Shield）

一款面向 Android 的**锁机病毒应急防护工具**：通过摇一摇 / 音量键触发紧急模式，悬浮窗全屏确认，配合 Root 或 Shizuku 一键卸载恶意应用；同时提供设备管理器保护、后台保活、智能拦截、防闪、一键静音、应用冻结与灵动岛自动点击器。

- 包名：`com.tianmeng.security`
- 支持系统：Android 7.0（API 24）及以上
- 当前版本：v1.4.2（versionCode 41）
- 许可证：[MIT](LICENSE)

---

## 功能模块

| 模块 | 说明 |
| --- | --- |
| 紧急模式 | 摇一摇 / 音量键触发，全屏不透明悬浮窗确认，倒计时后进入卸载页 |
| 全屏卸载页 | 覆盖状态栏、导航栏与刘海区，显示期间持续压制其他悬浮窗应用 |
| 智能拦截 | 结合设备管理器、悬浮窗、无障碍、侧载、开机自启、相机等特征评分，命中后自动卸载 |
| 病毒扫描 | 手动全量扫描 + 每 5 分钟后台巡检 + 安装 / 前台切换触发 |
| 设备管理器 | 激活后无法被直接卸载，降低被系统清理的概率（需 Root 授权才可激活） |
| 防冻结 / 保活 | 前台服务、通知、JobScheduler、开机自启、看门狗重启 |
| 防闪保护 | 监听手电筒，锁屏 / 息屏或可疑应用快速开关时强制关闭 |
| 一键静音 | 关闭全部音频流并持续压制，防止锁机病毒播放警报 |
| 应用冻结 | 批量冻结 / 解冻第三方应用 |
| 灵动岛点击器 | 多点位循环点击，可调间隔、按下时长、点击上限、随机偏移 |
| 内置终端 | 直接执行 Shell 命令，便于排查 |
| 意见反馈 | 应用内提交反馈 |

## 权限要求

**无 Root（Shizuku 方案）**

- Shizuku 服务已启动并授权本应用
- 无障碍服务
- 悬浮窗权限
- 通知权限（Android 13+）
- 使用情况访问（识别前台锁机应用，可选但强烈建议）

**有 Root**

- Root 授权
- 无障碍服务
- 悬浮窗权限
- 通知权限（Android 13+）

> 权限未全部开启时，智能拦截不会自动卸载，仅做提示与手动清理。

## 构建

### 环境

- JDK 17
- Android SDK（compileSdk 34、build-tools 34）
- Gradle 8.x（项目使用 AGP 8.2.2 / Kotlin 1.9.24）

### 命令

```bash
git clone <repository-url>
cd TianmengSecurityShield
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleDebug
```

APK 输出路径：`app/build/outputs/apk/debug/app-debug.apk`

### 发布签名

为避免泄露签名材料，仓库**不包含** keystore 和密码。发布构建时在 `local.properties`（已被 `.gitignore` 排除）或环境变量中提供：

```properties
RELEASE_STORE_FILE=/absolute/path/to/release.jks
RELEASE_STORE_PASSWORD=******
RELEASE_KEY_ALIAS=******
RELEASE_KEY_PASSWORD=******
```

四项齐全且 keystore 存在时，`release` 构建会自动签名；否则输出未签名包，可直接用 Android Studio 或 `apksigner` 自行签名。

## 目录结构

```
app/src/main/java/com/tianmeng/security/
├── App.kt                     # Application、通知渠道
├── MainActivity.kt            # 主界面与权限状态
├── manager/                   # 核心逻辑
│   ├── VirusManager.kt        # 病毒特征分析与卸载
│   ├── SmartBlockManager.kt   # 智能拦截调度
│   ├── EmergencyEngine.kt     # 紧急模式清理
│   ├── EmergencyOverlay.kt    # 全屏紧急确认悬浮窗
│   ├── ShellManager.kt        # Root / Shizuku 命令执行
│   ├── KeepAliveManager.kt    # 保活
│   ├── FreezeManager.kt       # 冻结 / 解冻
│   ├── AntiFlashManager.kt    # 防闪
│   ├── MuteManager.kt         # 一键静音
│   └── AutoClicker.kt         # 自动点击
├── service/                   # 前台服务与无障碍服务
├── receiver/                  # 开机、安装、重启广播
├── ui/                        # 卸载页、病毒拦截页、冻结列表等
└── util/                      # 全屏工具、应用信息、崩溃日志
```

## 已知限制

- **无法保证 100% 拦截**：未知的新型锁机手法、系统级 / 无障碍级恶意行为可能存在绕过。
- **无 Root / Shizuku 无法静默卸载**：只能跳转系统卸载界面，由用户确认。
- **悬浮窗层级**：其他应用的 `TYPE_APPLICATION_OVERLAY` 与普通 Activity 处于同一层级，Android 不保证护盾永远置顶；本项目通过不透明全屏、持续强制停止可疑悬浮窗应用来最大化覆盖。
- **部分 ROM 限制**：MIUI / HyperOS / ColorOS 等需要在系统设置中允许自启动、后台运行、锁定后台，否则保活效果下降。

## 安全与免责声明

本项目会申请设备管理器、无障碍、悬浮窗、使用情况访问、相机等高敏感权限，仅用于本机病毒防护与应急清理，**不收集、不上传任何用户数据**（反馈功能仅在用户主动提交时使用网络）。

请仅在**你自己的设备**上安装使用；卸载前请先在应用内取消设备管理器。因误操作、误删或系统差异造成的任何损失，作者不承担责任。详见 [LICENSE](LICENSE)。

## 贡献

欢迎提交 Issue 与 Pull Request。提交前请确保：

1. 不包含任何签名密钥、密码、`local.properties`；
2. 新增检测规则时优先降低误伤，系统应用与主流商店安装的应用不得被自动卸载；
3. 保持 `minSdk 24` 兼容。
