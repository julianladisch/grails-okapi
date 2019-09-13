package com.k_int.okapi.remote_resources;

import grails.gorm.MultiTenant

public abstract class RemoteOkapiLink implements MultiTenant<RemoteOkapiLink> {
  
  abstract def remoteUri()
  
  String id // This is the internal ID of the link and not the ID of the remote license.
  
  String remoteId
  
  static mapping = {
    tablePerHierarchy false
             id column:'rol_id', generator: 'uuid', length: 36
       remoteId column:'rol_remote_id', length: 50 // UUIDs are 36 chars. Allowing some wriggle-room here..
        version column:'rol_version'
  }
  
  static constraints = {
       remoteId (nullable:false, blank:false)
  }
}
