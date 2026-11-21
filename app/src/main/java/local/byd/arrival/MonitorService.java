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

/**
 * Arrival trigger, screen off included: GPS distance to the parked car, and the car's Bluetooth beacons when any are
 * chosen. Whichever comes first starts the car, and the other stays silent until you've left. Leaving works the same
 * way: whichever notices first (the beacon gone, or the distance) runs the walk-away action, once per stay.
 */
public final class MonitorService extends Service {
    private int fireMetres, rearmMetres;
    /** onStartCommand ran: Android may call it again for a running service. */
    private boolean running;
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
    /**
     * You've been with the car (an arrival fired, or you drove it) and the walk-away action hasn't run since: the first
     * confirmed departure, by Bluetooth or distance, runs it and clears this. Saved, so a restart doesn't run it twice.
     */
    private boolean walkAwayPending;
    /** An accurate, fresh phone reading taken while a beacon was at its strength: where you were with the car. */
    private Location atCarFix;
    /** When a beacon was last at its strength (wall clock), and when the last beacon departure check started (elapsed). */
    private long lastStrongAt, beaconCheckAt;
    /**
     * The beacon went and GPS once agreed you're away: its departure check is retried every minute until BYD answers,
     * even if GPS later puts you near the car again (seen 21 Nov: the house is about 10 m from where the car parks).
     */
    private boolean beaconLeaving;
    /** A reading 20 to 120 s older than the latest, to tell your speed from how far you moved. */
    private Location speedAnchor;
    /** A car position read from BYD as you approached (and when, elapsed): a start within 90 s of it needn't ask again. */
    private double[] approachCar;
    private long approachCarAt=-1, lastApproachRead=-10*60_000L, lastBeaconLog=-10_000;
    private double lastMetres=Double.MAX_VALUE;
    /** GPS confirmed you walked away from the parked car, but left the walk-away to the beacon, which was still strong. */
    private boolean gpsSawYouLeave;
    /** When an arrival last fired (elapsed): a walk-away decided before it isn't sent, because you came back. */
    private long lastArrivalAt=-1;
    private long lastDistanceAt;
    private final ConnectivityManager.NetworkCallback wifiCallback=new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
        @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps) {
            String name=AppRuntime.wifiName(caps);
            int signal=caps.getSignalStrength();
            if(name!=null) handler.post(()->wifiChanged(name,signal));
        }
        @Override public void onLost(Network network) { handler.post(()-> { currentWifi=null; syncWifiPause(); }); }
    };
    private BluetoothLeScanner scanner;
    private List<Beacons.Beacon> beacons=List.of();
    /** Null while no beacon is being watched. */
    private ArrivalGate beaconGate;
    private List<ScanFilter> filters;
    private long started, scanEveryMs;
    /**
     * Power saving: on a trusted Wi-Fi with nothing left to do, location and Bluetooth pause (wifiPaused); more than
     * 1 km from the car, Bluetooth alone pauses (farFromCar). beaconScanning: Bluetooth is listening now.
     */
    private boolean wifiPaused, farFromCar, beaconScanning;
    private final Handler handler=new Handler(Looper.getMainLooper());
    /** Once a second: has the beacon been out of range long enough, with GPS agreeing you're away from the car? */
    private final Runnable tick=new Runnable() {
        @Override public void run() {
            // While Bluetooth rests the beacon only seems gone, so it decides nothing then.
            if(beaconScanning && beaconGate.absent(SystemClock.elapsedRealtime()-started) && (beaconLeaving || awayFromCar())) beaconGone();
            handler.postDelayed(this,1000);
        }
    };
    private final ScanCallback callback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult result) {
            ScanRecord record=result.getScanRecord();
            String id=Beacons.idFor(result.getDevice().getAddress(),record==null?null:record.getManufacturerSpecificData(0x004C));
            for(Beacons.Beacon beacon:beacons) if(beacon.id.equals(id)) beaconSeen(beacon,result.getRssi());
        }
        @Override public void onBatchScanResults(List<ScanResult> results) {
            // Delayed batches do not prove current proximity. Only fresh live sightings count.
        }
        @Override public void onScanFailed(int code) {
            AppRuntime.note(MonitorService.this,"Bluetooth scan failed ("+code+"): beacons paused; the start distance still works.");
        }
    };
    /** Sticky: if Android ends the app while monitoring, it restarts this service with no intent. */
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent!=null && "STOP".equals(intent.getAction())) { AppRuntime.stopMonitoring(this); return START_NOT_STICKY; }
        if(running) return START_STICKY;
        running=true;
        if(intent==null) AppRuntime.proximityEvent(this,"Restarted after Android stopped the app");
        AppRuntime.prefs(this).edit().putBoolean("monitoring",true).apply();
        startForeground(11,AppRuntime.notification(this,"Monitoring the car. Tap Stop to disable.","monitor"),ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        startGps();
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
        walkAwayPending=prefs.getBoolean("walkAwayPending",false);
        showProximity("No fix",System.currentTimeMillis());
        locations=getSystemService(LocationManager.class);
        wifiPaused=false; farFromCar=false;
        startLocationUpdates();
        connectivity=getSystemService(ConnectivityManager.class);
        connectivity.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),wifiCallback);
        startBeacons();
        AppRuntime.status(this,beaconGate==null?"Location monitoring on.":"Location and Bluetooth monitoring on.");
    }
    /** Abroad mode, switched on by hand: nothing is tracked or asked until it's switched off in Settings. */
    private void beginAbroad() {
        stopTracking();
        AppRuntime.setProximity(this,false,"Abroad mode",System.currentTimeMillis());
        AppRuntime.status(this,"Abroad mode: auto-start paused until you switch it off in Settings.");
    }
    /** Stops location readings and Wi-Fi arming, before switching between normal and paused tracking. */
    private void stopTracking() {
        stopBeacons();
        stopLocationUpdates();
        wifiPaused=false; farFromCar=false;
        if(connectivity!=null) connectivity.unregisterNetworkCallback(wifiCallback);
        connectivity=null;
    }
    /**
     * Fused adds Wi-Fi and cell positions, so readings keep coming indoors. GPS is asked separately, because fused can
     * fall back to a rough network position (seen: 1991 m off for minutes beside the car) while satellites are available.
     * Permission was checked in beginGps.
     */
    @android.annotation.SuppressLint("MissingPermission")
    private void startLocationUpdates() {
        LocationRequest request=new LocationRequest.Builder(AppRuntime.intervalSeconds(this)*1000L).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build();
        if(locations.hasProvider(LocationManager.FUSED_PROVIDER)) {
            fusedListener=this::onFix;
            locations.requestLocationUpdates(LocationManager.FUSED_PROVIDER,request,getMainExecutor(),fusedListener);
        }
        if(locations.hasProvider(LocationManager.GPS_PROVIDER)) {
            satelliteListener=this::onFix;
            locations.requestLocationUpdates(LocationManager.GPS_PROVIDER,request,getMainExecutor(),satelliteListener);
        }
    }
    private void stopLocationUpdates() {
        if(fusedListener!=null) locations.removeUpdates(fusedListener);
        if(satelliteListener!=null) locations.removeUpdates(satelliteListener);
        fusedListener=null; satelliteListener=null;
    }
    /**
     * On a trusted Wi-Fi, armed, with no walk-away action left to run, nothing can happen: auto-start never fires on a
     * trusted Wi-Fi. Location readings and Bluetooth then pause until the phone leaves that Wi-Fi. A walk-away still due
     * (you parked at home and walked in) keeps them on until it's decided.
     */
    private void syncWifiPause() {
        if(connectivity==null) return; // not tracking: abroad mode, or restarting
        boolean pause=onTrustedWifi() && proximity.armed() && !walkAwayPending;
        if(pause==wifiPaused) return;
        wifiPaused=pause;
        if(pause) stopLocationUpdates(); else startLocationUpdates();
        syncBeaconScan();
        AppRuntime.proximityEvent(this,pause?"Armed on "+currentWifi+": location and Bluetooth paused":"Left trusted Wi-Fi: location and Bluetooth back on");
        showProximity(pause?"Paused on "+currentWifi:"No fix",System.currentTimeMillis());
    }
    /**
     * Bluetooth listens unless it can't matter: paused on a trusted Wi-Fi, or more than 1 km from the car, where the
     * beacon can't be heard. Otherwise it listens as chosen in Settings, nonstop by default.
     */
    private void syncBeaconScan() {
        if(beaconGate==null) return;
        boolean listen=!wifiPaused && !farFromCar;
        if(listen==beaconScanning) return;
        beaconScanning=listen;
        if(listen) { if(scanEveryMs==0) startScan(); else handler.post(scanRound); }
        else { handler.removeCallbacks(scanRound); handler.removeCallbacks(stopRound); stopScan(); }
        AppRuntime.log(this,"Bluetooth "+(listen?"listening":"paused: "+(wifiPaused?"trusted Wi-Fi":"over 1 km from the car")));
    }
    /** Saves the disarmed state before any start, so a restart cannot repeat a start. */
    private void onFix(Location fix) {
        lastFix=fix;
        long age=(SystemClock.elapsedRealtimeNanos()-fix.getElapsedRealtimeNanos())/1000000;
        double metres=ProximityGate.metres(fix.getLatitude(),fix.getLongitude(),carLatitude,carLongitude);
        if(metres>AppRuntime.restartKm(this)*1000.0) { impossibleDistance(metres); return; }
        boolean rough=fix.getAccuracy()>rearmMetres, late=age>30000;
        // Faster than walking you're in a vehicle (or running), not walking up to the car, so it never starts then.
        // In a car GPS is often too rough for its speed to count (seen 21 Nov: ±30 to ±130 m while driving), so how far
        // you moved between readings counts too.
        boolean reportedFast=ProximityGate.movingFast(fix.hasSpeed(),fix.getSpeed(),fix.getAccuracy(),rearmMetres);
        boolean moving=reportedFast || movedFast(fix);
        if(moving) { lastFastAt=SystemClock.elapsedRealtime(); onTrip=true; }
        // A reading delivered more than 30 s late can't start or arm anything. It's shown with its own time, so it looks stale.
        ProximityGate.Event event=late?ProximityGate.Event.NONE
            :proximity.observe(metres,fix.getAccuracy(),cooldownPassed() && !onTrustedWifi() && !moving
                && !verifying && SystemClock.elapsedRealtime()>=verifyRetryAt);
        AppRuntime.log(this,fix.getProvider()+" "+Math.round(metres)+" m ±"+Math.round(fix.getAccuracy())+" m, "+age/1000+" s old"
            +(reportedFast?", "+Math.round(fix.getSpeed()*3.6)+" km/h":moving?", moving (by position)":"")+(event==ProximityGate.Event.NONE?"":", "+event));
        // Following the car, the distance to its last checked spot means nothing: you're in it.
        String shown=withCar && !proximity.armed()?"With the car":Math.round(metres)+" m"+(rough?" ±"+Math.round(fix.getAccuracy()):"");
        showProximity(shown,System.currentTimeMillis()-age);
        String reading=Math.round(metres)+" m ±"+Math.round(fix.getAccuracy())+" m";
        // Over 1 km from the car Bluetooth rests; not while you travel with it, since its saved spot lags behind then.
        if(!late) { farFromCar=ProximityGate.beyondBeacon(metres,fix.getAccuracy()) && !withCar && !onTrip; syncBeaconScan(); }
        if(event==ProximityGate.Event.LEFT) checkCar(reading,60000,false,false);
        // Leaving is only seen after a good reading beside the car. Without one (GPS is often rough there), the car could
        // be driven off unnoticed, so while you're in a vehicle the app also checks whether the car moves with you.
        else if(moving && !late) checkCar(reading,ProximityGate.vehicleCheckGapMs(unsureInRow),false,false);
        // Two minutes without fast readings ends the trip: one car check follows the car to where it was parked, even
        // if checks during the drive failed (seen: BYD busy, 1008), and the next trip starts with checks every minute.
        else if(onTrip && !late && SystemClock.elapsedRealtime()-lastFastAt>2*60_000 && checkCar(reading,0,true,false)) {
            onTrip=false; unsureInRow=0;
        }
        if(!late && !moving) readCarOnApproach(metres);
        if(event==ProximityGate.Event.FIRE) verifyThenArrive(Math.round(metres));
    }
    /** Fast by position: comparing with a reading 20 to 120 s older, after taking off both readings' error. */
    private boolean movedFast(Location fix) {
        Location from=speedAnchor;
        long ms=from==null?Long.MAX_VALUE:(fix.getElapsedRealtimeNanos()-from.getElapsedRealtimeNanos())/1_000_000;
        if(ms>120_000) { speedAnchor=fix; return false; }
        if(ms<20_000) return false;
        speedAnchor=fix;
        return ProximityGate.movedFast(ProximityGate.metres(from.getLatitude(),from.getLongitude(),fix.getLatitude(),fix.getLongitude()),
            from.getAccuracy(),fix.getAccuracy(),ms);
    }
    /**
     * Walking up to the car (armed, crossing into 100 m of it, not on a trusted Wi-Fi), BYD is asked where the car is
     * in advance, at most every 5 minutes. By the start distance the answer is in, so unlocking needn't wait for BYD
     * (seen 21 Nov: 19 s at the car).
     */
    private void readCarOnApproach(double metres) {
        boolean crossed=lastMetres>ProximityGate.APPROACH_METRES && metres<=ProximityGate.APPROACH_METRES;
        lastMetres=metres;
        long now=SystemClock.elapsedRealtime();
        if(!crossed || !proximity.armed() || onTrustedWifi() || now-lastApproachRead<5*60_000L) return;
        lastApproachRead=now;
        AppRuntime.IO.execute(()-> {
            try {
                double[] car=AppRuntime.api(this).location(AppRuntime.prefs(this).getString("vin",""));
                logCarPosition("approaching",car);
                handler.post(()-> { approachCar=car; approachCarAt=SystemClock.elapsedRealtime(); });
            } catch(Exception error) {
                AppRuntime.clearSession();
                AppRuntime.log(this,"approaching: couldn't read the car's position: "+AppRuntime.safeError(error));
            }
        });
    }
    /** An arrival is being confirmed with BYD, and when a failed confirmation may be tried again (elapsed time). */
    private boolean verifying;
    private long verifyRetryAt;
    /**
     * Someone else may have the car, so its saved spot can be out of date. Before unlocking, BYD is asked where the car
     * is: only if it's really here do the unlock and start run. Otherwise its position is updated and the trigger stays armed.
     */
    private void verifyThenArrive(long metres) {
        long now=SystemClock.elapsedRealtime();
        // The beacon is in the car: heard at full strength in the last 20 s, the car is right here. A faint signal
        // isn't enough: it could come from the car parked further off by someone else.
        if(beaconGate!=null && beaconGate.strongSince(now-started-20_000)) {
            AppRuntime.log(this,"car is here: its beacon was just heard at full strength, so BYD isn't asked");
            arrivalChecked(new double[]{carLatitude,carLongitude,0},metres); return;
        }
        double[] read=approachCar;
        approachCar=null; // used once: a "car not here" answer isn't repeated from it
        if(read!=null && now-approachCarAt<=90_000) {
            AppRuntime.log(this,"car is here? using BYD's position read as you approached, "+(now-approachCarAt)/1000+" s ago");
            arrivalChecked(read,metres); return;
        }
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
        if(beaconGate!=null) { beaconGate.disarm(); saveBeaconArmed(); }
        setWalkAwayPending(true);
        lastArrivalAt=SystemClock.elapsedRealtime();
        AppRuntime.proximityEvent(this,"Triggered by distance: "+metres+" m");
        arrival("Distance: "+metres+" m from the car");
    }
    /** Every 15 minutes, a follow-only check, so the car is followed while someone else drives it. */
    private final Runnable periodicCheck=new Runnable() {
        @Override public void run() {
            if(!AppRuntime.abroad(MonitorService.this)) checkCar("periodic check",0,true,false);
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
     * You got away from the car's saved spot, by walking or by driving it, you're in a vehicle, or the beacon went
     * (byBeacon). BYD is asked where the car is, at most once per gapMs: once a minute after leaving or while in a
     * vehicle, less often while the car stays put.
     */
    private boolean checkCar(String reading,long gapMs,boolean followOnly,boolean byBeacon) {
        if(checking || SystemClock.elapsedRealtime()-lastCheck<gapMs) return false;
        checking=true;
        boolean travelling=onTrip || SystemClock.elapsedRealtime()-lastFastAt<2*60_000;
        AppRuntime.IO.execute(()-> {
            try {
                double[] car=AppRuntime.api(this).location(AppRuntime.prefs(this).getString("vin",""));
                logCarPosition(followOnly?"follow-only check":byBeacon?"beacon out of range":"car check",car);
                handler.post(()->carChecked(car,reading,followOnly,travelling,byBeacon));
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
    private void carChecked(double[] car,String reading,boolean followOnly,boolean travelling,boolean byBeacon) {
        checking=false; lastCheck=SystemClock.elapsedRealtime();
        if(stopped || AppRuntime.abroad(this)) return;
        long moved=Math.round(ProximityGate.metres(car[0],car[1],carLatitude,carLongitude));
        if(byBeacon) { if(beaconGate!=null) beaconLeft(car,moved); return; }
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
                setWalkAwayPending(false); // someone else has the car: your stay with it is over, so nothing runs for it
                AppRuntime.proximityEvent(this,"Car moved "+moved+" m without you, "+Math.round(apart)+" m from you: position updated");
                return;
            case MOVED_WITH_YOU:
                unsureInRow=0;
                carLatitude=car[0]; carLongitude=car[1]; AppRuntime.saveCar(this,carLatitude,carLongitude);
                proximity.carChecked(true); withCar=true;
                if(beaconGate!=null) { beaconGate.disarm(); saveBeaconArmed(); } // you're in the car: a dip in its signal mustn't start it mid-trip
                beaconLeaving=false;
                setWalkAwayPending(true);
                AppRuntime.prefs(this).edit().putBoolean("gateArmed",false).commit();
                AppRuntime.proximityEvent(this,"Car moved "+moved+" m with you: following it");
                showProximity("With the car",System.currentTimeMillis());
                return;
            default: // WALKED_AWAY or AWAY: the car is at its spot and you're away from it
                unsureInRow=0;
                boolean wasArmed=proximity.armed();
                // The beacon arms only when it is itself out of range: armed now, a beacon still strong 25 m away
                // would start the car again right after the walk-away action.
                proximity.carChecked(false); withCar=false;
                AppRuntime.prefs(this).edit().putBoolean("gateArmed",true).commit();
                if(!wasArmed) AppRuntime.proximityEvent(this,"Armed by distance: "+reading);
                showProximity(lastDistance,lastDistanceAt);
                syncWifiPause();
                // Only if you were with the car: it never locks or turns off a car someone else is using (seen 17:18).
                if(result!=ProximityGate.CarCheck.WALKED_AWAY) return;
                // A beacon at full strength in the last 15 s says you're at the car, whatever GPS says: Bluetooth decides then.
                if(beaconGate!=null && beaconGate.strongSince(SystemClock.elapsedRealtime()-started-15_000)) {
                    gpsSawYouLeave=true;
                    AppRuntime.log(this,"car check: GPS says you left, but the beacon is at full strength: leaving the walk-away to Bluetooth");
                }
                else walkAway("Walk away by distance: "+reading);
        }
    }
    /**
     * BYD's answer after the beacon went. Its position becomes the car's spot (one saved during the drive may be out of
     * date) and both triggers arm. In a vehicle you didn't walk away, so nothing runs. The walk-away action may run only
     * if BYD's position, reported after you parted, puts the car where you were with it: if it has moved since, someone
     * else is driving it (your wife after dropping you off). Without an accurate reading from where you parted, it's
     * left to the distance.
     */
    private void beaconLeft(double[] car,long moved) {
        beaconLeaving=false;
        if(beaconGate.strongSince(beaconCheckAt-started)) { AppRuntime.log(this,"beacon back at full strength: you're at the car"); return; }
        beaconGate.leftCar(); saveBeaconArmed();
        if(SystemClock.elapsedRealtime()-lastFastAt<60_000) {
            AppRuntime.log(this,"beacon out of range while you're in a vehicle: not walking away");
            return;
        }
        carLatitude=car[0]; carLongitude=car[1]; AppRuntime.saveCar(this,carLatitude,carLongitude);
        Location were=atCarFix;
        String trigger="Walk away by Bluetooth: beacon out of range";
        if(were==null || were.getTime()<lastStrongAt-120_000 || car[2]*1000<lastStrongAt) {
            // GPS already confirmed the car at its spot with you on foot, and it hasn't moved since: that decides.
            if(gpsSawYouLeave && moved<=rearmMetres) { gpsSawYouLeave=false; walkAway(trigger); return; }
            // The GPS trigger is left as it is, so the distance can still see you leave and decide.
            AppRuntime.log(this,"walk-away by Bluetooth: no accurate reading from where you left the car, or BYD's position is older; left to the distance");
            return;
        }
        gpsSawYouLeave=false;
        boolean wasArmed=proximity.armed();
        proximity.carChecked(false); withCar=false; unsureInRow=0;
        AppRuntime.prefs(this).edit().putBoolean("gateArmed",true).commit();
        if(!wasArmed) AppRuntime.proximityEvent(this,"Armed by Bluetooth: beacon out of range, car "+moved+" m from its saved spot");
        showProximity(lastDistance,lastDistanceAt);
        syncWifiPause();
        double drift=ProximityGate.metres(car[0],car[1],were.getLatitude(),were.getLongitude());
        if(!ProximityGate.leftWhereYouWere(drift,were.getAccuracy())) {
            if(walkAwayPending) {
                setWalkAwayPending(false);
                AppRuntime.record(this,trigger,List.of(AppRuntime.leaveAction(this)),History.SKIPPED,
                    "Not sent: the car moved "+Math.round(drift)+" m since you were at it, so someone else may be driving it.");
            }
            return;
        }
        walkAway(trigger);
    }
    private void setWalkAwayPending(boolean pending) {
        walkAwayPending=pending;
        AppRuntime.prefs(this).edit().putBoolean("walkAwayPending",pending).commit();
        syncWifiPause();
    }
    /**
     * Runs the walk-away action at most once per stay: the first confirmed departure takes it, so the beacon and the
     * distance can't both run it.
     */
    private void walkAway(String trigger) {
        String action=AppRuntime.leaveAction(this);
        if(!walkAwayPending || action.isEmpty()) return;
        setWalkAwayPending(false);
        sendWalkAway(action,trigger,SystemClock.elapsedRealtime(),1);
    }
    /**
     * Reads BYD's live data first: unless the car reports standing still, someone may be driving it, so nothing is sent.
     * A failed read, or another action still running, is tried again in 30 seconds, 3 times in all.
     */
    private void sendWalkAway(String action,String trigger,long decidedAt,int attempt) {
        if(stopped) return;
        if(AppRuntime.BUSY.get()) { retryWalkAway(action,trigger,decidedAt,attempt,"another action was running"); return; }
        AppRuntime.IO.execute(()-> {
            try {
                org.json.JSONObject live=AppRuntime.api(this).realtime(AppRuntime.prefs(this).getString("vin",""));
                AppRuntime.log(this,"walk-away: car live data: powerGear "+live.optInt("powerGear",-1)+", speed "+live.optDouble("speed",-1));
                handler.post(()->walkAwayChecked(action,trigger,decidedAt,live));
            } catch(Exception error) {
                AppRuntime.clearSession();
                handler.post(()->retryWalkAway(action,trigger,decidedAt,attempt,"couldn't read whether the car is moving: "+AppRuntime.safeError(error)));
            }
        });
    }
    private void retryWalkAway(String action,String trigger,long decidedAt,int attempt,String reason) {
        if(attempt>=3) { AppRuntime.record(this,trigger,List.of(action),History.FAIL,"Not sent: "+reason); return; }
        AppRuntime.log(this,"walk-away: "+reason+"; trying again in 30 seconds");
        handler.postDelayed(()->sendWalkAway(action,trigger,decidedAt,attempt+1),30_000);
    }
    /** If an arrival fired since the walk-away was decided, you came back, so it's dropped. */
    private void walkAwayChecked(String action,String trigger,long decidedAt,org.json.JSONObject live) {
        if(stopped) return;
        if(lastArrivalAt>decidedAt) {
            AppRuntime.record(this,trigger,List.of(action),History.SKIPPED,"Not sent: you came back and the car was started again.");
            return;
        }
        if(!BydApi.standingStill(live)) {
            AppRuntime.record(this,trigger,List.of(action),History.SKIPPED,"Not sent: the car is moving or didn't report its speed, so someone may be driving it.");
            return;
        }
        leave(action,trigger);
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
        if(onTrustedWifi() && !proximity.armed()) {
            if(signal<AppRuntime.wifiSignal(this)) {
                if(joined) AppRuntime.proximityEvent(this,"Wi-Fi "+name+" too weak to arm: "+signal+" dBm");
            } else {
                proximity.arm();
                if(beaconGate!=null) { beaconGate.arm(); saveBeaconArmed(); }
                AppRuntime.prefs(this).edit().putBoolean("gateArmed",true).commit();
                AppRuntime.proximityEvent(this,"Armed by Wi-Fi "+name+": "+signal+" dBm");
                showProximity(lastDistance,lastDistanceAt);
            }
        }
        syncWifiPause();
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
        // Armed when either trigger can start the car.
        AppRuntime.setProximity(this,proximity.armed() || beaconGate!=null && beaconGate.armed(),distance,at);
    }
    /** The car's Bluetooth beacons, if any were chosen in Settings: a strong signal starts the car like the start distance. */
    private void startBeacons() {
        beacons=AppRuntime.beacons(this);
        if(beacons.isEmpty()) return;
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED) {
            AppRuntime.note(this,"Beacons ignored: allow Nearby devices for this app."); return;
        }
        BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();
        if(adapter==null || !adapter.isEnabled()) { AppRuntime.note(this,"Beacons ignored: Bluetooth is off."); return; }
        scanner=adapter.getBluetoothLeScanner();
        started=SystemClock.elapsedRealtime();
        int seconds=AppRuntime.beaconScanSeconds(this);
        beaconGate=new ArrivalGate(AutoStartSettings.beaconAbsentMillis(seconds));
        beaconLeaving=false;
        // Armed before a restart (app update, phone restart), it stays armed: near the car the beacon is still heard,
        // so it couldn't re-arm until you walked well away (seen 20:32, "Not armed · 22 m" right after an update).
        if(AppRuntime.prefs(this).getBoolean("beaconArmed",false)) beaconGate.arm();
        // Filters are required: Android stops delivering unfiltered scan results while the screen is off.
        filters=new ArrayList<>();
        byte[] mask=new byte[22];
        Arrays.fill(mask,(byte)0xff);
        for(Beacons.Beacon beacon:beacons) {
            byte[] iBeacon=Beacons.iBeaconFilter(beacon.id);
            filters.add(iBeacon!=null?new ScanFilter.Builder().setManufacturerData(0x004C,iBeacon,mask).build()
                :new ScanFilter.Builder().setDeviceAddress(Beacons.address(beacon.id)).build());
        }
        scanEveryMs=seconds*1000L;
        beaconScanning=false;
        syncBeaconScan();
        handler.post(tick);
        List<String> strengths=new ArrayList<>();
        for(Beacons.Beacon beacon:beacons) strengths.add(beacon.label+" at "+beacon.threshold+" dBm");
        AppRuntime.log(this,"Bluetooth: "+AutoStartSettings.beaconScanLabel(seconds)+"; "+String.join(", ",strengths));
    }
    /**
     * The beacon has been out of range for the away time and GPS agrees you're away. This arms the Bluetooth trigger.
     * If you had been at the car, BYD is asked where the car is (see beaconLeft), at most once a minute: a check that
     * failed, or couldn't start while another ran, is tried again.
     */
    private void beaconGone() {
        if(!beaconGate.armed()) {
            beaconGate.arm(); saveBeaconArmed();
            AppRuntime.proximityEvent(this,"Armed by Bluetooth: the beacon is out of range");
            showProximity(lastDistance,lastDistanceAt);
        }
        if(!beaconGate.atCar()) return;
        beaconLeaving=true;
        if(checkCar("beacon out of range",60_000,false,true)) beaconCheckAt=SystemClock.elapsedRealtime();
    }
    /**
     * GPS agrees you're away from the car: a reading from the last minute, its whole accuracy margin beyond the start
     * distance, and not in a vehicle for a minute (while driving, the car's saved spot lags behind). So a dip in the
     * beacon's signal while you sit in the car, park or drive can't re-arm it. Without such a reading it waits.
     */
    private boolean awayFromCar() {
        Location you=lastFix;
        if(you==null || SystemClock.elapsedRealtime()-lastFastAt<60_000) return false;
        long age=(SystemClock.elapsedRealtimeNanos()-you.getElapsedRealtimeNanos())/1_000_000;
        double metres=ProximityGate.metres(you.getLatitude(),you.getLongitude(),carLatitude,carLongitude);
        return age<=60_000 && metres-you.getAccuracy()>fireMetres;
    }
    /**
     * A sighting of a chosen beacon, as its margin over the calibrated strength. The beacon is in the car, so a strong
     * signal proves the car is here: unlike the start distance, BYD isn't asked first.
     */
    private void beaconSeen(Beacons.Beacon beacon,int rssi) {
        if(beaconGate==null) return;
        if(rssi>=beacon.threshold) { atCar(); beaconLeaving=false; }
        long now=SystemClock.elapsedRealtime();
        if(beaconGate.armed() && now-lastBeaconLog>=10_000) { lastBeaconLog=now; AppRuntime.log(this,"beacon "+beacon.label+" "+rssi+" dBm (armed)"); }
        boolean mayStart=cooldownPassed() && !onTrustedWifi() && !verifying && SystemClock.elapsedRealtime()-lastFastAt>=15000;
        if(!beaconGate.observe(SystemClock.elapsedRealtime()-started,rssi-beacon.threshold,mayStart)) return;
        saveBeaconArmed();
        proximity.startedByBeacon();
        AppRuntime.prefs(this).edit().putLong("lastAutoStartAt",System.currentTimeMillis()).putBoolean("gateArmed",false).commit();
        setWalkAwayPending(true);
        lastArrivalAt=SystemClock.elapsedRealtime();
        AppRuntime.proximityEvent(this,"Triggered by Bluetooth: "+beacon.label+" at "+rssi+" dBm");
        showProximity(lastDistance,lastDistanceAt);
        arrival("Bluetooth: "+beacon.label+" at "+rssi+" dBm");
    }
    /** Saves whether the Bluetooth trigger is armed, so a restart keeps it, like the distance trigger's. */
    private void saveBeaconArmed() { AppRuntime.prefs(this).edit().putBoolean("beaconArmed",beaconGate.armed()).commit(); }
    /** A beacon at its strength: you're at the car. Keeps the phone's reading if it's fresh and accurate enough to say where. */
    private void atCar() {
        lastStrongAt=System.currentTimeMillis();
        Location you=lastFix;
        if(you==null) return;
        long age=(SystemClock.elapsedRealtimeNanos()-you.getElapsedRealtimeNanos())/1_000_000;
        if(age<=30_000 && you.getAccuracy()<=rearmMetres) atCarFix=you;
    }
    /** Permission was checked in startBeacons; Android ends the app if it is revoked. */
    @android.annotation.SuppressLint("MissingPermission")
    private void startScan() {
        scanner.startScan(filters,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0).build(),callback);
    }
    @android.annotation.SuppressLint("MissingPermission")
    private void stopScan() {
        try { scanner.stopScan(callback); }
        catch(IllegalStateException bluetoothTurnedOff) { /* nothing is scanning then */ }
    }
    private final Runnable stopRound=this::stopScan;
    /**
     * Scans for 5 seconds every chosen interval, enough for several sightings of the beacon. The choices keep rounds
     * at least 10 seconds apart, because Android blocks apps that start a scan more than 5 times in 30 seconds.
     */
    private final Runnable scanRound=new Runnable() {
        @Override public void run() {
            startScan();
            handler.postDelayed(stopRound,5000);
            handler.postDelayed(this,scanEveryMs);
        }
    };
    private void stopBeacons() {
        if(beaconGate==null) return;
        handler.removeCallbacks(tick); handler.removeCallbacks(scanRound); handler.removeCallbacks(stopRound);
        if(beaconScanning) stopScan();
        beaconScanning=false;
        beaconGate=null;
    }
    private void stopWithStatus(String message) { AppRuntime.status(this,message); stopSelf(); }
    /** A failure here doesn't stop monitoring: the trigger is armed, so it can't repeat before your next arrival. */
    private void leave(String action,String trigger) {
        if(stopped) return;
        AppRuntime.execute(this,List.of(action),trigger,false,true,()->{});
    }
    private void arrival(String trigger) {
        AppRuntime.execute(this,Actions.arrival(AppRuntime.arrivalAction(this),AppRuntime.customs(this)),trigger,true,true,()->{});
    }
    @Override public void onDestroy() {
        stopped=true;
        handler.removeCallbacksAndMessages(null);
        stopTracking();
        AppRuntime.setProximity(this,false,"Off",System.currentTimeMillis());
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
