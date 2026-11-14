#!/usr/bin/env python3
"""실행 중인 로컬 서버에 독립적인 가상 회사 데이터를 만들고 전체 API 흐름을 검증합니다."""
import argparse
import json
import re
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import uuid
import zipfile
import io

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', default='http://127.0.0.1:8080')
parser.add_argument('--output-dir', default='target/smoke')
args = parser.parse_args()
base = args.base_url.rstrip('/')


def api(method, path, body=None, token=None, binary=False):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    request = Request(base + path, data=json.dumps(body).encode() if body is not None else None,
                      headers=headers, method=method)
    try:
        with urlopen(request, timeout=30) as response:
            data = response.read()
            return data if binary else json.loads(data) if data else None
    except HTTPError as error:
        raise RuntimeError(f'{method} {path}: HTTP {error.code}: {error.read().decode()}') from None


assert api('GET', '/actuator/health')['status'] == 'UP'
suffix = uuid.uuid4().hex[:12]
session = api('POST', '/api/auth/companies', {
    'companyName': '로컬 검증 회사 ' + suffix, 'ownerName': '검증 대표',
    'email': f'smoke-{suffix}@example.test', 'password': 'Local-smoke-only-42!',
})
owner = session['accessToken']
staff = api('POST', '/api/admins', {'email': f'staff-{suffix}@example.test', 'name': '검증 직원'}, owner)
staff_session = api('POST', '/api/auth/password/setup', {
    'token': staff['invitation']['setupToken'], 'password': 'Local-smoke-only-42!',
})
admin = staff_session['accessToken']
vehicle = api('POST', '/api/vehicles', {'registrationNumber': '12가3456', 'model': '검증 차량', 'active': True}, admin)['id']
purpose = api('POST', '/api/catalog/purposes', {'name': '고객 미팅', 'active': True}, admin)['id']
pair = api('POST', f'/api/vehicles/{vehicle}/pairing', token=admin)['token']
assert isinstance(pair, str) and re.fullmatch(r'[0-9]{8}', pair)
device = api('POST', '/api/device/pair', {'pairingToken': pair})['accessToken']
profile = api('POST', '/api/device/drivers', {'name': '검증 운전자', 'department': '개발팀'}, device)
assert profile['status'] == 'ACTIVE'
driver = profile['id']
trip = str(uuid.uuid4())
payload = {'sourceRevision': 1, 'baseServerVersion': 0, 'driverId': driver,
           'startedAt': '2026-10-10T00:00:00Z', 'startOdometerKm': 10000, 'state': 'RUNNING'}
api('PUT', f'/api/device/trips/{trip}', payload, device)
assert api('PUT', f'/api/device/trips/{trip}', payload, device)['replayed'] is True
payload.update(sourceRevision=2, baseServerVersion=1, state='COMPLETED',
               endedAt='2026-10-10T01:00:00Z', endOdometerKm=10012.5)
api('PUT', f'/api/device/trips/{trip}', payload, device)
api('POST', f'/api/device/trips/{trip}/confirm', {
    'expectedVersion': 2, 'driverId': driver, 'classification': 'BUSINESS', 'purposeId': purpose,
}, device)
assert api('POST', f'/api/trips/{trip}/approve', {'expectedVersion': 3}, admin)['reportEligible'] is True
stats = api('GET', '/api/statistics?month=2026-10', token=admin)
assert stats['approvedKm'] == 12.5 and stats['approvedCount'] == 1
assert stats['vehicles'][0]['approvedDrivingDays'] == 1
assert stats['vehicles'][0]['currentMonthEnd']['status'] == 'LAST_KNOWN_REQUIRES_REVIEW'
assert stats['pendingApprovalKm'] == 0 and stats['unconfirmedKnownKm'] == 0
assert api('GET', '/api/trips?confirmed=true', token=admin)['total'] == 1
assert api('GET', f'/api/device/trips/{trip}', token=device)['durationSeconds'] == 3600
output = Path(args.output_dir)
output.mkdir(parents=True, exist_ok=True)
for name in ('monthly', 'nts-draft'):
    data = api('GET', f'/api/reports/{name}.xlsx?month=2026-10', token=admin, binary=True)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        assert 'xl/workbook.xml' in archive.namelist()
    (output / f'{name}.xlsx').write_bytes(data)
assert len(api('GET', f'/api/trips/{trip}/audit', token=admin)) == 4
assert api('GET', '/api/docs')['info']['title'] == 'AutoLog Server API'
for token in (owner, admin, device):
    api('POST', '/api/auth/logout', token=token)
print(f'SMOKE PASSED: signup → staff → pairing → device driver auto-approval → sync/retry → confirmation → approval → statistics → XLSX')
print(f'Company: {session["admin"]["companyId"]}; reports: {output.resolve()}')
