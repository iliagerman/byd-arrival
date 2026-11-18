package local.byd.arrival;

import org.json.*;
import java.util.*;

/** Bluetooth beacons kept in the car: how each is recognised, the strength that counts as at the car, and calibration. */
public final class Beacons {
    /**
     * A chosen beacon. id is "ibeacon:UUID:major:minor" for an iBeacon, whose address may change, or "mac:ADDRESS" for
     * anything else (e.g. Eddystone), whose address is fixed. threshold: the signal, in dBm, at which you're at the car.
     * note: how that strength was set, shown in Settings; empty while it's still the default.
     */
    public static final class Beacon {
        public final String id, label, note;
        public final int threshold;
        public Beacon(String id, String label, int threshold, String note) { this.id = id; this.label = label; this.threshold = threshold; this.note = note; }
        public Beacon withThreshold(int strength, String how) { return new Beacon(id, label, strength, how); }
    }
    /** What a calibration heard: the weakest, middle and strongest reading, and the strength it suggests. */
    public static final class Calibration {
        public final int readings, weakest, middle, strongest, threshold;
        Calibration(int readings, int weakest, int middle, int strongest, int threshold) {
            this.readings = readings; this.weakest = weakest; this.middle = middle; this.strongest = strongest; this.threshold = threshold;
        }
    }
    /** Until calibrated: a beacon in a car is usually around this strong beside the door. */
    public static final int DEFAULT_THRESHOLD = -70;
    /** Strengths that can be set: from far (weak) to touching the beacon (strong). */
    public static final int WEAKEST = -100, STRONGEST = -30;
    /** The iBeacon id in Apple's manufacturer data: 0x02 0x15, UUID, major, minor, power. Null if it's not an iBeacon. */
    public static String iBeaconId(byte[] apple) {
        if (apple == null || apple.length < 23 || apple[0] != 0x02 || apple[1] != 0x15) return null;
        StringBuilder uuid = new StringBuilder();
        for (int i = 2; i < 18; i++) {
            uuid.append(String.format("%02x", apple[i]));
            if (i == 5 || i == 7 || i == 9 || i == 11) uuid.append('-');
        }
        int major = ((apple[18] & 0xff) << 8) | (apple[19] & 0xff), minor = ((apple[20] & 0xff) << 8) | (apple[21] & 0xff);
        return "ibeacon:" + uuid + ":" + major + ":" + minor;
    }
    public static String idFor(String address, byte[] apple) {
        String iBeacon = iBeaconId(apple);
        return iBeacon != null ? iBeacon : "mac:" + address;
    }
    /** For Android's scan filter: the 22 bytes of Apple data (mask all ones) that match one iBeacon. Null for an address id. */
    public static byte[] iBeaconFilter(String id) {
        if (!id.startsWith("ibeacon:")) return null;
        String[] parts = id.split(":");
        String hex = parts[1].replace("-", "");
        byte[] data = new byte[22];
        data[0] = 0x02; data[1] = 0x15;
        for (int i = 0; i < 16; i++) data[2 + i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        int major = Integer.parseInt(parts[2]), minor = Integer.parseInt(parts[3]);
        data[18] = (byte) (major >> 8); data[19] = (byte) major;
        data[20] = (byte) (minor >> 8); data[21] = (byte) minor;
        return data;
    }
    public static String address(String id) { return id.substring("mac:".length()); }
    /** One line saying what it is: "iBeacon · major 1, minor 0" or "Bluetooth · 48:87:2D:9E:60:BC". */
    public static String describe(String id) {
        if (!id.startsWith("ibeacon:")) return "Bluetooth · " + address(id);
        String[] parts = id.split(":");
        return "iBeacon · major " + parts[2] + ", minor " + parts[3];
    }
    /** Calibration: 3 dB under the middle reading taken where the car should start, so that spot triggers reliably. */
    public static Calibration calibrate(List<Integer> rssi) {
        if (rssi.size() < 3) throw new IllegalArgumentException("Too few readings. Stand closer to the beacon and try again.");
        List<Integer> sorted = new ArrayList<>(rssi);
        Collections.sort(sorted);
        int middle = sorted.get(sorted.size() / 2);
        return new Calibration(sorted.size(), sorted.get(0), middle, sorted.get(sorted.size() - 1), Math.max(WEAKEST, Math.min(STRONGEST, middle - 3)));
    }
    public static List<Beacon> parse(String json) {
        try {
            JSONArray array = new JSONArray(json);
            List<Beacon> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                // Beacons saved by build 51 have no note.
                out.add(new Beacon(item.getString("id"), item.getString("label"), item.getInt("threshold"), item.optString("note", "")));
            }
            return out;
        } catch (JSONException e) { throw new IllegalStateException("Saved beacons are invalid", e); }
    }
    public static String toJson(List<Beacon> beacons) {
        JSONArray array = new JSONArray();
        for (Beacon beacon : beacons) {
            try { array.put(new JSONObject().put("id", beacon.id).put("label", beacon.label).put("threshold", beacon.threshold).put("note", beacon.note)); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        }
        return array.toString();
    }
}
