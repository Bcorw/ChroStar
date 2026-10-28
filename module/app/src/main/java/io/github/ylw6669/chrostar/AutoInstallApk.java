package io.github.ylw6669.chrostar;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.text.TextUtils;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

import java.io.File;
import java.lang.reflect.Method;

/**
 * APK 自动安装(v1.8.2 引入, v1.15.0 工具收拢) —— 下载完成后若为 APK 文件, 自动打开系统安装器。
 *
 * 只匹配 APK 文件(MIME application/vnd.android.package-archive 或 .apk 后缀),
 * 其他文件一律不自动打开。
 *
 * 用户日志确认的根因(v1.8.1):
 *  - zkg.f hook 已生效, APK 也被识别, 但 OfflineItem.P 只给了裸文件名
 *    (如 FA4TB_26081901_arm64-v8a.apk), 没有目录前缀。
 *  - DownloadUtils.e(裸文件名) 里 new File("xxx.apk").getAbsolutePath() = "/xxx.apk"
 *    → t49.a() 生成 content uri 失败 → 退回 Uri.fromFile = file:///xxx.apk
 *    → 启动安装器时抛 android.os.FileUriExposedException:
 *      "file:///FA4TB_26081901_arm64-v8a.apk exposed beyond app through Intent.getData()"
 *
 * v1.8.2 修复:
 *  1. zkg.f 完成回调: 裸文件名 → 先解析出真实绝对路径(公共 Download /
 *     Chrome 私有下载目录 / PathUtils.getDownloadsDirectory()), 再走
 *     DownloadUtils.e(绝对路径) → 得到 content://com.android.chrome.DownloadFileProvider/...
 *     → ACTION_VIEW + package-archive 拉起系统安装器。
 *  2. 新增 hook DownloadUtils.a(qe7): Chrome 所有"打开下载项"的入口(通知上的
 *     打开按钮、下载页点文件、下载完自动打开)都会经过这里; 只要目标是 APK
 *     就自动转成安装 Intent 并阻止原逻辑, 其他文件完全放行。
 *  3. 保留 qgg(系统 DownloadManager) + onDownloadCompleted(内嵌) 兜底。
 */
public final class AutoInstallApk {

    private static final String CLS_DOWNLOAD_CONTROLLER =
            "org.chromium.chrome.browser.download.DownloadController";
    private static final String CLS_DOWNLOAD_UTILS =
            "org.chromium.chrome.browser.download.DownloadUtils";
    private static final String CLS_OFFLINE_ITEM =
            "org.chromium.components.offline_items_collection.OfflineItem";
    private static final String APK_MIME = "application/vnd.android.package-archive";

    /** 防重复: 同一文件只触发一次安装(v1.9.6: 原子去重) */
    private static String sLastAutoInstalled;
    private static final Object sInstallLock = new Object();

    private AutoInstallApk() {
    }

    /** 安装 hook(主进程) */
    public static void hook(ClassLoader cl) {
        OverwriteAndAutoOpen.hookOverwriteDuplicate(cl);
        if (HookEntry.is156()) {
            hook156(cl);
            hookInlineDownloadPath(cl); // 156 兜底: DownloadController.onDownloadCompleted
            return;
        }
        if (HookEntry.is152()) {
            hook152(cl);
            return; // 152 短名(c9o/zkg)不同, 独立路径
        }
        hookCompletedNotification(cl);
        hookOfflineItemCompletePath(cl);
        hookOpenDownloadEntry(cl);
        hookInlineDownloadPath(cl);
    }

    // ------------------------------------------------------------------
    // 主路径 0(最新): c9o.run() —— "文件已下载完毕"通知的更新执行点。
    // c9o 是 Runnable, 字段 O=e9o(DownloadManagerService观察者宿主), P=d9o(事件)。
    // d9o 字段: a=事件类型(1=完成), b=DownloadInfo, d=时间, e/f=标志, g=int。
    // DownloadInfo: c=MIME, e=路径, g=文件名。
    // 下载完成时 Chrome 必执行 c9o.run() 且 i==1 → 更新完成通知,
    // 这就是用户看到的"文件已下载完毕"通知 —— 最准确的完成时机。
    // ------------------------------------------------------------------
    private static void hookCompletedNotification(ClassLoader cl) {
        try {
            Class<?> c9o = XposedHelpers.findClass("c9o", cl);
            XposedHelpers.findAndHookMethod(c9o, "run",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(
                                        HookEntry.KEY_AUTO_INSTALL_APK, true)) {
                                    return;
                                }
                                Object d9o = XposedHelpers.getObjectField(param.thisObject, "P");
                                if (d9o == null) return;
                                int event = XposedHelpers.getIntField(d9o, "a");
                                if (event != 1) return;  // 1 = 下载完成
                                Object info = XposedHelpers.getObjectField(d9o, "b");
                                if (info == null) return;
                                ClassLoader cl = info.getClass().getClassLoader();
                                String mime = (String) XposedHelpers.getObjectField(info, "c");
                                // 156: c=mime, e=title, g=file_path; 145: e=path, g=name
                                String path = (String) XposedHelpers.getObjectField(
                                        info, HookEntry.is156() ? "g" : "e");
                                String name = (String) XposedHelpers.getObjectField(
                                        info, HookEntry.is156() ? "e" : "g");
                                if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, name)) return;
                                final String fileName = (path != null && path.contains("/"))
                                        ? new File(path).getName() : path;
                                final String finalName = (name != null && !name.isEmpty())
                                        ? name : fileName;
                                if (finalName == null || finalName.isEmpty()) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": apk completed (c9o) but no file name, skip");
                                    return;
                                }
                                final ClassLoader fcl = cl;
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        waitAndInstall(finalName, fcl,
                                                mime != null ? mime : mimeFromName(finalName));
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": auto-install apk (c9o path) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked c9o.run (download-complete notification, auto-install apk)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook c9o.run failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // 主路径 1: 内置下载完成 → zkg.f(OfflineItem, OfflineItemVisuals)
    // 下载完成后"文件已下载完毕"通知由此产生, 自动触发安装。
    // ------------------------------------------------------------------
    private static void hookOfflineItemCompletePath(ClassLoader cl) {
        try {
            Class<?> zkg = XposedHelpers.findClass("zkg", cl);
            Class<?> offlineItemCls = XposedHelpers.findClass(CLS_OFFLINE_ITEM,
                    cl);
            Class<?> visualsCls = XposedHelpers.findClass(
                    "org.chromium.components.offline_items_collection.OfflineItemVisuals",
                    cl);
            XposedHelpers.findAndHookMethod(zkg, "f", offlineItemCls, visualsCls,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(
                                        HookEntry.KEY_AUTO_INSTALL_APK, true)) {
                                    return;
                                }
                                Object item = param.args[0];
                                if (item == null) return;
                                // 完成态: Chromium OfflineItem 枚举 COMPLETE 在不同版本/混淆中
                                // 可能映射为 1 或 2, 两个都接受, 由 isApk 过滤避免误伤。
                                int state = XposedHelpers.getIntField(item, "m0");
                                if (state != 1 && state != 2) return;
                                String mime = (String) XposedHelpers.getObjectField(item, "f0");
                                String name = (String) XposedHelpers.getObjectField(item, "e0");
                                if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, name)) return;
                                String path = (String) XposedHelpers.getObjectField(item, "P");
                                if (path == null) path = "";
                                // 关键修复: OfflineItem.P 只有正式文件名, 且此时文件可能还在
                                // 私有临时目录(.com.google.Chrome.XXXX), 尚未复制到公共
                                // /sdcard/Download。因此改为后台轮询等待文件出现在公共目录。
                                final String fileName = (path.contains("/")
                                        ? new File(path).getName() : path);
                                final String finalName = (name != null && !name.isEmpty())
                                        ? name : fileName;
                                if (finalName == null || finalName.isEmpty()) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": apk download completed but no file name, skip");
                                    return;
                                }
                                final ClassLoader fcl = item.getClass().getClassLoader();
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        waitAndInstall(finalName, fcl, mimeFromName(finalName));
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": auto-install apk (zkg.f path) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked zkg.f (built-in download complete, auto-install apk)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook zkg.f failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // 主路径 2: Chrome 所有"打开下载项"入口 → DownloadUtils.a(qe7)
    // 覆盖: 通知"打开"按钮 / 下载页点文件 / 下载完自动打开。
    // qe7 字段: a=文件名, b=MIME, c=id, d=OtrProfileId, e/f=URL, g=action,
    //           h=Context, i=完整路径。
    // 只拦截 APK → 自动安装; 其他文件放行原逻辑。
    // ------------------------------------------------------------------
    private static void hookOpenDownloadEntry(ClassLoader cl) {
        try {
            Class<?> utilsCls = XposedHelpers.findClass(CLS_DOWNLOAD_UTILS,
                    cl);
            Class<?> qe7Cls = XposedHelpers.findClass("qe7", cl);
            XposedHelpers.findAndHookMethod(utilsCls, "a", qe7Cls,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(
                                        HookEntry.KEY_AUTO_INSTALL_APK, true)) {
                                    return;
                                }
                                Object qe7 = param.args[0];
                                if (qe7 == null) return;
                                String mime = (String) XposedHelpers.getObjectField(qe7, "b");
                                String name = (String) XposedHelpers.getObjectField(qe7, "a");
                                if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, name)) return;
                                String path = (String) XposedHelpers.getObjectField(qe7, "i");
                                if (path == null) path = "";
                                String abs = resolveRealPath(path, name,
                                        qe7.getClass().getClassLoader());
                                if (abs == null || abs.isEmpty()) {
                                    // 路径解析不到就不拦截, 交给 Chrome 原逻辑
                                    return;
                                }
                                openApkInstaller(abs, qe7.getClass().getClassLoader());
                                // 阻止 Chrome 原打开逻辑
                                param.setResult(Boolean.TRUE);
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": auto-install apk (DownloadUtils.a) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked DownloadUtils.a(qe7) (open download entry, auto-install apk)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook DownloadUtils.a failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // 兜底 2: Chrome 内嵌下载完成 → DownloadController.onDownloadCompleted
    // ------------------------------------------------------------------
    private static void hookInlineDownloadPath(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    CLS_DOWNLOAD_CONTROLLER,
                    cl,
                    "onDownloadCompleted",
                    XposedHelpers.findClass("org.chromium.chrome.browser.tab.Tab",
                            cl),
                    XposedHelpers.findClass(
                            "org.chromium.chrome.browser.download.DownloadInfo",
                            cl),
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(
                                        HookEntry.KEY_AUTO_INSTALL_APK, true)) {
                                    return;
                                }
                                Object info = param.args[1];
                                if (info == null) return;
                                ClassLoader cl = info.getClass().getClassLoader();
                                String mime = (String) XposedHelpers.getObjectField(info, "c");
                                // 156: c=mime, e=title, g=file_path; 145: e=path, g=name
                                String path = (String) XposedHelpers.getObjectField(
                                        info, HookEntry.is156() ? "g" : "e");
                                String name = (String) XposedHelpers.getObjectField(
                                        info, HookEntry.is156() ? "e" : "g");
                                if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, name)) return;
                                // 真机日志证实: onDownloadCompleted 是下载完成时确定触发的回调,
                                // 但此时文件可能还在 Chrome 私有临时目录, 尚未复制到公共目录,
                                // 因此改为后台轮询等待文件落盘后再安装。
                                final String fileName = (path != null && path.contains("/"))
                                        ? new File(path).getName() : path;
                                final String finalName = (name != null && !name.isEmpty())
                                        ? name : fileName;
                                if (finalName == null || finalName.isEmpty()) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": apk download completed but no file name, skip");
                                    return;
                                }
                                final ClassLoader fcl = cl;
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        waitAndInstall(finalName, fcl, mimeFromName(finalName));
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": auto-install apk (inline path) error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": hooked DownloadController.onDownloadCompleted (auto-install apk)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": hook auto-install apk failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // 轮询等待 APK 文件出现在下载目录, 出现后自动安装。
    // Chrome 内置下载完成时只有正式文件名, 真实文件先落在私有目录
    // (.com.google.Chrome.XXXX 临时名), 随后复制到公共目录并改正式名。
    // 因此同时轮询: ①公共 Download ②Chrome 私有 Download ③私有目录下
    // 的 .com.google.Chrome. 临时文件(大小>0 即认为完成)。最多 60 秒,
    // 每 500ms 查一次。
    // ------------------------------------------------------------------
    private static void waitAndInstall(final String fileName, final ClassLoader cl) {
        waitAndInstall(fileName, cl, null);
    }

    /** 支持 MIME 参数(扩展类型自动打开); mime 为 null 时按文件名推断 */
    private static void waitAndInstall(final String pathOrName, final ClassLoader cl,
                                       final String mimeOverride) {
        if (pathOrName == null || pathOrName.isEmpty()) {
            return;
        }
        final String mime = (mimeOverride != null && !mimeOverride.isEmpty())
                ? mimeOverride : mimeFromName(pathOrName);
        // content://(MediaStore/FileProvider), 直接打开
        if (pathOrName.startsWith("content://")) {
            openFileUri(Uri.parse(pathOrName), mime, cl);
            return;
        }
        // 已经是绝对路径且文件存在: 直接打开(156 OfflineItem.k0 就是完整路径)
        String fileName = pathOrName;
        if (pathOrName.startsWith("/")) {
            File direct = new File(pathOrName);
            fileName = direct.getName();
            if (direct.exists() && direct.length() > 0
                    && !fileName.startsWith(".com.google.Chrome.")) {
                openDownloadedFile(direct.getAbsolutePath(), mime, cl);
                return;
            }
        }
        File publicDl = null;
        File privateDl = null;
        File privateRoot = null;
        try {
            publicDl = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS);
        } catch (Throwable ignored) {
        }
        try {
            privateRoot = new File(Environment.getExternalStorageDirectory(),
                    "Android/data/com.android.chrome/files");
            privateDl = new File(privateRoot, "Download");
        } catch (Throwable ignored) {
        }
        long deadline = System.currentTimeMillis() + 60000L;
        File found = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (publicDl != null) {
                    File f = new File(publicDl, fileName);
                    if (f.exists() && f.length() > 0) {
                        found = f;
                        break;
                    }
                    File[] cands = publicDl.listFiles();
                    if (cands != null) {
                        for (File c : cands) {
                            if (c == null) continue;
                            String cn = c.getName();
                            if (cn.equals(fileName)
                                    || cn.startsWith(fileName) || fileName.startsWith(cn)) {
                                found = c;
                                break;
                            }
                        }
                    }
                    if (found != null) break;
                }
                if (privateDl != null) {
                    File f2 = new File(privateDl, fileName);
                    if (f2.exists() && f2.length() > 0) {
                        found = f2;
                        break;
                    }
                    File[] cands2 = privateDl.listFiles();
                    if (cands2 != null) {
                        for (File c : cands2) {
                            if (c == null) continue;
                            String cn = c.getName();
                            if (cn.equals(fileName)
                                    || cn.startsWith(fileName) || fileName.startsWith(cn)) {
                                found = c;
                                break;
                            }
                        }
                    }
                    if (found != null) break;
                    // Chrome 临时文件 .com.google.Chrome.XXXX
                    File[] tmps = privateDl.listFiles();
                    if (tmps != null) {
                        for (File c : tmps) {
                            if (c == null) continue;
                            String cn = c.getName();
                            if (cn.startsWith(".com.google.Chrome.")
                                    && c.length() > 0
                                    && c.lastModified() > System.currentTimeMillis() - 120000L) {
                                found = c;
                                break;
                            }
                        }
                    }
                    if (found != null) break;
                }
                // MediaStore 兜底: Android 11+ 作用域存储下 File.exists 可能不可见
                Uri msUri = queryMediaStore(fileName);
                if (msUri != null) {
                    openFileUri(msUri, mime, cl);
                    return;
                }
            } catch (Throwable ignored) {
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                return;
            }
        }
        if (found == null) {
            XposedBridge.log(HookEntry.TAG + ": file not found in Download dir within 60s ["
                    + fileName + "], give up");
            return;
        }
        openDownloadedFile(found.getAbsolutePath(), mime, cl);
    }

    /** APK -> 系统安装器; 其他类型 -> ACTION_VIEW(mime) */
    private static void openDownloadedFile(String absPath, String mime, ClassLoader cl) {
        if (HookEntry.isApk(mime, absPath) || mime == null) {
            openApkInstaller(absPath, cl);
        } else {
            Uri uri = toUriFromPath(absPath, cl);
            if (uri == null) {
                XposedBridge.log(HookEntry.TAG
                        + ": cannot build content uri for [" + absPath + "], skip");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mime);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchInstaller(absPath, intent, cl);
        }
    }

    /** content Uri + mime 打开 */
    private static void openFileUri(Uri uri, String mime, ClassLoader cl) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String type = (mime != null && !mime.isEmpty()) ? mime : APK_MIME;
            intent.setDataAndType(uri, type);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchInstaller(uri.toString(), intent, cl);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": open file uri failed -> " + t);
        }
    }

    /**
     * 绝对路径 -> content Uri。
     * 156: DownloadUtils.f(String); 145: DownloadUtils.e(String)。统一按
     * "static Uri (String)" 签名查找, 避免版本间方法名变化。
     */
    private static Uri toUriFromPath(String absPath, ClassLoader cl) {
        try {
            Class<?> utilsClass = Class.forName(CLS_DOWNLOAD_UTILS, false, cl);
            for (Method m : utilsClass.getMethods()) {
                if (!java.lang.reflect.Modifier.isStatic(m.getModifiers())) continue;
                if (m.getReturnType() != Uri.class) continue;
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length != 1 || ps[0] != String.class) continue;
                try {
                    Object r = m.invoke(null, absPath);
                    if (r instanceof Uri) {
                        Uri u = (Uri) r;
                        if (u != null && !Uri.EMPTY.equals(u)
                                && !"file".equals(u.getScheme())) {
                            return u;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            return queryMediaStore(new File(absPath).getName());
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** MediaStore 查询刚下载的 APK(按文件名, 取最新) */
    private static Uri queryMediaStore(String fileName) {
        try {
            Context ctx = HookEntry.getAppContext(null);
            if (ctx == null) return null;
            android.content.ContentResolver cr = ctx.getContentResolver();
            Uri collection = android.provider.MediaStore.Files
                    .getContentUri("external");
            String sel = android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=?";
            android.database.Cursor c = cr.query(collection,
                    new String[]{android.provider.MediaStore.MediaColumns._ID},
                    sel, new String[]{fileName},
                    android.provider.MediaStore.MediaColumns.DATE_ADDED + " DESC");
            if (c != null) {
                try {
                    if (c.moveToFirst()) {
                        long id = c.getLong(0);
                        return android.content.ContentUris.withAppendedId(collection, id);
                    }
                } finally {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 直接用 content:// Uri 打开安装器(v1.9.6: 原子去重) */
    private static void openApkInstallerUri(Uri uri, ClassLoader cl) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchInstaller(uri.toString(), intent, cl);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": open apk installer failed (uri) -> " + t);
        }
    }

    /**
     * v1.9.6: 全局原子去重安装。
     * 所有安装路径(content:// / MediaStore / File)统一走此方法:
     * synchronized 块内 check-and-set, 同一文件只发一次 Intent。
     * 之前的问题: 多个 hook 点(c9o.run/zkg.f/onDownloadCompleted)同时触发,
     * 多线程轮询找到文件后都通过了非原子的去重检查
     * → 多次安装提示。
     */
    private static void launchInstaller(String dedupKey, Intent intent, ClassLoader cl) {
        synchronized (sInstallLock) {
            if (dedupKey == null || dedupKey.equals(sLastAutoInstalled)) {
                XposedBridge.log(HookEntry.TAG
                        + ": apk install skipped (already installed) -> " + dedupKey);
                return;
            }
            sLastAutoInstalled = dedupKey;
        }
        try {
            Context ctx = HookEntry.getAppContext(cl);
            if (ctx == null) {
                XposedBridge.log(HookEntry.TAG + ": no context for apk install, skip");
                return;
            }
            ctx.startActivity(intent);
            XposedBridge.log(HookEntry.TAG + ": apk install intent launched -> " + dedupKey);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": open apk installer failed -> " + t);
        }
    }


    // ------------------------------------------------------------------
    // 路径解析: 把可能只是裸文件名的 path 解析成真实绝对路径
    // 依次尝试: ①已是绝对路径 ②公共 Download 目录 ③Chrome 私有下载目录
    //          ④PathUtils.getDownloadsDirectory() ⑤当前应用文件目录
    // ------------------------------------------------------------------
    private static String resolveRealPath(String path, String name, ClassLoader cl) {
        try {
            if (path != null && !path.isEmpty()) {
                String p = path.trim();
                if (p.startsWith("/")) {
                    File f = new File(p);
                    if (f.exists()) return f.getAbsolutePath();
                }
            }
            String fileName = (name != null && !name.isEmpty()) ? name
                    : (path != null ? new File(path).getName() : null);
            if (fileName == null || fileName.isEmpty()) return null;

            // ① 公共 Download 目录
            try {
                File publicDl = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (publicDl != null) {
                    File f = new File(publicDl, fileName);
                    if (f.exists()) return f.getAbsolutePath();
                }
            } catch (Throwable ignored) {
            }
            // ② Chrome 私有下载目录 /data/user/0/com.android.chrome/.../Download
            try {
                Class<?> pathUtils = Class.forName(
                        "org.chromium.base.PathUtils", false, cl);
                Method getDl = pathUtils.getMethod("getDownloadsDirectory");
                Object dlDir = getDl.invoke(null);
                if (dlDir instanceof String) {
                    String s = (String) dlDir;
                    if (!TextUtils.isEmpty(s)) {
                        File f = new File(s, fileName);
                        if (f.exists()) return f.getAbsolutePath();
                    }
                }
            } catch (Throwable ignored) {
            }
            // ③ 应用外部文件目录 Download
            try {
                Class<?> activityThread = Class.forName("android.app.ActivityThread",
                        false, cl);
                Object app = activityThread.getMethod("currentApplication").invoke(null);
                if (app instanceof Context) {
                    Context ctx = (Context) app;
                    File[] dirs = ctx.getExternalFilesDirs(null);
                    if (dirs != null) {
                        for (File dir : dirs) {
                            if (dir == null) continue;
                            File f = new File(new File(dir, "Download"), fileName);
                            if (f.exists()) return f.getAbsolutePath();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            // ④ 最后尝试: 文件名带完整路径的情况(如 content:// 已解析)
            if (path != null && path.contains("/")) {
                File f = new File(path);
                if (f.exists()) return f.getAbsolutePath();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }


    /** 打开系统安装器(绝对路径 → content URI) */
    private static void openApkInstaller(String absPath, ClassLoader cl) {
        try {
            Uri uri = toUriFromPath(absPath, cl);
            if (uri == null) {
                XposedBridge.log(HookEntry.TAG
                        + ": cannot build content uri for [" + absPath + "], skip");
                return;
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, APK_MIME);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            launchInstaller(absPath, intent, cl);
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": open apk installer failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // v2.3.0: Chrome 156 安装路径。
    // vg8 = DownloadMessageUiController; d(OfflineItem,boolean,boolean,boolean)
    // 完成态 OfflineItem.s0==2(COMPLETE); 字段 k0=file_path, V=title, l0=mime。
    // ------------------------------------------------------------------
    private static void hook156(ClassLoader cl) {
        try {
            Class<?> vg8 = XposedHelpers.findClass(Chrome156.DOWNLOAD_MESSAGE, cl);
            Class<?> offlineItemCls = XposedHelpers.findClass(CLS_OFFLINE_ITEM, cl);
            XposedHelpers.findAndHookMethod(vg8, "d", offlineItemCls,
                    boolean.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                if (!HookEntry.readPrefBoolean(
                                        HookEntry.KEY_AUTO_INSTALL_APK, true)) {
                                    return;
                                }
                                Object item = param.args[0];
                                if (item == null) return;
                                int state = XposedHelpers.getIntField(item,
                                        Chrome156.OI_STATE);
                                if (state != Chrome156.STATE_COMPLETE) return;
                                String path = tryStringField(item, Chrome156.OI_PATH);
                                String title = tryStringField(item, Chrome156.OI_TITLE);
                                String mime = tryStringField(item, Chrome156.OI_MIME);
                                String name = (title != null && !title.isEmpty()) ? title
                                        : (path != null ? new File(path).getName() : null);
                                if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, name)
                                        && !OverwriteAndAutoOpen.shouldAutoOpen(mime, path)) {
                                    return;
                                }
                                if (name == null || name.isEmpty()) {
                                    XposedBridge.log(HookEntry.TAG
                                            + ": [156] download complete but no file name, skip");
                                    return;
                                }
                                XposedBridge.log(HookEntry.TAG
                                        + ": [156] download complete: " + name
                                        + " mime=" + mime + " path=" + path);
                                final String target = (path != null && !path.isEmpty())
                                        ? path : name;
                                final String fName = name;
                                final String fMime = (mime != null && !mime.isEmpty())
                                        ? mime : mimeFromName(fName);
                                final ClassLoader fcl = cl;
                                Thread t = new Thread(new Runnable() {
                                    @Override
                                    public void run() {
                                        waitAndInstall(target, fcl, fMime);
                                    }
                                });
                                t.setDaemon(true);
                                t.start();
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG
                                        + ": [156] auto-install hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG
                    + ": [156] hooked " + Chrome156.DOWNLOAD_MESSAGE + ".d (auto-install/apk)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [156] hook vg8 failed -> " + t);
        }
    }

    // ------------------------------------------------------------------
    // v2.1.0: Chrome 152 专用安装路径。
    // 语义锚点: ia8.d(OfflineItem,boolean,boolean,boolean) 同 145 je7.d —
    // 完成态(offlineItem.q0==2)时从 OfflineItem 拿路径/文件名执行安装逻辑。
    // OfflineItem 152 字段: q0=state, 其余路径/名字段名经 DownloadInfo 转换确认后补;
    // 本实现仅对 APK( mime 判定 via OfflineItem 兜底字段) 触发, 字段缺失时安全跳过。
    // ------------------------------------------------------------------
    private static void hook152(ClassLoader cl) {
        try {
            Class<?> ia8 = XposedHelpers.findClass("ia8", cl);
            Class<?> offlineItemCls = XposedHelpers.findClass(CLS_OFFLINE_ITEM,
                    cl);
            XposedHelpers.findAndHookMethod(ia8, "d", offlineItemCls,
                    boolean.class, boolean.class, boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object item = param.args[0];
                                if (item == null) return;
                                int state = XposedHelpers.getIntField(item,
                                        "chrome152".equals(HookEntry.engineVersion) ? "q0" : "m0");
                                if (state != 2) return; // 只处理完成态
                                if (!HookEntry.readPrefBoolean(HookEntry.KEY_AUTO_INSTALL_APK, true)) {
                                    return;
                                }
                                // 152: OfflineItem 路径/名字段名变化, 用通用反射尝试常见字段
                                // 152 字段(DownloadInfo.a 官方转换实证): path=i0, name=T, mime=j0
                                String path = tryStringField(item, "i0");
                                String name = tryStringField(item, "T", "e0");
                                String mime = tryStringField(item, "j0", "f0");
                                if (!OverwriteAndAutoOpen.shouldAutoOpen(mime, name)) return;
                                XposedBridge.log(HookEntry.TAG + ": [152] apk download completed: " + name);
                                installApk152(path, name, mime, cl);
                            } catch (Throwable t) {
                                XposedBridge.log(HookEntry.TAG + ": [152] install hook error -> " + t);
                            }
                        }
                    });
            XposedBridge.log(HookEntry.TAG + ": [152] hooked ia8.d (auto-install path)");
        } catch (Throwable t) {
            XposedBridge.log(HookEntry.TAG + ": [152] hook ia8 failed -> " + t);
        }
    }

    private static String tryStringField(Object obj, String... names) {
        for (String n : names) {
            try {
                Object v = XposedHelpers.getObjectField(obj, n);
                if (v instanceof String && !((String) v).isEmpty()) {
                    return (String) v;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void installApk152(String path, String name, String mime, ClassLoader cl) {
        // 复用 145 已有解析/去重/安装链; mime 透传(扩展类型用)
        waitAndInstall(path != null ? path : name, cl, mime);
    }


    /** v2.2.1: 按文件名推断 MIME(用于扩展类型自动打开) */
    private static String mimeFromName(String name) {
        if (name == null) return null;
        String n = name.toLowerCase();
        if (n.endsWith(".pdf")) return "application/pdf";
        if (n.endsWith(".zip")) return "application/zip";
        if (n.endsWith(".7z")) return "application/x-7z-compressed";
        if (n.endsWith(".rar")) return "application/x-rar-compressed";
        if (n.endsWith(".doc")) return "application/msword";
        if (n.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (n.endsWith(".xls")) return "application/vnd.ms-excel";
        if (n.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (n.endsWith(".ppt")) return "application/vnd.ms-powerpoint";
        if (n.endsWith(".pptx")) return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if (n.endsWith(".txt") || n.endsWith(".md") || n.endsWith(".log")) return "text/plain";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".gif")) return "image/gif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".mkv")) return "video/x-matroska";
        if (n.endsWith(".webm")) return "video/webm";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".ogg")) return "audio/ogg";
        if (n.endsWith(".m4a")) return "audio/mp4";
        if (n.endsWith(".epub")) return "application/epub+zip";
        if (n.endsWith(".mobi")) return "application/x-mobipocket-ebook";
        return null;
    }
}