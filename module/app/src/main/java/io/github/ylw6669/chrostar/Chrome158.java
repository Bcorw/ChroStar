// ChroStar v2.4.0 — Chrome 158 符号表(逆向实证 158.0.8127.2 / versionCode 812700214)
//
// 与 156 的关系: 158 仍使用 isolated splits(ChromeTabbedActivity 等在 split_chrome),
// 但 R8 短名整体重排。经过对 split_chrome / base / split_on_demand 三个 dex 的
// 类表与字节码交叉验证, 得到以下映射:
//
//   vvr  = TabModelSelector 实现(ChromeTabbedActivity.T2 字段类型; RecentlyClosedBridge
//          构造参数; l(Z)->TabModel, i()->TabModel 当前模型, j()->Tab 当前标签)
//   ri8  = DownloadMessageUiController(d(OfflineItem,ZZZ)V)
//   jv4  = ChromeTabCreator(createNewTab 家族 a/f/k/m; hmm 为无痕子类)
//   org.chromium.chrome.browser.recent_tabs.RecentlyClosedBridge
//        = 「关闭的标签页」(156 的 ntp.RecentlyClosedBridge, 包名迁移 + 构造参数变 (Profile, vvr))
//
// 保持不变(公开/保留名):
//   - ChromeTabbedActivity / TabModelJniBridge / Tab / BookmarkModel / DownloadController
//   - 6 个下载弹窗 Bridge 与 native 回调 id(47/48/2/1/51/9/8)
//   - OfflineItem 字段 s0=state(V=2 完成)、k0=path、V=title、l0=mime
package io.github.ylw6669.chrostar;

public final class Chrome158 {

    /** TabModelSelector 实现 */
    public static final String SELECTOR = "vvr";
    /** DownloadMessageUiController */
    public static final String DOWNLOAD_MESSAGE = "ri8";
    /** MessageUiCoordinator(b(PM,WebContents,I,Z) / c(PM,Z)) */
    public static final String MESSAGE_UI = "x0h";
    /** ChromeTabCreator(正常) / 无痕 */
    public static final String TAB_CREATOR = "jv4";
    public static final String TAB_CREATOR_INCOGNITO = "hmm";
    /** 「关闭的标签页」bridge(158 迁移到 recent_tabs 包; 构造 (Profile, TabModelSelector)) */
    public static final String RECENTLY_CLOSED =
            "org.chromium.chrome.browser.recent_tabs.RecentlyClosedBridge";

    // ---- OfflineItem 字段(158 与 156 一致, 已由 DownloadInfo.a 转换交叉验证) ----
    public static final String OI_STATE = "s0";
    public static final String OI_PATH = "k0";
    public static final String OI_TITLE = "V";
    public static final String OI_MIME = "l0";
    public static final String OI_RECEIVED = "v0";
    public static final String OI_TOTAL = "e0";
    public static final int STATE_COMPLETE = 2;

    // ---- 下载弹窗 native 回调 id(158 字节码常量, 与 156 完全相同) ----
    public static final int ID_DANGEROUS_ALLOW = 47;   // z57.onResult
    public static final int ID_DANGEROUS_DENY = 48;    // DangerousDownloadDialogBridge.d
    public static final int ID_INSECURE = 2;           // InsecureDownloadDialogBridge.showDialog
    public static final int ID_DUPLICATE = 1;          // DuplicateDownloadDialogBridge.showDialog
    public static final int ID_POLICY_ALLOW = 51;
    public static final int ID_POLICY_DENY = 52;       // PolicyWarningDownloadDialogBridge.showDialog
    public static final int ID_OPEN_KEEP = 9;          // OpenDownloadDialogBridge.showDialog
    public static final int ID_LOCATION_CONFIRM = 8;   // DownloadDialogBridge.b

    private Chrome158() {
    }

    /**
     * 运行时指纹: 158 的短名 + 签名同时命中才按 158 处理。
     * 只做 false 安全的探测, 任一缺失返回 false(回落到 156 判断)。
     */
    public static boolean matches(ClassLoader cl) {
        try {
            Class.forName("org.chromium.chrome.browser.ChromeTabbedActivity", false, cl);

            Class<?> dm = Class.forName(DOWNLOAD_MESSAGE, false, cl);
            boolean dmOk = false;
            for (java.lang.reflect.Method m : dm.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 4 && ps[0].getName().endsWith("OfflineItem")
                        && ps[1] == boolean.class && ps[2] == boolean.class
                        && ps[3] == boolean.class) {
                    dmOk = true;
                    break;
                }
            }
            if (!dmOk) return false;

            Class<?> selector = Class.forName(SELECTOR, false, cl);
            boolean modelOk = false;
            boolean currentOk = false;
            for (java.lang.reflect.Method m : selector.getDeclaredMethods()) {
                if (m.getName().equals("l") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == boolean.class
                        && m.getReturnType().getName().endsWith("TabModel")) {
                    modelOk = true;
                }
                if (m.getName().equals("i") && m.getParameterTypes().length == 0
                        && m.getReturnType().getName().endsWith("TabModel")) {
                    currentOk = true;
                }
            }
            if (!modelOk || !currentOk) return false;

            Class<?> creator = Class.forName(TAB_CREATOR, false, cl);
            boolean creatorOk = false;
            for (java.lang.reflect.Method m : creator.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length > 0 && ps[0].getName().endsWith("LoadUrlParams")) {
                    creatorOk = true;
                    break;
                }
            }
            return creatorOk;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
