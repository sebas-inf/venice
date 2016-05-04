package com.linkedin.venice.controllerapi;

import org.codehaus.jackson.annotate.JsonIgnore;


/**
 * Created by mwise on 3/17/16.
 */
public class StoreCreationResponse extends ControllerResponse {

  private int version = -1;
  private String owner = null;
  private int partitions = 0;
  private int replicas = 0;
  private String kafkaTopic = null;
  private String kafkaBootstrapServers = null;

  public StoreCreationResponse(){  }

  public void setVersion(int version) {
    this.version = version;
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }

  public void setPartitions(int partitions) {
    this.partitions = partitions;
  }

  public void setReplicas(int replicas) {
    this.replicas = replicas;
  }

  public void setKafkaTopic(String kafkaTopic) {
    this.kafkaTopic = kafkaTopic;
  }

  public void setKafkaBootstrapServers(String kafkaBootstrapServers) {
    this.kafkaBootstrapServers = kafkaBootstrapServers;
  }

  public int getVersion() {
    return version;
  }

  public String getOwner() {
    return owner;
  }

  public int getPartitions() {
    return partitions;
  }

  public int getReplicas() {
    return replicas;
  }

  public String getKafkaTopic() {
    return kafkaTopic;
  }

  public String getKafkaBootstrapServers() {
    return kafkaBootstrapServers;
  }

}
