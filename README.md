# AutoLog Server

법인차량 운행기록 관리 서버입니다. Docs의 P0·P1 서버 범위와 합의한 계정·승인 정책을 Spring Boot + Java로 구현했습니다.

차량 앱이 Pleos SDK에서 측정한 값을 REST API로 보냅니다. 서버는 기업/관리자/차량 연결, 운전자와 목록, 운행기록 동기화, 확인·승인·수정 이력, 통계, 월간 XLSX와 국세청 서식 초안을 처리합니다.

## 로컬 실행

Java 21과 실행 중인 Docker가 필요합니다. Maven은 Wrapper가 내려받습니다.

```sh
docker compose up -d --wait
./mvnw spring-boot:run
```

- 서버: http://127.0.0.1:8080
- Swagger UI: http://127.0.0.1:8080/docs
- OpenAPI JSON / YAML: http://127.0.0.1:8080/api/docs / http://127.0.0.1:8080/api/docs.yaml
- 상태: http://127.0.0.1:8080/actuator/health
- PostgreSQL: `localhost:55432`, 개발 DB `autolog`, 테스트 DB `autolog_test`
- 로컬 DB 사용자/비밀번호: `autolog` / `autolog`

기업 가입 API로 대표 계정을 만든 뒤 반환된 `accessToken`을 Swagger의 **Authorize**에 입력합니다. 관리자 API에는 관리자 토큰, `/api/device/**`에는 차량 연결 후 받은 차량 토큰을 사용합니다. 가입·로그인·비밀번호 설정·최초 차량 연결은 공개 API입니다.

기본 설정으로 바로 실행할 수 있습니다. `.env.example`은 변경할 환경변수의 예시이며 Spring Boot는 `.env` 파일을 자동으로 읽지 않습니다. 쉘에서 `export`하거나 IDE 실행 환경에 지정합니다. Docker의 `DB_PORT`를 바꾸면 `DB_URL`, `TEST_DB_URL`도 맞춰야 합니다.

## 검증

```sh
./mvnw clean verify
# 실행 중인 서버에 가상 회사 데이터를 생성하는 실제 HTTP 시나리오
python3 dev/smoke.py
```

`verify`는 실제 PostgreSQL 마이그레이션·통합 테스트와 Java 포맷 검사를 수행하고 실행 가능한 JAR를 만듭니다. 테스트는 이름이 `_test`로 끝나는 전용 DB에서만 실행되며 각 테스트가 그 DB의 업무 데이터를 초기화합니다. H2나 모의 DB로 대체하지 않습니다.

`smoke.py`는 가입 → 직원 초대 → 차량 연결 → 진행 중 기록 → 중복 재전송 → 완료 → 운전자 확인 → 직원 관리자 승인 → 통계 → XLSX 다운로드를 검증합니다. 생성한 가상 회사 데이터는 개발 DB에 남고, 보고서는 Git에서 제외된 `target/smoke/`에 저장됩니다.

```sh
java -jar target/autolog-server-0.1.0-SNAPSHOT.jar
./mvnw spotless:apply    # Java 포맷 적용
docker compose stop    # 데이터 볼륨을 보존한 채 DB 중지
```

## 문서

- [API와 차량 동기화 계약](docs/API.md)
- [확정 범위와 Docs 대응](docs/SCOPE.md)
- [구조·데이터·상태 전이](docs/ARCHITECTURE.md)
- [통계와 엑셀 출력 기준](docs/REPORTS.md)
- [개발·커밋·PR 규칙](CONTRIBUTING.md)

## 적용 범위

배포 환경 구성은 포함하지 않습니다. 기본 바인딩은 `127.0.0.1`입니다. 에뮬레이터나 다른 기기에서 연결할 때만 `SERVER_ADDRESS`와 CORS를 환경에 맞춰 설정합니다.

실차 SDK 수집, Android 서비스/알림, 주행 중 UI 제한, 로컬 저장·재전송 큐는 차량 앱의 책임입니다. 이 저장소는 차량 데이터 수신부터 검증했습니다. 실제 차량·에뮬레이터 앱 연결과 관리자 웹 화면은 이 서버 검증에 포함되지 않습니다. 위치·경로 수집, 실제 비용 정산, 알림 자동 발송 등 P2 기능은 포함하지 않습니다.

