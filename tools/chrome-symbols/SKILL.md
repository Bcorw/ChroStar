<!--
本文件是「Chrome 升版适配」技能的仓库归档副本（WorkBuddy skill:
chrome-lsposed-version-adapt）。放在这里是为了随代码一起版本化；
日常使用时由本机 ~/.workbuddy/skills/chrome-lsposed-version-adapt/SKILL.md 加载。
-->
---
name: chrome-lsposed-version-adapt
description: 让 LSPosed/Xposed 模块适配新的 Chrome for Android 版本。当 Chrome 大版本升级后模块「指纹不匹配 / 所有 hook 静默不装 / 功能全失效」，或需要为新的 Chrome 版本（如 158/159）重新标定 R8 短名符号表、排查模块导致的 Chrome 原生崩溃（SIGSEGV / tombstone）时使用。触发词：Chrome 升级后模块失效、Chrome 157 适配、Chrome 158 适配、fingerprint 不匹配、R8 短名、ChroStar 升版、LSPosed Chrome 模块。
---

# Chrome 升版适配（LSPosed 模块）

Chrome for Android 每个大版本 R8 都会重排短名与混淆方法名。模块一旦按旧名字 hook：

- 指纹判定失败 → 日志 `no ChromeNNN fingerprint matched` → **所有业务 hook 静默不装**
- 或者名字侥幸相同、但 native id 改了 → **Chrome 主线程 SIGSEGV**，几秒后闪回桌面

本技能是「重新标定一张符号表 + 验证到真机」的标准流程。配套脚本在
`ChroStar/tools/chrome-symbols/`（仓库内，`chrome_symbols.py`）。

## 0. 先确认现象（别急着改代码）

```bash
adb shell dumpsys package com.android.chrome | grep -E 'versionName|versionCode'
adb shell su -c 'LOG=$(ls -t /data/adb/lspd/log/modules*.log | head -1); grep "<模块 TAG>" $LOG | tail -20'
```

三种典型日志：

| 日志 | 含义 | 下一步 |
|---|---|---|
| `no Chrome156/157/158 fingerprint matched` | 短名变了 | 走第 1 步重标定 |
| `Chrome NNN fingerprint OK` + `hooks installed` | 已适配 | 查具体功能开关/业务 bug |
| `Fatal signal 11 (SIGSEGV) ... libchrome.so` + AM `Force finishing` | 用错 native id | 走第 4 步崩溃反查 |

## 1. 拉三包 + 扫描（脚本一键）

```bash
cd <repo>/ChroStar
python3 tools/chrome-symbols/chrome_symbols.py pull
python3 tools/chrome-symbols/chrome_symbols.py scan \
    --workdir work/chrome-symbols/<versionCode> \
    --version <大版本号> \
    --emit-java module/app/src/main/java/io/github/ylw6669/chrostar/Chrome<NNN>.java
```

扫描按**签名特征**定位，绝不猜短名：

- TabModelSelector：`(Z)->TabModel` + `()->TabModel`，且出现在 `RecentlyClosedBridge(Profile, selector)` 构造器
- ChromeTabCreator：ChromeTabbedActivity 字节码里直接 invoke 的、首参 `LoadUrlParams` 的根包短名；无痕子类 = `super == creator`
- DownloadMessageUiController：`(OfflineItem,Z,Z,Z)`
- MessageUiCoordinator：`(PropertyModel,Z)` + `(PropertyModel,WebContents,I,Z)`
- HomepageManager：静态 `()->自身` + `(Z,Z)->GURL`
- OfflineItem 字段：名字 + 类型双重校验；BookmarkBridge / J.N：保留名类，列入口

报告里出现 `!!` 说明该版本改了签名，需人工复核（脚本会列候选）。

## 2. 落地符号表

生成的 Java 只有常量。还必须补：

1. `matches(ClassLoader)` 指纹 —— 决定「这个版本走哪条路径」，脚本不生成
2. `isNNN()` + `detectEngineVersion` + `maybeInstallSplitHooks` 的判定顺序
3. 版本特有语义（例：158 的 HomepageManager 被 R8 内联 → 固定用 NTP 作主页）
4. 下载弹窗 native id（与基线 47/48/2/1/51/9/8 对比，不同才改）

## 3. 业务类里的版本分支：优先「候选尝试」而不是堆 if/else

每加一个版本都会让 `is156() ? … : is157() ? …` 爆炸。改名的高频点直接试：

```java
// Tab.loadUrl：156=l / 157=m / 158=r(两参)
static void loadUrlOnTab(Object tab, Object lp) {
    for (String mn : new String[]{"m", "l", "b", "r"}) {
        try { XposedHelpers.callMethod(tab, mn, lp); return; } catch (Throwable ignored) {}
    }
    try { XposedHelpers.callMethod(tab, "r", lp, (String) null); } catch (Throwable ignored) {}
}

// GURL -> String：字段 a 各版本都在，方法名会变
static String gurlSpecOf(Object gurl) { /* 字段 a，再 j/g/e/getSpec 兜底 */ }
```

`openTabProgrammatically` 的第三参（`boolean` / `int`）同理，两种都试一次。

## 4. 崩了怎么定位

```bash
adb logcat -d | grep -B 5 -A 20 'Fatal signal 11'
# 栈里出现 io.github.ylw6669.chrostar.<短名>.<短名> 时：
python3 tools/chrome-symbols/chrome_symbols.py crash z.c
#   z.c  =>  io.github.ylw6669.chrostar.HomeCleaner.c
#     候选原方法: clearClosedTabs
```

已知高危：**任何 `J.N` native 调用**（`VIOOOOO` / `ZIJO` / `VIJ` / `VJO` …）。
这些是 C++ 枚举 id，跨版本可能重编号；**未知版本宁可跳过该功能，也不要带着旧 id 调**。
（`clearClosedTabs` 清「最近关闭」就是这么崩的；157/158 直接跳过。）

## 5. 必须验证到真机（不要只编译通过）

```bash
adb install -r module/app/build/outputs/apk/release/app-release.apk
adb shell su -c 'killall com.android.chrome'
adb shell am start -n com.android.chrome/com.google.android.apps.chrome.Main
adb shell su -c 'LOG=$(ls -t /data/adb/lspd/log/modules*.log | head -1); grep "<TAG>" $LOG | tail -40'
adb exec-out screencap -p > /tmp/check.png
```

检查清单：

- [ ] `Chrome NNN fingerprint OK`
- [ ] `hooks installed (NNN …)`，每个 hook 一行「hooked …」
- [ ] Chrome 存活 >15s（不闪退）；`pidof com.android.chrome` 有值
- [ ] 书签面板条数 = Chrome 真实条数（`AccountBookmarks` 里按 root 统计，183+25+5 这种要逐条对齐）
- [ ] 点一个书签能真的打开
- [ ] 冷启动 3s+7s 两轮后 `settled (1 tab)`，不残留多个恢复标签
- [ ] 下载一个真实文件：无弹窗、无崩溃
- [ ] 模块设置全部保留（覆盖安装不动 `shared_prefs`）

## 环境坑（本机）

- `utime` 被 seccomp 拦 → 编译前 `export LD_PRELOAD=<toolchain>/noutime.so`
- `sdkmanager` 的 Java 网络可能被拦 → `curl` 直接下 `platform-*.zip` / `build-tools_*.zip`，
  再手写 `source.properties`（`AndroidVersion.ApiLevel` 不能带 `.0`）
- Miuix/Compose 依赖会拉高 AGP+Kotlin：Miuix 0.9.2 需 Kotlin 2.4.0 + compileSdk 37
- **卸载重装模块会被 LSPosed 从 `modules_config.db` 移除**（modules / modules_state / scope 三表），
  需要手动插回或重装后在 LSPosed 里重新启用；覆盖安装则设置全保留

## 收尾

- 符号表 + README 一并提交，commit message 写清「按签名特征定位」的依据
- 发 GitHub Release 附 APK，并在 readme 里补该版本的「定位依据表」和「修掉的专属问题」
- 把新版本的坑补进本技能（尤其新的 `J.N` 高危 id、新的改名方法）
