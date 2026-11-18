package local.byd.arrival;

/**
 * Bluetooth arrival trigger for a beacon in the car. Each sighting is given as its margin over the beacon's calibrated
 * strength in dB (0: exactly at it). It fires on three strong sightings in a row, at most 10 s apart, but only once
 * armed. The monitor arms it when the beacon has been out of range (more than AWAY_DB under its strength, or unheard)
 * for a while and GPS agrees you're away from the car, so a dip in the signal while you sit in the car can't re-arm it.
 */
public final class ArrivalGate {
    /** This many dB under its strength, the beacon counts as out of range. */
    public static final int AWAY_DB = 10;
    private final long absentMillis;
    private long lastNearby, previousSample = -1, lastStrong = -1;
    private int samples;
    private boolean armed, atCar;
    public ArrivalGate(long absentMillis) { this.absentMillis = absentMillis; }
    public boolean armed() { return armed; }
    /** The beacon has been out of range for absentMillis. */
    public boolean absent(long now) { return now - lastNearby >= absentMillis; }
    /** A sighting at full strength at or after time t: you were back at the car. */
    public boolean strongSince(long t) { return lastStrong >= t; }
    /** Armed: you're away (beacon out of range, or a trusted Wi-Fi), so the next strong signal is an arrival. */
    public void arm() { armed = true; samples = 0; }
    /** You were at the car (a sighting at its strength, or an arrival) since you last left it. */
    public boolean atCar() { return atCar; }
    /** Leaving the car was checked (beacon gone, or GPS): armed, and the beacon going doesn't need checking again. */
    public void leftCar() { arm(); atCar = false; }
    /** The GPS trigger started the car: you're at it, and Bluetooth doesn't start it again on this arrival. */
    public void disarm() { armed = false; samples = 0; atCar = true; }
    /** True when the car should start. mayStart is false during the cooldown, on trusted Wi-Fi or while driving: it stays armed. */
    public boolean observe(long now, int marginDb, boolean mayStart) {
        if (marginDb >= -AWAY_DB) lastNearby = now;
        if (marginDb >= 0) { atCar = true; lastStrong = now; }
        if (previousSample < 0 || now - previousSample > 10_000) samples = 0;
        previousSample = now;
        if (!armed || marginDb < 0) { samples = 0; return false; }
        if (++samples < 3 || !mayStart) return false;
        armed = false; samples = 0;
        return true;
    }
}
