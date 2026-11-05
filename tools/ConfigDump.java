package local.byd.arrival;

import org.json.*;
import java.nio.file.*;

/** Read-only: prints the car's capability list (functionNo, code, name). No PIN check, no car commands. */
public final class ConfigDump {
    public static void main(String[] args) throws Exception {
        BydApi api=new BydApi(Files.readAllBytes(Path.of(args[0])),System.getenv("USERNAME"),System.getenv("PASSWORD"),System.getenv("PIN"));
        JSONArray cars=api.vehicles();
        System.out.println("Vehicle fields: "+cars.getJSONObject(0).keySet());
        String vin=cars.getJSONObject(0).getString("vin");
        JSONObject config=api.latestConfig(vin).getJSONObject(vin);
        System.out.println("Config fields: "+config.keySet());
        print(config.getJSONArray("cfFixedList"),"");
        System.out.println("No car commands sent.");
    }
    private static void print(JSONArray nodes,String indent) {
        for(int i=0;i<nodes.length();i++) {
            JSONObject node=nodes.getJSONObject(i);
            System.out.println(indent+node.opt("functionNo")+"  "+node.opt("code")+"  "+node.opt("functionName"));
            JSONArray children=node.optJSONArray("cfFixedSecondLevelList");
            if(children!=null) print(children,indent+"    ");
        }
    }
}
