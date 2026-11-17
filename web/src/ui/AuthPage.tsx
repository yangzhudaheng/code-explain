import { useCallback, useEffect, useState } from 'react';
import { authApi, HttpError, type Captcha } from '../api/auth';
import { useSession } from '../store/sessionStore';

type Mode = 'login' | 'register';

export function AuthPage() {
  const setUser = useSession((s) => s.setUser);

  const [mode, setMode] = useState<Mode>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [code, setCode] = useState('');
  const [captchaCode, setCaptchaCode] = useState('');
  const [captcha, setCaptcha] = useState<Captcha | null>(null);
  const [cooldown, setCooldown] = useState(0);
  const [busy, setBusy] = useState<'send' | 'submit' | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [errorField, setErrorField] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const loadCaptcha = useCallback(async () => {
    try {
      setCaptcha(await authApi.captcha());
    } catch {
      setCaptcha(null);
      setError('图形验证码加载失败，请检查后端是否已启动');
    }
  }, []);

  useEffect(() => {
    if (mode === 'register') {
      void loadCaptcha();
    }
  }, [mode, loadCaptcha]);

  // 每 1 秒减一，用 setTimeout 而不是 setInterval：依赖 cooldown 就不会出现多个计时器叠加
  useEffect(() => {
    if (cooldown <= 0) return;
    const timer = setTimeout(() => setCooldown(cooldown - 1), 1000);
    return () => clearTimeout(timer);
  }, [cooldown]);

  const fail = (e: unknown) => {
    if (e instanceof HttpError) {
      setError(e.message);
      setErrorField(e.field ?? null);
    } else {
      setError('网络异常，请稍后重试');
      setErrorField(null);
    }
  };

  const switchMode = (next: Mode) => {
    setMode(next);
    setError(null);
    setErrorField(null);
    setNotice(null);
    setCode('');
    setCaptchaCode('');
    setCooldown(0);
  };

  const sendCode = async () => {
    setError(null);
    setNotice(null);
    if (!captcha) {
      setError('请先刷新图形验证码');
      return;
    }
    if (!/^\S+@\S+\.\S+$/.test(email)) {
      setError('请先填写正确的邮箱');
      setErrorField('email');
      return;
    }
    setBusy('send');
    try {
      const res = await authApi.sendEmailCode(email.trim(), captcha.id, captchaCode.trim());
      setCooldown(res.cooldownSeconds);
      setNotice(
        res.devMode
          ? '开发模式：未配置 SMTP，验证码已打印在后端日志里'
          : '验证码已发送，请查收邮件（没收到就看下垃圾箱）',
      );
      setCaptchaCode('');
      await loadCaptcha();
    } catch (e) {
      fail(e);
      // 图形码是一次性的，失败后必须换一张，否则用户再怎么点都是错的
      setCaptchaCode('');
      await loadCaptcha();
    } finally {
      setBusy(null);
    }
  };

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);
    setErrorField(null);
    setBusy('submit');
    try {
      const user =
        mode === 'login'
          ? await authApi.login(email.trim(), password)
          : await authApi.register({
              email: email.trim(),
              code: code.trim(),
              password,
              displayName: displayName.trim() || undefined,
            });
      setUser(user);
    } catch (e) {
      fail(e);
      if (mode === 'register') {
        // 注册失败多半是验证码问题：清掉图形码，让用户换一张重来
        setCaptchaCode('');
      }
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="auth-root">
      <div className="auth-card">
        <div className="auth-brand">
          <span className="brand-mark">CE</span>
          <div>
            <div className="brand-name">Code Explain</div>
            <div className="brand-sub">把代码贴上来，选中一段就能问</div>
          </div>
        </div>

        <div className="auth-tabs">
          <button
            type="button"
            className={mode === 'login' ? 'on' : ''}
            onClick={() => switchMode('login')}
          >
            登录
          </button>
          <button
            type="button"
            className={mode === 'register' ? 'on' : ''}
            onClick={() => switchMode('register')}
          >
            注册
          </button>
        </div>

        <form className="auth-form" onSubmit={submit}>
          <label className={`auth-field ${errorField === 'email' ? 'err' : ''}`}>
            <span>邮箱</span>
            <input
              type="email"
              autoComplete="email"
              placeholder="you@example.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </label>

          {mode === 'register' && (
            <>
              <label className={`auth-field ${errorField === 'captchaCode' ? 'err' : ''}`}>
                <span>图形验证码</span>
                <div className="auth-inline">
                  <input
                    type="text"
                    inputMode="text"
                    maxLength={6}
                    placeholder="输入图中 4 位字符"
                    value={captchaCode}
                    onChange={(e) => setCaptchaCode(e.target.value.toUpperCase())}
                  />
                  <button
                    type="button"
                    className="captcha-img"
                    onClick={() => void loadCaptcha()}
                    title="看不清？点击换一张"
                  >
                    {captcha ? <img src={captcha.image} alt="图形验证码" /> : <span>加载中</span>}
                  </button>
                </div>
              </label>

              <label className={`auth-field ${errorField === 'code' ? 'err' : ''}`}>
                <span>邮箱验证码</span>
                <div className="auth-inline">
                  <input
                    type="text"
                    inputMode="numeric"
                    maxLength={6}
                    placeholder="6 位数字"
                    value={code}
                    onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
                    required
                  />
                  <button
                    type="button"
                    className="auth-code-btn"
                    onClick={() => void sendCode()}
                    disabled={cooldown > 0 || busy === 'send'}
                  >
                    {busy === 'send' ? '发送中…' : cooldown > 0 ? `${cooldown}s 后重发` : '获取验证码'}
                  </button>
                </div>
              </label>
            </>
          )}

          <label className={`auth-field ${errorField === 'password' ? 'err' : ''}`}>
            <span>密码</span>
            <input
              type="password"
              autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
              placeholder={mode === 'register' ? '至少 8 位，含字母和数字' : '输入密码'}
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
          </label>

          {mode === 'register' && (
            <label className="auth-field">
              <span>昵称（可选）</span>
              <input
                type="text"
                maxLength={40}
                placeholder="画布上怎么称呼你"
                value={displayName}
                onChange={(e) => setDisplayName(e.target.value)}
              />
            </label>
          )}

          {error && <div className="auth-msg auth-msg-err">{error}</div>}
          {notice && !error && <div className="auth-msg auth-msg-ok">{notice}</div>}

          <button type="submit" className="auth-submit" disabled={busy === 'submit'}>
            {busy === 'submit' ? '处理中…' : mode === 'login' ? '登录' : '注册并进入'}
          </button>
        </form>

        <div className="auth-foot">
          {mode === 'login' ? (
            <span>
              还没有账号？
              <button type="button" onClick={() => switchMode('register')}>
                去注册
              </button>
            </span>
          ) : (
            <span>
              已有账号？
              <button type="button" onClick={() => switchMode('login')}>
                去登录
              </button>
            </span>
          )}
        </div>
      </div>
    </div>
  );
}
