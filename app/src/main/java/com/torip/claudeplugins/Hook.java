package com.torip.claudeplugins;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

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
    private static final long NEW_ENTRY_GAP_MS = 1500;
    private static final long SHOW_MS = 2500;

    private static volatile WeakReference<Activity> activity = new WeakReference<>(null);
    private static Handler ui = null;
    private static long lastSeen = 0;
    private static View badge = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!PKG.equals(lpp.packageName)) return;
        XposedBridge.log(TAG + "stage1 loaded, process=" + lpp.processName);
        hookActivity(lpp.classLoader);
        hookItem(lpp.classLoader);
    }

    private void hookActivity(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.anthropic.claude.mainactivity.MainActivity", cl);
            XposedHelpers.findAndHookMethod(c, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    activity = new WeakReference<>((Activity) p.thisObject);
                }
            });
        } catch (Throwable t) {
            XposedBridge.log(TAG + "activity hook failed: " + t);
        }
    }

    private void hookItem(ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.anthropic.claude.settings.q", cl);
            Method[] ms = c.getDeclaredMethods();
            XposedBridge.log(TAG + "v3 q methods: " + ms.length);
            int n = 0;
            for (Method m : ms) {
                Class<?>[] pt = m.getParameterTypes();
                XposedBridge.log(TAG + "q." + m.getName() + " argc=" + pt.length
                        + " arg0=" + (pt.length > 0 ? pt[0].getName() : "-"));
                if (m.getName().equals("c") && pt.length == 8 && pt[0].getName().endsWith("m090")) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            onItem(p.args[0]);
                        }
                    });
                    n++;
                }
            }
            XposedBridge.log(TAG + "item hooks: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "item hook failed: " + t);
        }
    }

    private static void onItem(Object item) {
        try {
            long now = SystemClock.uptimeMillis();
            boolean fresh = now - lastSeen > NEW_ENTRY_GAP_MS;
            lastSeen = now;
            String name = item instanceof Enum ? ((Enum<?>) item).name() : "?";
            if (fresh) {
                XposedBridge.log(TAG + "settings entry, first item=" + name);
                showBadge();
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + "onItem: " + t);
        }
    }

    private static void showBadge() {
        final Activity a = activity.get();
        if (a == null) return;
        a.runOnUiThread(() -> {
            try {
                hideBadge();
                TextView tv = new TextView(a);
                tv.setText("Работает");
                tv.setTextColor(Color.parseColor("#1A1A1A"));
                tv.setTextSize(14);
                tv.setTypeface(null, android.graphics.Typeface.BOLD);
                float d = a.getResources().getDisplayMetrics().density;
                tv.setPadding((int) (16 * d), (int) (8 * d), (int) (16 * d), (int) (8 * d));
                GradientDrawable bg = new GradientDrawable(
                        GradientDrawable.Orientation.LEFT_RIGHT,
                        new int[]{Color.parseColor("#E5E4E2"), Color.parseColor("#B8B8BC"), Color.parseColor("#F4F4F6")});
                bg.setCornerRadius(100 * d);
                tv.setBackground(bg);
                tv.setElevation(8 * d);

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                        Gravity.TOP | Gravity.CENTER_HORIZONTAL);
                lp.topMargin = (int) (72 * d);

                ((ViewGroup) a.getWindow().getDecorView()).addView(tv, lp);
                badge = tv;
                if (ui == null) ui = new Handler(Looper.getMainLooper()); ui.postDelayed(Hook::hideBadge, SHOW_MS);
            } catch (Throwable t) {
                XposedBridge.log(TAG + "showBadge: " + t);
            }
        });
    }

    private static void hideBadge() {
        View b = badge;
        badge = null;
        if (b != null && b.getParent() instanceof ViewGroup) {
            ((ViewGroup) b.getParent()).removeView(b);
        }
    }
}
