package org.telegram.ui.Components.Shorts;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Fullscreen horizontal snap pager. The layout is reversed so that moving the finger to the
 * right reveals the next clip and moving it to the left the previous one.
 * <p>
 * Axis arbitration: the axis is chosen once the touch passes touch slop and one axis dominates
 * the other by {@link #AXIS_DOMINANCE}. Only horizontal drags reach the RecyclerView; vertical
 * drags fire {@link Listener#onVerticalSwipe(boolean)} on release after {@link #VERTICAL_TRIGGER_DP}.
 * Children that call requestDisallowInterceptTouchEvent (buttons, timeline) keep the gesture.
 */
public class ShortsViewPager extends RecyclerView {

    public static final float AXIS_DOMINANCE = 1.25f;
    public static final int VERTICAL_TRIGGER_DP = 72;

    private static final int AXIS_NONE = 0;
    private static final int AXIS_HORIZONTAL = 1;
    private static final int AXIS_VERTICAL = 2;

    public interface Listener {
        void onPageSelected(int position);

        /** up = finger moved up (open channel), otherwise down (open comments). */
        void onVerticalSwipe(boolean up);
    }

    private final LinearLayoutManager layoutManager;
    private final PagerSnapHelper snapHelper = new PagerSnapHelper();
    private final int touchSlop;
    private Listener listener;

    private float downX, downY;
    private int axis = AXIS_NONE;
    private boolean childOwnsGesture;
    private int currentPage = NO_POSITION;

    public ShortsViewPager(@NonNull Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        layoutManager = new LinearLayoutManager(context, HORIZONTAL, true) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }

            @Override
            protected int getExtraLayoutSpace(State state) {
                return getWidth();
            }
        };
        setLayoutManager(layoutManager);
        setItemAnimator(null);
        setHasFixedSize(true);
        setOverScrollMode(OVER_SCROLL_NEVER);
        snapHelper.attachToRecyclerView(this);
        addOnScrollListener(new OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == SCROLL_STATE_IDLE) {
                    dispatchCurrentPage();
                }
            }
        });
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    public int getCurrentPage() {
        return currentPage;
    }

    public void setCurrentPage(int position, boolean smooth) {
        if (position < 0) {
            return;
        }
        if (smooth && !ShortsActivity.isReducedMotion()) {
            smoothScrollToPosition(position);
        } else {
            layoutManager.scrollToPositionWithOffset(position, 0);
            post(this::dispatchCurrentPage);
        }
    }

    /** Keeps the pager's notion of the current page in sync after items before it were removed. */
    public void onPageShifted(int position) {
        currentPage = position;
    }

    public View findPageView(int position) {
        return layoutManager.findViewByPosition(position);
    }

    private void dispatchCurrentPage() {
        final View snap = snapHelper.findSnapView(layoutManager);
        if (snap == null) {
            return;
        }
        final int position = layoutManager.getPosition(snap);
        if (position != NO_POSITION && position != currentPage) {
            currentPage = position;
            if (listener != null) {
                listener.onPageSelected(position);
            }
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (getScrollState() == SCROLL_STATE_IDLE) {
            post(this::dispatchCurrentPage);
        }
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (currentPage != NO_POSITION && (w != oldw || h != oldh)) {
            final int page = currentPage;
            post(() -> layoutManager.scrollToPositionWithOffset(page, 0));
        }
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallow) {
        if (disallow) {
            childOwnsGesture = true;
        }
        super.requestDisallowInterceptTouchEvent(disallow);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = e.getX();
                downY = e.getY();
                axis = AXIS_NONE;
                childOwnsGesture = false;
                break;
            case MotionEvent.ACTION_MOVE:
                resolveAxis(e);
                break;
        }
        final boolean result = super.dispatchTouchEvent(e);
        final int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_UP && axis == AXIS_VERTICAL && !childOwnsGesture) {
            final float dy = e.getY() - downY;
            if (Math.abs(dy) > dp(VERTICAL_TRIGGER_DP) && listener != null) {
                listener.onVerticalSwipe(dy < 0);
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            axis = AXIS_NONE;
        }
        return result;
    }

    private void resolveAxis(MotionEvent e) {
        if (axis != AXIS_NONE || childOwnsGesture) {
            return;
        }
        final float dx = Math.abs(e.getX() - downX);
        final float dy = Math.abs(e.getY() - downY);
        if (Math.max(dx, dy) <= touchSlop) {
            return;
        }
        if (dx > AXIS_DOMINANCE * dy) {
            axis = AXIS_HORIZONTAL;
        } else if (dy > AXIS_DOMINANCE * dx) {
            axis = AXIS_VERTICAL;
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        final int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            return super.onInterceptTouchEvent(e);
        }
        if (axis != AXIS_HORIZONTAL || childOwnsGesture) {
            return false;
        }
        return super.onInterceptTouchEvent(e);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_MOVE && axis == AXIS_VERTICAL) {
            return true;
        }
        return super.onTouchEvent(e);
    }
}
