/**
 * Client-side export of a single object's field mapping to CSV or JSON.
 *
 * The mapping rows are already loaded in the tab's state ({ jcProperty, sfField, defaultValue? } per row),
 * so export is done entirely in the browser (Blob download) - no backend action, no CSRF surface.
 *
 * `direction` is 'inbound' (SFDC -> jCustomer) or 'outbound' (jCustomer -> SFDC); `object` is the
 * Salesforce object name (Contact / Account / Lead). Only complete rows are exported: a Salesforce field
 * fed by a property, a default value (outbound), or both.
 */

// RFC 4180: quote a field if it contains a comma, quote, CR or LF; double any embedded quotes.
export const csvCell = v => {
    const s = v == null ? '' : String(v);
    return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};

const completeRows = rows => (Array.isArray(rows) ? rows : [])
    .map(r => ({
        jcProperty: (r.jcProperty || '').trim(),
        sfField: (r.sfField || '').trim(),
        defaultValue: (r.defaultValue || '').trim()
    }))
    .filter(r => r.sfField && (r.jcProperty || r.defaultValue));

/** Rows as exported: defaultValue only when the direction has default values (any row sets one). */
const exported = rows => {
    const complete = completeRows(rows);
    const withDefaults = complete.some(r => r.defaultValue);
    return {
        withDefaults,
        rows: complete.map(r => (withDefaults ? r : { jcProperty: r.jcProperty, sfField: r.sfField }))
    };
};

const baseName = (direction, object) => `sfdc-${direction}-${object}-mapping`;

/** Serialize the given rows to CSV text (header + one line per complete mapping). */
export const toCsv = rows => {
    const { withDefaults, rows: out } = exported(rows);
    const lines = [withDefaults ? 'jcProperty,sfField,defaultValue' : 'jcProperty,sfField'];
    out.forEach(r => lines.push([r.jcProperty, r.sfField, ...(withDefaults ? [r.defaultValue] : [])].map(csvCell).join(',')));
    // Trailing newline so POSIX tools treat the last row as a complete line.
    return lines.join('\r\n') + '\r\n';
};

/** Serialize the given rows to a self-describing JSON document. */
export const toJson = (direction, object, rows) => JSON.stringify({
    direction,
    object,
    exportedAt: new Date().toISOString(),
    mappings: exported(rows).rows
}, null, 2);

/** Trigger a browser download of `content` under `filename` with the given MIME type. */
export const download = (filename, content, mime) => {
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
