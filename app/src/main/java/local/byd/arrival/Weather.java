package local.byd.arrival;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Outside temperature at the car from Open-Meteo (free, no account), for when the car gives no cabin reading.
 * The position is rounded to about 1 km before it's sent: plenty for the weather, without the exact spot.
 */
public final class Weather {
    public static String url(double latitude, double longitude) {
        return String.format(Locale.ROOT, "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m", latitude, longitude);
    }
    public static double currentCelsius(String json) throws JSONException {
        return new JSONObject(json).getJSONObject("current").getDouble("temperature_2m");
    }
    /** Waits at most 5 s to connect and 5 s for the answer, so a slow service can't hold up the start for long. */
    public static double outside(double latitude, double longitude) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url(latitude, longitude)).openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("weather service answered " + connection.getResponseCode());
            try (InputStream in = connection.getInputStream()) {
                return currentCelsius(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            connection.disconnect();
        }
    }
}
