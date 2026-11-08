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
    /** You were at the car (a reading within the re-arm distance of its spot) since its spot last changed. */
    public boolean visited() { return visited; }
    /** The car's spot changed without you: being near the old spot no longer counts as having been at the car. */
    public void spotChanged() { visited=false; }
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
    /** What a car check means for the trigger. */
    public enum CarCheck { MOVED_WITH_YOU, MOVED_WITHOUT_YOU, WALKED_AWAY, AWAY, UNSURE }
    /** The car counts as moving with you within this distance of your phone; BYD's position is a few seconds old. */
    static final double WITH_YOU_METRES=300;
    /**
     * Someone else may drive the car, so the car moving doesn't mean you drove it.
     * MOVED_WITH_YOU: it moved more than the re-arm distance and is near you while you travel: you're in it.
     * MOVED_WITHOUT_YOU: it moved, but you're not travelling with it: someone else drove it. Only its position changes.
     * WALKED_AWAY: it's still at its spot and you're on foot, after having been at the car: you walked away from it.
     * AWAY: the same, but you were never at the car: the trigger arms, but no walk-away action runs.
     * UNSURE: it's still at its spot while you move faster than walking (you've just driven off and BYD lags, or you're
     * in another vehicle), or a follow-only check (end of a trip, every 15 minutes) where traffic looks like parked.
     */
    public static CarCheck carCheck(double movedMetres,double rearmMetres,double phoneToCarMetres,boolean movingFast,
                                    boolean travelling,boolean followOnly,boolean wereAtCar) {
        if(movedMetres>rearmMetres)
            return travelling && phoneToCarMetres<=WITH_YOU_METRES?CarCheck.MOVED_WITH_YOU:CarCheck.MOVED_WITHOUT_YOU;
        if(movingFast || followOnly) return CarCheck.UNSURE;
        return wereAtCar?CarCheck.WALKED_AWAY:CarCheck.AWAY;
    }
    /**
     * Faster than walking (about 12 km/h): in a vehicle, or running. Speed counts only from a reading accurate enough
     * to use (within the re-arm distance): indoors GPS reports noise such as 17 km/h at ±235 m.
     */
    public static boolean movingFast(boolean hasSpeed,float metresPerSecond,float accuracyMetres,double rearmMetres) {
        return hasSpeed && metresPerSecond>3.5f && accuracyMetres<=rearmMetres;
    }
    /** Before unlocking on arrival: BYD's position for the car must be within the start distance of you, plus 30 m of GPS error. */
    public static boolean carIsHere(double phoneToCarMetres,double fireMetres) { return phoneToCarMetres<=fireMetres+30; }
    /**
     * How long to wait between car checks while you're in a vehicle: a minute, so even a short drive is followed. Each
     * UNSURE result in a row (the car stays put, e.g. you're on a bus) doubles it, up to 15 minutes.
     */
    public static long vehicleCheckGapMs(int unsureInRow) { return Math.min(60_000L<<Math.min(unsureInRow,4),15*60_000L); }
    /**
     * In Israel the car is never farther than restartKm (default 700 km), so a reading beyond it is an error: a wrong
     * car position or phone location. Tracking then restarts, at most once every 5 minutes so a stuck error can't loop.
     * Abroad mode stops tracking altogether, so this never applies abroad.
     */
    public static boolean restartDue(double metresFromCar,int restartKm,long msSinceLastRestart) {
        return metresFromCar>restartKm*1000.0 && msSinceLastRestart>=5*60_000L;
    }
    /** Great-circle distance in metres between two WGS84 coordinates. */
    public static double metres(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371000 * Math.asin(Math.sqrt(a));
    }
}
