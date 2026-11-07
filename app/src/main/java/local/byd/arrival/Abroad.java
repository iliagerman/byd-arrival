package local.byd.arrival;

/**
 * Abroad mode pauses auto-start and the app's background requests to BYD while you travel. It turns on by itself
 * beyond 700 km from the car, or by hand before a trip, and turns itself off once you're back within 700 km after
 * having been beyond it, so switching it on at home doesn't turn it straight off again.
 */
public final class Abroad {
    public static final double METRES=700_000;
    public final boolean on, beenFar;
    public Abroad(boolean on,boolean beenFar) { this.on=on; this.beenFar=beenFar; }
    public Abroad observe(double metresFromCar) {
        if(metresFromCar>METRES) return new Abroad(true,true);
        if(on && beenFar) return new Abroad(false,false);
        return this;
    }
    @Override public boolean equals(Object other) {
        return other instanceof Abroad && ((Abroad)other).on==on && ((Abroad)other).beenFar==beenFar;
    }
    @Override public int hashCode() { return (on?2:0)+(beenFar?1:0); }
}
