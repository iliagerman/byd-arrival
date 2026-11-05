package local.byd.arrival;

import org.json.*;
import java.nio.file.*;

/** Read-only: login, PIN verification and GPS. Prints only outcome and field names. No car commands. */
public final class ResponseProbe {
    public static void main(String[] args) throws Exception {
        BydApi api=new BydApi(Files.readAllBytes(Path.of(args[0])),System.getenv("USERNAME"),System.getenv("PASSWORD"),System.getenv("PIN"),
            Regions.host(Regions.country(System.getenv("COUNTRY")).server),System.getenv("COUNTRY"),java.util.TimeZone.getDefault().getID());
        String vin=api.vehicles().getJSONObject(0).getString("vin");
        step("verifyPin",()->{ api.verifyPin(vin); return "ok"; });
        step("gpsInfo",()->api.gpsInfo(vin).keySet().toString());
        System.out.println("No car commands sent.");
    }
    interface Step { String run() throws Exception; }
    private static void step(String name,Step step) {
        try { System.out.println(name+": "+step.run()); }
        catch(Exception error) { System.out.println(name+" failed: "+error.getClass().getSimpleName()+" "+error.getMessage()); }
    }
}
