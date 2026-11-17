import type { Anchor, CodeBlockModel, Rect4 } from '../model/types';

/**
 * 把 DOM Selection 的端点换算成「相对代码块纯文本」的字符偏移。
 * 前提：<pre> 内只有一个 Text 节点（粘贴进来的整段代码），
 * 这样偏移量永远稳定，且不会被高亮 DOM 污染。
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
  const textNode = pre.firstChild;
  if (!textNode || textNode.nodeType !== Node.TEXT_NODE) return null;

  const max = (textNode as Text).length;
  const s = Math.max(0, Math.min(startOffset, max));
  const e = Math.max(s, Math.min(endOffset, max));

  const range = document.createRange();
  range.setStart(textNode, s);
  range.setEnd(textNode, e);

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

  const rects: Rect4[] = clientRects.map((r) => ({
    left: (r.left - bodyRect.left) / zoom,
    top: (r.top - bodyRect.top) / zoom,
    width: r.width / zoom,
    height: r.height / zoom,
  }));

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
