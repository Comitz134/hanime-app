// rails.js
//
// Cut from the old single-file client: the body below is original source,
// a block or two moved in with its concern when the split happened. The import
// block at the top and the export list at the bottom were generated
// mechanically once — they are ordinary code now, not something a tool
// rebuilds.


/* ------------------------------------------------------------------ drag rails */

function makeDraggable(rail) {
  let down = false, startX = 0, startScroll = 0, moved = 0;
  rail.addEventListener('pointerdown', (e) => {
    if (e.pointerType === 'touch') return;   // native touch scroll is better
    down = true; moved = 0; startX = e.clientX; startScroll = rail.scrollLeft;
    rail.classList.add('dragging');
  });
  rail.addEventListener('pointermove', (e) => {
    if (!down) return;
    const dx = e.clientX - startX;
    moved = Math.abs(dx);
    rail.scrollLeft = startScroll - dx;
  });
  const up = () => { down = false; rail.classList.remove('dragging'); };
  rail.addEventListener('pointerup', up);
  rail.addEventListener('pointerleave', up);
  rail.addEventListener('pointercancel', up);
  // suppress the click that ends a drag, or grabbing a rail would open a card
  rail.addEventListener('click', (e) => { if (moved > 6) { e.preventDefault(); e.stopPropagation(); } }, true);
}

export { makeDraggable };
