package com.abdullaherturk.duopanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.util.List;

/**
 * Muzik paneli: bolunmus ekranin bir tarafinda, arka planda calan muzik uygulamasinin
 * sarkisi, kapagi, kontrolleri ve sarki listeleri. Cam gorunum, acik/koyu tema.
 */
public class MusicActivity extends Activity implements MusicHub.Listener {
    private final Handler h = new Handler();
    private MusicHub hub;
    private boolean light;
    private int themeAccent, accent, text, muted;

    private FrameLayout root, body;
    private ImageView bg;
    private View scrim;

    private FeatherImageView art;
    private ImageView bPrev, bPlay, bNext, bShuffle, bRepeat;
    private TextView title, artist, chip, tPos, tDur, empty, crumb;
    private SeekBar seek;
    private LinearLayout tabs, libCard;
    private ListView list;
    private SongAdapter adapter;

    private int lastW, lastH;
    private boolean hadAccess;
    private String artKey;
    private boolean artDone;
    private Uri artUri;
    private android.graphics.drawable.Drawable lastBg;
    private Bitmap artBmp;
    private boolean dragging, resumed;

    @Override
    protected void onCreate(Bundle b) {
        setTheme(UiUtil.theme(this));
        super.onCreate(b);
        SplitLog.install(this);
        light = UiUtil.isLight(this);
        themeAccent = UiUtil.attr(this, R.attr.dpAccent);
        accent = themeAccent;
        text = UiUtil.attr(this, R.attr.dpText);
        muted = UiUtil.attr(this, R.attr.dpMuted);
        hub = MusicHub.get(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(UiUtil.attr(this, R.attr.dpBg));
        bg = new ImageView(this);
        bg.setScaleType(ImageView.ScaleType.CENTER_CROP);
        root.addView(bg, new FrameLayout.LayoutParams(-1, -1));
        scrim = new View(this);
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));
        body = new FrameLayout(this);
        body.setPadding(dp(14), dp(14), dp(14), dp(14));
        root.addView(body, new FrameLayout.LayoutParams(-1, -1));
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            public void onLayoutChange(View v, int l, int t, int r, int bt, int ol, int ot, int or, int ob) {
                final int w = r - l, ht = bt - t;
                if (w > 0 && ht > 0 && (w != lastW || ht != lastH)) {
                    lastW = w; lastH = ht;
                    int[] at = new int[2];
                    v.getLocationOnScreen(at);
                    SplitLog.add("music: panel " + w + "x" + ht + " at " + at[0] + "," + at[1]);
                    h.post(new Runnable() { public void run() { build(); } });
                }
            }
        });
        setContentView(root);
        paintBackground();
    }

    // ---------------- yasam dongusu ----------------

    private final SharedPreferences.OnSharedPreferenceChangeListener prefs = new SharedPreferences.OnSharedPreferenceChangeListener() {
        public void onSharedPreferenceChanged(SharedPreferences sp, String k) {
            if ("themeMode".equals(k)) h.post(new Runnable() { public void run() { recreate(); } });
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        getSharedPreferences("duo", 4).registerOnSharedPreferenceChangeListener(prefs);
        if (UiUtil.isLight(this) != light) { recreate(); return; }
        hub.add(this);
        if (MusicHub.hasAccess(this) != hadAccess) build();
        hub.refresh();
        bindLib();   // duraklatilmisken degisen liste kopyasini tazele
        h.removeCallbacks(tick);
        h.post(tick);
    }

    @Override
    protected void onPause() {
        resumed = false;
        cancelArtworkLoads();
        hub.remove(this);
        h.removeCallbacks(tick);
        getSharedPreferences("duo", 4).unregisterOnSharedPreferenceChangeListener(prefs);
        super.onPause();
    }

    /** Tani: panelin pencere modu (bolunmus / tam ekran / serbest pencere) */
    private String winMode(Configuration c) {
        String t = String.valueOf(c);
        int i = t.indexOf("mWindowingMode=");
        if (i < 0) {
            try { return ((Boolean) Activity.class.getMethod("isInMultiWindowMode").invoke(this)) ? "multi" : "full"; }
            catch (Throwable e) { return "?"; }
        }
        int e = t.indexOf(' ', i);
        return t.substring(i + 15, e < 0 ? Math.min(t.length(), i + 30) : e);
    }
    private String lastMode;

    @Override
    protected void onStop() {
        SplitLog.add("music: panel stopped");
        super.onStop();
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        String m = winMode(c);
        if (!m.equals(lastMode)) { SplitLog.add("music: panel window mode " + m); lastMode = m; }
        if (UiUtil.isLight(this) != light) recreate();
    }

    private MusicArt.Request primaryArt, fallbackArt;
    private int artGeneration;

    private void cancelArtworkLoads() {
        if (primaryArt != null) primaryArt.cancel();
        if (fallbackArt != null) fallbackArt.cancel();
        primaryArt = fallbackArt = null;
        if (!artDone) { artUri = null; artKey = null; }
    }

    @Override
    protected void onDestroy() {
        resumed = false;
        h.removeCallbacksAndMessages(null);
        cancelArtworkLoads();
        hub.remove(this);
        artKey = null;
        artBmp = null;
        artGeneration = 0;
        lastBg = null;
        if (art != null) art.setImageDrawable(null);
        if (bg != null) bg.setImageDrawable(null);
        super.onDestroy();
    }

    private int tickN;
    private final Runnable tick = new Runnable() {
        public void run() {
            updateProgress();
            // tema baska surecte (serit / ayarlar) degisebilir: 2 sn'de bir denetle
            if (++tickN % 4 == 0 && UiUtil.isLight(MusicActivity.this) != light) { recreate(); return; }
            if (resumed) h.postDelayed(this, 500);
        }
    };

    public void onMusicChanged() { bindNow(); }
    public void onSectionsChanged() {
        bindLib();
        // liste yeni yuklendiyse calan sarkiyi ortaya getir (kapaga dokunulduysa ya da kendiliginden)
        if (!hub.songs().isEmpty()) {
            // yalnizca kaydir; tekrar baska listeye yonlendirme (dongu olmasin)
            final boolean asked = pendingScroll;
            h.post(new Runnable() { public void run() { scrollToCurrent(asked, false); } });
            pendingScroll = false;
        }
    }

    // ---------------- calan sarkiya kaydirma ----------------

    private boolean pendingScroll;
    private long listTouchedAt;

    /**
     * Listede calan sarkiyi ortaya getirir. Kullanici son birkac saniyede listeyi kaydirdiysa kendiliginden kaydirmaz.
     * force: kapaga dokunuldu (her durumda kaydir); bulunamazsa Siradakiler'e gecilir.
     */
    private void scrollToCurrent(boolean force, boolean fromTap) {
        if (list == null || libCard == null || libCard.getVisibility() != View.VISIBLE) return;
        if (!force && android.os.SystemClock.uptimeMillis() - listTouchedAt < 5000) return;
        List<MusicHub.Song> ss = shown;
        int idx = -1;
        for (int i = 0; i < ss.size(); i++) if (!ss.get(i).folder && isCurrent(ss.get(i))) { idx = i; break; }
        if (idx < 0) {
            if (fromTap) {
                if ((!MusicHub.QUEUE.equals(hub.section()) || hub.folderTitle() != null) && hub.queueSize() > 0) {
                    // acik listede yok: Siradakiler'e gec, yuklenince orada bul
                    pendingScroll = true;
                    hub.selectSection(MusicHub.QUEUE);
                } else {
                    android.widget.Toast.makeText(this, R.string.music_not_in_list, android.widget.Toast.LENGTH_SHORT).show();
                }
            }
            return;
        }
        int rowH = dp(60);
        int off = Math.max(0, list.getHeight() / 2 - rowH / 2);
        int first = list.getFirstVisiblePosition(), last = list.getLastVisiblePosition();
        if (Math.abs(idx - first) > 40 && Math.abs(idx - last) > 40) list.setSelectionFromTop(idx, off);   // cok uzaksa atla
        else list.smoothScrollToPositionFromTop(idx, off, 350);
    }

    // ---------------- yerlesim ----------------

    private void build() {
        body.removeAllViews();
        art = null; title = null; libCard = null; list = null; tabs = null; seek = null; chip = null; bPlay = null; bShuffle = null; bRepeat = null;
        hadAccess = MusicHub.hasAccess(this);
        if (lastW == 0) return;
        if (!hadAccess) { buildAccess(); return; }
        float d = getResources().getDisplayMetrics().density;
        int w = lastW - dp(28), ht = lastH - dp(28);
        boolean wide = w > ht * 1.15f && w / d >= 560;

        LinearLayout now = card();
        libCard = card();
        buildLib(libCard);

        if (wide) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            int colW = Math.round(Math.min(w * 0.46f, Math.max(dp(300), w * 0.38f)));
            // kapak disindaki icerik ~280dp: kapak kalan yuksekligi asmasin
            int artPx = Math.min(colW - dp(40), ht - dp(285));
            buildNow(now, false, Math.max(dp(90), Math.min(artPx, dp(400))));
            now.setGravity(Gravity.CENTER);
            row.addView(now, new LinearLayout.LayoutParams(colW, -1));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1f);
            lp.leftMargin = dp(14);
            row.addView(libCard, lp);
            body.addView(row, new FrameLayout.LayoutParams(-1, -1));
        } else {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            boolean compact = ht / d < 560;
            int artPx = compact ? dp(92) : Math.max(dp(110), Math.min(w - dp(40), Math.min(Math.round(ht * 0.38f), dp(420))));
            buildNow(now, compact, artPx);
            col.addView(now, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
            lp.topMargin = dp(12);
            col.addView(libCard, lp);
            body.addView(col, new FrameLayout.LayoutParams(-1, -1));
            // liste icin yer kalmadiysa yalnizca calma kontrolleri gorunsun
            libCard.post(new Runnable() {
                public void run() { if (libCard != null && libCard.getHeight() > 0 && libCard.getHeight() < dp(130)) libCard.setVisibility(View.GONE); }
            });
        }
        artKey = null;
        bindNow();
        bindLib();
    }

    /**
     * DuoPanel ayarlarini acan kucuk dugme. Panel ekrandayken Tbox'in yan cubugundaki DuoPanel simgesi
     * "uygulama zaten acik" sayilip ayarlari acmayabiliyor; buradan her zaman acilir.
     */
    private void addSettingsButton(int gravity) {
        ImageView g = new ImageView(this);
        g.setImageResource(R.drawable.ic_settings);
        g.setColorFilter(muted);
        g.setPadding(dp(9), dp(9), dp(9), dp(9));
        g.setBackground(oval(light ? 0x66FFFFFF : 0x22FFFFFF, 0));
        g.setContentDescription(getString(R.string.music_settings));
        g.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                android.content.Intent i = new android.content.Intent(MusicActivity.this, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                try { startActivity(i); } catch (Exception e) { SplitLog.add("music: settings open failed: " + e); }
            }
        });
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(38), dp(38), gravity);
        lp.setMargins(dp(8), dp(8), dp(8), dp(8));
        body.addView(g, lp);
    }

    private void buildAccess() {
        LinearLayout c = card();
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(24), dp(24), dp(24), dp(24));
        ImageView ic = new ImageView(this);
        ic.setImageResource(R.drawable.ic_music_app);
        c.addView(ic, new LinearLayout.LayoutParams(dp(64), dp(64)));
        TextView t = label(getString(R.string.music_access), 16, text, true);
        t.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-2, -2);
        tp.topMargin = dp(14);
        c.addView(t, tp);
        TextView s = label(getString(R.string.music_access_d), 13, muted, false);
        s.setGravity(Gravity.CENTER);
        c.addView(s, new LinearLayout.LayoutParams(-2, -2));
        TextView b = label(getString(R.string.open), 15, Color.WHITE, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(round(accent, 40));
        b.setTextColor(onAccent());
        b.setPadding(dp(28), 0, dp(28), 0);
        b.setMinHeight(dp(48));
        b.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { MusicHub.openAccess(MusicActivity.this); } });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-2, -2);
        bp.topMargin = dp(16);
        c.addView(b, bp);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -2, Gravity.CENTER);
        body.addView(c, lp);
    }

    /** Calan sarki karti: kapak, ad, sanatci, kaynak secici, ilerleme, kontroller */
    private void buildNow(LinearLayout c, boolean compact, int artPx) {
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        art = new FeatherImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        final float r = dp(compact ? 14 : 24);
        art.setOutlineProvider(new ViewOutlineProvider() {
            public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), r); }
        });
        art.setClipToOutline(true);
        art.setElevation(dp(6));
        art.setOnTouchListener(new ArtSwipe());

        title = label("", compact ? 17 : 20, text, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        artist = label("", 14, muted, false);
        artist.setSingleLine(true);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        chip = label("", 13, accent, true);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(14), 0, dp(10), 0);
        chip.setMinHeight(dp(34));
        chip.setCompoundDrawablePadding(dp(4));
        chip.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { sourceDialog(); } });

        if (compact) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(art, new LinearLayout.LayoutParams(artPx, artPx));
            LinearLayout info = new LinearLayout(this);
            info.setOrientation(LinearLayout.VERTICAL);
            info.addView(title);
            info.addView(artist);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.topMargin = dp(6);
            info.addView(chip, cp);
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, -2, 1f);
            ip.leftMargin = dp(14);
            row.addView(info, ip);
            c.addView(row, new LinearLayout.LayoutParams(-1, -2));
        } else {
            c.addView(art, new LinearLayout.LayoutParams(artPx, artPx));
            title.setGravity(Gravity.CENTER);
            artist.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
            tp.topMargin = dp(16);
            c.addView(title, tp);
            c.addView(artist, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-2, -2);
            cp.topMargin = dp(10);
            c.addView(chip, cp);
        }

        seek = new SeekBar(this);
        seek.setMax(1000);
        seek.setPadding(dp(6), 0, dp(6), 0);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) {
                if (user) tPos.setText(time(hub.duration() * p / 1000));
            }
            public void onStartTrackingTouch(SeekBar s) { dragging = true; }
            public void onStopTrackingTouch(SeekBar s) {
                dragging = false;
                if (hub.canSeek()) hub.seek(hub.duration() * s.getProgress() / 1000);
            }
        });
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(28));
        sp.topMargin = dp(compact ? 10 : 14);
        c.addView(seek, sp);
        LinearLayout times = new LinearLayout(this);
        times.setOrientation(LinearLayout.HORIZONTAL);
        tPos = label("0:00", 12, muted, false);
        tDur = label("0:00", 12, muted, false);
        tDur.setGravity(Gravity.END);
        times.addView(tPos, new LinearLayout.LayoutParams(0, -2, 1f));
        times.addView(tDur, new LinearLayout.LayoutParams(0, -2, 1f));
        times.setPadding(dp(10), 0, dp(10), 0);
        c.addView(times, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);
        int small = dp(compact ? 50 : 56), big = dp(compact ? 62 : 72);
        bPrev = ctlButton(R.drawable.ic_prev, small, false);
        bPlay = ctlButton(R.drawable.ic_play, big, true);
        bNext = ctlButton(R.drawable.ic_next, small, false);
        bPrev.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hub.prev(); } });
        bPlay.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hub.playPause(); } });
        bNext.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hub.next(); } });
        int mini = dp(compact ? 40 : 44);
        bShuffle = modeButton(R.drawable.ic_shuffle, mini);
        bRepeat = modeButton(R.drawable.ic_repeat, mini);
        bShuffle.setContentDescription(getString(R.string.music_shuffle));
        bRepeat.setContentDescription(getString(R.string.music_repeat));
        bShuffle.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hub.toggleShuffle(); } });
        bRepeat.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                // uygulama disaridan tekrar ayarini kabul etmiyorsa: dugme yalnizca durumu gosterir
                if (hub.repeatUnsupported()) {
                    android.widget.Toast.makeText(MusicActivity.this, getString(R.string.music_repeat_unsupported, AppList.label(MusicActivity.this, hub.pkg())), android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                hub.cycleRepeat();
            }
        });
        int gap = dp(compact ? 14 : 18);
        LinearLayout.LayoutParams p0 = new LinearLayout.LayoutParams(mini, mini);
        p0.rightMargin = gap;
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(small, small);
        LinearLayout.LayoutParams p2 = new LinearLayout.LayoutParams(big, big);
        p2.leftMargin = dp(compact ? 16 : 22); p2.rightMargin = dp(compact ? 16 : 22);
        LinearLayout.LayoutParams p3 = new LinearLayout.LayoutParams(small, small);
        LinearLayout.LayoutParams p4 = new LinearLayout.LayoutParams(mini, mini);
        p4.leftMargin = gap;
        ctl.addView(bShuffle, p0);
        ctl.addView(bPrev, p1);
        ctl.addView(bPlay, p2);
        ctl.addView(bNext, p3);
        ctl.addView(bRepeat, p4);
        LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(-1, -2);
        cl.topMargin = dp(compact ? 6 : 10);
        c.addView(ctl, cl);
    }

    /** Liste karti: bolum sekmeleri + duz sarki listesi */
    private void buildLib(LinearLayout c) {
        c.setPadding(dp(12), dp(12), dp(12), dp(8));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(tabs);
        c.addView(hs, new LinearLayout.LayoutParams(-1, -2));
        // klasor icindeyken geri satiri: "‹ Calma listesi adi"
        crumb = label("", 14, text, true);
        crumb.setSingleLine(true);
        crumb.setEllipsize(TextUtils.TruncateAt.END);
        crumb.setGravity(Gravity.CENTER_VERTICAL);
        crumb.setMinHeight(dp(40));
        crumb.setPadding(dp(8), 0, dp(8), 0);
        crumb.setCompoundDrawablePadding(dp(6));
        crumb.setVisibility(View.GONE);
        crumb.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { hub.back(); } });
        LinearLayout.LayoutParams crp = new LinearLayout.LayoutParams(-1, -2);
        crp.topMargin = dp(6);
        c.addView(crumb, crp);
        FrameLayout box = new FrameLayout(this);
        list = new ListView(this);
        list.setDivider(null);
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setVerticalScrollBarEnabled(false);
        list.setClipToPadding(false);
        list.setPadding(0, dp(6), 0, dp(6));
        adapter = new SongAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                if (pos < shown.size()) hub.playSong(shown.get(pos));
            }
        });
        list.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            public void onScrollStateChanged(android.widget.AbsListView v, int st) {
                if (st == SCROLL_STATE_TOUCH_SCROLL || st == SCROLL_STATE_FLING) listTouchedAt = android.os.SystemClock.uptimeMillis();
            }
            public void onScroll(android.widget.AbsListView v, int a, int b, int c) { }
        });
        // klasore basili tut: sekme olarak sabitle (or. Home > Yeniden dinleyin > "Listem")
        list.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            public boolean onItemLongClick(AdapterView<?> a, View v, int pos, long id) {
                if (pos >= shown.size() || !shown.get(pos).folder) return false;
                MusicHub.Song f = shown.get(pos);
                android.widget.Toast.makeText(MusicActivity.this,
                        hub.pin(f) ? getString(R.string.music_pinned, f.title) : getString(R.string.music_pin_exists, f.title),
                        android.widget.Toast.LENGTH_LONG).show();
                return true;
            }
        });
        box.addView(list, new FrameLayout.LayoutParams(-1, -1));
        empty = label("", 13, muted, false);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), 0, dp(16), 0);
        box.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = dp(8);
        c.addView(box, lp);
    }

    // ---------------- verileri bagla ----------------

    private void bindNow() {
        if (title == null || !resumed) return;
        MediaMetadata m = hub.metadata();
        String pkg = hub.pkg();
        String t = m == null ? null : first(m, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String a = m == null ? null : first(m, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM);
        if (t == null) {
            title.setText(pkg == null ? R.string.music_pick_app : R.string.music_nothing);
            artist.setText(pkg == null ? "" : getString(R.string.music_press_play, AppList.label(this, pkg)));
        } else {
            title.setText(t);
            artist.setText(a == null ? "" : a);
        }
        chip.setText(pkg == null ? getString(R.string.music_app) : AppList.label(this, pkg));
        boolean playing = hub.isPlaying();
        bPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        boolean has = hub.controller() != null;
        bPrev.setAlpha(has ? 1f : 0.45f);
        bNext.setAlpha(has ? 1f : 0.45f);
        bPrev.setEnabled(has);
        bNext.setEnabled(has);
        bindModes(has);
        seek.setEnabled(hub.canSeek());

        // kapak
        Bitmap bm = MusicArt.metadataBitmap(m);
        final Uri u = bm == null ? MusicArt.metadataUri(m) : null;
        // muzik uygulamalari ayni sarki icin bilgileri sik sik yeniden gonderir (her seferinde yeni resim nesnesiyle):
        // kapak ve arka plan yalnizca sarki degisince guncellenir, yoksa panel "parlar"
        final String key = (t == null ? "" : t) + "|" + (a == null ? "" : a);
        boolean newSong = !key.equals(artKey);
        if (newSong && artKey != null) h.postDelayed(new Runnable() { public void run() { scrollToCurrent(false, false); } }, 400);
        if (newSong) { cancelArtworkLoads(); artKey = key; artDone = false; artUri = null; }
        if (!artDone) {
            if (bm != null) { setArt(bm); artDone = true; }
            else if (u != null) {
                if (!u.equals(artUri)) {
                    artUri = u;
                    primaryArt = MusicArt.load(this, u, dp(320), new MusicArt.Done() {
                        public void done(Bitmap b) {
                            if (b != null && key.equals(artKey) && !artDone) { setArt(b); artDone = true; }
                        }
                    });
                }
            }
            if (newSong && !artDone) {
                // asil kapak gelene kadar: listede (acik liste / calma sirasi) ayni sarkinin kucuk resmi varsa hemen onu goster
                MusicHub.Song hit = t == null ? null : hub.findSong(t, a);
                if (hit != null && hit.icon != null) setArt(hit.icon);
                else if (hit != null && hit.iconUri != null) {
                    fallbackArt = MusicArt.load(this, hit.iconUri, dp(320), new MusicArt.Done() {
                        public void done(Bitmap b) { if (b != null && key.equals(artKey) && !artDone) setArt(b); }
                    });
                    if (u == null) setArt(null);
                } else if (u == null) setArt(null);
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateProgress();
    }

    private void setArt(Bitmap b) {
        int generation = b == null ? 0 : b.getGenerationId();
        boolean changed = b != artBmp || generation != artGeneration;
        artBmp = b;
        artGeneration = generation;
        if (art != null) {
            if (b != null) {
                art.setBackground(null);
                art.setFeather(false);
                art.setClipToOutline(true);
                art.setElevation(dp(6));
                art.setPadding(0, 0, 0, 0);
                art.setScaleType(ImageView.ScaleType.CENTER_CROP);
                art.setImageBitmap(b);
            } else {
                GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF6366F1, 0xFF22D3EE});
                art.setBackground(g);
                art.setFeather(false);
                art.setClipToOutline(true);
                art.setElevation(dp(6));
                art.setScaleType(ImageView.ScaleType.FIT_CENTER);
                art.setImageResource(R.drawable.ic_music);
                art.post(new Runnable() {
                    public void run() { if (art != null) { int p = art.getWidth() / 4; art.setPadding(p, p, p, p); } }
                });
            }
        }
        if (changed) {
            int ac = MusicArt.accent(b, light);
            accent = ac != 0 ? ac : themeAccent;
            paintBackground();
        }
        tint();
    }

    /** Arka plan: kapagin bulanik hali + tema perdesi; kapak yoksa vurgu renginden yumusak gecis */
    private void paintBackground() {
        Bitmap blur = MusicArt.blur(artBmp);
        int base = UiUtil.attr(this, R.attr.dpBg);
        if (blur != null) {
            // eski arka plandan yenisine yumusak gecis (araya bos/karanlik kare girmez)
            android.graphics.drawable.Drawable nd = new android.graphics.drawable.BitmapDrawable(getResources(), blur);
            if (lastBg != null) {
                android.graphics.drawable.TransitionDrawable td =
                        new android.graphics.drawable.TransitionDrawable(new android.graphics.drawable.Drawable[]{lastBg, nd});
                td.setCrossFadeEnabled(true);
                bg.setImageDrawable(td);
                td.startTransition(500);
            } else bg.setImageDrawable(nd);
            lastBg = nd;
            scrim.setBackgroundColor(light ? 0x99EEF2F6 : 0xA30A0D12);
        } else {
            lastBg = null;
            bg.setImageDrawable(null);
            int a = mix(accent, base, light ? 0.78f : 0.82f);
            GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{a, base, mix(0xFF6366F1, base, light ? 0.85f : 0.88f)});
            scrim.setBackground(g);
        }
    }

    private void tint() {
        ColorStateList ac = ColorStateList.valueOf(accent);
        if (chip != null) {
            chip.setTextColor(accent);
            chip.setBackground(round(withAlpha(accent, light ? 0x26 : 0x2E), 40));
            android.graphics.drawable.Drawable dn = getDrawable(R.drawable.ic_chevron_down).mutate();
            dn.setTint(accent);
            dn.setBounds(0, 0, dp(16), dp(16));
            chip.setCompoundDrawables(null, null, dn, null);
        }
        if (seek != null) {
            seek.setProgressTintList(ac);
            seek.setThumbTintList(ac);
            seek.setProgressBackgroundTintList(ColorStateList.valueOf(light ? 0x33101828 : 0x40FFFFFF));
        }
        if (bPlay != null) {
            bPlay.setBackground(oval(accent, 0));
            bPlay.setColorFilter(onAccent());
            bindModes(hub.controller() != null);
        }
        bindLib();
    }

    private void updateProgress() {
        if (seek == null) return;
        long d = hub.duration();
        boolean show = d > 0;
        seek.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        tPos.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        tDur.setVisibility(show ? View.VISIBLE : View.INVISIBLE);
        if (!show || dragging) return;
        long p = hub.position();
        seek.setProgress((int) (p * 1000 / d));
        tPos.setText(time(p));
        tDur.setText(time(d));
    }

    private void bindLib() {
        if (tabs == null) return;
        tabs.removeAllViews();
        List<MusicHub.Section> ss = hub.sections();
        String cur = hub.section();
        for (final MusicHub.Section s : ss) {
            boolean sel = s.id.equals(cur);
            TextView t = label(s.title, 13, sel ? accent : muted, true);
            t.setGravity(Gravity.CENTER);
            t.setSingleLine(true);
            t.setMinHeight(dp(36));
            t.setPadding(dp(14), 0, dp(14), 0);
            t.setBackground(sel ? round(withAlpha(accent, light ? 0x26 : 0x2E), 40) : pillGlass());
            t.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { pendingScroll = false; hub.selectSection(s.id); } });
            if (s.pinned) {
                // sabitlenmis sekme: basili tutunca kaldir
                t.setOnLongClickListener(new View.OnLongClickListener() {
                    public boolean onLongClick(View v) {
                        // yanlislikla kaldirilmasin: onay sor
                        new AlertDialog.Builder(MusicActivity.this, UiUtil.dialogTheme(MusicActivity.this))
                                .setMessage(getString(R.string.music_unpin_ask, s.title))
                                .setPositiveButton(R.string.music_unpin_yes, new android.content.DialogInterface.OnClickListener() {
                                    public void onClick(android.content.DialogInterface d, int w) {
                                        hub.unpin(s.id);
                                        android.widget.Toast.makeText(MusicActivity.this, getString(R.string.music_unpinned, s.title), android.widget.Toast.LENGTH_SHORT).show();
                                    }
                                })
                                .setNegativeButton(R.string.close, null).show();
                        return true;
                    }
                });
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(8);
            tabs.addView(t, lp);
        }
        String ft = hub.folderTitle();
        crumb.setVisibility(ft == null ? View.GONE : View.VISIBLE);
        if (ft != null) {
            crumb.setText(ft);
            crumb.setTextColor(accent);
            android.graphics.drawable.Drawable bk = getDrawable(R.drawable.ic_chevron_down).mutate();
            bk.setTint(accent);
            bk.setBounds(0, 0, dp(18), dp(18));
            crumb.setCompoundDrawables(new RotatedDrawable(bk, 90), null, null, null);
        }
        shown.clear();
        shown.addAll(hub.songs());
        boolean none = shown.isEmpty();
        empty.setVisibility(none ? View.VISIBLE : View.GONE);
        if (none) {
            boolean q = MusicHub.QUEUE.equals(cur);
            empty.setText(hub.pkg() == null ? R.string.music_pick_app
                    : q && hub.libraryFailed() && hub.controller() == null ? R.string.music_nothing
                    : q ? R.string.music_queue_empty : R.string.music_list_empty);
        }
        adapter.notifyDataSetChanged();
    }

    // ---------------- sarki listesi ----------------

    /** Listede gosterilen sarkilarin kopyasi: yalnizca ana is parcaciginda, notifyDataSetChanged ile birlikte degisir */
    private final java.util.List<MusicHub.Song> shown = new java.util.ArrayList<MusicHub.Song>();

    private class SongAdapter extends BaseAdapter {
        public int getCount() { return shown.size(); }
        public Object getItem(int i) { return shown.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int i, View v, ViewGroup p) {
            Holder hd;
            if (v == null) {
                LinearLayout row = new LinearLayout(MusicActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(6), dp(6), dp(8), dp(6));
                row.setMinimumHeight(dp(60));
                TypedValue tv = new TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
                row.setBackgroundResource(tv.resourceId);
                hd = new Holder();
                hd.icon = new ImageView(MusicActivity.this);
                hd.icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                hd.icon.setOutlineProvider(new ViewOutlineProvider() {
                    public void getOutline(View x, Outline o) { o.setRoundRect(0, 0, x.getWidth(), x.getHeight(), dp(10)); }
                });
                hd.icon.setClipToOutline(true);
                row.addView(hd.icon, new LinearLayout.LayoutParams(dp(46), dp(46)));
                LinearLayout col = new LinearLayout(MusicActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                hd.title = label("", 15, text, true);
                hd.title.setSingleLine(true);
                hd.title.setEllipsize(TextUtils.TruncateAt.END);
                hd.sub = label("", 12, muted, false);
                hd.sub.setSingleLine(true);
                hd.sub.setEllipsize(TextUtils.TruncateAt.END);
                col.addView(hd.title);
                col.addView(hd.sub);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
                cp.leftMargin = dp(12);
                row.addView(col, cp);
                hd.bars = new ImageView(MusicActivity.this);
                hd.bars.setImageResource(R.drawable.ic_bars);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(18), dp(18));
                bp.leftMargin = dp(8);
                row.addView(hd.bars, bp);
                row.setTag(hd);
                v = row;
            } else hd = (Holder) v.getTag();
            List<MusicHub.Song> list = shown;
            if (i >= list.size()) return v;
            MusicHub.Song s = list.get(i);
            hd.title.setText(s.title);
            hd.sub.setText(s.sub);
            hd.sub.setVisibility(s.sub == null || s.sub.length() == 0 ? View.GONE : View.VISIBLE);
            boolean active = !s.folder && isCurrent(s);
            hd.title.setTextColor(active ? accent : text);
            hd.title.setTextSize(active ? 16 : 15);
            hd.sub.setTextColor(active ? withAlpha(accent, 0xCC) : muted);
            if (s.folder) {
                // klasor (calma listesi, album): saga bakan ok
                hd.bars.setImageResource(R.drawable.ic_chevron_down);
                hd.bars.setRotation(-90f);
                hd.bars.setColorFilter(muted);
                hd.bars.setVisibility(View.VISIBLE);
            } else {
                hd.bars.setImageResource(R.drawable.ic_bars);
                hd.bars.setRotation(0f);
                hd.bars.setColorFilter(accent);
                hd.bars.setVisibility(active ? View.VISIBLE : View.GONE);
            }
            if (active) {
                // calan sarki: seffaf vurgu zemini, ince kenar ve solda dar serit (yazilar okunur kalir)
                v.setBackground(new RowHighlight(withAlpha(accent, light ? 0x24 : 0x2E), withAlpha(accent, 0x80), accent));
            } else {
                TypedValue tv = new TypedValue();
                getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
                v.setBackgroundResource(tv.resourceId);
            }
            hd.icon.setBackground(round(light ? 0x14101828 : 0x1FFFFFFF, 10));
            // yer tutucu: soluk nota; kapak gelince MusicArt filtreyi ve boslugu kaldirir
            hd.icon.setColorFilter(muted);
            hd.icon.setPadding(dp(12), dp(12), dp(12), dp(12));
            MusicArt.into(MusicActivity.this, hd.icon, s.icon, s.iconUri, dp(96), R.drawable.ic_music);
            return v;
        }
    }

    private static class Holder { ImageView icon, bars; TextView title, sub; }

    /** Secili / calan muzik uygulamasini acar (bolunmus ekranda panelin yerine gecer; yuzen simgeyle geri donulur) */
    private void openMusicApp() {
        String p = hub.pkg();
        if (p == null) return;
        android.content.Intent i = getPackageManager().getLaunchIntentForPackage(p);
        if (i == null) return;
        SplitLog.add("music: opening " + p + " (poster long-press)");
        try { startActivity(i); } catch (Exception e) { SplitLog.add("music: open failed: " + e); }
    }

    /** Calan sarki satiri: seffaf dolgu + kenar + solda 4dp serit; sinirlar her cizimde satirin olcusunden alinir */
    private class RowHighlight extends android.graphics.drawable.Drawable {
        private final android.graphics.Paint fill = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint line = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint bar = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF r = new android.graphics.RectF();
        RowHighlight(int f, int l, int b) {
            fill.setColor(f);
            line.setColor(l);
            line.setStyle(android.graphics.Paint.Style.STROKE);
            line.setStrokeWidth(Math.max(1, dp(1)));
            bar.setColor(b);
        }
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float half = line.getStrokeWidth() / 2f, rad = dp(14);
            r.set(b.left + half, b.top + half, b.right - half, b.bottom - half);
            c.drawRoundRect(r, rad, rad, fill);
            c.drawRoundRect(r, rad, rad, line);
            r.set(b.left + dp(3), b.top + dp(12), b.left + dp(7), b.bottom - dp(12));
            c.drawRoundRect(r, dp(2), dp(2), bar);
        }
        public void setAlpha(int a) { }
        public void setColorFilter(android.graphics.ColorFilter f) { }
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** Kapagi sola kaydir: sonraki sarki, saga kaydir: onceki sarki. Kapak parmagi izler, birakinca kayar ya da geri doner */
    private class ArtSwipe implements View.OnTouchListener {
        private float downX, downY;
        private boolean dragging, longDone;
        private android.view.VelocityTracker vt;
        private View cur;
        // basili tut: secili muzik uygulamasini ac
        private final Runnable longPress = new Runnable() {
            public void run() {
                if (dragging || cur == null) return;
                longDone = true;
                cur.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                cur.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                openMusicApp();
            }
        };

        public boolean onTouch(View v, android.view.MotionEvent e) {
            if (hub.pkg() == null) return false;
            boolean canSwipe = hub.controller() != null;
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    downX = e.getRawX(); downY = e.getRawY(); dragging = false; longDone = false;
                    cur = v;
                    v.animate().cancel();
                    v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(450).start();
                    h.postDelayed(longPress, 550);
                    vt = android.view.VelocityTracker.obtain();
                    vt.addMovement(e);
                    return true;
                case android.view.MotionEvent.ACTION_MOVE: {
                    if (vt != null) vt.addMovement(e);
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (Math.abs(dx) > dp(10) || Math.abs(dy) > dp(10)) h.removeCallbacks(longPress);
                    if (longDone) return true;
                    if (canSwipe && !dragging && Math.abs(dx) > dp(10) && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) {
                        v.setTranslationX(dx);
                        v.setRotation(dx / v.getWidth() * 6f);
                        v.setAlpha(1f - Math.min(0.6f, Math.abs(dx) / v.getWidth()));
                    }
                    return true;
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    h.removeCallbacks(longPress);
                    v.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
                    if (longDone) { longDone = false; dragging = false; return true; }
                    float dx = e.getRawX() - downX;
                    float vx = 0;
                    if (vt != null) { vt.addMovement(e); vt.computeCurrentVelocity(1000); vx = vt.getXVelocity(); vt.recycle(); vt = null; }
                    boolean go = dragging && e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                            && (Math.abs(dx) > v.getWidth() * 0.28f || (Math.abs(vx) > dp(900) && Math.abs(dx) > dp(24)));
                    if (go) {
                        final boolean next = dx < 0;
                        final View art = v;
                        final float w = v.getWidth();
                        v.animate().translationX(next ? -w * 1.2f : w * 1.2f).alpha(0f).setDuration(160).withEndAction(new Runnable() {
                            public void run() {
                                if (next) hub.next(); else hub.prev();
                                art.setTranslationX(next ? w * 0.6f : -w * 0.6f);
                                art.setRotation(0f);
                                art.animate().translationX(0f).alpha(1f).setDuration(260)
                                        .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                            }
                        }).start();
                    } else {
                        v.animate().translationX(0f).rotation(0f).alpha(1f).setDuration(200)
                                .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
                        // kaydirma degil, tek dokunus: listede calan sarkiya git
                        if (!dragging && e.getActionMasked() == android.view.MotionEvent.ACTION_UP
                                && Math.abs(dx) < dp(10) && Math.abs(e.getRawY() - downY) < dp(10)) {
                            scrollToCurrent(true, true);
                        }
                    }
                    dragging = false;
                    return true;
                }
            }
            return false;
        }
    }

    /** Geri oku icin donmus cizim (asagi oku 90 derece cevirir) */
    private static class RotatedDrawable extends android.graphics.drawable.Drawable {
        private final android.graphics.drawable.Drawable d;
        private final float deg;
        RotatedDrawable(android.graphics.drawable.Drawable d, float deg) { this.d = d; this.deg = deg; setBounds(d.getBounds()); }
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            c.save();
            c.rotate(deg, b.exactCenterX(), b.exactCenterY());
            d.setBounds(b);
            d.draw(c);
            c.restore();
        }
        public void setAlpha(int a) { d.setAlpha(a); }
        public void setColorFilter(android.graphics.ColorFilter f) { d.setColorFilter(f); }
        public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        public int getIntrinsicWidth() { return d.getBounds().width(); }
        public int getIntrinsicHeight() { return d.getBounds().height(); }
    }

    private boolean isCurrent(MusicHub.Song s) {
        if (s.queueId >= 0) return s.queueId == hub.activeQueueId();
        MediaMetadata m = hub.metadata();
        if (m == null) return false;
        // uygulamalar ayni sarkiyi listede yol onekiyle ("liste/.../videoId"), calarken yalin kimlikle verebilir
        String id = m.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (id != null && s.id != null) {
            if (id.equals(s.id) || s.id.endsWith("/" + id) || id.endsWith("/" + s.id) || lastSeg(id).equals(lastSeg(s.id))) return true;
        }
        CharSequence tc = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (tc == null || !tc.toString().equals(s.title)) return false;
        CharSequence ac = m.getText(MediaMetadata.METADATA_KEY_ARTIST);
        return ac == null || s.sub == null || s.sub.length() == 0 || s.sub.contains(ac.toString()) || ac.toString().contains(s.sub);
    }

    private static String lastSeg(String id) {
        int i = id.lastIndexOf('/');
        return i >= 0 && i < id.length() - 1 ? id.substring(i + 1) : id;
    }

    // ---------------- kaynak secimi ----------------

    private void sourceDialog() {
        final List<String> apps = MusicHub.musicApps(this);
        apps.add(0, null);
        final String cur = Prefs.musicPkg(this);
        final Context ctx = this;
        GridView gv = new GridView(this);
        gv.setNumColumns(GridView.AUTO_FIT);
        gv.setColumnWidth(dp(88));
        gv.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        gv.setVerticalSpacing(dp(8));
        gv.setPadding(dp(8), dp(8), dp(8), dp(8));
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
                t.setTextColor(sel ? themeAccent : UiUtil.attr(ctx, R.attr.dpText));
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
                hub.choose(apps.get(pos));
            }
        });
        dlg.show();
    }

    // ---------------- yardimcilar ----------------

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setPadding(dp(18), dp(18), dp(18), dp(14));
        GradientDrawable g = new GradientDrawable();
        g.setColor(light ? 0x9EFFFFFF : 0x5C161D29);
        g.setStroke(Math.max(1, dp(1)), light ? 0x8CFFFFFF : 0x24FFFFFF);
        g.setCornerRadius(dp(26));
        c.setBackground(g);
        return c;
    }

    private GradientDrawable pillGlass() {
        GradientDrawable g = new GradientDrawable();
        g.setColor(light ? 0x66FFFFFF : 0x1AFFFFFF);
        g.setStroke(Math.max(1, dp(1)), light ? 0x1F101828 : 0x1FFFFFFF);
        g.setCornerRadius(dp(40));
        return g;
    }

    private ImageView ctlButton(int icon, int size, boolean primary) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = size / 4;
        b.setPadding(pad, pad, pad, pad);
        if (primary) { b.setBackground(oval(accent, 0)); b.setColorFilter(onAccent()); b.setElevation(dp(4)); }
        else { b.setBackground(oval(light ? 0x99FFFFFF : 0x24FFFFFF, light ? 0x1F101828 : 0x1FFFFFFF)); b.setColorFilter(text); }
        b.setClickable(true);
        return b;
    }

    /** Karistir / tekrarla: acikken vurgu renginde ve hafif zeminli, kapaliyken soluk */
    private ImageView modeButton(int icon, int size) {
        ImageView b = new ImageView(this);
        b.setImageResource(icon);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = size / 4;
        b.setPadding(pad, pad, pad, pad);
        b.setClickable(true);
        return b;
    }

    private void bindModes(boolean has) {
        if (bShuffle == null) return;
        bShuffle.setAlpha(has ? 1f : 0.4f);
        bRepeat.setAlpha(has ? 1f : 0.4f);
        bShuffle.setEnabled(has);
        bRepeat.setEnabled(has);
        boolean sh = hub.shuffleOn();
        bShuffle.setColorFilter(sh ? accent : muted);
        bShuffle.setBackground(sh ? oval(withAlpha(accent, light ? 0x26 : 0x2E), 0) : null);
        // tekrar: uygulama kendi durum simgesini veriyorsa onu goster (kapali / tumu / tek dogru gorunur)
        if (hub.repeatJustDisabled) {
            hub.repeatJustDisabled = false;
            android.widget.Toast.makeText(this, getString(R.string.music_repeat_unsupported, AppList.label(this, hub.pkg())), android.widget.Toast.LENGTH_LONG).show();
        }
        int rm = hub.repeatMode();
        bRepeat.setImageResource(rm == 2 ? R.drawable.ic_repeat_one : R.drawable.ic_repeat);
        bRepeat.setColorFilter(rm != 0 ? accent : muted);
        bRepeat.setBackground(rm != 0 ? oval(withAlpha(accent, light ? 0x26 : 0x2E), 0) : null);
    }

    private GradientDrawable oval(int color, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(color);
        if (stroke != 0) g.setStroke(Math.max(1, dp(1)), stroke);
        return g;
    }

    private GradientDrawable round(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int onAccent() {
        float l = (0.299f * Color.red(accent) + 0.587f * Color.green(accent) + 0.114f * Color.blue(accent)) / 255f;
        return l > 0.62f ? 0xFF04222A : 0xFFFFFFFF;
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        t.setIncludeFontPadding(true);
        return t;
    }

    private static String first(MediaMetadata m, String... keys) {
        for (String k : keys) {
            CharSequence v = m.getText(k);
            if (v != null && v.length() > 0) return v.toString();
        }
        return null;
    }

    private static String time(long ms) {
        long s = Math.max(0, ms / 1000);
        long hh = s / 3600, mm = (s % 3600) / 60, ss = s % 60;
        return hh > 0 ? String.format("%d:%02d:%02d", hh, mm, ss) : String.format("%d:%02d", mm, ss);
    }

    private static int withAlpha(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }

    private static int mix(int a, int b, float t) {
        return Color.rgb(Math.round(Color.red(a) * (1 - t) + Color.red(b) * t),
                Math.round(Color.green(a) * (1 - t) + Color.green(b) * t),
                Math.round(Color.blue(a) * (1 - t) + Color.blue(b) * t));
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
