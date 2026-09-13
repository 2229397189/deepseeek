import { forwardRef, useState } from 'react';
import { renderMarkdown } from './markdown';

interface ResumePreviewProps {
  markdown: string;
}

/** 中栏：A4 实时预览（缩放 / 页边距 / 字号 / 行高 可调）。docs §6.2。 */
export const ResumePreview = forwardRef<HTMLDivElement, ResumePreviewProps>(
  function ResumePreview({ markdown }, ref): JSX.Element {
    const [zoom, setZoom] = useState(0.76);
    const [marginMm, setMarginMm] = useState(14);
    const [fontPx, setFontPx] = useState(13);
    const [lineH, setLineH] = useState(1.6);

    return (
      <div className="flex h-full flex-col">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2 border-b border-line px-3 py-2 text-xs text-ink-soft">
          <label className="flex items-center gap-2">
            缩放
            <input
              type="range"
              min={0.5}
              max={1}
              step={0.02}
              value={zoom}
              onChange={(e) => setZoom(Number(e.target.value))}
              className="accent-brand"
            />
            <span className="font-mono">{Math.round(zoom * 100)}%</span>
          </label>
          <label className="flex items-center gap-2">
            页边距
            <input
              type="range"
              min={6}
              max={24}
              step={1}
              value={marginMm}
              onChange={(e) => setMarginMm(Number(e.target.value))}
              className="accent-brand"
            />
            <span className="font-mono">{marginMm}mm</span>
          </label>
          <label className="flex items-center gap-2">
            字号
            <input
              type="range"
              min={11}
              max={16}
              step={1}
              value={fontPx}
              onChange={(e) => setFontPx(Number(e.target.value))}
              className="accent-brand"
            />
            <span className="font-mono">{fontPx}px</span>
          </label>
          <label className="flex items-center gap-2">
            行高
            <input
              type="range"
              min={1.2}
              max={2}
              step={0.1}
              value={lineH}
              onChange={(e) => setLineH(Number(e.target.value))}
              className="accent-brand"
            />
            <span className="font-mono">{lineH.toFixed(1)}</span>
          </label>
        </div>

        <div className="min-h-0 flex-1 overflow-auto bg-surface-2 p-4">
          <div
            style={{ width: '210mm', transform: `scale(${zoom})`, transformOrigin: 'top center' }}
            className="mx-auto"
          >
            <div
              ref={ref}
              className="prose-resume bg-white shadow-card"
              style={{
                padding: `${marginMm}mm`,
                fontSize: `${fontPx}px`,
                lineHeight: lineH,
                minHeight: '297mm',
              }}
              dangerouslySetInnerHTML={{ __html: renderMarkdown(markdown) }}
            />
          </div>
        </div>
      </div>
    );
  },
);
