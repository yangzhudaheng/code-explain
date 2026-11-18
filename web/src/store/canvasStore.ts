import { create } from 'zustand';
import type {
  Anchor,
  CanvasBg,
  CanvasDoc,
  CodeBlockModel,
  Question,
  Rect4,
  Side,
  Viewport,
} from '../model/types';
import {
  arrange,
  BOX_WIDTH,
  columnX,
  DEFAULT_BOX_H,
  emptyDoc,
  SIDEBAR_W,
  zoomAround,
} from '../canvas/layout';
import { buildAnchor } from '../canvas/anchors';
import { canvasApi, type CanvasDetail } from '../api/canvas';

export const SAMPLE_CODE = `class Solution {
    public int search(int[] nums, int target) {
        int lo = 0, hi = nums.length - 1;
        while (lo < hi) {
            int mid = lo + (hi - lo) / 2;
            if (nums[mid] < target) lo = mid + 1;
            else hi = mid;
        }
        return nums[lo] == target ? lo : -1;
    }
}`;

/** 保存状态：UI 只认这一个字段，不自己拼「有没有未保存改动」 */
export type SaveState = 'idle' | 'pending' | 'saving' | 'saved' | 'conflict' | 'error';

let seq = 1;
const uid = (prefix: string) =>
  `${prefix}_${Date.now().toString(36)}${(seq++).toString(36)}`;

/**
 * 代码被编辑后，按原文片段在新文本里找位置。
 * 同一段代码可能出现多次（例如两行 return -1;），所以取「离原偏移最近」的那一处，
 * 而不是简单 indexOf 的第一个，否则在顶部插几行就会把锚点挪到别的同名片段上。
 */
function nearestIndexOf(content: string, quote: string, hint: number): number {
  if (!quote) return -1;
  let best = -1;
  let idx = content.indexOf(quote);
  while (idx >= 0) {
    if (best < 0 || Math.abs(idx - hint) < Math.abs(best - hint)) best = idx;
    idx = content.indexOf(quote, idx + 1);
  }
  return best;
}

/** 服务端快照 → 内存模型。缺字段一律回落到默认值，坏数据不该让整页白屏。 */
export function docFromDetail(detail: CanvasDetail): CanvasDoc {
  const data = (detail.data ?? {}) as {
    blocks?: CodeBlockModel[];
    questions?: Question[];
    viewport?: Viewport;
    bg?: CanvasBg;
  };
  const base = emptyDoc(detail.id, detail.title);
  return {
    ...base,
    version: detail.version,
    blocks: Array.isArray(data.blocks) ? data.blocks : [],
    questions: Array.isArray(data.questions)
      ? data.questions.map((q) => ({
          ...q,
          // 行内矩形是按像素测出来的缓存，刷新后由 CodeBlock 重新测量；
          // 存进快照会让体积翻几倍，而且换了字体/缩放后反而是错的
          anchorRects: [],
          // 上次关页面时还在流式输出，这次不可能续上，别让 UI 永远转圈
          status: q.status === 'streaming' ? (q.answer ? 'done' : 'error') : q.status,
        }))
      : [],
    viewport: data.viewport ?? base.viewport,
    bg: data.bg ?? base.bg,
  };
}

export interface AddQuestionInput {
  blockId: string;
  anchor: Anchor;
  anchorPoint: { x: number; y: number };
  anchorRects: Rect4[];
  text: string;
  side?: Side;
  sideMode?: 'auto' | 'manual';
}

interface CanvasStore {
  docs: Record<string, CanvasDoc>;
  order: string[];
  activeId: string;
  hoverQid: string | null;

  saveState: SaveState;
  saveError: string | null;
  lastSavedAt: number | null;

  doc: () => CanvasDoc;

  /** 首次从服务端拉回全部画布后一次性灌入 */
  hydrate: (docs: CanvasDoc[], activeId?: string) => void;
  replaceDoc: (doc: CanvasDoc) => void;
  /** 只把版本号推上去，不覆盖用户在这期间的新改动 */
  patchVersion: (id: string, version: number) => void;
  setSaveState: (state: SaveState, error?: string | null) => void;

  setViewport: (v: Viewport) => void;
  panBy: (dx: number, dy: number) => void;
  zoomAt: (factor: number, sx: number, sy: number, rect: DOMRect) => void;
  resetView: () => void;

  setActive: (id: string) => void;
  addCanvas: () => Promise<void>;
  removeCanvas: (id: string) => Promise<void>;
  renameCanvas: (id: string, title: string) => void;
  setBg: (patch: Partial<CanvasBg>) => void;
  /** 回答框落到可视区之外时，最小幅度平移画布把它拉回来 */
  ensureVisible: (id: string, containerRect: DOMRect) => void;

  addBlock: (input: {
    content: string;
    language?: string;
    title?: string;
    x?: number;
    y?: number;
  }) => string;
  moveBlock: (id: string, dx: number, dy: number) => void;
  removeBlock: (id: string) => void;
  /** 修改代码块文本/标题/语言；文本变化后按原文片段重新定位锚点 */
  updateBlock: (id: string, patch: Partial<Pick<CodeBlockModel, 'content' | 'title' | 'language'>>) => void;
  /** 文本变化后重测锚点几何（行内矩形与连线起点），由 CodeBlock 在布局阶段调用 */
  refreshAnchors: (
    blockId: string,
    payload: Record<string, { rects: Rect4[]; point: { x: number; y: number } }>,
  ) => void;

  addQuestion: (input: AddQuestionInput) => string;
  moveBox: (id: string, dx: number, dy: number) => void;
  setBoxH: (id: string, h: number) => void;
  setSide: (id: string, side: Side) => void;
  togglePin: (id: string) => void;
  toggleCollapse: (id: string) => void;
  removeQuestion: (id: string) => void;
  appendAnswer: (id: string, chunk: string) => void;
  /** 整体替换回答内容（重新回答前先清空、或拿服务端补偿时用） */
  setAnswer: (id: string, answer: string) => void;
  setModel: (id: string, model: string) => void;
  setStatus: (id: string, status: Question['status']) => void;
  arrangeAll: () => void;

  setHover: (id: string | null) => void;
}

/** 新建画布时用的初始快照：一个示例代码块，让用户打开就有东西可点 */
export function starterDoc(title: string) {
  return {
    viewport: { x: 250, y: 70, zoom: 1 },
    bg: { pattern: 'dot', tone: 'warm' },
    blocks: [
      {
        id: 'b_sample',
        x: 80,
        y: 60,
        width: 520,
        title: 'Solution.java',
        language: 'java',
        content: SAMPLE_CODE,
      },
    ],
    questions: [],
  };
}

export const useCanvas = create<CanvasStore>((set, get) => ({
  // 首屏从服务端灌数据，这里不能放示例：否则未登录/加载中会先渲染出一份假画布
  docs: {},
  order: [],
  activeId: '',
  hoverQid: null,

  saveState: 'idle',
  saveError: null,
  lastSavedAt: null,

  doc: () => get().docs[get().activeId],

  hydrate: (list, activeId) => {
    const docs: Record<string, CanvasDoc> = {};
    for (const d of list) docs[d.id] = d;
    const first = activeId && docs[activeId] ? activeId : list[0]?.id ?? '';
    set({
      docs,
      order: list.map((d) => d.id),
      activeId: first,
      hoverQid: null,
      saveState: 'saved',
      lastSavedAt: Date.now(),
      saveError: null,
    });
  },

  replaceDoc: (doc) =>
    set((s) => ({
      docs: { ...s.docs, [doc.id]: doc },
      order: s.order.includes(doc.id) ? s.order : [doc.id, ...s.order],
    })),

  patchVersion: (id, version) =>
    set((s) => {
      const d = s.docs[id];
      if (!d || d.version === version) return {};
      return { docs: { ...s.docs, [id]: { ...d, version } } };
    }),

  setSaveState: (state, error = null) => set({ saveState: state, saveError: error }),

  setViewport: (v) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return { docs: { ...s.docs, [s.activeId]: { ...d, viewport: v } } };
    }),

  panBy: (dx, dy) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return {
        docs: {
          ...s.docs,
          [s.activeId]: {
            ...d,
            viewport: { ...d.viewport, x: d.viewport.x + dx, y: d.viewport.y + dy },
          },
        },
      };
    }),

  zoomAt: (factor, sx, sy, rect) =>
    set((s) => {
      const d = s.docs[s.activeId];
      const viewport = zoomAround(d.viewport, factor, sx, sy, rect);
      return { docs: { ...s.docs, [s.activeId]: { ...d, viewport } } };
    }),

  resetView: () =>
    set((s) => {
      const d = s.docs[s.activeId];
      return {
        docs: { ...s.docs, [s.activeId]: { ...d, viewport: { x: 250, y: 70, zoom: 1 } } },
      };
    }),

  setActive: (id) => set({ activeId: id, hoverQid: null }),

  addCanvas: async () => {
    const n = get().order.length + 1;
    // 先落库再进内存：本地先插一个临时 id 会让「保存时才发现没落库」的补丁到处漏
    const created = await canvasApi.create({
      title: `未命名画布 ${n}`,
      data: starterDoc(`未命名画布 ${n}`),
    });
    const doc = docFromDetail(created);
    set((s) => ({
      docs: { ...s.docs, [doc.id]: doc },
      order: [doc.id, ...s.order],
      activeId: doc.id,
      hoverQid: null,
      saveState: 'saved',
      lastSavedAt: Date.now(),
      saveError: null,
    }));
  },

  removeCanvas: async (id) => {
    await canvasApi.remove(id);
    const s = get();
    const order = s.order.filter((x) => x !== id);
    const docs = { ...s.docs };
    delete docs[id];

    if (order.length > 0) {
      set({
        docs,
        order,
        activeId: s.activeId === id ? order[0] : s.activeId,
        hoverQid: null,
      });
      return;
    }

    // 删的是最后一个：先把新画布建好再一次性替换。
    // 中间不能出现「docs 为空」的已提交状态，否则 Canvas 会拿到 undefined 直接崩。
    const title = '未命名画布 1';
    const created = await canvasApi.create({ title, data: starterDoc(title) });
    const doc = docFromDetail(created);
    set({
      docs: { [doc.id]: doc },
      order: [doc.id],
      activeId: doc.id,
      hoverQid: null,
      saveState: 'saved',
      saveError: null,
      lastSavedAt: Date.now(),
    });
  },

  renameCanvas: (id, title) =>
    set((s) => {
      const d = s.docs[id];
      const next = title.trim();
      if (!d || !next || next === d.title) return {};
      return { docs: { ...s.docs, [id]: { ...d, title: next } } };
    }),

  setBg: (patch) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return { docs: { ...s.docs, [s.activeId]: { ...d, bg: { ...d.bg, ...patch } } } };
    }),

  ensureVisible: (id, rect) =>
    set((s) => {
      const d = s.docs[s.activeId];
      const q = d.questions.find((x) => x.id === id);
      if (!q) return {};
      const z = d.viewport.zoom;
      const screenLeft = rect.left + d.viewport.x + q.boxX * z;
      const screenRight = screenLeft + BOX_WIDTH * z;
      const minLeft = rect.left + SIDEBAR_W + 8;
      const maxRight = rect.right - 8;
      // 比可视区还宽就无从补救，保持原样让用户自己平移
      if (screenRight - screenLeft > maxRight - minLeft) return {};
      let dx = 0;
      if (screenLeft < minLeft) dx = minLeft - screenLeft;
      else if (screenRight > maxRight) dx = maxRight - screenRight;
      if (Math.abs(dx) < 1) return {};
      return {
        docs: {
          ...s.docs,
          [s.activeId]: { ...d, viewport: { ...d.viewport, x: d.viewport.x + dx } },
        },
      };
    }),

  addBlock: (input) => {
    const id = uid('b');
    const { activeId, docs } = get();
    const d = docs[activeId];
    const maxY = d.blocks.reduce((m, b) => Math.max(m, b.y + 40), 0);
    const block: CodeBlockModel = {
      id,
      x: input.x ?? 80,
      y: input.y ?? maxY + 60,
      width: 520,
      title: input.title ?? 'snippet.txt',
      language: input.language ?? 'java',
      content: input.content,
    };
    const questions = arrange(d.questions, [...d.blocks, block]);
    set((s) => ({
      docs: {
        ...s.docs,
        [activeId]: { ...d, blocks: [...d.blocks, block], questions },
      },
    }));
    return id;
  },

  moveBlock: (id, dx, dy) =>
    set((s) => {
      const d = s.docs[s.activeId];
      const blocks = d.blocks.map((b) => (b.id === id ? { ...b, x: b.x + dx, y: b.y + dy } : b));
      const moved = d.questions.map((q) =>
        q.blockId === id
          ? { ...q, anchorPoint: { x: q.anchorPoint.x + dx, y: q.anchorPoint.y + dy } }
          : q,
      );
      return {
        docs: {
          ...s.docs,
          [s.activeId]: { ...d, blocks, questions: arrange(moved, blocks) },
        },
      };
    }),

  removeBlock: (id) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return {
        docs: {
          ...s.docs,
          [s.activeId]: {
            ...d,
            blocks: d.blocks.filter((b) => b.id !== id),
            questions: d.questions.filter((q) => q.blockId !== id),
          },
        },
      };
    }),

  updateBlock: (id, patch) =>
    set((s) => {
      const d = s.docs[s.activeId];
      const prev = d.blocks.find((b) => b.id === id);
      if (!prev) return {};

      const block: CodeBlockModel = { ...prev, ...patch };
      const contentChanged = patch.content !== undefined && patch.content !== prev.content;

      // 代码改了，锚点的字符偏移全部作废。用原文片段在新文本里重新定位：
      // 找得到就重算偏移（几何交给 refreshAnchors 重测），找不到就标记 stale，
      // 保留问答内容而不是静默删掉——用户的问题不该因为改代码而消失。
      const questions = contentChanged
        ? d.questions.map((q) => {
            if (q.blockId !== id) return q;
            const quote = q.anchor.quote;
            const idx = nearestIndexOf(block.content, quote, q.anchor.startOffset);
            if (idx < 0) return { ...q, stale: true, anchorRects: [] };
            return {
              ...q,
              stale: false,
              anchor: buildAnchor(block.content, idx, idx + quote.length),
              anchorRects: [],
            };
          })
        : d.questions;

      return {
        docs: {
          ...s.docs,
          [s.activeId]: {
            ...d,
            blocks: d.blocks.map((b) => (b.id === id ? block : b)),
            questions,
          },
        },
      };
    }),

  refreshAnchors: (blockId, payload) =>
    set((s) => {
      const d = s.docs[s.activeId];
      if (!d.blocks.some((b) => b.id === blockId)) return {};

      let changed = false;
      const questions = d.questions.map((q) => {
        const next = payload[q.id];
        if (!next || q.stale) return q;
        const sameRects =
          q.anchorRects.length === next.rects.length &&
          q.anchorRects.every(
            (r, i) =>
              Math.abs(r.left - next.rects[i].left) < 0.5 &&
              Math.abs(r.top - next.rects[i].top) < 0.5 &&
              Math.abs(r.width - next.rects[i].width) < 0.5 &&
              Math.abs(r.height - next.rects[i].height) < 0.5,
          );
        const samePoint =
          Math.abs(q.anchorPoint.x - next.point.x) < 0.5 &&
          Math.abs(q.anchorPoint.y - next.point.y) < 0.5;
        if (sameRects && samePoint) return q;
        changed = true;
        return { ...q, anchorRects: next.rects, anchorPoint: next.point };
      });

      if (!changed) return {};
      return {
        docs: { ...s.docs, [s.activeId]: { ...d, questions: arrange(questions, d.blocks) } },
      };
    }),

  addQuestion: (input) => {
    const id = uid('q');
    set((s) => {
      const d = s.docs[s.activeId];
      const block = d.blocks.find((b) => b.id === input.blockId);
      const side: Side = input.side ?? 'right';
      const q: Question = {
        id,
        blockId: input.blockId,
        anchor: input.anchor,
        anchorPoint: input.anchorPoint,
        anchorRects: input.anchorRects,
        text: input.text,
        answer: '',
        status: 'streaming',
        boxX: block ? columnX(block, side) : 0,
        boxY: input.anchorPoint.y - 24,
        boxH: DEFAULT_BOX_H,
        side,
        sideMode: input.sideMode ?? 'auto',
        pinned: false,
        collapsed: false,
        model: 'mock-model',
      };
      return {
        docs: {
          ...s.docs,
          [s.activeId]: { ...d, questions: arrange([...d.questions, q], d.blocks) },
        },
      };
    });
    return id;
  },

  moveBox: (id, dx, dy) =>
    set((s) => {
      const d = s.docs[s.activeId];
      const source = d.questions.find((q) => q.id === id);
      const block = source ? d.blocks.find((b) => b.id === source.blockId) : undefined;
      const moved = d.questions.map((q) => {
        if (q.id !== id) return q;
        const boxX = q.boxX + dx;
        // 拖过代码块中线就换边：连线起点跟着走到另一侧，避免箭头横穿代码
        const side: Side =
          block && boxX + BOX_WIDTH / 2 < block.x + block.width / 2 ? 'left' : 'right';
        return { ...q, boxX, boxY: q.boxY + dy, pinned: true, side, sideMode: 'manual' as const };
      });
      // 拖动后立刻重排其余未锁定的框，避免被拖动的框压住别人
      return {
        docs: { ...s.docs, [s.activeId]: { ...d, questions: arrange(moved, d.blocks) } },
      };
    }),

  setSide: (id, side) =>
    set((s) => {
      const d = s.docs[s.activeId];
      const target = d.questions.find((q) => q.id === id);
      const block = target ? d.blocks.find((b) => b.id === target.blockId) : undefined;
      if (!target || !block) return {};
      const questions = arrange(
        d.questions.map((q) =>
          q.id === id
            ? {
                ...q,
                side,
                sideMode: 'manual' as const,
                boxX: columnX(block, side),
                pinned: q.pinned,
              }
            : q,
        ),
        d.blocks,
      );
      return { docs: { ...s.docs, [s.activeId]: { ...d, questions } } };
    }),

  setBoxH: (id, h) => {
    const s = get();
    const d = s.docs[s.activeId];
    const target = d.questions.find((q) => q.id === id);
    if (!target || Math.abs(target.boxH - h) < 1.5) return;
    const questions = arrange(
      d.questions.map((q) => (q.id === id ? { ...q, boxH: h } : q)),
      d.blocks,
    );
    set({ docs: { ...s.docs, [s.activeId]: { ...d, questions } } });
  },

  togglePin: (id) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return {
        docs: {
          ...s.docs,
          [s.activeId]: {
            ...d,
            questions: d.questions.map((q) =>
              q.id === id ? { ...q, pinned: !q.pinned } : q,
            ),
          },
        },
      };
    }),

  toggleCollapse: (id) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return {
        docs: {
          ...s.docs,
          [s.activeId]: {
            ...d,
            questions: d.questions.map((q) =>
              q.id === id ? { ...q, collapsed: !q.collapsed } : q,
            ),
          },
        },
      };
    }),

  removeQuestion: (id) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return {
        docs: {
          ...s.docs,
          [s.activeId]: { ...d, questions: d.questions.filter((q) => q.id !== id) },
        },
      };
    }),

  appendAnswer: (id, chunk) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return {
        docs: {
          ...s.docs,
          [s.activeId]: {
            ...d,
            questions: d.questions.map((q) =>
              q.id === id ? { ...q, answer: q.answer + chunk } : q,
            ),
          },
        },
      };
    }),

  setAnswer: (id, answer) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return {
        docs: {
          ...s.docs,
          [s.activeId]: {
            ...d,
            questions: d.questions.map((q) => (q.id === id ? { ...q, answer } : q)),
          },
        },
      };
    }),

  setModel: (id, model) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return {
        docs: {
          ...s.docs,
          [s.activeId]: {
            ...d,
            questions: d.questions.map((q) => (q.id === id ? { ...q, model } : q)),
          },
        },
      };
    }),

  setStatus: (id, status) =>
    set((s) => {
      const d = s.docs[s.activeId];
      return {
        docs: {
          ...s.docs,
          [s.activeId]: {
            ...d,
            questions: d.questions.map((q) => (q.id === id ? { ...q, status } : q)),
          },
        },
      };
    }),

  arrangeAll: () =>
    set((s) => {
      const d = s.docs[s.activeId];
      const released = d.questions.map((q) => ({ ...q, pinned: false }));
      return {
        docs: { ...s.docs, [s.activeId]: { ...d, questions: arrange(released, d.blocks) } },
      };
    }),

  setHover: (id) => set({ hoverQid: id }),
}));
