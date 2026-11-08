package local.byd.arrival;

import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;

/** Direct EU API. No request/response logging and never retries a mutation. */
public final class BydApi {
    private final Bangcle codec;
    private final String username, password, pin, device, host, country, timeZone;
    private final Map<String,String> cookies = new HashMap<>();
    private JSONObject token;
    private long authenticatedAt;
    public BydApi(byte[] tables, String username, String password, String pin, String host, String country, String timeZone) throws Exception {
        this.codec = new Bangcle(tables);
        this.username = username; this.password = password; this.pin = pin;
        this.host = host; this.country = country; this.timeZone = timeZone;
        device = Crypto.md5(username);
    }
    static LinkedHashMap<String,String> fields(String... pairs) {
        LinkedHashMap<String,String> out=new LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2) out.put(pairs[i],pairs[i+1]);
        return out;
    }
    static String json(Map<String,?> map) {
        StringJoiner out=new StringJoiner(",","{","}");
        for(Map.Entry<String,?> e:map.entrySet()) {
            Object v=e.getValue();
            out.add(JSONObject.quote(e.getKey())+":"+(v==null ? "null" : v instanceof String ? JSONObject.quote((String)v) : v.toString()));
        }
        return out.toString();
    }
    private static String random() {
        byte[] raw=new byte[16]; new SecureRandom().nextBytes(raw); return Crypto.hex(raw);
    }
    private LinkedHashMap<String,String> base(String vin) {
        LinkedHashMap<String,String> m=fields("deviceType","0","imeiMD5",device,"networkType","wifi",
            "random",random(),"timeStamp",Long.toString(System.currentTimeMillis()),"version","351");
        if (!vin.isEmpty()) m.put("vin",vin);
        return m;
    }
    private void deviceFields(Map<String,String> m) {
        m.putAll(fields("ostype","and","imei","BANGCLE01234","mac","00:00:00:00:00:00",
            "model","POCO F1","sdk","35","mod","Xiaomi","serviceTime",Long.toString(System.currentTimeMillis())));
    }
    public synchronized void login() throws Exception {
        String now=Long.toString(System.currentTimeMillis());
        Map<String,String> inner=fields("agreeStatus","0","agreementType","[1,2]","appInnerVersion","351",
            "appVersion","3.5.1","deviceName","XIAOMIPOCO F1","deviceType","0","imeiMD5",device,
            "isAuto","1","mobileBrand","XIAOMI","mobileModel","POCO F1","networkType","wifi",
            "osType","15","osVersion","35","random",random(),"softType","0","timeStamp",now,"timeZone",timeZone);
        Map<String,String> signed=new LinkedHashMap<>(inner);
        signed.putAll(fields("appName","pyBYD+0.1","countryCode",country,"functionType","pwdLogin",
            "identifier",username,"identifierType","0","language","en","reqTimestamp",now));
        Map<String,String> outer=fields("appName","pyBYD+0.1","countryCode",country,
            "encryData",Crypto.encrypt(json(inner),Crypto.md5(Crypto.md5(password))),"functionType","pwdLogin",
            "identifier",username,"identifierType","0","imeiMD5",device,"isAuto","1","language","en",
            "reqTimestamp",now,"sign",Crypto.sign(signed,Crypto.md5(password)),"signKey",password);
        deviceFields(outer);
        JSONObject response=post("/app/account/login",outer);
        JSONObject decoded=new JSONObject(Crypto.decrypt(response.getString("respondData"),Crypto.md5(Crypto.md5(password))));
        JSONObject fresh=decoded.getJSONObject("token");
        fresh.getString("userId"); fresh.getString("encryToken"); fresh.getString("signToken");
        token=fresh; authenticatedAt=System.currentTimeMillis();
    }
    /**
     * Signing in with the same account elsewhere, e.g. in the official app, ends this session. BYD then refuses the
     * request without acting on it, so signing in again and sending it once more is safe. Nothing else is retried.
     */
    private synchronized Object request(String endpoint, Map<String,String> inner) throws Exception {
        try { return send(endpoint,inner); }
        catch(SessionEnded ended) {
            onSessionEnded.accept(ended.code);
            return send(endpoint,renewed(inner));
        }
    }
    /** A request sent again gets a new nonce and time, as a new request would. */
    private static Map<String,String> renewed(Map<String,String> inner) {
        if(inner.containsKey("random")) inner.put("random",random());
        if(inner.containsKey("timeStamp")) inner.put("timeStamp",Long.toString(System.currentTimeMillis()));
        return inner;
    }
    /** BYD's code for a car still busy with the previous command, e.g. windows still closing (pyBYD's 6024). */
    static final class CarBusy extends IOException {
        CarBusy() { super("BYD says the car is still busy with the previous command (code 6024). Tried 3 times; no further retry."); }
    }
    /** Wait before asking a busy car again, as pyBYD does. Tests set it to 0. */
    static long busyWaitMs=5000;
    /** BYD's codes for an ended session, as in pyBYD. */
    private static final Set<String> SESSION_ENDED=Set.of("1002","1005","1010");
    /** Told each time BYD ended the session and the request is sent again after a new sign-in, e.g. to log it. */
    java.util.function.Consumer<String> onSessionEnded=code->{};
    static final class SessionEnded extends IOException {
        final String code;
        SessionEnded(String code) { super("BYD ended the session (code "+code+"), probably because the account signed in elsewhere. Try again."); this.code=code; }
    }
    private Object send(String endpoint, Map<String,String> inner) throws Exception {
        if (token==null || System.currentTimeMillis()-authenticatedAt>12*3600000L) login();
        String now=Long.toString(System.currentTimeMillis());
        String contentKey=Crypto.md5(token.getString("encryToken"));
        Map<String,String> signed=new LinkedHashMap<>(inner);
        signed.putAll(fields("countryCode",country,"identifier",token.getString("userId"),"imeiMD5",device,
            "language","en","reqTimestamp",now));
        Map<String,String> outer=fields("countryCode",country,"encryData",Crypto.encrypt(json(inner),contentKey),
            "identifier",token.getString("userId"),"imeiMD5",device,"language","en","reqTimestamp",now,
            "sign",Crypto.sign(signed,Crypto.md5(token.getString("signToken"))));
        deviceFields(outer);
        JSONObject response=post(endpoint,outer);
        // Acknowledgement-only endpoints (e.g. verifyControlPassword) return no or empty respondData.
        String encrypted=response.optString("respondData");
        if(encrypted.isEmpty()) return new JSONObject();
        String plain=Crypto.decrypt(encrypted,contentKey);
        if(plain.isBlank()) return new JSONObject();
        return new JSONTokener(plain).nextValue();
    }
    public JSONArray vehicles() throws Exception {
        return (JSONArray)request("/app/account/getAllListByUserId",base(""));
    }
    /** Read-only per-vehicle capability list; each node carries functionNo and command code. */
    public JSONObject latestConfig(String vin) throws Exception {
        Map<String,String> inner=base("");
        inner.put("appConfigVersion","2"); inner.put("terminalType","0");
        inner.put("vinList",new JSONArray().put(vin).toString());
        return (JSONObject)request("/vehicle/vehicleswitch/getLatestConfig",inner);
    }
    /** Read-only: asks the car to report GPS; polls until data beyond requestSerial arrives. */
    public JSONObject gpsInfo(String vin) throws Exception {
        JSONObject data=(JSONObject)request("/control/getGpsInfo",base(vin));
        String serial=data.optString("requestSerial");
        for(int attempt=0;attempt<10 && onlySerial(data);attempt++) {
            Thread.sleep(1500);
            Map<String,String> poll=base(vin); poll.put("requestSerial",serial);
            data=(JSONObject)request("/control/getGpsInfoResult",poll);
        }
        if(onlySerial(data)) throw new IOException("BYD GPS not reported by car");
        return data;
    }
    /**
     * Read-only: asks the car for live data (battery level, range) and polls until the answer holds data, as pyBYD does.
     * energyType 0 is an electric car; a hybrid would get its fuel fields zeroed, but its battery level still comes back.
     */
    public JSONObject realtime(String vin) throws Exception {
        Map<String,String> ask=base(vin); ask.put("energyType","0"); ask.put("tboxVersion","3");
        JSONObject data=(JSONObject)request("/vehicleInfo/vehicle/vehicleRealTimeRequest",ask);
        for(int attempt=0;attempt<10 && !realtimeReady(data);attempt++) {
            Thread.sleep(1500);
            Map<String,String> poll=base(vin); poll.put("energyType","0"); poll.put("tboxVersion","3");
            poll.put("requestSerial",data.optString("requestSerial"));
            data=(JSONObject)request("/vehicleInfo/vehicle/vehicleRealTimeResult",poll);
        }
        if(!realtimeReady(data)) throw new IOException("BYD battery level not reported by car");
        return data;
    }
    /** pyBYD's rule: the answer holds data once it has a report time or a range. */
    static boolean realtimeReady(JSONObject data) { return data.optLong("time",0)>0 || data.optDouble("enduranceMileage",0)>0; }
    /** Battery level in percent, or -1 when the car sent none (BYD uses -1 after the car wakes or sleeps deeply). */
    static int batteryPercent(JSONObject data) {
        double percent=data.optDouble("elecPercent",-1);
        return percent<0?-1:(int)Math.round(percent);
    }
    /** A GPS response holding only the correlation id means the car has not reported a fix yet. */
    private static boolean onlySerial(JSONObject data) { return data.length()==1 && data.has("requestSerial"); }
    /** Returns {latitude, longitude} of the car. */
    public double[] location(String vin) throws Exception { return firstPosition(()->gpsInfo(vin),4,15000); }
    /** BYD's answer (0, 0) while the car hasn't reported a position yet. */
    static final class NoPosition extends IOException {
        NoPosition() { super("BYD has no position for the car right now. Start monitoring again in a minute."); }
    }
    /**
     * BYD answers 0, 0 until the car reports a fix, which can take some seconds after it wakes (seen right after a
     * phone restart). Asks again, waitMs apart, up to tries times. Read-only, so asking again is safe.
     */
    static double[] firstPosition(java.util.concurrent.Callable<JSONObject> ask,int tries,long waitMs) throws Exception {
        for(int attempt=1;;attempt++) {
            try { return position(ask.call()); }
            catch(NoPosition none) { if(attempt==tries) throw none; Thread.sleep(waitMs); }
        }
    }
    /**
     * Reads a GPS response, tolerating nested data and alternate field names. BYD sometimes answers 0, 0 when it
     * has no position; that point is thousands of kilometres away and would arm auto-start, so it is rejected.
     */
    static double[] position(JSONObject data) throws IOException {
        JSONObject nested=data.optJSONObject("data");
        JSONObject source=nested!=null?nested:data;
        double lat=coordinate(source,"latitudeDone","latitude");
        double lon=coordinate(source,"longitudeDone","longitude");
        if(lat==0 && lon==0) throw new NoPosition();
        return new double[]{lat,lon,source.optLong("gpsTimeStamp",0)};
    }
    private static double coordinate(JSONObject source,String primary,String fallback) throws IOException {
        Object raw=source.opt(primary); if(raw==null) raw=source.opt(fallback);
        if(raw==null) throw new IOException("BYD GPS response missing coordinates");
        return Double.parseDouble(raw.toString());
    }
    /** Read-only climate status (statusNow): cabin/outside temperature, A/C range, seat heat. No car command. */
    public JSONObject climate(String vin) throws Exception {
        return ((JSONObject)request("/control/getStatusNow",base(vin))).getJSONObject("statusNow");
    }
    public void verifyPin(String vin) throws Exception {
        Map<String,String> inner=base(vin);
        inner.put("commandPwd",Crypto.md5(pin)); inner.put("functionType","remoteControl");
        request("/vehicle/vehicleswitch/verifyControlPassword",inner);
    }
    /** Remote commands whose commandType strings are confirmed by public BYD clients (pyBYD, ioBroker). */
    static final Set<String> COMMANDS=Set.of("OPENDOOR","LOCKDOOR","OPENAIR","CLOSEAIR","OPENTRUNK","CLOSETRUNK",
        "FINDCAR","FLASHLIGHTNOWHISTLE","CLOSEWINDOW","VENTILATIONHEATING");
    /**
     * A/C start parameters. The car reports its own range (rangeMin..rangeMin+16 °C, 17–33 on the Atto 3),
     * which maps onto BYD's 1–17 scale. fan 0 leaves the fan on automatic.
     */
    static LinkedHashMap<String,Object> climateParams(int celsius,int rangeMin,int fan,int minutes) {
        int scale=celsius-(rangeMin-1);
        if(scale<1 || scale>17) throw new IllegalArgumentException("The car accepts "+rangeMin+"–"+(rangeMin+16)+" °C, not "+celsius+" °C");
        if(minutes<10 || minutes>30 || minutes%5!=0) throw new IllegalArgumentException("Engine-on time must be 10–30 minutes in steps of 5");
        LinkedHashMap<String,Object> climate=new LinkedHashMap<>();
        climate.put("airAccuracy",1); climate.put("airConditioningMode",1); climate.put("airSet",null);
        climate.put("copilotSettingTemp",scale); climate.put("mainSettingTemp",scale);
        climate.put("timeSpan",minutes/5-1); climate.put("cycleMode",2); climate.put("remoteMode",4);
        if(fan>0) climate.put("windLevel",fan);
        return climate;
    }
    /** Seat heat parameters. BYD wants every seat on each command: 1=high, 2=low, 3=off, 0=no change. chairType names the changed seat. */
    static LinkedHashMap<String,Object> seatParams(String chairType,int mainHeat,int copilotHeat) {
        LinkedHashMap<String,Object> seats=new LinkedHashMap<>();
        seats.put("chairType",chairType); seats.put("remoteMode",1);
        seats.put("mainHeat",mainHeat); seats.put("mainVentilation",0);
        seats.put("copilotHeat",copilotHeat); seats.put("copilotVentilation",0);
        for(String rear:List.of("lrSeatHeatState","lrSeatVentilationState","lrThirdHeatState","lrThirdVentilationState",
                "rrSeatHeatState","rrSeatVentilationState","rrThirdHeatState","rrThirdVentilationState")) seats.put(rear,0);
        seats.put("steeringWheelHeatState",0);
        return seats;
    }
    /** Seat status (1=off, 2=low, 3=high) to the command scale (3=off, 2=low, 1=high); anything else is 0 (no change). */
    static int seatCommandLevel(int status) {
        switch(status) { case 1: return 3; case 2: return 2; case 3: return 1; default: return 0; }
    }
    public void command(String vin,String command,Map<String,Object> params) throws Exception {
        if (!COMMANDS.contains(command)) throw new IllegalArgumentException("Unsupported command");
        Map<String,String> inner=base(vin);
        inner.put("commandPwd",Crypto.md5(pin)); inner.put("commandType",command);
        if (params!=null) inner.put("controlParamsMap",json(params));
        JSONObject accepted=trigger(inner);
        if (finished(accepted)) return;
        String serial=accepted.optString("requestSerial");
        if (serial.isEmpty()) throw new IOException("Command result unknown (no request serial); check car before retrying");
        for(int attempt=0;attempt<20;attempt++) {
            Thread.sleep(1500);
            Map<String,String> poll=base(vin);
            poll.put("commandPwd",Crypto.md5(pin)); poll.put("commandType",command); poll.put("requestSerial",serial);
            if (inner.containsKey("controlParamsMap")) poll.put("controlParamsMap",inner.get("controlParamsMap"));
            if (finished((JSONObject)request("/control/remoteControlResult",poll))) return;
        }
        throw new IOException("Command result unknown; check car before retrying");
    }
    /**
     * Sends the command. A car still busy with the previous one refuses it without acting (6024), so it's sent again
     * after 5 seconds, up to 3 times, as pyBYD does. Any other refusal is never retried.
     */
    private JSONObject trigger(Map<String,String> inner) throws Exception {
        for(int attempt=1;;attempt++) {
            try { return (JSONObject)request("/control/remoteControl",inner); }
            catch(CarBusy busy) {
                if(attempt==3) throw busy;
                Thread.sleep(busyWaitMs);
                renewed(inner);
            }
        }
    }
    /** True on success, false while pending; throws on rejection. controlState 0/1/2 or res 1=pending, 2=success. */
    static boolean finished(JSONObject status) throws IOException, JSONException {
        if (status.has("controlState")) {
            int state=status.getInt("controlState");
            if (state==0) return false;
            if (state==1) return true;
            throw new IOException("Vehicle rejected command; no retry");
        }
        if (status.has("res")) {
            int res=status.getInt("res");
            if (res==1) return false;
            if (res==2) return true;
            throw new IOException("Vehicle rejected command; no retry");
        }
        return false;
    }
    private JSONObject post(String endpoint,Map<String,String> fields) throws Exception {
        String digest=Crypto.md5(json(fields)).toLowerCase(Locale.ROOT);
        fields.put("checkcode",digest.substring(24)+digest.substring(8,16)+digest.substring(16,24)+digest.substring(0,8));
        byte[] body=("{\"request\":"+JSONObject.quote(codec.encode(json(fields)))+"}").getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection=(HttpURLConnection)new URL(host+endpoint).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST"); connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type","application/json; charset=UTF-8");
        connection.setRequestProperty("User-Agent","okhttp/4.12.0"); connection.setRequestProperty("Accept-Encoding","identity");
        if(!cookies.isEmpty()) connection.setRequestProperty("Cookie",String.join("; ",cookies.values()));
        try {
            try(OutputStream stream=connection.getOutputStream()) { stream.write(body); }
            int status=connection.getResponseCode();
            if(status!=200) throw new IOException("BYD HTTP "+status+"; no retry");
            for(Map.Entry<String,List<String>> header:connection.getHeaderFields().entrySet()) {
                if("Set-Cookie".equalsIgnoreCase(header.getKey())) for(String value:header.getValue()) {
                    String cookie=value.split(";",2)[0]; cookies.put(cookie.split("=",2)[0],cookie);
                }
            }
            String text;
            try(InputStream stream=connection.getInputStream()) { text=new String(stream.readAllBytes(),StandardCharsets.UTF_8); }
            String decoded=codec.decode(new JSONObject(text).getString("response"));
            if(decoded.startsWith("F{")) decoded=decoded.substring(1);
            JSONObject result=new JSONObject(decoded);
            String code=String.valueOf(result.get("code"));
            if(!code.equals("0")) {
                if(SESSION_ENDED.contains(code)) { token=null; throw new SessionEnded(code); }
                if(code.equals("6024")) throw new CarBusy();
                if(Set.of("1003","1004").contains(code)) token=null;
                throw new IOException("BYD API rejected request (code "+(code.matches("[0-9]+")?code:"unknown")+"); no retry");
            }
            return result;
        } finally { connection.disconnect(); }
    }
}
