package com.abdullaherturk.duopanel;

import android.content.Context;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.net.ssl.HttpsURLConnection;

/** One cancellable request chain. No Activity references, credentials or unbounded responses. */
class UpdateClient {
    private volatile boolean cancelled;
    private volatile HttpsURLConnection connection;
    void cancel() {
        cancelled = true;
        HttpsURLConnection c = connection;
        if (c != null) c.disconnect();
    }
    void active() throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
    }
    static boolean allowed(String address) {
        try {
            URI u = new URI(address);
            String h = u.getHost();
            return "https".equals(u.getScheme()) && u.getUserInfo() == null && u.getFragment() == null &&
                    u.getPort() == -1 && ("github.com".equals(h) || "api.github.com".equals(h) ||
                    "release-assets.githubusercontent.com".equals(h) || "objects.githubusercontent.com".equals(h) ||
                    "github-releases.githubusercontent.com".equals(h));
        } catch (Exception e) { return false; }
    }
    HttpsURLConnection connect(String address) throws IOException {
        return (HttpsURLConnection) new URL(address).openConnection();
    }
    private HttpsURLConnection open(String address) throws Exception {
        for (int i = 0; i < 6; i++) {
            active();
            if (!allowed(address)) throw new SecurityException("Untrusted redirect");
            HttpsURLConnection c = connect(address);
            connection = c;
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000); c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", "DuoPanel-Streambox-Updater");
            c.setRequestProperty("Accept", address.equals(UpdateRelease.API) ? "application/vnd.github+json" : "application/octet-stream");
            c.setRequestProperty("Accept-Encoding", "identity");
            int status = c.getResponseCode();
            if (status == 200) { active(); return c; }
            String next = c.getHeaderField("Location");
            c.disconnect(); connection = null;
            if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308) && next != null) {
                address = new URI(address).resolve(next).toString();
            } else throw new IOException("GitHub HTTP " + status);
        }
        throw new IOException("Too many redirects");
    }
    private long transfer(String address, OutputStream out, long limit, Progress progress) throws Exception {
        HttpsURLConnection c = null;
        try {
            c = open(address);
            if (c.getContentLengthLong() > limit) throw new IOException("Response too large");
            long total = 0;
            long deadline = android.os.SystemClock.elapsedRealtime() + 180000;
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[32768]; int n; int previous = -1;
                while ((n = in.read(buffer)) != -1) {
                    active(); total += n;
                    if (total > limit || android.os.SystemClock.elapsedRealtime() > deadline) throw new IOException("Response limit");
                    out.write(buffer, 0, n);
                    int percent = (int) (100 * total / limit);
                    if (progress != null && percent != previous) { progress.changed(percent); previous = percent; }
                }
            }
            active(); return total;
        } finally {
            // Also close when connect/getResponseCode failed before open() returned.
            HttpsURLConnection current = connection;
            if (current != null) current.disconnect();
            if (c != null) c.disconnect();
            connection = null;
        }
    }
    String text(String url, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        transfer(url, out, limit, null);
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
    UpdateRelease check() throws Exception { return UpdateRelease.parse(text(UpdateRelease.API, 1024 * 1024)); }
    interface Progress { void changed(int percent); }
    static final class Download {
        final File file; final long size; final String sha;
        Download(File f, long s, String h) { file = f; size = s; sha = h; }
    }
    static void cleanup(Context context) {
        File[] files = new File(context.getCacheDir(), "updates").listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L;
        for (File file : files) {
            String name = file.getName();
            if (file.lastModified() < cutoff && (UpdateApkProvider.validName(name) ||
                    (name.endsWith(".part") && UpdateApkProvider.validName(name.replace(".part", ".apk"))))) file.delete();
        }
    }
    Download download(Context context, UpdateRelease release, Progress progress) throws Exception {
        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create update directory");
        String id = UUID.randomUUID().toString();
        File partial = new File(dir, id + ".part");
        File ready = new File(dir, id + ".apk");
        boolean success = false;
        try {
            String sha = release.digest != null ? release.digest : release.checksum(text(release.sumsUrl, 65536));
            try (FileOutputStream out = new FileOutputStream(partial)) {
                if (transfer(release.url, out, release.size, progress) != release.size) throw new IOException("Truncated APK");
                out.getFD().sync();
            }
            UpdateSecurity.verify(context, partial, release.size, sha);
            active();
            if (!partial.renameTo(ready)) throw new IOException("Cannot finalize APK");
            active(); success = true;
            return new Download(ready, release.size, sha);
        } finally { partial.delete(); if (!success) ready.delete(); }
    }
}
