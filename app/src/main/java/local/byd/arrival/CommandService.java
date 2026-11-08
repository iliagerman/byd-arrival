package local.byd.arrival;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import java.util.Arrays;
import java.util.List;

/** Runs one-tap actions from the widget or app. Extra "actions" = comma-separated built-in codes or custom ids. */
public final class CommandService extends Service {
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        startForeground(10,AppRuntime.notification(this,"Running car actions…","results"));
        List<String> actions=Arrays.asList(intent.getStringExtra("actions").split(","));
        AppRuntime.execute(this,actions,false,false,()->stopSelf(startId));
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
