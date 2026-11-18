package local.byd.arrival;

import android.app.*;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.function.Consumer;

/**
 * Settings for the car's Bluetooth beacons: "Your beacons" (trigger strength, Calibrate, Remove) kept apart from what
 * a scan finds nearby. Beacons are listed first and strongest first, so the one in your hand is at the top.
 */
final class BeaconSettings {
    /** Something a scan heard. kind: iBeacon, Eddystone beacon or other device. */
    private static final class Nearby {
        final String id;
        String name, kind;
        int rssi;
        Nearby(String id,String kind) { this.id=id; this.kind=kind; }
        String label() { return name!=null?name:kind.equals("Other device")?"Unnamed device":kind; }
        boolean beacon() { return !kind.equals("Other device"); }
    }
    private final Activity activity;
    private final Runnable changed;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Map<String,Nearby> nearby=new LinkedHashMap<>();
    /** Latest signal of each chosen beacon while a scan runs, shown next to its trigger strength. */
    private final Map<String,Integer> live=new HashMap<>();
    private BluetoothLeScanner scanner;
    private Consumer<ScanResult> listener;
    private final ScanCallback callback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult result) { activity.runOnUiThread(()->listener.accept(result)); }
        @Override public void onScanFailed(int error) { activity.runOnUiThread(()->toast("Bluetooth scan failed ("+error+").")); }
    };
    /** Redraws the lists once a second while scanning, instead of on every one of dozens of sightings. */
    private final Runnable redraw=new Runnable() {
        @Override public void run() { showChosen(); showNearby(); handler.postDelayed(this,1000); }
    };

    BeaconSettings(Activity activity,Runnable changed) {
        this.activity=activity; this.changed=changed;
        activity.findViewById(R.id.scan).setOnClickListener(v->scan());
        showChosen(); showNearby();
    }
    void stop() {
        handler.removeCallbacksAndMessages(null);
        stopScan();
        ((Button)activity.findViewById(R.id.scan)).setText(R.string.scan);
    }
    private void scan() {
        if(!startScan(this::heard)) return;
        nearby.clear();
        Button button=activity.findViewById(R.id.scan);
        button.setText(R.string.scanning);
        handler.post(redraw);
        handler.postDelayed(()-> { stop(); showChosen(); showNearby(); },20000);
    }
    private void heard(ScanResult result) {
        ScanRecord record=result.getScanRecord();
        String id=Beacons.idFor(result.getDevice().getAddress(),record==null?null:record.getManufacturerSpecificData(0x004C));
        boolean eddystone=false;
        if(record!=null && record.getServiceData()!=null)
            for(ParcelUuid uuid:record.getServiceData().keySet()) eddystone|=uuid.toString().startsWith("0000feaa");
        Nearby found=nearby.computeIfAbsent(id,key->new Nearby(key,key.startsWith("ibeacon:")?"iBeacon":"Other device"));
        if(eddystone) found.kind="Eddystone beacon";
        if(record!=null && record.getDeviceName()!=null) found.name=record.getDeviceName();
        found.rssi=result.getRssi();
        live.put(id,result.getRssi());
    }
    private void showChosen() {
        LinearLayout list=activity.findViewById(R.id.beacon_list);
        list.removeAllViews();
        List<Beacons.Beacon> beacons=AppRuntime.beacons(activity);
        activity.findViewById(R.id.beacon_none).setVisibility(beacons.isEmpty()?View.VISIBLE:View.GONE);
        for(Beacons.Beacon beacon:beacons) {
            Integer now=live.get(beacon.id);
            View row=row(list,beacon.label,Beacons.describe(beacon.id)+"\nStarts the car at "+beacon.threshold+" dBm or stronger"
                +(now==null?"":" · now "+now+" dBm"),activity.getString(R.string.calibrate),()->calibrate(beacon));
            View remove=row.findViewById(R.id.row_remove);
            remove.setVisibility(View.VISIBLE);
            remove.setContentDescription(activity.getString(R.string.wifi_remove,beacon.label));
            remove.setOnClickListener(v->remove(beacon));
            list.addView(row);
        }
    }
    private void showNearby() {
        LinearLayout list=activity.findViewById(R.id.nearby_list);
        list.removeAllViews();
        Set<String> chosen=new HashSet<>();
        for(Beacons.Beacon beacon:AppRuntime.beacons(activity)) chosen.add(beacon.id);
        List<Nearby> sorted=new ArrayList<>(nearby.values());
        sorted.removeIf(n->chosen.contains(n.id));
        sorted.sort(Comparator.comparing((Nearby n)->!n.beacon()).thenComparing(n->-n.rssi));
        TextView none=activity.findViewById(R.id.nearby_none);
        none.setVisibility(sorted.isEmpty()?View.VISIBLE:View.GONE);
        none.setText(scanner==null?R.string.nearby_idle:R.string.nearby_listening);
        for(Nearby found:sorted) {
            String detail=(found.id.startsWith("ibeacon:")?Beacons.describe(found.id):found.kind+" · "+Beacons.address(found.id))+" · "+found.rssi+" dBm";
            list.addView(row(list,found.label(),detail,activity.getString(R.string.add_beacon),()->add(found)));
        }
    }
    private View row(LinearLayout list,String name,String detail,String action,Runnable onAction) {
        View row=activity.getLayoutInflater().inflate(R.layout.beacon_row,list,false);
        ((TextView)row.findViewById(R.id.row_name)).setText(name);
        ((TextView)row.findViewById(R.id.row_detail)).setText(detail);
        Button button=row.findViewById(R.id.row_action);
        button.setText(action);
        button.setOnClickListener(v->onAction.run());
        return row;
    }
    /** Added with a typical strength; calibrating at the car sets the real one. */
    private void add(Nearby found) {
        List<Beacons.Beacon> beacons=new ArrayList<>(AppRuntime.beacons(activity));
        beacons.add(new Beacons.Beacon(found.id,found.label(),Beacons.DEFAULT_THRESHOLD));
        AppRuntime.setBeacons(activity,beacons);
        showChosen(); showNearby(); changed.run();
        toast("Added "+found.label()+". Put it in the car, then tap Calibrate beside the car.");
    }
    private void remove(Beacons.Beacon beacon) {
        List<Beacons.Beacon> beacons=new ArrayList<>(AppRuntime.beacons(activity));
        beacons.removeIf(b->b.id.equals(beacon.id));
        AppRuntime.setBeacons(activity,beacons);
        showChosen(); showNearby(); changed.run();
    }
    /**
     * Listens to one beacon for 10 seconds where the car should start, e.g. beside the driver's door, and offers
     * 3 dB under the middle reading as its trigger strength, so that spot triggers reliably.
     */
    private void calibrate(Beacons.Beacon beacon) {
        List<Integer> readings=new ArrayList<>();
        TextView message=new TextView(activity);
        message.setPadding(64,32,64,0); message.setTextSize(15);
        message.setText(R.string.calibrate_listening);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(activity.getString(R.string.calibrate_title,beacon.label)).setView(message)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",null).create();
        dialog.setOnDismissListener(d->stop());
        if(!startScan(result-> {
            ScanRecord record=result.getScanRecord();
            String id=Beacons.idFor(result.getDevice().getAddress(),record==null?null:record.getManufacturerSpecificData(0x004C));
            if(!id.equals(beacon.id)) return;
            readings.add(result.getRssi());
            message.setText(activity.getString(R.string.calibrate_progress,readings.size(),result.getRssi()));
        })) return;
        dialog.show();
        Button save=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        save.setEnabled(false);
        handler.postDelayed(()-> {
            stopScan();
            try {
                int strength=Beacons.calibrate(readings);
                message.setText(activity.getString(R.string.calibrate_result,readings.size(),strength));
                save.setEnabled(true);
                save.setOnClickListener(v-> {
                    List<Beacons.Beacon> beacons=new ArrayList<>(AppRuntime.beacons(activity));
                    beacons.replaceAll(b->b.id.equals(beacon.id)?b.withThreshold(strength):b);
                    AppRuntime.setBeacons(activity,beacons);
                    dialog.dismiss(); showChosen(); changed.run();
                    toast(beacon.label+" now starts the car at "+strength+" dBm or stronger.");
                });
            } catch(IllegalArgumentException tooFew) {
                message.setText(tooFew.getMessage());
            }
        },10000);
    }
    /** Asks for Nearby devices if needed; false when the scan can't start. */
    @android.annotation.SuppressLint("MissingPermission")
    private boolean startScan(Consumer<ScanResult> onResult) {
        if(!Permissions.bluetoothReady(activity)) { Permissions.requestBluetooth(activity); toast("Allow Nearby devices, then try again."); return false; }
        BluetoothAdapter adapter=activity.getSystemService(BluetoothManager.class).getAdapter();
        if(adapter==null || !adapter.isEnabled()) { toast("Turn Bluetooth on first."); return false; }
        stopScan();
        listener=onResult;
        scanner=adapter.getBluetoothLeScanner();
        scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),callback);
        return true;
    }
    /** Permission was checked when the scan started. */
    @android.annotation.SuppressLint("MissingPermission")
    private void stopScan() {
        if(scanner==null) return;
        try { scanner.stopScan(callback); }
        catch(IllegalStateException bluetoothTurnedOff) { /* nothing is scanning then */ }
        scanner=null;
    }
    private void toast(String text) { Toast.makeText(activity,text,Toast.LENGTH_LONG).show(); }
}
