# 개발 규칙

- 커밋과 PR 제목은 `feat: 한국어 설명` 형식의 Conventional Commits를 사용합니다.
- 타입: feat, fix, refactor, chore, test, docs, ci, perf, build, style.
- 커밋은 독립적인 작업 단위로 나눕니다.
- PR 본문은 변경 목적과 동작, 실제 검증 결과, 필요한 API/DB 변경 사항을 한국어로 작성합니다.
- 회사 경계를 넘는 조회·수정과 차량 자격 증명의 관리자 API 접근을 허용하지 않습니다.
- 확정되지 않은 운행을 업무 거리 또는 승인된 보고서 데이터로 처리하지 않습니다.
- 테스트는 전용 PostgreSQL `autolog_test` 데이터베이스에서 실행합니다.
