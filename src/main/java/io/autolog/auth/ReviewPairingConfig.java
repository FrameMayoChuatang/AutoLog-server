package io.autolog.auth;

import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Limits the reusable review code to one explicitly configured company and vehicle. */
@Component
public final class ReviewPairingConfig {
  private final String codeHash;
  private final UUID companyId, vehicleId;

  public ReviewPairingConfig(
      @Value("${autolog.review-pairing.enabled:false}") boolean enabled,
      @Value("${autolog.review-pairing.code:}") String code,
      @Value("${autolog.review-pairing.company-id:}") String companyId,
      @Value("${autolog.review-pairing.vehicle-id:}") String vehicleId) {
    if (!enabled) {
      this.codeHash = null;
      this.companyId = null;
      this.vehicleId = null;
      return;
    }
    if (!code.matches("[0-9]{8}")) {
      throw new IllegalArgumentException("심사용 페어링 코드는 8자리 숫자로 설정해야 합니다.");
    }
    this.codeHash = TokenCodec.hash(code);
    try {
      this.companyId = UUID.fromString(companyId);
      this.vehicleId = UUID.fromString(vehicleId);
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("심사용 회사 ID와 차량 ID를 유효한 UUID로 설정해야 합니다.");
    }
  }

  public boolean matches(String hash) {
    return codeHash != null && codeHash.equals(hash);
  }

  public UUID companyId() {
    return companyId;
  }

  public UUID vehicleId() {
    return vehicleId;
  }
}
