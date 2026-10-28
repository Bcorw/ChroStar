package io.github.ylw6669.chrostar;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Chrome 156 新标签页「书签启动器」(纯原生 View, 不使用 WebView)。
 *
 * Chrome 的 NTP 是原生 View, 没有扩展 API; 这里在 ChromeTabbedActivity 的
 * content FrameLayout 上叠加一个原生面板(只覆盖 NTP 下半部分, 保留顶部
 * Google 搜索框), 通过 BookmarkModel 读取书签树, 渲染成可搜索/按文件夹
 * 浏览的卡片网格, 点击用 Tab.l(LoadUrlParams) 在 Chrome 内打开。
 */
public final class BookmarkHome {

    private static final String CLS_TABBED = "org.chromium.chrome.browser.ChromeTabbedActivity";
    private static final String CLS_PROFILE_MANAGER =
            "org.chromium.chrome.browser.profiles.ProfileManager";
    private static final String CLS_PROFILE =
            "org.chromium.chrome.browser.profiles.Profile";
    private static final String CLS_BOOKMARK_MODEL =
            "org.chromium.chrome.browser.bookmarks.BookmarkModel";
    private static final String CLS_GURL = "org.chromium.url.GURL";
    private static final String CLS_LOAD_URL_PARAMS =
            "org.chromium.content_public.browser.LoadUrlParams";

    private static final int MAX_NODES = 1200;
    private static final int COLUMNS = 4;

    private static final Map<Activity, Host> sHosts =
            new WeakHashMap<Activity, Host>();

    private BookmarkHome() {
    }

    public static void hook(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(CLS_TABBED, cl, "onResume",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Host host = host((Activity) param.thisObject);
                                if (host != null) host.start();
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG + ": bookmark onResume -> " + t);
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(CLS_TABBED, cl, "onPause",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                Host host = sHosts.get((Activity) param.thisObject);
                                if (host != null) host.stop();
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedHelpers.findAndHookMethod(CLS_TABBED, cl, "onStop",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Activity activity = (Activity) param.thisObject;
                                if (activity.isFinishing()) {
                                    Host host = sHosts.remove(activity);
                                    if (host != null) host.stop();
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG + ": BookmarkHome hooked (NTP bookmark launcher)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": BookmarkHome hook failed -> " + t);
        }
    }

    private static Host host(Activity activity) {
        if (activity == null) return null;
        Host host = sHosts.get(activity);
        if (host == null) {
            View content = activity.findViewById(android.R.id.content);
            if (!(content instanceof FrameLayout)) return null;
            host = new Host(activity, (FrameLayout) content);
            sHosts.put(activity, host);
        }
        return host;
    }

    private static final class Node {
        String title;
        String url;
        String host;
        boolean folder;
        final List<Node> children = new ArrayList<Node>();
    }

    private static final class Data {
        final List<Node> roots = new ArrayList<Node>();
        final List<Node> folders = new ArrayList<Node>();
        final List<Node> all = new ArrayList<Node>();
    }

    private static final class Host implements Runnable {
        final Activity activity;
        final FrameLayout content;
        final Handler handler = new Handler(Looper.getMainLooper());

        LinearLayout panel;
        LinearLayout chips;
        LinearLayout list;
        TextView count;
        TextView crumb;
        TextView empty;
        EditText search;

        final SharedPreferences usage;
        Data data;
        Node current;
        boolean running;
        boolean attached;
        long dataTime;

        final int colorBg;
        final int colorCard;
        final int colorText;
        final int colorMuted;
        final int colorAccent;

        Host(Activity activity, FrameLayout content) {
            this.activity = activity;
            this.content = content;
            boolean dark = (activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            colorBg = dark ? 0xFF1E1F22 : 0xFFFFFFFF;
            colorCard = dark ? 0xFF2B2D31 : 0xFFF2F3F7;
            colorText = dark ? 0xFFF1F3F5 : 0xFF1B1C1E;
            colorMuted = dark ? 0xFF9AA0A6 : 0xFF68707C;
            colorAccent = dark ? 0xFF8AB4F8 : 0xFF1A73E8;
            usage = activity.getSharedPreferences("chrostar_bookmark_usage",
                    Activity.MODE_PRIVATE);
        }

        void start() {
            if (running) return;
            running = true;
            handler.post(this);
        }

        void stop() {
            running = false;
            handler.removeCallbacks(this);
            hide();
        }

        @Override
        public void run() {
            if (!running) return;
            try {
                boolean enabled = HookEntry.readPrefBoolean(
                        HookEntry.KEY_HOMEPAGE_BOOKMARKS, true);
                if (enabled && isNtp(currentUrl())) {
                    show();
                } else {
                    hide();
                }
            } catch (Throwable t) {
                if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG + ": bookmark tick -> " + t);
            }
            if (running) handler.postDelayed(this, 1000L);
        }

        void show() {
            ensurePanel();
            if (!attached) {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT);
                DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                lp.topMargin = (int) (dm.heightPixels * 0.38f);
                int bottomInset = 0;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowInsets wi = activity.getWindow()
                            .getDecorView().getRootWindowInsets();
                    if (wi != null) {
                        bottomInset = wi.getInsets(
                                android.view.WindowInsets.Type.navigationBars()).bottom;
                    }
                }
                lp.bottomMargin = Math.max(bottomInset, dp(20)) + dp(6);
                lp.leftMargin = dp(4);
                lp.rightMargin = dp(4);
                content.addView(panel, lp);
                attached = true;
            }
            panel.bringToFront();
            if (data == null || System.currentTimeMillis() - dataTime > 60000L) {
                loadData();
            }
        }

        void hide() {
            if (attached && panel != null) {
                try {
                    content.removeView(panel);
                } catch (Throwable ignored) {
                }
                attached = false;
            }
        }

        private void ensurePanel() {
            if (panel != null) return;
            panel = new LinearLayout(activity);
            panel.setOrientation(LinearLayout.VERTICAL);
            panel.setPadding(dp(14), dp(8), dp(14), 0);
            panel.setBackground(panelBackground());
            panel.setElevation(dp(16));

            View handle = new View(activity);
            GradientDrawable handleBg = rounded(dp(2), 0x55808080);
            handle.setBackground(handleBg);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(38), dp(4));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            handleLp.bottomMargin = dp(8);
            panel.addView(handle, handleLp);

            LinearLayout header = new LinearLayout(activity);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            panel.addView(header, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView title = text("书签", 20, colorText, true);
            header.addView(title);
            count = text("", 12, colorMuted, false);
            LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            countLp.leftMargin = dp(6);
            header.addView(count, countLp);
            View spacer = new View(activity);
            header.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

            TextView refresh = text("\u21bb", 24, colorMuted, false);
            refresh.setGravity(Gravity.CENTER);
            refresh.setContentDescription("刷新书签");
            refresh.setPadding(dp(8), dp(2), dp(8), dp(2));
            refresh.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    data = null;
                    loadData();
                }
            });
            header.addView(refresh);

            search = new EditText(activity);
            search.setSingleLine(true);
            search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            search.setTextColor(colorText);
            search.setHintTextColor(colorMuted);
            search.setHint("搜索书签");
            search.setPadding(dp(14), dp(8), dp(14), dp(8));
            search.setBackground(rounded(dp(14), colorCard));
            search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            searchLp.topMargin = dp(10);
            searchLp.bottomMargin = dp(8);
            search.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) {
                    renderGrid();
                }
            });
            panel.addView(search, searchLp);

            HorizontalScrollView hsv = new HorizontalScrollView(activity);
            hsv.setHorizontalScrollBarEnabled(false);
            hsv.setOverScrollMode(View.OVER_SCROLL_NEVER);
            chips = new LinearLayout(activity);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            hsv.addView(chips, new HorizontalScrollView.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams chipsLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            chipsLp.bottomMargin = dp(6);
            panel.addView(hsv, chipsLp);

            crumb = text("", 12, colorMuted, false);
            crumb.setPadding(dp(2), 0, dp(2), dp(6));
            crumb.setVisibility(View.GONE);
            crumb.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    current = null;
                    search.setText("");
                    renderGrid();
                }
            });
            panel.addView(crumb, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            ScrollView scroll = new ScrollView(activity);
            scroll.setFillViewport(true);
            scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
            list = new LinearLayout(activity);
            list.setOrientation(LinearLayout.VERTICAL);
            list.setPadding(0, 0, 0, dp(22));
            scroll.addView(list, new ScrollView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            empty = text("正在读取 Chrome 书签…", 13, colorMuted, false);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10), dp(30), dp(10), dp(30));
            empty.setVisibility(View.GONE);
            panel.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            panel.addView(scroll, scrollLp);
            panel.setAlpha(0.99f);
        }

        private GradientDrawable panelBackground() {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(colorBg);
            float r = dp(24);
            gd.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
            return gd;
        }

        void loadData() {
            try {
                Data d = buildData(activity.getClass().getClassLoader());
                if (d == null) {
                    empty.setVisibility(View.VISIBLE);
                    empty.setText("书签加载中…");
                    if (running) {
                        handler.postDelayed(new Runnable() {
                            @Override public void run() {
                                if (running && data == null) loadData();
                            }
                        }, 1200L);
                    }
                    return;
                }
                data = d;
                dataTime = System.currentTimeMillis();
                current = null;
                if (search != null) search.setText("");
                renderChips();
                renderGrid();
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": bookmark loadData -> " + t);
            }
        }

        private void renderChips() {
            chips.removeAllViews();
            addChip("全部", current == null, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    current = null;
                    if (search != null) search.setText("");
                    renderChips();
                    renderGrid();
                }
            });
            if (data == null) return;
            for (final Node f : data.folders) {
                addChip(f.title, current == f, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        current = f;
                        if (search != null) search.setText("");
                        renderChips();
                        renderGrid();
                    }
                });
            }
        }

        private void addChip(String label, boolean selected, View.OnClickListener click) {
            TextView chip = text(label, 13, selected ? Color.WHITE : colorText, false);
            chip.setPadding(dp(13), dp(7), dp(13), dp(7));
            chip.setBackground(rounded(dp(20), selected ? colorAccent : colorCard));
            chip.setClickable(true);
            chip.setOnClickListener(click);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chips.addView(chip, lp);
        }

        private void renderGrid() {
            if (list == null) return;
            list.removeAllViews();
            if (crumb != null) {
                if (current != null) {
                    crumb.setVisibility(View.VISIBLE);
                    crumb.setText("\u2190  " + current.title);
                } else {
                    crumb.setVisibility(View.GONE);
                }
            }
            if (data == null) {
                empty.setVisibility(View.VISIBLE);
                empty.setText("正在读取 Chrome 书签…");
                return;
            }
            List<Node> items;
            String q = search == null ? "" : search.getText().toString().trim().toLowerCase();
            if (!TextUtils.isEmpty(q)) {
                items = new ArrayList<Node>();
                for (Node n : data.all) {
                    if (n.title != null && n.title.toLowerCase().contains(q)) {
                        items.add(n);
                    } else if (n.url != null && n.url.toLowerCase().contains(q)) {
                        items.add(n);
                    }
                }
            } else if (current != null) {
                items = current.children;
            } else {
                // 全部视图按使用频率/最近点击排序, 模拟 Markoob 的推荐逻辑
                items = new ArrayList<Node>(data.all);
                Collections.sort(items, new Comparator<Node>() {
                    @Override public int compare(Node a, Node b) {
                        int ca = usage(a.url), cb = usage(b.url);
                        if (ca != cb) return cb - ca;
                        long ta = usageTime(a.url), tb = usageTime(b.url);
                        if (ta != tb) return ta > tb ? -1 : 1;
                        return 0;
                    }
                });
            }
            count.setText(" \u00b7 " + data.all.size());
            empty.setVisibility(items.isEmpty() ? View.VISIBLE : View.GONE);
            if (items.isEmpty()) {
                empty.setText(TextUtils.isEmpty(q) ? "还没有书签" : "没有匹配的书签");
                return;
            }
            for (int i = 0; i < items.size(); i += COLUMNS) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setWeightSum(COLUMNS);
                for (int c = 0; c < COLUMNS; c++) {
                    int idx = i + c;
                    if (idx < items.size()) {
                        View card = createCard(items.get(idx));
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
                        row.addView(card, lp);
                    } else {
                        View gap = new View(activity);
                        row.addView(gap, new LinearLayout.LayoutParams(0, 1, 1f));
                    }
                }
                list.addView(row);
            }
        }

        private View createCard(final Node n) {
            LinearLayout card = new LinearLayout(activity);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(5), dp(10), dp(5), dp(8));
            card.setMinimumHeight(dp(104));
            card.setClickable(true);
            card.setBackground(cardBackground());

            TextView icon = new TextView(activity);
            icon.setGravity(Gravity.CENTER);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, n.folder ? 20 : 15);
            icon.setTextColor(Color.WHITE);
            if (n.folder) {
                icon.setText("\ud83d\udcc1");
                icon.setBackground(rounded(dp(10), 0xFFE9A23B));
            } else {
                char c = (n.title == null || n.title.isEmpty()) ? '?' : n.title.charAt(0);
                icon.setText(String.valueOf(Character.toUpperCase(c)));
                icon.setTypeface(Typeface.DEFAULT_BOLD);
                icon.setBackground(rounded(dp(10), colorFor(n.url)));
            }
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            iconLp.bottomMargin = dp(7);
            card.addView(icon, iconLp);

            TextView title = text(n.title == null ? "" : n.title, 12, colorText, false);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER);
            title.setLineSpacing(0f, 1.08f);
            card.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView host = text(n.folder ? n.children.size() + " 项" : n.host, 10,
                    colorMuted, false);
            host.setMaxLines(1);
            host.setEllipsize(TextUtils.TruncateAt.END);
            host.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hostLp.topMargin = dp(3);
            card.addView(host, hostLp);

            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (n.folder) {
                        current = n;
                        if (search != null) search.setText("");
                        renderChips();
                        renderGrid();
                    } else {
                        recordUsage(n.url);
                        openUrl(n.url);
                    }
                }
            });
            return card;
        }

        private int usage(String url) {
            if (url == null) return 0;
            return usage.getInt("c:" + url, 0);
        }

        private long usageTime(String url) {
            if (url == null) return 0L;
            return usage.getLong("t:" + url, 0L);
        }

        private void recordUsage(String url) {
            if (url == null) return;
            usage.edit()
                    .putInt("c:" + url, usage(url) + 1)
                    .putLong("t:" + url, System.currentTimeMillis())
                    .apply();
        }

        private RippleDrawable cardBackground() {
            GradientDrawable content = rounded(dp(16), colorCard);
            return new RippleDrawable(ColorStateList.valueOf(0x22808080), content, null);
        }

        String currentUrl() {
            try {
                Object selector = HomeCleaner.findTabModelSelector(activity);
                if (selector == null) return null;
                Object tab = XposedHelpers.callMethod(selector, "i");
                if (tab == null) return null;
                Object incognito = XposedHelpers.callMethod(tab, "isIncognito");
                if (Boolean.TRUE.equals(incognito)) return null;
                Object gurl = XposedHelpers.callMethod(tab, "getUrl");
                if (gurl == null) return null;
                Object spec = XposedHelpers.getObjectField(gurl, "a");
                return spec instanceof String ? (String) spec : null;
            } catch (Throwable t) {
                return null;
            }
        }

        void openUrl(String url) {
            if (url == null) return;
            if (!(url.startsWith("http://") || url.startsWith("https://"))) return;
            try {
                ClassLoader cl = activity.getClass().getClassLoader();
                Class<?> lpCls = Class.forName(CLS_LOAD_URL_PARAMS, false, cl);
                Object params = lpCls.getConstructor(String.class, int.class)
                        .newInstance(url, Integer.valueOf(0));
                Object selector = HomeCleaner.findTabModelSelector(activity);
                Object tab = selector == null ? null : XposedHelpers.callMethod(selector, "i");
                if (tab != null) {
                    XposedHelpers.callMethod(tab, "l", params);
                } else if (selector != null) {
                    Object model = XposedHelpers.callMethod(selector, "k", Boolean.FALSE);
                    Class<?> gurlCls = Class.forName(CLS_GURL, false, cl);
                    Object gurl = gurlCls.getConstructor(String.class).newInstance(url);
                    XposedHelpers.callMethod(model, "openTabProgrammatically",
                            gurl, Integer.valueOf(2), Boolean.FALSE);
                }
                hide();
                XposedBridge.log(HookEntry.TAG + ": bookmark open -> " + url);
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": bookmark open failed -> " + t);
            }
        }

        int dp(int v) {
            return (int) (v * activity.getResources().getDisplayMetrics().density + 0.5f);
        }

        TextView text(String s, int sp, int color, boolean bold) {
            TextView tv = new TextView(activity);
            tv.setText(s);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            tv.setTextColor(color);
            if (bold) tv.setTypeface(Typeface.DEFAULT_BOLD);
            return tv;
        }

        GradientDrawable rounded(int radiusPx, int color) {
            GradientDrawable gd = new GradientDrawable();
            gd.setColor(color);
            gd.setCornerRadius(radiusPx);
            return gd;
        }

        int colorFor(String url) {
            int h = 0;
            if (url != null) {
                for (int i = 0; i < url.length(); i++) h = h * 31 + url.charAt(i);
            }
            int[] palette = {0xFF4285F4, 0xFFEA4335, 0xFF34A853, 0xFFFBBC05,
                    0xFF7E57C2, 0xFF00897B, 0xFFEF6C00};
            return palette[Math.abs(h) % palette.length];
        }
    }

    private static boolean isNtp(String url) {
        if (url == null) return false;
        return url.startsWith("chrome-native://newtab")
                || url.startsWith("chrome://newtab")
                || url.startsWith("about:newtab")
                || url.contains("local-ntp");
    }

    private static Data buildData(ClassLoader cl) {
        try {
            Class<?> pm = Class.forName(CLS_PROFILE_MANAGER, false, cl);
            Object profile = pm.getMethod("b").invoke(null);
            if (profile == null) return null;
            Class<?> modelCls = Class.forName(CLS_BOOKMARK_MODEL, false, cl);
            Class<?> profileCls = Class.forName(CLS_PROFILE, false, cl);
            Object model = modelCls.getMethod("z", profileCls).invoke(null, profile);
            if (model == null) return null;
            long ptr = XposedHelpers.getLongField(model, "c");
            if (ptr == 0L) return null;

            Data data = new Data();
            int[] budget = {MAX_NODES};
            String[] rootMethods = {"g", "o", "q", "r"};
            List<String> seen = new ArrayList<String>();
            for (String rn : rootMethods) {
                Object id;
                try {
                    id = XposedHelpers.callMethod(model, rn);
                } catch (Throwable t) {
                    continue;
                }
                if (id == null) continue;
                String key = String.valueOf(id);
                if (seen.contains(key)) continue;
                seen.add(key);
                Object item = XposedHelpers.callMethod(model, "j", id);
                if (item == null) continue;
                Node root = new Node();
                root.folder = true;
                root.title = stringField(item, "a", "书签");
                Object children = XposedHelpers.callMethod(model, "l", id);
                if (children instanceof ArrayList) {
                    for (Object child : (ArrayList<?>) children) {
                        addNode(model, childId(child), root.children, 0, budget);
                    }
                }
                if (!root.children.isEmpty()) {
                    data.roots.add(root);
                    for (Node n : root.children) {
                        if (n.folder) data.folders.add(n);
                    }
                }
            }
            flatten(data.roots, data.all);
            return data;
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": bookmark buildData -> " + t);
            return null;
        }
    }

    private static void addNode(Object model, Object id, List<Node> out, int depth, int[] budget) {
        if (id == null || depth > 5 || budget[0] <= 0) return;
        try {
            Object item = XposedHelpers.callMethod(model, "j", id);
            if (item == null) return;
            Object folderObj = XposedHelpers.getObjectField(item, "d");
            boolean folder = Boolean.TRUE.equals(folderObj);
            Node node = new Node();
            node.folder = folder;
            node.title = stringField(item, "a", "(未命名)");
            Object children = folder ? XposedHelpers.callMethod(model, "l", id) : null;
            if (folder) {
                if (children instanceof ArrayList) {
                    for (Object child : (ArrayList<?>) children) {
                        addNode(model, childId(child), node.children, depth + 1, budget);
                    }
                }
                if (node.children.isEmpty() && depth > 1) return;
            } else {
                Object gurl = XposedHelpers.getObjectField(item, "b");
                String url = null;
                if (gurl != null) {
                    Object spec = XposedHelpers.getObjectField(gurl, "a");
                    if (spec instanceof String) url = (String) spec;
                }
                if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
                    return;
                }
                node.url = url;
                node.host = hostOf(url);
            }
            out.add(node);
            budget[0]--;
        } catch (Throwable t) {
            if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG + ": bookmark node -> " + t);
        }
    }

    private static void flatten(List<Node> nodes, List<Node> out) {
        for (Node n : nodes) {
            if (n.folder) {
                flatten(n.children, out);
            } else if (n.url != null) {
                out.add(n);
            }
        }
    }

    private static Object childId(Object child) {
        if (child == null) return null;
        if (child.getClass().getName().endsWith("BookmarkId")) return child;
        try {
            return XposedHelpers.getObjectField(child, "c");
        } catch (Throwable t) {
            return null;
        }
    }

    private static String stringField(Object obj, String field, String def) {
        try {
            Object v = XposedHelpers.getObjectField(obj, field);
            if (v instanceof String && !((String) v).isEmpty()) return (String) v;
        } catch (Throwable ignored) {
        }
        return def;
    }

    private static String hostOf(String url) {
        try {
            java.net.URL u = new java.net.URL(url);
            String h = u.getHost();
            if (h == null) return "";
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Throwable t) {
            return "";
        }
    }
}
