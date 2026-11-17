import { useCanvas, docFromDetail, type SaveState } from './canvasStore';
import { canvasApi } from '../api/canvas';
import { HttpError } from '../api/client';
import { useSession } from './sessionStore';
import type { CanvasDoc } from '../model/types';

/** 改动停下来再存：拖代码块时每帧都会改 doc，逐帧发请求会把后端打满 */
const DEBOUNCE_MS = 700;

let timer: ReturnType<typeof setTimeout> | null = null;
let inFlight = false;
let queued = false;
let started = false;

/**
 * 每个画布「最后一次与服务端对齐」的 doc 引用。
 *
 * <p>用引用相等而不是深比较：zustand 的每次改动作都会生成新对象，
 * 因此「引用没变」就等于「一个字段都没动」，不用为了判断脏数据去序列化整个画布。
 */
const synced = new Map<string, CanvasDoc>();

/** 落库用的快照：剔除可以重算的像素缓存 */
function snapshot(doc: CanvasDoc) {
  return {
    viewport: doc.viewport,
    bg: doc.bg,
    blocks: doc.blocks,
    questions: doc.questions.map(({ anchorRects, ...rest }) => rest),
  };
}

function setState(state: SaveState, error: string | null = null) {
  useCanvas.setState({ saveState: state, saveError: error });
}

function schedule() {
  const st = useCanvas.getState();
  // 冲突没解决前不再自动写，否则等于用本地旧内容把别人的改动盖掉
  if (st.saveState === 'conflict') return;
  if (st.saveState !== 'pending') setState('pending');
  if (timer) clearTimeout(timer);
  timer = setTimeout(() => {
    timer = null;
    void flush();
  }, DEBOUNCE_MS);
}

async function flush(): Promise<void> {
  if (inFlight) {
    queued = true;
    return;
  }
  const st = useCanvas.getState();
  const doc = st.docs[st.activeId];
  if (!doc) return;
  if (st.saveState === 'conflict') return;

  if (synced.get(doc.id) === doc) {
    // 没有真改动（比如只是切了画布），别白跑一次请求
    if (st.saveState === 'pending') setState('saved');
    return;
  }

  inFlight = true;
  setState('saving');
  try {
    const saved = await canvasApi.save(doc.id, {
      title: doc.title,
      data: snapshot(doc),
      baseVersion: doc.version,
    });

    const cur = useCanvas.getState().docs[doc.id];
    if (cur === doc) {
      const next = { ...doc, version: saved.version, title: saved.title };
      synced.set(doc.id, next);
      useCanvas.getState().replaceDoc(next);
    } else {
      // 保存期间用户又改了：只把版本号推到最新，内容留给下一轮保存
      useCanvas.getState().patchVersion(doc.id, saved.version);
      synced.set(doc.id, doc);
    }
    useCanvas.setState({ saveState: 'saved', saveError: null, lastSavedAt: Date.now() });
  } catch (e) {
    if (e instanceof HttpError && e.code === 'CANVAS_VERSION_CONFLICT') {
      setState('conflict', '这个画布已在别处被修改');
    } else if (e instanceof HttpError && e.status === 401) {
      setState('error', '登录已过期，请重新登录');
      void useSession.getState().refresh();
    } else if (e instanceof HttpError && e.code === 'CANVAS_NOT_FOUND') {
      setState('error', '画布已被删除');
    } else {
      setState('error', e instanceof HttpError ? e.message : '网络异常，改动还没保存');
    }
  } finally {
    inFlight = false;
    if (queued) {
      queued = false;
      void flush();
    }
  }
}

/** 立即落盘（不等防抖），切画布、关页面前调用 */
export async function flushNow(): Promise<void> {
  if (timer) {
    clearTimeout(timer);
    timer = null;
  }
  await flush();
}

/** 切换画布前先把当前画布的改动存住：内存里的脏数据不会自己跟着切过去 */
export async function switchCanvas(id: string): Promise<void> {
  if (id === useCanvas.getState().activeId) return;
  await flushNow();
  useCanvas.getState().setActive(id);
}

/** 冲突时选「以服务器为准」：丢掉本地未保存的改动 */
export async function reloadFromServer(): Promise<void> {
  const st = useCanvas.getState();
  const doc = st.docs[st.activeId];
  if (!doc) return;
  const detail = await canvasApi.get(doc.id);
  const fresh = docFromDetail(detail);
  synced.set(fresh.id, fresh);
  useCanvas.getState().replaceDoc(fresh);
  useCanvas.setState({ saveState: 'saved', saveError: null, lastSavedAt: Date.now() });
}

/** 冲突时选「以我为准」：拿最新版本号再写一次，覆盖对方 */
export async function overwriteServer(): Promise<void> {
  const st = useCanvas.getState();
  const doc = st.docs[st.activeId];
  if (!doc) return;
  setState('saving');
  try {
    const latest = await canvasApi.get(doc.id);
    const saved = await canvasApi.save(doc.id, {
      title: doc.title,
      data: snapshot(doc),
      baseVersion: latest.version,
    });
    const next = { ...doc, version: saved.version, title: saved.title };
    synced.set(doc.id, next);
    useCanvas.getState().replaceDoc(next);
    useCanvas.setState({ saveState: 'saved', saveError: null, lastSavedAt: Date.now() });
  } catch (e) {
    setState('conflict', e instanceof HttpError ? e.message : '覆盖保存失败');
  }
}

/** 保存失败后手动重试 */
export async function retrySave(): Promise<void> {
  setState('pending');
  await flushNow();
}

function onBeforeUnload() {
  const st = useCanvas.getState();
  const doc = st.docs[st.activeId];
  if (!doc || synced.get(doc.id) === doc) return;
  // 页面正在卸载，普通请求会被浏览器取消；keepalive 让它至少能发出去
  void fetch(`/api/canvases/${doc.id}`, {
    method: 'PUT',
    keepalive: true,
    credentials: 'include',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      title: doc.title,
      data: snapshot(doc),
      baseVersion: doc.version,
    }),
  });
}

/**
 * 挂载自动保存。返回取消函数，登出时调用。
 *
 * <p>只在加载完成后调用：首屏 hydrate 会替换整个 docs，若此时已经在监听，
 * 会被当成「用户改了内容」立刻回写一遍。
 */
export function initPersistence(): () => void {
  if (started) return () => {};
  started = true;

  for (const doc of Object.values(useCanvas.getState().docs)) {
    synced.set(doc.id, doc);
  }

  const unsub = useCanvas.subscribe((s) => {
    const doc = s.docs[s.activeId];
    if (!doc) return;
    if (synced.get(doc.id) === doc) return;
    schedule();
  });

  const onVisibility = () => {
    // 手机切后台/锁屏时可能直接被回收，先存一次
    if (document.visibilityState === 'hidden') void flushNow();
  };
  document.addEventListener('visibilitychange', onVisibility);
  window.addEventListener('beforeunload', onBeforeUnload);

  return () => {
    unsub();
    document.removeEventListener('visibilitychange', onVisibility);
    window.removeEventListener('beforeunload', onBeforeUnload);
    if (timer) {
      clearTimeout(timer);
      timer = null;
    }
    started = false;
    synced.clear();
  };
}
