import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Input, Loader, Separator, Switch, Typography } from '@jahia/moonstone';
import { getConnection, saveConnection, testConnection } from '../api';
import classes from './ConnectionTab.module.css';

const NS = 'jcustomer-sfdc-connector';

const EMPTY = { instanceUrl: '', clientId: '', sfUsername: '', apiVersion: '', privateKey: '', enabled: 'true' };

// Labels and placeholders come from the locale files (connection.field.<key>.*).
const FIELDS = ['instanceUrl', 'clientId', 'sfUsername', 'apiVersion'];

export default function ConnectionTab() {
    const { t } = useTranslation(NS);
    const [form,       setForm]       = useState(EMPTY);
    const [apiVersionDefault, setApiVersionDefault] = useState('');
    const [pkStored,   setPkStored]   = useState(false);
    const [loading,    setLoading]    = useState(true);
    const [saving,     setSaving]     = useState(false);
    const [testing,    setTesting]    = useState(false);
    const [testResult, setTestResult] = useState(null);
    const [saveMsg,    setSaveMsg]    = useState(null);

    useEffect(() => {
        (async () => {
            try {
                const data = await getConnection();
                setForm({
                    instanceUrl: data.instanceUrl || '',
                    clientId:    data.clientId    || '',
                    sfUsername:  data.sfUsername   || '',
                    apiVersion:  data.apiVersion   || '',
                    privateKey:  '',
                    enabled:     data.enabled !== undefined ? String(data.enabled) : 'true',
                });
                setApiVersionDefault(data.apiVersionDefault || '');
                setPkStored(!!data.privateKeyStored);
            } catch (_) {}
            finally { setLoading(false); }
        })();
    }, []);

    const set = (key, val) => { setForm(f => ({ ...f, [key]: val })); setTestResult(null); setSaveMsg(null); };

    const handleSave = async () => {
        setSaving(true); setSaveMsg(null);
        try {
            const payload = { ...form };
            if (!payload.privateKey) delete payload.privateKey;
            await saveConnection(payload);
            setSaveMsg({ ok: true, text: t(`${NS}:connection.saved`) });
            if (form.privateKey) setPkStored(true);
        } catch (e) {
            setSaveMsg({ ok: false, text: e.message });
        } finally { setSaving(false); }
    };

    const handleTest = async () => {
        setTesting(true); setTestResult(null);
        try {
            const result = await testConnection();
            setTestResult({ ok: result.ok, message: result.ok ? result.instanceUrl : result.error });
        } catch (e) {
            setTestResult({ ok: false, message: e.message });
        } finally { setTesting(false); }
    };

    if (loading) return <div className={classes.center}><Loader size="big" /></div>;

    return (
        <div className={classes.wrapper}>
            <Typography variant="heading" weight="bold">{t(`${NS}:connection.title`)}</Typography>
            <Typography variant="body" className={classes.subtitle}>{t(`${NS}:connection.subtitle`)}</Typography>

            <Separator />

            {FIELDS.map(key => (
                <div key={key} className={classes.field}>
                    <label className={classes.label}>{t(`${NS}:connection.field.${key}.label`)}</label>
                    <Input
                        value={form[key] || ''}
                        placeholder={key === 'apiVersion' && apiVersionDefault
                            ? apiVersionDefault
                            : t(`${NS}:connection.field.${key}.placeholder`)}
                        onChange={e => set(key, e.target.value)}
                    />
                    {key === 'apiVersion' && apiVersionDefault && (
                        <Typography variant="caption" className={classes.hint}>
                            {t(`${NS}:connection.field.apiVersion.hint`, { version: apiVersionDefault })}
                        </Typography>
                    )}
                </div>
            ))}

            <div className={classes.field}>
                <label className={classes.label}>{t(`${NS}:connection.field.privateKey.label`)}</label>
                <textarea
                    className={classes.textarea}
                    rows={8}
                    value={form.privateKey || ''}
                    placeholder={pkStored
                        ? t(`${NS}:connection.field.privateKey.placeholderStored`)
                        : t(`${NS}:connection.field.privateKey.placeholder`)}
                    onChange={e => set('privateKey', e.target.value)}
                />
                {pkStored && !form.privateKey && (
                    <Typography variant="caption" className={classes.hint}>
                        {t(`${NS}:connection.field.privateKey.stored`)}
                    </Typography>
                )}
            </div>

            <div className={classes.switchRow}>
                <Switch
                    checked={form.enabled === 'true'}
                    onChange={e => set('enabled', e.target.checked ? 'true' : 'false')}
                    label={t(`${NS}:connection.enabled`)}
                />
            </div>

            {testResult && (
                <div className={`${classes.alert} ${testResult.ok ? classes.alertSuccess : classes.alertError}`}>
                    {testResult.ok
                        ? `✓ ${t(`${NS}:connection.testOk`)}: ${testResult.message}`
                        : `✗ ${t(`${NS}:connection.testFail`)}: ${testResult.message}`}
                </div>
            )}

            {saveMsg && (
                <div className={`${classes.alert} ${saveMsg.ok ? classes.alertSuccess : classes.alertError}`}>
                    {saveMsg.text}
                </div>
            )}

            <div className={classes.actions}>
                <Button
                    variant="outlined"
                    label={testing ? t(`${NS}:connection.testing`) : t(`${NS}:connection.test`)}
                    isDisabled={testing}
                    isLoading={testing}
                    onClick={handleTest}
                />
                <Button
                    variant="default"
                    color="accent"
                    label={saving ? t(`${NS}:action.saving`) : t(`${NS}:action.save`)}
                    isDisabled={saving}
                    isLoading={saving}
                    onClick={handleSave}
                />
            </div>
        </div>
    );
}
