import { HttpError } from '../api/client';
import { qaApi, streamAnswer } from '../api/qa';
import { useCanvas } from '../store/canvasStore';

/**
 * 提问 → 落库 → 流式回答的完整流程。
 *
 * <p>为什么不放在组件里：加号弹层、回答框的「重试」按钮、将来的「重新回答」都要用同一条流程，
 * 散在组件里必然出现三份略有差异的实现（漏 abort、漏状态复位）。
 *
 * <p>一个问答的 id 由前端生成（{@code q_xxx}），它同时是「快照里的问答」与
 * 「questions 表里的 client_id」——服务端靠它把两条链路对上，所以这里直接用 q.id 当幂等键。
 */

/** 正在进行的流，切画布 / 删问答 / 关闭页面时要能中止，否则白花 token。 */
const controllers = new Map<string, AbortController>();

export function openAiSettings() {
  window.dispatchEvent(new CustomEvent('ce:open-ai-settings'));
}

export function cancelAsk(questionId: string) {
  controllers.get(questionId)?.abort();
  controllers.delete(questionId);
}

/** 当前画布上这条问答还在不在；不在就说明用户已经切走了。 */
function stillThere(questionId: string): boolean {
  const s = useCanvas.getState();
  return !!s.docs[s.activeId]?.questions.some((q) => q.id === questionId);
}

export async function askAi(questionId: string): Promise<void> {
  const state = useCanvas.getState();
  const doc = state.docs[state.activeId];
  const question = doc?.questions.find((q) => q.id === questionId);
  if (!doc || !question) return;

  const block = doc.blocks.find((b) => b.id === question.blockId);

  cancelAsk(questionId);
  const controller = new AbortController();
  controllers.set(questionId, controller);

  state.setAnswer(questionId, '');
  state.setStatus(questionId, 'streaming');

  const fail = (message: string, code: string) => {
    const s = useCanvas.getState();
    const current = s.docs[s.activeId]?.questions.find((q) => q.id === questionId);
    if (!current) return;
    // 已经吐出一部分内容时把错误追加在后面，而不是把它冲掉
    s.setAnswer(questionId, current.answer ? `${current.answer}\n\n⚠️ ${message}` : `⚠️ ${message}`);
    s.setStatus(questionId, 'error');
    if (code === 'AI_NOT_CONFIGURED') openAiSettings();
  };

  try {
    const { questionId: serverId } = await qaApi.createQuestion(doc.id, {
      clientId: question.id,
      blockId: question.blockId,
      anchor: question.anchor,
      text: question.text,
      language: block?.language,
      title: block?.title,
      code: block?.content,
    });

    await streamAnswer(
      serverId,
      { code: block?.content, language: block?.language, title: block?.title },
      {
        onDelta: (text) => {
          // 用户已经切到别的画布：这条流的增量没有地方可写，直接断掉省 token
          if (!stillThere(questionId)) {
            controller.abort();
            return;
          }
          useCanvas.getState().appendAnswer(questionId, text);
        },
        onDone: (usage) => {
          if (!stillThere(questionId)) return;
          const s = useCanvas.getState();
          if (usage.model) s.setModel(questionId, usage.model);
          s.setStatus(questionId, 'done');
        },
        onError: (message, code) => fail(message, code),
      },
      controller.signal,
    );

    // 流正常结束但没收到 done（极少见：上游提前关连接）
    const s = useCanvas.getState();
    const q = s.docs[s.activeId]?.questions.find((x) => x.id === questionId);
    if (q?.status === 'streaming') s.setStatus(questionId, 'done');
  } catch (e) {
    if ((e as Error)?.name === 'AbortError') return;
    if (e instanceof HttpError) {
      fail(
        e.code === 'AI_NOT_CONFIGURED'
          ? '还没配置模型 API Key。在右上角「AI 设置」里填入 DeepSeek 的 Key，然后点本文框右上角的 ↻ 重新回答。'
          : e.message,
        e.code,
      );
    } else {
      fail(e instanceof Error ? e.message : '请求失败，请稍后重试', 'UNKNOWN');
    }
  } finally {
    controllers.delete(questionId);
  }
}
