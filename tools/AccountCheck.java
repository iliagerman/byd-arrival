package local.byd.arrival;

import java.nio.file.*;

/** Read-only native-client smoke test. Does not verify PIN or issue vehicle commands. */
public final class AccountCheck {
    public static void main(String[] args) throws Exception {
        try {
            BydApi api=new BydApi(Files.readAllBytes(Path.of(args[0])),System.getenv("USERNAME"),System.getenv("PASSWORD"),System.getenv("PIN"));
            int count=api.vehicles().length();
            System.out.println("Native Android client authenticated; vehicle count: "+count+". No car commands sent.");
        } catch(Exception error) {
            System.out.println("Native account check failed: "+error.getClass().getSimpleName()+". No retry; no car commands sent.");
            System.exit(1);
        }
    }
}
