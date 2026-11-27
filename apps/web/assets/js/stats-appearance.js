/* A small, decorative depth effect; statistics remain in their original plane. */
(function () {
  'use strict';
  var hero = document.querySelector('.stats-hero');
  if (!hero) return;
  var motion = window.matchMedia('(prefers-reduced-motion: reduce)');
  var pointer = window.matchMedia('(hover: hover) and (pointer: fine)');
  var frame = 0;
  var x = 0;
  var y = 0;

  function reset() {
    cancelAnimationFrame(frame);
    frame = 0;
    hero.style.removeProperty('--scene-x');
    hero.style.removeProperty('--scene-y');
  }
  hero.addEventListener('pointermove', function (event) {
    if (motion.matches || !pointer.matches) return;
    var box = hero.getBoundingClientRect();
    x = (event.clientX - box.left) / box.width - 0.5;
    y = (event.clientY - box.top) / box.height - 0.5;
    if (frame) return;
    frame = requestAnimationFrame(function () {
      hero.style.setProperty('--scene-x', (x * 12).toFixed(2) + 'px');
      hero.style.setProperty('--scene-y', (y * 8).toFixed(2) + 'px');
      frame = 0;
    });
  });
  hero.addEventListener('pointerleave', reset);
  motion.addEventListener('change', reset);
  pointer.addEventListener('change', reset);
  window.addEventListener('blur', reset);
}());
