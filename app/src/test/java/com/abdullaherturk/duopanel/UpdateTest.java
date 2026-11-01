package com.abdullaherturk.duopanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.provider.Settings;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public class UpdateTest {
    private static final String SHA = "1f788184f03cd7ba0fe3405fa5d2be3657c898fc7bba347881716908d9cf261d";
    private Context context;
    private Activity activity;
    private UpdateController controller;
    private final List<QueueExecutor> executors = new ArrayList<>();
    private int checks, downloads;
    private boolean networkError;
    private String payload;
    private String downloadSha = SHA;

    static String payload(String tag) throws Exception {
        String version = tag.replaceFirst("^v", "").replace("-streambox", "");
        String name = "DuoPanel-Streambox-Fix-" + version + ".apk";
        String base = "https://github.com/" + UpdateRelease.REPO + "/releases/download/" + tag + "/";
        return new JSONObject().put("tag_name", tag).put("draft", false).put("prerelease", false)
                .put("assets", new org.json.JSONArray().put(new JSONObject().put("name", name)
                        .put("state", "uploaded").put("size", 456148)
                        .put("content_type", "application/vnd.android.package-archive")
                        .put("browser_download_url", base + name).put("digest", "sha256:" + SHA))
                        .put(new JSONObject().put("name", "SHA256SUMS").put("browser_download_url", base + "SHA256SUMS")))
                .toString();
    }
    static class QueueExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>(); boolean stopped;
        @Override public void execute(Runnable r) { tasks.add(r); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; tasks.clear(); return Collections.emptyList(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long t, TimeUnit u) { return stopped; }
        void drain() { while (!tasks.isEmpty()) tasks.remove(0).run(); }
    }
    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("duo", 0).edit().clear().commit();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        payload = payload("v1.0.10-streambox");
        UpdateController.executorFactory = () -> { QueueExecutor e = new QueueExecutor(); executors.add(e); return e; };
        UpdateController.factory = () -> new UpdateClient() {
            @Override String text(String url, int limit) throws Exception {
                checks++; if (networkError) throw new java.io.IOException("HTTP 403 rate limit"); return payload;
            }
            @Override Download download(Context c, UpdateRelease r, Progress p) throws Exception {
                downloads++;
                File dir = new File(c.getCacheDir(), "updates"); dir.mkdirs();
                File apk = new File(dir, "00000000-0000-0000-0000-000000000001.apk");
                try (FileOutputStream out = new FileOutputStream(apk)) { out.write(new byte[]{1, 2, 3}); }
                p.changed(100);
                return new Download(apk, 3, downloadSha);
            }
        };
        controller = new UpdateController(activity, null);
    }
    @After public void tearDown() {
        controller.pause();
        UpdateController.factory = UpdateClient::new;
        UpdateController.executorFactory = java.util.concurrent.Executors::newSingleThreadExecutor;
        for (QueueExecutor e : executors) e.shutdownNow();
        activity.finish();
    }
    private void drain() {
        for (int i = 0; i < 4; i++) {
            for (QueueExecutor e : new ArrayList<>(executors)) e.drain();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private AlertDialog latest() { return ShadowAlertDialog.getLatestAlertDialog(); }
    private void click(int button) {
        latest().getButton(button).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }
    private static void invalid(String value) {
        try { UpdateRelease.parse(value); fail("Payload accepted"); } catch (Exception expected) { }
    }

    @Test public void numericStableVersions() {
        assertTrue(UpdateRelease.compare("v1.0.10-streambox", "1.0.9-streambox") > 0);
        assertEquals(0, UpdateRelease.compare("v1.0.4-streambox", "1.0.4-streambox"));
        assertTrue(UpdateRelease.compare("1.0.3-streambox", "1.0.4-streambox") < 0);
        assertTrue(UpdateRelease.compare("999999999999999999.0.0-streambox", "2.0.0-streambox") > 0);
        for (String tag : new String[]{"1.0", "1.0.4", "v01.0.4-streambox", "1.0.4-streambox-beta", "-1.0.0-streambox"}) {
            try { UpdateRelease.compare(tag, "1.0.4-streambox"); fail(tag); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void realisticReleaseAndChecksumFallback() throws Exception {
        JSONObject j = new JSONObject(payload("v1.0.4-streambox"));
        UpdateRelease r = UpdateRelease.parse(j.toString());
        assertEquals("DuoPanel-Streambox-Fix-1.0.4.apk", r.name);
        assertEquals(456148, r.size); assertEquals(SHA, r.digest);
        j.getJSONArray("assets").getJSONObject(0).remove("digest");
        r = UpdateRelease.parse(j.toString());
        assertNull(r.digest); assertNotNull(r.sumsUrl);
        assertEquals(SHA, r.checksum(SHA + "  " + r.name + "\n"));
        assertEquals(SHA, r.checksum(SHA + "  ./" + r.name + "\n"));
        assertEquals(SHA, r.checksum(SHA + " *./" + r.name + "\r\n"));
        try { r.checksum(SHA + "  other.apk\n"); fail(); } catch (IllegalArgumentException expected) { }
        try { r.checksum(SHA + "  " + r.name + "\n" + SHA + " *" + r.name); fail(); } catch (IllegalArgumentException expected) { }
        try { r.checksum(SHA + "  " + r.name + "\n" + SHA + " *./" + r.name); fail(); } catch (IllegalArgumentException expected) { }
        for (String prefix : new String[]{"../", "subdir/", "././", "/"}) {
            try { r.checksum(SHA + "  " + prefix + r.name); fail(prefix); } catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void rejectsUnstableMalformedMissingAndAmbiguousAssets() throws Exception {
        for (String key : new String[]{"draft", "prerelease"}) invalid(new JSONObject(payload).put(key, true).toString());
        invalid(new JSONObject(payload).put("tag_name", "v1.0.10-beta").toString());
        invalid("{}"); invalid("not-json");
        for (String key : new String[]{"state", "content_type", "name"}) {
            JSONObject j = new JSONObject(payload);
            j.getJSONArray("assets").getJSONObject(0).put(key, "debug.zip"); invalid(j.toString());
        }
        JSONObject j = new JSONObject(payload);
        j.getJSONArray("assets").put(j.getJSONArray("assets").getJSONObject(0)); invalid(j.toString());
        j = new JSONObject(payload); j.getJSONArray("assets").getJSONObject(0).put("size", 0); invalid(j.toString());
        j = new JSONObject(payload); j.getJSONArray("assets").getJSONObject(0).put("size", UpdateRelease.MAX_APK + 1); invalid(j.toString());
        j = new JSONObject(payload); j.getJSONArray("assets").getJSONObject(0).put("digest", "sha256:bad"); invalid(j.toString());
    }
    @Test public void rejectsWrongAssetOriginAndRedirects() throws Exception {
        UpdateRelease r = UpdateRelease.parse(payload);
        for (String url : new String[]{r.url.replace("https:", "http:"), r.url.replace("github.com", "github.com.evil.test"),
                r.url.replace(UpdateRelease.REPO, "attacker/DuoPanel"), r.url + "?download=1", r.url.replace("github.com/", "user@github.com/")}) {
            JSONObject j = new JSONObject(payload); j.getJSONArray("assets").getJSONObject(0).put("browser_download_url", url); invalid(j.toString());
        }
        assertTrue(UpdateClient.allowed("https://release-assets.githubusercontent.com/a?signature=abc"));
        assertFalse(UpdateClient.allowed("http://github.com/a"));
        assertFalse(UpdateClient.allowed("https://evil.githubusercontent.com/a"));
        assertFalse(UpdateClient.allowed("https://user@github.com/a"));
    }
    @Test public void throttleBoundaryAndClockRollback() {
        assertTrue(UpdateController.due(100, 0));
        assertFalse(UpdateController.due(1000 + UpdateController.INTERVAL - 1, 1000));
        assertTrue(UpdateController.due(1000 + UpdateController.INTERVAL, 1000));
        assertTrue(UpdateController.due(999, 1000));
    }
    @Test public void automaticCheckDeduplicatesAndDownloadNeedsConsent() {
        controller.resume();
        AlertDialog pending = latest(); assertTrue(pending == null || !pending.isShowing());
        controller.check(true); drain();
        assertEquals(1, checks); assertEquals(0, downloads);
        assertTrue(latest().isShowing());
        click(AlertDialog.BUTTON_NEGATIVE);
        controller.pause(); controller.resume(); drain(); assertEquals(1, checks);
        controller.check(true); drain(); assertEquals(2, checks);
        click(AlertDialog.BUTTON_POSITIVE); controller.check(true); drain();
        assertEquals(1, downloads); assertTrue(latest().isShowing());
    }
    @Test @Config(sdk = {24, 26, 33}) public void manualJoinsAutomaticCheckAndReportsCurrentVersion() throws Exception {
        payload = payload(activity.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName);
        controller.resume();
        controller.check(true);
        assertEquals(activity.getString(R.string.update_checking), org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        assertEquals(1, executors.get(0).tasks.size());
        drain();
        assertEquals(1, checks); assertEquals(0, downloads);
        assertEquals(activity.getString(R.string.update_current), org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        AlertDialog d = latest(); assertTrue(d == null || !d.isShowing());
    }
    @Test @Config(sdk = {24, 26, 33}) public void manualJoinsAutomaticCheckAndReportsError() {
        networkError = true;
        controller.resume();
        controller.check(true);
        assertEquals(activity.getString(R.string.update_checking), org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
        assertEquals(1, executors.get(0).tasks.size());
        drain();
        assertEquals(1, checks); assertEquals(0, downloads);
        assertEquals(activity.getString(R.string.update_error), org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
    }
    @Test public void manualNetworkErrorCanRetryAndAutomaticIsThrottled() {
        networkError = true; controller.resume(); drain(); assertEquals(1, checks);
        controller.pause(); controller.resume(); drain(); assertEquals(1, checks);
        controller.check(true); drain(); assertEquals(2, checks);
        networkError = false; controller.check(true); drain(); assertEquals(3, checks);
        assertTrue(latest().isShowing());
    }
@Test public void equalAndOldReleaseNeverOfferDownload() throws Exception {
        String installedVersion = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0).versionName;
        for (String tag : new String[]{"v" + installedVersion, "v1.0.4-streambox"}) {
            payload = payload(tag); controller.resume(); controller.check(true); drain();
            assertEquals(0, downloads);
            AlertDialog d = latest(); assertTrue(d == null || !d.isShowing());
            controller.pause();
        }
    }
    @Test public void resultAfterPauseHasNoUiAndNoInstaller() {
        controller.resume();
        Runnable late = executors.get(0).tasks.remove(0);
        controller.pause(); late.run(); Shadows.shadowOf(Looper.getMainLooper()).idle();
        AlertDialog d = latest(); assertTrue(d == null || !d.isShowing());
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity()); assertEquals(0, downloads);
    }
    @Test @Config(sdk = {24, 26, 33})
    public void mainActivityChecksOnlyWhileVisibleAndManualButtonBypassesThrottle() {
        Prefs.set(context, "setupSeen", true);
        org.robolectric.android.controller.ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).create().start();
        try {
            assertTrue(executors.isEmpty());
            main.resume();
            assertEquals(1, executors.get(0).tasks.size());
            main.get().findViewById(R.id.btn_update).performClick();
            assertEquals(1, executors.get(0).tasks.size());
            Runnable late = executors.get(0).tasks.remove(0);
            main.pause().stop();
            late.run(); drain();
            assertEquals(1, checks);
            assertTrue(latest() == null || !latest().isShowing());
            assertNull(Shadows.shadowOf(main.get()).getNextStartedActivity());
            main.restart().start().resume(); drain();
            assertEquals(1, checks);
            main.get().findViewById(R.id.btn_update).performClick(); drain();
            assertEquals(2, checks); assertEquals(0, downloads);
            assertTrue(latest().isShowing());
            main.pause().stop();
            assertFalse(latest().isShowing());
        } finally { main.destroy(); }
    }

    @Test @Config(sdk = {24, 26, 33})
    public void bootPairDispatchContinuesWhileMainActivityCheckIsPending() {
        Prefs.set(context, "setupSeen", true);
        Prefs.set(context, "autostart", false);
        Prefs.set(context, "autolaunch", true);
        for (String name : new String[]{"example.left", "example.right"}) {
            PackageInfo info = new PackageInfo(); info.packageName = name;
            info.applicationInfo = new android.content.pm.ApplicationInfo();
            info.applicationInfo.packageName = name;
            Shadows.shadowOf(context.getPackageManager()).installPackage(info);
            // Legacy APIs launch the installed app directly, not PairActivity.
            android.content.pm.ResolveInfo launcher = new android.content.pm.ResolveInfo();
            launcher.activityInfo = new android.content.pm.ActivityInfo();
            launcher.activityInfo.packageName = name;
            launcher.activityInfo.name = name + ".MainActivity";
            launcher.activityInfo.applicationInfo = info.applicationInfo;
            Shadows.shadowOf(context.getPackageManager()).addResolveInfoForIntent(
                    new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(name), launcher);
        }
        Prefs.setSide(context, Prefs.LEFT, "example.left");
        Prefs.setSide(context, Prefs.RIGHT, "example.right");
        Settings.Global.putInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 12);
        Settings.Secure.putString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                context.getPackageName() + "/.SplitService");
        Shadows.shadowOf((android.app.KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE)).setKeyguardLocked(false);
        Shadows.shadowOf((android.os.UserManager) context.getSystemService(Context.USER_SERVICE)).setUserUnlocked(true);
        Shadows.shadowOf((android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE)).setIsInteractive(true);
        new BootReceiver().onReceive(context, new Intent(Intent.ACTION_BOOT_COMPLETED));
        assertTrue(executors.isEmpty());
        assertNull(Shadows.shadowOf(RuntimeEnvironment.getApplication()).getNextStartedService());
        org.robolectric.android.controller.ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).create().start().resume();
        SplitService service = Robolectric.buildService(SplitService.class).create().get();
        try {
            assertFalse(main.get().isFinishing());
            assertEquals(1, executors.get(0).tasks.size());
            android.view.accessibility.AccessibilityNodeInfo root = android.view.accessibility.AccessibilityNodeInfo.obtain();
            root.setPackageName("example.launcher");
            android.view.accessibility.AccessibilityWindowInfo window = android.view.accessibility.AccessibilityWindowInfo.obtain();
            org.robolectric.shadows.ShadowAccessibilityWindowInfo shadow = Shadows.shadowOf(window);
            shadow.setType(android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION);
            shadow.setRoot(root); shadow.setBoundsInScreen(new android.graphics.Rect(0, 0, 1000, 400));
            Shadows.shadowOf(service).setWindows(Collections.singletonList(window));
            service.onServiceConnected();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(701));
            assertTrue(Prefs.sp(context).contains("startupDispatchBoot"));
            assertNotNull(Shadows.shadowOf(service).getNextStartedActivity());
            assertEquals(0, checks); assertEquals(0, downloads);
            drain(); assertEquals(1, checks); assertEquals(0, downloads);
            main.pause().stop();
            assertFalse(latest().isShowing());
            service.onUnbind(new Intent()); service.onServiceConnected();
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000));
            assertNull("same boot must not launch the pair twice", Shadows.shadowOf(service).getNextStartedActivity());
            assertNull(Shadows.shadowOf(main.get()).getNextStartedActivity());
        } finally { service.onUnbind(new Intent()); main.destroy(); }
    }

    @Test @Config(sdk = {24, 26, 33})
    public void pausingMainDuringRealDownloadClosesTransportAndDeletesPartialBeforeRecreation() {
        Prefs.set(context, "setupSeen", true);
        final boolean[] disconnected = {false}, closed = {false};
        org.robolectric.android.controller.ActivityController<MainActivity> main =
                Robolectric.buildActivity(MainActivity.class).create().start();
        Bundle saved = new Bundle();
        UpdateController.factory = () -> new UpdateClient() {
            @Override String text(String url, int limit) { checks++; return payload; }
            @Override javax.net.ssl.HttpsURLConnection connect(String address) throws java.io.IOException {
                downloads++;
                return new javax.net.ssl.HttpsURLConnection(new java.net.URL(address)) {
                    @Override public int getResponseCode() { return 200; }
                    @Override public long getContentLengthLong() { return 456148; }
                    @Override public java.io.InputStream getInputStream() {
                        return new java.io.InputStream() {
                            int reads;
                            @Override public int read() { throw new AssertionError("bulk reads expected"); }
                            @Override public int read(byte[] b, int off, int len) {
                                if (reads++ == 0) { b[off] = 1; return 1; }
                                File[] partials = new File(context.getCacheDir(), "updates").listFiles((dir, name) -> name.endsWith(".part"));
                                assertNotNull(partials); assertEquals(1, partials.length);
                                assertEquals(1, partials[0].length());
                                assertTrue(latest().isShowing());
                                main.saveInstanceState(saved).pause().stop();
                                b[off] = 2; return 1; // Simulate a read completing after disconnect.
                            }
                            @Override public void close() { closed[0] = true; }
                        };
                    }
                    @Override public void connect() { }
                    @Override public void disconnect() { disconnected[0] = true; }
                    @Override public boolean usingProxy() { return false; }
                    @Override public String getCipherSuite() { return "test"; }
                    @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }
                    @Override public java.security.cert.Certificate[] getServerCertificates() { return null; }
                };
            }
        };
        try {
            main.resume(); drain();
            click(AlertDialog.BUTTON_POSITIVE); drain();
            assertEquals(1, downloads); assertTrue(disconnected[0]); assertTrue(closed[0]);
            assertTrue(executors.get(0).isShutdown());
            assertFalse(latest().isShowing());
            File[] files = new File(context.getCacheDir(), "updates").listFiles();
            assertNotNull(files); assertEquals(0, files.length);
            assertNull(Shadows.shadowOf(main.get()).getNextStartedActivity());
        } finally { main.destroy(); }
        org.robolectric.android.controller.ActivityController<MainActivity> recreated =
                Robolectric.buildActivity(MainActivity.class).create(saved).start().resume();
        try {
            drain(); assertEquals(1, checks); assertEquals(1, downloads);
            assertTrue(latest() == null || !latest().isShowing());
            assertNull(Shadows.shadowOf(recreated.get()).getNextStartedActivity());
            recreated.get().findViewById(R.id.btn_update).performClick(); drain();
            assertEquals(2, checks); assertTrue(latest().isShowing());
        } finally { recreated.pause().stop().destroy(); }
    }

    @Test public void cancellingProgressStopsTaskAndAllowsManualRetry() {
        context.getSharedPreferences("duo", 0).edit().putLong("update.lastCheck", System.currentTimeMillis()).commit();
        controller.resume(); controller.check(true); latest().cancel();
        // Dialog.cancel posts its listener: deliver it before running queued worker tasks.
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(executors.get(0).isShutdown());
        assertTrue(executors.get(0).tasks.isEmpty());
        assertFalse(latest().isShowing());
        drain(); assertEquals(0, checks);
        controller.check(true); drain(); assertEquals(1, checks);
    }
    @Test @Config(sdk = {26, 33}) public void unknownSourcesDeniedRestoresOnceWithoutLoop() {
        Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(false);
        controller.resume(); drain(); click(AlertDialog.BUTTON_POSITIVE); drain(); click(AlertDialog.BUTTON_POSITIVE);
        Intent settings = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(settings); assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.getAction());
        Bundle state = new Bundle(); controller.save(state); controller.pause();
        controller = new UpdateController(activity, state); controller.resume(); drain();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        controller.pause(); controller.resume(); drain();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        controller.check(true); drain(); assertEquals(2, checks);
    }
    @Test @Config(sdk = {24, 26, 33}) public void installerCannotBypassIntegrityEvenWithPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 26) Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(true);
        controller.resume(); drain(); click(AlertDialog.BUTTON_POSITIVE); drain(); click(AlertDialog.BUTTON_POSITIVE); drain();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        controller.check(true); drain(); assertEquals(2, checks);
    }
    @Test @Config(sdk = 24) public void packageVersionAndCertificatesMustMatchInstalled() {
        PackageInfo installed = new PackageInfo(), archive = new PackageInfo();
        installed.packageName = archive.packageName = context.getPackageName();
        installed.versionCode = 5; archive.versionCode = 6;
        installed.signatures = archive.signatures = new Signature[]{new Signature(new byte[]{1, 2, 3})};
        assertTrue(UpdateSecurity.compatible(installed, archive));
        archive.versionCode = 5; assertFalse(UpdateSecurity.compatible(installed, archive));
        archive.versionCode = 4; assertFalse(UpdateSecurity.compatible(installed, archive));
        archive.versionCode = 6; archive.packageName = "other.package"; assertFalse(UpdateSecurity.compatible(installed, archive));
        archive.packageName = installed.packageName; archive.signatures = new Signature[]{new Signature(new byte[]{4})};
        assertFalse(UpdateSecurity.compatible(installed, archive));
        archive.signatures = null; assertFalse(UpdateSecurity.compatible(installed, archive));
    }
    @Test public void sizeAndDigestAreCheckedBeforePackageParsing() throws Exception {
        File file = File.createTempFile("update-test", ".apk", context.getCacheDir());
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(new byte[]{1, 2, 3}); }
        try {
            for (long size : new long[]{0, 2, 3, UpdateRelease.MAX_APK + 1}) {
                try { UpdateSecurity.verify(context, file, size, SHA); fail("Invalid APK accepted"); } catch (SecurityException expected) { }
            }
        } finally { file.delete(); }
    }
    @org.robolectric.annotation.Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    public static class ArchivePackageManager extends org.robolectric.shadows.ShadowApplicationPackageManager {
        static PackageInfo archive;
        @org.robolectric.annotation.Implementation
        public PackageInfo getPackageArchiveInfo(String path, int flags) { return archive; }
    }
    @org.robolectric.annotation.Implements(android.content.pm.SigningInfo.class)
    public static class CurrentSigningInfo {
        @org.robolectric.annotation.Implementation
        public Signature[] getApkContentsSigners() { return new Signature[]{new Signature(new byte[]{1, 2, 3})}; }
    }
    @Test @Config(sdk = {24, 26, 33}, shadows = {ArchivePackageManager.class, CurrentSigningInfo.class})
    public void verifiedCompatibleApkUsesNativeInstallerWithReadOnlyContentGrant() throws Exception {
        PackageInfo installed = activity.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        installed.versionCode = 5;
        installed.signatures = new Signature[]{new Signature(new byte[]{1, 2, 3})};
        if (android.os.Build.VERSION.SDK_INT >= 28) installed.signingInfo = new android.content.pm.SigningInfo();
        Shadows.shadowOf(activity.getPackageManager()).installPackage(installed);
        PackageInfo archive = new PackageInfo();
        archive.packageName = installed.packageName; archive.versionCode = 6; archive.signatures = installed.signatures;
        if (android.os.Build.VERSION.SDK_INT >= 28) archive.signingInfo = new android.content.pm.SigningInfo();
        ArchivePackageManager.archive = archive;
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(new byte[]{1, 2, 3});
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        JSONObject release = new JSONObject(payload);
        release.getJSONArray("assets").getJSONObject(0).put("size", 3).put("digest", "sha256:" + hex);
        payload = release.toString();
        final String apkUrl = UpdateRelease.parse(payload).url;
        // Replace only HTTPS transport: exercise real streaming, verification and .part finalization.
        UpdateController.factory = () -> new UpdateClient() {
            @Override javax.net.ssl.HttpsURLConnection connect(String address) throws java.io.IOException {
                final byte[] body;
                if (UpdateRelease.API.equals(address)) {
                    checks++; body = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                } else {
                    assertEquals(apkUrl, address);
                    downloads++; body = new byte[]{1, 2, 3};
                }
                return new javax.net.ssl.HttpsURLConnection(new java.net.URL(address)) {
                    @Override public int getResponseCode() { return 200; }
                    @Override public long getContentLengthLong() { return body.length; }
                    @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(body); }
                    @Override public void connect() { }
                    @Override public void disconnect() { }
                    @Override public boolean usingProxy() { return false; }
                    @Override public String getCipherSuite() { return "test"; }
                    @Override public java.security.cert.Certificate[] getLocalCertificates() { return null; }
                    @Override public java.security.cert.Certificate[] getServerCertificates() { return null; }
                };
            }
        };
        if (android.os.Build.VERSION.SDK_INT >= 26) Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(false);
        controller.resume(); drain(); assertEquals(0, downloads);
        click(AlertDialog.BUTTON_POSITIVE); drain(); assertEquals(1, downloads);
        click(AlertDialog.BUTTON_POSITIVE); drain();
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            Intent settings = Shadows.shadowOf(activity).getNextStartedActivity();
            assertNotNull(settings); assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, settings.getAction());
            Bundle state = new Bundle(); controller.save(state); controller.pause();
            Shadows.shadowOf(activity.getPackageManager()).setCanRequestPackageInstalls(true);
            controller = new UpdateController(activity, state); controller.resume(); drain();
            // Returning from Settings offers confirmation, never installs automatically.
            assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
            click(AlertDialog.BUTTON_POSITIVE); drain();
        }
        Intent installer = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(installer); assertEquals(Intent.ACTION_VIEW, installer.getAction());
        assertEquals("content", installer.getData().getScheme());
        assertEquals("application/vnd.android.package-archive", installer.getType());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, installer.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, installer.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        File downloaded = new File(new File(context.getCacheDir(), "updates"), installer.getData().getLastPathSegment());
        assertTrue(downloaded.isFile()); assertEquals(3, downloaded.length());
        assertFalse(new File(downloaded.getPath().replace(".apk", ".part")).exists());
        assertEquals(1, checks); assertEquals(1, downloads);
        controller.pause(); controller.resume(); drain();
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        ArchivePackageManager.archive = null;
    }
    @Test public void providerIsReadOnlyAndRejectsTraversalAndPartials() throws Exception {
        File dir = new File(context.getCacheDir(), "updates"); dir.mkdirs();
        File file = new File(dir, "00000000-0000-0000-0000-000000000002.apk");
        try (FileOutputStream out = new FileOutputStream(file)) { out.write(42); }
        UpdateApkProvider provider = Robolectric.buildContentProvider(UpdateApkProvider.class).create().get();
        Uri uri = UpdateApkProvider.uri(context, file);
        assertEquals("content", uri.getScheme());
        try (android.os.ParcelFileDescriptor fd = provider.openFile(uri, "r")) { assertNotNull(fd); }
        for (String mode : new String[]{"w", "rw", "wa"}) {
            try { provider.openFile(uri, mode); fail(); } catch (java.io.FileNotFoundException expected) { }
        }
        for (String path : new String[]{"../secret", "update.part", "arbitrary.apk"}) {
            Uri bad = uri.buildUpon().path(path).build();
            try { provider.openFile(bad, "r"); fail(); } catch (java.io.FileNotFoundException expected) { }
        }
        file.delete();
    }
}
