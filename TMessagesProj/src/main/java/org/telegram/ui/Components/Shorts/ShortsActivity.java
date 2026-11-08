package org.telegram.ui.Components.Shorts;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Clips screen: chronological feed of channel video messages in a horizontal pager.
 * Implemented as a BaseFragment like the rest of the fork; it keeps its own feed state and does
 * not share anything with the Stories viewer.
 */
public class ShortsActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, ShortsCell.Delegate, ShortsViewPager.Listener {

    private final ShortsItem startItem;
    private ShortsRepository repository;
    private ShortsPlayerPool playerPool;
    private final ArrayList<ShortsItem> items = new ArrayList<>();
    private final HashSet<String> itemKeys = new HashSet<>();

    private ShortsViewPager pager;
    private Adapter adapter;
    private TextView emptyView;
    private RadialProgressView progressView;
    private int currentPage = RecyclerView.NO_POSITION;
    private boolean paused = true;

    /** Opens the general chronological feed. */
    public ShortsActivity() {
        this(null);
    }

    /** Opens the feed with an already verified clip (deep link) as the current page. */
    public ShortsActivity(ShortsItem startItem) {
        super();
        this.startItem = startItem;
        if (startItem != null) {
            currentAccount = startItem.account;
        }
    }

    public static boolean isReducedMotion() {
        return AndroidUtilities.getAnimatorDurationScale() == 0f || !LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_EMOJI_CHAT);
    }

    private static String key(long dialogId, int messageId) {
        return dialogId + "_" + messageId;
    }

    @Override
    public boolean onFragmentCreate() {
        repository = new ShortsRepository(currentAccount);
        playerPool = new ShortsPlayerPool(ApplicationLoader.applicationContext);
        getNotificationCenter().addObserver(this, NotificationCenter.didUpdateReactions);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        if (startItem != null) {
            addItems(Collections.singletonList(startItem));
            repository.startAfter(startItem);
        }
        loadMore();
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        getNotificationCenter().removeObserver(this, NotificationCenter.didUpdateReactions);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        repository.cancel();
        releasePlayers();
        super.onFragmentDestroy();
    }

    private void releasePlayers() {
        if (pager != null) {
            for (int i = 0; i < pager.getChildCount(); i++) {
                final View child = pager.getChildAt(i);
                if (child instanceof ShortsCell) {
                    ((ShortsCell) child).setPlayer(null);
                }
            }
        }
        playerPool.release();
    }

    @Override
    public ActionBar createActionBar(Context context) {
        final ActionBar actionBar = super.createActionBar(context);
        actionBar.setAddToContainer(false);
        return actionBar;
    }

    @Override
    public View createView(Context context) {
        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);
        fragmentView = root;

        pager = new ShortsViewPager(context);
        pager.setListener(this);
        pager.setAdapter(adapter = new Adapter());
        root.addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        progressView = new RadialProgressView(context);
        progressView.setProgressColor(Color.WHITE);
        root.addView(progressView, LayoutHelper.createFrame(48, 48, Gravity.CENTER));

        emptyView = new TextView(context);
        emptyView.setTextColor(0xccffffff);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        final ImageView backButton = new ImageView(context);
        backButton.setImageResource(R.drawable.ic_ab_back);
        backButton.setScaleType(ImageView.ScaleType.CENTER);
        backButton.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        backButton.setBackground(Theme.createSelectorDrawable(0x33ffffff, 1));
        backButton.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        backButton.setOnClickListener(v -> finishFragment());
        root.addView(backButton, LayoutHelper.createFrame(44, 44, Gravity.TOP | Gravity.LEFT, 6, AndroidUtilities.statusBarHeight / AndroidUtilities.density + 36, 0, 0));

        updateState();
        if (!items.isEmpty()) {
            pager.setCurrentPage(Math.max(0, currentPage), false);
        }
        return root;
    }

    private void addItems(List<ShortsItem> page) {
        final int start = items.size();
        for (ShortsItem item : page) {
            if (itemKeys.add(key(item.getDialogId(), item.messageId))) {
                items.add(item);
            }
        }
        if (adapter != null && items.size() > start) {
            adapter.notifyItemRangeInserted(start, items.size() - start);
        }
    }

    private void loadMore() {
        if (repository.isLoading() || repository.isEndReached()) {
            return;
        }
        repository.loadNextPage((page, endReached, error) -> {
            final boolean wasEmpty = items.isEmpty();
            addItems(page);
            if (error != null && items.isEmpty() && emptyView != null) {
                emptyView.setText(LocaleController.getString(R.string.ShortsLoadFailed));
                emptyView.setOnClickListener(v -> {
                    emptyView.setText("");
                    updateState();
                    loadMore();
                });
            } else if (error == null && page.isEmpty() && endReached && items.isEmpty() && emptyView != null) {
                emptyView.setText(LocaleController.getString(R.string.ShortsEmpty));
                emptyView.setOnClickListener(null);
            }
            updateState();
            if (wasEmpty && !items.isEmpty() && pager != null) {
                pager.setCurrentPage(0, false);
            } else if (!endReached && !page.isEmpty() && currentPage >= items.size() - 3) {
                loadMore();
            } else {
                refreshPlayers();
            }
        });
    }

    private void updateState() {
        if (progressView == null) {
            return;
        }
        final boolean empty = items.isEmpty();
        progressView.setVisibility(empty && repository.isLoading() ? View.VISIBLE : View.GONE);
        if (empty && !repository.isLoading()) {
            if (emptyView.getText().length() == 0) {
                emptyView.setText(LocaleController.getString(R.string.ShortsEmpty));
            }
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setClickable(emptyView.getText().toString().equals(LocaleController.getString(R.string.ShortsLoadFailed)));
        } else {
            emptyView.setVisibility(View.GONE);
        }
    }

    private ShortsCell findCell(int position) {
        if (pager == null) {
            return null;
        }
        final View view = pager.findPageView(position);
        return view instanceof ShortsCell ? (ShortsCell) view : null;
    }

    private ShortsCell findCell(ShortsItem item) {
        if (pager == null) {
            return null;
        }
        for (int i = 0; i < pager.getChildCount(); i++) {
            final View child = pager.getChildAt(i);
            if (child instanceof ShortsCell && ((ShortsCell) child).getItem() == item) {
                return (ShortsCell) child;
            }
        }
        return null;
    }

    @Override
    public void onPageSelected(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        final ShortsCell previous = findCell(currentPage);
        if (previous != null && previous.getPlayer() != null && previous.getItem() != null) {
            previous.getItem().resumePositionMs = previous.getPlayer().getCurrentPosition();
        }
        currentPage = position;
        playerPool.setCurrent(position);
        refreshPlayers();
        if (position >= items.size() - 3) {
            loadMore();
        }
    }

    /** Binds pooled players to visible cells; preloads current + next two; plays only the current one. */
    private void refreshPlayers() {
        if (pager == null || currentPage == RecyclerView.NO_POSITION || items.isEmpty()) {
            return;
        }
        for (int p = currentPage; p <= Math.min(items.size() - 1, currentPage + ShortsPlayerPool.MAX_PLAYERS - 1); p++) {
            playerPool.acquire(p, items.get(p));
        }
        for (int i = 0; i < pager.getChildCount(); i++) {
            final View child = pager.getChildAt(i);
            if (child instanceof ShortsCell) {
                bindPlayer((ShortsCell) child);
            }
        }
        final ExoPlayer current = playerPool.get(currentPage);
        final ShortsCell cell = findCell(currentPage);
        if (current != null && cell != null && !paused && !cell.isUserPaused()) {
            current.play();
        }
    }

    private void bindPlayer(ShortsCell cell) {
        final int position = cell.getPosition();
        final ExoPlayer player = position >= 0 ? playerPool.get(position) : null;
        if (player == null) {
            playerPool.detach(cell.getVideoView());
            cell.setPlayer(null);
            return;
        }
        playerPool.attach(position, cell.getVideoView());
        cell.setPlayer(player);
    }

    @Override
    public void onResume() {
        super.onResume();
        paused = false;
        refreshPlayers();
    }

    @Override
    public void onPause() {
        super.onPause();
        paused = true;
        final ShortsCell cell = findCell(currentPage);
        if (cell != null && cell.getPlayer() != null && cell.getItem() != null) {
            cell.getItem().resumePositionMs = cell.getPlayer().getCurrentPosition();
        }
        playerPool.pauseAll();
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return false;
    }

    @Override
    public boolean isLightStatusBar() {
        return false;
    }

    @Override
    public int getNavigationBarColor() {
        return Color.BLACK;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount) {
            return;
        }
        if (id == NotificationCenter.didUpdateReactions) {
            final long dialogId = (long) args[0];
            final int messageId = (int) args[1];
            if (!(args[2] instanceof TLRPC.TL_messageReactions)) {
                return;
            }
            final TLRPC.TL_messageReactions reactions = (TLRPC.TL_messageReactions) args[2];
            for (ShortsItem item : items) {
                if (item.getDialogId() == dialogId && item.messageId == messageId) {
                    // Optimistic state wins while our own request is still in flight.
                    if (!repository.hasPendingReaction(item)) {
                        item.applyReactions(reactions);
                        final ShortsCell cell = findCell(item);
                        if (cell != null) {
                            cell.updateCounters();
                        }
                    }
                    break;
                }
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            final long channelId = (long) args[1];
            if (channelId == 0 || args.length > 2 && Boolean.TRUE.equals(args[2])) {
                return;
            }
            @SuppressWarnings("unchecked") final ArrayList<Integer> ids = (ArrayList<Integer>) args[0];
            boolean removed = false;
            for (int i = items.size() - 1; i >= 0; i--) {
                final ShortsItem item = items.get(i);
                if (item.channelId == channelId && ids.contains(item.messageId) && i != currentPage) {
                    items.remove(i);
                    itemKeys.remove(key(item.getDialogId(), item.messageId));
                    if (i < currentPage) {
                        currentPage--;
                    }
                    if (adapter != null) {
                        adapter.notifyItemRemoved(i);
                    }
                    removed = true;
                }
            }
            if (removed) {
                // Pool entries are keyed by position, which just shifted.
                releasePlayers();
                if (pager != null) {
                    pager.onPageShifted(currentPage);
                }
                playerPool.setCurrent(currentPage);
                if (adapter != null) {
                    adapter.notifyItemRangeChanged(0, items.size());
                }
                refreshPlayers();
            }
        } else if (id == NotificationCenter.appDidLogout) {
            releasePlayers();
            removeSelfFromStack();
        }
    }

    @Override
    public void onToggleLike(ShortsCell cell, boolean fromDoubleTap) {
        final ShortsItem item = cell.getItem();
        if (item == null) {
            return;
        }
        final boolean liked = fromDoubleTap || !item.liked;
        repository.setLiked(item, liked, (result, error) -> {
            final ShortsCell current = findCell(result);
            if (current != null) {
                current.updateCounters();
            }
            if (error != null && getParentActivity() != null) {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ShortsLikeFailed)).show();
            }
        });
        cell.updateCounters();
    }

    @Override
    public void onOpenComments(ShortsCell cell) {
        final ShortsItem item = cell.getItem();
        if (item == null || getParentActivity() == null) {
            return;
        }
        final ExoPlayer player = cell.getPlayer();
        if (player != null) {
            player.pause();
        }
        final ShortsCommentsSheet sheet = new ShortsCommentsSheet(getParentActivity(), repository, item);
        showDialog(sheet, dialog -> {
            final ShortsCell current = findCell(item);
            if (current != null) {
                current.updateCounters();
                if (!paused && !current.isUserPaused() && current.getPlayer() != null && current.getPosition() == currentPage) {
                    current.getPlayer().play();
                }
            }
        });
    }

    @Override
    public void onShare(ShortsCell cell) {
        final ShortsItem item = cell.getItem();
        if (item == null) {
            return;
        }
        repository.exportLink(item, link -> {
            if (getParentActivity() == null) {
                return;
            }
            if (link == null) {
                BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.ErrorOccurred)).show();
                return;
            }
            showDialog(ShareAlert.createShareAlert(getParentActivity(), null, link, false, link, false));
        });
    }

    @Override
    public void onOpenChannel(ShortsCell cell) {
        final ShortsItem item = cell.getItem();
        if (item == null) {
            return;
        }
        final Bundle args = new Bundle();
        args.putLong("chat_id", item.channelId);
        presentFragment(new ProfileActivity(args));
    }

    @Override
    public void onTogglePlay(ShortsCell cell) {
        final ExoPlayer player = cell.getPlayer();
        final boolean pause = !cell.isUserPaused();
        cell.setUserPaused(pause);
        if (player != null) {
            if (pause) {
                player.pause();
            } else {
                player.play();
            }
        }
    }

    @Override
    public void onSeek(ShortsCell cell, long positionMs) {
        final ExoPlayer player = cell.getPlayer();
        if (player != null) {
            player.seekTo(positionMs);
        }
        if (cell.getItem() != null) {
            cell.getItem().resumePositionMs = positionMs;
        }
    }

    @Override
    public void onMovePage(ShortsCell cell, int delta) {
        final int target = cell.getPosition() + delta;
        if (target >= 0 && target < items.size()) {
            pager.setCurrentPage(target, true);
        }
    }

    @Override
    public void onVerticalSwipe(boolean up) {
        final ShortsCell cell = findCell(currentPage);
        if (cell == null) {
            return;
        }
        if (up) {
            onOpenChannel(cell);
        } else {
            onOpenComments(cell);
        }
    }

    private class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            final ShortsCell cell = new ShortsCell(parent.getContext(), ShortsActivity.this);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new RecyclerView.ViewHolder(cell) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            final ShortsCell cell = (ShortsCell) holder.itemView;
            cell.bind(items.get(position), position);
            bindPlayer(cell);
        }

        @Override
        public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
            final ShortsCell cell = (ShortsCell) holder.itemView;
            playerPool.detach(cell.getVideoView());
            cell.setPlayer(null);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }
}
