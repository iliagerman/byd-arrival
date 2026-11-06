package local.byd.arrival;

import java.util.*;

/** Sends a chosen set of commands in order, stopping at the first failure (never retries). */
public final class Routine {
    public interface Sender { void send(String command) throws Exception; }
    public static void run(List<String> commands, Sender sender) throws Exception {
        for (String command : commands) sender.send(command);
    }
    /** Sends every command even if one fails, and returns the failures (empty when all succeeded). Still never retries. */
    public static Map<String, Exception> runAll(List<String> commands, Sender sender) {
        Map<String, Exception> failed = new LinkedHashMap<>();
        for (String command : commands) {
            try { sender.send(command); }
            catch (Exception error) { failed.put(command, error); }
        }
        return failed;
    }
}
