package local.byd.arrival;

import android.app.Activity;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Settings card for the smart Start car: engine-on time, temperature bands, the four levels and the A/C on temperature. */
final class StartSettings {
    private static final int[] MINUTES={10,15,20,25,30};
    private final Activity activity;
    private final Spinner minutes;
    private final EditText coldBelow,warmFrom,hotAbove,fixedTemp;
    private final List<View> levelViews=new ArrayList<>();

    StartSettings(Activity activity) {
        this.activity=activity;
        minutes=activity.findViewById(R.id.engine_minutes);
        coldBelow=activity.findViewById(R.id.cold_below); warmFrom=activity.findViewById(R.id.warm_from);
        hotAbove=activity.findViewById(R.id.hot_above); fixedTemp=activity.findViewById(R.id.temperature);
        List<String> labels=new ArrayList<>();
        for(int m:MINUTES) labels.add(m+" minutes");
        minutes.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));
        minutes.setSelection(Arrays.binarySearch(MINUTES,AppRuntime.engineMinutes(activity)));
        ClimatePlan plan=AppRuntime.climatePlan(activity);
        coldBelow.setText(String.valueOf(plan.coldBelow)); warmFrom.setText(String.valueOf(plan.warmFrom));
        hotAbove.setText(String.valueOf(plan.hotAbove));
        fixedTemp.setText(String.valueOf(AppRuntime.prefs(activity).getInt("temperature",21)));
        LinearLayout container=activity.findViewById(R.id.climate_levels);
        for(ClimatePlan.Level level:plan.levels()) {
            View view=activity.getLayoutInflater().inflate(R.layout.climate_level,container,false);
            ((EditText)view.findViewById(R.id.level_temp)).setText(String.valueOf(level.temperature));
            ((EditText)view.findViewById(R.id.level_fan)).setText(String.valueOf(level.fan));
            ((CheckBox)view.findViewById(R.id.level_driver)).setChecked(level.driverSeat);
            ((CheckBox)view.findViewById(R.id.level_passenger)).setChecked(level.passengerSeat);
            container.addView(view); levelViews.add(view);
        }
        titles(plan);
    }
    /** Validates and saves. Throws IllegalArgumentException with a message the user can act on. */
    void save() {
        ClimatePlan current=AppRuntime.climatePlan(activity);
        ClimatePlan.Level[] old=current.levels(), levels=new ClimatePlan.Level[4];
        for(int i=0;i<4;i++) {
            View view=levelViews.get(i);
            levels[i]=new ClimatePlan.Level(old[i].name,number(view.findViewById(R.id.level_temp)),number(view.findViewById(R.id.level_fan)),
                ((CheckBox)view.findViewById(R.id.level_driver)).isChecked(),((CheckBox)view.findViewById(R.id.level_passenger)).isChecked());
        }
        ClimatePlan plan=new ClimatePlan(number(coldBelow),number(warmFrom),number(hotAbove),levels[0],levels[1],levels[2],levels[3]);
        int fixed=number(fixedTemp);
        if(fixed<15 || fixed>33) throw new IllegalArgumentException("A/C on temperature must be 15–33 °C");
        AppRuntime.prefs(activity).edit().putString("climatePlan",plan.toJson())
            .putInt("engineMinutes",MINUTES[minutes.getSelectedItemPosition()]).putInt("temperature",fixed).apply();
        titles(plan);
    }
    private void titles(ClimatePlan plan) {
        ClimatePlan.Level[] levels=plan.levels();
        for(int i=0;i<4;i++)
            ((TextView)levelViews.get(i).findViewById(R.id.level_title)).setText(levels[i].name+" · "+plan.band(levels[i]));
    }
    private static int number(View field) {
        String text=((EditText)field).getText().toString().trim();
        if(text.isEmpty()) throw new IllegalArgumentException("Fill in every number");
        return Integer.parseInt(text);
    }
}
