package com.example.cinestream;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

public final class GlassUi {

    private static final String ABOUT_DIALOG_TITLE = "About CineStream";
    private static final String CINESTREAM_REPOSITORY_URL =
            "https://github.com/exor-26/CineStream";
    private static final String CINESTREAM_DEVELOPER_NAME = "Aditya Singh";

    public interface ConfirmCallback {
        void onConfirm();
    }

    public interface InputCallback {
        void onConfirm(String value);
    }

    public interface ActionCallback {
        void onSelected(ActionItem item);
    }

    public static final class ActionItem {
        public final int id;
        public final String title;
        public final String subtitle;
        public final boolean selected;

        public ActionItem(int id, String title, String subtitle) {
            this(id, title, subtitle, false);
        }

        public ActionItem(int id, String title, String subtitle, boolean selected) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.selected = selected;
        }
    }

    public static final class InfoItem {
        public final String label;
        public final String value;

        public InfoItem(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    private GlassUi() {
    }

    @SuppressWarnings("deprecation")
    public static void showToast(Context context, String message) {
        View view = LayoutInflater.from(context).inflate(R.layout.view_glass_toast, null, false);
        TextView messageView = view.findViewById(R.id.toast_message);
        messageView.setText(message);

        Toast toast = new Toast(context.getApplicationContext());
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(context, 72));
        toast.setView(view);
        toast.show();
    }

    public static void showConfirmDialog(
            Context context,
            String title,
            String message,
            String positiveText,
            ConfirmCallback confirmCallback
    ) {
        Dialog dialog = buildDialog(context, R.layout.dialog_glass_message);
        TextView titleView = dialog.findViewById(R.id.dialog_title);
        TextView messageView = dialog.findViewById(R.id.dialog_message);
        TextView positiveButton = dialog.findViewById(R.id.dialog_positive);
        TextView negativeButton = dialog.findViewById(R.id.dialog_negative);

        titleView.setText(title);
        messageView.setText(message);
        positiveButton.setText(positiveText);

        negativeButton.setOnClickListener(v -> dialog.dismiss());
        positiveButton.setOnClickListener(v -> {
            dialog.dismiss();
            if (confirmCallback != null) {
                confirmCallback.onConfirm();
            }
        });

        dialog.show();
    }

    public static void showInputDialog(
            Context context,
            String title,
            String initialValue,
            String hint,
            String positiveText,
            InputCallback callback
    ) {
        Dialog dialog = buildDialog(context, R.layout.dialog_glass_input);
        TextView titleView = dialog.findViewById(R.id.dialog_title);
        EditText inputView = dialog.findViewById(R.id.dialog_input);
        TextView positiveButton = dialog.findViewById(R.id.dialog_positive);
        TextView negativeButton = dialog.findViewById(R.id.dialog_negative);

        titleView.setText(title);
        inputView.setText(initialValue);
        inputView.setHint(hint);
        inputView.setSelection(inputView.getText().length());
        positiveButton.setText(positiveText);

        negativeButton.setOnClickListener(v -> dialog.dismiss());
        positiveButton.setOnClickListener(v -> {
            dialog.dismiss();
            if (callback != null) {
                callback.onConfirm(inputView.getText().toString());
            }
        });

        dialog.show();
    }

    public static void showInfoDialog(Context context, String title, List<InfoItem> items) {
        // Keep Detailed Info generic, but render the app-level About surface with its dedicated,
        // deliberately sparse layout. MainActivity already routes its About button through this
        // title, so this avoids changing media-inspection presentation or metadata extraction.
        if (ABOUT_DIALOG_TITLE.equals(title)) {
            showAboutDialog(context);
            return;
        }

        Dialog dialog = buildDialog(context, R.layout.dialog_glass_info);
        TextView titleView = dialog.findViewById(R.id.dialog_title);
        ViewGroup container = dialog.findViewById(R.id.info_rows);
        TextView closeButton = dialog.findViewById(R.id.dialog_close);

        titleView.setText(title);
        LayoutInflater inflater = LayoutInflater.from(context);
        container.removeAllViews();

        for (InfoItem item : items) {
            View row = inflater.inflate(R.layout.item_glass_info_row, container, false);
            TextView labelView = row.findViewById(R.id.info_label);
            TextView valueView = row.findViewById(R.id.info_value);
            labelView.setText(item.label);
            valueView.setText(item.value);
            container.addView(row);
        }

        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private static void showAboutDialog(Context context) {
        Dialog dialog = buildDialog(context, R.layout.dialog_glass_about);
        TextView titleView = dialog.findViewById(R.id.dialog_title);
        TextView productView = dialog.findViewById(R.id.about_product_name);
        TextView versionView = dialog.findViewById(R.id.about_version);
        TextView developerView = dialog.findViewById(R.id.about_developer_name);
        View repositoryView = dialog.findViewById(R.id.about_repository);
        ScrollView scrollView = dialog.findViewById(R.id.about_scroll);
        View closeButton = dialog.findViewById(R.id.dialog_close);

        titleView.setText(ABOUT_DIALOG_TITLE);
        productView.setText("CineStream");
        versionView.setText("Version " + appVersionName(context));
        developerView.setText(CINESTREAM_DEVELOPER_NAME);
        repositoryView.setContentDescription("View CineStream source code on GitHub");
        repositoryView.setOnClickListener(v ->
                openExternalUri(context, CINESTREAM_REPOSITORY_URL));
        closeButton.setOnClickListener(v -> dialog.dismiss());
        scrollView.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        dialog.show();
        sizeAboutDialog(context, dialog);
        scrollView.postDelayed(() -> {
            scrollView.fullScroll(View.FOCUS_UP);
            scrollView.scrollTo(0, 0);
            scrollView.requestFocus();
            scrollView.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        }, 120L);
    }

    private static String appVersionName(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    context.getPackageName(),
                    0
            );
            return packageInfo.versionName != null ? packageInfo.versionName : "—";
        } catch (PackageManager.NameNotFoundException ignored) {
            return "—";
        }
    }

    private static void openExternalUri(Context context, String uriText) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(uriText));
        intent.addCategory(Intent.CATEGORY_BROWSABLE);
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        try {
            context.startActivity(intent);
        } catch (ActivityNotFoundException | SecurityException ignored) {
            showToast(context, "No browser is available to open this link.");
        }
    }

    public static void showActionSheet(
            Context context,
            String title,
            List<ActionItem> items,
            ActionCallback callback
    ) {
        BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.GlassBottomSheetDialogTheme);
        View view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_glass_actions, null, false);
        TextView titleView = view.findViewById(R.id.sheet_title);
        RecyclerView recyclerView = view.findViewById(R.id.sheet_actions);
        TextView closeButton = view.findViewById(R.id.sheet_close);

        titleView.setText(title);
        recyclerView.setLayoutManager(new LinearLayoutManager(context));
        recyclerView.setAdapter(new ActionAdapter(items, item -> {
            dialog.dismiss();
            if (callback != null) {
                callback.onSelected(item);
            }
        }));
        closeButton.setOnClickListener(v -> dialog.dismiss());

        dialog.setContentView(view);
        View bottomSheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (bottomSheet != null) {
            bottomSheet.setBackground(new ColorDrawable(Color.TRANSPARENT));
        }
        if (dialog.getWindow() != null) {
            applyFrostedWindow(context, dialog.getWindow());
        }
        dialog.show();

        if (context.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE) {
            FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if (sheet != null) {
                sheet.post(() -> {
                    BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
                    behavior.setSkipCollapsed(true);
                    behavior.setFitToContents(true);
                    behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
                });
            }
        }
    }

    public static void showDualActionSheet(
            Context context,
            String title,
            String leftTitle,
            List<ActionItem> leftItems,
            ActionCallback leftCallback,
            String rightTitle,
            List<ActionItem> rightItems,
            ActionCallback rightCallback
    ) {
        BottomSheetDialog dialog = new BottomSheetDialog(context, R.style.GlassBottomSheetDialogTheme);
        View view = LayoutInflater.from(context).inflate(R.layout.bottom_sheet_glass_dual_actions, null, false);
        TextView titleView = view.findViewById(R.id.sheet_title);
        TextView leftTitleView = view.findViewById(R.id.left_title);
        TextView rightTitleView = view.findViewById(R.id.right_title);
        RecyclerView leftRecyclerView = view.findViewById(R.id.left_actions);
        RecyclerView rightRecyclerView = view.findViewById(R.id.right_actions);
        TextView closeButton = view.findViewById(R.id.sheet_close);

        titleView.setText(title);
        leftTitleView.setText(leftTitle);
        rightTitleView.setText(rightTitle);

        leftRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        leftRecyclerView.setNestedScrollingEnabled(true);
        leftRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        leftRecyclerView.setAdapter(new ActionAdapter(leftItems, item -> {
            dialog.dismiss();
            if (leftCallback != null) {
                leftCallback.onSelected(item);
            }
        }));

        rightRecyclerView.setLayoutManager(new LinearLayoutManager(context));
        rightRecyclerView.setNestedScrollingEnabled(true);
        rightRecyclerView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rightRecyclerView.setAdapter(new ActionAdapter(rightItems, item -> {
            dialog.dismiss();
            if (rightCallback != null) {
                rightCallback.onSelected(item);
            }
        }));

        closeButton.setOnClickListener(v -> dialog.dismiss());
        dialog.setContentView(view);
        FrameLayout sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            sheet.setBackground(new ColorDrawable(Color.TRANSPARENT));
        }
        if (dialog.getWindow() != null) {
            applyFrostedWindow(context, dialog.getWindow());
        }
        dialog.show();

        if (sheet != null) {
            BottomSheetBehavior<FrameLayout> behavior = BottomSheetBehavior.from(sheet);
            behavior.setSkipCollapsed(true);
            behavior.setFitToContents(true);
            behavior.setHideable(true);
            behavior.setDraggable(true);

            installBottomSheetScrollArbitration(context, leftRecyclerView, behavior);
            installBottomSheetScrollArbitration(context, rightRecyclerView, behavior);

            // Start fully expanded in every orientation so the track lists, not a partially
            // collapsed parent sheet, receive the first vertical gesture.
            sheet.post(() -> behavior.setState(BottomSheetBehavior.STATE_EXPANDED));
        }
    }

    private static void installBottomSheetScrollArbitration(
            Context context,
            RecyclerView recyclerView,
            BottomSheetBehavior<FrameLayout> behavior
    ) {
        final int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        recyclerView.setOnTouchListener(new View.OnTouchListener() {
            private float downY;
            private boolean directionResolved;
            private boolean childOwnsGesture = true;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downY = event.getY();
                        directionResolved = false;
                        childOwnsGesture = true;
                        // Protect the new gesture until its direction crosses touch slop. This is
                        // what prevents a fresh upward drag from being captured by the sheet.
                        behavior.setDraggable(false);
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float deltaY = event.getY() - downY;
                        if (!directionResolved && Math.abs(deltaY) >= touchSlop) {
                            directionResolved = true;
                            childOwnsGesture = shouldActionListOwnVerticalGesture(
                                    deltaY,
                                    touchSlop,
                                    recyclerView.canScrollVertically(-1)
                            );
                            behavior.setDraggable(!childOwnsGesture);
                            view.getParent().requestDisallowInterceptTouchEvent(childOwnsGesture);
                        } else if (directionResolved) {
                            // Ownership stays fixed for the rest of this gesture. If a downward
                            // drag began while the list was not at the top, reaching the top later
                            // does not hand the same gesture to the parent unexpectedly.
                            behavior.setDraggable(!childOwnsGesture);
                            view.getParent().requestDisallowInterceptTouchEvent(childOwnsGesture);
                        }
                        break;

                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        behavior.setDraggable(true);
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        directionResolved = false;
                        childOwnsGesture = true;
                        break;

                    default:
                        break;
                }
                // RecyclerView still receives the event for normal scrolling and item selection.
                return false;
            }
        });
    }

    static boolean shouldActionListOwnVerticalGesture(
            float deltaY,
            int touchSlop,
            boolean canScrollUp
    ) {
        if (Math.abs(deltaY) < touchSlop) {
            return true;
        }
        // Finger moving upward always belongs to the list. Finger moving downward belongs to the
        // list until it is genuinely at the top; only then may the sheet handle dismissal.
        return deltaY < 0f || canScrollUp;
    }

    private static Dialog buildDialog(Context context, int layoutRes) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutRes);
        dialog.setCancelable(true);

        Window window = dialog.getWindow();
        if (window != null) {
            applyFrostedWindow(context, window);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.copyFrom(window.getAttributes());
            params.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
        return dialog;
    }

    private static void sizeAboutDialog(Context context, Dialog dialog) {
        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }
        int displayWidth = context.getResources().getDisplayMetrics().widthPixels;
        int displayHeight = context.getResources().getDisplayMetrics().heightPixels;
        WindowManager.LayoutParams params = window.getAttributes();
        params.width = Math.min(Math.round(displayWidth * 0.92f), dp(context, 640));
        params.height = Math.round(displayHeight * (
                context.getResources().getConfiguration().orientation
                        == Configuration.ORIENTATION_LANDSCAPE ? 0.92f : 0.86f));
        window.setAttributes(params);
    }

    private static void applyFrostedWindow(Context context, Window window) {
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        WindowManager.LayoutParams params = window.getAttributes();
        params.dimAmount = 0.28f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(dp(context, 32));
            window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
            params.setBlurBehindRadius(dp(context, 18));
        }
        window.setAttributes(params);
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density);
    }

    private static final class ActionAdapter extends RecyclerView.Adapter<ActionAdapter.ActionViewHolder> {
        private final List<ActionItem> items;
        private final ActionCallback callback;

        private ActionAdapter(List<ActionItem> items, ActionCallback callback) {
            this.items = items;
            this.callback = callback;
        }

        @NonNull
        @Override
        public ActionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_glass_action, parent, false);
            return new ActionViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull ActionViewHolder holder, int position) {
            ActionItem item = items.get(position);
            holder.titleView.setText(item.title);
            holder.itemView.setActivated(item.selected);
            holder.selectedView.setVisibility(item.selected ? View.VISIBLE : View.GONE);
            holder.titleView.setTextColor(ContextCompat.getColor(
                    holder.itemView.getContext(),
                    item.selected ? R.color.glass_text_selected : R.color.glass_text_primary
            ));
            holder.subtitleView.setTextColor(ContextCompat.getColor(
                    holder.itemView.getContext(),
                    item.selected ? R.color.glass_text_accent : R.color.glass_text_secondary
            ));
            if (TextUtils.isEmpty(item.subtitle)) {
                holder.subtitleView.setVisibility(View.GONE);
            } else {
                holder.subtitleView.setVisibility(View.VISIBLE);
                holder.subtitleView.setText(item.subtitle);
            }
            holder.itemView.setOnClickListener(v -> callback.onSelected(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static final class ActionViewHolder extends RecyclerView.ViewHolder {
            private final TextView titleView;
            private final TextView subtitleView;
            private final ImageView selectedView;

            ActionViewHolder(@NonNull View itemView) {
                super(itemView);
                titleView = itemView.findViewById(R.id.action_title);
                subtitleView = itemView.findViewById(R.id.action_subtitle);
                selectedView = itemView.findViewById(R.id.action_selected);
            }
        }
    }
}
