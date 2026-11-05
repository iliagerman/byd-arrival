package local.byd.arrival;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.*;

public final class Crypto {
    static String hex(byte[] data) {
        StringBuilder b = new StringBuilder();
        for (byte v : data) b.append(String.format(Locale.ROOT, "%02X", v & 255));
        return b.toString();
    }
    static byte[] unhex(String text) {
        if (text.length() % 2 != 0 || !text.matches("[0-9A-Fa-f]+")) throw new IllegalArgumentException("Invalid hex");
        byte[] out = new byte[text.length()/2];
        for (int i=0; i<out.length; i++) out[i]=(byte)Integer.parseInt(text.substring(i*2,i*2+2),16);
        return out;
    }
    static String md5(String text) throws Exception {
        return hex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    static String sign(Map<String, String> fields, String key) throws Exception {
        StringJoiner join = new StringJoiner("&");
        for (Map.Entry<String,String> e : new TreeMap<>(fields).entrySet()) join.add(e.getKey()+"="+e.getValue());
        byte[] bytes = MessageDigest.getInstance("SHA-1").digest((join+"&password="+key).getBytes(StandardCharsets.UTF_8));
        StringBuilder mixed = new StringBuilder();
        for (int i=0; i<bytes.length; i++) {
            String h=String.format(Locale.ROOT,"%02x",bytes[i]&255);
            mixed.append(i%2==0 ? h.toUpperCase(Locale.ROOT) : h);
        }
        StringBuilder out=new StringBuilder();
        for(int i=0;i<mixed.length();i++) if (!(i%2==0 && mixed.charAt(i)=='0')) out.append(mixed.charAt(i));
        return out.toString();
    }
    static String encrypt(String text,String key) throws Exception {
        Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(unhex(key),"AES"),new IvParameterSpec(new byte[16]));
        return hex(c.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }
    static String decrypt(String text,String key) throws Exception {
        Cipher c=Cipher.getInstance("AES/CBC/PKCS5Padding");
        c.init(Cipher.DECRYPT_MODE,new SecretKeySpec(unhex(key),"AES"),new IvParameterSpec(new byte[16]));
        return new String(c.doFinal(unhex(text)),StandardCharsets.UTF_8);
    }
}
