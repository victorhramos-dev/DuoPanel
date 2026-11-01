package com.abdullaherturk.duopanel;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class SplitService extends AccessibilityService {
    private static final int ACTION_RECENTS = 3;
    private static final int ACTION_TOGGLE_SPLIT_SCREEN = 7;
    private static final int FLAG_LAUNCH_ADJACENT = 0x00001000;
    private static final Pattern SPLIT_ITEM = Pattern.compile(
            "(split|bölünmüş|bolunmus|böl|yan yana|multi.?window|çoklu pencere|ekranı böl)", Pattern.CASE_INSENSITIVE);

    private static SplitService inst;
    private BootStartup bootStartup;

    private Handler h;
    private String fg;
    private long busyUntil;
    private int attempt;

    public static SplitService get() { return inst; }

    @Override
    protected void onServiceConnected() {
        inst = this;
        if (bootStartup != null) bootStartup.close();
        if (h == null) h = new Handler(getMainLooper());
        SplitLog.install(this);
        SplitLog.add("accessibility connected");
        if (Prefs.autostart(this)) OverlayService.start(this);
        bootStartup = new BootStartup(this, h, new BootStartup.Host() {
            @Override public boolean ready() {
                if (inst != SplitService.this || SystemClock.elapsedRealtime() < busyUntil) return false;
                try {
                    List<AccessibilityWindowInfo> windows = getWindows();
                    if (windows != null) for (AccessibilityWindowInfo window : windows) {
                        if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                        AccessibilityNodeInfo root = window.getRoot();
                        if (root != null && root.getPackageName() != null
                                && !"com.android.systemui".contentEquals(root.getPackageName())) return true;
                    }
                } catch (Exception e) { SplitLog.add("startup windows unavailable: " + e); }
                return false;
            }
            @Override public boolean visible() { return currentPair() != null; }
            @Override public void recognized() { updateActive(); }
            @Override public void dispatch(String left, String right) { runPair(left, right); }
        });
        bootStartup.start();
        updateActive();
        if (SplitController.isActive()) {
            // surec yeniden baslatildiysa ikili zaten ekranda: once kayitli orani uygula (ayracin o anki yeri kaydedilmesin)
            scheduleRatio(Prefs.side(this, Prefs.LEFT), Prefs.side(this, Prefs.RIGHT), 1500);
        }
        h.removeCallbacks(mapsWatch);
        h.postDelayed(mapsWatch, 3000);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null) return;
        if (bootStartup != null) bootStartup.wake();
        if (e.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence p = e.getPackageName();
            CharSequence cls = e.getClassName();
            if (p != null) {
                String s = p.toString();
                boolean own = s.equals(getPackageName());
                boolean ownActivity = own && cls != null && cls.toString().endsWith("Activity");
                if ((!own || ownActivity) && !s.equals("com.android.systemui")
                        && !s.toLowerCase().contains("inputmethod")) fg = s;
            }
        }
        updateActive();
    }

    @Override
    public void onInterrupt() { }

    @Override
    public boolean onUnbind(Intent i) {
        if (bootStartup != null) { bootStartup.close(); bootStartup = null; }
        ratioToken++;
        ratioArmed = false;
        blockedAt = -1;
        if (h != null) h.removeCallbacksAndMessages(null);
        if (inst == this) inst = null;
        SplitLog.add("accessibility disconnected");
        return super.onUnbind(i);
    }

    /** Onde ikiliden biri var mi (sol veya sag uygulama) */
    private boolean pairInFront() {
        if (fg == null) return false;
        return fg.equals(Prefs.side(this, Prefs.LEFT)) || fg.equals(Prefs.side(this, Prefs.RIGHT));
    }

    /** Bolunmus ekran bolmesi ekranin tum yuksekligini (yan yana) ya da tum genisligini (ust alta) kaplar; iletisim kutulari kaplamaz */
    private static boolean pane(Rect r, DisplayMetrics dm) {
        return r.height() >= dm.heightPixels * 0.8f || r.width() >= dm.widthPixels * 0.8f;
    }

    /** Iki buyuk uygulama penceresi varsa bolunmus sayilir; ayrac konumunu da hesaplar */
    public boolean isSplitActive() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) { SplitController.setDivider(null); return false; }
            SplitGeometry geometry = splitGeometry(ws);
            if (geometry != null) {
                SplitController.setDivider(new Point(geometry.startX(), geometry.startY()));
                return true;
            }
            DisplayMetrics dm = getResources().getDisplayMetrics();
            long full = (long) dm.widthPixels * dm.heightPixels;
            Rect a = null, b = null;
            int n = 0;
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                long ar = (long) r.width() * r.height();
                // kucuk sistem pencereleri (kenar paneli, durum cipleri) ve tam ekran pencereler sayilmaz
                if (ar < full * 0.06 || ar >= full * 0.8 || !pane(r, dm)) continue;
                n++;
                if (a == null) a = r; else if (b == null) b = r;
            }
            if (n >= 2 && a != null && b != null) {
                Rect first = a.left <= b.left ? a : b, second = first == a ? b : a;
                Point p;
                if (second.left >= first.right - Math.round(40 * dm.density)) {
                    p = new Point((first.right + second.left) / 2, first.top + Math.round(first.height() * 0.80f));
                } else {
                    Rect top = a.top <= b.top ? a : b, bot = top == a ? b : a;
                    p = new Point(top.left + Math.round(top.width() * 0.80f), (top.bottom + bot.top) / 2);
                }
                SplitController.setDivider(p);
                return true;
            }
            SplitController.setDivider(null);
            return false;
        } catch (Exception e) { return false; }
    }

    /** Serit yalnizca kayitli ikilinin iki uygulamasi da ekrandayken gorunur; aksi halde yuzen simge */
    private void updateActive() {
        boolean split = isSplitActive(); // ayrac konumunu da gunceller
        boolean on = false;
        if (split) {
            String[] lr = currentPair();
            String l = Prefs.side(this, Prefs.LEFT), r = Prefs.side(this, Prefs.RIGHT);
            on = lr != null && l != null && r != null
                    && ((lr[0].equals(l) && lr[1].equals(r)) || (lr[0].equals(r) && lr[1].equals(l)));
        }
        rememberRatio(on);
        // ikili kendiliginden bozulduysa (biz bir islem yapmiyorken) ekranda ne oldugunu kaydet: tani icin
        if (!on && SplitController.isActive() && SystemClock.elapsedRealtime() > busyUntil
                && SystemClock.uptimeMillis() - lastBrokenDump > 10000) {
            lastBrokenDump = SystemClock.uptimeMillis();
            SplitLog.add("pair left the screen (fg=" + fg + ")");
            dumpWindows();
            // otomatik geri kurma kaldirildi: ana ekran tusuna basilinca ikiliyi geri aciyordu
        }
        SplitController.setActive(on);
    }
    private long lastBrokenDump, lastDragAt, lastAutoRecover;

    /**
     * Ikili kendiliginden bozulduysa (Tbox'ta Haritalar bazen bolunmus ekrandan dusuyor) bir kez geri kur.
     * Yalnizca onde hala ikilinin bir uygulamasi varsa: kullanici baska uygulama ya da ana ekran actiysa dokunma.
     * En fazla 90 sn'de bir; mesgulken (surukleme, yer degistirme, acilis) hic.
     */
    private void scheduleAutoRecover() {
        if (h == null) h = new Handler(getMainLooper());
        h.postDelayed(new Runnable() {
            public void run() {
                String l = Prefs.side(SplitService.this, Prefs.LEFT), r = Prefs.side(SplitService.this, Prefs.RIGHT);
                long now = SystemClock.uptimeMillis();
                if (l == null || r == null || SplitController.isActive()) return;
                if (SystemClock.elapsedRealtime() < busyUntil || now - lastDragAt < 3000) return;
                if (fg == null || !(fg.equals(l) || fg.equals(r))) return;
                if (now - lastAutoRecover < 90000) { SplitLog.add("pair broke again, auto restore skipped (limit)"); return; }
                lastAutoRecover = now;
                SplitLog.add("pair broke by itself (fg=" + fg + "), restoring once");
                restorePair(l, r);
            }
        }, 2500);
    }

    // ---------- elle ayarlanan orani hatirla ----------
    private int seenRatio = -1, blockedAt = -1;
    private long seenAt, ratioOpAt;
    private long pendingSince;
    /** Manual remembering starts only after movement away from the last automatic position. */
    private boolean ratioArmed;

    /** Ikili ekrandayken ayracin yerini izler; ikiliden cikinca son konumu o ikilinin orani olarak kaydeder */
    private void rememberRatio(boolean on) {
        long t = SystemClock.uptimeMillis();
        boolean settled = (ratioArmed || blockedAt >= 0) && t - ratioOpAt > 4000
                && SystemClock.elapsedRealtime() > busyUntil;
        if (on) {
            if (settled && t - seenAt > 1000) {
                SplitGeometry geometry = splitGeometry();
                int p = geometry != null && geometry.coversScreen() ? geometry.percent() : -1;
                if (p >= 10 && p <= 90 && (ratioArmed || Math.abs(p - blockedAt) >= 3)) {
                    ratioArmed = true;
                    blockedAt = -1;
                    // ayrac elle cekilip birakildiysa (iki okuma ayni) hemen kaydet ve seridi guncelle
                    if (p != seenRatio) pendingSince = t;   // ayrac yeni bir yerde: sayaci yeniden baslat
                    // ikili bozulurken (or. Haritalar ikiliden cikarken) ayrac da kayar: ancak 5 sn ayni yerde
                    // kalan ve ikili hala ekrandayken okunan konum "elle ayar" sayilir
                    if (p == seenRatio && !floatStuck && t - pendingSince >= 5000) {
                        String l = Prefs.side(this, Prefs.LEFT), r = Prefs.side(this, Prefs.RIGHT);
                        if (l != null && r != null && Math.abs(Prefs.ratio(this, l, r) - p) >= 3) {
                            Prefs.setRatio(this, l, r, p);
                            SplitLog.add("ratio remembered: " + p + "%");
                            OverlayService.refreshRatio();
                        }
                    }
                    seenRatio = p;
                } else {
                    seenRatio = -1;
                }
                seenAt = t;
            }
            return;
        }
        // ikili ekrandan giderken okunan son konum guvenilmez (kapanma animasyonu): kaydetme
        seenRatio = -1;
    }

    private void start(String pkg, int extraFlags) {
        try {
            Intent i = AppList.launchIntent(this, pkg);
            if (i == null) { SplitLog.add("no launch intent: " + pkg); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | extraFlags);
            startActivity(i);
            SplitLog.add("start " + pkg + (extraFlags != 0 ? " (adjacent)" : ""));
        } catch (Exception e) { SplitLog.add("start failed " + pkg + ": " + e); }
    }

    private boolean pairKeep;
    private boolean pairBroken;

    public void runPair(final String left, final String right) { runPair(left, right, false); }

    /** keep=true: yuzen simgeden donus; uygulamalar sadece one getirilir, surus istegi gonderilmez */
    public void runPair(final String left, final String right, final boolean keep) {
        pairKeep = keep;
        if (h == null) h = new Handler(getMainLooper());
        ratioOpAt = SystemClock.uptimeMillis() + 20000;   // ikili acilirken (denemeler dahil) orani izleme
        ratioArmed = false;
        blockedAt = -1;
        seenRatio = -1;
        long now = SystemClock.elapsedRealtime();
        if (now < busyUntil) { SplitLog.add("busy, ignored"); return; }
        busyUntil = now + 25000;
        ratioToken++;
        if (pendingRatio != null) h.removeCallbacks(pendingRatio);
        pendingRatio = null;
        attempt = 1;
        floatFixed = false;
        floatStuck = false;
        SplitLog.add("pair: " + left + " + " + right + " (API " + Build.VERSION.SDK_INT + ")");
        // eski muzik paneli gorevi tam ekran one gelip digerini ezmesin: yalnizca ilk denemede, ana is parcaciginin disinda kapat
        if (AppList.isPanel(this, left) || AppList.isPanel(this, right)) {
            new Thread(new Runnable() { public void run() { PairActivity.closePanelTask(SplitService.this); } }).start();
        }
        quietMaps();
        performGlobalAction(GLOBAL_ACTION_HOME);
        h.postDelayed(new Runnable() {
            public void run() {
                if (Build.VERSION.SDK_INT >= 32) {
                    try { PairActivity.launch(SplitService.this, left, right, keep); return; }
                    catch (Exception e) { SplitLog.add("pair activity failed: " + e); }
                }
                legacy(left, right);
            }
        }, 700);
    }

    /** PairActivity bitince cagirilir: bolunme olmadiysa diger yontemlere gec */
    public void afterPair(final String left, final String right) { afterPair(left, right, 0); }

    private void afterPair(final String left, final String right, final int waited) {
        if (h == null) h = new Handler(getMainLooper());
        h.postDelayed(new Runnable() {
            public void run() {
                String[] lr = currentPair();
                if (lr == null && waited < 3 && isSplitActive()) {
                    // iki bolme var ama biri henuz tanimlanamadi (yavas acilan uygulama, or. Yandex Navi): bozup yeniden deneme, bekle
                    SplitLog.add("split formed, waiting for app to finish loading (" + (waited + 1) + ")");
                    afterPair(left, right, waited + 1);
                    return;
                }
                dumpWindows();
                SplitLog.add("on screen: " + (lr == null ? "no split" : lr[0] + " | " + lr[1]));
                if (lr != null && lr[0].equals(left) && lr[1].equals(right)) {
                    SplitLog.add("split OK (attempt " + attempt + ")");
                    if (!floatFixed && fixFloating(left, right, dividerWindow())) {
                        floatFixed = true;
                        SplitLog.add("floating window fixed, reopening pair");
                        h.postDelayed(new Runnable() {
                            public void run() {
                                performGlobalAction(GLOBAL_ACTION_HOME);
                                h.postDelayed(new Runnable() {
                                    public void run() { PairActivity.launch(SplitService.this, left, right, true); }
                                }, 900);
                            }
                        }, 1200);
                        return;
                    }
                    pairBroken = false;
                    busyUntil = 0;
                    updateActive();
                    if (!pairKeep) resendMapsDrive(left, right);
                    else resendIfMapsNormal(left, right);
                    scheduleRatio(left, right, initialRatioDelay(left, right));
                    return;
                }
                if (lr != null && lr[0].equals(right) && lr[1].equals(left)) {
                    // bu cihaz yan uygulamayi diger tarafa koyuyor: ogren. Ikili kuruldu, bozup yeniden denemek
                    // (Tbox'ta) zincirleme bozulmalara yol aciyordu: oldugu gibi kabul et, sonraki acilis dogru tarafta olur
                    boolean sw = !Prefs.get(SplitService.this, "swap", false);
                    Prefs.set(SplitService.this, "swap", sw);
                    SplitLog.add("sides reversed, learned adjacent side (swap=" + sw + "), kept as is");
                    String nl = right, nr = left;
                    Prefs.setSide(SplitService.this, Prefs.LEFT, nl);
                    Prefs.setSide(SplitService.this, Prefs.RIGHT, nr);
                    OverlayService.refreshIcons();
                    pairBroken = false;
                    busyUntil = 0;
                    updateActive();
                    if (!pairKeep) resendMapsDrive(nl, nr);
                    else resendIfMapsNormal(nl, nr);
                    scheduleRatio(nl, nr, initialRatioDelay(nl, nr));
                    return;
                }
                if (attempt < 3) {
                    attempt++;
                    SplitLog.add("retrying adjacent method (attempt " + attempt + ")");
                    performGlobalAction(GLOBAL_ACTION_HOME);
                    h.postDelayed(new Runnable() {
                        public void run() { PairActivity.launch(SplitService.this, left, right, pairKeep); }
                    }, 900);
                } else {
                    SplitLog.add("adjacent method failed, trying system methods");
                    enterSplit(left, right);
                }
            }
        }, waited == 0 ? 900 : 1200);
    }

    /** Surus modu acikken Haritalar eski ciftten geri gelirse istegi kacirabilir: bir kez daha gonder */
    private void resendMapsDrive(String left, String right) {
        if (!Prefs.mapsDrive(this)) return;
        if (!AppList.MAPS.equals(left) && !AppList.MAPS.equals(right)) return;
        h.postDelayed(new Runnable() {
            public void run() {
                try {
                    // Haritalar zaten surus modundaysa tekrar gonderme (bir an arama ekranina dusup geri donuyor)
                    if ("drive".equals(mapsStateNow())) { SplitLog.add("maps already in drive mode, no re-send"); return; }
                    Intent i = AppList.launchIntent(SplitService.this, AppList.MAPS);
                    if (i == null) return;
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                    SplitLog.add("maps drive intent re-sent: " + i.getData() + " fg=" + fg);
                } catch (Exception e) { SplitLog.add("maps drive re-send failed: " + e); }
            }
        }, 1200);
    }

    /** Ikili geri acilirken Haritalar ana ekranda (sekmeler) geldiyse surus istegini gonder */
    private void resendIfMapsNormal(final String left, final String right) {
        if (!Prefs.mapsDrive(this)) return;
        if (!AppList.MAPS.equals(left) && !AppList.MAPS.equals(right)) return;
        h.postDelayed(new Runnable() {
            public void run() {
                String st = mapsStateNow();
                if ("normal".equals(st)) {
                    SplitLog.add("maps came back on main map, sending drive intent");
                    resendMapsDrive(left, right);
                }
            }
        }, 600);
    }

    /** Haritalar'in su anki durumu: normal / drive / other ya da bulunamadiysa null */
    private String mapsStateNow() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return null;
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo x = w.getRoot();
                if (x == null || x.getPackageName() == null || !AppList.MAPS.equals(x.getPackageName().toString())) continue;
                int[] c = new int[5];
                scanMaps(x, c, 0);
                return c[0] >= 2 ? "normal" : c[1] >= 1 ? "drive" : "other";
            }
        } catch (Exception e) { }
        return null;
    }

    /** Bolunmus ekrandaki [sol/ust, sag/alt] paketler; bolunme yoksa null */
    private String[] currentPair() {
        SplitGeometry geometry = splitGeometry();
        if (geometry == null) return null;
        String left = Prefs.side(this, Prefs.LEFT), right = Prefs.side(this, Prefs.RIGHT);
        return geometry.firstIsLeft ? new String[]{left, right} : new String[]{right, left};
    }

    private void legacy(final String left, final String right) {
        start(left, 0);
        h.postDelayed(new Runnable() {
            public void run() { enterSplit(left, right); }
        }, 1600);
    }

    /** On plandaki sol uygulamayi bolunmus ekrana alir */
    private void enterSplit(String left, String right) {
        if (AppList.isPanel(this, left) || AppList.isPanel(this, right)) {
            // panel son uygulamalar menusunde secilemiyor: kullaniciyi orada birakma
            SplitLog.add("panel pair: system methods skipped");
            PairActivity.closePanelTask(this);
            performGlobalAction(GLOBAL_ACTION_HOME);
            return;
        }
        boolean ok = performGlobalAction(ACTION_TOGGLE_SPLIT_SCREEN);
        SplitLog.add("toggle split: " + ok);
        if (ok) startSecond(right, 1200);
        else viaRecents(left, right);
    }

    // ---------- yer degistirme: once sistemin kendi yontemi ----------
    private static final Pattern SWAP_ITEM = Pattern.compile(
            "(swap|switch|yer değiş|yer degis|değiştir|degistir|takas)", Pattern.CASE_INSENSITIVE);

    /** Bolunmus ekrandaki iki uygulamanin yerini degistirir:
     *  1) ayrac menusu (Samsung vb.), 2) ayraca cift dokunma (AOSP), 3) ikiliyi yeniden kurma */
    public void nativeSwap() {
        if (h == null) h = new Handler(getMainLooper());
        long now = SystemClock.elapsedRealtime();
        if (now < busyUntil) { SplitLog.add("busy, ignored"); return; }
        ratioToken++;
        if (pendingRatio != null) h.removeCallbacks(pendingRatio);
        pendingRatio = null;
        ratioArmed = false;
        blockedAt = -1;
        seenRatio = -1;
        ratioOpAt = SystemClock.uptimeMillis();
        busyUntil = now + 25000;
        final String before = leftPackage();
        final Rect div = dividerBounds();
        SplitLog.add("native swap: left=" + before + " divider=" + (div == null ? "-" : div.toShortString()));
        quietMaps();
        if (div == null) { fallbackSwap(); return; }
        // 1) ayracin erisilebilirlik eylemlerinde "yer degistir" var mi
        AccessibilityNodeInfo root = dividerRoot();
        if (root != null) {
            StringBuilder sb = new StringBuilder();
            Object[] hit = findAction(root, sb, 0);
            SplitLog.add("divider actions:" + sb);
            if (hit != null) {
                boolean ok = ((AccessibilityNodeInfo) hit[0]).performAction((Integer) hit[1]);
                SplitLog.add("divider swap action: " + ok);
                if (ok) { verifySwap(before, 900, new Runnable() { public void run() { tapMenuSwap(before, div); } }); return; }
            }
        }
        tapMenuSwap(before, div);
    }

    /** 2) ayraca tek dokun, acilan menude yer degistir ogesini ara */
    private void tapMenuSwap(final String before, final Rect div) {
        boolean t = tap(div.centerX(), div.centerY(), 1);
        SplitLog.add("divider tap: " + t);
        h.postDelayed(new Runnable() {
            public void run() {
                SplitLog.add("divider menu texts: " + texts());
                List<AccessibilityNodeInfo> ms = matchesIn(SWAP_ITEM, "com.android.systemui");
                AccessibilityNodeInfo item = ms.isEmpty() ? null : ms.get(0);
                if (item != null && click(item)) {
                    SplitLog.add("swap item: " + nodeText(item));
                    verifySwap(before, 900, new Runnable() { public void run() { gestureSwap(before, div); } });
                } else {
                    gestureSwap(before, div);
                }
            }
        }, 800);
    }

    private AccessibilityNodeInfo dividerRoot() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) if (w.getType() == 5) return w.getRoot();
        } catch (Exception e) { }
        return null;
    }

    /** Dugum agacinda etiketi SWAP_ITEM'a uyan erisilebilirlik eylemini bulur; tum eylemleri sb'ye yazar */
    private Object[] findAction(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 10) return null;
        Object[] hit = null;
        try {
            for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
                CharSequence l = a.getLabel();
                sb.append(" [").append(a.getId()).append(l != null ? ":" + l : "").append("]");
                if (hit == null && l != null && SWAP_ITEM.matcher(l).find()) hit = new Object[]{n, a.getId()};
            }
        } catch (Exception e) { }
        for (int i = 0; hit == null && i < n.getChildCount(); i++) hit = findAction(n.getChild(i), sb, depth + 1);
        return hit;
    }

    private void gestureSwap(final String before, Rect div) {
        boolean ok = tap(div.centerX(), div.centerY(), 2);
        SplitLog.add("divider double tap: " + ok);
        if (!ok) { fallbackSwap(); return; }
        verifySwap(before, 1200, new Runnable() { public void run() { fallbackSwap(); } });
    }

    private void verifySwap(final String before, long delay, final Runnable onFail) {
        h.postDelayed(new Runnable() {
            public void run() {
                String after = leftPackage();
                if (after != null && before != null && !after.equals(before)) {
                    SplitLog.add("native swap OK: left=" + after);
                    busyUntil = 0;
                    updateActive();
                    keepRatioAfterSwap();
                } else {
                    SplitLog.add("native swap not applied (left=" + after + ")");
                    onFail.run();
                }
            }
        }, delay);
    }

    private void fallbackSwap() {
        SplitLog.add("swap by re-launching the pair");
        busyUntil = 0;
        runPair(Prefs.side(this, Prefs.LEFT), Prefs.side(this, Prefs.RIGHT));
    }

    /** Ekranin solundaki (ya da ustundeki) buyuk uygulama penceresinin paketi */
    private String leftPackage() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return null;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            long full = (long) dm.widthPixels * dm.heightPixels;
            String best = null; int bx = Integer.MAX_VALUE, by = Integer.MAX_VALUE;
            Rect r = new Rect();
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                w.getBoundsInScreen(r);
                long ar = (long) r.width() * r.height();
                if (ar < full * 0.06 || ar >= full * 0.8 || !pane(r, dm)) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                if (r.left < bx || (r.left == bx && r.top < by)) { bx = r.left; by = r.top; best = root.getPackageName().toString(); }
            }
            return best;
        } catch (Exception e) { return null; }
    }

    /** Sistem ayrac penceresi (TYPE_SPLIT_SCREEN_DIVIDER = 5) */
    private Rect dividerBounds() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != 5) continue;
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                return r;
            }
        } catch (Exception e) { }
        android.graphics.Point p = SplitController.divider();
        if (p == null) return null;
        // ayrac penceresi yoksa hesaplanan ayrac cizgisini kullan (yukseklik ortasi)
        DisplayMetrics dm = getResources().getDisplayMetrics();
        return new Rect(p.x - 4, dm.heightPixels / 2 - 4, p.x + 4, dm.heightPixels / 2 + 4);
    }

    private List<AccessibilityNodeInfo> matchesIn(Pattern p, String pkg) {
        List<AccessibilityNodeInfo> out = new java.util.ArrayList<AccessibilityNodeInfo>();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                if (!root.getPackageName().toString().equals(pkg)) continue;
                collectMatches(root, p, out, 0);
            }
        } catch (Exception e) { }
        return out;
    }

    /** API 24+ dispatchGesture (derleme API 23 ile, bu yuzden yansima) */
    /** API 24+ dispatchGesture ile count kez dokunma (derleme API 23 ile, bu yuzden yansima) */
    private boolean tap(int x, int y, int count) {
        try {
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(x, y);
            Class<?> sd = Class.forName("android.accessibilityservice.GestureDescription$StrokeDescription");
            Class<?> gb = Class.forName("android.accessibilityservice.GestureDescription$Builder");
            Object b = gb.newInstance();
            for (int i = 0; i < count; i++) {
                Object st = sd.getConstructor(android.graphics.Path.class, long.class, long.class).newInstance(path, i * 140L, 40L);
                gb.getMethod("addStroke", sd).invoke(b, st);
            }
            Object g = gb.getMethod("build").invoke(b);
            Class<?> gd = Class.forName("android.accessibilityservice.GestureDescription");
            Class<?> cb = Class.forName("android.accessibilityservice.AccessibilityService$GestureResultCallback");
            Object r = AccessibilityService.class.getMethod("dispatchGesture", gd, cb, Handler.class).invoke(this, g, null, null);
            return Boolean.TRUE.equals(r);
        } catch (Throwable e) {
            SplitLog.add("gesture failed: " + e);
            return false;
        }
    }

    /** Seritten acilan uygulama: ikili arka plana alinir, uygulama tek basina tam ekran acilir */
    /** Otomatik surukleme suruyor mu (bu sirada gelen dokunuslar bizim parmagimizdir) */
    public boolean dragging() { return SystemClock.uptimeMillis() - lastDragAt < 3500; }

    public void openFullscreen(final String pkg) {
        if (h == null) h = new Handler(getMainLooper());
        SplitLog.add("open fullscreen: " + pkg);
        pairBroken = true;   // tam ekran uygulama acilinca sistem ikiliyi ayirir
        SplitController.setActive(false);
        performGlobalAction(GLOBAL_ACTION_HOME);
        h.postDelayed(new Runnable() {
            public void run() { start(pkg, 0); }
        }, 600);
    }

    // ---------- genislik orani ----------
    private static final Pattern LEFT_TOP = Pattern.compile("(sol|left|üst|ust|top)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FULL = Pattern.compile("(tam|full)", Pattern.CASE_INSENSITIVE);

    private void scheduleRatio(final String left, final String right, long delay) {
        if (left == null || right == null) return;
        if (h == null) h = new Handler(getMainLooper());
        if (pendingRatio != null) h.removeCallbacks(pendingRatio);
        final int token = ++ratioToken;
        ratioArmed = false;
        blockedAt = -1;
        delay = Math.max(0L, Math.max(delay, busyUntil - SystemClock.elapsedRealtime() + 1));
        ratioOpAt = SystemClock.uptimeMillis() + delay;
        seenRatio = -1;
        pendingRatio = new Runnable() {
            public void run() {
                pendingRatio = null;
                applyWhenStable(left, right, null, 0, token);
            }
        };
        h.postDelayed(pendingRatio, delay);
    }

    private long initialRatioDelay(String left, String right) {
        // Only drive-mode re-sends can still disturb the pair (also on keep).
        // Watcher quiet time is not pending Maps work; geometry settles below.
        return Prefs.mapsDrive(this)
                && (AppList.MAPS.equals(left) || AppList.MAPS.equals(right)) ? 5000 : 0;
    }

    private int ratioToken;
    private long mapsQuietUntil;

    /** Ikili acilirken / oran ya da yer degisirken Haritalar gecici olarak farkli gorunebilir: 10 sn izleme */
    private void quietMaps() {
        mapsQuietUntil = SystemClock.uptimeMillis() + 10000;
        mapsNormalSince = 0;
        ratioOpAt = Math.max(ratioOpAt, SystemClock.uptimeMillis());
        seenRatio = -1;
    }
    private Runnable pendingRatio;

    /** Seritteki dugme icin: son dokunustan 1 sn sonra uygula (art arda dokunuslar birikir) */
    public void applyRatioDebounced(final String left, final String right) {
        scheduleRatio(left, right, 1000);
    }

    /**
     * Yer degistirmeden sonra ayraci SURUKLEME (Tbox'ta ikiliyi bozuyor / sistemi kilitliyordu):
     * ayrac yerinde kalir, o anki konum yeni dizilisin orani olarak kaydedilir.
     */
    private void keepRatioAfterSwap() {
        h.postDelayed(new Runnable() {
            public void run() {
                String l = Prefs.side(SplitService.this, Prefs.LEFT), r = Prefs.side(SplitService.this, Prefs.RIGHT);
                SplitGeometry geometry = splitGeometry();
                int p = geometry == null ? -1 : geometry.percent();
                ratioArmed = geometry != null && geometry.coversScreen() && !floatStuck;
                if (ratioArmed && l != null && r != null && p >= 10 && p <= 90) {
                    Prefs.setRatio(SplitService.this, l, r, p);
                    SplitLog.add("ratio after swap kept: " + p + "%");
                    OverlayService.refreshRatio();
                }
            }
        }, 1500);
    }

    /** Wait for the actual window edges, not a percentage of a possibly truncated area. */
    private void applyWhenStable(final String left, final String right,
            final SplitGeometry previous, final int n, final int token) {
        if (token != ratioToken || !selectedPair(left, right)) return;
        final SplitGeometry geometry = splitGeometry();
        if (geometry == null || !geometry.sameBounds(previous)) {
            if (n >= 4) {
                SplitLog.add("ratio stopped: window bounds did not settle");
                return;
            }
            h.postDelayed(new Runnable() {
                public void run() { applyWhenStable(left, right, geometry, n + 1, token); }
            }, 700);
            return;
        }
        applyRatio(left, right);
    }

    private boolean selectedPair(String left, String right) {
        return left != null && right != null && left.equals(Prefs.side(this, Prefs.LEFT))
                && right.equals(Prefs.side(this, Prefs.RIGHT));
    }

    public void applyRatio(final String left, final String right) {
        if (h == null) h = new Handler(getMainLooper());
        if (SystemClock.elapsedRealtime() < busyUntil || !SplitController.isActive()
                || !selectedPair(left, right)) {
            SplitLog.add("ratio skipped (busy or no pair)");
            return;
        }
        if (floatStuck) { SplitLog.add("ratio skipped (floating window)"); return; }
        SplitGeometry geometry = splitGeometry();
        if (geometry == null) return;
        ratioArmed = false;
        blockedAt = -1;
        seenRatio = -1;
        new RatioRequest(left, right).start(geometry);
    }

    /** A resize owns its callbacks; a new pair, preference or request invalidates it. */
    private final class RatioRequest {
        final String left, right;
        final int target, token;

        RatioRequest(String left, String right) {
            this.left = left;
            this.right = right;
            target = Prefs.ratio(SplitService.this, left, right);
            token = ++ratioToken;
        }

        boolean current() {
            if (token != ratioToken) return false;
            if (!selectedPair(left, right) || !SplitController.isActive()
                    || target != Prefs.ratio(SplitService.this, left, right)) {
                finish(false, "request changed");
                return false;
            }
            return true;
        }

        void start(SplitGeometry geometry) {
            if (geometry.coversScreen() && Math.abs(geometry.percent() - target) < 5) {
                finish(true, "already fills screen");
                return;
            }
            SplitLog.add("ratio: " + geometry.percent() + "% -> " + target
                    + "%, fills screen=" + geometry.coversScreen());
            quietMaps();
            AccessibilityNodeInfo root = dividerRoot();
            Object[] action = findRatioAction(root, target, 0);
            if (action != null && ((AccessibilityNodeInfo) action[0]).performAction((Integer) action[1])) {
                busyUntil = SystemClock.elapsedRealtime() + 4500;
                SplitLog.add("ratio action: " + action[2]);
                verify(null, null, 0, 2);
                return;
            }
            drag(geometry, 1);
        }

        void drag(final SplitGeometry geometry, final int remaining) {
            if (!current()) return;
            boolean accepted = dragRatio(target, geometry, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription gesture) {
                    if (current()) verify(geometry, null, 0, remaining);
                }
                @Override public void onCancelled(GestureDescription gesture) {
                    finish(false, "gesture cancelled");
                }
            });
            if (!accepted) finish(false, "gesture rejected");
        }

        void verify(final SplitGeometry before, final SplitGeometry previous,
                final int checks, final int remaining) {
            h.postDelayed(new Runnable() {
                public void run() {
                    if (!current()) return;
                    SplitGeometry geometry = splitGeometry();
                    if (geometry == null) { finish(false, "pair no longer visible"); return; }
                    if (!geometry.sameBounds(previous)) {
                        if (checks >= 4) { finish(false, "window bounds did not settle"); return; }
                        verify(before, geometry, checks + 1, remaining);
                        return;
                    }
                    SplitLog.add("ratio measured: " + geometry.percent() + "%, fills screen="
                            + geometry.coversScreen() + ", " + geometry.first.toShortString()
                            + " | " + geometry.second.toShortString() + ", frame=" + geometry.frame.toShortString());
                    if (geometry.coversScreen() && Math.abs(geometry.percent() - target) < 5) {
                        finish(true, "bounds and ratio verified");
                    } else if (geometry.coversScreen() && geometry.sameBounds(before)) {
                        finish(false, "divider did not move (app minimum width?)");
                    } else if (remaining > 0) {
                        drag(geometry, remaining - 1);
                    } else {
                        finish(false, "bounds or ratio still incomplete");
                    }
                }
            }, 700);
        }

        void finish(boolean success, String reason) {
            if (token != ratioToken) return;
            ratioToken++;
            blockedAt = leftPercent();
            ratioArmed = false;
            ratioOpAt = SystemClock.uptimeMillis();
            seenRatio = -1;
            busyUntil = 0;
            SplitLog.add("ratio " + (success ? "verified: " : "stopped: ") + reason);
        }
    }

    /** Ayracin erisilebilirlik eylemlerinde "sol/ust ... yuzde N" olani bulur */
    private Object[] findRatioAction(AccessibilityNodeInfo n, int target, int depth) {
        if (n == null || depth > 10) return null;
        Pattern num = Pattern.compile("(^|\\D)" + target + "(\\D|$)");
        try {
            for (AccessibilityNodeInfo.AccessibilityAction a : n.getActionList()) {
                CharSequence l = a.getLabel();
                if (l == null) continue;
                String t = l.toString();
                if (num.matcher(t).find() && LEFT_TOP.matcher(t).find() && !FULL.matcher(t).find())
                    return new Object[]{n, a.getId(), t};
            }
        } catch (Exception e) { }
        for (int i = 0; i < n.getChildCount(); i++) {
            Object[] hit = findRatioAction(n.getChild(i), target, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    /** Selected app panes and their divider, captured from one accessibility-window list. */
    private SplitGeometry splitGeometry() {
        try { return splitGeometry(getWindows()); }
        catch (Exception e) { return null; }
    }

    private SplitGeometry splitGeometry(List<AccessibilityWindowInfo> windows) {
        String left = Prefs.side(this, Prefs.LEFT), right = Prefs.side(this, Prefs.RIGHT);
        if (windows == null || left == null || right == null) return null;
        try {
            List<Rect> leftWindows = new ArrayList<>(), rightWindows = new ArrayList<>();
            Rect divider = null;
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int outerLeft = Integer.MAX_VALUE, outerTop = Integer.MAX_VALUE;
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() == 5 && divider == null) {
                    divider = new Rect();
                    window.getBoundsInScreen(divider);
                }
                if (window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                CharSequence pkg = root.getPackageName();
                List<Rect> selected = left.contentEquals(pkg) ? leftWindows : right.contentEquals(pkg) ? rightWindows : null;
                if (selected == null) continue;
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (bounds.isEmpty() || !pane(bounds, dm)) continue;
                selected.add(bounds);
                outerLeft = Math.min(outerLeft, bounds.left);
                outerTop = Math.min(outerTop, bounds.top);
            }
            if (leftWindows.isEmpty() || rightWindows.isEmpty()) return null;
            WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
            Rect frame = new Rect();
            if (Build.VERSION.SDK_INT >= 30) {
                WindowMetrics metrics = wm.getMaximumWindowMetrics();
                frame.set(metrics.getBounds());
                android.graphics.Insets insets = metrics.getWindowInsets().getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                frame.set(frame.left + insets.left, frame.top + insets.top,
                        frame.right - insets.right, frame.bottom - insets.bottom);
            } else {
                wm.getDefaultDisplay().getRectSize(frame);
                Point real = new Point();
                wm.getDefaultDisplay().getRealSize(real);
                int leftInset = real.x - frame.width();
                int tolerance = Math.max(2, Math.round(4 * dm.density));
                if (leftInset > 0 && Math.abs(outerLeft - leftInset) <= tolerance) frame.offset(leftInset, 0);
                int statusId = getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (statusId != 0 && outerTop > 0 && outerTop <= getResources().getDimensionPixelSize(statusId))
                    frame.top = outerTop;
            }
            return new SplitGeometry(frame, leftWindows, rightWindows, divider, dm.density);
        } catch (Exception e) { return null; }
    }

    private boolean floatFixed;
    /** pencere bolmesini doldurmuyor ve duzeltilemedi: ayraci surukleme, orani da kaydetme */
    private boolean floatStuck;
    private static final Pattern MAXIMIZE = Pattern.compile("(maximi|büyüt|buyut|tam ekran|full ?screen)", Pattern.CASE_INSENSITIVE);

    /**
     * Ikiliden biri yuzen (serbest bicim, basligi olan kucuk) pencerede mi? Oyleyse baslik cubugundaki
     * "buyut" dugmesiyle tam ekrana alir. div != null: bolunmus ekranda, bolmesini doldurmayan pencere aranir.
     */
    private boolean fixFloating(String left, String right, Rect div) {
        SplitGeometry geometry = splitGeometry();
        if (geometry != null && geometry.isTiled()) {
            // An aligned pair can occupy only part of the display. That is not evidence of freeform mode.
            if (!geometry.coversScreen()) SplitLog.add("incomplete split bounds; keeping divider normalization enabled");
            return false;
        }
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return false;
            android.graphics.Point sc = screen();
            for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                String pkg = root.getPackageName().toString();
                if (!pkg.equals(left) && !pkg.equals(right)) continue;
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                if (r.width() <= 0 || r.height() <= 0) continue;
                if ((long) r.width() * r.height() < (long) sc.x * sc.y * 0.06) continue;
                boolean floating;
                if (div != null && div.height() >= div.width()) {
                    int slot = r.centerX() < div.centerX() ? div.centerX() : sc.x - div.centerX();
                    floating = r.width() < slot * 0.75f;
                } else if (div != null) {
                    int slot = r.centerY() < div.centerY() ? div.centerY() : sc.y - div.centerY();
                    floating = r.height() < slot * 0.75f;
                } else {
                    floating = r.width() < sc.x * 0.8f && r.height() < sc.y * 0.97f
                            && (long) r.width() * r.height() > (long) sc.x * sc.y * 0.06;
                }
                if (!floating) continue;
                SplitLog.add("floating window: " + pkg + " " + r.toShortString());
                AccessibilityNodeInfo b = findMaximize(root, r, 0);
                if (b != null && b.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    SplitLog.add("floating window maximized: " + pkg);
                    return true;
                }
                SplitLog.add("maximize button not found: " + pkg + " (ratio disabled for this pair)");
                floatStuck = true;
            }
        } catch (Exception e) { SplitLog.add("floating check failed: " + e); }
        return false;
    }

    /** Baslik cubugundaki buyut dugmesi: kimligi maximize_window olan ya da pencerenin ust kenarinda "buyut" yazan */
    private AccessibilityNodeInfo findMaximize(AccessibilityNodeInfo n, Rect win, int depth) {
        if (n == null || depth > 12) return null;
        String id = n.getViewIdResourceName();
        CharSequence d = n.getContentDescription();
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        boolean top = b.top - win.top < 80 && b.height() < 120;
        if ((id != null && id.contains("maximize")) || (top && d != null && MAXIMIZE.matcher(d).find())) {
            AccessibilityNodeInfo c = n;
            while (c != null && !c.isClickable()) c = c.getParent();
            if (c != null) return c;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo hit = findMaximize(n.getChild(i), win, depth + 1);
            if (hit != null) return hit;
        }
        return null;
    }

    private android.graphics.Point screen() {
        android.graphics.Point p = new android.graphics.Point();
        ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealSize(p);
        return p;
    }

    /** Sistem ayrac penceresinin (tip 5) konumu; yoksa null */
    private Rect dividerWindow() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                if (w.getType() != 5) continue;
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                return r;
            }
        } catch (Exception e) { }
        return null;
    }

    /** Always relative to the usable display, never to the sum of two truncated panes. */
    private int leftPercent() {
        SplitGeometry geometry = splitGeometry();
        return geometry == null ? -1 : geometry.percent();
    }

    /** Acceptance is not completion: only the callback may start bounds verification. */
    private boolean dragRatio(int target, SplitGeometry geometry, GestureResultCallback callback) {
        ratioOpAt = SystemClock.uptimeMillis();
        lastDragAt = ratioOpAt;
        busyUntil = Math.max(busyUntil, SystemClock.elapsedRealtime() + 4500);
        seenRatio = -1;
        int sx = geometry.startX(), sy = geometry.startY();
        int ex = geometry.targetX(target), ey = geometry.targetY(target);
        try {
            // sure yol uzunluguna gore dagitilir: baslangicta 2 px'lik git-gel ile ~0.3 sn "basili tut"
            android.graphics.Path path = new android.graphics.Path();
            path.moveTo(sx, sy);
            float dist = (float) Math.hypot(ex - sx, ey - sy);
            int travel = Math.max(ViewConfiguration.get(this).getScaledTouchSlop() * 2,
                    Math.round(16 * getResources().getDisplayMetrics().density));
            boolean nudge = dist < travel;
            if (nudge) dist += travel * 2;
            int wiggles = Math.max(10, Math.round(dist * 0.3f / 2f));   // ~%23 sure = ~0.3 sn bekleme
            // baslangicta uzun basili tutma, Tbox'ta pencereyi tutup yuzen pencereye cevirebiliyor: kisa tut
            int startHold = 3;
            boolean horiz = geometry.horizontal;
            for (int i = 0; i < startHold; i++) {
                float o = (i % 2 == 0) ? 2f : 0f;
                if (horiz) path.lineTo(sx, sy + o); else path.lineTo(sx + o, sy);
            }
            // Even at the requested percentage, cross touch slop to trigger Android's real resize,
            // then return to the requested position instead of permanently changing the ratio.
            if (nudge) {
                if (horiz) path.lineTo(sx + (sx <= geometry.frame.centerX() ? travel : -travel), sy);
                else path.lineTo(sx, sy + (sy <= geometry.frame.centerY() ? travel : -travel));
            }
            path.lineTo(ex, ey);
            // hedefte de ~0.3 sn bekle: birakma hizi sifira yakin olsun, ayrac firlatma sanmasin
            for (int i = 0; i < wiggles; i++) {
                float o = (i % 2 == 0) ? 2f : 0f;
                if (horiz) path.lineTo(ex, ey + o); else path.lineTo(ex + o, ey);
            }
            long dur = 1100L + Math.round(dist * 1.6f);   // ~450 px icin ~1.8 sn (yavas cihazda firlatma sanilmasin)
            GestureDescription gesture = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0L, dur)).build();
            boolean accepted = dispatchGesture(gesture, callback, h);
            SplitLog.add("ratio drag " + sx + "," + sy + " -> " + ex + "," + ey + ": " + accepted);
            return accepted;
        } catch (Throwable e) { SplitLog.add("ratio drag failed: " + e); return false; }
    }

    // ---------- Haritalar: navigasyon bitince hedefsiz surus moduna don ----------
    // Haritalar alt sekmeleri (Kesfet / Kayitlilar / Katki) - birden cok dil
    private static final Pattern MAPS_TABS = Pattern.compile("^(" +
            "keşfet|kesfet|kayıtlılar|kayitlilar|kaydedilenler|katkı|katki" +       // tr
            "|explore|saved|contribute" +                                           // en
            "|entdecken|gespeichert|beitragen" +                                    // de
            "|explorer|enregistrés|enregistres|contribuer" +                        // fr
            "|explorar|guardados|guardado|contribuir" +                             // es / pt
            "|salvos|salvo" +                                                       // pt
            "|esplora|salvati|contribuisci" +                                       // it
            "|verkennen|opgeslagen|bijdragen" +                                     // nl
            "|что рядом|обзор|сохранено|сохраненное|сохранённое|добавить|вклад" +    // ru
            "|استكشاف|الاستكشاف|الحفظ|المحفوظة|محفوظة|المساهمة|مساهمة|ساهم" +         // ar
            ")[?؟]?$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private boolean mapsWasDriving;
    private long mapsNormalSince;

    private final Runnable mapsWatch = new Runnable() {
        public void run() {
            try { checkMaps(); } catch (Exception e) { }
            try { if (SplitController.isActive()) rememberRatio(true); } catch (Exception e) { }
            h.postDelayed(this, 2000);
        }
    };

    // surus / navigasyon ekranindaki dugmeler (kendi gunlugunden: "Navigasyon menusu", "Ses duzeyi secici", "Hedefi Ayarla")
    private static final Pattern MAPS_DRIVE = Pattern.compile(
            "(navigasyon menüsü|ses düzeyi|hedefi ayarla|navigasyondan çık|rotaya genel bakış|genel bakış|yeniden ortala|alternatif rota" +
            "|navigation menu|sound|volume|set destination|exit navigation|route overview|overview|re-center|recenter|alternate route" +
            "|navigationsmenü|lautstärke|menu de navigation|volume|menú de navegación|volumen|menu di navigazione|меню навигации|громкость|قائمة التنقل|مستوى الصوت)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    // rota onizlemesindeki baslat dugmesi: kullanici hedef secerken araya girme
    private static final Pattern MAPS_START = Pattern.compile(
            "^(başlat|start|starten|démarrer|demarrer|iniciar|avvia|начать|ابدأ|بدء)$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private String mapsState = "";
    private void setFree(boolean v) { if (Prefs.get(this, "mapsFree", false) != v) Prefs.set(this, "mapsFree", v); }
    // hedefsiz surus ekraninda "Hedefi Ayarla" var, hedefli navigasyonda yok
    private static final Pattern MAPS_FREE = Pattern.compile(
            "(hedefi ayarla|set destination|ziel festlegen|ziel eingeben|définir la destination|establecer destino|definir destino|imposta destinazione|bestemming instellen|указать пункт назначения|تعيين الوجهة)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Haritalar son goruldugunde hedefsiz surus modundaydi (navigasyon degil) */
    public boolean mapsInFreeDrive() { return Prefs.get(this, "mapsFree", false); }
    private boolean otherLogged;

    private void checkMaps() {
        if (!Prefs.mapsDrive(this) || !Prefs.mapsReturn(this) || !SplitController.isActive()) { mapsNormalSince = 0; return; }
        String l = Prefs.side(this, Prefs.LEFT), r = Prefs.side(this, Prefs.RIGHT);
        if (!AppList.MAPS.equals(l) && !AppList.MAPS.equals(r)) return;
        AccessibilityNodeInfo root = null;
        boolean ime = false;
        List<AccessibilityWindowInfo> ws = getWindows();
        if (ws != null) for (AccessibilityWindowInfo w : ws) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) ime = true;
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || root != null) continue;
            AccessibilityNodeInfo x = w.getRoot();
            if (x != null && x.getPackageName() != null && AppList.MAPS.equals(x.getPackageName().toString())) root = x;
        }
        if (root == null) return;
        int[] c = new int[5];  // 0 sekme, 1 surus dugmesi, 2 yazi kutusu, 3 baslat dugmesi, 4 hedefi ayarla
        scanMaps(root, c, 0);
        String state = c[0] >= 2 ? "normal" : c[1] >= 1 ? "drive" : "other";
        if (!state.equals(mapsState)) {
            mapsState = state;
            SplitLog.add("maps state: " + state + " (tabs=" + c[0] + " drive=" + c[1] + " edit=" + c[2] + " start=" + c[3] + " ime=" + ime + ")");
            if (state.equals("other") && !otherLogged) {
                otherLogged = true;   // surus dugmeleri gizlendiginde ekranda ne var: ileride daha iyi tanima icin
                StringBuilder sb = new StringBuilder();
                collect(root, sb, 0);
                SplitLog.add("maps other texts: " + (sb.length() > 400 ? sb.substring(0, 400) : sb.toString()));
            }
        }
        // surus dugmeleri bir sure sonra kendiliginden gizlenebiliyor: "other" surus disi sayilmaz, sadece ana harita sekmeleri sayilir
        if (state.equals("other")) { mapsNormalSince = 0; return; }
        if (state.equals("drive")) { mapsWasDriving = true; setFree(c[4] > 0); mapsNormalSince = 0; return; }
        if (state.equals("normal")) setFree(false);
        if (!mapsWasDriving) return;
        if (SystemClock.uptimeMillis() < mapsQuietUntil) { mapsNormalSince = 0; return; }
        // kullanici hedef ariyor ya da rota onizlemesinde: bekle
        if (state.equals("other") && (ime || c[2] > 0 || c[3] > 0)) { mapsNormalSince = 0; return; }
        long now = SystemClock.uptimeMillis();
        if (mapsNormalSince == 0) { mapsNormalSince = now; return; }
        long wait = 3000;
        if (now - mapsNormalSince < wait) return;
        mapsWasDriving = false;
        mapsNormalSince = 0;
        SplitLog.add("maps left driving mode (" + state + "), returning to free drive");
        start(AppList.MAPS, 0);
    }

    private void scanMaps(AccessibilityNodeInfo n, int[] c, int depth) {
        if (n == null || depth > 80) return;
        String t = nodeText(n).trim().replaceAll("[ً-ْـ]", "");
        String id = n.getViewIdResourceName();
        CharSequence cls = n.getClassName();
        if (cls != null && cls.toString().contains("EditText")) c[2]++;
        if (id != null && Prefs.idSet(this, "mapsTabIds").contains(id)) c[0]++;
        else if (t.length() > 0 && t.length() < 24 && MAPS_TABS.matcher(t).find()) { c[0]++; learn(n, "mapsTabIds"); }
        if (id != null && Prefs.idSet(this, "mapsDriveIds").contains(id)) c[1]++;
        else if (t.length() > 0 && t.length() < 40 && MAPS_DRIVE.matcher(t).find()) { c[1]++; learn(n, "mapsDriveIds"); }
        if (t.length() > 0 && t.length() < 16 && MAPS_START.matcher(t).find()) c[3]++;
        if (t.length() > 0 && t.length() < 40 && MAPS_FREE.matcher(t).find()) c[4]++;
        for (int i = 0; i < n.getChildCount(); i++) scanMaps(n.getChild(i), c, depth + 1);
    }

    /** Dilden bagimsiz tanima icin gorunum kimligini ogren (kendisi ya da en fazla 2 ustu) */
    private void learn(AccessibilityNodeInfo n, String key) {
        AccessibilityNodeInfo x = n;
        for (int k = 0; k < 3 && x != null; k++) {
            String vid = x.getViewIdResourceName();
            if (vid != null && vid.startsWith(AppList.MAPS)) {
                if (Prefs.addId(this, key, vid)) SplitLog.add("learned " + key + ": " + vid);
                return;
            }
            x = x.getParent();
        }
    }

    /** Yuzen simge: Son uygulamalar'daki cift kartina dokunarak ikiliyi oldugu gibi geri getir; olmazsa bastan kur */
    public void restorePair(final String left, final String right) {
        if (h == null) h = new Handler(getMainLooper());
        long now = SystemClock.elapsedRealtime();
        if (now < busyUntil) { SplitLog.add("busy, ignored"); return; }
        busyUntil = now + 12000;
        quietMaps();
        final String ll = AppList.label(this, left), rl = AppList.label(this, right);
        SplitLog.add("restore pair: " + ll + " + " + rl);
        // Son uygulamalar'daki cift kartina dokunmak cihazdan cihaza guvenilmez (Tbox'ta kart adi eksik, dokunus kayabiliyor).
        // Yeniden dizme artik uygulamalari sifirlamiyor, surus modunu ve orani koruyor: dogrudan onu kullan.
        if (true) {
            SplitLog.add("reopening pair without reset");
            busyUntil = 0;
            runPair(left, right, true);
            return;
        }
        boolean r = performGlobalAction(ACTION_RECENTS);
        if (!r) { busyUntil = 0; runPair(left, right); return; }
        h.postDelayed(new Runnable() {
            public void run() {
                AccessibilityNodeInfo card = findPairCard(ll, rl);
                if (card == null) {
                    SplitLog.add("pair card not found in recents, reopening without reset" + texts());
                    busyUntil = 0;
                    runPair(left, right, true);
                    return;
                }
                boolean ok = card.performAction(AccessibilityNodeInfo.ACTION_CLICK) || click(card);
                if (!ok && cardTapX <= 0) {
                    SplitLog.add("pair card not tappable, reopening without reset");
                    busyUntil = 0;
                    runPair(left, right, true);
                    return;
                }
                if (!ok) {
                    // tiklama komutu kabul edilmedi: ikonlarin altina, kartin icine gercek dokunus
                    ok = tap(cardTapX, cardTapY, 1);
                    SplitLog.add("pair card tap " + cardTapX + "," + cardTapY + ": " + ok);
                } else SplitLog.add("pair card click: " + ok);
                verifyRestore(left, right, 2200, true);
            }
        }, 1100);
    }

    private void verifyRestore(final String left, final String right, long delay, final boolean again) {
        h.postDelayed(new Runnable() {
            public void run() {
                String[] lr = currentPair();
                boolean same = lr != null && ((lr[0].equals(left) && lr[1].equals(right)) || (lr[0].equals(right) && lr[1].equals(left)));
                if (same) {
                    SplitLog.add("pair restored from recents");
                    Prefs.setLong(SplitService.this, "recentsFail2", 0);
                    busyUntil = 0;
                    updateActive();
                    scheduleRatio(left, right, 700);
                    return;
                }
                if (again) { verifyRestore(left, right, 1500, false); return; }
                long f = Prefs.getLong(SplitService.this, "recentsFail2") + 1;
                Prefs.setLong(SplitService.this, "recentsFail2", f);
                if (f >= 2) Prefs.set(SplitService.this, "recentsPairs2", false);   // iki kez ust uste olmadi: bu cihazda atla
                SplitLog.add("pair not restored (" + (lr == null ? "no split" : lr[0] + " | " + lr[1]) + ", fail " + f + "), reopening without reset");
                busyUntil = 0;
                runPair(left, right, true);
            }
        }, delay);
    }

    private int cardTapX = -1, cardTapY = -1;

    /** Son uygulamalar'da cift karti: iki uygulama adinin en yakin ortak ustu (once tiklanabilir kart, sonra ortak ust) */
    private AccessibilityNodeInfo findPairCard(String ll, String rl) {
        String a = ll.toLowerCase(), b = rl.toLowerCase();
        String a1 = a.split("\\s+")[0], b1 = b.split("\\s+")[0];
        cardTapX = -1; cardTapY = -1;
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return null;
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || root.getPackageName() == null) continue;
                if (root.getPackageName().toString().equals(getPackageName())) continue;
                List<AccessibilityNodeInfo> as = new java.util.ArrayList<AccessibilityNodeInfo>(), bs = new java.util.ArrayList<AccessibilityNodeInfo>();
                findLabel(root, a, a1, as, 0);
                findLabel(root, b, b1, bs, 0);
                AccessibilityNodeInfo best = null;
                int bestSize = Integer.MAX_VALUE;
                for (AccessibilityNodeInfo x : as) for (AccessibilityNodeInfo y : bs) {
                    AccessibilityNodeInfo lca = commonAncestor(x, y);
                    if (lca == null) continue;
                    // ayni kartta mi: ikili kartinda iki ad karta cok yakin (en fazla 2 seviye); ayri kartlarda ortak ust tum listedir
                    int dx = depthTo(x, lca), dy = depthTo(y, lca);
                    if (dx > 2 || dy > 2) { SplitLog.add("pair card: apps are in separate cards (" + dx + "/" + dy + ")"); continue; }
                    int size = subtreeText(lca, new StringBuilder(), 0);
                    if (size < bestSize) {
                        best = lca; bestSize = size;
                        // kartlar kucultulerek cizildigi icin kartin sinirlari guvenilmez: ikonlarin gercek konumundan dokunma noktasi
                        Rect ra = new Rect(), rb = new Rect();
                        x.getBoundsInScreen(ra);
                        y.getBoundsInScreen(rb);
                        android.graphics.Point sc = screen();
                        cardTapX = (ra.centerX() + rb.centerX()) / 2;
                        cardTapY = Math.min(sc.y - 40, Math.max(ra.bottom, rb.bottom) + Math.round(sc.y * 0.28f));
                    }
                }
                // ortak ust, tum liste olmamali (karti ayri olan iki uygulama): en fazla ~25 dugum
                if (best != null && bestSize <= 25) return best;
                if (best != null) SplitLog.add("pair card: common parent too large (" + bestSize + ")");
            }
        } catch (Exception e) { SplitLog.add("pair card search failed: " + e); }
        return null;
    }

    private void findLabel(AccessibilityNodeInfo n, String full, String first, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 30) return;
        String t = nodeText(n).trim().toLowerCase();
        if (t.length() > 0 && n.isVisibleToUser() && (t.equals(full) || t.equals(first) || t.startsWith(full + " ") || t.endsWith(", " + full))) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (!r.isEmpty()) out.add(n);
        }
        for (int i = 0; i < n.getChildCount(); i++) findLabel(n.getChild(i), full, first, out, depth + 1);
    }

    private static int depthTo(AccessibilityNodeInfo n, AccessibilityNodeInfo anc) {
        int d = 0;
        for (AccessibilityNodeInfo p = n; p != null && d < 50; p = p.getParent(), d++) if (p.equals(anc)) return d;
        return 99;
    }

    private static AccessibilityNodeInfo commonAncestor(AccessibilityNodeInfo x, AccessibilityNodeInfo y) {
        List<AccessibilityNodeInfo> px = new java.util.ArrayList<AccessibilityNodeInfo>();
        for (AccessibilityNodeInfo p = x; p != null; p = p.getParent()) px.add(p);
        for (AccessibilityNodeInfo p = y; p != null; p = p.getParent()) if (px.contains(p)) return p;
        return null;
    }

    private void collectClickables(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 30) return;
        if (n.isClickable()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectClickables(n.getChild(i), out, depth + 1);
    }

    /** Alt agactaki tum yazilari toplar, dugum sayisini dondurur */
    private int subtreeText(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 20) return 0;
        String t = nodeText(n);
        if (t.length() > 0) sb.append(t).append(" | ");
        int c = 1;
        for (int i = 0; i < n.getChildCount(); i++) c += subtreeText(n.getChild(i), sb, depth + 1);
        return c;
    }

    private void startSecond(final String right, long delay) {
        h.postDelayed(new Runnable() {
            public void run() {
                start(right, FLAG_LAUNCH_ADJACENT);
                h.postDelayed(new Runnable() {
                    public void run() {
                        dumpWindows();
                        updateActive();
                        busyUntil = 0;
                        if (SplitController.isActive())
                            scheduleRatio(Prefs.side(SplitService.this, Prefs.LEFT), right, 1500);
                    }
                }, 1500);
            }
        }, delay);
    }

    private static final Pattern MENU_HINT = Pattern.compile(
            "(seçenek|secenek|options|menu|menü|app info|uygulama bilgi)", Pattern.CASE_INSENSITIVE);

    /** Toggle desteklenmiyorsa: Son uygulamalar > sol kartin menusu > Bolunmus ekran > sag kart */
    private void viaRecents(final String left, final String right) {
        boolean r = performGlobalAction(ACTION_RECENTS);
        SplitLog.add("recents: " + r);
        final String label = AppList.label(this, left);
        final String rlabel = AppList.label(this, right);
        h.postDelayed(new Runnable() {
            public void run() {
                SplitLog.add("recents texts: " + texts());
                AccessibilityNodeInfo menu = findMenuButton(label);
                // kart basligi etiketi kisaltabilir ("YouTube Morphe" -> "YouTube")
                if (menu == null && label.trim().contains(" ")) menu = findMenuButton(label.trim().split("\\s+")[0]);
                if (menu == null) {
                    SplitLog.add("menu button not found for '" + label + "'");
                    startSecond(right, 300);
                    return;
                }
                SplitLog.add("menu button: " + nodeText(menu) + " click=" + click(menu));
                h.postDelayed(new Runnable() {
                    public void run() {
                        SplitLog.add("menu texts: " + texts());
                        AccessibilityNodeInfo item = find(SPLIT_ITEM, false);
                        if (item == null) {
                            SplitLog.add("split item not found");
                            startSecond(right, 300);
                            return;
                        }
                        SplitLog.add("split item: " + nodeText(item) + " click=" + click(item));
                        h.postDelayed(new Runnable() {
                            public void run() {
                                AccessibilityNodeInfo card = findExact(rlabel);
                                if (card != null && click(card)) {
                                    SplitLog.add("right card picked: " + rlabel);
                                    h.postDelayed(new Runnable() {
                                        public void run() {
                                            dumpWindows(); updateActive(); busyUntil = 0;
                                            keepRatioAfterSwap();
                                        }
                                    }, 1500);
                                } else {
                                    SplitLog.add("right card not found, launching adjacent");
                                    startSecond(right, 100);
                                }
                            }
                        }, 1300);
                    }
                }, 1000);
            }
        }, 1300);
    }

    private boolean click(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo x = n;
        while (x != null) {
            if (x.isClickable() && x.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            x = x.getParent();
        }
        return false;
    }

    /** Kartin menu dugmesi: etiketi iceren ama etiketin kendisi olmayan, tercihen "secenek/options" iceren dugum */
    private AccessibilityNodeInfo findMenuButton(String label) {
        List<AccessibilityNodeInfo> all = matches(Pattern.compile(Pattern.quote(label), Pattern.CASE_INSENSITIVE));
        AccessibilityNodeInfo hint = null, longer = null, icon = null;
        for (AccessibilityNodeInfo n : all) {
            String t = nodeText(n).trim();
            CharSequence cls = n.getClassName();
            if (!t.equalsIgnoreCase(label)) {
                if (hint == null && MENU_HINT.matcher(t).find()) hint = n;
                if (longer == null) longer = n;
            } else if (icon == null && cls != null && cls.toString().contains("Image")) icon = n;
        }
        if (hint != null) return hint;
        if (icon != null) return icon;   // AOSP / Pixel: kart ikonu menuyu acar
        return longer;
    }

    private AccessibilityNodeInfo findExact(String label) {
        for (AccessibilityNodeInfo n : matches(Pattern.compile("^\\s*" + Pattern.quote(label) + "\\s*$", Pattern.CASE_INSENSITIVE)))
            return n;
        return null;
    }

    private List<AccessibilityNodeInfo> matches(Pattern p) {
        List<AccessibilityNodeInfo> out = new java.util.ArrayList<AccessibilityNodeInfo>();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence pk = root.getPackageName();
                if (pk != null && pk.toString().equals(getPackageName())) continue;
                collectMatches(root, p, out, 0);
            }
        } catch (Exception e) { SplitLog.add("match failed: " + e); }
        return out;
    }

    private void collectMatches(AccessibilityNodeInfo n, Pattern p, List<AccessibilityNodeInfo> out, int depth) {
        if (n == null || depth > 40) return;
        String t = nodeText(n);
        if (t.length() > 0 && p.matcher(t).find()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectMatches(n.getChild(i), p, out, depth + 1);
    }

    private static String nodeText(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t == null || t.length() == 0) t = n.getContentDescription();
        return t == null ? "" : t.toString();
    }

    private AccessibilityNodeInfo find(Pattern p, boolean preferIcon) {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws == null) return null;
            AccessibilityNodeInfo fallback = null;
            for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence pk = root.getPackageName();
                if (pk != null && pk.toString().equals(getPackageName())) continue;
                AccessibilityNodeInfo[] out = new AccessibilityNodeInfo[2];
                walk(root, p, out, 0);
                if (preferIcon && out[1] != null) return out[1];
                if (out[0] != null && fallback == null) fallback = out[0];
            }
            return fallback;
        } catch (Exception e) { SplitLog.add("find failed: " + e); return null; }
    }

    /** out[0] ilk eslesme, out[1] ImageView/ikon olan eslesme */
    private void walk(AccessibilityNodeInfo n, Pattern p, AccessibilityNodeInfo[] out, int depth) {
        if (n == null || depth > 40) return;
        String t = nodeText(n);
        if (t.length() > 0 && p.matcher(t).find()) {
            if (out[0] == null) out[0] = n;
            CharSequence cls = n.getClassName();
            if (out[1] == null && cls != null && cls.toString().contains("Image")) out[1] = n;
        }
        for (int i = 0; i < n.getChildCount(); i++) walk(n.getChild(i), p, out, depth + 1);
    }

    private String texts() {
        StringBuilder sb = new StringBuilder();
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence pk = root.getPackageName();
                if (pk != null && pk.toString().equals(getPackageName())) continue;
                sb.append("\n   [").append(pk).append("] ");
                collect(root, sb, 0);
                if (sb.length() > 1500) break;
            }
        } catch (Exception e) { sb.append(e); }
        return sb.toString();
    }

    private void collect(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 40 || sb.length() > 1500) return;
        String t = nodeText(n);
        if (t.length() > 0) sb.append(t.length() > 30 ? t.substring(0, 30) : t).append(" | ");
        for (int i = 0; i < n.getChildCount(); i++) collect(n.getChild(i), sb, depth + 1);
    }

    private void dumpWindows() {
        try {
            List<AccessibilityWindowInfo> ws = getWindows();
            Rect r = new Rect();
            StringBuilder sb = new StringBuilder("windows:");
            if (ws != null) for (AccessibilityWindowInfo w : ws) {
                w.getBoundsInScreen(r);
                AccessibilityNodeInfo root = w.getRoot();
                sb.append("\n   type=").append(w.getType()).append(" ").append(r.toShortString())
                        .append(" ").append(root == null ? "" : root.getPackageName());
            }
            SplitLog.add(sb.toString());
        } catch (Exception e) { SplitLog.add("dump failed: " + e); }
    }
}
