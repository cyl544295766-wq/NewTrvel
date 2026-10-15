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

  var loading = null;

  /**
   * 加载高德地图 JS API（全局单例，重复调用不会重复插脚本）。
   * @param {string[]} plugins 需要的插件，如 ['AMap.Driving']
   * @returns {Promise<any>} resolve(window.AMap)
   */
  function load(plugins) {
    plugins = plugins || [];

    if (global.AMap) {
      if (!plugins.length) return Promise.resolve(global.AMap);
      // 脚本已在但缺插件：用官方 plugin 接口按需补载
      return new Promise(function (resolve, reject) {
        global.AMap.plugin(plugins, function () { resolve(global.AMap); });
        if (global.AMap.on) global.AMap.on('error', reject);
      });
    }
    if (loading) return loading;

    if (!KEY) {
      return Promise.reject(new Error('未配置高德地图 JS API Key'));
    }
    // 安全密钥必须在脚本加载前挂到 window 上
    if (SECURITY_CODE) {
      global._AMapSecurityConfig = { securityJsCode: SECURITY_CODE };
    }

    var src = 'https://webapi.amap.com/maps?v=2.0&key=' + encodeURIComponent(KEY);
    if (plugins.length) {
      src += '&plugin=' + plugins.map(encodeURIComponent).join(',');
    }

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
    return loading;
  }

  global.TravelOSAmap = {
    load: load,
    KEY: KEY,
    securityCode: SECURITY_CODE,
    DAY_COLORS: DAY_COLORS,
    TYPE_STYLES: TYPE_STYLES,
    typeStyle: typeStyle,
    dayColor: function (i) { return DAY_COLORS[i % DAY_COLORS.length]; }
  };
})(window);
