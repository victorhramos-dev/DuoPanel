package com.abdullaherturk.duopanel;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** UI exists only while the real main screen is resumed. Pausing cancels network and dialogs. */
final class UpdateController {
    interface Factory { UpdateClient create(); }
    static Factory factory = UpdateClient::new;
    interface ExecutorFactory { ExecutorService create(); }
    static ExecutorFactory executorFactory = Executors::newSingleThreadExecutor;
    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private UpdateClient client;
    private AlertDialog dialog;
    private ProgressDialog progress;
    private boolean visible, busy, waitingSettings, installing, manualCheck;
    private int generation;
    private UpdateClient.Download ready;
    static final long INTERVAL = 24 * 60 * 60 * 1000L;

    UpdateController(Activity activity, Bundle saved) {
        this.activity = activity;
        if (saved != null && saved.getBoolean("update.waiting")) {
            String name = saved.getString("update.file", "");
            if (UpdateApkProvider.validName(name)) {
                File f = new File(new File(activity.getCacheDir(), "updates"), name);
                if (f.isFile()) {
                    ready = new UpdateClient.Download(f, saved.getLong("update.size"), saved.getString("update.sha"));
                    waitingSettings = true;
                }
            }
        }
    }
    void save(Bundle state) {
        if (waitingSettings && ready != null) {
            state.putBoolean("update.waiting", true); state.putString("update.file", ready.file.getName());
            state.putLong("update.size", ready.size); state.putString("update.sha", ready.sha);
        }
    }
    static boolean due(long now, long last) { return last <= 0 || now < last || now - last >= INTERVAL; }
    synchronized void resume() {
        visible = true;
        executor = executorFactory.create();
        if (waitingSettings) {
            waitingSettings = false; // Consume once: denial/back must never reopen Settings.
            if (canInstall() && ready != null) offerInstall();
            else { clearReady(); message(R.string.update_permission_denied); }
            return;
        }
        if (installing) { installing = false; clearReady(); return; }
        check(false);
    }
    synchronized void pause() {
        visible = false; generation++; busy = false;
        if (client != null) client.cancel();
        client = null;
        if (executor != null) executor.shutdownNow();
        executor = null;
        handler.removeCallbacksAndMessages(null);
        if (dialog != null) dialog.dismiss(); dialog = null;
        if (progress != null) progress.dismiss(); progress = null;
        if (!waitingSettings && !installing) clearReady();
    }
    private void clearReady() { if (ready != null) ready.file.delete(); ready = null; }
    private void message(int resource) { if (visible) Toast.makeText(activity, resource, Toast.LENGTH_LONG).show(); }
    private boolean canInstall() { return Build.VERSION.SDK_INT < 26 || activity.getPackageManager().canRequestPackageInstalls(); }

    synchronized void check(boolean manual) {
        if (!visible || waitingSettings || installing || activity.isFinishing()) return;
        if (busy) {
            // Join the automatic check without starting a duplicate request.
            if (manual && progress == null) {
                manualCheck = true;
                message(R.string.update_checking);
            }
            return;
        }
        if (dialog != null && dialog.isShowing()) return;
        if (ready != null) { offerInstall(); return; }
        SharedPreferences prefs = activity.getSharedPreferences("duo", Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && !due(now, prefs.getLong("update.lastCheck", 0))) return;
        prefs.edit().putLong("update.lastCheck", now).apply();
        manualCheck = manual;
        begin(manual ? R.string.update_checking : 0);
        final Context context = activity.getApplicationContext();
        final UpdateClient request = client;
        final WeakReference<UpdateController> ref = new WeakReference<>(this);
        final int id = generation;
        executor.execute(() -> {
            try {
                UpdateClient.cleanup(context);
                UpdateRelease release = request.check();
                String current = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
                boolean newer = UpdateRelease.compare(release.tag, current) > 0;
                post(ref, id, c -> {
                    c.end();
                    if (newer) c.offer(release);
                    else if (c.manualCheck) c.message(R.string.update_current);
                });
            } catch (Exception error) { post(ref, id, c -> { c.end(); if (c.manualCheck) c.message(R.string.update_error); }); }
        });
    }
    private void begin(int label) {
        busy = true; generation++; client = factory.create();
        if (label == 0) return; // Automatic checks must not block the main screen.
        progress = new ProgressDialog(activity);
        progress.setMessage(activity.getString(label));
        progress.setCancelable(true);
        progress.setOnCancelListener(d -> { pause(); resumeWithoutCheck(); });
        progress.show();
    }
    private synchronized void resumeWithoutCheck() { visible = true; executor = executorFactory.create(); }
    private void end() {
        busy = false; client = null;
        if (progress != null) progress.dismiss(); progress = null;
    }
    private interface Result { void apply(UpdateController c); }
    private static void post(WeakReference<UpdateController> ref, int id, Result result) {
        UpdateController c = ref.get();
        if (c == null) return;
        synchronized (c) {
            if (!c.visible || c.generation != id) return;
            c.handler.post(() -> {
                UpdateController current = ref.get();
                if (current != null && current.visible && current.generation == id && !current.activity.isFinishing()) result.apply(current);
            });
        }
    }
    private void offer(UpdateRelease release) {
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.update_available)
                .setMessage(activity.getString(R.string.update_offer, release.tag))
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_download, (d, w) -> download(release)).show();
    }
    private void download(UpdateRelease release) {
        if (!visible || busy) return;
        begin(R.string.update_downloading);
        final Context context = activity.getApplicationContext();
        final UpdateClient request = client;
        final WeakReference<UpdateController> ref = new WeakReference<>(this);
        final int id = generation;
        executor.execute(() -> {
            UpdateClient.Download result = null;
            try {
                result = request.download(context, release, percent -> post(ref, id, c -> {
                    if (c.progress != null) c.progress.setMessage(c.activity.getString(R.string.update_progress, percent));
                }));
                final UpdateClient.Download downloaded = result;
                // Transfer file ownership only while the matching foreground task is still active.
                UpdateController c = ref.get();
                if (c == null) { downloaded.file.delete(); return; }
                synchronized (c) {
                    if (!c.visible || c.generation != id) { downloaded.file.delete(); return; }
                    c.ready = downloaded;
                    post(ref, id, ui -> { ui.end(); ui.offerInstall(); });
                }
            } catch (Exception e) {
                if (result != null) result.file.delete();
                post(ref, id, c -> { c.end(); c.clearReady(); c.message(R.string.update_error); });
            }
        });
    }
    private void offerInstall() {
        if (!visible || ready == null) return;
        dialog = new AlertDialog.Builder(activity).setTitle(R.string.update_available)
                .setMessage(R.string.update_ready)
                .setNegativeButton(R.string.update_later, (d, w) -> clearReady())
                .setPositiveButton(R.string.update_install, (d, w) -> install()).show();
    }
    private void install() {
        if (!visible || busy || ready == null) return;
        if (!canInstall()) {
            waitingSettings = true;
            try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()))); }
            catch (RuntimeException e) { waitingSettings = false; clearReady(); message(R.string.update_error); }
            return;
        }
        // Revalidate after returning from Settings / recreation; never trust saved metadata alone.
        begin(R.string.update_checking);
        final Context context = activity.getApplicationContext();
        final UpdateClient.Download apk = ready;
        final WeakReference<UpdateController> ref = new WeakReference<>(this);
        final int id = generation;
        executor.execute(() -> {
            try {
                UpdateSecurity.verify(context, apk.file, apk.size, apk.sha);
                post(ref, id, c -> {
                    c.end();
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW)
                                .setDataAndType(UpdateApkProvider.uri(c.activity, apk.file), "application/vnd.android.package-archive")
                                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        c.installing = true;
                        c.activity.startActivity(intent);
                    } catch (RuntimeException e) { c.installing = false; c.clearReady(); c.message(R.string.update_error); }
                });
            } catch (Exception e) { post(ref, id, c -> { c.end(); c.clearReady(); c.message(R.string.update_error); }); }
        });
    }
}
