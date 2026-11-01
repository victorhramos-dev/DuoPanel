package com.abdullaherturk.duopanel;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Looper;
import android.util.LruCache;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MusicArtTest {
    private Application app;

    @Before public void setUp() {
        app = RuntimeEnvironment.getApplication();
        cache().evictAll();
    }

    private LruCache<String, Bitmap> cache() {
        return ReflectionHelpers.getStaticField(MusicArt.class, "cache");
    }

    private Uri image(int w, int h) throws Exception {
        File file = File.createTempFile("art-", ".png", app.getCacheDir());
        Bitmap source = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally { source.recycle(); }
        return Uri.fromFile(file);
    }

    private Bitmap load(Context context, Uri uri, int max) throws Exception {
        AtomicReference<Bitmap> result = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        MusicArt.load(context, uri, max, bitmap -> { result.set(bitmap); done.countDown(); });
        for (int i = 0; i < 500; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            if (done.await(10, TimeUnit.MILLISECONDS)) return result.get();
        }
        fail("artwork callback did not finish");
        return null;
    }

    @Test @Config(sdk = {28, 30, 33}) public void wideArtworkIsBoundedByItsLongestEdge() throws Exception {
        Bitmap result = load(app, image(2048, 64), 64);
        assertNotNull(result);
        assertTrue("panoramic covers must not decode at full resolution", result.getWidth() <= 64);
        assertTrue(result.getHeight() >= 1 && result.getHeight() <= 64);
        assertTrue(result.getByteCount() <= 64 * 64 * 4);
    }

    @Test public void tallArtworkIsBoundedByItsLongestEdge() throws Exception {
        Bitmap result = load(app, image(64, 2048), 64);
        assertNotNull(result);
        assertTrue(result.getWidth() >= 1 && result.getWidth() <= 64);
        assertTrue(result.getHeight() <= 64);
    }

    @Test public void squareArtworkDoesNotExceedRequestedSize() throws Exception {
        Bitmap result = load(app, image(1000, 1000), 128);
        assertNotNull(result);
        assertEquals(128, result.getWidth());
        assertEquals(128, result.getHeight());
    }

    @Test public void workerUsesApplicationContextNotTheScreen() throws Exception {
        Context screen = new ContextWrapper(app) {
            @Override public ContentResolver getContentResolver() {
                throw new AssertionError("worker retained the screen context");
            }
        };
        assertNotNull(load(screen, image(64, 64), 64));
    }

    @Test public void hiddenUiReleasesCachedArtworkWithoutRecyclingVisibleImages() throws Exception {
        Bitmap visible = load(app, image(64, 64), 64);
        assertTrue(cache().size() > 0);
        app.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN);
        assertEquals(0, cache().size());
        assertFalse(visible.isRecycled());
        assertEquals(Color.RED, visible.getPixel(0, 0));
    }

    @Test public void blurAndAccentLeaveSharedSourceUntouched() {
        Bitmap source = Bitmap.createBitmap(40, 40, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        source.setPixel(20, 20, Color.BLUE);
        Bitmap result = MusicArt.blur(source);
        assertNotNull(result);
        assertNotSame(source, result);
        assertEquals(Color.BLUE, source.getPixel(20, 20));
        assertTrue(MusicArt.accent(source, false) != 0);
        assertFalse(source.isRecycled());
    }

    @Test public void asynchronousRowArtworkActuallyReachesTheView() throws Exception {
        android.widget.ImageView view = new android.widget.ImageView(app);
        Uri uri = image(64, 64);
        MusicArt.into(app, view, null, uri, 64, R.drawable.ic_music);
        Bitmap expected = load(app, uri, 64);
        assertTrue(view.getDrawable() instanceof android.graphics.drawable.BitmapDrawable);
        assertSame(expected, ((android.graphics.drawable.BitmapDrawable) view.getDrawable()).getBitmap());
    }

    private static final class GateProvider extends android.content.ContentProvider {
        final File file;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        GateProvider(File file) {
            this.file = file;
            android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
            info.authority = "art-test;art-cancel";
            info.exported = true;
            attachInfo(RuntimeEnvironment.getApplication(), info);
        }
        public boolean onCreate() { return true; }
        public android.os.ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.FileNotFoundException("test gate timed out"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new java.io.FileNotFoundException(); }
            return android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY);
        }
        public String getType(Uri uri) { return "image/png"; }
        public android.database.Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
        public Uri insert(Uri u, android.content.ContentValues v) { return null; }
        public int delete(Uri u, String s, String[] a) { return 0; }
        public int update(Uri u, android.content.ContentValues v, String s, String[] a) { return 0; }
    }

    @Test public void contentProviderStreamCanBeDecoded() throws Exception {
        GateProvider provider = new GateProvider(new File(image(64, 64).getPath()));
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("art-test", provider);
        provider.release.countDown();
        Uri uri = Uri.parse("content://art-test/cover");
        try (java.io.InputStream in = app.getContentResolver().openInputStream(uri)) {
            assertEquals("PNG signature", 137, in.read());
        }
        Bitmap bitmap = ReflectionHelpers.callStaticMethod(MusicArt.class, "decode",
                ReflectionHelpers.ClassParameter.from(Context.class, app),
                ReflectionHelpers.ClassParameter.from(Uri.class, uri),
                ReflectionHelpers.ClassParameter.from(int.class, 64));
        assertNotNull(bitmap);
    }

    @Test public void simultaneousLoadsShareOneDecodedBitmap() throws Exception {
        GateProvider provider = new GateProvider(new File(image(64, 64).getPath()));
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("art-test", provider);
        Uri uri = Uri.parse("content://art-test/cover");
        AtomicReference<Bitmap> first = new AtomicReference<>(), second = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(2);
        MusicArt.load(app, uri, 64, b -> { first.set(b); done.countDown(); });
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
        MusicArt.load(app, uri, 64, b -> { second.set(b); done.countDown(); });
        provider.release.countDown();
        for (int i = 0; i < 500 && done.getCount() != 0; i++) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            done.await(10, TimeUnit.MILLISECONDS);
        }
        assertEquals(0, done.getCount());
        assertNotNull(first.get());
        assertSame("one URI and size must have only one decoded allocation", first.get(), second.get());
    }

    @Test public void cancellationReleasesCallbackWithoutCancellingOtherConsumers() throws Exception {
        GateProvider provider = new GateProvider(new File(image(64, 64).getPath()));
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("art-cancel", provider);
        Uri uri = Uri.parse("content://art-cancel/cover");
        MusicArt.Request cancelled = MusicArt.load(app, uri, 64, b -> fail("cancelled callback was invoked"));
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
        cancelled.cancel();
        assertNull(ReflectionHelpers.getField(cancelled, "callback"));
        provider.release.countDown();
        assertNotNull(load(app, uri, 64));
    }

    @Test public void recycledRowKeepsItsNewestArtwork() throws Exception {
        GateProvider provider = new GateProvider(new File(image(64, 64).getPath()));
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("art-test", provider);
        Uri oldUri = Uri.parse("content://art-test/old-row");
        Uri nextUri = image(32, 32);
        android.widget.ImageView view = new android.widget.ImageView(app);
        MusicArt.into(app, view, null, oldUri, 64, R.drawable.ic_music);
        assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
        MusicArt.into(app, view, null, nextUri, 64, R.drawable.ic_music);
        provider.release.countDown();
        Bitmap newest = load(app, nextUri, 64);
        assertNotNull(load(app, oldUri, 64));
        assertSame(newest, ((android.graphics.drawable.BitmapDrawable) view.getDrawable()).getBitmap());
    }

    @Test public void artworkCacheIsCappedAtFourMiB() {
        assertEquals(4 * 1024 * 1024, cache().maxSize());
    }

    @Test @Config(sdk = {28, 33}) public void hardwareArtworkCanBeDownscaledBeforeSoftwareReadback() {
        Bitmap source = Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888);
        source.eraseColor(Color.RED);
        Bitmap hardware = source.copy(Bitmap.Config.HARDWARE, false);
        assertNotNull(hardware);
        Bitmap small = Bitmap.createScaledBitmap(hardware, 24, 24, true);
        Bitmap readable = small.copy(Bitmap.Config.ARGB_8888, false);
        assertNotNull(readable);
        assertEquals(Color.RED, readable.getPixel(0, 0));
        assertNotNull(MusicArt.blur(hardware));
        assertTrue(MusicArt.accent(hardware, false) != 0);
        assertFalse(hardware.isRecycled());
    }
}
