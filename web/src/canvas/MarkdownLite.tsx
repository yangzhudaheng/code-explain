import { memo } from 'react';

/**
 * 极简 Markdown 渲染：只认代码块、行内代码、加粗和段落。
 *
 * <p>列表不做特殊结构，靠 `white-space: pre-wrap` 保留换行原样阅读——在 320px 宽的
 * 方框里，`- xxx` 本来就比「标准列表缩进」更省横向空间。
 *
 * <p>为什么不用 marked / react-markdown：本项目的回答框只有 320px 宽，长表格、图片、
 * 嵌套引用都读不了；而且流式输出时文本是不完整的（可能停在 ``` 中间），
 * 完整解析器遇到半截语法容易吐出乱结构。这里逐行处理，任何半截状态都能渲染出一个可读的东西。
 *
 * <p>更重要的：<b>不用 dangerouslySetInnerHTML</b>。模型输出是不可信内容，
 * 直接塞进 innerHTML 等于把 XSS 交给提示词去防。这里全部走 React 元素，天然转义。
 */
export const MarkdownLite = memo(function MarkdownLite({ text }: { text: string }) {
  const lines = text.split('\n');
  const blocks: React.ReactNode[] = [];
  let i = 0;

  while (i < lines.length) {
    const line = lines[i];

    // 代码块：```lang ... ```，流式过程中还没闭合也要能显示
    const fence = line.trimStart().match(/^```(\w*)/);
    if (fence) {
      const body: string[] = [];
      i += 1;
      while (i < lines.length && !lines[i].trimStart().startsWith('```')) {
        body.push(lines[i]);
        i += 1;
      }
      i += 1; // 跳过结尾的 ```
      blocks.push(
        <pre className="md-code" key={blocks.length}>
          {body.join('\n')}
        </pre>,
      );
      continue;
    }

    // 连续的非空行合并成一段（模型常把一句话折成几行）
    if (line.trim()) {
      const paragraph: string[] = [];
      while (i < lines.length && lines[i].trim() && !lines[i].trimStart().startsWith('```')) {
        paragraph.push(lines[i]);
        i += 1;
      }
      blocks.push(
        <p className="md-p" key={blocks.length}>
          {paragraph.map((l, idx) => (
            <span key={idx}>
              {inline(l)}
              {idx < paragraph.length - 1 ? '\n' : null}
            </span>
          ))}
        </p>,
      );
      continue;
    }

    i += 1;
  }

  return <>{blocks}</>;
});

/** 行内格式：`code` 与 **bold**。其余原样输出，不猜测链接与图片。 */
function inline(text: string): React.ReactNode[] {
  const parts = text.split(/(`[^`]+`|\*\*[^*]+\*\*)/g);
  return parts.filter(Boolean).map((part, idx) => {
    if (part.startsWith('`') && part.endsWith('`') && part.length > 2) {
      return (
        <code className="md-inline-code" key={idx}>
          {part.slice(1, -1)}
        </code>
      );
    }
    if (part.startsWith('**') && part.endsWith('**') && part.length > 4) {
      return <strong key={idx}>{part.slice(2, -2)}</strong>;
    }
    return <span key={idx}>{part}</span>;
  });
}
