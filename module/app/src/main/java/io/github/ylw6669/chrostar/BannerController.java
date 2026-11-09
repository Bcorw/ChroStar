package io.github.ylw6669.chrostar;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;


/**
 * 横幅控制(v1.9.7 引入; v1.15.0 移除自定义时长) —— 下载完成横幅 -> 系统 Toast 替代。
 *
 * 机制:
 * 1. je7.d(OfflineItem,boolean,boolean,boolean) = DownloadMessageUiController 状态更新,
 *    m0==2(完成)时按开关弹 Toast 并标记待拦横幅。
 * 2. nze.c(PropertyModel,boolean) / nze.b(...) = 所有消息横幅统一上屏点,
 *    标记存在时拦截横幅(setResult); 翻译横幅另有 showMessage/create 双重拦截。
 *
 * 只影响下载完成横幅(je7 路径), 翻译/密码等横幅不受 Toast 拦截影响。
 */
public final class BannerController {

    private static final String CLS_JE7 = "je7";
    private static final String CLS_NZE = "nze";
    private static final String CLS_JE7_152 = "ia8";
    private static final String CLS_NZE_152 = "gig";
    private static final String CLS_OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";

    /** 待拦截的横幅标志(je7 下载完成标记, nze.c 拦截后清) */
    private static volatile boolean sPendingBanner = false;
    /** 156: 最近一次 OfflineItem 更新是否为 APK(用于上屏时决定是否拦截下载横幅) */
    private static volatile boolean sLastItemApk = false;

    private BannerController() {
    }

    private static boolean is152() {
        return HookEntry.is152();
    }

    private static boolean is156() {
        return HookEntry.is156();
    }

    private static boolean is158() {
        return HookEntry.is158();
    }

    /** 156/158 共用同一套业务语义, 只是符号表不同 */
    private static boolean modern() {
        return HookEntry.isSplitModern();
    }

    private static String oiState() {
        if (is158()) return Chrome158.OI_STATE;
        if (HookEntry.is157()) return Chrome157.OI_STATE;
        return Chrome156.OI_STATE;
    }

    private static String oiPath() {
        if (is158()) return Chrome158.OI_PATH;
        if (HookEntry.is157()) return Chrome157.OI_PATH;
        return Chrome156.OI_PATH;
    }

    private static String oiTitle() {
        if (is158()) return Chrome158.OI_TITLE;
        if (HookEntry.is157()) return Chrome157.OI_TITLE;
        return Chrome156.OI_TITLE;
    }

    private static String oiMime() {
        if (is158()) return Chrome158.OI_MIME;
        if (HookEntry.is157()) return Chrome157.OI_MIME;
        return Chrome156.OI_MIME;
    }

    /** 安装 hook(主进程) */
    public static void hook(ClassLoader cl) {
        hookDownloadBanner(cl);
        hookTranslateBanner(cl);
        hookTranslateCreate(cl);
        hookBannerDisplay(cl);
    }

    // ------------------------------------------------------------------
    // v1.10.3: 最彻底拦截 —— native 创建翻译消息对象时直接断掉
    // TranslateMessage.create(WebContents, long, int) 是 native 调用的静态工厂,
    // 返回 null 则 native 拿不到对象, 横幅无从显示(create 内部本身有 null 兜底路径)。
    // ------------------------------------------------------------------
    private static void hookTranslateCreate(ClassLoader cl) {
        try {
            Class<?> tmCls = XposedHelpers.findClass(
                    "org.chromium.components.translate.TranslateMessage",
                    cl);
            Class<?> wcCls = XposedHelpers.findClass(
                    "org.chromium.content_public.browser.WebContents",
                    cl);
            XposedHelpers.findAndHookMethod(tmCls, "create", wcCls, long.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                boolean hide = HookEntry.readPrefBoolean(
                                        HookEntry.KEY_HIDE_TRANSLATE_BANNER, true);
                                if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                                        + ": TranslateMessage.create called, hide=" + hide);
                                if (hide) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": translate create hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked TranslateMessage.create (block translate banner)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook TranslateMessage.create failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // v1.10.0: 隐藏翻译幅幅开关
    // TranslateMessage.showMessage(String, String, String, boolean)
    // = 翻译提示幅幅的显示入口(native 触发),
    // 拦截后翻译提示完全不显示。
    // ------------------------------------------------------------------
    private static void hookTranslateBanner(ClassLoader cl) {
        try {
            Class<?> tmCls = XposedHelpers.findClass(
                    "org.chromium.components.translate.TranslateMessage",
                    cl);
            XposedHelpers.findAndHookMethod(tmCls, "showMessage",
                    String.class, String.class, String.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                boolean hide = HookEntry.readPrefBoolean(
                                        HookEntry.KEY_HIDE_TRANSLATE_BANNER, true);
                                if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                                        + ": TranslateMessage.showMessage called, hide=" + hide);
                                if (hide) {
                                    param.setResult(null);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": translate banner hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked TranslateMessage.showMessage (hide translate banner)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook TranslateMessage failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // je7.d(OfflineItem, boolean, boolean, boolean) → 标记下载完成横幅
    // ------------------------------------------------------------------
    private static void hookDownloadBanner(ClassLoader cl) {
        try {
            String clsName = is158() ? Chrome158.DOWNLOAD_MESSAGE
                    : (HookEntry.is157() ? Chrome157.DOWNLOAD_MESSAGE
                    : (is156() ? Chrome156.DOWNLOAD_MESSAGE
                    : (is152() ? CLS_JE7_152 : CLS_JE7)));
            Class<?> je7 = XposedHelpers.findClass(clsName, cl);
            Class<?> offlineItemCls = XposedHelpers.findClass(CLS_OFFLINE_ITEM,
                    cl);
            XposedHelpers.findAndHookMethod(je7, "d", offlineItemCls,
                    boolean.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Object item = param.args[0];
                                if (item == null) return;
                                // 先记录本次更新是否 APK: 下载进度横幅在完成前就已创建,
                                // 只能靠 OfflineItem 的 mime/title 判断。
                                if (modern()) {
                                    String m = (String) XposedHelpers.getObjectField(
                                            item, oiMime());
                                    String n = (String) XposedHelpers.getObjectField(
                                            item, oiTitle());
                                    sLastItemApk = HookEntry.isApk(m, n);
                                }
                                int state = XposedHelpers.getIntField(item,
                                        modern() ? oiState()
                                                : (is152() ? "q0" : "m0"));
                                if (HookEntry.DEBUG && is156() && state == 2) {
                                    XposedBridge.log(HookEntry.TAG + ": [156] vg8.d complete title="
                                            + XposedHelpers.getObjectField(item, oiTitle()));
                                }
                                if (state != 2) {
                                    return; // 只处理完成态
                                }
                                String mime = (String) XposedHelpers.getObjectField(item,
                                        modern() ? oiMime()
                                                : (is152() ? "j0" : "f0"));
                                String name = (String) XposedHelpers.getObjectField(item,
                                        modern() ? oiTitle()
                                                : (is152() ? "T" : "e0"));
                                boolean isApk = HookEntry.isApk(mime, name);

                                boolean apkToast = HookEntry.readPrefBoolean(
                                        HookEntry.KEY_BANNER_APK_TOAST, true);
                                boolean allToast = HookEntry.readPrefBoolean(
                                        HookEntry.KEY_BANNER_ALL_TOAST, false);

                                boolean shouldToast = false;
                                if (allToast) {
                                    shouldToast = true;
                                } else if (apkToast && isApk) {
                                    shouldToast = true;
                                }

                                if (shouldToast) {
                                    // v1.9.9: 从路径提取文件名(而非 e0 title, 后者可能是 URL)
                                    String path = (String) XposedHelpers.getObjectField(item,
                                            modern() ? oiPath()
                                                    : (is152() ? "i0" : "P"));
                                    String fileName = null;
                                    if (path != null && !path.isEmpty() && !path.startsWith("content://")) {
                                        try {
                                            fileName = new java.io.File(path).getName();
                                        } catch (Throwable ignored) {}
                                    }
                                    if (fileName == null || fileName.isEmpty()) {
                                        fileName = (name != null && !name.isEmpty()) ? name : "下载文件";
                                    }
                                    // URL 式 title: 取最后一段
                                    if (fileName.contains("/")) {
                                        fileName = fileName.substring(fileName.lastIndexOf('/') + 1);
                                    }
                                    if (fileName.contains("?")) {
                                        fileName = fileName.substring(0, fileName.indexOf('?'));
                                    }
                                    showToast("下载完成: " + fileName);
                                    sPendingBanner = true;
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": banner (je7.d) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked je7.d (download-banner-toast control)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook je7.d failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // nze.b(PropertyModel, WebContents, int, boolean) / nze.c(PropertyModel, boolean)
    // → 拦截横幅(Toast) + 自定义显示时长
    // 注意: 下载横幅走 c(), 翻译横幅走 b()(TranslateMessage.showMessage), 两个都 hook。
    // v1.9.8: 修复 after 中 getResult()==null 对 void 方法恒真导致时长从不生效的 bug,
    //         改用 mIntercepted 标志位判断是否被拦截。
    // ------------------------------------------------------------------
    private static void hookBannerDisplay(ClassLoader cl) {
        // v1.10.2: 分开写死参数类型, 不再用 Class[] 数组传递
        // (LSPosed varargs 不展开数组, 导致 hook nze.b/c 注册失败)
        hookNzeC(cl);
        hookNzeB(cl);
    }

    private static void hookNzeC(ClassLoader cl) {
        try {
            String clsName = is158() ? Chrome158.MESSAGE_UI
                    : (HookEntry.is157() ? Chrome157.MESSAGE_UI
                    : (is156() ? Chrome156.MESSAGE_UI
                    : (is152() ? CLS_NZE_152 : CLS_NZE)));
            Class<?> nze = XposedHelpers.findClass(clsName, cl);
            Class<?> pmCls = XposedHelpers.findClass(
                    "org.chromium.ui.modelutil.PropertyModel", cl);
            XposedHelpers.findAndHookMethod(nze, "c", pmCls, boolean.class,
                    new BannerHook("c"));
            XposedBridge.log(HookEntry.TAG
                    + ": hooked nze.c (banner duration + toast intercept)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook nze.c failed -> " + t);
        }
    }

    private static void hookNzeB(ClassLoader cl) {
        try {
            String clsName = is158() ? Chrome158.MESSAGE_UI
                    : (HookEntry.is157() ? Chrome157.MESSAGE_UI
                    : (is156() ? Chrome156.MESSAGE_UI
                    : (is152() ? CLS_NZE_152 : CLS_NZE)));
            Class<?> nze = XposedHelpers.findClass(clsName, cl);
            Class<?> pmCls = XposedHelpers.findClass(
                    "org.chromium.ui.modelutil.PropertyModel", cl);
            Class<?> wcCls = XposedHelpers.findClass(
                    "org.chromium.content_public.browser.WebContents", cl);
            XposedHelpers.findAndHookMethod(nze, "b", pmCls, wcCls, int.class, boolean.class,
                    new BannerHook("b"));
            XposedBridge.log(HookEntry.TAG
                    + ": hooked nze.b (banner duration + toast intercept)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook nze.b failed -> " + t);
        }
    }

    /** nze.b / nze.c 共用 hook 逻辑 */
    private static final class BannerHook extends XC_MethodHook {
        private final String mName;
        private boolean mIntercepted;

        BannerHook(String name) {
            this.mName = name;
        }

        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            mIntercepted = false;
            try {
                if (is156()) {
                    Object model = param.args[0];
                    if (shouldSuppressDownloadMessage(model)) {
                        if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                                + ": [156] suppressed download message via qvg." + mName);
                        mIntercepted = true;
                        param.setResult(null);
                        return;
                    }
                }
                // v1.10.1: 隐藏翻译横幅双保险 —— 消息 ID == 29 即翻译横幅
                if (HookEntry.readPrefBoolean(
                        HookEntry.KEY_HIDE_TRANSLATE_BANNER, true)) {
                    Object model = param.args[0];
                    boolean isTrans = isTranslateMessage(model,
                            param.thisObject.getClass().getClassLoader());
                    if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                            + ": nze." + mName + " called, isTranslate=" + isTrans);
                    if (isTrans) {
                        mIntercepted = true;
                        param.setResult(null);
                        return;
                    }
                }
                // 拦截下载完成横幅(仅 je7 标记的下载完成场景)
                if (sPendingBanner) {
                    sPendingBanner = false;
                    mIntercepted = true;
                    param.setResult(null);
                }
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG
                        + ": banner (nze." + mName + " before) error -> " + t);
            }
        }
    }

    /**
     * 156: 下载消息(ID=16)在下载开始时就创建, 完成时只更新文本, 不会再次走 qvg 显示。
     * 因此必须在上屏时按 title 判断是否 APK, 直接拦截整个下载横幅;
     * 完成 Toast 由 vg8.d 钩子负责。
     */
    private static boolean shouldSuppressDownloadMessage(Object model) {
        if (model == null) return false;
        try {
            boolean all = HookEntry.readPrefBoolean(HookEntry.KEY_BANNER_ALL_TOAST, false);
            boolean apk = HookEntry.readPrefBoolean(HookEntry.KEY_BANNER_APK_TOAST, true);
            if (!all && !apk) return false;
            ClassLoader cl = model.getClass().getClassLoader();
            Class<?> qug = Class.forName("qug", false, cl);
            Object idKey = XposedHelpers.getStaticObjectField(qug, "a");
            Object id = XposedHelpers.callMethod(model, "h", idKey);
            if (!(id instanceof Integer) || ((Integer) id).intValue() != 16) {
                return false; // 16 = download message
            }
            if (all) return true;
            if (apk && sLastItemApk) {
                XposedBridge.log(HookEntry.TAG
                        + ": [156] suppressing download banner (APK in progress)");
                return true;
            }
            // 兜底: 某些场景 title 直接是文件名
            Object titleKey = XposedHelpers.getStaticObjectField(qug, "i");
            Object title = XposedHelpers.callMethod(model, "h", titleKey);
            String name = title instanceof String ? (String) title : null;
            return name != null && name.toLowerCase().endsWith(".apk");
        } catch (Throwable t) {
            if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                    + ": shouldSuppressDownloadMessage error -> " + t);
            return false;
        }
    }

    /** v1.10.1: 判断 PropertyModel 是否为翻译横幅(消息 ID == 29) */
    private static boolean isTranslateMessage(Object model, ClassLoader cl) {
        // 156 消息系统的 PropertyModel key 类已重构; 翻译横幅由
        // TranslateMessage.create/showMessage 双重拦截, 这里只服务 145/152
        if (is156()) return false;
        try {
            Class<?> qye = Class.forName("qye", false, cl);
            Object idKey = XposedHelpers.getStaticObjectField(qye, "a");
            if (idKey == null) return false;
            Object id = XposedHelpers.callMethod(model, "g", idKey);
            return id instanceof Integer && ((Integer) id).intValue() == 29;
        } catch (Throwable t) {
            return false;
        }
    }


    private static void showToast(final String msg) {
        try {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    try {
                        android.content.Context app = HookEntry.getAppContext(null);
                        if (app != null) {
                            Toast.makeText(app, msg, Toast.LENGTH_SHORT).show();
                            if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG + ": toast " + msg);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }
}