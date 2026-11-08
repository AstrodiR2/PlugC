package com.torip.claudeplugins;

import android.content.res.Resources;
import android.os.SystemClock;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "ClaudePlugins: ";
    private static final String PKG = "com.anthropic.claude";

    private static final String[] TARGETS = {
            "Профиль",
            "Хотите больше возможностей Claude?",
            "Profile"
    };

    private static final Map<String, Long> last = new HashMap<>();

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!PKG.equals(lpp.packageName)) return;
        XposedBridge.log(TAG + "probe2 loaded, process=" + lpp.processName);
        hookStrings();
        hookSettingsClass(lpp.classLoader);
    }

    private static boolean throttle(String key, long ms) {
        long now = SystemClock.uptimeMillis();
        synchronized (last) {
            Long t = last.get(key);
            if (t != null && now - t < ms) return false;
            last.put(key, now);
            return true;
        }
    }

    private void hookStrings() {
        try {
            XC_MethodHook h = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Object r = p.getResult();
                        if (r == null) return;
                        String s = r.toString();
                        for (String t : TARGETS) {
                            if (t.equals(s)) {
                                if (!throttle("str:" + t, 3000)) return;
                                int id = (Integer) p.args[0];
                                XposedBridge.log(TAG + "STRING \"" + s + "\" id=0x"
                                        + Integer.toHexString(id));
                                logStack();
                                return;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            };
            XposedHelpers.findAndHookMethod(Resources.class, "getText", int.class, h);
            XposedHelpers.findAndHookMethod(Resources.class, "getString", int.class, h);
            XposedBridge.log(TAG + "string hooks installed");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "string hook failed: " + t);
        }
    }

    private static void logStack() {
        StackTraceElement[] st = new Throwable().getStackTrace();
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (StackTraceElement e : st) {
            String c = e.getClassName();
            if (c.startsWith("android.") || c.startsWith("java.")
                    || c.startsWith("de.robv") || c.startsWith("LSPHooker")
                    || c.startsWith("com.torip") || c.startsWith("kotlin.")
                    || c.startsWith("dalvik.") || c.startsWith("org.lsposed")) continue;
            sb.append("\n    ").append(c).append('.').append(e.getMethodName())
                    .append(':').append(e.getLineNumber());
            if (++n >= 25) break;
        }
        XposedBridge.log(TAG + "stack:" + sb);
    }

    private void hookSettingsClass(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.anthropic.claude.settings.c", cl);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.isSynthetic()) continue;
                final String name = m.getName();
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam p) {
                        if (!throttle("c." + name, 1000)) return;
                        String a0 = "none";
                        if (p.args != null && p.args.length > 0) {
                            a0 = p.args[0] == null ? "null"
                                    : p.args[0].getClass().getName();
                        }
                        XposedBridge.log(TAG + "settings.c." + name
                                + " argc=" + (p.args == null ? 0 : p.args.length)
                                + " arg0=" + a0);
                    }
                });
                n++;
            }
            XposedBridge.log(TAG + "settings.c hooks: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "settings.c hook failed: " + t);
        }
    }
}
