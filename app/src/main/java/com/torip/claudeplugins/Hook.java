package com.torip.claudeplugins;

import android.app.Activity;
import android.os.SystemClock;
import android.widget.Toast;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "ClaudePlugins: ";
    private static final String PKG = "com.anthropic.claude";
    private static final String SCREEN = "com.anthropic.claude.settings.SettingsAppScreen";

    private static volatile WeakReference<Activity> activity = new WeakReference<>(null);
    private static String lastRoute = "";
    private static long lastLog = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!PKG.equals(lpp.packageName)) return;
        XposedBridge.log(TAG + "loaded, process=" + lpp.processName);
        hookActivity(lpp.classLoader);
        hookSettings(lpp.classLoader);
    }

    private void hookActivity(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.anthropic.claude.mainactivity.MainActivity", cl);
            XposedHelpers.findAndHookMethod(c, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    activity = new WeakReference<>((Activity) p.thisObject);
                    XposedBridge.log(TAG + "MainActivity.onResume");
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "activity hook failed: " + t);
        }
    }

    private void hookSettings(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.anthropic.claude.settings.c", cl);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (m.getName().equals("b") && pt.length > 0 && pt[0].getName().equals(SCREEN)) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            onRoute(p.args[0]);
                        }
                    });
                    n++;
                }
            }
            XposedBridge.log(TAG + "settings hooks: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "settings hook failed: " + t);
        }
    }

    private static void onRoute(Object route) {
        String name = route == null ? "null" : route.getClass().getSimpleName();
        long now = SystemClock.uptimeMillis();
        boolean changed = !name.equals(lastRoute);
        if (changed || now - lastLog > 1000) {
            XposedBridge.log(TAG + "route=" + name + (changed ? " (changed)" : " (same)") + " t=" + now);
            lastLog = now;
        }
        if (changed && "SettingsScreen".equals(name)) {
            final Activity a = activity.get();
            if (a != null) {
                a.runOnUiThread(() ->
                        Toast.makeText(a, "Работает", Toast.LENGTH_SHORT).show());
            }
        }
        lastRoute = name;
    }
}
