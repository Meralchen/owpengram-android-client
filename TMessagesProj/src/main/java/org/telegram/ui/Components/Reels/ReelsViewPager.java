package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSmoothScroller;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Fullscreen vertical snap pager: swipe up for the next Reel, swipe down for the previous one.
 * Horizontal drags stay inside the page (progress seek), vertical drags page. There is no
 * story-style swipe-to-comments/dismiss here; the comments sheet owns its own drag handle.
 */
public class ReelsViewPager extends RecyclerView {

    public interface Listener {
        void onPageSelected(int position);
    }

    private final LinearLayoutManager layoutManager;
    private final PagerSnapHelper snapHelper = new PagerSnapHelper();
    private Listener listener;
    private int currentPage = NO_POSITION;

    public ReelsViewPager(@NonNull Context context) {
        super(context);
        layoutManager = new LinearLayoutManager(context, VERTICAL, false) {
            @Override
            public boolean supportsPredictiveItemAnimations() {
                return false;
            }

            @Override
            protected int getExtraLayoutSpace(State state) {
                return getHeight();
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
                @Override
                protected int calculateTimeForScrolling(int dx) {
                    return 220;
                }

                @Override
                protected int calculateTimeForDeceleration(int dx) {
                    return 320;
                }
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
