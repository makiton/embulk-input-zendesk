package org.embulk.input.zendesk.utils;

import org.embulk.config.ConfigException;
import org.embulk.input.zendesk.ZendeskInputPlugin;
import org.embulk.input.zendesk.models.Target;
import org.embulk.input.zendesk.services.ZendeskSupportAPIService;
import org.embulk.spi.Exec;
import org.slf4j.Logger;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ZendeskValidatorUtils
{
    private ZendeskValidatorUtils(){}

    private static final Logger logger = Exec.getLogger(ZendeskValidatorUtils.class);

    public static void validateInputTask(final ZendeskInputPlugin.PluginTask task, final ZendeskSupportAPIService zendeskSupportAPIService)
    {
        validateHost(task.getLoginUrl());
        validateAppMarketPlace(task.getAppMarketPlaceIntegrationName().isPresent(),
                task.getAppMarketPlaceAppId().isPresent(),
                task.getAppMarketPlaceOrgId().isPresent());
        validateCredentials(task, zendeskSupportAPIService);
        validateInclude(task.getIncludes(), task.getTarget());
        validateIncremental(task);
    }

    private static void validateHost(final String loginUrl)
    {
        final Matcher matcher = Pattern.compile(ZendeskConstants.Regex.HOST).matcher(loginUrl);
        if (!matcher.matches()) {
            throw new ConfigException(String.format("Login URL, '%s', is unmatched expectation. " +
                    "It should be followed this format: https://abc.zendesk.com/", loginUrl));
        }
    }

    private static void validateInclude(final List<String> includes, final Target target)
    {
        if (includes != null && !includes.isEmpty()) {
            if (!ZendeskUtils.isSupportInclude(target)) {
                logger.warn("Target: '{}' doesn't support include size loading. Will be ignored include option", target.toString());
            }
        }
    }

    private static void validateCredentials(final ZendeskInputPlugin.PluginTask task, final ZendeskSupportAPIService zendeskSupportAPIService)
    {
        switch (task.getAuthenticationMethod()) {
            case OAUTH:
                if (!task.getAccessToken().isPresent()) {
                    throw new ConfigException(String.format("access_token is required for authentication method '%s'",
                            task.getAuthenticationMethod().name().toLowerCase()));
                }
                break;
            case TOKEN:
                if (!task.getUsername().isPresent() || !task.getToken().isPresent()) {
                    throw new ConfigException(String.format("username and token are required for authentication method '%s'",
                            task.getAuthenticationMethod().name().toLowerCase()));
                }
                break;
            case BASIC:
                if (!task.getUsername().isPresent() || !task.getPassword().isPresent()) {
                    throw new ConfigException(String.format("username and password are required for authentication method '%s'",
                            task.getAuthenticationMethod().name().toLowerCase()));
                }
                break;
            default:
                throw new ConfigException("Unknown authentication method");
        }

        // Validate credentials by sending one request to users.json. It Should always have at least one user

        zendeskSupportAPIService.validateCredential(String.format("%s%s/users.json?per_page=1", task.getLoginUrl(),
                ZendeskConstants.Url.API));
    }

    private static void validateAppMarketPlace(final boolean isAppMarketIntegrationNamePresent,
                                        final boolean isAppMarketAppIdPresent,
                                        final boolean isAppMarketOrgIdPresent)
    {
        final boolean isAllAvailable =
                isAppMarketIntegrationNamePresent && isAppMarketAppIdPresent && isAppMarketOrgIdPresent;
        final boolean isAllUnAvailable =
                !isAppMarketIntegrationNamePresent && !isAppMarketAppIdPresent && !isAppMarketOrgIdPresent;
        // All or nothing needed
        if (!(isAllAvailable || isAllUnAvailable)) {
            throw new ConfigException("All of app_marketplace_integration_name, app_marketplace_org_id, " +
                    "app_marketplace_app_id " +
                    "are required to fill out for Apps Marketplace API header");
        }
    }

    private static void validateIncremental(final ZendeskInputPlugin.PluginTask task)
    {
        if (task.getIncremental()) {
            if (!task.getDedup()) {
                logger.warn("You've selected to skip de-duplicating records, result may contain duplicated data");
            }

            if (!ZendeskUtils.isSupportIncremental(task.getTarget()) && task.getStartTime().isPresent()) {
                logger.warn(String.format("target: '%s' don't support incremental export API. Will be ignored start_time option",
                        task.getTarget()));
            }
        }
    }
}
