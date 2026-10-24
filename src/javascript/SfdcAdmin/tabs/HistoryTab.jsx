import React, { useState, useEffect, useCallback, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import {
    Banner, Button, Chip, DataTable, Delete, Download, EmptyData, Loader, Reload, Tooltip, Typography
} from '@jahia/moonstone';
import { getSyncHistory, getSyncHistoryRun, exportSyncHistory, purgeSyncHistory } from '../api';
import { exportHistoryCsv, exportHistoryJson } from '../exportHistory';
import classes from './HistoryTab.module.css';
import layout from '../ui/layout.module.css';
import { Section } from '../ui/Section';
import { cell } from '../ui/table';
import { absoluteTime, relativeTime } from '../ui/format';

const NS = 'jcustomer-sfdc-connector';
const OUTCOME_COLOR = { created: 'success', updated: 'accent', skipped: 'warning', error: 'danger' };
const COUNTS = [
    ['created', 'success'], ['updated', 'accent'], ['skipped', 'warning'], ['errors', 'danger']
];


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
    const { t } = useTranslation(NS);
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
                if (row.kind === 'run') return (
                    <Tooltip label={absoluteTime(v.startedAt)}>
                        <span><Typography variant="body">{relativeTime(v.startedAt)}</Typography></span>
                    </Tooltip>
                );
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
                        <div className={classes.wrapText}>
                            <Typography variant="caption" className={classes.wrapText}>{v.status}</Typography>
                            {v.truncated && <Typography variant="caption" className={classes.wrapText}>{t(`${NS}:history.truncated`)}</Typography>}
                        </div>
                    );
                }
                return (
                    <div className={classes.wrapText}>
                        {(v.fields || []).map((f, i) => <Typography key={i} variant="caption" className={classes.wrapText}>{f}</Typography>)}
                        {v.message && <Typography variant="caption" className={classes.wrapText}>{v.message}</Typography>}
                    </div>
                );
            })
        }
    ];

    if (loading) return <div className={classes.center}><Loader size="big" /></div>;

    return (
        <div className={`${layout.page} ${classes.fitWidth}`}>
            <Section
                title={t(`${NS}:history.title.${direction}`)}
                description={t(`${NS}:history.subtitle`)}
                actions={(
                    <>
                        <Button variant="ghost" icon={<Reload />} label={t(`${NS}:history.refresh`)}
                            onClick={load} isDisabled={busy !== null} />
                        <Button variant="ghost" icon={<Download />} label={t(`${NS}:history.exportCsv`)}
                            onClick={() => handleExport('csv')} isLoading={busy === 'csv'} isDisabled={busy !== null || runs.length === 0} />
                        <Button variant="ghost" icon={<Download />} label={t(`${NS}:history.exportJson`)}
                            onClick={() => handleExport('json')} isLoading={busy === 'json'} isDisabled={busy !== null || runs.length === 0} />
                        <Button variant="outlined" color="danger" icon={<Delete />} label={t(`${NS}:history.purge`)}
                            onClick={() => setConfirming(true)} isDisabled={confirming || busy !== null || runs.length === 0} />
                    </>
                )}
            >
            {confirming && (
                <Banner variant="danger" title={t(`${NS}:history.purgeConfirm.${direction}`)}>
                    <div className={layout.actions}>
                        <Button variant="default" color="danger" icon={<Delete />} label={t(`${NS}:history.purgeYes`)}
                            onClick={handlePurge} isLoading={busy === 'purge'} isDisabled={busy !== null} />
                        <Button variant="ghost" label={t(`${NS}:history.purgeNo`)}
                            onClick={() => setConfirming(false)} isDisabled={busy !== null} />
                    </div>
                </Banner>
            )}

            {msg && <Banner variant="info" title={msg}><span /></Banner>}

            {runs.length === 0
                ? <EmptyData title={t(`${NS}:history.emptyTitle`)} message={t(`${NS}:history.empty`)} />
                : (
                    <div className={classes.tableScroll}>
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
                    </div>
                )}
            </Section>
        </div>
    );
}
