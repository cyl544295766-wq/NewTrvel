/* Travel OS · 高德地图 JS API 加载器（全站共享）
 *
 * Key 与安全密钥集中定义在本文件（前端是零构建的静态站点，没有环境变量注入环节）：
 *   KEY           = 0a940c6e91af319d56bdf040de7c0a5e  （高德控制台中的「Web端(JS API)」类型 Key）
 *   SECURITY_CODE = 1ba465f03ba6bb5d236006572c8d3877  （同一应用的安全密钥）
 * JS API 的安全密钥按高德官方文档即需在前端可见，故直接内联。
 *
 * 注意区分：服务端另有 AMAP_WEB_SERVICE_KEY（「Web服务」类型），仅用于地点联想
 * /api/places/suggestions，与地图展示无关 —— 两种服务类型的 Key 不通用。
 */
(function (global) {
  'use strict';

  var KEY = '0a940c6e91af319d56bdf040de7c0a5e';
  var SECURITY_CODE = '1ba465f03ba6bb5d236006572c8d3877';

  /* 每个行程天的主题色（D1 用品牌湖水青，与全站主色呼应） */
  var DAY_COLORS = ['#0D6E6E', '#C08A2E', '#C2566C', '#2F6FB5', '#7A5EA8', '#5B8C4A'];

  /* 地点类型样式（与 itinerary 页完全一致，跨页同语言） */
  var TYPE_STYLES = {
    attraction: { label: '景点', ic: '◎', c: '#2F6FB5', bg: '#E9F2FD' },
    hotel:      { label: '酒店', ic: '⌂', c: '#B8830F', bg: '#FDF5E2' },
    restaurant: { label: '餐厅', ic: '◔', c: '#C1704A', bg: '#FCECE4' },
    transport:  { label: '交通', ic: '✈', c: '#6B4FC0', bg: '#EFEAFB' },
    shopping:   { label: '购物', ic: '▣', c: '#C2566C', bg: '#FDEFF2' },
    custom:     { label: '自定义', ic: '◆', c: '#2A8055', bg: '#E9F8EE' }
  };

  function typeStyle(v) {
    return TYPE_STYLES[v] || TYPE_STYLES.custom;
  }

  /* ---------- 驾车路线缓存 ----------
   * 高德驾车规划按 QPS 计费且会限流（10021 CUQPS_HAS_EXCEEDED_THE_LIMIT）。
   * 同一组坐标在短期内路线不会变化，因此把成功结果缓存到 localStorage：
   * - 键由坐标序列哈希得到，地点坐标一变自动失效
   * - 30 天过期；总量超过 2.5MB 时按时间淘汰最旧条目
   * - 只缓存成功且有几何的路线，失败结果不缓存
   */
  var ROUTE_CACHE_PREFIX = 'travelos.route.v1.';
  var ROUTE_CACHE_TTL = 30 * 24 * 60 * 60 * 1000;
  var ROUTE_CACHE_MAX_BYTES = 2.5 * 1024 * 1024;
  var ROUTE_CACHE_KEEP_BYTES = 1.5 * 1024 * 1024;

  function routeCacheKey(path) {
    var seed = '';
    for (var i = 0; i < path.length; i++) {
      seed += Number(path[i][0]).toFixed(5) + ',' + Number(path[i][1]).toFixed(5) + ';';
    }
    /* FNV-1a：把整条坐标串压成短键，避免 localStorage key 过长 */
    var h = 2166136261;
    for (var j = 0; j < seed.length; j++) {
      h ^= seed.charCodeAt(j);
      h = Math.imul(h, 16777619) >>> 0;
    }
    return ROUTE_CACHE_PREFIX + h.toString(36) + '-' + path.length + '-' + seed.length;
  }

  function routeCacheGet(path) {
    if (!path || path.length < 2) return null;
    try {
      var key = routeCacheKey(path);
      var raw = localStorage.getItem(key);
      if (!raw) return null;
      var item = JSON.parse(raw);
      if (!item || !item.p || item.p.length < 2 || (Date.now() - item.ts) > ROUTE_CACHE_TTL) {
        localStorage.removeItem(key);
        return null;
      }
      return {
        path: item.p,
        timeSec: Number(item.t) || 0,
        distanceM: Number(item.d) || 0,
        cached: true
      };
    } catch (e) {
      return null;
    }
  }

  function pruneRouteCache() {
    try {
      var keys = [];
      var total = 0;
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (!k || k.indexOf(ROUTE_CACHE_PREFIX) !== 0) continue;
        var raw = localStorage.getItem(k) || '';
        keys.push({ k: k, size: raw.length, ts: 0 });
        total += raw.length;
      }
      if (total <= ROUTE_CACHE_MAX_BYTES) return;
      /* 只有超限时才解析时间戳，避免每次写入都做大量 JSON.parse */
      keys.forEach(function (it) {
        try { it.ts = Number(JSON.parse(localStorage.getItem(it.k)).ts) || 0; } catch (e) {}
      });
      keys.sort(function (a, b) { return a.ts - b.ts; });
      for (var j = 0; j < keys.length && total > ROUTE_CACHE_KEEP_BYTES; j++) {
        try { localStorage.removeItem(keys[j].k); } catch (e) {}
        total -= keys[j].size;
      }
    } catch (e) { /* localStorage 不可用时静默降级 */ }
  }

  function clearRouteCache() {
    try {
      var keys = [];
      for (var i = 0; i < localStorage.length; i++) {
        var k = localStorage.key(i);
        if (k && k.indexOf(ROUTE_CACHE_PREFIX) === 0) keys.push(k);
      }
      keys.forEach(function (k) { localStorage.removeItem(k); });
    } catch (e) {}
  }

  function routeCacheSet(path, result) {
    if (!path || !result || !result.path || result.path.length < 2) return;
    var raw;
    try {
      raw = JSON.stringify({
        p: result.path,
        t: Number(result.timeSec) || 0,
        d: Number(result.distanceM) || 0,
        ts: Date.now()
      });
    } catch (e) { return; }
    /* 单条过大（异常路线）时放弃缓存，避免挤占配额 */
    if (raw.length > ROUTE_CACHE_MAX_BYTES / 2) return;
    try {
      localStorage.setItem(routeCacheKey(path), raw);
      pruneRouteCache();
    } catch (e) {
      /* 配额不足：先清掉本功能缓存，再重试一次 */
      clearRouteCache();
      try { localStorage.setItem(routeCacheKey(path), raw); } catch (_) {}
    }
  }

  var loading = null;

  /** 基础脚本就绪后按需补载插件（带超时保护，避免插件加载失败时 Promise 永不结束） */
  function withPlugins(AMap, plugins) {
    if (!plugins || !plugins.length) return Promise.resolve(AMap);
    return new Promise(function (resolve, reject) {
      var done = false;
      var timer = setTimeout(function () {
        if (done) return;
        done = true;
        reject(new Error('高德插件加载超时：' + plugins.join(',')));
      }, 20000);
      try {
        AMap.plugin(plugins, function () {
          if (done) return;
          done = true;
          clearTimeout(timer);
          resolve(AMap);
        });
      } catch (e) {
        if (!done) { done = true; clearTimeout(timer); reject(e); }
      }
    });
  }

  /**
   * 加载高德地图 JS API（全局单例，重复调用不会重复插脚本）。
   * 始终先加载「不带 plugin 参数」的基础脚本：URL 固定，便于 head 里 <link rel=preload>
   * 提前发起下载（290KB，冷启动实测占关键路径 1–2 秒）；插件随后用 AMap.plugin 按需补载。
   * @param {string[]} plugins 需要的插件，如 ['AMap.Driving']
   * @returns {Promise<any>} resolve(window.AMap)
   */
  function load(plugins) {
    plugins = plugins || [];

    if (global.AMap) {
      return withPlugins(global.AMap, plugins);
    }
    if (loading) {
      /* 基础脚本已在加载：等它完成后按需补插件 */
      return loading.then(function (AMap) { return withPlugins(AMap, plugins); });
    }

    if (!KEY) {
      return Promise.reject(new Error('未配置高德地图 JS API Key'));
    }
    // 安全密钥必须在脚本加载前挂到 window 上
    if (SECURITY_CODE) {
      global._AMapSecurityConfig = { securityJsCode: SECURITY_CODE };
    }

    var src = 'https://webapi.amap.com/maps?v=2.0&key=' + encodeURIComponent(KEY);

    loading = new Promise(function (resolve, reject) {
      var script = document.createElement('script');
      script.async = true;
      script.src = src;
      script.onload = function () {
        if (global.AMap) resolve(global.AMap);
        else reject(new Error('高德地图脚本加载异常（多半是 Key 域名白名单限制）'));
      };
      script.onerror = function () {
        loading = null;
        reject(new Error('高德地图网络连接失败，请检查网络'));
      };
      document.head.appendChild(script);
    });

    /* 失败后清空 loading，允许下一次调用重试 */
    loading.catch(function () { loading = null; });

    return loading.then(function (AMap) { return withPlugins(AMap, plugins); });
  }

  global.TravelOSAmap = {
    load: load,
    KEY: KEY,
    securityCode: SECURITY_CODE,
    DAY_COLORS: DAY_COLORS,
    TYPE_STYLES: TYPE_STYLES,
    typeStyle: typeStyle,
    dayColor: function (i) { return DAY_COLORS[i % DAY_COLORS.length]; },
    routeCache: {
      get: routeCacheGet,
      set: routeCacheSet,
      clear: clearRouteCache
    }
  };
})(window);
