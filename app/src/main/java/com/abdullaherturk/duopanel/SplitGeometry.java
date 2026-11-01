package com.abdullaherturk.duopanel;

import android.graphics.Rect;
import java.util.Comparator;
import java.util.List;

/** One snapshot in screen coordinates. The caller transfers ownership of the bounds. */
final class SplitGeometry {
    final Rect frame, first, second, divider;
    final boolean horizontal, firstIsLeft;
    private final List<Rect> firstWindows, secondWindows;
    private final int tolerance, maxGap;
    private static final Comparator<Rect> HORIZONTAL = (a, b) -> compare(a, b, true);
    private static final Comparator<Rect> VERTICAL = (a, b) -> compare(a, b, false);

    SplitGeometry(Rect frame, List<Rect> leftWindows, List<Rect> rightWindows, Rect divider, float density) {
        this.frame = frame;
        this.divider = divider;
        Rect a = union(leftWindows), b = union(rightWindows);
        horizontal = Math.abs(a.centerX() - b.centerX()) >= Math.abs(a.centerY() - b.centerY());
        firstIsLeft = horizontal ? a.centerX() < b.centerX() : a.centerY() < b.centerY();
        first = firstIsLeft ? a : b;
        second = firstIsLeft ? b : a;
        Comparator<Rect> order = horizontal ? HORIZONTAL : VERTICAL;
        leftWindows.sort(order);
        rightWindows.sort(order);
        firstWindows = firstIsLeft ? leftWindows : rightWindows;
        secondWindows = firstIsLeft ? rightWindows : leftWindows;
        tolerance = Math.max(2, Math.round(4 * density));
        maxGap = Math.max(Math.round(16 * density), divider == null ? 0
                : horizontal ? divider.width() : divider.height());
    }

    private static Rect union(List<Rect> windows) {
        Rect bounds = new Rect();
        for (Rect window : windows) bounds.union(window);
        return bounds;
    }

    private static int compare(Rect a, Rect b, boolean horizontal) {
        int result = Integer.compare(horizontal ? a.left : a.top, horizontal ? b.left : b.top);
        if (result == 0) result = Integer.compare(horizontal ? a.right : a.bottom, horizontal ? b.right : b.bottom);
        if (result == 0) result = Integer.compare(horizontal ? a.top : a.left, horizontal ? b.top : b.left);
        if (result == 0) result = Integer.compare(horizontal ? a.bottom : a.right, horizontal ? b.bottom : b.right);
        return result;
    }

    /** Aligned panes, not a small freeform window inside a larger split slot. */
    boolean isTiled() {
        int gap = horizontal ? second.left - first.right : second.top - first.bottom;
        if (gap < -tolerance || gap > maxGap) return false;
        return horizontal
                ? first.top <= frame.top + tolerance && second.top <= frame.top + tolerance
                    && first.bottom >= frame.bottom - tolerance && second.bottom >= frame.bottom - tolerance
                : first.left <= frame.left + tolerance && second.left <= frame.left + tolerance
                    && first.right >= frame.right - tolerance && second.right >= frame.right - tolerance;
    }

    boolean coversScreen() {
        if (frame.isEmpty() || first.isEmpty() || second.isEmpty() || !isTiled()) return false;
        boolean reachesEdges = horizontal
                ? first.left <= frame.left + tolerance && second.right >= frame.right - tolerance
                : first.top <= frame.top + tolerance && second.bottom >= frame.bottom - tolerance;
        return reachesEdges && coversPane(firstWindows, first) && coversPane(secondWindows, second);
    }

    private boolean coversPane(List<Rect> windows, Rect bounds) {
        int edge = horizontal ? bounds.left : bounds.top;
        for (Rect window : windows) {
            // Contained dialogs do not add coverage or invalidate an already covered base pane.
            if (window.isEmpty() || (horizontal
                    ? window.top > frame.top + tolerance || window.bottom < frame.bottom - tolerance
                    : window.left > frame.left + tolerance || window.right < frame.right - tolerance)) continue;
            int start = horizontal ? window.left : window.top;
            if (start > edge + tolerance) return false;
            edge = Math.max(edge, horizontal ? window.right : window.bottom);
        }
        return edge >= (horizontal ? bounds.right : bounds.bottom) - tolerance;
    }

    int startX() {
        return divider != null ? divider.centerX() : horizontal
                ? (first.right + second.left) / 2 : first.centerX();
    }

    int startY() {
        return divider != null ? divider.centerY() : horizontal
                ? first.centerY() : (first.bottom + second.top) / 2;
    }

    int targetX(int percent) {
        return horizontal ? frame.left + Math.round(frame.width() * percent / 100f) : startX();
    }

    int targetY(int percent) {
        return horizontal ? startY() : frame.top + Math.round(frame.height() * percent / 100f);
    }

    int percent() {
        if (frame.isEmpty()) return -1;
        return horizontal ? Math.round((startX() - frame.left) * 100f / frame.width())
                : Math.round((startY() - frame.top) * 100f / frame.height());
    }

    boolean sameBounds(SplitGeometry other) {
        return other != null && horizontal == other.horizontal && firstIsLeft == other.firstIsLeft
                && frame.equals(other.frame) && first.equals(other.first) && second.equals(other.second)
                && firstWindows.equals(other.firstWindows) && secondWindows.equals(other.secondWindows)
                && (divider == null ? other.divider == null : divider.equals(other.divider));
    }
}
