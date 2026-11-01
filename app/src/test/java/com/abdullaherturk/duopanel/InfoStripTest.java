package com.abdullaherturk.duopanel;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.GridView;
import android.widget.RadioGroup;
import android.widget.TextView;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33, shadows = InfoStripTest.Launches.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class InfoStripTest {
    /** Keep the actual view listeners/prefs, intercept only the external split operation. */
    @Implements(value = SplitController.class, isInAndroidSdk = false)
    public static class Launches {
        static int count;
        static String left, right;
        @Implementation protected static void launchPair(Context c) {
            count++;
            left = Prefs.side(c, Prefs.LEFT);
            right = Prefs.side(c, Prefs.RIGHT);
        }
    }

    private ServiceController<OverlayService> controller;
    private OverlayService service;

    @Before public void setup() {
        Context c = RuntimeEnvironment.getApplication();
        Prefs.sp(c).edit().clear().commit();
        ShadowSettings.setCanDrawOverlays(true);
        Prefs.set(c, "bubble", false);
        Launches.count = 0;
        controller = Robolectric.buildService(OverlayService.class).create();
        service = controller.get();
        ReflectionHelpers.setField(service, "hasWx", true);
        controller.startCommand(0, 1);
        service.onSplitChanged(true);
    }

    @After public void teardown() { controller.destroy(); }

    private View strip() { return ReflectionHelpers.getField(service, "strip"); }
    private View popup() { return ReflectionHelpers.getField(service, "popup"); }
    private WindowManager.LayoutParams lp() { return ReflectionHelpers.getField(service, "stripLp"); }
    private void refresh() { OverlayService.refresh(service); service.onSplitChanged(true); }
    private void touch(View v, int action, long time, float x, float y) {
        MotionEvent event = MotionEvent.obtain(0, time, action, x, y, 0);
        v.dispatchTouchEvent(event);
        event.recycle();
    }
    private void drag() {
        touch(strip(), MotionEvent.ACTION_DOWN, 0, 1, 1);
        touch(strip(), MotionEvent.ACTION_MOVE, 100, 80, 90);
        touch(strip(), MotionEvent.ACTION_UP, 200, 80, 90);
    }

    @Test public void selectedAppIconsRemainVisibleBesideAllAppsLabelsInBothModes() {
        String installed = service.getPackageName();
        assertNotNull(AppList.icon(service, installed));
        for (boolean edge : new boolean[]{true, false}) {
            Prefs.setStripEdge(service, edge);
            Prefs.setSide(service, Prefs.LEFT, installed);
            Prefs.setSide(service, Prefs.RIGHT, installed);
            refresh();
            service.updateIcons();
            View view = strip();
            int width = service.getResources().getDisplayMetrics().widthPixels;
            int height = Math.round(56 * service.getResources().getDisplayMetrics().density);
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
            for (int id : new int[]{R.id.iv_left, R.id.iv_right}) {
                android.widget.ImageView icon = view.findViewById(id);
                assertEquals(View.VISIBLE, icon.getVisibility());
                assertTrue(icon.isShown());
                assertNotNull(icon.getDrawable());
                assertTrue(icon.getWidth() > 0);
                ViewGroup button = (ViewGroup) icon.getParent();
                View label = button.getChildAt(1);
                assertEquals(View.VISIBLE, label.getVisibility());
                assertTrue("selected icon must not overlap the All Apps label",
                        icon.getRight() <= label.getLeft());
                assertTrue(label.getRight() <= button.getWidth());
            }
        }
    }

    @Test public void edgeIsDefaultIgnoresSavedCoordinatesAndDoesNotDragOrCenter() {
        Prefs.setStrip(service, 23, 87);
        refresh();
        assertTrue(Prefs.stripEdge(service));
        assertEquals(WindowManager.LayoutParams.MATCH_PARENT, lp().width);
        assertEquals(0, lp().x);
        assertEquals(0, lp().y);
        assertEquals(0, lp().flags & WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        drag();
        assertEquals(0, lp().x);
        assertEquals(0, lp().y);
        // Long-press must still expose settings/hide, but no center action in EDGE.
        touch(strip(), MotionEvent.ACTION_DOWN, 1000, 1, 1);
        touch(strip(), MotionEvent.ACTION_UP, 1800, 1, 1);
        ViewGroup menu = ReflectionHelpers.getField(service, "stripMenu");
        assertNotNull(menu);
        for (int i = 0; i < menu.getChildCount(); i++) {
            assertNotEquals(service.getString(R.string.strip_center), ((TextView) menu.getChildAt(i)).getText().toString());
        }
        assertEquals(23, Prefs.stripX(service));
        assertEquals(87, Prefs.stripY(service));
    }

    @Test public void floatingPositionSurvivesEdgeAndRecreationAndFloatingCenterWorks() {
        Prefs.setStrip(service, 0, 80);
        Prefs.setStripEdge(service, false);
        refresh();
        assertTrue(lp().width > 0);
        assertEquals(80, lp().y);
        drag();
        int x = Prefs.stripX(service), y = Prefs.stripY(service);
        assertTrue(y > 80);
        assertEquals(x, lp().x);
        assertEquals(y, lp().y);
        assertTrue("drag must use the window width even before measurement",
                x <= Math.max(0, service.getResources().getDisplayMetrics().widthPixels - lp().width));
        assertTrue("drag must use the window height even before measurement",
                y <= Math.max(0, service.getResources().getDisplayMetrics().heightPixels - lp().height));
        Prefs.setStripEdge(service, true);
        refresh();
        drag();
        Prefs.setStripEdge(service, false);
        refresh();
        assertEquals(x, lp().x);
        assertEquals(y, lp().y);
        controller.destroy();
        controller = Robolectric.buildService(OverlayService.class).create();
        service = controller.get();
        ReflectionHelpers.setField(service, "hasWx", true);
        controller.startCommand(0, 2);
        service.onSplitChanged(true);
        assertFalse(Prefs.stripEdge(service));
        assertEquals(y, lp().y);
        touch(strip(), MotionEvent.ACTION_DOWN, 1000, 1, 1);
        touch(strip(), MotionEvent.ACTION_UP, 1800, 1, 1);
        ViewGroup menu = ReflectionHelpers.getField(service, "stripMenu");
        menu.getChildAt(0).performClick();
        assertEquals(-1, Prefs.stripX(service));
        assertEquals(-1, Prefs.stripY(service));
    }

    @Test public void settingsChoicePersistsRefreshesImmediatelyAndRebindsOnResume() {
        org.robolectric.android.controller.ActivityController<MainActivity> activityController =
                Robolectric.buildActivity(MainActivity.class).create(new Bundle()).start().resume();
        MainActivity activity = activityController.get();
        RadioGroup group = activity.findViewById(R.id.strip_position);
        assertEquals(R.id.strip_edge, group.getCheckedRadioButtonId());
        activity.findViewById(R.id.strip_floating).performClick();
        assertFalse(Prefs.stripEdge(service));
        assertTrue(lp().width > 0);
        assertTrue(Prefs.strip(service));
        Prefs.setStripEdge(service, true);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        assertEquals(R.id.strip_edge, group.getCheckedRadioButtonId());
        activityController.pause();
        Prefs.setStripEdge(service, false);
        activityController.resume();
        assertEquals(R.id.strip_floating, group.getCheckedRadioButtonId());
        activityController.pause().stop().destroy();
    }

    @Test public void bothVisibleControlsOpenCompleteGridImmediatelyAndSelectOnlyTheirSide() {
        Prefs.setSide(service, Prefs.LEFT, "old.left");
        Prefs.setSide(service, Prefs.RIGHT, "old.right");
        for (int side = 0; side < 2; side++) {
            int id = side == 0 ? R.id.btn_left : R.id.btn_right;
            View button = strip().findViewById(id);
            assertEquals(View.VISIBLE, button.getVisibility());
            assertEquals(service.getString(side == 0 ? R.string.all_apps_left : R.string.all_apps_right), button.getContentDescription());
            String opposite = Prefs.side(service, 1 - side);
            java.util.List<String> history = Prefs.history(service, 1 - side);
            button.performClick();
            GridView grid = popup().findViewById(R.id.gv_all);
            assertEquals(View.VISIBLE, grid.getVisibility());
            assertEquals(AppList.list(service).size(), grid.getAdapter().getCount());
            // Inject one adapter item, then exercise the REAL installed item-click listener.
            AppList.App app = new AppList.App();
            app.pkg = "new.side." + side;
            Object adapter = grid.getAdapter();
            java.util.List<AppList.App> data = ReflectionHelpers.getField(adapter, "data");
            data.add(app);
            grid.performItemClick(new View(service), data.size() - 1, data.size() - 1);
            assertEquals(app.pkg, Prefs.side(service, side));
            assertEquals(app.pkg, Prefs.history(service, side).get(0));
            assertEquals(opposite, Prefs.side(service, 1 - side));
            assertEquals(history, Prefs.history(service, 1 - side));
            assertEquals(side + 1, Launches.count);
            assertEquals(Prefs.side(service, 0), Launches.left);
            assertEquals(Prefs.side(service, 1), Launches.right);
            assertNull(popup());
        }
    }

    @Test public void narrowPortraitAndLandscapeKeepBothControlsInBoundsAndGridScrollable() {
        for (String qualifiers : new String[]{"w320dp-h640dp-port", "w640dp-h320dp-land"}) {
            RuntimeEnvironment.setQualifiers(qualifiers);
            service.onConfigurationChanged(new Configuration(service.getResources().getConfiguration()));
            service.onSplitChanged(true);
            View view = strip();
            int width = service.getResources().getDisplayMetrics().widthPixels;
            int height = Math.round(56 * service.getResources().getDisplayMetrics().density);
            view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            view.layout(0, 0, width, height);
            View left = view.findViewById(R.id.btn_left), right = view.findViewById(R.id.btn_right);
            assertTrue(left.getWidth() > 0);
            assertTrue(right.getLeft() >= left.getRight());
            assertTrue(right.getRight() <= width);
            right.performClick();
            GridView grid = popup().findViewById(R.id.gv_all);
            assertTrue(grid.getLayoutParams().height > 0);
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) popup().getLayoutParams();
            assertTrue(p.width <= width);
            assertEquals(View.VISIBLE, grid.getVisibility());
        }
    }
}
