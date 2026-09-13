import { useEffect, useRef, useState } from 'react';
import { Mic } from 'lucide-react';

interface VoiceInputProps {
  value: string;
  onChange: (value: string) => void;
  disabled?: boolean;
}

/**
 * 麦克风实时转写（Web Speech API）。转写文本写入回答框。
 * 不支持时禁用并提示。docs §6.4 / §8-3（采用浏览器端方案）。
 */
export function VoiceInput({ value, onChange, disabled }: VoiceInputProps): JSX.Element {
  const [listening, setListening] = useState(false);
  const [supported, setSupported] = useState(true);
  const recognitionRef = useRef<{
    start: () => void;
    stop: () => void;
    lang: string;
    continuous: boolean;
    interimResults: boolean;
    onresult: ((e: { results: ArrayLike<ArrayLike<{ transcript: string }>> }) => void) | null;
    onend: (() => void) | null;
    onerror: (() => void) | null;
  } | null>(null);
  const valueRef = useRef(value);
  valueRef.current = value;

  useEffect(() => {
    const SR =
      (window as unknown as { SpeechRecognition?: new () => unknown }).SpeechRecognition ||
      (window as unknown as { webkitSpeechRecognition?: new () => unknown }).webkitSpeechRecognition;
    if (!SR) {
      setSupported(false);
      return;
    }
    const rec = new SR() as NonNullable<typeof recognitionRef.current>;
    rec.lang = 'zh-CN';
    rec.continuous = true;
    rec.interimResults = true;
    rec.onresult = (e) => {
      let text = '';
      for (let i = 0; i < e.results.length; i += 1) {
        text += e.results[i][0].transcript;
      }
      onChange((valueRef.current ? valueRef.current + ' ' : '') + text);
    };
    rec.onend = () => setListening(false);
    rec.onerror = () => setListening(false);
    recognitionRef.current = rec;

    return () => {
      try {
        recognitionRef.current?.stop();
      } catch {
        /* ignore */
      }
      recognitionRef.current = null;
    };
  }, [onChange]);

  const toggle = () => {
    const rec = recognitionRef.current;
    if (!rec || disabled) return;
    if (listening) {
      rec.stop();
      setListening(false);
    } else {
      try {
        rec.start();
        setListening(true);
      } catch {
        setListening(false);
      }
    }
  };

  return (
    <button
      type="button"
      onClick={toggle}
      disabled={disabled || !supported}
      title={supported ? (listening ? '停止转写' : '语音转写') : '当前浏览器不支持语音识别'}
      className={[
        'inline-flex h-9 w-9 items-center justify-center rounded-md border transition-colors duration-base',
        listening
          ? 'border-danger bg-danger-soft text-danger'
          : 'border-line bg-surface text-ink-soft hover:bg-surface-2',
        disabled || !supported ? 'cursor-not-allowed opacity-50' : '',
      ].join(' ')}
    >
      <Mic size={16} className={listening ? 'animate-pulse' : ''} />
    </button>
  );
}
