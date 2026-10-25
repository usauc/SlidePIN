# SlidePIN — 锁屏数字密码「滑动输入」

给 Android 数字密码锁屏加上 Flyme 风格的滑动输入：手指依次划过密码的各位数字，一笔完成输入，不用逐个点按。

- 支持 Android 13（AOSP Keyguard 类名，HyperOS / MIUI 移植包实测可用）
- 形态：LSPosed 模块（Zygisk 版），只 Hook `com.android.systemui`
- 无界面、无后台服务、不联网、不申请任何权限

## 效果

```
密码 2580
手指从 2 → 5 → 8 → 0 连续划过，密码框依次填入 4 个圆点
```

## 原理

`KeyguardPINView` 没有声明 `dispatchTouchEvent`，所以 Hook 点放在 `ViewGroup.dispatchTouchEvent`，再用实例类型过滤：

1. `ACTION_DOWN` 只记录起点，**不接管**，普通点按完全走原生逻辑
2. 移动距离超过 30px 判定为滑动，开始接管手势
3. 每次进入一个新的数字键，向上找到可点击的容器（本 ROM 为 `NumPadKey`）并 `performClick()`
4. 判定滑动时给首键补发 `ACTION_CANCEL`，避免按压态残留

关键取舍：**普通点击永不被拦截**。即使模块逻辑失效，你仍然可以正常点按输入密码。

## 安装

1. 安装 `SlidePIN.apk`
2. LSPosed 管理器 → 模块 → 启用 **SlidePIN**
3. 点进模块，作用域勾选「**系统界面**」（`com.android.systemui`）
4. 重启 SystemUI 或重启手机

模块没有桌面图标，这是 Xposed 模块的正常形态。

## 构建

依赖：JDK 17+、Android SDK build-tools 34、`platforms/android-33/android.jar`

```bash
export JDK_HOME=/path/to/jdk-17
export BUILD_TOOLS=/path/to/build-tools/34.0.0
export ANDROID_JAR=/path/to/platforms/android-33/android.jar
./build.sh
```

不设置环境变量时，脚本会尝试仓库同级的 `toolchain/` 目录和常见 SDK 路径。

### 签名（发布用）

仓库不含任何密钥文件（`.gitignore` 已排除 `*.keystore`）。发布版请自备密钥：

```bash
export SLIDEPIN_KEYSTORE=/path/to/release.keystore
export SLIDEPIN_STOREPASS='store 密码'
export SLIDEPIN_KEYPASS='key 密码'    # PKCS12 通常与 store 密码相同
export SLIDEPIN_ALIAS=slidepin
./build.sh
```

不设置 `SLIDEPIN_KEYSTORE` 时会自动生成 debug 密钥，仅用于本地测试。

### 官方发布签名

从 v1.5 起的公开版本使用以下证书签名，可用它核对下载到的 APK：

```
Subject : CN=SlidePIN, OU=Open Source, O=SlidePIN, C=CN
SHA-256 : 2A:72:C4:98:8C:73:8A:1F:A1:01:57:8B:35:11:6D:6B:6A:FA:21:DD:CA:65:D8:7F:A8:17:E7:9C:A2:85:43:92
SHA-1   : 9D:E0:00:BF:B3:B1:52:F1:79:82:89:94:C3:4D:3F:56:41:4A:2E:D5
```

```bash
apksigner verify --print-certs SlidePIN.apk
```

### 关于 stubs/

`stubs/` 是 Xposed API 的**编译期占位**，只为让 `javac` 找到 `XposedBridge` / `XC_MethodHook` 等符号。它们不会被打进 APK（`build.sh` 只对 `build/com/` 下的 class 做 dex），运行时由 LSPosed 提供真实实现。这样仓库不依赖已停止分发的 `api-82.jar`，也不需要联网拉 Maven 依赖。

## 已知限制

- 数字键必须是 `TextView`（文字为单个 0-9）；完全自绘的拨号盘不适用
- 不做自动提交：滑完需要按一次确认键。自动提交会增加误触导致的密码错误次数
- 不同 ROM 的锁屏类名可能不同，需要改 `PIN_VIEW` 常量并重新验证
- 只在 Android 13 的 HyperOS 移植包上实测过，其他版本未验证

## 适配其他 ROM

模块在首次命中 PIN 视图时会打印整棵控件树，用它判断需要改什么：

```bash
adb logcat | grep "\[SlidePin\]"
```

- 类名不同 → 改 `SlidePinHook.PIN_VIEW`
- 数字不是 `TextView` → 改 `isDigitKey()`
- 点击不生效 → 检查 `clickableAncestor()` 是否找到了正确的容器

## 安全说明

- 模块只 Hook SystemUI 进程内的方法，不修改系统分区、不注入其他应用
- 卸载 APK 或关闭模块开关即完全失效
- 锁屏属于安全敏感界面，修改前请确保有指纹/备用解锁方式，避免被锁在门外

## 许可证

MIT，见 [LICENSE](LICENSE)。
