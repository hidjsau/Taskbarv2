package com.farmerbb.taskbar.ui;

import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import java.util.function.Consumer;

/**
 * Frosted-glass backdrop for the taskbar (Android 12+).
 *
 * The taskbar is a WindowManager overlay view, which has no android.view.Window, and
 * FLAG_BLUR_BEHIND would blur the entire screen. Window background blur
 * (Window#setBackgroundBlurRadius) is clipped to its own window bounds, but only exists on
 * Window. So this class owns a taskbar-sized, non-touchable, translucent Dialog
 * (TYPE_APPLICATION_OVERLAY) that is added BEFORE the taskbar window, so it sits below it.
 * It follows the taskbar's on-screen bounds and is hidden whenever the taskbar is collapsed.
 *
 * Pattern based on alkisstam/taskbarandroid PR #11 (DockBlurWindow).
 */
@RequiresApi(Build.VERSION_CODES.S)
public class TaskbarBlurWindow {

    private static final String TAG = "TaskbarBlurWindow";
    private static final int BLUR_RADIUS_DP = 24;
    private static final long SETTLE_MS = 50;
    private static final long FADE_MS = 160;

    private final Context context;
    private final View layout;
    private final View expandedIndicator;
    private final Runnable onBlurStateChanged;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int blurRadiusPx;
    private final int[] loc = new int[2];

    private Dialog dialog;
    private ValueAnimator fade;
    private boolean visible;
    private int shownL = -1, shownT = -1, shownR = -1, shownB = -1;
    private int seenL = -1, seenT = -1, seenR = -1, seenB = -1;
    private boolean seenExpanded;
    private boolean attached;

    private final Consumer<Boolean> blurListener;

    private final ViewTreeObserver.OnPreDrawListener preDraw = () -> {
        check();
        return true;
    };

    private final Runnable apply = this::applyNow;

    /** Whether the system currently allows cross-window blur (API 31+, GPU, battery saver...). */
    public static boolean isSupported(Context context) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false;
        try {
            WindowManager wm = context.getSystemService(WindowManager.class);
            return wm != null && wm.isCrossWindowBlurEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * @param layout            the taskbar root view
     * @param expandedIndicator view whose VISIBLE state means "taskbar is expanded" (start button)
     * @param onBlurStateChanged called when system blur is switched on/off at runtime, so the
     *                           caller can refresh the bar tint
     */
    public TaskbarBlurWindow(Context context, View layout, View expandedIndicator,
                             Runnable onBlurStateChanged) {
        this.context = context;
        this.layout = layout;
        this.expandedIndicator = expandedIndicator;
        this.onBlurStateChanged = onBlurStateChanged;
        this.blurRadiusPx = (int) (BLUR_RADIUS_DP * context.getResources().getDisplayMetrics().density);
        this.blurListener = enabled -> {
            if(!enabled) hide();
            else {
                seenL = -1;
                handler.post(this::check);
            }
            if(this.onBlurStateChanged != null) this.onBlurStateChanged.run();
        };
    }

    /** Creates the (hidden) window so it is registered BEFORE the taskbar window. */
    public void attach() {
        if(attached) return;
        try {
            Dialog d = new Dialog(context, android.R.style.Theme_Translucent_NoTitleBar);
            d.setCancelable(false);
            d.setContentView(new View(context));

            Window w = d.getWindow();
            if(w == null) return;

            w.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            w.setFormat(PixelFormat.TRANSLUCENT);
            w.setWindowAnimations(0);
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);

            WindowManager.LayoutParams p = w.getAttributes();
            p.gravity = Gravity.TOP | Gravity.START;
            p.width = 1;
            p.height = 1;
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            w.setAttributes(p);

            d.show();
            // hide() only sets the decor GONE; the window keeps its z-order slot
            d.hide();

            dialog = d;
            visible = false;
            attached = true;

            layout.getViewTreeObserver().addOnPreDrawListener(preDraw);
            context.getSystemService(WindowManager.class)
                    .addCrossWindowBlurEnabledListener(context.getMainExecutor(), blurListener);
        } catch (Exception e) {
            Log.w(TAG, "Failed to attach blur window", e);
            detach();
        }
    }

    public void detach() {
        handler.removeCallbacks(apply);
        if(fade != null) fade.cancel();
        fade = null;

        try {
            ViewTreeObserver vto = layout.getViewTreeObserver();
            if(vto.isAlive()) vto.removeOnPreDrawListener(preDraw);
        } catch (Exception ignored) {}

        try {
            context.getSystemService(WindowManager.class)
                    .removeCrossWindowBlurEnabledListener(blurListener);
        } catch (Exception ignored) {}

        try {
            if(dialog != null) dialog.dismiss();
        } catch (Exception e) {
            Log.w(TAG, "Failed to remove blur window", e);
        }

        dialog = null;
        visible = false;
        attached = false;
    }

    // ---------------------------------------------------------------------------------------

    private boolean isExpanded() {
        return layout.isAttachedToWindow()
                && expandedIndicator.getVisibility() == View.VISIBLE
                && layout.getAlpha() > 0f
                && layout.getWidth() > 0
                && layout.getHeight() > 0
                && layout.getVisibility() == View.VISIBLE;
    }

    /** Runs on every taskbar draw: cheap compare, then debounce a real update. */
    private void check() {
        if(!attached) return;

        boolean expanded = isExpanded();
        layout.getLocationOnScreen(loc);
        int l = loc[0], t = loc[1], r = l + layout.getWidth(), b = t + layout.getHeight();

        if(expanded == seenExpanded && l == seenL && t == seenT && r == seenR && b == seenB)
            return;

        seenExpanded = expanded;
        seenL = l; seenT = t; seenR = r; seenB = b;

        if(!expanded) {
            handler.removeCallbacks(apply);
            hide();
            return;
        }

        handler.removeCallbacks(apply);
        handler.postDelayed(apply, SETTLE_MS);
    }

    private void applyNow() {
        if(!attached || dialog == null) return;
        if(!isExpanded() || !isSupported(context)) {
            hide();
            return;
        }

        layout.getLocationOnScreen(loc);
        int l = loc[0], t = loc[1], r = l + layout.getWidth(), b = t + layout.getHeight();
        if(visible && l == shownL && t == shownT && r == shownR && b == shownB) return;

        Window w = dialog.getWindow();
        if(w == null) return;

        try {
            boolean firstShow = !visible;

            WindowManager.LayoutParams p = w.getAttributes();
            p.x = l;
            p.y = t;
            p.width = r - l;
            p.height = b - t;
            if(firstShow) p.alpha = 0f;
            w.setAttributes(p);

            if(firstShow) {
                dialog.show();
                w.setBackgroundBlurRadius(blurRadiusPx);
                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.TRANSPARENT);
                bg.setCornerRadius(0f);
                w.setBackgroundDrawable(bg);
                visible = true;

                if(fade != null) fade.cancel();
                fade = ValueAnimator.ofFloat(0f, 1f);
                fade.setDuration(FADE_MS);
                fade.addUpdateListener(a -> {
                    if(dialog == null || dialog.getWindow() == null) return;
                    WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
                    lp.alpha = (float) a.getAnimatedValue();
                    dialog.getWindow().setAttributes(lp);
                });
                fade.start();
            }

            shownL = l; shownT = t; shownR = r; shownB = b;
        } catch (Exception e) {
            Log.w(TAG, "Failed to show blur window", e);
        }
    }

    private void hide() {
        handler.removeCallbacks(apply);
        if(fade != null) fade.cancel();
        fade = null;
        shownL = shownT = shownR = shownB = -1;
        if(dialog != null && visible) {
            try {
                dialog.hide();
            } catch (Exception e) {
                Log.w(TAG, "Failed to hide blur window", e);
            }
        }
        visible = false;
    }
}
