package org.eclipse.slm.service_management.service.rest.service_deployment;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.eclipse.slm.common.awx.client.observer.AwxJobExecutor;
import org.eclipse.slm.common.awx.client.observer.AwxJobObserver;
import org.eclipse.slm.common.awx.client.observer.AwxJobObserverInitializer;
import org.eclipse.slm.common.awx.client.observer.IAwxJobObserverListener;
import org.eclipse.slm.common.awx.model.ExtraVars;
import org.eclipse.slm.common.consul.client.apis.ConsulServicesApiClient;
import org.eclipse.slm.common.keycloak.config.KeycloakUtil;
import org.eclipse.slm.common.utils.keycloak.KeycloakTokenUtil;
import org.eclipse.slm.notification_service.model.*;
import org.eclipse.slm.notification_service.service.client.NotificationServiceClient;
import org.eclipse.slm.resource_management.model.actions.ActionType;
import org.eclipse.slm.resource_management.model.consul.capability.SingleHostCapabilityService;
import org.eclipse.slm.resource_management.service.client.ResourceManagementApiClientInitializer;
import org.eclipse.slm.resource_management.service.client.handler.ApiException;
import org.eclipse.slm.service_management.model.exceptions.ServiceOptionNotFoundException;
import org.eclipse.slm.service_management.service.rest.utils.DockerContainerServiceOfferingOrderUtil;
import org.eclipse.slm.service_management.model.offerings.ServiceOrder;
import org.eclipse.slm.service_management.model.offerings.ServiceOfferingVersion;
import org.eclipse.slm.service_management.model.offerings.ServiceOrderResult;
import org.eclipse.slm.service_management.model.offerings.docker.compose.DockerComposeDeploymentDefinition;
import org.eclipse.slm.service_management.model.offerings.docker.container.DockerContainerDeploymentDefinition;
import org.eclipse.slm.service_management.model.offerings.exceptions.InvalidServiceOfferingDefinitionException;
import org.eclipse.slm.service_management.model.services.ServiceInstance;
import org.eclipse.slm.service_management.persistence.api.ServiceOrderJpaRepository;
import org.eclipse.slm.service_management.service.rest.docker_compose.DockerComposeFile;
import org.eclipse.slm.service_management.service.rest.docker_compose.DockerComposeFileParser;
import org.eclipse.slm.service_management.service.rest.service_instances.ServiceInstancesConsulClient;
import org.eclipse.slm.service_management.service.rest.kubernetes.KubernetesManifestFile;
import org.eclipse.slm.service_management.service.rest.kubernetes.KubernetesManifestFileParser;
import org.apache.commons.lang3.NotImplementedException;
import org.keycloak.KeycloakPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.net.ssl.SSLException;
import java.util.*;

@Component
public class ServiceDeploymentHandler  extends AbstractServiceDeploymentHandler implements IAwxJobObserverListener {

    private final static Logger LOG = LoggerFactory.getLogger(ServiceDeploymentHandler.class);

    private final NotificationServiceClient notificationServiceClient;

    private final ConsulServicesApiClient consulServicesApiClient;

    private final KeycloakUtil keycloakUtil;

    private final ServiceOrderJpaRepository serviceOrderJpaRepository;

    private Map<AwxJobObserver, DeploymentJobRun> observedAwxJobsToDeploymentJobDetails = new HashMap<>();


    public ServiceDeploymentHandler(AwxJobObserverInitializer awxJobObserverInitializer,
                                    AwxJobExecutor awxJobExecutor,
                                    NotificationServiceClient notificationServiceClient,
                                    ConsulServicesApiClient consulServicesApiClient,
                                    KeycloakUtil keycloakUtil,
                                    ResourceManagementApiClientInitializer resourceManagementApiClientInitializer,
                                    ServiceOrderJpaRepository serviceOrderJpaRepository,
                                    ServiceInstancesConsulClient serviceInstancesConsulClient) {
        super(resourceManagementApiClientInitializer, serviceInstancesConsulClient, awxJobObserverInitializer, awxJobExecutor);
        this.notificationServiceClient = notificationServiceClient;
        this.consulServicesApiClient = consulServicesApiClient;
        this.keycloakUtil = keycloakUtil;
        this.serviceOrderJpaRepository = serviceOrderJpaRepository;
    }

    public DeploymentJobRun deployServiceOfferingToResource(
            KeycloakPrincipal keycloakPrincipal,
            UUID deploymentCapabilityServiceId,
            ServiceOfferingVersion serviceOfferingVersion, ServiceOrder serviceOrder)
            throws SSLException, JsonProcessingException, ServiceOptionNotFoundException, ApiException, InvalidServiceOfferingDefinitionException, CapabilityServiceNotFoundException {

        var serviceId = UUID.randomUUID();
        serviceOrder.setServiceInstanceId(serviceId);
        var serviceOfferingDeploymentType = serviceOfferingVersion.getDeploymentType();
        var serviceHoster = this.getServiceHoster(keycloakPrincipal, deploymentCapabilityServiceId);
        serviceOrder.setDeploymentCapabilityServiceId(deploymentCapabilityServiceId);
        var awxCapabilityAction = this.getAwxDeployCapabilityAction(ActionType.DEPLOY, serviceHoster.getCapabilityService().getCapability());

        AwxJobObserver awxJobObserver;
        Map<String, String> serviceMetaData = new HashMap<>();
        List<Integer> servicePorts = new ArrayList<>();
        switch (serviceOfferingDeploymentType) {
            case DOCKER_CONTAINER:
            case DOCKER_COMPOSE: {
                var deployableComposeFile = this.getDeployableComposeFile(serviceOfferingVersion, serviceOrder);
                serviceMetaData = this.getServiceMetaData(serviceOfferingVersion, serviceOrder, deployableComposeFile);
                servicePorts = this.getServicePorts(serviceOfferingVersion, serviceOrder, deployableComposeFile);

                HashMap<String, Object> extraVarsMap = new HashMap<>() {{
                    put("service_id", serviceId);
                    put("keycloak_token", keycloakPrincipal.getKeycloakSecurityContext().getTokenString());
                    put("service_name", serviceHoster.getCapabilityService().getService());
                    put("supported_connection_types", awxCapabilityAction.getConnectionTypes());
                    put("docker_compose_file", deployableComposeFile);
                }};
                extraVarsMap = this.addExtraVarsForServiceRepositories(extraVarsMap, serviceOfferingVersion);

                if (serviceHoster.getCapabilityService().getCapability().getName().toLowerCase().contains("transferapp")) {
                    Map<String, String> configYaml = new HashMap<>() {{
                        put("Name", serviceOfferingVersion.getServiceOffering().getName());
                        put("Description", serviceOfferingVersion.getServiceOffering().getShortDescription());
                        put("Version", serviceOfferingVersion.getVersion());
                    }};
                    extraVarsMap.put("config_yml", configYaml);
                }
                var extraVars = new ExtraVars(extraVarsMap);

                awxJobObserver = this.runAwxCapabilityAction(awxCapabilityAction, keycloakPrincipal, extraVars, JobGoal.CREATE, this);
                this.notificationServiceClient.postJobObserver(keycloakPrincipal, awxJobObserver);
                break;
            }
            case KUBERNETES: {
                KubernetesManifestFile deployableManifestFile = this.getDeployableManifestFile(serviceOfferingVersion, serviceOrder);

                HashMap<String, Object> extraVarsMap = new HashMap<>() {{
                    put("resource_id", deploymentCapabilityServiceId);
                    put("service_id", serviceId);
                    put("keycloak_token", keycloakPrincipal.getKeycloakSecurityContext().getTokenString());
                    put("service_name", serviceHoster.getCapabilityService().getService());
                    put("supported_connection_types", awxCapabilityAction.getConnectionTypes());
                    put("manifest_file", KubernetesManifestFileParser.manifestFinalizer(deployableManifestFile));
                }};

                awxJobObserver = this.runAwxCapabilityAction(awxCapabilityAction, keycloakPrincipal, new ExtraVars(extraVarsMap), JobGoal.CREATE, this);
                this.notificationServiceClient.postJobObserver(keycloakPrincipal, awxJobObserver);
                break;
            }
            default:
                throw new NotImplementedException("Deployment Type '" + serviceOfferingDeploymentType + "' not supported");
        }

        UUID resourceId;
        if (serviceHoster.getCapabilityService() instanceof SingleHostCapabilityService) {
            resourceId = ((SingleHostCapabilityService)serviceHoster.getCapabilityService()).getConsulNodeId();
        }
        else {
            resourceId = serviceHoster.getCapabilityService().getId();
        }
        var serviceInstance = new ServiceInstance(
                serviceId,
                new ArrayList<>(),
                serviceMetaData,
                resourceId,
                serviceHoster.getCapabilityService().getId(),
                serviceOfferingVersion.getServiceOffering().getId(),
                serviceOfferingVersion.getId(),
                servicePorts,
                new ArrayList<>()
        );

        var deploymentJobRun = new DeploymentJobRun(awxJobObserver, keycloakPrincipal, serviceInstance, serviceOrder);
        this.observedAwxJobsToDeploymentJobDetails.put(awxJobObserver, deploymentJobRun);

        return deploymentJobRun;
    }

    private Map<String, String> getServiceMetaData(ServiceOfferingVersion serviceOfferingVersion, ServiceOrder serviceOrder,
                                                   DockerComposeFile deployableComposeFile) {
        var serviceMetaData = new HashMap<String, String>();
        switch (serviceOfferingVersion.getDeploymentType()) {
            case DOCKER_CONTAINER:
                var dockerContainerServiceOffering = (DockerContainerDeploymentDefinition) serviceOfferingVersion.getDeploymentDefinition();
                serviceMetaData = DockerContainerServiceOfferingOrderUtil
                        .getServiceMetaData(dockerContainerServiceOffering);
                break;

            case DOCKER_COMPOSE:
                var dockerComposeServiceOffering = (DockerComposeDeploymentDefinition) serviceOfferingVersion.getDeploymentDefinition();
                serviceMetaData = DockerComposeFileParser.getServiceMetaData(deployableComposeFile);
                break;

            case KUBERNETES:
                break;

            default:
                throw new NotImplementedException("Deployment Type '" + serviceOfferingVersion.getDeploymentType() + "' not supported");
        }

        return serviceMetaData;
    }

    private List<Integer> getServicePorts(ServiceOfferingVersion serviceOfferingVersion, ServiceOrder serviceOrder,
                                                   DockerComposeFile deployableComposeFile) {
        List<Integer> servicePorts = new ArrayList<Integer>();
        switch (serviceOfferingVersion.getDeploymentType()) {
            case DOCKER_CONTAINER:
            case DOCKER_COMPOSE:
                servicePorts = DockerComposeFileParser.getServicePorts(deployableComposeFile);
                break;

            case KUBERNETES:
                break;

            default:
                throw new NotImplementedException("Deployment Type '" + serviceOfferingVersion.getDeploymentType() + "' not supported");
        }

        servicePorts.addAll(serviceOfferingVersion.getServicePorts());

        return servicePorts;
    }

    private HashMap<String, Object> addExtraVarsForServiceRepositories(HashMap<String, Object> extraVarsMap, ServiceOfferingVersion serviceOfferingVersion) {
        if (serviceOfferingVersion.getServiceRepositories().size() > 0) {
            var dockerRegistriesVaultPaths = new ArrayList<String>();
            for (var serviceRepositoryId : serviceOfferingVersion.getServiceRepositories()) {
                dockerRegistriesVaultPaths.add("vendor_" + serviceOfferingVersion.getServiceOffering().getServiceVendor().getId() + "/" + serviceRepositoryId);
            }
            extraVarsMap.put("docker_registries_vault_paths", dockerRegistriesVaultPaths);
        }

        return extraVarsMap;
    }

    private DockerComposeFile getDeployableComposeFile(ServiceOfferingVersion serviceOfferingVersion, ServiceOrder serviceOrder)
            throws JsonProcessingException, ServiceOptionNotFoundException, InvalidServiceOfferingDefinitionException {
        DockerComposeFile deployableComposeFile = null;
        switch (serviceOfferingVersion.getDeploymentType())
        {
            case DOCKER_CONTAINER:
                deployableComposeFile = DockerContainerServiceOfferingOrderUtil
                        .generateDockerComposeFile(serviceOfferingVersion, serviceOrder);
                break;

            case DOCKER_COMPOSE:
                deployableComposeFile = DockerComposeFileParser.generateDeployableComposeFileForServiceOffering(
                        serviceOfferingVersion, serviceOrder.getServiceOptionValues());
                break;
        }

        if (deployableComposeFile == null) {
            throw new RuntimeException("Unable to create deployable Docker Compose File for order of service offering '"
                    + serviceOfferingVersion.getId() + "' version '" + serviceOfferingVersion.getVersion() + "'");
        }
        else {
            return deployableComposeFile;
        }
    }


    private KubernetesManifestFile getDeployableManifestFile(ServiceOfferingVersion serviceOfferingVersion, ServiceOrder serviceOrder)
            throws InvalidServiceOfferingDefinitionException {
        KubernetesManifestFile deployableManifestFile = null;
        switch (serviceOfferingVersion.getDeploymentType())
        {
            case KUBERNETES:
                deployableManifestFile = KubernetesManifestFileParser.generateDeployableManifestFileForServiceOffering(
                        serviceOfferingVersion, serviceOrder.getServiceOptionValues()
                );
                break;
        }

        if (deployableManifestFile == null) {
            throw new RuntimeException("Unable to create deployable Docker Compose File for order of service offering '"
                    + serviceOfferingVersion.getId() + "' version '" + serviceOfferingVersion.getVersion() + "'");
        }
        else {
            return deployableManifestFile;
        }
    }


    @Override
    public void onJobStateChanged(AwxJobObserver sender, JobState newState) {
    }

    @Override
    public void onJobStateFinished(AwxJobObserver sender, JobFinalState finalState) {
        if (this.observedAwxJobsToDeploymentJobDetails.containsKey(sender))
        {
            var jobDetails = this.observedAwxJobsToDeploymentJobDetails.get(sender);
            var keycloakPrincipal = jobDetails.getKeycloakPrincipal();
            var userUuid = KeycloakTokenUtil.getUserUuid(keycloakPrincipal);
            var serviceInstance = jobDetails.getServiceInstance();
            var serviceOrder = jobDetails.getServiceOrder();

            switch (finalState) {
                case SUCCESSFUL -> {
                    LOG.info("Service '" + serviceInstance.getId() + "' deployed for user '" + userUuid + "'");

                    // Add role for new service instance in Keycloak
                    var serviceKeycloakRoleName = "service_" + serviceInstance.getId();
                    this.keycloakUtil.createRealmRoleAndAssignToUser(keycloakPrincipal, serviceKeycloakRoleName);

                    // Add consul service for new service instance
                    this.serviceInstancesConsulClient.registerConsulServiceForServiceInstance(serviceInstance);

                    serviceOrder.setServiceOrderResult(ServiceOrderResult.SUCCESSFULL);
                    this.notificationServiceClient.postNotification(keycloakPrincipal, Category.Services, JobTarget.SERVICE, JobGoal.CREATE);
                }

                default -> {
                    serviceOrder.setServiceOrderResult(ServiceOrderResult.FAILED);
                    LOG.info("Service not deployed for user '" + userUuid + "', because job '" + sender.jobId +"' " + finalState);
                }
            }

            this.serviceOrderJpaRepository.save(serviceOrder);
            this.observedAwxJobsToDeploymentJobDetails.remove(sender);
        }
    }
}
