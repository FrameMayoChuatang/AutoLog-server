# 개발 규칙

- 커밋과 PR 제목은 `feat: 한국어 설명` 형식의 Conventional Commits를 사용합니다.
- 타입: feat, fix, refactor, chore, test, docs, ci, perf, build, style.
- 커밋은 독립적인 작업 단위로 나눕니다.
- PR 본문은 변경 목적과 동작, 실제 검증 결과, 필요한 API/DB 변경 사항을 한국어로 작성합니다.
- 회사 경계를 넘는 조회·수정과 차량 자격 증명의 관리자 API 접근을 허용하지 않습니다.
- 확정되지 않은 운행을 업무 거리 또는 승인된 보고서 데이터로 처리하지 않습니다.
- 테스트는 전용 PostgreSQL `autolog_test` 데이터베이스에서 실행합니다.

## 코드 구조

- Controller는 HTTP 입력/출력만 처리하고 Service를 호출합니다. 트랜잭션과 업무 규칙은 Service에, SQL과 RowMapper는 Repository에 둡니다.
- 요청은 `*Request`, 응답은 명시적인 record를 사용합니다. 저장 모델의 민감 필드를 HTTP나 감사 응답에 노출하지 않습니다.
- 업무 값에 `Map<String, Object>`와 문자열 키 캐스팅을 사용하지 않습니다. 기존 payload 해시 호환을 위한 `SyncPayloadHasher` 직렬화는 예외입니다.
- 거리·품질·공백·보고서 집계는 DB에 의존하지 않는 계산 코드로 유지합니다. XLSX 셀 작성과 집계 규칙을 섞지 않습니다.
- SQL은 매개변수 바인딩과 회사 조건을 사용합니다. 테이블 이름은 허용된 enum으로만 선택하며 긴 SQL은 text block으로 작성합니다.
- 구조를 변경할 때도 공개 JSON 필드, 원본 보호, 트랜잭션 경계, 차량 → 운행 잠금 순서, 재전송 해시 호환을 보존합니다.
