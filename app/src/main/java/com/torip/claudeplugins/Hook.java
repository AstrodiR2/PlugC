package com.torip.claudeplugins;

import android.os.SystemClock;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
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
    private static final int PRIVACY_ORDINAL = 12;

    private static ClassLoader appCl;
    private static Method qc;
    private static Object fakeItem, unit, lastRoute, content, noopClick;
    private static boolean failed = false;
    private static boolean stackLogged = false;
    private static Object provider, inner;
    private static Method wdc;
    private static boolean pluginsMode = false;
    private static boolean inPrivacy = false;
    private static long pluginsSince = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!PKG.equals(lpp.packageName)) return;
        appCl = lpp.classLoader;
        XposedBridge.log(TAG + "v10 loaded");
        hookItem();
        hookPrivacy();
        hookScaffold();
        hookProvider();
        hookProbe();
    }

    // ---------- root list: item "Plugins" ----------
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
            if (!(item instanceof Enum)) return;
            String nm = ((Enum<?>) item).name();
            if (nm.equals("Plugins")) return;
            lastRoute = orig[1];
            if (nm.equals("Profile") && !stackLogged) { stackLogged = true; logStack(); }
            if (nm.equals("Profile") && pluginsMode
                    && SystemClock.uptimeMillis() - pluginsSince > 1500) {
                pluginsMode = false;
            }
            if (!nm.equals("Billing")) return;

            if (fakeItem == null) fakeItem = makeFake(item);
            if (unit == null) unit = findUnit();

            Object[] a = orig.clone();
            a[0] = fakeItem;
            a[2] = wrapClick(qc.getParameterTypes()[2], orig[2]);
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
        // ordinal 12 = Privacy: native click navigates to Privacy route
        XposedHelpers.setIntField(fake, "ordinal", PRIVACY_ORDINAL);
        XposedHelpers.setObjectField(fake, "v", XposedHelpers.getObjectField(billing, "v"));
        XposedHelpers.setIntField(fake, "w", XposedHelpers.getIntField(billing, "w"));
        XposedHelpers.setObjectField(fake, "x", null);
        XposedBridge.log(TAG + "fake item created");
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

    private static Object objMethod(Object proxy, Method method, Object[] args, String name) {
        switch (method.getName()) {
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == args[0];
            default: return name;
        }
    }

    // click on root item: set flag, then run the original (native) navigation
    private static Object wrapClick(Class<?> type, final Object original) {
        if (!type.isInterface()) return original;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getDeclaringClass() == Object.class) {
                            return objMethod(proxy, method, args, "PluginsClick");
                        }
                        pluginsMode = true;
                        pluginsSince = SystemClock.uptimeMillis();
                        try {
                            return method.invoke(original, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }

    // ---------- Privacy screen -> our screen ----------
    private void hookPrivacy() {
        try {
            Class<?> c = XposedHelpers.findClass("ywz", appCl);
            int n = 0;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("e") && m.getParameterTypes().length == 10) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            inPrivacy = true;
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            inPrivacy = false;
                        }
                    });
                    n++;
                }
            }
            XposedBridge.log(TAG + "privacy hooks: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "privacy hook failed: " + t);
        }
    }

    private void hookScaffold() {
        try {
            Class<?> c = XposedHelpers.findClass("p090", appCl);
            int n = 0;
            for (final Method m : c.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (m.getName().equals("c") && pt.length == 13
                        && pt[0] == String.class && pt[9].getName().equals("r4d")) {
                    final Class<?> backType = pt[1];
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            XposedBridge.log(TAG + "scaffold call title=" + p.args[0] + " mode=" + pluginsMode + " priv=" + inPrivacy + " fake=" + (fakeItem != null) + " route=" + (lastRoute != null));
                            if (!pluginsMode || failed
                                    || fakeItem == null || qc == null) return;
                            try {
                                p.args[0] = "Плагины";
                                p.args[1] = wrapBack(backType, p.args[1]);
                                p.args[9] = getContent();
                            } catch (Throwable t) {
                                failed = true;
                                XposedBridge.log(TAG + "scaffold failed: " + t + " / " + t.getCause());
                            }
                        }
                    });
                    n++;
                }
            }
            XposedBridge.log(TAG + "scaffold hooks: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "scaffold hook failed: " + t);
        }
    }

    private static Object wrapBack(Class<?> type, final Object original) {
        if (original == null || !type.isInterface()) return original;
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        if (method.getDeclaringClass() == Object.class) {
                            return objMethod(proxy, method, args, "PluginsBack");
                        }
                        pluginsMode = false;
                        try {
                            return method.invoke(original, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                });
    }

    // our content: r4d wrapping a pjl lambda (zrx scope, composer, int)
    private static Object getContent() throws Throwable {
        if (content != null) return content;
        Class<?> pjl = Class.forName("pjl", false, appCl);
        Class<?> r4d = Class.forName("r4d", false, appCl);
        Object lambda = Proxy.newProxyInstance(pjl.getClassLoader(), new Class<?>[]{pjl},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getDeclaringClass() == Object.class) {
                            return objMethod(proxy, method, args, "PluginsContent");
                        }
                        try {
                            drawWithProvider(args[1]);
                        } catch (Throwable t) {
                            XposedBridge.log(TAG + "content failed: " + t + " / " + t.getCause());
                        }
                        return unit;
                    }
                });
        Constructor<?> k = r4d.getDeclaredConstructor(int.class, boolean.class, Object.class);
        k.setAccessible(true);
        content = k.newInstance(0x50C0DE, true, lambda);
        XposedBridge.log(TAG + "content created");
        return content;
    }

    private void hookProvider() {
        try {
            Class<?> wd = XposedHelpers.findClass("wd10", appCl);
            int n = 0;
            for (Method m : wd.getDeclaredMethods()) {
                Class<?>[] pt = m.getParameterTypes();
                if (m.getName().equals("c") && pt.length == 4 && pt[0].getName().equals("rf10")) {
                    m.setAccessible(true);
                    wdc = m;
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            try {
                                Object rf = p.args[0];
                                if (rf == null || rf == provider) return;
                                Object g = XposedHelpers.getObjectField(rf, "g");
                                if (g != null && g.getClass().getName().equals("xos")) {
                                    provider = rf;
                                    XposedBridge.log(TAG + "provider captured");
                                }
                            } catch (Throwable ignored) {
                            }
                        }
                    });
                    n++;
                }
            }
            XposedBridge.log(TAG + "provider hooks: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "provider hook failed: " + t);
        }
    }

    private static void drawCard(Object composer) {
        try {
            Object[] a = new Object[8];
            a[0] = fakeItem;
            a[1] = lastRoute;
            a[2] = getNoop();
            a[3] = null;
            a[4] = "Тест: экран Плагины";
            a[5] = composer;
            a[6] = 0;
            a[7] = 8;
            XposedBridge.invokeOriginalMethod(qc, null, a);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "card failed: " + t + " / " + t.getCause());
        }
    }

    private static void drawWithProvider(Object composer) {
        try {
            if (provider == null || wdc == null) {
                XposedBridge.log(TAG + "no provider, drawing bare");
                drawCard(composer);
                return;
            }
            if (inner == null) {
                Class<?> njl = Class.forName("njl", false, appCl);
                inner = Proxy.newProxyInstance(njl.getClassLoader(), new Class<?>[]{njl},
                        new InvocationHandler() {
                            @Override
                            public Object invoke(Object proxy, Method method, Object[] args) {
                                if (method.getDeclaringClass() == Object.class) {
                                    return objMethod(proxy, method, args, "PluginsInner");
                                }
                                drawCard(args[0]);
                                return unit;
                            }
                        });
            }
            XposedBridge.invokeOriginalMethod(wdc, null, new Object[]{provider, inner, composer, 56});
        } catch (Throwable t) {
            XposedBridge.log(TAG + "provider draw failed: " + t + " / " + t.getCause());
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
            sb.append("\n    ").append(c).append('.').append(e.getMethodName());
            if (++n >= 30) break;
        }
        XposedBridge.log(TAG + "STACK:" + sb);
    }

    private static final ThreadLocal<java.util.ArrayList<Object>> k3pStack =
            new ThreadLocal<java.util.ArrayList<Object>>() {
                @Override
                protected java.util.ArrayList<Object> initialValue() {
                    return new java.util.ArrayList<Object>();
                }
            };
    private static int w090Logged = 0;

    private static String cn(Object o) {
        return o == null ? "null" : o.getClass().getName();
    }

    private static String desc(Object k) {
        try {
            byte v = XposedHelpers.getByteField(k, "v");
            Object w = XposedHelpers.getObjectField(k, "w");
            Object x = XposedHelpers.getObjectField(k, "x");
            String ws = cn(w);
            if (w != null && ws.endsWith("r4d")) {
                ws += "(" + cn(XposedHelpers.getObjectField(w, "x")) + ")";
            }
            return "k3p v=" + v + " w=" + ws + " x=" + cn(x);
        } catch (Throwable t) {
            return "k3p ?";
        }
    }

    private void hookProbe() {
        try {
            Class<?> k = XposedHelpers.findClass("k3p", appCl);
            int n = 0;
            for (Method m : k.getDeclaredMethods()) {
                if (m.getName().equals("p") && m.getParameterTypes().length == 3) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            k3pStack.get().add(p.thisObject);
                        }

                        @Override
                        protected void afterHookedMethod(MethodHookParam p) {
                            java.util.ArrayList<Object> l = k3pStack.get();
                            if (!l.isEmpty()) l.remove(l.size() - 1);
                        }
                    });
                    n++;
                }
            }
            Class<?> w = XposedHelpers.findClass("w090", appCl);
            for (Method m : w.getDeclaredMethods()) {
                if (m.getName().equals("i") && m.getParameterTypes().length == 4) {
                    XposedBridge.hookMethod(m, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam p) {
                            if (w090Logged >= 6) return;
                            w090Logged++;
                            StringBuilder sb = new StringBuilder();
                            sb.append("W090.i v=").append(XposedHelpers.getByteField(p.thisObject, "v"));
                            sb.append(" arg0=").append(cn(p.args[0]));
                            for (Object o : k3pStack.get()) sb.append("\n    ").append(desc(o));
                            XposedBridge.log(TAG + "PROBE " + sb);
                        }
                    });
                    n++;
                }
            }
            XposedBridge.log(TAG + "probe hooks: " + n);
        } catch (Throwable t) {
            XposedBridge.log(TAG + "probe failed: " + t);
        }
    }

    private static Object getNoop() {
        if (noopClick != null) return noopClick;
        Class<?> type = qc.getParameterTypes()[2];
        noopClick = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) {
                        if (method.getDeclaringClass() == Object.class) {
                            return objMethod(proxy, method, args, "Noop");
                        }
                        return unit;
                    }
                });
        return noopClick;
    }
}
