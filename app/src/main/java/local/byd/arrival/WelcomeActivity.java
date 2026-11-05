package local.byd.arrival;

import android.app.Activity;
import android.content.*;
import android.os.Bundle;
import android.view.View;
import android.widget.*;

/** Walkthrough of auto-start after the first login: how it works, its values, and a safe first try. Also opened from Settings. */
public final class WelcomeActivity extends Activity {
    /** Result when a permission was refused here, so the caller doesn't ask again straight away. */
    static final int DECLINED=RESULT_FIRST_USER;
    private View[] pages;
    private int page;
    private boolean saved;
    private TextView step,summary;
    private Button next,back;
    private EditText start,rearm,minutes;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_welcome);
        AppRuntime.prefs(this).edit().putBoolean("welcomeDone",true).apply();
        pages=new View[]{findViewById(R.id.page_how),findViewById(R.id.page_values),findViewById(R.id.page_try)};
        step=findViewById(R.id.step); summary=findViewById(R.id.summary);
        next=findViewById(R.id.next); back=findViewById(R.id.back);
        start=findViewById(R.id.start_distance); rearm=findViewById(R.id.rearm_distance); minutes=findViewById(R.id.minimum_minutes);
        start.setText(String.valueOf(AppRuntime.fireMetres(this)));
        rearm.setText(String.valueOf(AppRuntime.rearmMetres(this)));
        minutes.setText(String.valueOf(AppRuntime.cooldownMinutes(this)));
        next.setOnClickListener(v->next());
        back.setOnClickListener(v->show(page-1));
        findViewById(R.id.not_now).setOnClickListener(v->notNow());
        show(0);
    }
    private void show(int index) {
        page=index;
        for(int i=0;i<pages.length;i++) pages[i].setVisibility(i==page?View.VISIBLE:View.GONE);
        step.setText(getString(R.string.welcome_step,page+1,pages.length));
        next.setText(page==pages.length-1?R.string.welcome_try:R.string.welcome_next);
        back.setVisibility(page==0?View.GONE:View.VISIBLE);
    }
    private void next() {
        if(page==pages.length-1) { tryInTestMode(); return; }
        if(page==1) saveValues();
        show(page+1);
    }
    /** Saves to the same settings the Settings screen uses, and shows the values as adjusted. */
    private void saveValues() {
        AutoStartSettings values=new AutoStartSettings(start.getText().toString(),rearm.getText().toString(),minutes.getText().toString());
        AppRuntime.saveAutoStart(this,values);
        saved=true;
        start.setText(String.valueOf(values.start)); rearm.setText(String.valueOf(values.rearm)); minutes.setText(String.valueOf(values.minutes));
        summary.setText(getString(R.string.welcome_summary,values.start,values.rearm,values.minutes));
    }
    private void tryInTestMode() {
        AppRuntime.prefs(this).edit().putBoolean("detectionOnly",true).apply();
        if(Permissions.gpsReady(this)) startMonitoring("gps"); else Permissions.askNext(this);
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        if(Permissions.gpsReady(this)) { startMonitoring("gps"); return; }
        if(Permissions.allGranted(results)) { Permissions.askNext(this); return; }
        Toast.makeText(this,R.string.welcome_location_needed,Toast.LENGTH_LONG).show();
        setResult(DECLINED); finish();
    }
    private void startMonitoring(String mode) {
        stopService(new Intent(this,MonitorService.class));
        startForegroundService(new Intent(this,MonitorService.class).putExtra("mode",mode));
        finish();
    }
    /** A monitor already running keeps its old values until restarted, so new values restart it. */
    private void notNow() {
        SharedPreferences prefs=AppRuntime.prefs(this);
        if(saved && prefs.getBoolean("monitoring",false)) startMonitoring(prefs.getString("monitorMode","gps")); else finish();
    }
}
