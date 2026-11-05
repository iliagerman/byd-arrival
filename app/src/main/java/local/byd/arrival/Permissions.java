package local.byd.arrival;

import android.Manifest;
import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import java.util.*;

/** What the app needs from Android, asked one step at a time. Bluetooth is asked only when scanning for a beacon. */
final class Permissions {
    static final int CORE=1, BACKGROUND=2, BATTERY=3, BLUETOOTH=4;
    static boolean has(Context c,String permission) { return c.checkSelfPermission(permission)==PackageManager.PERMISSION_GRANTED; }
    static boolean batteryUnrestricted(Context c) { return c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName()); }
    static boolean bluetoothReady(Context c) { return has(c,Manifest.permission.BLUETOOTH_SCAN) && has(c,Manifest.permission.BLUETOOTH_CONNECT); }
    /** Location (for GPS monitoring and the Wi-Fi name) and notifications. */
    static List<String> missingCore(Context c) {
        List<String> missing=new ArrayList<>();
        for(String permission:List.of(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.POST_NOTIFICATIONS))
            if(!has(c,permission)) missing.add(permission);
        return missing;
    }
    static boolean gpsReady(Context c) { return missingCore(c).isEmpty() && has(c,Manifest.permission.ACCESS_BACKGROUND_LOCATION); }
    static boolean complete(Context c) { return gpsReady(c) && batteryUnrestricted(c); }
    /** Asks for the next missing step: location and notifications, then location all the time, then unrestricted battery. */
    static void askNext(Activity activity) {
        List<String> missing=missingCore(activity);
        if(!missing.isEmpty()) { activity.requestPermissions(missing.toArray(new String[0]),CORE); return; }
        if(!has(activity,Manifest.permission.ACCESS_BACKGROUND_LOCATION)) {
            activity.requestPermissions(new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},BACKGROUND); return;
        }
        if(!batteryUnrestricted(activity))
            activity.startActivityForResult(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+activity.getPackageName())),BATTERY);
    }
    /** Android returns empty results when a prompt is interrupted, which counts as not granted. */
    static boolean allGranted(int[] results) {
        for(int result:results) if(result!=PackageManager.PERMISSION_GRANTED) return false;
        return results.length>0;
    }
    /** After a permission answer: carry on only if everything asked was granted, so a "Don't allow" is respected. */
    static void continueAfter(Activity activity,int code,int[] results) {
        if(code!=BLUETOOTH && allGranted(results) && !complete(activity)) askNext(activity);
    }
    static void requestBluetooth(Activity activity) {
        activity.requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT},BLUETOOTH);
    }
}
