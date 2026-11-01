package com.abdullaherturk.duopanel;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.view.View;

/**
 * Bolunmus ekran icin tam ekran ara ekran.
 * Android 12L+ : tam ekrandaki bir activity FLAG_ACTIVITY_LAUNCH_ADJACENT ile uygulama acarsa
 * sistem ekrani boler. Ardindan ayni taraftan diger uygulama acilir ve bu ekran kapanir.
 */
public class PairActivity extends Activity {
    private static final int FLAG_LAUNCH_ADJACENT = 0x00001000;
    private long startedAt;
    private int stage;
    private final Handler h = new Handler();

    public static void launch(Context c, String left, String right) { launch(c, left, right, false); }

    /** keep: uygulamalar sadece one getirilir (surus istegi yok, navigasyon/oynatma kesilmez) */
public static void launch(Context c, String left, String right, boolean keep) {
        SplitService service = c instanceof SplitService ? (SplitService) c : SplitService.get();
        Intent i = new Intent(c, PairActivity.class)
                .putExtra("l", left).putExtra("r", right).putExtra("k", keep)
                .putExtra("g", service == null ? -1 : service.pairGeneration())
                .putExtra("t", SystemClock.elapsedRealtime())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        c.startActivity(i);
    }

    protected void onCreate(Bundle b) {
        setTheme(UiUtil.theme(this));
        super.onCreate(b);
        View v = new View(this);
        v.setBackgroundColor(UiUtil.attr(this, R.attr.dpBg));
        setContentView(v);
        final String l = getIntent().getStringExtra("l");
        final String r = getIntent().getStringExtra("r");
        if (l == null || r == null || !currentLaunch()) { finish(); return; }
        startedAt = b == null ? SystemClock.uptimeMillis() : b.getLong("pairStartedAt");
        stage = b == null ? 0 : b.getInt("pairStage");
        if (stage >= 3) { finish(); return; }
        boolean swap = Prefs.get(this, "swap", false);
        final String adjacent = swap ? l : r;
        final String same = swap ? r : l;
        SplitLog.add("pair activity: adjacent=" + adjacent + " same=" + same);
        if (stage < 1) h.postDelayed(new Runnable() {
            public void run() { stage = 1; open(adjacent, FLAG_LAUNCH_ADJACENT); }
        }, Math.max(0, startedAt + 400 - SystemClock.uptimeMillis()));
        if (stage < 2) h.postDelayed(new Runnable() {
            public void run() { stage = 2; open(same, 0); }
        }, Math.max(0, startedAt + 1800 - SystemClock.uptimeMillis()));
        h.postDelayed(new Runnable() {
            public void run() {
                stage = 3;
                finish();
                overridePendingTransition(0, 0);
                SplitService s = SplitService.get();
                if (s != null && currentLaunch())
                    s.afterPair(l, r, getIntent().getIntExtra("g", s.pairGeneration()));
            }
        }, Math.max(0, startedAt + 2700 - SystemClock.uptimeMillis()));
    }

    static void closePanelTask(Context c) {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(ACTIVITY_SERVICE);
            for (android.app.ActivityManager.AppTask t : am.getAppTasks()) {
                android.content.ComponentName cn = null;
                try { cn = t.getTaskInfo().baseIntent.getComponent(); } catch (Exception ignored) {}
                if (cn != null && cn.getClassName().endsWith(".MusicActivity")) {
                    t.finishAndRemoveTask();
                    SplitLog.add("old panel task closed");
                }
            }
        } catch (Exception e) { SplitLog.add("panel task close failed: " + e); }
    }

    private boolean currentLaunch() {
        int generation = getIntent().getIntExtra("g", -1);
        if (generation < 0) return true;
        SplitService service = SplitService.get();
        return service != null && service.pairLaunchCurrent(getIntent().getStringExtra("l"),
                getIntent().getStringExtra("r"), generation);
    }

private void open(String pkg, int flags) {
        if (!currentLaunch()) return;
        try {
            boolean keep = getIntent().getBooleanExtra("k", false);
            SplitService ss = SplitService.get();
            boolean freeDrive = AppList.MAPS.equals(pkg) && ss != null && ss.mapsInFreeDrive();
            Intent i = (keep && !freeDrive && !AppList.isPanel(this, pkg))
                    ? getPackageManager().getLaunchIntentForPackage(pkg)
                    : AppList.launchIntent(this, pkg);
            if (i == null) { SplitLog.add("no launch intent: " + pkg); return; }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | flags);
            try { startActivity(i); }
            catch (android.content.ActivityNotFoundException nf) {
                SplitLog.add("drive intent rejected, normal launch: " + pkg);
                i = AppList.isPanel(this, pkg) ? AppList.launchIntent(this, pkg) : getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) return;
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | flags);
                startActivity(i);
            }
            SplitLog.add("pair open " + pkg + (flags != 0 ? " (adjacent)" : "") + (i.getData() != null ? " " + i.getData() : ""));
        } catch (Exception e) { SplitLog.add("pair open failed " + pkg + ": " + e); }
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        state.putLong("pairStartedAt", startedAt);
        state.putInt("pairStage", stage);
        super.onSaveInstanceState(state);
    }
    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
