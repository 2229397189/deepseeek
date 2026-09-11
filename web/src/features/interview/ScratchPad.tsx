import { useRef, useState } from 'react';
import type { MouseEvent as ReactMouseEvent } from 'react';
import { Button } from '@/shared/components/Button';
import { Textarea } from '@/shared/components/Textarea';

/** 构思板：自由画板 + 笔记。docs §6.4。 */
export function ScratchPad(): JSX.Element {
  const [notes, setNotes] = useState('');
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
    ctx.fillStyle = '#1C1B1A';
    ctx.beginPath();
    ctx.arc(p.x, p.y, 2, 0, Math.PI * 2);
    ctx.fill();
  };

  const clear = () => {
    const c = canvasRef.current;
    const ctx = c?.getContext('2d');
    if (c && ctx) ctx.clearRect(0, 0, c.width, c.height);
  };

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
        className="w-full cursor-crosshair rounded-md border border-line bg-surface"
      />
      <div className="flex items-center justify-between">
        <span className="text-2xs text-ink-faint">自由画板</span>
        <Button size="sm" variant="ghost" onClick={clear}>
          清空
        </Button>
      </div>
      <Textarea
        rows={3}
        placeholder="构思笔记…"
        value={notes}
        onChange={(e) => setNotes(e.target.value)}
      />
    </div>
  );
}
