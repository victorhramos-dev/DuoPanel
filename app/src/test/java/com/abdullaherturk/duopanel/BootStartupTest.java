package com.abdullaherturk.duopanel;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.time.Duration;
import java.util.Collections;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public class BootStartupTest {
    private Context context;
    private BootStartup startup;
    private FakeHost host;
    private KeyguardManager keyguard;
    private UserManager user;

    private static class FakeHost implements BootStartup.Host {
        boolean ready = true, visible;
        int dispatches, recognitions;
        String left, right;
        long dispatchedAt;
        @Override public boolean ready() { return ready; }
        @Override public boolean visible() { return visible; }
        @Override public void recognized() { recognitions++; }
        @Override public void dispatch(String l, String r) {
            dispatches++; left = l; right = r; dispatchedAt = SystemClock.elapsedRealtime();
        }
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Prefs.sp(context).edit().clear().commit();
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 12);
        keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        user = (UserManager) context.getSystemService(Context.USER_SERVICE);
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        Shadows.shadowOf(user).setUserUnlocked(true);
        Shadows.shadowOf((PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(true);
        install("example.left"); install("example.right"); install("example.new");
        Prefs.setSide(context, Prefs.LEFT, "example.left");
        Prefs.setSide(context, Prefs.RIGHT, "example.right");
        Prefs.set(context, "autolaunch", true);
        Prefs.set(context, "autostart", false);
        host = new FakeHost();
    }

    @After public void tearDown() { if (startup != null) startup.close(); }

    private void install(String name) {
        PackageInfo info = new PackageInfo();
        info.packageName = name;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = name;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
    }

    private void start() {
        startup = new BootStartup(context, new Handler(Looper.getMainLooper()), host);
        startup.start();
    }
    private void idle(long ms) { Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private boolean marked() { return Prefs.sp(context).contains("startupDispatchBoot"); }
    private void pending() { Prefs.setLong(context, "pendingLaunch", SystemClock.elapsedRealtime()); }

    @Test public void readyBootDispatchesBefore1500RatherThan15000() {
        long before = SystemClock.elapsedRealtime();
        start(); idle(1499);
        assertEquals(1, host.dispatches);
        assertTrue(host.dispatchedAt - before < 1500);
        assertTrue(marked());
    }

    @Test public void requestIsConsumedOnlyAfterHostAcceptsDispatch() {
        Prefs.set(context, "autolaunch", false);
        pending();
        host = new FakeHost() {
            @Override public void dispatch(String left, String right) {
                assertFalse("guard must not precede dispatch", marked());
                assertTrue("request must survive until dispatch", Prefs.sp(context).contains("pendingLaunch"));
                super.dispatch(left, right);
            }
        };
        start(); idle(1);
        assertEquals(1, host.dispatches);
        assertTrue(marked());
        assertFalse(Prefs.sp(context).contains("pendingLaunch"));
        startup.close(); start(); idle(1000);
        assertEquals(1, host.dispatches);
    }

    @Test public void failedDispatchPreservesExplicitRequestAcrossReconnect() {
        Prefs.set(context, "autolaunch", false);
        pending();
        host = new FakeHost() {
            @Override public void dispatch(String left, String right) {
                throw new IllegalStateException("host unavailable");
            }
        };
        start(); idle(1);
        assertFalse(marked());
        assertTrue(Prefs.sp(context).contains("pendingLaunch"));
        startup.close();
        host = new FakeHost();
        start(); idle(1);
        assertEquals(1, host.dispatches);
        assertTrue(marked());
        assertFalse(Prefs.sp(context).contains("pendingLaunch"));
    }

    @Test public void failedRecognitionRemainsRetryableWithoutDispatch() {
        pending();
        host = new FakeHost() {
            @Override public void recognized() {
                assertFalse(marked());
                assertTrue(Prefs.sp(context).contains("pendingLaunch"));
                throw new IllegalStateException("recognition unavailable");
            }
        };
        host.visible = true;
        start(); idle(1);
        assertFalse(marked());
        assertTrue(Prefs.sp(context).contains("pendingLaunch"));
        startup.close();
        host = new FakeHost(); host.visible = true;
        start(); idle(1);
        assertEquals(1, host.recognitions);
        assertEquals(0, host.dispatches);
        assertTrue(marked());
        assertFalse(Prefs.sp(context).contains("pendingLaunch"));
    }

    @Test public void disabledAutoLaunchDoesNotDependOnOverlayAutostart() {
        Prefs.set(context, "autolaunch", false);
        Prefs.set(context, "autostart", true);
        start(); idle(2000);
        assertEquals(0, host.dispatches); assertFalse(marked());
    }

    @Test public void invalidInstalledPairRemainsRetryable() {
        Prefs.setSide(context, Prefs.RIGHT, "missing.package");
        start(); idle(1000);
        assertFalse(marked()); assertEquals(0, host.dispatches);
        Prefs.setSide(context, Prefs.RIGHT, "example.right");
        idle(250); assertEquals(1, host.dispatches);
    }

    @Test public void missingPairDoesNotConsumeBoot() {
        Prefs.sp(context).edit().remove("side0").apply();
        start(); idle(1500); assertEquals(0, host.dispatches); assertFalse(marked());
    }

    @Test public void alreadyVisiblePairIsRecognizedWithoutDispatch() {
        host.visible = true;
        start(); idle(1);
        assertEquals(1, host.recognitions); assertEquals(0, host.dispatches); assertTrue(marked());
    }

    @Test public void lockedBootWaitsForUnlockBroadcastWithoutConsumingBoot() {
        Shadows.shadowOf(keyguard).setKeyguardLocked(true);
        start(); idle(1499); assertFalse(marked()); assertEquals(0, host.dispatches);
        Shadows.shadowOf(keyguard).setKeyguardLocked(false);
        context.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        idle(1); assertEquals(1, host.dispatches);
    }

    @Test public void userLockedEvenWithoutKeyguardIsNotReady() {
        Shadows.shadowOf(user).setUserUnlocked(false);
        start(); idle(1000); assertFalse(marked());
        Shadows.shadowOf(user).setUserUnlocked(true);
        context.sendBroadcast(new Intent(Intent.ACTION_USER_UNLOCKED));
        idle(1); assertEquals(1, host.dispatches);
    }

    @Test public void nonInteractiveDeviceDefers() {
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        Shadows.shadowOf(power).setIsInteractive(false);
        start(); idle(1000); assertFalse(marked());
        Shadows.shadowOf(power).setIsInteractive(true);
        idle(250); assertEquals(1, host.dispatches);
    }

    @Test public void selectionIsReadAtExecutionAndPreferencesRevalidated() {
        host.ready = false;
        start(); idle(500);
        Prefs.setSide(context, Prefs.LEFT, "example.new");
        host.ready = true;
        idle(250); assertEquals("example.new", host.left);
    }

    @Test public void disablingPreferenceWhileWaitingCancelsAutomaticDispatch() {
        host.ready = false; start(); idle(500);
        Prefs.set(context, "autolaunch", false); host.ready = true;
        idle(2000); assertEquals(0, host.dispatches); assertFalse(marked());
    }

    @Test public void duplicateBroadcastsAndReconnectDoNotRedispatchSameBoot() {
        start(); idle(1);
        for (int i = 0; i < 3; i++) context.sendBroadcast(new Intent(Intent.ACTION_BOOT_COMPLETED));
        idle(1000); startup.close(); start(); idle(2000);
        assertEquals(1, host.dispatches);
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 13);
        startup.close(); start(); idle(1); assertEquals(2, host.dispatches);
    }

    @Test public void disconnectBeforeDispatchIsRetryableOnReconnect() {
        host.ready = false; start(); idle(1); startup.close();
        host.ready = true; idle(1500); assertEquals(0, host.dispatches); assertFalse(marked());
        start(); idle(1); assertEquals(1, host.dispatches);
    }

    @Test public void freshExplicitAtExactlyCurrentTimeOverridesDisabledAutoLaunch() {
        Prefs.set(context, "autolaunch", false); pending();
        start(); idle(1); assertEquals(1, host.dispatches);
        assertFalse(Prefs.sp(context).contains("pendingLaunch"));
    }

    @Test public void zeroTimestampIsAValidExplicitRequestAtEarlyBoot() {
        // Zero must be distinguished from absence with contains(), not timestamp > 0.
        assertTrue(SystemClock.elapsedRealtime() < BootStartup.PENDING_MS);
        Prefs.set(context, "autolaunch", false);
        Prefs.setLong(context, "pendingLaunch", 0);
        start(); idle(1); assertEquals(1, host.dispatches);
    }

    @Test public void explicitAndAutomaticShareOneQueue() {
        pending(); start(); idle(16000);
        assertEquals(1, host.dispatches);
    }

    @Test public void freshExplicitSurvivesUnbindAndMergesOnReconnect() {
        pending(); host.ready = false; start(); idle(500); startup.close();
        assertTrue(Prefs.sp(context).contains("pendingLaunch")); assertFalse(marked());
        host.ready = true; start(); idle(1); assertEquals(1, host.dispatches);
    }

    @Test public void staleAndFutureExplicitValuesAreDiscarded() {
        Prefs.set(context, "autolaunch", false);
        idle(21000); Prefs.setLong(context, "pendingLaunch", SystemClock.elapsedRealtime() - 20000);
        start(); idle(1); assertFalse(Prefs.sp(context).contains("pendingLaunch"));
        Prefs.setLong(context, "pendingLaunch", SystemClock.elapsedRealtime() + 10);
        startup.wake(); idle(1); assertFalse(Prefs.sp(context).contains("pendingLaunch"));
        assertEquals(0, host.dispatches);
    }

    @Test public void lateConnectionCannotAutomaticLaunchOutsideEarlyBootWindow() {
        idle(BootStartup.EARLY_BOOT_MS);
        start(); idle(1000); assertEquals(0, host.dispatches); assertFalse(marked());
        pending(); startup.wake(); idle(1); assertEquals(1, host.dispatches);
    }

    @Test public void readinessTimeoutCancelsCallbacksAndReceiversWithoutMarkingSuccess() {
        host.ready = false; start(); idle(BootStartup.DEADLINE_MS);
        assertEquals(0, host.dispatches); assertFalse(marked());
        host.ready = true;
        context.sendBroadcast(new Intent(Intent.ACTION_USER_PRESENT));
        startup.wake(); idle(1000); assertEquals(0, host.dispatches);
    }

    @Test public void connectionIntegrationWaitsForApplicationWindowAndCancelsOnUnbind() {
        SplitService service = Robolectric.buildService(SplitService.class).create().get();
        try {
            Shadows.shadowOf(service).setWindows(Collections.emptyList());
            service.onServiceConnected(); idle(500);
            assertFalse(marked());
            service.onUnbind(new Intent());
            AccessibilityNodeInfo root = AccessibilityNodeInfo.obtain();
            root.setPackageName("example.launcher");
            AccessibilityWindowInfo window = AccessibilityWindowInfo.obtain();
            ShadowAccessibilityWindowInfo shadow = Shadows.shadowOf(window);
            shadow.setType(AccessibilityWindowInfo.TYPE_APPLICATION);
            shadow.setRoot(root); shadow.setBoundsInScreen(new Rect(0, 0, 1000, 400));
            Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
            idle(1000); assertFalse(marked());
            service.onServiceConnected(); idle(1); assertTrue(marked());
            idle(700);
            assertNotNull("native 700ms staging still emits launch bridge", Shadows.shadowOf(service).getNextStartedActivity());
            assertNull("autostart=false must not create overlay", Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        } finally { service.onUnbind(new Intent()); }
    }

    @Test @Config(sdk = 24) public void schedulerSupportsMinimumSdk() {
        start(); idle(1000); assertEquals(1, host.dispatches);
    }
}
