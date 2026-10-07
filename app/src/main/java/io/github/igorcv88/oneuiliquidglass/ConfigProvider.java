package io.github.igorcv88.oneuiliquidglass;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

/** Read-only config for SystemUI when the LSPosed preferences bridge is unavailable. */
public final class ConfigProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String caller = getCallingPackage();
        if (!"com.android.systemui".equals(caller) && !Config.PACKAGE.equals(caller)) return null;
        MatrixCursor cursor = new MatrixCursor(new String[]{Config.KEY_ENABLED});
        cursor.addRow(new Object[]{Config.open(getContext()).getBoolean(Config.KEY_ENABLED, false) ? 1 : 0});
        return cursor;
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
