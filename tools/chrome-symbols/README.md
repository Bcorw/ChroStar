# chrome-symbols — Chrome 升版符号定位器

ChroStar 是 LSPosed 模块，靠 **R8 短名**（`zor` / `c0s` / `vvr` …）和**混淆方法名**去 hook Chrome。
Chrome 每个大版本 R8 都会重排这些名字，所以每次 Chrome 升版，模块都会「指纹不匹配 → 所有 hook 静默不装 → 功能全失效」。
本工具把「重新标定一张符号表」这件事变成三条命令。

> 不是猜短名，是**按签名特征反查**：例如 TabModelSelector 一定同时有
> `(boolean)->TabModel` 和 `()->TabModel`，且被 `RecentlyClosedBridge(Profile, selector)` 构造器引用。

---

## 快速开始

```bash
# 0) 前置：adb 可用、手机已 root（读取 /data/app 下的 split APK）
adb devices

# 1) 从手机拉三包（base / split_chrome / split_on_demand）
python3 tools/chrome-symbols/chrome_symbols.py pull
#   -> work/chrome-symbols/<versionCode>/{base,split_chrome,split_on_demand}.apk + meta.json
#   （work/ 在 .gitignore 里，APK 不进 Git）

# 2) 扫描并生成符号表
python3 tools/chrome-symbols/chrome_symbols.py scan \
    --workdir work/chrome-symbols/<versionCode> \
    --version 158 \
    --emit-java module/app/src/main/java/io/github/ylw6669/chrostar/Chrome158.java
```

`scan` 会打印一份人看的报告 + 写出 `symbols.json`（机器可读，方便跨版本 diff），
`--emit-java` 直接产出可落地的符号表类。

```bash
# 3) 崩溃栈里的混淆帧反查源码（构建产物里的 mapping.txt）
python3 tools/chrome-symbols/chrome_symbols.py crash z.c
#   z.c  =>  io.github.ylw6669.chrostar.HomeCleaner.c
#     候选原方法: clearClosedTabs / recentlyClosedClass
```

---

## 它到底定位了什么

| 目标 | 判据 | 156 | 157 | 158 |
|---|---|---|---|---|
| TabModelSelector | `(Z)->TabModel` + `()->TabModel`，且出现在 RecentlyClosedBridge 构造器 | `zor` | `c0s` | `vvr` |
| HomepageManager | 静态 `()->自身` + 实例 `(Z,Z)->GURL` | `sgc` | `rnc` | 无（改读 prefs） |
| ChromeTabCreator | ChromeTabbedActivity 字节码里直接 invoke 的、首参 `LoadUrlParams` 的根包短名 | `ju4` | `bv4` | `jv4` |
| （无痕 creator） | `super == TAB_CREATOR` | — | `iqm` | `hmm` |
| DownloadMessageUiController | `(OfflineItem,Z,Z,Z)` | `vg8` | `vk8` | `ri8` |
| MessageUiCoordinator | `(PropertyModel,Z)` + `(PropertyModel,WebContents,I,Z)` | `qvg` | `y3h` | `x0h` |
| OfflineItem 字段 | 名字 + 类型双重校验（`s0:I` / `k0:String` / `V:String` / `l0:String`） | s0… | s0… | s0… |
| BookmarkBridge | 保留名类，列 10 个根 getter / `j` / `l` | g,h,i,m,n,o,p,q,r,t | 同 | 同 |
| J.N native 桥 | 保留名类，确认 `OJ` / `VIOOOOO` 存在 | ✓ | ✓ | ✓ |
| 下载弹窗 Bridge | 6 个保留名类 + 调用 J.N 时出现的小整数（对照基线） | 47/48/2/1/51/9/8 | 同 | 同 |

>`OfflineItem` 字段与弹窗 id 属于 C++ 侧常量，跨版本通常不变；
>脚本仍会逐项校验并在不一致时列出候选，避免「字段类型漂移 → `getIntField` 抛异常 → 功能静默失效」。

---

## 升版适配的完整流程（踩过的坑都在这里）

1. **拉包**：`pull`。若手机没 root，可让用户手动导出或用 `adb shell pm path` + `su -c cp`。
2. **扫描**：`scan --emit-java …`，先看报告里有没有 `!!`（未命中/字段不符）。
3. **写符号表**：生成的类只含索引常量。还要补：
   - `match()` 指纹（脚本不生成，因为它决定「这个版本走哪条路径」，需要人判断）；
   - 下载弹窗 id（如与基线不同才需要改）；
   - 版本特有语义（例如 158 的 HomepageManager 被内联 → 固定 NTP）。
4. **接到 HookEntry**：`isNNN()` + `detectEngineVersion` + `maybeInstallSplitHooks` 的指纹判定顺序。
5. **改业务类里的版本分支**：selector 取模型/取标签、Tab 载入、`openTabProgrammatically` 第三参……
   **建议写成「候选名依次尝试」而不是 if/else 堆版本**，这样下次改名只需补一个候选。
6. **编译 + 真机验证**：
   ```bash
   export JAVA_HOME=…/jdk-17 export ANDROID_HOME=…/sdk
   export LD_PRELOAD=…/noutime.so        # 本机 utime 被 seccomp 拦，需 shim
   cd module && gradle assembleRelease
   adb install -r app/build/outputs/apk/release/app-release.apk
   adb shell su -c 'killall com.android.chrome'
   adb shell am start -n com.android.chrome/com.google.android.apps.chrome.Main
   # 日志里必须出现：Chrome NNN fingerprint OK / hooks installed (NNN …)
   ```
7. **崩溃排查**：`adb logcat | grep -E 'Fatal signal|tombstone'`，
   栈里出现 `io.github.ylw6669.chrostar.<短名>.<短名>` 就用 `crash` 子命令反查。

### 已知的「会崩」与「会静默失效」点

| 现象 | 原因 | 处理 |
|---|---|---|
| Chrome 启动几秒后闪回桌面（`SIGSEGV fault addr 0x9 in libchrome.so`） | 用错版本的 native id 调 `J.N`（如 `clearClosedTabs` 的 `ZIJO/VIJ`） | 未知版本直接跳过该 native 通道 |
| 书签面板永不出现 | `selector.i()` 在新版返回 TabModel 而非 Tab → 每帧异常被吞 | 157/158 用 `j()`；或按返回类型动态判断 |
| 冷启动 `open home failed` | `openHomeTab` 缺新版分支 | `openTabProgrammatically` 的 boolean / int 两种第三参各试一次 |
| 指纹不匹配（`no Chrome156/157/158 fingerprint matched`） | 短名变了 | 跑本工具重标定 |
| 点书签没反应 | `Tab.loadUrl` 改名（156=`l` / 157=`m` / 158=`r`） | 候选名 `m,l,b,r` 依次尝试 |

### 环境坑

- 本机文件系统 `utime` 被 seccomp 拦：编译前 `export LD_PRELOAD=…/noutime.so`。
- `sdkmanager` 走 Java 网络可能被拦：直接 `curl` 下 `platform-*.zip` / `build-tools_*.zip`。
- 卸载重装模块会让 **LSPosed 从 `modules_config.db` 移除该模块**（modules / modules_state / scope 三表），
  需要手动插回或重装后在 LSPosed 里重新启用。
- 覆盖安装保留模块设置；签名不一致时才需要卸载，卸载前先 `su -c cp` 备份 `shared_prefs`。

---

## 输出文件

```
work/chrome-symbols/<versionCode>/
  base.apk  split_chrome.apk  split_on_demand.apk
  meta.json       # version / versionCode / 已拉取的包
  symbols.json    # 全部定位结果 + 候选，便于与上一版 diff
```
