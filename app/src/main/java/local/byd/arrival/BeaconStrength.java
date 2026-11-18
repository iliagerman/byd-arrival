package local.byd.arrival;

import android.app.*;
import android.os.*;
import android.view.View;
import android.widget.*;
import java.util.*;

/**
 * The trigger strength of one beacon: set it by hand, or Calibrate where the car should start. A scan runs while the
 * dialog is open, so you see the signal where you stand and whether it would start the car.
 */
final class BeaconStrength {
    private static final long CALIBRATE_MS=10_000, QUIET_MS=5_000;
    private final Activity activity;
    private final Beacons.Beacon beacon;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SeekBar bar;
    private final TextView value, meaning, live, note;
    private final Button calibrate;
    private boolean heard;
    private int signal;
    private long heardAt;
    /** Readings while calibrating, else null. */
    private List<Integer> readings;
    /** This dialog's calibration, if any. */
    private Beacons.Calibration calibration;
    /** Once a second, so "Not heard for" stays current when the beacon goes quiet. */
    private final Runnable refresh=new Runnable() {
        @Override public void run() { showLive(); handler.postDelayed(this,1000); }
    };

    BeaconStrength(Activity activity,BeaconSettings scans,Beacons.Beacon beacon,Runnable saved) {
        this.activity=activity; this.beacon=beacon;
        View view=activity.getLayoutInflater().inflate(R.layout.beacon_strength,null);
        bar=view.findViewById(R.id.strength_bar); value=view.findViewById(R.id.strength_value); meaning=view.findViewById(R.id.strength_meaning);
        live=view.findViewById(R.id.strength_live); note=view.findViewById(R.id.strength_note); calibrate=view.findViewById(R.id.strength_calibrate);
        bar.setMax(Beacons.STRONGEST-Beacons.WEAKEST);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar,int progress,boolean fromUser) { showStrength(); }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        bar.setProgress(beacon.threshold-Beacons.WEAKEST);
        showStrength();
        view.findViewById(R.id.strength_down).setOnClickListener(v->bar.setProgress(bar.getProgress()-1));
        view.findViewById(R.id.strength_up).setOnClickListener(v->bar.setProgress(bar.getProgress()+1));
        note.setText(beacon.note.isEmpty()?activity.getString(R.string.strength_default):beacon.note);
        calibrate.setOnClickListener(v->startCalibration());
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle(beacon.label).setView(view)
            .setNegativeButton("Cancel",null).setPositiveButton("Save",(d,which)-> { if(save()) saved.run(); }).create();
        dialog.setOnDismissListener(d-> { handler.removeCallbacksAndMessages(null); scans.stop(); });
        // Without a scan (Bluetooth off, no permission) the value can still be set by hand.
        if(!scans.listen(beacon.id,this::heard)) { live.setText(R.string.strength_not_listening); calibrate.setEnabled(false); }
        dialog.show();
        handler.post(refresh);
    }
    private int strength() { return bar.getProgress()+Beacons.WEAKEST; }
    private void showStrength() {
        value.setText(activity.getString(R.string.strength_value,strength()));
        meaning.setText(activity.getString(R.string.strength_meaning,strength(),strength()-ArrivalGate.AWAY_DB));
        showLive();
    }
    private void showLive() {
        if(!heard) return;
        long quiet=SystemClock.elapsedRealtime()-heardAt;
        if(quiet>QUIET_MS) { live.setTextColor(activity.getColor(R.color.muted)); live.setText(activity.getString(R.string.strength_unheard,quiet/1000)); return; }
        boolean starts=signal>=strength();
        live.setTextColor(activity.getColor(starts?R.color.ok:R.color.danger));
        live.setText(activity.getString(starts?R.string.strength_live_yes:R.string.strength_live_no,signal));
    }
    private void heard(int rssi) {
        heard=true; signal=rssi; heardAt=SystemClock.elapsedRealtime();
        if(readings!=null) { readings.add(rssi); calibrate.setText(activity.getString(R.string.calibrating,readings.size())); }
        showLive();
    }
    private void startCalibration() {
        readings=new ArrayList<>();
        calibrate.setEnabled(false);
        calibrate.setText(activity.getString(R.string.calibrating,0));
        handler.postDelayed(this::finishCalibration,CALIBRATE_MS);
    }
    /** Moves the value to the suggestion; it's kept only if you tap Save. */
    private void finishCalibration() {
        List<Integer> taken=readings;
        readings=null;
        calibrate.setEnabled(true);
        calibrate.setText(R.string.calibrate_again);
        try {
            calibration=Beacons.calibrate(taken);
            bar.setProgress(calibration.threshold-Beacons.WEAKEST);
            note.setText(activity.getString(R.string.calibrated_now,detail(calibration),calibration.threshold));
        } catch(IllegalArgumentException tooFew) {
            note.setText(tooFew.getMessage());
        }
    }
    private String detail(Beacons.Calibration c) {
        return activity.getString(R.string.calibration_detail,c.middle,c.weakest,c.strongest,c.readings);
    }
    /** Saves the strength with a note on how it was set. False when nothing changed. */
    private boolean save() {
        int strength=strength();
        String when=android.text.format.DateFormat.format("d MMM HH:mm",System.currentTimeMillis()).toString();
        String how;
        if(calibration!=null && strength==calibration.threshold) how=activity.getString(R.string.note_calibrated,when,detail(calibration));
        else if(calibration!=null) how=activity.getString(R.string.note_hand_after_calibration,when,calibration.threshold,detail(calibration));
        else if(strength!=beacon.threshold) how=activity.getString(R.string.note_hand,when);
        else return false;
        List<Beacons.Beacon> beacons=new ArrayList<>(AppRuntime.beacons(activity));
        beacons.replaceAll(b->b.id.equals(beacon.id)?b.withThreshold(strength,how):b);
        AppRuntime.setBeacons(activity,beacons);
        Toast.makeText(activity,beacon.label+" now starts the car at "+strength+" dBm or stronger.",Toast.LENGTH_LONG).show();
        return true;
    }
}
