import React, { Suspense, lazy } from 'react';
import { registry } from '@jahia/ui-extender';
import { Loader } from '@jahia/moonstone';

const SfdcAdmin = lazy(() => import('./SfdcAdmin'));

export const registerSfdcAdminRoutes = () => {
    // Parent accordion entry in jcontent left nav
    const accordionKey  = 'sfdcConnectorAccordion';


    // Main admin panel
    registry.add('adminRoute', 'jcustomer-sfdc-connector', {
        targets: ['administration-sites:999'],
        icon: window.jahia.moonstone.toIconComponent('<svg xmlns="http://www.w3.org/2000/svg" height="24" width="24" viewBox="0 0 520 520" fill="#fff"><path d="m238 312-38 176c-2 10 11 17 17 9l210-260c6-7 1-17-7-17H308c-8 0-13-9-9-15l99-162c6-10-1-23-13-23H208c-11 0-20 7-24 17l-83 243c-3 10 4 20 14 20h114c6 0 11 6 9 12z"/></svg>'),
        label: 'jcustomer-sfdc-connector:admin.panel.label',
        path: '/sfdc-connector',
        defaultPath: '/sfdc-connector',
        isSelectable: true,
        requireModuleInstalledOnSite: 'jcustomer-sfdc-connector',
        render: () => (
            <Suspense fallback={<div style={{ padding: 48, display: 'flex', justifyContent: 'center' }}><Loader size="big" /></div>}>
                <SfdcAdmin />
            </Suspense>
        )
    });
};
