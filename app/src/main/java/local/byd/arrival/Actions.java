package local.byd.arrival;

import org.json.*;
import java.util.*;

/** Android-free action model: built-in car actions, user-made custom actions, and how they expand into steps. */
public final class Actions {
    /** Built-in actions: code to label, in display order. START is the smart climate start (see ClimatePlan). */
    public static final LinkedHashMap<String,String> BUILT_IN=new LinkedHashMap<>();
    static {
        BUILT_IN.put("START","Start car");
        BUILT_IN.put("CLOSEAIR","Turn off");
        BUILT_IN.put("OPENDOOR","Unlock");
        BUILT_IN.put("LOCKDOOR","Lock");
        BUILT_IN.put("OPENTRUNK","Open trunk");
        BUILT_IN.put("CLOSETRUNK","Close trunk");
        BUILT_IN.put("OPENAIR","A/C on");
        BUILT_IN.put("SEAT_DRIVER","Driver seat heat");
        BUILT_IN.put("SEAT_PASSENGER","Passenger seat heat");
        BUILT_IN.put("CLOSEWINDOW","Close windows");
        BUILT_IN.put("FLASHLIGHTNOWHISTLE","Flash lights");
        BUILT_IN.put("FINDCAR","Find car");
    }
    /** A user-made action: a name and an ordered list of built-in steps. */
    public static final class Custom {
        public final String id, name;
        public final List<String> steps;
        public Custom(String id, String name, List<String> steps) {
            if (name.isBlank()) throw new IllegalArgumentException("Name the action");
            if (steps.isEmpty()) throw new IllegalArgumentException("Pick at least one step");
            for (String step : steps) if (!BUILT_IN.containsKey(step)) throw new IllegalArgumentException("Unknown step " + step);
            this.id = id; this.name = name.trim(); this.steps = List.copyOf(steps);
        }
    }
    public static List<Custom> parse(String json) {
        try {
            JSONArray array = new JSONArray(json);
            List<Custom> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                JSONArray steps = item.getJSONArray("steps");
                List<String> list = new ArrayList<>();
                for (int j = 0; j < steps.length(); j++) list.add(steps.getString(j));
                out.add(new Custom(item.getString("id"), item.getString("name"), list));
            }
            return out;
        } catch (JSONException e) { throw new IllegalStateException("Saved custom actions are invalid", e); }
    }
    public static String toJson(List<Custom> customs) {
        JSONArray array = new JSONArray();
        for (Custom custom : customs) {
            try { array.put(new JSONObject().put("id", custom.id).put("name", custom.name).put("steps", new JSONArray(custom.steps))); }
            catch (JSONException e) { throw new IllegalStateException(e); }
        }
        return array.toString();
    }
    public static String nextId(List<Custom> customs) {
        int max = 0;
        for (Custom custom : customs) max = Math.max(max, Integer.parseInt(custom.id.substring(1)));
        return "C" + (max + 1);
    }
    public static Custom find(String id, List<Custom> customs) {
        for (Custom custom : customs) if (custom.id.equals(id)) return custom;
        throw new IllegalArgumentException("Unknown action " + id);
    }
    public static boolean exists(String code, List<Custom> customs) {
        if (BUILT_IN.containsKey(code)) return true;
        for (Custom custom : customs) if (custom.id.equals(code)) return true;
        return false;
    }
    public static String label(String code, List<Custom> customs) {
        return BUILT_IN.containsKey(code) ? BUILT_IN.get(code) : find(code, customs).name;
    }
    public static String labels(List<String> codes, List<Custom> customs) {
        List<String> names = new ArrayList<>();
        for (String code : codes) names.add(label(code, customs));
        return String.join(" → ", names);
    }
    /** All actions a button can run: built-ins, then custom actions. */
    public static List<String> all(List<Custom> customs) {
        List<String> out = new ArrayList<>(BUILT_IN.keySet());
        for (Custom custom : customs) out.add(custom.id);
        return out;
    }
    /**
     * Expands actions into built-in steps. Start car and A/C on both start the A/C, so only the first
     * of them runs: a second start would just restart the climate with other settings.
     */
    public static List<String> expand(List<String> actions, List<Custom> customs) {
        List<String> steps = new ArrayList<>();
        for (String action : actions) {
            List<String> parts = BUILT_IN.containsKey(action) ? List.of(action) : find(action, customs).steps;
            for (String step : parts) {
                boolean climate = step.equals("START") || step.equals("OPENAIR");
                if (climate && (steps.contains("START") || steps.contains("OPENAIR"))) continue;
                steps.add(step);
            }
        }
        return steps;
    }
    public static String progress(String step) {
        switch (step) {
            case "OPENDOOR": return "Unlocking…";
            case "LOCKDOOR": return "Locking…";
            case "CLOSEAIR": return "Turning off…";
            case "OPENTRUNK": return "Opening trunk…";
            case "CLOSETRUNK": return "Closing trunk…";
            case "CLOSEWINDOW": return "Closing windows…";
            case "FLASHLIGHTNOWHISTLE": return "Flashing lights…";
            case "FINDCAR": return "Flashing lights and sounding horn…";
            default: return "Sending command…";
        }
    }
}
