import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Dropdown, Typography } from '@jahia/moonstone';
import classes from './CronBuilder.module.css';

const NS = 'jcustomer-sfdc-connector';

// ── Static data (labels resolved at render time via t()) ──────────────────────

const HOURS         = Array.from({ length: 24 }, (_, i) => ({ label: String(i).padStart(2, '0') + 'h', value: String(i) }));
const MINUTES       = [0, 15, 30, 45].map(m => ({ label: String(m).padStart(2, '0'), value: String(m) }));
const DAYS_OF_MONTH = Array.from({ length: 28 }, (_, i) => ({ label: String(i + 1), value: String(i + 1) }));

const FREQ_KEYS = ['hourly', 'daily', 'weekly', 'monthly'];
const DAY_KEYS  = [
    { key: 'monday',    value: 'MON' },
    { key: 'tuesday',   value: 'TUE' },
    { key: 'wednesday', value: 'WED' },
    { key: 'thursday',  value: 'THU' },
    { key: 'friday',    value: 'FRI' },
    { key: 'saturday',  value: 'SAT' },
    { key: 'sunday',    value: 'SUN' },
];

/** Build a Quartz cron expression from the UI state. */
const buildCron = ({ freq, hour, minute, day, dayOfMonth }) => {
    const h = hour   || '5';
    const m = minute || '0';
    switch (freq) {
        case 'hourly':  return `0 ${m} * * * ?`;
        case 'weekly':  return `0 ${m} ${h} ? * ${day || 'MON'}`;
        case 'monthly': return `0 ${m} ${h} ${dayOfMonth || '1'} * ?`;
        case 'daily':
        default:        return `0 ${m} ${h} * * ?`;
    }
};

/** Parse an existing Quartz cron into UI state (best-effort). */
const parseCron = cron => {
    if (!cron) return { freq: 'daily', hour: '5', minute: '0', day: 'MON', dayOfMonth: '1' };
    const parts = cron.trim().split(/\s+/);
    if (parts.length < 6) return { freq: 'daily', hour: '5', minute: '0', day: 'MON', dayOfMonth: '1' };

    const [, minute, hour, dom, , dow] = parts;

    if (hour === '*') return { freq: 'hourly', hour: '0', minute: minute === '*' ? '0' : minute, day: 'MON', dayOfMonth: '1' };
    if (dow !== '?' && dow !== '*') return { freq: 'weekly',  hour, minute, day: dow, dayOfMonth: '1' };
    if (dom !== '?' && dom !== '*') return { freq: 'monthly', hour, minute, day: 'MON', dayOfMonth: dom };
    return { freq: 'daily', hour, minute, day: 'MON', dayOfMonth: '1' };
};

// ── Component ─────────────────────────────────────────────────────────────────

export default function CronBuilder({ value, onChange }) {
    const { t } = useTranslation(NS);
    const [state, setState] = useState(() => parseCron(value));

    // Build translated option lists at render time
    const frequencies = FREQ_KEYS.map(k => ({ label: t(`${NS}:cron.freq.${k}`), value: k }));
    const days        = DAY_KEYS.map(d => ({ label: t(`${NS}:cron.day.${d.key}`), value: d.value }));

    // Re-parse if the external value changes (e.g. loaded from JCR)
    useEffect(() => { setState(parseCron(value)); }, [value]);

    const update = patch => {
        const next = { ...state, ...patch };
        setState(next);
        onChange(buildCron(next));
    };

    const pick = (e, item) => item?.value ?? '';   // Moonstone onChange(e, item)

    return (
        <div className={classes.builder}>
            {/* Row 1: frequency */}
            <div className={classes.row}>
                <span className={classes.word}>{t(`${NS}:cron.every`)}</span>
                <Dropdown
                    size="small"
                    value={state.freq}
                    data={frequencies}
                    onChange={(e, item) => update({ freq: pick(e, item) })}
                />

                {/* Hourly: at :MM */}
                {state.freq === 'hourly' && (
                    <>
                        <span className={classes.word}>{t(`${NS}:cron.at`)}</span>
                        <span className={classes.word}>:</span>
                        <Dropdown size="small" value={state.minute}
                            data={MINUTES}
                            onChange={(e, item) => update({ minute: pick(e, item) })} />
                    </>
                )}

                {/* Weekly: on [day] */}
                {state.freq === 'weekly' && (
                    <>
                        <span className={classes.word}>{t(`${NS}:cron.on`)}</span>
                        <Dropdown size="small" value={state.day}
                            data={days}
                            onChange={(e, item) => update({ day: pick(e, item) })} />
                    </>
                )}

                {/* Monthly: on day [N] */}
                {state.freq === 'monthly' && (
                    <>
                        <span className={classes.word}>{t(`${NS}:cron.onDay`)}</span>
                        <Dropdown size="small" value={state.dayOfMonth}
                            data={DAYS_OF_MONTH}
                            onChange={(e, item) => update({ dayOfMonth: pick(e, item) })} />
                    </>
                )}

                {/* Daily/Weekly/Monthly: at HH:MM */}
                {state.freq !== 'hourly' && (
                    <>
                        <span className={classes.word}>{t(`${NS}:cron.at`)}</span>
                        <Dropdown size="small" value={state.hour}
                            data={HOURS}
                            onChange={(e, item) => update({ hour: pick(e, item) })} />
                        <span className={classes.word}>:</span>
                        <Dropdown size="small" value={state.minute}
                            data={MINUTES}
                            onChange={(e, item) => update({ minute: pick(e, item) })} />
                        <span className={classes.word}>UTC</span>
                    </>
                )}
            </div>

            {/* Preview */}
            <Typography variant="caption" className={classes.preview}>
                cron: <code>{buildCron(state)}</code>
            </Typography>
        </div>
    );
}
