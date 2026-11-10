package org.telegram.ui.Components.Reels;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Fullscreen horizontal snap pager.
 * <p>
 * Axis arbitration:
 * - Horizontal swipe: Paging between clips (Right-to-Left for next, Left-to-Right for previous).
 * - Vertical swipe up: Triggers comments drawer.
 * - Vertical swipe down: Triggers dismiss / pull-to-profile transition.
 */
public class ReelsViewPager extends RecyclerView {

    public static final float AXIS_DOMINANCE = 1.25f;
    private static final int AXIS_NONE = 0;
    private static final int AXIS_HORIZONTAL = 1;
    private static final int AXIS_VERTICAL = 2;

    public interface Listener {
        void onPageSelected(int position);
        void onVerticalSwipeUp();
        void onVerticalSwipeDown();
    }

    private final LinearLayoutManager layoutManager;
    private final PagerSnapHelper snapHelper = new PagerSnapHelper();
    private final int touchSlop;
    private Listener listener;

    private float downX, downY;
    private int axis = AXIS_NONE;
    private boolean childOwnsGesture;
    private boolean verticalDispatched;
    private int currentPage = NO_POSITION;

    public ReelsViewPager(@NonNull Context context) {
        super(context);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        layoutManager = new LinearLayoutManager(context, HORIZONTAL, false) {
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
        if (smooth && !ReelsViewer.isReducedMotion()) {
            final LinearSmoothScroller scroller = new LinearSmoothScroller(getContext()) {
                @Override protected int calculateTimeForScrolling(int dx) { return 220; }
                @Override protected int calculateTimeForDeceleration(int dx) { return 320; }
            };
            scroller.setTargetPosition(position);
            layoutManager.startSmoothScroll(scroller);
        } else {
            layoutManager.scrollToPositionWithOffset(position, 0);
            post(this::dispatchCurrentPage);
        }
    }

    public void onPageShifted(int position) {
        currentPage = position;
    }

    @Override
    public void requestDisallowInterceptTouchEvent(boolean disallowIntercept) {
        childOwnsGesture = disallowIntercept;
        super.requestDisallowInterceptTouchEvent(disallowIntercept);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (childOwnsGesture) {
            return false;
        }
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            axis = AXIS_NONE;
            verticalDispatched = false;
            super.onInterceptTouchEvent(ev);
            return false;
        }
        if (action == MotionEvent.ACTION_MOVE && axis == AXIS_NONE) {
            final float dx = Math.abs(ev.getX() - downX);
            final float dy = Math.abs(ev.getY() - downY);
            if (dx > touchSlop || dy > touchSlop) {
                if (dx >= dy * AXIS_DOMINANCE) {
                    axis = AXIS_HORIZONTAL;
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                } else if (dy >= dx * AXIS_DOMINANCE) {
                    axis = AXIS_VERTICAL;
                    handleVerticalGesture(ev.getY() - downY);
                    return false;
                }
            }
        }
        return axis == AXIS_HORIZONTAL && super.onInterceptTouchEvent(ev);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (childOwnsGesture) {
            return false;
        }
        final int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_MOVE && axis == AXIS_NONE) {
            final float dx = Math.abs(ev.getX() - downX);
            final float dy = Math.abs(ev.getY() - downY);
            if (dx > touchSlop || dy > touchSlop) {
                if (dx >= dy * AXIS_DOMINANCE) {
                    axis = AXIS_HORIZONTAL;
                    getParent().requestDisallowInterceptTouchEvent(true);
                } else if (dy >= dx * AXIS_DOMINANCE) {
                    axis = AXIS_VERTICAL;
                    handleVerticalGesture(ev.getY() - downY);
                    return false;
                }
            }
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            childOwnsGesture = false;
            axis = AXIS_NONE;
            verticalDispatched = false;
        }
        return super.onTouchEvent(ev);
    }

    private void handleVerticalGesture(float deltaY) {
        if (verticalDispatched || listener == null) return;
        if (deltaY < -touchSlop * 2) { // Swiped up
            verticalDispatched = true;
            listener.onVerticalSwipeUp();
        } else if (deltaY > touchSlop * 2) { // Swiped down
            verticalDispatched = true;
            listener.onVerticalSwipeDown();
        }
    }

    private void dispatchCurrentPage() {
        final View snapView = snapHelper.findSnapView(layoutManager);
        if (snapView == null) {
            return;
        }
        final int position = layoutManager.getPosition(snapView);
        if (position != NO_POSITION && position != currentPage) {
            currentPage = position;
            if (listener != null) {
                listener.onPageSelected(position);
            }
        }
    }
}
