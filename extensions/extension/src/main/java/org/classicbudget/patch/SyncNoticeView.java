package org.classicbudget.patch;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.AttributeSet;
import android.widget.TextView;

/** A full-width row that takes space only when the current budget needs attention. */
public final class SyncNoticeView extends TextView {
    private static final long REFRESH_MS = 30000;
    private final boolean reconnect;
    private boolean reading;
    private final Runnable refresh = this::refresh;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> refresh();

    public SyncNoticeView(Context context) { this(context, null); }

    public SyncNoticeView(Context context, AttributeSet attributes) {
        super(context, attributes);
        reconnect = "dropbox-reconnect".equals(getTag());
        setVisibility(GONE);
        setTextColor(0xFF452A00);
        setBackgroundColor(0xFFFFE6BA);
        setTextSize(15);
        setPadding(dp(16), dp(10), dp(16), dp(10));
        setMinHeight(dp(48));
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        setOnClickListener(view -> {
            if (reconnect) new DropboxProfile().a(context);
            else context.startActivity(new Intent(context, SyncStatusActivity.class));
        });
    }

    private int dp(int size) {
        return Math.round(getResources().getDisplayMetrics().density * size);
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        SyncHealth.prefs(getContext()).registerOnSharedPreferenceChangeListener(changed);
        refresh();
    }

    @Override protected void onDetachedFromWindow() {
        SyncHealth.prefs(getContext()).unregisterOnSharedPreferenceChangeListener(changed);
        removeCallbacks(refresh);
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) refresh();
        else removeCallbacks(refresh);
    }

    private void refresh() {
        if (reading || !isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
        reading = true;
        Context app = getContext().getApplicationContext();
        SyncHealth.READER.execute(() -> {
            String message = reconnect
                ? (SyncHealth.needsDropboxReconnect(app) ? PlusLang.tr(
                    "Dropbox needs a new sign-in. Tap to reconnect.",
                    "Dropbox wymaga ponownego logowania. Dotknij, aby połączyć konto.") : "")
                : SyncHealth.message(SyncHealth.snapshot(app));
            post(() -> {
                reading = false;
                if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE) return;
                if (!message.contentEquals(getText())) setText(message);
                setVisibility(message.isEmpty() ? GONE : VISIBLE);
                removeCallbacks(refresh);
                postDelayed(refresh, REFRESH_MS);
            });
        });
    }
}
