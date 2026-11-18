package local.byd.arrival;

import android.app.Activity;
import android.view.View;
import android.widget.*;
import java.util.*;
import java.util.function.IntConsumer;

/**
 * Settings card for the smart Start car: engine-on time, the five temperature ranges (where each starts, A/C, fan,
 * seat heat off, low or high) and the A/C on temperature. Numbers are set with − and + so they can't be invalid, and every change
 * is saved straight away.
 */
final class StartSettings {
    private static final int[] MINUTES={10,15,20,25,30};
    private final Activity activity;
    private final List<View> levelViews=new ArrayList<>();
    private ClimatePlan plan;

    StartSettings(Activity activity) {
        this.activity=activity;
        plan=AppRuntime.climatePlan(activity);
        bindMinutes();
        LinearLayout container=activity.findViewById(R.id.climate_levels);
        for(int i=0;i<ClimatePlan.NAMES.length;i++) {
            View view=activity.getLayoutInflater().inflate(R.layout.climate_level,container,false);
            bindLevel(view,i);
            container.addView(view); levelViews.add(view);
        }
        View fixed=activity.findViewById(R.id.temperature);
        stepper(fixed,R.string.temperature_label,
            delta->AppRuntime.prefs(activity).edit().putInt("temperature",ClimatePlan.clamp(fixedTemperature()+delta,ClimatePlan.MIN_AC,ClimatePlan.MAX_AC)).apply());
        show();
    }
    private void bindMinutes() {
        Spinner minutes=activity.findViewById(R.id.engine_minutes);
        List<String> labels=new ArrayList<>();
        for(int m:MINUTES) labels.add(m+" minutes");
        minutes.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));
        minutes.setSelection(Arrays.binarySearch(MINUTES,AppRuntime.engineMinutes(activity)));
        minutes.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long rowId) {
                AppRuntime.prefs(activity).edit().putInt("engineMinutes",MINUTES[position]).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    /** One range. The lowest has no start: it covers everything below the next one. */
    private void bindLevel(View view,int index) {
        View from=view.findViewById(R.id.level_from);
        if(index==0) from.setVisibility(View.GONE);
        else stepper(from,R.string.level_from,delta->save(plan.withFrom(index,plan.from(index)+delta)));
        stepper(view.findViewById(R.id.level_temp),R.string.level_temperature,
            delta->save(plan.withLevel(index,plan.level(index).withTemperature(plan.level(index).temperature+delta))));
        stepper(view.findViewById(R.id.level_fan),R.string.level_fan,
            delta->save(plan.withLevel(index,plan.level(index).withFan(plan.level(index).fan+delta))));
        stepper(view.findViewById(R.id.level_driver),R.string.level_driver,
            delta->save(plan.withLevel(index,plan.level(index).withHeat(plan.level(index).driverHeat+delta,plan.level(index).passengerHeat))));
        stepper(view.findViewById(R.id.level_passenger),R.string.level_passenger,
            delta->save(plan.withLevel(index,plan.level(index).withHeat(plan.level(index).driverHeat,plan.level(index).passengerHeat+delta))));
    }
    /** Wires − and + to change(−1) and change(+1); the change saves, then everything is shown again. */
    private void stepper(View row,int label,IntConsumer change) {
        ((TextView)row.findViewById(R.id.stepper_label)).setText(label);
        row.findViewById(R.id.stepper_down).setOnClickListener(v-> { change.accept(-1); show(); });
        row.findViewById(R.id.stepper_up).setOnClickListener(v-> { change.accept(1); show(); });
    }
    private void save(ClimatePlan changed) {
        plan=changed;
        AppRuntime.prefs(activity).edit().putString("climatePlan",plan.toJson()).apply();
    }
    private int fixedTemperature() { return AppRuntime.acOnTemperature(activity); }
    /** Moving one border can move its neighbours, so every range is shown again after each change. */
    private void show() {
        for(int i=0;i<levelViews.size();i++) {
            View view=levelViews.get(i);
            ClimatePlan.Level level=plan.level(i);
            ((TextView)view.findViewById(R.id.level_title)).setText(level.name+" · "+plan.band(i));
            if(i>0) value(view.findViewById(R.id.level_from),plan.from(i)+" °C");
            value(view.findViewById(R.id.level_temp),level.temperature+" °C");
            value(view.findViewById(R.id.level_fan),String.valueOf(level.fan));
            value(view.findViewById(R.id.level_driver),ClimatePlan.HEAT[level.driverHeat]);
            value(view.findViewById(R.id.level_passenger),ClimatePlan.HEAT[level.passengerHeat]);
        }
        value(activity.findViewById(R.id.temperature),fixedTemperature()+" °C");
    }
    private static void value(View row,String text) { ((TextView)row.findViewById(R.id.stepper_value)).setText(text); }
}
