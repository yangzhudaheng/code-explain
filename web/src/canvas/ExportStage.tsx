import type { CanvasDoc } from '../model/types';
import { bgStyle, toneOf } from './background';
import { ConnectorLayer } from './ConnectorLayer';
import { CodeBlock } from './CodeBlock';
import { AnswerBox } from './AnswerBox';

interface Props {
  doc: CanvasDoc;
  /** 导出区域的左上角（世界坐标） */
  origin: { x: number; y: number };
  width: number;
  height: number;
}

/**
 * 导出用的画布视图。
 *
 * <p>它和屏幕上的画布是两套 DOM：屏幕上的那份带着 `translate + scale`，
 * 而且混着加号、按钮、选中态。栅格化必须基于「缩放为 1、无交互元素」的干净版本，
 * 否则导出的图会带上不属于作品的东西，而且矢量缩放会被 <foreignObject> 吃掉。
 *
 * <p>尺寸由父级算好传进来（父级会先量一遍真实 DOM 里各块的高度），
 * 所以这里不做二次测量，避免「先渲染再改尺寸」的抖动。
 */
export function ExportStage({ doc, origin, width, height }: Props) {
  const tone = toneOf(doc.bg.tone);

  return (
    <div
      className={`export-stage tone-${doc.bg.tone}`}
      style={{ width, height, background: tone.base }}
    >
      <div
        className="grid"
        style={bgStyle(doc.bg, { x: -origin.x, y: -origin.y, zoom: 1 })}
      />

      {/* 用 left/top 偏移代替 transform：栅格化时 transform 更容易出偏差 */}
      <div
        className="world"
        style={{
          left: -origin.x,
          top: -origin.y,
          width,
          height,
          transform: 'none',
        }}
      >
        <ConnectorLayer />
        {doc.blocks.map((block) => (
          <CodeBlock
            key={block.id}
            block={block}
            questions={doc.questions.filter((q) => q.blockId === block.id)}
          />
        ))}
        {doc.questions.map((q) => (
          <AnswerBox key={q.id} q={q} />
        ))}
      </div>
    </div>
  );
}
