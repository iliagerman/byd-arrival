package local.byd.arrival;

import java.util.List;

/** Sends a chosen set of commands in order, stopping at the first failure (never retries). */
public final class Routine {
    public interface Sender { void send(String command) throws Exception; }
    public static void run(List<String> commands, Sender sender) throws Exception {
        for (String command : commands) sender.send(command);
    }
}
