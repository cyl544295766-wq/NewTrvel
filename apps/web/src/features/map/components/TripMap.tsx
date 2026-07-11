import { LocateFixed, MapPinned, Minus, Plus, X } from 'lucide-react';
import { useEffect, useMemo, useRef, useState } from 'react';
import { AMapMap, AMapNamespace, AMapOverlay, loadAmap } from '../amap/amap-loader';
import { TripRouteDay, TripRoutePlace, TripRoutePlaceType } from '../types/map.types';

export const dayColors = ['#b45b4d', '#3f6f66', '#b28a45', '#62708d', '#88677b', '#65764f'];
const typeLabels: Record<TripRoutePlaceType, string> = { attraction: '景点', hotel: '酒店', restaurant: '餐厅', transport: '交通', shopping: '购物', custom: '其他' };

type Props = {
  activeDayId: string | null;
  onSelectPlace: (placeId: string | null) => void;
  route: TripRouteDay[];
  selectedPlaceId: string | null;
};

type MappedPlace = TripRoutePlace & { position: [number, number] };
type MappedDay = Omit<TripRouteDay, 'places'> & { places: MappedPlace[] };

export function TripMap({ activeDayId, onSelectPlace, route, selectedPlaceId }: Props) {
  const mapRef = useRef<AMapMap | null>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  const [mapApi, setMapApi] = useState<AMapNamespace | null>(null);
  const [mapError, setMapError] = useState<string | null>(null);
  const routeDays = useMemo<MappedDay[]>(
    () => route.map((day) => ({ ...day, places: day.places.filter(hasValidCoordinates).sort((a, b) => a.sortOrder - b.sortOrder).map((place) => ({ ...place, position: [Number(place.longitude), Number(place.latitude)] as [number, number] })) })).filter((day) => day.places.length > 0),
    [route],
  );
  const places = useMemo(() => routeDays.flatMap((day) => day.places.map((place) => ({ day, place }))), [routeDays]);
  const selected = places.find(({ place }) => place.id === selectedPlaceId);

  useEffect(() => {
    let cancelled = false;
    loadAmap().then((api) => { if (!cancelled) setMapApi(api); }).catch((error: Error) => { if (!cancelled) setMapError(error.message); });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    if (!mapApi || !containerRef.current || mapRef.current) return;
    const map = new mapApi.Map(containerRef.current, { center: places[0]?.place.position ?? [116.3974, 39.9093], zoom: 12, resizeEnable: true });
    mapRef.current = map;
    return () => { map.destroy(); mapRef.current = null; };
  }, [mapApi, places]);

  useEffect(() => {
    const map = mapRef.current;
    if (!map || !mapApi) return;
    map.clearMap();
    const overlays: AMapOverlay[] = [];
    routeDays.forEach((day) => {
      const color = dayColors[(day.dayIndex - 1) % dayColors.length];
      const path = day.places.map((place) => place.position);
      if (path.length > 1) {
        overlays.push(new mapApi.Polyline({ path, strokeColor: '#ffffff', strokeWeight: 8, strokeOpacity: 0.84, lineJoin: 'round', lineCap: 'round', zIndex: 30 }));
        overlays.push(new mapApi.Polyline({ path, strokeColor: color, strokeWeight: 4, strokeOpacity: 1, lineJoin: 'round', lineCap: 'round', zIndex: 31 }));
      }
      day.places.forEach((place) => {
        const marker = new mapApi.Marker({ anchor: 'bottom-center', content: createMarkerContent(color, place.sortOrder + 1, place.type, place.id === selectedPlaceId), extData: { placeId: place.id }, offset: new mapApi.Pixel(-18, -38), position: place.position, title: `${place.name}，第 ${day.dayIndex} 天第 ${place.sortOrder + 1} 站` });
        marker.on?.('click', () => onSelectPlace(place.id));
        overlays.push(marker);
      });
    });
    map.add(overlays);
    if (selected) map.setZoomAndCenter(Math.max(map.getZoom(), 14), selected.place.position);
    else if (overlays.length) map.setFitView(overlays, false, [56, 56, 56, 56]);
  }, [mapApi, onSelectPlace, routeDays, selected, selectedPlaceId]);

  if (mapError) return <div className="map-empty-canvas"><MapPinned size={34} /><h2>高德地图加载失败</h2><p>{mapError}，请检查 VITE_AMAP_MAP_KEY 或网络连接。</p></div>;
  if (!routeDays.length) return <div className="map-empty-canvas"><MapPinned size={34} /><h2>还没有可显示的地点</h2><p>为行程地点添加坐标后，它们会出现在这里。</p></div>;

  return (
    <div className="trip-map-shell map-workspace-canvas">
      <div className="trip-map amap-container" ref={containerRef} />
      <div className="map-floating-controls"><button aria-label="适应全部点位" onClick={() => mapRef.current?.setFitView()} title="适应全部点位" type="button"><LocateFixed size={18} /></button><button aria-label="放大地图" onClick={() => mapRef.current?.zoomIn()} title="放大" type="button"><Plus size={18} /></button><button aria-label="缩小地图" onClick={() => mapRef.current?.zoomOut()} title="缩小" type="button"><Minus size={18} /></button></div>
      {selected ? <article className="map-place-detail"><button aria-label="关闭地点详情" onClick={() => onSelectPlace(null)} title="关闭" type="button"><X size={17} /></button><span style={{ color: dayColors[(selected.day.dayIndex - 1) % dayColors.length] }}>DAY {selected.day.dayIndex} · STOP {selected.place.sortOrder + 1}</span><h2>{selected.place.name}</h2><p>{typeLabels[selected.place.type]}{selected.place.startTime ? ` · ${selected.place.startTime}` : ''}</p>{selected.place.address ? <address>{selected.place.address}</address> : null}{selected.place.notes ? <small>{selected.place.notes}</small> : null}</article> : null}
      <div className="map-day-legend" aria-label="路线图例">{routeDays.map((day) => <span className={!activeDayId || activeDayId === day.dayId ? 'is-active' : ''} key={day.dayId}><i style={{ background: dayColors[(day.dayIndex - 1) % dayColors.length] }} />D{day.dayIndex}</span>)}</div>
    </div>
  );
}

function createMarkerContent(color: string, label: number, type: TripRoutePlaceType, selected: boolean) {
  return `<span class="route-marker route-marker-${type}${selected ? ' is-selected' : ''}" style="--marker-color:${color}"><b>${label}</b></span>`;
}

export function hasValidCoordinates(place: Pick<TripRoutePlace, 'latitude' | 'longitude'>) {
  if (!place.latitude?.trim() || !place.longitude?.trim()) return false;
  const latitude = Number(place.latitude); const longitude = Number(place.longitude);
  return Number.isFinite(latitude) && Number.isFinite(longitude) && latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
}
