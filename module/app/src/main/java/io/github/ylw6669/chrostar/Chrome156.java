// ChroStar v2.3.0 — Chrome 156 符号表(逆向实证 156.0.8078.47 / versionCode 807804704)
//
// Chrome 156 的关键变化: APK 采用 isolated splits(android:isolatedSplits=true),
// ChromeTabbedActivity 等类不再位于 base.dex, 而在 split_chrome(独立 ClassLoader)。
// LSPosed 的 lpparam.classLoader 只能看到 base.dex, 因此必须先从
// SplitCompatAppComponentFactory.instantiateActivity() 取得 split ClassLoader,
// 再安装业务 hook(见 HookEntry.install156Bootstrap)。
//
// 所有短名均经 jadx 反编译 + DownloadInfo/OfflineItemBridge 交叉验证。
package io.github.ylw6669.chrostar;

public final class Chrome156 {

    // ---- R8 短名(156.0.8078.47) ----
    public static final String SELECTOR = "zor";        // TabModelSelector(ChromeTabbedActivity.S2 的字段类型)
    public static final String HOME_MGR = "sgc";        // HomepageManager(static d(), b(boolean,boolean) -> GURL)
    public static final String TAB_CREATOR = "ju4";     // ChromeTabCreator(m(LoadUrlParams,...) = createNewTab)
    public static final String DOWNLOAD_MESSAGE = "vg8";// DownloadMessageUiController(d(OfflineItem,boolean,boolean,boolean))
    public static final String MESSAGE_UI = "qvg";      // MessageUiCoordinator(b(PM,WC,int,boolean) / c(PM,boolean))

    // ---- OfflineItem 字段(由 OfflineItemBridge 参数顺序 + DownloadInfo.a 转换确认) ----
    public static final String OI_STATE = "s0";         // OfflineItemState, COMPLETE = 2
    public static final String OI_PATH = "k0";          // file_path
    public static final String OI_TITLE = "V";          // title(通常就是文件名)
    public static final String OI_MIME = "l0";          // mime_type
    public static final String OI_RECEIVED = "v0";      // received_bytes
    public static final String OI_TOTAL = "e0";         // total_size_bytes

    // offline_item_state.h: IN_PROGRESS=0, PENDING=1, COMPLETE=2, CANCELLED=3,
    // INTERRUPTED=4, FAILED=5, PAUSED=6
    public static final int STATE_COMPLETE = 2;

    // ---- 下载弹窗 native 回调 id(156.0.8078.47 jadx 实证) ----
    // DangerousDownloadDialogBridge: q47.onResult -> VJO(47) 允许 / d() -> VJO(48) 拒绝
    public static final int ID_DANGEROUS_ALLOW = 47;
    public static final int ID_DANGEROUS_DENY = 48;
    // InsecureDownloadDialogBridge / DuplicateDownloadDialogBridge: showDialog 失败路径 VJJZ(2/1,false)
    public static final int ID_INSECURE = 2;
    public static final int ID_DUPLICATE = 1;
    // PolicyWarningDownloadDialogBridge: p5l.onResult -> VJO(51) 允许 / VJO(52) 拒绝
    public static final int ID_POLICY_ALLOW = 51;
    public static final int ID_POLICY_DENY = 52;
    // OpenDownloadDialogBridge: showDialog 无可用应用时 VJOZ(9,path,false) = 保留不打开
    public static final int ID_OPEN_KEEP = 9;
    // DownloadDialogBridge.b(path,false) 内部固定使用 VJOZ(8,...)
    public static final int ID_LOCATION_CONFIRM = 8;

    private Chrome156() {
    }

    /** 运行时指纹校验: 156 的短名/签名全部命中才走 156 硬编码路径 */
    public static boolean matches(ClassLoader cl) {
        try {
            Class.forName("org.chromium.chrome.browser.ChromeTabbedActivity", false, cl);
            Class<?> vg8 = Class.forName(DOWNLOAD_MESSAGE, false, cl);
            boolean vg8Ok = false;
            for (java.lang.reflect.Method m : vg8.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (m.getName().equals("d") && ps.length == 4
                        && ps[0].getName().endsWith("OfflineItem")
                        && ps[1] == boolean.class && ps[2] == boolean.class
                        && ps[3] == boolean.class) {
                    vg8Ok = true;
                    break;
                }
            }
            if (!vg8Ok) return false;
            Class<?> selector = Class.forName(SELECTOR, false, cl);
            boolean selectorOk = false;
            for (java.lang.reflect.Method m : selector.getDeclaredMethods()) {
                if (m.getName().equals("k") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == boolean.class
                        && m.getReturnType().getName().endsWith("TabModel")) {
                    selectorOk = true;
                    break;
                }
            }
            if (!selectorOk) return false;
            Class<?> home = Class.forName(HOME_MGR, false, cl);
            boolean homeOk = false;
            for (java.lang.reflect.Method m : home.getDeclaredMethods()) {
                if (m.getName().equals("b") && m.getParameterTypes().length == 2
                        && m.getParameterTypes()[0] == boolean.class
                        && m.getParameterTypes()[1] == boolean.class) {
                    homeOk = true;
                    break;
                }
            }
            return homeOk;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
