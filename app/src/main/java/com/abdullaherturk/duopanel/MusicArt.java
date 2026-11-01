package com.abdullaherturk.duopanel;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Kapak yukleme, bulaniklastirma ve kapaktan vurgu rengi */
public class MusicArt {
    private static final int MAX_ENCODED_BYTES = 12 * 1024 * 1024;
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(4 * 1024 * 1024) {
        protected int sizeOf(String k, Bitmap b) { return b.getAllocationByteCount(); }
    };
    private static final ThreadPoolExecutor pool = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<Runnable>(32));
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, Pending> pending = new HashMap<>();
    private static WeakReference<Context> registeredContext = new WeakReference<>(null);
    private static int cacheGeneration;
    private static final ComponentCallbacks2 memory = new ComponentCallbacks2() {
        public void onConfigurationChanged(Configuration c) { }
        public void onLowMemory() { onTrimMemory(TRIM_MEMORY_COMPLETE); }
        public void onTrimMemory(int level) {
            synchronized (pending) {
                cacheGeneration++;
                if (level >= TRIM_MEMORY_UI_HIDDEN || level == TRIM_MEMORY_RUNNING_CRITICAL) cache.evictAll();
                else if (level >= TRIM_MEMORY_RUNNING_LOW) cache.trimToSize(cache.size() / 2);
            }
        }
    };

    public interface Done { void done(Bitmap b); }

    /** Cancelling releases the screen callback even if an I/O operation is already running. */
    public static final class Request {
        private Done callback;
        private Pending owner;

        private Request(Done callback) { this.callback = callback; }

        public void cancel() {
            synchronized (pending) {
                callback = null;
                if (owner != null) {
                    owner.requests.remove(this);
                    if (owner.requests.isEmpty() && pool.remove(owner)) pending.remove(owner.key);
                    owner = null;
                }
            }
        }

        private void deliver(final Bitmap bitmap) {
            Runnable delivery = new Runnable() {
                public void run() {
                    Done done;
                    synchronized (pending) { done = callback; callback = null; owner = null; }
                    if (done != null) done.done(bitmap);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) delivery.run(); else main.post(delivery);
        }
    }

    private static final class Pending implements Runnable {
        final Context context;
        final Uri uri;
        final String key;
        final int maxPx, generation;
        final List<Request> requests = new ArrayList<>();

        Pending(Context context, Uri uri, String key, int maxPx) {
            this.context = context;
            this.uri = uri;
            this.key = key;
            this.maxPx = maxPx;
            generation = cacheGeneration;
        }

        public void run() {
            synchronized (pending) {
                if (requests.isEmpty()) { pending.remove(key); return; }
            }
            Bitmap bitmap = null;
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                bitmap = decode(context, uri, maxPx);
            } catch (Throwable e) { }
            finish(bitmap);
        }

        void finish(Bitmap bitmap) {
            List<Request> deliveries;
            synchronized (pending) {
                pending.remove(key);
                if (bitmap != null && !requests.isEmpty() && generation == cacheGeneration) cache.put(key, bitmap);
                deliveries = new ArrayList<>(requests);
                for (Request request : deliveries) request.owner = null;
                requests.clear();
            }
            for (Request request : deliveries) request.deliver(bitmap);
        }
    }

    /** Sarkinin kapagi: once oturumdaki resim, yoksa adresinden yuklenir */
    public static Bitmap metadataBitmap(MediaMetadata m) {
        if (m == null) return null;
        for (String k : BITMAP_KEYS) {
            try { Bitmap b = m.getBitmap(k); if (b != null) return b; } catch (Exception e) { }
        }
        return null;
    }

    private static final String[] BITMAP_KEYS = {MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON};
    private static final String[] URI_KEYS = {MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_ART_URI, MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI};

    public static Uri metadataUri(MediaMetadata m) {
        if (m == null) return null;
        for (String k : URI_KEYS) {
            String s = m.getString(k);
            if (s != null && s.length() > 0) return Uri.parse(s);
        }
        return null;
    }

    /** Shared, bounded background loads; results are delivered on the main thread. */
    public static Request load(Context c, Uri u, int maxPx, Done done) {
        Request request = new Request(done);
        if (u == null) { request.deliver(null); return request; }
        Context app = c.getApplicationContext();
        int size = Math.max(1, maxPx);
        String key = u.toString() + "#" + size;
        Pending job = null;
        Bitmap hit;
        synchronized (pending) {
            Context previous = registeredContext.get();
            if (previous != app) {
                if (previous != null) previous.unregisterComponentCallbacks(memory);
                registeredContext = new WeakReference<>(app);
                app.registerComponentCallbacks(memory);
            }
            hit = cache.get(key);
            if (hit == null) {
                job = pending.get(key);
                if (job != null) {
                    job.requests.add(request);
                    request.owner = job;
                    return request;
                }
                job = new Pending(app, u, key, size);
                job.requests.add(request);
                request.owner = job;
                pending.put(key, job);
            }
        }
        if (hit != null) { request.deliver(hit); return request; }
        try { pool.execute(job); }
        catch (RejectedExecutionException e) { job.finish(null); }
        return request;
    }

    private static Bitmap decode(Context c, Uri uri, int maxPx) throws java.io.IOException {
        File downloaded = null;
        HttpURLConnection connection = null;
        try {
            String scheme = uri.getScheme();
            if ("http".equals(scheme) || "https".equals(scheme)) {
                connection = (HttpURLConnection) new URL(uri.toString()).openConnection();
                connection.setConnectTimeout(6000);
                connection.setReadTimeout(8000);
                if (connection.getContentLengthLong() > MAX_ENCODED_BYTES) throw new java.io.IOException("artwork too large");
                downloaded = File.createTempFile("music-art-", ".tmp", c.getCacheDir());
                try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(downloaded)) {
                    byte[] buffer = new byte[16384];
                    int count, total = 0;
                    while ((count = in.read(buffer)) != -1) {
                        total += count;
                        if (total > MAX_ENCODED_BYTES) throw new java.io.IOException("artwork too large");
                        out.write(buffer, 0, count);
                    }
                }
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (InputStream in = open(c, uri, downloaded)) { BitmapFactory.decodeStream(in, null, options); }
            int longest = Math.max(options.outWidth, options.outHeight);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            int sample = 1;
            while (sample < (1 << 30) && longest / (sample * 2) >= maxPx) sample *= 2;
            options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap decoded;
            try (InputStream in = open(c, uri, downloaded)) { decoded = BitmapFactory.decodeStream(in, null, options); }
            if (decoded == null) return null;
            longest = Math.max(decoded.getWidth(), decoded.getHeight());
            if (longest <= maxPx) return decoded;
            float scale = (float) maxPx / longest;
            Bitmap resized = Bitmap.createScaledBitmap(decoded, Math.max(1, Math.round(decoded.getWidth() * scale)),
                    Math.max(1, Math.round(decoded.getHeight() * scale)), true);
            if (resized != decoded) decoded.recycle();
            return resized;
        } finally {
            if (connection != null) connection.disconnect();
            if (downloaded != null) downloaded.delete();
        }
    }

    private static InputStream open(Context c, Uri uri, File downloaded) throws java.io.IOException {
        InputStream in = downloaded == null ? c.getContentResolver().openInputStream(uri) : new FileInputStream(downloaded);
        if (in == null) throw new java.io.IOException("artwork stream unavailable");
        return in;
    }

    /** Recycled rows cancel stale work and are never retained by an image request. */
    private static final class Binding { Request request; }

    public static void into(Context c, final ImageView iv, Bitmap bm, Uri u, int px, final int placeholder) {
        Object old = iv.getTag();
        if (old instanceof Binding && ((Binding) old).request != null) ((Binding) old).request.cancel();
        final Binding binding = new Binding();
        iv.setTag(binding);
        if (bm != null) { iv.clearColorFilter(); iv.setPadding(0, 0, 0, 0); iv.setImageBitmap(bm); return; }
        iv.setImageResource(placeholder);
        if (u == null) return;
        final WeakReference<ImageView> target = new WeakReference<>(iv);
        binding.request = load(c, u, px, new Done() {
            public void done(Bitmap b) {
                ImageView view = target.get();
                if (b != null && view != null && view.getTag() == binding) {
                    view.clearColorFilter(); view.setPadding(0, 0, 0, 0); view.setImageBitmap(b);
                }
            }
        });
    }

    /** Downscale before software readback; never copy a full-resolution hardware cover. */
    private static Bitmap small(Bitmap src, int width, int height) {
        Bitmap scaled = Bitmap.createScaledBitmap(src, width, height, true);
        Bitmap result = scaled.copy(Bitmap.Config.ARGB_8888, true);
        if (scaled != src) scaled.recycle();
        return result;
    }

    /** Arka plan icin bulanik kucuk kopya; ImageView buyuturken yumusatir */
    public static Bitmap blur(Bitmap src) {
        if (src == null) return null;
        try {
            int w = 40;
            int h = Math.max(1, Math.min(80, Math.round(40f * src.getHeight() / Math.max(1, src.getWidth()))));
            Bitmap bitmap = small(src, w, h);
            int[] pixels = new int[w * h];
            int[] scratch = new int[Math.max(w, h)];
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
            for (int pass = 0; pass < 3; pass++) {
                boxH(pixels, w, h, 3, scratch);
                boxV(pixels, w, h, 3, scratch);
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h);
            return bitmap;
        } catch (Throwable e) { return null; }
    }

    private static void boxH(int[] p, int w, int h, int r, int[] out) {
        for (int y = 0; y < h; y++) {
            int off = y * w;
            for (int x = 0; x < w; x++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int xx = Math.min(w - 1, Math.max(0, x + k));
                    int c = p[off + xx];
                    a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                out[x] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
            System.arraycopy(out, 0, p, off, w);
        }
    }

    private static void boxV(int[] p, int w, int h, int r, int[] out) {
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int a = 0, rr = 0, g = 0, b = 0, n = 0;
                for (int k = -r; k <= r; k++) {
                    int yy = Math.min(h - 1, Math.max(0, y + k));
                    int c = p[yy * w + x];
                    a += c >>> 24; rr += (c >> 16) & 255; g += (c >> 8) & 255; b += c & 255; n++;
                }
                out[y] = ((a / n) << 24) | ((rr / n) << 16) | ((g / n) << 8) | (b / n);
            }
            for (int y = 0; y < h; y++) p[y * w + x] = out[y];
        }
    }

    /** Kapaktaki en canli renk; temaya gore okunur tona getirilir. Bulunamazsa 0 */
    public static int accent(Bitmap src, boolean light) {
        if (src == null) return 0;
        Bitmap sample = null;
        try {
            sample = small(src, 24, 24);
            int[] pixels = new int[24 * 24];
            sample.getPixels(pixels, 0, 24, 0, 0, 24, 24);
            float best = 0, hue = 0, saturation = 0;
            float[] hsv = new float[3];
            for (int color : pixels) {
                Color.colorToHSV(color, hsv);
                if (hsv[2] < 0.25f) continue;
                float score = hsv[1] * (0.4f + hsv[2]);
                if (score > best) { best = score; hue = hsv[0]; saturation = hsv[1]; }
            }
            if (saturation < 0.18f) return 0;
            hsv[0] = hue;
            hsv[1] = clamp(saturation, light ? 0.5f : 0.35f, light ? 0.9f : 0.7f);
            hsv[2] = light ? 0.52f : 0.95f;
            return Color.HSVToColor(hsv);
        } catch (Throwable e) { return 0; }
        finally { if (sample != null) sample.recycle(); }
    }

    private static float clamp(float v, float a, float b) { return Math.max(a, Math.min(b, v)); }
}
