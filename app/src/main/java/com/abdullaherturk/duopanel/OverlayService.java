package com.abdullaherturk.duopanel;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.AdapterView;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import org.json.JSONObject;

public class OverlayService extends Service implements SplitController.Listener {
    private static OverlayService inst;
    private static final int TYPE_OVERLAY = Build.VERSION.SDK_INT >= 26 ? 2038 : 2002;

    private WindowManager wm;
    private Context tc;
    private Handler h;
    private View strip, bubble, popup;
    private int popupSide = -1;
    private static final int APPS = 2;
    private boolean popupDel;
    private WindowManager.LayoutParams stripLp;
    private View divBtn;
    private String divKey = "";
    private TextView tvClock, tvDay, tvDate, tvTemp, tvSpeed, tvDir;
    private ImageView ivLeft, ivRight, ivWeather, ivCompass;
    private View dotGps, wxBox;
    private LocationManager lm;
    private SensorManager sm;
    private Location lastLoc;
    private long lastFix, lastWx;
    private float azimuth = Float.NaN;
    private long lastClockMinute = Long.MIN_VALUE;
    private int lastSpeed = Integer.MIN_VALUE, lastGps = -1;
    private String lastDirection = "";
    private static final String[] DIRECTIONS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
    private static final String[] DIRECTIONS_TR = {"K", "KD", "D", "GD", "G", "GB", "B", "KB"};
    private final float[] sensorRotation = new float[9], sensorRemapped = new float[9], sensorOrientation = new float[3];

    /** Baska surecten (muzik paneli) ikiliyi geri getir */
    public static void requestResumePair(Context c) {
        try { c.sendBroadcast(new Intent(PanelReceiver.RESUME).setClass(c, PanelReceiver.class)); }
        catch (Exception e) { SplitLog.add("resume request failed: " + e); }
    }

    public static void start(Context c) {
        if (!(Prefs.strip(c) || Prefs.bubble(c) || Prefs.dividerSwap(c))) return;
        if (!Settings.canDrawOverlays(c)) return;
        try { c.startService(new Intent(c, OverlayService.class)); } catch (Exception e) { SplitLog.add("overlay start failed: " + e); }
    }

    /** Hava konumu degisti: hemen yeniden al */
    public static void weatherChanged() {
        if (inst == null) return;
        inst.lastWx = 0;
        inst.hasWx = false;
    }

    public static void refresh(Context c) {
        if (inst != null) inst.rebuild();
        else start(c);
    }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        inst = this;
        SplitLog.install(this);
        h = new Handler(getMainLooper());
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        sm = (SensorManager) getSystemService(SENSOR_SERVICE);
        SplitController.add(this);
    }

    @Override
    public int onStartCommand(Intent i, int f, int id) {
        rebuild();
        return START_STICKY;
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        rebuild();
    }

    @Override
    public void onDestroy() {
        SplitController.remove(this);
        removeAll();
        stopSensors();
        h.removeCallbacksAndMessages(null);
        inst = null;
        super.onDestroy();
    }

    // ---------- ayrac uzerindeki yer degistir dugmesi ----------
    private void removeDivider() {
        if (divBtn != null) { try { wm.removeView(divBtn); } catch (Exception e) { } divBtn = null; }
        divKey = "";
    }

    /**
     * Yuzen yer degistir dugmesi: bilgi seridi gorunurken seritteki dugme yeterli, bu gizlenir.
     * Serit kapaliysa gorunur; surukleyerek istenen yere tasinir (yeri hatirlanir), dokununca yer degistirir.
     */
    private void updateDivider() {
        android.graphics.Point p = SplitController.divider();
        boolean want = p != null && SplitController.isActive() && Prefs.dividerSwap(this) && !Prefs.strip(this);
        final int sx = Prefs.getLong(this, "swapX") > 0 ? (int) Prefs.getLong(this, "swapX") - 1 : -1;
        final int sy = Prefs.getLong(this, "swapY") > 0 ? (int) Prefs.getLong(this, "swapY") - 1 : -1;
        boolean custom = sx >= 0 && sy >= 0;
        String key = want ? (custom ? "c" + sx + "," + sy : p.x + "," + p.y) : "";
        if (key.equals(divKey)) return;
        removeDivider();
        if (!want) return;
        final ImageView b = new ImageView(tc);
        b.setImageResource(R.drawable.ic_swap);
        b.setColorFilter(UiUtil.attr(tc, R.attr.dpAccent));
        b.setBackgroundResource(R.drawable.bg_glass_circle);
        b.setPadding(dp(10), dp(10), dp(10), dp(10));
        b.setContentDescription(getString(R.string.swap_desc));
        int s = dp(44);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(s, s, TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        lp.x = custom ? Math.min(sx, dm.widthPixels - s) : p.x - s / 2;
        lp.y = custom ? Math.min(sy, dm.heightPixels - s) : p.y - s / 2;
        b.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int ox, oy; boolean moved;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY(); ox = lp.x; oy = lp.y; moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                        if (!moved && (Math.abs(mx) > dp(8) || Math.abs(my) > dp(8))) moved = true;
                        if (moved) {
                            lp.x = Math.max(0, Math.min(dm.widthPixels - v.getWidth(), ox + Math.round(mx)));
                            lp.y = Math.max(0, Math.min(dm.heightPixels - v.getHeight(), oy + Math.round(my)));
                            try { wm.updateViewLayout(v, lp); } catch (Exception ex) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) {
                            // yeni yeri hatirla (0 = varsayilan oldugu icin +1 ile saklanir)
                            Prefs.setLong(OverlayService.this, "swapX", lp.x + 1);
                            Prefs.setLong(OverlayService.this, "swapY", lp.y + 1);
                            divKey = "c" + lp.x + "," + lp.y;
                        } else SplitController.swapSides(OverlayService.this);
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(b, lp); divBtn = b; divKey = key; } catch (Exception e) { SplitLog.add("divider button failed: " + e); }
    }

    @Override
    public void onSplitChanged(boolean active) {
        if (bubble != null) bubble.setVisibility(active ? View.GONE : View.VISIBLE);
        if (strip != null) strip.setVisibility(active ? View.VISIBLE : View.GONE);
        if (active && strip != null) startSensors(); else stopSensors();
        if (!active) dismissPopup();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void removeAll() {
        dismissPopup();
        if (strip != null) { try { wm.removeView(strip); } catch (Exception e) { } strip = null; }
        stripLp = null;
        tvClock = tvDay = tvDate = tvTemp = tvSpeed = tvDir = null;
        ivLeft = ivRight = ivWeather = ivCompass = null;
        dotGps = wxBox = null;
        lastClockMinute = Long.MIN_VALUE;
        lastSpeed = Integer.MIN_VALUE;
        lastGps = -1;
        lastDirection = "";
        removeStripMenu();
        removeDivider();
        if (bubble != null) { try { wm.removeView(bubble); } catch (Exception e) { } bubble = null; }
        removeBubbleMenu();
    }

    private void rebuild() {
        removeAll();
        tc = new ContextThemeWrapper(this, UiUtil.theme(this));
        if (!Settings.canDrawOverlays(this)) { stopSelf(); return; }
        boolean s = Prefs.strip(this), b = Prefs.bubble(this);
        boolean sw = !s && Prefs.dividerSwap(this);   // serit yokken yuzen yer degistir dugmesi
        if (!s && !b && !sw) {
            // gosterilecek bir sey kalmadi: uyari mesaji gorunebilsin diye biraz bekleyip kapan
            h.removeCallbacks(tick);
            stopSensors();
            h.postDelayed(new Runnable() { public void run() { if (!Prefs.strip(OverlayService.this) && !Prefs.bubble(OverlayService.this)) stopSelf(); } }, 4000);
            return;
        }
        if (s) addStrip();
        if (b) addBubble();
        if (s && SplitController.isActive()) startSensors(); else stopSensors();
        h.removeCallbacks(tick);
        h.post(tick);
    }

    // ---------- serit ----------
    private void addStrip() {
        strip = LayoutInflater.from(tc).inflate(R.layout.strip, null);
        tvClock = (TextView) strip.findViewById(R.id.tv_clock);
        tvDay = (TextView) strip.findViewById(R.id.tv_day);
        tvDate = (TextView) strip.findViewById(R.id.tv_date);
        tvTemp = (TextView) strip.findViewById(R.id.tv_temp);
        tvSpeed = (TextView) strip.findViewById(R.id.tv_speed);
        tvDir = (TextView) strip.findViewById(R.id.tv_dir);
        ivLeft = (ImageView) strip.findViewById(R.id.iv_left);
        ivRight = (ImageView) strip.findViewById(R.id.iv_right);
        ivWeather = (ImageView) strip.findViewById(R.id.iv_weather);
        ivCompass = (ImageView) strip.findViewById(R.id.iv_compass);
        dotGps = strip.findViewById(R.id.dot_gps);
        wxBox = strip.findViewById(R.id.wx_container);
        wxBox.setClickable(true);
        wxBox.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dismissPopup();
                try {
                    startActivity(new Intent(OverlayService.this, WeatherActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
                } catch (Exception e) { SplitLog.add("weather dialog failed: " + e); }
            }
        });
        strip.findViewById(R.id.btn_left).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePopup(Prefs.LEFT); }
        });
        strip.findViewById(R.id.btn_right).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePopup(Prefs.RIGHT); }
        });
        strip.findViewById(R.id.btn_left).setVisibility(View.GONE);
        strip.findViewById(R.id.btn_right).setVisibility(View.GONE);
        strip.findViewById(R.id.btn_apps).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { togglePopup(APPS); }
        });
        final TextView ratioBtn = (TextView) strip.findViewById(R.id.btn_ratio);
        updateRatioLabel(ratioBtn);
        ratioBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String l = Prefs.side(OverlayService.this, Prefs.LEFT), r = Prefs.side(OverlayService.this, Prefs.RIGHT);
                if (l == null || r == null) return;
                Prefs.setRatio(OverlayService.this, l, r, Prefs.nextRatio(Prefs.ratio(OverlayService.this, l, r)));
                updateRatioLabel(ratioBtn);
                SplitService ss = SplitService.get();
                if (ss != null) ss.applyRatioDebounced(l, r);
            }
        });
        strip.findViewById(R.id.btn_swap).setVisibility(Prefs.dividerSwap(this) ? View.VISIBLE : View.GONE);
        strip.findViewById(R.id.btn_swap).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) { dismissPopup(); SplitController.swapSides(OverlayService.this); }
        });
        final DisplayMetrics dm = getResources().getDisplayMetrics();
        strip.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(dp(56), View.MeasureSpec.EXACTLY));
        final int sw = strip.getMeasuredWidth();
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(56), TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int sx = Prefs.stripX(this), sy = Prefs.stripY(this);
        lp.x = sx >= 0 ? Math.min(sx, Math.max(0, dm.widthPixels - sw)) : Math.max(0, (dm.widthPixels - sw) / 2);
        lp.y = sy >= 0 ? Math.min(sy, Math.max(0, dm.heightPixels - dp(56))) : dp(6);
        stripLp = lp;
        // seridi surukle; yerinde 0.8 sn basili tutunca ortala
        strip.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int ox, oy; boolean moved; long down;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY(); ox = lp.x; oy = lp.y; moved = false; down = e.getEventTime();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                        if (!moved && (Math.abs(mx) > dp(8) || Math.abs(my) > dp(8))) { moved = true; dismissPopup(); }
                        if (moved) {
                            lp.x = Math.max(0, Math.min(dm.widthPixels - strip.getWidth(), ox + Math.round(mx)));
                            lp.y = Math.max(0, Math.min(dm.heightPixels - strip.getHeight(), oy + Math.round(my)));
                            try { wm.updateViewLayout(strip, lp); } catch (Exception ex) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) Prefs.setStrip(OverlayService.this, lp.x, lp.y);
                        else if (e.getEventTime() - down > 700) showStripMenu();   // basili tut: Ortala / Gizle
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(strip, lp); } catch (Exception e) { SplitLog.add("strip add failed: " + e); strip = null; return; }
        updateIcons();
        lastClockMinute = Long.MIN_VALUE;
        tickOnce();
        if (hasWx) showWeather(wxTemp, wxCode, wxDay);
        strip.setVisibility(SplitController.isActive() ? View.VISIBLE : View.GONE);
    }

    /** Ayrac elle cekildiginde serideki oran yazisini gunceller */
    public static void dismissPopups() {
        final OverlayService o = inst;
        if (o == null) return;
        new Handler(android.os.Looper.getMainLooper()).post(new Runnable() { public void run() { o.dismissPopup(); o.removeBubbleMenu(); } });
    }

    /** Oto. surukleme sirasinda gelen dokunus kullanicinin degil: yok say */
    private boolean dragTouch(String what) {
        SplitService ss = SplitService.get();
        if (ss != null && ss.dragging()) { SplitLog.add("touch during ratio drag ignored: " + what); return true; }
        return false;
    }

    public static void refreshIcons() {
        final OverlayService o = inst;
        if (o == null) return;
        new Handler(android.os.Looper.getMainLooper()).post(new Runnable() { public void run() { o.updateIcons(); refreshRatio(); } });
    }

    public static void refreshRatio() {
        OverlayService o = inst;
        if (o == null || o.strip == null) return;
        TextView t = (TextView) o.strip.findViewById(R.id.btn_ratio);
        if (t != null) o.updateRatioLabel(t);
    }

    private void updateRatioLabel(TextView t) {
        String l = Prefs.side(this, Prefs.LEFT), r = Prefs.side(this, Prefs.RIGHT);
        t.setText("%" + (l == null || r == null ? 50 : Prefs.ratio(this, l, r)));
    }

    public void updateIcons() {
        if (strip == null) return;
        setIcon(ivLeft, Prefs.side(this, Prefs.LEFT));
        setIcon(ivRight, Prefs.side(this, Prefs.RIGHT));
    }

    private void setIcon(ImageView iv, String pkg) {
        Drawable d = pkg == null ? null : AppList.icon(this, pkg);
        if (d != null) { iv.setColorFilter(null); iv.setImageDrawable(d); }
        else { iv.setImageResource(R.drawable.ic_plus); iv.setColorFilter(UiUtil.attr(tc, R.attr.dpAccent)); }
    }

    private final Runnable tick = new Runnable() {
        public void run() {
            tickOnce();
            updateDivider();
            boolean animated = (strip != null && strip.getVisibility() == View.VISIBLE) || divBtn != null;
            h.postDelayed(this, animated ? 250 : 1000);
        }
    };

    private void tickOnce() {
        if (strip == null || strip.getVisibility() != View.VISIBLE) return;
        long t = System.currentTimeMillis();
        long minute = t / 60000;
        if (minute != lastClockMinute) {
            lastClockMinute = minute;
            Date now = new Date(t);
            Locale locale = Locale.getDefault();
            tvClock.setText(new SimpleDateFormat("HH:mm", locale).format(now));
            tvDay.setText(new SimpleDateFormat("EEEE", locale).format(now));
            tvDate.setText(new SimpleDateFormat("d MMMM", locale).format(now));
        }
        boolean fix = lastLoc != null && t - lastFix < 6000;
        int gps = fix ? 1 : 0;
        if (gps != lastGps) {
            lastGps = gps;
            dotGps.setBackgroundResource(fix ? R.drawable.bg_dot_ok : R.drawable.bg_dot_off);
        }
        int speed = -1;
        if (fix && lastLoc.hasSpeed()) {
            int kmh = Math.round(lastLoc.getSpeed() * 3.6f);
            speed = kmh < 2 ? 0 : kmh;
        }
        if (speed != lastSpeed) {
            lastSpeed = speed;
            tvSpeed.setText(speed < 0 ? "--" : String.valueOf(speed));
        }
        float hd = Float.NaN;
        if (fix && lastLoc.hasBearing() && lastLoc.hasSpeed() && lastLoc.getSpeed() > 1.5f) hd = lastLoc.getBearing();
        else if (!Float.isNaN(azimuth)) hd = azimuth;
        if (!Float.isNaN(hd)) ivCompass.setRotation(hd);
        String direction = Float.isNaN(hd) ? "--" : dirName(hd);
        if (!direction.equals(lastDirection)) { lastDirection = direction; tvDir.setText(direction); }
        if (!locOn && t - lastLocTry > 10000) registerLocation();
        double[] manual = Prefs.wxLatLon(this);
        if (manual == null && lastLoc == null && !hasWx && t - startedAt > 20000 && t - lastIpWx > 15 * 60 * 1000L) {
            lastIpWx = t;
            fetchWeatherByIp();
        }
        if ((manual != null || lastLoc != null) && t - lastWx > 15 * 60 * 1000L) {
            lastWx = t;
            if (manual != null) fetchWeather(manual[0], manual[1]);
            else fetchWeather(lastLoc.getLatitude(), lastLoc.getLongitude());
        }
    }

    private String dirName(float deg) {
        String[] names = "tr".equals(Locale.getDefault().getLanguage()) ? DIRECTIONS_TR : DIRECTIONS;
        return names[Math.round(((deg % 360 + 360) % 360) / 45f) % 8];
    }

    // ---------- konum / pusula ----------
    private final LocationListener ll = new LocationListener() {
        public void onLocationChanged(Location l) {
            if (l == null) return;
            lastLoc = l;
            if (LocationManager.GPS_PROVIDER.equals(l.getProvider())) lastFix = System.currentTimeMillis();
            else if (lastFix == 0) lastFix = 0;
        }
        public void onStatusChanged(String p, int s, Bundle b) { }
        public void onProviderEnabled(String p) { }
        public void onProviderDisabled(String p) { }
    };

    private final SensorEventListener sl = new SensorEventListener() {
        public void onSensorChanged(SensorEvent e) {
            float[] r = sensorRotation, m = sensorRemapped, o = sensorOrientation;
            SensorManager.getRotationMatrixFromVector(r, e.values);
            // ekran dondurulmusse eksenleri ekrana gore cevir (yatay tablet vb.)
            int ax = SensorManager.AXIS_X, ay = SensorManager.AXIS_Y;
            switch (wm.getDefaultDisplay().getRotation()) {
                case 1: ax = SensorManager.AXIS_Y; ay = SensorManager.AXIS_MINUS_X; break;        // 90
                case 2: ax = SensorManager.AXIS_MINUS_X; ay = SensorManager.AXIS_MINUS_Y; break;  // 180
                case 3: ax = SensorManager.AXIS_MINUS_Y; ay = SensorManager.AXIS_X; break;        // 270
            }
            SensorManager.remapCoordinateSystem(r, ax, ay, m);
            SensorManager.getOrientation(m, o);
            // manyetik kuzey -> cografi kuzey
            float az = (float) Math.toDegrees(o[0]) + declination();
            az = (az % 360 + 360) % 360;
            // titremeyi azalt (aci farki uzerinden yumusatma)
            if (Float.isNaN(azimuth)) azimuth = az;
            else {
                float d = ((az - azimuth + 540) % 360) - 180;
                azimuth = ((azimuth + d * 0.2f) % 360 + 360) % 360;
            }
        }
        public void onAccuracyChanged(Sensor s, int a) { }
    };

    private boolean locOn, sensorOn;
    private long lastLocTry, startedAt = System.currentTimeMillis(), lastIpWx;

    private float decl = Float.NaN;
    private long declAt;

    /** Konuma gore manyetik sapma (Ardahan'da yaklasik +6 derece) */
    private float declination() {
        if (lastLoc == null) return Float.isNaN(decl) ? 0f : decl;
        long now = System.currentTimeMillis();
        if (Float.isNaN(decl) || now - declAt > 30 * 60 * 1000L) {
            decl = new android.hardware.GeomagneticField((float) lastLoc.getLatitude(), (float) lastLoc.getLongitude(),
                    (float) lastLoc.getAltitude(), now).getDeclination();
            declAt = now;
        }
        return decl;
    }

    private void startSensors() {
        registerLocation();
        if (!sensorOn) {
            try {
                Sensor rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
                if (rv != null) { sm.registerListener(sl, rv, SensorManager.SENSOR_DELAY_UI); sensorOn = true; }
            } catch (Exception e) { }
        }
    }

    /** Konum dinleyicisini kaydeder; izin yoksa sonra tekrar denenir (tick) */
    private void registerLocation() {
        if (locOn) return;
        lastLocTry = System.currentTimeMillis();
        if (!PermissionHelper.location(this)) return;
        StringBuilder used = new StringBuilder();
        try {
            List<String> all = lm.getAllProviders();
            for (String p : new String[]{"passive", LocationManager.NETWORK_PROVIDER, "fused", LocationManager.GPS_PROVIDER}) {
                if (!all.contains(p)) continue;
                try {
                    Location l = lm.getLastKnownLocation(p);
                    if (l != null && (lastLoc == null || l.getTime() > lastLoc.getTime())) lastLoc = l;
                } catch (Exception e) { }
                long interval = LocationManager.GPS_PROVIDER.equals(p) ? 1000 : 30000;
                try { lm.requestLocationUpdates(p, interval, 0, ll, getMainLooper()); used.append(p).append(' '); }
                catch (Exception e) { }
            }
        } catch (Exception e) { SplitLog.add("location failed: " + e); }
        locOn = used.length() > 0;
        SplitLog.add("location: providers=" + used + " last=" + (lastLoc == null ? "-" : lastLoc.getProvider())
                + " bg=" + PermissionHelper.backgroundLocation(this));
    }

    private void stopSensors() {
        try { lm.removeUpdates(ll); } catch (Exception e) { }
        try { sm.unregisterListener(sl); } catch (Exception e) { }
        locOn = false;
        sensorOn = false;
    }

    /** GPS/ag konumu hic gelmezse hava durumu icin IP'den yaklasik konum */
    private void fetchWeatherByIp() {
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    c = (HttpURLConnection) new URL("https://ipapi.co/json/").openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    c.setRequestProperty("User-Agent", "DuoPanel");
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject o = new JSONObject(sb.toString());
                    final double lat = o.getDouble("latitude"), lon = o.getDouble("longitude");
                    SplitLog.add("weather by IP: " + o.optString("city"));
                    h.post(new Runnable() { public void run() { fetchWeather(lat, lon); } });
                } catch (Exception e) {
                    SplitLog.add("IP location failed: " + e);
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    // ---------- hava ----------
    private void fetchWeather(final double lat, final double lon) {
        new Thread(new Runnable() {
            public void run() {
                HttpURLConnection c = null;
                try {
                    URL u = new URL("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon
                            + "&current=temperature_2m,weather_code,is_day&timezone=auto");
                    c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = br.readLine()) != null) sb.append(line);
                    br.close();
                    JSONObject cur = new JSONObject(sb.toString()).getJSONObject("current");
                    final int temp = (int) Math.round(cur.getDouble("temperature_2m"));
                    final int code = cur.getInt("weather_code");
                    final boolean day = cur.optInt("is_day", 1) == 1;
                    h.post(new Runnable() {
                        public void run() { showWeather(temp, code, day); }
                    });
                } catch (Exception e) {
                    SplitLog.add("weather failed: " + e);
                    lastWx = System.currentTimeMillis() - 14 * 60 * 1000L; // 1 dk sonra tekrar dene
                } finally {
                    if (c != null) c.disconnect();
                }
            }
        }).start();
    }

    private boolean hasWx, wxDay;
    private int wxTemp, wxCode;

    private void showWeather(int temp, int code, boolean day) {
        hasWx = true; wxTemp = temp; wxCode = code; wxDay = day;
        if (strip == null) return;
        int res;
        if (code == 0) res = day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        else if (code <= 3) res = R.drawable.ic_wx_cloud;
        else if (code == 45 || code == 48) res = R.drawable.ic_wx_fog;
        else if (code >= 95) res = R.drawable.ic_wx_storm;
        else if ((code >= 71 && code <= 77) || code == 85 || code == 86) res = R.drawable.ic_wx_snow;
        else res = R.drawable.ic_wx_rain;
        ivWeather.setImageResource(res);
        tvTemp.setText(temp + "°");
        wxBox.setVisibility(View.VISIBLE);
    }

    // ---------- yuzen simge ----------
    private void addBubble() {
        bubble = LayoutInflater.from(tc).inflate(R.layout.bubble, null);
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                dp(96), dp(96), TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Prefs.bubbleX(this);
        lp.y = Prefs.bubbleY(this);
        bubble.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int sx, sy; boolean moved; long down;
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = e.getRawX(); dy = e.getRawY(); sx = lp.x; sy = lp.y; moved = false; down = e.getEventTime();
                        removeBubbleMenu();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float mx = e.getRawX() - dx, my = e.getRawY() - dy;
                        if (Math.abs(mx) > dp(6) || Math.abs(my) > dp(6)) moved = true;
                        if (moved) {
                            lp.x = Math.max(0, sx + Math.round(mx));
                            lp.y = Math.max(0, sy + Math.round(my));
                            try { wm.updateViewLayout(bubble, lp); } catch (Exception ex) { }
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (moved) Prefs.setBubble(OverlayService.this, lp.x, lp.y);
                        else if (e.getEventTime() - down >= 600) showBubbleMenu(lp);   // basili tut: kaldir
                        else SplitController.resumePair(OverlayService.this);
                        return true;
                }
                return false;
            }
        });
        try { wm.addView(bubble, lp); } catch (Exception e) { SplitLog.add("bubble add failed: " + e); bubble = null; return; }
        bubble.setVisibility(SplitController.isActive() ? View.GONE : View.VISIBLE);
    }

    /** Kisa bilgi kutusu (arka planda Toast engellenebildigi icin kendi kutumuz) */
    private void showNotice(int text) {
        final TextView t = new TextView(tc);
        t.setText(text);
        t.setTextColor(UiUtil.attr(tc, R.attr.dpText));
        t.setTextSize(15);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bg_glass_r22);
        t.setPadding(dp(22), dp(14), dp(22), dp(14));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(80);
        try { wm.addView(t, lp); } catch (Exception e) { return; }
        h.postDelayed(new Runnable() {
            public void run() { try { wm.removeView(t); } catch (Exception e) { } }
        }, 3500);
    }

    // ---------- serit: basili tutunca "Ortala" / "Gizle" ----------
    private View stripMenu;
    private final Runnable hideStripMenu = new Runnable() { public void run() { removeStripMenu(); } };

    private void removeStripMenu() {
        h.removeCallbacks(hideStripMenu);
        if (stripMenu != null) { try { wm.removeView(stripMenu); } catch (Exception e) { } stripMenu = null; }
    }

    private TextView menuPill(int text, int bg, int color) {
        TextView t = new TextView(tc);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(bg);
        t.setPadding(dp(20), 0, dp(20), 0);
        t.setMinHeight(dp(48));
        return t;
    }

    private void showStripMenu() {
        if (strip == null || stripLp == null) return;
        removeStripMenu();
        dismissPopup();
        android.widget.LinearLayout box = new android.widget.LinearLayout(tc);
        box.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        box.setBackgroundResource(R.drawable.bg_glass_r22);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView center = menuPill(R.string.strip_center, R.drawable.bg_pill_glass, UiUtil.attr(tc, R.attr.dpText));
        center.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                removeStripMenu();
                Prefs.setStrip(OverlayService.this, -1, -1);
                DisplayMetrics dm = getResources().getDisplayMetrics();
                stripLp.x = Math.max(0, (dm.widthPixels - strip.getWidth()) / 2);
                stripLp.y = dp(6);
                try { wm.updateViewLayout(strip, stripLp); } catch (Exception ex) { }
            }
        });
        TextView hide = menuPill(R.string.strip_hide, R.drawable.bg_badge_pill, 0xFFFFFFFF);
        hide.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                removeStripMenu();
                Prefs.set(OverlayService.this, "strip", false);
                showNotice(R.string.strip_hidden);
                SplitLog.add("strip hidden by long press");
                rebuild();
            }
        });
        // DuoPanel ayarlari: panel ekrandayken Tbox'in yan cubugundaki simge ayarlari acmayabiliyor
        TextView settings = menuPill(R.string.strip_settings, R.drawable.bg_pill_glass, UiUtil.attr(tc, R.attr.dpText));
        settings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                removeStripMenu();
                try { startActivity(new Intent(OverlayService.this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (Exception ex) { SplitLog.add("settings open failed: " + ex); }
            }
        });
        box.addView(center);
        android.widget.LinearLayout.LayoutParams sp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        sp.leftMargin = dp(8);
        box.addView(settings, sp);
        android.widget.LinearLayout.LayoutParams hp = new android.widget.LinearLayout.LayoutParams(-2, -2);
        hp.leftMargin = dp(8);
        box.addView(hide, hp);
        box.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) { removeStripMenu(); return true; }
                return false;
            }
        });
        DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int mw = box.getMeasuredWidth();
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = Math.max(dp(8), Math.min(dm.widthPixels - mw - dp(8), stripLp.x + strip.getWidth() / 2 - mw / 2));
        if (stripLp.y < dm.heightPixels / 2) lp.y = stripLp.y + dp(64);
        else { lp.gravity = Gravity.BOTTOM | Gravity.START; lp.y = dm.heightPixels - stripLp.y + dp(8); }
        try { wm.addView(box, lp); stripMenu = box; } catch (Exception e) { SplitLog.add("strip menu failed: " + e); return; }
        h.postDelayed(hideStripMenu, 4000);
    }

    // ---------- yuzen simge: basili tutunca "Kaldir" ----------
    private View bubbleMenu;
    private final Runnable hideBubbleMenu = new Runnable() { public void run() { removeBubbleMenu(); } };

    private void removeBubbleMenu() {
        h.removeCallbacks(hideBubbleMenu);
        if (bubbleMenu != null) { try { wm.removeView(bubbleMenu); } catch (Exception e) { } bubbleMenu = null; }
    }

    private void showBubbleMenu(WindowManager.LayoutParams blp) {
        removeBubbleMenu();
        TextView t = new TextView(tc);
        t.setText(R.string.bubble_remove);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(15);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.bg_badge_pill);
        t.setPadding(dp(20), 0, dp(20), 0);
        t.setMinHeight(dp(48));
        t.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                removeBubbleMenu();
                Prefs.set(OverlayService.this, "bubble", false);
                showNotice(R.string.bubble_removed);
                SplitLog.add("bubble removed by long press");
                rebuild();
            }
        });
        t.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) { removeBubbleMenu(); return true; }
                return false;
            }
        });
        DisplayMetrics dm = getResources().getDisplayMetrics();
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, dp(48), TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        boolean rightSpace = blp.x + dp(96) + dp(150) < dm.widthPixels;
        lp.x = rightSpace ? blp.x + dp(104) : Math.max(0, blp.x - dp(150));
        lp.y = blp.y + dp(24);
        try { wm.addView(t, lp); bubbleMenu = t; } catch (Exception e) { SplitLog.add("bubble menu failed: " + e); return; }
        h.postDelayed(hideBubbleMenu, 4000);
    }

    // ---------- gecmis / tum uygulamalar acilir penceresi ----------
    private int outsideSide = -1;
    private long outsideAt;

    private void togglePopup(int side) {
        // dis dokunusla az once kapandiysa ayni dugmeye basilmis demektir: yeniden acma
        if (outsideSide == side && android.os.SystemClock.uptimeMillis() - outsideAt < 400) { outsideSide = -1; return; }
        boolean same = popup != null && popupSide == side;
        dismissPopup();
        if (!same) showPopup(side);
    }

    private void dismissPopup() {
        if (popup != null) { try { wm.removeView(popup); } catch (Exception e) { } popup = null; }
        popupSide = -1;
    }

    private void pick(int side, String pkg) {
        Prefs.setSide(this, side, pkg);
        dismissPopup();
        updateIcons();
        SplitController.launchPair(this);
    }

    private void openApp(String pkg) {
        dismissPopup();
        SplitService ss = SplitService.get();
        if (ss != null) { ss.openFullscreen(pkg); return; }
        try {
            Intent i = AppList.launchIntent(this, pkg);
            if (i == null) return;
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            SplitLog.add("open app " + pkg);
        } catch (Exception e) { SplitLog.add("open app failed " + pkg + ": " + e); }
    }

    private void showPopup(final int side) {
        popupSide = side;
        popup = LayoutInflater.from(tc).inflate(R.layout.popup_side, null);
        ((TextView) popup.findViewById(R.id.tv_popup_title)).setText(
                side == Prefs.LEFT ? R.string.popup_left : side == Prefs.RIGHT ? R.string.popup_right : R.string.all_apps);
        popupDel = false;
        final GridView gv = (GridView) popup.findViewById(R.id.gv_all);
        final List<AppList.App> apps = new ArrayList<AppList.App>();
        if (side == APPS) {
            // tum uygulamalar: secilen uygulama normal sekilde acilir
            popup.findViewById(R.id.hs_history).setVisibility(View.GONE);
            popup.findViewById(R.id.tv_no_history).setVisibility(View.GONE);
            popup.findViewById(R.id.btn_all_apps).setVisibility(View.GONE);
            apps.addAll(AppList.list(this));
            gv.setAdapter(new AppAdapter(apps));
            gv.setVisibility(View.VISIBLE);
        } else {
            renderPopupHistory(side);
        }
        popup.findViewById(R.id.btn_all_apps).setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (gv.getVisibility() == View.VISIBLE) { gv.setVisibility(View.GONE); return; }
                if (apps.isEmpty()) {
                    apps.addAll(AppList.list(OverlayService.this));
                    gv.setAdapter(new AppAdapter(apps));
                }
                gv.setVisibility(View.VISIBLE);
            }
        });
        gv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> a, View v, int pos, long id) {
                if (side == APPS) openApp(apps.get(pos).pkg);
                else pick(side, apps.get(pos).pkg);
            }
        });
        popup.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_OUTSIDE) {
                    outsideSide = popupSide;
                    outsideAt = android.os.SystemClock.uptimeMillis();
                    dismissPopup();
                    return true;
                }
                return false;
            }
        });
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int w = Math.min(dp(560), Math.round(dm.widthPixels * 0.48f));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                w, WindowManager.LayoutParams.WRAP_CONTENT, TYPE_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        int stX = stripLp != null ? stripLp.x : 0, stY = stripLp != null ? stripLp.y : 0;
        int stW = strip != null ? strip.getWidth() : 0;
        lp.x = Math.max(dp(8), Math.min(dm.widthPixels - w - dp(8), stX + stW / 2 - w / 2));
        if (stY < dm.heightPixels / 2) lp.y = stY + dp(64);
        else { lp.gravity = Gravity.BOTTOM | Gravity.START; lp.y = dm.heightPixels - stY + dp(8); }
        try { wm.addView(popup, lp); } catch (Exception e) { SplitLog.add("popup failed: " + e); popup = null; popupSide = -1; }
    }

    private void renderPopupHistory(final int side) {
        if (popup == null) return;
        android.widget.LinearLayout ll = (android.widget.LinearLayout) popup.findViewById(R.id.ll_history);
        ll.removeAllViews();
        int added = 0;
        for (final String pkg : Prefs.history(this, side)) {
            if (!AppList.installed(this, pkg)) continue;
            View iv = UiUtil.histItem(tc, AppList.icon(this, pkg), dp(52), popupDel);
            android.widget.LinearLayout.LayoutParams p = new android.widget.LinearLayout.LayoutParams(dp(60), dp(60));
            p.rightMargin = dp(8);
            iv.setLayoutParams(p);
            iv.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (popupDel) { Prefs.removeHistory(OverlayService.this, side, pkg); renderPopupHistory(side); }
                    else pick(side, pkg);
                }
            });
            ll.addView(iv);
            added++;
        }
        if (added == 0) popupDel = false;
        else {
            ImageView t = new ImageView(tc);
            t.setImageResource(R.drawable.ic_trash);
            t.setColorFilter(UiUtil.attr(tc, popupDel ? R.attr.dpWarn : R.attr.dpMuted));
            t.setBackgroundResource(R.drawable.bg_glass_r14);
            t.setPadding(dp(11), dp(11), dp(11), dp(11));
            t.setContentDescription(getString(R.string.history_remove));
            android.widget.LinearLayout.LayoutParams p = new android.widget.LinearLayout.LayoutParams(dp(46), dp(46));
            p.leftMargin = dp(6);
            p.gravity = Gravity.CENTER_VERTICAL;
            t.setLayoutParams(p);
            t.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    popupDel = !popupDel;
                    if (popupDel) android.widget.Toast.makeText(OverlayService.this, R.string.hist_hint, android.widget.Toast.LENGTH_SHORT).show();
                    renderPopupHistory(side);
                }
            });
            ll.addView(t);
        }
        popup.findViewById(R.id.hs_history).setVisibility(added > 0 ? View.VISIBLE : View.GONE);
        popup.findViewById(R.id.tv_no_history).setVisibility(added > 0 ? View.GONE : View.VISIBLE);
    }

    private class AppAdapter extends BaseAdapter {
        private final List<AppList.App> data;
        AppAdapter(List<AppList.App> d) { data = d; }
        public int getCount() { return data.size(); }
        public Object getItem(int i) { return data.get(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View v, ViewGroup p) {
            if (v == null) v = LayoutInflater.from(tc).inflate(R.layout.item_app, p, false);
            AppList.App a = data.get(i);
            ((ImageView) v.findViewById(R.id.iv_app)).setImageDrawable(AppList.icon(OverlayService.this, a.pkg));
            ((TextView) v.findViewById(R.id.tv_app)).setText(a.label);
            return v;
        }
    }
}
