package io.autolog.auth;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

public final class TokenCodec {
  private static final SecureRandom RANDOM = new SecureRandom();

  private TokenCodec() {}

  public static String create() {
    byte[] bytes = new byte[32];
    RANDOM.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String createPairingCode() {
    return String.format(Locale.ROOT, "%08d", RANDOM.nextInt(100_000_000));
  }

  public static String hash(String token) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException(ex);
    }
  }
}
