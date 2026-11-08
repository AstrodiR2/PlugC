package com.torip.claudeplugins;

import android.app.Activity;
import android.widget.Toast;

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

    private static volatile WeakReference<Activity> activity = new WeakReference<>(null);
    private static Object fakeItem = null;
    private static Object unit = null;
    private static boolean failed = false;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!PKG.equals(lpp.packageName)) return;
        XposedBridge.log(TAG + "v4 loaded");
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

    private void hookItem(final ClassLoader cl) {
        try {
            Class<?> c = XposedHelpers.findClass("com.anthropic.claude.settings.q", cl);
            int n = 0;
            for (final Method m : c.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (m.getName().equals("c") && pt.length == 8 && pt[0].getName().endsWith("m090")) {
                    m.setAccessible(true);
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            onItem(m, p.args, cl);
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

    private static void onItem(Method m, Object[] orig, ClassLoader cl) {
        if (failed) return;
        try {
            Object item = orig[0];
            if (!(item instanceof Enum) || !"Billing".equals(((Enum<?>) item).name())) return;

            if (fakeItem == null) fakeItem = makeFake(item);
            if (unit == null) unit = findUnit(cl);

            Object[] a = orig.clone();
            a[0] = fakeItem;
            a[2] = wrapClick(m.getParameterTypes()[2], orig[2]);
            a[3] = null;
            a[4] = "Плагины";
            a[6] = 0;
            a[7] = 8;
            XposedBridge.invokeOriginalMethod(m, null, a);
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
        XposedBridge.log(TAG + "fake item created");
        return fake;
    }

    private static Object findUnit(ClassLoader cl) {
        try {
            Class<?> k = Class.forName("kotlin.Unit", false, cl);
            return k.getField("INSTANCE").get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object wrapClick(Class<?> type, Object original) {
        if (!type.isInterface()) {
            XposedBridge.log(TAG + "click type not interface: " + type.getName());
            return original;
        }
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
                                public void run() {
                                    Toast.makeText(a, "Плагины", Toast.LENGTH_SHORT).show();
                                }
                            });
                        }
                        return unit;
                    }
                });
    }
}
