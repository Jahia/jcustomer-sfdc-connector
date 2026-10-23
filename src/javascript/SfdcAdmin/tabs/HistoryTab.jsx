import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
    Button, Chip, DataTable, Delete, Download, Loader, Reload, Separator, Typography
} from '@jahia/moonstone';
import { getSyncHistory, getSyncHistoryRun, exportSyncHistory, purgeSyncHistory } from '../api';
import { exportHistoryCsv, exportHistoryJson } from '../exportHistory';
import classes from './MappingTab.module.css';

const NS = 'jcustomer-sfdc-connector';
const OUTCOME_COLOR = { created: 'success', updated: 'accent', skipped: 'warning', error: 'danger' };
const COUNTS = [
    ['created', 'success'], ['updated', 'accent'], ['skipped', 'warning'], ['errors', 'danger']
];

/**
 * Column render adapter. The UI runs on the host's Moonstone (Module Federation singleton), and
 * DataTable changed its column contract: up to 2.19 it calls render(value, row), from 2.20 render({ id,
 * value, data, meta }). Accept both, so the tab works whichever version jContent ships.
 */
const cell = fn => (a, b) => (b === undefined && a && typeof a === 'object' && 'meta' in a && 'data' in a
    ? fn(a.value, a.data)
    : fn(a, b));

/**
 * Rendered in the placeholder sub-row of a run. That row only mounts when the run is expanded, so this
 * asks for the run's records the first time someone opens it. It relies only on DataTable's
 * isStructured/subRows: the UI uses the host's Moonstone at runtime (Module Federation singleton), and
 * renderRow / onExpandChange differ between Moonstone versions.
 */
const RecordsLoader = ({ runId, onLoad }) => {
    useEffect(() => { onLoad(runId); }, [runId, onLoad]);
    return <Loader size="small" />;
};

/**
 * Sync history of one direction: one collapsible row per run (when, trigger, result), its records
 * (who, outcome, Salesforce record, fields, message) loaded on expand; export to CSV/JSON; purge.
 *
 * @param direction 'inbound' (Salesforce -> jCustomer) or 'outbound' (jCustomer -> Salesforce)
 */
export default function HistoryTab({ direction }) {
    const { t, i18n } = useTranslation(NS);
    const [runs,        setRuns]        = useState([]);
    const [records,     setRecords]     = useState({});
    const [loading,     setLoading]     = useState(true);
    const [busy,        setBusy]        = useState(null);     // 'csv' | 'json' | 'purge'
    const [confirming,  setConfirming]  = useState(false);
    const [msg,         setMsg]         = useState(null);
    const requested = useRef(new Set());

    const load = useCallback(async () => {
        setLoading(true); setMsg(null);
        requested.current = new Set();
        setRecords({});
        try {
            const data = await getSyncHistory(direction);
            setRuns(Array.isArray(data?.runs) ? data.runs : []);
        } catch (e) {
            setMsg(e.message);
        } finally { setLoading(false); }
    }, [direction]);

    useEffect(() => { load(); }, [load]);

    const loadRecords = useCallback(async runId => {
        if (!runId || requested.current.has(runId)) return;
        requested.current.add(runId);
        try {
            const data = await getSyncHistoryRun(direction, runId);
            setRecords(r => ({ ...r, [runId]: Array.isArray(data?.records) ? data.records : [] }));
        } catch (e) {
            requested.current.delete(runId);
            setMsg(e.message);
        }
    }, [direction]);

    const handleExport = async fmt => {
        setBusy(fmt); setMsg(null);
        try {
            const data = await exportSyncHistory(direction);
            const all = Array.isArray(data?.runs) ? data.runs : [];
            const n = fmt === 'csv' ? exportHistoryCsv(direction, all) : exportHistoryJson(direction, all);
            setMsg(t(`${NS}:history.exported`, { count: n, fmt: fmt.toUpperCase() }));
        } catch (e) {
            setMsg(e.message);
        } finally { setBusy(null); }
    };

    const handlePurge = async () => {
        setBusy('purge'); setMsg(null);
        try {
            const res = await purgeSyncHistory(direction);
            setConfirming(false);
            await load();
            setMsg(t(`${NS}:history.purged`, { count: res?.removed ?? 0 }));
        } catch (e) {
            setMsg(e.message);
        } finally { setBusy(null); }
    };

    const formatDate = iso => {
        if (!iso) return '';
        const d = new Date(iso);
        return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(i18n.language);
    };

    const data = runs.map(run => {
        const loaded = records[run.id];
        let subRows;
        if (run.recordCount > 0) {
            subRows = loaded
                ? loaded.map((r, i) => ({ id: `${run.id}/${i}`, kind: 'record', runId: run.id, when: r, what: r, result: r, detail: r }))
                : [{ id: `${run.id}/loading`, kind: 'loading', runId: run.id, when: null, what: null, result: null, detail: null }];
        }
        return { id: run.id, kind: 'run', runId: run.id, when: run, what: run, result: run, detail: run, subRows };
    });

    const columns = [
        {
            key: 'when', label: t(`${NS}:history.col.when`),
            render: cell((v, row) => {
                if (row.kind === 'loading') return <RecordsLoader runId={row.runId} onLoad={loadRecords} />;
                if (row.kind === 'run') return <Typography variant="body">{formatDate(v.startedAt)}</Typography>;
                return (
                    <div>
                        <Typography variant="body">{v.who || t(`${NS}:history.unknownWho`)}</Typography>
                        {v.profileId && <Typography variant="caption">{v.profileId}</Typography>}
                    </div>
                );
            })
        },
        {
            key: 'what', label: t(`${NS}:history.col.what`),
            render: cell((v, row) => {
                if (row.kind === 'loading') return null;
                if (row.kind === 'run') return <Typography variant="body">{t(`${NS}:history.trigger.${v.trigger}`)}</Typography>;
                return <Chip label={t(`${NS}:history.outcome.${v.outcome}`)} color={OUTCOME_COLOR[v.outcome] || 'default'} />;
            })
        },
        {
            key: 'result', label: t(`${NS}:history.col.result`),
            render: cell((v, row) => {
                if (row.kind === 'loading') return null;
                if (row.kind === 'record') {
                    return <Typography variant="body">{[v.sfObject, v.sfId].filter(Boolean).join(' ')}</Typography>;
                }
                const chips = COUNTS.filter(([k]) => v[k] > 0)
                    .map(([k, color]) => <Chip key={k} color={color} label={t(`${NS}:history.count.${k}`, { count: v[k] })} />);
                return chips.length ? <div className={classes.chips}>{chips}</div>
                    : <Typography variant="caption">{t(`${NS}:history.nothing`)}</Typography>;
            })
        },
        {
            key: 'detail', label: t(`${NS}:history.col.details`),
            render: cell((v, row) => {
                if (row.kind === 'loading') return null;
                if (row.kind === 'run') {
                    return (
                        <div>
                            <Typography variant="caption">{v.status}</Typography>
                            {v.truncated && <Typography variant="caption">{t(`${NS}:history.truncated`)}</Typography>}
                        </div>
                    );
                }
                return (
                    <div>
                        {(v.fields || []).map((f, i) => <Typography key={i} variant="caption">{f}</Typography>)}
                        {v.message && <Typography variant="caption">{v.message}</Typography>}
                    </div>
                );
            })
        }
    ];

    if (loading) return <div className={classes.center}><Loader size="big" /></div>;

    return (
        <div className={classes.wrapper}>
            <div className={classes.header}>
                <div>
                    <Typography variant="heading" weight="bold">{t(`${NS}:history.title.${direction}`)}</Typography>
                    <Typography variant="body" className={classes.subtitle}>{t(`${NS}:history.subtitle`)}</Typography>
                </div>
                <div className={classes.headerActions}>
                    <Button variant="ghost" icon={<Reload />} label={t(`${NS}:history.refresh`)}
                        onClick={load} isDisabled={busy !== null} />
                    <Button variant="outlined" icon={<Download />} label={t(`${NS}:history.exportCsv`)}
                        onClick={() => handleExport('csv')} isLoading={busy === 'csv'} isDisabled={busy !== null || runs.length === 0} />
                    <Button variant="outlined" icon={<Download />} label={t(`${NS}:history.exportJson`)}
                        onClick={() => handleExport('json')} isLoading={busy === 'json'} isDisabled={busy !== null || runs.length === 0} />
                    {!confirming && (
                        <Button variant="outlined" color="danger" icon={<Delete />} label={t(`${NS}:history.purge`)}
                            onClick={() => setConfirming(true)} isDisabled={busy !== null || runs.length === 0} />
                    )}
                </div>
            </div>

            {confirming && (
                <div className={classes.headerActions}>
                    <Typography variant="body">{t(`${NS}:history.purgeConfirm.${direction}`)}</Typography>
                    <Button variant="default" color="danger" icon={<Delete />} label={t(`${NS}:history.purgeYes`)}
                        onClick={handlePurge} isLoading={busy === 'purge'} isDisabled={busy !== null} />
                    <Button variant="ghost" label={t(`${NS}:history.purgeNo`)}
                        onClick={() => setConfirming(false)} isDisabled={busy !== null} />
                </div>
            )}

            {msg && <Typography variant="body">{msg}</Typography>}

            <Separator />

            {runs.length === 0
                ? <Typography variant="body" className={classes.subtitle}>{t(`${NS}:history.empty`)}</Typography>
                : (
                    <DataTable
                        primaryKey="id"
                        data={data}
                        columns={columns}
                        isStructured
                        defaultExpandedRows={[]}
                        enablePagination
                        defaultItemsPerPage={20}
                        itemsPerPageOptions={[10, 20, 50]}
                        i18n={{ itemsPerPage: t(`${NS}:history.pagination.perPage`), of: t(`${NS}:history.pagination.of`) }}
                    />
                )}
        </div>
    );
}
