import { useEffect, useState } from 'react';
import { Canvas } from './canvas/Canvas';
import { loadWorkspace, resetWorkspace, seedSampleQuestions } from './canvas/workspace';
import { AuthPage } from './ui/AuthPage';
import { useSession } from './store/sessionStore';
import { initPersistence } from './store/persist';

type Phase = 'loading' | 'ready' | 'error';

/**
 * 登录后才挂载画布：未登录时不加载任何画布数据，避免上一个账号的内容
 * 在下一个账号登录前闪一下。
 */
function AuthedCanvas() {
  const [phase, setPhase] = useState<Phase>('loading');
  const [error, setError] = useState('');
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let alive = true;
    setPhase('loading');
    loadWorkspace()
      .then(() => {
        if (alive) setPhase('ready');
      })
      .catch((e: unknown) => {
        if (!alive) return;
        setError(e instanceof Error ? e.message : '画布加载失败');
        setPhase('error');
      });
    return () => {
      alive = false;
    };
  }, [attempt]);

  // 自动保存必须在数据灌进去之后才挂监听，否则首屏 hydrate 会被当成用户改动
  useEffect(() => {
    if (phase !== 'ready') return;
    const stop = initPersistence();
    seedSampleQuestions();
    return stop;
  }, [phase]);

  if (phase === 'loading') {
    return (
      <div className="app-splash">
        <span className="brand-mark">CE</span>
        <p className="splash-text">正在打开画布…</p>
      </div>
    );
  }

  if (phase === 'error') {
    return (
      <div className="app-splash">
        <span className="brand-mark">CE</span>
        <p className="splash-text">{error}</p>
        <button className="tb-primary" onClick={() => setAttempt((n) => n + 1)}>
          重试
        </button>
      </div>
    );
  }

  return <Canvas />;
}

export default function App() {
  const user = useSession((s) => s.user);
  const loading = useSession((s) => s.loading);
  const refresh = useSession((s) => s.refresh);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  // 退出登录时把画布与加载锁一起清掉：下一个登录的可能是别的账号
  useEffect(() => {
    if (!user) resetWorkspace();
  }, [user]);

  // 会话探测期间不闪登录页，否则每次刷新都会「先看到登录页再跳回画布」
  if (loading) {
    return (
      <div className="app-splash">
        <span className="brand-mark">CE</span>
      </div>
    );
  }

  return user ? <AuthedCanvas /> : <AuthPage />;
}
