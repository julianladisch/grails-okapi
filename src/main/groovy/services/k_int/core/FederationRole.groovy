package services.k_int.core;

public enum FederationRole {
  LEADER,
  DRONE
  
  @Override
  public String toString() {
    this.name().toLowerCase()
  }
}
