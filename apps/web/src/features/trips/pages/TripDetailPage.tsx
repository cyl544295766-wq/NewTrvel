import { useState } from 'react';
import { Link, Navigate, useNavigate, useParams } from 'react-router-dom';
import { ConfirmDialog } from '../../../components/ConfirmDialog';
import { TripMembers } from '../components/TripMembers';
import { TripStatusBadge } from '../components/TripStatusBadge';
import {
  useArchiveTrip,
  useDeleteTrip,
  useDuplicateTrip,
  useFavoriteTrip,
  useTrip,
} from '../hooks/useTrips';
import { TripMemberRole } from '../types/trip.types';

const roleLabels: Record<TripMemberRole, string> = {
  owner: '所有者',
  admin: '管理员',
  member: '成员',
  viewer: '观察者',
};

export function TripDetailPage() {
  const navigate = useNavigate();
  const { tripId } = useParams<{ tripId: string }>();
  const trip = useTrip(tripId ?? '', Boolean(tripId));
  const archiveTrip = useArchiveTrip();
  const duplicateTrip = useDuplicateTrip();
  const favoriteTrip = useFavoriteTrip();
  const deleteTrip = useDeleteTrip();
  const [isDeleteDialogOpen, setIsDeleteDialogOpen] = useState(false);
  const [isMembersOpen, setIsMembersOpen] = useState(false);
  const data = trip.data?.trip;
  const canManage = data?.currentUserRole === 'owner' || data?.currentUserRole === 'admin';

  if (!tripId) {
    return <Navigate replace to="/" />;
  }

  if (trip.isLoading) {
    return <main className="loading-shell">加载中...</main>;
  }

  if (trip.isError || !data) {
    return <Navigate replace to="/" />;
  }

  async function handleDelete() {
    if (!data) {
      return;
    }

    await deleteTrip.mutateAsync(data.id);
    setIsDeleteDialogOpen(false);
    navigate('/', { replace: true });
  }

  return (
    <main className="app-page narrow-page">
      <Link className="text-link" to="/">
        返回旅行列表
      </Link>
      <section className="content-panel detail-panel">
        <div className="detail-heading">
          <div>
            <p className="eyebrow">旅行详情</p>
            <h1>
              <button
                aria-label={data.isFavorite ? '取消收藏旅行' : '收藏旅行'}
                className={data.isFavorite ? 'icon-button favorite active' : 'icon-button favorite'}
                disabled={favoriteTrip.isPending}
                onClick={() => {
                  void favoriteTrip.mutateAsync(data.id);
                }}
                type="button"
              >
                ★
              </button>
              {data.title}
            </h1>
          </div>
          <TripStatusBadge status={data.status} />
        </div>
        <dl className="detail-grid">
          <div>
            <dt>目的地</dt>
            <dd>{data.destination || '未设置'}</dd>
          </div>
          <div>
            <dt>日期</dt>
            <dd>{formatDateRange(data.startDate, data.endDate)}</dd>
          </div>
          <div>
            <dt>我的角色</dt>
            <dd>{roleLabels[data.currentUserRole]}</dd>
          </div>
          <div>
            <dt>更新时间</dt>
            <dd>{new Date(data.updatedAt).toLocaleString('zh-CN')}</dd>
          </div>
        </dl>
        <p>{data.description || '暂无描述'}</p>
        <div className="detail-actions">
          {canManage ? (
            <Link className="button-link" to={`/trips/${data.id}/edit`}>
              编辑
            </Link>
          ) : null}
          <Link className="button-link" to={`/trips/${data.id}/itinerary`}>
            行程
          </Link>
          <Link className="button-link" to={`/trips/${data.id}/expenses`}>
            费用
          </Link>
          <Link className="button-link" to={`/trips/${data.id}/photos`}>
            照片
          </Link>
          <Link className="button-link" to={`/trips/${data.id}/documents`}>
            文档
          </Link>
          <Link className="button-link" to={`/trips/${data.id}/packing-lists`}>
            打包
          </Link>
          <Link className="button-link" to={`/trips/${data.id}/journals`}>
            游记
          </Link>
          <Link className="button-link" to={`/trips/${data.id}/map`}>
            地图
          </Link>
          <button
            className="secondary-button"
            onClick={() => setIsMembersOpen((current) => !current)}
            type="button"
          >
            成员
          </button>
          {canManage ? (
            <>
              <button
                className="secondary-button"
                disabled={duplicateTrip.isPending}
                onClick={() => {
                  void duplicateTrip.mutateAsync(data.id);
                }}
                type="button"
              >
                复制
              </button>
              <button
                className="secondary-button"
                disabled={archiveTrip.isPending}
                onClick={() => {
                  void archiveTrip.mutateAsync(data.id);
                  navigate('/', { replace: true });
                }}
                type="button"
              >
                归档
              </button>
              <button
                className="secondary-button danger-button"
                disabled={deleteTrip.isPending}
                onClick={() => setIsDeleteDialogOpen(true)}
                type="button"
              >
                删除
              </button>
            </>
          ) : null}
        </div>
        {isMembersOpen ? (
          <TripMembers currentUserRole={data.currentUserRole} tripId={data.id} />
        ) : null}
      </section>
      <ConfirmDialog
        confirmLabel="删除"
        content={`"${data.title}" 将从旅行列表和详情页中隐藏。`}
        isOpen={isDeleteDialogOpen}
        isPending={deleteTrip.isPending}
        onCancel={() => setIsDeleteDialogOpen(false)}
        onConfirm={() => {
          void handleDelete();
        }}
        title="删除旅行？"
      />
    </main>
  );
}

function formatDateRange(startDate: string | null, endDate: string | null) {
  if (!startDate && !endDate) {
    return '未设置';
  }

  return [startDate?.slice(0, 10), endDate?.slice(0, 10)].filter(Boolean).join(' 至 ');
}
