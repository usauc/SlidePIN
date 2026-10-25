# SlidePIN
Flyme 风格的锁屏数字密码滑动输入 —— LSPosed 模块
SlidePIN v1.5
给 Android 数字密码锁屏加上 Flyme 风格的滑动输入：手指依次划过密码的各位数字，一笔输入完成。
功能
- 数字 PIN 键盘支持滑动输入（密码 2580 → 从 2 划到 5、8、0）
- 普通点按完全走原生逻辑，模块失效也不影响正常解锁
- 无界面、无后台、不联网、不申请权限，只 Hook SystemUI 进程
修复
- 修正 Hook 点：KeyguardPINView 未声明 dispatchTouchEvent，改挂 ViewGroup.dispatchTouchEvent 并以实例类型过滤
- 修正点击目标：数字文本在 NumPadKey 容器内，改为向上寻找可点击祖先再派发
- 修正首键按压态残留：判定滑动时补发 ACTION_CANCEL
安装
1. 安装 SlidePIN-v1.5.apk
2. LSPosed → 模块 → 启用 SlidePIN
3. 作用域勾选「系统界面」（com.android.systemui）
4. 重启 SystemUI 或重启手机
已知限制
- 数字键必须是 TextView；完全自绘的拨号盘不适用
- 不做自动提交，滑完需按一次确认键（避免误触导致的密码错误）
- 已在 Android 13 的 HyperOS 移植包实测；其他 ROM 可能需改 PIN_VIEW 常量
- 三星 One UI、鸿蒙等自研锁屏不适用
