import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Dropdown, Loader, Separator, Typography } from '@jahia/moonstone';
import { getSfdcFields, getJcProperties, getOutboundMapping, saveOutboundMapping } from '../api';
import classes from './MappingTab.module.css';

const NS = 'jcustomer-sfdc-connector';
const SF_OBJECT = 'Lead';                       // outbound targets Lead only
const REQUIRED_TO_CREATE = ['LastName', 'Company'];

export default function OutboundMappingTab() {
    const { t } = useTranslation(NS);
    const [leadFields, setLeadFields] = useState([]);
    const [jcProps,    setJcProps]    = useState([]);
    const [rows,       setRows]       = useState([]);
    const [loading,    setLoading]    = useState(true);
    const [saving,     setSaving]     = useState(false);
    const [msg,        setMsg]        = useState(null);

    const load = useCallback(async () => {
        setLoading(true);
        setMsg(null);
        const [fieldsR, propsR, mappingR] = await Promise.allSettled([
            getSfdcFields(), getJcProperties(), getOutboundMapping()
        ]);

        if (fieldsR.status === 'fulfilled') {
            setLeadFields(Array.isArray(fieldsR.value?.Lead) ? fieldsR.value.Lead : []);
        }
        if (propsR.status === 'fulfilled') {
            setJcProps(Array.isArray(propsR.value) ? propsR.value : []);
        }
        if (mappingR.status === 'fulfilled') {
            setRows(Array.isArray(mappingR.value?.Lead) ? mappingR.value.Lead : []);
        }

        const errors = [fieldsR, propsR, mappingR]
            .filter(r => r.status === 'rejected').map(r => r.reason?.message).filter(Boolean);
        if (errors.length) setMsg({ ok: false, text: errors.join(' | ') });

        setLoading(false);
    }, []);

    useEffect(() => { load(); }, [load]);

    const addRow    = () => setRows(r => [...r, { jcProperty: '', sfField: '' }]);
    const removeRow = i  => setRows(r => r.filter((_, idx) => idx !== i));
    const updateRow = (i, key, val) => setRows(r => {
        const next = [...r];
        next[i] = { ...next[i], [key]: val };
        return next;
    });

    const handleSave = async () => {
        setSaving(true); setMsg(null);
        try {
            await saveOutboundMapping({ sfObject: SF_OBJECT, mappings: rows });
            setMsg({ ok: true, text: t(`${NS}:outbound.mapping.saved`) });
        } catch (e) {
            setMsg({ ok: false, text: e.message });
        } finally { setSaving(false); }
    };

    const jcOptions   = jcProps.map(p => ({ label: `${p.name}${p.multivalued ? ' [multi]' : ''}`, value: p.id }));
    const leadOptions = leadFields.map(f => ({ label: `${f.label || f.name} (${f.name})`, value: f.name }));
    const mappedFields = new Set(rows.map(r => r.sfField));
    const missingRequired = REQUIRED_TO_CREATE.filter(f => !mappedFields.has(f));

    if (loading) return <div className={classes.center}><Loader size="big" /></div>;

    return (
        <div className={classes.wrapper}>
            <div className={classes.header}>
                <div>
                    <Typography variant="heading" weight="bold">{t(`${NS}:outbound.mapping.title`)}</Typography>
                    <Typography variant="body" className={classes.subtitle}>{t(`${NS}:outbound.mapping.subtitle`)}</Typography>
                </div>
                <div className={classes.headerActions}>
                    <Button variant="outlined" label={t(`${NS}:mapping.addRow`)} onClick={addRow} />
                    <Button variant="default" color="accent"
                        label={saving ? t(`${NS}:action.saving`) : t(`${NS}:action.save`)}
                        isDisabled={saving} isLoading={saving} onClick={handleSave} />
                    <Button variant="ghost" label={t(`${NS}:action.reload`)} onClick={load} isDisabled={loading} />
                </div>
            </div>

            <Separator />

            {missingRequired.length > 0 && (
                <div className={`${classes.alert} ${classes.alertError}`}>
                    {t(`${NS}:outbound.mapping.requiredHint`, { fields: missingRequired.join(', ') })}
                </div>
            )}

            <table className={classes.table}>
                <thead>
                    <tr>
                        <th className={classes.th}>{t(`${NS}:mapping.col.jcProperty`)}</th>
                        <th className={classes.th}>{t(`${NS}:mapping.col.sfField`)} — {SF_OBJECT}</th>
                        <th className={classes.th} style={{ width: 48 }} />
                    </tr>
                </thead>
                <tbody>
                    {rows.length === 0 && (
                        <tr><td colSpan={3} className={classes.empty}>{t(`${NS}:mapping.empty`)}</td></tr>
                    )}
                    {rows.map((row, i) => (
                        <tr key={i} className={classes.tr}>
                            <td className={classes.td}>
                                <Dropdown key={`jc-${jcOptions.length}-${i}`} size="small" value={row.jcProperty || ''}
                                    data={[{ label: t(`${NS}:mapping.selectProperty`), value: '' }, ...jcOptions]}
                                    onChange={(e, item) => updateRow(i, 'jcProperty', item?.value ?? '')} />
                            </td>
                            <td className={classes.td}>
                                <Dropdown key={`sf-${leadOptions.length}-${i}`} size="small" value={row.sfField || ''}
                                    data={[{ label: t(`${NS}:mapping.selectField`), value: '' }, ...leadOptions]}
                                    onChange={(e, item) => updateRow(i, 'sfField', item?.value ?? '')} />
                            </td>
                            <td className={classes.td}>
                                <Button size="small" variant="ghost" color="danger" label="✕"
                                    title={t(`${NS}:action.remove`)} onClick={() => removeRow(i)} />
                            </td>
                        </tr>
                    ))}
                </tbody>
            </table>

            {msg && (
                <div className={`${classes.alert} ${msg.ok ? classes.alertSuccess : classes.alertError}`}>
                    {msg.text}
                </div>
            )}
        </div>
    );
}
