package local.byd.arrival;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.*;
import android.net.*;
import android.os.*;
import java.util.*;

/** Arrival trigger, screen off included: GPS distance to the parked car ("gps") or a BLE beacon ("ble"). */
public final class MonitorService extends Service {
    private int fireMetres, rearmMetres;
    private String active;
    private double carLatitude, carLongitude;
    private ProximityGate proximity;
    private LocationManager locations;
    private LocationListener fusedListener, satelliteListener;
    private ConnectivityManager connectivity;
    /** Connected Wi-Fi name, null when none. Only touched on the main thread. */
    private String currentWifi;
    private String lastDistance="No fix";
    /** The last car check found the car moving with you; until you walk away from it, the widget says "With the car". */
    private boolean withCar;
    /** When a reading last showed you moving faster than walking (elapsed time). */
    private long lastFastAt=-60000;
    /** Car checks in a row that found the car at its spot while you were moving (UNSURE), to space them out. */
    private int unsureInRow;
    /** Readings faster than walking were seen and the trip hasn't been closed with a car check yet. */
    private boolean onTrip;
    private long lastDistanceAt;
    private final ConnectivityManager.NetworkCallback wifiCallback=new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps) {
            String name=AppRuntime.wifiName(caps);
            int signal=caps.getSignalStrength();
            if(name!=null) handler.post(()->wifiChanged(name,signal));
        }
        @Override public void onLost(Network network) { handler.post(()->currentWifi=null); }
    };
    private BluetoothLeScanner scanner;
    private ArrivalGate gate;
    private long started;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            if(gate.tick(SystemClock.elapsedRealtime()-started)) leave(AppRuntime.leaveAction(MonitorService.this));
            handler.postDelayed(this,1000);
        }
    };
    private final ScanCallback callback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult result) {
            if(gate.observe(SystemClock.elapsedRealtime()-started,result.getRssi())) arrival();
        }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            // Delayed batches do not prove current proximity. Only fresh live sightings count.
        }
        @Override public void onScanFailed(int code) {
            AppRuntime.status(MonitorService.this,"Bluetooth scan failed ("+code+"). Monitoring stopped."); stopSelf();
        }
    };
    /** Sticky: if Android ends the app while monitoring, it restarts this service with no intent, in the mode last used. */
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "STOP".equals(intent.getAction())) { AppRuntime.stopMonitoring(this); return START_NOT_STICKY; }
        if(active!=null) return START_STICKY;
        if(intent==null) AppRuntime.proximityEvent(this,"Restarted after Android stopped the app");
        active=intent!=null?intent.getStringExtra("mode"):AppRuntime.prefs(this).getString("monitorMode","gps");
        AppRuntime.prefs(this).edit().putString("monitorMode",active).putBoolean("monitoring",true).apply();
        boolean gps="gps".equals(active);
        startForeground(11,AppRuntime.notification(this,"Monitoring the car. Tap Stop to disable.","monitor"),
            gps?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        try {
            if(gps) startGps(); else startBle();
        } catch(Exception error) {
            AppRuntime.status(this,"Cannot monitor: "+error.getMessage()); stopSelf();
        }
        return START_STICKY;
    }
    /** GPS mode first reads the car's position from BYD, then tracks the phone against it. Paused, BYD isn't asked. */
    private void startGps() {
        if(AppRuntime.abroad(this)) { beginAbroad(); return; }
        AppRuntime.note(this,"Finding the car's position…");
        AppRuntime.IO.execute(()-> {
            try {
                double[] at=AppRuntime.api(this).location(AppRuntime.prefs(this).getString("vin",""));
                logCarPosition("monitoring started",at);
                handler.post(()->beginGps(at));
            } catch(Exception error) {
                AppRuntime.clearSession();
                handler.post(()->retryLater(AppRuntime.safeError(error)));
            }
        });
    }
    private long retryMs=60000;
    /** No car position yet (no network right after a reboot, BYD busy, no fix): try again after 1 minute, doubling up to 15. */
    private void retryLater(String reason) {
        String at=android.text.format.DateFormat.format("HH:mm",System.currentTimeMillis()+retryMs).toString();
        AppRuntime.note(this,reason+" Trying again at "+at+".");
        handler.postDelayed(this::startGps,retryMs);
        retryMs=Math.min(retryMs*2,15*60000L);
    }
    /** Set when the service ends, so a car check that answers afterwards changes nothing. */
    private boolean stopped;
    private void beginGps(double[] car) {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            stopWithStatus("Allow location for this app, then try again."); return;
        }
        stopTracking();
        carLatitude=car[0]; carLongitude=car[1];
        AppRuntime.saveCar(this,carLatitude,carLongitude);
        lastCheck=SystemClock.elapsedRealtime();
        handler.removeCallbacks(periodicCheck);
        handler.postDelayed(periodicCheck,15*60_000L);
        android.content.SharedPreferences prefs=AppRuntime.prefs(this);
        fireMetres=AppRuntime.fireMetres(this); rearmMetres=AppRuntime.rearmMetres(this);
        proximity=new ProximityGate(fireMetres,rearmMetres,prefs.getBoolean("gateArmed",false));
        showProximity("No fix",System.currentTimeMillis());
        locations=getSystemService(LocationManager.class);
        LocationRequest request=new LocationRequest.Builder(AppRuntime.intervalSeconds(this)*1000L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build();
        // Fused adds Wi-Fi and cell positions, so readings keep coming indoors. GPS is asked separately, because fused can
        // fall back to a rough network position (seen: 1991 m off for minutes beside the car) while satellites are available.
        if(locations.hasProvider(LocationManager.FUSED_PROVIDER)) {
            fusedListener=this::onFix;
            locations.requestLocationUpdates(LocationManager.FUSED_PROVIDER,request,getMainExecutor(),fusedListener);
        }
        if(locations.hasProvider(LocationManager.GPS_PROVIDER)) {
            satelliteListener=this::onFix;
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,request,getMainExecutor(),satelliteListener);
        }
        connectivity=getSystemService(ConnectivityManager.class);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),wifiCallback);
        AppRuntime.status(this,"Location monitoring on.");
    }
    /** Abroad mode, switched on by hand: nothing is tracked or asked until it's switched off in Settings. */
    private void beginAbroad() {
        stopTracking();
        AppRuntime.setProximity(this,false,"Abroad mode",System.currentTimeMillis());
        AppRuntime.status(this,"Abroad mode: auto-start paused until you switch it off in Settings.");
    }
    /** Stops location readings and Wi-Fi arming, before switching between normal and paused tracking. */
    private void stopTracking() {
        if(fusedListener!=null) locations.removeUpdates(fusedListener);
        if(satelliteListener!=null) locations.removeUpdates(satelliteListener);
        fusedListener=null; satelliteListener=null;
        if(connectivity!=null) connectivity.unregisterNetworkCallback(wifiCallback);
        connectivity=null;
    }
    /** Saves the disarmed state before any start, so a restart cannot repeat a start. */
    private void onFix(Location fix) {
        lastFix=fix;
        long age=(SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos())/1000000;
        double metres=ProximityGate.metres(fix.getLatitude(),fix.getLongitude(),carLatitude,carLongitude);
        if(metres>AppRuntime.restartKm(this)*1000.0) { impossibleDistance(metres); return; }
        boolean rough=fix.getAccuracy()>rearmMetres, late=age>30000;
        // Faster than walking you're in a vehicle (or running), not walking up to the car, so it never starts then.
        boolean moving=ProximityGate.movingFast(fix.hasSpeed(),fix.getSpeed(),fix.getAccuracy(),rearmMetres);
        if(moving) { lastFastAt=SystemClock.elapsedRealtime(); onTrip=true; }
        // A reading delivered more than 30 s late can't start or arm anything. It's shown with its own time, so it looks stale.
        ProximityGate.Event event=late?ProximityGate.Event.NONE
            :proximity.observe(metres,fix.getAccuracy(),cooldownPassed() && !onTrustedWifi() && !moving
                && !verifying && SystemClock.elapsedRealtime()>=verifyRetryAt);
        AppRuntime.log(this,fix.getProvider()+" "+Math.round(metres)+" m ±"+Math.round(fix.getAccuracy())+" m, "+age/1000+" s old"
            +(moving?", "+Math.round(fix.getSpeed()*3.6)+" km/h":"")+(event==ProximityGate.Event.NONE?"":", "+event));
        // Following the car, the distance to its last checked spot means nothing: you're in it.
        String shown=withCar && !proximity.armed()?"With the car":Math.round(metres)+" m"+(rough?" ±"+Math.round(fix.getAccuracy()):"");
        showProximity(shown,System.currentTimeMillis()-age);
        String reading=Math.round(metres)+" m ±"+Math.round(fix.getAccuracy())+" m";
        if(event==ProximityGate.Event.LEFT) checkCar(reading,60000,false);
        // Leaving is only seen after a good reading beside the car. Without one (GPS is often rough there), the car could
        // be driven off unnoticed, so while you're in a vehicle the app also checks whether the car moves with you.
        else if(moving && !late) checkCar(reading,ProximityGate.vehicleCheckGapMs(unsureInRow),false);
        // Two minutes without fast readings ends the trip: one car check follows the car to where it was parked, even
        // if checks during the drive failed (seen: BYD busy, 1008), and the next trip starts with checks every minute.
        else if(onTrip && !late && SystemClock.elapsedRealtime()-lastFastAt>2*60_000 && checkCar(reading,0,true)) {
            onTrip=false; unsureInRow=0;
        }
        if(event==ProximityGate.Event.FIRE) verifyThenArrive(Math.round(metres));
    }
    /** An arrival is being confirmed with BYD, and when a failed confirmation may be tried again (elapsed time). */
    private boolean verifying;
    private long verifyRetryAt;
    /**
     * Someone else may have the car, so its saved spot can be out of date. Before unlocking, BYD is asked where the car
     * is: only if it's really here do the unlock and start run. Otherwise its position is updated and the trigger stays armed.
     */
    private void verifyThenArrive(long metres) {
        verifying=true;
        AppRuntime.IO.execute(()-> {
            try {
                double[] car=AppRuntime.api(this).location(AppRuntime.prefs(this).getString("vin",""));
                logCarPosition("before unlocking",car);
                handler.post(()->arrivalChecked(car,metres));
            } catch(Exception error) {
                AppRuntime.clearSession();
                handler.post(()-> {
                    verifying=false; verifyRetryAt=SystemClock.elapsedRealtime()+30_000;
                    if(stopped) return;
                    proximity.arm();
                    AppRuntime.note(this,"Couldn't confirm the car is here: "+AppRuntime.safeError(error)+" Not unlocking; trying again in 30 seconds.");
                });
            }
        });
    }
    private void arrivalChecked(double[] car,long metres) {
        verifying=false;
        if(stopped || AppRuntime.abroad(this)) return;
        Location you=lastFix;
        double apart=ProximityGate.metres(car[0],car[1],you.getLatitude(),you.getLongitude());
        carLatitude=car[0]; carLongitude=car[1]; AppRuntime.saveCar(this,carLatitude,carLongitude);
        if(!ProximityGate.carIsHere(apart,fireMetres)) {
            proximity.arm(); proximity.spotChanged();
            AppRuntime.prefs(this).edit().putBoolean("gateArmed",true).commit();
            AppRuntime.proximityEvent(this,"Not unlocking: the car is "+Math.round(apart)+" m from you, not at its saved spot. Position updated");
            return;
        }
        AppRuntime.prefs(this).edit().putLong("lastAutoStartAt",System.currentTimeMillis()).putBoolean("gateArmed",false).commit();
        AppRuntime.proximityEvent(this,"Triggered at "+metres+" m");
        arrival();
    }
    /** Every 15 minutes, a follow-only check, so the car is followed while someone else drives it. */
    private final Runnable periodicCheck=new Runnable() {
        @Override public void run() {
            if(!AppRuntime.abroad(MonitorService.this)) checkCar("periodic check",0,true);
            handler.postDelayed(this,15*60_000L);
        }
    };
    /** When tracking last restarted for an impossible distance (elapsed time); never at first. */
    private long lastRestart=-5*60_000L;
    /**
     * A reading farther from the car than the restart distance (default 700 km; Israel is about 420 km long) is an error,
     * a wrong car position or phone location. It's ignored, and tracking restarts as after an app update: the car's position
     * is read from BYD again and location readings start afresh. At most once every 5 minutes.
     */
    private void impossibleDistance(double metres) {
        long now=SystemClock.elapsedRealtime();
        AppRuntime.log(this,"ignored: "+Math.round(metres/1000)+" km from the car is more than the restart distance");
        if(!ProximityGate.restartDue(metres,AppRuntime.restartKm(this),now-lastRestart)) return;
        lastRestart=now;
        AppRuntime.proximityEvent(this,"Read "+Math.round(metres/1000)+" km from the car, which can't be right: restarting tracking");
        stopTracking(); // no readings until the car's position is read again
        startGps();
    }
    /** Your last location reading, for the log's car-position lines. */
    private volatile Location lastFix;
    /** For the log: how old BYD's car position was and how far it was from your last reading. Never coordinates. */
    private void logCarPosition(String why,double[] car) {
        String age=car[2]>0?(System.currentTimeMillis()/1000-(long)car[2])+" s ago":"at an unknown time";
        Location you=lastFix;
        String apart=you==null?"":", "+Math.round(ProximityGate.metres(car[0],car[1],you.getLatitude(),you.getLongitude()))+" m from your last reading";
        AppRuntime.log(this,"car position ("+why+"): reported "+age+apart);
    }
    /** A car check is running, and when the last one ended (elapsed time), so checks are spaced out. */
    private boolean checking;
    private long lastCheck;
    /**
     * You got away from the car's saved spot, by walking or by driving it, or you're in a vehicle. BYD is asked where the
     * car is, at most once per gapMs: once a minute after leaving or while in a vehicle, less often while the car stays put.
     */
    private boolean checkCar(String reading,long gapMs,boolean followOnly) {
        if(checking || SystemClock.elapsedRealtime()-lastCheck<gapMs) return false;
        checking=true;
        boolean travelling=onTrip || SystemClock.elapsedRealtime()-lastFastAt<2*60_000;
        AppRuntime.IO.execute(()-> {
            try {
                double[] car=AppRuntime.api(this).location(AppRuntime.prefs(this).getString("vin",""));
                logCarPosition(followOnly?"follow-only check":"car check",car);
                handler.post(()->carChecked(car,reading,followOnly,travelling));
            } catch(Exception error) {
                AppRuntime.clearSession();
                handler.post(()-> {
                    checking=false; lastCheck=SystemClock.elapsedRealtime();
                    AppRuntime.note(this,"Couldn't check where the car is: "+AppRuntime.safeError(error)+" Trying again in a minute.");
                    if(followOnly) onTrip=true; // the trip-end check failed: try it again
                });
            }
        });
        return true;
    }
    /**
     * Moved more than the re-arm distance: you drove it, so its new spot is saved and the trigger waits for you to leave it.
     * Still there: you walked away, so the trigger arms and, if it had fired, the walk-away action runs.
     */
    private void carChecked(double[] car,String reading,boolean followOnly,boolean travelling) {
        checking=false; lastCheck=SystemClock.elapsedRealtime();
        if(stopped || AppRuntime.abroad(this)) return;
        long moved=Math.round(ProximityGate.metres(car[0],car[1],carLatitude,carLongitude));
        Location you=lastFix;
        double apart=you==null?Double.MAX_VALUE:ProximityGate.metres(car[0],car[1],you.getLatitude(),you.getLongitude());
        ProximityGate.CarCheck result=ProximityGate.carCheck(moved,rearmMetres,apart,SystemClock.elapsedRealtime()-lastFastAt<15000,
            travelling,followOnly,proximity.visited() || withCar);
        switch(result) {
            case UNSURE:
                // Seen: Turn off sent while pulling out, refused by BYD with 6042. The next check, a minute later, decides.
                if(followOnly) { AppRuntime.log(this,"follow-only check: car still at its saved spot"); return; }
                unsureInRow++;
                AppRuntime.log(this,"car check: car still at its spot but you're moving, so not arming");
                return;
            case MOVED_WITHOUT_YOU:
                // Someone else is driving it (seen: your wife, 10:13 and 17:16): follow it, change nothing else.
                if(!followOnly) unsureInRow++; // you're in another vehicle: space the checks out
                carLatitude=car[0]; carLongitude=car[1]; AppRuntime.saveCar(this,carLatitude,carLongitude);
                proximity.spotChanged(); withCar=false;
                AppRuntime.proximityEvent(this,"Car moved "+moved+" m without you, "+Math.round(apart)+" m from you: position updated");
                return;
            case MOVED_WITH_YOU:
                unsureInRow=0;
                carLatitude=car[0]; carLongitude=car[1]; AppRuntime.saveCar(this,carLatitude,carLongitude);
                proximity.carChecked(true); withCar=true;
                AppRuntime.prefs(this).edit().putBoolean("gateArmed",false).commit();
                AppRuntime.proximityEvent(this,"Car moved "+moved+" m with you: following it");
                showProximity("With the car",System.currentTimeMillis());
                return;
            default: // WALKED_AWAY or AWAY: the car is at its spot and you're away from it
                unsureInRow=0;
                boolean wasArmed=proximity.armed();
                proximity.carChecked(false); withCar=false;
                AppRuntime.prefs(this).edit().putBoolean("gateArmed",true).commit();
                if(!wasArmed) AppRuntime.proximityEvent(this,"Armed by GPS: "+reading);
                showProximity(lastDistance,lastDistanceAt);
                // Only if you were with the car: it never locks or turns off a car someone else is using (seen 17:18).
                if(result==ProximityGate.CarCheck.WALKED_AWAY && !wasArmed) leave(AppRuntime.leaveAction(this));
        }
    }
    private boolean onTrustedWifi() { return currentWifi!=null && AppRuntime.trustedWifi(this).contains(currentWifi); }
    /**
     * A strong signal from a trusted Wi-Fi arms the trigger as if you had walked away; nothing is sent to the car.
     * A weak one, as at the car, is ignored, so Wi-Fi that reaches the car cannot cause repeat starts.
     * Android reports signal changes on a connected network too, so a signal that grows strong later still arms it.
     */
    private void wifiChanged(String name,int signal) {
        boolean joined=!name.equals(currentWifi);
        currentWifi=name;
        if(!onTrustedWifi() || proximity.armed()) return;
        if(signal<AppRuntime.wifiSignal(this)) {
            if(joined) AppRuntime.proximityEvent(this,"Wi-Fi "+name+" too weak to arm: "+signal+" dBm");
            return;
        }
        proximity.arm();
        AppRuntime.prefs(this).edit().putBoolean("gateArmed",true).commit();
        AppRuntime.proximityEvent(this,"Armed by Wi-Fi "+name+": "+signal+" dBm");
        showProximity(lastDistance,lastDistanceAt);
    }
    /** Minimum minutes between automatic starts, so passing the car again soon does not start it again. */
    private boolean cooldownPassed() {
        android.content.SharedPreferences prefs=AppRuntime.prefs(this);
        long minutes=AppRuntime.cooldownMinutes(this);
        return System.currentTimeMillis()-prefs.getLong("lastAutoStartAt",0)>=minutes*60000L;
    }
    /** at: when the shown reading was taken, so the widget's time is the reading's, not the refresh's. */
    private void showProximity(String distance,long at) {
        lastDistance=distance; lastDistanceAt=at;
        AppRuntime.setProximity(this,proximity.armed(),distance,at);
    }
    private void startBle() {
        if(Build.VERSION.SDK_INT>=31 && checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED)
            throw new IllegalStateException("Missing Bluetooth permission");
        String address=AppRuntime.prefs(this).getString("beacon","");
        if(!BluetoothAdapter.checkBluetoothAddress(address)) throw new IllegalStateException("Select a BLE signal");
        BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();
        if(adapter==null || !adapter.isEnabled()) throw new IllegalStateException("Bluetooth is off");
        scanner=adapter.getBluetoothLeScanner();
        started=SystemClock.elapsedRealtime();
        int seconds=AppRuntime.intervalSeconds(this);
        long absent=AutoStartSettings.beaconAbsentMillis(seconds);
        gate=new ArrivalGate(AppRuntime.prefs(this).getInt("rssi",-65),absent);
        filters=List.of(new ScanFilter.Builder().setDeviceAddress(address).build());
        if(seconds<AutoStartSettings.SCAN_ROUNDS_FROM_SECONDS) startScan(); else scanRound(seconds*1000L);
        handler.post(tick);
        AppRuntime.status(this,"Bluetooth monitoring started. Leave signal range for "+absent/1000+" seconds before approaching.");
    }
    private List<ScanFilter> filters;
    /** Permission was checked in startBle; Android ends the app if it is revoked. */
    @android.annotation.SuppressLint("MissingPermission")
    private void startScan() {
        scanner.startScan(filters,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),callback);
    }
    /**
     * Scans for 5 seconds every interval, enough for several sightings of the beacon. Rounds start at least
     * 10 seconds apart, because Android blocks apps that start a scan more than 5 times in 30 seconds.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private void scanRound(long everyMs) {
        startScan();
        handler.postDelayed(()->scanner.stopScan(callback),5000);
        handler.postDelayed(()->scanRound(everyMs),everyMs);
    }
    private void stopWithStatus(String message) { AppRuntime.status(this,message); stopSelf(); }
    /** A failure here doesn't stop monitoring: the trigger is armed, so it can't repeat before your next arrival. */
    private void leave(String action) {
        if(action.isEmpty()) return;
        AppRuntime.execute(this,List.of(action),false,true,()->{});
    }
    private void arrival() { AppRuntime.execute(this,Actions.arrival(AppRuntime.arrivalAction(this),AppRuntime.customs(this)),true,true,()->{}); }
    @Override public void onDestroy() {
        stopped=true;
        handler.removeCallbacksAndMessages(null);
        stopTracking();
        try {
            if(scanner!=null && (Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED))
                scanner.stopScan(callback);
        } catch(IllegalStateException ignored) {
            // Bluetooth can be disabled externally while the service is stopping.
        }
        AppRuntime.setProximity(this,false,"Off",System.currentTimeMillis());
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
