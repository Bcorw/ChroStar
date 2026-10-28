package io.github.ylw6669.chrostar;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/** 统一管理桌面图标(LauncherAlias)的显示/隐藏。 */
public final class HideIcon {

    private static final String ALIAS_CLASS =
            "io.github.ylw6669.chrostar.LauncherAlias";

    private HideIcon() {
    }

    private static ComponentName alias(Context context) {
        return new ComponentName(context.getPackageName(), ALIAS_CLASS);
    }

    public static boolean isHidden(Context context) {
        try {
            return context.getPackageManager().getComponentEnabledSetting(alias(context))
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        } catch (Throwable t) {
            return false;
        }
    }

    /** true=隐藏桌面图标; false=恢复桌面图标。返回是否与实际组件状态一致。 */
    public static boolean apply(Context context, boolean hide) {
        try {
            int state = hide
                    ? PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            context.getPackageManager().setComponentEnabledSetting(
                    alias(context), state, PackageManager.DONT_KILL_APP);
            return isHidden(context) == hide;
        } catch (Throwable t) {
            android.util.Log.e(HookEntry.TAG, "setComponentEnabledSetting failed", t);
            return false;
        }
    }
}
