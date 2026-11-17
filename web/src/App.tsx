import { useEffect } from 'react';
import { Canvas } from './canvas/Canvas';
import { useBootstrapSamples } from './canvas/bootstrap';
import { AuthPage } from './ui/AuthPage';
import { useSession } from './store/sessionStore';

/** 登录后才挂载画布：未登录时连示例数据都不初始化，避免泄漏到未认证的会话里。 */
function AuthedCanvas() {
  useBootstrapSamples();
  return <Canvas />;
}

export default function App() {
  const user = useSession((s) => s.user);
  const loading = useSession((s) => s.loading);
  const refresh = useSession((s) => s.refresh);

  useEffect(() => {
    void refresh();
  }, [refresh]);

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
