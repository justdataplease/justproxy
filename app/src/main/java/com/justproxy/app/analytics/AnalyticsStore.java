package com.justproxy.app.analytics;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Persistent, privacy-limited analytics for proxy traffic and completed sessions. */
public final class AnalyticsStore extends SQLiteOpenHelper {
    public static final int DEFAULT_MAX_SESSION_HISTORY = 5_000;
    public static final int DEFAULT_MAX_IP_HISTORY = 500;

    private static final String DATABASE_NAME = "justproxy_analytics.db";
    private static final int DATABASE_VERSION = 1;
    private static final int MAX_DAILY_ROWS = 400;
    private static final long TOTALS_ROW_ID = 1L;

    private static final String TABLE_SESSIONS = "proxy_sessions";
    private static final String TABLE_IP = "public_ip_observations";
    private static final String TABLE_TOTALS = "analytics_totals";
    private static final String TABLE_DAILY = "daily_traffic";

    private final int maxSessionHistory;
    private final int maxIpHistory;

    public AnalyticsStore(Context context) {
        this(context, DEFAULT_MAX_SESSION_HISTORY, DEFAULT_MAX_IP_HISTORY);
    }

    /** Allows a caller to select smaller bounded histories; aggregate totals are not pruned. */
    public AnalyticsStore(Context context, int maxSessionHistory, int maxIpHistory) {
        super(context.getApplicationContext(), DATABASE_NAME, null, DATABASE_VERSION);
        if (maxSessionHistory < 0 || maxIpHistory < 0) {
            throw new IllegalArgumentException("Retention limits cannot be negative");
        }
        this.maxSessionHistory = maxSessionHistory;
        this.maxIpHistory = maxIpHistory;
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE " + TABLE_SESSIONS + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "started_at_ms INTEGER NOT NULL,"
                        + "ended_at_ms INTEGER NOT NULL,"
                        + "client_address TEXT NOT NULL,"
                        + "protocol TEXT NOT NULL,"
                        + "target_endpoint TEXT NOT NULL,"
                        + "uploaded_bytes INTEGER NOT NULL,"
                        + "downloaded_bytes INTEGER NOT NULL,"
                        + "outcome TEXT NOT NULL"
                        + ")");
        database.execSQL(
                "CREATE INDEX sessions_by_end ON " + TABLE_SESSIONS
                        + " (ended_at_ms DESC, _id DESC)");

        database.execSQL(
                "CREATE TABLE " + TABLE_IP + " ("
                        + "_id INTEGER PRIMARY KEY AUTOINCREMENT,"
                        + "observed_at_ms INTEGER NOT NULL,"
                        + "ip_address TEXT NOT NULL,"
                        + "changed_from_previous INTEGER NOT NULL"
                        + ")");
        database.execSQL(
                "CREATE INDEX ips_by_time ON " + TABLE_IP
                        + " (observed_at_ms DESC, _id DESC)");

        database.execSQL(
                "CREATE TABLE " + TABLE_TOTALS + " ("
                        + "singleton_id INTEGER PRIMARY KEY CHECK(singleton_id = 1),"
                        + "session_count INTEGER NOT NULL DEFAULT 0,"
                        + "uploaded_bytes INTEGER NOT NULL DEFAULT 0,"
                        + "downloaded_bytes INTEGER NOT NULL DEFAULT 0,"
                        + "ip_observation_count INTEGER NOT NULL DEFAULT 0,"
                        + "ip_change_count INTEGER NOT NULL DEFAULT 0,"
                        + "last_public_ip TEXT,"
                        + "last_public_ip_at_ms INTEGER NOT NULL DEFAULT 0"
                        + ")");

        database.execSQL(
                "CREATE TABLE " + TABLE_DAILY + " ("
                        + "day_key TEXT PRIMARY KEY,"
                        + "session_count INTEGER NOT NULL DEFAULT 0,"
                        + "uploaded_bytes INTEGER NOT NULL DEFAULT 0,"
                        + "downloaded_bytes INTEGER NOT NULL DEFAULT 0"
                        + ")");
        ensureTotalsRow(database);
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        // Version 1 is the initial schema. Future migrations must preserve the rollup tables.
    }

    public synchronized long recordSession(ProxySessionRecord session) {
        return recordSessionInternal(session, true);
    }

    /**
     * Persists a completed session's bounded detail and increments session counts only.
     *
     * <p>Use this method when the session's bytes have already been persisted through
     * {@link #recordTrafficDelta(long, long, long)}. The final byte counts remain available in
     * recent-session detail, but are deliberately not added to aggregate byte totals again.</p>
     */
    public synchronized long recordCompletedSessionMetadata(ProxySessionRecord session) {
        return recordSessionInternal(session, false);
    }

    public long recordCompletedSessionMetadata(
            long startedAtMillis,
            long endedAtMillis,
            String clientAddress,
            String protocol,
            String target,
            long uploadedBytes,
            long downloadedBytes,
            String result) {
        return recordCompletedSessionMetadata(new ProxySessionRecord(
                startedAtMillis,
                endedAtMillis,
                clientAddress,
                protocol,
                target,
                uploadedBytes,
                downloadedBytes,
                result));
    }

    /**
     * Transactionally adds newly observed traffic to lifetime and local-day byte rollups.
     *
     * <p>The arguments must be deltas since the previous successful checkpoint, never cumulative
     * counters. A zero/zero checkpoint is accepted as a no-op and does not create a daily row.</p>
     */
    public synchronized void recordTrafficDelta(
            long uploadedBytes, long downloadedBytes, long recordedAtMillis) {
        AnalyticsRollup rollup = AnalyticsRollup.trafficDelta(
                uploadedBytes, downloadedBytes, recordedAtMillis, ZoneId.systemDefault());
        if (rollup.isEmpty()) {
            return;
        }

        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            ensureTotalsRow(database);
            applyRollup(database, rollup);
            trimDailyRows(database);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private long recordSessionInternal(ProxySessionRecord session, boolean includeBytesInRollup) {
        AnalyticsRollup rollup = AnalyticsRollup.completedSession(session, includeBytesInRollup);

        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            ensureTotalsRow(database);
            ContentValues values = new ContentValues();
            values.put("started_at_ms", session.getStartedAtMillis());
            values.put("ended_at_ms", session.getEndedAtMillis());
            values.put("client_address", session.getClientAddress());
            values.put("protocol", session.getProtocol());
            values.put("target_endpoint", session.getTarget());
            values.put("uploaded_bytes", session.getUploadedBytes());
            values.put("downloaded_bytes", session.getDownloadedBytes());
            values.put("outcome", session.getResult());
            long rowId = database.insertOrThrow(TABLE_SESSIONS, null, values);

            applyRollup(database, rollup);

            trimTable(database, TABLE_SESSIONS, maxSessionHistory);
            trimDailyRows(database);
            database.setTransactionSuccessful();
            return rowId;
        } finally {
            database.endTransaction();
        }
    }

    public long recordSession(
            long startedAtMillis,
            long endedAtMillis,
            String clientAddress,
            String protocol,
            String target,
            long uploadedBytes,
            long downloadedBytes,
            String result) {
        return recordSession(new ProxySessionRecord(
                startedAtMillis,
                endedAtMillis,
                clientAddress,
                protocol,
                target,
                uploadedBytes,
                downloadedBytes,
                result));
    }

    public long recordPublicIp(String ipAddress) {
        return recordPublicIp(ipAddress, System.currentTimeMillis());
    }

    public synchronized long recordPublicIp(String ipAddress, long observedAtMillis) {
        if (observedAtMillis < 0L) {
            throw new IllegalArgumentException("Observation timestamp is invalid");
        }
        String normalizedIp = IpAddressValidator.normalize(ipAddress);

        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            ensureTotalsRow(database);
            String previousIp = null;
            try (Cursor cursor = database.query(
                    TABLE_TOTALS,
                    new String[]{"last_public_ip"},
                    "singleton_id = 1",
                    null,
                    null,
                    null,
                    null)) {
                if (cursor.moveToFirst() && !cursor.isNull(0)) {
                    previousIp = cursor.getString(0);
                }
            }
            boolean changed = previousIp != null && !previousIp.equals(normalizedIp);

            ContentValues values = new ContentValues();
            values.put("observed_at_ms", observedAtMillis);
            values.put("ip_address", normalizedIp);
            values.put("changed_from_previous", changed ? 1 : 0);
            long rowId = database.insertOrThrow(TABLE_IP, null, values);

            database.execSQL(
                    "UPDATE " + TABLE_TOTALS + " SET "
                            + "ip_observation_count = ip_observation_count + 1,"
                            + "ip_change_count = ip_change_count + ?,"
                            + "last_public_ip = ?,"
                            + "last_public_ip_at_ms = ? "
                            + "WHERE singleton_id = 1",
                    new Object[]{changed ? 1 : 0, normalizedIp, observedAtMillis});

            trimTable(database, TABLE_IP, maxIpHistory);
            database.setTransactionSuccessful();
            return rowId;
        } finally {
            database.endTransaction();
        }
    }

    public AnalyticsSummary getSummary() {
        return getSummary(System.currentTimeMillis());
    }

    /** The overload accepting time is useful for deterministic rendering/tests around midnight. */
    public synchronized AnalyticsSummary getSummary(long nowMillis) {
        if (nowMillis < 0L) {
            throw new IllegalArgumentException("Current timestamp is invalid");
        }
        SQLiteDatabase database = getWritableDatabase();
        ensureTotalsRow(database);

        long lifetimeSessions = 0L;
        long lifetimeUploaded = 0L;
        long lifetimeDownloaded = 0L;
        long ipObservations = 0L;
        long ipChanges = 0L;
        String currentIp = null;
        long currentIpAt = 0L;
        try (Cursor cursor = database.query(
                TABLE_TOTALS,
                new String[]{
                        "session_count",
                        "uploaded_bytes",
                        "downloaded_bytes",
                        "ip_observation_count",
                        "ip_change_count",
                        "last_public_ip",
                        "last_public_ip_at_ms"
                },
                "singleton_id = 1",
                null,
                null,
                null,
                null)) {
            if (cursor.moveToFirst()) {
                lifetimeSessions = cursor.getLong(0);
                lifetimeUploaded = cursor.getLong(1);
                lifetimeDownloaded = cursor.getLong(2);
                ipObservations = cursor.getLong(3);
                ipChanges = cursor.getLong(4);
                currentIp = cursor.isNull(5) ? null : cursor.getString(5);
                currentIpAt = cursor.getLong(6);
            }
        }

        long todaySessions = 0L;
        long todayUploaded = 0L;
        long todayDownloaded = 0L;
        try (Cursor cursor = database.query(
                TABLE_DAILY,
                new String[]{"session_count", "uploaded_bytes", "downloaded_bytes"},
                "day_key = ?",
                new String[]{AnalyticsRollup.dayKey(nowMillis, ZoneId.systemDefault())},
                null,
                null,
                null)) {
            if (cursor.moveToFirst()) {
                todaySessions = cursor.getLong(0);
                todayUploaded = cursor.getLong(1);
                todayDownloaded = cursor.getLong(2);
            }
        }

        return new AnalyticsSummary(
                lifetimeSessions,
                lifetimeUploaded,
                lifetimeDownloaded,
                todaySessions,
                todayUploaded,
                todayDownloaded,
                ipObservations,
                ipChanges,
                currentIp,
                currentIpAt);
    }

    /** Returns newest first. The returned list never exceeds the configured retention limit. */
    public synchronized List<ProxySessionRecord> getRecentSessions(int limit) {
        int boundedLimit = boundedQueryLimit(limit, maxSessionHistory);
        if (boundedLimit == 0) {
            return Collections.emptyList();
        }

        List<ProxySessionRecord> sessions = new ArrayList<>(boundedLimit);
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_SESSIONS,
                new String[]{
                        "_id",
                        "started_at_ms",
                        "ended_at_ms",
                        "client_address",
                        "protocol",
                        "target_endpoint",
                        "uploaded_bytes",
                        "downloaded_bytes",
                        "outcome"
                },
                null,
                null,
                null,
                null,
                "ended_at_ms DESC, _id DESC",
                Integer.toString(boundedLimit))) {
            while (cursor.moveToNext()) {
                sessions.add(new ProxySessionRecord(
                        cursor.getLong(0),
                        cursor.getLong(1),
                        cursor.getLong(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getString(5),
                        cursor.getLong(6),
                        cursor.getLong(7),
                        cursor.getString(8)));
            }
        }
        return Collections.unmodifiableList(sessions);
    }

    /** Returns newest first, including whether each observation was an actual change. */
    public synchronized List<PublicIpObservation> getPublicIpHistory(int limit) {
        int boundedLimit = boundedQueryLimit(limit, maxIpHistory);
        if (boundedLimit == 0) {
            return Collections.emptyList();
        }

        List<PublicIpObservation> observations = new ArrayList<>(boundedLimit);
        try (Cursor cursor = getReadableDatabase().query(
                TABLE_IP,
                new String[]{"_id", "observed_at_ms", "ip_address", "changed_from_previous"},
                null,
                null,
                null,
                null,
                "observed_at_ms DESC, _id DESC",
                Integer.toString(boundedLimit))) {
            while (cursor.moveToNext()) {
                observations.add(new PublicIpObservation(
                        cursor.getLong(0),
                        cursor.getLong(1),
                        cursor.getString(2),
                        cursor.getInt(3) != 0));
            }
        }
        return Collections.unmodifiableList(observations);
    }

    /** Re-applies the configured detail-history bounds without changing aggregate totals. */
    public synchronized void trimHistory() {
        trimHistory(maxSessionHistory, maxIpHistory);
    }

    /**
     * Removes old detail rows while preserving lifetime/today rollups. Values may only make the
     * configured bounds stricter, preventing this method from making storage unbounded.
     */
    public synchronized void trimHistory(int sessionsToKeep, int ipObservationsToKeep) {
        if (sessionsToKeep < 0 || ipObservationsToKeep < 0) {
            throw new IllegalArgumentException("Retention limits cannot be negative");
        }
        int boundedSessions = Math.min(sessionsToKeep, maxSessionHistory);
        int boundedIps = Math.min(ipObservationsToKeep, maxIpHistory);
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            trimTable(database, TABLE_SESSIONS, boundedSessions);
            trimTable(database, TABLE_IP, boundedIps);
            trimDailyRows(database);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    /** Permanently resets detailed history, daily values, lifetime totals, and current-IP state. */
    public synchronized void clearAll() {
        SQLiteDatabase database = getWritableDatabase();
        database.beginTransaction();
        try {
            database.delete(TABLE_SESSIONS, null, null);
            database.delete(TABLE_IP, null, null);
            database.delete(TABLE_DAILY, null, null);
            database.delete(TABLE_TOTALS, null, null);
            ensureTotalsRow(database);
            database.setTransactionSuccessful();
        } finally {
            database.endTransaction();
        }
    }

    private static int boundedQueryLimit(int requested, int configuredMaximum) {
        if (requested < 0) {
            throw new IllegalArgumentException("Limit cannot be negative");
        }
        return Math.min(requested, configuredMaximum);
    }

    private static void ensureTotalsRow(SQLiteDatabase database) {
        ContentValues values = new ContentValues();
        values.put("singleton_id", TOTALS_ROW_ID);
        database.insertWithOnConflict(
                TABLE_TOTALS, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void ensureDailyRow(SQLiteDatabase database, String dayKey) {
        ContentValues values = new ContentValues();
        values.put("day_key", dayKey);
        database.insertWithOnConflict(
                TABLE_DAILY, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static void applyRollup(SQLiteDatabase database, AnalyticsRollup rollup) {
        database.execSQL(
                "UPDATE " + TABLE_TOTALS + " SET "
                        + "session_count = session_count + ?,"
                        + "uploaded_bytes = uploaded_bytes + ?,"
                        + "downloaded_bytes = downloaded_bytes + ? "
                        + "WHERE singleton_id = 1",
                new Object[]{
                        rollup.getSessionCount(),
                        rollup.getUploadedBytes(),
                        rollup.getDownloadedBytes()
                });

        ensureDailyRow(database, rollup.getDayKey());
        database.execSQL(
                "UPDATE " + TABLE_DAILY + " SET "
                        + "session_count = session_count + ?,"
                        + "uploaded_bytes = uploaded_bytes + ?,"
                        + "downloaded_bytes = downloaded_bytes + ? "
                        + "WHERE day_key = ?",
                new Object[]{
                        rollup.getSessionCount(),
                        rollup.getUploadedBytes(),
                        rollup.getDownloadedBytes(),
                        rollup.getDayKey()
                });
    }

    private static void trimTable(SQLiteDatabase database, String table, int rowsToKeep) {
        if (rowsToKeep == 0) {
            database.delete(table, null, null);
            return;
        }
        database.execSQL(
                "DELETE FROM " + table + " WHERE _id NOT IN ("
                        + "SELECT _id FROM " + table + " ORDER BY _id DESC LIMIT "
                        + rowsToKeep + ")");
    }

    private static void trimDailyRows(SQLiteDatabase database) {
        database.execSQL(
                "DELETE FROM " + TABLE_DAILY + " WHERE day_key NOT IN ("
                        + "SELECT day_key FROM " + TABLE_DAILY + " ORDER BY day_key DESC LIMIT "
                        + MAX_DAILY_ROWS + ")");
    }
}
