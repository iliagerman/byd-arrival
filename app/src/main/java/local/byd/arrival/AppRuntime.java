package local.byd.arrival;

import android.app.*;
import android.content.*;
import android.net.*;
import android.net.wifi.WifiInfo;
import android.os.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

final class AppRuntime {
    static final ExecutorService IO=Executors.newSingleThreadExecutor();
    static final AtomicBoolean BUSY=new AtomicBoolean();
    private static BydApi api;
    static List<Actions.Custom> customs(Context c) { return Actions.parse(prefs(c).getString("customActions","[]")); }
    static void setCustoms(Context c,List<Actions.Custom> customs) { prefs(c).edit().putString("customActions",Actions.toJson(customs)).apply(); }
    /**
     * What the proximity trigger runs after unlocking. Default: Start car. That puts the car in remote mode, where BYD
     * asks for the key before you can drive, even after another unlock; Unlock alone avoids that.
     */
    static String arrivalAction(Context c) { return prefs(c).getString("arrivalAction",Actions.DEFAULT_ARRIVAL); }
    /** What runs when you walk away after an auto-start. Default: Close & lock. Empty: nothing. */
    static String leaveAction(Context c) { return prefs(c).getString("leaveAction","SECURE"); }
    /** Widget buttons, in order (up to ten). Older versions kept a separate main button; it becomes the first button. */
    static List<String> widgetTiles(Context c) {
        SharedPreferences prefs=prefs(c);
        if(prefs.contains("widgetMain")) {
            List<String> tiles=new ArrayList<>(List.of(prefs.getString("widgetMain","START")));
            for(String code:prefs.getString("widgetTiles","").split(",")) if(!code.isEmpty() && !tiles.contains(code)) tiles.add(code);
            prefs.edit().putString("widgetTiles",String.join(",",tiles)).remove("widgetMain").apply();
        }
        String saved=prefs.getString("widgetTiles","START,CLOSEAIR,OPENDOOR,LOCKDOOR,FINDCAR");
        return saved.isEmpty()?List.of():Arrays.asList(saved.split(","));
    }
    static ClimatePlan climatePlan(Context c) {
        String saved=prefs(c).getString("climatePlan",null);
        return saved==null?ClimatePlan.defaults():ClimatePlan.parse(saved);
    }
    static int fireMetres(Context c) { return prefs(c).getInt("fireMetres",AutoStartSettings.DEFAULT_START); }
    static int rearmMetres(Context c) { return prefs(c).getInt("rearmMetres",AutoStartSettings.DEFAULT_REARM); }
    static int cooldownMinutes(Context c) { return prefs(c).getInt("cooldownMinutes",AutoStartSettings.DEFAULT_MINUTES); }
    static Abroad abroad(Context c) { return new Abroad(prefs(c).getBoolean("abroad",false),prefs(c).getBoolean("abroadBeenFar",false)); }
    static int abroadKm(Context c) { return prefs(c).getInt("abroadKm",AutoStartSettings.DEFAULT_ABROAD_KM); }
    static void setAbroad(Context c,Abroad state) { prefs(c).edit().putBoolean("abroad",state.on).putBoolean("abroadBeenFar",state.beenFar).apply(); }
    /** The car's last position read from BYD, kept so abroad mode can measure the distance without asking BYD. */
    static void saveCar(Context c,double latitude,double longitude) {
        prefs(c).edit().putString("carLatitude",Double.toString(latitude)).putString("carLongitude",Double.toString(longitude)).apply();
    }
    /** null before the car's position was ever read. */
    static double[] savedCar(Context c) {
        SharedPreferences p=prefs(c);
        if(!p.contains("carLatitude")) return null;
        return new double[]{Double.parseDouble(p.getString("carLatitude","")),Double.parseDouble(p.getString("carLongitude",""))};
    }
    static int intervalSeconds(Context c) { return prefs(c).getInt("intervalSeconds",AutoStartSettings.DEFAULT_SECONDS); }
    static int wifiSignal(Context c) { return prefs(c).getInt("wifiSignal",AutoStartSettings.DEFAULT_WIFI_SIGNAL); }
    static void saveAutoStart(Context c,AutoStartSettings values) {
        prefs(c).edit().putInt("fireMetres",values.start).putInt("rearmMetres",values.rearm).putInt("cooldownMinutes",values.minutes).apply();
    }
    static int engineMinutes(Context c) { return prefs(c).getInt("engineMinutes",30); }
    static int icon(String code) {
        switch(code) {
            case "START": return R.drawable.ic_power;
            case "CLOSEAIR": return R.drawable.ic_power_off;
            case "OPENDOOR": return R.drawable.ic_lock_open;
            case "LOCKDOOR": return R.drawable.ic_lock;
            case "SECURE": return R.drawable.ic_secure;
            case "OPENTRUNK": case "CLOSETRUNK": return R.drawable.ic_trunk;
            case "OPENAIR": return R.drawable.ic_ac;
            case "SEAT_DRIVER": case "SEAT_PASSENGER": return R.drawable.ic_seat;
            case "CLOSEWINDOW": return R.drawable.ic_window;
            case "FLASHLIGHTNOWHISTLE": return R.drawable.ic_light;
            case "FINDCAR": return R.drawable.ic_horn;
            default: return R.drawable.ic_play;
        }
    }
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("settings",0); }
    static void clearSession() { api=null; }
    /** Sign-in saves both. Installs signed in before regions existed can only be Israel on the Europe server. */
    static String country(Context c) { return prefs(c).getString("country","IL"); }
    static String server(Context c) { return prefs(c).getString("server","eu"); }
    static BydApi api(Context c) throws Exception {
        if(api==null) {
            JSONObject credentials=new Vault(c).load();
            try(InputStream stream=c.getAssets().open("bangcle_tables.bin")) {
                api=new BydApi(stream.readAllBytes(),credentials.getString("username"),credentials.getString("password"),credentials.getString("pin"),
                    Regions.host(server(c)),country(c),TimeZone.getDefault().getID());
            }
        }
        return api;
    }
    static void channels(Context c) {
        NotificationManager manager=c.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("monitor","Bluetooth monitoring",NotificationManager.IMPORTANCE_LOW));
        manager.createNotificationChannel(new NotificationChannel("results","Car action results",NotificationManager.IMPORTANCE_DEFAULT));
    }
    static Notification notification(Context c,String text,String channel) {
        channels(c);
        PendingIntent settings=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(c,channel).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("BYD Arrival").setContentText(text).setContentIntent(settings);
        if(channel.equals("monitor")) {
            PendingIntent stop=PendingIntent.getService(c,2,new Intent(c,MonitorService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            b.setOngoing(true).addAction(new Notification.Action.Builder(null,"Stop automation",stop).build());
        }
        return b.build();
    }
    /**
     * Turns auto-start off on purpose. Only this clears "monitoring", so a restart after a reboot or update
     * still happens after a start that failed (for example with no network yet).
     */
    static void stopMonitoring(Context c) {
        prefs(c).edit().putBoolean("monitoring",false).apply();
        c.stopService(new Intent(c,MonitorService.class));
    }
    /** Updates the status shown in the app and widget, without a notification. */
    static void note(Context c,String message) {
        log(c,"status: "+message);
        prefs(c).edit().putString("status",message).putLong("statusAt",System.currentTimeMillis()).apply();
        ArrivalWidget.updateAll(c);
    }
    /** Wi-Fi network name, or null when the network is not Wi-Fi or its name is hidden (no location permission). */
    static String wifiName(NetworkCapabilities caps) {
        if(caps==null || !(caps.getTransportInfo() instanceof WifiInfo)) return null;
        String name=((WifiInfo)caps.getTransportInfo()).getSSID().replace("\"","");
        return name.equals("<unknown ssid>")?null:name;
    }
    static Set<String> trustedWifi(Context c) { return new HashSet<>(prefs(c).getStringSet("trustedWifi",Set.of())); }
    static void setTrustedWifi(Context c,Set<String> names) { prefs(c).edit().putStringSet("trustedWifi",new HashSet<>(names)).apply(); }
    /** Proximity indicator for the app and widget, stamped with the time of the reading it shows (at). */
    static void setProximity(Context c,boolean armed,String distance,long at) {
        prefs(c).edit().putBoolean("proximityArmed",armed).putString("proximityDistance",distance).putLong("proximityAt",at).apply();
        ArrivalWidget.updateAll(c);
    }
    /** Last arm, trigger or ignored-Wi-Fi event with its time, shown in the app so you can see why the state changed. */
    static void proximityEvent(Context c,String text) {
        prefs(c).edit().putString("proximityEvent",text+" · "+android.text.format.DateFormat.format("HH:mm:ss",System.currentTimeMillis())).apply();
        log(c,"event: "+text);
    }
    /** The last lines of the log, older file first, for sharing. */
    static String logTail(Context c,int lines) throws IOException {
        List<String> all=new ArrayList<>();
        for(String name:List.of("readings.old.log","readings.log")) {
            File file=new File(c.getExternalFilesDir(null),name);
            if(file.exists()) all.addAll(java.nio.file.Files.readAllLines(file.toPath()));
        }
        return String.join("\n",all.subList(Math.max(0,all.size()-lines),all.size()));
    }
    /**
     * Rolling log of location readings, events and statuses, without coordinates. Readable over ADB at
     * /sdcard/Android/data/local.byd.arrival/files/readings.log; the previous half MB is kept in readings.old.log.
     */
    static void log(Context c,String line) {
        File dir=c.getExternalFilesDir(null), file=new File(dir,"readings.log");
        if(file.length()>512*1024 && !file.renameTo(new File(dir,"readings.old.log"))) android.util.Log.w("byd-arrival","Log rotation failed");
        try(FileWriter out=new FileWriter(file,true)) {
            out.write(android.text.format.DateFormat.format("MM-dd HH:mm:ss",System.currentTimeMillis())+" "+line+"\n");
        } catch(IOException error) {
            android.util.Log.w("byd-arrival","Log write failed",error);
        }
    }
    /**
     * Shown next to the icon: armed or not, distance to the car and time of the last reading. Monitoring stopped: "Off".
     * Readings normally arrive every few seconds, so one older than 10 minutes means they stopped, and it says so.
     */
    static String proximityLabel(SharedPreferences p) {
        String distance=p.getString("proximityDistance","Off");
        if(distance.equals("Off")) return distance;
        long at=p.getLong("proximityAt",0);
        String time=android.text.format.DateFormat.format("HH:mm:ss",at).toString();
        if(distance.startsWith("Abroad")) return distance+" · "+time;
        if(System.currentTimeMillis()-at>10*60000L) return "No reading since "+time;
        return (p.getBoolean("proximityArmed",false)?"Armed":"Not armed")+" · "+distance+" · "+time;
    }
    static void status(Context c,String message) {
        note(c,message);
        c.getSystemService(NotificationManager.class).notify(12,notification(c,message,"results"));
    }
    /** Credentials saved and a vehicle selected. */
    static boolean ready(Context c) {
        return c.getSharedPreferences("vault",0).contains("ciphertext") && !prefs(c).getString("vin","").isEmpty();
    }
    static String safeError(Exception error) {
        if(error instanceof java.net.SocketTimeoutException) return "Network timeout. Result may be unknown; check car. No retry.";
        if(error instanceof IOException && error.getMessage()!=null &&
            (error.getMessage().startsWith("BYD ") || error.getMessage().startsWith("Command ") || error.getMessage().startsWith("Vehicle ")))
            return error.getMessage();
        if(error instanceof IllegalArgumentException) return error.getMessage();
        if(error instanceof IllegalStateException) return "Save credentials and select a vehicle first.";
        return "Action failed ("+error.getClass().getSimpleName()+"). No retry. Check settings and car.";
    }
    /**
     * Runs actions (built-in codes or custom ids) once: verifies the stored PIN, then each step, no retries.
     * Records the pressed action and its result (busy, ok, fail) for the widget's badge.
     */
    static void execute(Context context,List<String> actions,boolean automation,Runnable finished) {
        Context c=context.getApplicationContext();
        if(!BUSY.compareAndSet(false,true)) { status(c,"Already running; duplicate ignored."); finished.run(); return; }
        // pref change wakes UI listeners; BUSY is the truth
        prefs(c).edit().putBoolean("busy",true).putString("lastAction",String.join(",",actions)).putString("lastResult","busy").apply();
        IO.execute(()-> {
            PowerManager.WakeLock wake=c.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"byd-arrival:command");
            wake.acquire(180000);
            try {
                String vin=prefs(c).getString("vin","");
                if(vin.isEmpty()) throw new IllegalStateException("Select a vehicle");
                if(actions.isEmpty()) throw new IllegalStateException("Select at least one action");
                status(c,"Authenticating and verifying control PIN…");
                BydApi client=api(c); client.verifyPin(vin);
                List<Actions.Custom> customs=customs(c);
                List<String> notes=new ArrayList<>();
                Routine.run(Actions.expand(actions,customs),step->runStep(c,client,vin,step,notes));
                prefs(c).edit().putString("lastResult","ok").apply();
                refreshBattery(c,()->{}); // the car is awake now, so its battery level is current
                status(c,"Done: "+Actions.labels(actions,customs)+(notes.isEmpty()?"":" ("+String.join(", ",notes)+")")+". Confirmed by BYD.");
            } catch(Exception error) {
                prefs(c).edit().putString("lastResult","fail").apply();
                clearSession();
                if(automation) stopMonitoring(c);
                status(c,safeError(error)+(automation?" Automation stopped.":""));
            } finally {
                if(wake.isHeld()) wake.release();
                BUSY.set(false); prefs(c).edit().putBoolean("busy",false).apply(); finished.run();
            }
        });
    }
    private static void runStep(Context c,BydApi client,String vin,String step,List<String> notes) throws Exception {
        switch(step) {
            case "START": smartStart(c,client,vin,notes); return;
            case "SECURE": closeAndLock(c,client,vin); return;
            case "OPENAIR": {
                int temperature=prefs(c).getInt("temperature",21);
                JSONObject climate=client.climate(vin);
                status(c,"Starting A/C at "+temperature+" °C…");
                client.command(vin,"OPENAIR",BydApi.climateParams(temperature,climate.getInt("vdeTempRangeMin"),0,engineMinutes(c)));
                return;
            }
            case "SEAT_DRIVER": case "SEAT_PASSENGER": {
                JSONObject climate=client.climate(vin);
                boolean driver=step.equals("SEAT_DRIVER");
                seatHeat(c,client,vin,climate,driver,!driver);
                return;
            }
            default:
                status(c,Actions.progress(step));
                client.command(vin,step,null);
        }
    }
    /** Reads the cabin temperature (outside if the car has none), picks a level, starts the A/C, then the chosen seat heat. */
    private static void smartStart(Context c,BydApi client,String vin,List<String> notes) throws Exception {
        status(c,"Reading the car's temperature…");
        JSONObject climate=client.climate(vin);
        int cabin=climate.optInt("tempInCar",NO_READING), outside=climate.optInt("tempOutCar",NO_READING);
        ClimatePlan plan=climatePlan(c);
        String source; ClimatePlan.Level level;
        if(cabin!=NO_READING) { source="cabin "+cabin+" °C"; level=plan.choose(cabin); }
        else if(outside!=NO_READING) { source="outside "+outside+" °C"; level=plan.choose(outside); }
        else { source="no temperature reading"; level=plan.mild; }
        status(c,"Starting: "+level.name.toLowerCase(Locale.ROOT)+", A/C "+level.temperature+" °C, fan "+level.fan+"…");
        client.command(vin,"OPENAIR",BydApi.climateParams(level.temperature,climate.getInt("vdeTempRangeMin"),level.fan,engineMinutes(c)));
        notes.add(source+" → "+level.name+": A/C "+level.temperature+" °C, fan "+level.fan);
        if(level.driverSeat || level.passengerSeat) {
            seatHeat(c,client,vin,climate,level.driverSeat,level.passengerSeat);
            notes.add("seat heat");
        }
    }
    /** Sends every Close & lock step even if one fails, so a rejected A/C off or tailgate close can't leave the car unlocked. */
    private static void closeAndLock(Context c,BydApi client,String vin) throws Exception {
        Map<String,Exception> failed=Routine.runAll(Actions.CLOSE_AND_LOCK,step-> { status(c,Actions.progress(step)); client.command(vin,step,null); });
        if(failed.isEmpty()) return;
        List<String> lines=new ArrayList<>();
        failed.forEach((step,error)->lines.add(Actions.BUILT_IN.get(step)+": "+safeError(error)));
        boolean others=failed.size()<Actions.CLOSE_AND_LOCK.size();
        throw new IOException("Command failed in Close & lock. "+String.join(" ",lines)+(others?" The other steps were confirmed.":""));
    }
    /**
     * Reads the car's battery level (read-only) for the widget, then runs done. A failure keeps the last level and is
     * logged: the widget then shows the older level, which the next refresh replaces.
     */
    static void refreshBattery(Context context,Runnable done) {
        Context c=context.getApplicationContext();
        if(!ready(c)) { done.run(); return; }
        IO.execute(()-> {
            try {
                int percent=BydApi.batteryPercent(api(c).realtime(prefs(c).getString("vin","")));
                log(c,"battery: "+(percent<0?"not reported":percent+"%"));
                if(percent>=0) prefs(c).edit().putInt("batteryPercent",percent).putLong("batteryAt",System.currentTimeMillis()).apply();
            } catch(Exception error) {
                clearSession();
                log(c,"battery check failed: "+safeError(error));
            }
            ArrivalWidget.updateAll(c);
            done.run();
        });
    }
    /** The car reports -129 °C when it has no reading. */
    private static final int NO_READING=-129;
    /** Turns on heat for the chosen seats, one command per seat, keeping the other seat as the car reported it. */
    private static void seatHeat(Context c,BydApi client,String vin,JSONObject climate,boolean driver,boolean passenger) throws Exception {
        int main=BydApi.seatCommandLevel(climate.optInt("mainSeatHeatState",0));
        int copilot=BydApi.seatCommandLevel(climate.optInt("copilotSeatHeatState",0));
        if(driver) { main=1; status(c,"Heating driver seat…"); client.command(vin,"VENTILATIONHEATING",BydApi.seatParams("1",main,copilot)); }
        if(passenger) { copilot=1; status(c,"Heating passenger seat…"); client.command(vin,"VENTILATIONHEATING",BydApi.seatParams("2",main,copilot)); }
    }
}
