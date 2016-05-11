package com.linkedin.venice.controller;

import com.linkedin.venice.controlmessage.StoreStatusMessage;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.helix.HelixAdapterSerializer;
import com.linkedin.venice.helix.HelixCachedMetadataRepository;
import com.linkedin.venice.helix.HelixInstanceConverter;
import com.linkedin.venice.helix.HelixJobRepository;
import com.linkedin.venice.helix.HelixRoutingDataRepository;
import com.linkedin.venice.helix.TestHelixRoutingDataRepository;
import com.linkedin.venice.job.Job;
import com.linkedin.venice.job.ExecutionStatus;
import com.linkedin.venice.meta.Instance;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.meta.VersionStatus;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import java.util.HashMap;
import java.util.Map;
import org.apache.helix.HelixAdmin;
import org.apache.helix.HelixManager;
import org.apache.helix.HelixManagerFactory;
import org.apache.helix.InstanceType;
import org.apache.helix.LiveInstanceInfoProvider;
import org.apache.helix.ZNRecord;
import org.apache.helix.controller.HelixControllerMain;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.helix.manager.zk.ZKHelixManager;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.ZkServerWrapper;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.helix.model.HelixConfigScope;
import org.apache.helix.model.IdealState;
import org.apache.helix.model.builder.HelixConfigScopeBuilder;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


/**
 * Test cases for Venice job manager.
 */
public class TestVeniceJobManager {
  private VeniceJobManager jobManager;
  private HelixJobRepository jobRepository;
  private HelixCachedMetadataRepository metadataRepository;

  private String zkAddress;
  private ZkServerWrapper zkServerWrapper;
  private ZkClient zkClient;
  private String cluster = "jobTestCluster";
  private HelixRoutingDataRepository routingDataRepository;
  private HelixAdmin admin;
  private HelixManager controller;
  private HelixManager manager;
  private String storeName = "ts1";
  private String kafkaTopic = "ts1_v1";
  private String nodeId = "localhost_9985";

  private Store store;
  private Version version;

  @BeforeMethod
  public void setup()
      throws Exception {
    zkServerWrapper = ServiceFactory.getZkServer();
    zkAddress = zkServerWrapper.getAddress();

    admin = new ZKHelixAdmin(zkAddress);
    admin.addCluster(cluster);
    HelixConfigScope configScope = new HelixConfigScopeBuilder(HelixConfigScope.ConfigScopeProperty.CLUSTER).
        forCluster(cluster).build();
    Map<String, String> helixClusterProperties = new HashMap<String, String>();
    helixClusterProperties.put(ZKHelixManager.ALLOW_PARTICIPANT_AUTO_JOIN, String.valueOf(true));
    admin.setConfig(configScope, helixClusterProperties);
    admin.addStateModelDef(cluster, TestHelixRoutingDataRepository.UnitTestStateModel.UNIT_TEST_STATE_MODEL,
        TestHelixRoutingDataRepository.UnitTestStateModel.getDefinition());

    admin.addResource(cluster, kafkaTopic, 1, TestHelixRoutingDataRepository.UnitTestStateModel.UNIT_TEST_STATE_MODEL,
        IdealState.RebalanceMode.FULL_AUTO.toString());
    admin.rebalance(cluster, kafkaTopic, 1);

    controller = HelixControllerMain
        .startHelixController(zkAddress, cluster, Utils.getHelixNodeIdentifier(12345), HelixControllerMain.STANDALONE);
    manager = HelixManagerFactory.getZKHelixManager(cluster, nodeId, InstanceType.PARTICIPANT, zkAddress);
    manager.getStateMachineEngine()
        .registerStateModelFactory(TestHelixRoutingDataRepository.UnitTestStateModel.UNIT_TEST_STATE_MODEL,
            new TestHelixRoutingDataRepository.UnitTestStateModelFactory());
    Instance instance = new Instance(nodeId, Utils.getHostName(), 9986);
    manager.setLiveInstanceInfoProvider(new LiveInstanceInfoProvider() {
      @Override
      public ZNRecord getAdditionalLiveInstanceInfo() {
        return HelixInstanceConverter.convertInstanceToZNRecord(instance);
      }
    });
    manager.connect();
    Thread.sleep(1000l);
    routingDataRepository = new HelixRoutingDataRepository(controller);
    routingDataRepository.refresh();

    zkClient = new ZkClient(zkAddress);
    zkClient.createPersistent("/" + cluster + "stores");
    HelixAdapterSerializer adapterSerializer = new HelixAdapterSerializer();
    routingDataRepository = new HelixRoutingDataRepository(manager);
    routingDataRepository.refresh();
    jobRepository = new HelixJobRepository(zkClient, adapterSerializer, cluster, routingDataRepository);
    jobRepository.refresh();
    metadataRepository = new HelixCachedMetadataRepository(zkClient, adapterSerializer, cluster);
    metadataRepository.refresh();
    jobManager = new VeniceJobManager(cluster , 1, jobRepository, metadataRepository);
    store = TestUtils.createTestStore(storeName, "test", System.currentTimeMillis());
    version = store.increaseVersion();
  }

  @AfterMethod
  public void cleanup() {
    jobRepository.clear();
    metadataRepository.clear();
    routingDataRepository.clear();
    manager.disconnect();
    controller.disconnect();
    admin.dropCluster(cluster);
    admin.close();
    zkClient.deleteRecursive("/" + cluster + "stores");
    zkClient.close();
    zkServerWrapper.close();
  }

  @Test
  public void testHandleMessage()
      throws InterruptedException {

    metadataRepository.addStore(store);
    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1);

    StoreStatusMessage message =
        new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    jobManager.handleMessage(message);
    Job job = jobRepository.getRunningJobOfTopic(version.kafkaTopicName()).get(0);
    Assert.assertEquals(jobRepository.getJobStatus(job.getJobId(), job.getKafkaTopic()), ExecutionStatus.STARTED,
        "Job should be started.");

    message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.COMPLETED);
    jobManager.handleMessage(message);
    //Wait ZK notification.
    Thread.sleep(1000l);
    Store updatedStore = metadataRepository.getStore(storeName);
    Assert.assertEquals(updatedStore.getCurrentVersion(), version.getNumber(),
        "Push has been done, store's current should be updated.");

    Assert.assertEquals(updatedStore.getVersions().get(0).getStatus(), VersionStatus.ACTIVE,
        "Push has been done. Version should be activated.");
    jobManager.archiveJobs(version.kafkaTopicName());
    try {
      jobRepository.getJob(job.getJobId(), job.getKafkaTopic());
      Assert.fail("Job should be archived.");
    } catch (VeniceException e) {
      //expected.
    }
  }

  @Test
  public void testHandleMessageWhenTaskFailed()
      throws InterruptedException {
    metadataRepository.addStore(store);
    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1);

    StoreStatusMessage message =
        new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    jobManager.handleMessage(message);
    Job job = jobRepository.getRunningJobOfTopic(version.kafkaTopicName()).get(0);
    Assert.assertEquals(jobRepository.getJobStatus(job.getJobId(), job.getKafkaTopic()), ExecutionStatus.STARTED,
        "Job should be started.");

    message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.ERROR);
    jobManager.handleMessage(message);
    //Wait ZK notification.
    Thread.sleep(1000l);
    Store updatedStore = metadataRepository.getStore(storeName);
    Assert.assertEquals(updatedStore.getCurrentVersion(), 0,
        "Push was failed. No current version is active for this store.");
    Assert.assertEquals(updatedStore.getVersions().get(0).getStatus(), VersionStatus.STARTED,
        "Push was failed. Version should not be activated.");
    jobManager.archiveJobs(version.kafkaTopicName());
    try {
      jobRepository.getJob(job.getJobId(), job.getKafkaTopic());
      Assert.fail("Job should be archived.");
    } catch (VeniceException e) {
      //expected.
    }
  }

  @Test
  public void testGetOfflineJobStatus() {
    metadataRepository.addStore(store);
    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1);
    Assert.assertEquals(jobManager.getOfflineJobStatus(version.kafkaTopicName()), ExecutionStatus.STARTED,
        "Job should be started.");

    StoreStatusMessage message =
        new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    jobManager.handleMessage(message);

    message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.COMPLETED);
    jobManager.handleMessage(message);

    Assert.assertEquals(jobManager.getOfflineJobStatus(version.kafkaTopicName()), ExecutionStatus.COMPLETED);
  }

  @Test
  public void testGetOfflineJobStatusWhenTaskFailed() {
    metadataRepository.addStore(store);
    jobManager.startOfflineJob(version.kafkaTopicName(), 1, 1);
    Assert.assertEquals(jobManager.getOfflineJobStatus(version.kafkaTopicName()), ExecutionStatus.STARTED,
        "Job should be started.");

    StoreStatusMessage message =
        new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.STARTED);
    jobManager.handleMessage(message);

    message = new StoreStatusMessage(version.kafkaTopicName(), 0, nodeId, ExecutionStatus.ERROR);
    jobManager.handleMessage(message);

    Assert.assertEquals(jobManager.getOfflineJobStatus(version.kafkaTopicName()), ExecutionStatus.ERROR);
  }
}
