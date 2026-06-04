import i18next from 'i18next';
import en from '../main/resources/javascript/locales/en.json';
import fr from '../main/resources/javascript/locales/fr.json';
import { registerSfdcAdminRoutes } from './SfdcAdmin/SfdcAdmin.routes';

const NS = 'jcustomer-sfdc-connector';

const registerResources = () => {
    [['en', en], ['fr', fr]].forEach(([lang, resource]) => {
        const nsData = resource[NS];
        if (nsData && !i18next.hasResourceBundle(lang, NS)) {
            i18next.addResourceBundle(lang, NS, nsData, true, true);
        }
    });
};

export default async function () {
    registerResources();
    await i18next.loadNamespaces(NS);
    registerSfdcAdminRoutes();
}
