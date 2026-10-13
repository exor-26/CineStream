package com.exor.cinestream;

import android.view.Gravity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.PopupWindow;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;

/** App settings entries; Media3 still owns its unchanged playback-speed picker. */
@UnstableApi
final class PlayerSettingsMenu implements AutoCloseable {
    private final UnifiedPlayerView playerView;
    private final PlayerControlsCoordinator coordinator;
    private final View anchor;
    private final View nativeSpeedPicker;
    private PopupWindow popup;
    private boolean closed;

    PlayerSettingsMenu(UnifiedPlayerView playerView, PlayerControlsCoordinator coordinator) {
        this.playerView = playerView;
        this.coordinator = coordinator;
        anchor = playerView.findViewById(R.id.btn_player_settings);
        nativeSpeedPicker = playerView.findViewById(androidx.media3.ui.R.id.exo_playback_speed);
        anchor.setOnClickListener(v -> show());
    }

    private void show() {
        if (closed || playerView.isPlayerLocked() || !anchor.isShown()) return;
        dismiss();
        View content = LayoutInflater.from(anchor.getContext()).inflate(R.layout.view_player_settings, null, false);
        View speed = content.findViewById(R.id.player_settings_speed);
        View lock = content.findViewById(R.id.player_settings_lock);
        Player player = playerView.getPlayer();
        boolean speedAvailable = player != null && player.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH);
        String currentSpeed = player == null ? "" : speedLabel(player.getPlaybackParameters().speed);
        bind(speed, R.string.player_speed_settings_label, currentSpeed, R.drawable.ic_speed_forward);
        bind(lock, R.string.lock, anchor.getResources().getString(R.string.screen_lock_description), R.drawable.ic_screen_lock);
        speed.setEnabled(speedAvailable);
        speed.setAlpha(speedAvailable ? 1 : .4f);
        speed.setOnClickListener(v -> {
            Player current = playerView.getPlayer();
            if (current == null || !current.isCommandAvailable(Player.COMMAND_SET_SPEED_AND_PITCH)) return;
            dismiss();
            // This hidden, laid-out native control shares the real Settings button's bounds.
            // No private adapters, reflection or recreated speed options are used.
            nativeSpeedPicker.performClick();
        });
        lock.setOnClickListener(v -> {
            dismiss();
            playerView.lockPlayer(anchor);
        });
        int gap = Math.round(8 * anchor.getResources().getDisplayMetrics().density);
        int preferredWidth = Math.round(224 * anchor.getResources().getDisplayMetrics().density);
        int width = Math.min(preferredWidth, Math.max(1, playerView.getWidth() - 4 * gap));
        content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Math.max(1, playerView.getHeight() - 4 * gap), View.MeasureSpec.AT_MOST));
        PopupWindow window = new PopupWindow(content, width, content.getMeasuredHeight(), true);
        popup = window;
        // The content already draws glass: do not stack a second tinted glass background.
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        window.setElevation(gap);
        window.setOutsideTouchable(true);
        window.setAnimationStyle(GlassPlayerButton.motionEnabled() ? R.style.CinePlayerSettingsPopup : 0);
        window.setOnDismissListener(() -> {
            if (popup == window) { popup = null; coordinator.menuOpen(false); }
        });
        coordinator.menuOpen(true);
        window.showAsDropDown(anchor, 0, -anchor.getHeight() - window.getHeight() - gap, Gravity.END);
    }

    private static void bind(View row, int label, String detail, int icon) {
        TextView title = row.findViewById(androidx.media3.ui.R.id.exo_main_text);
        TextView subtitle = row.findViewById(androidx.media3.ui.R.id.exo_sub_text);
        ImageView image = row.findViewById(androidx.media3.ui.R.id.exo_icon);
        title.setText(label);
        subtitle.setText(detail);
        subtitle.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
        image.setImageResource(icon);
    }

    private static String speedLabel(float speed) {
        return new java.text.DecimalFormat("0.##").format(speed) + "×";
    }

    void dismiss() { if (popup != null) popup.dismiss(); }

    @Override public void close() {
        closed = true;
        dismiss();
        anchor.setOnClickListener(null);
    }
}
