package com.justproxy.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.justproxy.app.analytics.AnalyticsStore;
import com.justproxy.app.analytics.AnalyticsSummary;
import com.justproxy.app.analytics.ProxySessionRecord;
import com.justproxy.app.analytics.PublicIpObservation;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class AnalyticsActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private AnalyticsStore store;
    private TextView summaryView;
    private LinearLayout ipList;
    private LinearLayout sessionList;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new AnalyticsStore(this);
        setContentView(buildContent());
        load();
    }

    private ScrollView buildContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(getColor(R.color.off_white));
        LinearLayout root = column();
        root.setPadding(dp(16), dp(18), dp(16), dp(32));
        scroll.addView(root);

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("Back");
        back.setOnClickListener(view -> finish());
        top.addView(back, new LinearLayout.LayoutParams(dp(88), dp(48)));
        TextView title = title("Traffic & IP history");
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.setMarginStart(dp(10));
        top.addView(title, titleParams);
        root.addView(top, matchWrap());

        LinearLayout summaryCard = card();
        summaryView = text("Loading...");
        summaryView.setTextSize(15);
        summaryCard.addView(summaryView, matchWrap());
        root.addView(summaryCard, cardParams());

        LinearLayout ipCard = card();
        ipCard.addView(title("Public IP observations"), matchWrap());
        TextView ipNote = caption("A change is counted only after a successful external IP check returns a different address.");
        ipNote.setPadding(0, dp(5), 0, dp(10));
        ipCard.addView(ipNote, matchWrap());
        ipList = column();
        ipCard.addView(ipList, matchWrap());
        root.addView(ipCard, cardParams());

        LinearLayout sessionsCard = card();
        sessionsCard.addView(title("Recent proxy sessions"), matchWrap());
        TextView privacy = caption("Only time, client, protocol, target host/port, byte counts, and result are stored. No payloads, auth headers, or full URLs.");
        privacy.setPadding(0, dp(5), 0, dp(10));
        sessionsCard.addView(privacy, matchWrap());
        sessionList = column();
        sessionsCard.addView(sessionList, matchWrap());
        root.addView(sessionsCard, cardParams());

        LinearLayout actions = new LinearLayout(this);
        Button refresh = new Button(this);
        refresh.setText("Refresh");
        refresh.setOnClickListener(view -> load());
        actions.addView(refresh, weighted());
        Button clear = new Button(this);
        clear.setText("Clear history");
        clear.setOnClickListener(view -> confirmClear());
        LinearLayout.LayoutParams clearParams = weighted();
        clearParams.setMarginStart(dp(8));
        actions.addView(clear, clearParams);
        root.addView(actions, matchWrap());
        return scroll;
    }

    private void load() {
        summaryView.setText("Loading...");
        worker.execute(() -> {
            AnalyticsSummary summary = store.getSummary();
            List<PublicIpObservation> ips = store.getPublicIpHistory(50);
            List<ProxySessionRecord> sessions = store.getRecentSessions(50);
            runOnUiThread(() -> render(summary, ips, sessions));
        });
    }

    private void render(AnalyticsSummary summary, List<PublicIpObservation> ips,
                        List<ProxySessionRecord> sessions) {
        summaryView.setText("Today  " + formatBytes(summary.getTodayTotalBytes())
                + " in " + summary.getTodaySessionCount() + " sessions\n"
                + "Lifetime  " + formatBytes(summary.getLifetimeTotalBytes())
                + " in " + summary.getLifetimeSessionCount() + " sessions\n"
                + "Upload  " + formatBytes(summary.getLifetimeUploadedBytes())
                + "    Download  " + formatBytes(summary.getLifetimeDownloadedBytes()) + "\n"
                + "Public IP changes  " + summary.getPublicIpChangeCount());

        ipList.removeAllViews();
        if (ips.isEmpty()) {
            ipList.addView(caption("No public IP checks recorded yet."), matchWrap());
        } else {
            for (PublicIpObservation observation : ips) {
                TextView row = text(observation.getIpAddress()
                        + (observation.isChangedFromPrevious() ? "  - changed" : "")
                        + "\n" + formatTime(observation.getObservedAtMillis()));
                row.setPadding(0, dp(8), 0, dp(8));
                ipList.addView(row, matchWrap());
            }
        }

        sessionList.removeAllViews();
        if (sessions.isEmpty()) {
            sessionList.addView(caption("No completed proxy sessions yet."), matchWrap());
        } else {
            for (ProxySessionRecord session : sessions) {
                TextView row = text(session.getProtocol() + "  " + session.getTarget()
                        + "\n" + formatTime(session.getStartedAtMillis())
                        + "  |  " + session.getClientAddress()
                        + "\nUp " + formatBytes(session.getUploadedBytes())
                        + "  Down " + formatBytes(session.getDownloadedBytes())
                        + "  |  " + session.getResult());
                row.setPadding(0, dp(9), 0, dp(9));
                sessionList.addView(row, matchWrap());
            }
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("Clear traffic and IP history?")
                .setMessage("This permanently removes all locally stored session metadata, byte totals, and public-IP observations.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear", (dialog, which) -> worker.execute(() -> {
                    store.clearAll();
                    runOnUiThread(this::load);
                })).show();
    }

    private LinearLayout card() {
        LinearLayout view = column();
        view.setPadding(dp(18), dp(16), dp(18), dp(16));
        view.setBackgroundResource(R.drawable.card_background);
        view.setElevation(dp(2));
        return view;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView title(String value) {
        TextView view = text(value);
        view.setTextColor(getColor(R.color.navy));
        view.setTextSize(20);
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private TextView caption(String value) {
        TextView view = text(value);
        view.setTextColor(getColor(R.color.slate));
        view.setTextSize(13);
        return view;
    }

    private TextView text(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(getColor(R.color.navy));
        view.setTextSize(14);
        view.setTextIsSelectable(true);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWrap();
        params.setMargins(0, dp(14), 0, 0);
        return params;
    }

    private LinearLayout.LayoutParams weighted() {
        return new LinearLayout.LayoutParams(0, dp(48), 1);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static String formatTime(long millis) {
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                .format(new Date(millis));
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1_000L) return bytes + " B";
        if (bytes < 1_000_000L) return String.format(Locale.ROOT, "%.1f KB", bytes / 1_000d);
        if (bytes < 1_000_000_000L) {
            return String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000d);
        }
        return String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000d);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        store.close();
        super.onDestroy();
    }
}
