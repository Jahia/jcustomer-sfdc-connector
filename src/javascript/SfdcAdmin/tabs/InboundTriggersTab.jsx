import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Banner, Button, Chip, Loader, SwapHoriz, Switch } from '@jahia/moonstone';
import { getTriggers, saveTriggers, triggerSync } from '../api';
import CronBuilder from './CronBuilder';
import { Section, SettingRow } from '../ui/Section';
import { describeCron } from '../ui/format';
import classes from '../ui/layout.module.css';

const NS = 'jcustomer-sfdc-connector';
const isOn = v => v === true || v === 'true';
const comparable = c => JSON.stringify([isOn(c.loginEnabled), isOn(c.schedulerEnabled), c.schedulerCron || '']);

/**
 * Salesforce → jCustomer triggers: on login and on a schedule (saved together), and a full sync now.
 * Values come from the server only.
 */
export default function InboundTriggersTab() {
    const { t } = useTranslation(NS);
    const [cfg,     setCfg]     = useState(null);
    const [saved,   setSaved]   = useState('');
    const [saving,  setSaving]  = useState(false);
    const [syncing, setSyncing] = useState(false);
    const [banner,  setBanner]  = useState(null);

    const load = useCallback(async () => {
        setBanner(null);
        try {
            const data = await getTriggers() || {};
            setCfg(data);
            setSaved(comparable(data));
        } catch (e) {
            setCfg({});
            setBanner({ variant: 'danger', title: e.message });
        }
    }, []);

    useEffect(() => { load(); }, [load]);

    if (!cfg) return <div className={classes.center}><Loader size="big" /></div>;

    const set = (key, val) => { setCfg(c => ({ ...c, [key]: val })); setBanner(null); };
    const dirty = comparable(cfg) !== saved;
    const anyTrigger = isOn(cfg.loginEnabled) || isOn(cfg.schedulerEnabled);

    const handleSave = async () => {
        setSaving(true); setBanner(null);
        try {
            await saveTriggers({
                loginEnabled: String(isOn(cfg.loginEnabled)),
                schedulerEnabled: String(isOn(cfg.schedulerEnabled)),
                schedulerCron: cfg.schedulerCron || ''
            });
            setSaved(comparable(cfg));
            setBanner({ variant: 'info', title: t(`${NS}:sync.triggersSaved`) });
        } catch (e) {
            setBanner({ variant: 'danger', title: e.message });
        } finally { setSaving(false); }
    };

    const handleSync = async () => {
        setSyncing(true); setBanner(null);
        try {
            await triggerSync();
            setBanner({ variant: 'info', title: t(`${NS}:sync.started`) });
        } catch (e) {
            setBanner({ variant: 'danger', title: e.message });
        } finally { setSyncing(false); }
    };

    const switchControl = key => (
        <Switch checked={isOn(cfg[key])} onChange={(e, v, checked) => set(key, String(checked ?? e.target.checked))} />
    );

    return (
        <div className={classes.page}>
            {banner && <Banner variant={banner.variant} title={banner.title}><span /></Banner>}

            <Section
                title={t(`${NS}:sync.triggers.title`)}
                description={t(`${NS}:sync.triggers.subtitle`)}
                actions={(
                    <>
                        <Chip color={anyTrigger ? 'success' : 'default'}
                            label={t(`${NS}:overview.status.${anyTrigger ? 'active' : 'paused'}`)} />
                        {dirty && <Chip color="warning" label={t(`${NS}:common.unsaved`)} />}
                        <Button variant="default" color="accent" label={saving ? t(`${NS}:action.saving`) : t(`${NS}:action.save`)}
                            isLoading={saving} isDisabled={saving || !dirty} onClick={handleSave} />
                    </>
                )}
            >
                <SettingRow label={t(`${NS}:sync.triggers.login.label`)}
                    description={t(`${NS}:sync.triggers.login.desc`)} control={switchControl('loginEnabled')} />
                <SettingRow
                    label={t(`${NS}:sync.triggers.scheduler.label`)}
                    description={t(`${NS}:sync.triggers.scheduler.desc`)}
                    control={switchControl('schedulerEnabled')}
                >
                    {isOn(cfg.schedulerEnabled) && (
                        <>
                            {cfg.schedulerCron && <Chip color="accent" label={describeCron(cfg.schedulerCron, t)} />}
                            <CronBuilder value={cfg.schedulerCron} onChange={cron => set('schedulerCron', cron)} />
                        </>
                    )}
                </SettingRow>
            </Section>

            <Section title={t(`${NS}:sync.now.title`)} description={t(`${NS}:sync.triggers.manual.desc`)}
                actions={<Button variant="default" color="accent" icon={<SwapHoriz />}
                    label={syncing ? t(`${NS}:sync.running`) : t(`${NS}:sync.trigger`)}
                    isLoading={syncing} isDisabled={syncing} onClick={handleSync} />}
            />
        </div>
    );
}
