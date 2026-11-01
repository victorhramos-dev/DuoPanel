package com.abdullaherturk.duopanel;

import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Kayitli ikililer icin ana ekran ve uzun basma kisayollari (API 25+ yansima ile) */
public class Shortcuts {
    public static final String ACTION = "com.abdullaherturk.duopanel.streamboxfix.OPEN_PAIR";

    public static String id(String l, String r) { return "pair_" + Integer.toHexString((l + "|" + r).hashCode()); }

    public static String label(Context c, String l, String r) { return AppList.label(c, l) + " + " + AppList.label(c, r); }

    static Intent intent(Context c, String l, String r) {
        Intent i = new Intent(ACTION);
        i.setClassName(c.getPackageName(), ShortcutActivity.class.getName());
        i.putExtra("l", l);
        i.putExtra("r", r);
        return i;
    }

    public static Bitmap bitmap(Context c, String l, String r) {
        int s = 192;
        Bitmap bm = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF111A2E);
        cv.drawRoundRect(new RectF(0, 0, s, s), 44, 44, p);
        p.setColor(0x3322D3EE);
        cv.drawRect(95, 34, 97, 158, p);
        draw(cv, AppList.icon(c, l), 12, 52, 88);
        draw(cv, AppList.icon(c, r), 104, 52, 88);
        return bm;
    }

    private static void draw(Canvas cv, Drawable d, int x, int y, int size) {
        if (d == null) return;
        d.setBounds(x, y, x + size, y + size);
        d.draw(cv);
    }

    private static Object manager(Context c) throws Exception {
        return c.getSystemService(Class.forName("android.content.pm.ShortcutManager"));
    }

    private static Object build(Context c, String l, String r) throws Exception {
        Class<?> bc = Class.forName("android.content.pm.ShortcutInfo$Builder");
        Object b = bc.getConstructor(Context.class, String.class).newInstance(c, id(l, r));
        String lab = label(c, l, r);
        bc.getMethod("setShortLabel", CharSequence.class).invoke(b, lab);
        bc.getMethod("setLongLabel", CharSequence.class).invoke(b, lab);
        bc.getMethod("setIcon", Icon.class).invoke(b, Icon.createWithBitmap(bitmap(c, l, r)));
        bc.getMethod("setIntent", Intent.class).invoke(b, intent(c, l, r));
        return bc.getMethod("build").invoke(b);
    }

    /** Ana ekrana kisayol ekleme istegi. false = baslatici desteklemiyor */
    public static boolean pin(Context c, String l, String r) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                Object sm = manager(c);
                enable(sm, id(l, r));
                boolean sup = (Boolean) sm.getClass().getMethod("isRequestPinShortcutSupported").invoke(sm);
                if (!sup) return false;
                Object info = build(c, l, r);
                sm.getClass().getMethod("requestPinShortcut", info.getClass(), IntentSender.class).invoke(sm, info, null);
                return true;
            }
            Intent add = new Intent("com.android.launcher.action.INSTALL_SHORTCUT");
            add.putExtra(Intent.EXTRA_SHORTCUT_INTENT, intent(c, l, r));
            add.putExtra(Intent.EXTRA_SHORTCUT_NAME, label(c, l, r));
            add.putExtra(Intent.EXTRA_SHORTCUT_ICON, bitmap(c, l, r));
            c.sendBroadcast(add);
            return true;
        } catch (Throwable e) {
            SplitLog.add("pin failed: " + e);
            return false;
        }
    }

    /** Uygulama simgesine uzun basinca gorunen kisayollar (ilk 4 kayitli ikili) */
    public static void refreshDynamic(Context c) {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            Object sm = manager(c);
            List<Object> list = new ArrayList<Object>();
            for (String[] p : Prefs.pairs(c)) {
                if (list.size() >= 4) break;
                if (!AppList.installed(c, p[0]) || !AppList.installed(c, p[1])) continue;
                enable(sm, id(p[0], p[1]));
                list.add(build(c, p[0], p[1]));
            }
            sm.getClass().getMethod("setDynamicShortcuts", List.class).invoke(sm, list);
        } catch (Throwable e) { SplitLog.add("dynamic shortcuts failed: " + e); }
    }

    /** Silinen ikilinin ana ekrandaki kisayolunu devre disi birakir */
    public static void disable(Context c, String l, String r) {
        if (Build.VERSION.SDK_INT < 25) return;
        try {
            Object sm = manager(c);
            sm.getClass().getMethod("disableShortcuts", List.class).invoke(sm, Collections.singletonList(id(l, r)));
        } catch (Throwable e) { SplitLog.add("disable shortcut failed: " + e); }
    }

    private static void enable(Object sm, String id) {
        try { sm.getClass().getMethod("enableShortcuts", List.class).invoke(sm, Collections.singletonList(id)); }
        catch (Throwable e) { }
    }
}
