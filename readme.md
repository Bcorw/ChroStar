# ChroStar

[![Latest release](https://img.shields.io/github/v/release/Bcorw/ChroStar?label=release)](https://github.com/Bcorw/ChroStar/releases/latest)
[![Chrome](https://img.shields.io/badge/Chrome-158.0.8127.2-4285F4)](https://github.com/Bcorw/ChroStar/releases/latest)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-3DDC84)](https://github.com/Bcorw/ChroStar/releases/latest)

**LSPosed 模块**：让 Chrome 启动直开主页，并接管下载体验。

当前版本 **v2.4.0**，适配 **Chrome 158.0.8127.2（versionCode 812700214，isolated splits）**，
同时保留 Chrome 156 / 152 / 145 旧路径。

> 本仓库是 Chrome 156 适配分支，上游项目为 [ylw6669/ChroStar](https://github.com/ylw6669/ChroStar)
> （基于上游 v2.2.1 继续开发）。

## 下载

- **最新版本**：[Releases](https://github.com/Bcorw/ChroStar/releases/latest)
- 当前 APK：`ChroStar-v2.4.0-chrome158.apk`
- 安装：安装 APK → LSPosed 启用模块并勾选 **Google Chrome** → 强制停止 Chrome → 重新打开

## v2.4.0 更新内容（Chrome 158 适配 + 重构）

Chrome 158 的 R8 短名整体重排，v2.3.1 的指纹校验全部落空（日志表现为
`Chrome156 fingerprint mismatch, skip`），模块完全失效。v2.4.0 重新标定 158 符号，
并把几处硬编码改为"签名 + 结构"解析：

### 158 符号（对 `split_chrome` / `base` / `split_on_demand` 三个 dex 交叉验证）

| 对象 | 156 | 158 |
|------|-----|-----|
| TabModelSelector 实现 | `zor` | `vvr`（`l(Z)` 取常规模型、`i()` 取当前模型、`j()` 取当前标签） |
| DownloadMessageUiController | `vg8` | `ri8`（`d(OfflineItem,ZZZ)V`） |
| MessageUiCoordinator | `qvg` | `x0h`（`b(PM,WebContents,I,Z)` / `c(PM,Z)`） |
| ChromeTabCreator | `ju4` | `jv4`（+ 无痕子类 `hmm`） |
| 关闭的标签页 Bridge | `ntp.RecentlyClosedBridge` | `recent_tabs.RecentlyClosedBridge`，构造 `(Profile, TabModelSelector)` |

`OfflineItem` 字段（`s0=state(2=完成)`、`k0=path`、`V=title`、`l0=mime`）与下载弹窗
native 回调 id（47/48/2/1/51/52/9/8）在 158 中保持不变，已由 `z57.onResult`、
`DownloadDialogBridge.b` 等字节码常量复核。

### 修复

- **冷启动判定**：158 的 `ChromeTabbedActivity` 由 Chrome 内部拉起时 `intent.action == null`，
  旧逻辑要求 `ACTION_MAIN` 导致清理从不触发；现在 action 为空且无 data 同样按冷启动处理。
- **关闭标签**：冷启动清理改为直接操作当前 `TabModel`，不再依赖"记忆模型"（158 里
  `getCount` 的 hook 会误命中 `destroy()`，模型记忆为空）。
- **复用主页标签**：先扫描已有标签中的 NTP/主页，没有再新建，避免每次冷启动多开一个标签。
- **浏览保护**：7 秒复查若发现当前标签已被用户导航到其它网址，直接跳过，不再清标签。
- **native 指针**：158 的 `DangerousDownloadDialogBridge.a` 由 `long` 变成 `JniRawPtr`，
  `nativePtr()` 改为兼容 long / JniRawPtr（取内部 long 或 `JniPtr→long` 帮助方法）。
- **PrefService**：下载位置开关改为按 `(String)->boolean/int` 签名绑定，不再依赖 `b`/`c` 短名。
- **书签面板**：适配 `selector.j()`/`selector.l(false)`、`Tab.r(LoadUrlParams,String)`、
  `openTabProgrammatically(GURL,int,int)`；158 实测 184 条书签正常展示与打开。

### 158 已知限制

- **「关闭的标签页」清理已停用**：158 直接构造 `RecentlyClosedBridge` 后调用 native
  `ZIJO/VIJ` 会在主线程触发 SIGSEGV（Chrome 崩溃），已在冷启动路径跳过。
- **主页固定为新标签页**：158 的 `HomepageManager` 被 R8 内联，默认 prefs 里的
  `homepage` 是布尔键（写入字符串会让 Chrome 启动崩溃），因此不再读取自定义主页。

## v2.3.1 更新内容

### Chrome 156 适配

- Chrome 156 起 APK 使用 `android:isolatedSplits=true`：`ChromeTabbedActivity`、
  下载弹窗、`vg8`、`zor` 等类不再位于 `base.dex`，而在 `split_chrome.apk` 的独立
  ClassLoader 中。新增 hook `SplitCompatAppComponentFactory.instantiateActivity` /
  `SplitChromeApplication` / `BundleUtils` 取得 split ClassLoader 后再安装业务 hook。
- 重新标定 156 的 R8 短名与下载弹窗 native ID：
  `zor`、`sgc`、`ju4`、`vg8`、`qvg`；危险文件 `VJO 47`、不安全 `VJJZ 2`、
  重复 `VJJZ 1`、策略 `VJO 51`、位置 `VJOZ 8`、打开询问 `VJOZ 9`。
- `OfflineItem` 字段：`s0=state`（完成=2）、`k0=file_path`、`V=title`、`l0=mime`。

### 新增功能

- **主页书签启动器**：在 Chrome 新标签页下半部分显示 Chrome 书签；支持搜索、
  文件夹浏览、按使用频率排序、点击直接在 Chrome 内打开。可在设置页关闭。
- **隐藏桌面图标修复**：显式组件名 + 打开设置页时自动重新应用；覆盖安装后若图标
  回来，打开一次设置即可重新隐藏。
- **配置缓存兜底**：MIUI/HyperOS 会拦截 Chrome 拉起模块进程；在 Chrome 自己的
  SharedPreferences 中缓存上次成功读取的配置，Provider 被拦截时自动回落缓存。

### 修复与优化

- 冷启动改为 **3s + 7s 两轮**兜底：先关闭恢复出来的标签，再复核，避免会话恢复
  晚到导致旧标签重新出现。
- 启动初期不再强改 `no-restore-state`：该 flag 会让 Chrome 156 在无恢复标签时
  直接 finish。
- 「关闭的标签页」清理改走 `RecentlyClosedBridge`：
  `ZIJO(0,25,ptr,list)` 取条目、`VIJ(20,count,ptr)` 删除，避免旧
  `VIOOOOO(...,{8},...)` 连当前标签一起清掉。
- APK 自动安装适配 `vg8.d` 完成态 + `DownloadController.onDownloadCompleted`
  双路径；下载横幅按消息 ID / APK 状态拦截，完成时转 Toast。

## 功能

- **启动直开主页**：冷启动两轮清理恢复出来的历史标签，自动打开主页（Chrome 设置的主页，默认新标签页）
- **历史标签清理**：启动时清空「最近关闭」列表（156 走 `RecentlyClosedBridge`）
- **主页书签启动器**：NTP 下半部分显示书签（搜索、文件夹、按使用频率排序、点击打开）
- **隐藏桌面图标**：设置页内切换；覆盖安装/系统重置后打开一次设置即可重新隐藏
- **新标签页(+) 开主页**：点击 + 新建标签时把 NTP 替换为主页
- **下载弹窗全绕过**（各配独立开关）：文件可能有害 / 无法安全地下载 / 重复下载确认 / 策略警告 / 保存位置重命名 / 要打开此文件吗
- **APK 自动安装**：APK 下载完成后自动拉起系统安装器（156 走 `vg8.d` + `DownloadController` 双路径）
- **横幅控制**：APK 下载横幅改为系统 Toast；翻译横幅隐藏
- **同名文件覆盖**：重复下载时先备份旧文件
- **下载后自动打开类型扩展**：PDF/图片/视频/文档等按开关自动打开
- **诊断导出**：设置页可导出 hook 安装状态与引擎版本

## 书签启动器

Chrome Android 不支持扩展，且在该进程内创建 WebView 会崩溃，因此使用**原生 View 面板**：

- 位于 NTP 下半部分，保留顶部 Google 搜索框和 AI/无痕模式按钮；
- 读取 Chrome `BookmarkModel`（本机实测约 182 个书签）；
- 「全部」按使用频率/最近点击排序；文件夹 chip 可进入/返回；搜索支持标题与 URL；
- 点击书签调用 `Tab.l(LoadUrlParams)` 在当前标签打开；
- 无痕标签页不显示书签面板。

设置页开关：**启动行为 → 主页显示书签**。

## 隐藏桌面图标

- 设置页打开时自动按 `hide_icon` 重新应用 `LauncherAlias` 组件状态；
- 覆盖安装会触发 `MY_PACKAGE_REPLACED`（MIUI 可能拦截自启动，此时打开一次设置即可）；
- Chrome 启动时也会通过 `ConfigProvider` 让模块进程同步一次隐藏状态。

> MIUI/HyperOS 的“自启动”限制会阻止 Chrome 拉起模块进程。配置读取已加 Chrome
> 侧缓存；修改开关时请保持模块设置页在后台，或先打开一次设置页。

## 适配与测试环境

| 项目 | 值 |
|------|----|
| 设备 | Xiaomi 13（2211133C） |
| 系统 | Android 17 / API 37 |
| Chrome | 158.0.8127.2（812700214） / 156.0.8078.47 兼容 |
| 框架 | LSPosed 2.2.1（API 102） |
| Root | KernelSU |

## 实现原理（Chrome 156 Hook 点位）

混淆类名经 jadx 反编译 + `OfflineItemBridge` / `DownloadInfo` 交叉验证：

| 功能 | 156 Hook 点 |
|------|-------------|
| split ClassLoader | `SplitCompatAppComponentFactory.instantiateActivity` / `SplitChromeApplication.b` / `BundleUtils.b` |
| 冷启动兜底 | `ChromeTabbedActivity.onStart` → 3s + 7s 两轮 `coldStartCleanup` |
| 标签模型记忆 | `TabModelJniBridge` 无参 int 方法（签名匹配） |
| 标签选择器 | `zor.k(boolean)`；主页管理器 `sgc.d().b(false,false)` |
| +号开主页 | `ju4.m(LoadUrlParams,…)`（ChromeTabCreator.createNewTab）NTP 替换 |
| 下载弹窗 | `DangerousDownloadDialogBridge`(VJO 47/48) 等 6 个 `showDialog` |
| APK 自动安装 | `vg8.d(OfflineItem,…)`（`s0==2` 完成态）+ `DownloadController.onDownloadCompleted` |
| 横幅 | `vg8.d` 完成 Toast + `qvg.b/c` 下载消息拦截 |
| 翻译横幅 | `TranslateMessage.create/showMessage` |
| 关闭标签页历史 | `RecentlyClosedBridge` + `J.N.ZIJO(0,25,ptr,list)` / `VIJ(20,count,ptr)` |
| 主页书签 | `BookmarkModel.z(profile)` 读取书签树；`ChromeTabbedActivity.onResume` 叠加原生书签面板 |

Chrome 156 OfflineItem 字段：`s0=state`（完成=2）、`k0=file_path`、`V=title`、`l0=mime`。

配置跨进程读取：`XSharedPreferences` 失效（Android 11+），改用 exported
`ConfigProvider` + 5 秒缓存；Provider 被系统拦截时使用 Chrome 侧缓存兜底。

## 安装

1. 安装 APK（[Releases](https://github.com/Bcorw/ChroStar/releases/latest)）
2. LSPosed 中启用模块，勾选作用域 **Google Chrome**
3. 强制停止 Chrome 后重新打开

> 排错：LSPosed 日志过滤 `HomeLauncherLSP` 可看到全部 hook 安装与执行日志。

## 构建

```bash
# Android Studio，或命令行（AGP 9 + JDK 17+ / compileSdk 37）
cd module
gradle assembleRelease
```

- minSdk 33 / targetSdk 35 / compose + miuix 0.9.x
- 仓库没有 gradle wrapper 时使用系统 Gradle 9.x
- 签名：debug keystore 即可（模块签名与 Chrome 无关）

## 已知限制

- 「关闭的标签页」清理使用 Chrome 自身 `RecentlyClosedBridge`；不同 OEM 的时序可能不同。
  **Chrome 158 起该功能已停用**（native 调用会崩溃）。
- Chrome 158 的主页固定为新标签页，不读取自定义主页（HomepageManager 已内联）。
- Chrome 156 未启用「退出时清理」：启动 intent 交接会误触发；由冷启动两轮清理保证只剩主页。
- 书签启动器只读取本机 Chrome 书签，不读取云端/其他浏览器书签；图标使用首字母色块，未加载 favicon。
- MIUI/HyperOS 的自启动限制会影响模块进程被 Chrome 冷启动；设置修改后建议保持设置页在后台或先打开一次设置页。

## 免责声明

本项目仅供学习研究，请勿用于非法用途。使用本模块产生的一切后果由使用者自行承担。

---

模块名 **ChroStar** · 上游作者 [星辰](https://www.coolapk.com/u/3110354) · Chrome 156 适配：[Bcorw](https://github.com/Bcorw)
