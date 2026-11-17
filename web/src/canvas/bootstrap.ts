import { useEffect } from 'react';
import { useCanvas } from '../store/canvasStore';
import { buildAnchor, measureAnchor } from './anchors';

const SAMPLES = [
  {
    quote: 'while (lo < hi)',
    text: '这里为什么用 lo < hi 而不是 lo <= hi？',
    answer: [
      '因为循环里维护的区间是「左闭右闭」的候选集，但退出条件被刻意写成相遇即停。',
      '',
      '用 lo < hi 时，循环结束时 lo 与 hi 一定相等，这个位置就是唯一候选答案；此时区间里只剩一个元素，不需要再进循环判断。换成 lo <= hi，最后一轮会对单元素区间再判一次，循环体内就必须处理「单元素且不等于 target」的情况，否则会死循环。',
      '',
      '代价是：退出后必须补一次 nums[lo] == target 的校验，因为循环停止不代表找到了目标值，只代表区间收敛到一点。',
    ].join('\n'),
  },
  {
    quote: 'return nums[lo] == target ? lo : -1;',
    text: '最后这行为什么还要再判一次相等？',
    answer: [
      '循环只能保证「如果答案存在，它一定在 lo 这个位置」，但不能保证答案存在。',
      '',
      '所以这一行做的是存在性校验：命中就返回下标，否则返回 -1。如果省掉它，target 不存在时会返回一个越界或错误的下标。',
      '',
      '还有一个边界：nums 为空时 hi = -1，循环不进入，直接走到这里会访问 nums[0] 而抛数组越界。生产代码建议在函数入口加 if (nums.length == 0) return -1;',
    ].join('\n'),
  },
];

let booted = false;

export function useBootstrapSamples() {
  useEffect(() => {
    if (booted) return;
    booted = true;

    requestAnimationFrame(() => {
      const st = useCanvas.getState();
      const doc = st.docs[st.activeId];
      const block = doc.blocks[0];
      if (!block) return;

      const blockEl = document.querySelector(
        `[data-block-id="${block.id}"]`,
      ) as HTMLElement | null;
      const pre = blockEl?.querySelector('pre') as HTMLElement | null;
      const bodyEl = blockEl?.querySelector('.block-body') as HTMLElement | null;
      if (!blockEl || !pre || !bodyEl) return;

      for (const sample of SAMPLES) {
        const index = block.content.indexOf(sample.quote);
        if (index < 0) continue;

        const measured = measureAnchor({
          pre,
          blockEl,
          bodyEl,
          block,
          zoom: doc.viewport.zoom,
          startOffset: index,
          endOffset: index + sample.quote.length,
        });
        if (!measured) continue;

        const id = useCanvas.getState().addQuestion({
          blockId: block.id,
          anchor: buildAnchor(block.content, index, index + sample.quote.length),
          anchorPoint: measured.point,
          anchorRects: measured.rects,
          text: sample.text,
        });
        useCanvas.getState().appendAnswer(id, sample.answer);
        useCanvas.getState().setStatus(id, 'done');
      }
    });
  }, []);
}
