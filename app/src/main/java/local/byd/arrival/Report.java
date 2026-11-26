package local.byd.arrival;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Android-free problem report: your description, when it was sent, the phone and app facts, the app's settings and the
 * recent log, posted as JSON to your webhook. Never included: the sign-in email, the password and PIN (kept apart,
 * encrypted), the car's coordinates, or more than the VIN's last 4 characters.
 */
public final class Report {
    /** Settings left out of a report. */
    static final Set<String> PRIVATE = Set.of("account", "carLatitude", "carLongitude", "ciphertext", "iv", "reportUrl");
    /** About a day of log: enough to see what led up to a problem. */
    public static final int LOG_LINES = 20_000;

    /** A webhook address: https with a host. Plain http is refused, since the log travels in it. */
    public static boolean validUrl(String text) {
        try {
            URI uri = new URI(text.trim());
            return "https".equals(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isEmpty();
        } catch (URISyntaxException e) { return false; }
    }

    /** The app's settings as JSON, private ones left out and the VIN cut to its last 4 characters. */
    public static JSONObject settings(Map<String, ?> all) throws JSONException {
        JSONObject out = new JSONObject();
        for (Map.Entry<String, ?> entry : new TreeMap<>(all).entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (PRIVATE.contains(key)) continue;
            if (key.equals("vin")) value = "···" + value.toString().substring(Math.max(0, value.toString().length() - 4));
            out.put(key, value instanceof Set ? new JSONArray((Set<?>) value) : value);
        }
        return out;
    }

    /** The whole report. sentAt is the phone's time, written with its offset, e.g. 2026-11-26T17:28:03+02:00. */
    public static JSONObject build(String description, String sentAt, JSONObject device, JSONObject now, JSONObject settings,
                                   String log) throws JSONException {
        return new JSONObject()
            .put("kind", "byd-arrival-report")
            .put("description", description.trim())
            .put("sentAt", sentAt)
            .put("device", device)
            .put("now", now)
            .put("settings", settings)
            .put("logLines", log.isEmpty() ? 0 : log.split("\n", -1).length)
            .put("log", log);
    }

    /** Posts the report. Returns the HTTP status; anything but 2xx is an IOException with the status in it. */
    public static int post(String url, JSONObject report) throws IOException {
        byte[] body = report.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) new URL(url.trim()).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setDoOutput(true);
        connection.setRequestMethod("POST");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setFixedLengthStreamingMode(body.length);
        try {
            try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            int status = connection.getResponseCode();
            if (status / 100 != 2) throw new IOException("the webhook answered HTTP " + status);
            return status;
        } finally {
            connection.disconnect();
        }
    }
}
