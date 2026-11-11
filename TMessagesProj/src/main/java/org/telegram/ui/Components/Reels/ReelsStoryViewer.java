package org.telegram.ui.Components.Reels;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.viewpager.widget.PagerAdapter;
import androidx.viewpager.widget.ViewPager;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.RadialProgressView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Reels is Telegram Stories renamed: a full-bleed Story pager over profile-owned Reels. */
public final class ReelsStoryViewer extends BaseFragment implements ReelsStoryPage.Delegate, ReelsCommentsSheet.GifPickerHost {

    private static final int PAGE_SIZE = 20;
    private static final int REQUEST_PICK_GIF = 0x5EED3;

    private final ReelsFeedSource initialSource;
    private final ReelsItem startItem;
    private final String singleId;
    private final int account;
    private final ReelsRepository repository;
    private final ArrayList<ReelsItem> items = new ArrayList<>();
    private final HashSet<String> startedViews = new HashSet<>();
    private final HashSet<String> completedViews = new HashSet<>();

    private FrameLayout root;
    private ViewPager pager;
    private ReelsPagerAdapter adapter;
    private ReelsPlayerPool playerPool;
    private LinearLayout stateBox;
    private TextView stateText;
    private TextView stateAction;
    private RadialProgressView stateProgress;
    private ReelsFeedSource source;
    private String username;
    private boolean resumed;
    private boolean initialStateShown;
    private boolean endStateShown;
    private boolean muted;
    private int currentPage;
    private Runnable homeAction;
    private Runnable exitAction;
    private ReelsCommentsSheet pendingGifSheet;

    public ReelsStoryViewer() {
        this(ReelsFeedSource.FOR_YOU, null, null);
    }

    public ReelsStoryViewer(ReelsFeedSource source) {
        this(source, null, null);
    }

    public ReelsStoryViewer(ReelsFeedSource source, ReelsItem startItem) {
        this(source, startItem, startItem == null ? null : startItem.id);
    }

    public ReelsStoryViewer(String singleId) {
        this(ReelsFeedSource.SINGLE, null, singleId);
    }

    private ReelsStoryViewer(ReelsFeedSource source, ReelsItem startItem, String singleId) {
        this.initialSource = source == null ? ReelsFeedSource.FOR_YOU : source;
        this.startItem = startItem;
        this.singleId = singleId;
        this.account = UserConfig.selectedAccount;
        this.username = startItem == null ? null : startItem.ownerUsername;
        this.repository = new ReelsRepository(account);
    }

    public void setHomeAction(Runnable action) {
        homeAction = action;
    }

    public void setExitAction(Runnable action) {
        exitAction = action;
    }

    @Override
    public View createView(Context context) {
        actionBar.setAddToContainer(false);
        actionBar.setVisibility(View.GONE);
        fragmentView = root = new FrameLayout(context);
        root.setBackgroundColor(ReelsTheme.STAGE_BACKGROUND);

        pager = new ViewPager(context);
        pager.setOffscreenPageLimit(1);
        pager.setBackgroundColor(ReelsTheme.STAGE_BACKGROUND);
        adapter = new ReelsPagerAdapter();
        pager.setAdapter(adapter);
        root.addView(pager, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        buildStateView(context);
        root.addView(stateBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        pager.addOnPageChangeListener(new ViewPager.SimpleOnPageChangeListener() {
            @Override
            public void onPageSelected(int position) {
                selectPage(position);
            }
        });

        playerPool = new ReelsPlayerPool(context);
        repository.setSource(initialSource, username);
        source = initialSource;

        if (startItem != null) {
            addItem(startItem);
            adapter.notifyDataSetChanged();
            hideState();
        }
        loadCreatorState();
        if (source == ReelsFeedSource.SINGLE) {
            if (startItem == null) {
                loadSingle(singleId);
            }
        } else {
            loadNextPage();
        }
        return fragmentView;
    }

    private void buildStateView(Context context) {
        stateBox = new LinearLayout(context);
        stateBox.setOrientation(LinearLayout.VERTICAL);
        stateBox.setGravity(Gravity.CENTER);
        stateBox.setPadding(dp(28), dp(26), dp(28), dp(26));
        stateBox.setBackground(ReelsTheme.rounded(0xCC000000, dp(18)));
        stateBox.setVisibility(View.GONE);

        stateProgress = new RadialProgressView(context);
        stateBox.addView(stateProgress, new LinearLayout.LayoutParams(dp(34), dp(34)));

        stateText = new TextView(context);
        stateText.setTextColor(Color.WHITE);
        stateText.setTextSize(15);
        stateText.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        textParams.topMargin = dp(10);
        stateBox.addView(stateText, textParams);

        stateAction = new TextView(context);
        stateAction.setTextColor(Color.WHITE);
        stateAction.setTextSize(13);
        stateAction.setGravity(Gravity.CENTER);
        stateAction.setPadding(dp(18), dp(9), dp(18), dp(9));
        stateAction.setBackground(ReelsTheme.rounded(0x33FFFFFF, dp(18)));
        final LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(14);
        actionParams.gravity = Gravity.CENTER_HORIZONTAL;
        stateBox.addView(stateAction, actionParams);
    }

    private void loadInitialOrNext() {
        if (source == ReelsFeedSource.SINGLE) return;
        loadNextPage();
    }

    private void loadCreatorState() {
        if (TextUtils.isEmpty(username)) return;
        repository.loadCreator(username, (creator, error) -> {
            if (creator == null) return;
            for (int i = 0; i < items.size(); i++) {
                final ReelsItem item = items.get(i);
                if (!username.equals(item.ownerUsername)) continue;
                item.following = creator.isFollowing;
                final ReelsStoryPage page = adapter == null ? null : adapter.pageAt(i);
                if (page != null) page.setFollowing(creator.isFollowing);
            }
        });
    }

    private void loadNextPage() {
        if (source == ReelsFeedSource.SINGLE || repository.isEndReached() || repository.isLoading()) {
            return;
        }
        if (items.isEmpty()) {
            showState(null, R.string.ReelsLoading, null, null);
        }
        repository.loadNextPage((pageItems, endReached, error) -> {
            if (error != null) {
                if (items.isEmpty()) {
                    if (error.isAuth()) {
                        showState(error.describe(), R.string.ReelsRetry, null, this::loadInitialOrNext);
                    } else {
                        showState(error.describe(), R.string.ReelsRetry, null, this::loadInitialOrNext);
                    }
                }
                return;
            }
            if (pageItems != null) {
                for (ReelsItem item : pageItems) {
                    addItem(item);
                }
                if (!pageItems.isEmpty()) {
                    adapter.notifyDataSetChanged();
                    hideState();
                    attachPlayers();
                }
            }
            if (items.isEmpty() && endReached) {
                showEmptyState();
            } else if (endReached && !items.isEmpty()) {
                // The end is presented only when the user actually reaches it.
            }
            loadCreatorState();
        });
    }

    private void loadSingle(String id) {
        showState(null, R.string.ReelsLoading, null, null);
        repository.loadSingle(id, (item, error) -> {
            if (error != null || item == null) {
                showState(error == null ? "This Reel is unavailable." : error.describe(),
                        R.string.ReelsRetry, null, () -> loadSingle(singleId));
                return;
            }
            addItem(item);
            adapter.notifyDataSetChanged();
            hideState();
            attachPlayers();
        });
    }

    private void showEmptyState() {
        if (initialStateShown) return;
        initialStateShown = true;
        if (source == ReelsFeedSource.SAVED) {
            showState("No saved Reels yet.", R.string.ReelsExplore, null, () -> switchSource(ReelsFeedSource.FOR_YOU));
        } else if (source == ReelsFeedSource.FOLLOWING) {
            showState("Follow creators to fill this feed.", R.string.ReelsForYou, null,
                    () -> switchSource(ReelsFeedSource.FOR_YOU));
        } else if (source == ReelsFeedSource.PROFILE) {
            showState("No Reels yet.", null, null, null);
        } else {
            showState("No Reels yet.", R.string.ReelsRetry, null, this::loadInitialOrNext);
        }
    }

    private void showEndState() {
        if (endStateShown) return;
        endStateShown = true;
        showState("You’re all caught up.", R.string.ReelsReplay, null, this::replay);
    }

    private void replay() {
        endStateShown = false;
        if (pager != null && adapter.getCount() > 0) {
            pager.setCurrentItem(0, false);
        }
        hideState();
        attachPlayers();
    }

    private void showState(String text, Integer actionText, Integer actionText2, Runnable action) {
        if (stateBox == null) return;
        stateBox.setVisibility(View.VISIBLE);
        stateProgress.setVisibility(text == null ? View.VISIBLE : View.GONE);
        stateText.setVisibility(text == null ? View.GONE : View.VISIBLE);
        if (text != null) stateText.setText(text);
        if (actionText == null) {
            stateAction.setVisibility(View.GONE);
        } else {
            stateAction.setVisibility(View.VISIBLE);
            stateAction.setText(LocaleController.getString(actionText));
            stateAction.setOnClickListener(v -> {
                if (action != null) action.run();
            });
        }
    }

    private void hideState() {
        if (stateBox != null) stateBox.setVisibility(View.GONE);
    }

    private void addItem(ReelsItem item) {
        if (item == null) return;
        for (ReelsItem existing : items) {
            if (existing.id.equals(item.id)) return;
        }
        items.add(item);
    }

    private void selectPage(int position) {
        if (position < 0 || position >= items.size()) return;
        currentPage = position;
        for (int i = 0; i < adapter.getCount(); i++) {
            final ReelsStoryPage page = adapter.pageAt(i);
            if (page != null) page.setActive(i == position);
        }
        attachPlayers();
        if (position >= items.size() - 2) {
            loadNextPage();
        }
    }

    private void attachPlayers() {
        if (pager == null || playerPool == null || items.isEmpty()) return;
        final int current = Math.max(0, Math.min(currentPage, items.size() - 1));
        currentPage = current;
        playerPool.setCurrent(current);
        for (int i = 0; i < adapter.getCount(); i++) {
            if (i < current - 1 || i > current + 1) {
                final ReelsStoryPage page = adapter.pageAt(i);
                if (page != null) page.detachPlayer();
            }
        }
        for (int i = Math.max(0, current - 1); i <= Math.min(items.size() - 1, current + 1); i++) {
            final ReelsStoryPage page = adapter.pageAt(i);
            if (page == null) continue;
            final ExoPlayer player = playerPool.acquire(i, items.get(i));
            page.attachPlayer(player, i == current && resumed);
            if (i != current && player != null) player.pause();
        }
        final ReelsStoryPage currentView = adapter.pageAt(current);
        if (currentView != null) currentView.setActive(true);
    }

    private void switchSource(ReelsFeedSource newSource) {
        if (newSource == null || newSource == source) return;
        source = newSource;
        initialSourceChanged();
        items.clear();
        startedViews.clear();
        completedViews.clear();
        currentPage = 0;
        initialStateShown = false;
        endStateShown = false;
        repository.setSource(newSource, username);
        if (adapter != null) adapter.notifyDataSetChanged();
        if (playerPool != null) playerPool.releaseAll();
        if (pager != null) pager.setCurrentItem(0, false);
        if (newSource == ReelsFeedSource.SINGLE) {
            loadSingle(singleId);
        } else {
            loadNextPage();
        }
    }

    private void initialSourceChanged() {
        // Kept as a deliberate hook so the source change is visible in one place.
    }

    @Override
    public void onResume() {
        super.onResume();
        resumed = true;
        attachPlayers();
    }

    @Override
    public void onPause() {
        resumed = false;
        if (adapter != null) {
            for (int i = 0; i < adapter.getCount(); i++) {
                final ReelsStoryPage page = adapter.pageAt(i);
                if (page != null) {
                    page.pause();
                    page.setActive(false);
                }
            }
        }
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        repository.cancel();
        if (playerPool != null) playerPool.releaseAll();
        if (adapter != null) {
            adapter.releasePages();
        }
        super.onFragmentDestroy();
    }

    public boolean onBackPressed() {
        closeViewer();
        return true;
    }

    private void closeViewer() {
        if (exitAction != null) {
            exitAction.run();
        } else if (homeAction != null) {
            homeAction.run();
        } else {
            finishFragment();
        }
    }

    @Override
    public void onNext(ReelsStoryPage page) {
        final int index = page == null ? currentPage : page.getIndex();
        if (index >= 0 && index < items.size() - 1) {
            pager.setCurrentItem(index + 1, true);
        } else if (!repository.isEndReached()) {
            loadNextPage();
        } else {
            showEndState();
        }
    }

    @Override
    public void onPrevious(ReelsStoryPage page) {
        final int index = page == null ? currentPage : page.getIndex();
        if (index > 0) {
            pager.setCurrentItem(index - 1, true);
        }
    }

    @Override
    public void onClose() {
        closeViewer();
    }

    @Override
    public void onLike(ReelsItem item) {
        if (item == null) return;
        repository.setLiked(item, !item.liked, (updated, error) -> {
            if (updated != null) {
                final ReelsStoryPage page = adapter.pageAt(currentPage);
                if (page != null && page.getItem() == updated) page.update(updated);
                else adapter.updateItem(updated);
            }
        });
    }

    @Override
    public void onComments(ReelsItem item) {
        if (item == null || getParentActivity() == null) return;
        final ReelsCommentsSheet sheet = new ReelsCommentsSheet(getParentActivity(), account, item,
                resourceProvider, changed -> {
                    final ReelsStoryPage page = adapter.pageAt(currentPage);
                    if (page != null) page.update(item);
                    repository.refreshItem(item, (updated, error) -> {
                        final ReelsStoryPage refreshed = adapter.pageAt(currentPage);
                        if (refreshed != null && updated != null) refreshed.update(updated);
                    });
                });
        sheet.setGifPickerHost(this);
        sheet.show();
    }

    @Override
    public void pickGif(ReelsCommentsSheet sheet) {
        if (getParentActivity() == null) return;
        pendingGifSheet = sheet;
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/gif");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            getParentActivity().startActivityForResult(intent, REQUEST_PICK_GIF);
        } catch (Exception ignored) {
            Toast.makeText(getParentActivity(), "No GIF picker is available.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_GIF && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null && getParentActivity() != null) {
            try (InputStream in = getParentActivity().getContentResolver().openInputStream(data.getData())) {
                if (in != null) {
                    final ByteArrayOutputStream out = new ByteArrayOutputStream();
                    final byte[] buffer = new byte[16 * 1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                    if (pendingGifSheet != null) pendingGifSheet.onGifPicked(out.toByteArray());
                }
            } catch (Exception ignored) {
                Toast.makeText(getParentActivity(), "The selected GIF could not be read.", Toast.LENGTH_SHORT).show();
            }
            pendingGifSheet = null;
        } else {
            super.onActivityResultFragment(requestCode, resultCode, data);
        }
    }

    @Override
    public void onShare(ReelsItem item) {
        if (item == null) return;
        final String link = item.shareUrl;
        if (TextUtils.isEmpty(link)) return;
        final Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, link);
        final Intent chooser = Intent.createChooser(intent, LocaleController.getString(R.string.ReelsShare));
        if (getParentActivity() != null) {
            getParentActivity().startActivity(chooser);
            repository.reportShare(item);
        } else {
            copyLink(link);
        }
    }

    private void copyLink(String link) {
        if (getParentActivity() == null) return;
        final ClipboardManager clipboard = (ClipboardManager) getParentActivity()
                .getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("Reel", link));
            Toast.makeText(getParentActivity(), "Link copied", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onSave(ReelsItem item) {
        if (item == null) return;
        repository.setSaved(item, !item.saved, (updated, error) -> {
            final ReelsStoryPage page = adapter.pageAt(currentPage);
            if (page != null) page.update(updated);
            else adapter.updateItem(updated);
        });
    }

    @Override
    public void onOpenProfile(ReelsItem item) {
        if (item == null || TextUtils.isEmpty(item.ownerUsername)) return;
        MessagesController.getInstance(account).openByUserName(item.ownerUsername, this, 1);
    }

    @Override
    public void onFollow(ReelsItem item) {
        if (item == null) return;
        final boolean follow = !isFollowing(item.ownerUsername);
        repository.setFollowing(item.ownerUsername, follow, (result, error) -> {
            if (error == null && result != null) {
                for (int i = 0; i < items.size(); i++) {
                    final ReelsItem candidate = items.get(i);
                    if (item.ownerUsername.equals(candidate.ownerUsername)) {
                        candidate.following = result.following;
                    }
                    final ReelsStoryPage page = adapter.pageAt(i);
                    if (page != null && item.ownerUsername.equals(page.getItem().ownerUsername)) {
                        page.setFollowing(result.following);
                    }
                }
            }
        });
    }

    private boolean isFollowing(String creatorUsername) {
        for (int i = 0; i < items.size(); i++) {
            if (creatorUsername.equals(items.get(i).ownerUsername)) {
                final ReelsStoryPage page = adapter.pageAt(i);
                if (page != null) {
                    return page.getItem().following;
                }
            }
        }
        return false;
    }

    @Override
    public void onMore(ReelsItem item) {
        if (getParentActivity() == null) return;
        final PopupMenu popup = new PopupMenu(getParentActivity(), pager);
        popup.getMenu().add(0, 1, 0, LocaleController.getString(R.string.ReelsSaved));
        if (source != ReelsFeedSource.FOR_YOU) {
            popup.getMenu().add(0, 2, 1, LocaleController.getString(R.string.ReelsForYou));
        }
        if (source != ReelsFeedSource.FOLLOWING) {
            popup.getMenu().add(0, 3, 2, LocaleController.getString(R.string.ReelsFollowing));
        }
        popup.setOnMenuItemClickListener(menuItem -> {
            if (menuItem.getItemId() == 1) {
                new ReelsSavedSheet(getParentActivity(), account, resourceProvider,
                        savedItem -> presentFragment(new ReelsStoryViewer(ReelsFeedSource.SAVED, savedItem))).show();
                return true;
            }
            if (menuItem.getItemId() == 2) {
                switchSource(ReelsFeedSource.FOR_YOU);
                return true;
            }
            if (menuItem.getItemId() == 3) {
                switchSource(ReelsFeedSource.FOLLOWING);
                return true;
            }
            return false;
        });
        popup.show();
    }

    @Override
    public void onEnded(ReelsStoryPage page) {
        if (page == null) return;
        if (page.getIndex() < items.size() - 1) {
            pager.setCurrentItem(page.getIndex() + 1, true);
        } else if (!repository.isEndReached()) {
            loadNextPage();
        } else {
            showEndState();
        }
    }

    @Override
    public void onView(ReelsItem item, long positionMs, boolean completed) {
        if (item == null) return;
        if (completed) {
            if (completedViews.add(item.id)) repository.reportView(item, positionMs, true);
        } else {
            if (startedViews.add(item.id)) repository.reportView(item, positionMs, false);
        }
    }

    private int dp(float value) {
        return AndroidUtilities.dp(value);
    }

    private final class ReelsPagerAdapter extends PagerAdapter {
        private final android.util.SparseArray<ReelsStoryPage> pages = new android.util.SparseArray<>();

        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public boolean isViewFromObject(View view, Object object) {
            return view == object;
        }

        @Override
        public Object instantiateItem(ViewGroup container, int position) {
            final ReelsStoryPage page = new ReelsStoryPage(container.getContext(), account, ReelsStoryViewer.this);
            final ReelsItem item = items.get(position);
            page.bind(item, position, items.size(), isOwn(item), muted);
            final int top = statusBarHeight(container.getContext());
            final int bottom = navigationBarHeight(container.getContext());
            page.setInsets(top, bottom);
            page.setActive(position == currentPage);
            container.addView(page, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            pages.put(position, page);
            return page;
        }

        @Override
        public void destroyItem(ViewGroup container, int position, Object object) {
            final ReelsStoryPage page = (ReelsStoryPage) object;
            page.detachPlayer();
            container.removeView(page);
            pages.remove(position);
        }

        ReelsStoryPage pageAt(int position) {
            return pages.get(position);
        }

        void updateItem(ReelsItem item) {
            for (int i = 0; i < pages.size(); i++) {
                final ReelsStoryPage page = pages.valueAt(i);
                if (page != null && page.getItem() != null && page.getItem().id.equals(item.id)) {
                    page.update(item);
                }
            }
        }

        void releasePages() {
            for (int i = 0; i < pages.size(); i++) {
                final ReelsStoryPage page = pages.valueAt(i);
                if (page != null) page.detachPlayer();
            }
            pages.clear();
        }

        @Override
        public int getItemPosition(Object object) {
            return POSITION_NONE;
        }
    }

    private long currentUserId() {
        final org.telegram.tgnet.TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();
        return user == null ? 0 : user.id;
    }

    private boolean isOwn(ReelsItem item) {
        return item != null && item.ownerUserId != 0 && item.ownerUserId == currentUserId();
    }

    private static int statusBarHeight(Context context) {
        final int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? context.getResources().getDimensionPixelSize(id) : 0;
    }

    private static int navigationBarHeight(Context context) {
        final int id = context.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? context.getResources().getDimensionPixelSize(id) : 0;
    }
}
