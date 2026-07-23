import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Dropdown, Loader, Separator, Switch, Typography } from '@jahia/moonstone';
import { getOutboundTriggers, saveOutboundTriggers, triggerOutboundReconcile } from '../api';
import CronBuilder from './CronBuilder';
import classes from './MappingTab.module.css';

const NS = 'jcustomer-sfdc-connector';
const DEFAULT_CRON = '0 0 * * * ?';   // hourly (CronBuilder's finest granularity)
const isOn = v => v === true || v === 'true';

export default function OutboundTriggersTab() {
    const { t } = useTranslation(NS);
    const [cfg, setCfg] = useState({ onProfileUpdate: 'true', onFormSubmission: 'true', outboundObject: 'Lead', reconcileEnabled: 'false', reconcileCron: DEFAULT_CRON });
    const [loading, setLoading] = useState(true);
    const [saving,  setSaving]  = useState(false);
    const [polling, setPolling] = useState(false);
    const [msg,     setMsg]     = useState(null);

    const load = useCallback(async () => {
        setLoading(true); setMsg(null);
        try {
            const data = await getOutboundTriggers() || {};
            setCfg(c => ({ ...c, ...data }));
        } catch (e) { setMsg({ ok: false, text: e.message }); }
        finally { setLoading(false); }
    }, []);

    useEffect(() => { load(); }, [load]);

    const set = (key, val) => setCfg(c => ({ ...c, [key]: String(val) }));

    const handleSave = async () => {
        setSaving(true); setMsg(null);
        try {
            await saveOutboundTriggers({
                onProfileUpdate:  isOn(cfg.onProfileUpdate),
                onFormSubmission: isOn(cfg.onFormSubmission),
                outboundObject:   cfg.outboundObject === 'Contact' ? 'Contact' : 'Lead',
                reconcileEnabled: isOn(cfg.reconcileEnabled),
                reconcileCron:    (cfg.reconcileCron || DEFAULT_CRON).trim()
            });
            setMsg({ ok: true, text: t(`${NS}:outbound.triggers.saved`) });
        } catch (e) {
            setMsg({ ok: false, text: e.message });
        } finally { setSaving(false); }
    };

    const handlePollNow = async () => {
        setPolling(true); setMsg(null);
        try {
            await triggerOutboundReconcile();
            setMsg({ ok: true, text: t(`${NS}:outbound.triggers.pollStarted`) });
        } catch (e) {
            setMsg({ ok: false, text: e.message });
        } finally { setPolling(false); }
    };

    if (loading) return <div className={classes.center}><Loader size="big" /></div>;

    return (
        <div className={classes.wrapper}>
            <div className={classes.header}>
                <div>
                    <Typography variant="heading" weight="bold">{t(`${NS}:outbound.triggers.title`)}</Typography>
                    <Typography variant="body" className={classes.subtitle}>{t(`${NS}:outbound.triggers.subtitle`)}</Typography>
                </div>
                <div className={classes.headerActions}>
                    <Button variant="default" color="accent"
                        label={saving ? t(`${NS}:action.saving`) : t(`${NS}:action.save`)}
                        isDisabled={saving} isLoading={saving} onClick={handleSave} />
                    <Button variant="ghost" label={t(`${NS}:action.reload`)} onClick={load} isDisabled={loading} />
                </div>
            </div>

            <Separator />

            {/* What to sync */}
            <Typography variant="subheading" weight="semiBold" className={classes.sectionTitle}>{t(`${NS}:outbound.triggers.what.title`)}</Typography>
            <Typography variant="caption" className={classes.subtitle}>{t(`${NS}:outbound.triggers.what.subtitle`)}</Typography>

            <div className={classes.triggers}>
                <div className={classes.triggerRow}>
                    <div className={classes.triggerInfo}>
                        <Typography variant="body" weight="semiBold">{t(`${NS}:outbound.triggers.target.label`)}</Typography>
                        <Typography variant="caption" className={classes.subtitle}>{t(`${NS}:outbound.triggers.target.desc`)}</Typography>
                    </div>
                    <Dropdown size="medium"
                        value={cfg.outboundObject === 'Contact' ? 'Contact' : 'Lead'}
                        data={[
                            { label: t(`${NS}:outbound.triggers.target.lead`), value: 'Lead' },
                            { label: t(`${NS}:outbound.triggers.target.contact`), value: 'Contact' }
                        ]}
                        onChange={(e, item) => set('outboundObject', item?.value ?? 'Lead')} />
                </div>

                <div className={classes.triggerRow}>
                    <div className={classes.triggerInfo}>
                        <Typography variant="body" weight="semiBold">{t(`${NS}:outbound.triggers.profileUpdate.label`)}</Typography>
                        <Typography variant="caption" className={classes.subtitle}>{t(`${NS}:outbound.triggers.profileUpdate.desc`)}</Typography>
                    </div>
                    <Switch checked={isOn(cfg.onProfileUpdate)} label=""
                        onChange={e => set('onProfileUpdate', e.target.checked)} />
                </div>

                <div className={classes.triggerRow}>
                    <div className={classes.triggerInfo}>
                        <Typography variant="body" weight="semiBold">{t(`${NS}:outbound.triggers.formSubmission.label`)}</Typography>
                        <Typography variant="caption" className={classes.subtitle}>{t(`${NS}:outbound.triggers.formSubmission.desc`)}</Typography>
                    </div>
                    <Switch checked={isOn(cfg.onFormSubmission)} label=""
                        onChange={e => set('onFormSubmission', e.target.checked)} />
                </div>
            </div>

            <Separator />

            {/* Schedule */}
            <Typography variant="subheading" weight="semiBold" className={classes.sectionTitle}>{t(`${NS}:outbound.triggers.schedule.title`)}</Typography>
            <Typography variant="caption" className={classes.subtitle}>{t(`${NS}:outbound.triggers.schedule.subtitle`)}</Typography>

            <div className={classes.triggers}>
                <div className={classes.triggerRow}>
                    <div className={classes.triggerInfo}>
                        <Typography variant="body" weight="semiBold">{t(`${NS}:outbound.triggers.schedule.enabled.label`)}</Typography>
                        <Typography variant="caption" className={classes.subtitle}>{t(`${NS}:outbound.triggers.schedule.enabled.desc`)}</Typography>
                    </div>
                    <Switch checked={isOn(cfg.reconcileEnabled)} label=""
                        onChange={e => set('reconcileEnabled', e.target.checked)} />
                </div>

                {isOn(cfg.reconcileEnabled) && (
                    <div className={classes.cronRow}>
                        <CronBuilder value={cfg.reconcileCron || DEFAULT_CRON}
                            onChange={cron => set('reconcileCron', cron)} />
                    </div>
                )}

                <div>
                    <Button variant="outlined"
                        label={polling ? t(`${NS}:outbound.triggers.pollRunning`) : t(`${NS}:outbound.triggers.pollNow`)}
                        isDisabled={polling} isLoading={polling} onClick={handlePollNow} />
                </div>
            </div>

            {msg && (
                <div className={`${classes.alert} ${msg.ok ? classes.alertSuccess : classes.alertError}`} style={{ marginTop: 20 }}>
                    {msg.text}
                </div>
            )}
        </div>
    );
}
