import { create } from 'zustand';
import { authApi, type SessionUser } from '../api/auth';

interface SessionState {
  user: SessionUser | null;
  /** 首次进入时先问后端「我是谁」，问完之前不要闪一下登录页 */
  loading: boolean;
  setUser: (user: SessionUser | null) => void;
  refresh: () => Promise<void>;
  logout: () => Promise<void>;
}

export const useSession = create<SessionState>((set) => ({
  user: null,
  loading: true,

  setUser: (user) => set({ user }),

  refresh: async () => {
    try {
      const user = await authApi.me();
      set({ user, loading: false });
    } catch {
      // 401 是正常情况（还没登录），不是错误
      set({ user: null, loading: false });
    }
  },

  logout: async () => {
    try {
      await authApi.logout();
    } finally {
      // 即使请求失败也要清本地状态，不能把用户卡在「登不出去」
      set({ user: null });
    }
  },
}));
