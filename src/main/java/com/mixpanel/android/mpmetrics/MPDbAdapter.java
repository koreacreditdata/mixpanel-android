package com.mixpanel.android.mpmetrics;

import java.io.File;
import java.io.FilenameFilter;
import java.util.HashMap;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import com.mixpanel.android.util.MPLog;

/**
 * SQLite database adapter for MixpanelAPI.
 *
 * <p>Not thread-safe. Instances of this class should only be used
 * by a single thread.
 *
 */
/* package */ class MPDbAdapter {
    private static final String LOGTAG = "MixpanelAPI.Database";
    private static final Map<Context, MPDbAdapter> sInstances = new HashMap<>();

    public enum Table {
        EVENTS ("events"),
        PEOPLE ("people"),
        ANONYMOUS_PEOPLE ("anonymous_people"),
        GROUPS ("groups");

        Table(String name) {
            mTableName = name;
        }

        public String getName() {
            return mTableName;
        }

        private final String mTableName;
    }

    public static final String KEY_DATA = "data";
    public static final String KEY_CREATED_AT = "created_at";
    public static final String KEY_AUTOMATIC_DATA = "automatic_data";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_SERVICE_NAME = "service_name";

    public static final int DB_UPDATE_ERROR = -1;
    public static final int DB_OUT_OF_MEMORY_ERROR = -2;
    public static final int DB_UNDEFINED_CODE = -3;

    private static final String DATABASE_NAME = "mixpanel";
    private static final int MIN_DB_VERSION = 4;

    // If you increment DATABASE_VERSION, don't forget to define migration
    private static final int DATABASE_VERSION = 8; // current database version
    private static final int MAX_DB_VERSION = 8; // Max database version onUpdate can migrate to.

    private static final String CREATE_EVENTS_TABLE =
       "CREATE TABLE " + Table.EVENTS.getName() + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
        KEY_DATA + " STRING NOT NULL, " +
        KEY_CREATED_AT + " INTEGER NOT NULL, " +
        KEY_AUTOMATIC_DATA + " INTEGER DEFAULT 0, " +
        KEY_SERVICE_NAME + " STRING NOT NULL DEFAULT '', " +
        KEY_TOKEN + " STRING NOT NULL DEFAULT '')";
    private static final String CREATE_PEOPLE_TABLE =
       "CREATE TABLE " + Table.PEOPLE.getName() + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
        KEY_DATA + " STRING NOT NULL, " +
        KEY_CREATED_AT + " INTEGER NOT NULL, " +
        KEY_AUTOMATIC_DATA + " INTEGER DEFAULT 0, " +
        KEY_SERVICE_NAME + " STRING NOT NULL DEFAULT '', " +
        KEY_TOKEN + " STRING NOT NULL DEFAULT '')";
    private static final String CREATE_GROUPS_TABLE =
            "CREATE TABLE " + Table.GROUPS.getName() + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    KEY_DATA + " STRING NOT NULL, " +
                    KEY_CREATED_AT + " INTEGER NOT NULL, " +
                    KEY_AUTOMATIC_DATA + " INTEGER DEFAULT 0, " +
                    KEY_SERVICE_NAME + " STRING NOT NULL DEFAULT '', " +
                    KEY_TOKEN + " STRING NOT NULL DEFAULT '')";
    private static final String CREATE_ANONYMOUS_PEOPLE_TABLE =
            "CREATE TABLE " + Table.ANONYMOUS_PEOPLE.getName() + " (_id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    KEY_DATA + " STRING NOT NULL, " +
                    KEY_CREATED_AT + " INTEGER NOT NULL, " +
                    KEY_AUTOMATIC_DATA + " INTEGER DEFAULT 0, " +
                    KEY_SERVICE_NAME + " STRING NOT NULL DEFAULT '', " +
                    KEY_TOKEN + " STRING NOT NULL DEFAULT '')";
    private static final String EVENTS_TIME_INDEX =
        "CREATE INDEX IF NOT EXISTS time_idx ON " + Table.EVENTS.getName() +
        " (" + KEY_CREATED_AT + ");";
    private static final String PEOPLE_TIME_INDEX =
        "CREATE INDEX IF NOT EXISTS time_idx ON " + Table.PEOPLE.getName() +
        " (" + KEY_CREATED_AT + ");";
    private static final String GROUPS_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS time_idx ON " + Table.GROUPS.getName() +
                    " (" + KEY_CREATED_AT + ");";
    private static final String ANONYMOUS_PEOPLE_TIME_INDEX =
            "CREATE INDEX IF NOT EXISTS time_idx ON " + Table.ANONYMOUS_PEOPLE.getName() +
                    " (" + KEY_CREATED_AT + ");";

    private final MPDatabaseHelper mDb;

    private static class MPDatabaseHelper extends SQLiteOpenHelper {
        MPDatabaseHelper(Context context, String dbName) {
            super(context, dbName, null, DATABASE_VERSION);
            mDatabaseFile = context.getDatabasePath(dbName);
            mConfig = MPConfig.getInstance(context);
            mContext = context;
        }

        /**
         * Completely deletes the DB file from the file system.
         */
        public void deleteDatabase() {
            close();
            mDatabaseFile.delete();
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            MPLog.v(LOGTAG, "Creating a new Mixpanel events DB");

            db.execSQL(CREATE_EVENTS_TABLE);
            db.execSQL(CREATE_PEOPLE_TABLE);
            db.execSQL(CREATE_GROUPS_TABLE);
            db.execSQL(CREATE_ANONYMOUS_PEOPLE_TABLE);
            db.execSQL(EVENTS_TIME_INDEX);
            db.execSQL(PEOPLE_TIME_INDEX);
            db.execSQL(GROUPS_TIME_INDEX);
            db.execSQL(ANONYMOUS_PEOPLE_TIME_INDEX);
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            MPLog.v(LOGTAG, "Upgrading app, replacing Mixpanel events DB");

            if (oldVersion >= MIN_DB_VERSION && newVersion <= MAX_DB_VERSION) {
                if (oldVersion == 4) {
                    migrateTableFrom4To5(db);
                    migrateTableFrom5To6(db);
                    migrateTableFrom6To7(db);
                    migrateTableFrom7To8(db);
                }

                if (oldVersion == 5) {
                    migrateTableFrom5To6(db);
                    migrateTableFrom6To7(db);
                    migrateTableFrom7To8(db);
                }

                if (oldVersion == 6) {
                    migrateTableFrom6To7(db);
                    migrateTableFrom7To8(db);
                }

                if (oldVersion == 7) {
                    migrateTableFrom7To8(db);
                }
            } else {
                db.execSQL("DROP TABLE IF EXISTS " + Table.EVENTS.getName());
                db.execSQL("DROP TABLE IF EXISTS " + Table.PEOPLE.getName());
                db.execSQL("DROP TABLE IF EXISTS " + Table.GROUPS.getName());
                db.execSQL("DROP TABLE IF EXISTS " + Table.ANONYMOUS_PEOPLE.getName());
                db.execSQL(CREATE_EVENTS_TABLE);
                db.execSQL(CREATE_PEOPLE_TABLE);
                db.execSQL(CREATE_GROUPS_TABLE);
                db.execSQL(CREATE_ANONYMOUS_PEOPLE_TABLE);
                db.execSQL(EVENTS_TIME_INDEX);
                db.execSQL(PEOPLE_TIME_INDEX);
                db.execSQL(GROUPS_TIME_INDEX);
                db.execSQL(ANONYMOUS_PEOPLE_TIME_INDEX);
            }
        }

        public boolean belowMemThreshold() {
            if (mDatabaseFile.exists()) {
                return Math.max(mDatabaseFile.getUsableSpace(), mConfig.getMinimumDatabaseLimit()) >= mDatabaseFile.length();
            }
            return true;
        }

        private void migrateTableFrom4To5(SQLiteDatabase db) {
            db.execSQL("ALTER TABLE " + Table.EVENTS.getName() + " ADD COLUMN " + KEY_AUTOMATIC_DATA + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + Table.PEOPLE.getName() + " ADD COLUMN " + KEY_AUTOMATIC_DATA + " INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + Table.EVENTS.getName() + " ADD COLUMN " + KEY_TOKEN + " STRING NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + Table.PEOPLE.getName() + " ADD COLUMN " + KEY_TOKEN + " STRING NOT NULL DEFAULT ''");

            Cursor eventsCursor = db.rawQuery("SELECT * FROM " + Table.EVENTS.getName(), null);
            while (eventsCursor.moveToNext()) {
                int rowId = 0;
                try {
                    final JSONObject j = new JSONObject(eventsCursor.getString(eventsCursor.getColumnIndex(KEY_DATA)));
                    String token = j.getJSONObject("prop").getString("token");
                    rowId = eventsCursor.getInt(eventsCursor.getColumnIndex("_id"));
                    db.execSQL("UPDATE " + Table.EVENTS.getName() + " SET " + KEY_TOKEN + " = '" + token + "' WHERE _id = " + rowId);
                } catch (final JSONException e) {
                    db.delete(Table.EVENTS.getName(), "_id = " + rowId, null);
                }
            }

            Cursor peopleCursor = db.rawQuery("SELECT * FROM " + Table.PEOPLE.getName(), null);
            while (peopleCursor.moveToNext()) {
                int rowId = 0;
                try {
                    final JSONObject j = new JSONObject(peopleCursor.getString(peopleCursor.getColumnIndex(KEY_DATA)));
                    String token = j.getString("$token");
                    rowId = peopleCursor.getInt(peopleCursor.getColumnIndex("_id"));
                    db.execSQL("UPDATE " + Table.PEOPLE.getName() + " SET " + KEY_TOKEN + " = '" + token + "' WHERE _id = " + rowId);
                } catch (final JSONException e) {
                    db.delete(Table.PEOPLE.getName(), "_id = " + rowId, null);
                }
            }
        }

        private void migrateTableFrom5To6(SQLiteDatabase db) {
            db.execSQL(CREATE_GROUPS_TABLE);
            db.execSQL(GROUPS_TIME_INDEX);
        }

        private void migrateTableFrom6To7(SQLiteDatabase db) {
            db.execSQL(CREATE_ANONYMOUS_PEOPLE_TABLE);
            db.execSQL(ANONYMOUS_PEOPLE_TIME_INDEX);

            File prefsDir = new File(mContext.getApplicationInfo().dataDir, "shared_prefs");

            if (prefsDir.exists() && prefsDir.isDirectory()) {
                String[] storedPrefsFiles = prefsDir.list(new FilenameFilter() {
                    @Override
                    public boolean accept(File dir, String name) {
                        return name.startsWith("com.mixpanel.android.mpmetrics.MixpanelAPI_");
                    }
                });

                for (String storedPrefFile : storedPrefsFiles) {
                    String storedPrefName = storedPrefFile.split("\\.xml")[0];
                    SharedPreferences s = mContext.getSharedPreferences(storedPrefName, Context.MODE_PRIVATE);
                    final String waitingPeopleUpdates = s.getString("waiting_array", null);
                    if (waitingPeopleUpdates != null) {
                        try {
                            JSONArray waitingObjects = new JSONArray(waitingPeopleUpdates);
                            db.beginTransaction();
                            try {
                                for (int i = 0; i < waitingObjects.length(); i++) {
                                    try {
                                        final JSONObject j = waitingObjects.getJSONObject(i);
                                        String token = j.getString("$token");

                                        final ContentValues cv = new ContentValues();
                                        cv.put(KEY_DATA, j.toString());
                                        cv.put(KEY_CREATED_AT, System.currentTimeMillis());
                                        cv.put(KEY_AUTOMATIC_DATA, false);
                                        cv.put(KEY_TOKEN, token);
                                        db.insert(Table.ANONYMOUS_PEOPLE.getName(), null, cv);
                                    } catch (JSONException e) {
                                        // ignore record
                                    }
                                }
                                db.setTransactionSuccessful();
                            } finally {
                                db.endTransaction();
                            }
                        } catch (JSONException e) {
                            // waiting array is corrupted. dismiss.
                        }

                        SharedPreferences.Editor e = s.edit();
                        e.remove("waiting_array");
                        e.apply();
                    }
                }
            }
        }

        private void migrateTableFrom7To8(SQLiteDatabase db) {
            db.execSQL("ALTER TABLE " + Table.EVENTS.getName() + " ADD COLUMN " + KEY_SERVICE_NAME + " STRING NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + Table.PEOPLE.getName() + " ADD COLUMN " + KEY_SERVICE_NAME + " STRING NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + Table.GROUPS.getName() + " ADD COLUMN " + KEY_SERVICE_NAME + " STRING NOT NULL DEFAULT ''");
            db.execSQL("ALTER TABLE " + Table.ANONYMOUS_PEOPLE.getName() + " ADD COLUMN " + KEY_SERVICE_NAME + " STRING NOT NULL DEFAULT ''");
        }

        private final File mDatabaseFile;
        private final MPConfig mConfig;
        private final Context mContext;
    }

    public MPDbAdapter(Context context) {
        this(context, DATABASE_NAME);
    }

    public MPDbAdapter(Context context, String dbName) {
        mDb = new MPDatabaseHelper(context, dbName);
    }

    public static MPDbAdapter getInstance(Context context) {
        synchronized (sInstances) {
            final Context appContext = context.getApplicationContext();
            MPDbAdapter ret;
            if (! sInstances.containsKey(appContext)) {
                ret = new MPDbAdapter(appContext);
                sInstances.put(appContext, ret);
            } else {
                ret = sInstances.get(appContext);
            }
            return ret;
        }
    }

    /**
     * Adds a JSON string representing an event with properties or a person record
     * to the SQLiteDatabase.
     * @param j the JSON to record
     * @param token token of the project
     * @param table the table to insert into, one of "events", "people", "groups" or "anonymous_people"
     * @param isAutomaticRecord mark the record as an automatic event or not
     * @return the number of rows in the table, or DB_OUT_OF_MEMORY_ERROR/DB_UPDATE_ERROR
     * on failure
     */
    public int addJSON(JSONObject j, String token, String serviceName, Table table, boolean isAutomaticRecord) {
        // we are aware of the race condition here, but what can we do..?
        if (!this.belowMemThreshold()) {
            MPLog.e(LOGTAG, "There is not enough space left on the device to store Mixpanel data, so data was discarded");
            return DB_OUT_OF_MEMORY_ERROR;
        }

        final String tableName = table.getName();

        Cursor c = null;
        int count = DB_UPDATE_ERROR;

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();

            final ContentValues cv = new ContentValues();
            cv.put(KEY_DATA, j.toString());
            cv.put(KEY_CREATED_AT, System.currentTimeMillis());
            cv.put(KEY_AUTOMATIC_DATA, isAutomaticRecord);
            cv.put(KEY_TOKEN, token);
            cv.put(KEY_SERVICE_NAME, serviceName);
            db.insert(tableName, null, cv);

            c = db.rawQuery("SELECT COUNT(*) FROM " + tableName + " WHERE token='" + token + "' "
                    + " AND service_name='" + serviceName + "'", null);
            c.moveToFirst();
            count = c.getInt(0);
        } catch (final SQLiteException e) {
            MPLog.e(LOGTAG, "Could not add Mixpanel data to table");

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            if (c != null) {
                c.close();
                c = null;
            }
            mDb.deleteDatabase();
        } catch (final OutOfMemoryError e) {
            MPLog.e(LOGTAG, "Out of memory when adding Mixpanel data to table");
        } finally {
            if (c != null) {
                c.close();
            }
            mDb.close();
        }
        return count;
    }

    /**
     * Copies anonymous people updates to people db after a user has been identified
     * @param token project token
     * @param distinctId people profile distinct id
     * @return the number of rows copied (anonymous updates), or DB_OUT_OF_MEMORY_ERROR/DB_UPDATE_ERROR
     * on failure
     */
    /* package */ int pushAnonymousUpdatesToPeopleDb(String token, String serviceName, String distinctId) {
        if (!this.belowMemThreshold()) {
            MPLog.e(LOGTAG, "There is not enough space left on the device to store Mixpanel data, so data was discarded");
            return DB_OUT_OF_MEMORY_ERROR;
        }
        Cursor selectCursor = null;
        int count = DB_UPDATE_ERROR;

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            StringBuffer allAnonymousQuery = new StringBuffer("SELECT * FROM " + Table.ANONYMOUS_PEOPLE.getName() + " WHERE "
                    + KEY_TOKEN + " = '" + token + "' AND "
                    + KEY_SERVICE_NAME + " = '" + serviceName + "'"
            );

            selectCursor = db.rawQuery(allAnonymousQuery.toString(), null);
            db.beginTransaction();
            try {
                while (selectCursor.moveToNext()) {
                    try {
                        ContentValues values = new ContentValues();
                        values.put(KEY_CREATED_AT, selectCursor.getLong(selectCursor.getColumnIndex(KEY_CREATED_AT)));
                        values.put(KEY_AUTOMATIC_DATA, selectCursor.getInt(selectCursor.getColumnIndex(KEY_AUTOMATIC_DATA)));
                        values.put(KEY_TOKEN, selectCursor.getString(selectCursor.getColumnIndex(KEY_TOKEN)));
                        values.put(KEY_SERVICE_NAME, selectCursor.getString(selectCursor.getColumnIndex(KEY_SERVICE_NAME)));

                        JSONObject updatedData = new JSONObject(selectCursor.getString(selectCursor.getColumnIndex(KEY_DATA)));
                        updatedData.put("$distinct_id", distinctId);
                        values.put(KEY_DATA, updatedData.toString());
                        db.insert(Table.PEOPLE.getName(), null, values);
                        int rowId = selectCursor.getInt(selectCursor.getColumnIndex("_id"));
                        db.delete(Table.ANONYMOUS_PEOPLE.getName(), "_id = " + rowId, null);
                        count++;
                    } catch (final JSONException e) {
                        // Ignore this object
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (final SQLiteException e) {
            MPLog.e(LOGTAG, "Could not push anonymous updates records from " + Table.ANONYMOUS_PEOPLE.getName() + ". Re-initializing database.", e);

            if (selectCursor != null) {
                selectCursor.close();
                selectCursor = null;
            }
            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            if (selectCursor != null) {
                selectCursor.close();
            }
            mDb.close();
        }

        return count;
    }

    /**
     * Copies anonymous people updates to people db after a user has been identified
     * @param properties Map of properties that will be added to existing events.
     * @param token project token
     * @return the number of rows updated , or DB_OUT_OF_MEMORY_ERROR/DB_UPDATE_ERROR
     * on failure
     */
    /* package */ int rewriteEventDataWithProperties(Map<String, String> properties, String token, String serviceName) {
        if (!this.belowMemThreshold()) {
            MPLog.e(LOGTAG, "There is not enough space left on the device to store Mixpanel data, so data was discarded");
            return DB_OUT_OF_MEMORY_ERROR;
        }
        Cursor selectCursor = null;
        int count = 0;

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            StringBuffer allAnonymousQuery = new StringBuffer("SELECT * FROM " + Table.EVENTS.getName() + " WHERE "
                    + KEY_TOKEN + " = '" + token + "' AND "
                    + KEY_SERVICE_NAME + " = '" + serviceName + "'"
            );

            selectCursor = db.rawQuery(allAnonymousQuery.toString(), null);
            db.beginTransaction();
            try {
                while (selectCursor.moveToNext()) {
                    try {
                        ContentValues values = new ContentValues();
                        JSONObject updatedData = new JSONObject(selectCursor.getString(selectCursor.getColumnIndex(KEY_DATA)));
                        JSONObject existingProps = updatedData.getJSONObject("prop");
                        for (final Map.Entry<String, String> entry : properties.entrySet()) {
                            final String key = entry.getKey();
                            final String value = entry.getValue();
                            existingProps.put(key, value);
                        }
                        updatedData.put("prop", existingProps);
                        values.put(KEY_DATA, updatedData.toString());

                        int rowId = selectCursor.getInt(selectCursor.getColumnIndex("_id"));
                        db.update(Table.EVENTS.getName(), values, "_id = " + rowId, null);
                        count++;
                    } catch (final JSONException e) {
                        // Ignore this object
                    }
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (final SQLiteException e) {
            MPLog.e(LOGTAG, "Could not re-write events history. Re-initializing database.", e);

            if (selectCursor != null) {
                selectCursor.close();
                selectCursor = null;
            }
            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            if (selectCursor != null) {
                selectCursor.close();
            }
            mDb.close();
        }

        return count;
    }

    /**
     * Removes events with an _id <= last_id from table
     * @param last_id the last id to delete
     * @param table the table to remove events from, one of "events", "people", "groups" or "anonymous_people"
     * @param includeAutomaticEvents whether or not automatic events should be included in the cleanup
     */
    public void cleanupEvents(String last_id, Table table, String token, String serviceName, boolean includeAutomaticEvents) {
        final String tableName = table.getName();

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            StringBuffer deleteQuery = new StringBuffer("_id <= " + last_id + " AND "
                    + KEY_TOKEN + " = '" + token + "' AND "
                    + KEY_SERVICE_NAME + " = '" + serviceName + "'");

            if (!includeAutomaticEvents) {
                deleteQuery.append(" AND " + KEY_AUTOMATIC_DATA + "=0");
            }
            db.delete(tableName, deleteQuery.toString(), null);
        } catch (final SQLiteException e) {
            MPLog.e(LOGTAG, "Could not clean sent Mixpanel records from " + tableName + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } catch (final Exception e) {
            MPLog.e(LOGTAG, "Unknown exception. Could not clean sent Mixpanel records from " + tableName + ".Re-initializing database.", e);
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    /**
     * Removes events before time.
     * @param time the unix epoch in milliseconds to remove events before
     * @param table the table to remove events from, one of "events", "people", "groups" or "anonymous_people"
     */
    public void cleanupEvents(long time, Table table) {
        final String tableName = table.getName();

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            db.delete(tableName, KEY_CREATED_AT + " <= " + time, null);
        } catch (final SQLiteException e) {
            MPLog.e(LOGTAG, "Could not clean timed-out Mixpanel records from " + tableName + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    /**
     * Removes all events given a project token.
     * @param table the table to remove events from, one of "events", "people", "groups" or "anonymous_people"
     * @param token token of the project to remove events from
     */
    public void cleanupAllEvents(Table table, String token, String serviceName) {
        final String tableName = table.getName();

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            db.delete(tableName, KEY_TOKEN + " = '" + token + "' AND "
                    + KEY_SERVICE_NAME + " = '" + serviceName + "'", null);
        } catch (final SQLiteException e) {
            MPLog.e(LOGTAG, "Could not clean timed-out Mixpanel records from " + tableName + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    /**
     * Removes automatic events.
     * @param token token of the project you want to remove automatic events from
     */
    public synchronized void cleanupAutomaticEvents(String token, String serviceName) {
        cleanupAutomaticEvents(Table.EVENTS, token, serviceName);
        cleanupAutomaticEvents(Table.PEOPLE, token, serviceName);
        cleanupAutomaticEvents(Table.GROUPS, token, serviceName);
    }

    private void cleanupAutomaticEvents(Table table, String token, String serviceName) {
        final String tableName = table.getName();

        try {
            final SQLiteDatabase db = mDb.getWritableDatabase();
            db.delete(tableName, KEY_AUTOMATIC_DATA + " = 1 AND "
                    + KEY_TOKEN + " = '" + token + "' AND "
                    + KEY_SERVICE_NAME + " = '" + serviceName + "'", null);
        } catch (final SQLiteException e) {
            MPLog.e(LOGTAG, "Could not clean automatic Mixpanel records from " + tableName + ". Re-initializing database.", e);

            // We assume that in general, the results of a SQL exception are
            // unrecoverable, and could be associated with an oversized or
            // otherwise unusable DB. Better to bomb it and get back on track
            // than to leave it junked up (and maybe filling up the disk.)
            mDb.deleteDatabase();
        } finally {
            mDb.close();
        }
    }

    public void deleteDB() {
        mDb.deleteDatabase();
    }

    /**
     * Returns the data string to send to Mixpanel and the maximum ID of the row that
     * we're sending, so we know what rows to delete when a track request was successful.
     *
     * @param table the table to read the JSON from, one of "events", "people", or "groups"
     * @param token the token of the project you want to retrieve the records for
     * @param includeAutomaticEvents whether or not it should include pre-track records
     * @return String array containing the maximum ID, the data string
     * representing the events (or null if none could be successfully retrieved) and the total
     * current number of events in the queue.
     */
    public String[] generateDataString(Table table, String token, boolean includeAutomaticEvents) {
        Cursor c = null;
        Cursor queueCountCursor = null;
        String data = null;
        String last_id = null;
        String queueCount = null;
        final String tableName = table.getName();
        final SQLiteDatabase db = mDb.getReadableDatabase();

        try {
            StringBuffer rawDataQuery = new StringBuffer("SELECT * FROM " + tableName + " WHERE " + KEY_TOKEN + " = '" + token + "' ");
            StringBuffer queueCountQuery = new StringBuffer("SELECT COUNT(*) FROM " + tableName + " WHERE " + KEY_TOKEN + " = '" + token + "' ");
            if (!includeAutomaticEvents) {
                rawDataQuery.append("AND " + KEY_AUTOMATIC_DATA + " = 0 ");
                queueCountQuery.append(" AND " + KEY_AUTOMATIC_DATA + " = 0");
            }

            rawDataQuery.append("ORDER BY " + KEY_CREATED_AT + " ASC LIMIT 50");
            c = db.rawQuery(rawDataQuery.toString(), null);

            queueCountCursor = db.rawQuery(queueCountQuery.toString(), null);
            queueCountCursor.moveToFirst();
            queueCount = String.valueOf(queueCountCursor.getInt(0));

            final JSONArray arr = new JSONArray();

            while (c.moveToNext()) {
                if (c.isLast()) {
                    last_id = c.getString(c.getColumnIndex("_id"));
                }
                try {
                    final JSONObject j = new JSONObject(c.getString(c.getColumnIndex(KEY_DATA)));
                    arr.put(j);
                } catch (final JSONException e) {
                    // Ignore this object
                }
            }

            if (arr.length() > 0) {
                data = arr.toString();
            }
        } catch (final SQLiteException e) {
            MPLog.e(LOGTAG, "Could not pull records for Mixpanel out of database " + tableName + ". Waiting to send.", e);

            // We'll dump the DB on write failures, but with reads we can
            // let things ride in hopes the issue clears up.
            // (A bit more likely, since we're opening the DB for read and not write.)
            // A corrupted or disk-full DB will be cleaned up on the next write or clear call.
            last_id = null;
            data = null;
        } finally {
            mDb.close();
            if (c != null) {
                c.close();
            }
            if (queueCountCursor != null) {
                queueCountCursor.close();
            }
        }

        if (last_id != null && data != null) {
            final String[] ret = {last_id, data, queueCount};
            return ret;
        }
        return null;
    }

    public File getDatabaseFile() {
        return mDb.mDatabaseFile;
    }

    /* For testing use only, do not call from in production code */
    protected boolean belowMemThreshold() {
        return mDb.belowMemThreshold();
    }
}
