package io.github.igorcv88.oneuiliquidglass.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Only package identifiers are recorded; never notification titles, bodies or account data. */
public final class NotificationIconStore {
    private NotificationIconStore() {}
    private static final String PREFS = "notification_icon_misses", ENTRIES = "packages";
    public static final String SEGMENT = "notification-icon";
    private static final int MAX_MISSING = 100, MAX_PNG_BYTES = 1024 * 1024, MAX_SIDE = 1024;

    public static boolean validPackage(String name) {
        return "android".equals(name) || name != null && name.length() >= 3 && name.length() <= 220
                && name.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+");
    }

    public static void recordMissing(Context context, String pkg) {
        if (!validPackage(pkg)) return;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Set<String> now = p.getStringSet(ENTRIES, Collections.emptySet());
        if (now.contains(pkg) || now.size() >= MAX_MISSING) return;
        Set<String> next = new TreeSet<>(now);
        next.add(pkg);
        p.edit().putStringSet(ENTRIES, next).apply();
    }

    public static List<String> missing(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new ArrayList<>(new TreeSet<>(p.getStringSet(ENTRIES, Collections.emptySet())));
    }

    public static Uri uri(String authority, String pkg) {
        if (!validPackage(pkg)) throw new IllegalArgumentException("Invalid package");
        return new Uri.Builder().scheme("content").authority(authority).appendPath(SEGMENT).appendPath(pkg).build();
    }

    public static File file(Context context, String pkg) {
        if (!validPackage(pkg)) throw new IllegalArgumentException("Invalid package");
        return new File(new File(context.getFilesDir(), "notification_icons"), pkg + ".png");
    }

    public static boolean imported(Context context, String pkg) {
        return validPackage(pkg) && file(context, pkg).isFile();
    }

    public static List<String> importedPackages(Context context) {
        File directory = new File(context.getFilesDir(), "notification_icons");
        String[] names = directory.list();
        List<String> out = new ArrayList<>();
        if (names != null) for (String name : names) {
            if (!name.endsWith(".png")) continue;
            String pkg = name.substring(0, name.length() - 4);
            if (validPackage(pkg)) out.add(pkg);
        }
        Collections.sort(out);
        return out;
    }

    public static boolean remove(Context context, String pkg) {
        return validPackage(pkg) && (!file(context, pkg).exists() || file(context, pkg).delete());
    }

    /** PNG only; bounded file, dimensions and alpha. Saved as a white alpha mask, not raw artwork. */
    public static void importPng(Context context, String pkg, InputStream source) throws IOException {
        if (!validPackage(pkg) || source == null) throw new IOException("Invalid source");
        byte[] input = source.readNBytes(MAX_PNG_BYTES + 1);
        if (input.length > MAX_PNG_BYTES || input.length < 24 || (input[0] & 255) != 137
                || input[1] != 80 || input[2] != 78 || input[3] != 71) throw new IOException("Only PNG <= 1 MB");
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(input, 0, input.length, opts);
        if (opts.outWidth < 16 || opts.outHeight < 16 || opts.outWidth > MAX_SIDE || opts.outHeight > MAX_SIDE)
            throw new IOException("PNG dimensions must be 16-1024 px");
        opts.inJustDecodeBounds = false;
        Bitmap original = BitmapFactory.decodeByteArray(input, 0, input.length, opts);
        if (original == null) throw new IOException("Cannot decode PNG");
        Bitmap mask = null, sized = null;
        File temporary = null;
        try {
            int width = original.getWidth(), height = original.getHeight();
            int[] pixels = new int[width * height];
            original.getPixels(pixels, 0, width, 0, 0, width, height);
            int clear = 0, covered = 0;
            for (int i = 0; i < pixels.length; i++) {
                int alpha = pixels[i] >>> 24;
                if (alpha < 26) clear++;
                if (alpha > 128) covered++;
                pixels[i] = (alpha << 24) | 0x00ffffff;
            }
            if (clear * 10 < pixels.length || covered * 20 < pixels.length || covered * 10 > pixels.length * 9)
                throw new IOException("Use a transparent PNG containing only the icon silhouette");
            mask = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888);
            if (Math.max(width, height) > 256) {
                float factor = 256f / Math.max(width, height);
                sized = Bitmap.createScaledBitmap(mask, Math.max(1, Math.round(width * factor)),
                        Math.max(1, Math.round(height * factor)), true);
            }
            File dest = file(context, pkg), dir = dest.getParentFile();
            if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create icon directory");
            temporary = File.createTempFile("new-", ".png", dir);
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                if (!(sized == null ? mask : sized).compress(Bitmap.CompressFormat.PNG, 100, out))
                    throw new IOException("Cannot encode icon");
                out.getFD().sync();
            }
            if (!temporary.renameTo(dest)) throw new IOException("Cannot save icon");
        } finally {
            original.recycle();
            if (mask != null) mask.recycle();
            if (sized != null) sized.recycle();
            if (temporary != null && temporary.exists()) temporary.delete();
        }
    }
}
