package local.byd.arrival;

/**
 * GPS arrival trigger. Fires once when the phone gets within the fire radius, then waits for a
 * "got away" reading: even the near edge of the fix's accuracy circle must be beyond the re-arm radius,
 * so GPS noise while you sit in the car cannot re-arm it. Fixes less accurate than the re-arm radius are ignored.
 */
public final class ProximityGate {
    private final double fireMetres, rearmMetres;
    private boolean armed;
    public ProximityGate(double fireMetres, double rearmMetres, boolean armed) {
        this.fireMetres = fireMetres;
        this.rearmMetres = rearmMetres;
        this.armed = armed;
    }
    public boolean armed() { return armed; }
    /** Arms the trigger without a distance reading, e.g. when a trusted Wi-Fi is joined. */
    public void arm() { armed = true; }
    /** Returns true when the car should be started. mayStart is false during the cooldown; the gate stays armed then. */
    public boolean observe(double distanceMetres, float accuracyMetres, boolean mayStart) {
        if (accuracyMetres > rearmMetres) return false;
        if (distanceMetres - accuracyMetres > rearmMetres) { armed = true; return false; }
        if (armed && distanceMetres <= fireMetres && mayStart) { armed = false; return true; }
        return false;
    }
    /** Great-circle distance in metres between two WGS84 coordinates. */
    public static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(a));
    }
}
