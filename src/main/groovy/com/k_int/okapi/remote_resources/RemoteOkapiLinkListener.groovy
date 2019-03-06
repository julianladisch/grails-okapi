package com.k_int.okapi.remote_resources;


import org.grails.datastore.gorm.jdbc.schema.SchemaHandler
import org.grails.datastore.mapping.engine.event.*
import org.grails.orm.hibernate.AbstractHibernateDatastore
import org.grails.orm.hibernate.connections.HibernateConnectionSource
import org.springframework.beans.BeanUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEvent
import org.springframework.context.ConfigurableApplicationContext

import com.k_int.okapi.OkapiClient
import com.k_int.okapi.OkapiTenantResolver

import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
class RemoteOkapiLinkListener implements PersistenceEventListener {
  
  @Autowired
  OkapiClient okapiClient
  
  protected final Set<String> datasourceNames = []
  
  public static void register (final AbstractHibernateDatastore datastore, ConfigurableApplicationContext ctx) {
    log.debug "Adding RemoteOkapiLinkListener for datastore ${datastore}'s children"
    
    // Schema handler is private. Re-initialise it from the connection settings.
    Class<? extends SchemaHandler> schemaHandlerClass = datastore.connectionSources.defaultConnectionSource.settings.dataSource.schemaHandler
    SchemaHandler schemaHandler = BeanUtils.instantiate(schemaHandlerClass)
    
    // Assume Hibernate connection, given Hibernate store
    HibernateConnectionSource hcs = datastore.connectionSources.defaultConnectionSource as HibernateConnectionSource
    
    // Grab all the Okapi schemas.
    def okapiSchemas = schemaHandler.resolveSchemaNames(hcs.dataSource).findResults { OkapiTenantResolver.isValidTenantSchemaName( it ) ? it : null }
    
    if (okapiSchemas) {
      // Cretae the listener
      RemoteOkapiLinkListener listener = new RemoteOkapiLinkListener()
      
      // ADd to the app context. 
      ctx.addApplicationListener ( listener )
      
      // Add all Okapi schema names. 
      for (String schema : okapiSchemas) {
        final AbstractHibernateDatastore ds = datastore.getDatastoreForConnection ( schema )

        // For this listener we use the datasource name.
        listener.datasourceNames << ds.dataSourceName
      }
    }
  }
  
  private RemoteOkapiLinkListener() { /* Hide the constructor */ }  
  
  @Memoized
  protected boolean isValidSource(AbstractPersistenceEvent event) {
    Object source = event.source
    return (source instanceof AbstractHibernateDatastore) && datasourceNames.contains((source as AbstractHibernateDatastore).dataSourceName)
  }
  
  protected void onPersistenceEvent(final AbstractPersistenceEvent event) {
    log.debug "${event.source} EVENT: ${event.eventType} on ${event.entityObject}"
  }
  
  /**
   * {@inheritDoc}
   * @see org.springframework.context.ApplicationListener#onApplicationEvent(
   *     org.springframework.context.ApplicationEvent)
   */
  @Override
  public final void onApplicationEvent(ApplicationEvent e) {
    if(e instanceof AbstractPersistenceEvent) {

      AbstractPersistenceEvent event = (AbstractPersistenceEvent)e;
      if(!isValidSource(event)) {
        return;
      }

      if (event.isCancelled()) {
        return;
      }

      if (event.isListenerExcluded(getClass().getName())) {
        return;
      }
      onPersistenceEvent(event);
    }
  }

  @Override
  @Memoized
  public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
    return eventType == PostLoadEvent
  }

  @Override
  public boolean supportsSourceType(Class<?> sourceType) {
    AbstractHibernateDatastore.isAssignableFrom(sourceType);
  }

  @Override
  public int getOrder() {
    return DEFAULT_ORDER;
  }
}