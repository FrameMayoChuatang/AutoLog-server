# Docs 상세 명세 대조 검토

2026-11-11 기준 [기능 명세서](https://app.notion.com/p/41112d57245380d49f5aff55230600d6)의 **45개 기능 페이지를 각각 열어 속성·본문·예외 처리·완료 조건을 읽고** 서버 코드와 대조했습니다. P0 27개, P1 14개가 합의 범위이며 P2 4개도 경계를 확인하기 위해 읽었습니다. [기획서](https://app.notion.com/p/41112d57245380238b49c1d6929be78f), [평가 메모](https://app.notion.com/p/41112d57245380989752d83597918fc7), [카택스 비교](https://app.notion.com/p/41212d5724538074bba2ed416a50318a)도 함께 확인했습니다.

검토는 서버 구현과 연동 계약을 대상으로 합니다. 차량 앱 UI·Pleos SDK 실차 수집·백그라운드 실행·오프라인 로컬 DB를 이 저장소에서 구현하거나 실차 검증한 것으로 간주하지 않습니다. 앱 책임인 기능도 아래에 남겨 통합 시 빠지지 않도록 했습니다.

## 발견 및 수정

- 보완 요청이 현재 운전자 확인 시각을 해제하면 확인했던 원시 거리도 다시 동기화할 수 있었습니다. 영구 측정 잠금을 추가하고 기존 확인 기록·감사 이력을 마이그레이션했습니다.
- 마지막 관측값과 종료값의 역전, 관측 시각·거리의 퇴행 및 관측 필드 생략을 보완했습니다. SDK 수집 출처와 오류, 최종·관측 경과 시간도 응답합니다.
- 바로 이전이 아닌 앞선 장시간 운행과 겹칠 때 공백을 잘못 합산했습니다. 이전 종료 시각의 최댓값을 사용하고 오류가 있는 이전 관측도 공백 계산에서 제외했습니다. 중첩 운행이 모두 끝났더라도 바로 이전 기록의 종료가 최신 경계가 아니면 판단 불가로 남깁니다.
- 확인 여부 필터, 운전자의 종료 후 미승인 기록 편집, 미확인 분류를 추가했습니다. 재확인 수정에는 사유가 필요하고 승인된 기록의 차량 편집은 차단합니다.
- 기존 운전자가 비활성이면 관리자 목적 정정과 동의 철회도 막히던 문제를 수정했습니다. 새 확인·동의에는 활성 조건을 유지합니다.
- 거래처 설명, 미확인/승인 대기 거리 구분, 차량별 운행일수·월말 근거·보고사항을 추가했습니다. 월말 관측이 없으면 추정 확정값 대신 관측 시각과 확인 필요 상태를 제공합니다.
- 운행기록부 사용자 칸을 Docs대로 운전자 이름·부서로 미리 채우고, 실제 사용자 확인 안내를 유지했습니다.
- 경유 시 구간을 분할한다는 기존 연동 문구를 바로잡았습니다. 종료하지 않은 중간 정차는 같은 세션이며 실제 운전자 교대만 새 구간입니다.
- 비밀번호 설정 성공 후 로그아웃 네트워크 오류를 설정 실패로 표시하던 페이지 흐름도 수정했습니다.

## P0 상세 대조

| 기능 원문 | 확인한 요구·예외 | 서버 대응·검증 |
|---|---|---|
| [운전자 검색](https://app.notion.com/p/dca12d5724538292987201379f0f2326) | 이름 검색, 동명이인 구분, 검색 결과를 자동 신규 등록하지 않음 | 이름·부서 검색, UUID 선택. Fleet API 및 회사 격리 검사 |
| [운전자 선택](https://app.notion.com/p/a4512d57245382b886ea81fa8ef9cb95) | 회사 운전자 선택, 임의 자동 선택 없음 | 등록 ID와 회사 검증, ACTIVE 확인 조건. `foreignDriverAndPurposeAreRejectedAndPendingDriverCannotConfirm` |
| [운행 목적 분류](https://app.notion.com/p/3b212d57245382b2a60e8146477a56f7) | 출퇴근·업무·개인·미확인, 추천은 확정 아님 | UNCONFIRMED 편집 허용·확인 해제. `adminCanReturnClassificationToUnconfirmedWithoutApprovingIt` |
| [운행 목적 입력](https://app.notion.com/p/37a12d572453822b8f7b8102bf09e69c) | 목록/직접 입력, 빈 업무 목적·잘못된 ID 제한 | 목록/텍스트 상호 배타, 글자 수·회사 검증, 업무 목적 필수. Trip API 검증 |
| [운행기록 확인](https://app.notion.com/p/43112d5724538390937d01a618303ecd) | 종료 후 확인, 누락·이상 자료 미확인 유지, 중복 방지 | 정상 완료만 확인, 버전 검사. `approvalRequiresConfirmationAndEditsInvalidateApproval` |
| [출퇴근·일반업무 거리 집계](https://app.notion.com/p/7c80387965434662b33433037e2bb2da) | 분류별 거리, 미확인 별도 | 합의한 승인 정책에 따라 승인 거리 분류와 미확인/대기 거리 분리. Report API |
| [기록 누락 감지](https://app.notion.com/p/cbf12d57245383ddbad1819037a6bbc5) | 기록 사이 공백·수집 중단, 자동 업무거리 보정 금지 | `/trips/gaps`, SAMPLING_INTERRUPTED; 공백 불명확 시 null. `nonAdjacentOverlapCannotBeCountedAsMissingDistance` |
| [누적거리 유효성 검사](https://app.notion.com/p/edd6be254b5f49e78dbb81e72b5cdd9e) | 속성에 부팅 0·작은 값 무시 요구, 본문 없음 | 최초 0은 검토, 유효 시작값 고정, 마지막 관측 퇴행 거절. `bootZeroCanRecoverBeforeConfirmationButValidStartIsImmutable` |
| [미완료 운행 복구](https://app.notion.com/p/a6412d572453826b8b2b81334b4476a5) | 시작·마지막 시각/계기판 보존, 종료 추정 금지 | INTERRUPTED, 관측과 종료 분리. `interruptedRecoveryPreservesUnknownDataAndRequiresFreshVersion` |
| [오프라인 동기화 대기열](https://app.notion.com/p/3077f94f2794457a823726d59f902a2e) | ACK 전 보존, 재시도 중복 방지 | UUID/revision 재응답 및 이전 앱 payload 호환. 큐는 앱 책임. `preUpgradePayloadHashStillAcknowledgesOfflineRetry` |
| [운행 데이터 수집](https://app.notion.com/p/3c512d57245383a88b3781c76b32dab0) | 수집 값·출처·시각·오류 보존 | SDK 호출은 앱, 서버는 measurementSource/collectionIssues 및 관측 시각 보존. `collectionErrorsAreRetainedUntilExplicitlyResolved` |
| [운행 상태 주기 저장](https://app.notion.com/p/754f63c12c0b48bba9eb8d6fb655ccdc) | 로컬 주기 저장, 마지막 값을 종료로 간주하지 않음 | 앱의 주기 스냅샷을 RUNNING으로 수신, 누락 필드 보존. `observedProgressCannotRegressOrDisappearOnCompletion` |
| [운행 시작](https://app.notion.com/p/0af12d572453839d8d428188564f6704) | 시작 중복 방지, 유효 계기판 확보 | 시작 시각/유효 시작값 고정, 동시 업로드 한 건. 실제 감지는 앱. Trip API 동시 업로드 테스트 |
| [운행 종료](https://app.notion.com/p/c2b12d57245382ca9ed581ffb139f64e) | 종료값으로 거리·시간 계산, 없는 값 생성 금지 | COMPLETED 종료 시각 필수, 거리 품질 검사, durationSeconds. ReviewRegressionTest |
| [운행거리 계산](https://app.notion.com/p/41412d5724538142bc2decce53be72c0) | 계기판 차이, 음수·불연속·누락 검토 | 정밀 decimal 차이, 이상이면 null 및 승인 불가. `endReadingCannotIgnoreLargerLastObservedReading` |
| [운행기록 동기화](https://app.notion.com/p/b1312d57245382d691d081e9f6b4a8a4) | 회사/차량 인증, 고유 운행 ID | 차량 토큰 범위·revision·서버 버전·동시 업로드. Trip API 격리/중복 테스트 |
| [운행기록 조회](https://app.notion.com/p/4f012d572453823ebd0f019c8a68a165) | 목록/상세 값 일치, 회사·차량 격리 | 같은 decorate 계산, 목록 total/items 스냅샷 일치. `tenantAndVehicleScopesCoverEveryTripOperation` |
| [운행정보 조회](https://app.notion.com/p/f0312d57245383fb921601a273e2ae94) | 운전자·시간·거리·마지막 갱신, 알 수 없는 값 구분 | 마지막 관측 시각, observedDurationSeconds/observedDistanceKm, null 구분. 실시간 SDK 화면은 앱 |
| [관리자 통합 조회](https://app.notion.com/p/ba312d57245382eb84618178c756210f) | 회사 통합 조회, 다른 회사 제외 | 관리자 회사 범위 목록/상세/통계. Auth/Trip/Report API 격리 테스트 |
| [관리자 회원가입·로그인](https://app.notion.com/p/74b725ad366b4efaac16333d72c6382c) | 관리자 인증·보호 경로 | 합의한 회사+OWNER/STAFF, 이메일·비밀번호, 일회용 설정·토큰 폐기. AuthApiTest |
| [운전자 등록](https://app.notion.com/p/81eb08f0a23d447cb1e78ada7b93a9ea) | 이름·부서, 차량 신규 승인 대기, 보고서 반영 | PENDING/ACTIVE, 이름 중복 가능, 운전자 스냅샷 출력. Fleet/Report API |
| [운행기록 필터링](https://app.notion.com/p/68c12d572453825ab1f701dbe209570d) | 차량·운전자·기간·유형·확인 여부 | confirmed=true/false를 분류·승인과 독립 적용. `confirmedFilterDoesNotConfuseClassificationAndApproval` |
| [운행보고서 생성](https://app.notion.com/p/caa12d572453824fb1e6810d73098bfb) | 차량별 월간, 누락 기준/미확인 구분, 합계 일치 | 승인 본표·검토/공백 및 차량별 집계. `vehicleSummaryIncludesDaysMonthEndEvidenceAndSeparateUnconfirmedDistance` |
| [월간 운행 통계](https://app.notion.com/p/6d312d5724538213a821814e14893970) | 총/업무/개인/미확인, 미완료 임의 합산 금지 | 정상 종료·승인·미확인·승인 대기·미완료 관측 분리. `statisticsSeparateApprovedUnreviewedIncompleteAndUnknownDistance` |
| [차량 등록](https://app.notion.com/p/e4036d6f5dd74ffd85f2af2baf79e106) | 차량번호/모델, 수동 코드 연결 | 회사 차량 CRUD/비활성화, 일회용 페어링. `pairingIsSingleUseAndReplacesOldDevice` |
| [회사 생성](https://app.notion.com/p/41412d57245381879d15fb8a518e1040) | 회사 단위 관리 | 회사와 OWNER 트랜잭션 생성, 회사당 OWNER 제약. AuthApiTest |
| [국세청 운행기록부 출력](https://app.notion.com/p/39912d572453824a89458191a37c5a8a) | 운전자·차량·날짜·거리·업무 분류, 검토 필요 표시 | 2025-8호 필드 초안, 운전자 이름·부서 반영, 미확정 법인 정보는 빈칸. `ntsDraftMapsOfficialFieldsAndLeavesUnconfirmedLegalFieldsBlank` |

## P1 상세 대조

| 기능 원문 | 확인한 요구·예외 | 서버 대응·검증 |
|---|---|---|
| [운전자 선택 알림](https://app.notion.com/p/29ec293e87ba40049e8d921b6a042e20) | 알림 요구 속성만 있고 본문 없음 | 앱 알림 책임. 서버 driverId=null 수신·미확인 유지 |
| [운행 목적 자동 추천](https://app.notion.com/p/5e612d57245382aaba4501bfe47c4073) | 후보 제안, 최종 선택은 운전자 | 합의한 최근 확인 기록 후보 5개, 비활성 목록 제외. `historySurvivesCatalogChangesAndRecommendationsExcludeInactiveEntries` |
| [위치정보 수집 동의](https://app.notion.com/p/c6e298da70cd40608a463a475909f459) | 고지·선택 동의·철회, 비동의여도 거리 기능 | 고지 버전/차량/시각 이력, 비활성 운전자도 철회 가능. 위치 수집은 P2. `inactiveDriverCanStillWithdrawLocationConsent` |
| [최근 운전자 표시](https://app.notion.com/p/ba512d57245383b08de8010188dfc55c) | 차량 최근 선택 후보, 자동 확정 금지 | 차량 운행에 사용한 ACTIVE 운전자 최대 5명; 선택 적용은 앱 |
| [로컬 저장 용량·보존 관리](https://app.notion.com/p/8168be3c5a274a3c89837f5f89c8d4bc) | 미전송·미완료 삭제 금지, ACK 기반 보존 | 로컬 DB/용량 관리는 앱. 서버 중복 재전송 ACK 계약 제공 |
| [수정 이력 관리](https://app.notion.com/p/66912d57245382dfbf0c0128750fcf07) | 전후 값·행위자·시각·사유 원자 기록 | trip_audit와 상태 변경 같은 트랜잭션. `confirmedDriverCorrectionRequiresAnAuditReason` |
| [운전자 변경 처리](https://app.notion.com/p/8c9ab88ef8484002be508fd49cefadbb) | 실제 교대 시 기존 종료+새 운행, 이전 운전자 덮어쓰기 금지 | sync 운전자 교체 거절. 관리자 오입력 정정은 이력+재확인. Trip API |
| [운행 자동 시작](https://app.notion.com/p/12fb5d8345ee43bebc8d60d9d2c01c67) | 앱 자동 시작, 선택 전 미확인 | SDK/백그라운드는 앱. 서버 RUNNING·운전자 null 수신 |
| [운행기록 수정](https://app.notion.com/p/8e012d57245383da99f7814a5b3737b1) | 권한·수정 사유·원본 보호 | 관리자/차량 메타데이터 편집, 원본 영구 잠금, 재승인. `requestingChangesDoesNotUnlockConfirmedMeasurements` |
| [정차 판단](https://app.notion.com/p/1e87bd98cd46430db1f280f436a03eb2) | 정차 후보와 운행 종료 구분 | 정차 판단은 앱, 서버 RUNNING 자동 종료 없음 |
| [중간 정차(경유) 처리](https://app.notion.com/p/e8a9d802a8134d67bd03cc4e319ff509) | 종료 버튼 없이 정차하면 같은 세션 | RUNNING 한 ID 유지·정차 시간 포함. `intermediateStopKeepsOneSessionUntilExplicitEndAndDraftNeedsConfirmation` |
| [거래처 등록](https://app.notion.com/p/1a77fedb0e25401789d26bf9773c3c3c) | 이름·선택 주소/설명, 회사 CRUD·비활성 이력 | 설명 500자 추가, 논리 비활성화. `clientDescriptionIsStoredUpdatedAndExcludedFromPurposeCatalog` |
| [업무 목적 목록 등록](https://app.notion.com/p/41412d57245381359660f0be2708359d) | 회사 목록 CRUD·과거 선택 보존 | 활성 후보 조회·논리 비활성·확정 스냅샷. Fleet/Trip API |
| [운행기록 승인](https://app.notion.com/p/0d512d572453828984e601efc958ab3d) | 확인 후 승인/보완, 행위자·시간, 누락 자동 승인 금지 | PENDING만 승인, 이상·중첩 차단, 수정 시 재승인. Trip API 동시 승인 테스트 |

## P2 — 상세 확인 후 범위 제외

| 기능 원문 | 읽은 내용 및 제외 경계 |
|---|---|
| [도착 위치 수집](https://app.notion.com/p/1eb46cb99d5a4a399ea8c4a78da422df) | 동의 기반 위치·목적 후보 보조. 현재 서버는 위치를 수집하지 않음 |
| [블루투스 운전자 추천](https://app.notion.com/p/73412d57245383dfbc6e81a4b3d41dc1) | 연결 기기 기반 후보이며 확정은 수동. 현 범위는 최근 운전자 후보만 제공 |
| [주유·충전 감지](https://app.notion.com/p/ca812d5724538389934e81435bf85fcd) | 잔량/상태 변화 기반 감지. 서버 주유·충전 이벤트 API는 추가하지 않음 |
| [주유·충전 비용 추정](https://app.notion.com/p/19312d5724538319ac72019931be6f53) | 감지량과 단가를 사용한 추정. 결제·실비 정산 기능으로 확장하지 않음 |

## 문서 불일치와 미검증 영역

- 일부 상세 본문의 우선순위 표기가 DB 속성과 다릅니다. 범위는 기능 DB 우선순위 속성과 대화에서 합의한 P0·P1을 기준으로 했습니다. 예: 주기 저장, 정차 판단, 도착 위치 수집.
- 일부 이전 Trip API 가정과 현재 SDK 검증 메모가 일치하지 않습니다. 서버가 해당 SDK를 직접 호출하거나 지원 여부를 가정하지 않고 실제 앱 수집값을 받습니다.
- 기능 2개(누적거리 유효성 검사, 운전자 선택 알림)는 상세 본문이 없어 표시된 속성 요구만 검토했습니다. 없는 설명을 추정하여 구현하지 않았습니다.
- 별도 기업 엑셀 템플릿·배포 환경은 미정입니다. 월간 출력은 저장소 양식이고 세무 서식은 검토용 초안입니다.
- SDK 차량 수집, 실제 종료 이벤트, 주행 중 UI, 알림, 오프라인 저장·정리, 웹 화면과 실차 통합 검증은 해당 클라이언트 작업입니다. 서버 단독 테스트 결과와 구분합니다.

테스트 소스: [AuthApiTest](../src/test/java/io/autolog/AuthApiTest.java), [FleetApiTest](../src/test/java/io/autolog/FleetApiTest.java), [TripApiTest](../src/test/java/io/autolog/TripApiTest.java), [ReportApiTest](../src/test/java/io/autolog/ReportApiTest.java), [ReviewRegressionTest](../src/test/java/io/autolog/ReviewRegressionTest.java), [MeasurementMigrationTest](../src/test/java/io/autolog/MeasurementMigrationTest.java). 실행 절차와 최신 검증 결과는 PR 본문에 기록합니다.
