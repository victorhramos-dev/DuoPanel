package com.abdullaherturk.duopanel;

import android.content.Context;
import android.view.View;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public class EdgeDrawerTest {
    private ServiceController<OverlayService> controller;
    private OverlayService service;

    @Before public void setup() {
        RuntimeEnvironment.setQualifiers("w320dp-h640dp-port");
        Context context = RuntimeEnvironment.getApplication();
        Prefs.sp(context).edit().clear().commit();
        ShadowSettings.setCanDrawOverlays(true);
        Prefs.set(context, "bubble", false);
        Prefs.setStripEdge(context, true);
        SplitController.setActive(false);
        SplitController.setDivider(null);
        controller = Robolectric.buildService(OverlayService.class).create();
        service = controller.get();
        ReflectionHelpers.setField(service, "hasWx", true);
        controller.startCommand(0, 1);
        service.onSplitChanged(true);
    }

    @After public void teardown() {
        if (controller != null) controller.destroy();
        SplitController.setActive(false);
        SplitController.setDivider(null);
        ShadowSettings.setCanDrawOverlays(false);
        Prefs.sp(RuntimeEnvironment.getApplication()).edit().clear().commit();
        RuntimeEnvironment.setQualifiers("");
    }

    @Test public void edgePanelIsInitiallyHidden() {
        assertTrue(Prefs.strip(service));
        assertTrue(Prefs.stripEdge(service));
        View panel = ReflectionHelpers.getField(service, "strip");
        assertFalse("edge panel must start collapsed", panel != null && panel.isShown());
    }

    private View panel() { return ReflectionHelpers.getField(service, "strip"); }
    private EdgeDrawer drawer() { return ReflectionHelpers.getField(service, "edgeDrawer"); }
    private android.os.Handler handler() { return ReflectionHelpers.getField(service, "h"); }
    private Runnable tick() { return ReflectionHelpers.getField(service, "tick"); }
    private int dp(int value) { return Math.round(value * service.getResources().getDisplayMetrics().density); }

    private void event(View view, int action, long time, float x, float y) {
        android.view.MotionEvent event = android.view.MotionEvent.obtain(0, time, action, x, y, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    private void swipe(View view, float start, float finish) {
        event(view, android.view.MotionEvent.ACTION_DOWN, 0, start, 20);
        event(view, android.view.MotionEvent.ACTION_MOVE, 100, finish, 20);
        event(view, android.view.MotionEvent.ACTION_UP, 200, finish, 20);
    }

    private void rebuild(boolean right) {
        Prefs.setStripRight(service, right);
        OverlayService.refresh(service);
        service.onSplitChanged(true);
    }

    @Test public void collapsedWindowIsOnlyASmallCenteredTabOnSelectedPhysicalEdge() {
        for (boolean right : new boolean[]{false, true}) {
            rebuild(right);
            View handle = drawer().handle;
            android.view.WindowManager.LayoutParams lp = (android.view.WindowManager.LayoutParams) handle.getLayoutParams();
            android.graphics.Rect safe = EdgeDrawer.usableBounds(service,
                    (android.view.WindowManager) service.getSystemService(Context.WINDOW_SERVICE));
            assertEquals(dp(24), lp.width);
            assertEquals(dp(56), lp.height);
            assertEquals(right ? safe.right - lp.width : safe.left, lp.x);
            assertEquals(safe.top + (safe.height() - lp.height) / 2, lp.y);
            assertEquals(View.VISIBLE, handle.getVisibility());
            assertEquals(View.GONE, panel().getVisibility());
            assertEquals(0, lp.flags & android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
            assertTrue((lp.flags & android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0);
            assertFalse(handler().hasCallbacks(tick()));
            assertFalse((Boolean) ReflectionHelpers.getField(service, "locOn"));
            assertFalse((Boolean) ReflectionHelpers.getField(service, "sensorOn"));
            assertFalse((Boolean) ReflectionHelpers.getField(service, "weatherEnabled"));
        }
    }

    @Test public void inwardPullAndOutwardHeaderPullWorkOnBothEdges() {
        for (boolean right : new boolean[]{false, true}) {
            rebuild(right);
            View handle = drawer().handle;
            swipe(handle, dp(100), dp(right ? 60 : 140));
            assertEquals(View.VISIBLE, panel().getVisibility());
            assertEquals(View.GONE, handle.getVisibility());
            assertTrue(handler().hasCallbacks(tick()));
            assertTrue((Boolean) ReflectionHelpers.getField(service, "weatherEnabled"));
            swipe(panel().findViewById(R.id.drawer_header), dp(100), dp(right ? 140 : 60));
            assertEquals(View.GONE, panel().getVisibility());
            assertEquals(View.VISIBLE, handle.getVisibility());
            assertFalse(handler().hasCallbacks(tick()));
            assertFalse((Boolean) ReflectionHelpers.getField(service, "weatherEnabled"));
        }
    }

    @Test public void compassRunsOnlyWhileExpandedAndDoesNotDuplicateOnReopen() {
        android.hardware.SensorManager manager = ReflectionHelpers.getField(service, "sm");
        org.robolectric.Shadows.shadowOf(manager).addSensor(org.robolectric.shadows.ShadowSensor.newInstance(
                android.hardware.Sensor.TYPE_ROTATION_VECTOR));
        assertFalse((Boolean) ReflectionHelpers.getField(service, "sensorOn"));
        for (int i = 0; i < 3; i++) {
            drawer().handle.performClick();
            assertTrue((Boolean) ReflectionHelpers.getField(service, "sensorOn"));
            panel().findViewById(R.id.drawer_close).performClick();
            assertFalse((Boolean) ReflectionHelpers.getField(service, "sensorOn"));
            assertFalse((Boolean) ReflectionHelpers.getField(service, "locOn"));
            assertFalse(handler().hasCallbacks(tick()));
        }
    }

    @Test @Config(sdk = 24) public void minimumSdkTabAndDrawerRemainFunctional() {
        assertEquals(View.GONE, panel().getVisibility());
        swipe(drawer().handle, dp(100), dp(140));
        assertEquals(View.VISIBLE, panel().getVisibility());
        panel().findViewById(R.id.drawer_close).performClick();
        assertEquals(View.GONE, panel().getVisibility());
        assertEquals(View.VISIBLE, drawer().handle.getVisibility());
    }

    @Test @Config(sdk = 29) public void legacyWindowInsetsKeepTabAndPanelOutOfCutoutAndBars() {
        android.view.WindowInsets insets = new android.view.WindowInsets.Builder()
                .setSystemWindowInsets(android.graphics.Insets.of(20, 32, 16, 40))
                .setStableInsets(android.graphics.Insets.of(20, 32, 16, 40))
                .setDisplayCutout(new android.view.DisplayCutout(new android.graphics.Rect(48, 64, 0, 0),
                        java.util.Collections.singletonList(new android.graphics.Rect(0, 0, 48, 64))))
                .build();
        drawer().handle.dispatchApplyWindowInsets(insets);
        android.view.WindowManager.LayoutParams tab = (android.view.WindowManager.LayoutParams) drawer().handle.getLayoutParams();
        assertEquals(48, tab.x);
        assertTrue(tab.y >= 64);
        assertEquals(48, drawer().panelParams.x);
        assertTrue(drawer().panelParams.y >= 64);
    }

    @Test @Config(sdk = 34) public void targetSdkRightEdgeIsCollapsedUntilPulledInward() {
        rebuild(true);
        assertEquals(View.GONE, panel().getVisibility());
        swipe(drawer().handle, dp(100), dp(60));
        assertEquals(View.VISIBLE, panel().getVisibility());
        OverlayService.collapseDrawer();
        assertEquals(View.VISIBLE, drawer().handle.getVisibility());
    }

    @Test public void wrongDirectionAndCanceledPullDoNotOpenAndAccessibleTapDoes() {
        swipe(drawer().handle, dp(100), dp(60));
        assertEquals(View.GONE, panel().getVisibility());
        View handle = drawer().handle;
        event(handle, android.view.MotionEvent.ACTION_DOWN, 0, 1, 20);
        event(handle, android.view.MotionEvent.ACTION_CANCEL, 100, dp(80), 20);
        event(handle, android.view.MotionEvent.ACTION_UP, 200, dp(80), 20);
        assertEquals(View.GONE, panel().getVisibility());
        assertTrue(handle.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(View.VISIBLE, panel().getVisibility());
        assertTrue(panel().findViewById(R.id.drawer_close).performAccessibilityAction(
                android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK, null));
        assertEquals(View.GONE, panel().getVisibility());
        assertEquals(View.VISIBLE, handle.getVisibility());
    }

    @Test public void outsideCollapsesButAppGridTouchesKeepSelectionAlive() {
        drawer().handle.performClick();
        panel().findViewById(R.id.btn_left).performClick();
        View popup = ReflectionHelpers.getField(service, "popup");
        assertNotNull(popup);
        event(panel(), android.view.MotionEvent.ACTION_OUTSIDE, 0, 0, 0);
        assertEquals(View.VISIBLE, panel().getVisibility());
        assertSame(popup, ReflectionHelpers.getField(service, "popup"));
        assertEquals(View.VISIBLE, popup.findViewById(R.id.gv_all).getVisibility());
        // A true outside touch dismisses both windows; a selection is covered by InfoStripTest.
        event(popup, android.view.MotionEvent.ACTION_OUTSIDE, 100, 0, 0);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(32, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertNull(ReflectionHelpers.getField(service, "popup"));
        assertEquals(View.GONE, panel().getVisibility());
        drawer().handle.performClick();
        event(panel(), android.view.MotionEvent.ACTION_OUTSIDE, 200, -1, -1);
        assertEquals(View.GONE, panel().getVisibility());
    }

    @Test public void redactedPopupOutsideCoordinatesDoNotCollapseAnActualSidebarTouch() {
        drawer().handle.performClick();
        panel().findViewById(R.id.btn_left).performClick();
        View popup = ReflectionHelpers.getField(service, "popup");
        event(popup, android.view.MotionEvent.ACTION_OUTSIDE, 100, 0, 0);
        // The DOWN belongs to the sidebar, even if the popup's OUTSIDE coordinates were zeroed.
        event(panel(), android.view.MotionEvent.ACTION_DOWN, 100, 1, 1);
        event(panel(), android.view.MotionEvent.ACTION_CANCEL, 101, 1, 1);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(32, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertEquals(View.VISIBLE, panel().getVisibility());
        assertNull(ReflectionHelpers.getField(service, "popup"));
    }

    @Test public void collapseDisconnectsWeatherAndRejectsOldRequestTokens() throws Exception {
        drawer().handle.performClick();
        int token = ReflectionHelpers.callInstanceMethod(service, "weatherToken");
        assertTrue(token >= 0);
        final boolean[] disconnected = {false};
        java.net.HttpURLConnection connection = new java.net.HttpURLConnection(new java.net.URL("https://example.invalid/")) {
            @Override public void disconnect() { disconnected[0] = true; }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { fail("test connection must never reach network"); }
        };
        boolean tracked = ReflectionHelpers.callInstanceMethod(service, "trackWeather",
                ReflectionHelpers.ClassParameter.from(java.net.HttpURLConnection.class, connection),
                ReflectionHelpers.ClassParameter.from(int.class, token));
        assertTrue(tracked);
        OverlayService.collapseDrawer();
        assertTrue(disconnected[0]);
        assertFalse((Boolean) ReflectionHelpers.callInstanceMethod(service, "weatherCurrent",
                ReflectionHelpers.ClassParameter.from(int.class, token)));
        assertEquals(-1, (int) ReflectionHelpers.callInstanceMethod(service, "weatherToken"));
        drawer().handle.performClick();
        assertFalse((Boolean) ReflectionHelpers.callInstanceMethod(service, "weatherCurrent",
                ReflectionHelpers.ClassParameter.from(int.class, token)));
    }

    @Test public void splitInactiveRotationAndRebuildAlwaysReturnToCollapsedWithoutGhosts() {
        drawer().handle.performClick();
        View oldPanel = panel(), oldHandle = drawer().handle;
        service.onSplitChanged(false);
        assertEquals(View.GONE, oldPanel.getVisibility());
        assertEquals(View.GONE, oldHandle.getVisibility());
        assertFalse(handler().hasCallbacks(tick()));
        service.onSplitChanged(true);
        assertEquals(View.VISIBLE, oldHandle.getVisibility());
        assertEquals(View.GONE, oldPanel.getVisibility());
        oldHandle.performClick();
        service.onConfigurationChanged(new android.content.res.Configuration(service.getResources().getConfiguration()));
        service.onSplitChanged(true);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNull(oldPanel.getParent());
        assertNull(oldHandle.getParent());
        assertNotSame(oldPanel, panel());
        assertEquals(View.GONE, panel().getVisibility());
        assertEquals(View.VISIBLE, drawer().handle.getVisibility());
        assertFalse(handler().hasCallbacks(tick()));
        View handle = drawer().handle, panel = panel();
        controller.destroy();
        controller = null;
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertNull(handle.getParent());
        assertNull(panel.getParent());
        assertNull(ReflectionHelpers.getField(service, "edgeDrawer"));
        assertFalse(handler().hasCallbacks(tick()));
    }

    @Test public void overlayAttachmentCompletesOnFirstTraversal() {
        rebuild(false);
        View handle = drawer().handle;
        View panel = panel();
        assertNotNull(handle.getParent());
        assertNotNull(panel.getParent());
        boolean before = handle.isAttachedToWindow();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertTrue("handle before traversal=" + before + " parent=" + handle.getParent(), handle.isAttachedToWindow());
        assertTrue(panel.isAttachedToWindow());
    }
    @Test public void panelControlsMeasureAfterWindowTraversal() {
        RuntimeEnvironment.setQualifiers("w240dp-h480dp-port");
        rebuild(false);
        drawer().handle.performClick();
        android.view.WindowManager.LayoutParams lp = drawer().panelParams;
        panel().measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY));
        panel().layout(0, 0, lp.width, lp.height);
        int before = panel().findViewById(R.id.btn_left).getWidth();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        panel().measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY));
        panel().layout(0, 0, lp.width, lp.height);
        assertTrue("beforeTraversal=" + before + " after=" + panel().findViewById(R.id.btn_left).getWidth()
                + " panel=" + lp.width + " visibility=" + panel().getVisibility()
                + " row=" + panel().findViewById(R.id.btn_left).getParent(), panel().findViewById(R.id.btn_left).getWidth() > 0);
    }
    @Test public void panelsAndPopupsFitUsablePortraitLandscapeAndNarrowScreens() {
        for (String qualifiers : new String[]{"w240dp-h480dp-port", "w320dp-h640dp-port", "w640dp-h320dp-land"}) {
            RuntimeEnvironment.setQualifiers(qualifiers);
            for (boolean right : new boolean[]{false, true}) {
                rebuild(right);
                drawer().handle.performClick();
                org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                android.view.WindowManager.LayoutParams lp = drawer().panelParams;
                android.graphics.Rect safe = EdgeDrawer.usableBounds(service,
                        (android.view.WindowManager) service.getSystemService(Context.WINDOW_SERVICE));
                assertTrue(safe.contains(lp.x, lp.y, lp.x + lp.width, lp.y + lp.height));
                panel().measure(View.MeasureSpec.makeMeasureSpec(lp.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY));
                panel().layout(0, 0, lp.width, lp.height);
                assertEquals(android.widget.LinearLayout.VERTICAL, ((android.widget.LinearLayout) panel()).getOrientation());
                android.widget.ScrollView scroll = panel().findViewById(R.id.drawer_scroll);
                assertTrue(scroll.getHeight() > 0);
                for (int id : new int[]{R.id.btn_left, R.id.btn_right, R.id.btn_ratio, R.id.btn_swap,
                        R.id.tv_clock, R.id.wx_container, R.id.tv_speed, R.id.iv_compass}) {
                    View child = panel().findViewById(id);
                    assertTrue("qualifiers=" + qualifiers + " safe=" + safe + " panel=" + lp.width + "x" + lp.height + " child=" + id + " width=" + child.getWidth() + " wm=" + ((android.view.WindowManager) service.getSystemService(Context.WINDOW_SERVICE)).getCurrentWindowMetrics(), child.getWidth() > 0);
                    assertTrue(child.getHeight() > 0);
                    android.graphics.Rect bounds = new android.graphics.Rect();
                    child.getDrawingRect(bounds);
                    ((android.view.ViewGroup) panel()).offsetDescendantRectToMyCoords(child, bounds);
                    assertTrue(bounds.left >= 0 && bounds.right <= lp.width);
                }
                scroll.setSmoothScrollingEnabled(false);
                scroll.fullScroll(View.FOCUS_DOWN);
                panel().findViewById(R.id.btn_right).performClick();
                View popup = ReflectionHelpers.getField(service, "popup");
                android.view.WindowManager.LayoutParams p = (android.view.WindowManager.LayoutParams) popup.getLayoutParams();
                assertTrue(safe.contains(p.x, p.y, p.x + p.width, p.y + p.height));
                assertTrue(((android.widget.GridView) popup.findViewById(R.id.gv_all)).getLayoutParams().height > 0);
            }
        }
    }

    @Test public void settingsExposeBothEdgesRebindExternalChangesAndPreserveFloatingCoordinates() {
        Prefs.setStrip(service, 17, 71);
        Prefs.sp(service).edit().putLong("update.lastCheck", System.currentTimeMillis()).commit();
        org.robolectric.android.controller.ActivityController<MainActivity> activityController =
                Robolectric.buildActivity(MainActivity.class).create().start().resume();
        MainActivity activity = activityController.get();
        android.widget.RadioGroup group = activity.findViewById(R.id.strip_position);
        assertEquals(R.id.strip_edge, group.getCheckedRadioButtonId());
        activity.findViewById(R.id.strip_edge_right).performClick();
        assertTrue(Prefs.stripEdge(service));
        assertTrue(Prefs.stripRight(service));
        assertEquals(View.GONE, panel().getVisibility());
        activity.findViewById(R.id.strip_floating).performClick();
        assertFalse(Prefs.stripEdge(service));
        assertEquals(17, Prefs.stripX(service));
        assertEquals(71, Prefs.stripY(service));
        Prefs.sp(service).edit().putBoolean("stripEdge", true).putBoolean("stripRight", false).commit();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(R.id.strip_edge, group.getCheckedRadioButtonId());
        Prefs.setStripRight(service, true);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(R.id.strip_edge_right, group.getCheckedRadioButtonId());
        activityController.pause();
        Prefs.setStripRight(service, false);
        activityController.resume();
        assertEquals(R.id.strip_edge, group.getCheckedRadioButtonId());
        activityController.pause().stop().destroy();
        assertEquals(17, Prefs.stripX(service));
        assertEquals(71, Prefs.stripY(service));
    }
}
