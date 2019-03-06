package com.k_int.okapi.remote_resources;


import org.grails.datastore.gorm.jdbc.schema.SchemaHandler
import org.grails.datastore.mapping.engine.event.*
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.model.PersistentProperty
import org.grails.datastore.mapping.model.types.Association
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
import java.util.concurrent.CompletableFuture

@CompileStatic
@Slf4j
class RemoteOkapiLinkListener implements PersistenceEventListener {
  
  @Autowired
  OkapiClient okapiClient
  
  // This will serve as a cache of paths to keep the performance up.
  // The value will either be a boolean FALSE or a set of properties to act on.
  private final Map<Class, ?> linkedProperties = [:]
  
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
        log.debug "\t...watching ${ds.dataSourceName}"
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
    
    Map<String, String> propertyNames = [:]
    
    def obj = event.entityObject
    log.debug "Checking cache..."
    if (linkedProperties.containsKey(obj.class)) {
      def value = linkedProperties[ obj.class ]
      if (value == false) {
        log.debug "\tIgnoring as per cache"
        return
      }
      
      if (value instanceof Map) {
        log.debug "\tFound cached values"
        propertyNames = value
      }
    } else {
      log.debug "\tNo cached values"
      // No cache. We need to find any properties we need to act on.
      if (RemoteOkapiLink.isAssignableFrom(obj.class)) {
        log.debug "${obj} is of link type"
        // In this early implementation we know what we want. Future expansion will allow for annotated properties etc.
        RemoteOkapiLink rol = RemoteOkapiLink as RemoteOkapiLink
        propertyNames.putAll(['remoteId':rol.remoteUri()])
      }
    }
    
    // If we arrive here with an empty collection of property names then we can ignore
    // in the future.
    if (propertyNames.size() < 1) {
      // Cache to ignore
      log.debug "Caching ${obj.class} as ignored"
      linkedProperties[obj.class] = false
      return
    }
    
    // From now on we know we want to prefetch some things.
    // Hand off to the decorator.
    decorateObject(obj, propertyNames)
  }
  
  public static final String FETCHED_PROPERTY_SUFFIX = '_object'
  private void decorateObject (def obj, Map<String,String> propertyNames) {
    log.debug "decorator called for ${obj}"
    propertyNames.each { propName, location ->
      final String uri = "${location}".replaceAll(/^\s*\/?(.*?)\/?\s*$/, '/$1') + '/' + obj[propName]
      
      // Use the okapi client to fetch a completable future for the value.
      log.debug "prefetching uri ${uri}"
      CompletableFuture backGroundFetch = okapiClient.getAsync(uri)
      
      // Add a metaproperty to the instance metaclass so we can access it later :)
      log.debug "adding property ${propName}${FETCHED_PROPERTY_SUFFIX}"
      obj.metaClass["${propName}${FETCHED_PROPERTY_SUFFIX}"] = backGroundFetch
    }
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
        return
      }

      if (event.isCancelled()) {
        return
      }

      if (event.isListenerExcluded(getClass().getName())) {
        return
      }
      onPersistenceEvent(event)
    }
  }

  @Override
  public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
    return eventType == PostLoadEvent
  }

  @Override
  public boolean supportsSourceType(Class<?> sourceType) {
    AbstractHibernateDatastore.isAssignableFrom(sourceType)
  }

  @Override
  public int getOrder() {
    return DEFAULT_ORDER
  }
}