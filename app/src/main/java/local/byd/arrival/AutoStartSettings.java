package local.byd.arrival;

/** Auto-start distances and timing. Whatever is entered becomes a working set, so nobody is stopped by an error. */
public final class AutoStartSettings {
    public static final int DEFAULT_START=15, DEFAULT_REARM=20, DEFAULT_MINUTES=5, DEFAULT_WIFI_SIGNAL=-70, DEFAULT_SECONDS=5, DEFAULT_RESTART_KM=700;
    /** From this check interval on, the Bluetooth scan runs in short rounds instead of nonstop. */
    public static final int SCAN_ROUNDS_FROM_SECONDS=10;
    private static final int MIN_START=3, MAX_REARM=500, MIN_GAP=5, MIN_MINUTES=1, MAX_MINUTES=60;
    private static final int WEAKEST_WIFI_SIGNAL=-95, STRONGEST_WIFI_SIGNAL=-30;
    public final int start, rearm, minutes;
    /** Blank or unreadable values use the default. The re-arm distance always ends up clearly beyond the start distance. */
    public AutoStartSettings(String start,String rearm,String minutes) {
        this.start=clamp(number(start,DEFAULT_START),MIN_START,MAX_REARM-MIN_GAP);
        this.rearm=clamp(number(rearm,DEFAULT_REARM),this.start+MIN_GAP,MAX_REARM);
        this.minutes=clamp(number(minutes,DEFAULT_MINUTES),MIN_MINUTES,MAX_MINUTES);
    }
    /**
     * The Wi-Fi signal, in dBm, from which a listed network arms auto-start. Blank or unreadable uses the default.
     * dBm is always negative here, so a missing minus sign is added: 70 means −70.
     */
    public static int wifiSignal(String text) {
        String digits=text.trim().replaceFirst("^[-−]","");
        return -clamp(number(digits,-DEFAULT_WIFI_SIGNAL),-STRONGEST_WIFI_SIGNAL,-WEAKEST_WIFI_SIGNAL);
    }
    /** How often, in seconds, to read the phone's location or scan for the beacon: 1 to 60. Blank or unreadable uses the default. */
    /** A reading farther than this many km from the car is an error and restarts tracking: 100 to 5000. Blank or unreadable uses 700. */
    public static int restartKm(String text) { return clamp(number(text,DEFAULT_RESTART_KM),100,5000); }
    public static int intervalSeconds(String text) { return clamp(number(text,DEFAULT_SECONDS),1,60); }
    /**
     * How long the beacon must be out of range before you count as having left: 60 s, or two scan rounds when
     * rounds are further apart, so the gap between two rounds beside the car never looks like leaving.
     */
    public static long beaconAbsentMillis(int seconds) { return Math.max(60000L,2000L*seconds); }
    private static int number(String text,int fallback) {
        String trimmed=text.trim();
        return trimmed.matches("[0-9]{1,6}")?Integer.parseInt(trimmed):fallback;
    }
    private static int clamp(int value,int min,int max) { return Math.max(min,Math.min(max,value)); }
}
