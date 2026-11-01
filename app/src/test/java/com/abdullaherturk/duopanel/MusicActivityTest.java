package com.abdullaherturk.duopanel;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.widget.ImageView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MusicActivityTest {
    private ActivityController<MusicActivity> controller;
    private MusicActivity activity;
    private boolean destroyed;

    @Before public void setUp() {
        controller = Robolectric.buildActivity(MusicActivity.class).create().start().resume();
        activity = controller.get();
    }

    @After public void tearDown() { if (!destroyed) destroy(); }

    private void destroy() { controller.pause().stop().destroy(); destroyed = true; }

    private Bitmap artwork() {
        Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        return bitmap;
    }

    private void setArt(Bitmap bitmap) {
        ReflectionHelpers.callInstanceMethod(activity, "setArt", ReflectionHelpers.ClassParameter.from(Bitmap.class, bitmap));
    }

    @Test public void unchangedArtworkReusesTheProcessedBackground() {
        Bitmap bitmap = artwork();
        setArt(bitmap);
        ImageView background = ReflectionHelpers.getField(activity, "bg");
        Object first = background.getDrawable();
        assertNotNull(first);
        setArt(bitmap);
        assertSame("rebuilding controls must not re-blur the same album cover", first, background.getDrawable());
    }

    @Test public void changedPixelsOfTheSameBitmapStillRefreshBackground() {
        Bitmap bitmap = artwork();
        setArt(bitmap);
        ImageView background = ReflectionHelpers.getField(activity, "bg");
        Object first = background.getDrawable();
        bitmap.eraseColor(Color.BLUE);
        setArt(bitmap);
        assertNotSame(first, background.getDrawable());
    }

    @Test public void pauseDetachesBothPendingArtworkCallbacks() {
        MusicArt.Done callback = bitmap -> fail("paused screen callback was retained");
        MusicArt.Request primary = ReflectionHelpers.callConstructor(MusicArt.Request.class,
                ReflectionHelpers.ClassParameter.from(MusicArt.Done.class, callback));
        MusicArt.Request fallback = ReflectionHelpers.callConstructor(MusicArt.Request.class,
                ReflectionHelpers.ClassParameter.from(MusicArt.Done.class, callback));
        ReflectionHelpers.setField(activity, "primaryArt", primary);
        ReflectionHelpers.setField(activity, "fallbackArt", fallback);
        controller.pause();
        assertNull(ReflectionHelpers.getField(primary, "callback"));
        assertNull(ReflectionHelpers.getField(fallback, "callback"));
        assertNull(ReflectionHelpers.getField(activity, "primaryArt"));
        assertNull(ReflectionHelpers.getField(activity, "fallbackArt"));
        controller.resume();
    }

    @Test public void pauseInvalidatesUnfinishedCoverSoFallbackCanRestart() {
        ReflectionHelpers.setField(activity, "artDone", false);
        ReflectionHelpers.setField(activity, "artKey", "unfinished song");
        controller.pause();
        assertNull("fallback is loaded on newSong, so cancellation must invalidate its key",
                ReflectionHelpers.getField(activity, "artKey"));
        controller.resume();
    }

    @Test public void destroyedScreenReleasesArtWithoutRecyclingSharedBitmap() {
        Bitmap bitmap = artwork();
        setArt(bitmap);
        ImageView background = ReflectionHelpers.getField(activity, "bg");
        destroy();
        assertNull(ReflectionHelpers.getField(activity, "artBmp"));
        assertNull(ReflectionHelpers.getField(activity, "lastBg"));
        assertNull(background.getDrawable());
        assertFalse(bitmap.isRecycled());
    }
}
