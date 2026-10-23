package org.jahia.community.modules.jcustomer.sfdc.config;

import org.jahia.services.content.JCRTemplate;
import org.jahia.services.content.decorator.JCRSiteNode;
import org.jahia.services.sites.JahiaSitesService;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Modified;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves the Jahia site the connector works for. The site key selects the jExperience settings
 * (and therefore the jCustomer instance and its third-party key) and scopes the events sent to
 * jCustomer; it also names the site whose administrators may manage the connector.
 *
 * <p>Resolution order, the first that applies wins:
 * <ol>
 *   <li>an explicit site key supplied by the caller (the site the admin UI is open on), when it is an
 *       existing site;</li>
 *   <li>{@code jcustomer.site.key} from the {@code org.jahia.community.jcustomer.sfdc} configuration,
 *       when set and an existing site;</li>
 *   <li>the single site that has the {@code jexperience} module installed.</li>
 * </ol>
 * Returns {@code null} when none applies (no jExperience site, or several and no key configured),
 * after logging what to configure. No project-specific default exists.
 *
 * <p>Thread-safe. The site scan is cached for five minutes; a configuration change clears it.
 */
@Component(service = SiteKeyResolver.class, immediate = true,
        configurationPid = "org.jahia.community.jcustomer.sfdc")
public class SiteKeyResolver {

    private static final Logger logger = LoggerFactory.getLogger(SiteKeyResolver.class);

    private static final String JEXPERIENCE_MODULE = "jexperience";
    private static final String SYSTEM_SITE        = "systemsite";
    private static final long   CACHE_MILLIS       = 5 * 60_000L;

    @interface Config {
        String jcustomer_site_key() default "";
    }

    /** Sites found on the server, and those among them with jExperience installed. */
    private static final class Scan {
        final List<String> allSites;
        final List<String> jexperienceSites;
        final long at;
        Scan(List<String> allSites, List<String> jexperienceSites) {
            this.allSites = allSites;
            this.jexperienceSites = jexperienceSites;
            this.at = System.currentTimeMillis();
        }
    }

    private volatile String configuredSiteKey = "";
    private volatile Scan   cachedScan;
    private volatile String lastWarning;

    @Activate
    @Modified
    public void activate(Config config) {
        String v = config.jcustomer_site_key();
        this.configuredSiteKey = v == null ? "" : v.trim();
        this.cachedScan = null;
    }

    /** Resolves the connector site without a caller-supplied site key (jobs, login sync). */
    public String resolve() {
        return resolve(null);
    }

    /**
     * Resolves the connector site.
     *
     * @param explicitSiteKey site key supplied by the caller (for example the site the admin UI is open
     *                        on); ignored when blank, {@code systemsite} or not an existing site
     * @return the site key to use, or {@code null} when none can be determined
     */
    public String resolve(String explicitSiteKey) {
        Scan scan = scan();
        String explicit = explicitSiteKey == null ? "" : explicitSiteKey.trim();

        if (!explicit.isEmpty() && !SYSTEM_SITE.equals(explicit)) {
            if (scan == null || scan.allSites.contains(explicit)) {
                return explicit;
            }
            warnOnce("[SFDC-Site] Requested site key '" + explicit + "' is not a site on this server - ignoring it");
        }

        String configured = configuredSiteKey;
        if (!configured.isEmpty()) {
            if (scan == null || scan.allSites.contains(configured)) {
                return configured;
            }
            warnOnce("[SFDC-Site] jcustomer.site.key='" + configured + "' is not a site on this server - "
                    + "falling back to auto-detection of the jExperience site");
        }

        if (scan == null) {
            logger.error("[SFDC-Site] Could not list the sites and no valid jcustomer.site.key is configured - "
                    + "set jcustomer.site.key in org.jahia.community.jcustomer.sfdc");
            return null;
        }
        if (scan.jexperienceSites.size() == 1) {
            return scan.jexperienceSites.get(0);
        }
        if (scan.jexperienceSites.isEmpty()) {
            warnOnce("[SFDC-Site] No site has the jexperience module installed - enable jExperience on the site "
                    + "the connector serves, or set jcustomer.site.key in org.jahia.community.jcustomer.sfdc");
        } else {
            warnOnce("[SFDC-Site] Several sites have jExperience installed " + scan.jexperienceSites
                    + " - set jcustomer.site.key in org.jahia.community.jcustomer.sfdc to the one the connector serves");
        }
        return null;
    }

    private Scan scan() {
        Scan s = cachedScan;
        if (s != null && System.currentTimeMillis() - s.at < CACHE_MILLIS) {
            return s;
        }
        try {
            s = JCRTemplate.getInstance().doExecuteWithSystemSession(session -> {
                List<String> all = new ArrayList<>();
                List<String> withJexperience = new ArrayList<>();
                for (JCRSiteNode site : JahiaSitesService.getInstance().getSitesNodeList(session)) {
                    String key = site.getSiteKey();
                    if (key == null || SYSTEM_SITE.equals(key)) {
                        continue;
                    }
                    all.add(key);
                    List<String> modules = site.getInstalledModules();
                    if (modules != null && modules.contains(JEXPERIENCE_MODULE)) {
                        withJexperience.add(key);
                    }
                }
                return new Scan(all, withJexperience);
            });
            cachedScan = s;
            return s;
        } catch (Exception e) {
            logger.warn("[SFDC-Site] Could not scan the sites: {}", e.getMessage());
            return null;
        }
    }

    /** Logs a warning once until a different one is raised, so per-login resolution does not flood the log. */
    private void warnOnce(String message) {
        if (!message.equals(lastWarning)) {
            lastWarning = message;
            logger.warn(message);
        } else {
            logger.debug(message);
        }
    }
}
