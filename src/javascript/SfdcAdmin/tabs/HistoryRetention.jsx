import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Banner, Button, Chip, Input, Switch, Typography } from '@jahia/moonstone';
import { saveHistoryRetention } from '../api';
import { Section, SettingRow } from '../ui/Section';
import classes from '../ui/layout.module.css';

const NS = 'jcustomer-sfdc-connector';

/**
 * Automatic purge of one direction's history: on/off and how many days runs are kept. Saving applies
 * it at once on the server; {@code onSaved} then reloads the run list.
 *
 * @param direction  'inbound' or 'outbound'
 * @param retention  { enabled, days, defaultDays, minDays, maxDays } as returned with the run list
 * @param onSaved    called with the server's answer: { removed, retention }
 */
export default function HistoryRetention({ direction, retention, onSaved }) {
    const { t } = useTranslation(NS);
    const [enabled, setEnabled] = useState(true);
    const [days,    setDays]    = useState('');
    const [saving,  setSaving]  = useState(false);
    const [banner,  setBanner]  = useState(null);

    useEffect(() => {
        if (!retention) return;
        setEnabled(retention.enabled !== false);
        setDays(String(retention.days ?? ''));
    }, [retention]);

    if (!retention) return null;

    const n = Number(days);
    const valid = Number.isInteger(n) && n >= retention.minDays && n <= retention.maxDays;
    const dirty = enabled !== (retention.enabled !== false) || days !== String(retention.days);

    const handleSave = async () => {
        setSaving(true); setBanner(null);
        try {
            const res = await saveHistoryRetention(direction, enabled, n);
            const removed = res?.removed ?? 0;
            setBanner({
                variant: 'info',
                title: removed > 0 ? t(`${NS}:history.retention.savedRemoved`, { count: removed }) : t(`${NS}:history.retention.saved`)
            });
            onSaved(res);
        } catch (e) {
            setBanner({ variant: 'danger', title: e.message });
        } finally { setSaving(false); }
    };

    return (
        <Section
            title={t(`${NS}:history.retention.title`)}
            description={t(`${NS}:history.retention.description`)}
            actions={(
                <>
                    <Chip color={enabled ? 'success' : 'default'}
                        label={enabled ? `${retention.days} ${t(`${NS}:history.retention.unit`)}` : t(`${NS}:overview.status.off`)} />
                    {dirty && <Chip color="warning" label={t(`${NS}:common.unsaved`)} />}
                    <Button variant="default" color="accent" label={saving ? t(`${NS}:action.saving`) : t(`${NS}:action.save`)}
                        isLoading={saving} isDisabled={saving || !dirty || !valid} onClick={handleSave} />
                </>
            )}
        >
            {banner && <Banner variant={banner.variant} title={banner.title}><span /></Banner>}
            <SettingRow
                label={t(`${NS}:history.retention.label`)}
                description={enabled ? t(`${NS}:history.retention.help`) : t(`${NS}:history.retention.off`)}
                control={<Switch checked={enabled}
                    onChange={(e, v, checked) => { setEnabled(checked ?? e.target.checked); setBanner(null); }} />}
            >
                {enabled && (
                    <div className={classes.stack}>
                        <div className={classes.inlineForm}>
                            <Typography variant="body">{t(`${NS}:history.retention.days`)}</Typography>
                            <div className={classes.inlineNumber}>
                                <Input id={`sfdc-retention-${direction}`} type="number" value={days}
                                    aria-label={t(`${NS}:history.retention.days`)}
                                    onChange={e => { setDays(e.target.value.trim()); setBanner(null); }} />
                            </div>
                            <Typography variant="body">{t(`${NS}:history.retention.unit`)}</Typography>
                        </div>
                        <Typography variant="caption">
                            {valid
                                ? t(`${NS}:history.retention.default`, { days: retention.defaultDays })
                                : t(`${NS}:history.retention.invalid`, { min: retention.minDays, max: retention.maxDays })}
                        </Typography>
                    </div>
                )}
            </SettingRow>
        </Section>
    );
}
