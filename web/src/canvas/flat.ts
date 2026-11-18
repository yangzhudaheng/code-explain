import { createContext, useContext } from 'react';

/**
 * 「导出模式」的上下文。
 *
 * <p>为什么用 context 而不是给每个组件加 prop：CodeBlock / AnswerBox /
 * ConnectorLayer 都是被 Canvas 直接渲染的，加 prop 会污染正常渲染路径的签名；
 * 而且导出视图和正常视图用的是同一套组件、同一份 store，只在几处行为上不同。
 *
 * <p>非 null 就代表「当前在导出视图里」，值是导出画布的尺寸（SVG 连线层需要它
 * 撑开自己的 viewport，否则 1×1 的 svg 会把溢出部分裁掉）。
 */
export interface FlatInfo {
  width: number;
  height: number;
}

export const FlatContext = createContext<FlatInfo | null>(null);

/** 在导出视图里返回尺寸，正常渲染返回 null。 */
export function useFlat(): FlatInfo | null {
  return useContext(FlatContext);
}
