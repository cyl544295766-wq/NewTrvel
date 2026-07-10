type ConfirmDialogProps = {
  title: string;
  content: string;
  confirmLabel: string;
  cancelLabel?: string;
  isOpen: boolean;
  isPending?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
};

export function ConfirmDialog({
  title,
  content,
  confirmLabel,
  cancelLabel = 'Cancel',
  isOpen,
  isPending = false,
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  if (!isOpen) {
    return null;
  }

  return (
    <div className="dialog-backdrop" role="presentation">
      <div aria-modal="true" className="confirm-dialog" role="dialog">
        <h2>{title}</h2>
        <p>{content}</p>
        <div className="dialog-actions">
          <button className="secondary-button" disabled={isPending} onClick={onCancel} type="button">
            {cancelLabel}
          </button>
          <button disabled={isPending} onClick={onConfirm} type="button">
            {confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
