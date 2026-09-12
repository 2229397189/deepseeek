import { useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { Pen, Eraser, Download, FileImage } from 'lucide-react';
import { Button } from '@/shared/components/Button';
import { Textarea } from '@/shared/components/Textarea';

/** 画笔 / 橡皮。 */
type Tool = 'pen' | 'eraser';

/** 行线间距（px），与画布坐标系一致。 */
const LINE_GAP = 24;
/** 橡皮半径（px）。 */
const ERASER_RADIUS = 10;

/** 触发浏览器下载。objectUrl 会在下载后回收。 */
function download(href: string, filename: string, isObjectUrl: boolean): void {
  const a = document.createElement('a');
  a.href = href;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  if (isObjectUrl) URL.revokeObjectURL(href);
}

/** 构思板：自由画板（画笔/橡皮/行线）+ 笔记 + 导出。docs §6.4 / P2-21。 */
export function ScratchPad(): JSX.Element {
  const [notes, setNotes] = useState('');
  const [tool, setTool] = useState<Tool>('pen');
  const [showLines, setShowLines] = useState(true);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const drawing = useRef(false);

  const pointFromEvent = (e: ReactMouseEvent<HTMLCanvasElement>) => {
    const c = canvasRef.current;
    if (!c) return null;
    const rect = c.getBoundingClientRect();
    return {
      x: (e.clientX - rect.left) * (c.width / rect.width),
      y: (e.clientY - rect.top) * (c.height / rect.height),
    };
  };

  const draw = (e: ReactMouseEvent<HTMLCanvasElement>) => {
    if (!drawing.current) return;
    const c = canvasRef.current;
    const ctx = c?.getContext('2d');
    const p = pointFromEvent(e);
    if (!c || !ctx || !p) return;

    if (tool === 'eraser') {
      // 橡皮：destination-out 擦除已有笔迹（保留透明背景，不涂抹白色）
      ctx.save();
      ctx.globalCompositeOperation = 'destination-out';
      ctx.beginPath();
      ctx.arc(p.x, p.y, ERASER_RADIUS, 0, Math.PI * 2);
      ctx.fill();
      ctx.restore();
      return;
    }

    ctx.fillStyle = '#1C1B1A';
    ctx.beginPath();
    ctx.arc(p.x, p.y, 2, 0, Math.PI * 2);
    ctx.fill();
  };

  const clearCanvas = () => {
    const c = canvasRef.current;
    const ctx = c?.getContext('2d');
    if (c && ctx) ctx.clearRect(0, 0, c.width, c.height);
  };

  /** 清空全部：画板 + 笔记。 */
  const clearAll = () => {
    clearCanvas();
    setNotes('');
  };

  /** 导出笔记为 Markdown。 */
  const exportNotes = () => {
    const blob = new Blob([notes], { type: 'text/markdown;charset=utf-8' });
    download(URL.createObjectURL(blob), '构思笔记.md', true);
  };

  /** 导出画板为 PNG（仅笔迹，不含 CSS 行线背景）。 */
  const exportImage = () => {
    const c = canvasRef.current;
    if (!c) return;
    download(c.toDataURL('image/png'), '画板.png', false);
  };

  const hasNotes = notes.trim().length > 0;

  return (
    <div className="space-y-3">
      <canvas
        ref={canvasRef}
        width={400}
        height={200}
        onMouseDown={(e) => {
          drawing.current = true;
          draw(e);
        }}
        onMouseUp={() => (drawing.current = false)}
        onMouseLeave={() => (drawing.current = false)}
        onMouseMove={draw}
        className={`w-full rounded-md border border-line bg-surface ${
          tool === 'eraser' ? 'cursor-cell' : 'cursor-crosshair'
        }`}
        style={
          showLines
            ? {
                backgroundImage: `repeating-linear-gradient(to bottom, transparent, transparent ${
                  LINE_GAP - 1
                }px, var(--color-line) ${LINE_GAP - 1}px, var(--color-line) ${LINE_GAP}px)`,
              }
            : undefined
        }
      />

      {/* 工具条：画笔 / 橡皮 / 行线 */}
      <div className="flex flex-wrap items-center gap-2">
        <div className="inline-flex rounded-md bg-surface-2 p-1">
          <button
            type="button"
            onClick={() => setTool('pen')}
            className={`flex items-center gap-1 rounded px-2 py-1 text-2xs transition-colors ${
              tool === 'pen' ? 'bg-surface text-ink shadow-sm' : 'text-ink-faint hover:text-ink-soft'
            }`}
          >
            <Pen size={12} /> 画笔
          </button>
          <button
            type="button"
            onClick={() => setTool('eraser')}
            className={`flex items-center gap-1 rounded px-2 py-1 text-2xs transition-colors ${
              tool === 'eraser'
                ? 'bg-surface text-ink shadow-sm'
                : 'text-ink-faint hover:text-ink-soft'
            }`}
          >
            <Eraser size={12} /> 橡皮
          </button>
        </div>
        <label className="flex items-center gap-1 text-2xs text-ink-soft">
          <input
            type="checkbox"
            checked={showLines}
            onChange={(e) => setShowLines(e.target.checked)}
            className="accent-brand"
          />
          行线
        </label>
        <span className="flex-1" />
        <Button size="sm" variant="ghost" onClick={clearAll}>
          清空
        </Button>
      </div>

      <Textarea
        rows={3}
        placeholder="构思笔记…"
        value={notes}
        onChange={(e) => setNotes(e.target.value)}
      />

      <div className="flex items-center justify-between">
        <span className="text-2xs text-ink-faint">自由画板 + 笔记</span>
        <div className="flex items-center gap-2">
          <Button size="sm" variant="secondary" onClick={exportNotes} disabled={!hasNotes}>
            <Download size={14} /> 导出笔记
          </Button>
          <Button size="sm" variant="secondary" onClick={exportImage}>
            <FileImage size={14} /> 导出画板
          </Button>
        </div>
      </div>
    </div>
  );
}
