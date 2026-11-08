package com.torip.claudeplugins;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

final class PluginsUi {
    static final int BG = 0xFF141414, CARD = 0xFF1F1F1E, STROKE = 0xFF2E2E2C,
            TEXT = 0xFFF2F1EE, SUB = 0xFFB4B3AE, DIM = 0xFF8E8D89, ACCENT = 0xFFD97757,
            BLUE = 0xFF3B82F6, RED = 0xFFFF6B6B, MENU = 0xFF222222;
    private static final int MP = -1, WC = -2;
    private static boolean showing = false;

    private final Activity a;
    private final float d;
    private final SharedPreferences prefs;
    private Dialog dlg;
    private FrameLayout root;
    private LinearLayout list;
    private View scrim;
    private PopupWindow popup;
    private float tx, ty;

    private PluginsUi(Activity a) {
        this.a = a;
        this.d = a.getResources().getDisplayMetrics().density;
        this.prefs = a.getSharedPreferences("claudeplugins_ui", 0);
    }

    static void show(Activity a) {
        if (showing) return;
        showing = true;
        try {
            new PluginsUi(a).open();
        } catch (Throwable t) {
            showing = false;
            XposedBridge.log("ClaudePlugins: ui failed: " + t);
        }
    }

    private int dp(float v) { return (int) (v * d + 0.5f); }

    private void toast(String s) { Toast.makeText(a, s, Toast.LENGTH_SHORT).show(); }

    // ---------- icons ----------
    static class Ic extends View {
        static final int BACK = 0, REFRESH = 1, FILE = 2;
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

    // ---------- switch ----------
    static class Sw extends View {
        boolean on;
        Runnable change;
        final float d;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        Sw(Context c, boolean state) {
            super(c);
            this.on = state;
            this.d = c.getResources().getDisplayMetrics().density;
            setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    on = !on;
                    invalidate();
                    if (change != null) change.run();
                }
            });
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension((int) (45 * d), (int) (25 * d));
        }

        @Override
        protected void onDraw(Canvas c) {
            p.setColor(on ? BLUE : 0xFF2F2F2E);
            c.drawRoundRect(0, 0, 45 * d, 25 * d, 12.5f * d, 12.5f * d, p);
            p.setColor(TEXT);
            c.drawCircle(on ? 35.5f * d : 12.5f * d, 12.5f * d, 9.5f * d, p);
        }
    }

    // ---------- data ----------
    private static class Plugin {
        File file;
        String dir, name, version, desc, author;
    }

    private List<Plugin> load() {
        List<Plugin> out = new ArrayList<Plugin>();
        File[] fs = new File(a.getFilesDir(), "plugins").listFiles();
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

    // ---------- screen ----------
    private void open() {
        dlg = new Dialog(a, android.R.style.Theme_DeviceDefault_NoActionBar);
        Window w = dlg.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(BG));
        w.setLayout(MP, MP);
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);

        root = new FrameLayout(a);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets ins) {
                v.setPadding(0, ins.getSystemWindowInsetTop(), 0, ins.getSystemWindowInsetBottom());
                return ins;
            }
        });

        LinearLayout col = new LinearLayout(a);
        col.setOrientation(LinearLayout.VERTICAL);
        col.addView(header(), new LinearLayout.LayoutParams(MP, dp(56)));

        LinearLayout top = new LinearLayout(a);
        top.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams l1 = new LinearLayout.LayoutParams(0, dp(40), 1f);
        l1.rightMargin = dp(5);
        LinearLayout.LayoutParams l2 = new LinearLayout.LayoutParams(0, dp(40), 1f);
        l2.leftMargin = dp(5);
        top.addView(pill("Мои файлы", true, new Runnable() {
            public void run() { toast("Выбор файла ещё не сделан"); }
        }), l1);
        top.addView(pill("Каталог", false, new Runnable() {
            public void run() { toast("Каталог ещё не сделан"); }
        }), l2);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(MP, WC);
        tl.leftMargin = dp(16);
        tl.rightMargin = dp(16);
        tl.topMargin = dp(8);
        col.addView(top, tl);

        ScrollView sv = new ScrollView(a);
        list = new LinearLayout(a);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(16), dp(16), dp(24));
        sv.addView(list, new FrameLayout.LayoutParams(MP, WC));
        col.addView(sv, new LinearLayout.LayoutParams(MP, 0, 1f));
        root.addView(col, new FrameLayout.LayoutParams(MP, MP));

        scrim = new View(a);
        scrim.setBackgroundColor(0x99000000);
        scrim.setVisibility(View.GONE);
        root.addView(scrim, new FrameLayout.LayoutParams(MP, MP));

        dlg.setContentView(root);
        dlg.setOnDismissListener(new android.content.DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(android.content.DialogInterface di) {
                showing = false;
                if (popup != null) popup.dismiss();
            }
        });
        fill();
        dlg.show();
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
        h.addView(hit(Ic.BACK, new Runnable() {
            public void run() { dlg.dismiss(); }
        }), new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.START));
        h.addView(hit(Ic.REFRESH, new Runnable() {
            public void run() { fill(); toast("Список обновлён"); }
        }), new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END));
        return h;
    }

    private View hit(int type, final Runnable r) {
        FrameLayout f = new FrameLayout(a);
        f.addView(new Ic(a, type, 0xFFBDBCB8),
                new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
        f.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
        return f;
    }

    private View pill(String s, boolean fill, final Runnable r) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(20));
        if (fill) {
            g.setColor(0xFFF7F7F5);
            t.setTextColor(0xFF141414);
        } else {
            g.setColor(Color.TRANSPARENT);
            g.setStroke(dp(1), 0xFF4A4A47);
            t.setTextColor(TEXT);
        }
        t.setBackground(g);
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { r.run(); }
        });
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

    private void fill() {
        list.removeAllViews();
        List<Plugin> ps = load();
        if (ps.isEmpty()) {
            LinearLayout e = new LinearLayout(a);
            e.setOrientation(LinearLayout.VERTICAL);
            e.setGravity(Gravity.CENTER_HORIZONTAL);
            e.setPadding(dp(24), dp(100), dp(24), 0);
            e.addView(iconBox(56, 28));
            TextView t = tv("Плагинов пока нет. Открой «Мои файлы» и выбери плагин.", 15, SUB, false);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WC, WC);
            lp.topMargin = dp(16);
            e.addView(t, lp);
            list.addView(e, new LinearLayout.LayoutParams(MP, WC));
            return;
        }
        for (Plugin p : ps) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MP, WC);
            lp.bottomMargin = dp(12);
            list.addView(card(p), lp);
        }
    }

    private View card(final Plugin p) {
        LinearLayout c = new LinearLayout(a);
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
        sw.change = new Runnable() {
            public void run() { prefs.edit().putBoolean(key, sw.on).apply(); }
        };
        c.addView(sw, new LinearLayout.LayoutParams(WC, WC));

        c.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    tx = e.getRawX();
                    ty = e.getRawY();
                }
                return false;
            }
        });
        c.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showMenu(p);
                return true;
            }
        });
        return c;
    }

    // ---------- long-press menu ----------
    private void showMenu(final Plugin p) {
        LinearLayout m = new LinearLayout(a);
        m.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable g = new GradientDrawable();
        g.setColor(MENU);
        g.setCornerRadius(dp(28));
        m.setBackground(g);
        m.setPadding(0, dp(8), 0, dp(8));

        m.addView(item("Открыть с помощью…", TEXT, new Runnable() {
            public void run() { toast("Ещё не сделано"); }
        }));
        m.addView(item("Поделиться", TEXT, new Runnable() {
            public void run() { toast("Ещё не сделано"); }
        }));
        View div = new View(a);
        div.setBackgroundColor(0xFF3A3A38);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(MP, dp(1));
        dl.leftMargin = dp(16);
        dl.rightMargin = dp(16);
        dl.topMargin = dp(4);
        dl.bottomMargin = dp(4);
        m.addView(div, dl);
        m.addView(item("Удалить", RED, new Runnable() {
            public void run() { confirmDelete(p); }
        }));

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
        popup.setOnDismissListener(new PopupWindow.OnDismissListener() {
            public void onDismiss() { scrim.setVisibility(View.GONE); }
        });
        scrim.setVisibility(View.VISIBLE);
        popup.showAtLocation(root, Gravity.TOP | Gravity.START, x, y);
    }

    private View item(String s, int color, final Runnable r) {
        TextView t = tv(s, 17, color, false);
        t.setPadding(dp(24), dp(14), dp(24), dp(14));
        t.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), null,
                new ColorDrawable(Color.WHITE)));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (popup != null) popup.dismiss();
                r.run();
            }
        });
        return t;
    }

    private void confirmDelete(final Plugin p) {
        new AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Удалить плагин?")
                .setMessage(p.name)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", new android.content.DialogInterface.OnClickListener() {
                    public void onClick(android.content.DialogInterface di, int i) {
                        deleteDir(p.file);
                        fill();
                    }
                }).show();
    }

    private static void deleteDir(File f) {
        File[] c = f.listFiles();
        if (c != null) for (File x : c) deleteDir(x);
        f.delete();
    }
}
