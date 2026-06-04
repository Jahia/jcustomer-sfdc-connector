import { registry } from '@jahia/ui-extender';

export default function () {
    registry.add('callback', 'jcustomer-sfdc-connector', {
        targets: ['jahiaApp-init:50'],
        callback: async () => {
            const { default: init } = await import('./init');
            await init();
        }
    });
}
