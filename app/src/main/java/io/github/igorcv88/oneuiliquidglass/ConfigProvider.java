package io.github.igorcv88.oneuiliquidglass;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;

import io.github.igorcv88.oneuiliquidglass.config.*;

import java.util.Map;

/** Read-only protocol. root URI is the legacy enabled column; /v1/snapshot is key/value. */
public final class ConfigProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    private void authorize() {
        int uid = Binder.getCallingUid();
        if (uid == android.os.Process.myUid()) return;
        try {
            int expected =
                    getContext().getPackageManager().getPackageUid("com.android.systemui", 0);
            String[] names = getContext().getPackageManager().getPackagesForUid(uid);
            if (uid == expected
                    && names != null
                    && java.util.Arrays.asList(names).contains("com.android.systemui")) return;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
        }
        throw new SecurityException("Config is restricted to SystemUI and the app UID");
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] args, String sort) {
        authorize();
        if (!Config.AUTHORITY.equals(uri.getAuthority())
                || uri.getQuery() != null
                || uri.getFragment() != null
                || selection != null
                || args != null
                || sort != null) throw new IllegalArgumentException("Unsupported query");
        ConfigRepository repo = new ConfigRepository(getContext());
        if (uri.getPath() == null || uri.getPath().isEmpty() || uri.getPath().equals("/")) {
            if (projection != null
                    && !java.util.Arrays.equals(projection, new String[] {Config.KEY_ENABLED}))
                throw new IllegalArgumentException("projection");
            MatrixCursor c = new MatrixCursor(new String[] {Config.KEY_ENABLED});
            c.addRow(
                    new Object[] {
                        repo.preferences().getBoolean(Config.KEY_ENABLED, false) ? 1 : 0
                    });
            return c;
        }
        if (!uri.equals(ConfigRepository.SNAPSHOT)
                || projection != null
                        && !java.util.Arrays.equals(projection, new String[] {"key", "value"}))
            throw new IllegalArgumentException("Unsupported URI/projection");
        ConfigRepository.Snapshot snapshot = repo.snapshotValues();
        MatrixCursor c = new MatrixCursor(new String[] {"key", "value"});
        c.addRow(new Object[] {"schemaVersion", "1"});
        c.addRow(new Object[] {"generation", Long.toString(snapshot.generation)});
        c.addRow(new Object[] {"enabled", snapshot.enabled ? "1" : "0"});
        for (Map.Entry<String, String> e : snapshot.overrides.entrySet()) {
            SettingSpec s = SettingsRegistry.get(e.getKey());
            if (s == null || s.applyMode == SettingSpec.ApplyMode.LIVE && !s.helperOwned())
                c.addRow(new Object[] {e.getKey(), e.getValue()});
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        authorize();
        return "vnd.android.cursor.dir/vnd.oulg.config";
    }

    @Override
    public Uri insert(Uri u, ContentValues v) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int delete(Uri u, String s, String[] a) {
        throw new UnsupportedOperationException("Read only");
    }

    @Override
    public int update(Uri u, ContentValues v, String s, String[] a) {
        throw new UnsupportedOperationException("Read only");
    }
}
