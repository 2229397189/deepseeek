import { useEffect, useRef } from 'react';
import { Video, VideoOff } from 'lucide-react';
import { Button } from '@/shared/components/Button';

interface CameraPanelProps {
  on: boolean;
  onToggle: () => void;
}

/** 摄像头本地预览（仅本地，不上传）。docs §6.4 / §8-4。 */
export function CameraPanel({ on, onToggle }: CameraPanelProps): JSX.Element {
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);

  useEffect(() => {
    let cancelled = false;
    if (on) {
      navigator.mediaDevices
        ?.getUserMedia({ video: true })
        .then((stream) => {
          if (cancelled) {
            stream.getTracks().forEach((t) => t.stop());
            return;
          }
          streamRef.current = stream;
          if (videoRef.current) videoRef.current.srcObject = stream;
        })
        .catch(() => undefined);
    }
    return () => {
      cancelled = true;
      streamRef.current?.getTracks().forEach((t) => t.stop());
      streamRef.current = null;
    };
  }, [on]);

  return (
    <div className="space-y-2">
      <div className="relative aspect-video overflow-hidden rounded-md bg-black">
        {on ? (
          <video
            ref={videoRef}
            autoPlay
            playsInline
            muted
            className="h-full w-full object-cover"
          />
        ) : (
          <div className="flex h-full items-center justify-center text-xs text-white/60">
            摄像头已关闭
          </div>
        )}
      </div>
      <Button size="sm" variant="secondary" block onClick={onToggle}>
        {on ? <VideoOff size={15} /> : <Video size={15} />}
        {on ? '关闭摄像头' : '开启摄像头'}
      </Button>
    </div>
  );
}
