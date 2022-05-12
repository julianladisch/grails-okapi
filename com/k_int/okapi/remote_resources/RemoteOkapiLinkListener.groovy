package com.k_int.okapi.remote_resources;


import java.util.concurrent.ConcurrentHashMap

import org.grails.datastore.gorm.jdbc.schema.SchemaHandler
import org.grails.datastore.mapping.engine.event.*
import org.grails.orm.hibernate.AbstractHibernateDatastore
import org.grails.orm.hibernate.connections.HibernateConnectionSource
import org.springframework.beans.BeanUtils
import org.springframework.context.ApplicationEvent
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder

import com.k_int.okapi.OkapiClient
import com.k_int.okapi.OkapiTenantResolver
import grails.core.GrailsApplication
import grails.util.Holders
import grails.web.api.ServletAttributes
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
class RemoteOkapiLinkListener implements PersistenceEventListener, ServletAttributes {
  
  private static RemoteOkapiLinkListener singleton
  final OkapiClient okapiClient
  
  // This will serve as a cache of paths to keep the performance up.
  // The value will either be a boolean FALSE or a set of properties to act on.
  private final Map<Class, ?> linkedProperties = new ConcurrentHashMap<Class, ?> ()
  
  protected final Set<String> datasourceNames = ConcurrentHashMap.newKeySet() 
  
  public static void register (final AbstractHibernateDatastore datastore, final ConfigurableApplicationContext applicationContext) {
    
    if (!singleton) {
      // Create the listener
      singleton = new RemoteOkapiLinkListener( applicationContext )
    
      log.debug "Adding RemoteOkapiLinkListener for datastore ${datastore}'s children"
      
      // Schema handler is private. Re-initialise it from the connection settings.
      Class<? extends SchemaHandler> schemaHandlerClass = datastore.connectionSources.defaultConnectionSource.settings.dataSource.schemaHandler
      SchemaHandler schemaHandler = BeanUtils.instantiate(schemaHandlerClass)
      
      // Assume Hibernate connection, given Hibernate store
      HibernateConnectionSource hcs = datastore.connectionSources.defaultConnectionSource as HibernateConnectionSource
      
      // Grab all the Okapi schemas.
      def okapiSchemas = schemaHandler.resolveSchemaNames(hcs.dataSource).findResults { OkapiTenantResolver.isValidTenantSchemaName( it ) ? it : null }
      
      if (okapiSchemas) {
        
        // Add all Okapi schema names. 
        for (String schema : okapiSchemas) {
          final AbstractHibernateDatastore ds = datastore.getDatastoreForConnection ( schema )
  
          // For this listener we use the datasource name.
          singleton.datasourceNames << ds.dataSourceName
          log.debug "\t...watching ${ds.dataSourceName}"
        }
      }
    } else {
      log.warn "Application listener already exists. Not adding again."
    }
  }
  
  private RemoteOkapiLinkListener(final ConfigurableApplicationContext applicationContext) {
    
    // Just fetch the bean.
    this.okapiClient = applicationContext.getBean('okapiClient', OkapiClient)
        
    // Add to the app context.
    applicationContext.addApplicationListener ( this )
  }
  
  public static void listenForConnectionSourceName(final String connName) {
    singleton.datasourceNames << connName
  }
  
  private ConcurrentHashMap<String, Boolean> validSourceCache = [:] as ConcurrentHashMap<String, Boolean>
  
  protected boolean isValidSource(AbstractPersistenceEvent event) {
    Object source = event.source
    
    Boolean isValid = validSourceCache[event.source.class.name]
    if (isValid == null) {
      isValid = (source instanceof AbstractHibernateDatastore) && datasourceNames.contains((source as AbstractHibernateDatastore).dataSourceName)
      validSourceCache[event.source.class.name] = isValid
    }
    
    isValid
  }
  
  
  public static final String FETCHED_PROPERTY_SUFFIX = '_object'
  protected void onPersistenceEvent(final AbstractPersistenceEvent event) {
    AbstractHibernateDatastore es = (AbstractHibernateDatastore) event.source
    Map<String, Object> propertyNames = [:]
    
    def obj = event.entityObject
    log.trace "Checking cache..."
    def value
    if (linkedProperties.containsKey(obj.class)) {
      value = linkedProperties[ obj.class ]
      if (value == false) {
        log.trace "\tIgnoring as per cache"
        return
      }
      
      if (value instanceof Map) {
        log.trace "\tFound cached values"
        propertyNames = value
      }
    } else {
      log.debug "\tNo cached values"
      // No cache. We need to find any properties we need to act on.
      if (RemoteOkapiLink.isAssignableFrom(obj.class)) {
        log.debug "${obj} is of link type"
        // In this early implementation we know what we want. Future expansion will allow for annotated properties etc.
        RemoteOkapiLink rol = obj as RemoteOkapiLink
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
    
    // From now on we know we want to pre-fetch some things.
    // Hand off to the decorator.
    okapiClient.decorateWithRemoteObjects(obj, propertyNames, FETCHED_PROPERTY_SUFFIX)
    
    // We should also cache this.
    if (value != propertyNames) {
      linkedProperties[ obj.class ] = propertyNames
      log.debug "Cached ${propertyNames} for ${obj.class}"
    }
  }
  
  private final Map<String, Set<String>> allowedControllerActions = [:]
  private final Set<String> defaultAllowedActions = ['show', 'update', 'save', 'create'] as Set
  
//  @Memoized
  public final Set<String> getAllowedContollerActions(final String controller) {

    if ((allowedControllerActions?.size() ?: 0) == 0) {
      allowedControllerActions['_'] = defaultAllowedActions
      
      GrailsApplication ga = (GrailsApplication)applicationContext.getBean('grailsApplication')
      
      def nativeVal = ga.config.getProperty ('okapi.linkListener.allowedControllerActions', Map, [:])
      allowedControllerActions.putAll( nativeVal )
    }
    
    final Set<String> actionList = allowedControllerActions[controller] ?: allowedControllerActions['_']
    log.trace "allowed action for controller = ${actionList}"
    actionList
  }
  
  /**
   * {@inheritDoc}
   * @see org.springframework.context.ApplicationListener#onApplicationEvent(
   *     org.springframework.context.ApplicationEvent)
   */
  @Override
  public final void onApplicationEvent(ApplicationEvent e) {
    
//    log.debug "Caught Event: ${e} for source ${e.source}"
    if(e instanceof AbstractPersistenceEvent) {
      RequestAttributes rAttr = RequestContextHolder.getRequestAttributes()
      
      if (rAttr == null || !this.getAllowedContollerActions(controllerName).contains(actionName)) {
        log.trace "Skipping because ${!rAttr ? 'no request' : 'action ' + actionName + ' is not in list for controller ${controllerName}'}"
        return
      } 
      
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
  
  private static final Set<String> supportedEvents = [PostLoadEvent.class.name, PostUpdateEvent.class.name, PostInsertEvent.class.name] as Set<String>

  
  @Memoized
  public boolean supportsEventTypeClassName(final String eventTypeClassName) {
    boolean isSupported = supportedEvents.contains(eventTypeClassName)
    isSupported
  }
  
  @Override
  public boolean supportsEventType(Class<? extends ApplicationEvent> eventType) {
    supportsEventTypeClassName(eventType.name)
//    true
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