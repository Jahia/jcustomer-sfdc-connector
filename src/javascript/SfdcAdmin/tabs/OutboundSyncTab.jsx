import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Input, Loader, Separator, Typography } from '@jahia/moonstone';
import { triggerOutboundSync, getOutboundSyncState } from '../api';
import classes from './MappingTab.module.css';

const NS = 'jcustomer-sfdc-connector';

export default function OutboundSyncTab() {
    const { t } = useTranslation(NS);
    const [email,   setEmail]   = useState('');
    const [state,   setState]   = useState({});
    const [loading, setLoading] = useState(true);
    const [pushing, setPushing] = useState(false);
    const [msg,     setMsg]     = useState(null);

    const loadState = useCallback(async () => {
        setLoading(true);
        try { setState(await getOutboundSyncState() || {}); }
        catch (e) { setMsg({ ok: false, text: e.message }); }
        finally { setLoading(false); }
    }, []);

    useEffect(() => { loadState(); }, [loadState]);

    const handlePush = async () => {
        if (!email.trim()) { setMsg({ ok: false, text: t(`${NS}:outbound.sync.emailRequired`) }); return; }
        setPushing(true); setMsg(null);
        try {
            const res = await triggerOutboundSync({ email: email.trim() });
            setMsg({ ok: true, text: t(`${NS}:outbound.sync.result`, { status: res?.status ?? 'done', email: email.trim() }) });
            await loadState();
        } catch (e) {
            setMsg({ ok: false, text: e.message });
        } finally { setPushing(false); }
    };

    if (loading) return <div className={classes.center}><Loader size="big" /></div>;

    return (
        <div className={classes.wrapper}>
            <div className={classes.header}>
                <div>
                    <Typography variant="heading" weight="bold">{t(`${NS}:outbound.sync.title`)}</Typography>
                    <Typography variant="body" className={classes.subtitle}>{t(`${NS}:outbound.sync.subtitle`)}</Typography>
                </div>
                <div className={classes.headerActions}>
                    <Button variant="ghost" label={t(`${NS}:action.reload`)} onClick={loadState} isDisabled={loading} />
                </div>
            </div>

            <Separator />

            <div style={{ display: 'flex', gap: 12, alignItems: 'flex-end', maxWidth: 560, marginTop: 16 }}>
                <div style={{ flex: 1 }}>
                    <Typography variant="caption" weight="semiBold">{t(`${NS}:outbound.sync.emailLabel`)}</Typography>
                    <Input value={email} type="email" placeholder="user@example.com"
                        onChange={e => setEmail(e.target.value)} />
                </div>
                <Button variant="default" color="accent"
                    label={pushing ? t(`${NS}:outbound.sync.pushing`) : t(`${NS}:outbound.sync.push`)}
                    isDisabled={pushing} isLoading={pushing} onClick={handlePush} />
            </div>

            <div style={{ marginTop: 24 }}>
                <Typography variant="subheading" weight="semiBold">{t(`${NS}:outbound.sync.lastRun`)}</Typography>
                <table className={classes.table} style={{ maxWidth: 560 }}>
                    <tbody>
                        {[
                            ['lastSyncAt',      t(`${NS}:outbound.sync.at`)],
                            ['lastSyncStatus',  t(`${NS}:outbound.sync.status`)],
                            ['lastSyncCreated', t(`${NS}:outbound.sync.created`)],
                            ['lastSyncUpdated', t(`${NS}:outbound.sync.updated`)],
                            ['lastSyncErrors',  t(`${NS}:outbound.sync.errors`)],
                        ].map(([key, label]) => (
                            <tr key={key} className={classes.tr}>
                                <td className={classes.td} style={{ fontWeight: 600, width: 160 }}>{label}</td>
                                <td className={classes.td}>{state[key] ?? '—'}</td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>

            {msg && (
                <div className={`${classes.alert} ${msg.ok ? classes.alertSuccess : classes.alertError}`}>
                    {msg.text}
                </div>
            )}
        </div>
    );
}
