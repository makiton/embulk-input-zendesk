package org.embulk.input.zendesk.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import org.apache.http.client.utils.URIBuilder;
import org.embulk.config.TaskReport;
import org.embulk.input.zendesk.RecordImporter;
import org.embulk.input.zendesk.ZendeskInputPlugin;
import org.embulk.input.zendesk.clients.ZendeskRestClient;
import org.embulk.input.zendesk.models.Target;
import org.embulk.input.zendesk.stream.paginator.OrganizationSpliterator;
import org.embulk.input.zendesk.stream.paginator.UserEventSpliterator;
import org.embulk.input.zendesk.stream.paginator.UserSpliterator;
import org.embulk.input.zendesk.utils.ZendeskConstants;
import org.embulk.input.zendesk.utils.ZendeskDateUtils;
import org.embulk.input.zendesk.utils.ZendeskUtils;
import org.embulk.spi.DataException;
import org.embulk.spi.Exec;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public class ZendeskUserEventService implements ZendeskService
{
    protected ZendeskInputPlugin.PluginTask task;

    private ZendeskRestClient zendeskRestClient;

    public ZendeskUserEventService(final ZendeskInputPlugin.PluginTask task)
    {
        this.task = task;
    }

    @VisibleForTesting
    protected ZendeskRestClient getZendeskRestClient()
    {
        if (zendeskRestClient == null) {
            zendeskRestClient = new ZendeskRestClient();
        }
        return zendeskRestClient;
    }

    public boolean isSupportIncremental()
    {
        return true;
    }

    @Override
    public TaskReport execute(final int taskIndex, final RecordImporter recordImporter)
    {
        final TaskReport taskReport = Exec.newTaskReport();

        if (Exec.isPreview()) {
            JsonNode  jsonNode = mockJsonNode();
            recordImporter.addRecord(jsonNode.get(0));
        }
        else {
            final List<JsonNode> organizations = StreamSupport.stream(new OrganizationSpliterator(buildOrganizationURI(), getZendeskRestClient(), task), false)
                    .collect(Collectors.toList());
            final Set<String> knownUserIds = ConcurrentHashMap.newKeySet();
            final AtomicLong lastTime = new AtomicLong(0);
            organizations.parallelStream().forEach(
                    organization -> {
                        Stream<JsonNode> stream = StreamSupport.stream(new UserSpliterator(buildOrganizationWithUserURI(organization.get("url").asText()),
                                getZendeskRestClient(), task, Exec.isPreview()), true);

                        if (task.getDedup()) {
                            stream = stream.filter(item -> knownUserIds.add(item.get("id").asText()));
                        }

                        stream.forEach(s ->
                                {
                                    Stream<JsonNode> userEventStream = StreamSupport.stream(new UserEventSpliterator(s.get("id").asText(), buildUserEventURI(s.get("id").asText()),
                                            getZendeskRestClient(), task, Exec.isPreview()), true);

                                    if (task.getEndTime().isPresent()) {
                                        long endTime = ZendeskDateUtils.isoToEpochSecond(task.getEndTime().get());
                                        userEventStream = userEventStream.filter(item -> ZendeskDateUtils.isoToEpochSecond(item.get("created_at").asText()) <= endTime);
                                    }
                                    userEventStream.forEach(item -> {
                                                if (task.getIncremental()) {
                                                    long temp = ZendeskDateUtils.isoToEpochSecond(item.get("created_at").asText());
                                                    if (temp > lastTime.get()) {
                                                        // we need to store the created_at time of the latest records.
                                                        // So we can calculate the start_time for configDiff in case there is no specific end_time
                                                        lastTime.set(temp);
                                                    }
                                                }
                                                recordImporter.addRecord(item);
                                            });
                                });
                    }
            );
            storeStartTimeForConfigDiff(taskReport, lastTime.get());
        }

        return taskReport;
    }

    @Override
    public JsonNode getData(final String path, final int page, final boolean isPreview, final long startTime)
    {
        return new ObjectMapper().createObjectNode().set(task.getTarget().getJsonName(), mockJsonNode());
    }

    private String buildOrganizationURI()
    {
        return ZendeskUtils.getURIBuilder(task.getLoginUrl())
                .setPath(ZendeskConstants.Url.API + "/" + Target.ORGANIZATIONS.getJsonName())
                .setParameter("per_page", "100")
                .setParameter("page", "1")
                .toString();
    }

    private String buildOrganizationWithUserURI(final String path)
    {
        return path.replace(".json", "")
                + "/users.json?per_page=100&page=1";
    }

    private String buildUserEventURI(final String userID)
    {
        final URIBuilder uriBuilder = ZendeskUtils.getURIBuilder(task.getLoginUrl())
                .setPath(ZendeskConstants.Url.API_USER_EVENT)
                .setParameter("identifier", task.getProfileSource().get() + ":user_id:" + userID);

        task.getUserEventSource().ifPresent(eventSource -> uriBuilder.setParameter("source", eventSource));
        task.getUserEventType().ifPresent(eventType -> uriBuilder.setParameter("type", eventType));
        task.getStartTime().ifPresent(startTime -> {
            try {
                uriBuilder.setParameter("start_time", ZendeskDateUtils.convertToDateTimeFormat(startTime, ZendeskConstants.Misc.ISO_INSTANT));
            }
            catch (DataException e) {
                uriBuilder.setParameter("start_time", ZendeskDateUtils.convertToDateTimeFormat(Instant.EPOCH.toString(), ZendeskConstants.Misc.ISO_INSTANT));
            }
        });

        task.getEndTime().ifPresent(endTime -> uriBuilder.setParameter("end_time", ZendeskDateUtils.convertToDateTimeFormat(endTime, ZendeskConstants.Misc.ISO_INSTANT)));

        return uriBuilder.toString();
    }

    private JsonNode mockJsonNode()
    {
        try {
            String mockData = "[\n" +
                    "    {\n" +
                    "      \"id\": \"5c7f31aef8df240001e60bbf\",\n" +
                    "      \"type\": \"remove_from_cart\",\n" +
                    "      \"source\": \"shopify\",\n" +
                    "      \"description\": \"\",\n" +
                    "      \"authenticated\": true,\n" +
                    "      \"created_at\": \"2019-03-06T02:34:22Z\",\n" +
                    "      \"received_at\": \"2019-03-06T02:34:22Z\",\n" +
                    "      \"properties\": {\n" +
                    "        \"model\": 221,\n" +
                    "        \"size\": 6\n" +
                    "      },\n" +
                    "      \"user_id\": \"12312354234\"\n" +
                    "    }\n" +
                    "]";

            return new ObjectMapper().readTree(mockData);
        }
        catch (IOException ex) {
            throw new RuntimeException("Error when mock data for guess or preview " + ex.getMessage());
        }
    }

    private void storeStartTimeForConfigDiff(final TaskReport taskReport, final long lastTime)
    {
        long nextStartTime;
        if (task.getIncremental()) {
            // no records
            if (lastTime == 0) {
                nextStartTime = Instant.now().getEpochSecond();
            }
            else {
                // have end_time -> store min of (end_time + 1, last_time + 1)
                // end_time can be set in the future
                if (task.getEndTime().isPresent()) {
                    nextStartTime = Math.min(ZendeskDateUtils.isoToEpochSecond(task.getEndTime().get()) + 1,  lastTime + 1);
                }
                else {
                    // don't have end_time -> store last_time + 1
                    nextStartTime = lastTime + 1;
                }
            }

            taskReport.set(ZendeskConstants.Field.START_TIME, nextStartTime);

            if (task.getEndTime().isPresent()) {
                long endTime = ZendeskDateUtils.isoToEpochSecond(task.getEndTime().get());
                long startTime = ZendeskDateUtils.isoToEpochSecond(task.getEndTime().get());
                taskReport.set(ZendeskConstants.Field.END_TIME, nextStartTime + endTime - startTime);
            }
        }
    }
}
