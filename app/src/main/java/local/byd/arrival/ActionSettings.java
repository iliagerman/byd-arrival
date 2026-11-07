package local.byd.arrival;

import android.app.*;
import android.view.View;
import android.widget.*;
import java.util.*;

/** Settings for custom actions, the widget's buttons and what auto-start runs. */
final class ActionSettings {
    private static final int MAX_TILES=10;
    private final Activity activity;

    ActionSettings(Activity activity) {
        this.activity=activity;
        activity.findViewById(R.id.new_custom).setOnClickListener(v->editCustom(null));
        activity.findViewById(R.id.choose_tiles).setOnClickListener(v->chooseTiles());
        refresh();
    }
    private void refresh() {
        List<Actions.Custom> customs=AppRuntime.customs(activity);
        LinearLayout list=activity.findViewById(R.id.custom_list);
        list.removeAllViews();
        for(Actions.Custom custom:customs) {
            TextView row=(TextView)activity.getLayoutInflater().inflate(R.layout.device_row,list,false);
            row.setText(custom.name+"\n"+Actions.labels(custom.steps,customs));
            row.setOnClickListener(v->customMenu(custom));
            list.addView(row);
        }
        spinner(R.id.arrival_action,"arrivalAction",AppRuntime.arrivalAction(activity),customs,false);
        spinner(R.id.leave_action,"leaveAction",AppRuntime.leaveAction(activity),customs,true);
        List<String> tiles=AppRuntime.widgetTiles(activity);
        ((TextView)activity.findViewById(R.id.widget_tiles)).setText(tiles.isEmpty()?"None":Actions.labels(tiles,customs).replace(" → ",", "));
        ArrivalWidget.updateAll(activity);
    }
    /** A dropdown of every action, optionally led by Nothing (saved as ""), that saves the choice under key as soon as it changes. */
    private void spinner(int id,String key,String current,List<Actions.Custom> customs,boolean optional) {
        Spinner spinner=activity.findViewById(id);
        List<String> codes=Actions.all(customs), labels=new ArrayList<>();
        for(String code:codes) labels.add(Actions.label(code,customs));
        if(optional) { codes.add(0,""); labels.add(0,"Nothing"); }
        spinner.setOnItemSelectedListener(null);
        spinner.setAdapter(new ArrayAdapter<>(activity,android.R.layout.simple_spinner_dropdown_item,labels));
        spinner.setSelection(codes.indexOf(current),false);
        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent,View view,int position,long rowId) {
                AppRuntime.prefs(activity).edit().putString(key,codes.get(position)).apply();
                ArrivalWidget.updateAll(activity);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
    }
    private void customMenu(Actions.Custom custom) {
        new AlertDialog.Builder(activity).setTitle(custom.name).setItems(new String[]{"Edit","Delete"},(d,which)-> {
            if(which==0) editCustom(custom); else deleteCustom(custom);
        }).show();
    }
    /** Name plus built-in steps, run in the order they are ticked. */
    private void editCustom(Actions.Custom existing) {
        List<Actions.Custom> customs=new ArrayList<>(AppRuntime.customs(activity));
        List<String> chosen=new ArrayList<>(existing==null?List.of():existing.steps);
        View view=picker(new ArrayList<>(Actions.BUILT_IN.keySet()),chosen,customs,"Steps run in the order you tick them");
        EditText name=view.findViewById(R.id.picker_name);
        if(existing!=null) name.setText(existing.name);
        dialog(existing==null?"New custom action":"Edit custom action",view,()-> {
            String id=existing==null?Actions.nextId(customs):existing.id;
            Actions.Custom custom=new Actions.Custom(id,name.getText().toString(),chosen);
            if(existing==null) customs.add(custom); else customs.set(customs.indexOf(existing),custom);
            AppRuntime.setCustoms(activity,customs);
        });
    }
    /** Removes the action and any widget button or auto-start or walk-away setting that pointed at it. */
    private void deleteCustom(Actions.Custom custom) {
        List<Actions.Custom> customs=new ArrayList<>(AppRuntime.customs(activity));
        customs.remove(custom);
        List<String> tiles=new ArrayList<>(AppRuntime.widgetTiles(activity));
        tiles.remove(custom.id);
        android.content.SharedPreferences.Editor edit=AppRuntime.prefs(activity).edit().putString("widgetTiles",String.join(",",tiles));
        if(AppRuntime.arrivalAction(activity).equals(custom.id)) edit.putString("arrivalAction",Actions.DEFAULT_ARRIVAL);
        if(AppRuntime.leaveAction(activity).equals(custom.id)) edit.putString("leaveAction","SECURE");
        edit.apply();
        AppRuntime.setCustoms(activity,customs);
        refresh();
    }
    private void chooseTiles() {
        List<Actions.Custom> customs=AppRuntime.customs(activity);
        List<String> chosen=new ArrayList<>(AppRuntime.widgetTiles(activity));
        View view=picker(Actions.all(customs),chosen,customs,"Tick up to "+MAX_TILES+" (5 per row), in the order they should appear");
        view.findViewById(R.id.picker_name_label).setVisibility(View.GONE);
        view.findViewById(R.id.picker_name).setVisibility(View.GONE);
        dialog("Widget buttons",view,()-> {
            if(chosen.size()>MAX_TILES) throw new IllegalArgumentException("Pick at most "+MAX_TILES+" buttons");
            AppRuntime.prefs(activity).edit().putString("widgetTiles",String.join(",",chosen)).apply();
        });
    }
    /** Checkbox list that records the tick order in chosen and shows it live. */
    private View picker(List<String> codes,List<String> chosen,List<Actions.Custom> customs,String hint) {
        View view=activity.getLayoutInflater().inflate(R.layout.ordered_picker,null);
        ((TextView)view.findViewById(R.id.picker_hint)).setText(hint);
        TextView order=view.findViewById(R.id.picker_order);
        LinearLayout boxes=view.findViewById(R.id.picker_boxes);
        Runnable show=()->order.setText(chosen.isEmpty()?"Nothing ticked yet":Actions.labels(chosen,customs));
        for(String code:codes) {
            CheckBox box=new CheckBox(activity);
            box.setText(Actions.label(code,customs)); box.setTextSize(15); box.setChecked(chosen.contains(code));
            box.setOnCheckedChangeListener((b,on)-> { if(on) chosen.add(code); else chosen.remove(code); show.run(); });
            boxes.addView(box);
        }
        show.run();
        return view;
    }
    /** Dialog whose Save stays open with a message when the input is invalid. */
    private void dialog(String title,View view,Runnable save) {
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(title).setView(view)
            .setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v-> {
            try { save.run(); dialog.dismiss(); refresh(); }
            catch(IllegalArgumentException e) { Toast.makeText(activity,e.getMessage(),Toast.LENGTH_LONG).show(); }
        }));
        dialog.show();
    }
}
