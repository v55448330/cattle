package io.cattle.platform.servicediscovery.dao.impl;

import static io.cattle.platform.core.model.tables.HostTable.HOST;
import static io.cattle.platform.core.model.tables.InstanceHostMapTable.INSTANCE_HOST_MAP;
import static io.cattle.platform.core.model.tables.InstanceTable.INSTANCE;
import static io.cattle.platform.core.model.tables.ServiceExposeMapTable.SERVICE_EXPOSE_MAP;
import static io.cattle.platform.core.model.tables.ServiceTable.SERVICE;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.InstanceConstants;
import io.cattle.platform.core.dao.GenericMapDao;
import io.cattle.platform.core.model.Host;
import io.cattle.platform.core.model.Instance;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.core.model.tables.records.HostRecord;
import io.cattle.platform.core.model.tables.records.InstanceRecord;
import io.cattle.platform.core.model.tables.records.ServiceExposeMapRecord;
import io.cattle.platform.core.model.tables.records.ServiceRecord;
import io.cattle.platform.db.jooq.dao.impl.AbstractJooqDao;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.servicediscovery.api.constants.ServiceDiscoveryConstants;
import io.cattle.platform.servicediscovery.api.dao.ServiceExposeMapDao;
import io.cattle.platform.servicediscovery.service.ServiceDiscoveryService;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.apache.commons.lang3.tuple.Pair;

public class ServiceExposeMapDaoImpl extends AbstractJooqDao implements ServiceExposeMapDao {

    @Inject
    ObjectManager objectManager;

    @Inject
    ObjectProcessManager objectProcessManager;

    @Inject
    ServiceDiscoveryService sdService;

    @Inject
    GenericMapDao mapDao;


    @Override
    public Pair<Instance, ServiceExposeMap> createServiceInstance(Map<String, Object> properties, Service service,
            String instanceName) {
        final Instance instance = objectManager.create(Instance.class, properties);
        ServiceExposeMap exposeMap = createServiceInstanceMap(service, instance);
        return Pair.of(instance, exposeMap);
    }

    @Override
    @SuppressWarnings("unchecked")
    public ServiceExposeMap createServiceInstanceMap(Service service, final Instance instance) {
        Map<String, String> instanceLabels = DataAccessor.fields(instance)
                .withKey(InstanceConstants.FIELD_LABELS).withDefault(Collections.EMPTY_MAP).as(Map.class);
        String dnsPrefix = instanceLabels
                .get(ServiceDiscoveryConstants.LABEL_SERVICE_LAUNCH_CONFIG);
        if (ServiceDiscoveryConstants.PRIMARY_LAUNCH_CONFIG_NAME.equalsIgnoreCase(dnsPrefix)) {
            dnsPrefix = null;
        }

        return objectManager.create(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.INSTANCE_ID, instance.getId(),
                SERVICE_EXPOSE_MAP.SERVICE_ID, service.getId(), SERVICE_EXPOSE_MAP.ACCOUNT_ID,
                service.getAccountId(), SERVICE_EXPOSE_MAP.DNS_PREFIX, dnsPrefix);
    }

    @Override
    public List<? extends Instance> listServiceInstances(long serviceId) {
        return create()
                .select(INSTANCE.fields())
                .from(INSTANCE)
                .join(SERVICE_EXPOSE_MAP)
                .on(SERVICE_EXPOSE_MAP.INSTANCE_ID.eq(INSTANCE.ID)
                        .and(SERVICE_EXPOSE_MAP.SERVICE_ID.eq(serviceId))
                        .and(SERVICE_EXPOSE_MAP.STATE.in(CommonStatesConstants.ACTIVATING,
                                CommonStatesConstants.ACTIVE, CommonStatesConstants.REQUESTED))
                        .and(INSTANCE.STATE.notIn(CommonStatesConstants.PURGING, CommonStatesConstants.PURGED,
                                CommonStatesConstants.REMOVED, CommonStatesConstants.REMOVING)))
                .fetchInto(InstanceRecord.class);
    }

    @Override
    public List<? extends ServiceExposeMap> getNonRemovedServiceInstanceMap(long serviceId) {
        return create()
                .select(SERVICE_EXPOSE_MAP.fields())
                .from(SERVICE_EXPOSE_MAP)
                .join(INSTANCE)
                .on(SERVICE_EXPOSE_MAP.INSTANCE_ID.eq(INSTANCE.ID)
                        .and(SERVICE_EXPOSE_MAP.SERVICE_ID.eq(serviceId))
                        .and(SERVICE_EXPOSE_MAP.REMOVED.isNull())
                        .and(SERVICE_EXPOSE_MAP.STATE.notIn(CommonStatesConstants.REMOVED,
                                CommonStatesConstants.REMOVING))
                        .and(INSTANCE.STATE.notIn(CommonStatesConstants.PURGING,
                                CommonStatesConstants.PURGED, CommonStatesConstants.REMOVED,
                                CommonStatesConstants.REMOVING)))
                .fetchInto(ServiceExposeMapRecord.class);
    }

    @Override
    public ServiceExposeMap findInstanceExposeMap(Instance instance) {
        if (instance == null) {
            return null;
        }
        List<? extends ServiceExposeMap> instanceServiceMap = mapDao.findNonRemoved(ServiceExposeMap.class,
                Instance.class,
                instance.getId());
        if (instanceServiceMap.isEmpty()) {
            // not a service instance
            return null;
        }
        return instanceServiceMap.get(0);
    }

    @Override
    public ServiceExposeMap createIpToServiceMap(Service service, String ipAddress) {
        ServiceExposeMap map = getServiceIpExposeMap(service, ipAddress);
        if (map == null) {
            map = objectManager.create(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.SERVICE_ID, service.getId(),
                    SERVICE_EXPOSE_MAP.IP_ADDRESS, ipAddress, SERVICE_EXPOSE_MAP.ACCOUNT_ID,
                    service.getAccountId());
        }
        return map;
    }

    @Override
    public ServiceExposeMap getServiceIpExposeMap(Service service, String ipAddress) {
        return objectManager.findAny(ServiceExposeMap.class,
                SERVICE_EXPOSE_MAP.SERVICE_ID, service.getId(),
                SERVICE_EXPOSE_MAP.IP_ADDRESS, ipAddress,
                SERVICE_EXPOSE_MAP.REMOVED, null);
    }

    @Override
    public List<? extends Service> getActiveServices(long accountId) {
        return create()
                .select(SERVICE.fields())
                .from(SERVICE)
                .where(SERVICE.ACCOUNT_ID.eq(accountId))
                .and(SERVICE.REMOVED.isNull())
                .and(SERVICE.STATE.in(CommonStatesConstants.ACTIVE, CommonStatesConstants.ACTIVATING, CommonStatesConstants.UPDATING_ACTIVE))
                .fetchInto(ServiceRecord.class);
    }

    @Override
    public List<? extends ServiceExposeMap> getNonRemovedServiceIpMaps(long serviceId) {
        return create()
                .select(SERVICE_EXPOSE_MAP.fields())
                .from(SERVICE_EXPOSE_MAP)
                .where(SERVICE_EXPOSE_MAP.SERVICE_ID.eq(serviceId))
                .and(SERVICE_EXPOSE_MAP.REMOVED.isNull()
                        .and(SERVICE_EXPOSE_MAP.STATE.notIn(CommonStatesConstants.REMOVED,
                                CommonStatesConstants.REMOVING))
                        .and(SERVICE_EXPOSE_MAP.IP_ADDRESS.isNotNull()))
                .fetchInto(ServiceExposeMapRecord.class);
    }

    @Override
    public Host getHostForInstance(long instanceId) {
        List<? extends Host> results = create()
                .select(HOST.fields())
                .from(HOST)
                .join(INSTANCE_HOST_MAP)
                .on(HOST.ID.eq(INSTANCE_HOST_MAP.HOST_ID))
                .where(INSTANCE_HOST_MAP.INSTANCE_ID.eq(instanceId))
                .fetchInto(HostRecord.class);
        if (results.size() > 0) {
            return results.get(0);
        }
        return null;
    }

    @Override
    public boolean isPrimaryServiceInstance(ServiceExposeMap map) {
        return map.getDnsPrefix() == null;
    }

    @Override
    public boolean isActiveMap(ServiceExposeMap serviceExposeMap) {
        List<String> validStates = Arrays.asList(CommonStatesConstants.ACTIVATING,
                CommonStatesConstants.ACTIVE, CommonStatesConstants.UPDATING_ACTIVE, CommonStatesConstants.REQUESTED);
        return (validStates.contains(serviceExposeMap.getState()));

    }

    @Override
    public ServiceExposeMap getServiceHostnameExposeMap(Service service, String hostName) {
        return objectManager.findAny(ServiceExposeMap.class,
                SERVICE_EXPOSE_MAP.SERVICE_ID, service.getId(),
                SERVICE_EXPOSE_MAP.HOST_NAME, hostName,
                SERVICE_EXPOSE_MAP.REMOVED, null);
    }

    @Override
    public ServiceExposeMap createHostnameToServiceMap(Service service, String hostName) {
        ServiceExposeMap map = getServiceHostnameExposeMap(service, hostName);
        if (map == null) {
            map = objectManager.create(ServiceExposeMap.class, SERVICE_EXPOSE_MAP.SERVICE_ID, service.getId(),
                    SERVICE_EXPOSE_MAP.HOST_NAME, hostName, SERVICE_EXPOSE_MAP.ACCOUNT_ID,
                    service.getAccountId());
        }
        return map;
    }

    @Override
    public List<? extends ServiceExposeMap> getNonRemovedServiceHostnameMaps(long serviceId) {
        return create()
                .select(SERVICE_EXPOSE_MAP.fields())
                .from(SERVICE_EXPOSE_MAP)
                .where(SERVICE_EXPOSE_MAP.SERVICE_ID.eq(serviceId))
                .and(SERVICE_EXPOSE_MAP.REMOVED.isNull()
                        .and(SERVICE_EXPOSE_MAP.STATE.notIn(CommonStatesConstants.REMOVED,
                                CommonStatesConstants.REMOVING))
                        .and(SERVICE_EXPOSE_MAP.HOST_NAME.isNotNull()))
                .fetchInto(ServiceExposeMapRecord.class);
    }

    @Override
    public Service getIpAddressService(String ipAddress, long accountId) {
        List<? extends Service> services = create()
                .select(SERVICE.fields())
                .from(SERVICE)
                .join(SERVICE_EXPOSE_MAP)
                .on(SERVICE_EXPOSE_MAP.SERVICE_ID.eq(SERVICE.ID))
                .where(SERVICE.ACCOUNT_ID.eq(accountId))
                .and(SERVICE_EXPOSE_MAP.IP_ADDRESS.eq(ipAddress))
                .and(SERVICE.REMOVED.isNull())
                .fetchInto(ServiceRecord.class);
        if (services.isEmpty()) {
            return null;
        }
        return services.get(0);
    }
}
