cd ~/claude-plugins
D=app/src/main/java/com/torip/claudeplugins

cat > $D/Perms.java <<'EOF_P'
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
EOF_P

cat > $D/Danger.java <<'EOF_D'
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
EOF_D

python3 - <<'PY'
p = "app/src/main/java/com/torip/claudeplugins/Loader.java"
s = open(p, encoding="utf-8").read()
assert "dangerHook" not in s, "already patched"

s = s.replace(
"import app.cash.quickjs.QuickJs;",
"""import app.cash.quickjs.QuickJs;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Notification;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONObject;""")

s = s.replace(
"""    public interface Host {
        void log(String s);
        void toast(String s);
        String storageGet(String key);
        void storageSet(String key, String value);
    }""",
"""    public interface Host {
        void log(String s);
        void toast(String s);
        String storageGet(String key);
        void storageSet(String key, String value);
        void storageRemove(String key);
        String storageKeys();
        void storageClear();
        String httpRequest(String method, String url, String headersJson, String body);
        String fsRead(String path);
        void fsWrite(String path, String content);
        String fsList(String path);
        boolean fsDelete(String path);
        String clipboardGet();
        void clipboardSet(String text);
        void vibrate(long ms);
        void notify(String title, String text);
        String deviceInfo();
        String su(String cmd);
        int setTimeoutHost(long delayMs, boolean repeat);
        void clearTimerHost(int id);
        String dangerCallStatic(String cls, String method, String argTypes, String argsJson);
        String dangerCall(String handle, String method, String argTypes, String argsJson);
        String dangerGetStaticField(String cls, String field);
        void dangerSetStaticField(String cls, String field, String valueJson);
        String dangerGetField(String handle, String field);
        void dangerHook(String cls, String method, String argTypes);
    }""")

s = s.replace(
"""    private static final class Run {
        QuickJs js;
        String status = "stopped";
        String error = "";
    }""",
"""    private static final class Run {
        QuickJs js;
        String status = "stopped";
        String error = "";
        Danger danger;
        final Map<Integer, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    }

    private static final ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);""")

s = s.replace(
'''    private static final String PRELUDE =
            "var claude = (function (h) {\\n"
            + "  function s(v) { return String(v); }\\n"
            + "  return {\\n"
            + "    log: function () { var a = []; for (var i = 0; i < arguments.length; i++) a.push(s(arguments[i])); h.log(a.join(' ')); },\\n"
            + "    toast: function (m) { h.toast(s(m)); },\\n"
            + "    storage: {\\n"
            + "      get: function (k) { var v = h.storageGet(s(k)); return (v === null || v === undefined) ? undefined : v; },\\n"
            + "      set: function (k, v) { h.storageSet(s(k), s(v)); }\\n"
            + "    }\\n"
            + "  };\\n"
            + "})(__host);\\n";''',
'''    private static final String PRELUDE =
            "var __timers = {};\\n"
            + "function __timerFire(id) {\\n"
            + "  var t = __timers[id]; if (!t) return;\\n"
            + "  if (!t.repeat) delete __timers[id];\\n"
            + "  try { t.fn(); } catch (e) { __host.log('[timer] ' + e); }\\n"
            + "}\\n"
            + "var claude = (function (h) {\\n"
            + "  function s(v) { return String(v); }\\n"
            + "  function parseJ(j) { try { return JSON.parse(j); } catch (e) { return null; } }\\n"
            + "  function unwrap(j) { var o = parseJ(j); if (!o) return null; if ('v' in o) return o.v; if (o.handle) return { __handle: o.handle, __type: o.type }; return null; }\\n"
            + "  return {\\n"
            + "    log: function () { var a = []; for (var i = 0; i < arguments.length; i++) a.push(s(arguments[i])); h.log(a.join(' ')); },\\n"
            + "    toast: function (m) { h.toast(s(m)); },\\n"
            + "    vibrate: function (ms) { h.vibrate(ms || 200); },\\n"
            + "    notify: function (title, text) { h.notify(s(title), s(text)); },\\n"
            + "    device: function () { return JSON.parse(h.deviceInfo()); },\\n"
            + "    su: function (cmd) { return h.su(s(cmd)); },\\n"
            + "    setTimeout: function (fn, ms) { var id = h.setTimeoutHost(ms || 0, false); __timers[id] = { fn: fn, repeat: false }; return id; },\\n"
            + "    setInterval: function (fn, ms) { var id = h.setTimeoutHost(ms || 0, true); __timers[id] = { fn: fn, repeat: true }; return id; },\\n"
            + "    clearTimeout: function (id) { h.clearTimerHost(id); delete __timers[id]; },\\n"
            + "    clearInterval: function (id) { h.clearTimerHost(id); delete __timers[id]; },\\n"
            + "    storage: {\\n"
            + "      get: function (k) { var v = h.storageGet(s(k)); return (v === null || v === undefined) ? undefined : v; },\\n"
            + "      set: function (k, v) { h.storageSet(s(k), s(v)); },\\n"
            + "      remove: function (k) { h.storageRemove(s(k)); },\\n"
            + "      clear: function () { h.storageClear(); },\\n"
            + "      keys: function () { return JSON.parse(h.storageKeys()); }\\n"
            + "    },\\n"
            + "    clipboard: {\\n"
            + "      get: function () { return h.clipboardGet(); },\\n"
            + "      set: function (t) { h.clipboardSet(s(t)); }\\n"
            + "    },\\n"
            + "    fs: {\\n"
            + "      read: function (p) { return h.fsRead(s(p)); },\\n"
            + "      write: function (p, c) { h.fsWrite(s(p), s(c)); },\\n"
            + "      list: function (p) { return JSON.parse(h.fsList(s(p) || '')); },\\n"
            + "      delete: function (p) { return h.fsDelete(s(p)); }\\n"
            + "    },\\n"
            + "    http: {\\n"
            + "      get: function (url, headers) { return JSON.parse(h.httpRequest('GET', s(url), JSON.stringify(headers || {}), '')); },\\n"
            + "      post: function (url, body, headers) { return JSON.parse(h.httpRequest('POST', s(url), JSON.stringify(headers || {}), s(body || ''))); },\\n"
            + "      request: function (method, url, body, headers) { return JSON.parse(h.httpRequest(s(method), s(url), JSON.stringify(headers || {}), s(body || ''))); }\\n"
            + "    },\\n"
            + "    java: {\\n"
            + "      useStatic: function (cls) {\\n"
            + "        return { call: function (m, types, args) { return unwrap(h.dangerCallStatic(cls, m, (types || []).join(','), JSON.stringify(args || []))); },\\n"
            + "                 getField: function (f) { return unwrap(h.dangerGetStaticField(cls, f)); },\\n"
            + "                 setField: function (f, v) { h.dangerSetStaticField(cls, f, JSON.stringify({ v: v })); } };\\n"
            + "      },\\n"
            + "      call: function (ho, m, types, args) { return unwrap(h.dangerCall(ho.__handle, m, (types || []).join(','), JSON.stringify(args || []))); },\\n"
            + "      getField: function (ho, f) { return unwrap(h.dangerGetField(ho.__handle, f)); },\\n"
            + "      hook: function (cls, m, types) { h.dangerHook(cls, m, (types || []).join(',')); }\\n"
            + "    }\\n"
            + "  };\\n"
            + "})(__host);\\n";''')

s = s.replace(
'''            QuickJs js = QuickJs.create();
            r.js = js;
            js.set("__host", Host.class, new HostImpl(c, dir));''',
'''            QuickJs js = QuickJs.create();
            r.js = js;
            r.danger = new Danger(c.getClassLoader(), new HostImpl(c, dir));
            js.set("__host", Host.class, new HostImpl(c, dir, r));''')

s = s.replace(
'''    private static final class HostImpl implements Host {
        final Context c;
        final String dir;
        final SharedPreferences sp;

        HostImpl(Context c, String dir) {
            this.c = c;
            this.dir = dir;
            this.sp = c.getSharedPreferences("plugin_storage_" + dir, 0);
        }

        @Override
        public void log(String s) {
            Loader.log(c, dir, s);
        }

        @Override
        public void toast(final String s) {
            ui().post(new Runnable() {
                @Override
                public void run() {
                    Toast.makeText(c, s, Toast.LENGTH_SHORT).show();
                }
            });
        }

        @Override
        public String storageGet(String key) {
            return sp.getString(key, null);
        }

        @Override
        public void storageSet(String key, String value) {
            sp.edit().putString(key, value).apply();
        }
    }''',
'''    private static final class HostImpl implements Host {
        final Context c;
        final String dir;
        final SharedPreferences sp;
        Run run;

        HostImpl(Context c, String dir) { this(c, dir, null); }

        HostImpl(Context c, String dir, Run run) {
            this.c = c;
            this.dir = dir;
            this.run = run;
            this.sp = c.getSharedPreferences("plugin_storage_" + dir, 0);
        }

        private Danger danger() {
            if (run != null && run.danger != null) return run.danger;
            return new Danger(c.getClassLoader(), this);
        }

        private File dataDir() {
            File f = new File(new File(c.getFilesDir(), "plugins/" + dir), "data");
            f.mkdirs();
            return f;
        }

        private File safe(String path) throws Exception {
            File base = dataDir();
            File f = new File(base, path == null ? "" : path);
            String canon = base.getCanonicalPath() + File.separator;
            if (!f.getCanonicalPath().startsWith(canon) && !f.getCanonicalPath().equals(base.getCanonicalPath()))
                throw new SecurityException("path escapes plugin data dir");
            return f;
        }

        @Override
        public void log(String s) { Loader.log(c, dir, s); }

        @Override
        public void toast(final String s) {
            ui().post(() -> Toast.makeText(c, s, Toast.LENGTH_SHORT).show());
        }

        @Override
        public String storageGet(String key) { return sp.getString(key, null); }

        @Override
        public void storageSet(String key, String value) { sp.edit().putString(key, value).apply(); }

        @Override
        public void storageRemove(String key) { sp.edit().remove(key).apply(); }

        @Override
        public String storageKeys() {
            return new JSONArray(sp.getAll().keySet()).toString();
        }

        @Override
        public void storageClear() { sp.edit().clear().apply(); }

        @Override
        public String httpRequest(String method, String url, String headersJson, String body) {
            if (!Perms.check(c, dir, "network", method + " " + url)) {
                return err("permission denied: network");
            }
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                JSONObject headers = new JSONObject(headersJson);
                java.util.Iterator<String> it = headers.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    conn.setRequestProperty(k, headers.getString(k));
                }
                if (body != null && body.length() > 0) {
                    conn.setDoOutput(true);
                    OutputStream os = conn.getOutputStream();
                    os.write(body.getBytes("UTF-8"));
                    os.close();
                }
                int code = conn.getResponseCode();
                InputStream in = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
                String text = in == null ? "" : new String(readBytes(in), "UTF-8");
                JSONObject r = new JSONObject();
                r.put("status", code);
                r.put("body", text);
                r.put("ok", code >= 200 && code < 300);
                return r.toString();
            } catch (Throwable t) {
                log("[http] " + t);
                return err(String.valueOf(t));
            } finally {
                if (conn != null) conn.disconnect();
            }
        }

        private byte[] readBytes(InputStream in) throws Exception {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        }

        private String err(String m) {
            try {
                JSONObject r = new JSONObject();
                r.put("status", 0);
                r.put("body", "");
                r.put("ok", false);
                r.put("error", m);
                return r.toString();
            } catch (Exception e) { return "{}"; }
        }

        @Override
        public String fsRead(String path) {
            if (!Perms.check(c, dir, "fs", "read " + path)) return null;
            try {
                File f = safe(path);
                if (!f.exists()) return null;
                return new String(Loader.readBytes(f), "UTF-8");
            } catch (Throwable t) {
                log("[fs] read failed: " + t);
                return null;
            }
        }

        @Override
        public void fsWrite(String path, String content) {
            if (!Perms.check(c, dir, "fs", "write " + path)) return;
            try {
                File f = safe(path);
                f.getParentFile().mkdirs();
                FileOutputStream os = new FileOutputStream(f);
                try { os.write(content.getBytes("UTF-8")); } finally { os.close(); }
            } catch (Throwable t) {
                log("[fs] write failed: " + t);
            }
        }

        @Override
        public String fsList(String path) {
            try {
                File f = safe(path);
                File[] cs = f.listFiles();
                JSONArray a = new JSONArray();
                if (cs != null) for (File x : cs) a.put(x.getName() + (x.isDirectory() ? "/" : ""));
                return a.toString();
            } catch (Throwable t) {
                return "[]";
            }
        }

        @Override
        public boolean fsDelete(String path) {
            if (!Perms.check(c, dir, "fs", "delete " + path)) return false;
            try {
                return safe(path).delete();
            } catch (Throwable t) {
                return false;
            }
        }

        @Override
        public String clipboardGet() {
            if (!Perms.check(c, dir, "clipboard", "чтение буфера")) return null;
            try {
                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0) {
                    CharSequence t = cm.getPrimaryClip().getItemAt(0).getText();
                    return t == null ? null : t.toString();
                }
            } catch (Throwable ignored) {}
            return null;
        }

        @Override
        public void clipboardSet(final String text) {
            if (!Perms.check(c, dir, "clipboard", "запись в буфер")) return;
            ui().post(() -> {
                ClipboardManager cm = (ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText(dir, text));
            });
        }

        @Override
        public void vibrate(final long ms) {
            ui().post(() -> {
                try {
                    Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
                    if (v == null) return;
                    if (Build.VERSION.SDK_INT >= 26) {
                        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
                    } else {
                        v.vibrate(ms);
                    }
                } catch (Throwable ignored) {}
            });
        }

        @Override
        public void notify(final String title, final String text) {
            if (!Perms.check(c, dir, "notify", title + ": " + text)) return;
            ui().post(() -> {
                try {
                    NotificationManager nm = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
                    String chId = "claudeplugins";
                    if (Build.VERSION.SDK_INT >= 26) {
                        NotificationChannel ch = new NotificationChannel(chId, "Плагины", NotificationManager.IMPORTANCE_DEFAULT);
                        nm.createNotificationChannel(ch);
                    }
                    Notification n = new Notification.Builder(c, chId)
                            .setContentTitle(title)
                            .setContentText(text)
                            .setSmallIcon(android.R.drawable.ic_dialog_info)
                            .setAutoCancel(true)
                            .build();
                    nm.notify((dir + title).hashCode(), n);
                } catch (Throwable t) {
                    log("[notify] failed: " + t);
                }
            });
        }

        @Override
        public String deviceInfo() {
            try {
                JSONObject j = new JSONObject();
                j.put("model", Build.MODEL);
                j.put("manufacturer", Build.MANUFACTURER);
                j.put("sdk", Build.VERSION.SDK_INT);
                j.put("release", Build.VERSION.RELEASE);
                try {
                    j.put("appVersion", c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName);
                } catch (Throwable ignored) {}
                return j.toString();
            } catch (Throwable t) {
                return "{}";
            }
        }

        @Override
        public String su(String cmd) {
            if (!Perms.confirmSu(c, dir, cmd)) {
                log("[su] запрещено: " + cmd);
                return err("permission denied: root");
            }
            try {
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
                byte[] out = readBytes(p.getInputStream());
                byte[] errb = readBytes(p.getErrorStream());
                int code = p.waitFor();
                JSONObject r = new JSONObject();
                r.put("code", code);
                r.put("out", new String(out, "UTF-8"));
                r.put("err", new String(errb, "UTF-8"));
                return r.toString();
            } catch (Throwable t) {
                log("[su] failed: " + t);
                return err(String.valueOf(t));
            }
        }

        @Override
        public int setTimeoutHost(long delayMs, boolean repeat) {
            final int id = (int) (System.nanoTime() & 0x7fffffff);
            if (run == null) return id;
            Runnable fire = () -> ex.execute(() -> {
                try {
                    if (run.js != null) run.js.evaluate("__timerFire(" + id + ");", "timer.js");
                } catch (Throwable t) {
                    log("[timer] " + t);
                }
            });
            ScheduledFuture<?> f = repeat
                    ? sched.scheduleAtFixedRate(fire, delayMs, Math.max(16, delayMs), java.util.concurrent.TimeUnit.MILLISECONDS)
                    : sched.schedule(fire, delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            run.timers.put(id, f);
            return id;
        }

        @Override
        public void clearTimerHost(int id) {
            if (run == null) return;
            ScheduledFuture<?> f = run.timers.remove(id);
            if (f != null) f.cancel(false);
        }

        @Override
        public String dangerCallStatic(String cls, String method, String argTypes, String argsJson) {
            if (!Perms.dangerEnabled(c, dir)) return "null";
            return danger().callStatic(cls, method, argTypes, argsJson);
        }

        @Override
        public String dangerCall(String handle, String method, String argTypes, String argsJson) {
            if (!Perms.dangerEnabled(c, dir)) return "null";
            return danger().call(handle, method, argTypes, argsJson);
        }

        @Override
        public String dangerGetStaticField(String cls, String field) {
            if (!Perms.dangerEnabled(c, dir)) return "null";
            return danger().getStaticField(cls, field);
        }

        @Override
        public void dangerSetStaticField(String cls, String field, String valueJson) {
            if (!Perms.dangerEnabled(c, dir)) return;
            danger().setStaticField(cls, field, valueJson);
        }

        @Override
        public String dangerGetField(String handle, String field) {
            if (!Perms.dangerEnabled(c, dir)) return "null";
            return danger().getField(handle, field);
        }

        @Override
        public void dangerHook(String cls, String method, String argTypes) {
            if (!Perms.dangerEnabled(c, dir)) return;
            danger().hook(cls, method, argTypes);
        }
    }''')

open(p, "w", encoding="utf-8").write(s)
print("Loader.java patched ok")
PY

python3 - <<'PY'
p = "app/src/main/java/com/torip/claudeplugins/Hook.java"
s = open(p, encoding="utf-8").read()
s = s.replace(
'''                    activity = new WeakReference<>(a);''',
'''                    activity = new WeakReference<>(a);
                    Perms.activity = new WeakReference<>(a);''')
open(p, "w", encoding="utf-8").write(s)
print("Hook.java patched" if "Perms.activity" in s else "Hook.java PATCH FAILED")
PY

python3 - <<'PY'
p = "app/src/main/java/com/torip/claudeplugins/PluginsUi.java"
s = open(p, encoding="utf-8").read()

# компактная строка-переключатель allow/ask/deny — вставляется в info-блок деталей
anchor = '''        detStatus = padTop(tv("", 13, SUB, false), 10);
        info.addView(detStatus);
        body.addView(info, new LinearLayout.LayoutParams(MP, WC));'''
assert anchor in s, "anchor not found"

insert = '''        detStatus = padTop(tv("", 13, SUB, false), 10);
        info.addView(detStatus);
        body.addView(info, new LinearLayout.LayoutParams(MP, WC));

        TextView permsLab = tv("Разрешения", 13, DIM, true);
        LinearLayout.LayoutParams pl = new LinearLayout.LayoutParams(WC, WC);
        pl.topMargin = dp(20);
        pl.bottomMargin = dp(8);
        pl.leftMargin = dp(4);
        body.addView(permsLab, pl);

        LinearLayout permsBox = box();
        for (int pi = 0; pi < Perms.KEYS.length; pi++) {
            final String key = Perms.KEYS[pi];
            LinearLayout row = new LinearLayout(a);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (pi > 0) row.setPadding(0, dp(10), 0, 0);
            row.addView(tv(Perms.label(key), 14, TEXT, false),
                    new LinearLayout.LayoutParams(0, WC, 1f));
            row.addView(permSeg(p.dir, key), new LinearLayout.LayoutParams(WC, WC));
            permsBox.addView(row, new LinearLayout.LayoutParams(MP, WC));
        }
        body.addView(permsBox, new LinearLayout.LayoutParams(MP, WC));

        LinearLayout.LayoutParams dgl = new LinearLayout.LayoutParams(MP, WC);
        dgl.topMargin = dp(12);
        LinearLayout danger = new LinearLayout(a);
        danger.setOrientation(LinearLayout.HORIZONTAL);
        danger.setGravity(Gravity.CENTER_VERTICAL);
        danger.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable dg = new GradientDrawable();
        dg.setColor(0xFF241412);
        dg.setCornerRadius(dp(16));
        dg.setStroke(dp(1), 0xFF4A2320);
        danger.setBackground(dg);
        LinearLayout dcol = new LinearLayout(a);
        dcol.setOrientation(LinearLayout.VERTICAL);
        dcol.addView(tv("Опасный режим", 15, RED, true));
        TextView dd = tv("Прямой доступ к классам Claude: java.use, хуки, чтение токена. Видит всё.", 12, SUB, false);
        dd.setPadding(0, dp(4), 0, 0);
        dcol.addView(dd);
        danger.addView(dcol, new LinearLayout.LayoutParams(0, WC, 1f));
        final Sw dsw = new Sw(a, Perms.dangerEnabled(a, p.dir));
        dsw.change = () -> {
            if (dsw.on) {
                new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                        .setTitle("Включить опасный режим?")
                        .setMessage(p.name + " получит доступ ко всем классам Claude, включая токен и чаты.")
                        .setCancelable(false)
                        .setNegativeButton("Отмена", (di, w) -> { dsw.on = false; dsw.prog = 0f; dsw.invalidate(); })
                        .setPositiveButton("Включить", (di, w) -> Perms.setDanger(a, p.dir, true))
                        .show();
            } else {
                Perms.setDanger(a, p.dir, false);
            }
        };
        danger.addView(dsw, new LinearLayout.LayoutParams(WC, WC));
        body.addView(danger, dgl);'''

s = s.replace(anchor, insert)

# добавить helper permSeg() и импорт AlertDialog уже есть (используется в confirmDelete)
anchor2 = '''    private void refreshDetail() {'''
assert anchor2 in s, "anchor2 not found"
helper = '''    private View permSeg(final String dir, final String key) {
        final LinearLayout seg = new LinearLayout(a);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.TRANSPARENT);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFF3A3A38);
        seg.setBackground(bg);
        final TextView[] btns = new TextView[3];
        final String[] modes = {Perms.ALLOW, Perms.ASK, Perms.DENY};
        final String[] labels = {"Вкл", "Спр", "Выкл"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = new TextView(a);
            t.setText(labels[i]);
            t.setTextSize(11);
            t.setGravity(Gravity.CENTER);
            t.setPadding(dp(10), dp(6), dp(10), dp(6));
            t.setOnClickListener(v -> {
                Perms.set(a, dir, key, modes[idx]);
                paintSeg(btns, modes, Perms.get(a, dir, key));
            });
            btns[i] = t;
            seg.addView(t, new LinearLayout.LayoutParams(WC, WC));
        }
        paintSeg(btns, modes, Perms.get(a, dir, key));
        return seg;
    }

    private void paintSeg(TextView[] btns, String[] modes, String cur) {
        for (int i = 0; i < btns.length; i++) {
            if (modes[i].equals(cur)) {
                int col = Perms.ALLOW.equals(cur) ? GREEN : Perms.DENY.equals(cur) ? RED : ACCENT;
                GradientDrawable g = new GradientDrawable();
                g.setColor(col);
                g.setCornerRadius(dp(8));
                btns[i].setBackground(g);
                btns[i].setTextColor(0xFF141414);
                btns[i].setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                btns[i].setBackground(null);
                btns[i].setTextColor(SUB);
                btns[i].setTypeface(Typeface.DEFAULT);
            }
        }
    }

    private void refreshDetail() {'''
s = s.replace(anchor2, helper)

open(p, "w", encoding="utf-8").write(s)
print("PluginsUi.java patched ok")
PY

git add -A && git commit -qm "v14: stage 3 full API (http/fs/clipboard/notify/vibrate/timers/su/device), per-plugin permissions (allow/ask/deny), dangerous mode via reflection bridge" && git push -q
sleep 15
RID=$(gh run list -L1 --json databaseId -q '.[0].databaseId')
gh run watch --exit-status $RID
rm -rf ~/apk && gh run download $RID -n module-debug -D ~/apk
rm -rf ~/chk2 && mkdir ~/chk2 && unzip -o -q ~/apk/app-debug.apk -d ~/chk2
if grep -a -q "v13 loaded" ~/chk2/classes*.dex; then
  su -c "pm install -r $HOME/apk/app-debug.apk"
  su -c "am force-stop com.anthropic.claude"
  echo "ГОТОВО: v14 установлена"
else
  echo "СТОП: в APK нет v13/v14-меток, не ставлю"
fi
