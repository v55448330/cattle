package io.cattle.platform.process.containerevent;

import static io.cattle.platform.core.constants.CommonStatesConstants.*;
import static io.cattle.platform.core.constants.ContainerEventConstants.*;
import static io.cattle.platform.core.constants.InstanceConstants.*;
import static io.cattle.platform.core.constants.NetworkConstants.*;
import static io.cattle.platform.core.model.tables.HostTable.*;
import static io.cattle.platform.docker.constants.DockerInstanceConstants.*;
import static io.cattle.platform.docker.constants.DockerNetworkConstants.*;
import io.cattle.platform.agent.AgentLocator;
import io.cattle.platform.agent.RemoteAgent;
import io.cattle.platform.archaius.util.ArchaiusUtil;
import io.cattle.platform.core.dao.AccountDao;
import io.cattle.platform.core.dao.InstanceDao;
import io.cattle.platform.core.dao.NetworkDao;
import io.cattle.platform.core.model.ContainerEvent;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Network;
import io.cattle.platform.core.model.Subnet;
import io.cattle.platform.engine.handler.HandlerResult;
import io.cattle.platform.engine.process.ProcessInstance;
import io.cattle.platform.engine.process.ProcessState;
import io.cattle.platform.engine.process.impl.ProcessCancelException;
import io.cattle.platform.eventing.EventCallOptions;
import io.cattle.platform.eventing.model.Event;
import io.cattle.platform.eventing.model.EventVO;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.object.process.StandardProcess;
import io.cattle.platform.object.resource.ResourceMonitor;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.object.util.DataUtils;
import io.cattle.platform.process.base.AbstractDefaultProcessHandler;
import io.cattle.platform.storage.service.StorageService;
import io.cattle.platform.util.net.NetUtils;
import io.cattle.platform.util.type.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.netflix.config.DynamicBooleanProperty;

@Named
public class ContainerEventCreate extends AbstractDefaultProcessHandler {

    private static final String RANCHER_AGENT_IMAGE = "rancher/agent";
    public static final String AGENT_ID = "agentId";
    public static final String INSTANCE_INSPECT_EVENT_NAME = "compute.instance.inspect";
    private static final String INSTANCE_INSPECT_DATA_NAME = "instanceInspect";
    private static final DynamicBooleanProperty MANAGE_NONRANCHER_CONTAINERS = ArchaiusUtil.getBoolean("manage.nonrancher.containers");
    private static final String INSPECT_ENV = "Env";
    private static final String INSPECT_LABELS = "Labels";
    private static final String INSPECT_NAME = "Name";
    private static final String FIELD_DOCKER_INSPECT = "dockerInspect";
    private static final String INSPECT_CONFIG = "Config";
    private static final String IMAGE_PREFIX = "docker:";
    private static final String IMAGE_KIND_PATTERN = "^(sim|docker):.*";
    private static final String RANCHER_UUID_ENV_VAR = "RANCHER_UUID=";
    private static final String RANCHER_NETWORK_ENV_VAR = "RANCHER_NETWORK=";

    private static final Logger log = LoggerFactory.getLogger(ContainerEventCreate.class);

    @Inject
    StorageService storageService;

    @Inject
    NetworkDao networkDao;

    @Inject
    AccountDao accountDao;

    @Inject
    InstanceDao instanceDao;

    @Inject
    LockManager lockManager;

    @Inject
    ResourceMonitor resourceMonitor;

    @Inject
    AgentLocator agentLocator;

    @Override
    public HandlerResult handle(ProcessState state, ProcessInstance process) {
        if (!MANAGE_NONRANCHER_CONTAINERS.get()) {
            return null;
        }

        final ContainerEvent event = (ContainerEvent)state.getResource();

        Host host = objectManager.findOne(Host.class, HOST.ID, event.getHostId());
        if (host == null || host.getRemoved() != null) {
            log.info("Host [{}] is unavailable. Not processing container event [{}].", event.getHostId(), event.getId());
            return null;
        }

        final Map<String, Object> data = state.getData();
        HandlerResult result = lockManager.lock(new ContainerEventInstanceLock(event.getAccountId(), event.getExternalId()), new LockCallback<HandlerResult>() {
            @Override
            public HandlerResult doWithLock() {
                Map<String, Object> inspect = getInspect(event, data);
                String rancherUuid = getRancherUuidLabel(inspect, data);
                Instance instance = instanceDao.getInstanceByUuidOrExternalId(event.getAccountId(), rancherUuid, event.getExternalId());
                if ((instance != null && StringUtils.isNotEmpty(instance.getSystemContainer()))
                        || StringUtils.isNotEmpty(getLabel(LABEL_RANCHER_SYSTEM_CONTAINER, inspect, data)) || checkImageForRancherAgent(event)) {
                    // System containers are not managed by container events
                    return null;
                }

                try {
                    String status = event.getExternalStatus();
                    if (status.equals(EVENT_START) && instance == null) {
                        scheduleInstance(event, instance, inspect, data);
                        return null;
                    }

                    if (instance == null || instance.getRemoved() != null) {
                        return null;
                    }

                    String state = instance.getState();
                    if (EVENT_START.equals(status)) {
                        if (STATE_CREATING.equals(state) || STATE_RUNNING.equals(state) || STATE_STARTING.equals(state) || STATE_RESTARTING.equals(status))
                            return null;

                        if (STATE_STOPPING.equals(state)) {
                            // handle docker restarts
                            instance = resourceMonitor.waitForNotTransitioning(instance);
                        }

                        objectProcessManager.scheduleProcessInstance(PROCESS_START, instance, makeData());
                    } else if (EVENT_STOP.equals(status) || EVENT_DIE.equals(status)) {
                        if (STATE_STOPPED.equals(state) || STATE_STOPPING.equals(state))
                            return null;

                        objectProcessManager.scheduleProcessInstance(PROCESS_STOP, instance, makeData());
                    } else if (EVENT_DESTROY.equals(status)) {
                        if (REMOVED.equals(state) || REMOVING.equals(state) || PURGED.equals(state) || PURGING.equals(state))
                            return null;

                        Map<String, Object> data = makeData();
                        try {
                            objectProcessManager.scheduleStandardProcess(StandardProcess.REMOVE, instance, data);
                        } catch (ProcessCancelException e) {
                            if (STATE_STOPPING.equals(state)) {
                                // handle docker forced stop and remove
                                instance = resourceMonitor.waitForNotTransitioning(instance);
                                objectProcessManager.scheduleStandardProcess(StandardProcess.REMOVE, instance, data);
                            } else {
                                data.put(REMOVE_OPTION, true);
                                objectProcessManager.scheduleProcessInstance(PROCESS_STOP, instance, data);
                            }
                        }
                    }
                } catch (ProcessCancelException e) {
                    // ignore
                }
                return null;
            }
        });

        return result;
    }

    boolean checkImageForRancherAgent(ContainerEvent event) {
        if (StringUtils.isEmpty(event.getExternalFrom())) {
            return false;
        }
        String[] imageParts = event.getExternalFrom().split(":");
        String imageName = null;
        // Testing hack: images look like: sim:rancher/agent:latest
        if (imageParts.length <= 2) {
            imageName = imageParts[0];
        } else if (imageParts.length == 3) {
            imageName = imageParts[1];
        }
        return StringUtils.equals(imageName, RANCHER_AGENT_IMAGE);
    }

    void scheduleInstance(ContainerEvent event, Instance instance, Map<String, Object> inspect, Map<String, Object> data) {
        final Long accountId = event.getAccountId();
        final String externalId = event.getExternalId();

        instance = objectManager.newRecord(Instance.class);
        instance.setKind(KIND_CONTAINER);
        instance.setAccountId(accountId);
        instance.setExternalId(externalId);
        instance.setNativeContainer(true);
        setName(inspect, data, instance);
        setNetwork(inspect, data, instance);
        setImage(event, instance);
        setHost(event, instance);
        instance = objectManager.create(instance);
        objectProcessManager.scheduleProcessInstance(PROCESS_CREATE, instance, makeData());
    }

    private Map<String, Object> getInspect(ContainerEvent event, Map<String, Object> data) {
        Object inspectObj = DataUtils.getFields(event).get(FIELD_DOCKER_INSPECT);
        if (inspectObj == null) {
            Event inspectEvent = newInspectEvent(null, event.getExternalId());
            inspectObj = callAgentForInspect(data, inspectEvent);
        }
        return CollectionUtils.toMap(inspectObj);
    }

    private Object callAgentForInspect(Map<String, Object> data, Event inspectEvent) {
        Long agentId = DataAccessor.fromMap(data).withScope(ContainerEventCreate.class).withKey(AGENT_ID).as(Long.class);
        Object inspect = null;
        if (agentId != null) {
            RemoteAgent agent = agentLocator.lookupAgent(agentId);
            Event result = agent.callSync(inspectEvent, new EventCallOptions(0, 3000L));
            inspect = CollectionUtils.getNestedValue(result.getData(), INSTANCE_INSPECT_DATA_NAME);
        }
        return inspect;
    }

    private Event newInspectEvent(String containerName, String containerId) {
        Map<String, Object> inspectEventData = new HashMap<String, Object>();
        Map<String, Object> reqData = CollectionUtils.asMap("kind", "docker");
        if (StringUtils.isNotEmpty(containerId))
            reqData.put("id", containerId);
        if (StringUtils.isNotEmpty(containerName))
            reqData.put("name", containerName);

        inspectEventData.put(INSTANCE_INSPECT_DATA_NAME, reqData);
        EventVO<Object> inspectEvent = EventVO.newEvent(INSTANCE_INSPECT_EVENT_NAME).withData(inspectEventData);
        inspectEvent.setResourceType(INSTANCE_INSPECT_DATA_NAME);
        return inspectEvent;
    }

    public static boolean isNativeDockerStart(ProcessState state) {
        return DataAccessor.fromMap(state.getData()).withKey(PROCESS_DATA_NO_OP).withDefault(false).as(Boolean.class);
    }

    protected Map<String, Object> makeData() {
        Map<String, Object> data = new HashMap<String, Object>();
        DataAccessor.fromMap(data).withKey(PROCESS_DATA_NO_OP).set(true);
        return data;
    }

    void setHost(ContainerEvent event, Instance instance) {
        DataAccessor.fields(instance).withKey(FIELD_REQUESTED_HOST_ID).set(event.getHostId());
    }

    void setName(Map<String, Object> inspect, Map<String, Object> data, Instance instance) {
        String name = DataAccessor.fromMap(data).withKey(CONTAINER_EVENT_SYNC_NAME).as(String.class);
        if (StringUtils.isEmpty(name))
            name = DataAccessor.fromMap(inspect).withKey(INSPECT_NAME).as(String.class);

        if (name != null)
            name = name.replaceFirst("/", "");
        instance.setName(name);
    }

    void setNetwork(Map<String, Object> inspect, Map<String, Object> data, Instance instance) {
        String networkMode = checkNoneNetwork(inspect);

        if (networkMode == null) {
            String inspectNetMode = getInspectNetworkMode(inspect);
            networkMode = checkHostNetwork(inspectNetMode);

            if (networkMode == null)
                networkMode = checkContainerNetwork(inspectNetMode, instance, data);

            if (networkMode == null)
                networkMode = checkBridgeOrManagedNetwork(inspectNetMode, inspect, data, instance);
        }

        if (networkMode == null) {
            log.warn("Could not determine network mode for container [externalId: {}]. Using none networking.", instance.getExternalId());
            networkMode = NETWORK_MODE_NONE;
        }

        DataAccessor.fields(instance).withKey(FIELD_NETWORK_MODE).set(networkMode);
    }

    private String getInspectNetworkMode(Map<String, Object> inspect) {
        Object tempNM = CollectionUtils.getNestedValue(inspect, "HostConfig", "NetworkMode");
        String inspectNetMode = tempNM == null ? "" : tempNM.toString();
        return inspectNetMode;
    }

    private String checkNoneNetwork(Map<String, Object> inspect) {
        Object netDisabledObj = CollectionUtils.getNestedValue(inspect, "Config", "NetworkDisabled");
        if (Boolean.TRUE.equals(netDisabledObj)) {
            return NETWORK_MODE_NONE;
        }
        return null;
    }

    private String checkHostNetwork(String inspectNetMode) {
        if (NETWORK_MODE_HOST.equals(inspectNetMode)) {
            return inspectNetMode;
        }
        return null;
    }

    /*
     * If mode is container, will attempt to look up the corresponding container in rancher. If it is found, will also ahve the side effect of setting
     * networkContainerId on the instance.
     */
    private String checkContainerNetwork(String inspectNetMode, Instance instance, Map<String, Object> data) {
        if (!StringUtils.startsWith(inspectNetMode, NETWORK_MODE_CONTAINER))
            return null;

        String[] parts = StringUtils.split(inspectNetMode, ":", 2);
        String targetContainer = null;
        if (parts.length == 2) {
            targetContainer = parts[1];
            Instance netFromInstance = instanceDao.getInstanceByUuidOrExternalId(instance.getAccountId(), targetContainer, targetContainer);
            if (netFromInstance == null) {
                Event inspectEvent = newInspectEvent(targetContainer, targetContainer);
                Object inspectObj = callAgentForInspect(data, inspectEvent);
                Map<String, Object> inspect = CollectionUtils.toMap(inspectObj);
                String uuid = getRancherUuidLabel(inspect, null);
                String externalId = inspect.get("Id") != null ? inspect.get("Id").toString() : null;
                netFromInstance = instanceDao.getInstanceByUuidOrExternalId(instance.getAccountId(), uuid, externalId);
            }

            if (netFromInstance != null) {
                DataAccessor.fields(instance).withKey(FIELD_NETWORK_CONTAINER_ID).set(netFromInstance.getId());
                return NETWORK_MODE_CONTAINER;
            }
        }

        log.warn("Problem configuring container networking for container [externalId: {}]. Could not find target container: [{}].", instance.getExternalId(),
                targetContainer);

        return null;
    }

    /*
     * If mode is bridge, will check to see if managed networking or native docker bridge networking should be configured. Can have the side effect of setting
     * the requestedIp field on the instance, if it is appropriate to do so.
     */
    private String checkBridgeOrManagedNetwork(String inspectNetMode, Map<String, Object> inspect, Map<String, Object> data, Instance instance) {
        if (!NETWORK_MODE_BRIDGE.equals(inspectNetMode) && StringUtils.isNotEmpty(inspectNetMode) && !NETWORK_MODE_DEFAULT.equals(inspectNetMode))
            return null;

        String ip = getDockerIp(inspect);
        if (StringUtils.isNotEmpty(ip) && isIpInNetwork(ip, networkDao.getNetworkForObject(instance, KIND_HOSTONLY))) {
            DataAccessor.fields(instance).withKey(FIELD_REQUESTED_IP_ADDRESS).set(ip);
            return NETWORK_MODE_MANAGED;
        }
        if (BooleanUtils.toBoolean(getRancherNetworkLabel(inspect, data))) {
            return NETWORK_MODE_MANAGED;
        }
        return NETWORK_MODE_BRIDGE;
    }

    boolean isIpInNetwork(String ipAddress, Network network) {
        for (Subnet subnet : objectManager.children(network, Subnet.class)) {
            if (subnet.getRemoved() == null) {
                String startIp = NetUtils.getDefaultStartAddress(subnet.getNetworkAddress(), subnet.getCidrSize());
                long start = NetUtils.ip2Long(startIp);
                String endIp = NetUtils.getDefaultEndAddress(subnet.getNetworkAddress(), subnet.getCidrSize());
                long end = NetUtils.ip2Long(endIp);
                long ip = NetUtils.ip2Long(ipAddress);
                if (start <= ip && ip <= end)
                    return true;
            }
        }
        return false;
    }

    String getDockerIp(Map<String, Object> inspect) {
        Object ip = CollectionUtils.getNestedValue(inspect, "NetworkSettings", "IPAddress");
        if (ip != null)
            return ip.toString();

        return null;
    }

    void setImage(ContainerEvent event, Instance instance) {
        String imageUuid = event.getExternalFrom();

        // Somewhat of a hack, but needed for testing against sim contexts
        if (!imageUuid.matches(IMAGE_KIND_PATTERN)) {
            imageUuid = IMAGE_PREFIX + imageUuid;
        }
        DataAccessor.fields(instance).withKey(FIELD_IMAGE_UUID).set(imageUuid);
    }

    String getRancherUuidLabel(Map<String, Object> inspect, Map<String, Object> data) {
        return getLabel(LABEL_RANCHER_UUID, RANCHER_UUID_ENV_VAR, inspect, data);
    }

    String getRancherNetworkLabel(Map<String, Object> inspect, Map<String, Object> data) {
        return getLabel(RANCHER_NETWORK, RANCHER_NETWORK_ENV_VAR, inspect, data);
    }

    String getLabel(String labelKey, Map<String, Object> inspect, Map<String, Object> data) {
        return getLabel(labelKey, null, inspect, data);
    }

    @SuppressWarnings("unchecked")
    String getLabel(String labelKey, String envVarPrefix, Map<String, Object> inspect, Map<String, Object> data) {
        Map<String, Object> labelsFromData = CollectionUtils.toMap(DataAccessor.fromMap(data).withKey(CONTAINER_EVENT_SYNC_LABELS).get());
        String label = labelsFromData.containsKey(labelKey) ? labelsFromData.get(labelKey).toString() : null;
        if (StringUtils.isNotEmpty(label))
            return label;

        Map<String, Object> config = CollectionUtils.toMap(inspect.get(INSPECT_CONFIG));

        Map<String, String> labels = CollectionUtils.toMap(config.get(INSPECT_LABELS));
        label = labels.get(labelKey);
        if (StringUtils.isNotEmpty(label))
            return label;

        if (envVarPrefix == null)
            return null;

        List<String> envVars = (List<String>)CollectionUtils.toList(config.get(INSPECT_ENV));
        for (String envVar : envVars) {
            if (envVar.startsWith(envVarPrefix))
                return envVar.substring(envVarPrefix.length());
        }
        return null;
    }
}
