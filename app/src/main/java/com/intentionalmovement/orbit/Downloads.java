package com.intentionalmovement.orbit;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;

import org.mozilla.geckoview.WebResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

/** Files the page sends to download: a WireGuard .conf becomes a VPN configuration, anything else goes to Download/. */
final class Downloads {
    private Downloads() { }

    interface Sink {
        void vpnConfig(String name, String text);
        void saved(String where);
        void failed(String why);
    }

    static void handle(ContentResolver cr, WebResponse r, Sink sink) {
        String name = fileName(r);
        String type = header(r, "content-type");
        InputStream body = r.body;
        if (body == null) { sink.failed("the download was empty"); return; }
        new Thread(() -> {
            try (InputStream in = body) {
                // a WireGuard file is recognised by its name or by what is in it (Proton's page makes the file itself,
                // so it may arrive with no name): read the start, then either import it or save everything
                byte[] head = readUpTo(in, 256 * 1024);
                String text = head.length < 256 * 1024 ? new String(head, StandardCharsets.UTF_8) : "";
                boolean conf = text.contains("[Interface]") && text.contains("PrivateKey");
                if (conf) { sink.vpnConfig(name.toLowerCase(Locale.ROOT).endsWith(".conf") ? name : "Proton VPN.conf", text); return; }
                save(cr, name, type, new java.io.SequenceInputStream(new java.io.ByteArrayInputStream(head), in), sink);
            } catch (Exception e) {
                sink.failed(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
        }, "orbit-download").start();
    }

    private static void save(ContentResolver cr, String name, String type, InputStream in, Sink sink) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        if (type != null && !type.isEmpty()) v.put(MediaStore.MediaColumns.MIME_TYPE, type.split(";")[0].trim());
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (uri == null) throw new IOException("Download/ could not be written");
        try (OutputStream out = cr.openOutputStream(uri, "w")) {
            if (out == null) throw new IOException("Download/ could not be written");
            byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        sink.saved("Download/" + name);
    }

    /** Reads at most max bytes (fewer if the file is shorter). */
    private static byte[] readUpTo(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        while (out.size() < max && (n = in.read(buf, 0, Math.min(buf.length, max - out.size()))) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    static String header(WebResponse r, String name) {
        Map<String, String> h = r.headers;
        if (h == null) return null;
        for (Map.Entry<String, String> e : h.entrySet()) if (e.getKey() != null && e.getKey().equalsIgnoreCase(name)) return e.getValue();
        return null;
    }

    /** From Content-Disposition (filename*=UTF-8''… or filename="…"), else the last part of the address. */
    static String fileName(WebResponse r) {
        String cd = header(r, "content-disposition"), n = null;
        if (cd != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("filename\\*\\s*=\\s*[^']*''([^;]+)", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(cd);
            if (m.find()) { try { n = URLDecoder.decode(m.group(1).trim(), "UTF-8"); } catch (Exception ignored) { } }
            if (n == null) {
                m = java.util.regex.Pattern.compile("filename\\s*=\\s*\"?([^\";]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(cd);
                if (m.find()) n = m.group(1).trim();
            }
        }
        if (n == null && r.uri != null && !r.uri.startsWith("blob:") && !r.uri.startsWith("data:")) {
            try { n = Uri.parse(r.uri).getLastPathSegment(); } catch (Exception ignored) { }
        }
        if (n == null || n.trim().isEmpty()) n = "download";
        return n.replaceAll("[\\\\/:*?\"<>|]+", "_").trim();
    }
}
