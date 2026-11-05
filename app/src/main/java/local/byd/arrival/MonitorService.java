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
        @Override public void run() { gate.tick(SystemClock.elapsedRealtime()-started); handler.postDelayed(this,1000); }
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
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if("STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        if(active!=null) return START_NOT_STICKY;
        active=intent.getStringExtra("mode");
        AppRuntime.prefs(this).edit().putString("monitorMode",active).apply();
        boolean gps="gps".equals(active);
        startForeground(11,AppRuntime.notification(this,"Monitoring the car. Tap Stop to disable.","monitor"),
            gps?ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION:ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        try {
            if(gps) startGps(); else startBle();
        } catch(Exception error) {
            AppRuntime.status(this,"Cannot monitor: "+error.getMessage()); stopSelf();
        }
        return START_NOT_STICKY;
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
                handler.post(()->stopWithStatus(AppRuntime.safeError(error)));
            }
        });
    }
    private void beginGps(double[] car) {
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED) {
            stopWithStatus("Allow location for this app, then try again."); return;
        }
        carLatitude=car[0]; carLongitude=car[1];
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
        LocationRequest request=new LocationRequest.Builder(5000).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build();
        locations.requestLocationUpdates(provider,request,getMainExecutor(),gpsListener);
        connectivity=getSystemService(ConnectivityManager.class);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),wifiCallback);
        AppRuntime.prefs(this).edit().putBoolean("monitoring",true).apply();
        AppRuntime.status(this,"Location monitoring on.");
    }
    /** Saves the armed state on every change, before any start, so a restart cannot repeat a start. */
    private void onFix(Location fix) {
        boolean wasArmed=proximity.armed();
        double metres=ProximityGate.metres(fix.getLatitude(),fix.getLongitude(),carLatitude,carLongitude);
        boolean fire=proximity.observe(metres,fix.getAccuracy(),cooldownPassed() && !onTrustedWifi());
        if(fire) AppRuntime.prefs(this).edit().putLong("lastAutoStartAt",System.currentTimeMillis()).commit();
        if(wasArmed!=proximity.armed()) {
            AppRuntime.prefs(this).edit().putBoolean("gateArmed",proximity.armed()).commit();
            AppRuntime.proximityEvent(this,fire?"Triggered at "+Math.round(metres)+" m"
                :"Armed by GPS: "+Math.round(metres)+" m ±"+Math.round(fix.getAccuracy())+" m");
        }
        showProximity(Math.round(metres)+" m");
        if(fire) arrival();
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
        gate=new ArrivalGate(AppRuntime.prefs(this).getInt("rssi",-65),60000);
        scanner.startScan(List.of(new ScanFilter.Builder().setDeviceAddress(address).build()),
            new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),callback);
        handler.post(tick);
        AppRuntime.prefs(this).edit().putBoolean("monitoring",true).apply();
        AppRuntime.status(this,"Bluetooth monitoring started. Leave signal range for 60 seconds before approaching.");
    }
    private void stopWithStatus(String message) { AppRuntime.status(this,message); stopSelf(); }
    private void arrival() { AppRuntime.execute(this,List.of(AppRuntime.arrivalAction(this)),true,()->{}); }
    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if(locations!=null) locations.removeUpdates(gpsListener);
        if(connectivity!=null) connectivity.unregisterNetworkCallback(wifiCallback);
        try {
            if(scanner!=null && (Build.VERSION.SDK_INT<31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED))
                scanner.stopScan(callback);
        } catch(IllegalStateException ignored) {
            // Bluetooth can be disabled externally while the service is stopping.
        }
        AppRuntime.prefs(this).edit().putBoolean("monitoring",false).apply();
        AppRuntime.setProximity(this,false,"Off");
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
