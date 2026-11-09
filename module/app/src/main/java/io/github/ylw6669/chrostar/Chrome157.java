// ChroStar v2.5.2 — Chrome 157 稳定版符号表
// 逆向实证 157.0.8103.46（base.apk / split_chrome.apk / split_on_demand.apk 三包交叉验证）
//
// 157 仍是 isolated splits（ChromeTabbedActivity 只在 split_chrome）；
// 但 R8 短名与 156/158 都不同 —— 这三版必须各有一张表（158 是 beta 分支）。
//
// 定位方法（可复现，见 .tmp/c157/analyze*.py）：按「签名特征」而不是猜短名。
//   TabModelSelector  : 同时有 (Z)->TabModel 与 ()->TabModel，且被 RecentlyClosedBridge
//                       构造器引用 —— 命中 c0s
//   ChromeTabCreator  : ChromeTabbedActivity 字节码里直接 invoke 的 m(LoadUrlParams,…) 类
//                       —— 命中 bv4；iqm extends bv4 为无痕子类
//   DownloadMessageUiController : d(OfflineItem,Z,Z,Z) —— 命中 vk8
//   MessageUiCoordinator        : b(PropertyModel,WebContents,I,Z) + c(PropertyModel,Z) —— 命中 y3h
//   HomepageManager             : 静态 d() 取单例 + b(Z,Z)->GURL —— 命中 rnc（与 156 的 sgc 同形）
//
// 保持不变（非 R8 改名范围）：
//   ChromeTabbedActivity / TabModelJniBridge / Tab / BookmarkModel / BookmarkBridge /
//   BookmarkItem(157 已迁到 components.bookmarks) / ProfileManager / DownloadController /
//   GURL / 6 个下载弹窗 Bridge / J.N（native 桥，含 OJ、VIOOOOO 等）
package io.github.ylw6669.chrostar;

public final class Chrome157 {

    /** TabModelSelectorImpl（l(Z)->TabModel / i()->TabModel / j()->Tab） */
    public static final String SELECTOR = "c0s";
    /** HomepageManager（d() 单例，b(Z,Z)->GURL） */
    public static final String HOME_MGR = "rnc";
    /** ChromeTabCreator（ChromeTabbedActivity 直接调用 m(LoadUrlParams,…)） */
    public static final String TAB_CREATOR = "bv4";
    /** 无痕 TabCreator（extends bv4） */
    public static final String TAB_CREATOR_INCOGNITO = "iqm";
    /** DownloadMessageUiController（d(OfflineItem,Z,Z,Z)） */
    public static final String DOWNLOAD_MESSAGE = "vk8";
    /** MessageUiCoordinator（b(PropertyModel,WebContents,I,Z) / c(PropertyModel,Z)） */
    public static final String MESSAGE_UI = "y3h";
    /** 「关闭的标签页」bridge：157 仍在 ntp 包（158 才迁到 recent_tabs） */
    public static final String RECENTLY_CLOSED =
            "org.chromium.chrome.browser.ntp.RecentlyClosedBridge";

    /** 157 的 selector 取值方法名：getModel / getCurrentTab */
    public static final String SELECTOR_GET_MODEL = "l";
    public static final String SELECTOR_GET_TAB = "j";

    // ---- OfflineItem 字段（157 与 156 完全一致，逐字段核对过类型） ----
    public static final String OI_STATE = "s0";   // I
    public static final String OI_PATH = "k0";    // String
    public static final String OI_TITLE = "V";    // String
    public static final String OI_MIME = "l0";    // String
    public static final String OI_RECEIVED = "v0";// J
    public static final String OI_TOTAL = "e0";   // J
    public static final int STATE_COMPLETE = 2;

    // ---- 下载弹窗 native 回调 id（C++ 枚举，156/157/158 一致） ----
    public static final int ID_DANGEROUS_ALLOW = 47;
    public static final int ID_DANGEROUS_DENY = 48;
    public static final int ID_INSECURE = 2;
    public static final int ID_DUPLICATE = 1;
    public static final int ID_POLICY_ALLOW = 51;
    public static final int ID_POLICY_DENY = 52;
    public static final int ID_OPEN_KEEP = 9;
    public static final int ID_LOCATION_CONFIRM = 8;

    private Chrome157() {
    }

    /**
     * 运行时指纹：157 的短名 + 签名必须同时命中，缺一即 false（回落 156/158 判断）。
     * 只做 false 安全探测，不抛异常。
     */
    public static boolean matches(ClassLoader cl) {
        try {
            Class.forName("org.chromium.chrome.browser.ChromeTabbedActivity", false, cl);

            Class<?> dm = Class.forName(DOWNLOAD_MESSAGE, false, cl);
            boolean dmOk = false;
            for (java.lang.reflect.Method m : dm.getDeclaredMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (m.getName().equals("d") && ps.length == 4
                        && ps[0].getName().endsWith("OfflineItem")
                        && ps[1] == boolean.class && ps[2] == boolean.class
                        && ps[3] == boolean.class) {
                    dmOk = true;
                    break;
                }
            }
            if (!dmOk) return false;

            Class<?> selector = Class.forName(SELECTOR, false, cl);
            boolean modelOk = false;
            boolean tabOk = false;
            for (java.lang.reflect.Method m : selector.getDeclaredMethods()) {
                if (m.getName().equals(SELECTOR_GET_MODEL) && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == boolean.class
                        && m.getReturnType().getName().endsWith("TabModel")) {
                    modelOk = true;
                }
                if (m.getName().equals(SELECTOR_GET_TAB) && m.getParameterTypes().length == 0
                        && m.getReturnType().getName().endsWith("Tab")) {
                    tabOk = true;
                }
            }
            if (!modelOk || !tabOk) return false;

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
