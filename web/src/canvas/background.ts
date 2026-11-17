import type { CSSProperties } from 'react';
import type { BgPattern, BgTone, CanvasBg, Viewport } from '../model/types';

interface ToneDef {
  id: BgTone;
  label: string;
  /** 画布底色 */
  base: string;
  /** 点阵颜色 */
  dot: string;
  /** 网格 / 横线颜色 */
  line: string;
}

export const TONES: ToneDef[] = [
  { id: 'warm', label: '米白', base: '#fbfbfa', dot: '#e0e1dc', line: '#e8e9e3' },
  { id: 'white', label: '纯白', base: '#ffffff', dot: '#e9ebf0', line: '#f0f2f6' },
  { id: 'cool', label: '雾蓝', base: '#f5f8fe', dot: '#d6e0f0', line: '#e3eaf6' },
  { id: 'dark', label: '深灰', base: '#1b1e24', dot: '#353b46', line: '#2a2f39' },
];

export const PATTERNS: { id: BgPattern; label: string }[] = [
  { id: 'dot', label: '点阵' },
  { id: 'grid', label: '方格' },
  { id: 'line', label: '横线' },
  { id: 'blank', label: '纯色' },
];

export function toneOf(id: BgTone) {
  return TONES.find((t) => t.id === id) ?? TONES[0];
}

/**
 * 底纹随视口一起平移缩放：backgroundPosition 用 viewport 偏移，
 * 图案周期用 24 * zoom，这样缩放时底纹的疏密与画布内容同步，不会产生视差。
 */
export function bgStyle(bg: CanvasBg, viewport: Viewport): CSSProperties {
  const tone = toneOf(bg.tone);
  const step = 24 * viewport.zoom;
  const position = `${viewport.x}px ${viewport.y}px`;

  switch (bg.pattern) {
    case 'dot':
      return {
        backgroundImage: `radial-gradient(circle, ${tone.dot} 1.1px, transparent 1.1px)`,
        backgroundSize: `${step}px ${step}px`,
        backgroundPosition: position,
      };
    case 'grid':
      return {
        backgroundImage: `linear-gradient(to right, ${tone.line} 1px, transparent 1px), linear-gradient(to bottom, ${tone.line} 1px, transparent 1px)`,
        backgroundSize: `${step}px ${step}px`,
        backgroundPosition: position,
      };
    case 'line':
      return {
        backgroundImage: `repeating-linear-gradient(to bottom, ${tone.line} 0 1px, transparent 1px ${step}px)`,
        backgroundPosition: position,
      };
    default:
      return { backgroundImage: 'none' };
  }
}

/** 背景选择器里的小预览块 */
export function swatchStyle(pattern: BgPattern, tone: BgTone): CSSProperties {
  const def = toneOf(tone);
  return {
    backgroundColor: def.base,
    ...bgStyle({ pattern, tone }, { x: 0, y: 0, zoom: 0.45 }),
  };
}
