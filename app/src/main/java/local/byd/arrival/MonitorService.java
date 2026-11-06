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
    private LocationListener gpsListener;
    private ConnectivityManager connectivity;
    /** Connected Wi-Fi name, null when none. Only touched on the main thread. */
    private String currentWifi;
    private String lastDistance="No fix";
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
    /** GPS mode first reads the car's position from BYD, then tracks the phone against it. */
    private void startGps() {
        AppRuntime.note(this,"Finding the car's position…");
        AppRuntime.IO.execute(()-> {
            try {
                double[] at=AppRuntime.api(this).location(AppRuntime.prefs(this).getString("vin",""));
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
        carLatitude=car[0]; carLongitude=car[1];
        lastCheck=SystemClock.elapsedRealtime();
        android.content.SharedPreferences prefs=AppRuntime.prefs(this);
        fireMetres=AppRuntime.fireMetres(this); rearmMetres=AppRuntime.rearmMetres(this);
        proximity=new ProximityGate(fireMetres,rearmMetres,prefs.getBoolean("gateArmed",false));
        showProximity("No fix");
        gpsListener=new LocationListener() {
            @Override public void onLocationChanged(Location fix) { onFix(fix); }
        };
        locations=getSystemService(LocationManager.class);
        // Fused combines GPS, Wi-Fi and cell, so fixes keep coming indoors; GPS alone often has none there.
        String provider=locations.hasProvider(LocationManager.FUSED_PROVIDER)?LocationManager.FUSED_PROVIDER:LocationManager.GPS_PROVIDER;
        LocationRequest request=new LocationRequest.Builder(AppRuntime.intervalSeconds(this)*1000L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build();
        locations.requestLocationUpdates(provider,request,getMainExecutor(),gpsListener);
        connectivity=getSystemService(ConnectivityManager.class);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),wifiCallback);
        AppRuntime.status(this,"Location monitoring on.");
    }
    /** Saves the disarmed state before any start, so a restart cannot repeat a start. */
    private void onFix(Location fix) {
        double metres=ProximityGate.metres(fix.getLatitude(),fix.getLongitude(),carLatitude,carLongitude);
        ProximityGate.Event event=proximity.observe(metres,fix.getAccuracy(),cooldownPassed() && !onTrustedWifi());
        showProximity(Math.round(metres)+" m");
        if(event==ProximityGate.Event.LEFT) checkCar(Math.round(metres)+" m ±"+Math.round(fix.getAccuracy())+" m");
        if(event!=ProximityGate.Event.FIRE) return;
        AppRuntime.prefs(this).edit().putLong("lastAutoStartAt",System.currentTimeMillis()).putBoolean("gateArmed",false).commit();
        AppRuntime.proximityEvent(this,"Triggered at "+Math.round(metres)+" m");
        arrival();
    }
    /** A car check is running, and when the last one ended (elapsed time), so they run at most once a minute. */
    private boolean checking;
    private long lastCheck;
    /**
     * You got away from the car's saved spot, by walking or by driving it. BYD is asked where the car is: at most once
     * a minute, and only until you have walked away from where it's parked, so a parked car is rarely asked.
     */
    private void checkCar(String reading) {
        if(checking || SystemClock.elapsedRealtime()-lastCheck<60000) return;
        checking=true;
        AppRuntime.IO.execute(()-> {
            try {
                double[] car=AppRuntime.api(this).location(AppRuntime.prefs(this).getString("vin",""));
                handler.post(()->carChecked(car,reading));
            } catch(Exception error) {
                AppRuntime.clearSession();
                handler.post(()-> {
                    checking=false; lastCheck=SystemClock.elapsedRealtime();
                    AppRuntime.note(this,"Couldn't check where the car is: "+AppRuntime.safeError(error)+" Trying again in a minute.");
                });
            }
        });
    }
    /**
     * Moved more than the re-arm distance: you drove it, so its new spot is saved and the trigger waits for you to leave it.
     * Still there: you walked away, so the trigger arms and, if it had fired, the walk-away action runs.
     */
    private void carChecked(double[] car,String reading) {
        checking=false; lastCheck=SystemClock.elapsedRealtime();
        if(stopped) return;
        long moved=Math.round(ProximityGate.metres(car[0],car[1],carLatitude,carLongitude));
        boolean drove=moved>rearmMetres, wasArmed=proximity.armed();
        if(drove) { carLatitude=car[0]; carLongitude=car[1]; }
        proximity.carChecked(drove);
        AppRuntime.prefs(this).edit().putBoolean("gateArmed",proximity.armed()).commit();
        if(drove) AppRuntime.proximityEvent(this,"Car moved "+moved+" m: following it");
        else if(!wasArmed) AppRuntime.proximityEvent(this,"Armed by GPS: "+reading);
        showProximity(drove?"Car moved":lastDistance);
        if(!drove && !wasArmed) leave(AppRuntime.leaveAction(this));
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
        showProximity(lastDistance);
    }
    /** Minimum minutes between automatic starts, so passing the car again soon does not start it again. */
    private boolean cooldownPassed() {
        android.content.SharedPreferences prefs=AppRuntime.prefs(this);
        long minutes=AppRuntime.cooldownMinutes(this);
        return System.currentTimeMillis()-prefs.getLong("lastAutoStartAt",0)>=minutes*60000L;
    }
    private void showProximity(String distance) {
        lastDistance=distance;
        AppRuntime.setProximity(this,proximity.armed(),distance);
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
        AppRuntime.execute(this,List.of(action),false,()->{});
    }
    private void arrival() { AppRuntime.execute(this,Actions.arrival(AppRuntime.arrivalAction(this),AppRuntime.customs(this)),true,()->{}); }
    @Override public void onDestroy() {
        stopped=true;
        handler.removeCallbacksAndMessages(null);
        if(locations!=null) locations.removeUpdates(gpsListener);
        if(connectivity!=null) connectivity.unregisterNetworkCallback(wifiCallback);
        try {
            if(scanner!=null && (Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED))
                scanner.stopScan(callback);
        } catch(IllegalStateException ignored) {
            // Bluetooth can be disabled externally while the service is stopping.
        }
        AppRuntime.setProximity(this,false,"Off");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
