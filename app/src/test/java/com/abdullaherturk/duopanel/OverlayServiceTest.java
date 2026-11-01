package com.abdullaherturk.duopanel;

import android.content.Context;
import android.location.Location;
import android.location.LocationManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public class OverlayServiceTest {
    private ServiceController<OverlayService> controller;
    private OverlayService service;
    private CountingText speed, direction, clock;

    private static class CountingText extends TextView {
        int changes;
        CountingText(Context c) { super(c); changes = 0; }
        @Override public void setText(CharSequence text, BufferType type) { super.setText(text, type); changes++; }
    }

    @Before public void setUp() {
        controller = Robolectric.buildService(OverlayService.class).create();
        service = controller.get();
        attachStrip();
        ReflectionHelpers.setField(service, "lastWx", System.currentTimeMillis());
        ReflectionHelpers.setField(service, "locOn", true);
        Location location = new Location(LocationManager.GPS_PROVIDER);
        location.setSpeed(0f);
        location.setBearing(0f);
        ReflectionHelpers.setField(service, "lastLoc", location);
        ReflectionHelpers.setField(service, "lastFix", System.currentTimeMillis());
    }

    @After public void tearDown() { controller.destroy(); }

    private void attachStrip() {
        ReflectionHelpers.setField(service, "strip", new View(service));
        clock = new CountingText(service);
        speed = new CountingText(service);
        direction = new CountingText(service);
        ReflectionHelpers.setField(service, "tvClock", clock);
        ReflectionHelpers.setField(service, "tvSpeed", speed);
        ReflectionHelpers.setField(service, "tvDir", direction);
        for (String name : new String[]{"tvDay", "tvDate", "tvTemp"})
            ReflectionHelpers.setField(service, name, new TextView(service));
        for (String name : new String[]{"ivLeft", "ivRight", "ivWeather", "ivCompass"})
            ReflectionHelpers.setField(service, name, new ImageView(service));
        ReflectionHelpers.setField(service, "dotGps", new View(service));
        ReflectionHelpers.setField(service, "wxBox", new View(service));
        ReflectionHelpers.setField(service, "stripLp", new android.view.WindowManager.LayoutParams());
    }

    private void tick() { ReflectionHelpers.callInstanceMethod(service, "tickOnce"); }

    @Test public void unchangedTelemetryDoesNotRebindTextEveryTick() {
        for (int i = 0; i < 100; i++) tick();
        assertEquals("stable speed should be bound once, not 100 times", 1, speed.changes);
        assertEquals(1, direction.changes);
        assertEquals(1, clock.changes);
        assertEquals("0", speed.getText().toString());
    }

    @Test public void hiddenStripDoesNotRefreshWidgets() {
        View strip = ReflectionHelpers.getField(service, "strip");
        strip.setVisibility(View.GONE);
        for (int i = 0; i < 100; i++) tick();
        assertEquals(0, speed.changes);
        assertEquals(0, clock.changes);
    }

    @Test public void hiddenStripStopsLocationAndCompassListeners() {
        ReflectionHelpers.setField(service, "sensorOn", true);
        service.onSplitChanged(false);
        assertFalse(ReflectionHelpers.getField(service, "locOn"));
        assertFalse(ReflectionHelpers.getField(service, "sensorOn"));
    }

    @Test public void changedSpeedAndExpiredGpsStillRefreshNormally() {
        tick();
        Location location = ReflectionHelpers.getField(service, "lastLoc");
        location.setSpeed(10f);
        location.setBearing(90f);
        tick();
        assertEquals("36", speed.getText().toString());
        assertEquals("E", direction.getText().toString());
        ReflectionHelpers.setField(service, "lastFix", System.currentTimeMillis() - 7000);
        tick();
        assertEquals("--", speed.getText().toString());
        assertEquals("--", direction.getText().toString());
    }

    @Test public void compassRestartsWhenTheStripBecomesVisibleAgain() {
        android.hardware.SensorManager manager = ReflectionHelpers.getField(service, "sm");
        org.robolectric.Shadows.shadowOf(manager).addSensor(org.robolectric.shadows.ShadowSensor.newInstance(
                android.hardware.Sensor.TYPE_ROTATION_VECTOR));
        service.onSplitChanged(true);
        assertTrue(ReflectionHelpers.getField(service, "sensorOn"));
        service.onSplitChanged(false);
        assertFalse(ReflectionHelpers.getField(service, "sensorOn"));
        service.onSplitChanged(true);
        assertTrue(ReflectionHelpers.getField(service, "sensorOn"));
    }

    @Test public void removalReleasesChildrenAndWindowLayout() {
        ReflectionHelpers.callInstanceMethod(service, "removeAll");
        for (String name : new String[]{"strip", "stripLp", "tvClock", "tvDay", "tvDate", "tvTemp",
                "tvSpeed", "tvDir", "ivLeft", "ivRight", "ivWeather", "ivCompass", "dotGps", "wxBox"})
            assertNull(name + " retains the removed view tree", ReflectionHelpers.getField(service, name));
    }

    @Test public void replacementStripRefreshesClockWithinTheSameMinute() {
        tick();
        ReflectionHelpers.callInstanceMethod(service, "removeAll");
        attachStrip();
        tick();
        assertEquals(1, clock.changes);
    }
}
