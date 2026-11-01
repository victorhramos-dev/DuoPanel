package com.abdullaherturk.duopanel;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.graphics.PathMeasure;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.GridView;
import java.time.Duration;
import java.util.Arrays;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;

/** Real picker -> controller -> service -> PairActivity -> verified divider callback. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w1000dp-h400dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PairReplacementFlowTest {
    private static final String LEFT = "test.flow.left", RIGHT = "test.flow.right";
    private static final String NEW = "test.flow.new", LATEST = "test.flow.latest";
    private ServiceController<SplitService> splitController;
    private ServiceController<OverlayService> overlayController;
    private ActivityController<PairActivity> bridgeController;
    private SplitService split;
    private OverlayService overlay;
    private ShadowAccessibilityService accessibility;

    @Before public void setup() {
        splitController = Robolectric.buildService(SplitService.class).create();
        split = splitController.get();
        ReflectionHelpers.setStaticField(SplitService.class, "inst", split);
        accessibility = Shadows.shadowOf(split);
        accessibility.setCanDispatchGestures(true);
        Prefs.sp(split).edit().clear().commit();
        for (String pkg : new String[]{LEFT, RIGHT, NEW, LATEST}) install(pkg);
        Prefs.setSide(split, Prefs.LEFT, LEFT);
        Prefs.setSide(split, Prefs.RIGHT, RIGHT);
        Prefs.setStripEdge(split, false);
        Prefs.set(split, "bubble", false);
        Object config = ReflectionHelpers.getField(split.getResources().getConfiguration(), "windowConfiguration");
        ReflectionHelpers.callInstanceMethod(config, "setMaxBounds",
                ClassParameter.from(Rect.class, new Rect(0, 0, 1000, 400)));
        ShadowSettings.setCanDrawOverlays(true);
        SplitController.setActive(true);
        overlayController = Robolectric.buildService(OverlayService.class).create();
        overlay = overlayController.get();
        ReflectionHelpers.setField(overlay, "hasWx", true);
        overlayController.startCommand(0, 1);
        overlay.onSplitChanged(true);
    }

    @After public void teardown() {
        if (bridgeController != null) bridgeController.destroy();
        if (overlayController != null) overlayController.destroy();
        if (split != null) split.onUnbind(null);
        if (splitController != null) splitController.destroy();
        SplitController.setActive(false);
        SplitController.setDivider(null);
        ShadowSettings.setCanDrawOverlays(false);
    }

    @Test public void leftGridCarriesThirtyThroughRealBridge() { flow(Prefs.LEFT, 30, false); }
    @Test public void rightGridCarriesThirtyThroughRealBridge() { flow(Prefs.RIGHT, 30, false); }
    @Test public void leftGridCarriesSeventyThroughRealBridge() { flow(Prefs.LEFT, 70, false); }
    @Test public void rightGridCarriesSeventyThroughRealBridge() { flow(Prefs.RIGHT, 70, false); }
    @Test public void leftGridCarriesFiftyThroughRealBridge() { flow(Prefs.LEFT, 50, false); }
    @Test public void rightGridCarriesFiftyThroughRealBridge() { flow(Prefs.RIGHT, 50, false); }
    @Test public void leftHistoryCarriesSeventyThroughRealBridge() { flow(Prefs.LEFT, 70, true); }
    @Test public void rightHistoryCarriesThirtyThroughRealBridge() { flow(Prefs.RIGHT, 30, true); }

    private void flow(int side, int target, boolean history) {
        Prefs.setRatio(split, LEFT, RIGHT, target);
        String left = side == Prefs.LEFT ? NEW : LEFT;
        String right = side == Prefs.RIGHT ? NEW : RIGHT;
        Prefs.setRatio(split, left, right, target == 70 ? 30 : 70);
        Prefs.setRatio(split, right, left, 41);
        pick(side, NEW, history);
        assertEquals(target, Prefs.ratio(split, left, right));
        idle(700);
        Intent bridge = accessibility.getNextStartedActivity();
        assertNotNull(bridge);
        assertEquals(PairActivity.class.getName(), bridge.getComponent().getClassName());
        assertEquals(left, bridge.getStringExtra("l"));
        assertEquals(right, bridge.getStringExtra("r"));
        bridgeController = Robolectric.buildActivity(PairActivity.class, bridge).create().start().resume();
        PairActivity activity = bridgeController.get();
        idle(400);
        Intent adjacent = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("real adjacent app launch", adjacent);
        assertEquals(right, adjacent.getComponent().getPackageName());
        assertTrue((adjacent.getFlags() & 0x00001000) != 0);
        idle(1400);
        Intent same = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull("real same-side app launch", same);
        assertEquals(left, same.getComponent().getPackageName());
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        if (Prefs.stripEdge(split)) {
            windows(left, "test.flow.loading", 50, 1000);
            idle(7000);
            assertTrue("a loading pane must not trigger an invented midpoint or a resize",
                    accessibility.getGesturesDispatched().isEmpty());
            assertNull("a loading pane must not relaunch the pair", accessibility.getNextStartedActivity());
            assertEquals("native halfway geometry cannot replace the requested ratio", target,
                    Prefs.ratio(split, left, right));
        }
        windows(left, right, target == 70 ? 30 : 70, 1000);
        idle(3600);
        assertEquals("one final target, never an app-programmed midpoint", 1,
                accessibility.getGesturesDispatched().size());
        assertEquals(target * 10, gestureEnd(0), 1);
        ShadowAccessibilityService.GestureDispatch gesture = accessibility.getGesturesDispatched().get(0);
        windows(left, right, target, 1000);
        gesture.callback().onCompleted(gesture.description());
        idle(2500);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(target, Prefs.ratio(split, left, right));
        assertEquals(target, Prefs.ratio(split, LEFT, RIGHT));
        assertEquals(41, Prefs.ratio(split, right, left));
        assertEquals(left, Prefs.side(split, Prefs.LEFT));
        assertEquals(right, Prefs.side(split, Prefs.RIGHT));
        assertEquals("completion must measure the final covering geometry", target,
                ReflectionHelpers.<Integer>getField(split, "blockedAt").intValue());
    }

    @Test public void rapidPicksQueueOnlyLatestWithoutUnlockingPreviousTransition() {
        Prefs.setRatio(split, LEFT, RIGHT, 70);
        split.runPair(LEFT, RIGHT);
        idle(700);
        Intent oldBridge = accessibility.getNextStartedActivity();
        assertNotNull(oldBridge);
        int oldToken = oldBridge.getIntExtra("g", -1);
        pick(Prefs.LEFT, NEW, false);
        pick(Prefs.LEFT, LATEST, false);
        windows(RIGHT, LEFT, 50, 1000);
        split.afterPair(LEFT, RIGHT, oldToken);
        idle(2000);
        assertEquals(LATEST, Prefs.side(split, Prefs.LEFT));
        assertEquals(RIGHT, Prefs.side(split, Prefs.RIGHT));
        assertNull("do not lower the previous busy safety interval", accessibility.getNextStartedActivity());
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        idle(24000);
        Intent latest = accessibility.getNextStartedActivity();
        assertNotNull("latest intent must not be silently lost", latest);
        assertEquals(LATEST, latest.getStringExtra("l"));
        assertEquals(RIGHT, latest.getStringExtra("r"));
        assertNull("intermediate pick must never launch", accessibility.getNextStartedActivity());
        assertEquals(70, Prefs.ratio(split, LATEST, RIGHT));
        windows(LATEST, RIGHT, 30, 1000);
        split.afterPair(LATEST, RIGHT, latest.getIntExtra("g", -1));
        idle(2500);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(700, gestureEnd(0), 1);
    }

    @Test public void supersededActivityCannotLaunchItsRemainingAppsOrFinishANewerPair() {
        Prefs.setRatio(split, LEFT, RIGHT, 30);
        split.runPair(LEFT, RIGHT);
        idle(700);
        Intent bridge = accessibility.getNextStartedActivity();
        bridgeController = Robolectric.buildActivity(PairActivity.class, bridge).create().start().resume();
        pick(Prefs.RIGHT, NEW, false);
        idle(4500);
        assertNull(Shadows.shadowOf(bridgeController.get()).getNextStartedActivity());
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        assertEquals(NEW, Prefs.side(split, Prefs.RIGHT));
        assertTrue("stale bridge cannot clear the native transition safety interval",
                ReflectionHelpers.<Long>getField(split, "busyUntil") > android.os.SystemClock.elapsedRealtime());
    }

    @Test public void pickedPairWaitsForFullCoverageAndNeverLearnsItsNativeHalfwayGeometry() {
        Prefs.setRatio(split, LEFT, RIGHT, 30);
        pick(Prefs.LEFT, NEW, false);
        idle(700);
        Intent bridge = accessibility.getNextStartedActivity();
        assertNotNull(bridge);
        windows(NEW, RIGHT, 30, 500);
        split.afterPair(NEW, RIGHT, bridge.getIntExtra("g", -1));
        idle(6000);
        assertTrue("identified but incomplete new panes must not be resized", accessibility.getGesturesDispatched().isEmpty());
        assertNull("slow geometry must not trigger a relaunch", accessibility.getNextStartedActivity());
        assertEquals(30, Prefs.ratio(split, NEW, RIGHT));
        windows(NEW, RIGHT, 50, 1000);
        idle(3000);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(300, gestureEnd(0), 1);
        ShadowAccessibilityService.GestureDispatch gesture = accessibility.getGesturesDispatched().get(0);
        windows(NEW, RIGHT, 30, 1000);
        gesture.callback().onCompleted(gesture.description());
        idle(2500);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(30, Prefs.ratio(split, NEW, RIGHT));
    }

    @Test public void nativeMinimumWidthKeepsRequestedRatioInsteadOfSavingFiftyAsSuccess() {
        Prefs.setRatio(split, LEFT, RIGHT, 30);
        pick(Prefs.RIGHT, NEW, false);
        idle(700);
        Intent bridge = accessibility.getNextStartedActivity();
        windows(LEFT, NEW, 50, 1000);
        split.afterPair(LEFT, NEW, bridge.getIntExtra("g", -1));
        idle(2500);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(300, gestureEnd(0), 1);
        ShadowAccessibilityService.GestureDispatch gesture = accessibility.getGesturesDispatched().get(0);
        gesture.callback().onCompleted(gesture.description());
        idle(6000);
        assertEquals("unmoved native divider must not cause endless retries", 1,
                accessibility.getGesturesDispatched().size());
        assertEquals(30, Prefs.ratio(split, LEFT, NEW));
        assertEquals(50, ReflectionHelpers.<Integer>getField(split, "blockedAt").intValue());
        assertFalse(ReflectionHelpers.<Boolean>getField(split, "ratioArmed"));
    }

    @Test public void distinctLaunchesAtTheSameClockTickBothOpenAdjacent() {
        split.runPair(LEFT, RIGHT);
        idle(700);
        Intent first = accessibility.getNextStartedActivity();
        bridgeController = Robolectric.buildActivity(PairActivity.class, first).create().start().resume();
        idle(400);
        assertNotNull(Shadows.shadowOf(bridgeController.get()).getNextStartedActivity());
        bridgeController.destroy();
        ReflectionHelpers.setField(split, "busyUntil", 0L);
        split.runPair(LEFT, RIGHT);
        idle(700);
        Intent second = accessibility.getNextStartedActivity();
        second.putExtra("t", first.getLongExtra("t", -1));
        assertNotEquals(first.getIntExtra("g", -1), second.getIntExtra("g", -1));
        bridgeController = Robolectric.buildActivity(PairActivity.class, second).create().start().resume();
        idle(400);
        assertNotNull("a distinct generation must not be suppressed by its timestamp",
                Shadows.shadowOf(bridgeController.get()).getNextStartedActivity());
    }

    @Test public void recreatedBridgeResumesRemainingStagesWithoutRepeatingAdjacent() {
        split.runPair(LEFT, RIGHT);
        idle(700);
        Intent intent = accessibility.getNextStartedActivity();
        bridgeController = Robolectric.buildActivity(PairActivity.class, intent).create().start().resume();
        idle(400);
        assertNotNull(Shadows.shadowOf(bridgeController.get()).getNextStartedActivity());
        android.os.Bundle state = new android.os.Bundle();
        bridgeController.pause().saveInstanceState(state).stop().destroy();
        bridgeController = Robolectric.buildActivity(PairActivity.class, intent).create(state).start().resume();
        idle(1400);
        Intent same = Shadows.shadowOf(bridgeController.get()).getNextStartedActivity();
        assertNotNull("recreation must continue the same-side stage", same);
        assertEquals(LEFT, same.getComponent().getPackageName());
        assertEquals(0, same.getFlags() & 0x00001000);
        windows(LEFT, RIGHT, 50, 1000);
        idle(900);
        assertTrue(bridgeController.get().isFinishing());
        assertNull(Shadows.shadowOf(bridgeController.get()).getNextStartedActivity());
    }

    @Test public void oldBridgeCannotMatchReplacementServiceWithSamePair() {
        split.runPair(LEFT, RIGHT);
        idle(700);
        Intent oldIntent = accessibility.getNextStartedActivity();
        split.onUnbind(null);
        splitController.destroy();
        splitController = Robolectric.buildService(SplitService.class).create();
        split = splitController.get();
        ReflectionHelpers.setStaticField(SplitService.class, "inst", split);
        accessibility = Shadows.shadowOf(split);
        accessibility.setCanDispatchGestures(true);
        split.runPair(LEFT, RIGHT);
        bridgeController = Robolectric.buildActivity(PairActivity.class, oldIntent).create().start().resume();
        idle(400);
        assertTrue("old bridge must finish rather than open in the new service session", bridgeController.get().isFinishing());
        assertNull(Shadows.shadowOf(bridgeController.get()).getNextStartedActivity());
    }

    @Test public void busyResizeSwapThenPickerKeepsPhysicalSideAndRatio() {
        Prefs.setRatio(split, LEFT, RIGHT, 30);
        windows(LEFT, RIGHT, 50, 1000);
        split.applyRatio(LEFT, RIGHT);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertTrue(ReflectionHelpers.<Long>getField(split, "busyUntil") > android.os.SystemClock.elapsedRealtime());
        ReflectionHelpers.setStaticField(SplitController.class, "lastSwap", -4000L);
        SplitController.swapSides(split);
        assertEquals("rejected native swap must not change the selected physical left app", LEFT, Prefs.side(split, Prefs.LEFT));
        assertEquals(RIGHT, Prefs.side(split, Prefs.RIGHT));
        assertEquals(30, Prefs.ratio(split, LEFT, RIGHT));
        pick(Prefs.RIGHT, NEW, false);
        assertEquals(LEFT, Prefs.side(split, Prefs.LEFT));
        assertEquals(NEW, Prefs.side(split, Prefs.RIGHT));
        assertEquals(30, Prefs.ratio(split, LEFT, NEW));
        idle(6000);
        Intent bridge = accessibility.getNextStartedActivity();
        assertNotNull(bridge);
        assertEquals(LEFT, bridge.getStringExtra("l"));
        assertEquals(NEW, bridge.getStringExtra("r"));
    }
private void pick(int side, String pkg, boolean history) {
        if (history) {
            String previous = Prefs.side(split, side);
            Prefs.setSide(split, side, pkg);
            Prefs.setSide(split, side, previous);
        }
        View strip = ReflectionHelpers.getField(overlay, "strip");
        EdgeDrawer drawer = ReflectionHelpers.getField(overlay, "edgeDrawer");
        if (drawer != null) {
            assertCollapsedDrawer(drawer, strip);
            assertTrue(drawer.handle.performClick());
            assertEquals(View.VISIBLE, strip.getVisibility());
            assertEquals(View.GONE, drawer.handle.getVisibility());
        }
        assertTrue(strip.findViewById(side == Prefs.LEFT ? R.id.btn_left : R.id.btn_right).performClick());
        View popup = ReflectionHelpers.getField(overlay, "popup");
        assertNotNull(popup);
        if (drawer != null) {
            android.view.MotionEvent outside = android.view.MotionEvent.obtain(0,
                    android.os.SystemClock.uptimeMillis(), android.view.MotionEvent.ACTION_OUTSIDE, 0, 0, 0);
            try { strip.dispatchTouchEvent(outside); } finally { outside.recycle(); }
            assertSame("the picker owns this outside event", popup,
                    ReflectionHelpers.getField(overlay, "popup"));
            assertEquals(View.VISIBLE, strip.getVisibility());
        }
        if (history) {
            ViewGroup rows = popup.findViewById(R.id.ll_history);
            assertTrue(rows.getChildAt(Prefs.history(split, side).indexOf(pkg)).performClick());
        } else {
            GridView grid = popup.findViewById(R.id.gv_all);
            int index = -1;
            for (int i = 0; i < grid.getAdapter().getCount(); i++)
                if (pkg.equals(((AppList.App) grid.getAdapter().getItem(i)).pkg)) index = i;
            assertTrue(index >= 0);
            assertTrue(grid.performItemClick(grid.getAdapter().getView(index, null, grid), index,
                    grid.getAdapter().getItemId(index)));
        }
        if (drawer != null) assertCollapsedDrawer(drawer, strip);
    }

    private void install(String pkg) {
        Context context = split;
        PackageInfo info = new PackageInfo();
        info.packageName = pkg;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = pkg;
        info.applicationInfo.nonLocalizedLabel = pkg;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        ResolveInfo launcher = new ResolveInfo();
        launcher.activityInfo = new ActivityInfo();
        launcher.activityInfo.packageName = pkg;
        launcher.activityInfo.name = pkg + ".MainActivity";
        launcher.activityInfo.applicationInfo = info.applicationInfo;
        launcher.activityInfo.enabled = true;
        launcher.activityInfo.exported = true;
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), launcher);
        Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(pkg), launcher);
        assertNotNull("fixture package must be launchable: " + pkg, context.getPackageManager().getLaunchIntentForPackage(pkg));
    }

    private void windows(String left, String right, int ratio, int width) {
        int edge = ratio * 10 - 4;
        accessibility.setWindows(Arrays.asList(window(1, left, new Rect(0, 0, edge, 400)),
                window(1, right, new Rect(edge + 8, 0, width, 400)),
                window(5, "com.android.systemui", new Rect(edge - 4, 0, edge + 12, 400))));
    }

    private AccessibilityWindowInfo window(int type, String pkg, Rect rect) {
        AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
        root.setPackageName(pkg);
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadows.shadowOf(window);
        shadow.setType(type);
        shadow.setRoot(root);
        shadow.setBoundsInScreen(rect);
        return window;
    }

    private float gestureEnd(int index) {
        PathMeasure measure = new PathMeasure(accessibility.getGesturesDispatched().get(index)
                .description().getStroke(0).getPath(), false);
        float[] end = new float[2];
        measure.getPosTan(measure.getLength(), end, null);
        return end[0];
    }

    private void idle(long millis) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis)); }

    @Test public void edgeLeftGridCarriesThirtyThroughCollapseAndRealBridge() {
        edgeFlow(Prefs.LEFT, 30, false);
    }

    @Test public void edgeRightGridCarriesSeventyThroughCollapseAndRealBridge() {
        edgeFlow(Prefs.RIGHT, 70, false);
    }

    @Test public void edgeLeftHistoryCarriesThirtyThroughCollapseAndRealBridge() {
        edgeFlow(Prefs.LEFT, 30, true);
    }

    @Test public void edgeRightHistoryCarriesSeventyThroughCollapseAndRealBridge() {
        edgeFlow(Prefs.RIGHT, 70, true);
    }

    private void edgeFlow(int side, int target, boolean history) {
        edgeFlow(side, target, history, side == Prefs.RIGHT);
    }

    @Test public void rightEdgeLeftGridCarriesThirty() { edgeFlow(Prefs.LEFT, 30, false, true); }
    @Test public void leftEdgeRightGridCarriesSeventy() { edgeFlow(Prefs.RIGHT, 70, false, false); }
    @Test public void rightEdgeLeftHistoryCarriesThirty() { edgeFlow(Prefs.LEFT, 30, true, true); }
    @Test public void leftEdgeRightHistoryCarriesSeventy() { edgeFlow(Prefs.RIGHT, 70, true, false); }
    private void edgeFlow(int side, int target, boolean history, boolean rightEdge) {
        Prefs.setRatio(split, LEFT, RIGHT, target);
        Prefs.setStripEdge(split, true);
        Prefs.setStripRight(split, rightEdge);
        OverlayService.refresh(split);
        overlay.onSplitChanged(true);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        EdgeDrawer drawer = ReflectionHelpers.getField(overlay, "edgeDrawer");
        View panel = ReflectionHelpers.getField(overlay, "strip");
        assertNotNull(drawer);
        assertCollapsedDrawer(drawer, panel);
        flow(side, target, history);
        assertSame("replacement must not rebuild either drawer window", drawer,
                ReflectionHelpers.getField(overlay, "edgeDrawer"));
        assertSame(panel, ReflectionHelpers.getField(overlay, "strip"));
        assertCollapsedDrawer(drawer, panel);
        drawer.handle.performClick();
        assertEquals(View.VISIBLE, panel.getVisibility());
        assertEquals("%" + target, ((android.widget.TextView) panel.findViewById(R.id.btn_ratio)).getText().toString());
        panel.findViewById(R.id.drawer_close).performClick();
        assertCollapsedDrawer(drawer, panel);
    }

    private void assertCollapsedDrawer(EdgeDrawer drawer, View panel) {
        assertEquals(View.GONE, panel.getVisibility());
        assertEquals(View.VISIBLE, drawer.handle.getVisibility());
        assertTrue(drawer.handle.isAttachedToWindow());
        assertTrue(panel.isAttachedToWindow());
        assertNull(ReflectionHelpers.getField(overlay, "popup"));
        android.os.Handler handler = ReflectionHelpers.getField(overlay, "h");
        Runnable tick = ReflectionHelpers.getField(overlay, "tick");
        assertFalse("collapsed panel has no ticking work", handler.hasCallbacks(tick));
        assertFalse(ReflectionHelpers.<Boolean>getField(overlay, "sensorOn"));
        assertFalse(ReflectionHelpers.<Boolean>getField(overlay, "locOn"));
        assertFalse(ReflectionHelpers.<Boolean>getField(overlay, "weatherEnabled"));
    }
}
