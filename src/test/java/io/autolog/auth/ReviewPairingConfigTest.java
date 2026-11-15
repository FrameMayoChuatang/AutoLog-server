package io.autolog.auth;

import static org.assertj.core.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReviewPairingConfigTest {
  private static final String COMPANY = "7feec840-e7d0-49dc-a3d1-dbf640d10001";
  private static final String VEHICLE = "7feec840-e7d0-49dc-a3d1-dbf640d10002";

  @Test
  void disabledConfigurationDoesNotReserveAnyCode() {
    var config = new ReviewPairingConfig(false, "00123456", COMPANY, VEHICLE);
    assertThat(config.matches(TokenCodec.hash("00123456"))).isFalse();
    var empty = new ReviewPairingConfig(false, "", "", "");
    assertThat(empty.matches(TokenCodec.hash("00123456"))).isFalse();
  }

  @Test
  void enabledConfigurationPreservesLeadingZerosAndBindsOneVehicle() {
    var config = new ReviewPairingConfig(true, "00123456", COMPANY, VEHICLE);
    assertThat(config.matches(TokenCodec.hash("00123456"))).isTrue();
    assertThat(config.matches(TokenCodec.hash("11223344"))).isFalse();
    assertThat(config.companyId()).isEqualTo(UUID.fromString(COMPANY));
    assertThat(config.vehicleId()).isEqualTo(UUID.fromString(VEHICLE));
  }

  @Test
  void enabledConfigurationRejectsMissingMalformedAndNonAsciiCodesWithoutEchoingSecrets() {
    for (String code :
        new String[] {"", "1234567", "123456789", "１２３４５６７８", "1234 678", "12345678\n"}) {
      assertThatThrownBy(() -> new ReviewPairingConfig(true, code, COMPANY, VEHICLE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("심사용 페어링 코드는 8자리 숫자로 설정해야 합니다.");
    }
  }

  @Test
  void enabledConfigurationRejectsMissingOrMalformedTargetIds() {
    for (String id : new String[] {"", "not-a-uuid"}) {
      assertThatThrownBy(() -> new ReviewPairingConfig(true, "00123456", id, VEHICLE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("심사용 회사 ID와 차량 ID를 유효한 UUID로 설정해야 합니다.");
      assertThatThrownBy(() -> new ReviewPairingConfig(true, "00123456", COMPANY, id))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessage("심사용 회사 ID와 차량 ID를 유효한 UUID로 설정해야 합니다.");
    }
  }
}
