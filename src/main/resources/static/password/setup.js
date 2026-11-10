const token = new URLSearchParams(location.hash.slice(1)).get('token');
history.replaceState(null, '', location.pathname);
const form = document.querySelector('#setup');
const result = document.querySelector('#result');
if (!token) { form.hidden = true; result.textContent = '설정 토큰이 없습니다. 대표 관리자에게 새 링크를 요청해 주세요.'; }
form.addEventListener('submit', async event => {
  event.preventDefault();
  const password = document.querySelector('#password').value;
  if (password !== document.querySelector('#confirm').value) { result.textContent = '비밀번호가 일치하지 않습니다.'; return; }
  const button = form.querySelector('button'); button.disabled = true;
  try {
    const response = await fetch('/api/auth/password/setup', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ token, password }) });
    const body = await response.json();
    if (!response.ok) throw new Error(body.message || '설정에 실패했습니다.');
    // This page does not persist credentials. The user signs in through the admin client.
    await fetch('/api/auth/logout', { method: 'POST', headers: { Authorization: `Bearer ${body.accessToken}` } });
    form.reset(); form.hidden = true; result.textContent = '비밀번호를 설정했습니다. 관리자 화면에서 이메일과 비밀번호로 로그인해 주세요.';
  } catch (error) { result.textContent = error.message; button.disabled = false; }
});
