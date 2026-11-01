package com.abdullaherturk.duopanel;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.List;

public class Prefs {
    public static final int LEFT = 0, RIGHT = 1;

    static SharedPreferences sp(Context c) {
        // MODE_MULTI_PROCESS: muzik paneli ayri surecte; her okumada dosya degistiyse yeniden yuklenir
        return c.getApplicationContext().getSharedPreferences("duo", 4);
    }

    public static String side(Context c, int i) { return sp(c).getString("side" + i, null); }

    public static void setSide(Context c, int i, String pkg) {
        List<String> h = history(c, i);
        h.remove(pkg);
        h.add(0, pkg);
        while (h.size() > 10) h.remove(h.size() - 1);
        sp(c).edit().putString("side" + i, pkg).putString("hist" + i, join(h)).apply();
    }

    public static List<String> history(Context c, int i) { return split(sp(c).getString("hist" + i, "")); }

    public static void removeHistory(Context c, int i, String pkg) {
        List<String> h = history(c, i);
        h.remove(pkg);
        sp(c).edit().putString("hist" + i, join(h)).apply();
    }

    public static boolean hasPair(Context c) {
        String a = side(c, LEFT), b = side(c, RIGHT);
        return a != null && b != null && AppList.installed(c, a) && AppList.installed(c, b);
    }

    // ---- kayitli ikililer: her satir "sol|sag"
    public static List<String[]> pairs(Context c) {
        List<String[]> out = new ArrayList<String[]>();
        for (String line : split(sp(c).getString("pairs", ""))) {
            String[] p = line.split("\\|");
            if (p.length == 2) out.add(p);
        }
        return out;
    }

    public static boolean addPair(Context c, String l, String r) {
        List<String> lines = split(sp(c).getString("pairs", ""));
        String key = l + "|" + r;
        if (lines.contains(key)) return false;
        lines.add(0, key);
        sp(c).edit().putString("pairs", join(lines)).apply();
        return true;
    }

    public static void removePair(Context c, String l, String r) {
        List<String> lines = split(sp(c).getString("pairs", ""));
        lines.remove(l + "|" + r);
        sp(c).edit().putString("pairs", join(lines)).apply();
    }

    // ---- ikiliye ozel sol taraf genisligi (%30 / %50 / %70), varsayilan %50
    public static int ratio(Context c, String l, String r) { return sp(c).getInt("ratio_" + l + "|" + r, 50); }
    public static void setRatio(Context c, String l, String r, int v) { sp(c).edit().putInt("ratio_" + l + "|" + r, v).apply(); }
    public static int nextRatio(int v) { return v == 30 ? 50 : v == 50 ? 70 : 30; }

    // ---- ayarlar
    public static boolean get(Context c, String k, boolean d) { return sp(c).getBoolean(k, d); }
    public static long getLong(Context c, String k) { return sp(c).getLong(k, 0L); }
    public static void setLong(Context c, String k, long v) { sp(c).edit().putLong(k, v).apply(); }
    public static void set(Context c, String k, boolean v) { sp(c).edit().putBoolean(k, v).apply(); }

    public static boolean autostart(Context c) { return get(c, "autostart", true); }
    public static boolean autoLaunch(Context c) { return get(c, "autolaunch", false); }
    public static boolean strip(Context c) { return get(c, "strip", true); }
    public static boolean bubble(Context c) { return get(c, "bubble", true); }
    public static boolean mapsDrive(Context c) { return get(c, "mapsDrive", false); }
    public static boolean dividerSwap(Context c) { return get(c, "dividerSwap", true); }
    public static boolean mapsReturn(Context c) { return get(c, "mapsReturn", true); }

    /** Ogrenilmis gorunum kimlikleri (dilden bagimsiz tanima) */
    public static java.util.Set<String> idSet(Context c, String key) {
        return new java.util.HashSet<String>(split(sp(c).getString(key, "")));
    }
    public static boolean addId(Context c, String key, String id) {
        List<String> l = split(sp(c).getString(key, ""));
        if (l.contains(id) || l.size() >= 16) return false;
        l.add(id);
        sp(c).edit().putString(key, join(l)).apply();
        return true;
    }

    /** Haritalar alt sekmelerinin ogrenilmis gorunum kimlikleri (dilden bagimsiz) */
    public static java.util.Set<String> mapsTabIds(Context c) {
        return new java.util.HashSet<String>(split(sp(c).getString("mapsTabIds", "")));
    }
    public static boolean addMapsTabId(Context c, String id) {
        List<String> l = split(sp(c).getString("mapsTabIds", ""));
        if (l.contains(id) || l.size() >= 12) return false;
        l.add(id);
        sp(c).edit().putString("mapsTabIds", join(l)).apply();
        return true;
    }

    // ---- muzik paneli
    /** Muzik paneli ikilide uygulama yerine gecer: kendi paket adiyla saklanir */
    /**
     * Muzik paneli ayri surecte calisir: onun ayarlari ayri dosyada tutulur. Iki surec ayni dosyaya yazinca
     * birinin eski kopyasi digerinin yeni degerlerini (or. kayitli oran) ezebiliyordu.
     */
    private static boolean migrated;
    static SharedPreferences msp(Context c) {
        SharedPreferences m = c.getApplicationContext().getSharedPreferences("duo_music", 4);
        if (!migrated) {
            migrated = true;
            if (!m.getBoolean("migrated", false)) {
                SharedPreferences.Editor e = m.edit();
                for (java.util.Map.Entry<String, ?> en : sp(c).getAll().entrySet()) {
                    if (!en.getKey().startsWith("music")) continue;
                    Object v = en.getValue();
                    if (v instanceof String) e.putString(en.getKey(), (String) v);
                    else if (v instanceof Boolean) e.putBoolean(en.getKey(), (Boolean) v);
                    else if (v instanceof Long) e.putLong(en.getKey(), (Long) v);
                    else if (v instanceof Integer) e.putInt(en.getKey(), (Integer) v);
                }
                e.putBoolean("migrated", true).commit();
            }
        }
        return m;
    }
    public static boolean mget(Context c, String k, boolean d) { return msp(c).getBoolean(k, d); }
    public static void mset(Context c, String k, boolean v) { msp(c).edit().putBoolean(k, v).apply(); }
    public static long mgetLong(Context c, String k) { return msp(c).getLong(k, 0L); }
    public static void msetLong(Context c, String k, long v) { msp(c).edit().putLong(k, v).apply(); }

    public static String musicPkg(Context c) { return msp(c).getString("musicPkg", null); }
    public static void setMusicPkg(Context c, String p) {
        if (p == null) msp(c).edit().remove("musicPkg").apply(); else msp(c).edit().putString("musicPkg", p).apply();
    }
    public static String musicLast(Context c) { return msp(c).getString("musicLast", null); }
    public static void setMusicLast(Context c, String p) {
        if (p != null && !p.equals(musicLast(c))) msp(c).edit().putString("musicLast", p).apply();
    }
    public static String musicSection(Context c, String pkg) { return msp(c).getString("musicSec_" + pkg, MusicHub.QUEUE); }
    public static void setMusicSection(Context c, String pkg, String id) { msp(c).edit().putString("musicSec_" + pkg, id).apply(); }

    /** 0 = otomatik (sistem), 1 = acik, 2 = koyu */
    public static int themeMode(Context c) { return sp(c).getInt("themeMode", 0); }
    public static void setThemeMode(Context c, int m) { sp(c).edit().putInt("themeMode", m).apply(); }

    public static int bubbleX(Context c) { return sp(c).getInt("bx", 24); }
    public static int bubbleY(Context c) { return sp(c).getInt("by", 300); }
    public static void setBubble(Context c, int x, int y) { sp(c).edit().putInt("bx", x).putInt("by", y).apply(); }

    // ---- elle secilen hava durumu konumu (yoksa GPS)
    public static String wxName(Context c) { return sp(c).getString("wxName", null); }
    public static double[] wxLatLon(Context c) {
        if (wxName(c) == null) return null;
        return new double[]{ sp(c).getFloat("wxLat", 0f), sp(c).getFloat("wxLon", 0f) };
    }
    public static void setWxLocation(Context c, String name, double lat, double lon) {
        sp(c).edit().putString("wxName", name).putFloat("wxLat", (float) lat).putFloat("wxLon", (float) lon).apply();
    }
    public static void clearWxLocation(Context c) { sp(c).edit().remove("wxName").remove("wxLat").remove("wxLon").apply(); }

    /** -1 = ortala */
    /** Edge is the default, including upgrades with saved floating coordinates. */
    public static boolean stripEdge(Context c) { return get(c, "stripEdge", true); }
    public static void setStripEdge(Context c, boolean edge) { set(c, "stripEdge", edge); }
    public static int stripX(Context c) { return sp(c).getInt("sx", -1); }
    public static int stripY(Context c) { return sp(c).getInt("sy", -1); }
    public static void setStrip(Context c, int x, int y) { sp(c).edit().putInt("sx", x).putInt("sy", y).apply(); }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<String>();
        if (s == null || s.length() == 0) return out;
        for (String p : s.split("\n")) if (p.length() > 0) out.add(p);
        return out;
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) { if (sb.length() > 0) sb.append('\n'); sb.append(s); }
        return sb.toString();
    }
}
