import { useSyncExternalStore } from 'react';

/**
 * 「正在交互」的全局标记：指针拖动、画布平移、滚轮缩放期间为 true。
 *
 * <p>用途只有一个 —— 交互期间把阴影、过渡这类纯装饰的绘制关掉（见 styles.css 的
 * `.world.is-busy`）。节点多的时候，200 个 box-shadow 在缩放重栅格化时是实打实的开销，
 * 而用户此刻并不需要它们。
 *
 * <p>用计数而不是布尔：拖回答框的过程中可能嵌套触发别的交互（例如 ensureVisible），
 * 布尔会被内层提前清零。
 */
let depth = 0;
let pulsing = false;
let tailTimer: number | null = null;
const listeners = new Set<() => void>();

function emit() {
  for (const fn of listeners) fn();
}

function subscribe(fn: () => void) {
  listeners.add(fn);
  return () => {
    listeners.delete(fn);
  };
}

/** 结束那波「没有结束事件」的滚轮交互（如果正处在这一波里） */
function stopPulse() {
  if (tailTimer !== null) {
    window.clearTimeout(tailTimer);
    tailTimer = null;
  }
  if (!pulsing) return;
  pulsing = false;
  depth = Math.max(0, depth - 1);
}

export function beginInteraction() {
  stopPulse();
  depth += 1;
  emit();
}

export function endInteraction() {
  depth = Math.max(0, depth - 1);
  emit();
}

/**
 * 滚轮缩放/平移是一串离散事件，没有明确的结束点：
 * 每次 wheel 调一次，停止 ms 毫秒后自动退出交互态。
 * 整波只占一层计数，所以连续滚 50 次也不会把 depth 堆到 50。
 */
export function pulseInteraction(ms = 180) {
  if (!pulsing) {
    pulsing = true;
    depth += 1;
    emit();
  }
  if (tailTimer !== null) window.clearTimeout(tailTimer);
  tailTimer = window.setTimeout(() => {
    tailTimer = null;
    if (!pulsing) return;
    pulsing = false;
    depth = Math.max(0, depth - 1);
    emit();
  }, ms);
}

export function useInteracting(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => depth > 0,
    () => false,
  );
}

/**
 * 把高频 pointermove 合并成每帧一次。
 *
 * <p>鼠标/触控板的 pointermove 能到 120~240Hz，而一帧只画一次。
 * 不合并的话，一帧之内会触发两到四次 store 更新和重渲染，白烧 CPU 还更容易掉帧。
 * 位移先累加，rAF 回调里一次性应用；松手时手动 flush，避免丢掉最后几个像素。
 */
export function createFrameCoalescer(apply: (dx: number, dy: number) => void) {
  let dx = 0;
  let dy = 0;
  let raf = 0;

  const run = () => {
    raf = 0;
    if (dx === 0 && dy === 0) return;
    const x = dx;
    const y = dy;
    dx = 0;
    dy = 0;
    apply(x, y);
  };

  return {
    add(x: number, y: number) {
      dx += x;
      dy += y;
      if (!raf) raf = requestAnimationFrame(run);
    },
    flush() {
      if (raf) {
        cancelAnimationFrame(raf);
        raf = 0;
      }
      run();
    },
  };
}
