package com.call2iran.gateway;

import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public class CryptoUtils {

    private static final String ALGORITHM = "AES/CBC/PKCS5Padding";

    public static String encrypt(String plaintext, String hexKey) throws Exception {
        byte[] key = hexToBytes(hexKey);
        byte[] iv = new byte[16];
        new SecureRandom().nextBytes(iv);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes("UTF-8"));

        return bytesToHex(iv) + ":" + bytesToHex(encrypted);
    }

    public static String decrypt(String encrypted, String hexKey) throws Exception {
        int colonIdx = encrypted.indexOf(':');
        if (colonIdx < 0) throw new IllegalArgumentException("Invalid encrypted format");

        byte[] iv = hexToBytes(encrypted.substring(0, colonIdx));
        byte[] ciphertext = hexToBytes(encrypted.substring(colonIdx + 1));
        byte[] key = hexToBytes(hexKey);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(ciphertext);

        return new String(decrypted, "UTF-8");
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] bytes = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            bytes[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return bytes;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }
}
