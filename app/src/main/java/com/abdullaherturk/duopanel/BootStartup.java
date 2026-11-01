package com.abdullaherturk.duopanel;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.provider.Settings;

/** One bounded, lifecycle-owned queue for explicit and automatic startup requests. */
final class BootStartup {
    interface Host {
        boolean ready();
        boolean visible();
        void dispatch(String left, String right);
        void recognized();
    }
    static final long EARLY_BOOT_MS = 300000, PENDING_MS = 20000, DEADLINE_MS = 60000;
    private final Context context;
    private final Handler handler;
    private final Host host;
    private final long started = SystemClock.elapsedRealtime();
    private boolean closed, finished, registered;
    private String waiting;
    private final Runnable tick = this::evaluate;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { wake(); }
    };

    BootStartup(Context context, Handler handler, Host host) {
        this.context = context;
        this.handler = handler;
        this.host = host;
    }

    void start() {
        IntentFilter filter = new IntentFilter(Intent.ACTION_USER_PRESENT);
        filter.addAction(Intent.ACTION_USER_UNLOCKED);
        filter.addAction(Intent.ACTION_BOOT_COMPLETED);
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else context.registerReceiver(receiver, filter);
        registered = true;
        SplitLog.add("startup scheduled: readiness queue");
        wake();
    }

    void wake() {
        if (closed || finished) return;
        handler.removeCallbacks(tick);
        handler.post(tick);
    }

    void close() {
        if (closed) return;
        if (!finished) log("cancelled: disconnected");
        closed = true;
        handler.removeCallbacks(tick);
        unregister();
    }

    private void unregister() {
        if (registered) { context.unregisterReceiver(receiver); registered = false; }
    }

    private void finish(String reason) {
        finished = true;
        handler.removeCallbacks(tick);
        unregister();
        log(reason);
    }

    private void log(String reason) {
        SplitLog.add("startup " + reason + " elapsed=" + (SystemClock.elapsedRealtime() - started) + "ms");
    }

    private long bootToken() {
        // BOOT_COUNT survives process death and wall-clock corrections (Android 24+).
        int count = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        return count >= 0 ? -2L - count : (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60000L;
    }

    private boolean consumed(long token) {
        long saved = Prefs.sp(context).getLong("startupDispatchBoot", Long.MIN_VALUE);
        if (saved == Long.MIN_VALUE) {
            // Respect the previous release's per-boot marker when upgrading in the same boot.
            long old = Prefs.getLong(context, "autoBoot");
            long epoch = (System.currentTimeMillis() - SystemClock.elapsedRealtime()) / 60000L;
            return old != 0 && Math.abs(old - epoch) <= 1;
        }
        return token < 0 ? saved == token : saved >= 0 && Math.abs(saved - token) <= 1;
    }

    private void evaluate() {
        if (closed || finished) return;
        long now = SystemClock.elapsedRealtime();
        boolean pending = Prefs.sp(context).contains("pendingLaunch");
        long timestamp = Prefs.getLong(context, "pendingLaunch");
        if (pending && (timestamp < 0 || now < timestamp || now - timestamp >= PENDING_MS)) {
            Prefs.sp(context).edit().remove("pendingLaunch").apply();
            log("discarded stale explicit request");
            pending = false;
        }
        long token = bootToken();
        boolean automatic = Prefs.autoLaunch(context) && now < EARLY_BOOT_MS && !consumed(token);
        if (now - started >= DEADLINE_MS) { finish("cancelled: readiness timeout"); return; }
        if (!pending && !automatic) { defer("no eligible request"); return; }
        if (!Prefs.hasPair(context)) { defer("invalid saved pair"); return; }
        if (!unlocked(context)) { defer("locked or user not unlocked"); return; }
        if (!host.ready()) { defer("accessibility windows or launch busy"); return; }
        // Read selections only at execution, never capture them when enqueueing.
        String left = Prefs.side(context, Prefs.LEFT), right = Prefs.side(context, Prefs.RIGHT);
        boolean visible = host.visible();
        // Do not consume a request before the host has accepted it. A synchronous
        // failure must remain retryable, including across a service reconnect.
        try {
            if (visible) host.recognized();
            else host.dispatch(left, right);
        } catch (RuntimeException e) {
            log("host rejected request: " + e);
            defer("dispatch or recognition failed");
            return;
        }
        // No definitive platform completion callback exists. This records accepted
        // dispatch/recognition, not successful split completion. Even if persistence
        // fails, finish this queue to avoid dispatching the same accepted work again.
        boolean persisted = Prefs.sp(context).edit().putLong("startupDispatchBoot", token)
                .remove("pendingLaunch").commit();
        finish((pending ? "explicit " : "automatic ") + (visible ? "recognized visible pair" : "dispatch")
                + (persisted ? "" : " (dispatch guard persistence failed)"));
    }

    private void defer(String reason) {
        if (!reason.equals(waiting)) { waiting = reason; log("waiting: " + reason); }
        handler.postDelayed(tick, 250);
    }

    static boolean unlocked(Context context) {
        UserManager user = (UserManager) context.getSystemService(Context.USER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        return user != null && user.isUserUnlocked() && keyguard != null
                && !keyguard.isKeyguardLocked() && power != null && power.isInteractive();
    }
}
