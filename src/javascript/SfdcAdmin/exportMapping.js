/**
 * Client-side export of a single object's field mapping to CSV or JSON.
 *
 * The mapping rows are already loaded in the tab's state ({ jcProperty, sfField } per row),
 * so export is done entirely in the browser (Blob download) - no backend action, no CSRF surface.
 *
 * `direction` is 'inbound' (SFDC -> jCustomer) or 'outbound' (jCustomer -> SFDC); `object` is the
 * Salesforce object name (Contact / Account / Lead). Only complete rows (both fields set) are exported.
 */

// RFC 4180: quote a field if it contains a comma, quote, CR or LF; double any embedded quotes.
const csvCell = v => {
    const s = v == null ? '' : String(v);
    return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};

const completeRows = rows => (Array.isArray(rows) ? rows : [])
    .map(r => ({ jcProperty: (r.jcProperty || '').trim(), sfField: (r.sfField || '').trim() }))
    .filter(r => r.jcProperty && r.sfField);

const baseName = (direction, object) => `sfdc-${direction}-${object}-mapping`;

/** Serialize the given rows to CSV text (header + one line per complete mapping). */
export const toCsv = rows => {
    const lines = ['jcProperty,sfField'];
    completeRows(rows).forEach(r => lines.push(`${csvCell(r.jcProperty)},${csvCell(r.sfField)}`));
    // Trailing newline so POSIX tools treat the last row as a complete line.
    return lines.join('\r\n') + '\r\n';
};

/** Serialize the given rows to a self-describing JSON document. */
export const toJson = (direction, object, rows) => JSON.stringify({
    direction,
    object,
    exportedAt: new Date().toISOString(),
    mappings: completeRows(rows)
}, null, 2);

/** Trigger a browser download of `content` under `filename` with the given MIME type. */
const download = (filename, content, mime) => {
    const blob = new Blob([content], { type: `${mime};charset=utf-8` });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    // Revoke on the next tick so the download has started.
    setTimeout(() => URL.revokeObjectURL(url), 0);
};

/** Export one object's mapping as a CSV file. Returns the number of rows written. */
export const exportMappingCsv = (direction, object, rows) => {
    const complete = completeRows(rows);
    download(`${baseName(direction, object)}.csv`, toCsv(rows), 'text/csv');
    return complete.length;
};

/** Export one object's mapping as a JSON file. Returns the number of rows written. */
export const exportMappingJson = (direction, object, rows) => {
    const complete = completeRows(rows);
    download(`${baseName(direction, object)}.json`, toJson(direction, object, rows), 'application/json');
    return complete.length;
};
