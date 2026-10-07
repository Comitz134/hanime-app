// hero.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.

import { $, $$, esc, fmtCount, state } from './core.js';
import { views } from './views.js';

/* ------------------------------------------------------------------ hero */

function heroSlideHtml(v) {
  const tags = (v.tags ?? []).slice(0, 4);
  return `
    <div class="hero-hairline"></div>
    <div class="hero-bg"><img src="${esc(v.cover ?? '')}" alt="" fetchpriority="high"></div>
    <div class="hero-scrim"></div>
    <div class="hero-body">
      <div class="hero-kicker"><span class="hero-dash"></span><span>${esc(v.brand ?? 'Featured')}</span></div>
      <h2 class="hero-title">${esc(v.name)}</h2>
      <div class="hero-tags">
        ${tags.map((t) => `<span class="hero-tag">${esc(t)}</span>`).join('')}
        <span class="hero-tag">${fmtCount(v.views)} views</span>
      </div>
      <div class="hero-cta">
        <button class="pill pill-primary" data-open="${esc(v.slug)}">
          <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor"><path d="M8 5v14l11-7z"/></svg>
          Play
        </button>
        <button class="pill pill-ghost" data-open="${esc(v.slug)}">Details</button>
      </div>
    </div>`;
}

let heroTimer;
function mountHero() {
  const [a, b] = [$('#hero-a'), $('#hero-b')];
  [a, b].forEach((el, i) => { el.innerHTML = heroSlideHtml(state.featured[i]); });
  $('#hero-dots').innerHTML = state.featured
    .map((_, i) => `<button class="hero-dot" data-i="${i}" data-active="${i === 0}" aria-label="Slide ${i + 1}"></button>`).join('');

  const show = (i) => {
    state.slide = i;
    a.dataset.active = String(i === 0);
    b.dataset.active = String(i === 1);
    $$('#hero-dots .hero-dot').forEach((d) => { d.dataset.active = String(Number(d.dataset.i) === i); });
  };
  show(0);

  const cycle = () => {
    clearInterval(heroTimer);
    if (state.featured.length < 2) return;
    heroTimer = setInterval(() => show((state.slide + 1) % 2), 8200);
  };
  cycle();

  $('#hero-dots').onclick = (e) => {
    const d = e.target.closest('.hero-dot');
    if (!d) return;
    show(Number(d.dataset.i));
    cycle();
  };
}

export { heroSlideHtml, heroTimer, mountHero };
