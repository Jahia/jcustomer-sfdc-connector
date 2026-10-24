import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
    Banner, Button, Chip, Dropdown, Input, Information, Loader, SwapHoriz, Switch, Typography, Upload
} from '@jahia/moonstone';
import { getOutboundTriggers, saveOutboundTriggers, triggerOutboundReconcile, triggerOutboundSync } from '../api';
import CronBuilder from './CronBuilder';
import { Section, SettingRow } from '../ui/Section';
import { describeCron } from '../ui/format';
import classes from '../ui/layout.module.css';

const NS = 'jcustomer-sfdc-connector';
const isOn = v => v === true || v === 'true';
const comparable = c => JSON.stringify([isOn(c.onProfileUpdate), isOn(c.onFormSubmission), c.outboundObject, c.reconcileCron || '']);

/**
 * jCustomer → Salesforce triggers. "Automatic" is one saved configuration: the target object, the
 * triggers (turning one on starts the poll) and how often jCustomer is polled. "Run now" pushes
 * immediately without changing it.
 */
export default function OutboundTriggersTab() {
    const { t } = useTranslation(NS);
    const [cfg,     setCfg]     = useState(null);
    const [saved,   setSaved]   = useState('');
    const [saving,  setSaving]  = useState(false);
    const [polling, setPolling] = useState(false);
    const [email,   setEmail]   = useState('');
    const [pushing, setPushing] = useState(false);
    const [banner,  setBanner]  = useState(null);

    const load = useCallback(async () => {
        setBanner(null);
        try {
            const data = await getOutboundTriggers() || {};
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
    const anyTrigger = isOn(cfg.onProfileUpdate) || isOn(cfg.onFormSubmission);
    const target = cfg.outboundObject === 'Contact' ? 'Contact' : 'Lead';
    const schedule = cfg.reconcileCron || cfg.reconcileCronDefault;

    const handleSave = async () => {
        setSaving(true); setBanner(null);
        try {
            await saveOutboundTriggers({
                onProfileUpdate: isOn(cfg.onProfileUpdate),
                onFormSubmission: isOn(cfg.onFormSubmission),
                outboundObject: target,
                reconcileCron: (cfg.reconcileCron || '').trim()   // empty = default schedule
            });
            setSaved(comparable(cfg));
            setBanner({ variant: 'info', title: t(`${NS}:outbound.triggers.saved`) });
        } catch (e) {
            setBanner({ variant: 'danger', title: e.message });
        } finally { setSaving(false); }
    };

    const handlePoll = async () => {
        setPolling(true); setBanner(null);
        try {
            await triggerOutboundReconcile();
            setBanner({ at: 'now', variant: 'info', title: t(`${NS}:outbound.triggers.pollStarted`) });
        } catch (e) {
            setBanner({ at: 'now', variant: 'danger', title: e.message });
        } finally { setPolling(false); }
    };

    const handlePush = async () => {
        if (!email.trim()) { setBanner({ at: 'now', variant: 'warning', title: t(`${NS}:outbound.sync.emailRequired`) }); return; }
        setPushing(true); setBanner(null);
        try {
            const res = await triggerOutboundSync({ email: email.trim() });
            setBanner({
                at: 'now',
                variant: res?.status === 'pushed' ? 'info' : 'warning',
                title: t(`${NS}:outbound.sync.result.${res?.status === 'pushed' ? 'pushed' : 'skipped'}`, { email: email.trim(), object: target })
            });
        } catch (e) {
            setBanner({ at: 'now', variant: 'danger', title: e.message });
        } finally { setPushing(false); }
    };

    const switchControl = key => (
        <Switch checked={isOn(cfg[key])} onChange={(e, v, checked) => set(key, String(checked ?? e.target.checked))} />
    );

    return (
        <div className={classes.page}>
            <Section
                title={t(`${NS}:outbound.triggers.auto.title`)}
                description={t(`${NS}:outbound.triggers.auto.description`)}
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
                {banner && banner.at !== 'now' && <Banner variant={banner.variant} title={banner.title}><span /></Banner>}
                <SettingRow
                    label={t(`${NS}:outbound.triggers.target.label`)}
                    description={t(`${NS}:outbound.triggers.target.desc`)}
                    control={(
                        <Dropdown size="medium" value={target}
                            data={[
                                { label: t(`${NS}:outbound.triggers.target.lead`), value: 'Lead' },
                                { label: t(`${NS}:outbound.triggers.target.contact`), value: 'Contact' }
                            ]}
                            onChange={(e, item) => set('outboundObject', item?.value ?? 'Lead')} />
                    )}
                />
                <SettingRow label={t(`${NS}:outbound.triggers.profileUpdate.label`)}
                    description={t(`${NS}:outbound.triggers.profileUpdate.desc`)} control={switchControl('onProfileUpdate')} />
                <SettingRow label={t(`${NS}:outbound.triggers.formSubmission.label`)}
                    description={t(`${NS}:outbound.triggers.formSubmission.desc`)} control={switchControl('onFormSubmission')} />
                <SettingRow
                    label={t(`${NS}:outbound.triggers.schedule.title`)}
                    description={t(`${NS}:outbound.triggers.schedule.subtitle`)}
                    control={schedule && <Chip color={anyTrigger ? 'accent' : 'default'} label={describeCron(schedule, t)} />}
                >
                    {schedule && <CronBuilder value={schedule} onChange={cron => set('reconcileCron', cron)} />}
                    {!cfg.reconcileCron && cfg.reconcileCronDefault && (
                        <Typography variant="caption">{t(`${NS}:outbound.triggers.schedule.usingDefault`)}</Typography>
                    )}
                </SettingRow>
                <Banner variant={anyTrigger ? 'info' : 'warning'} iconStart={<Information />}
                    title={t(`${NS}:outbound.triggers.schedule.${anyTrigger ? 'activeTitle' : 'inactiveTitle'}`)}>
                    <Typography variant="body">{t(`${NS}:outbound.triggers.schedule.${anyTrigger ? 'active' : 'inactive'}`)}</Typography>
                </Banner>
            </Section>

            <Section title={t(`${NS}:outbound.triggers.now.title`)} description={t(`${NS}:outbound.triggers.now.description`)}>
                {banner?.at === 'now' && <Banner variant={banner.variant} title={banner.title}><span /></Banner>}
                <SettingRow
                    label={t(`${NS}:outbound.triggers.now.poll.label`)}
                    description={t(`${NS}:outbound.triggers.now.poll.desc`)}
                    control={<Button variant="default" color="accent" icon={<SwapHoriz />}
                        label={polling ? t(`${NS}:outbound.triggers.pollRunning`) : t(`${NS}:outbound.triggers.pollNow`)}
                        isLoading={polling} isDisabled={polling || !anyTrigger} onClick={handlePoll} />}
                />
                <SettingRow
                    label={t(`${NS}:outbound.triggers.now.push.label`)}
                    description={t(`${NS}:outbound.triggers.now.push.desc`, { object: target })}
                    control={(
                        <div className={classes.inlineForm}>
                            <div className={classes.inlineInput}>
                                <Input id="sfdc-push-email" type="email" value={email}
                                    aria-label={t(`${NS}:outbound.sync.emailLabel`)}
                                    placeholder={t(`${NS}:outbound.sync.emailPlaceholder`)}
                                    onChange={e => setEmail(e.target.value)}
                                    onKeyDown={e => { if (e.key === 'Enter' && email.trim() && !pushing) handlePush(); }} />
                            </div>
                            <Button variant="default" color="accent" icon={<Upload />}
                                label={pushing ? t(`${NS}:outbound.sync.pushing`) : t(`${NS}:outbound.sync.push`)}
                                isLoading={pushing} isDisabled={pushing || !email.trim()} onClick={handlePush} />
                        </div>
                    )}
                />
            </Section>
        </div>
    );
}
