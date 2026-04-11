package com.coara.browser.webview;

import android.app.Activity;
import androidx.appcompat.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.coara.browser.R;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import android.webkit.WebView;

public final class TabOverviewDialog {
    private TabOverviewDialog() {}

    public interface Host {
        int getTabCount();
        WebView getTabAt(int index);
        Bitmap getTabSnapshot(WebView webView);
        int getCurrentTabIndex();
        void switchToTab(int index);
        void closeTabAt(int index);
        void createNewTab();
        void refreshTabCount();
        void dismissCurrentKeyboard();
    }

    public static void show(@NonNull Activity activity, @NonNull Host host) {
        RecyclerView recyclerView = new RecyclerView(activity);
        recyclerView.setLayoutManager(new GridLayoutManager(activity, 2));
        recyclerView.setNestedScrollingEnabled(true);

        AlertDialog dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("タブ一覧")
                .setView(recyclerView)
                .setNegativeButton("閉じる", null)
                .create();

        TabOverviewAdapter adapter = new TabOverviewAdapter(activity, host, dialog);
        recyclerView.setAdapter(adapter);

        dialog.setButton(AlertDialog.BUTTON_POSITIVE, "新しいタブ", (d, which) -> {
            host.dismissCurrentKeyboard();
            host.createNewTab();
            host.refreshTabCount();
            adapter.notifyDataSetChanged();
        });

        dialog.show();
    }

    private static final class TabOverviewAdapter extends RecyclerView.Adapter<TabOverviewAdapter.TabViewHolder> {
        private final Activity activity;
        private final Host host;
        private final AlertDialog dialog;

        TabOverviewAdapter(Activity activity, Host host, AlertDialog dialog) {
            this.activity = activity;
            this.host = host;
            this.dialog = dialog;
        }

        @NonNull
        @Override
        public TabViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            int padding = dp(10);
            MaterialCardView card = new MaterialCardView(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(padding, padding, padding, padding);
            card.setLayoutParams(params);
            card.setRadius(dp(18));
            card.setUseCompatPadding(true);
            card.setCardElevation(dp(2));
            card.setStrokeWidth(dp(2));
            card.setStrokeColor(Color.TRANSPARENT);

            FrameLayout root = new FrameLayout(activity);
            root.setLayoutParams(new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));

            ImageView preview = new ImageView(activity);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setAdjustViewBounds(false);
            preview.setBackgroundColor(Color.parseColor("#1E1E1E"));
            FrameLayout.LayoutParams previewParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(220)
            );
            root.addView(preview, previewParams);

            LinearLayout overlay = new LinearLayout(activity);
            overlay.setOrientation(LinearLayout.VERTICAL);
            overlay.setPadding(dp(12), dp(8), dp(12), dp(8));
            overlay.setBackgroundColor(Color.argb(190, 20, 20, 20));
            FrameLayout.LayoutParams overlayParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
            );
            root.addView(overlay, overlayParams);

            TextView title = new TextView(activity);
            title.setTextColor(Color.WHITE);
            title.setTextSize(12);
            title.setMaxLines(2);
            overlay.addView(title);

            TextView activeBadge = new TextView(activity);
            activeBadge.setText("現在のタブ");
            activeBadge.setTextSize(11);
            activeBadge.setTextColor(Color.WHITE);
            activeBadge.setPadding(dp(8), dp(4), dp(8), dp(4));
            activeBadge.setBackgroundColor(Color.parseColor("#4A90E2"));
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.START
            );
            badgeParams.setMargins(dp(10), dp(10), dp(10), dp(10));
            root.addView(activeBadge, badgeParams);

            ImageButton close = new ImageButton(activity);
            close.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
            close.setBackgroundColor(Color.argb(160, 0, 0, 0));
            close.setColorFilter(Color.WHITE);
            FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END);
            closeParams.setMargins(dp(10), dp(10), dp(10), dp(10));
            root.addView(close, closeParams);

            card.addView(root);
            return new TabViewHolder(card, preview, title, activeBadge, close);
        }

        @Override
        public void onBindViewHolder(@NonNull TabViewHolder holder, int position) {
            WebView webView = host.getTabAt(position);
            if (webView == null) {
                holder.card.setVisibility(View.GONE);
                return;
            }
            holder.card.setVisibility(View.VISIBLE);

            Bitmap snapshot = host.getTabSnapshot(webView);
            if (snapshot != null && !snapshot.isRecycled()) {
                holder.preview.setImageBitmap(snapshot);
            } else {
                holder.preview.setImageDrawable(null);
                holder.preview.setBackgroundColor(Color.parseColor("#2A2A2A"));
            }

            holder.title.setText(safeTitle(webView));

            boolean isCurrent = position == host.getCurrentTabIndex();
            holder.activeBadge.setVisibility(isCurrent ? View.VISIBLE : View.GONE);
            holder.card.setStrokeColor(isCurrent ? Color.parseColor("#4A90E2") : Color.TRANSPARENT);

            holder.card.setOnClickListener(v -> {
                host.dismissCurrentKeyboard();
                host.switchToTab(position);
                host.refreshTabCount();
                dialog.dismiss();
            });

            holder.close.setOnClickListener(v -> {
                host.closeTabAt(position);
                host.refreshTabCount();
                notifyDataSetChanged();
            });
        }

        @Override
        public int getItemCount() {
            return Math.max(0, host.getTabCount());
        }

        private String safeTitle(WebView webView) {
            String title = webView.getTitle();
            if (title == null || title.trim().isEmpty()) {
                title = webView.getUrl();
            }
            if (title == null || title.trim().isEmpty()) {
                return "新しいタブ";
            }
            title = title.trim();
            if (title.length() > 48) {
                return title.substring(0, 45) + "...";
            }
            return title;
        }

        private int dp(int value) {
            float density = activity.getResources().getDisplayMetrics().density;
            return Math.round(value * density);
        }

        static final class TabViewHolder extends RecyclerView.ViewHolder {
            final MaterialCardView card;
            final ImageView preview;
            final TextView title;
            final TextView activeBadge;
            final ImageButton close;

            TabViewHolder(@NonNull View itemView, ImageView preview, TextView title,
                          TextView activeBadge, ImageButton close) {
                super(itemView);
                this.card = (MaterialCardView) itemView;
                this.preview = preview;
                this.title = title;
                this.activeBadge = activeBadge;
                this.close = close;
            }
        }
    }
}
