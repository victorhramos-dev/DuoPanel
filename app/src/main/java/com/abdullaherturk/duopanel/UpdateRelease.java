package com.abdullaherturk.duopanel;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URI;
import java.math.BigInteger;
import java.util.regex.Pattern;

/** Strict release contract for this fork; no network or Android UI. */
final class UpdateRelease {
    static final String REPO = "victorhramos-dev/DuoPanel";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final long MAX_APK = 100L * 1024 * 1024;
    private static final Pattern TAG = Pattern.compile("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)-streambox");
    final String tag, name, url, digest, sumsUrl;
    final long size;

    private UpdateRelease(String tag, String name, String url, long size, String digest, String sums) {
        this.tag = tag; this.name = name; this.url = url; this.size = size;
        this.digest = digest; this.sumsUrl = sums;
    }

    static int compare(String a, String b) {
        if (a == null || b == null || !TAG.matcher(a).matches() || !TAG.matcher(b).matches())
            throw new IllegalArgumentException("Invalid stable tag");
        String[] x = a.replaceFirst("^v", "").split("[-.]");
        String[] y = b.replaceFirst("^v", "").split("[-.]");
        for (int i = 0; i < 3; i++) {
            int c = new BigInteger(x[i]).compareTo(new BigInteger(y[i]));
            if (c != 0) return c;
        }
        return 0;
    }

    static void assetUrl(String url, String tag, String name) {
        try {
            URI u = new URI(url);
            String path = "/" + REPO + "/releases/download/" + tag + "/" + name;
            if (!"https".equals(u.getScheme()) || !"github.com".equals(u.getHost()) ||
                    u.getPort() != -1 || u.getUserInfo() != null || u.getQuery() != null ||
                    u.getFragment() != null || !path.equals(u.getRawPath())) throw new Exception();
        } catch (Exception e) { throw new IllegalArgumentException("Invalid asset URL"); }
    }

    static UpdateRelease parse(String payload) throws Exception {
        JSONObject j = new JSONObject(payload);
        if (j.getBoolean("draft") || j.getBoolean("prerelease")) throw new IllegalArgumentException("Not stable");
        String tag = j.getString("tag_name");
        compare(tag, tag);
        String name = "DuoPanel-Streambox-Fix-" + tag.replaceFirst("^v", "").replace("-streambox", "") + ".apk";
        JSONArray assets = j.getJSONArray("assets");
        JSONObject apk = null;
        String sums = null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            String n = a.getString("name");
            if (name.equals(n)) {
                if (apk != null) throw new IllegalArgumentException("Ambiguous APK");
                apk = a;
            } else if ("SHA256SUMS".equals(n)) {
                if (sums != null) throw new IllegalArgumentException("Ambiguous checksum");
                sums = a.getString("browser_download_url");
                assetUrl(sums, tag, n);
            }
        }
        if (apk == null || !"uploaded".equals(apk.getString("state")) ||
                !"application/vnd.android.package-archive".equals(apk.getString("content_type")))
            throw new IllegalArgumentException("Missing APK");
        long size = apk.getLong("size");
        if (size <= 0 || size > MAX_APK) throw new IllegalArgumentException("Invalid APK size");
        String url = apk.getString("browser_download_url");
        assetUrl(url, tag, name);
        String digest = apk.isNull("digest") ? null : apk.optString("digest", null);
        if (digest != null && !digest.matches("sha256:[a-fA-F0-9]{64}"))
            throw new IllegalArgumentException("Invalid digest");
        if (digest == null && sums == null) throw new IllegalArgumentException("No integrity metadata");
        return new UpdateRelease(tag, name, url, size, digest == null ? null : digest.substring(7), sums);
    }

    String checksum(String text) {
        String found = null;
        for (String line : text.split("\\r?\\n")) {
            if (line.matches("[a-fA-F0-9]{64} [ *](?:\\./)?" + Pattern.quote(name))) {
                if (found != null) throw new IllegalArgumentException("Ambiguous checksum");
                found = line.substring(0, 64);
            }
        }
        if (found == null) throw new IllegalArgumentException("Missing checksum");
        return found;
    }
}
