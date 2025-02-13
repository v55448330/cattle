package io.cattle.platform.configitem.version.impl;

import io.cattle.platform.agent.AgentLocator;
import io.cattle.platform.agent.RemoteAgent;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.async.utils.AsyncUtils;
import io.cattle.platform.async.utils.TimeoutException;
import io.cattle.platform.configitem.events.ConfigUpdate;
import io.cattle.platform.configitem.exception.ConfigTimeoutException;
import io.cattle.platform.configitem.model.Client;
import io.cattle.platform.configitem.model.ItemVersion;
import io.cattle.platform.configitem.request.ConfigUpdateItem;
import io.cattle.platform.configitem.request.ConfigUpdateRequest;
import io.cattle.platform.configitem.version.ConfigItemStatusManager;
import io.cattle.platform.configitem.version.dao.ConfigItemStatusDao;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.ConfigItemStatus;
import io.cattle.platform.deferred.util.DeferredUtils;
import io.cattle.platform.eventing.EventCallOptions;
import io.cattle.platform.eventing.EventProgress;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.eventing.RetryCallback;
import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.server.context.ServerContext;
import io.cattle.platform.server.context.ServerContext.BaseProtocol;
import io.cattle.platform.util.type.CollectionUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.netflix.config.DynamicBooleanProperty;
import com.netflix.config.DynamicIntProperty;
import com.netflix.config.DynamicLongProperty;

public class ConfigItemStatusManagerImpl implements ConfigItemStatusManager {

    private static final DynamicBooleanProperty BLOCK = ArchaiusUtil.getBoolean("item.migration.block.on.failure");
    private static final DynamicIntProperty RETRY = ArchaiusUtil.getInt("item.wait.for.event.tries");
    private static final DynamicLongProperty TIMEOUT = ArchaiusUtil.getLong("item.wait.for.event.timeout.millis");

    private static final Logger log = LoggerFactory.getLogger(ConfigItemStatusManagerImpl.class);

    @Inject
    ConfigItemStatusDao configItemStatusDao;

    @Inject
    ObjectManager objectManager;

    @Inject
    AgentLocator agentLocator;

    @Inject
    EventService eventService;

    protected Map<String, ConfigItemStatus> getStatus(ConfigUpdateRequest request) {
        Map<String, ConfigItemStatus> statuses = new HashMap<String, ConfigItemStatus>();

        for (ConfigItemStatus status : configItemStatusDao.listItems(request)) {
            statuses.put(status.getName(), status);
        }

        return statuses;
    }

    @Override
    public void updateConfig(ConfigUpdateRequest request) {
        if (request.getClient() == null) {
            throw new IllegalArgumentException("Client is null on request [" + request + "]");
        }

        Client client = request.getClient();
        Map<String, ConfigItemStatus> statuses = getStatus(request);
        List<ConfigUpdateItem> toTrigger = new ArrayList<ConfigUpdateItem>();

        for (ConfigUpdateItem item : request.getItems()) {
            boolean modified = false;
            String name = item.getName();
            ConfigItemStatus status = statuses.get(name);
            Long requestedVersion = item.getRequestedVersion();

            if (status == null) {
                if (item.isApply()) {
                    requestedVersion = configItemStatusDao.incrementOrApply(client, name);
                    modified = true;
                }
            }

            if (requestedVersion == null && item.isIncrement()) {
                requestedVersion = configItemStatusDao.incrementOrApply(client, name);
                modified = true;
            }

            if (requestedVersion == null) {
                requestedVersion = configItemStatusDao.getRequestedVersion(client, name);
            }

            item.setRequestedVersion(requestedVersion);

            if (modified) {
                toTrigger.add(item);
            }
        }

        triggerUpdate(request, toTrigger);
    }

    protected void triggerUpdate(final ConfigUpdateRequest request, final List<ConfigUpdateItem> items) {
        final Event event = getEvent(request, items);
        if (event == null) {
            return;
        }

        Runnable run = new Runnable() {
            @Override
            public void run() {
                request.setUpdateFuture(call(request.getClient(), event, defaultOptions(request)));
            }
        };

        if (request.isDeferredTrigger()) {
            DeferredUtils.defer(run);
        } else {
            run.run();
        }
    }

    protected EventCallOptions defaultOptions(final ConfigUpdateRequest request) {
        EventCallOptions options = new EventCallOptions(RETRY.get(), TIMEOUT.get()).withProgress(new EventProgress() {
            @Override
            public void progress(Event event) {
                logResponse(request, event);
            }
        });

        options.withRetryCallback(new RetryCallback() {
            @Override
            public Event beforeRetry(Event event) {
                Event updatedEvent = getEvent(request);
                EventVO<Object> newEvent = new EventVO<Object>(event);
                newEvent.setData(updatedEvent.getData());
                return newEvent;
            }
        });

        return options;
    }

    protected ConfigUpdate getEvent(ConfigUpdateRequest request, List<ConfigUpdateItem> items) {
        Client client = request.getClient();
        String url = ServerContext.getHostApiBaseUrl(BaseProtocol.HTTP);

        if (items.size() == 0) {
            return new ConfigUpdate(client.getEventName(), url, Collections.<ConfigUpdateItem> emptyList());
        }

        ConfigUpdate event = new ConfigUpdate(client.getEventName(), url, items);

        event.withResourceType(objectManager.getType(client.getResourceType())).withResourceId(Long.toString(client.getResourceId()));

        return event;
    }

    protected ConfigUpdate getEvent(ConfigUpdateRequest request) {
        List<ConfigUpdateItem> toTrigger = getNeedsUpdating(request, !request.isMigration());
        return getEvent(request, toTrigger);
    }

    @Override
    public ListenableFuture<?> whenReady(final ConfigUpdateRequest request) {
        ConfigUpdate event = getEvent(request);

        if (event.getData().getItems().size() == 0) {
            return AsyncUtils.done();
        }

        ListenableFuture<? extends Event> future = request.getUpdateFuture();
        if (future == null) {
            future = call(request.getClient(), event, defaultOptions(request));
        }

        return Futures.transform(future, new Function<Event, Object>() {
            @Override
            public Object apply(Event input) {
                logResponse(request, input);
                List<ConfigUpdateItem> toTrigger = getNeedsUpdating(request, true);
                if (toTrigger.size() > 0) {
                    throw new ConfigTimeoutException(request, toTrigger);
                }

                return Boolean.TRUE;
            }
        });
    }

    protected List<ConfigUpdateItem> getNeedsUpdating(ConfigUpdateRequest request, boolean checkVersions) {
        Client client = request.getClient();
        Map<String, ConfigItemStatus> statuses = getStatus(request);
        List<ConfigUpdateItem> toTrigger = new ArrayList<ConfigUpdateItem>();

        for (ConfigUpdateItem item : request.getItems()) {
            String name = item.getName();
            ConfigItemStatus status = statuses.get(item.getName());

            if (status == null) {
                log.error("Waiting on config item [{}] on client [{}] but it is not applied", name, client);
                continue;
            }

            if (item.isCheckInSyncOnly()) {
                if (!checkVersions || !ObjectUtils.equals(status.getRequestedVersion(), status.getAppliedVersion())) {
                    if (request.isMigration()) {
                        log.info("Waiting on [{}] on [{}], for migration", client, name);
                    } else {
                        log.info("Waiting on [{}] on [{}], not in sync requested [{}] != applied [{}]", client, name, status.getRequestedVersion(), status
                                .getAppliedVersion());
                    }
                    toTrigger.add(item);
                }
            } else if (item.getRequestedVersion() != null) {
                Long applied = status.getAppliedVersion();
                if (applied == null || item.getRequestedVersion() > applied) {
                    log.info("Waiting on [{}] on [{}], not applied requested [{}] > applied [{}]", client, name, item.getRequestedVersion(), applied);
                    toTrigger.add(item);
                }
            }
        }

        return toTrigger;
    }

    @Override
    public void waitFor(ConfigUpdateRequest request) {
        AsyncUtils.get(whenReady(request));
    }

    protected ListenableFuture<? extends Event> call(Client client, Event event, EventCallOptions options) {
        if (client.getResourceType() == Agent.class) {
            RemoteAgent agent = agentLocator.lookupAgent(client.getResourceId());
            return agent.call(event, options);
        }

        return eventService.call(event, options);
    }

    @Override
    public void sync(final boolean migration) {
        Map<Client, List<String>> items = configItemStatusDao.findOutOfSync(migration);

        boolean first = true;
        for (final Map.Entry<Client, List<String>> entry : items.entrySet()) {
            final Client client = entry.getKey();
            final ConfigUpdateRequest request = new ConfigUpdateRequest(client).withMigration(migration);

            for (String item : entry.getValue()) {
                request.addItem(item).withApply(false).withIncrement(false).withCheckInSyncOnly(true);
            }

            log.info("Requesting {} of item(s) {} on [{}]", migration ? "migration" : "update", entry.getValue(), client);

            if (first && migration && BLOCK.get()) {
                waitFor(request);
            } else {
                Event event = getEvent(request);
                ListenableFuture<? extends Event> future = call(client, event, defaultOptions(request).withRetry(0));
                Futures.addCallback(future, new FutureCallback<Event>() {
                    @Override
                    public void onSuccess(Event result) {
                        logResponse(request, result);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        if (t instanceof TimeoutException) {
                            log.info("Timeout {} item(s) {} on [{}]", migration ? "migrating" : "updating", entry.getValue(), client);
                        } else {
                            log.error("Error {} item(s) {} on [{}]", migration ? "migrating" : "updating", entry.getValue(), client, t);
                        }
                    }
                });
            }

            first = false;
        }
    }

    protected void logResponse(ConfigUpdateRequest request, Event event) {
        Map<String, Object> data = CollectionUtils.toMap(event.getData());

        Object exitCode = data.get("exitCode");
        Object output = data.get("output");

        if (exitCode != null) {
            long exit = Long.parseLong(exitCode.toString());

            if (exit == 0) {
                log.debug("Success {}", request);
            } else if (exit == 122 && "Lock failed".equals(output)) {
                /*
                 * This happens when the lock fails to apply. Really we should
                 * upgrade to newer util-linux that supports -E and then set a
                 * special exit code. That will be slightly better
                 */
                log.info("Failed {}, exit code [{}] output [{}]", request, exitCode, output);
            } else {
                log.error("Failed {}, exit code [{}] output [{}]", request, exitCode, output);
            }
        }
    }

    @Override
    public boolean setApplied(Client client, String itemName, ItemVersion version) {
        return configItemStatusDao.setApplied(client, itemName, version);
    }

    @Override
    public void setLatest(Client client, String itemName, String sourceRevision) {
        configItemStatusDao.setLatest(client, itemName, sourceRevision);
    }

    @Override
    public boolean isAssigned(Client client, String itemName) {
        return configItemStatusDao.isAssigned(client, itemName);
    }

    @Override
    public void setItemSourceVersion(String name, String sourceRevision) {
        configItemStatusDao.setItemSourceVersion(name, sourceRevision);
    }

    @Override
    public ItemVersion getRequestedVersion(Client client, String itemName) {
        return configItemStatusDao.getRequestedItemVersion(client, itemName);
    }

}