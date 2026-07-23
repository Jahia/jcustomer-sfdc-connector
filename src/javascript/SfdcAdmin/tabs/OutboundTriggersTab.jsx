import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Loader, Separator, Switch, Typography } from '@jahia/moonstone';
import { getOutboundTriggers, saveOutboundTriggers } from '../api';
import classes from './MappingTab.module.css';

const NS = 'jcustomer-sfdc-connector';
const isOn = v => v === true || v === 'true';

export default function OutboundTriggersTab() {
    const { t } = useTranslation(NS);
    const [cfg,     setCfg]     = useState({ enabled: 'false', onProfileUpdate: 'true', onFormSubmission: 'true' });
    const [loading, setLoading] = useState(true);
    const [saving,  setSaving]  = useState(false);
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
                enabled:          isOn(cfg.enabled),
                onProfileUpdate:  isOn(cfg.onProfileUpdate),
                onFormSubmission: isOn(cfg.onFormSubmission)
            });
            setMsg({ ok: true, text: t(`${NS}:outbound.triggers.saved`) });
        } catch (e) {
            setMsg({ ok: false, text: e.message });
        } finally { setSaving(false); }
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

            <div style={{ display: 'flex', flexDirection: 'column', gap: 20, marginTop: 16, maxWidth: 640 }}>
                <label style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                    <Switch checked={isOn(cfg.enabled)} onChange={e => set('enabled', e.target.checked)} />
                    <div>
                        <Typography variant="body" weight="semiBold">{t(`${NS}:outbound.triggers.enabled.label`)}</Typography>
                        <Typography variant="caption" className={classes.subtitle}>{t(`${NS}:outbound.triggers.enabled.desc`)}</Typography>
                    </div>
                </label>

                <label style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                    <Switch checked={isOn(cfg.onProfileUpdate)} isDisabled={!isOn(cfg.enabled)}
                        onChange={e => set('onProfileUpdate', e.target.checked)} />
                    <div>
                        <Typography variant="body" weight="semiBold">{t(`${NS}:outbound.triggers.profileUpdate.label`)}</Typography>
                        <Typography variant="caption" className={classes.subtitle}>{t(`${NS}:outbound.triggers.profileUpdate.desc`)}</Typography>
                    </div>
                </label>

                <label style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                    <Switch checked={isOn(cfg.onFormSubmission)} isDisabled={!isOn(cfg.enabled)}
                        onChange={e => set('onFormSubmission', e.target.checked)} />
                    <div>
                        <Typography variant="body" weight="semiBold">{t(`${NS}:outbound.triggers.formSubmission.label`)}</Typography>
                        <Typography variant="caption" className={classes.subtitle}>{t(`${NS}:outbound.triggers.formSubmission.desc`)}</Typography>
                    </div>
                </label>
            </div>

            {msg && (
                <div className={`${classes.alert} ${msg.ok ? classes.alertSuccess : classes.alertError}`} style={{ marginTop: 20 }}>
                    {msg.text}
                </div>
            )}
        </div>
    );
}
