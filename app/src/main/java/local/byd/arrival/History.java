package local.byd.arrival;

import org.json.*;
import java.util.*;

/** Android-free list of recent actions, newest first: when, what triggered it, what ran and how it ended. */
public final class History {
    public static final int MAX = 40;
    /** outcome: OK (confirmed by BYD), FAIL, or SKIPPED (an automatic action that was deliberately not sent). */
    public static final String OK = "ok", FAIL = "fail", SKIPPED = "skipped";
    public static final class Entry {
        public final long at;
        public final String trigger, actions, outcome, result;
        public Entry(long at, String trigger, String actions, String outcome, String result) {
            this.at = at; this.trigger = trigger; this.actions = actions; this.outcome = outcome; this.result = result;
        }
    }
    /** The list with entry added at the top, keeping the newest MAX. */
    public static List<Entry> add(List<Entry> entries, Entry entry) {
        List<Entry> out = new ArrayList<>();
        out.add(entry);
        out.addAll(entries.subList(0, Math.min(entries.size(), MAX - 1)));
        return out;
    }
    public static List<Entry> parse(String json) {
        try {
            JSONArray array = new JSONArray(json);
            List<Entry> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                out.add(new Entry(o.getLong("at"), o.getString("trigger"), o.getString("actions"), o.getString("outcome"), o.getString("result")));
            }
            return out;
        } catch (JSONException e) { throw new IllegalStateException("Saved history is invalid", e); }
    }
    public static String toJson(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry e : entries) {
            try { array.put(new JSONObject().put("at", e.at).put("trigger", e.trigger).put("actions", e.actions).put("outcome", e.outcome).put("result", e.result)); }
            catch (JSONException error) { throw new IllegalStateException(error); }
        }
        return array.toString();
    }
}
