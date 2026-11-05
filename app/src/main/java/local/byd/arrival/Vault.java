package local.byd.arrival;

import android.content.*;
import android.security.keystore.*;
import android.util.Base64;
import java.security.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** App-private AES-GCM ciphertext. Key remains in Android Keystore, usable while locked. */
final class Vault {
    private static final String ALIAS="byd-arrival-v1";
    private final Context context;
    Vault(Context context) { this.context=context; }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if(store.containsAlias(ALIAS)) return (SecretKey)store.getKey(ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance("AES","AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(false).build());
        return generator.generateKey();
    }
    void save(String username,String password,String pin) throws Exception {
        if(!username.contains("@") || password.isEmpty() || !pin.matches("[0-9]{6}"))
            throw new IllegalArgumentException("Enter BYD email, password and six-digit PIN");
        JSONObject data=new JSONObject().put("username",username).put("password",password).put("pin",pin);
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
        byte[] encrypted=cipher.doFinal(data.toString().getBytes(StandardCharsets.UTF_8));
        boolean saved=context.getSharedPreferences("vault",0).edit()
            .putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .putString("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit();
        if(!saved) throw new java.io.IOException("Could not save credentials");
    }
    JSONObject load() throws Exception {
        SharedPreferences prefs=context.getSharedPreferences("vault",0);
        String ciphertext=prefs.getString("ciphertext",null), iv=prefs.getString("iv",null);
        if(ciphertext==null || iv==null) throw new IllegalStateException("Save credentials first");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(iv,Base64.NO_WRAP)));
        return new JSONObject(new String(cipher.doFinal(Base64.decode(ciphertext,Base64.NO_WRAP)),StandardCharsets.UTF_8));
    }
    void forget() throws Exception {
        if(!context.getSharedPreferences("vault",0).edit().clear().commit()) throw new java.io.IOException("Could not erase credentials");
        KeyStore store=KeyStore.getInstance("AndroidKeyStore"); store.load(null); store.deleteEntry(ALIAS);
    }
}
