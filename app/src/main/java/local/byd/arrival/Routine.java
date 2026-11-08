package local.byd.arrival;

import java.util.*;

/** Sends a chosen set of commands in order, stopping at the first failure (never retries). */
public final class Routine {
    public interface Sender { void send(String command) throws Exception; }
    public static void run(List<String> commands, Sender sender) throws Exception {
        for (String command : commands) sender.send(command);
    }
    /**
     * Whether an automatic action may be tried once more: only after a network failure (timeout, no connection) before
     * any command was sent, e.g. while signing in or checking the PIN, so the car can't have acted. A refusal from BYD,
     * such as a wrong PIN, is never retried.
     */
    public static boolean retryable(Exception error, boolean commandsStarted) {
        return !commandsStarted && (error instanceof java.net.SocketTimeoutException || error instanceof java.net.ConnectException
            || error instanceof java.net.UnknownHostException || error instanceof java.net.NoRouteToHostException);
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
