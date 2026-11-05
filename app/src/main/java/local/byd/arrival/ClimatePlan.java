package local.byd.arrival;

import org.json.*;

/** Android-free smart start settings: temperature bands and what the A/C and seats do in each. */
public final class ClimatePlan {
    public static final class Level {
        public final String name;
        public final int temperature, fan;
        public final boolean driverSeat, passengerSeat;
        public Level(String name, int temperature, int fan, boolean driverSeat, boolean passengerSeat) {
            if (temperature < 15 || temperature > 33) throw new IllegalArgumentException(name + ": A/C temperature must be 15–33 °C");
            if (fan < 1 || fan > 7) throw new IllegalArgumentException(name + ": fan speed must be 1–7");
            this.name = name; this.temperature = temperature; this.fan = fan;
            this.driverSeat = driverSeat; this.passengerSeat = passengerSeat;
        }
    }
    /** Cold below coldBelow; mild below warmFrom; warm up to hotAbove; hot above it. */
    public final int coldBelow, warmFrom, hotAbove;
    public final Level cold, mild, warm, hot;
    public ClimatePlan(int coldBelow, int warmFrom, int hotAbove, Level cold, Level mild, Level warm, Level hot) {
        if (!(coldBelow <= warmFrom && warmFrom <= hotAbove)) throw new IllegalArgumentException("Temperatures must go up: cold ≤ warm ≤ hot");
        this.coldBelow = coldBelow; this.warmFrom = warmFrom; this.hotAbove = hotAbove;
        this.cold = cold; this.mild = mild; this.warm = warm; this.hot = hot;
    }
    public static ClimatePlan defaults() {
        return new ClimatePlan(22, 24, 27,
            new Level("Cold", 26, 3, true, false),
            new Level("Mild", 20, 3, false, false),
            new Level("Warm", 18, 5, false, false),
            new Level("Hot", 17, 7, false, false));
    }
    public Level choose(double celsius) {
        if (celsius < coldBelow) return cold;
        if (celsius < warmFrom) return mild;
        if (celsius <= hotAbove) return warm;
        return hot;
    }
    public Level[] levels() { return new Level[]{cold, mild, warm, hot}; }
    public String band(Level level) {
        if (level == cold) return "below " + coldBelow + " °C";
        if (level == mild) return coldBelow + "–" + warmFrom + " °C";
        if (level == warm) return warmFrom + "–" + hotAbove + " °C";
        return "above " + hotAbove + " °C";
    }
    public String toJson() {
        try {
            JSONObject out = new JSONObject().put("coldBelow", coldBelow).put("warmFrom", warmFrom).put("hotAbove", hotAbove);
            JSONArray levels = new JSONArray();
            for (Level l : levels()) levels.put(new JSONObject().put("name", l.name).put("temperature", l.temperature)
                .put("fan", l.fan).put("driverSeat", l.driverSeat).put("passengerSeat", l.passengerSeat));
            return out.put("levels", levels).toString();
        } catch (JSONException e) { throw new IllegalStateException(e); }
    }
    public static ClimatePlan parse(String json) {
        try {
            JSONObject in = new JSONObject(json);
            JSONArray l = in.getJSONArray("levels");
            Level[] levels = new Level[4];
            for (int i = 0; i < 4; i++) {
                JSONObject o = l.getJSONObject(i);
                levels[i] = new Level(o.getString("name"), o.getInt("temperature"), o.getInt("fan"), o.getBoolean("driverSeat"), o.getBoolean("passengerSeat"));
            }
            return new ClimatePlan(in.getInt("coldBelow"), in.getInt("warmFrom"), in.getInt("hotAbove"), levels[0], levels[1], levels[2], levels[3]);
        } catch (JSONException e) { throw new IllegalStateException("Saved start settings are invalid", e); }
    }
}
