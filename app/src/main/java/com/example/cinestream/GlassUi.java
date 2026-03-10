package com.example.cinestream;

import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

public final class GlassUi {

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

        public ActionItem(int id, String title, String subtitle) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
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

    private static Dialog buildDialog(Context context, int layoutRes) {
        Dialog dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(layoutRes);
        dialog.setCancelable(true);

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            WindowManager.LayoutParams params = new WindowManager.LayoutParams();
            params.copyFrom(window.getAttributes());
            params.width = (int) (context.getResources().getDisplayMetrics().widthPixels * 0.92f);
            params.height = WindowManager.LayoutParams.WRAP_CONTENT;
            window.setAttributes(params);
        }
        return dialog;
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

            ActionViewHolder(@NonNull View itemView) {
                super(itemView);
                titleView = itemView.findViewById(R.id.action_title);
                subtitleView = itemView.findViewById(R.id.action_subtitle);
            }
        }
    }
}
