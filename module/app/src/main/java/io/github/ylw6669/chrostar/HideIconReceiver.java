package io.github.ylw6669.chrostar;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * 模块覆盖安装后, Android 会把 activity-alias 的 enabled 状态重置为默认。
 * 这里在 MY_PACKAGE_REPLACED 时按 hide_icon 偏好重新隐藏, 避免“开关是开着的,
 * 桌面图标却回来了”。
 */
public class HideIconReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(
                    HookEntry.PREFS_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean("hide_icon", false)) {
                HideIcon.apply(context, true);
                android.util.Log.i(HookEntry.TAG, "re-applied hide icon after package update");
            }
        } catch (Throwable ignored) {
        }
    }
}
