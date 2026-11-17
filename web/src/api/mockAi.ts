import { useCanvas } from '../store/canvasStore';

function compose(question: string, quote: string) {
  const snippet = quote.length > 60 ? `${quote.slice(0, 60)}…` : quote;
  return [
    `先说结论：\`${snippet}\` 这一行的作用，是把搜索区间收敛到「一定包含答案」的范围里。`,
    '',
    '逐点拆开看：',
    '',
    '1. 循环条件用 lo < hi 而不是 lo <= hi，意味着区间是左闭右开思维的变体——结束时 lo 与 hi 相遇，该位置就是候选答案，不需要额外判断。',
    '2. mid 用 lo + (hi - lo) / 2 而不是 (lo + hi) / 2，是为了避免整数溢出；当 lo 与 hi 都接近 Integer.MAX_VALUE 时，相加会变成负数。',
    '3. nums[mid] < target 时把 lo 推到 mid + 1，因为 mid 已确认不是答案；否则把 hi 收到 mid，因为 mid 仍可能是答案。这两步的不对称，正是这个写法的关键。',
    '',
    '你问的是：',
    `「${question}」`,
    '',
    '如果换成 lo <= hi，循环会多跑一轮，需要在循环内额外判断 nums[lo] == target，退出后还要再判一次，边界更容易写错。当前写法把「收敛」和「判定」合并成了一次比较，代价是最后必须单独校验 nums[lo]，这就是 return 那一行的职责。',
    '',
    '常见坑：数组为空时 lo = 0、hi = -1，循环不进入，直接走最后一行，此时 nums[lo] 会越界。生产代码建议在函数开头补一个 if (nums.length === 0) return -1;',
  ].join('\n');
}

const timers = new Map<string, number>();

export function startMockStream(qid: string, question: string, quote: string) {
  const text = compose(question, quote);
  let i = 0;
  const timer = window.setInterval(() => {
    const state = useCanvas.getState();
    const doc = state.docs[state.activeId];
    const exists = doc?.questions.some((q) => q.id === qid);
    if (!exists) {
      window.clearInterval(timer);
      timers.delete(qid);
      return;
    }
    if (i >= text.length) {
      window.clearInterval(timer);
      timers.delete(qid);
      state.setStatus(qid, 'done');
      return;
    }
    const next = Math.min(text.length, i + 4);
    state.appendAnswer(qid, text.slice(i, next));
    i = next;
  }, 24);
  timers.set(qid, timer);
}
