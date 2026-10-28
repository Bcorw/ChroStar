package io.github.ylw6669.chrostar;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * ChroStar 模块入口 v2.3.0。
 *
 * 支持:
 *  - Chrome 156+ (isolated splits): base.dex 只有壳, 真正的 ChromeTabbedActivity /
 *    DownloadMessageUiController 等在 split_chrome.apk 的独立 ClassLoader 里。
 *    先 hook SplitCompatAppComponentFactory.instantiateActivity 拿到 split CL,
 *    再安装全部业务 hook; 同时用 Activity.onStart 兜底。
 *  - Chrome 152 / 145: 原有路径保持不变。
 *
 * 156 混淆符号见 Chrome156.java(全部经 jadx 反编译交叉验证)。
 */
public class HookEntry implements IXposedHookLoadPackage {

    public static final String TAG = "HomeLauncherLSP";
    /** 热路径诊断日志开关(编译期常量, 发布版关闭) */
    static final boolean DEBUG = false;
    public static final String PREFS_NAME = "io.github.ylw6669.chrostar_preferences";
    /** Chrome native 桥类(J.N), DownloadSafetyBypass/HomeCleaner 共用 */
    public static final String CLS_J_N = "J.N";

    /** 引擎版本绑定: chrome156 / chrome152 / chrome145 */
    public static volatile String engineVersion = "unknown";

    /** base.dex 的 ClassLoader(LSPosed 提供), 用于读取 base 层类 */
    public static volatile ClassLoader baseClassLoader;
    /** split_chrome 的 ClassLoader(156 专用) */
    public static volatile ClassLoader splitClassLoader;

    private static final Object sSplitLock = new Object();
    private static volatile boolean sSplitHooksInstalled;

    public static final String KEY_CLEAR_TABS = "clear_tabs";
    public static final String KEY_BYPASS_DANGEROUS = "bypass_dangerous";
    public static final String KEY_BYPASS_INSECURE = "bypass_insecure";
    public static final String KEY_BYPASS_DUPLICATE = "bypass_duplicate";
    public static final String KEY_BYPASS_POLICY = "bypass_policy";
    public static final String KEY_BYPASS_LOCATION = "bypass_location";
    public static final String KEY_BYPASS_OPEN = "bypass_open";
    public static final String KEY_CLEAN_START = "clean_start";
    public static final String KEY_AUTO_INSTALL_APK = "auto_install_apk";
    public static final String KEY_BANNER_APK_TOAST = "banner_apk_toast";
    public static final String KEY_BANNER_ALL_TOAST = "banner_all_toast";
    public static final String KEY_HIDE_TRANSLATE_BANNER = "hide_translate_banner";
    public static final String KEY_NEWTAB_HOME = "newtab_home";
    public static final String KEY_HOMEPAGE_BOOKMARKS = "homepage_bookmarks";
    public static final String KEY_OVERWRITE_DUPLICATE = "overwrite_duplicate";
    public static final String KEY_AUTO_OPEN_EXT = "auto_open_ext";
    public static final String KEY_AUTO_OPEN_PDF = "auto_open_pdf";
    public static final String KEY_AUTO_OPEN_ARCHIVE = "auto_open_archive";
    public static final String KEY_AUTO_OPEN_DOCUMENT = "auto_open_document";
    public static final String KEY_AUTO_OPEN_SPREADSHEET = "auto_open_spreadsheet";
    public static final String KEY_AUTO_OPEN_PRESENTATION = "auto_open_presentation";
    public static final String KEY_AUTO_OPEN_TEXT = "auto_open_text";
    public static final String KEY_AUTO_OPEN_IMAGE = "auto_open_image";
    public static final String KEY_AUTO_OPEN_VIDEO = "auto_open_video";
    public static final String KEY_AUTO_OPEN_AUDIO = "auto_open_audio";
    public static final String KEY_AUTO_OPEN_EBOOK = "auto_open_ebook";
    public static final String KEY_DIAGNOSTIC_MODE = "diagnostic_mode";

    static final String CLS_CHROME_TABBED_ACTIVITY =
            "org.chromium.chrome.browser.ChromeTabbedActivity";
    static final String CLS_TAB_MODEL_JNI_BRIDGE =
            "org.chromium.chrome.browser.tabmodel.TabModelJniBridge";
    private static final String CLS_SPLIT_APP =
            "org.chromium.chrome.browser.base.SplitChromeApplication";
    private static final String CLS_SPLIT_FACTORY =
            "org.chromium.chrome.browser.base.SplitCompatAppComponentFactory";

    private static final Set<Activity> sColdHandled =
            Collections.newSetFromMap(new WeakHashMap<Activity, Boolean>());

    /** 冷启动保护窗口: Chrome 156 启动初期由 launcher 创建的 NTP 不替换,
     *  否则 Chrome 会认为启动标签未就绪而直接 finish。窗口内由冷启动管线
     *  在 2s 后用 TabModel 打开主页。 */
    public static volatile long sColdStartProtectUntil;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!isChromePackage(lpparam)) {
            return;
        }
        if (lpparam.processName != null && !lpparam.processName.equals(lpparam.packageName)) {
            return;
        }
        baseClassLoader = lpparam.classLoader;
        engineVersion = detectEngineVersion(lpparam);
        XposedBridge.log(TAG + ": engine=" + engineVersion + ", module v"
                + BuildConfig.VERSION_NAME);
        syncHideIconLater();

        if ("chrome156".equals(engineVersion)) {
            install156Bootstrap(lpparam.classLoader);
            XposedBridge.log(TAG + ": v" + BuildConfig.VERSION_NAME
                    + " 156 isolated-split bootstrap installed for " + lpparam.packageName);
            return;
        }

        if ("chrome152".equals(engineVersion)) {
            hookTabModelMemory(lpparam.classLoader);
            hookOnStart(lpparam.classLoader);
            HomeCleaner.hookOpenNewTab(lpparam.classLoader);
            DownloadSafetyBypass.hook(lpparam.classLoader);
            AutoInstallApk.hook(lpparam.classLoader);
            BannerController.hook(lpparam.classLoader);
            LocationAndDiagnostics.hookLocationPrefSource(lpparam.classLoader);
            LocationAndDiagnostics.noteHook("152 path hooks", true);
            XposedBridge.log(TAG + ": v" + BuildConfig.VERSION_NAME + " hooks installed (152 path) for "
                    + lpparam.packageName);
            return;
        }

        // 145 原路径
        hookCommandLineNoRestore(lpparam.classLoader);
        hookTabModelMemory(lpparam.classLoader);
        hookOnStart(lpparam.classLoader);
        HomeCleaner.hookOpenNewTab(lpparam.classLoader);
        DownloadSafetyBypass.hook(lpparam.classLoader);
        AutoInstallApk.hook(lpparam.classLoader);
        BannerController.hook(lpparam.classLoader);
        LocationAndDiagnostics.hookLocationPrefSource(lpparam.classLoader);
        LocationAndDiagnostics.noteHook("145 path hooks", true);
        XposedBridge.log(TAG + ": v" + BuildConfig.VERSION_NAME + " hooks installed for " + lpparam.packageName
                + " (process " + lpparam.processName + ")");
    }

    /**
     * 模块覆盖安装后 activity-alias 可能被系统重置为 enabled; MIUI 又会拦截
     * MY_PACKAGE_REPLACED 自启动。Chrome 启动时通过 exported ConfigProvider
     * 让模块进程重新应用 hide_icon(ContentProvider 启动不受前台广播限制)。
     */
    private static void syncHideIconLater() {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    android.content.Context ctx = getAppContext(null);
                    if (ctx == null) return;
                    ctx.getContentResolver().call(
                            android.net.Uri.parse("content://"
                                    + ConfigProvider.AUTHORITY),
                            "sync_hide_icon", null, null);
                } catch (Throwable t) {
                    if (DEBUG) XposedBridge.log(TAG + ": sync hide icon failed -> " + t);
                }
            }
        }, 1500L);
    }

    public static boolean is156() {
        return "chrome156".equals(engineVersion);
    }

    public static boolean is152() {
        return "chrome152".equals(engineVersion);
    }

    // ------------------------------------------------------------------
    // Chrome 156 isolated splits 引导
    // ------------------------------------------------------------------

    private static void install156Bootstrap(final ClassLoader baseCL) {
        // 1) 156 不再强改 no-restore-state: 实测该 flag 会让 ChromeTabbedActivity
        //    在无恢复标签时直接 finish(启动后闪退)。冷启动改由 HomeCleaner 的
        //    2s 兜底管线完成(关闭恢复出来的标签并打开主页)。
        // hookCommandLineNoRestore(baseCL);

        // 2) split ClassLoader 来源 A: AppComponentFactory 实例化组件时拿到
        try {
            Class<?> factory = Class.forName(CLS_SPLIT_FACTORY, false, baseCL);
            XposedHelpers.findAndHookMethod(factory, "instantiateActivity",
                    ClassLoader.class, String.class, Intent.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                maybeInstallSplitHooks((ClassLoader) param.args[0],
                                        "instantiateActivity:" + param.args[1]);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": split bootstrap(activity) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked SplitCompatAppComponentFactory.instantiateActivity");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook SplitCompatAppComponentFactory failed -> " + t);
        }

        // 3) split ClassLoader 来源 B: SplitChromeApplication.createContextForSplit / b
        hookSplitContextMethod(baseCL, CLS_SPLIT_APP, "createContextForSplit", String.class);
        hookSplitContextMethod(baseCL, CLS_SPLIT_APP, "b", String.class);

        // 4) split ClassLoader 来源 C: BundleUtils.b(String) (返回 split Context)
        try {
            Class<?> bundleUtils = Class.forName("org.chromium.base.BundleUtils", false, baseCL);
            XposedHelpers.findAndHookMethod(bundleUtils, "b", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object ctx = param.getResult();
                                if (ctx instanceof Context) {
                                    maybeInstallSplitHooks(((Context) ctx).getClassLoader(),
                                            "BundleUtils.b:" + param.args[0]);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": split bootstrap(bundle) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked BundleUtils.b(String)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook BundleUtils.b failed -> " + t);
        }

        // 5) 兜底: 任何 Activity.onStart 都能拿到真实的 split ClassLoader
        try {
            XposedBridge.hookAllMethods(Activity.class, "onStart", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (!(param.thisObject instanceof Activity)) {
                            return;
                        }
                        Activity activity = (Activity) param.thisObject;
                        if (!CLS_CHROME_TABBED_ACTIVITY.equals(activity.getClass().getName())) {
                            return;
                        }
                        maybeInstallSplitHooks(activity.getClass().getClassLoader(),
                                "activity-onStart");
                        handleColdStart(activity);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": Activity.onStart fallback error -> " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": Activity.onStart fallback installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook Activity.onStart failed -> " + t);
        }

        // 6) 延迟再尝试一次(LSPosed 装载早于 split 初始化时, 2s 后通常已就绪)
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> bundleUtils = Class.forName("org.chromium.base.BundleUtils", false, baseCL);
                    Object cl = bundleUtils.getMethod("c", String.class).invoke(null, "chrome");
                    if (cl instanceof ClassLoader) {
                        maybeInstallSplitHooks((ClassLoader) cl, "delayed-BundleUtils.c");
                    }
                } catch (Throwable ignored) {
                }
            }
        }, 2500L);
    }

    private static void hookSplitContextMethod(ClassLoader baseCL, String className,
                                                String methodName, Class<?>... params) {
        try {
            Class<?> cls = Class.forName(className, false, baseCL);
            XposedBridge.hookAllMethods(cls, methodName, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object ctx = param.getResult();
                        if (ctx instanceof Context) {
                            maybeInstallSplitHooks(((Context) ctx).getClassLoader(),
                                    "SplitChromeApplication." + methodName);
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": split context hook error -> " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked " + className + "#" + methodName);
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook " + className + "#" + methodName + " failed -> " + t);
        }
    }

    /** 拿到可加载 ChromeTabbedActivity 的 split ClassLoader 后, 安装全部 156 hook(仅一次) */
    private static void maybeInstallSplitHooks(ClassLoader cl, String source) {
        if (cl == null || sSplitHooksInstalled) {
            return;
        }
        try {
            Class.forName(CLS_CHROME_TABBED_ACTIVITY, false, cl);
        } catch (Throwable notSplit) {
            return;
        }
        synchronized (sSplitLock) {
            if (sSplitHooksInstalled) {
                return;
            }
            if (!Chrome156.matches(cl)) {
                XposedBridge.log(TAG + ": split CL from " + source
                        + " but Chrome156 fingerprint mismatch, skip");
                return;
            }
            splitClassLoader = cl;
            engineVersion = "chrome156";
            XposedBridge.log(TAG + ": split ClassLoader acquired from " + source
                    + " -> " + cl);
            install156Hooks(cl);
            sSplitHooksInstalled = true;
        }
    }

    private static void install156Hooks(ClassLoader cl) {
        hookTabModelMemory(cl);
        hookOnStart(cl);
        // 退出清理暂不启用: Chrome 156 启动/接管 intent 时 onStop+isFinishing 会误触发,
        // 反而清掉刚打开的主页标签。冷启动清理已经保证下次启动只有主页。
        HomeCleaner.hookOpenNewTab(cl);
        DownloadSafetyBypass.hook(cl);
        AutoInstallApk.hook(cl);
        BannerController.hook(cl);
        LocationAndDiagnostics.hookLocationPrefSource(cl);
        BookmarkHome.hook(cl);
        LocationAndDiagnostics.noteHook("156 isolated-split hooks", true);
        XposedBridge.log(TAG + ": v" + BuildConfig.VERSION_NAME
                + " hooks installed (156 isolated-split path)");
    }

    /** 引擎版本判定 */
    private static String detectEngineVersion(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        // 156+: base 带 SplitChromeApplication, 但 ChromeTabbedActivity 不在 base.dex
        try {
            Class.forName(CLS_SPLIT_APP, false, cl);
            boolean tabbedInBase = true;
            try {
                Class.forName(CLS_CHROME_TABBED_ACTIVITY, false, cl);
            } catch (Throwable ignored) {
                tabbedInBase = false;
            }
            if (!tabbedInBase) {
                return "chrome156";
            }
        } catch (Throwable ignored) {
        }
        try {
            if (Chrome152.matches(cl)) {
                return "chrome152";
            }
        } catch (Throwable ignored) {
        }
        try {
            Class.forName("oo4", false, cl);
            return "chrome145";
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static boolean isChromePackage(XC_LoadPackage.LoadPackageParam lpparam) {
        String pkg = lpparam.packageName;
        if (pkg == null) {
            return false;
        }
        if ("com.android.chrome".equals(pkg)) {
            return true;
        }
        if (pkg.toLowerCase().contains("chrome")) {
            try {
                lpparam.classLoader.loadClass(CLS_CHROME_TABBED_ACTIVITY);
                XposedBridge.log(TAG + ": matched chrome-family package: " + pkg);
                return true;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /**
     * Hook 1: 命令行 flag "no-restore-state" 强制 true。
     * Chrome 145 走 oo4.c; 156 的检查点是公开类 org.chromium.base.CommandLine#c(String),
     * 这里直接 hook hasSwitch, 版本无关。
     */
    private static void hookCommandLineNoRestore(ClassLoader cl) {
        try {
            Class<?> cmd = Class.forName("org.chromium.base.CommandLine", false, cl);
            Method target = null;
            for (Method m : cmd.getDeclaredMethods()) {
                if (m.getName().equals("c") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == String.class
                        && m.getReturnType() == boolean.class) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                XposedBridge.log(TAG + ": CommandLine#c(String) not found");
                return;
            }
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!readPrefBoolean(KEY_CLEAN_START, true)) {
                            return;
                        }
                        if ("no-restore-state".equals(param.args[0])) {
                            param.setResult(Boolean.TRUE);
                            XposedBridge.log(TAG + ": no-restore-state switch forced ON");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": CommandLine.c hook error -> " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked CommandLine#c(String) [no-restore-state]");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook CommandLine failed -> " + t);
        }
    }

    /** Hook 2: TabModelJniBridge 无参返回 int 方法(签名匹配) → 模型记忆 */
    private static void hookTabModelMemory(ClassLoader cl) {
        try {
            Class<?> bridge = Class.forName(CLS_TAB_MODEL_JNI_BRIDGE, false, cl);
            Method target = null;
            for (Method m : XposedHelpers.findMethodsByExactParameters(bridge, int.class)) {
                if (m.getParameterTypes().length == 0 && !m.getName().equals("getCount")) {
                    target = m;
                    break;
                }
            }
            if (target == null) {
                try {
                    target = bridge.getDeclaredMethod("getCount");
                } catch (Throwable ignored) {
                }
            }
            if (target == null) {
                XposedBridge.log(TAG + ": getCount-like method not found on TabModelJniBridge");
                return;
            }
            final Method finalTarget = target;
            XposedBridge.hookMethod(target, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        HomeCleaner.rememberModel(param.thisObject);
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": rememberModel error -> " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": hooked TabModelJniBridge#" + target.getName()
                    + "() [model memory]");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook TabModel memory failed -> " + t);
        }
    }

    /** Hook 3: ChromeTabbedActivity.onStart() → 冷启动兜底管线 */
    private static void hookOnStart(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_CHROME_TABBED_ACTIVITY,
                    cl,
                    "onStart",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                handleColdStart((Activity) param.thisObject);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": onStart after-hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked ChromeTabbedActivity.onStart()");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook ChromeTabbedActivity.onStart() failed -> " + t);
        }
    }

    /** 冷启动处理(从 onStart 或 Activity 兜底进入, 同一 Activity 只处理一次) */
    static void handleColdStart(final Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (!readPrefBoolean(KEY_CLEAN_START, true)) {
            return;
        }
        if (!sColdHandled.add(activity)) {
            return;
        }
        Intent intent = activity.getIntent();
        if (intent == null
                || !Intent.ACTION_MAIN.equals(intent.getAction())
                || intent.getData() != null) {
            return;
        }
        sColdStartProtectUntil = System.currentTimeMillis() + 6000L;
        // 156 的会话恢复是异步的: 2s 时可能只看到初始 NTP, 随后才把恢复标签
        // 导航到旧网址。因此做两轮: 3s 首轮, 7s 复核; 第二轮仍会强制只剩主页。
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                HomeCleaner.coldStartCleanup(activity, 0);
            }
        }, 3000L);
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                HomeCleaner.coldStartCleanup(activity, 2);
            }
        }, 7000L);
        XposedBridge.log(TAG + ": cold start(MAIN) detected, cleanup scheduled (3s + 7s)");
    }

    /** Hook 4: 退出清理(Activity 正在 finish 时关闭全部标签) */
    private static void hookExitCleanup(ClassLoader cl) {
        try {
            // 156 的 ChromeTabbedActivity 没有覆写 onDestroy, 用它自己的 onStop 并在
            // isFinishing() 时清理(此时 TabModel 仍然有效)
            XposedHelpers.findAndHookMethod(CLS_CHROME_TABBED_ACTIVITY, cl,
                    "onStop", new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity = (Activity) param.thisObject;
                                if (activity != null && activity.isFinishing()) {
                                    HomeCleaner.exitCleanup(activity);
                                }
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + ": exit cleanup error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + ": hooked ChromeTabbedActivity.onStop() [exit cleanup]");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook onStop(exit cleanup) failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // 配置读取 —— ConfigProvider(5s 缓存)优先, XSharedPreferences 兜底。
    // ------------------------------------------------------------------
    private static long sCfgCacheTime;
    private static java.util.Map<String, String> sCfgCache;
    /** Chrome 侧配置缓存文件: MIUI 会拦截模块进程被 Chrome 拉起, 用上次成功读取的值兜底 */
    private static final String HOOK_CACHE_PREFS = "chrostar_hook_cache";
    private static java.util.Map<String, String> sHookCache;

    static boolean readPrefBoolean(String key, boolean defValue) {
        String v = readPref(key);
        return v == null ? defValue : "true".equalsIgnoreCase(v);
    }

    static int readPrefInt(String key, int defValue) {
        String v = readPref(key);
        if (v == null) return defValue;
        try {
            return Integer.parseInt(v);
        } catch (Throwable t) {
            return defValue;
        }
    }

    static String readPrefString(String key, String defValue) {
        String v = readPref(key);
        return v == null ? defValue : v;
    }

    private static String readPref(String key) {
        try {
            long now = System.currentTimeMillis();
            if (sCfgCache != null && now - sCfgCacheTime < 5000L) {
                String cv = sCfgCache.get(key);
                if (DEBUG) XposedBridge.log(TAG + ": pref CP-cache key=" + key + " -> " + cv);
                return cv;
            }
            android.content.Context ctx = getAppContext(null);
            if (DEBUG) XposedBridge.log(TAG + ": pref CP ctx=" + ctx);
            if (ctx != null) {
                android.database.Cursor cur = ctx.getContentResolver().query(
                        android.net.Uri.parse("content://"
                                + ConfigProvider.AUTHORITY + "/config"),
                        null, null, null, null);
                if (cur != null) {
                    try {
                        java.util.Map<String, String> map =
                                new java.util.HashMap<String, String>();
                        while (cur.moveToNext()) {
                            String k = cur.getString(0);
                            String v = cur.getString(1);
                            if (k != null && v != null) {
                                map.put(k, v);
                            }
                        }
                        sCfgCache = map;
                        sCfgCacheTime = now;
                        saveHookCache(ctx, map);
                        if (DEBUG) XposedBridge.log(TAG + ": pref CP key=" + key + " -> "
                                + map.get(key) + " (total=" + map.size() + ")");
                        return map.get(key);
                    } finally {
                        cur.close();
                    }
                }
            }
        } catch (Throwable t) {
            if (DEBUG) XposedBridge.log(TAG + ": pref CP key=" + key + " err -> " + t);
        }
        // 模块进程被 MIUI 拦截时, 用 Chrome 侧缓存的上次成功配置
        try {
            android.content.Context ctx = getAppContext(null);
            if (ctx != null) {
                String cached = readHookCache(ctx, key);
                if (cached != null) {
                    if (DEBUG) XposedBridge.log(TAG + ": pref cache key=" + key + " -> " + cached);
                    return cached;
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            XSharedPreferences prefs = new XSharedPreferences("io.github.ylw6669.chrostar");
            prefs.reload();
            if (prefs.contains(key)) {
                Object v = prefs.getAll().get(key);
                if (v != null) {
                    return String.valueOf(v);
                }
            }
        } catch (Throwable t) {
            if (DEBUG) XposedBridge.log(TAG + ": pref XSP key=" + key + " err -> " + t);
        }
        return null;
    }

    private static synchronized void saveHookCache(android.content.Context ctx,
                                                   java.util.Map<String, String> map) {
        try {
            if (sHookCache != null && sHookCache.equals(map)) return;
            android.content.SharedPreferences.Editor e = ctx.getSharedPreferences(
                    HOOK_CACHE_PREFS, android.content.Context.MODE_PRIVATE).edit();
            e.clear();
            for (java.util.Map.Entry<String, String> en : map.entrySet()) {
                e.putString(en.getKey(), en.getValue());
            }
            e.apply();
            sHookCache = new java.util.HashMap<String, String>(map);
            if (DEBUG) XposedBridge.log(TAG + ": saveHookCache size=" + map.size()
                    + " dir=" + ctx.getDataDir());
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": saveHookCache failed -> " + t);
        }
    }

    private static synchronized String readHookCache(android.content.Context ctx, String key) {
        try {
            if (sHookCache == null) {
                java.util.Map<String, String> map = new java.util.HashMap<String, String>();
                for (java.util.Map.Entry<String, ?> en : ctx.getSharedPreferences(
                        HOOK_CACHE_PREFS, android.content.Context.MODE_PRIVATE)
                        .getAll().entrySet()) {
                    if (en.getValue() != null) {
                        map.put(en.getKey(), String.valueOf(en.getValue()));
                    }
                }
                sHookCache = map;
            }
            String v = sHookCache.get(key);
            if (DEBUG) XposedBridge.log(TAG + ": readHookCache " + key + " -> " + v
                    + " size=" + sHookCache.size());
            return v;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": readHookCache failed -> " + t);
            return null;
        }
    }

    /** 获取应用上下文: ActivityThread 优先, ApplicationStatus 兜底 */
    static android.content.Context getAppContext(ClassLoader cl) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app instanceof android.content.Context) {
                return (android.content.Context) app;
            }
        } catch (Throwable ignored) {
        }
        ClassLoader appCl = (cl != null) ? cl
                : (splitClassLoader != null ? splitClassLoader : baseClassLoader);
        try {
            if (appCl != null) {
                Class<?> appStatus = Class.forName(
                        "org.chromium.base.ApplicationStatus", false, appCl);
                Object activity = XposedHelpers.getStaticObjectField(appStatus, "d");
                if (activity instanceof android.content.Context) {
                    return (android.content.Context) activity;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** APK 判断: MIME 或文件名后缀(收拢自 AutoInstallApk/BannerController) */
    static boolean isApk(String mime, String name) {
        if (mime != null && mime.contains("package-archive")) {
            return true;
        }
        return name != null && name.toLowerCase().endsWith(".apk");
    }
}
