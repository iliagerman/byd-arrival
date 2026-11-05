package local.byd.arrival;

/** Requires departure before the first arrival, then three close sightings. */
public final class ArrivalGate {
    private final int threshold;
    private final long absentMillis;
    private long lastNearby, previousSample = -1;
    private int samples;
    private boolean armed;
    public ArrivalGate(int threshold, long absentMillis) {
        this.threshold = threshold;
        this.absentMillis = absentMillis;
    }
    public void tick(long now) {
        if (now - lastNearby >= absentMillis) { armed = true; samples = 0; }
    }
    public boolean observe(long now, int rssi) {
        if (rssi >= threshold - 10) lastNearby = now;
        if (previousSample < 0 || now - previousSample > 10000) samples = 0;
        previousSample = now;
        if (!armed || rssi < threshold) { samples = 0; return false; }
        if (++samples < 3) return false;
        armed = false;
        samples = 0;
        return true;
    }
}
