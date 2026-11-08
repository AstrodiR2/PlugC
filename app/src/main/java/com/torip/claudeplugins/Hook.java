package com.torip.claudeplugins;

import android.app.Activity;
import android.content.Intent;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "ClaudePlugins: ";
    private static final String PKG = "com.anthropic.claude";

    private static ClassLoader appCl;
    private static Method qc;
    private static Object fakeItem, unit;
    private static boolean failed = false;
    private static volatile WeakReference<Activity> activity = new WeakReference<>(null);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!PKG.equals(lpp.packageName)) return;
        appCl = lpp.classLoader;
        XposedBridge.log(TAG + "v12 loaded");
        hookActivity();
        hookItem();
        hookResult();
    }

    private void hookActivity() {
        try {
            Class<?> c = XposedHelpers.findClass("com.anthropic.claude.mainactivity.MainActivity", appCl);
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

    private void hookResult() {
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onActivityResult",
                    int.class, int.class, Intent.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                PluginsUi.onResult((Activity) p.thisObject,
                                        (Integer) p.args[0], (Integer) p.args[1], (Intent) p.args[2]);
                            } catch (Throwable t) {
                                XposedBridge.log(TAG + "result failed: " + t);
                            }
                        }
                    });
            XposedBridge.log(TAG + "result hook ok");
        } catch (Throwable t) {
            XposedBridge.log(TAG + "result hook failed: " + t);
        }
    }

    private void hookItem() {
        try {
            Class<?> c = XposedHelpers.findClass("com.anthropic.claude.settings.q", appCl);
            int n = 0;
            for (final Method m : c.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (m.getName().equals("c") && pt.length == 8 && pt[0].getName().endsWith("m090")) {
                    m.setAccessible(true);
                    qc = m;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            onItem(p.args);
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

    private static void onItem(Object[] orig) {
        if (failed) return;
        try {
            Object item = orig[0];
            if (!(item instanceof Enum) || !"Billing".equals(((Enum<?>) item).name())) return;
            if (fakeItem == null) fakeItem = makeFake(item);
            if (unit == null) unit = findUnit();

            Object[] a = orig.clone();
            a[0] = fakeItem;
            a[2] = wrapClick(qc.getParameterTypes()[2]);
            a[3] = null;
            a[4] = "Плагины";
            a[6] = 0;
            a[7] = 8;
            XposedBridge.invokeOriginalMethod(qc, null, a);
        } catch (Throwable t) {
            failed = true;
            XposedBridge.log(TAG + "inject failed: " + t + " / " + t.getCause());
        }
    }

    private static Object makeFake(Object billing) throws Throwable {
        Class<?> uc = Class.forName("sun.misc.Unsafe");
        Field f = uc.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        Object u = f.get(null);
        Method alloc = uc.getMethod("allocateInstance", Class.class);
        Object fake = alloc.invoke(u, billing.getClass());
        XposedHelpers.setObjectField(fake, "name", "Plugins");
        XposedHelpers.setIntField(fake, "ordinal", ((Enum<?>) billing).ordinal());
        XposedHelpers.setObjectField(fake, "v", XposedHelpers.getObjectField(billing, "v"));
        XposedHelpers.setIntField(fake, "w", XposedHelpers.getIntField(billing, "w"));
        XposedHelpers.setObjectField(fake, "x", null);
        return fake;
    }

    private static Object findUnit() {
        try {
            Class<?> k = Class.forName("kotlin.Unit", false, appCl);
            return k.getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object wrapClick(Class<?> type) {
        if (!type.isInterface()) return null;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getDeclaringClass() == Object.class) {
                            switch (method.getName()) {
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals": return proxy == args[0];
                                default: return "PluginsClick";
                            }
                        }
                        final Activity a = activity.get();
                        if (a != null) {
                            a.runOnUiThread(new Runnable() {
                                @Override
                                public void run() { PluginsUi.show(a); }
                            });
                        }
                        return unit;
                    }
                });
    }
}
