import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Chip, Loader, Separator, Switch, Typography } from '@jahia/moonstone';
import { getSyncState, getTriggers, saveTriggers, triggerSync } from '../api';
import CronBuilder from './CronBuilder';
import classes from './SyncTab.module.css';

const NS = 'jcustomer-sfdc-connector';

export default function SyncTab() {
    const { t } = useTranslation(NS);

    // Trigger config
    const [loginEnabled,     setLoginEnabled]     = useState(true);
    const [schedulerEnabled, setSchedulerEnabled] = useState(true);
    const [schedulerCron,    setSchedulerCron]    = useState('0 0 5 * * ?');
    const [triggerLoading,   setTriggerLoading]   = useState(true);
    const [triggerSaving,    setTriggerSaving]    = useState(false);
    const [triggerMsg,       setTriggerMsg]       = useState(null);

    // Sync state
    const [state,   setState]   = useState(null);
    const [loading, setLoading] = useState(true);
    const [syncing, setSyncing] = useState(false);
    const [syncMsg, setSyncMsg] = useState(null);

    // ── Load ──────────────────────────────────────────────────────────────────

    const loadTriggers = useCallback(async () => {
        setTriggerLoading(true);
        try {
            const data = await getTriggers();
            setLoginEnabled(data.loginEnabled !== 'false');
            setSchedulerEnabled(data.schedulerEnabled !== 'false');
            setSchedulerCron(data.schedulerCron || '0 0 5 * * ?');
        } catch (_) {}
        finally { setTriggerLoading(false); }
    }, []);

    const loadState = useCallback(async () => {
        setLoading(true);
        try { setState(await getSyncState()); }
        catch (_) { setState(null); }
        finally { setLoading(false); }
    }, []);

    useEffect(() => { loadTriggers(); loadState(); }, [loadTriggers, loadState]);

    // ── Save triggers ─────────────────────────────────────────────────────────

    const handleSaveTriggers = async () => {
        setTriggerSaving(true); setTriggerMsg(null);
        try {
            await saveTriggers({
                loginEnabled:     String(loginEnabled),
                schedulerEnabled: String(schedulerEnabled),
                schedulerCron
            });
            setTriggerMsg({ ok: true, text: t(`${NS}:sync.triggersSaved`) });
        } catch (e) {
            setTriggerMsg({ ok: false, text: e.message });
        } finally { setTriggerSaving(false); }
    };

    // ── Manual sync ───────────────────────────────────────────────────────────

    const handleSync = async () => {
        setSyncing(true); setSyncMsg(null);
        try {
            await triggerSync();
            setSyncMsg({ ok: true, text: t(`${NS}:sync.started`) });
            setTimeout(loadState, 3000);
        } catch (e) {
            setSyncMsg({ ok: false, text: e.message });
        } finally { setSyncing(false); }
    };

    const isOk = state?.lastSyncStatus?.startsWith('success');

    return (
        <div className={classes.wrapper}>

            {/* ── Trigger configuration ──────────────────────────────────────── */}
            <Typography variant="heading" weight="bold">{t(`${NS}:sync.triggers.title`)}</Typography>
            <Typography variant="body" className={classes.subtitle}>{t(`${NS}:sync.triggers.subtitle`)}</Typography>

            {triggerLoading ? <Loader size="big" /> : (
                <div className={classes.triggers}>
                    {/* Login trigger */}
                    <div className={classes.triggerRow}>
                        <div className={classes.triggerInfo}>
                            <Typography variant="body" weight="semiBold">{t(`${NS}:sync.triggers.login.label`)}</Typography>
                            <Typography variant="caption" className={classes.subtitle}>
                                {t(`${NS}:sync.triggers.login.desc`)}
                            </Typography>
                        </div>
                        <Switch
                            checked={loginEnabled}
                            onChange={e => { setLoginEnabled(e.target.checked); setTriggerMsg(null); }}
                            label=""
                        />
                    </div>

                    <Separator />

                    {/* Scheduler trigger */}
                    <div className={classes.triggerRow}>
                        <div className={classes.triggerInfo}>
                            <Typography variant="body" weight="semiBold">{t(`${NS}:sync.triggers.scheduler.label`)}</Typography>
                            <Typography variant="caption" className={classes.subtitle}>
                                {t(`${NS}:sync.triggers.scheduler.desc`)}
                            </Typography>
                        </div>
                        <Switch
                            checked={schedulerEnabled}
                            onChange={e => { setSchedulerEnabled(e.target.checked); setTriggerMsg(null); }}
                            label=""
                        />
                    </div>

                    {schedulerEnabled && (
                        <div className={classes.cronRow}>
                            <CronBuilder
                                value={schedulerCron}
                                onChange={cron => { setSchedulerCron(cron); setTriggerMsg(null); }}
                            />
                        </div>
                    )}

                    {/* Manual trigger (always available) */}
                    <Separator />
                    <div className={classes.triggerRow}>
                        <div className={classes.triggerInfo}>
                            <Typography variant="body" weight="semiBold">{t(`${NS}:sync.triggers.manual.label`)}</Typography>
                            <Typography variant="caption" className={classes.subtitle}>
                                {t(`${NS}:sync.triggers.manual.desc`)}
                            </Typography>
                        </div>
                        <Button
                            variant="default" color="accent"
                            label={syncing ? t(`${NS}:sync.running`) : t(`${NS}:sync.trigger`)}
                            isDisabled={syncing} isLoading={syncing}
                            onClick={handleSync}
                        />
                    </div>

                    {triggerMsg && (
                        <div className={`${classes.alert} ${triggerMsg.ok ? classes.alertSuccess : classes.alertError}`}>
                            {triggerMsg.text}
                        </div>
                    )}
                    {syncMsg && (
                        <div className={`${classes.alert} ${syncMsg.ok ? classes.alertInfo : classes.alertError}`}>
                            {syncMsg.text}
                        </div>
                    )}

                    <div className={classes.saveRow}>
                        <Button
                            variant="default" color="accent"
                            label={triggerSaving ? t(`${NS}:action.saving`) : t(`${NS}:sync.triggers.save`)}
                            isDisabled={triggerSaving} isLoading={triggerSaving}
                            onClick={handleSaveTriggers}
                        />
                    </div>
                </div>
            )}

            <Separator />

            {/* ── Last sync status ───────────────────────────────────────────── */}
            <Typography variant="heading" weight="bold">{t(`${NS}:sync.title`)}</Typography>

            {loading ? <Loader size="big" /> : (
                state ? (
                    <div className={classes.grid}>
                        <Typography variant="body" className={classes.gridLabel}>{t(`${NS}:sync.lastRun`)}</Typography>
                        <Typography variant="body" className={classes.mono}>{state.lastSyncAt || '—'}</Typography>
                        <Typography variant="body" className={classes.gridLabel}>{t(`${NS}:sync.status`)}</Typography>
                        <Chip label={state.lastSyncStatus || '—'}
                            color={isOk ? 'success' : state?.lastSyncStatus?.startsWith('skip') ? 'warning' : 'danger'} />
                        <Typography variant="body" className={classes.gridLabel}>{t(`${NS}:sync.updated`)}</Typography>
                        <Typography variant="body">{state.lastSyncCount ?? '—'}</Typography>
                        <Typography variant="body" className={classes.gridLabel}>{t(`${NS}:sync.errors`)}</Typography>
                        <Typography variant="body" className={state.lastSyncErrors > 0 ? classes.red : ''}>
                            {state.lastSyncErrors ?? '—'}
                        </Typography>
                    </div>
                ) : (
                    <Typography variant="body" className={classes.subtitle}>{t(`${NS}:sync.noState`)}</Typography>
                )
            )}

            <Button variant="outlined" label={t(`${NS}:action.refresh`)}
                isDisabled={loading} onClick={loadState} />
        </div>
    );
}
