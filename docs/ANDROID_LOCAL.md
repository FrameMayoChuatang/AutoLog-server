# Android 차량 앱의 로컬 서버 연결

이 저장소에는 서버와 앱에 적용할 설정 예시가 있습니다. 아래 Android 파일은 앱 저장소에 반영해야 하며, 서버를 실행하는 것만으로 앱의 네트워크 설정이 바뀌지는 않습니다.

## 서버 실행

```sh
docker compose up -d --wait
./mvnw spring-boot:run -Dspring-boot.run.profiles=emulator
```

`emulator` 프로필은 `0.0.0.0:8080`에 바인딩합니다. 동일한 설정을 환경변수로 실행할 수도 있습니다.

```sh
SERVER_ADDRESS=0.0.0.0 ./mvnw spring-boot:run
# 빌드한 JAR 사용 시
java -jar target/autolog-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=emulator
```

일반 실행의 기본 바인딩은 `127.0.0.1`을 유지합니다. 명시한 `SERVER_ADDRESS`, `SERVER_PORT`는 프로필보다 우선합니다. `0.0.0.0`은 다른 네트워크 인터페이스에도 열리는 개발용 바인딩이므로 신뢰하는 로컬 환경에서 사용합니다. 연결할 때 쓰는 호스트 주소는 아닙니다.

| 접속하는 곳 | 주소 |
|---|---|
| 서버를 실행한 PC | `http://127.0.0.1:8080` |
| Android Emulator 내부 | `http://10.0.2.2:8080` |
| 다른 PC·실기기 | 서버 PC의 LAN IP와 포트. 방화벽 허용 및 해당 주소의 앱 HTTP 설정 필요 |

Android Emulator의 `10.0.2.2`는 개발 PC의 루프백으로 연결되는 별칭이므로 일반적으로 `127.0.0.1` 바인딩으로도 접근할 수 있습니다. 에뮬레이터에서 `localhost` 또는 `127.0.0.1`은 에뮬레이터 자신입니다. [Android 공식 주소 설명](https://developer.android.com/studio/run/emulator-networking-address)을 참고하세요.

## 앱의 개발용 설정

앱 HTTP 클라이언트의 개발용 기본 URL을 `http://10.0.2.2:8080/`으로 지정합니다. 예를 들어 Retrofit은 마지막 `/`까지 포함합니다. 포트를 바꿨다면 URL도 같은 포트로 맞춥니다.

다음 예시를 **앱 모듈의 `src/debug` 아래**에 반영하고 debug 빌드로 실행합니다.

| 이 저장소의 예시 | 앱 저장소의 적용 위치 |
|---|---|
| [AndroidManifest.xml](../dev/android/debug/AndroidManifest.xml) | `app/src/debug/AndroidManifest.xml` |
| [autolog_network_security_config.xml](../dev/android/debug/res/xml/autolog_network_security_config.xml) | `app/src/debug/res/xml/autolog_network_security_config.xml` |

예시는 인터넷 권한과 `10.0.2.2`에 한정한 HTTP 허용을 설정합니다. 기존 debug Manifest가 있으면 `<uses-permission>`과 `<application>` 속성을 병합합니다. 기존 network security config를 사용하는 앱은 기존 인증서·도메인 규칙을 보존하면서 개발용 `10.0.2.2` 규칙을 추가합니다. 예시 파일을 `src/main`이나 `src/release`에 복사하지 않습니다.

Android 9 이상을 대상으로 하는 앱은 HTTP를 기본적으로 허용하지 않습니다. [Android Network Security Configuration](https://developer.android.com/privacy-and-security/security-config)의 도메인별 설정을 사용합니다. 단순히 서버 CORS를 바꿔서는 이 문제가 해결되지 않습니다. 네이티브 HTTP 클라이언트는 브라우저 CORS 허용 목록을 추가할 필요가 없습니다.

## 연결 확인과 페어링

1. PC에서 `curl http://127.0.0.1:8080/actuator/health`가 `{"status":"UP"}`인지 확인합니다.
2. 에뮬레이터 앱에서 `GET http://10.0.2.2:8080/actuator/health`를 호출해 확인합니다. 브라우저에서만 성공하면 앱의 debug 네트워크 설정과 인터넷 권한도 확인합니다.
3. PC의 `http://127.0.0.1:8080/docs`에서 [관리자 웹 없이 차량 연결](../README.md#관리자-웹-없이-차량-연결) 절차로 8자리 코드를 발급합니다.
4. 앱은 `POST http://10.0.2.2:8080/api/device/pair`에 `{"pairingToken":"00123456"}`을 전송합니다. 숫자 문자열의 앞자리 `0`을 유지하고 Bearer 헤더는 붙이지 않습니다.
5. 응답의 차량 `accessToken`으로 `/api/device/**`를 호출합니다. 운행 업로드에는 [위치 제외 계약](API.md#위치정보와-앱-로컬-데이터)을 적용합니다.

`CLEARTEXT ... not permitted`는 앱의 HTTP 설정, `Connection refused`는 서버 실행·주소·포트, 타임아웃은 호스트 방화벽·네트워크 경로를 먼저 확인합니다. `401`은 토큰/페어링 코드 만료·폐기, `429`는 페어링 시도 제한이므로 응답의 `Retry-After`(초)만큼 기다립니다.

8080 포트가 다른 앱에서 사용 중이면 해당 앱을 종료하지 않고 다음처럼 별도 포트로 실행합니다.

```sh
SERVER_PORT=18080 ./mvnw spring-boot:run -Dspring-boot.run.profiles=emulator
# 앱 기본 URL: http://10.0.2.2:18080/
```

서버만으로 수행하는 HTTP 시나리오는 `python3 dev/smoke.py --base-url http://127.0.0.1:18080`입니다. 이것은 앱 빌드·Manifest 병합·에뮬레이터 실행 검증을 대신하지 않습니다.
