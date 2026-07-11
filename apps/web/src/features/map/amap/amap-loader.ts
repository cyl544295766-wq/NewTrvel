type AMapSecurityConfig = { securityJsCode?: string };

type AMapLoaderWindow = Window & {
  AMap?: AMapNamespace;
  _AMapSecurityConfig?: AMapSecurityConfig;
};

export type AMapNamespace = {
  Map: new (container: HTMLElement, options?: AMapMapOptions) => AMapMap;
  Marker: new (options: AMapMarkerOptions) => AMapOverlay;
  Polyline: new (options: AMapPolylineOptions) => AMapOverlay;
  Pixel: new (x: number, y: number) => unknown;
};

export type AMapMap = {
  add: (overlays: AMapOverlay | AMapOverlay[]) => void;
  clearMap: () => void;
  destroy: () => void;
  getZoom: () => number;
  on: (event: string, handler: (event: unknown) => void) => void;
  remove: (overlays: AMapOverlay | AMapOverlay[]) => void;
  setFitView: (overlays?: AMapOverlay[], immediately?: boolean, padding?: number[]) => void;
  setZoomAndCenter: (zoom: number, center: [number, number]) => void;
  setZoom: (zoom: number) => void;
  zoomIn: () => void;
  zoomOut: () => void;
};

export type AMapOverlay = {
  on?: (event: string, handler: (event: unknown) => void) => void;
  setMap?: (map: AMapMap | null) => void;
};

type AMapMapOptions = {
  center: [number, number];
  zoom: number;
  zooms?: [number, number];
  resizeEnable?: boolean;
};

type AMapMarkerOptions = {
  position: [number, number];
  content: string;
  offset?: unknown;
  anchor?: string;
  title?: string;
  extData?: { placeId: string };
};

type AMapPolylineOptions = {
  path: [number, number][];
  strokeColor: string;
  strokeWeight: number;
  strokeOpacity: number;
  lineJoin?: string;
  lineCap?: string;
  zIndex?: number;
};

let amapPromise: Promise<AMapNamespace> | null = null;

export function loadAmap() {
  if (typeof window === 'undefined') return Promise.reject(new Error('地图只能在浏览器中加载'));
  const amapWindow = window as AMapLoaderWindow;
  if (amapWindow.AMap) return Promise.resolve(amapWindow.AMap);
  if (amapPromise) return amapPromise;

  const key = import.meta.env.VITE_AMAP_MAP_KEY?.trim();
  const securityCode = import.meta.env.VITE_AMAP_SECURITY_CODE?.trim();
  if (!key) return Promise.reject(new Error('未配置高德地图 JS API Key'));

  amapWindow._AMapSecurityConfig = securityCode ? { securityJsCode: securityCode } : undefined;
  amapPromise = new Promise<AMapNamespace>((resolve, reject) => {
    const script = document.createElement('script');
    script.async = true;
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`;
    script.onload = () => amapWindow.AMap ? resolve(amapWindow.AMap) : reject(new Error('高德地图脚本加载失败'));
    script.onerror = () => reject(new Error('高德地图网络连接失败'));
    document.head.appendChild(script);
  }).catch((error) => {
    amapPromise = null;
    throw error;
  });
  return amapPromise;
}
