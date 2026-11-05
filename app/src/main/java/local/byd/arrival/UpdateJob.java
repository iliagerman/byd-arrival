package local.byd.arrival;

import android.app.job.*;

/** The periodic update check. A failed check just waits for the next period. */
public final class UpdateJob extends JobService {
    @Override public boolean onStartJob(JobParameters params) {
        new Thread(()-> {
            try { Updates.notifyOnce(this,Updates.newerBuild()); }
            catch(Exception offlineOrGitHubDown) { /* the next period checks again */ }
            jobFinished(params,false);
        }).start();
        return true;
    }
    @Override public boolean onStopJob(JobParameters params) { return true; }
}
