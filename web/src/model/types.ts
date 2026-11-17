export interface Viewport {
  x: number;
  y: number;
  zoom: number;
}

export interface CodeBlockModel {
  id: string;
  x: number;
  y: number;
  width: number;
  title: string;
  language: string;
  content: string;
}

/** 语义锚点：与像素无关，代码块移动/缩放后仍能还原高亮 */
export interface Anchor {
  startOffset: number;
  endOffset: number;
  quote: string;
  startLine: number;
  startChar: number;
  endLine: number;
  endChar: number;
}

/** 相对代码块正文容器的矩形，用于绘制持久高亮 */
export interface Rect4 {
  left: number;
  top: number;
  width: number;
  height: number;
}

export type AnswerStatus = 'streaming' | 'done' | 'error';

export interface Question {
  id: string;
  blockId: string;
  anchor: Anchor;
  /** 锚点在画布世界坐标里的位置（跟随代码块移动） */
  anchorPoint: { x: number; y: number };
  anchorRects: Rect4[];
  text: string;
  answer: string;
  status: AnswerStatus;
  boxX: number;
  boxY: number;
  boxH: number;
  pinned: boolean;
  collapsed: boolean;
  model: string;
}

export interface CanvasDoc {
  id: string;
  title: string;
  blocks: CodeBlockModel[];
  questions: Question[];
  viewport: Viewport;
}
