import React, { useState, useCallback, Suspense, lazy } from 'react';
import { useTranslation } from 'react-i18next';
import { Header, LayoutContent, Loader, Tab, TabItem } from '@jahia/moonstone';
import layout from './ui/layout.module.css';

const OverviewTab   = lazy(() => import('./tabs/OverviewTab'));
const ConnectionTab = lazy(() => import('./tabs/ConnectionTab'));
const DirectionPanel = lazy(() => import('./tabs/DirectionPanel'));

const NS = 'jcustomer-sfdc-connector';
// Overview first; the connection is shared by both directions.
const TABS = ['overview', 'connection', 'inbound', 'outbound'];

const fallback = <div style={{ display: 'flex', justifyContent: 'center', padding: 48 }}><Loader size="big" /></div>;

export default function SfdcAdmin() {
    const { t } = useTranslation(NS);
    // tab: top-level tab; sub: last sub-tab opened per direction (mapping | triggers | history)
    const [nav, setNav] = useState({ tab: 'overview', sub: { inbound: 'mapping', outbound: 'mapping' } });

    /** Opens a top-level tab, and for a direction optionally one of its sub-tabs. */
    const go = useCallback((tab, sub) => setNav(n => ({
        tab,
        sub: sub ? { ...n.sub, [tab]: sub } : n.sub
    })), []);

    return (
        <div style={{ display: 'flex', flexDirection: 'column', height: '100%', overflow: 'hidden', width: '100%', minWidth: 0, contain: 'inline-size' }}>
            <LayoutContent
                header={(
                    <Header
                        title={t(`${NS}:admin.panel.title`)}
                        toolbarLeft={[
                            <div key="tabs" className={layout.tabsScroll}>
                                <Tab>
                                    {TABS.map(key => (
                                        <TabItem key={key} label={t(`${NS}:admin.tab.${key}`)}
                                            isSelected={nav.tab === key} onClick={() => go(key)} />
                                    ))}
                                </Tab>
                            </div>
                        ]}
                    />
                )}
                content={(
                    // LayoutContent's wrapper is a wrapping flex column: its width follows the content's
                    // max-content, so the content must not report one (else it overflows narrow panes).
                    <div style={{ height: '100%', overflowY: 'auto', width: '100%', minWidth: 0, contain: 'inline-size' }}>
                        <Suspense fallback={fallback}>
                            {nav.tab === 'overview'   && <OverviewTab onNavigate={go} />}
                            {nav.tab === 'connection' && <ConnectionTab />}
                            {(nav.tab === 'inbound' || nav.tab === 'outbound') && (
                                <DirectionPanel key={nav.tab} direction={nav.tab}
                                    sub={nav.sub[nav.tab]} onSub={sub => go(nav.tab, sub)} />
                            )}
                        </Suspense>
                    </div>
                )}
            />
        </div>
    );
}
