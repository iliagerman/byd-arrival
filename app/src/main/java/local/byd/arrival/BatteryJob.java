package local.byd.arrival;

import android.app.job.*;
import android.content.*;

/** Reads the car's battery level every 30 minutes for the widget. Read-only; a failed read waits for the next period. */
public final class BatteryJob extends JobService {
    private static final int JOB_ID=101;
    private static final long EVERY_MILLIS=30*60000L;
    /** Starts the periodic read once. Later launches leave its timer alone. */
    static void schedule(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(JOB_ID)!=null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(context,BatteryJob.class))
            .setPeriodic(EVERY_MILLIS).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
    }
    @Override public boolean onStartJob(JobParameters params) {
        if(AppRuntime.abroad(this).on) return false; // paused while you travel
        AppRuntime.refreshBattery(this,()->jobFinished(params,false));
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
}
