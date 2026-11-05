package local.byd.arrival;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.net.Uri;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Finds newer releases on GitHub: at launch, about every 12 hours in the background, and on request.
 * The download opens in the browser, and Android's installer replaces the app, keeping its data.
 */
final class Updates {
    private static final String REPO="iliagerman/byd-arrival";
    private static final String LATEST_RELEASE="https://api.github.com/repos/"+REPO+"/releases/latest";
    private static final String DOWNLOAD="https://github.com/"+REPO+"/releases/latest/download/byd-arrival.apk";
    private static final long CHECK_EVERY_MILLIS=12*3600000L;
    private static final int JOB_ID=100, NOTIFICATION_ID=13;

    /** The newer build on GitHub, or 0 if this install is current. Network call: run off the main thread. */
    static int newerBuild() throws Exception {
        HttpURLConnection connection=(HttpURLConnection)new URL(LATEST_RELEASE).openConnection();
        connection.setConnectTimeout(15000); connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept","application/vnd.github+json");
        connection.setRequestProperty("User-Agent","byd-arrival");
        try {
            int status=connection.getResponseCode();
            if(status!=200) throw new IOException("GitHub HTTP "+status);
            try(InputStream stream=connection.getInputStream()) {
                String body=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
                return UpdateCheck.newerBuild(new JSONObject(body).getString("tag_name"),BuildConfig.VERSION_CODE);
            }
        } finally { connection.disconnect(); }
    }
    /** Starts the background check once. Later launches leave its timer alone. */
    static void schedule(Context context) {
        JobScheduler scheduler=context.getSystemService(JobScheduler.class);
        if(scheduler.getPendingJob(JOB_ID)!=null) return;
        scheduler.schedule(new JobInfo.Builder(JOB_ID,new ComponentName(context,UpdateJob.class))
            .setPeriodic(CHECK_EVERY_MILLIS).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build());
    }
    /** Background result: one notification per new build, so it doesn't repeat every 12 hours. */
    static void notifyOnce(Context context,int build) {
        SharedPreferences prefs=AppRuntime.prefs(context);
        if(build==0 || prefs.getInt("updateNotified",0)==build) return;
        prefs.edit().putInt("updateNotified",build).apply();
        NotificationManager manager=context.getSystemService(NotificationManager.class);
        manager.createNotificationChannel(new NotificationChannel("updates","App updates",NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent download=PendingIntent.getActivity(context,3,downloadIntent(),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        manager.notify(NOTIFICATION_ID,new Notification.Builder(context,"updates").setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("BYD Arrival update available").setContentText("Version "+build+" is ready. Tap to download.")
            .setContentIntent(download).setAutoCancel(true).build());
    }
    /** At launch, offer a newer build. A failed check stays quiet: the background check tries again. */
    static void offerOnLaunch(Activity activity) {
        new Thread(()-> {
            try {
                int build=newerBuild();
                if(build!=0) activity.runOnUiThread(()->offer(activity,build));
            } catch(Exception offlineOrGitHubDown) { /* nothing to act on until the next check */ }
        }).start();
    }
    /** The Settings button: unlike the launch check, it always reports the outcome. */
    static void checkNow(Activity activity) {
        Toast.makeText(activity,"Checking for updates…",Toast.LENGTH_SHORT).show();
        new Thread(()-> {
            try {
                int build=newerBuild();
                activity.runOnUiThread(()-> {
                    if(build!=0) offer(activity,build);
                    else Toast.makeText(activity,"You have the latest version.",Toast.LENGTH_LONG).show();
                });
            } catch(Exception error) {
                activity.runOnUiThread(()->Toast.makeText(activity,"Could not check for updates. Try again later.",Toast.LENGTH_LONG).show());
            }
        }).start();
    }
    private static void offer(Activity activity,int build) {
        if(activity.isFinishing() || activity.isDestroyed()) return;
        new AlertDialog.Builder(activity).setTitle("Update available")
            .setMessage("Version "+build+" is ready (you have "+BuildConfig.VERSION_NAME+"). Download and install it now? Your sign-in and settings are kept.")
            .setPositiveButton("Update",(dialog,which)->activity.startActivity(downloadIntent()))
            .setNegativeButton("Later",null).show();
    }
    private static Intent downloadIntent() { return new Intent(Intent.ACTION_VIEW,Uri.parse(DOWNLOAD)); }
}
