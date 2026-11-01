package com.abdullaherturk.duopanel;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;
import java.util.function.BooleanSupplier;

/** Two bounded windows: a small pull tab, and an on-demand scrollable sidebar. */
final class EdgeDrawer {
    private final Context context;
    private final WindowManager wm;
    private final View panel;
    final View handle;
    final WindowManager.LayoutParams panelParams;
    private final WindowManager.LayoutParams handleParams;
    private final boolean right;
    private final Runnable changed;
    private final BooleanSupplier childWindowOpen;
    private boolean active, expanded, attached;

    EdgeDrawer(Context context, WindowManager wm, View panel, int type, boolean right,
               Runnable changed, BooleanSupplier childWindowOpen) {
        this.context = context;
        this.wm = wm;
        this.panel = panel;
        this.right = right;
        this.changed = changed;
        this.childWindowOpen = childWindowOpen;
        TextView tab = new TextView(context);
        tab.setId(R.id.drawer_handle);
        tab.setText(right ? "‹" : "›");
        tab.setTextSize(24);
        tab.setGravity(Gravity.CENTER);
        tab.setTextColor(UiUtil.attr(context, R.attr.dpAccent));
        tab.setBackgroundResource(R.drawable.bg_glass_r14);
        tab.setContentDescription(context.getString(R.string.strip_open));
        tab.setFocusable(true);
        handle = tab;
        panelParams = params(type);
        panelParams.flags |= WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH;
        handleParams = params(type);
        layout();
        tab.setOnClickListener(v -> open());
        tab.setOnTouchListener(gesture(true));
        View header = panel.findViewById(R.id.drawer_header);
        header.setOnTouchListener(gesture(false));
        panel.findViewById(R.id.drawer_close).setOnClickListener(v -> collapse());
        panel.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) {
                // A higher overlay (the app grid/menu) owns its own input stream.
                if (!childWindowOpen.getAsBoolean()) collapse();
                return true;
            }
            return false;
        });
    }

    private WindowManager.LayoutParams params(int type) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 30) {
            lp.setFitInsetsTypes(0);
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        } else if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return lp;
    }

    static Rect usableBounds(Context context, WindowManager wm) {
        return usableBounds(context, wm, null);
    }

    static Rect usableBounds(Context context, WindowManager wm, View anchor) {
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.WindowMetrics metrics = wm.getCurrentWindowMetrics();
            Rect bounds = new Rect(metrics.getBounds());
            android.graphics.Insets insets = metrics.getWindowInsets().getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            bounds.inset(insets.left, insets.top, insets.right, insets.bottom);
            return bounds;
        }
        DisplayMetrics metrics = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(metrics);
        int top = dimension(context, "status_bar_height");
        int bottom = dimension(context, "navigation_bar_height");
        int side = 0;
        if (metrics.widthPixels > metrics.heightPixels
                && context.getResources().getConfiguration().smallestScreenWidthDp < 600) {
            side = dimension(context, "navigation_bar_width");
            bottom = 0;
        }
        WindowInsets insets = anchor == null ? null : anchor.getRootWindowInsets();
        int left = 0;
        if (insets != null) {
            left = Math.max(insets.getStableInsetLeft(), insets.getSystemWindowInsetLeft());
            top = Math.max(insets.getStableInsetTop(), insets.getSystemWindowInsetTop());
            side = Math.max(insets.getStableInsetRight(), insets.getSystemWindowInsetRight());
            bottom = Math.max(insets.getStableInsetBottom(), insets.getSystemWindowInsetBottom());
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                left = Math.max(left, cutout.getSafeInsetLeft());
                top = Math.max(top, cutout.getSafeInsetTop());
                side = Math.max(side, cutout.getSafeInsetRight());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }
        }
        return new Rect(left, top, metrics.widthPixels - side, metrics.heightPixels - bottom);
    }

    private static int dimension(Context c, String name) {
        int id = c.getResources().getIdentifier(name, "dimen", "android");
        return id == 0 ? 0 : c.getResources().getDimensionPixelSize(id);
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void layout() {
        layoutIn(usableBounds(context, wm, handle));
    }

    private void layoutIn(Rect safe) {
        int width = Math.max(1, safe.width()), height = Math.max(1, safe.height());
        handleParams.width = Math.min(dp(24), width);
        handleParams.height = Math.min(dp(56), height);
        handleParams.x = right ? safe.right - handleParams.width : safe.left;
        handleParams.y = safe.top + (height - handleParams.height) / 2;
        panelParams.width = Math.max(1, Math.min(dp(288), width - dp(16)));
        panelParams.height = Math.max(1, Math.min(dp(600), height - dp(16)));
        panelParams.x = right ? safe.right - panelParams.width : safe.left;
        panelParams.y = safe.top + (height - panelParams.height) / 2;
    }

    private void updateInsets(WindowInsets insets) {
        if (!attached) return;
        int x = handleParams.x, y = handleParams.y, w = panelParams.width, h = panelParams.height;
        if (Build.VERSION.SDK_INT >= 30) layout();
        else {
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            int left = Math.max(insets.getStableInsetLeft(), insets.getSystemWindowInsetLeft());
            int top = Math.max(insets.getStableInsetTop(), insets.getSystemWindowInsetTop());
            int right = Math.max(insets.getStableInsetRight(), insets.getSystemWindowInsetRight());
            int bottom = Math.max(insets.getStableInsetBottom(), insets.getSystemWindowInsetBottom());
            if (Build.VERSION.SDK_INT >= 28 && insets.getDisplayCutout() != null) {
                android.view.DisplayCutout cutout = insets.getDisplayCutout();
                left = Math.max(left, cutout.getSafeInsetLeft());
                top = Math.max(top, cutout.getSafeInsetTop());
                right = Math.max(right, cutout.getSafeInsetRight());
                bottom = Math.max(bottom, cutout.getSafeInsetBottom());
            }
            layoutIn(new Rect(left, top, metrics.widthPixels - right, metrics.heightPixels - bottom));
        }
        if (x != handleParams.x || y != handleParams.y || w != panelParams.width || h != panelParams.height) {
            wm.updateViewLayout(panel, panelParams);
            wm.updateViewLayout(handle, handleParams);
        }
    }

    void attach() {
        ((OverlayService.StripLayout) panel).setPanelDownListener(time -> lastPanelDown = time);
        handle.setOnApplyWindowInsetsListener((v, insets) -> { updateInsets(insets); return insets; });
        panel.setVisibility(View.GONE);
        handle.setVisibility(View.GONE);
        try {
            wm.addView(panel, panelParams);
            wm.addView(handle, handleParams);
            attached = true;
            layout();
            wm.updateViewLayout(panel, panelParams);
            wm.updateViewLayout(handle, handleParams);
        } catch (RuntimeException error) {
            try { wm.removeView(panel); } catch (RuntimeException ignored) { }
            try { wm.removeView(handle); } catch (RuntimeException ignored) { }
            throw error;
        }
    }

    void setActive(boolean active) {
        this.active = active;
        if (!active) collapse();
        handle.setVisibility(active && !expanded ? View.VISIBLE : View.GONE);
        panel.setVisibility(active && expanded ? View.VISIBLE : View.GONE);
    }

    void open() {
        if (!active || !attached || expanded) return;
        expanded = true;
        panel.setVisibility(View.VISIBLE);
        handle.setVisibility(View.GONE);
        changed.run();
    }

    void collapse() {
        cancelChildOutside();
        boolean wasExpanded = expanded;
        expanded = false;
        panel.setVisibility(View.GONE);
        handle.setVisibility(active && attached ? View.VISIBLE : View.GONE);
        if (wasExpanded) changed.run();
    }



    private long lastPanelDown = Long.MIN_VALUE;
    private Runnable childOutside;

    /** OUTSIDE coordinates may be redacted; wait for the sibling window's real DOWN. */
    void childOutside(long eventTime) {
        if (childOutside != null) panel.removeCallbacks(childOutside);
        childOutside = () -> {
            childOutside = null;
            if (attached && expanded && lastPanelDown != eventTime) collapse();
        };
        panel.postOnAnimation(childOutside);
    }

    private void cancelChildOutside() {
        if (childOutside != null) panel.removeCallbacks(childOutside);
        childOutside = null;
    }

    void detach() {
        cancelChildOutside();
        ((OverlayService.StripLayout) panel).setPanelDownListener(null);
        handle.setOnApplyWindowInsetsListener(null);
        active = false;
        expanded = false;
        if (attached) {
            try { wm.removeView(panel); } catch (RuntimeException ignored) { }
            try { wm.removeView(handle); } catch (RuntimeException ignored) { }
        }
        attached = false;
    }

    private View.OnTouchListener gesture(final boolean opening) {
        return new View.OnTouchListener() {
            float x, y;
            long down;
            boolean canceled;
            @Override public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        x = event.getRawX(); y = event.getRawY(); down = event.getEventTime(); canceled = false;
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                    case MotionEvent.ACTION_CANCEL:
                    case MotionEvent.ACTION_OUTSIDE:
                        canceled = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (event.getPointerCount() > 1) canceled = true;
                        return true;
                    case MotionEvent.ACTION_UP:
                        float dx = event.getRawX() - x, dy = event.getRawY() - y;
                        if (canceled) return true;
                        float inward = right ? -dx : dx;
                        if (Math.abs(dy) <= Math.max(dp(24), Math.abs(dx))) {
                            if (opening && inward > dp(24)) open();
                            else if (!opening && inward < -dp(24)) collapse();
                            else if (Math.abs(dx) < dp(8) && Math.abs(dy) < dp(8)) {
                                if (!opening && event.getEventTime() - down > 700) view.performLongClick();
                                else view.performClick();
                            }
                        }
                        return true;
                }
                return false;
            }
        };
    }
}
