package local.byd.arrival;

import org.json.*;
import java.nio.file.*;
import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** In-memory HTTPS fixture. Uses synthetic credentials; never contacts BYD. */
public final class ApiTest {
    static Bangcle cipher;
    static int triggers,polls,checks;
    static boolean reject;
    static final String USER="fixture@example.invalid", PASS="synthetic-password", PIN="123456";
    static void check(boolean ok,String text) { checks++;if(!ok)throw new AssertionError(text); }
    public static void main(String[] args) throws Exception {
        byte[] tables=Files.readAllBytes(Path.of(args[0]));cipher=new Bangcle(tables);
        URL.setURLStreamHandlerFactory(protocol->protocol.equals("https")?new URLStreamHandler() {
            protected URLConnection openConnection(URL url) { return new Fixture(url); }
        }:null);
        BydApi api=new BydApi(tables,USER,PASS,PIN,Regions.host("au"),"AU","Australia/Sydney");
        JSONArray cars=api.vehicles();check(cars.length()==1,"vehicle discovery");
        api.verifyPin("SYNTHETICVIN");
        api.command("SYNTHETICVIN","OPENAIR",BydApi.climateParams(21,17,5,30));
        check(triggers==1,"exactly one command trigger");
        check(polls==2,"pending res=1 is not success");
        reject=true;
        try { api.command("SYNTHETICVIN","OPENDOOR",null);throw new AssertionError("must reject PIN error"); }
        catch(IOException expected) { check(expected.getMessage().contains("5011"),"numeric error only"); }
        check(triggers==2,"rejected command not retried");
        check(BydApi.finished(new JSONObject().put("res",2)),"immediate success needs no serial");
        check(!BydApi.finished(new JSONObject().put("controlState",0)),"controlState 0 is pending");
        try { BydApi.finished(new JSONObject().put("controlState",2));throw new AssertionError("must reject failure"); }
        catch(IOException expected) { check(true,"controlState 2 is rejection"); }
        System.out.println("PASS: "+checks+" API fixture checks");
    }
    static final class Fixture extends HttpURLConnection {
        final ByteArrayOutputStream request=new ByteArrayOutputStream();
        Fixture(URL url) { super(url); }
        public void connect() {} public void disconnect() {} public boolean usingProxy() {return false;}
        public OutputStream getOutputStream() {return request;}
        public int getResponseCode() {return 200;}
        public Map<String,List<String>> getHeaderFields() {return Collections.emptyMap();}
        public InputStream getInputStream() throws IOException {
            try {
                JSONObject outer=new JSONObject(cipher.decode(new JSONObject(request.toString(StandardCharsets.UTF_8)).getString("request")));
                String key=Crypto.md5("test-encryption-token");
                String endpoint=url.getPath();
                check(url.getHost().equals("dilinkappoversea-au.byd.auto"),"requests go to the chosen server");
                check(outer.getString("countryCode").equals("AU"),"requests carry the chosen country");
                Object payload;
                if(endpoint.endsWith("/login")) {
                    check(outer.getString("signKey").equals(PASS),"password in encrypted envelope only");
                    key=Crypto.md5(Crypto.md5(PASS));
                    check(new JSONObject(Crypto.decrypt(outer.getString("encryData"),key)).getString("timeZone").equals("Australia/Sydney"),"login carries the phone's time zone");
                    payload=new JSONObject().put("token",new JSONObject().put("userId","fixture-user").put("encryToken","test-encryption-token").put("signToken","test-sign-token"));
                } else {
                    JSONObject inner=new JSONObject(Crypto.decrypt(outer.getString("encryData"),key));
                    if(endpoint.endsWith("getAllListByUserId")) payload=new JSONArray().put(new JSONObject().put("vin","SYNTHETICVIN"));
                    else {
                        check(inner.getString("commandPwd").equals(Crypto.md5(PIN)),"PIN hashed, not plaintext");
                        // Real BYD acknowledges a correct PIN with no respondData at all.
                        if(endpoint.endsWith("verifyControlPassword")) return response(new JSONObject().put("code","0"));
                        else if(endpoint.endsWith("remoteControl")) {
                            triggers++;
                            if(reject) return response(new JSONObject().put("code","5011"));
                            check(inner.getString("commandType").equals("OPENAIR"),"climate command identifier");
                            JSONObject params=new JSONObject(inner.getString("controlParamsMap"));
                            check(params.getInt("remoteMode")==4,"immediate mode required");
                            check(params.getInt("cycleMode")==2,"recirculation set");
                            check(!params.has("windMode") && params.getInt("windLevel")==5,"fan speed sent, no invented parameters");
                            check(params.getInt("mainSettingTemp")==5,"temperature converted using the car range");
                            check(params.getInt("timeSpan")==5,"30 minute duration");
                            payload=new JSONObject().put("requestSerial","fixture-request");
                        } else {
                            check(inner.getString("requestSerial").equals("fixture-request"),"poll correlated to trigger");
                            payload=new JSONObject().put("res",++polls==1?1:2);
                        }
                    }
                }
                return response(new JSONObject().put("code","0").put("respondData",Crypto.encrypt(payload.toString(),key)));
            } catch(Exception e) {throw new IOException("Fixture failure",e);}
        }
        InputStream response(JSONObject data) {
            String body=new JSONObject().put("response",cipher.encode(data.toString())).toString();
            return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        }
    }
}
