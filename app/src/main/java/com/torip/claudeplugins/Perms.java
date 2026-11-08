package com.torip.claudeplugins;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.lang.ref.WeakReference;
import java.util.concurrent.CountDownLatch;

final class Perms {
    static final String[] KEYS = {"network", "fs", "clipboard", "notify", "root"};
    static final String ALLOW = "allow", DENY = "deny", ASK = "ask";

    static volatile WeakReference<Activity> activity = new WeakReference<>(null);
    private static Handler ui;

    private Perms() {}

    static synchronized Handler ui() {
        if (ui == null) ui = new Handler(Looper.getMainLooper());
        return ui;
    }

    private static SharedPreferences sp(Context c, String dir) {
        return c.getSharedPreferences("plugin_perms_" + dir, 0);
    }

    static String get(Context c, String dir, String key) {
        String def = "root".equals(key) ? DENY : ASK;
        return sp(c, dir).getString(key, def);
    }

    static void set(Context c, String dir, String key, String val) {
        sp(c, dir).edit().putString(key, val).apply();
    }

    static boolean dangerEnabled(Context c, String dir) {
        return sp(c, dir).getBoolean("danger", false);
    }

    static void setDanger(Context c, String dir, boolean v) {
        sp(c, dir).edit().putBoolean("danger", v).apply();
    }

    static String label(String key) {
        switch (key) {
            case "network": return "Сеть";
            case "fs": return "Файлы";
            case "clipboard": return "Буфер обмена";
            case "notify": return "Уведомления";
            case "root": return "Root (su)";
            default: return key;
        }
    }

    /** Блокирует вызывающий (не UI) поток до ответа. */
    static boolean check(final Context c, final String dir, final String key, final String detail) {
        String mode = get(c, dir, key);
        if (ALLOW.equals(mode)) return true;
        if (DENY.equals(mode)) return false;
        return confirm(label(key) + " — " + dir, detail);
    }

    static boolean confirmSu(Context c, String dir, String cmd) {
        return check(c, dir, "root", cmd);
    }

    private static boolean confirm(final String title, final String msg) {
        final Activity a = activity.get();
        if (a == null || a.isFinishing()) return false;
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] res = {false};
        ui().post(() -> {
            try {
                new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle(title)
                        .setMessage(msg)
                        .setCancelable(false)
                        .setNegativeButton("Запретить", (d, w) -> { res[0] = false; latch.countDown(); })
                        .setPositiveButton("Разрешить", (d, w) -> { res[0] = true; latch.countDown(); })
                        .show();
            } catch (Throwable t) {
                latch.countDown();
            }
        });
        try { latch.await(); } catch (InterruptedException ignored) {}
        return res[0];
    }
}
