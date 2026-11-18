package local.byd.arrival;

import org.json.*;

/**
 * Android-free smart start settings: five temperature ranges and what the A/C and seats do in each. The ranges touch:
 * each one starts at its border and the one below ends there, so they can't overlap or leave a gap.
 */
public final class ClimatePlan {
    /** What the car accepts: A/C 17–33 °C on the Atto 3, fan 1–7. Range borders stay within MIN_BORDER..MAX_BORDER. */
    public static final int MIN_AC = 17, MAX_AC = 33, MIN_FAN = 1, MAX_FAN = 7, MIN_BORDER = -20, MAX_BORDER = 45;
    public static final String[] NAMES = {"Freezing", "Cold", "Mild", "Warm", "Hot"};
    public static final int MILD = 2;
    /** Seat heat per seat: OFF, LOW or HIGH. */
    public static final int OFF = 0, LOW = 1, HIGH = 2;
    public static final String[] HEAT = {"Off", "Low", "High"};

    public static final class Level {
        public final String name;
        public final int temperature, fan;
        /** OFF, LOW or HIGH. */
        public final int driverHeat, passengerHeat;
        public Level(String name, int temperature, int fan, int driverHeat, int passengerHeat) {
            if (temperature < MIN_AC || temperature > MAX_AC) throw new IllegalArgumentException(name + ": A/C temperature must be " + MIN_AC + "–" + MAX_AC + " °C");
            if (fan < MIN_FAN || fan > MAX_FAN) throw new IllegalArgumentException(name + ": fan speed must be " + MIN_FAN + "–" + MAX_FAN);
            this.name = name; this.temperature = temperature; this.fan = fan;
            if (driverHeat < OFF || driverHeat > HIGH || passengerHeat < OFF || passengerHeat > HIGH) throw new IllegalArgumentException(name + ": seat heat must be off, low or high");
            this.driverHeat = driverHeat; this.passengerHeat = passengerHeat;
        }
        public Level withTemperature(int celsius) { return new Level(name, clamp(celsius, MIN_AC, MAX_AC), fan, driverHeat, passengerHeat); }
        public Level withFan(int speed) { return new Level(name, temperature, clamp(speed, MIN_FAN, MAX_FAN), driverHeat, passengerHeat); }
        public Level withHeat(int driver, int passenger) { return new Level(name, temperature, fan, clamp(driver, OFF, HIGH), clamp(passenger, OFF, HIGH)); }
    }
    /** borders[i]: where level i+1 starts and level i ends. Always rising by at least 1 °C. */
    private final int[] borders;
    private final Level[] levels;
    public ClimatePlan(int[] borders, Level[] levels) {
        if (borders.length != NAMES.length - 1 || levels.length != NAMES.length) throw new IllegalArgumentException("Five levels and four borders");
        for (int i = 1; i < borders.length; i++)
            if (borders[i] <= borders[i - 1]) throw new IllegalArgumentException("Each range must start above the one below it");
        this.borders = borders.clone(); this.levels = levels.clone();
    }
    public static ClimatePlan defaults() {
        return new ClimatePlan(new int[]{17, 20, 24, 28}, new Level[]{
            new Level("Freezing", 26, 3, HIGH, OFF),
            new Level("Cold", 26, 3, OFF, OFF),
            new Level("Mild", 20, 3, OFF, OFF),
            new Level("Warm", 18, 5, OFF, OFF),
            new Level("Hot", 17, 7, OFF, OFF)});
    }
    public Level choose(double celsius) {
        for (int i = 0; i < borders.length; i++) if (celsius < borders[i]) return levels[i];
        return levels[levels.length - 1];
    }
    public Level level(int index) { return levels[index]; }
    /** Where level index starts; Freezing (0) has no lower border. */
    public int from(int index) { return borders[index - 1]; }
    /** "below 17 °C", "17 to 20 °C" (17 included, 20 not), "28 °C and above". */
    public String band(int index) {
        if (index == 0) return "below " + borders[0] + " °C";
        if (index == levels.length - 1) return borders[index - 1] + " °C and above";
        return borders[index - 1] + " to " + borders[index] + " °C";
    }
    /**
     * Moves where level index starts. The neighbouring borders move along when they would collide, so every range
     * keeps at least 1 °C and the one below always ends where this one starts.
     */
    public ClimatePlan withFrom(int index, int celsius) {
        int border = index - 1, last = borders.length - 1;
        int[] moved = borders.clone();
        moved[border] = clamp(celsius, MIN_BORDER + border, MAX_BORDER - (last - border));
        for (int i = border + 1; i <= last; i++) moved[i] = Math.max(moved[i], moved[i - 1] + 1);
        for (int i = border - 1; i >= 0; i--) moved[i] = Math.min(moved[i], moved[i + 1] - 1);
        return new ClimatePlan(moved, levels);
    }
    public ClimatePlan withLevel(int index, Level level) {
        Level[] changed = levels.clone();
        changed[index] = level;
        return new ClimatePlan(borders, changed);
    }
    public String toJson() {
        try {
            JSONArray out = new JSONArray();
            for (Level l : levels) out.put(new JSONObject().put("name", l.name).put("temperature", l.temperature)
                .put("fan", l.fan).put("driverHeat", l.driverHeat).put("passengerHeat", l.passengerHeat));
            return new JSONObject().put("borders", new JSONArray(borders)).put("levels", out).toString();
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    /**
     * Reads saved settings. Older saves are converted: four named borders, where Hot started above hotAbove (so at
     * hotAbove + 1 for the car's whole-degree readings), A/C temperatures down to 15 °C, which the car refuses, and seat
     * heat as on/off, where on was high.
     * Saves from before the Freezing level existed get the defaults.
     */
    public static ClimatePlan parse(String json) {
        try {
            JSONObject in = new JSONObject(json);
            if (!in.has("borders") && !in.has("freezingBelow")) return defaults();
            JSONArray l = in.getJSONArray("levels");
            Level[] levels = new Level[NAMES.length];
            for (int i = 0; i < levels.length; i++) {
                JSONObject o = l.getJSONObject(i);
                levels[i] = new Level(o.getString("name"), clamp(o.getInt("temperature"), MIN_AC, MAX_AC), o.getInt("fan"),
                    heat(o, "driverHeat", "driverSeat"), heat(o, "passengerHeat", "passengerSeat"));
            }
            if (in.has("borders")) {
                JSONArray b = in.getJSONArray("borders");
                int[] borders = new int[b.length()];
                for (int i = 0; i < borders.length; i++) borders[i] = b.getInt(i);
                return new ClimatePlan(borders, levels);
            }
            int[] old = {in.getInt("freezingBelow"), in.getInt("coldBelow"), in.getInt("warmFrom"), in.getInt("hotAbove") + 1};
            for (int i = 1; i < old.length; i++) old[i] = Math.max(old[i], old[i - 1] + 1);
            return new ClimatePlan(old, levels);
        } catch (JSONException e) { throw new IllegalStateException("Saved start settings are invalid", e); }
    }
    private static int heat(JSONObject level, String key, String oldKey) throws JSONException {
        return level.has(key) ? level.getInt(key) : level.getBoolean(oldKey) ? HIGH : OFF;
    }
    static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
}
