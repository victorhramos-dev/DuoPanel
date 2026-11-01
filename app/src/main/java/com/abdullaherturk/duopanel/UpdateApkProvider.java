package com.abdullaherturk.duopanel;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/** Read-only, non-exported provider. Grants expose only finalized update APKs, never partials. */
public final class UpdateApkProvider extends ContentProvider {
    static boolean validName(String name) {
        return name != null && name.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.apk");
    }
    static Uri uri(Context context, File file) {
        if (!validName(file.getName())) throw new IllegalArgumentException("Not an update APK");
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + ".updates")
                .appendPath(file.getName()).build();
    }
    private File resolve(Uri uri) throws FileNotFoundException {
        if (!"content".equals(uri.getScheme()) || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority()) ||
                uri.getQuery() != null || uri.getFragment() != null || uri.getPathSegments().size() != 1 ||
                !validName(uri.getLastPathSegment())) throw new FileNotFoundException("Invalid update URI");
        File dir = new File(getContext().getCacheDir(), "updates");
        File file = new File(dir, uri.getLastPathSegment());
        try {
            if (!file.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()) || !file.isFile())
                throw new FileNotFoundException("Missing APK");
        } catch (IOException e) { throw new FileNotFoundException("Invalid APK"); }
        return file;
    }
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return "application/vnd.android.package-archive"; }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = resolve(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = "DuoPanel-update.apk";
                if (OpenableColumns.SIZE.equals(columns[i])) row[i] = f.length();
            }
            cursor.addRow(row); return cursor;
        } catch (FileNotFoundException e) { throw new IllegalArgumentException("Missing APK"); }
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("Read only"); }
}
