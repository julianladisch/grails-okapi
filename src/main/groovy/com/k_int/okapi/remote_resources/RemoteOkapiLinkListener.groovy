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
import org.springframework.web.context.request.RequestAttributes
import org.springframework.web.context.request.RequestContextHolder
import com.k_int.okapi.OkapiClient
import com.k_int.okapi.OkapiTenantResolver
import grails.web.api.ServletAttributes
import groovy.transform.CompileStatic
import groovy.transform.Memoized
import groovy.util.logging.Slf4j
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import net.sf.ehcache.CacheManager

@CompileStatic
@Slf4j
class RemoteOkapiLinkListener implements PersistenceEventListener, ServletAttributes {
  
  private static RemoteOkapiLinkListener singleton
  final OkapiClient okapiClient
  
  // This will serve as a cache of paths to keep the performance up.
  // The value will either be a boolean FALSE or a set of properties to act on.
  private final Map<Class, ?> linkedProperties = new ConcurrentHashMap<Class, ?> ()
  
  protected final Set<String> datasourceNames = []
  
  public static void register (final AbstractHibernateDatastore datastore, final ConfigurableApplicationContext applicationContext) {
    
    if (!singleton) {
    
      log.debug "Adding RemoteOkapiLinkListener for datastore ${datastore}'s children"
      
      // Schema handler is private. Re-initialise it from the connection settings.
      Class<? extends SchemaHandler> schemaHandlerClass = datastore.connectionSources.defaultConnectionSource.settings.dataSource.schemaHandler
      SchemaHandler schemaHandler = BeanUtils.instantiate(schemaHandlerClass)
      
      // Assume Hibernate connection, given Hibernate store
      HibernateConnectionSource hcs = datastore.connectionSources.defaultConnectionSource as HibernateConnectionSource
      
      // Grab all the Okapi schemas.
      def okapiSchemas = schemaHandler.resolveSchemaNames(hcs.dataSource).findResults { OkapiTenantResolver.isValidTenantSchemaName( it ) ? it : null }
      
      if (okapiSchemas) {
        // Create the listener
        RemoteOkapiLinkListener listener = new RemoteOkapiLinkListener( applicationContext )
        
        // Add all Okapi schema names. 
        for (String schema : okapiSchemas) {
          final AbstractHibernateDatastore ds = datastore.getDatastoreForConnection ( schema )
  
          // For this listener we use the datasource name.
          listener.datasourceNames << ds.dataSourceName
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
    
    // Flag the singleton.
    singleton = this
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
  
  protected void onPersistenceEvent(final AbstractPersistenceEvent event) {
    AbstractHibernateDatastore es = (AbstractHibernateDatastore) event.source
    Map<String, String> propertyNames = [:]
    
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
    
    // From now on we know we want to prefetch some things.
    // Hand off to the decorator.
    decorateObject(obj, propertyNames)
    
    // We should also cache this.
    if (value != propertyNames) linkedProperties[ obj.class ] = propertyNames
  }
  
  public static final String FETCHED_PROPERTY_SUFFIX = '_object'
  private void decorateObject (def obj, Map<String,String> propertyNames) {
    log.debug "decorator called for ${obj}"
    propertyNames.each { propName, location ->
      final String uri = "${location}".replaceAll(/^\s*\/?(.*?)\/?\s*$/, '/$1') + '/' + obj[propName]
      
      log.debug "checking request cache..."
      def backGroundFetch = retrieveCacheValue( uri )
      
      if (!backGroundFetch) {
        log.debug "not found actually request the resource..."
        
        // Use the okapi client to fetch a completable future for the value.
        log.debug "prefetching uri ${uri}"
        backGroundFetch = cacheValue( uri, okapiClient.getAsync(uri) )
      }
      
      // Add a metaproperty to the instance metaclass so we can access it later :)
      log.debug "adding property ${propName}${FETCHED_PROPERTY_SUFFIX}"
      obj.metaClass["${propName}${FETCHED_PROPERTY_SUFFIX}"] = backGroundFetch
    }
  }
  
  
  private def cacheValue(final String key, final def value) {
    if (request) {
      // Cache the value.
      Map<String, ?> requestCache = request.getAttribute(this.class.name) as Map<String, ?>
      if (requestCache == null) {
        requestCache = [:]
        request.setAttribute(this.class.name, requestCache)
      }
      requestCache[key] = value
    }
    
    value
  }
  
  private def retrieveCacheValue(final String key) {
    // Grab the value from the cache.
    Map<String, ?> requestCache = request?.getAttribute(this.class.name) as Map<String, ?>
    requestCache?.get(key)
  }
  
  private static final Set<String> ALLOWED_ACTIONS = ['show',  'update', 'save', 'create'] as Set<String> 
  /**
   * {@inheritDoc}
   * @see org.springframework.context.ApplicationListener#onApplicationEvent(
   *     org.springframework.context.ApplicationEvent)
   */
  @Override
  public final void onApplicationEvent(ApplicationEvent e) {
    if(e instanceof AbstractPersistenceEvent) {
      RequestAttributes rAttr = RequestContextHolder.getRequestAttributes()
      
      if (rAttr == null || !ALLOWED_ACTIONS.contains(actionName)) {
        log.debug "Skipping because ${!rAttr ? 'no request' : 'action ' + actionName + ' is not in list'}"
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