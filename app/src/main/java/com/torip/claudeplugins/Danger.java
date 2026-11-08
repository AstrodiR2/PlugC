package com.torip.claudeplugins;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Опасный режим: мост reflection/Xposed <-> QuickJS через строки/JSON.
 * app.cash.quickjs маршалит только примитивы и String, поэтому произвольный
 * Java-объект в JS не возвращаем — только handle-id + ret-JSON {"v":...}.
 */
final class Danger {
    private final ClassLoader cl;
    private final Loader.Host host;
    private final Map<String, Object> handles = new HashMap<>();
    private int seq = 0;

    Danger(ClassLoader cl, Loader.Host host) {
        this.cl = cl;
        this.host = host;
    }

    private String put(Object o) {
        String id = "h" + (seq++);
        handles.put(id, o);
        return id;
    }

    private Class<?> forName(String name) throws Exception {
        return Class.forName(name, false, cl);
    }

    private Object[] jsonArgs(JSONArray a, Class<?>[] types) throws Exception {
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) out[i] = coerce(a.get(i), types[i]);
        return out;
    }

    @SuppressWarnings("unchecked")
    private Object coerce(Object v, Class<?> t) {
        if (t == int.class || t == Integer.class) return ((Number) v).intValue();
        if (t == long.class || t == Long.class) return ((Number) v).longValue();
        if (t == double.class || t == Double.class) return ((Number) v).doubleValue();
        if (t == float.class || t == Float.class) return ((Number) v).floatValue();
        if (t == boolean.class || t == Boolean.class) return (Boolean) v;
        if (t.isEnum() && v instanceof String) return Enum.valueOf((Class<Enum>) t, (String) v);
        return v;
    }

    private String ret(Object r) {
        try {
            JSONObject j = new JSONObject();
            if (r == null) return "null";
            if (r instanceof String || r instanceof Number || r instanceof Boolean) {
                j.put("v", r);
            } else {
                j.put("handle", put(r));
                j.put("type", r.getClass().getName());
            }
            return j.toString();
        } catch (Exception e) {
            return "null";
        }
    }

    private Class<?>[] parseTypes(String csv) throws Exception {
        if (csv == null || csv.length() == 0) return new Class<?>[0];
        String[] parts = csv.split(",");
        Class<?>[] out = new Class<?>[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = primOrClass(parts[i].trim());
        return out;
    }

    private Class<?> primOrClass(String n) throws Exception {
        switch (n) {
            case "int": return int.class;
            case "long": return long.class;
            case "double": return double.class;
            case "float": return float.class;
            case "boolean": return boolean.class;
            case "String": return String.class;
            default: return forName(n);
        }
    }

    String callStatic(String cls, String method, String argTypesCsv, String argsJson) {
        try {
            Class<?> c = forName(cls);
            Class<?>[] types = parseTypes(argTypesCsv);
            Method m = c.getDeclaredMethod(method, types);
            m.setAccessible(true);
            Object r = m.invoke(null, jsonArgs(new JSONArray(argsJson), types));
            return ret(r);
        } catch (Throwable t) {
            host.log("[danger] static " + cls + "." + method + " failed: " + t);
            return "null";
        }
    }

    String call(String handle, String method, String argTypesCsv, String argsJson) {
        try {
            Object obj = handles.get(handle);
            if (obj == null) return "null";
            Class<?>[] types = parseTypes(argTypesCsv);
            Method m = obj.getClass().getDeclaredMethod(method, types);
            m.setAccessible(true);
            return ret(m.invoke(obj, jsonArgs(new JSONArray(argsJson), types)));
        } catch (Throwable t) {
            host.log("[danger] call ." + method + " failed: " + t);
            return "null";
        }
    }

    String getStaticField(String cls, String field) {
        try {
            return ret(XposedHelpers.getStaticObjectField(forName(cls), field));
        } catch (Throwable t) {
            host.log("[danger] getStaticField failed: " + t);
            return "null";
        }
    }

    void setStaticField(String cls, String field, String valueJson) {
        try {
            JSONObject j = new JSONObject(valueJson);
            XposedHelpers.setStaticObjectField(forName(cls), field, j.opt("v"));
        } catch (Throwable t) {
            host.log("[danger] setStaticField failed: " + t);
        }
    }

    String getField(String handle, String field) {
        try {
            return ret(XposedHelpers.getObjectField(handles.get(handle), field));
        } catch (Throwable t) {
            host.log("[danger] getField failed: " + t);
            return "null";
        }
    }

    /**
     * Только логирует вызов (аргументы, результат). Менять аргументы/результат из JS
     * не делаем: QuickJs-инстанс не потокобезопасен, а Xposed-хук срабатывает на произвольном
     * потоке — звать JS оттуда напрямую небезопасно, можно повредить состояние движка.
     */
    void hook(String cls, String method, String argTypesCsv) {
        try {
            Class<?> c = forName(cls);
            Method m = c.getDeclaredMethod(method, parseTypes(argTypesCsv));
            XposedBridge.hookMethod(m, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam p) {
                    StringBuilder sb = new StringBuilder("[hook] " + cls + "." + method + "(");
                    for (int i = 0; i < p.args.length; i++) sb.append(i > 0 ? ", " : "").append(p.args[i]);
                    sb.append(") -> ").append(p.getResult());
                    host.log(sb.toString());
                }
            });
            host.log("[danger] hook set: " + cls + "." + method);
        } catch (Throwable t) {
            host.log("[danger] hook failed: " + t);
        }
    }
}
