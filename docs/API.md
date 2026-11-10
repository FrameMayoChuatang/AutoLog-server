# API 및 차량 앱 연동 계약

기본 URL은 `http://127.0.0.1:8080`입니다. JSON 필드와 모든 요청 스키마는 실행 중인 서버의 `/docs`, `/api/docs`에서 확인할 수 있습니다. `POST` 생성은 201, 나머지 성공은 특별한 표시가 없으면 200, 로그아웃은 204입니다. 운행 동기화 `PUT`은 최초 수신에도 200을 반환합니다.

인증은 `Authorization: Bearer <accessToken>`입니다. opaque 토큰이므로 JWT처럼 해석하지 않습니다. 회사 ID나 권한을 요청 본문에 넣지 않습니다. 모르는 JSON 필드는 400입니다. `PATCH`의 아래 DTO는 부분 필드 병합이 아니라 해당 편집 폼의 전체 값을 받습니다.

## 인증과 계정

| 메서드·경로 | 권한 | 요청·역할 |
|---|---|---|
| POST `/api/auth/companies` | 공개 | `companyName`, `ownerName`, `email`, `password`; 회사+OWNER 생성 |
| POST `/api/auth/login` | 공개 | `email`, `password` |
| POST `/api/auth/password/setup` | 공개 | `token`, `password`; 일회용 직원 설정 링크 사용 |
| GET `/api/auth/me` | 관리자/차량 | 현재 관리자 또는 차량 정보 |
| POST `/api/auth/logout` | 관리자/차량 | 현재 토큰 폐기, 204 |
| GET `/api/admins` | OWNER | 관리자 목록, `passwordConfigured` 표시 |
| POST `/api/admins` | OWNER | `email`, `name`; STAFF 생성 + 초대 정보 |
| POST `/api/admins/{id}/setup-link` | OWNER | 활성 STAFF의 비밀번호 설정 링크 재발급 |
| PATCH `/api/admins/{id}/active` | OWNER | `active`; OWNER 비활성화는 거절 |

가입/로그인/비밀번호 설정 응답:

```json
{
  "accessToken": "일회성으로_표시되는_토큰",
  "tokenType": "Bearer",
  "expiresAt": "2026-11-11T12:00:00Z",
  "admin": {
    "id": "관리자 UUID",
    "companyId": "회사 UUID",
    "email": "owner@example.test",
    "name": "대표",
    "role": "OWNER",
    "active": true,
    "createdAt": "2026-11-11T00:00:00Z"
  }
}
```

STAFF 생성은 `{ "admin": {...}, "invitation": {"setupToken":"...", "setupLink":"http://127.0.0.1:8080/password/setup#token=...", "expiresAt":"..."} }`를 반환합니다. 대표가 링크를 직접 전달합니다. 서버가 메일을 보내지 않습니다. 재발급 응답은 `invitation` 안쪽 객체입니다. 설정 페이지가 서버에 포함되어 있습니다.

비밀번호는 10자 이상, UTF-8 72바이트 이하입니다. 이메일은 소문자로 정규화하고 전체 서비스에서 유일합니다. 비활성 관리자와 아직 비밀번호를 정하지 않은 관리자는 로그인할 수 없습니다. 로그아웃·비활성화·비밀번호 재설정 후 이전 토큰은 401입니다.

## 차량 연결

| 메서드·경로 | 권한 | 요청·역할 |
|---|---|---|
| GET `/api/vehicles` | 관리자 | 회사의 차량 목록, 비활성 포함 |
| GET `/api/vehicles/{id}` | 관리자 | 차량 정보 |
| POST `/api/vehicles` | 관리자 | `registrationNumber`, `model`, `active` |
| PATCH `/api/vehicles/{id}` | 관리자 | 같은 필드 전체 편집. 비활성화 시 차량 토큰 폐기 |
| POST `/api/vehicles/{id}/pairing` | 관리자 | `{token, expiresAt}` 8자리 숫자 페어링 코드 발급 |
| POST `/api/device/pair` | 공개 | `pairingToken`(8자리 숫자 문자열); 차량 토큰 발급 |
| GET `/api/device/vehicle` | 차량 | 현재 연결된 자기 차량 정보 |

발급 응답의 `token`은 `"00123456"`처럼 **앞의 0을 포함하는 8자리 문자열**입니다. 기존 JSON 필드명 `token` / `pairingToken`은 유지합니다. 앱은 숫자 키보드로 입력받되 정수로 변환하지 않고 다음처럼 전송합니다.

```json
{"pairingToken":"00123456"}
```

코드는 기본 10분 후 만료되고 한 번만 사용할 수 있습니다. 새 코드를 발급하면 같은 차량의 이전 코드는 즉시 폐기됩니다. 짧은 코드는 접속 토큰으로 쓸 수 없으며 관리자/차량 접속 토큰과 비밀번호 설정 토큰은 기존의 긴 랜덤 문자열을 유지합니다. DB에는 코드 원문 대신 해시만 저장하고, 중복 코드가 나오면 다시 생성합니다. 이전 43자 페어링 코드는 새 규격에 맞게 재발급해야 합니다.

`POST /api/device/pair`는 연결 IP당 10분 동안 최대 20회 시도할 수 있습니다(성공·실패 모두 포함). 초과하면 `429 RATE_LIMITED`와 초 단위 `Retry-After`를 반환하므로 자동 반복 대신 안내 후 대기합니다. 단일 서버 메모리 기준이며 `X-Forwarded-For`를 신뢰하지 않습니다. 여러 서버나 프록시를 도입할 때는 공통 제한과 신뢰 프록시 설정을 함께 구성해야 합니다.

페어링 결과는 `{accessToken, tokenType, expiresAt, vehicle}`입니다. 페어링 토큰을 관리자 로그인 토큰 대신 사용하지 않습니다. 차량은 반환된 `accessToken`을 안전하게 보관합니다. 페어링을 재실행하면 기존 차량 연결을 폐기합니다. 재시도 중 응답을 잃었고 일회용 토큰이 이미 사용됐다면 새 페어링 토큰을 받아야 합니다. 단일 차량 토큰으로 여러 차량을 업로드할 수 없습니다.

## 운전자·동의·목록

| 메서드·경로 | 권한 | 요청·역할 |
|---|---|---|
| GET `/api/drivers?q=` | 관리자 | 이름/부서 검색, 모든 상태 |
| POST `/api/drivers` | 관리자 | `name`, `department`; ACTIVE 등록 |
| PATCH `/api/drivers/{id}` | 관리자 | `name`, `department`, `status` 전체 편집 |
| POST `/api/drivers/{id}/approve` | 관리자 | PENDING → ACTIVE |
| GET `/api/device/drivers?q=` | 차량 | 회사의 ACTIVE/PENDING 이름·부서 목록 |
| POST `/api/device/drivers` | 차량 | `name`, `department`; PENDING 등록 |
| GET `/api/device/drivers/recent` | 차량 | 이 차량에서 최근 사용한 ACTIVE 운전자 최대 5명 |
| POST `/api/device/drivers/{id}/consent` | 차량 | `granted`, `noticeVersion`; 위치정보 고지 동의/철회 이력 |
| GET `/api/drivers/{id}/consents` | 관리자 | 동의/철회 이력 |
| GET `/api/catalog/{kind}` | 관리자 | 목적/거래처 전체 목록 |
| POST `/api/catalog/{kind}` | 관리자 | `name`, `active`, 거래처에만 선택 `address`(최대 300자), `description`(최대 500자) |
| PATCH `/api/catalog/{kind}/{id}` | 관리자 | 같은 필드 전체 편집 |
| GET `/api/device/catalog/{kind}` | 차량 | 활성 항목만 조회 |
| GET `/api/device/drivers/{id}/recommendations` | 차량 | 해당 운전자의 최근 확인된 목적·거래처 후보 |
| GET `/api/drivers/{id}/recommendations` | 관리자 | 같은 후보 조회 |

`kind`는 `purposes`, `clients`만 가능합니다. 운전자 상태는 `PENDING`, `ACTIVE`, `INACTIVE`입니다. 이름 중복이 가능하므로 표시 이름 대신 UUID로 선택하고 부서를 함께 보여줍니다. PENDING 운전자의 측정 기록도 보관하지만 ACTIVE 승인 전에는 운전자 확인을 할 수 없습니다. INACTIVE 운전자의 뒤늦은 오프라인 측정 기록도 보존하며 새 확인은 제한합니다.

동의 철회(`granted=false`)는 비활성 운전자도 가능합니다. 비활성 운전자의 새 동의는 거절합니다. 과거 운전자를 그대로 둔 관리자 메타데이터 수정은 허용하고, 새 운전자 선택이나 운전자 확인에는 ACTIVE가 필요합니다.

추천은 `classification`, `purposeId`, `purposeText`, `clientId`, `clientText`, `lastUsedAt`, `usageCount`를 반환합니다. 최신 확인 순 최대 5개 조합이며 확인 해제·보완 요청 기록과 비활성 목록은 제외합니다. 추천을 선택할 때 ID가 있으면 ID만 전송하고, ID가 없으면 직접 입력 텍스트를 전송합니다. 추천 응답 전체를 확인 요청으로 복사하면 안 됩니다. 위치·AI 모델·자동 확정은 사용하지 않습니다.

## 운행 동기화

`PUT /api/device/trips/{tripId}`. 앱에서 UUID를 생성하여 오프라인에서도 같은 운행 ID를 유지합니다. 관리자 토큰으로 호출할 수 없습니다.

```json
{
  "sourceRevision": 1,
  "baseServerVersion": 0,
  "driverId": null,
  "startedAt": "2026-10-10T00:00:00Z",
  "endedAt": null,
  "lastObservedAt": "2026-10-10T00:10:00Z",
  "startOdometerKm": 10000.125,
  "endOdometerKm": null,
  "lastOdometerKm": 10004.25,
  "state": "RUNNING",
  "measurementSource": "PLEOS_ODOMETER",
  "collectionIssues": []
}
```

| 필드 | 계약 |
|---|---|
| sourceRevision | 양의 정수. 앱에서 데이터 변경마다 증가 |
| baseServerVersion | 마지막으로 확인한 서버 버전. 최초 생성은 0 |
| driverId | 회사 운전자 UUID 또는 null. 처음 null이면 추후 지정 가능 |
| startedAt | 필수 UTC offset 포함 ISO-8601 시각. 최초 저장 뒤 변경 불가 |
| endedAt | COMPLETED에 필수, RUNNING에는 지정 불가 |
| lastObservedAt | 마지막 실제 관측 시각. 마지막 계기판 값을 보낼 때 필수 |
| startOdometerKm | 시작 계기판 값. null/0이면 미확인으로 보존하며 확인 전 실제 값으로 갱신 가능. 양의 저장값 변경 불가 |
| endOdometerKm | 종료 계기판 값. 실제 값이 없으면 null. RUNNING에는 지정 불가 |
| lastOdometerKm | 종료 전에 저장한 마지막 관측값. 종료값을 대신하지 않음 |
| state | RUNNING / COMPLETED / INTERRUPTED |
| measurementSource | 선택: PLEOS_ODOMETER / UNKNOWN. 생략 시 기존 값 유지, 신규의 생략값은 null(출처 미제공) |
| collectionIssues | 선택: DATA_UNAVAILABLE / ODOMETER_DISCONTINUITY / SAMPLING_INTERRUPTED 배열. 생략 시 기존 값 유지, 실제 복구 후 명시적 빈 배열로 해제. 전후 이력 보존 |

거리는 음수가 아닌 **km**, 소수점 최대 3자리, 정수부 최대 11자리입니다. SDK의 m/float 입력은 앱에서 계약 단위와 정밀도로 변환합니다. 시각은 최대 소수점 6자리이고 시작 ≤ 관측 ≤ 종료 순서를 지킵니다. 현재 시각보다 5분 이상 미래인 입력은 400입니다.

응답은 저장한 운행 필드와 `id`, `companyId`, `vehicleId`, `sourceRevision`, `serverVersion`, `classification`, `approvalStatus`, `driverConfirmedAt`, `approvedAt`, `qualityIssues`, `distanceKm`, `observedDistanceKm`, `durationSeconds`, `observedDurationSeconds`, `measurementLockedAt`, `measurementSource`, `collectionIssues`, `reportEligible`를 포함합니다. `durationSeconds`는 COMPLETED의 종료-시작, `observedDurationSeconds`는 마지막 관측-시작(각각 초)이며 해당 시각이 없으면 null입니다. 서버 현재 시각으로 경과 시간을 추정하지 않습니다. 최초 `serverVersion=1`, `classification=UNCONFIRMED`, `approvalStatus=NOT_READY`입니다. 동일 요청 재전송에는 `replayed=true`가 추가됩니다. 이 내부 동기화 해시는 응답하지 않습니다.

### 재전송과 충돌 처리

1. 로컬에 tripId와 전송 payload를 저장합니다. 새 기록은 `baseServerVersion=0`으로 보냅니다.
2. 200 응답을 받은 뒤 반환된 `serverVersion`을 저장하고 그 revision을 ACK 처리합니다.
3. 응답을 못 받으면 **같은 sourceRevision과 같은 payload**로 재전송합니다. 현재 서버 상태를 반환하며 중복 기록·감사 이력을 생성하지 않습니다. ACK 전에는 재시도 payload를 바꾸지 않습니다.
4. 새 내용은 sourceRevision을 늘리고 마지막 ACK의 serverVersion을 baseServerVersion으로 보냅니다. 비행 중인 요청의 응답을 받기 전에 새 payload를 보내지 않는 순차 큐를 권장합니다.
5. 409면 `GET /api/device/trips/{id}`로 현재 상태를 조회합니다. 단순 서버 버전 차이인지 관리자 편집/확인/승인인지 확인한 후 병합 가능한 측정 갱신만 새 revision으로 보냅니다. 무조건 버전 숫자만 바꾸어 덮어쓰면 안 됩니다.
6. 401이면 토큰 만료·폐기 상태입니다. 관리자에게 새 페어링 토큰을 받아 연결합니다. 400/404는 데이터를 수정하기 전까지 무한 재전송하지 않습니다.

같은 revision의 다른 payload, 더 낮은 revision, 오래된 baseServerVersion은 409입니다. 한 번이라도 확인된 기록은 `measurementLockedAt`으로 원본을 잠급니다. 보완 요청·운전자 변경·미확인 재분류로 현재 확인이 해제되어도 원본은 동기화로 덮어쓸 수 없습니다. 새 선택 필드를 보내지 않는 이전 앱의 동일 요청 재시도도 호환됩니다. 같은 측정 요청을 다시 보내도 이후의 관리자 수정/승인을 되돌리지 않습니다. 표시되는 현재 버전이 과거 ACK보다 높을 수 있습니다.

강제 종료 시 마지막 스냅샷만 있는 기록은 INTERRUPTED로 보내고 end 값을 추정해서 채우지 않습니다. 실제 종료값을 확보하면 버전을 확인하여 COMPLETED로 갱신합니다. 중간 정차·경유는 종료 버튼을 누르지 않았다면 같은 tripId의 RUNNING을 유지합니다. 실제 운전자 교대는 기존 구간을 실제 값으로 종료하고 새 tripId로 다음 구간을 만듭니다. 기존 구간의 driverId를 다른 사람으로 바꾸려는 동기화는 거절합니다.

유효한 마지막 누적거리·관측 시각을 더 작은 값·과거 시각으로 되돌리는 갱신은 409입니다. 마지막 관측 필드를 생략해도 저장값은 유지합니다. 기존 관측값을 둔 채 시각만 새로 보내면 이전 값을 최신 관측처럼 보이게 하므로 400입니다. 시각을 갱신할 때는 계기판 값도 함께 보냅니다. 저장된 마지막 관측보다 이른 종료 시각은 400입니다.

### 품질 표시

`INCOMPLETE`, `MISSING_START_ODOMETER`, `MISSING_END_ODOMETER`, `ZERO_START_ODOMETER`, `ODOMETER_REGRESSION`, `ZERO_DURATION_WITH_DISTANCE`, `LAST_ODOMETER_BEFORE_START`, `LAST_ODOMETER_AFTER_END`와 앱이 보고한 `collectionIssues`를 사용합니다. 문제가 있으면 최종 `distanceKm=null`이며 확인/승인을 거절합니다. 정상 미완료 관측값은 `observedDistanceKm`로만 제공합니다. 전송 구조 오류와 달리 계기판 누락·역전은 실제로 발생할 수 있는 기록이므로 서버가 보존합니다.

## 운전자 확인·관리자 검토

| 메서드·경로 | 권한 | 요청 |
|---|---|---|
| POST `/api/device/trips/{id}/confirm` | 해당 차량 | 아래 Details. 이미 확인한 기록의 재확인에는 reason 필수 |
| PATCH `/api/device/trips/{id}` | 해당 차량 | 아래 Details + 필수 reason. 종료된 미승인 기록의 분류 편집, 확인 해제 |
| PATCH `/api/trips/{id}` | 관리자 | 아래 Details + 필수 reason |
| POST `/api/trips/{id}/approve` | 관리자 | `expectedVersion`, 선택 `reason` |
| POST `/api/trips/{id}/request-changes` | 관리자 | `expectedVersion`, 필수 `reason` |
| GET `/api/trips/{id}/audit` | 관리자 | 버전순 수정 전후·행위자·사유·시각 |

Details 예시:

```json
{
  "expectedVersion": 2,
  "driverId": "운전자 UUID",
  "classification": "BUSINESS",
  "purposeId": "목적 UUID",
  "purposeText": null,
  "clientId": null,
  "clientText": "직접 입력 거래처",
  "note": "신규 계약 미팅",
  "reason": "업무 목적 확인"
}
```

`classification`은 COMMUTE(출퇴근), BUSINESS(업무), PERSONAL(개인), UNCONFIRMED(미확인)입니다. PATCH에서는 UNCONFIRMED를 선택해 확인을 해제할 수 있고, confirm에서는 미확인 분류를 거절합니다. BUSINESS에는 purposeId 또는 purposeText가 필요합니다. 각각 ID와 직접 입력 텍스트를 동시에 넣을 수 없습니다. 거래처는 선택 항목입니다. `purposeText/clientText` 최대 200자, `note/reason` 최대 1,000자입니다.

확인은 정상 완료·활성 운전자 조건에서 PENDING으로 변경합니다. 관리자는 PENDING만 승인할 수 있습니다. 같은 차량의 이미 승인된 기록과 시간 또는 계기판 구간이 겹치면 409입니다. 승인 후 관리자 편집은 승인 정보를 지우고 PENDING으로 되돌립니다. 운전자 자체를 수정하거나 UNCONFIRMED로 재분류하면 driverConfirmedAt도 해제되어 NOT_READY가 되고 새 확인이 필요합니다. 차량의 PATCH도 확인을 해제하며, 이미 승인된 기록은 관리자에게 수정 요청해야 합니다. 보완 요청은 CHANGES_REQUESTED로 바꾸고 운전자 확인을 해제합니다.

동시 편집에는 `expectedVersion` 충돌 409를 반환합니다. 새 상태를 다시 읽고 변경 내용을 검토해야 합니다. 관리자 편집은 계기판 원본·운행 시각을 임의로 고치지 않습니다.

## 조회·통계·보고서

- `GET /api/trips`, `GET /api/device/trips`: `vehicleId`, `driverId`, `from`, `to`, `state`, `classification`, `approval`, `confirmed`, `page=0`, `size=50`. size 1~200, page 0~100000. 기간은 시작 시각 기준 `from <= startedAt < to`. 모든 기간/필터는 선택입니다. `confirmed=true/false`는 실제 `driverConfirmedAt` 존재 여부이며 분류·승인 상태와 별개입니다. 목록과 total은 같은 DB 스냅샷입니다.
- 응답: `{items: [...], total, page, size}`. 시작 시각 내림차순, 동일 시각에는 UUID 내림차순입니다. 차량은 자기 차량 기록만 볼 수 있습니다.
- `GET /api/trips/{id}`, `GET /api/device/trips/{id}`: 단건.
- `GET /api/trips/gaps?month=YYYY-MM&vehicleId=...`: 관리자 공백 검토.
- `GET /api/statistics?month=YYYY-MM&vehicleId=...`: 관리자 통계.
- `GET /api/reports/monthly.xlsx?month=YYYY-MM&vehicleId=...`: 월간 승인 기록 다운로드.
- `GET /api/reports/nts-draft.xlsx?month=YYYY-MM&vehicleId=...`: 국세청 서식 초안 다운로드.

월간 API의 `month`는 필수, vehicleId는 선택입니다. 자세한 합계·빈칸·공백 기준은 [REPORTS.md](REPORTS.md)에 있습니다.

## 오류와 설정

```json
{"code":"CONFLICT","message":"다른 변경이 먼저 반영되었습니다. 최신 serverVersion으로 다시 요청하세요.","timestamp":"2026-11-11T00:00:00Z"}
```

| 상태 | 처리 |
|---|---|
| 400 INVALID_REQUEST | 필수값·형식·시각·입력 정책 오류 |
| 401 UNAUTHORIZED | 로그인 필요, 토큰 만료/폐기, 비밀번호 오류, 일회용 토큰 이미 사용 |
| 403 FORBIDDEN | 권한 또는 자격 증명 종류 불일치 |
| 404 NOT_FOUND | 대상 없음 또는 접근 가능한 회사/차량 범위 밖 |
| 409 CONFLICT / DATA_CONFLICT | 버전·상태 충돌, 중복 이메일/차량번호/목록 이름, 데이터 연결 불가 |
| 429 RATE_LIMITED | 페어링 시도 한도 초과. `Retry-After`(초) 이후 다시 시도 |

| 설정 | 기본값 |
|---|---|
| DB_URL / DB_USERNAME / DB_PASSWORD | 로컬 PostgreSQL |
| TEST_DB_URL | jdbc:postgresql://localhost:55432/autolog_test |
| SERVER_ADDRESS / SERVER_PORT | 127.0.0.1 / 8080 |
| SETUP_URL | http://127.0.0.1:8080/password/setup |
| CORS_ORIGINS | localhost 및 127.0.0.1의 5173/3000 포트 |
| AUTOLOG_SESSION_TTL | 12h |
| AUTOLOG_DEVICE_TTL | 90d |
| AUTOLOG_SETUP_TTL | 24h |
| AUTOLOG_PAIRING_TTL | 10m |

프런트엔드의 다운로드 요청은 Bearer 헤더를 포함한 fetch로 호출하고 응답 blob을 저장합니다. CORS에 Content-Disposition을 노출합니다. 개발용 비밀번호·토큰을 소스에 저장하지 않습니다.
