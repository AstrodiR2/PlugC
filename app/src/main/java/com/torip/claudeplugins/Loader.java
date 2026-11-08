package com.torip.claudeplugins;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.cash.quickjs.QuickJs;

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
import org.json.JSONObject;

public final class Loader {
    public interface Host {
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
    }

    private static final class Run {
        QuickJs js;
        String status = "stopped";
        String error = "";
        Danger danger;
        final Map<Integer, ScheduledFuture<?>> timers = new ConcurrentHashMap<>();
    }

    private static final ScheduledExecutorService sched = Executors.newScheduledThreadPool(1);

    private static final String PRELUDE =
            "var __timers = {};\n"
            + "function __timerFire(id) {\n"
            + "  var t = __timers[id]; if (!t) return;\n"
            + "  if (!t.repeat) delete __timers[id];\n"
            + "  try { t.fn(); } catch (e) { __host.log('[timer] ' + e); }\n"
            + "}\n"
            + "var claude = (function (h) {\n"
            + "  function s(v) { return String(v); }\n"
            + "  function parseJ(j) { try { return JSON.parse(j); } catch (e) { return null; } }\n"
            + "  function unwrap(j) { var o = parseJ(j); if (!o) return null; if ('v' in o) return o.v; if (o.handle) return { __handle: o.handle, __type: o.type }; return null; }\n"
            + "  return {\n"
            + "    log: function () { var a = []; for (var i = 0; i < arguments.length; i++) a.push(s(arguments[i])); h.log(a.join(' ')); },\n"
            + "    toast: function (m) { h.toast(s(m)); },\n"
            + "    vibrate: function (ms) { h.vibrate(ms || 200); },\n"
            + "    notify: function (title, text) { h.notify(s(title), s(text)); },\n"
            + "    device: function () { return JSON.parse(h.deviceInfo()); },\n"
            + "    su: function (cmd) { return h.su(s(cmd)); },\n"
            + "    setTimeout: function (fn, ms) { var id = h.setTimeoutHost(ms || 0, false); __timers[id] = { fn: fn, repeat: false }; return id; },\n"
            + "    setInterval: function (fn, ms) { var id = h.setTimeoutHost(ms || 0, true); __timers[id] = { fn: fn, repeat: true }; return id; },\n"
            + "    clearTimeout: function (id) { h.clearTimerHost(id); delete __timers[id]; },\n"
            + "    clearInterval: function (id) { h.clearTimerHost(id); delete __timers[id]; },\n"
            + "    storage: {\n"
            + "      get: function (k) { var v = h.storageGet(s(k)); return (v === null || v === undefined) ? undefined : v; },\n"
            + "      set: function (k, v) { h.storageSet(s(k), s(v)); },\n"
            + "      remove: function (k) { h.storageRemove(s(k)); },\n"
            + "      clear: function () { h.storageClear(); },\n"
            + "      keys: function () { return JSON.parse(h.storageKeys()); }\n"
            + "    },\n"
            + "    clipboard: {\n"
            + "      get: function () { return h.clipboardGet(); },\n"
            + "      set: function (t) { h.clipboardSet(s(t)); }\n"
            + "    },\n"
            + "    fs: {\n"
            + "      read: function (p) { return h.fsRead(s(p)); },\n"
            + "      write: function (p, c) { h.fsWrite(s(p), s(c)); },\n"
            + "      list: function (p) { return JSON.parse(h.fsList(s(p) || '')); },\n"
            + "      delete: function (p) { return h.fsDelete(s(p)); }\n"
            + "    },\n"
            + "    http: {\n"
            + "      get: function (url, headers) { return JSON.parse(h.httpRequest('GET', s(url), JSON.stringify(headers || {}), '')); },\n"
            + "      post: function (url, body, headers) { return JSON.parse(h.httpRequest('POST', s(url), JSON.stringify(headers || {}), s(body || ''))); },\n"
            + "      request: function (method, url, body, headers) { return JSON.parse(h.httpRequest(s(method), s(url), JSON.stringify(headers || {}), s(body || ''))); }\n"
            + "    },\n"
            + "    java: {\n"
            + "      useStatic: function (cls) {\n"
            + "        return { call: function (m, types, args) { return unwrap(h.dangerCallStatic(cls, m, (types || []).join(','), JSON.stringify(args || []))); },\n"
            + "                 getField: function (f) { return unwrap(h.dangerGetStaticField(cls, f)); },\n"
            + "                 setField: function (f, v) { h.dangerSetStaticField(cls, f, JSON.stringify({ v: v })); } };\n"
            + "      },\n"
            + "      call: function (ho, m, types, args) { return unwrap(h.dangerCall(ho.__handle, m, (types || []).join(','), JSON.stringify(args || []))); },\n"
            + "      getField: function (ho, f) { return unwrap(h.dangerGetField(ho.__handle, f)); },\n"
            + "      hook: function (cls, m, types) { h.dangerHook(cls, m, (types || []).join(',')); }\n"
            + "    }\n"
            + "  };\n"
            + "})(__host);\n";

    private static final Map<String, Run> runs = new HashMap<String, Run>();
    private static final ExecutorService ex = Executors.newSingleThreadExecutor();
    private static Handler ui;
    private static boolean started = false;

    private Loader() {}

    private static synchronized Handler ui() {
        if (ui == null) ui = new Handler(Looper.getMainLooper());
        return ui;
    }

    private static void finish(Runnable done) {
        if (done != null) ui().post(done);
    }

    public static String status(String dir) {
        synchronized (runs) {
            Run r = runs.get(dir);
            return r == null ? "stopped" : r.status;
        }
    }

    public static String error(String dir) {
        synchronized (runs) {
            Run r = runs.get(dir);
            return r == null ? "" : r.error;
        }
    }

    public static void startAllOnce(Activity a) {
        if (started) return;
        started = true;
        final Context c = a.getApplicationContext();
        ex.execute(new Runnable() {
            @Override
            public void run() {
                File[] fs = new File(c.getFilesDir(), "plugins").listFiles();
                if (fs == null) return;
                SharedPreferences p = c.getSharedPreferences("claudeplugins_ui", 0);
                for (File f : fs) {
                    if (f.isDirectory() && p.getBoolean("en:" + f.getName(), false)) {
                        startNow(c, f.getName());
                    }
                }
            }
        });
    }

    public static void start(final Context c, final String dir, final Runnable done) {
        ex.execute(new Runnable() {
            @Override
            public void run() {
                startNow(c, dir);
                finish(done);
            }
        });
    }

    public static void stop(final Context c, final String dir, final Runnable done) {
        ex.execute(new Runnable() {
            @Override
            public void run() {
                if (doStop(dir)) log(c, dir, "[stop] остановлен");
                finish(done);
            }
        });
    }

    public static void deleteData(final Context c, final String dir) {
        ex.execute(new Runnable() {
            @Override
            public void run() {
                doStop(dir);
                new File(new File(c.getFilesDir(), "pluginlogs"), dir + ".log").delete();
                try {
                    c.deleteSharedPreferences("plugin_storage_" + dir);
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private static boolean doStop(String dir) {
        Run r;
        synchronized (runs) {
            r = runs.remove(dir);
        }
        if (r == null) return false;
        if (r.js != null) {
            try {
                r.js.close();
            } catch (Throwable ignored) {
            }
        }
        return true;
    }

    private static void startNow(Context c, String dir) {
        doStop(dir);
        Run r = new Run();
        synchronized (runs) {
            runs.put(dir, r);
        }
        try {
            File main = new File(new File(c.getFilesDir(), "plugins/" + dir), "main.js");
            if (!main.exists()) {
                r.status = "error";
                r.error = "нет main.js";
                log(c, dir, "[error] main.js не найден");
                return;
            }
            String code = new String(readBytes(main), "UTF-8");
            log(c, dir, "[start] запуск main.js");
            QuickJs js = QuickJs.create();
            r.js = js;
            r.danger = new Danger(c.getClassLoader(), new HostImpl(c, dir));
            js.set("__host", Host.class, new HostImpl(c, dir, r));
            js.evaluate(PRELUDE, "prelude.js");
            js.evaluate(code, "main.js");
            r.status = "running";
            log(c, dir, "[ok] main.js выполнен");
        } catch (Throwable t) {
            r.status = "error";
            r.error = brief(t);
            log(c, dir, "[error] " + t + (t.getCause() != null ? " / " + t.getCause() : ""));
            if (r.js != null) {
                try {
                    r.js.close();
                } catch (Throwable ignored) {
                }
                r.js = null;
            }
        }
    }

    private static String brief(Throwable t) {
        String m = t.getMessage();
        if (m == null && t.getCause() != null) m = String.valueOf(t.getCause());
        if (m != null && m.length() > 140) m = m.substring(0, 140);
        return t.getClass().getSimpleName() + (m == null ? "" : ": " + m);
    }

    private static final class HostImpl implements Host {
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
    }

    public static void log(Context c, String dir, String line) {
        try {
            File d = new File(c.getFilesDir(), "pluginlogs");
            d.mkdirs();
            File f = new File(d, dir + ".log");
            String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date());
            synchronized (Loader.class) {
                if (f.length() > 200000) trim(f);
                FileOutputStream os = new FileOutputStream(f, true);
                try {
                    os.write((ts + " " + line + "\n").getBytes("UTF-8"));
                } finally {
                    os.close();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void trim(File f) throws Exception {
        int keep = 100000;
        RandomAccessFile r = new RandomAccessFile(f, "r");
        byte[] b = new byte[keep];
        try {
            r.seek(r.length() - keep);
            r.readFully(b);
        } finally {
            r.close();
        }
        FileOutputStream os = new FileOutputStream(f, false);
        try {
            os.write(b);
        } finally {
            os.close();
        }
    }

    public static String readLog(Context c, String dir) {
        try {
            File f = new File(new File(c.getFilesDir(), "pluginlogs"), dir + ".log");
            if (!f.exists()) return "";
            synchronized (Loader.class) {
                return new String(readBytes(f), "UTF-8");
            }
        } catch (Throwable t) {
            return "";
        }
    }

    public static byte[] readBytes(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) > 0) bo.write(buf, 0, r);
            return bo.toByteArray();
        } finally {
            in.close();
        }
    }
}
