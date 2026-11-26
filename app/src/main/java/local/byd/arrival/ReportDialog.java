package local.byd.arrival;

import android.Manifest;
import android.app.*;
import android.bluetooth.*;
import android.content.*;
import android.location.LocationManager;
import android.os.*;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;
import org.json.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** Report a problem: asks what happened, then sends it with the app's state and recent log to the webhook in Settings. */
final class ReportDialog {
    /** Not AppRuntime.IO: a slow upload mustn't hold up car commands. */
    private static final ExecutorService SEND=Executors.newSingleThreadExecutor();

    static void show(Activity activity) {
        String url=AppRuntime.prefs(activity).getString("reportUrl","");
        if(!Report.validUrl(url)) {
            Toast.makeText(activity,R.string.report_no_url,Toast.LENGTH_LONG).show();
            activity.startActivity(new Intent(activity,SettingsActivity.class));
            return;
        }
        EditText text=new EditText(activity);
        text.setHint(R.string.report_hint);
        text.setMinLines(3);
        text.setGravity(Gravity.TOP);
        text.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        FrameLayout frame=new FrameLayout(activity);
        frame.setPadding(56,16,56,0);
        frame.addView(text);
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(R.string.report_title).setMessage(R.string.report_body)
            .setView(frame).setPositiveButton("Send",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v-> {
            String description=text.getText().toString();
            if(description.isBlank()) { text.setError("Say what happened"); return; }
            dialog.dismiss();
            send(activity.getApplicationContext(),url,description);
        }));
        dialog.show();
    }
    private static void send(Context c,String url,String description) {
        Toast.makeText(c,"Sending the report…",Toast.LENGTH_SHORT).show();
        SEND.execute(()-> {
            String result;
            try {
                JSONObject report=Report.build(description,now(),device(c),current(c),Report.settings(AppRuntime.prefs(c).getAll()),
                    AppRuntime.logTail(c,Report.LOG_LINES));
                int status=Report.post(url,report);
                AppRuntime.log(c,"report sent: HTTP "+status+", "+report.getInt("logLines")+" log lines");
                result="Report sent.";
            } catch(IOException|JSONException error) {
                AppRuntime.log(c,"report not sent: "+error.getMessage());
                result="Couldn't send the report: "+error.getMessage();
            }
            String shown=result;
            AppRuntime.MAIN.post(()->Toast.makeText(c,shown,Toast.LENGTH_LONG).show());
        });
    }
    /** The phone's time with its offset, e.g. 2026-11-26T17:28:03+02:00. */
    private static String now() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.ROOT).format(new Date());
    }
    /** What can explain a problem outside the app: version, phone, permissions, Bluetooth, location, battery saving. */
    private static JSONObject device(Context c) throws JSONException {
        BluetoothAdapter adapter=c.getSystemService(BluetoothManager.class).getAdapter();
        return new JSONObject()
            .put("appVersion",BuildConfig.VERSION_NAME)
            .put("phone",Build.MANUFACTURER+" "+Build.MODEL)
            .put("android",Build.VERSION.RELEASE).put("sdk",Build.VERSION.SDK_INT)
            .put("timeZone",TimeZone.getDefault().getID())
            .put("preciseLocation",Permissions.has(c,Manifest.permission.ACCESS_FINE_LOCATION))
            .put("locationAllTheTime",Permissions.has(c,Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            .put("nearbyDevices",Permissions.bluetoothReady(c))
            .put("notifications",Permissions.has(c,Manifest.permission.POST_NOTIFICATIONS))
            .put("batteryUnrestricted",Permissions.batteryUnrestricted(c))
            .put("batterySaverOn",c.getSystemService(PowerManager.class).isPowerSaveMode())
            .put("locationOn",c.getSystemService(LocationManager.class).isLocationEnabled())
            .put("bluetoothOn",adapter!=null && adapter.isEnabled());
    }
    /** What the app and widget show right now. */
    private static JSONObject current(Context c) throws JSONException {
        SharedPreferences p=AppRuntime.prefs(c);
        return new JSONObject()
            .put("status",p.getString("status",""))
            .put("widget",AppRuntime.proximityLabel(p))
            .put("lastEvent",p.getString("proximityEvent",""))
            .put("monitoring",p.getBoolean("monitoring",false))
            .put("actionRunning",AppRuntime.BUSY.get());
    }
}
