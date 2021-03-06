package wilda.fr;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.javaoperatorsdk.operator.api.reconciler.Context;
import io.javaoperatorsdk.operator.api.reconciler.DeleteControl;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceContext;
import io.javaoperatorsdk.operator.api.reconciler.EventSourceInitializer;
import io.javaoperatorsdk.operator.api.reconciler.Reconciler;
import io.javaoperatorsdk.operator.api.reconciler.UpdateControl;
import io.javaoperatorsdk.operator.processing.event.source.EventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.InformerEventSource;
import io.javaoperatorsdk.operator.processing.event.source.informer.Mappers;

public class NginxOperatorReconciler implements Reconciler<NginxOperator>, EventSourceInitializer<NginxOperator> { 
  private final KubernetesClient client;

  public NginxOperatorReconciler(KubernetesClient client) {
    this.client = client;
  }

  @Override
  public List<EventSource> prepareEventSources(EventSourceContext<NginxOperator> context) {
    System.out.println("👀 Create watcher on service 👀");
    SharedIndexInformer<Service> deploymentInformer = client.services().inAnyNamespace()
        .withLabel("app.kubernetes.io/managed-by", "nginx-operator").runnableInformer(0);

    return List.of(new InformerEventSource<>(deploymentInformer, Mappers.fromOwnerReference()));
  }

  @Override
  public UpdateControl<NginxOperator> reconcile(NginxOperator resource, Context context) {
    System.out.println("🛠️  Create / update Nginx resource operator ! 🛠️");

    String namespace = resource.getMetadata().getNamespace();

    // Load the Nginx deployment
    Deployment deployment = loadYaml(Deployment.class, "/k8s/nginx-deployment.yaml");
    // Apply the number of replicas and namespace
    deployment.getSpec().setReplicas(resource.getSpec().getReplicaCount());
    deployment.getMetadata().setNamespace(namespace);

    // Create or update Nginx server
    client.apps().deployments().inNamespace(namespace).createOrReplace(deployment);

    // Create service
    Service service = loadYaml(Service.class, "/k8s/nginx-service.yaml");
    Service existingService = client.services().inNamespace(resource.getMetadata().getNamespace())
        .withName("nginx-service").get();
    if (existingService == null || !existingService.getSpec().getPorts().get(0).getNodePort()
        .equals(resource.getSpec().getPort())) {
      service.getMetadata().getOwnerReferences().get(0).setName(resource.getMetadata().getName());
      service.getMetadata().getOwnerReferences().get(0).setUid(resource.getMetadata().getUid());
      service.getSpec().getPorts().get(0).setNodePort(resource.getSpec().getPort());
      client.services().inNamespace(namespace).createOrReplace(service);
    }

    return UpdateControl.noUpdate();
  }

  @Override
  public DeleteControl cleanup(NginxOperator resource, Context context) {
    System.out.println("💀 Delete Nginx resource operator ! 💀");

    client.apps().deployments().inNamespace(resource.getMetadata().getNamespace()).delete();
    client.services().inNamespace(resource.getMetadata().getNamespace()).withName("nginx-service")
        .delete();

    return Reconciler.super.cleanup(resource, context);
  }

  /**
   * Load a YAML file and transform it to a Java class.
   * 
   * @param clazz The java class to create
   * @param yamlPath The yaml file path in the classpath
   */
  private <T> T loadYaml(Class<T> clazz, String yamlPath) {
    try (InputStream is = getClass().getResourceAsStream(yamlPath)) {
      return Serialization.unmarshal(is, clazz);
    } catch (IOException ex) {
      throw new IllegalStateException("Cannot find yaml on classpath: " + yamlPath);
    }
  }
}

