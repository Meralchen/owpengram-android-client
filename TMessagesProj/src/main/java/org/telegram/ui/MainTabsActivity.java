package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;
import static org.telegram.ui.Components.Premium.LimitReachedBottomSheet.TYPE_ACCOUNTS;

import android.animation.Animator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ShapeDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextPaint;
import android.text.style.ReplacementSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.math.MathUtils;
import androidx.core.view.WindowInsetsCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.ContactsController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.EdgeToEdgeSupportMode;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FolderDrawable;
import org.telegram.ui.Components.HintsController;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.LimitReachedBottomSheet;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.BlurredBackgroundWithFadeDrawable;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.glass.GlassTabView;
import org.telegram.ui.Stories.recorder.HintView2;
import org.telegram.ui.Wallet.WalletActivity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class MainTabsActivity extends ViewPagerActivity implements NotificationCenter.NotificationCenterDelegate, FactorAnimator.Target {

    public static final int TABS_COUNT = 4;
    private static final int TAB_REELS = 0;
    private static final int TAB_CHATS = 1;
    private static final int TAB_CONTACTS = 2;
    private static final int TAB_PROFILE = 3;
    private int[] configuredTabs = readConfiguredTabs();
    private final HashMap<Integer, FragmentState> tabFragmentStates = new HashMap<>();

    private static int[] readConfiguredTabs() {
        final String stored = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getString("owpg_main_tabs_order", "0,1,2,3");
        final int hidden = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getInt("owpg_main_tabs_hidden", 0) & ~(1 << TAB_PROFILE);
        final ArrayList<Integer> order = new ArrayList<>();
        if (stored != null) {
            for (String part : stored.split(",")) {
                try {
                    int id = Integer.parseInt(part);
                    if (id >= TAB_REELS && id <= TAB_PROFILE && !order.contains(id)) order.add(id);
                } catch (Exception ignore) {}
            }
        }
        for (int id = TAB_REELS; id <= TAB_PROFILE; id++) if (!order.contains(id)) order.add(id);
        final ArrayList<Integer> visible = new ArrayList<>();
        for (int id : order) if ((hidden & (1 << id)) == 0) visible.add(id);
        if (visible.size() < 2) {
            visible.clear();
            visible.add(TAB_REELS);
            visible.add(TAB_CHATS);
            visible.add(TAB_CONTACTS);
            visible.add(TAB_PROFILE);
        }
        int[] result = new int[visible.size()];
        for (int i = 0; i < visible.size(); i++) result[i] = visible.get(i);
        return result;
    }

    private int positionForTab(int tabId) {
        for (int i = 0; i < configuredTabs.length; i++) if (configuredTabs[i] == tabId) return i;
        return -1;
    }

    private int tabAtPosition(int position) {
        return position >= 0 && position < configuredTabs.length ? configuredTabs[position] : TAB_CHATS;
    }

    private GlassTabView tabView(int tabId) {
        int position = positionForTab(tabId);
        return tabs != null && position >= 0 && position < tabs.length ? tabs[position] : null;
    }

    private static String tabName(int id) {
        switch (id) {
            case TAB_REELS: return LocaleController.getString(R.string.ShortsTitle);
            case TAB_CHATS: return LocaleController.getString(R.string.MainTabsChats);
            case TAB_CONTACTS: return LocaleController.getString(R.string.MainTabsContacts);
            default: return LocaleController.getString(R.string.MainTabsProfile);
        }
    }

    private static final int ANIMATOR_ID_TABS_VISIBLE = 0;
    private final BoolAnimator animatorTabsVisible = new BoolAnimator(ANIMATOR_ID_TABS_VISIBLE,
        this, CubicBezierInterpolator.EASE_OUT_QUINT, 380, true);


    private IUpdateLayout updateLayout;

    private UpdateLayoutWrapper updateLayoutWrapper;
    private FrameLayout tabsViewWrapper;
    private MainTabsLayout tabsView;
    private BlurredBackgroundDrawable tabsViewBackground;
    private View fadeView;

    public MainTabsActivity() {
        super();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            iBlur3SourceTabGlass = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceTabGlass.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(getThemedColor(Theme.key_windowBackgroundWhite));
                    hash.add(SharedConfig.chatBlurEnabled());

                    for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                        final FragmentState state = fragmentsArr.valueAt(a);
                        final BaseFragment fragment = state.fragment;
                        if (fragment.fragmentView == null) {
                            continue;
                        }
                        if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                            continue;
                        }
                        if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                            continue;
                        }

                        if (fragment instanceof TabFragmentDelegate) {
                            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                            BlurredBackgroundSourceRenderNode source = delegate.getGlassSource();
                            if (source != null) {
                                hash.addF(fragmentPosition.left);
                                hash.addF(fragmentPosition.top);
                                hash.add(fragment.getClassGuid());
                            }
                        }
                    }
                }

                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    final int width = fragmentView.getMeasuredWidth();
                    final int height = fragmentView.getMeasuredHeight();

                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));

                    for (int a = 0, N = fragmentsArr.size(); a < N; a++) {
                        final FragmentState state = fragmentsArr.valueAt(a);
                        final BaseFragment fragment = state.fragment;
                        if (fragment.fragmentView == null) {
                            continue;
                        }
                        if (!ViewPositionWatcher.computeRectInParent(fragment.fragmentView, contentView, fragmentPosition)) {
                            continue;
                        }
                        if (fragmentPosition.right <= 0 || fragmentPosition.left >= fragmentView.getMeasuredWidth()) {
                            continue;
                        }

                        if (fragment instanceof TabFragmentDelegate) {
                            TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
                            BlurredBackgroundSourceRenderNode source = delegate.getGlassSource();
                            if (source != null) {
                                canvas.save();
                                canvas.translate(fragmentPosition.left, fragmentPosition.top);
                                source.draw(canvas, 0, 0, width, height);
                                canvas.restore();
                            }
                        }
                    }
                }
            });
        } else {
            iBlur3SourceTabGlass = null;
        }

        iBlur3SourceColor = new BlurredBackgroundSourceColor();

        Bulletin.Delegate delegate = new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN);
            }
        };

        Bulletin.addDelegate(this, delegate);
        Bulletin.addDelegate(contentView, delegate);
    }

    @Override
    protected FrameLayout createContentView(Context context) {
        return new FrameLayout(context) {
            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                super.onLayout(changed, left, top, right, bottom);
                checkUi_tabsPosition();
                checkUi_fadeView();
            }

            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                final int color = getEstBackgroundColor();
                if (insetLeft != 0) {
                    canvas.drawRect(0, 0, insetLeft, getHeight(), Theme.fillingPaint(color));
                }
                if (insetRight != 0) {
                    canvas.drawRect(getWidth() - insetRight, 0, getWidth(), getHeight(), Theme.fillingPaint(color));
                }

                super.dispatchDraw(canvas);
                blur3_invalidateBlur();
                blur3_updateFadeColors();
            }
        };
    }

    private int getEstBackgroundColor() {
        return ColorUtils.blendARGB(
                getThemedColor(Theme.key_windowBackgroundGray),
                getThemedColor(Theme.key_windowBackgroundWhite),
                viewPager != null ? viewPager.getPositionVisibility(0) : 1);
    }

    private boolean tabletLayout;
    public void updateLayout() {
//        if (tabletLayout == AndroidUtilities.isTablet()) return;
//        tabletLayout = AndroidUtilities.isTablet();
//
//        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
//        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
//        int bottomMargin = isUpdateLayoutVisible ? (navigationBarHeight + updateLayoutHeight) : 0;
//        if (tabletLayout) {
//            bottomMargin = Math.max(bottomMargin, navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
//        }
//        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL);
//        if (tabletLayout) {
//            lp.leftMargin = dp(6);
//            lp.rightMargin = dp(6);
//            lp.topMargin = dp(6);
//        }
//        lp.bottomMargin = bottomMargin;
//
//        viewPager.setLayoutParams(lp);
//        viewPager.setTabletLayout(tabletLayout);
//        checkUi_fadeView();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        updateLayout();
    }

    @Override
    public void onResume() {
        super.onResume();
        blur3_updateColors();
        checkContactsTabBadge();
        checkUnreadCount(true);

        showAccountChangeHint();
    }

    private void checkContactsTabBadge() {
        if (tabsView != null && tabView(TAB_CONTACTS) != null) {
            final boolean hasPermission = Build.VERSION.SDK_INT >= 23 && ContactsController.hasContactsPermission();
            if (hasPermission) {
                MessagesController.getGlobalNotificationsSettings().edit().putBoolean("askAboutContacts2", true).apply();
            }
            if (Build.VERSION.SDK_INT >= 23 && UserConfig.getInstance(currentAccount).syncContacts && !hasPermission && MessagesController.getGlobalNotificationsSettings().getBoolean("askAboutContacts2", true)) {
                tabView(TAB_CONTACTS).setCounter("!", true, true);
            } else {
                tabView(TAB_CONTACTS).setCounter(null, true, true);
            }
        }
    }

    private GlassTabView createTab(Context context, int tabId) {
        final GlassTabView tab;
        if (tabId == TAB_REELS) {
            tab = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.REELS, R.string.ShortsTitle);
        } else if (tabId == TAB_CHATS) {
            tab = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CHATS, R.string.MainTabsChats);
            tab.setOnLongClickListener(this::openFoldersSelector);
        } else if (tabId == TAB_CONTACTS) {
            tab = GlassTabView.createMainTab(context, resourceProvider, GlassTabView.TabAnimation.CONTACTS, R.string.MainTabsContacts);
            tab.setOnLongClickListener(this::openContactsSelector);
        } else {
            tab = GlassTabView.createAvatar(context, resourceProvider, currentAccount, R.string.MainTabsProfile);
            tab.setOnLongClickListener(this::openAccountSelector);
        }
        return tab;
    }

    private void rebuildTabButtons(Context context) {
        tabsView.removeAllViews();
        tabs = new GlassTabView[configuredTabs.length];
        for (int index = 0; index < configuredTabs.length; index++) {
            final int position = index;
            final GlassTabView view = tabs[index] = createTab(context, configuredTabs[index]);
            tabsView.addTabToIgnoreClick(view);
            view.setOnClickListener(v -> {
                if (viewPager.isManualScrolling() || viewPager.isTouch()) return;
                if (viewPager.getCurrentPosition() == position) {
                    final BaseFragment fragment = getCurrentVisibleFragment();
                    if (fragment instanceof MainTabsActivity.TabFragmentDelegate) ((MainTabsActivity.TabFragmentDelegate) fragment).onParentScrollToTop();
                    return;
                }
                selectTab(position, true);
                viewPager.scrollToPosition(position);
            });
            tabsView.addView(view);
            tabsView.setViewVisible(view, true, false);
        }
    }

    private void applyTabConfiguration() {
        if (viewPager == null || tabsView == null) return;
        final int currentTab = tabAtPosition(viewPager.getCurrentPosition());
        final HashMap<Integer, FragmentState> states = new HashMap<>();
        for (int i = 0; i < configuredTabs.length; i++) {
            final FragmentState state = fragmentsArr.get(i);
            if (state != null) {
                states.put(configuredTabs[i], state);
                tabFragmentStates.put(configuredTabs[i], state);
            }
        }
        states.putAll(tabFragmentStates);
        configuredTabs = readConfiguredTabs();
        fragmentsArr.clear();
        for (int i = 0; i < configuredTabs.length; i++) {
            final FragmentState state = states.get(configuredTabs[i]);
            if (state != null) fragmentsArr.put(i, state);
        }
        rebuildTabButtons(getContext());
        final int target = positionForTab(currentTab) >= 0 ? positionForTab(currentTab) : Math.max(0, positionForTab(TAB_CHATS));
        viewPager.getAdapter().notifyDataSetChanged();
        viewPager.scrollToPosition(target);
        selectTab(target, false);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (accountSwitchHint != null) {
            accountSwitchHint.hide();
        }
    }

    @Override
    public View createView(Context context) {
        super.createView(context);
        tabletLayout = false;

        tabsView = new MainTabsLayout(context, resourceProvider);
        tabsView.setClipChildren(false);
        tabsView.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4), dp(DialogsActivity.MAIN_TABS_MARGIN + 4));
        tabsView.setMaxWidth(dp(328 + DialogsActivity.MAIN_TABS_MARGIN * 2));

        rebuildTabButtons(context);
        selectTab(viewPager.getCurrentPosition(), false);

        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));

        final ViewPositionWatcher viewPositionWatcher = new ViewPositionWatcher(contentView);

        BlurredBackgroundDrawableViewFactory iBlur3FactoryGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceTabGlass != null ? iBlur3SourceTabGlass : iBlur3SourceColor);
        iBlur3FactoryGlass.setSourceRootView(viewPositionWatcher, contentView);
        iBlur3FactoryGlass.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));

        tabsViewBackground = iBlur3FactoryGlass.create(tabsView, BlurredBackgroundProviderImpl.mainTabs(resourceProvider));
        tabsViewBackground.setRadius(dp(DialogsActivity.MAIN_TABS_HEIGHT / 2f));
        tabsViewBackground.setPadding(dp(DialogsActivity.MAIN_TABS_MARGIN - 0.334f));
        tabsView.setBackground(tabsViewBackground);

        BlurredBackgroundDrawableViewFactory iBlur3FactoryFade = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        iBlur3FactoryFade.setSourceRootView(viewPositionWatcher, contentView);

        fadeView = new View(context);
        BlurredBackgroundWithFadeDrawable fadeDrawable = new BlurredBackgroundWithFadeDrawable(iBlur3FactoryFade.create(fadeView, null));
        fadeDrawable.setFadeHeight(dp(60), true);
        fadeView.setBackground(fadeDrawable);

        contentView.addView(fadeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 0, Gravity.BOTTOM));

        tabsViewWrapper = new FrameLayout(context);
        tabsViewWrapper.setOnClickListener(v -> {});
        tabsViewWrapper.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL));
        tabsViewWrapper.setClipToPadding(false);
        contentView.addView(tabsViewWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateLayoutWrapper = new UpdateLayoutWrapper(context);
        contentView.addView(updateLayoutWrapper, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM));

        updateLayout = ApplicationLoader.applicationLoaderInstance.takeUpdateLayout(getParentActivity(), updateLayoutWrapper);
        if (updateLayout != null) {
            updateLayout.updateAppUpdateViews(currentAccount, false);
        }

        updateLayout();
        checkUnreadCount(false);
        return contentView;
    }

    private void checkUnreadCount(boolean animated) {
        if (tabsView == null || tabView(TAB_CHATS) == null) {
            return;
        }

        final int unreadCount = MessagesStorage.getInstance(currentAccount).getMainUnreadCount();
        if (unreadCount > 0) {
            final String unreadCountFmt = LocaleController.formatNumber(unreadCount, ',');
            tabView(TAB_CHATS).setCounter(unreadCountFmt, false, animated);
        } else {
            tabView(TAB_CHATS).setCounter(null, false, animated);
        }
    }

    public boolean openContactsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.msg_contact_add, getString(R.string.NewContact), () -> {
            new NewContactBottomSheet(this, getContext()).show();
        });
        o.add(R.drawable.msg_calls, getString(R.string.VoipChatRecentCalls), () -> {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            presentFragment(new CallLogActivity(args));
        });
        o.setBlur(true);
        o.translate(0, -dp(4));
        o.setGravity(Gravity.LEFT);
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    public boolean openCallsSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        o.add(R.drawable.menu_call_create, getString(R.string.GroupCallCreate2), () -> CallLogActivity.openCreateCall(this));
        if (getUserConfig().showCallsTab) {
            o.add(R.drawable.msg_archive_hide, getString(R.string.HideCallTab), () -> {
                getUserConfig().setShowCallsTab(false);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.callTabsVisibleToggled);
            });
        } else {
            o.add(R.drawable.menu_add_tab_24, getString(R.string.GroupCallShowInMainTabs), () -> {
                getUserConfig().setShowCallsTab(true);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.callTabsVisibleToggled);
            });
        }
        o.setBlur(true);
        o.translate(0, -dp(4));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();
        return true;
    }

    private Integer pendingFolderId;

    private boolean openFoldersSelector(View anchor) {
        if (getContext() == null || getParentActivity() == null) return false;
        final ArrayList<MessagesController.DialogFilter> filters = getMessagesController().getDialogFilters();
        if (filters == null || filters.size() <= 1) return false;

        final ItemOptions o = ItemOptions.makeOptions(this, anchor);
        for (int i = 0; i < filters.size(); i++) {
            final MessagesController.DialogFilter folder = filters.get(i);
            final ActionBarMenuSubItem folderItem = new ActionBarMenuSubItem(getParentActivity(), 2, false, false, getResourceProvider());
            folderItem.setPadding(dp(18), 0, dp(18), 0);
            CharSequence title = folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name;
            title = Emoji.replaceEmoji(title, folderItem.getTextView().getPaint().getFontMetricsInt(), false);
            if (!folder.isDefault()) {
                title = MessageObject.replaceAnimatedEmoji(title, folder.entities, folderItem.getTextView().getPaint().getFontMetricsInt());
            }
            final int unreadCount = folder.isDefault()
                    ? MessagesStorage.getInstance(currentAccount).getMainUnreadCount()
                    : folder.unreadCount;
            if (unreadCount > 0) {
                final SpannableStringBuilder titleWithCounter = new SpannableStringBuilder(title);
                final int counterStart = titleWithCounter.length();
                titleWithCounter.append(String.valueOf(unreadCount));
                titleWithCounter.setSpan(
                        new FolderCounterSpan(unreadCount, hasUnmutedUnreadDialogs(folder)),
                        counterStart,
                        titleWithCounter.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                title = titleWithCounter;
                folderItem.setContentDescription(
                        TextUtils.concat(
                                folder.isDefault() ? getString(R.string.FilterAllChats) : folder.name,
                                "\n",
                                LocaleController.formatPluralString("AccDescrUnreadCount", unreadCount)
                        )
                );
            }
            folderItem.setEmojiCacheType(folder.title_noanimate ? AnimatedEmojiDrawable.CACHE_TYPE_NOANIMATE_FOLDER : AnimatedEmojiDrawable.CACHE_TYPE_MESSAGES);
            final int color = getMessagesController().folderTags ? folder.color : -1;
            folderItem.setTextAndIcon(title, 0, new FolderDrawable(getContext(), R.drawable.msg_folders, color));
            folderItem.getTextView().setEmojiColor(getThemedColor(Theme.key_featuredStickers_addButton));
            folderItem.setMinimumWidth(160);
            folderItem.setOnClickListener(e -> {
                o.dismiss();
                openFolder(folder.id);
            });
            o.addView(folderItem, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
//        o.setBlur(true);
        o.translate(-dp(8), -dp(4));
        o.setMaxHeight(dp(400));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.setGravity(Gravity.LEFT);
        o.show();

        return true;
    }

    private boolean hasUnmutedUnreadDialogs(MessagesController.DialogFilter folder) {
        final MessagesController messagesController = getMessagesController();
        final ArrayList<TLRPC.Dialog> dialogs = folder.isDefault()
                ? messagesController.getDialogs(0)
                : messagesController.getAllDialogs();
        for (int i = 0; i < dialogs.size(); i++) {
            final TLRPC.Dialog dialog = dialogs.get(i);
            if (!folder.isDefault()) {
                long dialogId = dialog.id;
                if (DialogObject.isEncryptedDialog(dialogId)) {
                    final TLRPC.EncryptedChat encryptedChat = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
                    if (encryptedChat != null) {
                        dialogId = encryptedChat.user_id;
                    }
                }
                if (!folder.includesDialog(getAccountInstance(), dialogId, dialog)) {
                    continue;
                }
            }
            if ((messagesController.getDialogUnreadCount(dialog) > 0 || dialog.unread_mark)
                    && !messagesController.isDialogMuted(dialog.id, 0)) {
                return true;
            }
        }
        return false;
    }

    private class FolderCounterSpan extends ReplacementSpan {

        private static final float HEIGHT_DP = 17.333f;
        private final String count;
        private final boolean hasUnmutedUnreadDialogs;
        private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float counterWidth;

        FolderCounterSpan(int count, boolean hasUnmutedUnreadDialogs) {
            this.count = String.valueOf(count);
            this.hasUnmutedUnreadDialogs = hasUnmutedUnreadDialogs;
            textPaint.setTextSize(AndroidUtilities.dpf2(11));
            textPaint.setTypeface(AndroidUtilities.bold());
            counterWidth = Math.max(dp(HEIGHT_DP - 10), textPaint.measureText(this.count)) + dp(10);
        }

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
            return (int) Math.ceil(dp(5) + counterWidth);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float x, int top, int y, int bottom, @NonNull Paint paint) {
            final float left = x + dp(5);
            final float centerY = (top + bottom) / 2f + dp(1);
            final float halfHeight = dp(HEIGHT_DP) / 2f;
            backgroundPaint.setColor(getThemedColor(
                hasUnmutedUnreadDialogs ?
                    Theme.key_featuredStickers_addButton :
                    Theme.key_chats_tabUnreadUnactiveBackground
            ));
            textPaint.setColor(getThemedColor(Theme.key_actionBarDefault));
            AndroidUtilities.rectTmp.set(left, centerY - halfHeight, left + counterWidth, centerY + halfHeight);
            canvas.drawRoundRect(AndroidUtilities.rectTmp, halfHeight, halfHeight, backgroundPaint);
            final Paint.FontMetrics fontMetrics = textPaint.getFontMetrics();
            final float baseline = centerY - (fontMetrics.ascent + fontMetrics.descent) / 2f;
            canvas.drawText(count, left + (counterWidth - textPaint.measureText(count)) / 2f, baseline, textPaint);
        }
    }

    private void openFolder(int folderId) {
        if (positionForTab(TAB_CHATS) < 0) return;
        if (tabAtPosition(viewPager.getCurrentPosition()) == TAB_CHATS && dialogsActivity != null) {
            dialogsActivity.scrollToFolder(folderId);
        } else {
            if (dialogsActivity == null) {
                prepareDialogsActivity(null);
            }
            pendingFolderId = folderId;
            selectTab(positionForTab(TAB_CHATS), true);
            viewPager.scrollToPosition(positionForTab(TAB_CHATS));
        }
    }

    public boolean openAccountSelector(View button) {
        final ArrayList<Integer> accountNumbers = new ArrayList<>();

        accountNumbers.clear();
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            if (UserConfig.getInstance(a).isClientActivated()) {
                accountNumbers.add(a);
            }
        }
        Collections.sort(accountNumbers, (o1, o2) -> {
            // Group by server first, then by login time within a server.
            int c = serverLabel(o1).compareToIgnoreCase(serverLabel(o2));
            if (c != 0) {
                return c;
            }
            long l1 = UserConfig.getInstance(o1).loginTime;
            long l2 = UserConfig.getInstance(o2).loginTime;
            return Long.compare(l1, l2);
        });

        ItemOptions o = ItemOptions.makeOptions(this, button);
        o.add(R.drawable.tabs_reorder, getString(R.string.MainTabsCustomize), () -> showTabSettings());
        if (UserConfig.getActivatedAccountsCount() < UserConfig.MAX_ACCOUNT_COUNT) {
            o.add(R.drawable.msg_addbot, getString(R.string.AddAccount), () -> {
                // Any account (custom server) may use any free slot; the premium cap
                // is enforced per-server (Telegram only) in ServerSelectFragment.
                int availableAccount = org.telegram.owpengram.OwpengramServers.firstFreeAccountSlot();
                if (availableAccount >= 0) {
                    presentFragment(new ServerSelectFragment(availableAccount));
                } else {
                    showDialog(new LimitReachedBottomSheet(this, getContext(), TYPE_ACCOUNTS, currentAccount, null));
                }
            });
        }

        if (BuildConfig.DEBUG_PRIVATE_VERSION) {
            o.add(R.drawable.menu_download_round, "Dump Canvas", () -> AndroidUtilities.runOnUIThread(this::dumpCanvas, 1000));
        }

        if (accountNumbers.size() > 0) {
            if (o.getItemsCount() > 0) o.addGap();
            String lastServer = null;
            for (int acc : accountNumbers) {
                final int account = acc;
                final String serverName = serverLabel(acc);
                if (!serverName.equals(lastServer)) {
                    if (lastServer != null) {
                        o.addGap(); // separator between server groups
                    }
                    o.addView(serverHeaderView(serverName), LayoutHelper.createLinear(230, LayoutHelper.WRAP_CONTENT));
                    lastServer = serverName;
                }
                final View btn = accountView(acc, currentAccount == acc);
                btn.setOnClickListener(v -> {
                    if (currentAccount == account) return;
                    o.dismiss();
                    if (LaunchActivity.instance != null) {
                        LaunchActivity.instance.switchToAccount(account, true);
                    }
                });
                o.addView(btn, LayoutHelper.createLinear(230, 48));
            }
        }

        o.setBlur(true);
        o.translate(0, -dp(4));
        final ShapeDrawable bg = Theme.createRoundRectDrawable(dp(28), getThemedColor(Theme.key_windowBackgroundWhite));
        bg.getPaint().setShadowLayer(dp(6), 0, dp(1), Theme.multAlpha(0xFF000000, 0.15f));
        o.setScrimViewBackground(bg);
        o.show();

        HintsController.Hint.AccountSwitchHint.doNotShowAgain();

        return true;
    }

    private void showTabSettings() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.MainTabsCustomize));
        builder.setItems(new CharSequence[]{getString(R.string.MainTabsShowHide), getString(R.string.MainTabsReorder)}, (dialog, which) -> {
            if (which == 0) showTabVisibilitySettings(); else showTabOrderSettings();
        });
        showDialog(builder.create());
    }

    private void showTabVisibilitySettings() {
        final String[] names = new String[]{tabName(TAB_REELS), tabName(TAB_CHATS), tabName(TAB_CONTACTS), tabName(TAB_PROFILE)};
        final int[] ids = new int[]{TAB_REELS, TAB_CHATS, TAB_CONTACTS, TAB_PROFILE};
        final int hidden = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getInt("owpg_main_tabs_hidden", 0) & ~(1 << TAB_PROFILE);
        final CharSequence[] choices = new CharSequence[ids.length];
        for (int i = 0; i < ids.length; i++) {
            final String label = ids[i] == TAB_PROFILE ? getString(R.string.MainTabsProfileRequired) : names[i];
            choices[i] = label + ((hidden & (1 << ids[i])) == 0 ? "  ✓" : "");
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.MainTabsShowHide));
        builder.setItems(choices, (dialog, which) -> {
            final int id = ids[which];
            if (id == TAB_PROFILE) return;
            int nextHidden = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getInt("owpg_main_tabs_hidden", 0) ^ (1 << id);
            if (Integer.bitCount((~nextHidden) & 0xF) < 2) {
                showTabVisibilitySettings();
                return;
            }
            ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit().putInt("owpg_main_tabs_hidden", nextHidden).apply();
            applyTabConfiguration();
            showTabVisibilitySettings();
        });
        showDialog(builder.create());
    }

    private void showTabOrderSettings() {
        final int[] order = configuredTabs.clone();
        final CharSequence[] names = new CharSequence[order.length];
        for (int i = 0; i < order.length; i++) names[i] = tabName(order[i]);
        final AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(getString(R.string.MainTabsReorder));
        builder.setItems(names, (dialog, selected) -> {
            final CharSequence[] moves = new CharSequence[]{getString(R.string.MainTabsMoveEarlier), getString(R.string.MainTabsMoveLater)};
            new AlertDialog.Builder(getParentActivity()).setItems(moves, (moveDialog, direction) -> {
                int target = selected + (direction == 0 ? -1 : 1);
                if (target < 0 || target >= order.length) return;
                int value = order[selected];
                order[selected] = order[target];
                order[target] = value;
                saveTabOrder(order);
                applyTabConfiguration();
                showTabOrderSettings();
            }).show();
        });
        showDialog(builder.create());
    }

    private void saveTabOrder(int[] visibleOrder) {
        final StringBuilder value = new StringBuilder();
        for (int id : visibleOrder) {
            if (value.length() > 0) value.append(',');
            value.append(id);
        }
        int hidden = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).getInt("owpg_main_tabs_hidden", 0);
        for (int id = TAB_REELS; id <= TAB_PROFILE; id++) if ((hidden & (1 << id)) != 0) value.append(',').append(id);
        ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Context.MODE_PRIVATE).edit().putString("owpg_main_tabs_order", value.toString()).apply();
    }

    private String serverLabel(int account) {
        String name = org.telegram.owpengram.OwpengramServers.serverNameForAccount(account);
        return name != null ? name : getString(R.string.AppName);
    }

    private android.widget.TextView serverHeaderView(String title) {
        android.widget.TextView tv = new android.widget.TextView(getContext());
        tv.setText(title);
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 12);
        tv.setTypeface(AndroidUtilities.bold());
        tv.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueHeader));
        tv.setPadding(dp(16), dp(8), dp(16), dp(4));
        tv.setAllCaps(true);
        return tv;
    }

    public LinearLayout accountView(int account, boolean selected) {
        final LinearLayout btn = new LinearLayout(getContext());
        btn.setOrientation(LinearLayout.HORIZONTAL);
        btn.setBackground(Theme.createRadSelectorDrawable(getThemedColor(Theme.key_listSelector), 0, 0));

        final TLRPC.User user = UserConfig.getInstance(account).getCurrentUser();

        final AvatarDrawable avatarDrawable = new AvatarDrawable();
        avatarDrawable.setInfo(user);

        final FrameLayout avatarContainer = new FrameLayout(getContext()) {
            private final Paint selectedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                if (selected) {
                    selectedPaint.setStyle(Paint.Style.STROKE);
                    selectedPaint.setStrokeWidth(dp(1.33f));
                    selectedPaint.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                    canvas.drawCircle(getWidth() / 2.0f, getHeight() / 2.0f, dp(16), selectedPaint);
                }
                super.dispatchDraw(canvas);
            }
        };
        btn.addView(avatarContainer, LayoutHelper.createLinear(34, 34, Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        final BackupImageView avatarView = new BackupImageView(getContext());
        if (selected) {
            avatarView.setScaleX(0.833f);
            avatarView.setScaleY(0.833f);
        }
        avatarView.setRoundRadius(dp(16));
        avatarView.getImageReceiver().setCurrentAccount(account);
        avatarView.setForUserOrChat(user, avatarDrawable);
        avatarContainer.addView(avatarView, LayoutHelper.createLinear(32, 32, Gravity.CENTER, 1, 1, 1, 1));

        final TextView textView = new TextView(getContext());
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
        textView.setText(UserObject.getUserName(user));
        textView.setMaxLines(2);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        btn.addView(textView, LayoutHelper.createLinear(0, LayoutHelper.WRAP_CONTENT, 1f, Gravity.CENTER_VERTICAL, 13, 0, 14, 0));

        return btn;
    }

    @Override
    protected void onViewPagerScrollEnd() {
        if (tabsView != null) {
            selectTab(viewPager.getCurrentPosition(), true);
            setGestureSelectedOverride(0, false);
        }
        blur3_invalidateBlur();

        if (viewPager != null) {
            final int currentPosition = viewPager.getCurrentPosition();
            if (pendingFolderId != null && tabAtPosition(currentPosition) == TAB_CHATS && dialogsActivity != null) {
                dialogsActivity.scrollToFolder(pendingFolderId);
                pendingFolderId = null;
            }
        }

    }

    @Override
    protected void onViewPagerTabAnimationUpdate(boolean manual) {
        final boolean isDragByGesture = !manual;

        if (tabsView != null) {
            final float position = viewPager.getPositionAnimated();
            setGestureSelectedOverride(position, isDragByGesture);
            if (isDragByGesture) {
                selectTab(Math.round(position), true);
            }
        }

        checkUi_fadeView();
        blur3_invalidateBlur();
        contentView.invalidate();
    }


    @Override
    protected int getFragmentsCount() {
        return configuredTabs.length;
    }

    @Override
    protected int getStartPosition() {
        return Math.max(0, positionForTab(TAB_CHATS));
    }

    private DialogsActivity dialogsActivity;

    @Override
    public boolean onBackPressed(boolean invoked) {
        final boolean result = super.onBackPressed(invoked);
        if (result) {
            final int startPosition = getStartPosition();
            if (viewPager.getCurrentPosition() != startPosition) {
                if (invoked) {
                    viewPager.scrollToPosition(startPosition);
                }
                return false;
            }
        }
        return result;
    }

    public DialogsActivity prepareDialogsActivity(Bundle bundle) {
        if (bundle == null) {
            bundle = new Bundle();
        }

        bundle.putBoolean("hasMainTabs", true);
        dialogsActivity = new DialogsActivity(bundle);
        dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
        putFragmentAtPosition(Math.max(0, positionForTab(TAB_CHATS)), dialogsActivity);
        return dialogsActivity;
    }

    @Override
    protected BaseFragment createBaseFragmentAt(int position) {
        final int tabId = tabAtPosition(position);
        if (tabId == TAB_REELS) {
            return new org.telegram.ui.Components.Shorts.ShortsActivity();
        } else if (tabId == TAB_CONTACTS) {
            Bundle args = new Bundle();
            args.putBoolean("needPhonebook", true);
            args.putBoolean("needFinishFragment", false);
            args.putBoolean("hasMainTabs", true);
            return new ContactsActivity(args);
        } else if (tabId == TAB_CHATS) {
            Bundle args = new Bundle();
            args.putBoolean("hasMainTabs", true);
            dialogsActivity = new DialogsActivity(args);
            dialogsActivity.setMainTabsActivityController(new MainTabsActivityControllerImpl());
            return dialogsActivity;
        } else if (tabId == TAB_PROFILE) {
            Bundle args = new Bundle();
            args.putLong("user_id", UserConfig.getInstance(currentAccount).getClientUserId());
            args.putBoolean("my_profile", true);
            // args.putBoolean("expandPhoto", true);
            args.putBoolean("hasMainTabs", true);
            return new ProfileActivity(args);
        }
        return null;
    }

    public DialogsActivity getDialogsActivity() {
        return dialogsActivity;
    }

    /* */

    public GlassTabView[] tabs;

    public void selectTab(int position, boolean animated) {
        for (int a = 0; a < tabs.length; a++) {
            GlassTabView tab = tabs[a];
            tab.setSelected(a == position, animated);
        }
    }

    public void setGestureSelectedOverride(float animatedPosition, boolean allow) {
        for (int index = 0; index < tabs.length; index++) {
            final int position = index;
            final float visibility = Math.max(0, 1f - Math.abs(position - animatedPosition));
            tabs[index].setGestureSelectedOverride(visibility, allow);
        }
        tabsView.invalidate();
    }


    /* * */

    public interface TabFragmentDelegate {
        default boolean canParentTabsSlide(MotionEvent ev, boolean forward) {
            return false;
        }

        default void onParentScrollToTop() {

        }

        default BlurredBackgroundSourceRenderNode getGlassSource() {
            return null;
        }
    }

    @Override
    protected boolean canScrollForward(MotionEvent ev) {
        return canScrollInternal(ev, true);
    }

    @Override
    protected boolean canScrollBackward(MotionEvent ev) {
        return canScrollInternal(ev, false);
    }

    private boolean canScrollInternal(MotionEvent ev, boolean forward) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment instanceof TabFragmentDelegate) {
            final TabFragmentDelegate delegate = (TabFragmentDelegate) fragment;
            return delegate.canParentTabsSlide(ev, forward);

        }

        return false;
    }


    /* * */

    private int navigationBarHeight;
    private int insetLeft;
    private int insetRight;

    @NonNull
    @Override
    protected WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        final Insets systemInsets = AndroidUtilities.getDefaultWindowInsets(insets, false);

        insetLeft = systemInsets.left;
        insetRight = systemInsets.right;

        navigationBarHeight = systemInsets.bottom;
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        updateLayoutWrapper.setPadding(0, 0, 0, navigationBarHeight);

        ViewGroup.MarginLayoutParams lp;
        {
            final int height = navigationBarHeight + updateLayoutHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS);
            lp = (ViewGroup.MarginLayoutParams) fadeView.getLayoutParams();
            if (lp.height != height) {
                lp.height = height;
                fadeView.setLayoutParams(lp);
            }
        }
        {
            int bottomMargin = isUpdateLayoutVisible ? (navigationBarHeight + updateLayoutHeight) : 0;
            if (tabletLayout) {
                bottomMargin = Math.max(bottomMargin, navigationBarHeight + dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
            }
            lp = (ViewGroup.MarginLayoutParams) viewPager.getLayoutParams();
            if (lp.bottomMargin != bottomMargin || lp.leftMargin != systemInsets.left || lp.rightMargin != systemInsets.right) {
                lp.leftMargin = systemInsets.left;
                lp.rightMargin = systemInsets.right;
                lp.bottomMargin = bottomMargin;
                viewPager.setLayoutParams(lp);
            }
        }

        tabsViewWrapper.setPadding(systemInsets.left, 0, systemInsets.right, navigationBarHeight);

        final WindowInsetsCompat consumed = isUpdateLayoutVisible ?
            insets.inset(0, 0, 0, navigationBarHeight) : insets;

        checkUi_tabsPosition();
        checkUi_fadeView();

        return super.onApplyWindowInsets(v, consumed);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.notificationsCountUpdated || id == NotificationCenter.updateInterfaces) {
            checkUnreadCount(fragmentView != null && fragmentView.isAttachedToWindow());
        } else if (id == NotificationCenter.appUpdateLoading) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(null);
                updateLayout.updateAppUpdateViews(currentAccount, true);
            }
        } else if (id == NotificationCenter.fileLoaded) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadFailed) {
            String path = (String) args[0];
            if (SharedConfig.isAppUpdateAvailable()) {
                String name = FileLoader.getAttachFileName(SharedConfig.pendingAppUpdate.document);
                if (name.equals(path) && updateLayout != null) {
                    updateLayout.updateAppUpdateViews(currentAccount, true);
                }
            }
        } else if (id == NotificationCenter.fileLoadProgressChanged) {
            if (updateLayout != null) {
                updateLayout.updateFileProgress(args);
            }
        } else if (id == NotificationCenter.appUpdateAvailable) {
            if (updateLayout != null && LaunchActivity.instance != null) {
                updateLayout.updateAppUpdateViews(currentAccount, LaunchActivity.instance.getMainFragmentsStackSize() == 1);
            }
        } else if (id == NotificationCenter.needSetDayNightTheme) {
            clearAllHiddenFragments();
        } else if (id == NotificationCenter.mainUserInfoChanged) {
            if (tabView(TAB_PROFILE) != null) {
                tabView(TAB_PROFILE).updateUserAvatar(currentAccount);
            }
        } else if (id == NotificationCenter.contactsPermissionBadgeCheck) {
            checkContactsTabBadge();
        }
    }

    private NotificationCenter.ObserversGroup observersGroup;

    @Override
    public boolean onFragmentCreate() {
        observersGroup = NotificationCenter.getInstance(currentAccount)
            .createObserversGroup(this)
            .add(NotificationCenter.fileLoaded)
            .add(NotificationCenter.fileLoadProgressChanged)
            .add(NotificationCenter.fileLoadFailed)
            .add(NotificationCenter.notificationsCountUpdated)
            .add(NotificationCenter.updateInterfaces)
            .add(NotificationCenter.mainUserInfoChanged)
            .add(NotificationCenter.contactsPermissionBadgeCheck)
            .addGlobal(NotificationCenter.appUpdateAvailable)
            .addGlobal(NotificationCenter.appUpdateLoading)
            .addGlobal(NotificationCenter.needSetDayNightTheme);

        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        Bulletin.removeDelegate(this);
        Bulletin.removeDelegate(contentView);

        if (observersGroup != null) {
            observersGroup.removeAllObservers();
            observersGroup = null;
        }

        super.onFragmentDestroy();
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_TABS_VISIBLE) {
            checkUi_tabsPosition();
            checkUi_fadeView();
        }
    }

    private void checkUi_fadeView() {
        if (viewPager == null || fadeView == null) {
            return;
        }

        final float animatedPosition = viewPager.getPositionAnimated();
        final int profilePosition = positionForTab(TAB_PROFILE);
        final float isProfile = profilePosition < 0 ? 0f : 1f - MathUtils.clamp(Math.abs(profilePosition - animatedPosition), 0, 1);
        final float hide = 1f - AndroidUtilities.getNavigationBarThirdButtonsFactor(0, 1f, navigationBarHeight);
        float alpha = (1f - isProfile * hide) * animatorTabsVisible.getFloatValue();
        if (tabletLayout) {
            alpha = 0.0f;
        }

        fadeView.setAlpha(alpha);
        fadeView.setTranslationY(isProfile * dp(48));
        fadeView.setVisibility(alpha > 0 ? View.VISIBLE : View.GONE);
    }

    private void checkUi_tabsPosition() {
        final boolean isUpdateLayoutVisible = updateLayoutWrapper.isUpdateLayoutVisible();
        final int updateLayoutHeight = isUpdateLayoutVisible ? dp(UpdateLayoutWrapper.HEIGHT) : 0;
        final int normalY = -(updateLayoutHeight);
        final int hiddenY = normalY + dp(40);

        final float factor = animatorTabsVisible.getFloatValue();
        final float scale = lerp(0.85f, 1f, factor);

        tabsViewWrapper.setTranslationY(lerp(hiddenY, normalY, factor));
        tabsView.setClickable(factor > 1);
        tabsView.setEnabled(factor > 1);
        tabsView.setAlpha(factor);
        tabsView.setVisibility(factor > 0 ? View.VISIBLE : View.GONE);
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = super.getThemeDescriptions();

        ThemeDescription.ThemeDescriptionDelegate cellDelegate = this::blur3_updateColors;
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_dialogBackground));

        return themeDescriptions;
    }

    /* * */

    private class MainTabsActivityControllerImpl implements MainTabsActivityController {
        @Override
        public void setTabsVisible(boolean visible) {
            animatorTabsVisible.setValue(visible, true);
        }
    }


    /* Slide */

    @Override
    public boolean canBeginSlide() {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null && fragment.canBeginSlide();
    }

    @Override
    public void onBeginSlide() {
        super.onBeginSlide();
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onBeginSlide();
        }
    }

    @Override
    public void onSlideProgress(boolean isOpen, float progress) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.onSlideProgress(isOpen, progress);
        }
    }

    @Override
    public Animator getCustomSlideTransition(boolean topFragment, boolean backAnimation, float distanceToMove) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        return fragment != null ? fragment.getCustomSlideTransition(topFragment, backAnimation, distanceToMove) : null;
    }

    @Override
    public void prepareFragmentToSlide(boolean topFragment, boolean beginSlide) {
        final BaseFragment fragment = getCurrentVisibleFragment();
        if (fragment != null) {
            fragment.prepareFragmentToSlide(topFragment, beginSlide);
        }
    }


    private HintView2 accountSwitchHint;
    private boolean accountSwitchHintShown;

    private void showAccountChangeHint() {
        if (accountSwitchHintShown) return;

        if (accountSwitchHint == null && HintsController.Hint.AccountSwitchHint.show()) {
            AndroidUtilities.runOnUIThread(() -> {
                if (getContext() == null || tabs == null) return;

                final View v = tabView(TAB_PROFILE);
                final float translate = (contentView.getWidth() - ((tabsView.getX() + v.getX()) + v.getWidth()) + v.getWidth() / 2f) / AndroidUtilities.density;

                accountSwitchHint = new HintView2(getContext(), HintView2.DIRECTION_BOTTOM);
                accountSwitchHint.setTranslationY(-navigationBarHeight + dp(4));
                accountSwitchHint.setPadding(dp(7.33f), 0, dp(7.33f), 0);
                accountSwitchHint.setMultilineText(false);
                accountSwitchHint.setCloseButton(true);
                accountSwitchHint.setText(getString(R.string.SwitchAccountHint));
                accountSwitchHint.setJoint(1, -translate + 7.33f);
                contentView.addView(accountSwitchHint, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 100, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL, 0, 0, 0, DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS));
                accountSwitchHint.setOnHiddenListener(() -> AndroidUtilities.removeFromParent(accountSwitchHint));
                accountSwitchHint.setDuration(8000);
                accountSwitchHint.show();

                HintsController.Hint.AccountSwitchHint.increment();
            }, 1500);
        }

        accountSwitchHintShown = true;
    }


    /* * */

    private final @NonNull BlurredBackgroundSourceColor iBlur3SourceColor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceTabGlass;

    private final RectF fragmentPosition = new RectF();
    private void blur3_invalidateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || iBlur3SourceTabGlass == null || fragmentView == null) {
            return;
        }

        final int width = fragmentView.getMeasuredWidth();
        final int height = fragmentView.getMeasuredHeight();

        iBlur3SourceTabGlass.setSize(width, height);
        iBlur3SourceTabGlass.updateDisplayListIfNeeded();
    }

    private void blur3_updateFadeColors() {
        iBlur3SourceColor.setColor(getEstBackgroundColor());
        if (fadeView != null) {
            fadeView.invalidate();
        }
    }

    private void blur3_updateColors() {
        blur3_updateFadeColors();
        if (tabsViewBackground != null) {
            tabsViewBackground.updateColors();
        }
        blur3_invalidateBlur();
        if (fadeView != null) {
            fadeView.invalidate();
        }
        if (tabsView != null) {
            tabsView.invalidate();
        }
        if (tabs != null) {
            for (GlassTabView tabView : tabs) {
                tabView.updateColorsLottie();
            }
        }
    }

    @Override
    public EdgeToEdgeSupportMode getEdgeToEdgeSupportMode() {
        return EdgeToEdgeSupportMode.FULL;
    }
}
