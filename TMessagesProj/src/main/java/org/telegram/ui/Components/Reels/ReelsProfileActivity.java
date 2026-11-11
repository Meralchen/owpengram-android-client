package org.telegram.ui.Components.Reels;

import android.app.Activity;
import org.telegram.ui.ActionBar.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.BaseFragment;

/** A normal-theme profile page for a creator's Reels. */
public final class ReelsProfileActivity extends BaseFragment {

    private static final int REQUEST_PICK_REEL = 0x5EED2;
    private final String username;
    private final int account = UserConfig.selectedAccount;
    private ReelsProfileGridView grid;
    private ReelsUploadController uploadController;

    public ReelsProfileActivity(String username) {
        this.username = username;
    }

    @Override
    public View createView(Context context) {
        if (actionBar != null) {
            actionBar.setTitle("@" + username);
            actionBar.setBackgroundColor(ReelsTheme.background(resourceProvider));
        }
        uploadController = new ReelsUploadController(context, account);
        grid = new ReelsProfileGridView(context, account, username, resourceProvider,
                new ReelsProfileGridView.Delegate() {
                    @Override
                    public void onOpen(ReelsItem item) {
                        presentFragment(new ReelsStoryViewer(ReelsFeedSource.PROFILE, item));
                    }

                    @Override
                    public void onUpload() {
                        pickVideo();
                    }
                });
        fragmentView = grid;
        return fragmentView;
    }

    private void pickVideo() {
        if (getParentActivity() == null) return;
        final Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("video/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            getParentActivity().startActivityForResult(intent, REQUEST_PICK_REEL);
        } catch (Exception error) {
            Toast.makeText(getParentActivity(), "No video picker is available.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_REEL && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            showCaptionDialog(data.getData());
        } else {
            super.onActivityResultFragment(requestCode, resultCode, data);
        }
    }

    private void showCaptionDialog(Uri uri) {
        if (getParentActivity() == null) return;
        final LinearLayout layout = new LinearLayout(getParentActivity());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(20), dp(8), dp(20), 0);

        final EditText caption = new EditText(getParentActivity());
        caption.setHint("Caption");
        caption.setMaxLines(3);
        caption.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        layout.addView(caption, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final EditText cover = new EditText(getParentActivity());
        cover.setHint("Cover time in seconds");
        cover.setInputType(InputType.TYPE_CLASS_NUMBER);
        cover.setText("1");
        layout.addView(cover, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(getParentActivity())
                .setTitle("Upload Reel")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Upload", (dialog, which) -> {
                    int coverSeconds = 1;
                    try {
                        coverSeconds = Math.max(0, Integer.parseInt(cover.getText().toString()));
                    } catch (Exception ignore) {
                    }
                    startUpload(uri, caption.getText().toString(), coverSeconds * 1000);
                })
                .show();
    }

    private void startUpload(Uri uri, String caption, int coverMs) {
        if (getParentActivity() == null) return;
        final AlertDialog progress = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
        progress.showDelayed(300);
        uploadController.start(uri, caption, coverMs, (item, error) -> {
            try {
                progress.dismiss();
            } catch (Exception ignore) {
            }
            if (error != null) {
                Toast.makeText(getParentActivity(), error, Toast.LENGTH_SHORT).show();
                return;
            }
            if (grid != null) grid.refresh();
            if (item != null) {
                presentFragment(new ReelsStoryViewer(ReelsFeedSource.PROFILE, item));
            }
        });
    }

    @Override
    public void onFragmentDestroy() {
        if (uploadController != null) uploadController.cancel();
        super.onFragmentDestroy();
    }

    private int dp(float value) {
        return AndroidUtilities.dp(value);
    }
}
