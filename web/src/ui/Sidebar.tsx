import { useCanvas } from '../store/canvasStore';

export function Sidebar() {
  const order = useCanvas((s) => s.order);
  const docs = useCanvas((s) => s.docs);
  const activeId = useCanvas((s) => s.activeId);
  const setActive = useCanvas((s) => s.setActive);
  const addCanvas = useCanvas((s) => s.addCanvas);

  return (
    <aside className="sidebar" onPointerDown={(e) => e.stopPropagation()}>
      <div className="brand">
        <span className="brand-mark">CE</span>
        <div>
          <div className="brand-name">Code Explain</div>
          <div className="brand-sub">代码题解画布</div>
        </div>
      </div>

      <div className="side-section">
        <div className="side-title">
          画布
          <button className="side-add" onClick={addCanvas} title="新建画布">
            ＋
          </button>
        </div>
        <ul className="doc-list">
          {order.map((id) => (
            <li
              key={id}
              className={id === activeId ? 'active' : ''}
              onClick={() => setActive(id)}
            >
              <span className="doc-dot" />
              <span className="doc-name">{docs[id].title}</span>
              <span className="doc-count">{docs[id].questions.length}</span>
            </li>
          ))}
        </ul>
      </div>

      <div className="side-tips">
        <p>· 拖标题栏整块移动代码</p>
        <p>· 选中代码任意片段 → 点 +</p>
        <p>· 空格拖拽或滚轮平移画布</p>
        <p>· Ctrl + 滚轮缩放</p>
        <p>· Ctrl+V 直接粘贴代码</p>
      </div>

      <div className="side-foot">M0 交互原型 · 回答为模拟数据</div>
    </aside>
  );
}
