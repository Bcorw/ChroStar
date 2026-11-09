# ChroStar

[![Latest release](https://img.shields.io/github/v/release/Bcorw/ChroStar?label=release)](https://github.com/Bcorw/ChroStar/releases/latest)
[![Chrome](https://img.shields.io/badge/Chrome-157%20stable%20%7C%20156%20%7C%20158%20beta-4285F4)](https://github.com/Bcorw/ChroStar/releases/latest)
[![LSPosed](https://img.shields.io/badge/LSPosed-API%20102-3DDC84)](https://github.com/Bcorw/ChroStar/releases/latest)

**LSPosed 模块**：让 Chrome 启动直开主页，并接管下载体验。

当前版本 **v2.5.2**，实测适配 **Chrome 157.0.8103.46（稳定版）**，
同时保留 Chrome 156 / 158(beta) / 152 / 145 路径。

> Chrome 每个大版本的 R8 短名都会重排，**156 / 157 / 158 三版必须各有一张符号表**
> （`Chrome156.java` / `Chrome157.java` / `Chrome158.java`）。157 是稳定版，158 是 beta 分支，
> 两者短名完全不同，不能复用。

> 本仓库是 Chrome 156 适配分支，上游项目为 [ylw6669/ChroStar](https://github.com/ylw6669/ChroStar)
> （基于上游 v2.2.1 继续开发）。

## 下载

- **最新版本**：[Releases](https://github.com/Bcorw/ChroStar/releases/latest)
- 当前 APK：`ChroStar-v2.5.2-chrome157.apk`
- 安装：安装 APK → LSPosed 启用模块并勾选 **Google Chrome** → 强制停止 Chrome → 重新打开

## v2.5.2 更新内容（适配 Chrome 157 稳定版）

Chrome 157.0.8103.46 上指纹全部不匹配，模块所有业务 hook 都没装上（日志
`neither Chrome156 nor Chrome158 fingerprint matched`）。

### 新增 Chrome157 符号表（逆向实证，非猜测）

对 `base.apk / split_chrome.apk / split_on_demand.apk` 三包做 DEX 结构分析，
按**签名特征**定位（不是逐个猜短名）：

| 用途 | 157 短名 | 定位依据 |
|---|---|---|
| TabModelSelector | `c0s` | 同时有 `l(Z)->TabModel`、`i()->TabModel`、`j()->Tab`，且被 RecentlyClosedBridge 构造器引用 |
| HomepageManager | `rnc` | 静态 `d()` 单例 + `b(Z,Z)->GURL` |
| ChromeTabCreator | `bv4` / 无痕 `iqm` | ChromeTabbedActivity 字节码直接 invoke 的 `m(LoadUrlParams,…)` 类 |
| DownloadMessageUiController | `vk8` | `d(OfflineItem,Z,Z,Z)` |
| MessageUiCoordinator | `y3h` | `b(PropertyModel,WebContents,I,Z)` + `c(PropertyModel,Z)` |

OfflineItem 字段（`s0/k0/V/l0`）与 6 个下载弹窗 Bridge、`J.N` native 桥、
`BookmarkBridge` 的 10 个根 getter、`BookmarkModel.z(Profile)` 在 157 未变。

### 修掉的 3 个 157 专属崩溃/失效点

1. **启动即崩（native SIGSEGV）**：`clearClosedTabs` 用 156 的 native 通道
   `ZIJO(0,25)/VIJ(20)` 清「最近关闭」，157 上 id 变了 → Chrome 主线程
   `SIGSEGV fault addr 0x9 in libchrome.so`，进程被杀、桌面闪回。
   157/158 现在都跳过该通道（功能降级：不清「最近关闭」，其余不受影响）。
2. **书签面板不显示**：`BookmarkHome.currentUrl()` 还在调 156 的 `selector.i()`
   （157 的 `i()` 返回 TabModel 而非 Tab）→ 每帧异常返回 null → 面板永不出现。
   改为 157/158 都用 `j()`。
3. **冷启动「打开主页」失败**：`openHomeTab` 只用 156/158 两条分支，
   157 落到 `findBySignature` 失败 → `open home failed`。157 现复用 158 的
   多签名尝试（`openTabProgrammatically` int/boolean 各试一遍）。

另外把 `Tab.loadUrl`、`GURL` 取字符串改成**候选方法名依次尝试**
（156=`l`/157=`m`/158=`r`），下个版本改短名时这两处不用再改。

### 真机验证（Xiaomi 13 / Android 17 / Chrome 157.0.8103.46）

- `split ClassLoader acquired -> Chrome 157 fingerprint OK`，全部 hook 安装成功
- 书签面板：**213 条**（Chrome 实际 183+25+5=213，逐条对齐），chips / 搜索 / 常用直达正常
- 点击书签 → 正常打开（notion 实测），返回 NTP 面板恢复
- 冷启动：3s+7s 两轮清理后 settled（1 tab），无崩溃
- 下载：253KB zip 直下、无弹窗、无崩溃
- 模块设置全部保留

## v2.5.1 更新内容（修复书签漏读：账号「其他书签」全部丢失）

### 问题

Chrome 原生书签页显示账号书签 214 条（书签栏 184 + 其他书签 25 + 移动设备 5），
模块只显示 188 条，且 chips 里没有「其他书签」下的文件夹。

### 根因（反编译 `split_chrome.apk` + `BookmarkBridge` 字节码确认）

Chrome 156 的 `BookmarkBridge` 有 **10 个**无参返回 `BookmarkId` 的根 getter，
它们都走 native 分派 `J.N.OJ(int id, long ptr)`，ID 为 19/20/22/23/24/25/26/27/28/30。
并且 **账号书签与本地书签是两套根**：

| 分组 | 根 | BookmarkId |
|---|---|---|
| Google 账号 | 书签栏 / 移动设备书签 / 阅读清单 | 5 / 7 / r1 |
| 仅本设备 | 书签栏 / 其他书签 / 移动设备书签 / 阅读清单 | 1 / 2 / 3 / r0 |
| 汇总 | root（children = 全部根） | 0（native id 30） |

旧代码只取 `g/o/q/r` 四个 getter，而**账号「其他书签」没有独立 Java getter**
（对应 `t` 被 R8 内联，`getMethods()` / `getDeclaredMethods()` 都取不到），
只能从 root 汇总节点（native id 30）的 children 里拿 —— 这就是 25 条书签
整根丢失的原因。

### 修复

1. 直接调用 native 分派 `J.N.OJ(id, ptr)` 取根，绕开 R8 内联导致的反射取不到；
2. 取 root 汇总节点的 children，排除本地根（native 25~28），得到账号全部根；
3. `addNode` 用 `BookmarkId` 去重，避免 root / 子根重叠时重复计数。

**实测：188 → 214 条**，与 Chrome 原生书签页 184 + 25 + 5 完全一致，
chips 补回「娱乐 / 购物 / 其他」等文件夹。

## v2.5.0 更新内容（书签启动器体验升级 + Chrome 156 全功能核查）

### 书签启动器（主页显示书签）

- **面板高度可拖拽**：顶部手柄上下拖动调节面板高度（30%–90% 屏高），位置记忆；
- **可隐藏**：单击手柄收起面板，NTP 右下角出现 ★ 浮钮，点击恢复；
- **首页直达**：打开面板先看「常用 · 一键直达」节（按使用频次取前 8 个），下方才是「全部书签」；
- **面包屑导航**：文件夹路径 `首页 › 学习 › AI 工具`，点任意段直接跳级；
- **长按书签卡片**：弹出菜单 —— 复制链接 / 分享链接 / 复制标题；
- Tabmark 风格视觉：URL 哈希 hue 柔和色块 + 白字首字母、2 列横版卡片、
  权重搜索（标题前缀 100 / 包含 60 / 域名前缀 45 / 包含 35 / URL 20 + 使用频次）、
  文件夹聚合分节视图。

### 功能核查（Chrome 156.0.8078.79 真机）

- 冷启动清理 / +开主页 / 书签面板 / 6 类下载弹窗绕过 / APK 自动安装 /
  横幅控制 / 翻译横幅隐藏 / 覆盖重复下载 / 自动打开文件 / 位置诊断 —— hook 全部安装成功；
- 修复 `TabModelJniBridge.getCount` abstract 方法 hook 报错（跳过 abstract，
  改用签名匹配的具体方法，模型记忆恢复工作）；
- 移除从未被调用的死代码 `hookExitCleanup`。

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
- 顶部手柄可上下拖拽调节面板高度（30%–90%，位置记忆），单击收起、右下角 ★ 浮钮恢复；
- 读取 Chrome `BookmarkModel`（本机实测约 188 个书签）；
- 「常用 · 一键直达」按使用频次置顶；文件夹 chip / 面包屑路径双导航；
- 搜索支持标题与 URL，按 Tabmark 权重排序（标题前缀 > 包含 > 域名 > URL + 使用频次）；
- 点击书签调用 `Tab.l(LoadUrlParams)` 在当前标签打开；
- **长按书签卡片**：复制链接 / 分享链接 / 复制标题；
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
| Chrome | 156.0.8078.79（807807904，v2.5.0 实测） / 158.0.8127.2（812700214，v2.4.0 标定） |
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

## 签名

构建使用仓库内的 [`signing/chrostar.keystore`](signing/README.md)，让任何机器、任何一次
重建都产出**同一签名**——这样新版本可以直接 `adb install -r` 覆盖已安装的旧版，不必卸载
重装（卸载会丢模块配置和 LSPosed 作用域）。

`signing/keystore.properties` 里的口令是公开的，构建脚本不存在该文件时会回落到 AGP
默认调试密钥（签名随之改变，旧版无法覆盖）。

> ⚠️ **本仓库公开了签名密钥**：任何人都能签出与官方 Release 一致的 APK，Android 会将其
> 视为正常更新。请只从本仓库 [Releases](https://github.com/Bcorw/ChroStar/releases) 下载；
> 需要轮换密钥时见 [`signing/README.md`](signing/README.md)。

## 构建

```bash
# Android Studio，或命令行（AGP 9 + JDK 17+ / compileSdk 37）
cd module
gradle assembleRelease
```

- minSdk 33 / targetSdk 35 / compose + miuix 0.9.x
- compileSdk 37 需配 `compileSdkMinor 0`：SDK 把 API 37 落盘为 `platforms/android-37.0`，
  而 AGP 按 `android-37` 查找，缺了这行会报 `Failed to find target with hash string 'android-37'`
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
