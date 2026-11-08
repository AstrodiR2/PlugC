package com.torip.claudeplugins;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import de.robv.android.xposed.XposedBridge;

final class PluginsUi {
    static final int BG = 0xFF141414, CARD = 0xFF1F1F1E, STROKE = 0xFF2E2E2C,
            TEXT = 0xFFF2F1EE, SUB = 0xFFB4B3AE, DIM = 0xFF8E8D89, ACCENT = 0xFFD97757,
            BLUE = 0xFF3B82F6, RED = 0xFFFF6B6B, MENU = 0xFF222222;
    private static final int MP = -1, WC = -2;
    static final int REQ = 7731;
    private static boolean showing = false;
    private static PluginsUi cur;
    private static final ArgbEvaluator EVAL = new ArgbEvaluator();

    private final Activity a;
    private final float d;
    private final SharedPreferences prefs;
    private Dialog dlg;
    private Slide root;
    private LinearLayout list;
    private View scrim, indicator, addBar, pageFiles, pageCat;
    private TextView t0, t1;
    private PopupWindow popup;
    private float tx, ty;
    private int tab = 0;
    private boolean closing = false;

    private PluginsUi(Activity a) {
        this.a = a;
        this.d = a.getResources().getDisplayMetrics().density;
        this.prefs = a.getSharedPreferences("claudeplugins_ui", 0);
    }

    static void show(Activity a) {
        if (showing) return;
        showing = true;
        try {
            cur = new PluginsUi(a);
            cur.open();
        } catch (Throwable t) {
            showing = false;
            cur = null;
            XposedBridge.log("ClaudePlugins: ui failed: " + t);
        }
    }

    static void onResult(Activity act, int req, int res, Intent data) {
        if (req != REQ || cur == null) return;
        if (res == Activity.RESULT_OK && data != null && data.getData() != null) {
            cur.importUri(data.getData());
        }
    }

    private int dp(float v) { return (int) (v * d + 0.5f); }

    private void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); }

    private float width() { return a.getResources().getDisplayMetrics().widthPixels; }

    private View content() { return a.findViewById(android.R.id.content); }

    // ================= icons =================
    static class Ic extends View {
        static final int BACK = 0, REFRESH = 1, FILE = 2, EQNOT = 3;
        final int type;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        Ic(Context c, int type, int color) {
            super(c);
            this.type = type;
            p.setStyle(Paint.Style.STROKE);
            p.setColor(color);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            p.setStrokeWidth(2f);
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()) / 24f;
            c.scale(s, s);
            path.reset();
            if (type == BACK) {
                c.drawLine(20, 12, 4, 12, p);
                path.moveTo(10, 6);
                path.lineTo(4, 12);
                path.lineTo(10, 18);
                c.drawPath(path, p);
            } else if (type == REFRESH) {
                c.drawArc(new RectF(4, 4, 20, 20), 0, 315, false, p);
                path.moveTo(20, 4);
                path.lineTo(20, 9);
                path.lineTo(15, 9);
                c.drawPath(path, p);
            } else if (type == EQNOT) {
                c.drawLine(5, 9, 19, 9, p);
                c.drawLine(5, 15, 19, 15, p);
                c.drawLine(19, 5, 5, 19, p);
            } else {
                path.moveTo(6, 22);
                path.quadTo(4, 22, 4, 20);
                path.lineTo(4, 4);
                path.quadTo(4, 2, 6, 2);
                path.lineTo(14, 2);
                path.lineTo(20, 8);
                path.lineTo(20, 20);
                path.quadTo(20, 22, 18, 22);
                path.close();
                c.drawPath(path, p);
                path.reset();
                path.moveTo(14, 2);
                path.lineTo(14, 7);
                path.quadTo(14, 8, 15, 8);
                path.lineTo(20, 8);
                c.drawPath(path, p);
            }
        }
    }

    // ================= animated switch =================
    static class Sw extends View {
        boolean on;
        float prog;
        Runnable change;
        final float d;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ValueAnimator va;

        Sw(Context c, boolean state) {
            super(c);
            on = state;
            prog = state ? 1f : 0f;
            d = c.getResources().getDisplayMetrics().density;
            setOnClickListener(v -> toggle());
        }

        void toggle() {
            on = !on;
            if (va != null) va.cancel();
            va = ValueAnimator.ofFloat(prog, on ? 1f : 0f);
            va.setDuration(200);
            va.setInterpolator(new DecelerateInterpolator());
            va.addUpdateListener(an -> {
                prog = (Float) an.getAnimatedValue();
                invalidate();
            });
            va.start();
            if (change != null) change.run();
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) (45 * d), (int) (25 * d));
        }

        @Override
        protected void onDraw(Canvas c) {
            p.setColor((Integer) EVAL.evaluate(prog, 0xFF2F2F2E, BLUE));
            c.drawRoundRect(0, 0, 45 * d, 25 * d, 12.5f * d, 12.5f * d, p);
            p.setColor(TEXT);
            float x = (12.5f + 23f * prog) * d;
            c.drawCircle(x, 12.5f * d, 9.5f * d, p);
        }
    }

    // ================= root with edge-swipe back =================
    static class Slide extends FrameLayout {
        interface Host { void onDrag(float tx); void onRelease(float tx); }
        Host host;
        float downX, downY, lastTx;
        final float edge;
        boolean drag;

        Slide(Context c) {
            super(c);
            edge = 28 * c.getResources().getDisplayMetrics().density;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    drag = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (!drag && downX < edge) {
                        float dx = e.getRawX() - downX, dy = Math.abs(e.getRawY() - downY);
                        if (dx > edge / 3 && dx > dy) {
                            drag = true;
                            return true;
                        }
                    }
                    break;
                default:
                    break;
            }
            return false;
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    drag = false;
                    return downX < edge;
                case MotionEvent.ACTION_MOVE:
                    if (downX >= edge) return false;
                    if (!drag) {
                        float dx = e.getRawX() - downX, dy = Math.abs(e.getRawY() - downY);
                        if (dx > edge / 3 && dx > dy) drag = true;
                    }
                    if (drag && host != null) {
                        lastTx = Math.max(0f, e.getRawX() - downX);
                        host.onDrag(lastTx);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    if (drag && host != null) {
                        drag = false;
                        host.onRelease(lastTx);
                    }
                    return true;
                default:
                    return false;
            }
        }
    }

    // ================= data =================
    private static class Plugin {
        File file;
        String dir, name, version, desc, author;
    }

    private File pluginsDir() { return new File(a.getFilesDir(), "plugins"); }

    private List<Plugin> load() {
        List<Plugin> out = new ArrayList<Plugin>();
        File[] fs = pluginsDir().listFiles();
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) {
            if (!f.isDirectory()) continue;
            Plugin p = new Plugin();
            p.file = f;
            p.dir = f.getName();
            p.name = p.dir;
            p.version = "?";
            p.desc = "";
            p.author = "";
            try {
                File m = new File(f, "manifest.json");
                if (m.exists()) {
                    byte[] b = new byte[(int) m.length()];
                    FileInputStream in = new FileInputStream(m);
                    int off = 0;
                    while (off < b.length) {
                        int r = in.read(b, off, b.length - off);
                        if (r < 0) break;
                        off += r;
                    }
                    in.close();
                    JSONObject j = new JSONObject(new String(b, "UTF-8"));
                    p.name = j.optString("name", p.dir);
                    p.version = j.optString("version", "?");
                    p.desc = j.optString("description", "");
                    p.author = j.optString("author", "");
                }
            } catch (Throwable ignored) {
            }
            out.add(p);
        }
        return out;
    }

    // ================= import =================
    private void importUri(final Uri uri) {
        new Thread(() -> {
            try {
                final String res = doImport(uri);
                a.runOnUiThread(() -> {
                    toast("Добавлено: " + res);
                    fill();
                });
            } catch (final Throwable t) {
                a.runOnUiThread(() -> toast("Не удалось: " + t.getMessage()));
            }
        }).start();
    }

    private String displayName(Uri uri) {
        Cursor c = null;
        try {
            c = a.getContentResolver().query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) c.close();
        }
        String s = uri.getLastPathSegment();
        return s == null ? "plugin" : s;
    }

    private File uniqueDir(String base) {
        File root0 = pluginsDir();
        root0.mkdirs();
        File dir = new File(root0, base);
        int n = 2;
        while (dir.exists()) dir = new File(root0, base + "-" + (n++));
        return dir;
    }

    private String doImport(Uri uri) throws Exception {
        String name = displayName(uri);
        String lower = name.toLowerCase();
        int dot = name.lastIndexOf('.');
        String base = (dot > 0 ? name.substring(0, dot) : name).replaceAll("[^\\p{L}\\p{N}._-]", "_");
        if (base.length() == 0) base = "plugin";
        File dir = uniqueDir(base);
        dir.mkdirs();
        if (lower.endsWith(".zip")) {
            String prefix = null;
            boolean common = true, any = false;
            InputStream in1 = a.getContentResolver().openInputStream(uri);
            try {
                ZipInputStream z = new ZipInputStream(in1);
                ZipEntry e;
                while ((e = z.getNextEntry()) != null) {
                    String n = e.getName();
                    if (n.startsWith("__MACOSX")) continue;
                    any = true;
                    int i = n.indexOf('/');
                    if (i < 0) { common = false; break; }
                    String f = n.substring(0, i + 1);
                    if (prefix == null) prefix = f;
                    else if (!prefix.equals(f)) { common = false; break; }
                }
            } finally {
                in1.close();
            }
            if (!any) throw new Exception("пустой архив");
            String strip = (common && prefix != null) ? prefix : "";
            String canon = dir.getCanonicalPath() + File.separator;
            InputStream in2 = a.getContentResolver().openInputStream(uri);
            try {
                ZipInputStream z = new ZipInputStream(in2);
                ZipEntry e;
                byte[] buf = new byte[16384];
                while ((e = z.getNextEntry()) != null) {
                    String n = e.getName();
                    if (n.startsWith("__MACOSX")) continue;
                    if (strip.length() > 0 && n.startsWith(strip)) n = n.substring(strip.length());
                    if (n.length() == 0) continue;
                    File out = new File(dir, n);
                    if (!out.getCanonicalPath().startsWith(canon)) continue;
                    if (e.isDirectory()) { out.mkdirs(); continue; }
                    out.getParentFile().mkdirs();
                    OutputStream os = new FileOutputStream(out);
                    try {
                        int r;
                        while ((r = z.read(buf)) > 0) os.write(buf, 0, r);
                    } finally {
                        os.close();
                    }
                }
            } finally {
                in2.close();
            }
        } else if (lower.endsWith(".js")) {
            InputStream in = a.getContentResolver().openInputStream(uri);
            OutputStream os = new FileOutputStream(new File(dir, "main.js"));
            try {
                byte[] buf = new byte[16384];
                int r;
                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
            } finally {
                in.close();
                os.close();
            }
        } else {
            deleteDir(dir);
            throw new Exception("нужен .zip или .js");
        }
        File mf = new File(dir, "manifest.json");
        if (!mf.exists()) {
            JSONObject j = new JSONObject();
            j.put("name", base);
            j.put("version", "0");
            j.put("description", "");
            FileOutputStream os = new FileOutputStream(mf);
            os.write(j.toString().getBytes("UTF-8"));
            os.close();
        }
        return dir.getName();
    }

    private void openPicker() {
        try {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.setType("*/*");
            i.addCategory(Intent.CATEGORY_OPENABLE);
            a.startActivityForResult(i, REQ);
        } catch (Throwable t) {
            toast("Не удалось открыть выбор файла");
        }
    }

    // ================= screen =================
    private void open() {
        dlg = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar) {
            @Override
            public void onBackPressed() {
                if (popup != null && popup.isShowing()) {
                    popup.dismiss();
                    return;
                }
                closeScreen();
            }
        };
        Window w = dlg.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        w.setWindowAnimations(0);
        w.setLayout(MP, MP);
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);

        root = new Slide(a);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, ins) -> {
            v.setPadding(0, ins.getSystemWindowInsetTop(), 0, ins.getSystemWindowInsetBottom());
            return ins;
        });
        root.host = new Slide.Host() {
            @Override
            public void onDrag(float t) {
                root.setTranslationX(t);
                parallax(1f - t / width());
            }

            @Override
            public void onRelease(float t) {
                if (t > width() / 3f) {
                    closeScreen();
                } else {
                    root.animate().translationX(0).setDuration(200).start();
                    content().animate().translationX(-width() * 0.3f).setDuration(200).start();
                }
            }
        };

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(header(), new LinearLayout.LayoutParams(MP, dp(56)));

        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(MP, dp(40));
        tl.leftMargin = dp(16);
        tl.rightMargin = dp(16);
        tl.topMargin = dp(8);
        col.addView(buildTabs(), tl);

        FrameLayout pages = new FrameLayout(a);
        ScrollView sv = new ScrollView(a);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(16), dp(16), dp(16));
        sv.addView(list, new FrameLayout.LayoutParams(MP, WC));
        pageFiles = sv;
        pages.addView(sv, new FrameLayout.LayoutParams(MP, MP));

        LinearLayout cat = new LinearLayout(a);
        cat.setOrientation(LinearLayout.VERTICAL);
        cat.setGravity(Gravity.CENTER_HORIZONTAL);
        cat.setPadding(dp(32), dp(120), dp(32), 0);
        cat.addView(new Ic(a, Ic.EQNOT, SUB), new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView ct = tv("Извините, но каталог не реализован", 15, SUB, false);
        ct.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(WC, WC);
        cl.topMargin = dp(16);
        cat.addView(ct, cl);
        cat.setVisibility(View.GONE);
        pageCat = cat;
        pages.addView(cat, new FrameLayout.LayoutParams(MP, MP));
        col.addView(pages, new LinearLayout.LayoutParams(MP, 0, 1f));

        LinearLayout bar = new LinearLayout(a);
        bar.setPadding(dp(16), dp(8), dp(16), dp(16));
        View add = pill("Добавить файл", true, this::openPicker);
        bar.addView(add, new LinearLayout.LayoutParams(MP, dp(48)));
        addBar = bar;
        col.addView(bar, new LinearLayout.LayoutParams(MP, WC));

        root.addView(col, new FrameLayout.LayoutParams(MP, MP));

        scrim = new View(a);
        scrim.setBackgroundColor(0x99000000);
        scrim.setVisibility(View.GONE);
        root.addView(scrim, new FrameLayout.LayoutParams(MP, MP));

        dlg.setContentView(root);
        dlg.setOnDismissListener(di -> {
            showing = false;
            cur = null;
            content().setTranslationX(0);
            if (popup != null) popup.dismiss();
        });
        root.setTranslationX(width());
        fill();
        dlg.show();
        root.post(() -> {
            root.animate().translationX(0).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
            content().animate().translationX(-width() * 0.3f).setDuration(300)
                    .setInterpolator(new DecelerateInterpolator(1.6f)).start();
        });
    }

    private void parallax(float k) {
        content().setTranslationX(-width() * 0.3f * Math.max(0f, Math.min(1f, k)));
    }

    private void closeScreen() {
        if (closing) return;
        closing = true;
        if (popup != null) popup.dismiss();
        root.animate().translationX(width()).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.4f))
                .withEndAction(() -> dlg.dismiss()).start();
        content().animate().translationX(0).setDuration(260).start();
    }

    private View header() {
        FrameLayout h = new FrameLayout(a);
        TextView t = new TextView(a);
        t.setText("Плагины");
        t.setTextColor(0xFFF6F5F2);
        t.setTextSize(20);
        t.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        t.setGravity(Gravity.CENTER);
        h.addView(t, new FrameLayout.LayoutParams(MP, MP));
        final View back = hit(Ic.BACK);
        back.setOnClickListener(v -> closeScreen());
        h.addView(back, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START));
        final FrameLayout ref = hit(Ic.REFRESH);
        ref.setOnClickListener(v -> {
            ref.getChildAt(0).animate().rotationBy(360).setDuration(450).start();
            fill();
        });
        h.addView(ref, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END));
        return h;
    }

    private FrameLayout hit(int type) {
        FrameLayout f = new FrameLayout(a);
        f.addView(new Ic(a, type, 0xFFBDBCB8),
                new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        press(f);
        return f;
    }

    // ================= tabs =================
    private View buildTabs() {
        final FrameLayout box = new FrameLayout(a);
        GradientDrawable bd = new GradientDrawable();
        bd.setColor(Color.TRANSPARENT);
        bd.setCornerRadius(dp(20));
        bd.setStroke(dp(1), 0xFF3A3A38);
        box.setBackground(bd);

        indicator = new View(a);
        GradientDrawable ig = new GradientDrawable();
        ig.setColor(0xFF2B2B29);
        ig.setCornerRadius(dp(20));
        indicator.setBackground(ig);
        box.addView(indicator, new FrameLayout.LayoutParams(0, MP));

        LinearLayout row = new LinearLayout(a);
        t0 = tabText("Мои файлы", 0);
        t1 = tabText("Каталог", 1);
        row.addView(t0, new LinearLayout.LayoutParams(0, MP, 1f));
        row.addView(t1, new LinearLayout.LayoutParams(0, MP, 1f));
        box.addView(row, new FrameLayout.LayoutParams(MP, MP));
        styleTabs();

        box.addOnLayoutChangeListener((v, l, t, r, b, nl, nt, nr, nb) -> {
            int w = (r - l) / 2;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) indicator.getLayoutParams();
            if (lp.width != w) {
                lp.width = w;
                indicator.setLayoutParams(lp);
            }
            indicator.setTranslationX(tab * w);
        });
        return box;
    }

    private TextView tabText(String s, final int idx) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setOnClickListener(v -> switchTo(idx));
        return t;
    }

    private void styleTabs() {
        t0.setTextColor(tab == 0 ? TEXT : DIM);
        t0.setTypeface(tab == 0 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
        t1.setTextColor(tab == 1 ? TEXT : DIM);
        t1.setTypeface(tab == 1 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    private void switchTo(final int i) {
        if (i == tab) return;
        int dir = i > tab ? 1 : -1;
        final int outIdx = tab;
        final View in = i == 0 ? pageFiles : pageCat;
        final View out = i == 0 ? pageCat : pageFiles;
        tab = i;
        styleTabs();
        indicator.animate().translationX(i * indicator.getWidth()).setDuration(240)
                .setInterpolator(new DecelerateInterpolator()).start();

        in.setVisibility(View.VISIBLE);
        in.setAlpha(0f);
        in.setTranslationX(dir * dp(36));
        in.animate().setStartDelay(0).alpha(1f).translationX(0).setDuration(260)
                .setInterpolator(new DecelerateInterpolator()).start();
        out.animate().setStartDelay(0).alpha(0f).translationX(-dir * dp(36)).setDuration(200)
                .withEndAction(() -> {
                    if (tab != outIdx) out.setVisibility(View.GONE);
                }).start();

        if (i == 0) {
            addBar.setVisibility(View.VISIBLE);
            addBar.animate().alpha(1f).translationY(0).setDuration(220).start();
        } else {
            addBar.animate().alpha(0f).translationY(dp(24)).setDuration(180)
                    .withEndAction(() -> {
                        if (tab == 1) addBar.setVisibility(View.GONE);
                    }).start();
        }
    }

    // ================= widgets =================
    private void press(View v) {
        v.setOnTouchListener((x, e) -> {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    tx = e.getRawX();
                    ty = e.getRawY();
                    x.animate().setStartDelay(0).scaleX(0.96f).scaleY(0.96f).setDuration(110).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    x.animate().setStartDelay(0).scaleX(1f).scaleY(1f).setDuration(180).start();
                    break;
                default:
                    break;
            }
            return false;
        });
    }

    private View pill(String s, boolean fill, final Runnable r) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(24));
        if (fill) {
            g.setColor(0xFFF7F7F5);
            t.setTextColor(0xFF141414);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(1), 0xFF4A4A47);
            t.setTextColor(TEXT);
        }
        t.setBackground(g);
        t.setOnClickListener(v -> r.run());
        press(t);
        return t;
    }

    private TextView tv(String s, float sp, int color, boolean bold) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private View iconBox(int size, int iconSize) {
        FrameLayout b = new FrameLayout(a);
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xFF2B2B29);
        g.setCornerRadius(dp(12));
        b.setBackground(g);
        b.addView(new Ic(a, Ic.FILE, ACCENT),
                new FrameLayout.LayoutParams(dp(iconSize), dp(iconSize), Gravity.CENTER));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return b;
    }

    // ================= list =================
    private void fill() {
        list.removeAllViews();
        List<Plugin> ps = load();
        if (ps.isEmpty()) {
            LinearLayout e = new LinearLayout(a);
            e.setOrientation(LinearLayout.VERTICAL);
            e.setGravity(Gravity.CENTER_HORIZONTAL);
            e.setPadding(dp(24), dp(100), dp(24), 0);
            e.addView(iconBox(56, 28));
            TextView t = tv("Плагинов пока нет. Нажми «Добавить файл» и выбери плагин.", 15, SUB, false);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WC, WC);
            lp.topMargin = dp(16);
            e.addView(t, lp);
            list.addView(e, new LinearLayout.LayoutParams(MP, WC));
            e.setAlpha(0f);
            e.animate().alpha(1f).setDuration(300).start();
            return;
        }
        int i = 0;
        for (Plugin p : ps) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC);
            lp.bottomMargin = dp(12);
            View c = card(p);
            list.addView(c, lp);
            c.setAlpha(0f);
            c.setTranslationY(dp(14));
            c.animate().setStartDelay(i * 45L).alpha(1f).translationY(0).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator()).start();
            i++;
        }
    }

    private View card(final Plugin p) {
        final LinearLayout c = new LinearLayout(a);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(CARD);
        g.setCornerRadius(dp(20));
        g.setStroke(dp(1), STROKE);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(20));
        c.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), g, mask));

        c.addView(iconBox(40, 22));

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(tv(p.name, 16, TEXT, true));
        col.addView(tv("Версия: " + p.version, 12, DIM, false));
        if (p.desc.length() > 0) {
            TextView ds = tv(p.desc, 14, SUB, false);
            ds.setPadding(0, dp(4), 0, 0);
            col.addView(ds);
        }
        if (p.author.length() > 0) {
            TextView au = tv("Автор: " + p.author, 12, DIM, false);
            au.setPadding(0, dp(4), 0, 0);
            col.addView(au);
        }
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, WC, 1f);
        cl.leftMargin = dp(14);
        cl.rightMargin = dp(12);
        c.addView(col, cl);

        final String key = "en:" + p.dir;
        final Sw sw = new Sw(a, prefs.getBoolean(key, false));
        sw.change = () -> prefs.edit().putBoolean(key, sw.on).apply();
        c.addView(sw, new LinearLayout.LayoutParams(WC, WC));

        press(c);
        c.setOnLongClickListener(v -> {
            showMenu(p, c);
            return true;
        });
        return c;
    }

    // ================= long-press menu =================
    private void showMenu(final Plugin p, final View cardView) {
        final LinearLayout m = new LinearLayout(a);
        m.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(MENU);
        g.setCornerRadius(dp(28));
        m.setBackground(g);
        m.setPadding(0, dp(8), 0, dp(8));

        m.addView(item("Открыть с помощью…", TEXT, () -> toast("Ещё не сделано")));
        m.addView(item("Поделиться", TEXT, () -> toast("Ещё не сделано")));
        View div = new View(a);
        div.setBackgroundColor(0xFF3A3A38);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(MP, dp(1));
        dl.leftMargin = dp(16);
        dl.rightMargin = dp(16);
        dl.topMargin = dp(4);
        dl.bottomMargin = dp(4);
        m.addView(div, dl);
        m.addView(item("Удалить", RED, () -> confirmDelete(p, cardView)));

        int pw = dp(270);
        m.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int ph = m.getMeasuredHeight();
        DisplayMetrics dm = a.getResources().getDisplayMetrics();
        int x = Math.max(dp(16), Math.min((int) tx - pw / 2, dm.widthPixels - pw - dp(16)));
        int y = Math.max(dp(40), Math.min((int) ty, dm.heightPixels - ph - dp(24)));

        popup = new PopupWindow(m, pw, WC, true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setElevation(dp(8));
        popup.setOnDismissListener(() -> scrim.animate().alpha(0f).setDuration(160)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start());

        scrim.setAlpha(0f);
        scrim.setVisibility(View.VISIBLE);
        scrim.animate().alpha(1f).setDuration(180).start();

        m.setAlpha(0f);
        m.setScaleX(0.8f);
        m.setScaleY(0.8f);
        m.setPivotX(Math.max(0, Math.min(pw, tx - x)));
        m.setPivotY(Math.max(0, ty - y));
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
        m.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(170)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private View item(String s, int color, final Runnable r) {
        TextView t = tv(s, 17, color, false);
        t.setPadding(dp(24), dp(14), dp(24), dp(14));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                new ColorDrawable(Color.WHITE)));
        t.setOnClickListener(v -> {
            final View m = (View) v.getParent();
            m.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(110)
                    .withEndAction(() -> {
                        if (popup != null) popup.dismiss();
                        r.run();
                    }).start();
        });
        return t;
    }

    private void confirmDelete(final Plugin p, final View cardView) {
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Удалить плагин?")
                .setMessage(p.name)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (di, i) -> collapse(cardView, () -> {
                    deleteDir(p.file);
                    list.removeView(cardView);
                    if (list.getChildCount() == 0) fill();
                })).show();
    }

    private void collapse(final View v, final Runnable end) {
        final int h = v.getHeight();
        final ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        final int mb = lp.bottomMargin;
        v.animate().setStartDelay(0).alpha(0f).setDuration(160).start();
        ValueAnimator va = ValueAnimator.ofFloat(1f, 0f);
        va.setDuration(280);
        va.setInterpolator(new DecelerateInterpolator());
        va.addUpdateListener(an -> {
            float f = (Float) an.getAnimatedValue();
            lp.height = (int) (h * f);
            lp.bottomMargin = (int) (mb * f);
            v.setLayoutParams(lp);
        });
        va.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                end.run();
            }
        });
        va.start();
    }

    private static void deleteDir(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteDir(x);
        f.delete();
    }
}
