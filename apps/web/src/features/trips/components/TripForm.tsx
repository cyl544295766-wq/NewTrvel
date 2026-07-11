import { ChangeEvent, FormEvent, useMemo, useRef, useState } from 'react';
import {
  ArrowLeft,
  ArrowRight,
  CalendarDays,
  Check,
  ImagePlus,
  MapPin,
  Sparkles,
  Upload,
  WalletCards,
} from 'lucide-react';
import { Trip, TripStatus, TripUpdateInput } from '../types/trip.types';

type TripFormProps = {
  trip?: Trip;
  includeStatus?: boolean;
  isSubmitting: boolean;
  mode?: 'standard' | 'editorial';
  onDestinationChange?: (destination: string) => void;
  onSubmit: (input: TripUpdateInput) => Promise<void>;
};

type FormErrors = Partial<Record<'title' | 'destination' | 'dates' | 'cover' | 'submit', string>>;

const statuses: TripStatus[] = ['draft', 'planning', 'active', 'completed', 'archived'];
const destinations = ['京都', '冰岛', '云南大理', '巴黎', '东京', '巴厘岛', '罗马', '清迈'];
const steps = ['基本信息', '时间', '封面与预算', '确认'];

export function TripForm({
  trip,
  includeStatus = false,
  isSubmitting,
  mode = 'standard',
  onDestinationChange,
  onSubmit,
}: TripFormProps) {
  const [title, setTitle] = useState(trip?.title ?? '');
  const [description, setDescription] = useState(trip?.description ?? '');
  const [destination, setDestination] = useState(trip?.destination ?? '');
  const [startDate, setStartDate] = useState(toDateInput(trip?.startDate));
  const [endDate, setEndDate] = useState(toDateInput(trip?.endDate));
  const [status, setStatus] = useState<TripStatus>(trip?.status ?? 'draft');
  const [step, setStep] = useState(0);
  const [furthestStep, setFurthestStep] = useState(0);
  const [coverImageUrl, setCoverImageUrl] = useState(trip?.coverImageUrl ?? '');
  const [budget, setBudget] = useState(trip?.budget ?? '');
  const [perPerson, setPerPerson] = useState(false);
  const [errors, setErrors] = useState<FormErrors>({});
  const [showSuggestions, setShowSuggestions] = useState(false);
  const uploadRef = useRef<HTMLInputElement>(null);

  const filteredDestinations = destinations.filter(
    (item) => item.includes(destination.trim()) && item !== destination.trim(),
  );
  const dayCount = getDayCount(startDate, endDate);
  const covers = useMemo(
    () =>
      Array.from({ length: 6 }, (_, index) =>
        getCoverUrl(destination || 'curated-travel', index + 1),
      ),
    [destination],
  );

  function updateDestination(value: string) {
    const previousDefaultTitle = destination ? `${destination}之旅` : '';
    setDestination(value);
    onDestinationChange?.(value);
    if (!title || title === previousDefaultTitle) setTitle(value ? `${value}之旅` : '');
    setErrors((current) => ({ ...current, destination: undefined }));
    setShowSuggestions(true);
  }

  function validateStep(index: number) {
    const nextErrors: FormErrors = {};
    if (index === 0) {
      if (!destination.trim()) nextErrors.destination = '请输入这次旅行的目的地';
      if (!title.trim()) nextErrors.title = '请为旅行写一个标题';
    }
    if (index === 1) {
      if (!startDate || !endDate) nextErrors.dates = '请选择完整的出发和结束日期';
      else if (new Date(endDate) < new Date(startDate)) nextErrors.dates = '结束日期不能早于出发日期';
    }
    if (index === 2 && !coverImageUrl) nextErrors.cover = '请选择一张旅行封面';
    setErrors(nextErrors);
    return Object.keys(nextErrors).length === 0;
  }

  function goNext() {
    if (!validateStep(step)) return;
    const nextStep = Math.min(step + 1, steps.length - 1);
    setStep(nextStep);
    setFurthestStep((current) => Math.max(current, nextStep));
  }

  function goToStep(index: number) {
    if (index <= furthestStep) setStep(index);
  }

  function handleUpload(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    if (!file) return;
    setCoverImageUrl(URL.createObjectURL(file));
    setErrors((current) => ({ ...current, cover: undefined }));
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (mode === 'editorial' && (step !== 3 || !validateStep(2))) return;
    setErrors({});
    try {
      await onSubmit({
        title: title.trim(),
        description: description.trim() || undefined,
        destination: destination.trim() || undefined,
        startDate: startDate || undefined,
        endDate: endDate || undefined,
        coverImageUrl: coverImageUrl.startsWith('blob:') ? undefined : coverImageUrl || undefined,
        budget: budget || undefined,
        status: includeStatus ? status : undefined,
      });
    } catch {
      setErrors({ submit: '创建旅行时出现问题，请稍后再试' });
    }
  }

  if (mode === 'standard') {
    return (
      <form className="trip-form" onSubmit={handleSubmit}>
        <label><span>标题</span><input maxLength={80} onChange={(event) => setTitle(event.target.value)} required value={title} /></label>
        <label><span>目的地</span><input maxLength={120} onChange={(event) => setDestination(event.target.value)} value={destination} /></label>
        <div className="form-grid">
          <label><span>开始日期</span><input onChange={(event) => setStartDate(event.target.value)} type="date" value={startDate} /></label>
          <label><span>结束日期</span><input min={startDate} onChange={(event) => setEndDate(event.target.value)} type="date" value={endDate} /></label>
        </div>
        {includeStatus ? <label><span>状态</span><select onChange={(event) => setStatus(event.target.value as TripStatus)} value={status}>{statuses.map((item) => <option key={item} value={item}>{item}</option>)}</select></label> : null}
        <label><span>描述</span><textarea maxLength={1000} onChange={(event) => setDescription(event.target.value)} rows={5} value={description} /></label>
        {errors.submit ? <p className="form-error">{errors.submit}</p> : null}
        <button disabled={isSubmitting} type="submit">{isSubmitting ? '保存中...' : '保存旅行'}</button>
      </form>
    );
  }

  return (
    <form className="trip-form trip-form-editorial" onSubmit={handleSubmit}>
      <nav className="trip-stepper" aria-label="创建旅行步骤">
        {steps.map((label, index) => (
          <button className={index === step ? 'is-current' : index < step ? 'is-complete' : ''} disabled={index > furthestStep} key={label} onClick={() => goToStep(index)} type="button">
            <span>{index < step ? <Check size={13} /> : index + 1}</span>{label}
          </button>
        ))}
      </nav>

      <div className="trip-form-stage" key={step}>
        {step === 0 ? (
          <section aria-labelledby="trip-basics-title">
            <header className="trip-stage-heading"><span>01 / 04</span><h2 id="trip-basics-title">从一个地方开始想象</h2><p>写下你此刻最想抵达的地方，其余细节可以慢慢展开。</p></header>
            <div className="editorial-field destination-field">
              <label htmlFor="trip-destination">目的地</label>
              <div className="editorial-input-wrap"><input autoComplete="off" id="trip-destination" maxLength={120} onBlur={() => setTimeout(() => setShowSuggestions(false), 150)} onChange={(event) => updateDestination(event.target.value)} onFocus={() => setShowSuggestions(true)} placeholder="例如：京都、冰岛、云南大理" value={destination} />{destination ? <span className="destination-badge"><MapPin size={15} />{destination}</span> : null}</div>
              {showSuggestions && filteredDestinations.length ? <div className="destination-suggestions" role="listbox">{filteredDestinations.map((item) => <button key={item} onClick={() => { updateDestination(item); setShowSuggestions(false); }} type="button"><MapPin size={15} /><span>{item}</span><small>热门目的地</small></button>)}</div> : null}
              {errors.destination ? <p className="field-error">{errors.destination}</p> : null}
            </div>
            <div className="editorial-field"><label htmlFor="trip-title">旅行标题</label><input id="trip-title" maxLength={80} onChange={(event) => { setTitle(event.target.value); setErrors((current) => ({ ...current, title: undefined })); }} placeholder="给这次旅行一个名字" value={title} />{errors.title ? <p className="field-error">{errors.title}</p> : null}</div>
            <div className="editorial-field"><label htmlFor="trip-description">旅行寄语</label><textarea id="trip-description" maxLength={1000} onChange={(event) => setDescription(event.target.value)} placeholder="写下你对这次旅行的期待..." rows={3} value={description} /></div>
          </section>
        ) : null}

        {step === 1 ? (
          <section aria-labelledby="trip-dates-title">
            <header className="trip-stage-heading"><span>02 / 04</span><h2 id="trip-dates-title">为旅程留出时间</h2><p>日期确定之后，一段模糊的向往就有了清晰的轮廓。</p></header>
            <div className="editorial-date-grid">
              <div className="editorial-field"><label htmlFor="trip-start">出发日期</label><div className="date-input-wrap"><CalendarDays size={18} /><input id="trip-start" onChange={(event) => { setStartDate(event.target.value); setErrors({}); }} type="date" value={startDate} /></div></div>
              <div className="editorial-field"><label htmlFor="trip-end">结束日期</label><div className="date-input-wrap"><CalendarDays size={18} /><input id="trip-end" min={startDate} onChange={(event) => { setEndDate(event.target.value); setErrors({}); }} type="date" value={endDate} /></div></div>
            </div>
            {errors.dates ? <p className="field-error">{errors.dates}</p> : null}
            <div className="trip-duration"><span>行程长度</span><strong>{dayCount || '—'}</strong><em>天</em><p>{dayCount ? '足够留下一些不赶时间的空白。' : '选择日期后自动计算'}</p></div>
          </section>
        ) : null}

        {step === 2 ? (
          <section aria-labelledby="trip-cover-title">
            <header className="trip-stage-heading"><span>03 / 04</span><h2 id="trip-cover-title">选择这段旅程的封面</h2><p>它会成为你每次打开计划时，最先看见的画面。</p></header>
            <div className="cover-grid">
              {covers.map((url, index) => <button aria-label={`选择封面 ${index + 1}`} className={coverImageUrl === url ? 'is-selected' : ''} key={url} onClick={() => { setCoverImageUrl(url); setErrors((current) => ({ ...current, cover: undefined })); }} type="button"><img alt={`${destination || '旅行'}封面候选 ${index + 1}`} src={url} />{coverImageUrl === url ? <span><Check size={15} /></span> : null}</button>)}
              <button className={`cover-upload ${coverImageUrl.startsWith('blob:') ? 'is-selected' : ''}`} onClick={() => uploadRef.current?.click()} type="button"><Upload size={22} /><span>上传自己的封面</span><small>JPG 或 PNG</small></button>
              <input accept="image/jpeg,image/png" hidden onChange={handleUpload} ref={uploadRef} type="file" />
            </div>
            {errors.cover ? <p className="field-error">{errors.cover}</p> : null}
            <div className="budget-row">
              <div className="editorial-field budget-field"><label htmlFor="trip-budget">旅行预算</label><div className="budget-input-wrap"><span>¥</span><input id="trip-budget" inputMode="decimal" min="0" onChange={(event) => setBudget(event.target.value)} placeholder="0" type="number" value={budget} /></div></div>
              <label className="per-person-toggle"><input checked={perPerson} onChange={(event) => setPerPerson(event.target.checked)} type="checkbox" /><span aria-hidden="true" /><div><strong>人均预算</strong><small>以每位旅行者计算</small></div></label>
            </div>
          </section>
        ) : null}

        {step === 3 ? (
          <section aria-labelledby="trip-confirm-title">
            <header className="trip-stage-heading"><span>04 / 04</span><h2 id="trip-confirm-title">你的旅行计划册，准备好了</h2><p>最后检查一次。创建后仍然可以随时修改这些信息。</p></header>
            <article className="trip-summary-card">
              <div className="trip-summary-cover">{coverImageUrl ? <img alt={`${destination}旅行封面`} src={coverImageUrl} /> : <ImagePlus size={28} />}</div>
              <div className="trip-summary-content"><span><MapPin size={14} />{destination}</span><h3>{title}</h3><p>{description || '一段值得期待的旅程。'}</p><dl><div><dt><CalendarDays size={15} />日期</dt><dd>{formatDateRange(startDate, endDate)} · {dayCount} 天</dd></div><div><dt><WalletCards size={15} />预算</dt><dd>{budget ? `¥${Number(budget).toLocaleString('zh-CN')}${perPerson ? ' / 人' : ''}` : '暂未设定'}</dd></div></dl></div>
            </article>
            {errors.submit ? <p className="form-error">{errors.submit}</p> : null}
          </section>
        ) : null}
      </div>

      <aside className="trip-live-preview" aria-label="实时行程预览"><div><Sparkles size={14} /><span>实时预览</span></div><strong>{title || '未命名的旅行'}</strong><p>{destination || '等待一个目的地'}{dayCount ? ` · ${dayCount} 天` : ''}</p></aside>

      <footer className="trip-form-actions">
        <button className="trip-back-button" disabled={step === 0 || isSubmitting} onClick={() => setStep((current) => Math.max(0, current - 1))} type="button"><ArrowLeft size={17} />返回{step === 3 ? '修改' : '上一步'}</button>
        {step < 3 ? <button className="trip-next-button" onClick={goNext} type="button">继续<ArrowRight size={17} /></button> : <button className="trip-create-button" disabled={isSubmitting} type="submit">{isSubmitting ? '正在创建...' : '创建旅行'}<ArrowRight size={17} /></button>}
      </footer>
    </form>
  );
}

function toDateInput(value?: string | null) { return value ? value.slice(0, 10) : ''; }
function getDayCount(startDate: string, endDate: string) { if (!startDate || !endDate) return 0; return Math.max(0, Math.round((new Date(`${endDate}T00:00:00`).getTime() - new Date(`${startDate}T00:00:00`).getTime()) / 86400000) + 1); }
function getCoverUrl(destination: string, index: number) { return `https://picsum.photos/seed/${encodeURIComponent(`${destination}-${index}`)}/800/600`; }
function formatDateRange(startDate: string, endDate: string) { if (!startDate || !endDate) return '日期待定'; const formatter = new Intl.DateTimeFormat('zh-CN', { month: 'short', day: 'numeric' }); return `${formatter.format(new Date(`${startDate}T00:00:00`))} — ${formatter.format(new Date(`${endDate}T00:00:00`))}`; }
