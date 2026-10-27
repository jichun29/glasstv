# 装 GlassTV 的指令词（给 AI 助手用）

把下面整段复制给你电脑上的 AI 助手（Claude Code / Codex / Trae / 豆包电脑版等）。
它需要能在这台电脑上执行命令，电脑和电视连同一个 Wi-Fi。

支持 Android TV / Google TV（小米、TCL、海信、索尼、创维、长虹、极米、Nvidia Shield 等）。
三星 Tizen、LG webOS、Fire TV 装不了，不用试。

开始前在电视上打开 ADB 调试：小米是「设置 → 账号与安全 → ADB 调试」（同一页把「安装未知来源的应用」也打开）；
原生 Android TV / Google TV 是「关于 → 连按版本号 7 次 → 开发者选项 → USB 调试」。
更全的品牌路径见 [docs/tv-adb-guide.md](docs/tv-adb-guide.md)。

---

## 复制这一段

```text
请帮我把 GlassTV 装到我的电视上，并设成默认桌面。仓库地址：https://github.com/jichun29/glasstv

硬性要求，请务必遵守：
1. 只下载 Release 里现成的 APK 安装，不要 clone 仓库、不要自己编译、不要自己写桌面、不要改包名、不要重新签名。
2. 只做三件事：装 APK、设为默认桌面、验证。不要停用/卸载电视上的任何系统应用（不要做 debloat）。
3. 每一步都在我的电脑上真的执行命令，不要只把步骤告诉我。需要我在电视上点确认时，提前告诉我。
4. 装完把验证命令的原始输出贴给我：版本号、当前默认桌面、按 Home 之后前台是哪个应用。
5. 哪一步失败就把完整报错贴给我，不要自己猜替代方案、不要换别的 APK 或别的桌面软件。

我的电视型号是：____
电视的 IP 是：____（不知道就用 adb 扫一遍局域网 5555 端口，或问我）

做法：
1. 电视上先打开 ADB 调试，然后 adb connect <电视IP>:5555，第一次会弹「允许 USB 调试」，我会点允许。
2. 查电视的 Android 版本：adb shell getprop ro.build.version.release
3. 按版本下载对应的包（两个包必须来自 Releases，不要用别处的 APK）：
   - Android 5.0 及以上：GlassTV-Launcher-universal.apk
   - Android 4.4：GlassTV-Launcher-lite44.apk
   下载地址（把文件名换掉即可）：
   https://github.com/jichun29/glasstv/releases/latest/download/GlassTV-Launcher-universal.apk
   如果 github.com 直连超时，换成 https://ghfast.top/<原来的完整地址> 再试。
   下完先核对文件大小和 Release 页面上写的一致，并且是 zip 格式（file 或 unzip -l 能打开）；
   大小不对或打不开就是没下完整，删掉重下，不要硬装。
4. 安装：adb install -r <下载的 apk>
   再用 adb shell dumpsys package com.glasstv.tvhome | grep -m1 versionName 确认装上的版本号。
5. 设为默认桌面：
   adb shell am start -W -n com.glasstv.tvhome/com.glasstv.tvhome.MainActivity
   adb shell cmd package set-home-activity --user 0 com.glasstv.tvhome/.MainActivity
6. 验证：adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME
   应该返回 com.glasstv.tvhome/...；然后让我按遥控器 Home 键，再跑
   adb shell dumpsys window | grep mCurrentFocus，确认前台是 com.glasstv.tvhome。

常见报错怎么读（照这个判断，不要瞎试）：
- INSTALL_FAILED_OLDER_SDK → 电视系统太老，换成 GlassTV-Launcher-lite44.apk（支持到 Android 4.4）。
- INSTALL_FAILED_UPDATE_INCOMPATIBLE → 电视上已经有一个同名包，但签名不同（多半是别处编的版本）。
  先 adb shell pm uninstall com.glasstv.tvhome，再装 Release 里的包。注意这会清掉桌面排序和壁纸设置。
- INSTALL_PARSE_FAILED_* → APK 文件不完整或被镜像换成了网页，重新下载，核对大小。
- INSTALL_FAILED_VERIFICATION_FAILURE → 电视固件禁止侧载：去电视设置里允许「安装未知来源的应用」，
  关掉安装校验后再试；还不行就是这台机器不允许第三方桌面，直接告诉我，不要继续折腾。
- Error: Activity class ... does not exist → 装的包名和 com.glasstv.tvhome 不一致，确认下的是本仓库的 Release。

不要做的事：
- 不要停用电视自带的桌面（保留它，随时能按 Home 切回去）。
- 不要为了「更干净」去停用系统应用或改私有 DNS。
- 不要装别的第三方桌面来替代。
```

---

## 装完之后（可选）：开桌面守护

小米等国行固件在待机时会强杀第三方桌面，醒来露出自带桌面。装好后可以用 adb 一次性开启守护
（这是无障碍服务，只能靠命令开，桌面里没有开关）：

```bash
adb shell settings put secure enabled_accessibility_services com.glasstv.tvhome/com.glasstv.tvhome.HomeGuardService
adb shell settings put secure accessibility_enabled 1
```

## 还原成原来的桌面

```bash
adb shell cmd package resolve-activity -c android.intent.category.HOME -a android.intent.action.MAIN
adb shell cmd package set-home-activity --user 0 <原桌面包名>/.<原桌面Activity>
adb shell pm uninstall com.glasstv.tvhome
```

## 想自己装（不用 AI）

见 [README 的「安装」一节](README.md#安装)：`adb install -r` 一行就够，不需要编译。
