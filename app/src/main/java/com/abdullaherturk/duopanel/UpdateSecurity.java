package com.abdullaherturk.duopanel;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class UpdateSecurity {
    static boolean compatible(PackageInfo installed, PackageInfo archive) {
        if (installed == null || archive == null || installed.packageName == null ||
                !installed.packageName.equals(archive.packageName) || code(archive) <= code(installed)) return false;
        Signature[] installedCerts = certificates(installed), archiveCerts = certificates(archive);
        if (installedCerts == null || archiveCerts == null || installedCerts.length == 0 || archiveCerts.length == 0)
            return false;
        // Exact CURRENT certificate set, not historical lineage; fail closed on key rotation.
        Set<Signature> a = new HashSet<>(Arrays.asList(installedCerts));
        Set<Signature> b = new HashSet<>(Arrays.asList(archiveCerts));
        return a.equals(b);
    }
    private static long code(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }
    private static Signature[] certificates(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
    }

    static void verify(Context context, File file, long size, String sha) throws Exception {
        if (file.length() != size || size <= 0 || size > UpdateRelease.MAX_APK ||
                sha == null || !sha.matches("[a-fA-F0-9]{64}")) throw new SecurityException("Invalid size/digest");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int n;
            while ((n = in.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                digest.update(buffer, 0, n);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        if (!sha.equalsIgnoreCase(hex.toString())) throw new SecurityException("Checksum mismatch");
        PackageManager pm = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), flags);
        PackageInfo archive = pm.getPackageArchiveInfo(file.getAbsolutePath(), flags);
        if (!compatible(installed, archive)) throw new SecurityException("Incompatible APK");
    }
}
