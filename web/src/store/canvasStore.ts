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

let seq = 1;
const uid = (prefix: string) =>
  `${prefix}_${Date.now().toString(36)}${(seq++).toString(36)}`;

function makeSampleDoc(): CanvasDoc {
  const block: CodeBlockModel = {
    id: 'b_sample',
    x: 80,
    y: 60,
    width: 520,
    title: 'Solution.java',
    language: 'java',
    content: SAMPLE_CODE,
  };
  return {
    id: 'doc_main',
    title: '二分查找 · 边界问题',
    blocks: [block],
    questions: [],
    viewport: { x: 250, y: 70, zoom: 1 },
    bg: { pattern: 'dot', tone: 'warm' },
  };
}

const sampleDoc = makeSampleDoc();
const secondDoc: CanvasDoc = {
  ...emptyDoc('doc_second', '滑动窗口 · 草稿'),
  blocks: [
    {
      id: 'b_second',
      x: 80,
      y: 60,
      width: 520,
      title: 'Window.java',
      language: 'java',
      content: `int left = 0, max = 0;
for (int right = 0; right < n; right++) {
    // 收缩窗口
    while (left <= right && !valid()) left++;
    max = Math.max(max, right - left + 1);
}
return max;`,
    },
  ],
  viewport: { x: 250, y: 70, zoom: 1 },
};

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

  doc: () => CanvasDoc;

  setViewport: (v: Viewport) => void;
  panBy: (dx: number, dy: number) => void;
  zoomAt: (factor: number, sx: number, sy: number, rect: DOMRect) => void;
  resetView: () => void;

  setActive: (id: string) => void;
  addCanvas: () => void;
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

  addQuestion: (input: AddQuestionInput) => string;
  moveBox: (id: string, dx: number, dy: number) => void;
  setBoxH: (id: string, h: number) => void;
  setSide: (id: string, side: Side) => void;
  togglePin: (id: string) => void;
  toggleCollapse: (id: string) => void;
  removeQuestion: (id: string) => void;
  appendAnswer: (id: string, chunk: string) => void;
  setStatus: (id: string, status: Question['status']) => void;
  arrangeAll: () => void;

  setHover: (id: string | null) => void;
}

export const useCanvas = create<CanvasStore>((set, get) => ({
  docs: { [sampleDoc.id]: sampleDoc, [secondDoc.id]: secondDoc },
  order: [sampleDoc.id, secondDoc.id],
  activeId: sampleDoc.id,
  hoverQid: null,

  doc: () => get().docs[get().activeId],

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

  addCanvas: () => {
    const id = uid('doc');
    const n = get().order.length + 1;
    set((s) => ({
      docs: { ...s.docs, [id]: emptyDoc(id, `未命名画布 ${n}`) },
      order: [...s.order, id],
      activeId: id,
    }));
  },

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
