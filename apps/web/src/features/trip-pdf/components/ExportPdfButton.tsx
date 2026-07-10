import { Check, Download, LoaderCircle, TriangleAlert } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { downloadTripPdf } from '../api/trip-pdf.api';
import { TripPdfButtonStatus } from '../types/trip-pdf.types';

type ExportPdfButtonProps = {
  tripId: string;
  compact?: boolean;
  className?: string;
};

const statusContent = {
  idle: { label: '导出 PDF', icon: Download },
  loading: { label: '生成中...', icon: LoaderCircle },
  success: { label: '已下载', icon: Check },
  error: { label: '导出失败', icon: TriangleAlert },
} satisfies Record<TripPdfButtonStatus, { label: string; icon: typeof Download }>;

export function ExportPdfButton({ tripId, compact = false, className }: ExportPdfButtonProps) {
  const [status, setStatus] = useState<TripPdfButtonStatus>('idle');
  const resetTimer = useRef<number | null>(null);
  const content = statusContent[status];
  const Icon = content.icon;

  useEffect(
    () => () => {
      if (resetTimer.current !== null) window.clearTimeout(resetTimer.current);
    },
    [],
  );

  async function handleExport() {
    setStatus('loading');
    try {
      const result = await downloadTripPdf(tripId);
      const objectUrl = window.URL.createObjectURL(result.blob);
      const anchor = document.createElement('a');
      anchor.href = objectUrl;
      anchor.download = result.filename;
      document.body.appendChild(anchor);
      anchor.click();
      anchor.remove();
      window.URL.revokeObjectURL(objectUrl);
      setStatus('success');
    } catch {
      setStatus('error');
    }

    resetTimer.current = window.setTimeout(() => setStatus('idle'), 2500);
  }

  return (
    <button
      className={[className ?? 'secondary-button', 'trip-pdf-button', compact ? 'compact' : '']
        .filter(Boolean)
        .join(' ')}
      disabled={status === 'loading'}
      onClick={() => void handleExport()}
      type="button"
    >
      <Icon className={status === 'loading' ? 'spin-icon' : undefined} size={compact ? 15 : 16} />
      <span>{compact && status === 'idle' ? 'PDF' : content.label}</span>
    </button>
  );
}
