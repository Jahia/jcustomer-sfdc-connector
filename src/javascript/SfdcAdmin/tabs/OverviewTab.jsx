import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
    Banner, Button, Chip, CloudCheck, Loader, Reload, SwapHoriz, Tooltip, Typography, Warning
} from '@jahia/moonstone';
import {
    getConnection, testConnection, getTriggers, getOutboundTriggers, getMapping, getOutboundMapping,
    getSyncHistory, triggerSync, triggerOutboundReconcile
} from '../api';
import { Section } from '../ui/Section';
import { absoluteTime, describeCron, relativeTime } from '../ui/format';
import classes from '../ui/layout.module.css';

const NS = 'jcustomer-sfdc-connector';
const isOn = v => v === true || v === 'true';
const COUNTS = [['created', 'success'], ['updated', 'accent'], ['skipped', 'warning'], ['errors', 'danger']];
const value = r => (r.status === 'fulfilled' ? r.value : null);

/** Number of complete mapping rows (both sides set) per object. */
const coverage = (mapping, objects) => objects.map(o => [o,
    (Array.isArray(mapping?.[o]) ? mapping[o] : []).filter(r => r.sfField && (r.jcProperty || r.defaultValue)).length]);

/** Result chips of a recorded run, or a caption when it touched nobody. */
const RunChips = ({ run, t }) => {
    const chips = COUNTS.filter(([k]) => run[k] > 0);
    if (chips.length === 0) return <Typography variant="caption">{t(`${NS}:history.nothing`)}</Typography>;
    return (
        <div className={classes.row}>
            {chips.map(([k, color]) => <Chip key={k} color={color} label={t(`${NS}:history.count.${k}`, { count: run[k] })} />)}
        </div>
    );
};

/** Last recorded activity of a direction: when (relative, absolute on hover) and what. */
const LastActivity = ({ run, t }) => (
    <div className={classes.facts}>
        <Typography variant="caption" weight="semiBold">{t(`${NS}:overview.lastActivity`)}</Typography>
        {run ? (
            <div className={classes.row}>
                <Tooltip label={absoluteTime(run.startedAt)}>
                    <span><Typography variant="body">{relativeTime(run.startedAt)}</Typography></span>
                </Tooltip>
                <Chip label={t(`${NS}:history.trigger.${run.trigger}`)} />
                <RunChips run={run} t={t} />
            </div>
        ) : <Typography variant="caption">{t(`${NS}:overview.noActivity`)}</Typography>}
    </div>
);

/**
 * Landing screen: is the connection working, is each direction running, what did it do last, and
 * what needs attention. Everything shown is read from the server; actions run a sync or open the
 * screen where a setting lives.
 */
export default function OverviewTab({ onNavigate }) {
    const { t } = useTranslation(NS);
    const [data,    setData]    = useState(null);
    const [test,    setTest]    = useState(null);      // { ok, message } | 'running' | null
    const [running, setRunning] = useState(null);      // 'inbound' | 'outbound'
    const [msg,     setMsg]     = useState(null);

    const runTest = useCallback(async () => {
        setTest('running');
        try {
            const r = await testConnection();
            setTest({ ok: !!r.ok, message: r.ok ? r.instanceUrl : r.error });
        } catch (e) {
            setTest({ ok: false, message: e.message });
        }
    }, []);

    const load = useCallback(async () => {
        setMsg(null);
        const [conn, inT, outT, inMap, outMap, inHist, outHist] = await Promise.allSettled([
            getConnection(), getTriggers(), getOutboundTriggers(), getMapping(), getOutboundMapping(),
            getSyncHistory('inbound'), getSyncHistory('outbound')
        ]);
        const next = {
            conn: value(conn) || {}, inT: value(inT) || {}, outT: value(outT) || {},
            inMap: value(inMap) || {}, outMap: value(outMap) || {},
            inRun: value(inHist)?.runs?.[0] || null, outRun: value(outHist)?.runs?.[0] || null
        };
        setData(next);
        if (next.conn.instanceUrl && isOn(next.conn.enabled)) runTest();
    }, [runTest]);

    useEffect(() => { load(); }, [load]);

    const syncNow = async direction => {
        setRunning(direction); setMsg(null);
        try {
            await (direction === 'inbound' ? triggerSync() : triggerOutboundReconcile());
            setMsg({ variant: 'info', text: t(`${NS}:overview.started.${direction}`) });
        } catch (e) {
            setMsg({ variant: 'danger', text: e.message });
        } finally { setRunning(null); }
    };

    if (!data) return <div className={classes.center}><Loader size="big" /></div>;

    const { conn, inT, outT, inMap, outMap, inRun, outRun } = data;
    const configured = !!conn.instanceUrl;
    const enabled    = isOn(conn.enabled);
    const target     = outT.outboundObject === 'Contact' ? 'Contact' : 'Lead';

    const inCoverage  = coverage(inMap, ['Contact', 'Account', 'Lead']);
    const outCoverage = coverage(outMap, [target]);
    const inTriggers  = [
        isOn(inT.loginEnabled) && t(`${NS}:overview.trigger.login`),
        isOn(inT.schedulerEnabled) && inT.schedulerCron && describeCron(inT.schedulerCron, t)
    ].filter(Boolean);
    const outCron     = outT.reconcileCron || outT.reconcileCronDefault;
    const outTriggers = [
        isOn(outT.onProfileUpdate) && t(`${NS}:overview.trigger.profileUpdate`),
        isOn(outT.onFormSubmission) && t(`${NS}:overview.trigger.formSubmission`)
    ].filter(Boolean);

    // What needs attention, most important first.
    const attention = [];
    if (!configured) attention.push({ variant: 'warning', key: 'notConfigured', go: ['connection'] });
    else if (!enabled) attention.push({ variant: 'warning', key: 'disabled', go: ['connection'] });
    if (test && test !== 'running' && !test.ok) attention.push({ variant: 'danger', key: 'testFailed', detail: test.message, go: ['connection'] });
    if (inTriggers.length > 0 && inCoverage.filter(([o]) => o !== 'Account').every(([, n]) => n === 0)) {
        attention.push({ variant: 'warning', key: 'inboundNoMapping', go: ['inbound', 'mapping'] });
    }
    if (outTriggers.length > 0 && outCoverage[0][1] === 0) {
        attention.push({ variant: 'warning', key: 'outboundNoMapping', params: { object: target }, go: ['outbound', 'mapping'] });
    }
    if (inRun?.errors > 0)  attention.push({ variant: 'danger', key: 'inboundErrors',  params: { count: inRun.errors },  go: ['inbound', 'history'] });
    if (outRun?.errors > 0) attention.push({ variant: 'danger', key: 'outboundErrors', params: { count: outRun.errors }, go: ['outbound', 'history'] });

    const directionStatus = triggers => {
        if (!configured || !enabled) return { color: 'default', label: t(`${NS}:overview.status.off`) };
        if (triggers.length === 0) return { color: 'default', label: t(`${NS}:overview.status.paused`) };
        return { color: 'success', label: t(`${NS}:overview.status.active`) };
    };

    const directionCard = (direction, triggers, schedule, cov, run, extraChip) => {
        const status = directionStatus(triggers);
        return (
            <Section
                title={t(`${NS}:overview.${direction}.title`)}
                description={t(`${NS}:overview.${direction}.description`)}
                actions={<Chip color={status.color} label={status.label} />}
            >
                <div className={classes.facts}>
                    <Typography variant="caption" weight="semiBold">{t(`${NS}:overview.triggers`)}</Typography>
                    <div className={classes.row}>
                        {triggers.length > 0
                            ? triggers.map(x => <Chip key={x} color="accent" label={x} />)
                            : <Typography variant="caption">{t(`${NS}:overview.noTrigger`)}</Typography>}
                        {schedule && <Typography variant="caption">{schedule}</Typography>}
                    </div>

                    <Typography variant="caption" weight="semiBold">{t(`${NS}:overview.mapping`)}</Typography>
                    <div className={classes.row}>
                        {extraChip}
                        {cov.map(([o, n]) => (
                            <Chip key={o} color={n > 0 ? 'default' : 'warning'}
                                label={t(`${NS}:overview.fields`, { object: o, count: n })} />
                        ))}
                    </div>
                </div>

                <LastActivity run={run} t={t} />

                <div className={classes.actions}>
                    <Button variant="default" color="accent" icon={<SwapHoriz />}
                        label={t(`${NS}:overview.syncNow`)} isLoading={running === direction}
                        isDisabled={!configured || !enabled || running !== null} onClick={() => syncNow(direction)} />
                    <Button variant="ghost" label={t(`${NS}:overview.openTriggers`)} onClick={() => onNavigate(direction, 'triggers')} />
                    <Button variant="ghost" label={t(`${NS}:overview.openHistory`)} onClick={() => onNavigate(direction, 'history')} />
                </div>
            </Section>
        );
    };

    return (
        <div className={classes.page}>
            {attention.map(a => (
                <Banner key={a.key} variant={a.variant} iconStart={<Warning />}
                    title={t(`${NS}:overview.attention.${a.key}.title`, a.params)}>
                    <div className={classes.row}>
                        <Typography variant="body">
                            {a.detail || t(`${NS}:overview.attention.${a.key}.text`, a.params)}
                        </Typography>
                        <Button variant="outlined" size="small" label={t(`${NS}:overview.fix`)}
                            onClick={() => onNavigate(...a.go)} />
                    </div>
                </Banner>
            ))}

            {msg && <Banner variant={msg.variant} title={msg.text}><span /></Banner>}

            <Section
                title={t(`${NS}:overview.connection.title`)}
                description={configured ? conn.instanceUrl : t(`${NS}:overview.connection.none`)}
                actions={(
                    <>
                        {test === 'running' && <Loader size="small" />}
                        {test && test !== 'running' && (
                            <Chip color={test.ok ? 'success' : 'danger'} icon={test.ok ? <CloudCheck /> : <Warning />}
                                label={t(`${NS}:overview.connection.${test.ok ? 'ok' : 'failed'}`)} />
                        )}
                        {configured && !enabled && <Chip color="warning" label={t(`${NS}:overview.connection.disabled`)} />}
                        <Button variant="ghost" icon={<Reload />} label={t(`${NS}:connection.test`)}
                            isDisabled={!configured || test === 'running'} onClick={runTest} />
                        <Button variant="outlined" label={t(`${NS}:overview.connection.edit`)} onClick={() => onNavigate('connection')} />
                    </>
                )}
            >
                {configured && (
                    <div className={classes.facts}>
                        <Typography variant="caption" weight="semiBold">{t(`${NS}:connection.field.sfUsername.label`)}</Typography>
                        <Typography variant="body">{conn.sfUsername || '-'}</Typography>
                        <Typography variant="caption" weight="semiBold">{t(`${NS}:connection.field.privateKey.label`)}</Typography>
                        <div className={classes.row}>
                            <Chip color={conn.privateKeyStored ? 'success' : 'warning'}
                                label={t(`${NS}:overview.connection.${conn.privateKeyStored ? 'keyStored' : 'keyMissing'}`)} />
                        </div>
                    </div>
                )}
            </Section>

            <div className={classes.grid2}>
                {directionCard('inbound', inTriggers,
                    null, inCoverage, inRun)}
                {directionCard('outbound', outTriggers,
                    outTriggers.length > 0 && outCron ? describeCron(outCron, t) : null, outCoverage, outRun,
                    <Chip color="accent" label={t(`${NS}:overview.target`, { object: target })} />)}
            </div>

            <div className={classes.actions}>
                <Button variant="ghost" icon={<Reload />} label={t(`${NS}:action.refresh`)} onClick={load} />
            </div>
        </div>
    );
}
