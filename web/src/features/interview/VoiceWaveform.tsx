import { useEffect, useRef, useState } from 'react';
import { Mic, MicOff } from 'lucide-react';

/** 显示的频谱条数量（analyser.fftSize = 64 → frequencyBinCount = 32）。 */
const BAR_COUNT = 32;

/** 语音波形：麦克风实时频谱条（Web Audio AnalyserNode）。docs §6.4 / P2-22。 */
export function VoiceWaveform(): JSX.Element {
  const [levels, setLevels] = useState<number[]>(() => new Array(BAR_COUNT).fill(0));
  const [error, setError] = useState<string | null>(null);
  const [active, setActive] = useState(false);
  const rafRef = useRef<number | null>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const ctxRef = useRef<AudioContext | null>(null);

  useEffect(() => {
    let cancelled = false;

    const start = async () => {
      if (!navigator.mediaDevices?.getUserMedia) {
        setError('浏览器不支持麦克风采集（需 HTTPS 或 localhost）');
        return;
      }
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
        // 组件可能在授权返回前就已卸载
        if (cancelled) {
          stream.getTracks().forEach((t) => t.stop());
          return;
        }
        streamRef.current = stream;

        const Ctor =
          window.AudioContext ??
          (window as unknown as { webkitAudioContext?: typeof AudioContext }).webkitAudioContext;
        if (!Ctor) {
          setError('当前浏览器不支持 Web Audio');
          return;
        }

        const ctx = new Ctor();
        ctxRef.current = ctx;
        const analyser = ctx.createAnalyser();
        analyser.fftSize = 64;
        ctx.createMediaStreamSource(stream).connect(analyser);

        const data = new Uint8Array(analyser.frequencyBinCount);
        setActive(true);

        const tick = () => {
          analyser.getByteFrequencyData(data);
          const next: number[] = [];
          for (let i = 0; i < BAR_COUNT; i += 1) {
            next.push((data[i] ?? 0) / 255);
          }
          if (!cancelled) setLevels(next);
          rafRef.current = requestAnimationFrame(tick);
        };
        tick();
      } catch (e) {
        if (!cancelled) setError(e instanceof Error ? e.message : '无法访问麦克风');
      }
    };

    void start();

    return () => {
      cancelled = true;
      if (rafRef.current !== null) cancelAnimationFrame(rafRef.current);
      streamRef.current?.getTracks().forEach((t) => t.stop());
      void ctxRef.current?.close();
    };
  }, []);

  return (
    <div className="space-y-2">
      <div className="flex h-16 items-end justify-center gap-0.5 rounded-md border border-line bg-surface-2 px-2 py-2">
        {levels.map((v, i) => (
          <span
            key={i}
            className="w-1.5 rounded-sm bg-brand"
            style={{ height: `${Math.max(3, v * 100)}%` }}
          />
        ))}
      </div>
      <div className="flex items-center gap-1 text-2xs text-ink-faint">
        {active && !error ? <Mic size={12} className="text-brand" /> : <MicOff size={12} />}
        <span>
          {error
            ? `麦克风不可用：${error}`
            : active
              ? '正在采集麦克风输入'
              : '等待麦克风权限…'}
        </span>
      </div>
    </div>
  );
}
