package org.eclipse.slm.resource_management.service.rest.resources;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import org.eclipse.slm.common.consul.client.ConsulCredential;
import org.eclipse.slm.common.consul.client.apis.*;
import org.eclipse.slm.common.consul.model.acl.BindingRule;
import org.eclipse.slm.common.consul.model.catalog.CatalogNode;
import org.eclipse.slm.common.consul.model.catalog.Node;
import org.eclipse.slm.common.consul.model.catalog.NodeService;
import org.eclipse.slm.common.consul.model.catalog.TaggedAddresses;
import org.eclipse.slm.common.consul.model.exceptions.ConsulLoginFailedException;
import org.eclipse.slm.resource_management.model.resource.*;
import org.eclipse.slm.resource_management.model.resource.exceptions.ResourceNotFoundException;
import org.eclipse.slm.resource_management.persistence.api.LocationJpaRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Component
public class ResourcesConsulClient {

    public final static Logger LOG = LoggerFactory.getLogger(ResourcesConsulClient.class);
    private static final String META_KEY_LOCATION = "locationId";
    private static final String META_KEY_RESOURCE_ID = "resourceId";
    private static final String META_KEY_CONNECTION_TYPE = "resourceConnectionType";
    private final ConsulNodesApiClient consulNodesApiClient;
    private final ConsulAclApiClient consulAclApiClient;
    private final ConsulServicesApiClient consulServicesApiClient;
    private final ConsulGenericServicesClient consulGenericServicesClient;
    private final ConsulKeyValueApiClient consulKeyValueApiClient;
    private final LocationJpaRepository locationJpaRepository;
    private final ConsulGenericNodeRemoveClient consulGenericNodeRemoveClient;


    public ResourcesConsulClient(
            ConsulNodesApiClient consulNodesApiClient,
            ConsulAclApiClient consulAclApiClient,
            ConsulServicesApiClient consulServicesApiClient,
            ConsulGenericServicesClient consulGenericServicesClient,
            ConsulKeyValueApiClient consulKeyValueApiClient,
            ConsulGenericNodeRemoveClient consulGenericNodeRemoveClient,
            LocationJpaRepository locationJpaRepository
    ) {
        this.consulNodesApiClient = consulNodesApiClient;
        this.consulAclApiClient = consulAclApiClient;
        this.consulServicesApiClient = consulServicesApiClient;
        this.consulGenericServicesClient = consulGenericServicesClient;
        this.consulKeyValueApiClient = consulKeyValueApiClient;
        this.consulGenericNodeRemoveClient = consulGenericNodeRemoveClient;
        this.locationJpaRepository = locationJpaRepository;
    }

    public List<BasicResource> getResources(ConsulCredential consulCredential)
            throws ConsulLoginFailedException {
        var consulNodes = this.consulNodesApiClient.getNodes(consulCredential);
        List<BasicResource> resources = new ArrayList<>();
        for (var node : consulNodes) {
            if (node.getMeta() != null) {
                var basicResource = this.convertConsulNodeToBasicResource(node);
                resources.add(basicResource);
            }
        }

        return resources;
    }

    public Optional<BasicResource> getResourceById(ConsulCredential consulCredential, UUID resourceId) throws ConsulLoginFailedException, ResourceNotFoundException {
        Optional<Node> optionalNode = consulNodesApiClient.getNodeById(consulCredential, resourceId);

        if(optionalNode.isEmpty())
            return Optional.empty();

        consulServicesApiClient.getNodeServicesByNodeId(consulCredential, resourceId);

        return Optional.of(
                this.convertConsulNodeToBasicResource(optionalNode.get())
        );
    }

    public Optional<BasicResource> getResourceByHostname(ConsulCredential consulCredential, String hostname) throws ConsulLoginFailedException {
        Predicate<Node> p = (n) -> n.getNode().equals(hostname);

        return getResourceByX(consulCredential,p);
    }

    public Optional<BasicResource> getResourceByIp(ConsulCredential consulCredential, String ip) throws ConsulLoginFailedException {
        Predicate<Node> p = (n) -> n.getAddress().equals(ip);

        return getResourceByX(consulCredential, p);
    }

    private Optional<BasicResource> getResourceByX(ConsulCredential consulCredential, Predicate predicate) throws ConsulLoginFailedException {
        Optional<Node> optionalNode = consulNodesApiClient.getNodes(consulCredential).stream().filter(predicate).findFirst();

        if(optionalNode.isEmpty())
            return Optional.empty();

        return Optional.of(convertConsulNodeToBasicResource(optionalNode.get()));
    }

    //region ConnectionService
    public RemoteAccessService addConnectionService(
            ConnectionType connectionType,
            int connectionPort,
            BasicResource basicResource,
            CredentialUsernamePassword credential
    ) throws ConsulLoginFailedException {
        RemoteAccessService remoteAccessService = new RemoteAccessService(
                connectionType,
                connectionPort,
                credential
        );

        //Register Service:
        consulGenericServicesClient.registerService(
                new ConsulCredential(),
                basicResource.getId(),
                remoteAccessService.getService(),
                remoteAccessService.getId(),
                Optional.ofNullable(remoteAccessService.getPort()),
                remoteAccessService.getTags(),
                remoteAccessService.getServiceMeta()
        );

        //region Register KV:
        consulKeyValueApiClient.createKey(
                new ConsulCredential(),
                remoteAccessService.getId()+"/credentialClasses",
                Arrays.asList(remoteAccessService.getCredential().getClass().getSimpleName())
        );

        consulKeyValueApiClient.createKey(
                new ConsulCredential(),
                remoteAccessService.getId()+"/connectionTypes",
                Arrays.asList(remoteAccessService.getConnectionType().name())
        );
        //endregion

        //region Set ACL:
        this.consulAclApiClient.addReadAccessViaKeycloakRole(
                remoteAccessService.getId(),
                remoteAccessService.getService(),
                "service"
        );

        consulAclApiClient.addReadRuleToPolicy(
                new ConsulCredential(),
                "service_"+remoteAccessService.getId(),
                "key_prefix",
                remoteAccessService.getId().toString()
        );
        //endregion

        return remoteAccessService;
    }

    public Optional<NodeService> getRemoteAccessServiceOfResourceAsNodeService(ConsulCredential consulCredential, UUID resourceId) throws ConsulLoginFailedException {
        return consulServicesApiClient.getNodeServiceByNodeIdAndServiceTag(
                consulCredential,
                resourceId,
                RemoteAccessService.class.getSimpleName()
        );
    }

    public List<ConnectionType> getConnectionTypesOfRemoteAccessService(
            ConsulCredential consulCredential,
            UUID serviceId
    ) throws ConsulLoginFailedException, JsonProcessingException {
        List<String> connectionTypesAsStrings = consulKeyValueApiClient.getValueOfKey(
                consulCredential,
                serviceId + "/connectionTypes",
                new TypeReference<>() {}
        );

        List<ConnectionType> resourceConnectionTypes = new ArrayList<>();
        if (connectionTypesAsStrings != null) {
            for (String connectionType : connectionTypesAsStrings) {
                resourceConnectionTypes.add(ConnectionType.valueOf(connectionType));
            }
        }

        return resourceConnectionTypes;
    }

    public List<CredentialClass> getCredentialClassesOfRemoteAccessService(
            ConsulCredential consulCredential,
            UUID serviceId
    ) throws ConsulLoginFailedException, JsonProcessingException {
        List<String> credentialClassesAsString = consulKeyValueApiClient.getValueOfKey(
                consulCredential,
                serviceId + "/credentialClasses",
                new TypeReference<>() {}
        );

        List<CredentialClass> credentialClasses = new ArrayList<>();
        if (credentialClassesAsString != null) {
            for (String credentialClass : credentialClassesAsString) {
                credentialClasses.add(CredentialClass.valueOf(credentialClass));
            }
        }

        return credentialClasses;

    }
    //endregion

    //region ADD/DELETE
    public BasicResource addResource(BasicResource basicResource) throws ConsulLoginFailedException {
        return addResource(
                basicResource.getId(),
                basicResource.getHostname(),
                basicResource.getIp(),
                ConnectionType.ssh,
                Optional.ofNullable(basicResource.getLocation())
        );

    }

    public BasicResource addResource(
            UUID resourceId,
            String resourceHostname,
            String resourceIp,
            ConnectionType connectionType,
            Optional<Location> optionalLocation
    ) throws ConsulLoginFailedException {
        /// Add new resource as node in Consul
        CatalogNode node = new CatalogNode();
        node.setId(resourceId);
        node.setNode(resourceHostname);
        node.setAddress(resourceIp);

        var taggedAddresses = new TaggedAddresses();
        taggedAddresses.setLan(resourceIp);
        node.setTaggedAddresses(taggedAddresses);

        HashMap<String, String> meta = new HashMap<>();
        meta.put("external-node", "true");
        meta.put("external-probe", "true");
        meta.put(META_KEY_RESOURCE_ID, resourceId.toString());
        if (optionalLocation.isPresent())
            meta.put(META_KEY_LOCATION, optionalLocation.get().getId().toString());
        if (connectionType!=null)
            meta.put(META_KEY_CONNECTION_TYPE, connectionType.name());
        node.setNodeMeta(meta);

        this.consulNodesApiClient.registerNode(new ConsulCredential(), node);

        var newResourceNode = this.consulNodesApiClient.getNodeById(new ConsulCredential(), resourceId);
        var resource = this.convertConsulNodeToBasicResource(newResourceNode.get());

        return resource;
    }

    public void deleteResource(ConsulCredential consulCredential, BasicResource resource)
            throws ConsulLoginFailedException {
        List<String> policyNames = new ArrayList<>();
        List<String> roleNames = new ArrayList<>();
        List<String> bindungRuleNames = new ArrayList<>();
        var consulNodeOptional = this.consulNodesApiClient.getNodeById(consulCredential, resource.getId());

        if(consulNodeOptional.isEmpty()) {
            LOG.error("Unable to delete resource with id '" + resource.getId() + "', no corresponding Consul node found.");
            return;
        }

        var consulNode = consulNodeOptional.get();
        Optional<NodeService> optionalRemoteAccessService = getRemoteAccessServiceOfResourceAsNodeService(consulCredential, consulNode.getId());

        if(optionalRemoteAccessService.isPresent()) {
            NodeService remoteAccessService = optionalRemoteAccessService.get();
            String serviceID = remoteAccessService.getID();

            consulKeyValueApiClient.deleteKeyRecursive(consulCredential, serviceID);
            policyNames.add("service_"+serviceID);
            roleNames.add("service_"+serviceID);
            bindungRuleNames.add(serviceID);
        }

        this.consulGenericNodeRemoveClient.removeNode(consulCredential, consulNode.getNode());

        policyNames.add("resource_"+resource.getId());
        roleNames.add("resource_"+resource.getId());
        bindungRuleNames.add(String.valueOf(resource.getId()));

//        var policyName = "resource_" + resource.getId();
        for(String policyName : policyNames) {
            var policy = this.consulAclApiClient.getPolicyByName(consulCredential, policyName);
            if (policy != null) {
                this.consulAclApiClient.deletePolicyById(consulCredential, policy.getId());
            } else {
                LOG.error("Unable to delete Consul policy for resource with id '" + resource.getId()
                        + "', policy with name '" + policyName + "'not found");
            }
        }


//        var roleName = "resource_" + resource.getId();
        for(String roleName : roleNames) {
            var role = this.consulAclApiClient.getRoleByName(consulCredential, roleName);
            if (role != null) {
                this.consulAclApiClient.deleteRoleById(consulCredential, role.getId());
            } else {
                LOG.error("Unable to delete Consul role for resource with id '" + resource.getId()
                        + "', role with name '" + roleName + "'not found");
            }
        }


        var bindingRules = this.consulAclApiClient.getBindingRules(consulCredential);

        for(String bindingRuleName : bindungRuleNames) {
            List<BindingRule> bindingRulesOfResource = bindingRules.stream()
                    .filter(
                            r -> r.getBindName().equals(bindingRuleName)
                    )
                    .collect(Collectors.toList());
            if (bindingRulesOfResource.size() > 0) {
                for (BindingRule bindingRule : bindingRulesOfResource) {
                    this.consulAclApiClient.deleteBindingRuleById(consulCredential, bindingRule.getId());
                }
            } else {
                LOG.error("Unable to delete Consul binding rules for resource with id '" + resource.getId()
                        + "', no binding rules found");
            }
        }

        LOG.info("Resource with id '" + resource.getId() + "' successfully delete in Consul");
    }
    //endregion

    private BasicResource convertConsulNodeToBasicResource(Node node) {
        var basicResource = new BasicResource(node.getId());
        basicResource.setLocation(
                getResourceLocation(node)
                        .orElse(null)
        );
        basicResource.setHostname(node.getNode());
        basicResource.setIp(node.getAddress());

        return basicResource;
    }

    private Optional<Location> getResourceLocation(Node node) {
        Optional<String> optionalLocationId = Optional.ofNullable(
            node.getMeta().get(META_KEY_LOCATION)
        );

        if (optionalLocationId.isPresent())
            return locationJpaRepository.findById(
                    UUID.fromString(optionalLocationId.get())
            );
        else
            return Optional.empty();
    }
}
