/* 首页创建流程与行程详情共享本标签页的规划进度。 */
(function (global) {
  'use strict';

  function key() {
    var user = getCurrentUser();
    if (!user || !user.id) throw new Error('请重新登录后发起 AI 规划。');
    return 'travelos.ai.planning.' + user.id;
  }

  function read() {
    try {
      return JSON.parse(sessionStorage.getItem(key()) || 'null');
    } catch (err) {
      return null;
    }
  }

  function save(plan) {
    var name = key();
    try {
      sessionStorage.setItem(name, JSON.stringify(plan));
    } catch (err) {
      throw new Error('浏览器无法保存规划进度，请开启站点存储后重试。');
    }
  }

  function clear() {
    sessionStorage.removeItem(key());
  }

  global.TravelOSPlanning = { read: read, save: save, clear: clear };
})(window);
