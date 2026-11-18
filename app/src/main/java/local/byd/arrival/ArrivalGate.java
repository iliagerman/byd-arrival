package local.byd.arrival;

/**
 * Bluetooth arrival trigger for a beacon in the car. Each sighting is given as its margin over the beacon's calibrated
 * strength in dB (0: exactly at it). It fires on three strong sightings in a row, at most 10 s apart, but only once
 * armed: after the beacon was out of range (more than 10 dB under its strength, or unheard) for a while.
 */
public final class ArrivalGate {
    private final long absentMillis;
    private long lastNearby, previousSample = -1;
    private int samples;
    private boolean armed;
    public ArrivalGate(long absentMillis) { this.absentMillis = absentMillis; }
    public boolean armed() { return armed; }
    /** Arms once the beacon has been out of range for absentMillis. Returns true when it just armed. */
    public boolean tick(long now) {
        if (armed || now - lastNearby < absentMillis) return false;
        armed = true; samples = 0;
        return true;
    }
    /** Armed by a trusted Wi-Fi, as if you had walked away. */
    public void arm() { armed = true; }
    /** The GPS trigger started the car, so Bluetooth doesn't start it again on the same arrival. */
    public void disarm() { armed = false; samples = 0; }
    /** True when the car should start. mayStart is false during the cooldown, on trusted Wi-Fi or while driving: it stays armed. */
    public boolean observe(long now, int marginDb, boolean mayStart) {
        if (marginDb >= -10) lastNearby = now;
        if (previousSample < 0 || now - previousSample > 10000) samples = 0;
        previousSample = now;
        if (!armed || marginDb < 0) { samples = 0; return false; }
        if (++samples < 3 || !mayStart) return false;
        armed = false; samples = 0;
        return true;
    }
}
