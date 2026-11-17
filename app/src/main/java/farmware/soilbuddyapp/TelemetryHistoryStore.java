package farmware.soilbuddyapp;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class TelemetryHistoryStore extends SQLiteOpenHelper {
    private static final String DATABASE_NAME = "soil_buddy_history.db";
    private static final int DATABASE_VERSION = 2;
    private static final String TABLE = "telemetry_samples";
    private static final long DAY = 24L * 60L * 60L * 1000L;
    private long lastMaintenanceDay = -1;

    public TelemetryHistoryStore(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        createTable(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            createTable(db);
            if (tableExists(db, "moisture_samples")) {
                db.execSQL("INSERT OR REPLACE INTO " + TABLE +
                        " (metric, granularity, bucket, time_millis, value)" +
                        " SELECT 'soil_moisture_percent', 1, minute_bucket, time_millis, percent FROM moisture_samples");
                db.execSQL("DROP TABLE moisture_samples");
            }
        }
    }

    private void createTable(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE +
                " (metric TEXT NOT NULL, granularity INTEGER NOT NULL, bucket INTEGER NOT NULL," +
                " time_millis INTEGER NOT NULL, value REAL NOT NULL," +
                " PRIMARY KEY(metric, granularity, bucket))");
        db.execSQL("CREATE INDEX IF NOT EXISTS telemetry_metric_time ON " + TABLE + " (metric, time_millis)");
    }

    private boolean tableExists(SQLiteDatabase db, String name) {
        try (Cursor cursor = db.rawQuery(
                "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", new String[]{name})) {
            return cursor.moveToFirst();
        }
    }

    /** Stores the latest value for each metric in a one-minute bucket. */
    public void recordPacket(long timeMillis, Map<String, Double> values) {
        SQLiteDatabase db = getWritableDatabase();
        long minuteBucket = Math.floorDiv(timeMillis, 60_000L);
        db.beginTransaction();
        try {
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                if (entry.getValue() == null || Double.isNaN(entry.getValue()) || Double.isInfinite(entry.getValue())) continue;
                ContentValues row = new ContentValues();
                row.put("metric", entry.getKey());
                row.put("granularity", 1);
                row.put("bucket", minuteBucket);
                row.put("time_millis", timeMillis);
                row.put("value", entry.getValue());
                db.insertWithOnConflict(TABLE, null, row, SQLiteDatabase.CONFLICT_REPLACE);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        maintainHistory(db, timeMillis);
    }

    public void maintainHistory(long now) {
        maintainHistory(getWritableDatabase(), now);
    }

    private void maintainHistory(SQLiteDatabase db, long now) {
        long today = Math.floorDiv(now, DAY);
        if (lastMaintenanceDay == today) return;
        long minuteCutoff = now - 31L * DAY;
        long hourCutoff = now - 90L * DAY;
        long yearCutoff = now - 365L * DAY;

        db.beginTransaction();
        try {
            db.execSQL("INSERT OR REPLACE INTO " + TABLE +
                    " (metric, granularity, bucket, time_millis, value)" +
                    " SELECT metric, 2, CAST(time_millis / 3600000 AS INTEGER)," +
                    " CAST(time_millis / 3600000 AS INTEGER) * 3600000 + 1800000, AVG(value)" +
                    " FROM " + TABLE + " WHERE granularity=1 AND time_millis<?" +
                    " GROUP BY metric, CAST(time_millis / 3600000 AS INTEGER)", new Object[]{minuteCutoff});
            db.delete(TABLE, "granularity=1 AND time_millis<?", new String[]{Long.toString(minuteCutoff)});

            db.execSQL("INSERT OR REPLACE INTO " + TABLE +
                    " (metric, granularity, bucket, time_millis, value)" +
                    " SELECT metric, 3, CAST(time_millis / 86400000 AS INTEGER),"+
                    " CAST(time_millis / 86400000 AS INTEGER) * 86400000 + 43200000, AVG(value)"+
                    " FROM " + TABLE + " WHERE granularity=2 AND time_millis<?"+
                    " GROUP BY metric, CAST(time_millis / 86400000 AS INTEGER)", new Object[]{hourCutoff});
            db.delete(TABLE, "granularity=2 AND time_millis<?", new String[]{Long.toString(hourCutoff)});
            db.delete(TABLE, "time_millis<?", new String[]{Long.toString(yearCutoff)});
            db.setTransactionSuccessful();
            lastMaintenanceDay = today;
        } finally {
            db.endTransaction();
        }
    }

    public List<MoistureChartView.Point> getSince(String metric, long startTimeMillis) {
        List<MoistureChartView.Point> points = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(
                TABLE,
                new String[]{"time_millis", "value"},
                "metric=? AND time_millis>=?",
                new String[]{metric, Long.toString(startTimeMillis)},
                null,
                null,
                "time_millis ASC")) {
            while (cursor.moveToNext()) {
                points.add(new MoistureChartView.Point(cursor.getLong(0), cursor.getFloat(1)));
            }
        }
        return points;
    }
}
