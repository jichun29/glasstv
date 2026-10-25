# 电视去广告库（debloat）

收录 **174 个电视/盒子/投影机型**的「建议停用包清单」，来自上游社区实测汇总。每条清单注明了这个包是干什么的、停用后会失去什么功能。**只停用、不卸载**，任何时候都能一键还原。

## 这是什么

电视厂商出厂时会预装一堆遥测上报、内容推荐流、赞助广告位、零售演示模式之类的系统 App。它们没有图标、卸载不掉，但会：

- 开机时抢资源（开机变慢）
- 在桌面/待机屏推广告内容
- 后台上传使用数据

ADB 的 `pm disable-user` 可以把它们**停用**（不是删除，系统更新后也不会自动复活，随时可以恢复）。

## 覆盖品牌（53 个品牌，174 台机型）

详细索引见 [brands.md](brands.md)。常见的：

| 品牌 | 台数 | 例子 |
|---|---|---|
| TCL | 43 | 32L5A ~ QM6K Pro 2025，含中国特供 MT9655 / Q10G Pro |
| 小米 / 红米 | 26 | Mi TV 4A / 4C Pro、Mi Box S、TV A Pro 2026 |
| 海信 | 8 | E5Q Pro、U75、A4 |
| 索尼 | 6 | BRAVIA 系列 |
| 其他 | 90+ | 创维代工飞利浦、长虹 CHiQ、海尔、康佳、一加 TV、极米/坚果投影、NVIDIA Shield、Chromecast、Fire TV 全家 |

找不到你的机型：挑**同品牌、同芯片平台（Amlogic/MediaTek/Realtek）、Android 版本最接近**的那份清单先用。每份清单只停用包、不卸载，禁错了用还原脚本一键恢复，没有风险。

## 怎么用

前提：电视开启「ADB 调试 / 网络调试」（一般在 设置 → 关于 → 连点版本号 7 次开开发者选项，再开 USB/网络调试），电脑装好 adb。

```bash
# 1. 找到你的机型清单，先读一遍（每条都有注释说明这个包是干什么的）
cat examples/xiaomi-mitv-axso0/debloat.txt

# 2. 禁用（只停用，不卸载）
TV_HOST=192.168.x.x tools/disable.sh examples/xiaomi-mitv-axso0/debloat.txt

# 3. 哪天想还原
TV_HOST=192.168.x.x tools/restore.sh examples/xiaomi-mitv-axso0/debloat.txt
```

清单里不存在的包会自动跳过，不会报错。

## 给 AI 机器人的指令词

把下面这段话直接复制粘贴给任意 AI 机器人（连同本仓库地址），它就能帮你完成整个去广告流程：

> 请克隆 https://github.com/jichun29/glasstv 仓库，查看 debloat/ 目录。我的电视型号是【在这里填你的型号，比如「小米 Mi TV 4A」或「TCL 55C725」】，电视 IP 是【填电视 IP】。请：① 在 debloat/examples/ 里找到与我的机型最匹配的 debloat.txt 清单（没有精确匹配时选同品牌、同芯片平台、Android 版本最接近的）；② 逐条向我解释清单里每个包的作用和停用后的影响；③ 经我确认后，用 debloat/tools/disable.sh 通过 ADB 执行停用；④ 停用后逐项验证包状态（pm list packages -d），最后告诉我重启电视确认效果。注意：只停用、绝不卸载（pm uninstall）；如果遇到 setupwraith 之类与首次开机向导/Wi-Fi 保存相关的包，先警告我再决定。

Windows 用户把指令词里的 `tools/disable.sh` 换成等价的 adb 命令逐条执行即可（脚本内容很简单，AI 看得懂）。

## 数据来源与免责

清单整理自上游 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv) 的 `examples/` 目录（社区贡献，上游标记为 unverified——即「社区报告、未逐机复验」）。每份清单顶部都注明了来源固件版本和注意事项。停用系统组件存在一定风险，执行前请读一遍清单注释；所有操作均可还原。
