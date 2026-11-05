package local.byd.arrival;

import android.app.*;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import org.json.*;

/**
 * Sign-in popup and car picker, used on first launch and from Settings. The popup window is secure (no screenshots)
 * and stays open until BYD accepts the login. Listing cars sends no car commands.
 */
final class SignIn {
    static boolean signedIn(Activity activity) { return activity.getSharedPreferences("vault",0).contains("ciphertext"); }

    static void show(Activity activity,Runnable done) {
        View view=activity.getLayoutInflater().inflate(R.layout.sign_in,null);
        EditText email=view.findViewById(R.id.email), password=view.findViewById(R.id.password), pin=view.findViewById(R.id.pin);
        email.setText(AppRuntime.prefs(activity).getString("account",""));
        AlertDialog dialog=new AlertDialog.Builder(activity).setTitle("Sign in to BYD").setView(view)
            .setPositiveButton("Sign in",null).setNegativeButton("Not now",null).create();
        dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v-> {
            v.setEnabled(false);
            signIn(activity,dialog,v,email.getText().toString().trim(),password.getText().toString(),pin.getText().toString(),done);
        }));
        dialog.show();
    }
    private static void signIn(Activity activity,AlertDialog dialog,View button,String user,String pass,String code,Runnable done) {
        activity.stopService(new Intent(activity,MonitorService.class));
        AppRuntime.IO.execute(()-> {
            try {
                new Vault(activity).save(user,pass,code); AppRuntime.clearSession();
                AppRuntime.prefs(activity).edit().putString("account",user).remove("vin").remove("vehicleName").apply();
                JSONArray cars=AppRuntime.api(activity).vehicles();
                activity.runOnUiThread(()-> { dialog.dismiss(); pickCar(activity,cars,done); });
            } catch(Exception error) {
                AppRuntime.clearSession();
                activity.runOnUiThread(()-> { button.setEnabled(true); toast(activity,AppRuntime.safeError(error)); });
            }
        });
    }
    /** Fetches the account's cars and lets the user pick one. */
    static void changeCar(Activity activity,Runnable done) {
        toast(activity,"Loading your cars…");
        AppRuntime.IO.execute(()-> {
            try {
                JSONArray cars=AppRuntime.api(activity).vehicles();
                activity.runOnUiThread(()->pickCar(activity,cars,done));
            } catch(Exception error) { AppRuntime.clearSession(); activity.runOnUiThread(()->toast(activity,AppRuntime.safeError(error))); }
        });
    }
    /** One car is selected straight away; several get a list. */
    private static void pickCar(Activity activity,JSONArray cars,Runnable done) {
        try {
            if(cars.length()==0) { toast(activity,"No cars on this BYD account."); return; }
            String[] labels=new String[cars.length()];
            for(int i=0;i<cars.length();i++) {
                JSONObject car=cars.getJSONObject(i); String vin=car.getString("vin");
                labels[i]=car.optString("modelName","My BYD")+" · VIN ···"+vin.substring(Math.max(0,vin.length()-4));
            }
            if(cars.length()==1) { select(activity,cars.getJSONObject(0),labels[0],done); return; }
            new AlertDialog.Builder(activity).setTitle("Choose your car").setItems(labels,(d,index)-> {
                try { select(activity,cars.getJSONObject(index),labels[index],done); }
                catch(JSONException e) { toast(activity,"BYD sent an unreadable car list."); }
            }).setNegativeButton("Cancel",null).show();
        } catch(JSONException e) { toast(activity,"BYD sent an unreadable car list."); }
    }
    private static void select(Activity activity,JSONObject car,String label,Runnable done) throws JSONException {
        AppRuntime.prefs(activity).edit().putString("vin",car.getString("vin")).putString("vehicleName",car.optString("modelName","My BYD")).apply();
        ArrivalWidget.updateAll(activity);
        toast(activity,"Ready: "+label);
        done.run();
    }
    private static void toast(Activity activity,String text) { Toast.makeText(activity,text,Toast.LENGTH_LONG).show(); }
}
