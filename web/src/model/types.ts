export interface Viewport {
  x: number;
  y: number;
  zoom: number;
}

/** 画布底纹图案 */
export type BgPattern = 'dot' | 'grid' | 'line' | 'blank';
/** 画布底色 */
export type BgTone = 'warm' | 'white' | 'cool' | 'dark';

export interface CanvasBg {
  pattern: BgPattern;
  tone: BgTone;
}

/** 回答框相对代码块的位置 */
export type Side = 'left' | 'right';

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
  /** 回答框在代码块的哪一侧；影响连线起点与所在列 */
  side: Side;
  /** auto = 由选中位置自动判断，manual = 用户指定 */
  sideMode: 'auto' | 'manual';
  pinned: boolean;
  collapsed: boolean;
  model: string;
  /** 代码被编辑后原文片段已不存在，锚点无法还原，只能保留问答不做高亮 */
  stale?: boolean;
}

export interface CanvasDoc {
  /** 与服务端 canvases.id 一致；新建未落库时才是临时 id */
  id: string;
  title: string;
  /** 服务端乐观锁版本；保存成功后由服务端返回的新值覆盖 */
  version: number;
  blocks: CodeBlockModel[];
  questions: Question[];
  viewport: Viewport;
  bg: CanvasBg;
}
