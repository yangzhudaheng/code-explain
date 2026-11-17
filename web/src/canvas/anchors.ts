import type { Anchor, CodeBlockModel, Rect4 } from '../model/types';

/**
 * 把 DOM Selection 的端点换算成「相对代码块纯文本」的字符偏移。
 * 用 Range.toString() 累计，因此 <pre> 内含多少个 span 都不影响结果——
 * 语法高亮只是把文本切碎包裹，字符总数与顺序不变。
 */
export function rangeToOffsets(
  pre: HTMLElement,
  range: Range,
): { start: number; end: number } {
  const startRange = document.createRange();
  startRange.selectNodeContents(pre);
  startRange.setEnd(range.startContainer, range.startOffset);

  const endRange = document.createRange();
  endRange.selectNodeContents(pre);
  endRange.setEnd(range.endContainer, range.endOffset);

  return {
    start: startRange.toString().length,
    end: endRange.toString().length,
  };
}

export function offsetToLineChar(content: string, offset: number) {
  const clamped = Math.max(0, Math.min(offset, content.length));
  let line = 0;
  let lastNl = -1;
  for (let i = 0; i < clamped; i++) {
    if (content.charCodeAt(i) === 10) {
      line++;
      lastNl = i;
    }
  }
  return { line, char: clamped - lastNl - 1 };
}

export function lineCharToOffset(content: string, line: number, char: number) {
  let offset = 0;
  let cur = 0;
  while (cur < line && offset < content.length) {
    const nl = content.indexOf('\n', offset);
    if (nl === -1) return content.length;
    offset = nl + 1;
    cur++;
  }
  return Math.min(offset + char, content.length);
}

/**
 * 在可能被高亮 span 切碎的元素里，找到第 offset 个字符所在的文本节点与节点内偏移。
 * 这是「有语法高亮之后仍然能按字符偏移还原选区」的关键：
 * 不能再用 pre.firstChild 这种单文本节点假设。
 */
function locateText(pre: HTMLElement, offset: number): { node: Text; offset: number } | null {
  const walker = document.createTreeWalker(pre, NodeFilter.SHOW_TEXT);
  let acc = 0;
  let last: Text | null = null;
  let raw = walker.nextNode();
  while (raw) {
    const node = raw as Text;
    const len = node.data.length;
    if (offset <= acc + len) return { node, offset: Math.max(0, offset - acc) };
    acc += len;
    last = node;
    raw = walker.nextNode();
  }
  // 偏移超出末尾（例如选中了最后一行行尾的换行）时贴着最后一个文本节点收尾
  return last ? { node: last, offset: last.data.length } : null;
}

/**
 * Range.getClientRects() 会按「行片段」返回矩形：一段选中文字被高亮 span 切成多个片段时，
 * 同一行会冒出好几个互相重叠的矩形。直接渲染会让半透明底色叠深、出现斑块，
 * 所以同一行（top 相同）的片段必须合并成一个。跨行仍然分开，保留逐行高亮效果。
 */
function mergeLineRects(rects: Rect4[]): Rect4[] {
  const sorted = [...rects].sort((a, b) => a.top - b.top || a.left - b.left);
  const out: Rect4[] = [];
  for (const r of sorted) {
    if (r.width <= 0.5) continue;
    const last = out[out.length - 1];
    const sameLine = last && Math.abs(r.top - last.top) < 1.5 && Math.abs(r.height - last.height) < 2;
    if (sameLine) {
      const right = Math.max(last.left + last.width, r.left + r.width);
      last.left = Math.min(last.left, r.left);
      last.width = right - last.left;
    } else {
      out.push({ ...r });
    }
  }
  return out;
}

/**
 * 由字符偏移测量出两点：
 * 1) rects —— 相对正文容器的矩形，用来画持久高亮
 * 2) point —— 画布世界坐标里的锚点（右边缘中点），连线从这里出发
 */
export function measureAnchor(input: {
  pre: HTMLElement;
  blockEl: HTMLElement;
  bodyEl: HTMLElement;
  block: CodeBlockModel;
  zoom: number;
  startOffset: number;
  endOffset: number;
}): { rects: Rect4[]; point: { x: number; y: number } } | null {
  const { pre, blockEl, bodyEl, block, zoom, startOffset, endOffset } = input;

  const start = locateText(pre, startOffset);
  const end = locateText(pre, endOffset);
  if (!start || !end) return null;

  const range = document.createRange();
  range.setStart(start.node, start.offset);
  range.setEnd(end.node, end.offset);

  const clientRects = Array.from(range.getClientRects()).filter(
    (r) => r.width > 0.5 || r.height > 0.5,
  );

  const bodyRect = bodyEl.getBoundingClientRect();
  const blockRect = blockEl.getBoundingClientRect();

  if (clientRects.length === 0) {
    // 选区落在行尾换行符上，退化到块右侧中点，保证连线可用
    return {
      rects: [],
      point: { x: block.x + block.width, y: block.y + blockRect.height / 2 / zoom },
    };
  }

  const rects = mergeLineRects(
    clientRects.map((r) => ({
      left: (r.left - bodyRect.left) / zoom,
      top: (r.top - bodyRect.top) / zoom,
      width: r.width / zoom,
      height: r.height / zoom,
    })),
  );

  const top = Math.min(...clientRects.map((r) => r.top));
  const bottom = Math.max(...clientRects.map((r) => r.bottom));

  return {
    rects,
    // 锚点投影到代码块右边界：箭头落在边框上永远可见，
    // 具体指向哪一行由持久高亮说明，避免箭头被代码块压住
    point: {
      x: block.x + block.width,
      y: block.y + ((top + bottom) / 2 - blockRect.top) / zoom,
    },
  };
}

export function trimOffsets(
  content: string,
  start: number,
  end: number,
): { start: number; end: number } {
  let s = start;
  let e = end;
  while (s < e && /\s/.test(content[s])) s++;
  while (e > s && /\s/.test(content[e - 1])) e--;
  return { start: s, end: e };
}

export function buildAnchor(
  content: string,
  start: number,
  end: number,
): Anchor {
  const sc = offsetToLineChar(content, start);
  const ec = offsetToLineChar(content, end);
  return {
    startOffset: start,
    endOffset: end,
    quote: content.slice(start, end),
    startLine: sc.line,
    startChar: sc.char,
    endLine: ec.line,
    endChar: ec.char,
  };
}
