import React, { Suspense, lazy } from 'react';
import { useTranslation } from 'react-i18next';
import { Loader, Tab, TabItem } from '@jahia/moonstone';

const MappingEditor       = lazy(() => import('./MappingEditor'));
const InboundTriggersTab  = lazy(() => import('./InboundTriggersTab'));
const OutboundTriggersTab = lazy(() => import('./OutboundTriggersTab'));
const HistoryTab          = lazy(() => import('./HistoryTab'));

const NS = 'jcustomer-sfdc-connector';
// The same three sub-tabs in both directions.
const SUB = ['mapping', 'triggers', 'history'];

/**
 * One sync direction: Mapping (what goes where), Triggers (when it runs, and run now) and History
 * (what it did).
 *
 * @param direction 'inbound' (Salesforce → jCustomer) or 'outbound' (jCustomer → Salesforce)
 */
export default function DirectionPanel({ direction, sub, onSub }) {
    const { t } = useTranslation(NS);
    const current = SUB.includes(sub) ? sub : 'mapping';

    return (
        <div style={{ display: 'flex', flexDirection: 'column', minWidth: 0 }}>
            <div style={{ padding: '8px 24px 0' }}>
                <Tab>
                    {SUB.map(k => (
                        <TabItem key={k} label={t(`${NS}:admin.sub.${k}`)}
                            isSelected={current === k} onClick={() => onSub(k)} />
                    ))}
                </Tab>
            </div>
            <Suspense fallback={<div style={{ display: 'flex', justifyContent: 'center', padding: 48 }}><Loader size="big" /></div>}>
                {current === 'mapping'  && <MappingEditor direction={direction} />}
                {current === 'triggers' && (direction === 'inbound' ? <InboundTriggersTab /> : <OutboundTriggersTab />)}
                {current === 'history'  && <HistoryTab direction={direction} />}
            </Suspense>
        </div>
    );
}
