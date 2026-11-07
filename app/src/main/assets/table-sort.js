// Default sorting for HTML applet tables. A template may supply its own header controls.
(function () {
 if (window.__bridgitTables) return;
 window.__bridgitTables = true;
 function order(table, column, descending) {
  Array.from(table.tBodies).forEach(function (body) {
   var rows = Array.from(body.children).filter(function (row) { return row.tagName === 'TR'; });
   function value(row) {
    var cell = row.cells[column], raw = cell ? (cell.dataset.sortValue ?? cell.textContent.trim()) : '';
    var type = table.querySelectorAll('thead th')[column]?.dataset.sortType;
    if (type === 'date') { var at = Date.parse(raw); return Number.isNaN(at) ? null : at; }
    if (!raw || raw === '—') return null;
    var number = raw.replace(/[$€£,%\s,]/g, '');
    return number && Number.isFinite(Number(number)) ? Number(number) : raw;
   }
   var sorted = rows.slice().sort(function (a, b) {
    var x = value(a), y = value(b);
    if (x == null) return y == null ? 0 : 1;
    if (y == null) return -1;
    var result = typeof x === 'number' && typeof y === 'number' ? x - y : String(x).localeCompare(String(y), undefined, {numeric: true, sensitivity: 'base'});
    return descending ? -result : result;
   });
   if (rows.some(function (row, i) { return row !== sorted[i]; })) sorted.forEach(function (row) { body.appendChild(row); });
  });
 }
 function enhance() {
  document.querySelectorAll('table').forEach(function (table) {
   if (table.closest('[data-bridgit-sort="off"]')) return;
   table.querySelectorAll('thead th').forEach(function (header, index) {
    if (header.querySelector('button')) return;
    var label = header.textContent, button = document.createElement('button');
    button.type = 'button'; button.textContent = label;
    button.style.cssText = 'font:inherit;color:inherit;background:transparent;border:0;cursor:pointer;min-height:44px;text-align:inherit';
    header.replaceChildren(button); header.setAttribute('aria-sort', 'none');
    button.onclick = function () {
     var descending = header.getAttribute('aria-sort') === 'ascending';
     table.querySelectorAll('thead th').forEach(function (other) { other.setAttribute('aria-sort', 'none'); });
     header.setAttribute('aria-sort', descending ? 'descending' : 'ascending');
     button.textContent = label + (descending ? ' ↓' : ' ↑');
     table.querySelectorAll('thead th').forEach(function (other) { if (other !== header) { var b = other.querySelector('button'); if (b) b.textContent = b.textContent.replace(/ [↑↓]$/, ''); } });
     table.__bridgitSort = {column:index, descending:descending};
     order(table, index, descending);
    };
   });
   if (table.__bridgitSort) order(table, table.__bridgitSort.column, table.__bridgitSort.descending);
  });
 }
 new MutationObserver(enhance).observe(document.body, {childList:true, subtree:true, characterData:true});
 enhance();
})();
