import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Dropdown, Loader, Separator, Tab, TabItem, Typography } from '@jahia/moonstone';
import { getSfdcFields, getJcProperties, getOutboundMapping, saveOutboundMapping } from '../api';
import { exportMappingCsv, exportMappingJson } from '../exportMapping';
import classes from './MappingTab.module.css';

const NS = 'jcustomer-sfdc-connector';
const SFDC_OBJECTS = ['Lead', 'Contact'];             // outbound targets
const REQUIRED_TO_CREATE = { Lead: ['LastName', 'Company'], Contact: ['LastName'] };
const emptyByObject = () => SFDC_OBJECTS.reduce((a, o) => ({ ...a, [o]: [] }), {});

export default function OutboundMappingTab() {
    const { t } = useTranslation(NS);
    const [sfObject, setSfObject] = useState('Lead');
    const [sfFields, setSfFields] = useState(emptyByObject());
    const [jcProps,  setJcProps]  = useState([]);
    const [mappings, setMappings] = useState(emptyByObject());
    const [loading,  setLoading]  = useState(true);
    const [saving,   setSaving]   = useState(false);
    const [msg,      setMsg]      = useState(null);

    const load = useCallback(async () => {
        setLoading(true); setMsg(null);
        const [fieldsR, propsR, mappingR] = await Promise.allSettled([
            getSfdcFields(), getJcProperties(), getOutboundMapping()
        ]);

        if (fieldsR.status === 'fulfilled') {
            const f = fieldsR.value || {};
            setSfFields(SFDC_OBJECTS.reduce((a, o) => ({ ...a, [o]: Array.isArray(f[o]) ? f[o] : [] }), {}));
        }
        if (propsR.status === 'fulfilled') {
            setJcProps(Array.isArray(propsR.value) ? propsR.value : []);
        }
        if (mappingR.status === 'fulfilled') {
            const m = mappingR.value || {};
            setMappings(SFDC_OBJECTS.reduce((a, o) => ({ ...a, [o]: Array.isArray(m[o]) ? m[o] : [] }), {}));
        }

        const errors = [fieldsR, propsR, mappingR]
            .filter(r => r.status === 'rejected').map(r => r.reason?.message).filter(Boolean);
        if (errors.length) setMsg({ ok: false, text: errors.join(' | ') });
        setLoading(false);
    }, []);

    useEffect(() => { load(); }, [load]);

    const addRow    = () => setMappings(m => ({ ...m, [sfObject]: [...m[sfObject], { jcProperty: '', sfField: '' }] }));
    const removeRow = i  => setMappings(m => ({ ...m, [sfObject]: m[sfObject].filter((_, idx) => idx !== i) }));
    const updateRow = (i, key, val) => setMappings(m => {
        const rows = [...m[sfObject]];
        rows[i] = { ...rows[i], [key]: val };
        return { ...m, [sfObject]: rows };
    });

    const handleSave = async () => {
        setSaving(true); setMsg(null);
        try {
            await saveOutboundMapping({ sfObject, mappings: mappings[sfObject] });
            setMsg({ ok: true, text: t(`${NS}:outbound.mapping.saved`, { obj: sfObject }) });
        } catch (e) {
            setMsg({ ok: false, text: e.message });
        } finally { setSaving(false); }
    };

    const handleExport = fmt => {
        const fn = fmt === 'csv' ? exportMappingCsv : exportMappingJson;
        const n = fn('outbound', sfObject, mappings[sfObject]);
        setMsg(n === 0
            ? { ok: false, text: t(`${NS}:mapping.export.empty`, { obj: sfObject }) }
            : { ok: true, text: t(`${NS}:mapping.export.done`, { obj: sfObject, count: n, fmt: fmt.toUpperCase() }) });
    };

    const jcOptions   = jcProps.map(p => ({ label: `${p.name}${p.multivalued ? ' [multi]' : ''}`, value: p.id }));
    const sfOptions   = (sfFields[sfObject] || []).map(f => ({ label: `${f.label || f.name} (${f.name})`, value: f.name }));
    const currentRows = mappings[sfObject] || [];
    const mappedFields = new Set(currentRows.map(r => r.sfField));
    const missingRequired = (REQUIRED_TO_CREATE[sfObject] || []).filter(f => !mappedFields.has(f));

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
                    <Button variant="ghost" label={t(`${NS}:mapping.export.csv`)}
                        title={t(`${NS}:mapping.export.csvTip`, { obj: sfObject })} onClick={() => handleExport('csv')} />
                    <Button variant="ghost" label={t(`${NS}:mapping.export.json`)}
                        title={t(`${NS}:mapping.export.jsonTip`, { obj: sfObject })} onClick={() => handleExport('json')} />
                    <Button variant="ghost" label={t(`${NS}:action.reload`)} onClick={load} isDisabled={loading} />
                </div>
            </div>

            <Separator />

            <Tab>
                {SFDC_OBJECTS.map(o => (
                    <TabItem key={o} label={o} isSelected={sfObject === o}
                        onClick={() => { setSfObject(o); setMsg(null); }} />
                ))}
            </Tab>

            {missingRequired.length > 0 && (
                <div className={`${classes.alert} ${classes.alertError}`}>
                    {t(`${NS}:outbound.mapping.requiredHint`, { obj: sfObject, fields: missingRequired.join(', ') })}
                </div>
            )}

            <table className={classes.table}>
                <thead>
                    <tr>
                        <th className={classes.th}>{t(`${NS}:mapping.col.jcProperty`)}</th>
                        <th className={classes.th}>{t(`${NS}:mapping.col.sfField`)} — {sfObject}</th>
                        <th className={classes.th} style={{ width: 48 }} />
                    </tr>
                </thead>
                <tbody>
                    {currentRows.length === 0 && (
                        <tr><td colSpan={3} className={classes.empty}>{t(`${NS}:mapping.empty`)}</td></tr>
                    )}
                    {currentRows.map((row, i) => (
                        <tr key={i} className={classes.tr}>
                            <td className={classes.td}>
                                <Dropdown key={`jc-${jcOptions.length}-${sfObject}-${i}`} size="small" value={row.jcProperty || ''}
                                    data={[{ label: t(`${NS}:mapping.selectProperty`), value: '' }, ...jcOptions]}
                                    onChange={(e, item) => updateRow(i, 'jcProperty', item?.value ?? '')} />
                            </td>
                            <td className={classes.td}>
                                <Dropdown key={`sf-${sfOptions.length}-${sfObject}-${i}`} size="small" value={row.sfField || ''}
                                    data={[{ label: t(`${NS}:mapping.selectField`), value: '' }, ...sfOptions]}
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
