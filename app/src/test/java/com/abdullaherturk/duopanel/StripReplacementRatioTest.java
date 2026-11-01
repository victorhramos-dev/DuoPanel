package com.abdullaherturk.duopanel;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.ResolveInfo;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;
import java.util.List;
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
import static org.robolectric.Shadows.shadowOf;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33, shadows = StripReplacementRatioTest.Launches.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class StripReplacementRatioTest {
    private static final String LEFT = "test.replace.left";
    private static final String RIGHT = "test.replace.right";
    private static final String NEW = "test.replace.new";

    /** Only the external split launch is recorded; preferences and every picker listener are real. */
    @Implements(value = SplitController.class, isInAndroidSdk = false)
    public static class Launches {
        static int count, ratio;
        static String left, right;
        @Implementation protected static void launchPair(Context context) {
            count++;
            left = Prefs.side(context, Prefs.LEFT);
            right = Prefs.side(context, Prefs.RIGHT);
            ratio = Prefs.ratio(context, left, right);
        }
    }

    private ServiceController<OverlayService> controller;
    private OverlayService service;

    @Before public void setup() {
        Context context = RuntimeEnvironment.getApplication();
        Prefs.sp(context).edit().clear().commit();
        ShadowSettings.setCanDrawOverlays(true);
        Prefs.set(context, "bubble", false);
        // Replacement semantics must not depend on whether the EDGE drawer is open.
        Prefs.setStripEdge(context, false);
        SplitController.setActive(false);
        SplitController.setDivider(null);
        install(context, LEFT);
        install(context, RIGHT);
        install(context, NEW);
        Prefs.setSide(context, Prefs.LEFT, LEFT);
        Prefs.setSide(context, Prefs.RIGHT, RIGHT);
        Launches.count = 0;
        Launches.ratio = -1;
        Launches.left = Launches.right = null;
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
        Launches.count = 0;
        Launches.ratio = -1;
        Launches.left = Launches.right = null;
        ShadowSettings.setCanDrawOverlays(false);
        Prefs.sp(RuntimeEnvironment.getApplication()).edit().clear().commit();
    }

    private void install(Context context, String pkg) {
        PackageInfo info = new PackageInfo();
        info.packageName = pkg;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = pkg;
        info.applicationInfo.nonLocalizedLabel = pkg;
        shadowOf(context.getPackageManager()).installPackage(info);
        ResolveInfo launcher = new ResolveInfo();
        launcher.activityInfo = new ActivityInfo();
        launcher.activityInfo.packageName = pkg;
        launcher.activityInfo.name = pkg + ".MainActivity";
        launcher.activityInfo.applicationInfo = info.applicationInfo;
        shadowOf(context.getPackageManager()).addResolveInfoForIntent(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), launcher);
    }

    private View popup(int side) {
        View strip = ReflectionHelpers.getField(service, "strip");
        assertNotNull(strip);
        assertTrue(strip.findViewById(side == Prefs.LEFT ? R.id.btn_left : R.id.btn_right).performClick());
        View popup = ReflectionHelpers.getField(service, "popup");
        assertNotNull(popup);
        return popup;
    }

    private void gridReplacement(int side) {
        GridView grid = popup(side).findViewById(R.id.gv_all);
        assertEquals(View.VISIBLE, grid.getVisibility());
        int index = -1;
        for (int i = 0; i < grid.getAdapter().getCount(); i++) {
            if (NEW.equals(((AppList.App) grid.getAdapter().getItem(i)).pkg)) index = i;
        }
        assertTrue("installed replacement must be offered by the real app grid", index >= 0);
        View item = grid.getAdapter().getView(index, null, grid);
        assertTrue(grid.performItemClick(item, index, grid.getAdapter().getItemId(index)));
    }

    private void historyReplacement(int side) {
        String old = Prefs.side(service, side);
        Prefs.setSide(service, side, NEW);
        Prefs.setSide(service, side, old);
        List<String> history = Prefs.history(service, side);
        int index = history.indexOf(NEW);
        assertTrue(index >= 0);
        ViewGroup rows = popup(side).findViewById(R.id.ll_history);
        assertTrue(rows.getChildAt(index).performClick());
    }

    private void assertReplacement(int side, int expected) {
        String left = side == Prefs.LEFT ? NEW : LEFT;
        String right = side == Prefs.RIGHT ? NEW : RIGHT;
        // Keep this first: the baseline fault must be a behavioral 30/70 -> 50 assertion.
        assertEquals(expected, Prefs.ratio(service, left, right));
        assertEquals(left, Prefs.side(service, Prefs.LEFT));
        assertEquals(right, Prefs.side(service, Prefs.RIGHT));
        assertEquals(NEW, Prefs.history(service, side).get(0));
        assertEquals(1, Launches.count);
        assertEquals(left, Launches.left);
        assertEquals(right, Launches.right);
        assertEquals(expected, Launches.ratio);
        assertEquals(expected, Prefs.ratio(service, LEFT, RIGHT));
        assertNull(ReflectionHelpers.getField(service, "popup"));
    }

    @Test public void leftReplacementKeepsThirtyPercent() {
        Prefs.setRatio(service, LEFT, RIGHT, 30);
        gridReplacement(Prefs.LEFT);
        assertReplacement(Prefs.LEFT, 30);
    }

    @Test public void rightReplacementKeepsSeventyPercent() {
        Prefs.setRatio(service, LEFT, RIGHT, 70);
        gridReplacement(Prefs.RIGHT);
        assertReplacement(Prefs.RIGHT, 70);
    }

    @Test public void leftHistoryReplacementKeepsThirtyPercent() {
        Prefs.setRatio(service, LEFT, RIGHT, 30);
        historyReplacement(Prefs.LEFT);
        assertReplacement(Prefs.LEFT, 30);
    }

    @Test public void rightHistoryReplacementKeepsSeventyPercent() {
        Prefs.setRatio(service, LEFT, RIGHT, 70);
        historyReplacement(Prefs.RIGHT);
        assertReplacement(Prefs.RIGHT, 70);
    }

    @Test public void replacementOverridesDestinationFiftyWithoutChangingReversePair() {
        Prefs.setRatio(service, LEFT, RIGHT, 30);
        Prefs.setRatio(service, NEW, RIGHT, 50);
        Prefs.setRatio(service, RIGHT, NEW, 70);
        gridReplacement(Prefs.LEFT);
        assertReplacement(Prefs.LEFT, 30);
        assertEquals(70, Prefs.ratio(service, RIGHT, NEW));
    }

    @Test public void bothPickersPreserveAllRatiosOnEitherSideAndOverrideOldDestinations() {
        for (int side : new int[]{Prefs.LEFT, Prefs.RIGHT}) {
            for (int ratio : new int[]{30, 50, 70}) {
                for (boolean history : new boolean[]{false, true}) {
                    Prefs.setSide(service, Prefs.LEFT, LEFT);
                    Prefs.setSide(service, Prefs.RIGHT, RIGHT);
                    Prefs.setRatio(service, LEFT, RIGHT, ratio);
                    String left = side == Prefs.LEFT ? NEW : LEFT;
                    String right = side == Prefs.RIGHT ? NEW : RIGHT;
                    Prefs.setRatio(service, left, right, ratio == 70 ? 30 : 70);
                    Prefs.setRatio(service, right, left, 41);
                    List<String> oppositeHistory = Prefs.history(service, 1 - side);
                    Launches.count = 0;
                    if (history) historyReplacement(side); else gridReplacement(side);
                    assertReplacement(side, ratio);
                    assertEquals(41, Prefs.ratio(service, right, left));
                    assertEquals(oppositeHistory, Prefs.history(service, 1 - side));
                }
            }
        }
    }

    @Test public void missingOriginalSideUsesFiftyInsteadOfDestinationHistory() {
        Prefs.sp(service).edit().remove("side0").apply();
        Prefs.setRatio(service, NEW, RIGHT, 70);
        gridReplacement(Prefs.LEFT);
        assertEquals(50, Prefs.ratio(service, NEW, RIGHT));
        assertEquals(50, Launches.ratio);
    }

    @Test public void sideObserversSeeTheCarriedRatioInTheSamePreferenceTransaction() {
        Prefs.setRatio(service, LEFT, RIGHT, 70);
        final List<Integer> observed = new java.util.ArrayList<Integer>();
        android.content.SharedPreferences.OnSharedPreferenceChangeListener listener =
                new android.content.SharedPreferences.OnSharedPreferenceChangeListener() {
                    @Override public void onSharedPreferenceChanged(android.content.SharedPreferences prefs, String key) {
                        if ("side0".equals(key)) observed.add(Prefs.ratio(service,
                                Prefs.side(service, Prefs.LEFT), Prefs.side(service, Prefs.RIGHT)));
                    }
                };
        Prefs.sp(service).registerOnSharedPreferenceChangeListener(listener);
        try {
            gridReplacement(Prefs.LEFT);
            shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(java.util.Arrays.asList(70), observed);
        } finally { Prefs.sp(service).unregisterOnSharedPreferenceChangeListener(listener); }
    }
}
