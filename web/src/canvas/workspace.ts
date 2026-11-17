import { canvasApi } from '../api/canvas';
import { docFromDetail, starterDoc, useCanvas } from '../store/canvasStore';
import { buildAnchor, measureAnchor } from './anchors';

/** 首次使用时自动灌进去的两条示例问答，让新用户一眼看懂「选中 → 提问 → 框」怎么用 */
const SAMPLE_QUESTIONS = [
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

/** 刚由本次加载新建的示例画布；只有它才需要注入示例问答 */
let seededId: string | null = null;

const ACTIVE_KEY = 'ce.activeCanvas';

/**
 * 加载去重。
 *
 * <p>React StrictMode 在开发环境会把 effect 执行两遍（挂载→清理→再挂载），
 * 裸写的话两次加载都会看到「一个画布都没有」，于是给同一个新账号建出两个示例画布。
 * 这里用一个共享 Promise 把并发调用压成一次；登出时再 {@link resetWorkspace}。
 */
let loading: Promise<void> | null = null;
let loaded = false;

export function loadWorkspace(): Promise<void> {
  if (loaded) return Promise.resolve();
  if (!loading) {
    loading = doLoad()
      .then(() => {
        loaded = true;
      })
      .catch((e) => {
        // 失败要能重试：把锁清掉，否则「重试」按钮永远拿到同一个 rejected promise
        loading = null;
        throw e;
      });
  }
  return loading;
}

/** 登出时清空：下次登录（可能是另一个账号）必须重新拉自己的画布 */
export function resetWorkspace(): void {
  loading = null;
  loaded = false;
  seededId = null;
  // 内存里的画布也要清掉，否则退出后换个账号登录，上一账号的内容会先闪一帧
  useCanvas.setState({
    docs: {},
    order: [],
    activeId: '',
    hoverQid: null,
    saveState: 'idle',
    saveError: null,
    lastSavedAt: null,
  });
}

async function doLoad(): Promise<void> {
  const list = await canvasApi.list();

  if (list.length === 0) {
    const title = '二分查找 · 边界问题';
    const created = await canvasApi.create({ title, data: starterDoc(title) });
    seededId = created.id;
    useCanvas.getState().hydrate([docFromDetail(created)]);
    return;
  }

  const details = await Promise.all(list.map((s) => canvasApi.get(s.id)));
  const rank = new Map(list.map((s, i) => [s.id, i]));
  const docs = details
    .map(docFromDetail)
    .sort((a, b) => (rank.get(a.id) ?? 0) - (rank.get(b.id) ?? 0));

  const remembered = localStorage.getItem(ACTIVE_KEY) ?? undefined;
  useCanvas.getState().hydrate(docs, remembered);
}

/** 示例数据与 canvasStore 的新建默认值保持一致，只是多带一个能讲清的标题 */
export function rememberActiveCanvas(id: string) {
  if (id) localStorage.setItem(ACTIVE_KEY, id);
}

/**
 * 首次进画布时补上示例问答。
 *
 * <p>必须在 DOM 渲染完成后测量锚点，所以走 requestAnimationFrame；
 * 只在「本次刚刚创建的示例画布」上跑，用户删掉示例后刷新不会再冒出来。
 */
export function seedSampleQuestions(): void {
  if (!seededId) return;
  const target = seededId;
  seededId = null;

  requestAnimationFrame(() => {
    const st = useCanvas.getState();
    const doc = st.docs[target];
    if (!doc || st.activeId !== target) return;
    const block = doc.blocks[0];
    if (!block) return;

    const blockEl = document.querySelector(
      `[data-block-id="${block.id}"]`,
    ) as HTMLElement | null;
    const pre = blockEl?.querySelector('pre') as HTMLElement | null;
    const bodyEl = blockEl?.querySelector('.block-body') as HTMLElement | null;
    if (!blockEl || !pre || !bodyEl) return;

    for (const sample of SAMPLE_QUESTIONS) {
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
}
