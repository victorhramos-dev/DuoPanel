package com.abdullaherturk.duopanel;

import android.content.Context;
import android.content.Intent;
import android.graphics.Point;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;

public class SplitController {
    public interface Listener { void onSplitChanged(boolean active); }

    private static final List<Listener> ls = new ArrayList<Listener>();
    private static boolean active;
    private static Point divider;

    public static boolean isActive() { return active; }
    public static Point divider() { return divider; }
    public static void setDivider(Point p) { divider = p; }

    public static void add(Listener l) { if (!ls.contains(l)) ls.add(l); }
    public static void remove(Listener l) { ls.remove(l); }

    public static void setActive(boolean a) {
        if (a == active) return;
        active = a;
        for (Listener l : new ArrayList<Listener>(ls)) l.onSplitChanged(a);
    }

    /** Sol ve sag uygulamanin yerini degistirir ve ikiliyi yeniden kurar */
    private static long lastSwap;

    public static void swapSides(Context c) {
        String l = Prefs.side(c, Prefs.LEFT), r = Prefs.side(c, Prefs.RIGHT);
        if (l == null || r == null) return;
        // Art arda hizli yer degistirme, sistemin bolunmus ekran gecisini kilitleyebiliyor (Tbox'ta cihaz donup
        // yeniden baslatma gerekti): onceki yer degistirme oturana kadar yenisi yapilmaz
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastSwap < 4000) { SplitLog.add("swap ignored: previous swap still settling"); return; }
        SplitService s = SplitService.get();
        boolean nativeSplit = s != null && s.isSplitActive();
        if (nativeSplit && !s.canNativeSwap()) { SplitLog.add("swap ignored: native transition still busy"); return; }
        lastSwap = now;
        int old = Prefs.ratio(c, l, r);
        Prefs.setSide(c, Prefs.LEFT, r);
        Prefs.setSide(c, Prefs.RIGHT, l);
        Prefs.setRatio(c, r, l, 100 - old);
        SplitLog.add("swap sides");
        OverlayService.refresh(c);
        if (nativeSplit) s.nativeSwap();
        else launchPair(c);
    }

    /** Yuzen simge: once arka planda duran cifti geri cagir, olmazsa bastan kur */
    public static void resumePair(Context c) {
        SplitService s = SplitService.get();
        if (s == null || !Prefs.hasPair(c)) { launchPair(c); return; }
        s.restorePair(Prefs.side(c, Prefs.LEFT), Prefs.side(c, Prefs.RIGHT));
    }

    public static void launchPair(Context c) {
        if (!Prefs.hasPair(c)) {
            Toast.makeText(c, R.string.pick_both, Toast.LENGTH_LONG).show();
            return;
        }
        SplitService s = SplitService.get();
        if (s == null && PermissionHelper.tryEnableAccessibility(c)) {
            // hizmet baglaninca ikiliyi ac
            Prefs.setLong(c, "pendingLaunch", android.os.SystemClock.elapsedRealtime());
            return;
        }
        if (s == null) {
            Toast.makeText(c, R.string.toast_noa11y, Toast.LENGTH_LONG).show();
            Intent i = new Intent(c, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
            return;
        }
        Toast.makeText(c, R.string.toast_started, Toast.LENGTH_SHORT).show();
        s.runPair(Prefs.side(c, Prefs.LEFT), Prefs.side(c, Prefs.RIGHT));
    }
}
