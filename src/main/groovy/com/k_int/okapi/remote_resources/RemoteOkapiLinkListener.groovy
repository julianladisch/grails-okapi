package com.k_int.okapi.remote_resources;


import org.grails.datastore.gorm.jdbc.schema.SchemaHandler
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.core.connections.ConnectionSource
import org.grails.datastore.mapping.core.connections.ConnectionSourcesListener
import org.grails.datastore.mapping.engine.event.*
import org.grails.datastore.mapping.multitenancy.MultiTenancySettings
import org.grails.orm.hibernate.AbstractHibernateDatastore
import org.grails.orm.hibernate.HibernateDatastore
import org.grails.orm.hibernate.connections.HibernateConnectionSource
import org.springframework.beans.BeanUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEvent
import com.k_int.okapi.OkapiClient
import com.k_int.okapi.OkapiTenantResolver

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
class RemoteOkapiLinkListener extends AbstractPersistenceEventListener {
  
  @Autowired
  OkapiClient okapiClient
  
  private RemoteOkapiLinkListener(final AbstractHibernateDatastore datastore, final boolean multiTenantInit) {
    
    // Handles this Datastore.
    super (datastore)
    
    // Should only be called once with boolean set to true
    if (multiTenantInit && datastore instanceof HibernateDatastore) {
      HibernateDatastore hds = datastore as HibernateDatastore
      
      Class<? extends SchemaHandler> schemaHandlerClass = hds.connectionSources.defaultConnectionSource.settings.dataSource.schemaHandler
      SchemaHandler schemaHandler = BeanUtils.instantiate(schemaHandlerClass)
      
      // Assume hibernate connection
      HibernateConnectionSource hcs = hds.connectionSources.defaultConnectionSource as HibernateConnectionSource
      
      for (String schema: schemaHandler.resolveSchemaNames(hcs.dataSource)) {
        if (OkapiTenantResolver.isValidTenantSchemaName( schema )) {
          final Datastore ds = hds.getDatastoreForConnection ( schema )
          log.debug "Adding listener for datastore ${ds}"
          datastore.applicationContext.addApplicationListener ( new RemoteOkapiLinkListener(ds, false) )
        }
      }
    }
  }
  
  public RemoteOkapiLinkListener(final AbstractHibernateDatastore datastore) {
    this(datastore, true)
    log.debug "Adding listener for datastore ${datastore}"
  }
  
  private boolean validateSource (AbstractHibernateDatastore ds) {
    ds.dataSourceName == (datastore as AbstractHibernateDatastore).dataSourceName
  }
  
  @Override
  protected boolean isValidSource(AbstractPersistenceEvent event) {
    Object source = event.getSource();
    return (source instanceof AbstractHibernateDatastore) && validateSource(source as AbstractHibernateDatastore);
  }
  
  @Override
  protected void onPersistenceEvent(final AbstractPersistenceEvent event) {
    log.debug "${datastore} EVENT: ${event.eventType} on ${event.entityObject}"
  }

  @Override
  @Memoized
  public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
    return eventType == PostLoadEvent
  }
}