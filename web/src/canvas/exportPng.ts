import { createElement } from 'react';
import { createRoot } from 'react-dom/client';
import { toPng } from 'html-to-image';
import type { CanvasDoc } from '../model/types';
import { useCanvas } from '../store/canvasStore';
import { toneOf } from './background';
import { BOX_WIDTH, DEFAULT_BOX_H } from './layout';
import { ExportStage } from './ExportStage';
import { FlatContext } from './flat';

/** 内容四周留白（世界坐标像素）：给阴影和连线端点留呼吸空间 */
const PAD = 40;
/** 单边像素上限：超过浏览器 canvas 尺寸限制会得到一张空白图 */
const MAX_SIDE = 8000;

/**
 * 把当前画布导出成 PNG 并下载。
 *
 * <p>取的是「整张画布的内容范围」而不是「当前视口」：用户想分享的是完整图解，
 * 缩放位置是他自己的浏览状态，不该影响导出结果。
 *
 * <p>为什么要先量真实 DOM 的高度：代码块没有存高度字段（由内容撑开），
 * 回答框的高度是 ResizeObserver 回写进 store 的。若用估算值算边界，
 * 最后一块或最下一个回答框会被裁掉一截。
 */
export async function exportCanvasPng(): Promise<void> {
  const state = useCanvas.getState();
  const doc = state.docs[state.activeId];
  const png = await renderCanvasPng();
  triggerDownload(png.dataUrl, `${safeName(doc?.title ?? '画布')}-${stamp()}.png`);
}

export interface RenderedPng {
  dataUrl: string;
  width: number;
  height: number;
}

/**
 * 只负责「把画布栅格化成 PNG」，不碰下载。
 *
 * <p>拆出来是为了能在无头浏览器里验证产物：直接调它把结果画到页面上比对，
 * 而不是去解析一次下载行为。
 */
export async function renderCanvasPng(): Promise<RenderedPng> {
  const state = useCanvas.getState();
  const doc = state.docs[state.activeId];
  if (!doc) throw new Error('画布还没加载完成');
  if (doc.blocks.length === 0) throw new Error('画布上还没有代码块，先插入一段代码');

  // 鼠标可能正悬在某个回答框上，导出时不该带上高亮态
  state.setHover(null);

  const heights = measureBlockHeights();
  const bounds = contentBounds(doc, heights);
  const width = Math.ceil(bounds.maxX - bounds.minX) + PAD * 2;
  const height = Math.ceil(bounds.maxY - bounds.minY) + PAD * 2;
  const origin = { x: bounds.minX - PAD, y: bounds.minY - PAD };

  const host = document.createElement('div');
  host.className = 'export-host';
  document.body.appendChild(host);
  const root = createRoot(host);

  try {
    root.render(
      createElement(
        FlatContext.Provider,
        { value: { width, height } },
        createElement(ExportStage, { doc, origin, width, height }),
      ),
    );

    // 等两帧：一帧让 React 提交、一帧让浏览器完成布局与字体度量
    await nextFrames(2);
    if (document.fonts?.ready) await document.fonts.ready;

    const stage = host.querySelector('.export-stage') as HTMLElement | null;
    if (!stage) throw new Error('导出视图没有渲染出来');

    // 内容特别宽时降到 1x：宁可少一点清晰度，也不要一张空图
    const ratio = Math.max(1, Math.min(2, MAX_SIDE / Math.max(width, height)));

    const dataUrl = await toPng(stage, {
      width,
      height,
      pixelRatio: ratio,
      backgroundColor: toneOf(doc.bg.tone).base,
      // 我们是同源页面，不需要给资源加防缓存参数
      cacheBust: false,
      style: { margin: '0' },
    });

    return { dataUrl, width: Math.round(width * ratio), height: Math.round(height * ratio) };
  } finally {
    // 无论成功失败都要收干净，否则 .export-host 会一直留在 body 里占内存
    root.unmount();
    host.remove();
  }
}

/** id → 屏幕上该代码块的实际高度（世界坐标，transform 缩放不影响 offsetHeight） */
function measureBlockHeights(): Map<string, number> {
  const map = new Map<string, number>();
  document.querySelectorAll<HTMLElement>('[data-block-id]').forEach((el) => {
    const id = el.dataset.blockId;
    if (id) map.set(id, el.offsetHeight);
  });
  return map;
}

function contentBounds(doc: CanvasDoc, heights: Map<string, number>) {
  let minX = Number.POSITIVE_INFINITY;
  let minY = Number.POSITIVE_INFINITY;
  let maxX = Number.NEGATIVE_INFINITY;
  let maxY = Number.NEGATIVE_INFINITY;

  for (const b of doc.blocks) {
    // 量不到（比如刚从别的画布切过来还没挂载）时按 6 行代码估
    const h = heights.get(b.id) ?? 150;
    minX = Math.min(minX, b.x);
    minY = Math.min(minY, b.y);
    maxX = Math.max(maxX, b.x + b.width);
    maxY = Math.max(maxY, b.y + h);
  }

  for (const q of doc.questions) {
    const h = q.boxH || DEFAULT_BOX_H;
    minX = Math.min(minX, q.boxX);
    minY = Math.min(minY, q.boxY);
    maxX = Math.max(maxX, q.boxX + BOX_WIDTH);
    maxY = Math.max(maxY, q.boxY + h);
  }

  if (!Number.isFinite(minX)) return { minX: 0, minY: 0, maxX: PAD * 2, maxY: PAD * 2 };
  return { minX, minY, maxX, maxY };
}

function nextFrames(n: number): Promise<void> {
  return new Promise((resolve) => {
    const step = (left: number) => {
      if (left <= 0) {
        resolve();
        return;
      }
      requestAnimationFrame(() => step(left - 1));
    };
    step(n);
  });
}

function triggerDownload(dataUrl: string, filename: string) {
  const a = document.createElement('a');
  a.href = dataUrl;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
}

/** 画布标题可能带 / \ : 之类在文件名里非法的字符 */
function safeName(title: string) {
  const cleaned = (title || '画布').replace(/[\\/:*?"<>|]+/g, '_').trim();
  return cleaned.slice(0, 40) || '画布';
}

function stamp() {
  const d = new Date();
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}`;
}
