package com.abdullaherturk.duopanel;

import android.content.Context;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Rect;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
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
import static org.robolectric.Shadows.shadowOf;

/** Real preferences, controller, overlay views and Handler; only the native bridge is recorded. */
@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public class InfoStripSwapTest {
    private static final String LEFT = "test.swap.left", RIGHT = "test.swap.right";

    public static class RecordingSplitService extends SplitService {
        int swaps;
        final List<String> appliedPairs = new ArrayList<String>();
        final List<Integer> appliedRatios = new ArrayList<Integer>();
        @Override public boolean isSplitActive() { return true; }
        @Override public void nativeSwap() { swaps++; }
        @Override public void applyRatioDebounced(String left, String right) {
            appliedPairs.add(left + "|" + right);
            appliedRatios.add(Prefs.ratio(this, left, right));
        }
    }

    private Context context;
    private ServiceController<OverlayService> overlayController;
    private ServiceController<RecordingSplitService> bridgeController;
    private OverlayService overlay;
    private RecordingSplitService bridge;
    private List<String> leftHistory, rightHistory;
    private long downTime;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        Prefs.sp(context).edit().clear().commit();
        SplitController.setActive(false);
        SplitController.setDivider(null);
        ReflectionHelpers.setStaticField(SplitController.class, "lastSwap", -4000L);
        ReflectionHelpers.setStaticField(SplitService.class, "inst", null);
        ShadowSettings.setCanDrawOverlays(true);
        Prefs.set(context, "bubble", false);
        Prefs.set(context, "strip", true);
        Prefs.set(context, "dividerSwap", true);
        Prefs.setStripEdge(context, true);
        Prefs.setSide(context, Prefs.LEFT, "older.left");
        Prefs.setSide(context, Prefs.RIGHT, "older.right");
        Prefs.setSide(context, Prefs.LEFT, LEFT);
        Prefs.setSide(context, Prefs.RIGHT, RIGHT);
        leftHistory = Prefs.history(context, Prefs.LEFT);
        rightHistory = Prefs.history(context, Prefs.RIGHT);
        Prefs.setRatio(context, LEFT, RIGHT, 30);
        Prefs.setRatio(context, RIGHT, LEFT, 50);
        bridgeController = Robolectric.buildService(RecordingSplitService.class).create();
        bridge = bridgeController.get();
        ReflectionHelpers.setStaticField(SplitService.class, "inst", bridge);
        SplitController.setActive(true);
        overlayController = Robolectric.buildService(OverlayService.class).create();
        overlay = overlayController.get();
        // Avoid weather/network work while exercising the actual overlay tick and listeners.
        ReflectionHelpers.setField(overlay, "hasWx", true);
        overlayController.startCommand(0, 1);
        overlay.onSplitChanged(true);
        layoutStrip();
        shadowOf(Looper.getMainLooper()).idle();
    }

    @After public void teardown() {
        if (overlayController != null) overlayController.destroy();
        if (bridgeController != null) bridgeController.destroy();
        ReflectionHelpers.setStaticField(SplitService.class, "inst", null);
        ReflectionHelpers.setStaticField(OverlayService.class, "inst", null);
        SplitController.setActive(false);
        SplitController.setDivider(null);
        ReflectionHelpers.setStaticField(SplitController.class, "lastSwap", -4000L);
        Prefs.sp(context).edit().clear().commit();
    }

    private View strip() { return ReflectionHelpers.getField(overlay, "strip"); }
    private View button() { return strip().findViewById(R.id.btn_swap); }
    private void layoutStrip() {
        View strip = strip();
        assertNotNull(strip);
        WindowManager.LayoutParams lp = ReflectionHelpers.getField(overlay, "stripLp");
        int width = lp.width > 0 ? lp.width : context.getResources().getDisplayMetrics().widthPixels;
        strip.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(lp.height, View.MeasureSpec.EXACTLY));
        strip.layout(0, 0, width, lp.height);
        assertTrue(button().getWidth() > 0);
        assertTrue(button().getHeight() > 0);
        assertEquals(View.VISIBLE, button().getVisibility());
    }
    private void rebuild() {
        OverlayService.refresh(context);
        overlay.onSplitChanged(true);
        layoutStrip();
    }
    private void advance(long millis) {
        shadowOf(Looper.getMainLooper()).idleFor(millis, TimeUnit.MILLISECONDS);
    }
    private void touch(View view, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private void centerTouch(View view, int action) {
        touch(view, action, view.getWidth() / 2f, view.getHeight() / 2f);
    }
    private View press() {
        View view = button();
        downTime = SystemClock.uptimeMillis();
        centerTouch(view, MotionEvent.ACTION_DOWN);
        return view;
    }
    private void release(View view) {
        centerTouch(view, MotionEvent.ACTION_UP);
        shadowOf(Looper.getMainLooper()).idle();
    }
    private void assertUnchanged(int ratio) {
        assertEquals(ratio, Prefs.ratio(context, LEFT, RIGHT));
        assertEquals(LEFT, Prefs.side(context, Prefs.LEFT));
        assertEquals(RIGHT, Prefs.side(context, Prefs.RIGHT));
        assertEquals(leftHistory, Prefs.history(context, Prefs.LEFT));
        assertEquals(rightHistory, Prefs.history(context, Prefs.RIGHT));
        assertEquals(50, Prefs.ratio(context, RIGHT, LEFT));
        assertEquals(0, bridge.swaps);
    }
    private void assertInverted(int ratio) {
        assertUnchanged(ratio);
        assertEquals("%" + ratio, ((TextView) strip().findViewById(R.id.btn_ratio)).getText().toString());
        assertEquals(1, bridge.appliedPairs.size());
        assertEquals(LEFT + "|" + RIGHT, bridge.appliedPairs.get(0));
        assertEquals(Integer.valueOf(ratio), bridge.appliedRatios.get(0));
    }
    private void assertSwapped() {
        assertEquals(RIGHT, Prefs.side(context, Prefs.LEFT));
        assertEquals(LEFT, Prefs.side(context, Prefs.RIGHT));
        assertEquals(70, Prefs.ratio(context, RIGHT, LEFT));
        List<String> expectedLeft = new ArrayList<String>(leftHistory);
        expectedLeft.add(0, RIGHT);
        List<String> expectedRight = new ArrayList<String>(rightHistory);
        expectedRight.add(0, LEFT);
        assertEquals(expectedLeft, Prefs.history(context, Prefs.LEFT));
        assertEquals(expectedRight, Prefs.history(context, Prefs.RIGHT));
        assertEquals(1, bridge.swaps);
        assertTrue(bridge.appliedPairs.isEmpty());
    }

    @Test public void shortTapStillSwapsApps() {
        View view = press();
        advance(100);
        release(view);
        assertSwapped();
    }

    @Test public void threeSecondHoldInvertsRatioWithoutSwapping() {
        View view = press();
        advance(2999);
        assertUnchanged(30);
        assertTrue(bridge.appliedPairs.isEmpty());
        advance(1);
        // Keep this numeric assertion first: the old click-only binding must fail 70 != 30.
        assertEquals(70, Prefs.ratio(context, LEFT, RIGHT));
        assertInverted(70);
        advance(6000);
        assertInverted(70);
        release(view);
        advance(4000);
        assertInverted(70);
    }

    @Test public void floatingHoldInvertsSeventyToThirty() {
        Prefs.setStripEdge(context, false);
        Prefs.setRatio(context, LEFT, RIGHT, 70);
        rebuild();
        View view = press();
        advance(3000);
        assertInverted(30);
        release(view);
        assertInverted(30);
    }

    @Test public void fiftyFiftyHoldLeavesAppsAndHistoriesUntouched() {
        Prefs.setRatio(context, LEFT, RIGHT, 50);
        rebuild();
        View view = press();
        advance(3000);
        assertUnchanged(50);
        assertEquals("%50", ((TextView) strip().findViewById(R.id.btn_ratio)).getText().toString());
        // An implementation may skip the unnecessary native apply for the unchanged ratio.
        assertTrue(bridge.appliedPairs.size() <= 1);
        int applies = bridge.appliedPairs.size();
        advance(6000);
        release(view);
        assertUnchanged(50);
        assertEquals(applies, bridge.appliedPairs.size());
    }

    @Test public void releasingAt2999MillisStillSwaps() {
        View view = press();
        advance(2999);
        assertUnchanged(30);
        release(view);
        assertSwapped();
        advance(4000);
        assertSwapped();
    }

    @Test public void defaultFiveHundredMillisLongPressDoesNotInvertEarly() {
        View view = press();
        advance(500);
        assertUnchanged(30);
        assertTrue(bridge.appliedPairs.isEmpty());
        advance(2499);
        assertUnchanged(30);
        assertTrue(bridge.appliedPairs.isEmpty());
        advance(1);
        assertInverted(70);
        release(view);
        assertInverted(70);
    }

    @Test public void actionCancelDisarmsHoldAndRelease() {
        View view = press();
        advance(2000);
        centerTouch(view, MotionEvent.ACTION_CANCEL);
        advance(4000);
        release(view);
        assertUnchanged(30);
        assertTrue(bridge.appliedPairs.isEmpty());
    }

    @Test public void draggingOutAndBackCancelsHoldAndClickInBothModes() {
        for (boolean edge : new boolean[]{true, false}) {
            Prefs.setStripEdge(context, edge);
            rebuild();
            View view = press();
            advance(1000);
            float outside = view.getWidth() + ViewConfiguration.get(context).getScaledTouchSlop() * 3f;
            touch(view, MotionEvent.ACTION_MOVE, outside, view.getHeight() / 2f);
            centerTouch(view, MotionEvent.ACTION_MOVE);
            advance(4000);
            release(view);
            assertUnchanged(30);
            assertTrue(bridge.appliedPairs.isEmpty());
        }
    }

    private void twoPointerEvent(View view, int action) {
        twoPointerEvent(view, action, view.getWidth() / 2f, view.getHeight() / 2f,
                view.getWidth() / 2f + 1, view.getHeight() / 2f + 1);
    }

    private void twoPointerEvent(View view, int action, float ax, float ay, float bx, float by) {
        MotionEvent.PointerProperties first = new MotionEvent.PointerProperties();
        first.id = 0;
        first.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerProperties second = new MotionEvent.PointerProperties();
        second.id = 1;
        second.toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords a = new MotionEvent.PointerCoords();
        a.x = ax; a.y = ay; a.pressure = 1; a.size = 1;
        MotionEvent.PointerCoords b = new MotionEvent.PointerCoords();
        b.x = bx; b.y = by; b.pressure = 1; b.size = 1;
        MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(),
                action | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT), 2,
                new MotionEvent.PointerProperties[]{first, second},
                new MotionEvent.PointerCoords[]{a, b}, 0, 0, 1, 1, 0, 0, 0, 0);
        try { view.dispatchTouchEvent(event); } finally { event.recycle(); }
    }

    @Test public void secondPointerCancelsEvenAfterReturningToOneFinger() {
        View view = press();
        advance(1000);
        twoPointerEvent(view, MotionEvent.ACTION_POINTER_DOWN);
        twoPointerEvent(view, MotionEvent.ACTION_POINTER_UP);
        advance(4000);
        release(view);
        assertUnchanged(30);
        assertTrue(bridge.appliedPairs.isEmpty());
    }

    @Test public void secondFingerOnSiblingCancelsThroughRealHierarchyInBothModes() {
        for (boolean edge : new boolean[]{true, false}) {
            for (long secondFingerAt : new long[]{1000, 2999}) {
                Prefs.setStripEdge(context, edge);
                rebuild();
                ViewGroup root = (ViewGroup) strip();
                assertTrue(root.isMotionEventSplittingEnabled());
                android.view.ViewParent parent = button().getParent();
                while (!(parent instanceof android.widget.HorizontalScrollView)) {
                    parent = parent.getParent();
                    assertNotNull(parent);
                }
                android.widget.HorizontalScrollView scroll = (android.widget.HorizontalScrollView) parent;
                // fullScroll otherwise starts an animation: with the PAUSED looper the
                // button is still clipped when the root receives the initial DOWN.
                scroll.setSmoothScrollingEnabled(false);
                assertTrue(scroll.fullScroll(View.FOCUS_RIGHT));
                Rect swap = new Rect(), sibling = new Rect();
                button().getDrawingRect(swap);
                root.offsetDescendantRectToMyCoords(button(), swap);
                View other = root.findViewById(R.id.btn_ratio);
                other.getDrawingRect(sibling);
                root.offsetDescendantRectToMyCoords(other, sibling);
                Rect viewport = new Rect();
                scroll.getDrawingRect(viewport);
                root.offsetDescendantRectToMyCoords(scroll, viewport);
                assertTrue("SWAP center must be visible before dispatch", viewport.contains(
                        swap.centerX(), swap.centerY()));
                assertTrue("Sibling center must be visible before dispatch", viewport.contains(
                        sibling.centerX(), sibling.centerY()));
                assertFalse(swap.contains(sibling.centerX(), sibling.centerY()));
                downTime = SystemClock.uptimeMillis();
                touch(root, MotionEvent.ACTION_DOWN, swap.centerX(), swap.centerY());
                assertTrue(button().isPressed()); // The real dispatch reached SWAP.
                advance(secondFingerAt);
                assertUnchanged(30);
                assertTrue(bridge.appliedPairs.isEmpty());
                twoPointerEvent(root, MotionEvent.ACTION_POINTER_DOWN,
                        swap.centerX(), swap.centerY(), sibling.centerX(), sibling.centerY());
                twoPointerEvent(root, MotionEvent.ACTION_POINTER_UP,
                        swap.centerX(), swap.centerY(), sibling.centerX(), sibling.centerY());
                advance(3000 - secondFingerAt);
                assertUnchanged(30); // Deadline must be canceled even after the second finger lifts.
                assertTrue(bridge.appliedPairs.isEmpty());
                touch(root, MotionEvent.ACTION_UP, swap.centerX(), swap.centerY());
                advance(4000);
                assertUnchanged(30);
                assertTrue(bridge.appliedPairs.isEmpty());
            }
        }
    }

    @Test public void hideThenShowBeforeDeadlineDoesNotReviveHold() {
        View view = press();
        advance(1000);
        overlay.onSplitChanged(false);
        advance(500);
        overlay.onSplitChanged(true);
        advance(2500);
        release(view);
        assertUnchanged(30);
        assertTrue(bridge.appliedPairs.isEmpty());
    }

    @Test public void rebuildingDisarmsDetachedButtonAndAllowsFreshPress() {
        View old = press();
        advance(1000);
        rebuild();
        assertNotSame(old, button());
        advance(4000);
        release(old);
        assertUnchanged(30);
        assertTrue(bridge.appliedPairs.isEmpty());
        View fresh = press();
        advance(3000);
        assertInverted(70);
        release(fresh);
        assertInverted(70);
    }

    @Test public void destroyRemovesPendingHoldAndDisarmsDetachedButton() {
        View old = press();
        advance(1000);
        overlayController.destroy();
        overlayController = null;
        advance(5000);
        release(old);
        assertUnchanged(30);
        assertTrue(bridge.appliedPairs.isEmpty());
    }

    @Test public void canceledHoldDoesNotPoisonNextPress() {
        View old = press();
        advance(2000);
        centerTouch(old, MotionEvent.ACTION_CANCEL);
        View fresh = press();
        advance(1000); // Original deadline must not complete the fresh press early.
        assertUnchanged(30);
        assertTrue(bridge.appliedPairs.isEmpty());
        advance(1999);
        assertUnchanged(30);
        advance(1);
        assertInverted(70);
        release(fresh);
        assertInverted(70);
    }

    @Test public void missingEitherSideMakesHoldAndReleaseNoOps() {
        for (int missing : new int[]{Prefs.LEFT, Prefs.RIGHT}) {
            Prefs.sp(context).edit().putString("side0", LEFT).putString("side1", RIGHT)
                    .remove("side" + missing).commit();
            rebuild();
            Map<String, ?> before = Prefs.sp(context).getAll();
            View view = press();
            advance(3000);
            release(view);
            advance(4000);
            assertEquals(before, Prefs.sp(context).getAll());
            assertEquals(0, bridge.swaps);
            assertTrue(bridge.appliedPairs.isEmpty());
        }
    }

    @Test public void accessibilityActionClickSwapsAppsAndPreservesCooldown() {
        View view = button();
        assertNotNull(view.getContentDescription());
        assertTrue(view.isFocusable());
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        shadowOf(Looper.getMainLooper()).idle();
        assertSwapped();
        Map<String, ?> afterSwap = Prefs.sp(context).getAll();
        advance(1000);
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, null));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(afterSwap, Prefs.sp(context).getAll());
        assertSwapped();
        advance(3000); // Accessibility clicks must not leave a physical-hold timer armed.
        assertEquals(afterSwap, Prefs.sp(context).getAll());
        assertSwapped();
    }

    @Test public void accessibilityActionLongClickInvertsWithoutSwap() {
        View view = button();
        assertNotNull(view.getContentDescription());
        assertTrue(view.isFocusable());
        assertTrue(view.performAccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, null));
        assertInverted(70);
        advance(4000);
        assertInverted(70);
    }

    @Test public void performLongClickInvertsWithoutSwap() {
        assertTrue(button().performLongClick());
        assertInverted(70);
        advance(4000);
        assertInverted(70);
    }
}
