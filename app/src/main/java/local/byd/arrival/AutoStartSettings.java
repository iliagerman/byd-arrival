package local.byd.arrival;

/** Auto-start distances and timing. Whatever is entered becomes a working set, so nobody is stopped by an error. */
public final class AutoStartSettings {
    public static final int DEFAULT_START=15, DEFAULT_REARM=20, DEFAULT_MINUTES=5;
    private static final int MIN_START=3, MAX_REARM=500, MIN_GAP=5, MIN_MINUTES=1, MAX_MINUTES=60;
    public final int start, rearm, minutes;
    /** Blank or unreadable values use the default. The re-arm distance always ends up clearly beyond the start distance. */
    public AutoStartSettings(String start,String rearm,String minutes) {
        this.start=clamp(number(start,DEFAULT_START),MIN_START,MAX_REARM-MIN_GAP);
        this.rearm=clamp(number(rearm,DEFAULT_REARM),this.start+MIN_GAP,MAX_REARM);
        this.minutes=clamp(number(minutes,DEFAULT_MINUTES),MIN_MINUTES,MAX_MINUTES);
    }
    private static int number(String text,int fallback) {
        String trimmed=text.trim();
        return trimmed.matches("[0-9]{1,6}")?Integer.parseInt(trimmed):fallback;
    }
    private static int clamp(int value,int min,int max) { return Math.max(min,Math.min(max,value)); }
}
