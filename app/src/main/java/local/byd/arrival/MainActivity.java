package local.byd.arrival;

import android.app.Activity;
import android.content.*;
import android.net.Uri;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Home screen: live status and one-tap car actions, including custom actions. Settings live in SettingsActivity. */
public final class MainActivity extends Activity implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final List<View> actions=new ArrayList<>();
    private View start;
    private GridLayout grid;
    private TextView title,subtitle,status,details,proxText;
    private ImageView proxIcon;
    private ProgressBar progress;
    private View setup;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_main);
        AppRuntime.channels(this);
        title=findViewById(R.id.title); subtitle=findViewById(R.id.subtitle);
        status=findViewById(R.id.status); details=findViewById(R.id.details);
        progress=findViewById(R.id.progress); setup=findViewById(R.id.setup);
        proxIcon=findViewById(R.id.prox_icon); proxText=findViewById(R.id.prox_text);
        findViewById(R.id.settings).setOnClickListener(v->openSettings());
        findViewById(R.id.setup_button).setOnClickListener(v->firstRunChecks());
        start=findViewById(R.id.start);
        start.setOnClickListener(v->send("START"));
        grid=findViewById(R.id.grid);
        if(state==null) {
            firstRunChecks();
            Updates.schedule(this);
            Updates.offerOnLaunch(this);
        }
    }
    /** On launch: sign in if there are no credentials, pick a car if none is chosen, then ask for missing permissions. */
    private void firstRunChecks() {
        Runnable permissions=()-> { if(!Permissions.complete(this)) Permissions.askNext(this); };
        if(!SignIn.signedIn(this)) SignIn.show(this,permissions);
        else if(!AppRuntime.ready(this)) SignIn.changeCar(this,permissions);
        else permissions.run();
    }
    @Override public void onRequestPermissionsResult(int code,String[] permissions,int[] results) {
        Permissions.continueAfter(this,code,results);
    }
    @Override protected void onResume() {
        super.onResume();
        buildTiles();
        AppRuntime.prefs(this).registerOnSharedPreferenceChangeListener(this);
        refresh();
    }
    @Override protected void onPause() {
        AppRuntime.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }
    @Override public void onSharedPreferenceChanged(SharedPreferences prefs,String key) { refresh(); }

    private void refresh() {
        SharedPreferences prefs=AppRuntime.prefs(this);
        boolean ready=AppRuntime.ready(this), busy=AppRuntime.BUSY.get();
        String vin=prefs.getString("vin","");
        title.setText(prefs.getString("vehicleName",getString(R.string.my_car)));
        subtitle.setText(ready?"VIN ···"+vin.substring(Math.max(0,vin.length()-4)):"Not set up");
        status.setText(prefs.getString("status","Ready."));
        long at=prefs.getLong("statusAt",0);
        details.setText(at==0?"":DateUtils.getRelativeTimeSpanString(at));
        boolean armed=prefs.getBoolean("proximityArmed",false);
        proxIcon.setImageResource(armed?R.drawable.ic_armed:R.drawable.ic_unarmed);
        proxText.setText(AppRuntime.proximityLabel(prefs));
        ((TextView)findViewById(R.id.prox_event)).setText(prefs.getString("proximityEvent",""));
        progress.setVisibility(busy?View.VISIBLE:View.GONE);
        setup.setVisibility(ready?View.GONE:View.VISIBLE);
        for(View action:actions) { action.setEnabled(ready && !busy); action.setAlpha(ready && !busy?1f:0.4f); }
    }
    /** Every action except Start car (the big button), then custom actions, then Locate car. Rebuilt so new custom actions show. */
    private void buildTiles() {
        grid.removeAllViews(); actions.clear(); actions.add(start);
        List<Actions.Custom> customs=AppRuntime.customs(this);
        for(String code:Actions.all(customs))
            if(!code.equals("START")) tile(AppRuntime.icon(code),Actions.label(code,customs),v->send(code));
        tile(R.drawable.ic_location,getString(R.string.locate),v->locate());
    }
    private void tile(int icon,String label,View.OnClickListener click) {
        View tile=getLayoutInflater().inflate(R.layout.tile,grid,false);
        ((ImageView)tile.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView)tile.findViewById(R.id.label)).setText(label);
        tile.setContentDescription(label);
        tile.setOnClickListener(click);
        grid.addView(tile); actions.add(tile);
    }
    private void send(String action) {
        startForegroundService(new Intent(this,CommandService.class).putExtra("actions",action));
    }
    private void locate() {
        AppRuntime.note(this,"Asking the car for its location…");
        AppRuntime.IO.execute(()-> {
            try {
                double[] at=AppRuntime.api(this).location(AppRuntime.prefs(this).getString("vin",""));
                String place=String.format(Locale.ROOT,"%.6f,%.6f",at[0],at[1]);
                AppRuntime.note(this,"Car is at "+place);
                runOnUiThread(()->startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("geo:"+place+"?q="+place+"(Car)"))));
            } catch(Exception e) { AppRuntime.clearSession(); AppRuntime.note(this,AppRuntime.safeError(e)); }
        });
    }
    private void openSettings() { startActivity(new Intent(this,SettingsActivity.class)); }
}
