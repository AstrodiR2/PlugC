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

public final class Loader {
    public interface Host {
        void log(String s);
        void toast(String s);
        String storageGet(String key);
        void storageSet(String key, String value);
    }

    private static final class Run {
        QuickJs js;
        String status = "stopped";
        String error = "";
    }

    private static final String PRELUDE =
            "var claude = (function (h) {\n"
            + "  function s(v) { return String(v); }\n"
            + "  return {\n"
            + "    log: function () { var a = []; for (var i = 0; i < arguments.length; i++) a.push(s(arguments[i])); h.log(a.join(' ')); },\n"
            + "    toast: function (m) { h.toast(s(m)); },\n"
            + "    storage: {\n"
            + "      get: function (k) { var v = h.storageGet(s(k)); return (v === null || v === undefined) ? undefined : v; },\n"
            + "      set: function (k, v) { h.storageSet(s(k), s(v)); }\n"
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
            js.set("__host", Host.class, new HostImpl(c, dir));
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
