package io.github.ylw6669.chrostar;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
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
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int COLUMNS = 2;

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
        LinearLayout crumbs;
        TextView empty;
        EditText search;
        View handle;
        TextView floatBtn;

        /* 导航栈替代单 current：面包屑可跳级 */
        final List<Node> navStack = new ArrayList<Node>();
        float panelRatio = 0.42f;
        boolean hiddenByUser;

        final SharedPreferences usage;
        Data data;

        Node current() {
            return navStack.isEmpty() ? null : navStack.get(navStack.size() - 1);
        }
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
                if (enabled && !hiddenByUser && isNtp(currentUrl())) {
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
                panelRatio = usage.getFloat("panel_ratio", 0.42f);
                lp.topMargin = (int) (dm.heightPixels * (1f - panelRatio));
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
            if (floatBtn != null) {
                try {
                    content.removeView(floatBtn);
                } catch (Throwable ignored) {
                }
            }
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

            handle = new View(activity);
            GradientDrawable handleBg = rounded(dp(2), 0x55808080);
            handle.setBackground(handleBg);
            LinearLayout.LayoutParams handleLp = new LinearLayout.LayoutParams(dp(56), dp(20));
            handleLp.gravity = Gravity.CENTER_HORIZONTAL;
            panel.addView(handle, handleLp);
            setupHandleDrag();

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

            crumbs = new LinearLayout(activity);
            crumbs.setOrientation(LinearLayout.HORIZONTAL);
            crumbs.setPadding(dp(4), 0, dp(4), dp(6));
            crumbs.setVisibility(View.GONE);
            panel.addView(crumbs, new LinearLayout.LayoutParams(
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

        /* ── 拖拽调高 + 单击隐藏 + 浮标恢复 ────────────────────────────── */
        private void setupHandleDrag() {
            final float[] downY = {0f};
            final boolean[] dragging = {false};
            handle.setOnTouchListener(new View.OnTouchListener() {
                @Override public boolean onTouch(View v, android.view.MotionEvent ev) {
                    DisplayMetrics dm = activity.getResources().getDisplayMetrics();
                    switch (ev.getActionMasked()) {
                        case android.view.MotionEvent.ACTION_DOWN:
                            downY[0] = ev.getRawY();
                            dragging[0] = false;
                            return true;
                        case android.view.MotionEvent.ACTION_MOVE:
                            float dy = ev.getRawY() - downY[0];
                            if (!dragging[0] && Math.abs(dy) > dp(10)) dragging[0] = true;
                            if (dragging[0]) {
                                downY[0] = ev.getRawY();
                                ViewGroup.LayoutParams lpRaw = panel.getLayoutParams();
                                if (lpRaw instanceof FrameLayout.LayoutParams) {
                                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lpRaw;
                                    lp.topMargin = (int) Math.max(
                                            dm.heightPixels * 0.10f,
                                            Math.min(lp.topMargin + dy, dm.heightPixels * 0.70f));
                                    panel.setLayoutParams(lp);
                                }
                            }
                            return true;
                        case android.view.MotionEvent.ACTION_UP:
                            if (dragging[0]) {
                                DisplayMetrics dm2 = activity.getResources().getDisplayMetrics();
                                ViewGroup.LayoutParams lpRaw = panel.getLayoutParams();
                                if (lpRaw instanceof FrameLayout.LayoutParams) {
                                    FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) lpRaw;
                                    float ratio = 1f - (float) lp.topMargin / dm2.heightPixels;
                                    panelRatio = Math.max(0.30f, Math.min(0.90f, ratio));
                                    usage.edit().putFloat("panel_ratio", panelRatio).apply();
                                }
                            } else {
                                toggleHide();
                            }
                            return true;
                    }
                    return false;
                }
            });
        }

        /* 单击手柄隐藏面板，NTP 右下角留一枚书签浮钮，点击恢复 */
        private void toggleHide() {
            hiddenByUser = true;
            hide();
            if (floatBtn != null) return;
            floatBtn = text("\u2605", 18, Color.WHITE, true);
            floatBtn.setGravity(Gravity.CENTER);
            floatBtn.setBackground(rounded(dp(20), colorAccent));
            floatBtn.setElevation(dp(10));
            floatBtn.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    hiddenByUser = false;
                    show();
                }
            });
            try {
                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(40), dp(40));
                lp.gravity = Gravity.BOTTOM | Gravity.END;
                int inset = 0;
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    android.view.WindowInsets wi = activity.getWindow()
                            .getDecorView().getRootWindowInsets();
                    if (wi != null) inset = wi.getInsets(
                            android.view.WindowInsets.Type.navigationBars()).bottom;
                }
                lp.bottomMargin = Math.max(inset, dp(12)) + dp(18);
                lp.rightMargin = dp(16);
                content.addView(floatBtn, lp);
            } catch (Throwable ignored) {
            }
        }

        /* ── 面包屑导航（栈渲染，点段跳级） ────────────────────────────── */
        private void renderCrumbs() {
            if (crumbs == null) return;
            crumbs.removeAllViews();
            if (navStack.isEmpty()) {
                crumbs.setVisibility(View.GONE);
                return;
            }
            crumbs.setVisibility(View.VISIBLE);
            crumbs.addView(crumbSeg("\u9996\u9875", -1));
            for (int i = 0; i < navStack.size(); i++) {
                TextView sep = text(" \u203a ", 12, colorMuted, false);
                crumbs.addView(sep);
                crumbs.addView(crumbSeg(navStack.get(i).title, i));
            }
        }

        private TextView crumbSeg(String label, final int index) {
            TextView t = text(label, 12, index == navStack.size() - 1 ? colorText : colorMuted,
                    index == navStack.size() - 1);
            t.setPadding(dp(2), dp(3), dp(2), dp(3));
            if (index < navStack.size() - 1 || index == -1) {
                t.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        while (navStack.size() > index + 1) navStack.remove(navStack.size() - 1);
                        if (search != null) search.setText("");
                        renderCrumbs();
                        renderGrid();
                    }
                });
            }
            return t;
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
                navStack.clear();
                if (search != null) search.setText("");
                renderChips();
                renderGrid();
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": bookmark loadData -> " + t);
            }
        }

        private void renderChips() {
            chips.removeAllViews();
            addChip("全部", current() == null, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    navStack.clear();
                    if (search != null) search.setText("");
                    renderChips();
                    renderCrumbs();
                    renderGrid();
                }
            });
            if (data == null) return;
            for (final Node f : data.folders) {
                addChip(f.title, current() == f, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        navStack.clear(); navStack.add(f);
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

        /* ── Tabmark 逻辑：权重搜索 + 文件夹聚合分节视图 ────────────────── */

        /* Tabmark matchScore: 标题前缀100 / 标题包含60 / 域名前缀45 /
           域名包含35 / URL包含20；文件夹只按名称包含50。 */
        private int matchScore(Node n, String q) {
            if (q.isEmpty()) return 0;
            String title = (n.title == null ? "" : n.title).toLowerCase();
            int s = 0;
            if (n.folder) {
                return title.contains(q) ? 50 : 0;
            }
            String url = n.url == null ? "" : n.url.toLowerCase();
            String host = n.host == null ? "" : n.host.toLowerCase();
            if (title.startsWith(q)) s = 100;
            else if (title.contains(q)) s = 60;
            if (host.startsWith(q)) s = Math.max(s, 45);
            else if (host.contains(q)) s = Math.max(s, 35);
            if (s == 0 && url.contains(q)) s = 20;
            return s;
        }

        /* Tabmark byScore: 分数降序 -> 使用次数降序 -> 原始顺序 */
        private void sortByScore(List<Node> items, final String q) {
            Collections.sort(items, new Comparator<Node>() {
                @Override public int compare(Node a, Node b) {
                    int sa = matchScore(a, q), sb = matchScore(b, q);
                    if (sa != sb) return sb - sa;
                    int ca = usage(a.url), cb = usage(b.url);
                    if (ca != cb) return cb - ca;
                    return 0;
                }
            });
        }

        private void renderGrid() {
            if (list == null) return;
            list.removeAllViews();
            renderCrumbs();
            if (data == null) {
                empty.setVisibility(View.VISIBLE);
                empty.setText("正在读取 Chrome 书签…");
                return;
            }
            String q = searchText();
            List<Node> items;
            if (!TextUtils.isEmpty(q)) {
                // 搜索：全库权重排序（Tabmark searchBookmarks）
                items = new ArrayList<Node>();
                for (Node n : data.all) {
                    if (matchScore(n, q) > 0) items.add(n);
                }
                sortByScore(items, q);
                renderCardGrid(items);
            } else if (current() != null) {
                // 聚合视图：按子路径分节铺开（含嵌套）
                count.setText(" \u00b7 " + countDirectBookmarks(current()));
                renderAggregate(current());
            } else {
                // 全部视图：按使用频率/最近点击（模拟 Tabmark 本地推荐）
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
                count.setText(" \u00b7 " + data.all.size());
                // 首页直达：常用书签（使用过的）置顶成节，其余跟后
                List<Node> frequent = new ArrayList<Node>();
                List<Node> rest = new ArrayList<Node>();
                for (Node n : items) {
                    if (usage(n.url) > 0 && frequent.size() < 8) frequent.add(n);
                    else rest.add(n);
                }
                if (!frequent.isEmpty()) {
                    LinearLayout head = new LinearLayout(activity);
                    head.setOrientation(LinearLayout.HORIZONTAL);
                    head.setGravity(Gravity.CENTER_VERTICAL);
                    head.setPadding(dp(2), dp(8), dp(2), dp(4));
                    head.addView(text("\u5e38\u7528", 13, colorText, true));
                    TextView fc = text(" \u00b7 \u4e00\u952e\u76f4\u8fbe", 11, colorMuted, false);
                    LinearLayout.LayoutParams fcLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    fcLp.leftMargin = dp(6);
                    head.addView(fc, fcLp);
                    list.addView(head, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                    renderCardGrid(frequent);
                    LinearLayout head2 = new LinearLayout(activity);
                    head2.setOrientation(LinearLayout.HORIZONTAL);
                    head2.setGravity(Gravity.CENTER_VERTICAL);
                    head2.setPadding(dp(2), dp(10), dp(2), dp(4));
                    head2.addView(text("\u5168\u90e8\u4e66\u7b7e", 13, colorText, true));
                    TextView rc = text(" \u00b7 " + rest.size(), 12, colorMuted, false);
                    LinearLayout.LayoutParams rcLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rcLp.leftMargin = dp(6);
                    head2.addView(rc, rcLp);
                    list.addView(head2, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                renderCardGrid(rest);
            }
            empty.setVisibility(View.GONE);
        }

        private String searchText() {
            return search == null ? "" : search.getText().toString().trim().toLowerCase();
        }

        private int countDirectBookmarks(Node folder) {
            int c = 0;
            for (Node n : folder.children) if (!n.folder) c++;
            return c;
        }

        /* 聚合视图：本层直接书签成一节（无标题），每个子文件夹深度优先
           展开成一节（节标题 = 相对路径，随层级缩进）—— Tabmark 的
           文件夹聚合视图逻辑。 */
        private void renderAggregate(Node folder) {
            List<Node> direct = new ArrayList<Node>();
            for (Node n : folder.children) if (!n.folder) direct.add(n);
            if (!direct.isEmpty()) {
                renderCardGrid(direct);
            }
            for (Node c : folder.children) {
                if (c.folder) walkSections(c.children, c.title, 0);
            }
        }

        private void walkSections(List<Node> children, String path, int depth) {
            List<Node> direct = new ArrayList<Node>();
            for (Node n : children) if (!n.folder) direct.add(n);
            for (Node c : children) {
                if (c.folder) walkSections(c.children, path + " / " + c.title, depth + 1);
            }
            if (direct.isEmpty()) return;
            // 节标题（对齐 Tabmark sec-head）：路径 + 计数，随层级缩进
            LinearLayout head = new LinearLayout(activity);
            head.setOrientation(LinearLayout.HORIZONTAL);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPadding(dp(2) + dp(10) * depth, dp(10), dp(2), dp(4));
            TextView st = text(path, 13, colorText, true);
            head.addView(st);
            TextView sc = text(" \u00b7 " + direct.size(), 12, colorMuted, false);
            LinearLayout.LayoutParams scLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            scLp.leftMargin = dp(6);
            head.addView(sc, scLp);
            list.addView(head, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            renderCardGrid(direct);
        }

        /* 2 列横版卡片网格（Tabmark 桌面卡片布局） */
        private void renderCardGrid(List<Node> items) {
            if (items.isEmpty()) return;
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
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(9), dp(8), dp(9), dp(8));
            card.setMinimumHeight(dp(56));
            card.setClickable(true);
            card.setBackground(cardBackground());

            TextView icon = new TextView(activity);
            icon.setGravity(Gravity.CENTER);
            icon.setTextSize(TypedValue.COMPLEX_UNIT_SP, n.folder ? 16 : 14);
            icon.setTextColor(Color.WHITE);
            if (n.folder) {
                icon.setText("\u25b8");
                icon.setTypeface(Typeface.DEFAULT_BOLD);
                icon.setBackground(rounded(dp(10), 0xFFE9A23B));
            } else {
                char ch = (n.title == null || n.title.isEmpty()) ? '?' : n.title.charAt(0);
                icon.setText(String.valueOf(Character.toUpperCase(ch)));
                icon.setTypeface(Typeface.DEFAULT_BOLD);
                icon.setBackground(rounded(dp(10), colorFor(n.url)));
            }
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp(34), dp(34));
            iconLp.rightMargin = dp(10);
            card.addView(icon, iconLp);

            LinearLayout texts = new LinearLayout(activity);
            texts.setOrientation(LinearLayout.VERTICAL);

            TextView title = text(n.title == null ? "" : n.title, 13, colorText, false);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setMaxLines(1);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setLetterSpacing(-0.01f);
            texts.addView(title, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView host = text(n.folder ? n.children.size() + " 项" : n.host, 11,
                    colorMuted, false);
            host.setMaxLines(1);
            host.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams hostLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            hostLp.topMargin = dp(2);
            texts.addView(host, hostLp);

            card.addView(texts, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (n.folder) {
                        navStack.add(n);
                        if (search != null) search.setText("");
                        renderChips();
                        renderGrid();
                    } else {
                        recordUsage(n.url);
                        openUrl(n.url);
                    }
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    if (n.folder) return false;
                    showBookmarkMenu(v, n);
                    return true;
                }
            });
            return card;
        }

        /* ── 长按书签卡片: 复制链接 / 分享 / 复制标题 ──────────────────────── */
        private void showBookmarkMenu(View anchor, final Node n) {
            try {
                PopupMenu menu = new PopupMenu(activity, anchor);
                menu.getMenu().add(0, 1, 0, "\u590d\u5236\u94fe\u63a5");
                menu.getMenu().add(0, 2, 0, "\u5206\u4eab\u94fe\u63a5");
                menu.getMenu().add(0, 3, 0, "\u590d\u5236\u6807\u9898");
                menu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override public boolean onMenuItemClick(android.view.MenuItem item) {
                        switch (item.getItemId()) {
                            case 1:
                                copyText(n.url);
                                toast("\u5df2\u590d\u5236\u94fe\u63a5");
                                break;
                            case 2:
                                shareUrl(n.title, n.url);
                                break;
                            case 3:
                                copyText(n.title);
                                toast("\u5df2\u590d\u5236\u6807\u9898");
                                break;
                            default:
                                break;
                        }
                        return true;
                    }
                });
                menu.show();
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": bookmark menu -> " + t);
            }
        }

        private void copyText(String text) {
            if (text == null || text.isEmpty()) return;
            try {
                ClipboardManager cm = (ClipboardManager) activity
                        .getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("tabmark", text));
                }
            } catch (Throwable ignored) {
            }
        }

        private void shareUrl(String title, String url) {
            if (url == null) return;
            try {
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("text/plain");
                send.putExtra(Intent.EXTRA_TEXT, url);
                if (title != null && !title.isEmpty()) {
                    send.putExtra(Intent.EXTRA_SUBJECT, title);
                }
                activity.startActivity(Intent.createChooser(send, "\u5206\u4eab\u94fe\u63a5"));
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": bookmark share -> " + t);
            }
        }

        private void toast(String msg) {
            try {
                Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            } catch (Throwable ignored) {
            }
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
            GradientDrawable content = rounded(dp(14), colorCard);
            return new RippleDrawable(ColorStateList.valueOf(0x22808080), content, null);
        }

        String currentUrl() {
            try {
                Object selector = HomeCleaner.findTabModelSelector(activity);
                if (selector == null) return null;
                // 157/158: selector.i() 返回 TabModel, 当前 Tab 是 j();
                // 156 及以前 i() 本身就是 Tab(方法名被 R8 整体重排过)
                Object tab = XposedHelpers.callMethod(selector,
                        (HookEntry.is158() || HookEntry.is157()) ? "j" : "i");
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

        /**
         * Tab.loadUrl(LoadUrlParams) 的 R8 名每版都变：156=l，157=m，158=r(两参)。
         * 按签名依次尝试，失败再换下一个 —— 这样下一版改名也不会让书签点不动。
         */
        private void loadUrlOnTab(Object tab, Object params) {
            String[] oneArg = {"m", "l", "r", "b"};
            for (String mn : oneArg) {
                try {
                    XposedHelpers.callMethod(tab, mn, params);
                    return;
                } catch (Throwable ignored) {
                }
            }
            // 158 风格: r(LoadUrlParams, String postData)
            try {
                XposedHelpers.callMethod(tab, "r", params, (String) null);
                return;
            } catch (Throwable ignored) {
            }
            XposedBridge.log(HookEntry.TAG + ": bookmark open -> no Tab.loadUrl match");
        }

        /** TabModel.openTabProgrammatically 第三参各版不同(boolean / int)，逐个试 */
        private void openTabProgrammatically(Object model, Object gurl) {
            try {
                XposedHelpers.callMethod(model, "openTabProgrammatically",
                        gurl, Integer.valueOf(2), Boolean.FALSE);
                return;
            } catch (Throwable ignored) {
            }
            try {
                XposedHelpers.callMethod(model, "openTabProgrammatically",
                        gurl, Integer.valueOf(2), Integer.valueOf(-1));
            } catch (Throwable t) {
                XposedBridge.log(HookEntry.TAG + ": bookmark open -> openTabProgrammatically failed -> " + t);
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
                Object tab = selector == null ? null
                        : XposedHelpers.callMethod(selector,
                        (HookEntry.is158() || HookEntry.is157()) ? "j" : "i");
                if (tab != null) {
                    loadUrlOnTab(tab, params);
                } else if (selector != null) {
                    Object model = XposedHelpers.callMethod(selector,
                            (HookEntry.is158() || HookEntry.is157()) ? "l" : "k",
                            Boolean.FALSE);
                    Class<?> gurlCls = Class.forName(CLS_GURL, false, cl);
                    Object gurl = gurlCls.getConstructor(String.class).newInstance(url);
                    openTabProgrammatically(model, gurl);
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

        /* Tabmark 的 hue 色块：h*31 哈希 -> HSL 柔和底色，白字首字母 */
        int colorFor(String url) {
            int h = 0;
            if (url != null) {
                for (int i = 0; i < url.length(); i++) h = h * 31 + url.charAt(i);
            }
            float hue = Math.abs(h) % 360;
            float[] hsv = {hue, 0.42f, 0.62f};
            return Color.HSVToColor(0xFF, hsv);
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
            /* Chrome 156 的 BookmarkBridge 有 10 个无参返回 BookmarkId 的根
               getter（native 分派 ID 19~30）。旧代码只取 g/o/q/r，漏掉了
               「其他书签」所在的根 —— 实测账号有 214 条书签只读到 188 条
               （184 书签栏 + 4 移动设备，其他书签 25 条全丢）。
               现在遍历全部 getter，再排除“父根”（children 里含其它根 id 的
               root 节点），既补齐漏读又不会重复。 */
            /* 主路径：直接走 native 分派 J.N.OJ(int id, long ptr)。
               每个 Java 根 getter 的实现就是 OJ(ID, ptr)，ID 见 dex：
               g=19 h=20 i=22 m=23 n=24 o=25 p=26 q=27 r=28 t=30。
               直接调 OJ 可绕开 R8 把 getter 内联/降级导致反射取不到的问题
               （实测账号「其他书签」的 t 就取不到，25 条书签全丢）。 */
            List<Object> rootIds = new ArrayList<Object>();
            Set<String> rootSeen = new HashSet<String>();
            Class<?> jnCls = null;
            try {
                jnCls = Class.forName("J.N", false, cl);
            } catch (Throwable ignored) {
            }
            if (jnCls != null) {
                // 1) 本地根（native id 25~28）：记录下来用于排除
                Set<String> localRoots = new HashSet<String>();
                for (int rid : new int[]{25, 26, 27, 28}) {
                    try {
                        Object id = XposedHelpers.callStaticMethod(jnCls, "OJ",
                                Integer.valueOf(rid), Long.valueOf(ptr));
                        if (id != null) localRoots.add(String.valueOf(id));
                    } catch (Throwable ignored) {
                    }
                }
                // 2) root 汇总节点（native id 30）的 children = 全部根。
                //    排除本地根后即为「Google 账号书签」的所有根 —— 账号的
                //    「其他书签」没有独立 getter（t 被 R8 内联），只能从
                //    root.children 拿到，这是 25 条书签丢失的真正原因。
                Object rootNode = null;
                try {
                    rootNode = XposedHelpers.callStaticMethod(jnCls, "OJ",
                            Integer.valueOf(30), Long.valueOf(ptr));
                } catch (Throwable ignored) {
                }
                if (rootNode != null) {
                    Object children = XposedHelpers.callMethod(model, "l", rootNode);
                    if (children instanceof ArrayList) {
                        for (Object child : (ArrayList<?>) children) {
                            Object cid = childId(child);
                            if (cid == null) continue;
                            String k = String.valueOf(cid);
                            if (localRoots.contains(k)) continue;   // 跳过本地根
                            if (!rootSeen.add(k)) continue;
                            rootIds.add(cid);
                            if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG + ": bm acct root " + k);
                        }
                    }
                }
            }
            // 回退：native 路径不可用时走 Java getter 枚举
            String[] rootMethods = {"g", "h", "i", "m", "n", "o", "p", "q", "r", "t"};
            if (rootIds.isEmpty()) {
            for (String rn : rootMethods) {
                Object id = null;
                try {
                    /* 精确匹配「无参 + 返回 BookmarkId」，并且递归父类用
                       getDeclaredMethods —— R8 会把部分 getter 降为
                       package-private（如账号「其他书签」的 t），
                       getMethods() 拿不到，会造成整根书签丢失。 */
                    java.lang.reflect.Method target = null;
                    Class<?> cc = model.getClass();
                    while (cc != null && target == null) {
                        for (java.lang.reflect.Method mm : cc.getDeclaredMethods()) {
                            if (mm.getName().equals(rn)
                                    && mm.getParameterTypes().length == 0
                                    && mm.getReturnType().getName().endsWith("BookmarkId")) {
                                target = mm;
                                break;
                            }
                        }
                        cc = cc.getSuperclass();
                    }
                    if (target == null) {
                        if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG + ": bm root " + rn + " -> not found");
                        continue;
                    }
                    target.setAccessible(true);
                    id = target.invoke(model);
                } catch (Throwable t) {
                    if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG + ": bm root " + rn + " -> err " + t);
                    continue;
                }
                if (id == null) {
                    if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG + ": bm root " + rn + " -> null");
                    continue;
                }
                if (!rootSeen.add(String.valueOf(id))) {
                    if (HookEntry.DEBUG) XposedBridge.log(HookEntry.TAG + ": bm root " + rn + " -> dup " + id);
                    continue;
                }
                rootIds.add(id);
            }
            }
            // 父根识别: children 中出现另一个根 id 的，即为 root 汇总节点
            Set<String> parentRoots = new HashSet<String>();
            for (Object id : rootIds) {
                try {
                    Object children = XposedHelpers.callMethod(model, "l", id);
                    if (children instanceof ArrayList) {
                        for (Object child : (ArrayList<?>) children) {
                            Object cid = childId(child);
                            if (cid != null && rootSeen.contains(String.valueOf(cid))) {
                                parentRoots.add(String.valueOf(id));
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            Set<String> nodeSeen = new HashSet<String>();
            for (Object id : rootIds) {
                if (parentRoots.contains(String.valueOf(id))) continue;  // 跳过 root 汇总节点
                try {
                    Object item = XposedHelpers.callMethod(model, "j", id);
                    if (item == null) continue;
                    Node root = new Node();
                    root.folder = true;
                    root.title = stringField(item, "a", "书签");
                    Object children = XposedHelpers.callMethod(model, "l", id);
                    if (HookEntry.DEBUG) {
                        int childCnt = children instanceof ArrayList
                                ? ((ArrayList<?>) children).size() : -1;
                        XposedBridge.log(HookEntry.TAG + ": bm root id=" + id
                                + " title=" + root.title + " children=" + childCnt);
                    }
                    if (children instanceof ArrayList) {
                        for (Object child : (ArrayList<?>) children) {
                            addNode(model, childId(child), root.children, 0, budget, nodeSeen);
                        }
                    }
                    if (!root.children.isEmpty()) {
                        data.roots.add(root);
                        for (Node n : root.children) {
                            if (n.folder) data.folders.add(n);
                        }
                    }
                } catch (Throwable t) {
                    if (HookEntry.DEBUG) {
                        XposedBridge.log(HookEntry.TAG + ": bookmark root -> " + t);
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

    private static void addNode(Object model, Object id, List<Node> out, int depth,
                                int[] budget, Set<String> seen) {
        if (id == null || depth > 5 || budget[0] <= 0) return;
        if (seen != null && !seen.add(String.valueOf(id))) return;  // 已在其它根下收集过
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
                        addNode(model, childId(child), node.children, depth + 1, budget, seen);
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
