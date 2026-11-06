package local.byd.arrival;

import android.app.PendingIntent;
import android.appwidget.*;
import android.content.*;
import android.view.View;
import android.widget.RemoteViews;
import java.util.List;

/** Home-screen widget: one-tap actions using the stored PIN; no unlock or password prompt. */
public final class ArrivalWidget extends AppWidgetProvider {
    /** An app update or a phone restart ends the monitor without onDestroy. Restart it if it was on, so you don't have to. */
    @Override public void onReceive(Context context,Intent intent) {
        String action=intent.getAction();
        if(Intent.ACTION_MY_PACKAGE_REPLACED.equals(action) || Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            SharedPreferences prefs=AppRuntime.prefs(context);
            if(prefs.getBoolean("monitoring",false)) {
                if(Intent.ACTION_BOOT_COMPLETED.equals(action)) AppRuntime.proximityEvent(context,"Restarted after the phone restarted");
                context.startForegroundService(new Intent(context,MonitorService.class).putExtra("mode",prefs.getString("monitorMode","gps")));
            }
            else AppRuntime.setProximity(context,false,"Off");
            return;
        }
        super.onReceive(context,intent);
    }
    private static final int[] TILES={R.id.w_tile1,R.id.w_tile2,R.id.w_tile3,R.id.w_tile4,R.id.w_tile5,
        R.id.w_tile6,R.id.w_tile7,R.id.w_tile8,R.id.w_tile9,R.id.w_tile10};
    private static final int[] ICONS={R.id.w_icon1,R.id.w_icon2,R.id.w_icon3,R.id.w_icon4,R.id.w_icon5,
        R.id.w_icon6,R.id.w_icon7,R.id.w_icon8,R.id.w_icon9,R.id.w_icon10};
    private static final int[] LABELS={R.id.w_label1,R.id.w_label2,R.id.w_label3,R.id.w_label4,R.id.w_label5,
        R.id.w_label6,R.id.w_label7,R.id.w_label8,R.id.w_label9,R.id.w_label10};
    private static final int[] BADGES={R.id.w_badge1,R.id.w_badge2,R.id.w_badge3,R.id.w_badge4,R.id.w_badge5,
        R.id.w_badge6,R.id.w_badge7,R.id.w_badge8,R.id.w_badge9,R.id.w_badge10};
    /** One header line, then up to ten equal buttons in two rows of five. Rows share the height, so nothing is cut off. */
    @Override public void onUpdate(Context context,AppWidgetManager manager,int[] ids) {
        SharedPreferences prefs=AppRuntime.prefs(context);
        List<Actions.Custom> customs=AppRuntime.customs(context);
        List<String> tiles=AppRuntime.widgetTiles(context);
        for(int id:ids) {
            RemoteViews views=new RemoteViews(context.getPackageName(),R.layout.arrival_widget);
            views.setTextViewText(R.id.w_title,prefs.getString("vehicleName",context.getString(R.string.my_car)));
            views.setImageViewResource(R.id.w_prox_icon,prefs.getBoolean("proximityArmed",false)?R.drawable.ic_armed:R.drawable.ic_unarmed);
            views.setTextViewText(R.id.w_prox_text,AppRuntime.proximityLabel(prefs));
            views.setOnClickPendingIntent(R.id.w_header,activity(context,21,MainActivity.class));
            for(int i=0;i<TILES.length;i++) {
                boolean used=i<tiles.size();
                views.setViewVisibility(TILES[i],used?View.VISIBLE:View.INVISIBLE);
                if(!used) continue;
                String code=tiles.get(i);
                views.setImageViewResource(ICONS[i],AppRuntime.icon(code));
                views.setTextViewText(LABELS[i],Actions.label(code,customs));
                views.setOnClickPendingIntent(TILES[i],action(context,31+i,code));
                badge(views,BADGES[i],code.equals(prefs.getString("lastAction",""))?prefs.getString("lastResult",""):"");
            }
            views.setViewVisibility(R.id.w_row2,tiles.size()>5?View.VISIBLE:View.GONE);
            manager.updateAppWidget(id,views);
        }
    }
    /** Result of the last pressed action: running …, confirmed ✓ (green), failed ✗ (red). */
    private static void badge(RemoteViews views,int id,String result) {
        switch(result) {
            case "busy": views.setTextViewText(id,"…"); views.setInt(id,"setBackgroundResource",R.drawable.bg_badge_busy); break;
            case "ok": views.setTextViewText(id,"✓"); views.setInt(id,"setBackgroundResource",R.drawable.bg_badge_ok); break;
            case "fail": views.setTextViewText(id,"✗"); views.setInt(id,"setBackgroundResource",R.drawable.bg_badge_fail); break;
            default: views.setViewVisibility(id,View.GONE); return;
        }
        views.setViewVisibility(id,View.VISIBLE);
    }
    private static PendingIntent activity(Context context,int requestCode,Class<?> screen) {
        return PendingIntent.getActivity(context,requestCode,new Intent(context,screen),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    private static PendingIntent action(Context context,int requestCode,String action) {
        Intent intent=new Intent(context,CommandService.class).putExtra("actions",action);
        return PendingIntent.getForegroundService(context,requestCode,intent,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    static void updateAll(Context context) {
        AppWidgetManager manager=AppWidgetManager.getInstance(context);
        new ArrivalWidget().onUpdate(context,manager,manager.getAppWidgetIds(new ComponentName(context,ArrivalWidget.class)));
    }
}
