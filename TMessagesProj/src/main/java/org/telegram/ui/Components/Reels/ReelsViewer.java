package org.telegram.ui.Components.Reels;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.ShareAlert;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.ProfileActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/**
 * Fullscreen horizontal snap Reels feed.
 * Paging is horizontal (Right-to-Left advances, Left-to-Right goes back).
 * Vertical gestures open comments (swipe up) or dismiss (swipe down).
 */
public class ReelsViewer extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, ReelsPageView.Delegate, ReelsViewPager.Listener, MainTabsActivity.TabFragmentDelegate {

    private final ReelsItem startItem;
    private ReelsRepository repository;
    private ReelsPlayerPool playerPool;
    private final ArrayList<ReelsItem> items = new ArrayList<>();
    private final HashSet<String> itemKeys = new HashSet<>();

    private ReelsViewPager pager;
    private Adapter adapter;
    private TextView emptyView;
    private RadialProgressView progressView;
    private ImageView backButton;
    private ImageView moreButton;
    private int currentPage = RecyclerView.NO_POSITION;
    private boolean paused = true;
    private Runnable hideEndMessage;
    private Runnable homeAction;
    private Runnable exitAction;

    public ReelsViewer() {
        this(null);
    }

    public ReelsViewer(ReelsItem startItem) {
        super();
        this.startItem = startItem;
        if (startItem != null) {
            currentAccount = startItem.account;
        }
    }

    public static boolean isReducedMotion() {
        return AndroidUtilities.getAnimatorDurationScale() == 0f || !LiteMode.isEnabled(LiteMode.FLAG_ANIMATED_EMOJI_CHAT);
    }

    private static String key(ReelsItem item) {
        return item.source == ReelsItem.Source.PROFILE
                ? "profile_" + item.reelId
                : item.getDialogId() + "_" + item.messageId;
    }

    @Override
    public boolean onFragmentCreate() {
        repository = new ReelsRepository(currentAccount);
        playerPool = new ReelsPlayerPool(ApplicationLoader.applicationContext, this::refreshPlayers);
        getNotificationCenter().addObserver(this, NotificationCenter.didUpdateReactions);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        getNotificationCenter().removeObserver(this, NotificationCenter.didUpdateReactions);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        if (repository != null) {
            repository.cancel();
        }
        if (playerPool != null) {
            playerPool.release();
        }
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        hasOwnBackground = true;

        final FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Color.BLACK);

        pager = new ReelsViewPager(context);
        pager.setListener(this);
        pager.setAdapter(adapter = new Adapter());
        root.addView(pager, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        progressView = new RadialProgressView(context);
        progressView.setSize(AndroidUtilities.dp(40));
        progressView.setProgressColor(Color.WHITE);
        root.addView(progressView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER));

        emptyView = new TextView(context);
        emptyView.setTextColor(Color.WHITE);
        emptyView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setVisibility(View.GONE);
        emptyView.setText(LocaleController.getString(R.string.ShortsEmpty));
        root.addView(emptyView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER, 32, 0, 32, 0));

        backButton = new ImageView(context);
        backButton.setImageResource(R.drawable.ic_ab_back);
        backButton.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        backButton.setContentDescription(LocaleController.getString(R.string.Back));
        backButton.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        backButton.setOnClickListener(v -> handleBackNavigation());
        root.addView(backButton, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.LEFT, 4, 32, 0, 0));

        moreButton = new ImageView(context);
        moreButton.setImageResource(R.drawable.ic_ab_other);
        moreButton.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
        moreButton.setContentDescription(LocaleController.getString(R.string.More));
        moreButton.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        moreButton.setOnClickListener(this::showMoreMenu);
        root.addView(moreButton, LayoutHelper.createFrame(48, 48, Gravity.TOP | Gravity.RIGHT, 0, 32, 4, 0));

        fragmentView = root;
        initFeed();
        return fragmentView;
    }

    private void handleBackNavigation() {
        if (exitAction != null) {
            exitAction.run();
            return;
        }
        if (homeAction != null) {
            homeAction.run();
            return;
        }
        finishFragment();
    }

    public void setHomeAction(Runnable homeAction) {
        this.homeAction = homeAction;
    }

    public void setExitAction(Runnable exitAction) {
        this.exitAction = exitAction;
    }

    private void initFeed() {
        items.clear();
        itemKeys.clear();
        currentPage = RecyclerView.NO_POSITION;
        repository.resetFeed();
        if (startItem != null) {
            items.add(startItem);
            itemKeys.add(key(startItem));
            repository.startAfter(startItem);
            adapter.notifyDataSetChanged();
            pager.setCurrentPage(0, false);
            currentPage = 0;
            updateControlsForPage(0);
        }
        loadPage();
    }

    private void loadPage() {
        if (repository.isLoading() || repository.isEndReached()) {
            return;
        }
        updateLoadingState();
        repository.loadNextPage((newItems, endReached, error) -> {
            updateLoadingState();
            if (error != null) {
                if (items.isEmpty()) {
                    emptyView.setVisibility(View.VISIBLE);
                    emptyView.setText(LocaleController.getString(R.string.ShortsLoadFailed));
                    emptyView.setOnClickListener(v -> {
                        emptyView.setVisibility(View.GONE);
                        loadPage();
                    });
                }
                return;
            }
            final int startSize = items.size();
            for (ReelsItem item : newItems) {
                final String k = key(item);
                if (itemKeys.add(k)) {
                    items.add(item);
                }
            }
            if (items.size() > startSize) {
                adapter.notifyItemRangeInserted(startSize, items.size() - startSize);
            }
            if (items.isEmpty()) {
                emptyView.setVisibility(View.VISIBLE);
                emptyView.setText(LocaleController.getString(R.string.ShortsEmpty));
                emptyView.setOnClickListener(null);
            } else {
                emptyView.setVisibility(View.GONE);
                if (currentPage == RecyclerView.NO_POSITION) {
                    currentPage = 0;
                    pager.setCurrentPage(0, false);
                    updateControlsForPage(0);
                }
            }
            refreshPlayers();
        });
    }

    private void updateLoadingState() {
        progressView.setVisibility(repository.isLoading() && items.isEmpty() ? View.VISIBLE : View.GONE);
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
        if (playerPool != null) {
            playerPool.pauseAll();
        }
    }

    @Override
    public void onPageSelected(int position) {
        if (position < 0 || position >= items.size()) {
            return;
        }
        currentPage = position;
        updateControlsForPage(position);
        refreshPlayers();
        if (position >= items.size() - 3) {
            loadPage();
        }
        if (position == items.size() - 1 && repository.isEndReached()) {
            showEndMessage();
        }
    }

    @Override
    public void onVerticalSwipeUp() {
        // Swipe UP opens comments
        if (currentPage >= 0 && currentPage < items.size()) {
            openComments(items.get(currentPage));
        }
    }

    @Override
    public void onVerticalSwipeDown() {
        // Swipe DOWN dismisses viewer / returns
        handleBackNavigation();
    }

    private void updateControlsForPage(int position) {
        backButton.setVisibility(View.VISIBLE);
        moreButton.setVisibility(View.VISIBLE);
    }

    private void refreshPlayers() {
        if (playerPool == null || pager == null) {
            return;
        }
        playerPool.setCurrent(currentPage);
        final int childCount = pager.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final View child = pager.getChildAt(i);
            final int pos = pager.getChildAdapterPosition(child);
            if (!(child instanceof ReelsPageView) || pos < 0 || pos >= items.size()) {
                continue;
            }
            final ReelsPageView pageView = (ReelsPageView) child;
            final ReelsItem item = items.get(pos);
            final ExoPlayer player = playerPool.acquire(pos, item);
            if (player == null) {
                pageView.detachPlayer();
                continue;
            }
            pageView.attachPlayer(player);
            playerPool.attach(pos, pageView.getVideoView());
            if (pos == currentPage && !paused) {
                player.play();
            } else {
                player.pause();
            }
        }
    }

    private void showEndMessage() {
        if (hideEndMessage != null || getParentActivity() == null) {
            return;
        }
        BulletinFactory.of(this).createSimpleBulletin(
                R.raw.info,
                LocaleController.getString(R.string.ShortsNoMoreVideos)
        ).show();
        hideEndMessage = () -> hideEndMessage = null;
        AndroidUtilities.runOnUIThread(hideEndMessage, 3000);
    }

    private void showMoreMenu(View anchor) {
        if (currentPage < 0 || currentPage >= items.size()) {
            return;
        }
        final ReelsItem item = items.get(currentPage);
        final PopupMenu menu = new PopupMenu(anchor.getContext(), anchor);
        menu.getMenu().add(0, 1, 0, LocaleController.getString(R.string.CopyLink));
        menu.getMenu().add(0, 2, 0, LocaleController.getString(R.string.ShareFile));
        menu.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == 1) {
                copyLink(item);
                return true;
            } else if (menuItem.getItemId() == 2) {
                onShare(null);
                return true;
            }
            return false;
        });
        menu.show();
    }

    private void copyLink(ReelsItem item) {
        if (item.source == ReelsItem.Source.PROFILE) {
            String url = item.publicShareUrl != null ? item.publicShareUrl : ReelsBackendApi.absoluteUrl(currentAccount, "/api/reels/" + item.reelId);
            if (url != null) {
                AndroidUtilities.addToClipboard(url);
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.LinkCopied)).show();
            }
            return;
        }
        repository.exportLink(item, link -> {
            if (link != null) {
                AndroidUtilities.addToClipboard(link);
                BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.LinkCopied)).show();
            }
        });
    }

    @Override
    public void onToggleLike(ReelsPageView page, boolean fromDoubleTap) {
        final ReelsItem item = page != null ? page.getItem() : (currentPage >= 0 && currentPage < items.size() ? items.get(currentPage) : null);
        if (item == null) return;
        final boolean newLiked = fromDoubleTap || !item.liked;
        repository.setLiked(item, newLiked, (updatedItem, error) -> {
            if (page != null) page.updateCounters();
        });
        if (page != null) page.updateCounters();
    }

    @Override
    public void onOpenComments(ReelsPageView page) {
        final ReelsItem item = page != null ? page.getItem() : (currentPage >= 0 && currentPage < items.size() ? items.get(currentPage) : null);
        if (item != null) openComments(item);
    }

    private void openComments(ReelsItem item) {
        if (item.source == ReelsItem.Source.PROFILE) {
            new ReelsProfileCommentsSheet(getParentActivity(), item).show();
        } else {
            new ReelsCommentsSheet(getParentActivity(), repository, item).show();
        }
    }

    @Override
    public void onShare(ReelsPageView page) {
        if (currentPage < 0 || currentPage >= items.size()) return;
        final ReelsItem item = items.get(currentPage);
        if (item.source == ReelsItem.Source.CHANNEL && item.messageObject != null) {
            showDialog(new ShareAlert(getParentActivity(), new ArrayList<>(Collections.singletonList(item.messageObject)), null, false, null, false));
        } else {
            copyLink(item);
        }
    }

    @Override
    public void onOpenChannel(ReelsPageView page) {
        final ReelsItem item = page != null ? page.getItem() : (currentPage >= 0 && currentPage < items.size() ? items.get(currentPage) : null);
        if (item == null) return;
        if (item.source == ReelsItem.Source.CHANNEL) {
            Bundle args = new Bundle();
            args.putLong("chat_id", item.channelId);
            presentFragment(new ChatActivity(args));
        } else if (!TextUtils.isEmpty(item.creatorUsername)) {
            MessagesController.getInstance(currentAccount).openByUserName(item.creatorUsername, this, 1);
        }
    }

    @Override
    public void onTogglePlay(ReelsPageView page) {
        if (playerPool == null || currentPage < 0) return;
        ExoPlayer player = playerPool.get(currentPage);
        if (player == null) return;
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
        if (page != null) page.updatePlayPauseIcon();
    }

    @Override
    public void onSeek(ReelsPageView page, long positionMs) {
        if (playerPool == null || currentPage < 0) return;
        ExoPlayer player = playerPool.get(currentPage);
        if (player != null) {
            player.seekTo(positionMs);
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.appDidLogout) {
            ReelsCache.clearAccount(account);
            if (account == currentAccount) {
                finishFragment();
            }
        }
    }

    @Override
    public boolean onBackPressed() {
        handleBackNavigation();
        return false;
    }

    @Override
    public void onSelected() {
        paused = false;
        refreshPlayers();
    }

    @Override
    public void onUnSelected() {
        paused = true;
        if (playerPool != null) playerPool.pauseAll();
    }

    private final class Adapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ReelsPageView cell = new ReelsPageView(parent.getContext(), ReelsViewer.this);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            return new RecyclerView.ViewHolder(cell) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            ReelsPageView cell = (ReelsPageView) holder.itemView;
            cell.bind(items.get(position), position);
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        @Override
        public void onViewAttachedToWindow(@NonNull RecyclerView.ViewHolder holder) {
            refreshPlayers();
        }

        @Override
        public void onViewDetachedFromWindow(@NonNull RecyclerView.ViewHolder holder) {
            if (holder.itemView instanceof ReelsPageView) {
                ((ReelsPageView) holder.itemView).detachPlayer();
            }
            if (playerPool != null) {
                playerPool.detach(((ReelsPageView) holder.itemView).getVideoView());
            }
        }
    }
}
