import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import {
    Banner, Button, Chip, CloudCheck, Collapsible, Field, Input, Key, Loader, Switch, Textarea, Typography, Warning
} from '@jahia/moonstone';
import { getConnection, saveConnection, testConnection } from '../api';
import { Section, SettingRow } from '../ui/Section';
import classes from '../ui/layout.module.css';

const NS = 'jcustomer-sfdc-connector';
const FIELDS_ORG = ['instanceUrl', 'apiVersion'];
const FIELDS_APP = ['clientId', 'sfUsername'];
const comparable = f => JSON.stringify({ ...f, privateKey: '' });

/**
 * Salesforce connection, shared by both sync directions: the org, the Connected App user and the
 * private key of the JWT Bearer flow. Save is enabled when something changed; the key is never read
 * back from the server, only whether one is stored.
 */
export default function ConnectionTab() {
    const { t } = useTranslation(NS);
    const [form,       setForm]       = useState(null);
    const [saved,      setSaved]      = useState('');
    const [apiDefault, setApiDefault] = useState('');
    const [pkStored,   setPkStored]   = useState(false);
    const [howTo,      setHowTo]      = useState(false);
    const [saving,     setSaving]     = useState(false);
    const [testing,    setTesting]    = useState(false);
    const [banner,     setBanner]     = useState(null);

    const load = useCallback(async () => {
        setBanner(null);
        try {
            const data = await getConnection();
            const next = {
                instanceUrl: data.instanceUrl || '', clientId: data.clientId || '', sfUsername: data.sfUsername || '',
                apiVersion: data.apiVersion || '', privateKey: '',
                enabled: data.enabled !== undefined ? String(data.enabled) : 'false'
            };
            setForm(next);
            setSaved(comparable(next));
            setApiDefault(data.apiVersionDefault || '');
            setPkStored(!!data.privateKeyStored);
        } catch (e) {
            setForm({ instanceUrl: '', clientId: '', sfUsername: '', apiVersion: '', privateKey: '', enabled: 'false' });
            setBanner({ variant: 'danger', title: e.message });
        }
    }, []);

    useEffect(() => { load(); }, [load]);

    if (!form) return <div className={classes.center}><Loader size="big" /></div>;

    const set = (key, val) => { setForm(f => ({ ...f, [key]: val })); setBanner(null); };
    const dirty = comparable(form) !== saved || !!form.privateKey;

    const handleSave = async () => {
        setSaving(true); setBanner(null);
        try {
            const payload = { ...form };
            if (!payload.privateKey) delete payload.privateKey;
            await saveConnection(payload);
            if (form.privateKey) setPkStored(true);
            const next = { ...form, privateKey: '' };
            setForm(next);
            setSaved(comparable(next));
            setBanner({ variant: 'info', title: t(`${NS}:connection.saved`) });
        } catch (e) {
            setBanner({ variant: 'danger', title: e.message });
        } finally { setSaving(false); }
    };

    const handleTest = async () => {
        setTesting(true); setBanner(null);
        try {
            const r = await testConnection();
            setBanner(r.ok
                ? { variant: 'info', title: t(`${NS}:connection.testOk`), text: r.instanceUrl }
                : { variant: 'danger', title: t(`${NS}:connection.testFail`), text: r.error });
        } catch (e) {
            setBanner({ variant: 'danger', title: t(`${NS}:connection.testFail`), text: e.message });
        } finally { setTesting(false); }
    };

    const input = key => (
        <Field key={key} id={`sfdc-${key}`} label={t(`${NS}:connection.field.${key}.label`)}
            helper={key === 'apiVersion' && apiDefault
                ? t(`${NS}:connection.field.apiVersion.hint`, { version: apiDefault })
                : t(`${NS}:connection.field.${key}.helper`)}>
            <Input id={`sfdc-${key}`} value={form[key] || ''}
                placeholder={key === 'apiVersion' && apiDefault ? apiDefault : t(`${NS}:connection.field.${key}.placeholder`)}
                onChange={e => set(key, e.target.value)} />
        </Field>
    );

    const actions = (
        <>
            {dirty && <Chip color="warning" label={t(`${NS}:common.unsaved`)} />}
            <Button variant="outlined" icon={<CloudCheck />}
                label={testing ? t(`${NS}:connection.testing`) : t(`${NS}:connection.test`)}
                isLoading={testing} isDisabled={testing || dirty || !form.instanceUrl} onClick={handleTest} />
            <Button variant="default" color="accent" label={saving ? t(`${NS}:action.saving`) : t(`${NS}:action.save`)}
                isLoading={saving} isDisabled={saving || !dirty} onClick={handleSave} />
        </>
    );

    return (
        <div className={classes.page}>
            <Section title={t(`${NS}:connection.title`)} description={t(`${NS}:connection.subtitle`)} actions={actions}>
                {banner && (
                    <Banner variant={banner.variant} title={banner.title}
                        iconStart={banner.variant === 'danger' ? <Warning /> : <CloudCheck />}>
                        <Typography variant="body">{banner.text || ''}</Typography>
                    </Banner>
                )}
                <SettingRow
                    label={t(`${NS}:connection.enabled`)}
                    description={t(`${NS}:connection.enabledHelp`)}
                    control={<Switch checked={form.enabled === 'true'}
                        onChange={(e, v, checked) => set('enabled', String(checked ?? e.target.checked))} />}
                />
            </Section>

            <Section title={t(`${NS}:connection.org.title`)} description={t(`${NS}:connection.org.description`)}>
                <div className={classes.fields}>{FIELDS_ORG.map(input)}</div>
            </Section>

            <Section title={t(`${NS}:connection.app.title`)} description={t(`${NS}:connection.app.description`)}>
                <div className={classes.fields}>{FIELDS_APP.map(input)}</div>
            </Section>

            <Section title={t(`${NS}:connection.key.title`)} description={t(`${NS}:connection.key.description`)}
                actions={<Chip color={pkStored ? 'success' : 'warning'} icon={<Key />}
                    label={t(`${NS}:connection.key.${pkStored ? 'stored' : 'missing'}`)} />}>
                <Field id="sfdc-privateKey" label={t(`${NS}:connection.field.privateKey.label`)}
                    helper={pkStored ? t(`${NS}:connection.field.privateKey.stored`) : t(`${NS}:connection.field.privateKey.helper`)}>
                    <Textarea id="sfdc-privateKey" rows={6} value={form.privateKey || ''}
                        placeholder={pkStored
                            ? t(`${NS}:connection.field.privateKey.placeholderStored`)
                            : t(`${NS}:connection.field.privateKey.placeholder`)}
                        onChange={e => set('privateKey', e.target.value)} />
                </Field>
                <Collapsible label={t(`${NS}:connection.key.howTo.title`)} isExpanded={howTo} onClick={() => setHowTo(h => !h)}>
                    <div className={classes.stack}>
                        {['step1', 'step2', 'step3'].map(s => (
                            <Typography key={s} variant="body">{t(`${NS}:connection.key.howTo.${s}`)}</Typography>
                        ))}
                    </div>
                </Collapsible>
            </Section>
        </div>
    );
}
