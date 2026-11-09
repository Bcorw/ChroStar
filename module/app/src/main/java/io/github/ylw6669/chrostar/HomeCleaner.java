package io.github.ylw6669.chrostar;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * ChroStar 清理逻辑 v1.15.0 —— 冷启动兜底管线 + 退出清理 + 新标签页转主页。
 *
 * 混淆类名为根包短名(铁证见 v1.4.0 dex 类表解析): tuo=TabModelSelector,
 * jza=HomepageManager, id4=菜单关闭 Runnable, l04=ChromeTabCreator。
 * 保留混淆无关兜底(签名匹配 + 模型记忆), 双保险。
 *
 * 行为固定项: 退出清理含无痕(v1.9.3 起无开关); 清「关闭的标签页」固定全部时间
 * (v1.9.5 起无天数滑块); 自定义主页 URL 已删除(v1.9.5, 跟随 Chrome 设置主页)。
 */
public final class HomeCleaner {

    private static String CLS_SELECTOR() {
        if (HookEntry.is158()) return Chrome158.SELECTOR;    // vvr
        if (HookEntry.is157()) return Chrome157.SELECTOR;    // c0s
        if (HookEntry.is156()) return Chrome156.SELECTOR;    // zor
        return HookEntry.is152() ? "k3r" : "tuo";             // TabModelSelector(根包)
    }
    private static String CLS_HOME_MGR() {
        if (HookEntry.is158()) return "";                     // 158 改读 Chrome prefs
        if (HookEntry.is157()) return Chrome157.HOME_MGR;     // rnc
        if (HookEntry.is156()) return Chrome156.HOME_MGR;     // sgc
        return HookEntry.is152() ? "w5c" : "jza";             // HomepageManager(根包)
    }
    private static String CLS_MENU_RUNNABLE() {
        if (HookEntry.is156()) return "q35";                  // 156 不走菜单路径, 仅占位
        return HookEntry.is152() ? "q35" : "id4";
    }
    private static final String CLS_TAB_MODEL_JNI_BRIDGE =
            "org.chromium.chrome.browser.tabmodel.TabModelJniBridge";
    private static final String CLS_GURL = "org.chromium.url.GURL";
    private static final String CLS_PROFILE_MANAGER =
            "org.chromium.chrome.browser.profiles.ProfileManager";
    private static final String CLS_PROFILE =
            "org.chromium.chrome.browser.profiles.Profile";
    private static final String CLS_RECENTLY_CLOSED =
            "org.chromium.chrome.browser.ntp.RecentlyClosedBridge";

    /** 「关闭的标签页」bridge: 158 迁到 recent_tabs 包 */
    private static String recentlyClosedClass() {
        return HookEntry.is158() ? Chrome158.RECENTLY_CLOSED : CLS_RECENTLY_CLOSED;
    }
    private static final String DEFAULT_NTP = "chrome-native://newtab/";

    private static final int BROWSING_DATA_TYPE_TABS = 8;
    private static final int METHOD_ID_REMOVE_BROWSING_DATA = 0;      // 145
    private static final int METHOD_ID_REMOVE_BROWSING_DATA_V156 = 4; // 156
    private static final int MAX_CLEAR_ATTEMPTS = 4;
    private static final int MAX_COLD_ROUNDS = 4;

    /** 记忆的模型实例(WeakReference) */
    private static final List<WeakReference<Object>> sModels = new ArrayList<WeakReference<Object>>();
    private static final Object sModelsLock = new Object();

    /** 缓存的 selector 字段 */
    private static Field sSelectorField;

    private HomeCleaner() {
    }

    /** 记忆一个 TabModel 实例(hook getCount 回调) */
    public static void rememberModel(Object model) {
        if (model == null) {
            return;
        }
        synchronized (sModelsLock) {
            Iterator<WeakReference<Object>> it = sModels.iterator();
            while (it.hasNext()) {
                WeakReference<Object> ref = it.next();
                Object m = ref.get();
                if (m == null) {
                    it.remove();
                } else if (m == model) {
                    return;
                }
            }
            sModels.add(new WeakReference<Object>(model));
            XposedBridge.log(HookEntry.TAG + ": remembered TabModel "
                    + model.getClass().getName() + " (incognito=" + isIncognito(model) + ")");
        }
    }

    private static List<Object> getModels() {
        List<Object> out = new ArrayList<Object>();
        synchronized (sModelsLock) {
            Iterator<WeakReference<Object>> it = sModels.iterator();
            while (it.hasNext()) {
                WeakReference<Object> ref = it.next();
                Object m = ref.get();
                if (m == null) {
                    it.remove();
                } else {
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** 冷启动兜底管线(多轮复查) */
    public static void coldStartCleanup(final Activity activity, final int round) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        final boolean lastRound = round >= MAX_COLD_ROUNDS - 1;
        try {
            Object selector = findTabModelSelector(activity);
            Object regular = selector != null ? getRegularModel(selector) : getRegularModelByMemory();
            if (regular == null) {
                XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                        + ": regular TabModel not found yet");
                if (!lastRound) {
                    scheduleColdRetry(activity, round);
                }
                return;
            }
            ClassLoader cl = activity.getClass().getClassLoader();
            String home = resolveHomeUrl(cl);
            // 第二轮及以后: 若用户已经在该标签里导航到别的页面, 说明正在使用,
            // 不再关闭标签(避免 7s 复查把用户刚打开的页面清掉)。
            if (round >= 1) {
                String cur = currentTabUrl(selector);
                if (cur != null && !cur.equals(home) && !isNtp(cur)) {
                    XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                            + ": user browsing (" + cur + "), skip cleanup");
                    return;
                }
            }
            // 优先复用已有的主页/新标签页, 避免每次冷启动都新开一个
            Object homeTab = findExistingHomeTab(regular, home, cl);
            if (homeTab == null) {
                homeTab = openHomeTab(regular, home, cl);
                if (homeTab == null) {
                    XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                            + ": open home failed, keep existing tabs");
                    if (!lastRound) {
                        scheduleColdRetry(activity, round);
                    }
                    return;
                }
            }
            closeAllTabsExcept(homeTab, regular);
            int after = modelCount(regular);
            boolean settled = after <= 1;
            if (after == 1) {
                Object t = modelGetTabAt(regular, 0);
                settled = t == homeTab || (t != null && isHomeTab(t, home, cl));
            }
            if (settled || lastRound) {
                XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                        + ": settled (" + after + " tab(s))");
                clearClosedTabs(activity, cl, 0);
            } else {
                XposedBridge.log(HookEntry.TAG + ": [cold] round " + round
                        + ": not settled (" + after + " tab(s)), next round");
                scheduleColdRetry(activity, round);
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [cold] round " + round + " failed -> " + t);
            if (!lastRound) {
                scheduleColdRetry(activity, round);
            }
        }
    }

    private static void scheduleColdRetry(final Activity activity, final int round) {
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                coldStartCleanup(activity, round + 1);
            }
        }, 2000L);
    }

    /** 退出清理: 关闭全部模型(含无痕)全部标签 + 菜单路径 */
    public static void exitCleanup(Activity activity) {
        if (activity == null) {
            return;
        }
        try {
            Object selector = findTabModelSelector(activity);
            // 含无痕固定开启(v1.9.3 起无开关); 菜单「关闭所有标签页」路径(根包 id4.run, 含无痕)
            if (selector != null) {
                // 菜单「关闭所有标签页」原始路径(根包 id4.run → jd4.a, 含无痕)
                menuCloseAllTabs(selector, activity.getClass().getClassLoader());
            }
            List<Object> models = getModels();
            int closed = 0;
            for (Object model : models) {
                try {
                    int count = modelCount(model);
                    for (int i = count - 1; i >= 0; i--) {
                        Object tab = modelGetTabAt(model, i);
                        if (tab != null) {
                            closeTab(model, tab);
                            closed++;
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(HookEntry.TAG + ": [exit] close failed on "
                            + model.getClass().getName() + " -> " + t);
                }
            }
            XposedBridge.log(HookEntry.TAG + ": [exit] cleanup done, closed " + closed
                    + " tab(s) across " + models.size() + " model(s)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [exit] cleanup failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // v1.10.5: 新标签页(+) 打开主页开关。
    // 用户点地址栏+号打开的是 Chrome 默认新标签页(NTP),
    // 而左下角小房子是主页按钮(走 HomepageManager) —— 两者是不同入口。
    // hook TabModelJniBridge.openNewTab(保留名, 新建标签页的统一入口,
    // 参数[1]=GURL), 当 URL 是 NTP 且开关开时替换为主页 URL。
    // target=_blank 等具体 URL 不是 NTP, 不受影响。
    // ------------------------------------------------------------------
    public static void hookOpenNewTab(ClassLoader cl) {
        hookTabCreator(cl);
        hookOpenNewTabJni(cl);
    }

    // ------------------------------------------------------------------
    // v1.10.6: 主入口 —— l04(ChromeTabCreator).l(LoadUrlParams,...) = createNewTab。
    // + 号按钮的真实路径(TraceEvent "ChromeTabCreator.createNewTab" 实锤),
    // LoadUrlParams.a = URL字符串(保留名字段), 是 NTP 且开关开时直接替换。
    // ------------------------------------------------------------------
    private static void hookTabCreator(ClassLoader cl) {
        // v2.4.0: 158 用 jv4(+无痕子类 hmm).m, 156 用 ju4.m, 152 用 iq4.m, 145 用 l04.l
        String[] creatorCls;
        String creatorMethod;
        if (HookEntry.is158()) {
            creatorCls = new String[]{Chrome158.TAB_CREATOR, Chrome158.TAB_CREATOR_INCOGNITO};
            creatorMethod = "m";
        } else if (HookEntry.is157()) {
            creatorCls = new String[]{Chrome157.TAB_CREATOR,
                    Chrome157.TAB_CREATOR_INCOGNITO};
            creatorMethod = "m";
        } else if (HookEntry.is156()) {
            creatorCls = new String[]{Chrome156.TAB_CREATOR}; // ju4
            creatorMethod = "m";
        } else if (HookEntry.is152()) {
            creatorCls = new String[]{"iq4"};
            creatorMethod = "m";
        } else {
            creatorCls = new String[]{"l04"};
            creatorMethod = "l";
        }
        for (String clsName : creatorCls) {
            try {
                hookCreatorClass(cl, clsName, creatorMethod);
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": hook " + clsName + "." + creatorMethod
                        + " failed -> " + t);
            }
        }
    }

    private static void hookCreatorClass(ClassLoader cl, String creatorCls,
                                         String creatorMethod) {
        try {
            Class<?> l04 = XposedHelpers.findClass(creatorCls, cl);
            // hookAllMethods 覆盖所有重载, 仅当 args[0] 是 LoadUrlParams 才处理。
            XposedBridge.hookAllMethods(l04, creatorMethod, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        boolean sw = HookEntry.readPrefBoolean(
                                HookEntry.KEY_NEWTAB_HOME, true);
                        Object params = (param.args != null && param.args.length > 0)
                                ? param.args[0] : null;
                        if (params == null) {
                            return;
                        }
                        Object urlObj;
                        try {
                            urlObj = XposedHelpers.getObjectField(params, "a");
                        } catch (Throwable t) {
                            urlObj = null; // 不是 LoadUrlParams
                        }
                        String spec = urlObj instanceof String ? (String) urlObj : null;
                        if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG
                                + ": " + creatorCls + "." + creatorMethod
                                + " called, sw=" + sw + ", url=" + spec);
                        if (!sw) return;
                        if (spec == null || !isNtp(spec)) return;
                        // 冷启动保护: 启动初期 launcher 创建的首个 NTP 不替换,
                        // 交由 coldStartCleanup 在 3s 后用 TabModel 打开主页。
                        if (HookEntry.isSplitModern()
                                && System.currentTimeMillis() < HookEntry.sColdStartProtectUntil) {
                            XposedBridge.log(HookEntry.TAG
                                    + ": cold-start window: keep initial NTP, "
                                    + "home tab will be opened by cleanup");
                            return;
                        }
                        String home = resolveHomeUrl(
                                param.thisObject.getClass().getClassLoader());
                        if (home == null || home.isEmpty() || isNtp(home)) return;
                        XposedHelpers.setObjectField(params, "a", home);
                        XposedBridge.log(HookEntry.TAG
                                + ": createNewTab NTP -> home " + home);
                    } catch (Throwable t) {
                        XposedBridge.log(HookEntry.TAG
                                + ": hookTabCreator error -> " + t);
                    }
                }
            });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked " + creatorCls + "." + creatorMethod
                    + "* (ChromeTabCreator all overloads, newtab->home)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook l04.l failed -> " + t);
        }
    }

    // 兼容旧入口(TabModelJniBridge.openNewTab, target=_blank 等)
    private static void hookOpenNewTabJni(ClassLoader cl) {
        try {
            Class<?> bridge = XposedHelpers.findClass(CLS_TAB_MODEL_JNI_BRIDGE, cl);
            XposedBridge.hookAllMethods(bridge, "openNewTab", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (!HookEntry.readPrefBoolean(HookEntry.KEY_NEWTAB_HOME, true)) {
                            return;
                        }
                        if (param.args == null || param.args.length < 2) {
                            return;
                        }
                        Object gurl = param.args[1];
                        if (gurl == null) {
                            return;
                        }
                        String spec = gurlSpecOf(gurl);
                        if (spec == null) {
                            return;
                        }
                        if (!isNtp(spec)) {
                            return; // 不是 NTP(如 target=_blank 具体 URL), 不替换
                        }
                        String home = resolveHomeUrl(
                                param.thisObject.getClass().getClassLoader());
                        if (home == null || home.isEmpty() || isNtp(home)) {
                            return;
                        }
                        Class<?> gurlClass = Class.forName(CLS_GURL, false,
                                param.thisObject.getClass().getClassLoader());
                        Object newGurl = gurlClass.getConstructor(String.class)
                                .newInstance(home);
                        param.args[1] = newGurl;
                        XposedBridge.log(HookEntry.TAG
                                + ": newtab -> home " + home);
                    } catch (Throwable t) {
                        XposedBridge.log(HookEntry.TAG
                                + ": openNewTab hook error -> " + t);
                    }
                }
            });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked TabModelJniBridge.openNewTab (newtab->home)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook openNewTab failed -> " + t);
        }
    }

    // ---- 精确路径(根包类名) ----

    /** 找 TabModelSelector(根包 tuo): 精确 Class.forName + activity 字段扫描 */
    static Object findTabModelSelector(Activity activity) {
        try {
            Class<?> selectorClass = Class.forName(CLS_SELECTOR(), false,
                    activity.getClass().getClassLoader());
            if (sSelectorField != null) {
                try {
                    Object cached = sSelectorField.get(activity);
                    if (cached != null && selectorClass.isInstance(cached)) {
                        return cached;
                    }
                } catch (Throwable ignored) {
                }
            }
            Class<?> c = activity.getClass();
            while (c != null && c != Object.class) {
                try {
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) {
                            continue;
                        }
                        try {
                            f.setAccessible(true);
                            Object v = f.get(activity);
                            if (v != null && selectorClass.isInstance(v)) {
                                sSelectorField = f;
                                XposedBridge.log(HookEntry.TAG + ": TabModelSelector field = "
                                        + c.getName() + "." + f.getName());
                                return v;
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                } catch (Throwable ignored) {
                }
                c = c.getSuperclass();
            }
            XposedBridge.log(HookEntry.TAG + ": selector field not found on activity");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": findTabModelSelector error -> " + t);
        }
        return null;
    }

    /** 常规模型: 158/156=selector.l(false)/k(false); 152/145=selector.l(false) */
    private static Object getRegularModel(Object selector) {
        String name = HookEntry.is156() ? "k" : "l";
        try {
            Object m = XposedHelpers.callMethod(selector, name, Boolean.FALSE);
            if (m != null) {
                return m;
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": selector." + name + "(false) failed -> " + t);
        }
        return getRegularModelByMemory();
    }

    /** 兜底: 从记忆模型里找非无痕 */
    private static Object getRegularModelByMemory() {
        for (Object m : getModels()) {
            if (!isIncognito(m)) {
                return m;
            }
        }
        return null;
    }

    /** 主页 URL: 158=Chrome prefs(homepage)/NTP; 156=sgc.d().b(false,false); 152/145=主页管理器 */
    private static String resolveHomeUrl(ClassLoader cl) {
        // v2.4.0: 158 的 HomepageManager 被 R8 内联, 没有可用的 getHomepageUrl;
        // 默认 prefs 里的 "homepage" 键是布尔型(写字符串会让 Chrome 启动崩溃),
        // 因此 158 固定使用 NTP 作为主页。
        if (HookEntry.is158()) {
            return DEFAULT_NTP;
        }
        // v1.9.5: 自定义主页已删除, 直接跟随 Chrome 设置主页 / 默认新标签页
        try {
            Class<?> jzaClass = Class.forName(CLS_HOME_MGR(), false, cl);
            Object singleton = XposedHelpers.callStaticMethod(jzaClass, "d");
            if (singleton != null) {
                Object gurl = (HookEntry.is156() || HookEntry.is157())
                        ? XposedHelpers.callMethod(singleton, "b", Boolean.FALSE, Boolean.FALSE)
                        : XposedHelpers.callMethod(singleton, "b", Boolean.FALSE);
                if (gurl != null) {
                    Object urlText = XposedHelpers.getObjectField(gurl, "a");
                    if (urlText instanceof String && !((String) urlText).isEmpty()) {
                        return (String) urlText;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": resolveHomeUrl(" + CLS_HOME_MGR() + ") failed -> " + t);
        }
        return DEFAULT_NTP;
    }

    /** 菜单「关闭所有标签页」原始路径: 根包 id4(O=selector,P=false,Q=false).run() → jd4.a */
    private static void menuCloseAllTabs(Object selector, ClassLoader cl) {
        // v2.1.0/2.4.0: 152/156/158 无 id4(类职责已换), 直接遍历记忆模型关闭全部
        if (HookEntry.isSplitModern() || HookEntry.is152()) {
            int closed = 0;
            for (Object model : getModels()) {
                try {
                    int count = modelCount(model);
                    for (int i = count - 1; i >= 0; i--) {
                        Object tab = modelGetTabAt(model, i);
                        if (tab != null) {
                            closeTab(model, tab);
                            closed++;
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(HookEntry.TAG + ": [model] close model failed -> " + t);
                }
            }
            XposedBridge.log(HookEntry.TAG + ": [model] close-all done, closed=" + closed);
            return;
        }
        try {
            Class<?> id4Cls = Class.forName(CLS_MENU_RUNNABLE(), false, cl);
            Object r;
            try {
                r = id4Cls.getConstructor().newInstance();
            } catch (Throwable t) {
                java.lang.reflect.Constructor<?> ctor = id4Cls.getDeclaredConstructor();
                ctor.setAccessible(true);
                r = ctor.newInstance();
            }
            XposedHelpers.setObjectField(r, "O", selector);
            XposedHelpers.setBooleanField(r, "P", false);
            XposedHelpers.setBooleanField(r, "Q", false);
            ((Runnable) r).run();
            XposedBridge.log(HookEntry.TAG + ": menu close-all-tabs invoked (id4.run)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": menu close-all-tabs failed -> " + t);
        }
    }

    /** 原生删除「关闭的标签页」(156: J.N.VIOOOOO(4, profile, null, {8}, [], []); 145: 旧通道) */
    private static void clearClosedTabs(final Activity activity, final ClassLoader cl,
                                        final int attempt) {
        try {
            if (!HookEntry.readPrefBoolean(HookEntry.KEY_CLEAR_TABS, true)) {
                return;
            }
            // v2.1.0: 152 通道重构后无等价 selector, 语义降级跳过; 156/145 不受影响
            if (HookEntry.is152()) {
                XposedBridge.log(HookEntry.TAG + ": 152 clearClosedTabs skipped (semantic channel changed)");
                return;
            }
            if (HookEntry.is158() || HookEntry.is157()) {
                // 158/157: RecentlyClosedBridge 的 native 通道(id)与 156 不同,
                // 用 156 的 ZIJO(0,25)/VIJ(20) 会让 Chrome 主线程 SIGSEGV
                // (实测 157: fault addr 0x9 in libchrome.so, 整进程崩溃)。
                // 因此这两个版本都不在冷启动路径清理「最近关闭」。
                XposedBridge.log(HookEntry.TAG + ": [" + HookEntry.engineVersion
                        + "] clearClosedTabs skipped (native bridge unsafe)");
                return;
            }
            Class<?> pmClass = Class.forName(CLS_PROFILE_MANAGER, false, cl);
            Method pmB = pmClass.getMethod("b");
            Object profile = pmB.invoke(null);
            if (profile == null) {
                XposedBridge.log(HookEntry.TAG + ": profile not ready (attempt " + attempt + ")");
                if (attempt < MAX_CLEAR_ATTEMPTS) {
                    new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            if (activity.isFinishing() || activity.isDestroyed()) {
                                return;
                            }
                            clearClosedTabs(activity, cl, attempt + 1);
                        }
                    }, 1000L);
                }
                return;
            }
            Class<?> nClass = Class.forName(HookEntry.CLS_J_N, false, cl);
            if (HookEntry.isSplitModern()) {
                // 156 不能用 BrowsingData VIOOOOO(...,{8},...)(会连当前标签一起清掉)。
                // 改走 RecentlyClosedBridge: ZIJO(0,25,ptr,list) 取回最近关闭条目,
                // VIJ(20,count,ptr) 删除末尾 count 条(Chrome 自己清理 180 天前条目用的通道)。
                Object selector = findTabModelSelector(activity);
                if (selector == null) {
                    XposedBridge.log(HookEntry.TAG
                            + ": [156] recently-closed: selector not ready (attempt " + attempt + ")");
                    if (attempt < MAX_CLEAR_ATTEMPTS) {
                        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                if (activity.isFinishing() || activity.isDestroyed()) return;
                                clearClosedTabs(activity, cl, attempt + 1);
                            }
                        }, 1000L);
                    }
                    return;
                }
                Class<?> selectorClass = Class.forName(CLS_SELECTOR(), false, cl);
                Class<?> profileClass = Class.forName(CLS_PROFILE, false, cl);
                Class<?> bridgeClass = Class.forName(recentlyClosedClass(), false, cl);
                java.lang.reflect.Constructor<?> ctor = bridgeClass.getDeclaredConstructor(
                        profileClass, selectorClass);
                ctor.setAccessible(true);
                Object bridge = ctor.newInstance(profile, selector);
                long ptr = XposedHelpers.getLongField(bridge, "a");
                if (ptr == 0L) {
                    XposedBridge.log(HookEntry.TAG + ": [156] recently-closed bridge ptr=0");
                    return;
                }
                Class<?> nClass2 = Class.forName(HookEntry.CLS_J_N, false, cl);
                java.util.ArrayList<Object> entries = new java.util.ArrayList<Object>();
                Method zijo = nClass2.getMethod("ZIJO", int.class, int.class, long.class,
                        Object.class);
                zijo.invoke(null, Integer.valueOf(0), Integer.valueOf(25),
                        Long.valueOf(ptr), entries);
                int count = entries.size();
                if (count > 0) {
                    Method vij = nClass2.getMethod("VIJ", int.class, int.class, long.class);
                    vij.invoke(null, Integer.valueOf(20), Integer.valueOf(count),
                            Long.valueOf(ptr));
                    XposedBridge.log(HookEntry.TAG + ": [156] cleared " + count
                            + " recently-closed entrie(s)");
                    try {
                        bridgeClass.getMethod("onUpdated").invoke(bridge);
                    } catch (Throwable ignored) {
                    }
                } else {
                    XposedBridge.log(HookEntry.TAG
                            + ": [156] recently-closed already empty");
                }
                try {
                    bridgeClass.getMethod("a").invoke(bridge); // 释放 native bridge
                } catch (Throwable ignored) {
                }
                return;
            }
            int period = 4; // 全部时间(v1.9.5 起固定, 原 mapDaysToPeriod(0)=4)
            Method m = nClass.getMethod("VIOOOOOOO", int.class, int.class, Object.class,
                    Object.class, Object.class, Object.class, Object.class, Object.class,
                    Object.class);
            m.invoke(null, Integer.valueOf(METHOD_ID_REMOVE_BROWSING_DATA),
                    Integer.valueOf(period), profile, null,
                    new int[]{BROWSING_DATA_TYPE_TABS},
                    new String[0], new int[0], new String[0], new int[0]);
            XposedBridge.log(HookEntry.TAG + ": native clear closed tabs OK "
                    + "(type=" + BROWSING_DATA_TYPE_TABS + ", period=" + period + ")");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": clearClosedTabs failed -> " + t);
        }
    }


    // ---- 混淆无关路径(签名匹配 + 保留名, 兜底) ----

    private static boolean isIncognito(Object model) {
        try {
            if (HookEntry.isSplitModern()) {
                return Boolean.TRUE.equals(XposedHelpers.callMethod(model, "isIncognito"));
            }
            Method m = findBySignature(model, boolean.class);
            if (m != null) {
                return Boolean.TRUE.equals(m.invoke(model));
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": isIncognito failed, treat as incognito -> " + t);
        }
        return true;
    }

    private static int modelCount(Object model) {
        try {
            if (HookEntry.isSplitModern()) {
                Object c = XposedHelpers.callMethod(model, "getCount");
                return c instanceof Integer ? ((Integer) c).intValue() : 0;
            }
            Method m = findBySignature(model, int.class);
            if (m != null) {
                Object c = m.invoke(model);
                return c instanceof Integer ? ((Integer) c).intValue() : 0;
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": getCount failed -> " + t);
        }
        return 0;
    }

    private static Object modelGetTabAt(Object model, int i) {
        try {
            if (HookEntry.isSplitModern()) {
                return XposedHelpers.callMethod(model, "getTabAt", Integer.valueOf(i));
            }
            Method m = findBySignature(model, Object.class, int.class);
            if (m != null) {
                return m.invoke(model, Integer.valueOf(i));
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": getTabAt failed -> " + t);
        }
        return null;
    }

    private static void closeTab(Object model, Object tab) {
        try {
            if (HookEntry.isSplitModern()) {
                XposedHelpers.callMethod(model, "closeTab", tab);
                return;
            }
            Method m = findBySignature(model, void.class, Object.class);
            if (m != null) {
                m.invoke(model, tab);
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": closeTab failed -> " + t);
        }
    }

    /** 当前标签 URL(158=selector.j(), 其余=selector.i()) */
    private static String currentTabUrl(Object selector) {
        try {
            Object tab = XposedHelpers.callMethod(selector,
                    (HookEntry.is158() || HookEntry.is157()) ? "j" : "i");
            if (tab == null) {
                return null;
            }
            Object gurl = XposedHelpers.callMethod(tab, "getUrl");
            if (gurl == null) {
                return null;
            }
            return gurlSpecOf(gurl);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 在给定模型里找已存在的主页/新标签页 */
    private static Object findExistingHomeTab(Object model, String homeUrl, ClassLoader cl) {
        try {
            int count = modelCount(model);
            for (int i = 0; i < count; i++) {
                Object tab = modelGetTabAt(model, i);
                if (tab != null && isHomeTab(tab, homeUrl, cl)) {
                    return tab;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": findExistingHomeTab failed -> " + t);
        }
        return null;
    }

    private static void closeAllTabsExcept(Object keepTab, Object primaryModel) {
        List<Object> models = new ArrayList<Object>();
        if (primaryModel != null) {
            models.add(primaryModel);
        }
        for (Object m : getModels()) {
            if (m != primaryModel) {
                models.add(m);
            }
        }
        for (Object model : models) {
            try {
                int count = modelCount(model);
                int closed = 0;
                for (int i = count - 1; i >= 0; i--) {
                    Object tab = modelGetTabAt(model, i);
                    if (tab == null || tab == keepTab) {
                        continue;
                    }
                    closeTab(model, tab);
                    closed++;
                }
                XposedBridge.log(HookEntry.TAG + ": closed " + closed + "/" + count
                        + " tab(s) in model " + model.getClass().getName());
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": close-all failed on "
                        + model.getClass().getName() + " -> " + t);
            }
        }
    }

    private static boolean isHomeTab(Object tab, String homeUrl, ClassLoader cl) {
        try {
            Object gurl = XposedHelpers.callMethod(tab, "getUrl");
            if (gurl == null) {
                return false;
            }
            String url = gurlSpecOf(gurl);
            if (url == null) {
                return false;
            }
            if (url.equals(homeUrl)) {
                return true;
            }
            // 只有主页本身就是 NTP 时, 任意 chrome-native:// 标签才算"主页"
            return isNtp(homeUrl) && isNtp(url);
        } catch (Throwable t) {
            return false;
        }
    }

    /** Tab.loadUrl(LoadUrlParams)：156=l / 157=m / 158=r(两参)，按候选依次试 */
    static void loadUrlOnTab(Object tab, Object loadUrlParams) {
        String[] oneArg = {"m", "l", "b", "r"};
        for (String mn : oneArg) {
            try {
                XposedHelpers.callMethod(tab, mn, loadUrlParams);
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            XposedHelpers.callMethod(tab, "r", loadUrlParams, (String) null);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": loadUrlOnTab failed -> " + t);
        }
    }

    private static Object openHomeTab(Object model, String url, ClassLoader cl) {
        try {
            Class<?> bridgeClass = Class.forName(CLS_TAB_MODEL_JNI_BRIDGE, false, cl);
            if (!bridgeClass.isInstance(model)) {
                XposedBridge.log(HookEntry.TAG + ": model is not TabModelJniBridge (actual "
                        + model.getClass().getName() + ")");
                return null;
            }
            Class<?> gurlClass = Class.forName(CLS_GURL, false, cl);
            Object gurl = gurlClass.getConstructor(String.class).newInstance(url);
            Object tab = null;
            if (HookEntry.is158() || HookEntry.is157()) {
                // 158: openTabProgrammatically(GURL,int,int) — 第三参由 boolean 变 int index。
                // 先用 -1(末尾), 失败退 0, 再失败就直接把主页 load 进现有标签。
                try {
                    tab = XposedHelpers.callMethod(model, "openTabProgrammatically",
                            gurl, Integer.valueOf(2), Integer.valueOf(-1));
                } catch (Throwable t1) {
                    XposedBridge.log(HookEntry.TAG
                            + ": [" + HookEntry.engineVersion
                            + "] openTabProgrammatically(-1) failed -> " + t1);
                    try {
                        tab = XposedHelpers.callMethod(model, "openTabProgrammatically",
                                gurl, Integer.valueOf(2), Integer.valueOf(0));
                    } catch (Throwable t2) {
                        XposedBridge.log(HookEntry.TAG
                                + ": [" + HookEntry.engineVersion
                                + "] openTabProgrammatically(0) failed -> " + t2);
                    }
                }
                if (tab == null && modelCount(model) > 0) {
                    Object existing = modelGetTabAt(model, 0);
                    if (existing != null) {
                        Object lp = Class.forName(
                                "org.chromium.content_public.browser.LoadUrlParams", false, cl)
                                .getConstructor(String.class, int.class)
                                .newInstance(url, Integer.valueOf(0));
                        loadUrlOnTab(existing, lp);
                        tab = existing;
                        XposedBridge.log(HookEntry.TAG
                                + ": [" + HookEntry.engineVersion
                                + "] fallback: load home into existing tab 0");
                    }
                }
            } else if (HookEntry.is156()) {
                // 156: TabModelJniBridge.openTabProgrammatically(GURL,int,boolean)
                tab = XposedHelpers.callMethod(model, "openTabProgrammatically",
                        gurl, Integer.valueOf(2), Boolean.FALSE);
            } else {
                Method m = findBySignature(model, Object.class, Object.class, int.class);
                if (m == null) {
                    return null;
                }
                tab = m.invoke(model, gurl, Integer.valueOf(2));
            }
            XposedBridge.log(HookEntry.TAG + ": opened home tab -> "
                    + (tab == null ? "null" : tab.getClass().getName()));
            return tab;
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": openHomeTab failed -> " + t);
            return null;
        }
    }

    /** 按"返回类型+参数类型"匹配方法(名字无关), 从实际类向上查 */
    private static Method findBySignature(Object model, Class<?> returnType,
                                          Class<?>... paramTypes) {
        try {
            // v1.9.0: 修复返回类型精确匹配失效问题。
            // 之前用 findMethodsByExactParameters(c, Object.class, ...) 精确匹配返回
            // 类型, 但 Chrome 145 真实方法返回具体类型
            // (Tab openTabProgrammatically(GURL,int) / Tab getTabAt(int) 等),
            // 导致永远匹配不到。改为手动遍历:
            // returnType == Object.class 表示接受任意返回类型;
            // 参数中 Object.class 表示接受任意实参类型。
            Class<?> c = model.getClass();
            while (c != null && c != Object.class) {
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) {
                        continue;
                    }
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length != paramTypes.length) {
                        continue;
                    }
                    boolean paramsOk = true;
                    for (int i = 0; i < pts.length; i++) {
                        Class<?> want = paramTypes[i];
                        if (want == Object.class) {
                            continue; // 任意参数类型
                        }
                        if (!pts[i].equals(want) && !pts[i].isAssignableFrom(want)) {
                            paramsOk = false;
                            break;
                        }
                    }
                    if (!paramsOk) {
                        continue;
                    }
                    if (returnType != null && returnType != Object.class
                            && !m.getReturnType().equals(returnType)) {
                        continue;
                    }
                    return m;
                }
                c = c.getSuperclass();
            }
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": findBySignature(" + returnType + ") failed -> " + t);
        }
        return null;
    }

    /**
     * GURL -> URL 字符串。字段 a 是各版本都保留的 spec；方法名会被 R8 改
     * （156/158=j()，157=e()/g()），所以字段优先 + 方法兜底，升级无需改这里。
     */
    static String gurlSpecOf(Object gurl) {
        if (gurl == null) {
            return null;
        }
        try {
            Object v = XposedHelpers.getObjectField(gurl, "a");
            if (v instanceof String) {
                return (String) v;
            }
        } catch (Throwable ignored) {
        }
        String[] candidates = {"j", "g", "e", "getSpec"};
        for (String mn : candidates) {
            try {
                Object v = XposedHelpers.callMethod(gurl, mn);
                if (v instanceof String) {
                    return (String) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    static boolean isNtp(String url) {
        return url != null && (url.startsWith("chrome://") || url.startsWith("chrome-native://"));
    }
}
