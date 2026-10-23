/**
 * Client-side export of a sync direction's history to CSV or JSON.
 *
 * `runs` come from getSyncHistory(direction, includeRecords) - each run with its `records`.
 * CSV is flat: one line per record, repeating its run's columns (a run without records gets one
 * line with empty record columns). JSON keeps the run -> records structure.
 */
import { csvCell, download } from './exportMapping';

const HEADER = ['direction', 'runId', 'startedAt', 'finishedAt', 'trigger', 'status',
    'created', 'updated', 'skipped', 'errors', 'truncated',
    'outcome', 'who', 'profileId', 'sfObject', 'sfId', 'fields', 'message'];

const stamp = () => new Date().toISOString().slice(0, 19).replace(/[:T]/g, '-');
const baseName = direction => `sfdc-${direction}-history-${stamp()}`;

/** Serialize runs (with records) to CSV text: header + one line per record. */
export const historyToCsv = (direction, runs) => {
    const lines = [HEADER.join(',')];
    (runs || []).forEach(run => {
        const base = [direction, run.id, run.startedAt, run.finishedAt, run.trigger, run.status,
            run.created, run.updated, run.skipped, run.errors, run.truncated];
        const records = Array.isArray(run.records) ? run.records : [];
        if (records.length === 0) {
            lines.push([...base, '', '', '', '', '', '', ''].map(csvCell).join(','));
            return;
        }
        records.forEach(r => lines.push([...base, r.outcome, r.who, r.profileId, r.sfObject, r.sfId,
            (r.fields || []).join('; '), r.message].map(csvCell).join(',')));
    });
    return lines.join('\r\n') + '\r\n';
};

/** Serialize runs (with records) to a self-describing JSON document. */
export const historyToJson = (direction, runs) => JSON.stringify({
    direction,
    exportedAt: new Date().toISOString(),
    runs: runs || []
}, null, 2);

/** Download the history as CSV. Returns the number of runs exported. */
export const exportHistoryCsv = (direction, runs) => {
    download(`${baseName(direction)}.csv`, historyToCsv(direction, runs), 'text/csv');
    return (runs || []).length;
};

/** Download the history as JSON. Returns the number of runs exported. */
export const exportHistoryJson = (direction, runs) => {
    download(`${baseName(direction)}.json`, historyToJson(direction, runs), 'application/json');
    return (runs || []).length;
};
