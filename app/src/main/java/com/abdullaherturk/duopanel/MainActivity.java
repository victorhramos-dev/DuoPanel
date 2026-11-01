package com.abdullaherturk.duopanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.CompoundButton;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import java.util.List;

public class MainActivity extends Activity {
    private final boolean[] del = new boolean[2];
    private UpdateController updates;

    @Override
    protected void onCreate(Bundle b) {
        setTheme(UiUtil.theme(this));
        super.onCreate(b);
        SplitLog.install(this);
        // "Tumunu temizle" hizmeti kapattiysa: simgeye dokununca hizmeti yeniden ac ve ikiliyi baslat
        if (b == null && Prefs.hasPair(this) && PermissionHelper.tryEnableAccessibility(this)) {
            Prefs.setLong(this, "pendingLaunch", android.os.SystemClock.elapsedRealtime());
            OverlayService.start(this);
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        updates = new UpdateController(this, b);
        findViewById(R.id.btn_update).setOnClickListener(v -> updates.check(true));
        findViewById(R.id.btn_setup).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { startActivity(new Intent(MainActivity.this, SetupActivity.class)); }
        });
        findViewById(R.id.btn_pick_left).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickDialog(Prefs.LEFT); }
        });
        findViewById(R.id.btn_pick_right).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { pickDialog(Prefs.RIGHT); }
        });
        findViewById(R.id.btn_start).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { SplitController.launchPair(MainActivity.this); }
        });
        findViewById(R.id.btn_save).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!Prefs.hasPair(MainActivity.this)) { toast(R.string.pick_both); return; }
                String l = Prefs.side(MainActivity.this, Prefs.LEFT), r = Prefs.side(MainActivity.this, Prefs.RIGHT);
                toast(Prefs.addPair(MainActivity.this, l, r) ? R.string.pair_saved : R.string.pair_exists);
                Shortcuts.refreshDynamic(MainActivity.this);
                renderPairs();
            }
        });
        link(R.id.link_github, "https://github.com/abdullah-erturk");
        link(R.id.link_coffee, "https://buymeacoffee.com/abdullaherturk");
        findViewById(R.id.btn_log).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { logDialog(); }
        });
        if (!Prefs.get(this, "setupSeen", false) && !PermissionHelper.allCore(this)) {
            Prefs.set(this, "setupSeen", true);
            startActivity(new Intent(this, SetupActivity.class));
        }
    }

    // yuzen simge seritten/simgeden kaldirilinca anahtar hemen guncellensin
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener prefListener =
            new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
                public void onSharedPreferenceChanged(android.content.SharedPreferences sp, String key) {
                    if ("bubble".equals(key) || "strip".equals(key) || "stripEdge".equals(key)) {
                        runOnUiThread(new Runnable() {
                            public void run() {
                                bindSwitch(R.id.sw_bubble, "bubble", true, true);
                                bindSwitch(R.id.sw_strip, "strip", true, true);
                                bindStripPosition();
                            }
                        });
                    }
                }
            };

    @Override
    protected void onPause() {
        if (updates != null) updates.pause();
        getSharedPreferences("duo", 4).unregisterOnSharedPreferenceChangeListener(prefListener);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getSharedPreferences("duo", 4).registerOnSharedPreferenceChangeListener(prefListener);
        refresh();
        OverlayService.start(this);
        if (updates != null && !isFinishing()) updates.resume();
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        if (updates != null) updates.save(state);
        super.onSaveInstanceState(state);
    }

    private void bindSwitch(int id, final String key, boolean def, final boolean overlayRefresh) {
        Switch s = (Switch) findViewById(id);
        s.setOnCheckedChangeListener(null);
        s.setChecked(Prefs.get(this, key, def));
        s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean on) {
                Prefs.set(MainActivity.this, key, on);
                if (overlayRefresh) OverlayService.refresh(MainActivity.this);
            }
        });
    }

    private void bindStripPosition() {
        final android.widget.RadioGroup group = (android.widget.RadioGroup) findViewById(R.id.strip_position);
        group.setOnCheckedChangeListener(null);
        group.check(Prefs.stripEdge(this) ? R.id.strip_edge : R.id.strip_floating);
        group.setOnCheckedChangeListener(new android.widget.RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(android.widget.RadioGroup g, int id) {
                Prefs.setStripEdge(MainActivity.this, id == R.id.strip_edge);
                OverlayService.refresh(MainActivity.this);
            }
        });
    }

    private void refresh() {
        boolean ok = PermissionHelper.allCore(this);
        findViewById(R.id.dot_status).setBackgroundResource(ok ? R.drawable.bg_dot_ok : R.drawable.bg_dot_warn);
        ((TextView) findViewById(R.id.tv_status)).setText(ok ? R.string.status_ok : R.string.status_missing);
        slot(Prefs.LEFT, R.id.iv_slot_left, R.id.tv_slot_left, R.id.ll_hist_left);
        slot(Prefs.RIGHT, R.id.iv_slot_right, R.id.tv_slot_right, R.id.ll_hist_right);
        bindSwitch(R.id.sw_autostart, "autostart", true, true);
        bindSwitch(R.id.sw_autolaunch, "autolaunch", false, false);
        bindSwitch(R.id.sw_strip, "strip", true, true);
        bindStripPosition();
        bindMapsSwitches();
        renderRatio();
        bindSwitch(R.id.sw_divider, "dividerSwap", true, true);
        renderPairs();
        bindSwitch(R.id.sw_bubble, "bubble", true, true);
        renderMusic();
        ImageView th = (ImageView) findViewById(R.id.btn_theme);
        int m = Prefs.themeMode(this);
        th.setImageResource(m == 1 ? R.drawable.ic_wx_sun : m == 2 ? R.drawable.ic_wx_moon : R.drawable.ic_theme_auto);
        th.setContentDescription(getString(m == 1 ? R.string.theme_light : m == 2 ? R.string.theme_dark : R.string.theme_auto));
        th.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                int n = (Prefs.themeMode(MainActivity.this) + 1) % 3;
                Prefs.setThemeMode(MainActivity.this, n);
                android.widget.Toast.makeText(MainActivity.this,
                        n == 1 ? R.string.theme_light : n == 2 ? R.string.theme_dark : R.string.theme_auto,
                        android.widget.Toast.LENGTH_SHORT).show();
                OverlayService.refresh(MainActivity.this);
                recreate();
            }
        });
    }

    /** Muzik paneli: secili muzik uygulamasi ve bildirim erisimi */
    private void renderMusic() {
        String mp = Prefs.musicPkg(this);
        ImageView iv = (ImageView) findViewById(R.id.iv_music_app);
        TextView tv = (TextView) findViewById(R.id.tv_music_app);
        if (mp != null && AppList.installed(this, mp)) {
            iv.setImageDrawable(AppList.icon(this, mp));
            tv.setText(AppList.label(this, mp));
        } else {
            iv.setImageResource(R.drawable.ic_music_app);
            tv.setText(R.string.music_auto);
        }
        findViewById(R.id.btn_music_app).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { musicDialog(); }
        });
        boolean acc = MusicHub.hasAccess(this);
        findViewById(R.id.dot_music).setBackgroundResource(acc ? R.drawable.bg_dot_ok : R.drawable.bg_dot_warn);
        TextView b = (TextView) findViewById(R.id.btn_music_access);
        b.setText(acc ? R.string.granted : R.string.open);
        b.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { MusicHub.openAccess(MainActivity.this); }
        });
    }

    private void musicDialog() {
        final List<String> apps = MusicHub.musicApps(this);
        apps.add(0, null);
        final String cur = Prefs.musicPkg(this);
        GridView gv = new GridView(this);
        gv.setNumColumns(GridView.AUTO_FIT);
        gv.setColumnWidth(dp(84));
        gv.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gv.setVerticalSpacing(dp(8));
        gv.setPadding(dp(8), dp(8), dp(8), dp(8));
        final Context ctx = this;
        gv.setAdapter(new BaseAdapter() {
            public int getCount() { return apps.size(); }
            public Object getItem(int i) { return apps.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(ctx).inflate(R.layout.item_app, p, false);
                String pk = apps.get(i);
                ImageView iv = (ImageView) v.findViewById(R.id.iv_app);
                TextView t = (TextView) v.findViewById(R.id.tv_app);
                if (pk == null) { iv.setImageResource(R.drawable.ic_music_app); t.setText(R.string.music_auto); }
                else { iv.setImageDrawable(AppList.icon(ctx, pk)); t.setText(AppList.label(ctx, pk)); }
                boolean sel = pk == null ? cur == null : pk.equals(cur);
                t.setTextColor(UiUtil.attr(ctx, sel ? R.attr.dpAccent : R.attr.dpText));
                t.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
                return v;
            }
        });
        final AlertDialog dlg = new AlertDialog.Builder(this, UiUtil.dialogTheme(this))
                .setTitle(R.string.music_app).setView(gv)
                .setNegativeButton(R.string.close, null).create();
        gv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                dlg.dismiss();
                String mp = apps.get(pos);
                Prefs.setMusicPkg(MainActivity.this, mp);
                if (mp != null) Prefs.setMusicLast(MainActivity.this, mp);
                renderMusic();
            }
        });
        dlg.show();
    }

    private void link(int id, final String url) {
        findViewById(id).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                try { startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))); }
                catch (Exception e) { toast(R.string.settings_missing); }
            }
        });
    }

    private void toast(int res) { android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_SHORT).show(); }

    private TextView pill(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(UiUtil.attr(this, R.attr.dpAccent));
        t.setTextSize(13);
        t.setTypeface(null, Typeface.BOLD);
        t.setGravity(android.view.Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bg_pill);
        t.setMinHeight(dp(40));
        t.setPadding(dp(16), 0, dp(16), 0);
        t.setClickable(true);
        return t;
    }

    private ImageView iconBtn(int icon, int desc, int tintAttr) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setColorFilter(UiUtil.attr(this, tintAttr));
        b.setBackgroundResource(R.drawable.bg_pill_glass);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setContentDescription(getString(desc));
        b.setClickable(true);
        return b;
    }

    /** Surus modu kapaninca "navigasyon bitince dondur" da kapanir ve pasif olur */
    private void bindMapsSwitches() {
        final Switch drive = (Switch) findViewById(R.id.sw_maps_drive);
        final Switch ret = (Switch) findViewById(R.id.sw_maps_return);
        drive.setOnCheckedChangeListener(null);
        ret.setOnCheckedChangeListener(null);
        boolean d = Prefs.mapsDrive(this);
        if (!d && Prefs.mapsReturn(this)) Prefs.set(this, "mapsReturn", false);
        drive.setChecked(d);
        ret.setChecked(Prefs.mapsReturn(this));
        ret.setEnabled(d);
        ret.setAlpha(d ? 1f : 0.45f);
        drive.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean on) {
                Prefs.set(MainActivity.this, "mapsDrive", on);
                if (!on) Prefs.set(MainActivity.this, "mapsReturn", false);
                bindMapsSwitches();
            }
        });
        ret.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton c, boolean on) { Prefs.set(MainActivity.this, "mapsReturn", on); }
        });
    }

    private void renderRatio() {
        final String l = Prefs.side(this, Prefs.LEFT), r = Prefs.side(this, Prefs.RIGHT);
        int cur = (l == null || r == null) ? 50 : Prefs.ratio(this, l, r);
        int[] ids = {R.id.ratio_30, R.id.ratio_50, R.id.ratio_70};
        int[] vals = {30, 50, 70};
        for (int i = 0; i < 3; i++) {
            TextView t = (TextView) findViewById(ids[i]);
            final int v = vals[i];
            boolean sel = v == cur;
            t.setBackgroundResource(sel ? R.drawable.bg_pill : R.drawable.bg_pill_glass);
            t.setTextColor(UiUtil.attr(this, sel ? R.attr.dpAccent : R.attr.dpMuted));
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View x) {
                    if (l == null || r == null) { toast(R.string.pick_both); return; }
                    Prefs.setRatio(MainActivity.this, l, r, v);
                    renderRatio();
                    renderPairs();
                    OverlayService.refresh(MainActivity.this);
                }
            });
        }
    }

    private void renderPairs() {
        LinearLayout box = (LinearLayout) findViewById(R.id.ll_pairs);
        box.removeAllViews();
        List<String[]> ps = Prefs.pairs(this);
        findViewById(R.id.tv_no_pairs).setVisibility(ps.isEmpty() ? View.VISIBLE : View.GONE);
        for (final String[] p : ps) {
            final String l = p[0], r = p[1];
            boolean ok = AppList.installed(this, l) && AppList.installed(this, r);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(6), 0, dp(6));
            ImageView a = new ImageView(this);
            a.setImageDrawable(AppList.icon(this, l));
            row.addView(a, new LinearLayout.LayoutParams(dp(36), dp(36)));
            ImageView b = new ImageView(this);
            b.setImageDrawable(AppList.icon(this, r));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(36), dp(36));
            bp.leftMargin = dp(4);
            row.addView(b, bp);
            TextView t = new TextView(this);
            t.setText(Shortcuts.label(this, l, r));
            t.setTextColor(UiUtil.attr(this, ok ? R.attr.dpText : R.attr.dpMuted));
            t.setTextSize(15);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
            tp.leftMargin = dp(12);
            row.addView(t, tp);
            TextView open = pill(R.string.open);
            open.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (!AppList.installed(MainActivity.this, l) || !AppList.installed(MainActivity.this, r)) { toast(R.string.pick_both); return; }
                    Prefs.setSide(MainActivity.this, Prefs.LEFT, l);
                    Prefs.setSide(MainActivity.this, Prefs.RIGHT, r);
                    refresh();
                    OverlayService.refresh(MainActivity.this);
                    SplitController.launchPair(MainActivity.this);
                }
            });
            final TextView ratio = pill(R.string.open);
            ratio.setText("%" + Prefs.ratio(this, l, r));
            ratio.setContentDescription(getString(R.string.ratio_label));
            ratio.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Prefs.setRatio(MainActivity.this, l, r, Prefs.nextRatio(Prefs.ratio(MainActivity.this, l, r)));
                    renderPairs();
                    renderRatio();
                    OverlayService.refresh(MainActivity.this);
                }
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-2, -2);
            rp.rightMargin = dp(8);
            row.addView(ratio, rp);
            row.addView(open);
            ImageView home = iconBtn(R.drawable.ic_home, R.string.add_home, R.attr.dpAccent);
            home.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    toast(Shortcuts.pin(MainActivity.this, l, r) ? R.string.pin_sent : R.string.pin_unsupported);
                }
            });
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(40), dp(40));
            hp.leftMargin = dp(8);
            row.addView(home, hp);
            ImageView del = iconBtn(R.drawable.ic_trash, R.string.delete, R.attr.dpWarn);
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Prefs.removePair(MainActivity.this, l, r);
                    Shortcuts.disable(MainActivity.this, l, r);
                    Shortcuts.refreshDynamic(MainActivity.this);
                    renderPairs();
                }
            });
            LinearLayout.LayoutParams dpm = new LinearLayout.LayoutParams(dp(40), dp(40));
            dpm.leftMargin = dp(8);
            row.addView(del, dpm);
            box.addView(row);
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void slot(final int side, int ivId, int tvId, int histId) {
        String pkg = Prefs.side(this, side);
        ImageView iv = (ImageView) findViewById(ivId);
        TextView tv = (TextView) findViewById(tvId);
        if (pkg != null && AppList.installed(this, pkg)) {
            iv.setColorFilter(null);
            iv.setImageDrawable(AppList.icon(this, pkg));
            tv.setText(AppList.label(this, pkg));
        } else {
            iv.setImageResource(R.drawable.ic_plus);
            iv.setColorFilter(UiUtil.attr(this, R.attr.dpAccent));
            tv.setText("-");
        }
        LinearLayout hist = (LinearLayout) findViewById(histId);
        hist.removeAllViews();
        int added = 0;
        for (final String p : Prefs.history(this, side)) {
            if (!AppList.installed(this, p)) continue;
            View h = UiUtil.histItem(this, AppList.icon(this, p), dp(34), del[side]);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(44), dp(44));
            lp.rightMargin = dp(4);
            h.setLayoutParams(lp);
            h.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (del[side]) Prefs.removeHistory(MainActivity.this, side, p);
                    else Prefs.setSide(MainActivity.this, side, p);
                    refresh();
                    OverlayService.refresh(MainActivity.this);
                }
            });
            hist.addView(h);
            added++;
        }
        if (added == 0) del[side] = false;
        else {
            ImageView t = new ImageView(this);
            t.setImageResource(R.drawable.ic_trash);
            t.setColorFilter(UiUtil.attr(this, del[side] ? R.attr.dpWarn : R.attr.dpMuted));
            t.setBackgroundResource(R.drawable.bg_pill_glass);
            t.setPadding(dp(8), dp(8), dp(8), dp(8));
            t.setContentDescription(getString(R.string.history_remove));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
            lp.leftMargin = dp(6);
            lp.gravity = android.view.Gravity.CENTER_VERTICAL;
            t.setLayoutParams(lp);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    del[side] = !del[side];
                    if (del[side]) android.widget.Toast.makeText(MainActivity.this, R.string.hist_hint, android.widget.Toast.LENGTH_SHORT).show();
                    refresh();
                }
            });
            hist.addView(t);
        }
    }

    private void pickDialog(final int side) {
        final List<AppList.App> apps = AppList.listWithPanel(this);
        GridView gv = new GridView(this);
        gv.setNumColumns(GridView.AUTO_FIT);
        gv.setColumnWidth(dp(84));
        gv.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gv.setVerticalSpacing(dp(8));
        gv.setPadding(dp(8), dp(8), dp(8), dp(8));
        final Context ctx = this;
        gv.setAdapter(new BaseAdapter() {
            public int getCount() { return apps.size(); }
            public Object getItem(int i) { return apps.get(i); }
            public long getItemId(int i) { return i; }
            public View getView(int i, View v, ViewGroup p) {
                if (v == null) v = LayoutInflater.from(ctx).inflate(R.layout.item_app, p, false);
                AppList.App a = apps.get(i);
                ((ImageView) v.findViewById(R.id.iv_app)).setImageDrawable(AppList.icon(ctx, a.pkg));
                TextView t = (TextView) v.findViewById(R.id.tv_app);
                t.setText(a.label);
                t.setTextColor(UiUtil.attr(ctx, R.attr.dpText));
                return v;
            }
        });
        final AlertDialog dlg = new AlertDialog.Builder(this, UiUtil.dialogTheme(this))
                .setTitle(R.string.pick_title).setView(gv)
                .setNegativeButton(R.string.close, null).create();
        gv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                Prefs.setSide(MainActivity.this, side, apps.get(pos).pkg);
                dlg.dismiss();
                refresh();
                OverlayService.refresh(MainActivity.this);
            }
        });
        dlg.show();
    }

    private void logDialog() {
        TextView tv = new TextView(this);
        tv.setText(SplitLog.dump());
        tv.setTextSize(11);
        tv.setTypeface(Typeface.MONOSPACE);
        tv.setPadding(dp(16), dp(12), dp(16), dp(12));
        tv.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this, UiUtil.dialogTheme(this))
                .setTitle(R.string.log_title).setView(sv)
                .setPositiveButton(R.string.close, null)
                .setNeutralButton(R.string.clear, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { SplitLog.clear(); }
                }).show();
    }
}
