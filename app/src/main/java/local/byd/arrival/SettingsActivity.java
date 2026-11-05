package local.byd.arrival;

import android.Manifest;
import android.app.*;
import android.appwidget.AppWidgetManager;
import android.bluetooth.*;
import android.bluetooth.le.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.util.*;

/** Account, start car, actions, widget, automation and phone settings. Secure window: credentials are typed here. */
public final class SettingsActivity extends Activity {
    private static final int GUIDE=5;
    private EditText rssi;
    private StartSettings start;
    private TextView accountState,beacon;
    private LinearLayout devices;
    private final Map<String,TextView> found=new LinkedHashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private BluetoothLeScanner scanner;
    private final ScanCallback scanCallback=new ScanCallback() {
        @Override public void onScanResult(int type,ScanResult result) { runOnUiThread(()->showDevice(result)); }
        @Override public void onScanFailed(int error) { runOnUiThread(()->toast("Scan failed: "+error)); }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_settings);
        SharedPreferences prefs=AppRuntime.prefs(this);
        rssi=findViewById(R.id.rssi);
        accountState=findViewById(R.id.account_state); beacon=findViewById(R.id.beacon); devices=findViewById(R.id.devices);
        rssi.setText(Integer.toString(prefs.getInt("rssi",-65)));
        loadAutoStart();
        showWifi();
        ((EditText)findViewById(R.id.wifi_signal)).setText(String.valueOf(AppRuntime.wifiSignal(this)));
        start=new StartSettings(this);
        new ActionSettings(this);
        click(R.id.back,this::finish);
        click(R.id.save_account,()->SignIn.show(this,this::refresh));
        click(R.id.change_car,()->SignIn.changeCar(this,this::refresh));
        click(R.id.save_start,()-> {
            try { start.save(); toast("Start settings saved."); }
            catch(IllegalArgumentException e) { toast(e.getMessage()); }
        });
        click(R.id.scan,this::scan);
        click(R.id.add_wifi,this::addCurrentWifi);
        click(R.id.monitor_gps,()->enableMonitor("gps"));
        click(R.id.auto_guide,()->startActivityForResult(new Intent(this,WelcomeActivity.class),GUIDE));
        click(R.id.monitor_ble,()->enableMonitor("ble"));
        click(R.id.monitor_stop,()-> { stopService(new Intent(this,MonitorService.class)); toast("Monitoring stopped."); });
        click(R.id.add_widget,this::addWidget);
        click(R.id.grant,()-> { if(Permissions.complete(this)) toast("All permissions are granted."); else Permissions.askNext(this); });
        click(R.id.forget,this::confirmForget);
        click(R.id.check_updates,()->Updates.checkNow(this));
        ((TextView)findViewById(R.id.app_version)).setText(getString(R.string.app_version,BuildConfig.VERSION_NAME));
    }
    @Override protected void onResume() { super.onResume(); refresh(); }
    /** The Wi-Fi signal is read live by the monitor, so saving it on leaving takes effect without a restart. */
    @Override protected void onPause() { stopScan(); saveWifiSignal(); super.onPause(); }

    private void refresh() {
        SharedPreferences prefs=AppRuntime.prefs(this);
        String account=prefs.getString("account","");
        String who=account.isEmpty()?"Signed in":"Signed in as "+account;
        if(!getSharedPreferences("vault",0).contains("ciphertext")) accountState.setText("Not signed in.");
        else if(!AppRuntime.ready(this)) accountState.setText(who+". Choose your car.");
        else accountState.setText(who+" · "+prefs.getString("vehicleName","car selected"));
        if(getSharedPreferences("vault",0).contains("ciphertext"))
            accountState.append("\n"+Regions.country(AppRuntime.country(this)).name+" · "+Regions.SERVERS.get(AppRuntime.server(this))+" server");
        android.text.SpannableStringBuilder state=new android.text.SpannableStringBuilder();
        mark(state,has(Manifest.permission.ACCESS_FINE_LOCATION),"Location");
        mark(state,has(Manifest.permission.ACCESS_BACKGROUND_LOCATION),"Location: allow all the time");
        mark(state,has(Manifest.permission.POST_NOTIFICATIONS),"Notifications");
        mark(state,Permissions.batteryUnrestricted(this),"Battery: unrestricted");
        mark(state,Permissions.bluetoothReady(this),"Nearby devices (only for a Bluetooth beacon)");
        ((TextView)findViewById(R.id.permission_state)).setText(state);
        String address=prefs.getString("beacon","");
        beacon.setText(address.isEmpty()?"No Bluetooth signal selected.":"Selected signal: "+address);
    }
    private boolean has(String permission) { return Permissions.has(this,permission); }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        refresh();
        Permissions.continueAfter(this,code,results);
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        refresh();
        if(code==GUIDE) loadAutoStart(); // the walkthrough may have changed these
    }
    /** One permission line: a green ✓ when granted, a red ✗ when missing. */
    private void mark(android.text.SpannableStringBuilder out,boolean ok,String name) {
        if(out.length()>0) out.append("\n");
        int start=out.length();
        out.append(ok?"✓":"✗");
        out.setSpan(new android.text.style.ForegroundColorSpan(getColor(ok?R.color.ok:R.color.danger)),start,out.length(),0);
        out.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),start,out.length(),0);
        out.append("  ").append(name);
    }
    private void scan() {
        if(!Permissions.bluetoothReady(this)) {
            Permissions.requestBluetooth(this);
            toast("Allow nearby devices, then tap Scan again."); return;
        }
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)!=PackageManager.PERMISSION_GRANTED) return;
        BluetoothAdapter adapter=getSystemService(BluetoothManager.class).getAdapter();
        if(adapter==null || !adapter.isEnabled()) { toast("Turn Bluetooth on first."); return; }
        stopScan(); found.clear(); devices.removeAllViews();
        scanner=adapter.getBluetoothLeScanner();
        scanner.startScan(null,new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),scanCallback);
        toast("Scanning for 20 seconds…");
        handler.postDelayed(()-> { stopScan(); toast("Scan complete. Tap a signal to select it."); },20000);
    }
    private void showDevice(ScanResult result) {
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED) return;
        String address=result.getDevice().getAddress(), name=result.getDevice().getName();
        if(!found.containsKey(address)) {
            TextView row=(TextView)getLayoutInflater().inflate(R.layout.device_row,devices,false);
            row.setOnClickListener(v-> {
                stopService(new Intent(this,MonitorService.class)); stopScan();
                AppRuntime.prefs(this).edit().putString("beacon",address).apply(); refresh();
            });
            devices.addView(row); found.put(address,row);
        }
        found.get(address).setText((name==null?"Unnamed signal":name)+"\n"+address+" · "+result.getRssi()+" dBm");
    }
    /** GPS needs only the car and location permission. Bluetooth also needs a selected signal and a threshold. */
    private void enableMonitor(String mode) {
        if(!AppRuntime.ready(this)) { toast("Sign in and choose your car first."); return; }
        if(!Permissions.gpsReady(this)) { toast("Grant the permissions first."); Permissions.askNext(this); return; }
        if("ble".equals(mode) && !Permissions.bluetoothReady(this)) { toast("Tap Scan to allow nearby devices first."); return; }
        if("ble".equals(mode) && !beaconReady()) return;
        if("gps".equals(mode)) saveAutoStart();
        startMonitor(mode);
    }
    private void loadAutoStart() {
        ((EditText)findViewById(R.id.fire_metres)).setText(String.valueOf(AppRuntime.fireMetres(this)));
        ((EditText)findViewById(R.id.rearm_metres)).setText(String.valueOf(AppRuntime.rearmMetres(this)));
        ((EditText)findViewById(R.id.cooldown_minutes)).setText(String.valueOf(AppRuntime.cooldownMinutes(this)));
    }
    /** Saves the auto-start values. Anything that doesn't work together is adjusted, and the fields show the result. */
    private void saveAutoStart() {
        AppRuntime.saveAutoStart(this,new AutoStartSettings(((EditText)findViewById(R.id.fire_metres)).getText().toString(),
            ((EditText)findViewById(R.id.rearm_metres)).getText().toString(),((EditText)findViewById(R.id.cooldown_minutes)).getText().toString()));
        loadAutoStart();
    }
    /**
     * Adds the Wi-Fi the phone is on now. Android hides the name from a direct lookup, so a short-lived
     * network listener asks for it with location info included. The request skips VPN networks.
     */
    private void addCurrentWifi() {
        if(!has(Manifest.permission.ACCESS_FINE_LOCATION)) { toast("Allow location first."); Permissions.askNext(this); return; }
        ConnectivityManager cm=getSystemService(ConnectivityManager.class);
        boolean[] done={false};
        ConnectivityManager.NetworkCallback probe=new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
            @Override public void onCapabilitiesChanged(Network network,NetworkCapabilities caps) {
                String name=AppRuntime.wifiName(caps);
                int signal=caps.getSignalStrength();
                if(name!=null) runOnUiThread(()-> { if(!done[0]) { done[0]=true; cm.unregisterNetworkCallback(this); saveWifi(name,signal); } });
            }
        };
        cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),probe);
        new Handler(Looper.getMainLooper()).postDelayed(()-> {
            if(done[0]) return;
            done[0]=true; cm.unregisterNetworkCallback(probe);
            toast("No Wi-Fi found. Connect to your Wi-Fi first.");
        },3000);
    }
    /** Shows the signal where you stand, so adding the same network again at the car helps pick the strong-signal value. */
    private void saveWifi(String name,int signal) {
        Set<String> names=AppRuntime.trustedWifi(this);
        names.add(name);
        AppRuntime.setTrustedWifi(this,names);
        showWifi();
        toast("Added "+name+". Signal here: "+signal+" dBm.");
    }
    /** Anything entered becomes a working value, and the field shows the result. */
    private void saveWifiSignal() {
        EditText field=findViewById(R.id.wifi_signal);
        int signal=AutoStartSettings.wifiSignal(field.getText().toString());
        AppRuntime.prefs(this).edit().putInt("wifiSignal",signal).apply();
        field.setText(String.valueOf(signal));
    }
    private void showWifi() {
        LinearLayout list=findViewById(R.id.wifi_list);
        list.removeAllViews();
        for(String name:new TreeSet<>(AppRuntime.trustedWifi(this))) {
            Button row=(Button)getLayoutInflater().inflate(R.layout.wifi_row,list,false);
            row.setText(getString(R.string.wifi_remove,name));
            row.setOnClickListener(v-> {
                Set<String> names=AppRuntime.trustedWifi(this);
                names.remove(name);
                AppRuntime.setTrustedWifi(this,names);
                showWifi();
            });
            list.addView(row);
        }
    }
    private boolean beaconReady() {
        int threshold;
        try { threshold=Integer.parseInt(rssi.getText().toString()); }
        catch(NumberFormatException e) { threshold=0; }
        if(threshold < -95 || threshold > -30) { toast("Threshold must be between −95 and −30 dBm."); return false; }
        if(!BluetoothAdapter.checkBluetoothAddress(AppRuntime.prefs(this).getString("beacon",""))) { toast("Scan and select a Bluetooth signal first."); return false; }
        AppRuntime.prefs(this).edit().putInt("rssi",threshold).apply();
        return true;
    }
    private void startMonitor(String mode) {
        stopScan(); stopService(new Intent(this,MonitorService.class));
        startForegroundService(new Intent(this,MonitorService.class).putExtra("mode",mode));
        toast("gps".equals(mode)?"Starting GPS monitoring…":"Starting Bluetooth monitoring…");
    }
    private void stopScan() {
        handler.removeCallbacksAndMessages(null);
        if(scanner==null) return;
        if(checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED) scanner.stopScan(scanCallback);
        scanner=null;
    }
    private void addWidget() {
        AppWidgetManager manager=getSystemService(AppWidgetManager.class);
        if(manager.isRequestPinAppWidgetSupported()) manager.requestPinAppWidget(new ComponentName(this,ArrivalWidget.class),null,null);
        else toast("Long-press the home screen → Widgets → BYD Arrival.");
    }
    private void confirmForget() {
        new AlertDialog.Builder(this).setTitle("Forget credentials?")
            .setMessage("Erases your BYD password and PIN from this phone and stops automation.")
            .setPositiveButton("Forget",(d,w)->forget()).setNegativeButton("Cancel",null).show();
    }
    private void forget() {
        stopService(new Intent(this,MonitorService.class));
        AppRuntime.IO.execute(()-> {
            try {
                new Vault(this).forget(); AppRuntime.clearSession();
                AppRuntime.prefs(this).edit().remove("vin").remove("vehicleName").remove("account").apply();
                runOnUiThread(()-> { refresh(); toast("Credentials erased."); });
            } catch(Exception e) { runOnUiThread(()->toast("Could not erase credentials.")); }
        });
    }
    private void click(int id,Runnable action) { findViewById(id).setOnClickListener(v->action.run()); }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
}
