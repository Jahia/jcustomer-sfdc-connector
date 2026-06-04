import React, { useState, useEffect, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import { Button, Dropdown, Loader, Separator, Tab, TabItem, Typography } from '@jahia/moonstone';
import { getSfdcFields, getJcProperties, getMapping, saveMapping } from '../api';
import classes from './MappingTab.module.css';

const NS = 'jcustomer-sfdc-connector';
const SFDC_OBJECTS = ['Contact', 'Account'];

export default function MappingTab() {
    const { t } = useTranslation(NS);
    const [sfObject,  setSfObject]  = useState('Contact');
    const [sfFields,  setSfFields]  = useState({ Contact: [], Account: [] });
    const [jcProps,   setJcProps]   = useState([]);
    const [mappings,  setMappings]  = useState({ Contact: [], Account: [] });
    const [loading,   setLoading]   = useState(true);
    const [saving,    setSaving]    = useState(false);
    const [msg,       setMsg]       = useState(null);

    const load = useCallback(async () => {
        setLoading(true);
        setMsg(null);

        // allSettled: each request is independent — a failed SF connection
        // does NOT prevent jCustomer properties or saved mappings from loading
        const [fieldsR, propsR, mappingR] = await Promise.allSettled([
            getSfdcFields(), getJcProperties(), getMapping()
        ]);

        if (fieldsR.status === 'fulfilled') {
            const fields = fieldsR.value;
            setSfFields({
                Contact: Array.isArray(fields?.Contact) ? fields.Contact : [],
                Account: Array.isArray(fields?.Account) ? fields.Account : [],
            });
        }

        if (propsR.status === 'fulfilled') {
            setJcProps(Array.isArray(propsR.value) ? propsR.value : []);
        } else {
            // keep existing list — don't wipe on transient error
        }

        if (mappingR.status === 'fulfilled') {
            const mapping = mappingR.value;
            setMappings({
                Contact: Array.isArray(mapping?.Contact) ? mapping.Contact : [],
                Account: Array.isArray(mapping?.Account) ? mapping.Account : [],
            });
        }

        // Surface any errors without blocking the render
        const errors = [fieldsR, propsR, mappingR]
            .filter(r => r.status === 'rejected')
            .map(r => r.reason?.message)
            .filter(Boolean);
        if (errors.length) setMsg({ ok: false, text: errors.join(' | ') });

        setLoading(false);
    }, []);

    useEffect(() => { load(); }, [load]);

    const addRow    = () => setMappings(m => ({ ...m, [sfObject]: [...m[sfObject], { sfField: '', jcProperty: '' }] }));
    const removeRow = i  => setMappings(m => ({ ...m, [sfObject]: m[sfObject].filter((_, idx) => idx !== i) }));
    const updateRow = (i, key, val) => setMappings(m => {
        const rows = [...m[sfObject]];
        rows[i] = { ...rows[i], [key]: val };
        return { ...m, [sfObject]: rows };
    });

    const handleSave = async () => {
        setSaving(true); setMsg(null);
        try {
            await saveMapping({ sfObject, mappings: mappings[sfObject] });
            setMsg({ ok: true, text: t(`${NS}:mapping.saved`, { obj: sfObject }) });
        } catch (e) {
            setMsg({ ok: false, text: e.message });
        } finally { setSaving(false); }
    };

    const buildSfOptions = obj => (sfFields[obj] || []).map(f => ({
        label: `${f.label || f.name} (${f.name})`,
        value: obj === 'Account' && !f.name.startsWith('Account.') ? `Account.${f.name}` : f.name
    }));

    const jcOptions     = jcProps.map(p => ({ label: `${p.name}${p.multivalued ? ' [multi]' : ''}`, value: p.id }));
    const currentRows   = mappings[sfObject] || [];
    const sfDropOptions = buildSfOptions(sfObject);

    // Keys that change when data changes — forces Dropdown to remount with fresh options
    const jcDropKey = `jc-${jcOptions.length}`;
    const sfDropKey = `sf-${sfDropOptions.length}-${sfObject}`;

    if (loading) return <div className={classes.center}><Loader size="big" /></div>;

    return (
        <div className={classes.wrapper}>
            <div className={classes.header}>
                <div>
                    <Typography variant="heading" weight="bold">{t(`${NS}:mapping.title`)}</Typography>
                    <Typography variant="body" className={classes.subtitle}>{t(`${NS}:mapping.subtitle`)}</Typography>
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

            <Tab>
                {SFDC_OBJECTS.map(o => (
                    <TabItem key={o} label={o} isSelected={sfObject === o}
                        onClick={() => { setSfObject(o); setMsg(null); }} />
                ))}
            </Tab>

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
                                <Dropdown key={`${jcDropKey}-${i}`} size="small" value={row.jcProperty || ''}
                                    data={[{ label: t(`${NS}:mapping.selectProperty`), value: '' }, ...jcOptions]}
                                    onChange={(e, item) => updateRow(i, 'jcProperty', item?.value ?? '')} />
                            </td>
                            <td className={classes.td}>
                                <Dropdown key={`${sfDropKey}-${i}`} size="small" value={row.sfField || ''}
                                    data={[{ label: t(`${NS}:mapping.selectField`), value: '' }, ...sfDropOptions]}
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
