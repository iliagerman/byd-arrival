package local.byd.arrival;

/**
 * GPS arrival trigger. Fires once when the phone gets within the fire radius of the car's saved spot. Arming needs a
 * "got away" reading, where even the near edge of the fix's accuracy circle is beyond the re-arm radius, so GPS noise
 * while you sit in the car cannot arm it. Fixes less accurate than the re-arm radius are ignored.
 * Getting away can mean walking away or driving the car off, so it first reports LEFT and waits for the car's position:
 * still at its spot, it arms; moved, the caller saves the new spot and it waits for you to leave that one.
 */
public final class ProximityGate {
    public enum Event { NONE, FIRE, LEFT }
    private final double fireMetres, rearmMetres;
    private boolean armed;
    /** A reading within the re-arm radius since the car was last checked: you were at the car, so you may drive it off. */
    private boolean visited;
    public ProximityGate(double fireMetres, double rearmMetres, boolean armed) {
        this.fireMetres = fireMetres;
        this.rearmMetres = rearmMetres;
        this.armed = armed;
    }
    public boolean armed() { return armed; }
    /** Arms the trigger without a distance reading, e.g. when a trusted Wi-Fi is joined. */
    public void arm() { armed = true; }
    /**
     * FIRE: start the car. LEFT: you're clearly away from the car's saved spot after a start or after being at the car,
     * so check where the car is. mayStart is false during the cooldown or on trusted Wi-Fi; the gate stays armed then.
     */
    public Event observe(double distanceMetres, float accuracyMetres, boolean mayStart) {
        if (accuracyMetres > rearmMetres) return Event.NONE;
        if (distanceMetres - accuracyMetres > rearmMetres) return !armed || visited ? Event.LEFT : Event.NONE;
        if (distanceMetres <= rearmMetres) visited = true;
        if (armed && distanceMetres <= fireMetres && mayStart) { armed = false; return Event.FIRE; }
        return Event.NONE;
    }
    /** The car check after LEFT. Not moved: you walked away, so it arms. Moved: you drove it, so it stays disarmed. */
    public void carChecked(boolean moved) {
        armed = !moved;
        visited = false;
    }
    /** Great-circle distance in metres between two WGS84 coordinates. */
    public static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(a));
    }
}
