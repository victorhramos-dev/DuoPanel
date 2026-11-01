package com.abdullaherturk.duopanel;

import android.graphics.PathMeasure;
import android.graphics.Rect;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAccessibilityService;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33, qualifiers = "w1000dp-h400dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SplitServiceTest {
    private static final String LEFT = "example.left", RIGHT = "example.right";
    private SplitService service;
    private ShadowAccessibilityService accessibility;

    @Before public void setUp() {
        service = Robolectric.buildService(SplitService.class).create().get();
        accessibility = Shadows.shadowOf(service);
        Prefs.sp(service).edit().clear().commit();
        Prefs.setSide(service, Prefs.LEFT, LEFT);
        Prefs.setSide(service, Prefs.RIGHT, RIGHT);
        Prefs.setRatio(service, LEFT, RIGHT, 30);
        SplitController.setActive(true);
        accessibility.setCanDispatchGestures(true);
        // Robolectric leaves API 31+ maximum bounds empty; API 30 has no setMaxBounds method.
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            android.util.DisplayMetrics display = service.getResources().getDisplayMetrics();
            Object windowConfig = ReflectionHelpers.getField(service.getResources().getConfiguration(), "windowConfiguration");
            ReflectionHelpers.callInstanceMethod(windowConfig, "setMaxBounds",
                    ClassParameter.from(Rect.class, new Rect(0, 0, display.widthPixels, display.heightPixels)));
        }
    }

    @Test @Config(sdk = {28, 30, 33})
    public void openingThirtyPercentInHalfTheScreenRequestsNormalization() {
        pair(150, 500, false);
        service.applyRatio(LEFT, RIGHT);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
        assertEquals(300, gestureEnd(0)[0], 1);
    }

    @Test public void initialLaunchDoesNotMistakeIncompleteRightPaneForFloatingWindow() {
        pair(150, 500, true);
        service.afterPair(LEFT, RIGHT);
        idle(2500);
        assertEquals(1, accessibility.getGesturesDispatched().size());
    }

    @Test public void fullScreenAtRequestedRatioNeedsNoGesture() {
        pair(296, 1000, false);
        service.applyRatio(LEFT, RIGHT);
        idle(10000);
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
    }

    @Test public void healthySplitWithContainedLargeDialogDoesNotResize() {
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(0, 0, 296, 400)),
                window(1, RIGHT, new Rect(304, 0, 1000, 400)),
                window(1, RIGHT, new Rect(350, 20, 950, 380)),
                window(5, "com.android.systemui", new Rect(292, 150, 308, 250))));
        service.applyRatio(LEFT, RIGHT);
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
    }

    @Test public void incompleteLayoutUsesNativeDividerActionBeforeSyntheticGesture() {
        AccessibilityNodeInfo divider = node("com.android.systemui");
        int action = 0x01020001;
        divider.addAction(new AccessibilityNodeInfo.AccessibilityAction(action, "Move left 30%"));
        Shadows.shadowOf(divider).setOnPerformActionListener((id, args) -> id == action);
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(0, 0, 150, 400)),
                window(1, RIGHT, new Rect(158, 0, 500, 400)),
                window(5, divider, new Rect(146, 0, 162, 400))));
        service.applyRatio(LEFT, RIGHT);
        assertEquals(Arrays.asList(action), Shadows.shadowOf(divider).getPerformedActions());
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        idle(2000);
        assertEquals("an accepted action without a layout change must fall back to a gesture",
                1, accessibility.getGesturesDispatched().size());
    }

    @Test public void acceptedNativeActionWithoutMovementFallsBackToDirectGesture() {
        AccessibilityNodeInfo divider = node("com.android.systemui");
        int action = 0x01020001;
        divider.addAction(new AccessibilityNodeInfo.AccessibilityAction(action, "Move left 30%"));
        Shadows.shadowOf(divider).setOnPerformActionListener((id, args) -> id == action);
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(0, 0, 696, 400)),
                window(1, RIGHT, new Rect(704, 0, 1000, 400)),
                window(5, divider, new Rect(692, 0, 708, 400))));
        service.applyRatio(LEFT, RIGHT);
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        idle(1500);
        assertEquals("an accepted native action must not strand a ready pair at the wrong ratio",
                1, accessibility.getGesturesDispatched().size());
        assertEquals(300, gestureEnd(0)[0], 1);
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
    }

    @Test public void geometryMustSettleEvenWhenLocalPercentageIsUnchanged() {
        Prefs.setRatio(service, LEFT, RIGHT, 50);
        pair(150, 500, false);
        service.afterPair(LEFT, RIGHT);
        idle(1100);
        pair(225, 750, false);
        idle(600);
        assertTrue("window edges are still changing", accessibility.getGesturesDispatched().isEmpty());
        idle(700);
        assertEquals(1, accessibility.getGesturesDispatched().size());
    }

    @Test public void acceptedGestureMustCompleteBeforeVerificationOrAnotherGesture() {
        pair(496, 1000, true);
        service.applyRatio(LEFT, RIGHT);
        ShadowAccessibilityService.GestureDispatch first = accessibility.getGesturesDispatched().get(0);
        assertNotNull(first.callback());
        idle(10000);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
        pair(296, 1000, true);
        first.callback().onCompleted(first.description());
        idle(5000);
        assertEquals(1, accessibility.getGesturesDispatched().size());
    }

    @Test public void cancelledGestureDoesNotSaveIntermediateRatioOrRetry() {
        pair(496, 1000, true);
        service.applyRatio(LEFT, RIGHT);
        ShadowAccessibilityService.GestureDispatch first = accessibility.getGesturesDispatched().get(0);
        assertNotNull(first.callback());
        pair(396, 1000, true);
        first.callback().onCancelled(first.description());
        idle(6000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle(6000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
        assertEquals(1, accessibility.getGesturesDispatched().size());
    }

    @Test public void completedGestureStillRequiresBothWindowsToCoverTheScreen() {
        pair(496, 1000, true);
        service.applyRatio(LEFT, RIGHT);
        ShadowAccessibilityService.GestureDispatch first = accessibility.getGesturesDispatched().get(0);
        assertNotNull(first.callback());
        pair(296, 500, true);
        first.callback().onCompleted(first.description());
        idle(5000);
        assertEquals("correct divider percentage alone is not success", 2,
                accessibility.getGesturesDispatched().size());
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
        ShadowAccessibilityService.GestureDispatch second = accessibility.getGesturesDispatched().get(1);
        second.callback().onCompleted(second.description());
        idle(10000);
        assertEquals("normalization attempts must be bounded", 2, accessibility.getGesturesDispatched().size());
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
    }

    @Test public void completedOldRequestCannotResizeNewlySelectedApps() {
        pair(496, 1000, true);
        service.applyRatio(LEFT, RIGHT);
        ShadowAccessibilityService.GestureDispatch first = accessibility.getGesturesDispatched().get(0);
        assertNotNull(first.callback());
        Prefs.setSide(service, Prefs.RIGHT, "example.replacement");
        first.callback().onCompleted(first.description());
        idle(10000);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
    }

    @Test @Config(qualifiers = "w400dp-h1000dp-port-mdpi")
    public void topBottomSplitAlsoUsesTheWholeAvailableScreen() {
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(0, 0, 400, 150)),
                window(1, RIGHT, new Rect(0, 158, 400, 500))));
        service.applyRatio(LEFT, RIGHT);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(300, gestureEnd(0)[1], 1);
    }

    @Test public void incompleteLayoutAtTargetStillCrossesTouchSlopAndReturnsToTarget() {
        pair(296, 500, true);
        service.applyRatio(LEFT, RIGHT);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        android.graphics.RectF pathBounds = new android.graphics.RectF();
        accessibility.getGesturesDispatched().get(0).description().getStroke(0).getPath().computeBounds(pathBounds, true);
        assertTrue(pathBounds.width() > android.view.ViewConfiguration.get(service).getScaledTouchSlop());
        assertEquals(300, gestureEnd(0)[0], 1);
    }

    @Test public void onlyStableFullScreenManualResizeCanReplaceTheSavedRatio() {
        pair(296, 1000, false);
        service.applyRatio(LEFT, RIGHT);
        pair(396, 500, false);
        idle(6000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle(6000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
        pair(396, 1000, false);
        idle(2000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle(6000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        assertEquals(40, Prefs.ratio(service, LEFT, RIGHT));
    }

    @Test public void embeddedAppWindowsAndPopupsAreNotFloatingPanes() {
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(340, 20, 450, 50)),
                window(1, LEFT, new Rect(0, 0, 496, 400)),
                window(1, RIGHT, new Rect(700, 0, 1000, 400)),
                window(1, RIGHT, new Rect(504, 0, 700, 400)),
                window(5, "com.android.systemui", new Rect(488, 150, 512, 250))));
        service.afterPair(LEFT, RIGHT);
        idle(8000);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(300, gestureEnd(0)[0], 1);
    }

    @Test public void nudgeCrossesTouchSlopMeasuredFromTheOriginalDownPosition() {
        int slop = android.view.ViewConfiguration.get(service).getScaledTouchSlop();
        int down = 300 + slop;
        pair(down - 4, 500, true);
        service.applyRatio(LEFT, RIGHT);
        android.graphics.RectF bounds = new android.graphics.RectF();
        accessibility.getGesturesDispatched().get(0).description().getStroke(0).getPath().computeBounds(bounds, true);
        float excursion = Math.max(Math.abs(bounds.left - down), Math.abs(bounds.right - down));
        assertTrue(excursion > slop);
        assertEquals(300, gestureEnd(0)[0], 1);
    }

    @Test @Config(sdk = 28)
    public void legacyDisplayWithNavigationBarOnTheLeftKeepsCorrectRatio() {
        android.view.WindowManager wm = (android.view.WindowManager) service.getSystemService(service.WINDOW_SERVICE);
        Shadows.shadowOf(wm.getDefaultDisplay()).setWidth(952);
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(48, 0, 330, 400)),
                window(1, RIGHT, new Rect(338, 0, 1000, 400))));
        service.applyRatio(LEFT, RIGHT);
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
    }

    @Test @Config(sdk = 28)
    public void legacyDisplayWithNavigationBarOnTheRightKeepsCorrectRatio() {
        android.view.WindowManager wm = (android.view.WindowManager) service.getSystemService(service.WINDOW_SERVICE);
        Shadows.shadowOf(wm.getDefaultDisplay()).setWidth(952);
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(0, 0, 282, 400)),
                window(1, RIGHT, new Rect(290, 0, 952, 400))));
        service.applyRatio(LEFT, RIGHT);
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
    }

    @Test public void manualResizeAfterFailedGestureCanStillBeRemembered() {
        pair(496, 1000, true);
        service.applyRatio(LEFT, RIGHT);
        ShadowAccessibilityService.GestureDispatch first = accessibility.getGesturesDispatched().get(0);
        first.callback().onCancelled(first.description());
        pair(596, 1000, true);
        idle(6000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle(6000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        assertEquals(60, Prefs.ratio(service, LEFT, RIGHT));
    }

    @Test public void embeddedLeftAppStillLaunchesAsOneMemberOfThePair() {
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(0, 0, 200, 400)),
                window(1, LEFT, new Rect(200, 0, 496, 400)),
                window(1, RIGHT, new Rect(504, 0, 1000, 400))));
        assertTrue(service.isSplitActive());
        assertEquals(500, SplitController.divider().x);
        assertEquals(200, SplitController.divider().y);
        service.afterPair(LEFT, RIGHT);
        idle(8000);
        assertEquals(1, accessibility.getGesturesDispatched().size());
    }

    @Test public void disconnectedWindowsOfTheSameAppDoNotHideMissingCoverage() {
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(0, 0, 296, 400)),
                window(1, RIGHT, new Rect(304, 0, 500, 400)),
                window(1, RIGHT, new Rect(700, 0, 1000, 400)),
                window(5, "com.android.systemui", new Rect(288, 150, 312, 250))));
        service.applyRatio(LEFT, RIGHT);
        assertEquals(1, accessibility.getGesturesDispatched().size());
    }

    @Test public void staleRatioCallbackCannotUnlockANewerSwap() {
        pair(496, 1000, true);
        service.applyRatio(LEFT, RIGHT);
        ShadowAccessibilityService.GestureDispatch first = accessibility.getGesturesDispatched().get(0);
        idle(5000);
        service.nativeSwap();
        first.callback().onCancelled(first.description());
        service.runPair(LEFT, RIGHT, true);
        idle(700);
        assertNull("launch must remain blocked while swap owns the operation", accessibility.getNextStartedActivity());
    }

    @Test public void nativeSnapRoundingDoesNotOverwriteRequestedRatioWithoutManualMovement() {
        pair(496, 1000, true);
        service.applyRatio(LEFT, RIGHT);
        ShadowAccessibilityService.GestureDispatch first = accessibility.getGesturesDispatched().get(0);
        pair(336, 1000, true);
        first.callback().onCompleted(first.description());
        idle(7000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        idle(6000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
    }

    @Test @Config(sdk = {28, 30, 33})
    public void seventyToThirtyDispatchesTheFinalTargetWithoutHalfwayStage() {
        assertDirectResize(70, 30);
    }

    @Test @Config(sdk = {28, 30, 33})
    public void thirtyToSeventyDispatchesTheFinalTargetWithoutHalfwayStage() {
        assertDirectResize(30, 70);
    }

    private void assertDirectResize(int initial, int target) {
        Prefs.setRatio(service, LEFT, RIGHT, target);
        pair(initial * 10 - 4, 1000, true);
        service.applyRatio(LEFT, RIGHT);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals("the first gesture must target the requested ratio, not 50%",
                target * 10, gestureEnd(0)[0], 1);
        ShadowAccessibilityService.GestureDispatch gesture = accessibility.getGesturesDispatched().get(0);
        idle(6000);
        assertEquals("acceptance alone cannot start another adjustment", 1,
                accessibility.getGesturesDispatched().size());
        pair(target * 10 - 4, 1000, true);
        gesture.callback().onCompleted(gesture.description());
        idle(8000);
        service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED));
        assertEquals("a completed, covering resize needs no halfway gesture", 1,
                accessibility.getGesturesDispatched().size());
        assertEquals(target, Prefs.ratio(service, LEFT, RIGHT));
        assertEquals(LEFT, Prefs.side(service, Prefs.LEFT));
        assertEquals(RIGHT, Prefs.side(service, Prefs.RIGHT));
    }

    @Test public void readyPairAtSeventyStartsRestoringThirtyWithoutFiveSecondSleep() {
        assertReadyPairStartsPromptly(70, 30);
    }

    @Test public void readyPairAtThirtyStartsRestoringSeventyWithoutFiveSecondSleep() {
        assertReadyPairStartsPromptly(30, 70);
    }

    private void assertReadyPairStartsPromptly(int initial, int target) {
        Prefs.setRatio(service, LEFT, RIGHT, target);
        pair(initial * 10 - 4, 1000, true);
        service.afterPair(LEFT, RIGHT);
        // Allows the existing 900ms pair check and two 700ms geometry samples;
        // this is a deterministic scheduler budget, not a physical timing claim.
        idle(2500);
        assertEquals("a ready non-Maps pair must not wait a fixed five seconds", 1,
                accessibility.getGesturesDispatched().size());
        assertEquals(target * 10, gestureEnd(0)[0], 1);
        assertEquals(target, Prefs.ratio(service, LEFT, RIGHT));
        assertEquals(LEFT, Prefs.side(service, Prefs.LEFT));
        assertEquals(RIGHT, Prefs.side(service, Prefs.RIGHT));
    }

    @Test public void slowAppIsNotResizedOrRelaunchedBeforeBothMembersAreIdentified() {
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(0, 0, 696, 400)),
                window(1, "example.loading", new Rect(704, 0, 1000, 400)),
                window(5, "com.android.systemui", new Rect(692, 0, 708, 400))));
        service.afterPair(LEFT, RIGHT);
        idle(2100);
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        assertNull("an unidentified loading pane must not trigger a relaunch",
                accessibility.getNextStartedActivity());
        assertEquals(LEFT, Prefs.side(service, Prefs.LEFT));
        assertEquals(RIGHT, Prefs.side(service, Prefs.RIGHT));
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
        pair(696, 1000, true);
        idle(8000);
        assertEquals("normalization resumes once both apps are identified", 1,
                accessibility.getGesturesDispatched().size());
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
        assertNull(accessibility.getNextStartedActivity());
    }

    @Test public void slowPaneIsNotRelaunchedAfterTheOriginalThreeIdentificationChecks() {
        accessibility.setWindows(Arrays.asList(
                window(1, LEFT, new Rect(0, 0, 696, 400)),
                window(1, "example.loading", new Rect(704, 0, 1000, 400)),
                window(5, "com.android.systemui", new Rect(692, 0, 708, 400))));
        service.afterPair(LEFT, RIGHT);
        idle(9000);
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        assertNull(accessibility.getNextStartedActivity());
        pair(696, 1000, true);
        idle(3000);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(300, gestureEnd(0)[0], 1);
    }

    @Test public void oemReversalTransfersTheComplementRatherThanAnOldReversePairRatio() {
        Prefs.setRatio(service, RIGHT, LEFT, 50);
        accessibility.setWindows(Arrays.asList(
                window(1, RIGHT, new Rect(0, 0, 296, 400)),
                window(1, LEFT, new Rect(304, 0, 1000, 400)),
                window(5, "com.android.systemui", new Rect(292, 0, 308, 400))));
        service.afterPair(LEFT, RIGHT);
        idle(2500);
        assertEquals(RIGHT, Prefs.side(service, Prefs.LEFT));
        assertEquals(LEFT, Prefs.side(service, Prefs.RIGHT));
        assertEquals(70, Prefs.ratio(service, RIGHT, LEFT));
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(700, gestureEnd(0)[0], 1);
    }

    @Test public void staleAfterPairCannotLearnOemReversalOrUnlockLatestSelection() {
        service.runPair(LEFT, RIGHT);
        int old = service.pairGeneration();
        Prefs.replaceSideKeepingRatio(service, Prefs.LEFT, "example.new");
        service.runPair("example.new", RIGHT);
        accessibility.setWindows(Arrays.asList(
                window(1, RIGHT, new Rect(0, 0, 496, 400)),
                window(1, "example.new", new Rect(504, 0, 1000, 400)),
                window(5, "com.android.systemui", new Rect(492, 0, 508, 400))));
        service.afterPair(LEFT, RIGHT, old);
        idle(2000);
        assertEquals("example.new", Prefs.side(service, Prefs.LEFT));
        assertEquals(RIGHT, Prefs.side(service, Prefs.RIGHT));
        assertFalse(Prefs.get(service, "swap", false));
        assertTrue(ReflectionHelpers.<Long>getField(service, "busyUntil")
                > android.os.SystemClock.elapsedRealtime());
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        assertNull(accessibility.getNextStartedActivity());
    }

    @Test public void staleGenerationIsRejectedEvenWhenSelectionReturnsToTheSamePair() {
        service.runPair(LEFT, RIGHT);
        int old = service.pairGeneration();
        Prefs.replaceSideKeepingRatio(service, Prefs.LEFT, "example.new");
        service.runPair("example.new", RIGHT);
        Prefs.replaceSideKeepingRatio(service, Prefs.LEFT, LEFT);
        service.runPair(LEFT, RIGHT);
        pair(696, 1000, true);
        service.afterPair(LEFT, RIGHT, old);
        idle(2500);
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        assertNull(accessibility.getNextStartedActivity());
        idle(24000);
        android.content.Intent launch = accessibility.getNextStartedActivity();
        assertNotNull(launch);
        assertEquals(LEFT, launch.getStringExtra("l"));
        assertTrue(launch.getIntExtra("g", -1) != old);
        assertNull(accessibility.getNextStartedActivity());
    }

    @Test public void busyDuplicateDoesNotRewriteTheInFlightKeepOrRatioTrackingState() {
        service.runPair(LEFT, RIGHT, true);
        long operation = ReflectionHelpers.getField(service, "ratioOpAt");
        int token = service.pairGeneration();
        idle(100);
        service.runPair(LEFT, RIGHT, false);
        assertTrue(ReflectionHelpers.<Boolean>getField(service, "pairKeep"));
        assertEquals(operation, ReflectionHelpers.<Long>getField(service, "ratioOpAt").longValue());
        assertEquals(token, service.pairGeneration());
    }

    @Test public void oldSwapMeasurementCannotOverwriteTheReplacementRatio() {
        pair(696, 1000, true);
        ReflectionHelpers.setField(service, "h", new android.os.Handler(Looper.getMainLooper()));
        ReflectionHelpers.callInstanceMethod(service, "keepRatioAfterSwap");
        Prefs.replaceSideKeepingRatio(service, Prefs.LEFT, "example.new");
        accessibility.setWindows(Arrays.asList(
                window(1, "example.new", new Rect(0, 0, 496, 400)),
                window(1, RIGHT, new Rect(504, 0, 1000, 400)),
                window(5, "com.android.systemui", new Rect(492, 0, 508, 400))));
        idle(2000);
        assertEquals(30, Prefs.ratio(service, "example.new", RIGHT));
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
    }

    @Test public void realStartupRestoresThirtyPromptlyWithoutKeep() {
        assertRealStartupRestoresThirtyPromptly(false);
    }

    @Test public void realStartupRestoresThirtyPromptlyWithKeep() {
        assertRealStartupRestoresThirtyPromptly(true);
    }

    private void assertRealStartupRestoresThirtyPromptly(boolean keep) {
        service.runPair(LEFT, RIGHT, keep);
        idle(700);
        assertNotNull("runPair must emit the launch bridge before the callback",
                accessibility.getNextStartedActivity());
        assertTrue("launch alone must not resize", accessibility.getGesturesDispatched().isEmpty());
        // Apps are on the correct sides; only their initial widths are inverted.
        pair(696, 1000, true);
        // Represents PairActivity's public completion callback, without launching it.
        service.afterPair(LEFT, RIGHT);
        idle(2500);
        assertEquals("a ready non-Maps startup must resize within 2500ms of its callback (keep="
                        + keep + ")", 1, accessibility.getGesturesDispatched().size());
        assertEquals("the first gesture must target 30% directly, never 50%",
                300, gestureEnd(0)[0], 1);
        assertEquals(30, Prefs.ratio(service, LEFT, RIGHT));
        assertEquals(LEFT, Prefs.side(service, Prefs.LEFT));
        assertEquals(RIGHT, Prefs.side(service, Prefs.RIGHT));
    }

    @Test public void mapsDrivePreferenceDoesNotDelayNonMapsStartup() {
        Prefs.set(service, "mapsDrive", true);
        assertRealStartupRestoresThirtyPromptly(false);
    }

    @Test public void mapsDrivePreferenceDoesNotDelayNonMapsReturn() {
        Prefs.set(service, "mapsDrive", true);
        assertRealStartupRestoresThirtyPromptly(true);
    }

    @Test public void mapsDriveStartupWaitsForResendBeforeResizing() {
        assertMapsResendPrecedesResize(false);
    }

    @Test public void mapsDriveReturnWaitsForResendBeforeResizing() {
        assertMapsResendPrecedesResize(true);
    }

    private void assertMapsResendPrecedesResize(boolean keep) {
        Prefs.set(service, "mapsDrive", true);
        Prefs.setSide(service, Prefs.LEFT, AppList.MAPS);
        Prefs.setRatio(service, AppList.MAPS, RIGHT, 30);
        // Two recognized tabs force the real keep=true normal-map resend path.
        Prefs.sp(service).edit().putString("mapsTabIds", "test:tab").commit();
        AccessibilityNodeInfo maps = node(AppList.MAPS);
        maps.setViewIdResourceName("test:tab");
        AccessibilityNodeInfo tab = node(AppList.MAPS);
        tab.setViewIdResourceName("test:tab");
        Shadows.shadowOf(maps).addChild(tab);
        service.runPair(AppList.MAPS, RIGHT, keep);
        idle(700);
        assertNotNull(accessibility.getNextStartedActivity());
        accessibility.setWindows(Arrays.asList(
                window(1, maps, new Rect(0, 0, 696, 400)),
                window(1, RIGHT, new Rect(704, 0, 1000, 400)),
                window(5, "com.android.systemui", new Rect(692, 0, 708, 400))));
        service.afterPair(AppList.MAPS, RIGHT);
        idle(900);
        long resendDelay = keep ? 1800 : 1200;
        idle(resendDelay - 1);
        assertNull("drive intent must still be pending", accessibility.getNextStartedActivity());
        assertTrue("no resize while the drive resend is pending",
                accessibility.getGesturesDispatched().isEmpty());
        idle(1);
        android.content.Intent resend = accessibility.getNextStartedActivity();
        assertNotNull("exercise the actual drive resend, including keep=true", resend);
        assertEquals(AppList.MAPS, resend.getPackage());
        assertEquals("google.navigation:/?free=1&mode=d", resend.getDataString());
        assertTrue(accessibility.getGesturesDispatched().isEmpty());
        idle(7000);
        assertEquals(1, accessibility.getGesturesDispatched().size());
        assertEquals(300, gestureEnd(0)[0], 1);
        assertEquals(30, Prefs.ratio(service, AppList.MAPS, RIGHT));
        assertEquals(AppList.MAPS, Prefs.side(service, Prefs.LEFT));
        assertEquals(RIGHT, Prefs.side(service, Prefs.RIGHT));
    }

    private void pair(int leftEdge, int rightEdge, boolean divider) {
        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        windows.add(window(1, LEFT, new Rect(0, 0, leftEdge, 400)));
        windows.add(window(1, RIGHT, new Rect(leftEdge + 8, 0, rightEdge, 400)));
        if (divider) windows.add(window(5, "com.android.systemui", new Rect(leftEdge - 4, 0, leftEdge + 12, 400)));
        accessibility.setWindows(windows);
    }

    private AccessibilityNodeInfo node(String pkg) {
        AccessibilityNodeInfo node = AccessibilityNodeInfo.obtain();
        node.setPackageName(pkg);
        return node;
    }

    private AccessibilityWindowInfo window(int type, String pkg, Rect bounds) {
        return window(type, node(pkg), bounds);
    }

    private AccessibilityWindowInfo window(int type, AccessibilityNodeInfo root, Rect bounds) {
        AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo shadow = Shadows.shadowOf(window);
        shadow.setType(type);
        shadow.setRoot(root);
        shadow.setBoundsInScreen(bounds);
        return window;
    }

    private float[] gestureEnd(int index) {
        PathMeasure measure = new PathMeasure(accessibility.getGesturesDispatched().get(index)
                .description().getStroke(0).getPath(), false);
        float[] end = new float[2];
        measure.getPosTan(measure.getLength(), end, null);
        return end;
    }

    private void idle(long millis) {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis));
    }
}
