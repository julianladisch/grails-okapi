package com.k_int.okapi

import static groovyx.net.http.ContentTypes.JSON
import static groovyx.net.http.HttpBuilder.configure

import com.github.zafarkhaja.semver.Version
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

import javax.annotation.PostConstruct
import javax.servlet.http.HttpServletRequest

import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException
import org.grails.io.support.PathMatchingResourcePatternResolver
import org.grails.io.support.Resource
import org.grails.web.servlet.mvc.GrailsWebRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.context.request.RequestContextHolder

import grails.core.GrailsApplication
import grails.gorm.multitenancy.Tenants
import grails.util.Metadata
import groovy.json.JsonSlurper
import groovy.text.SimpleTemplateEngine
import groovy.text.Template
import groovy.util.logging.Slf4j
import groovyx.net.http.FromServer
import groovyx.net.http.HttpBuilder
import groovyx.net.http.HttpConfig
import groovyx.net.http.HttpException
import groovyx.net.http.NativeHandlers
import groovyx.net.http.UriBuilder

/**
 * @TODO: This is getting too big and not well scoped.
 * @author Steve Osguthorpe<steve.osguthorpe@k-int.com>
 * 
 * Client for communicating with OKAPI and any modules
 * it is aware of. 
 */
@Slf4j
class OkapiClient {
  
  private static final String URI_PROXY = "/_/proxy"
  private static final String URI_VERSION = "/_/version"
  private static final String URI_DISCOVERY = "/_/discovery"
  
  private SimpleTemplateEngine tmplEng = new SimpleTemplateEngine()
  
  private static final List<String> EXTRA_JSON_TYPES = [
    'application/vnd.api+json'
  ]
  
  // Collect this for a full list of the supplied types plus our own. So it can be easily referenced elsewhere.
  public static final List<String> JSON_TYPES = JSON.collect { "${it}" } + EXTRA_JSON_TYPES
  
  @Autowired
  GrailsApplication grailsApplication
  
  @Value('${okapi.service.host:}')
  String okapiHost
  
  @Value('${okapi.service.port:80}')
  String okapiPort
  
  @Value('${grails.server.host:localhost}')
  String backReferenceHost
  
  @Value('${grails.server.port:8080}')
  String backReferencePort
  
  @Value('${okapi.service.deploy:true}')
  boolean selfDeployFlag
  
  @Value('${okapi.service.register:true}')
  boolean selfRegisterFlag
  
  HttpBuilder client
  
  final Map<String,Resource> descriptors = [:]
  
  boolean isRegistrationCapable() {
    descriptors.containsKey('module')
  }
  
  private final HttpServletRequest getRequestObject() {
    GrailsWebRequest gwr = (GrailsWebRequest)RequestContextHolder.requestAttributes
    if (!gwr) {
      log.debug "No request present."
    }
    
    gwr?.currentRequest
  }
  
  private final Map <String, Template> templateMap = new ConcurrentHashMap<String, Template>()
  public void decorateWithRemoteObjectFetcher (final def obj, final String methodName, final String uriTemplate, final Closure converter) {
    log.debug "Adding method ${methodName} to ${obj}"
    // Create the method closure base.
    final Closure metaMethod = { Template tmpl, Closure conv ->
      
      final def _me = delegate
      
      // Pipe in the current delegate as 'obj'
      final String uri = tmpl.make(['obj': _me])
      log.debug "Fetching ${uri} ..."
      
      log.debug "checking request cache..."
      def backGroundFetch = retrieveCacheValue( uri )
      
      if (!backGroundFetch) {
        log.debug "not found actually request the resource..."
        
        // Use the okapi client to fetch a completeable future for the value.
        log.debug "prefetching uri ${uri}"
        backGroundFetch = cacheValue( uri, getAsync(uri) )
      }
      
      if (backGroundFetch instanceof CompletableFuture) {
        CompletableFuture fut = backGroundFetch
        backGroundFetch = fut.thenApply({response -> 
          if (converter) {
            return converter.rehydrate(_me, _me, _me)( response )
          }
          
          // Default to returning the object.
          response
        })
      }

      // Return the fetch...
      backGroundFetch
    }
    
    // Fetch or create the template.
    final String uri = uriTemplate.replaceAll(/^\s*\/?(.*?)\/?\s*$/, '/$1')
    Template template = templateMap[uri]
    if (!template) {
      template = tmplEng.createTemplate(uri)
    }
    
    // Bind as a meta method.
    obj.metaClass[methodName] = metaMethod.curry(template, converter)
  }
  
  private String evaluateLocation (final def obj, final String propName, final def location) {
    
    String evaluated = ''
    switch (location) {
      case {it instanceof Closure} :
        
        Closure locationTmp = (location as Closure).rehydrate(obj, obj, obj)
        def result
        switch (locationTmp.maximumNumberOfParameters) {
          case 0:
            result = locationTmp.call()
            break
          case 1:
            result = locationTmp.call(obj)
            break
          default:
            throw new RuntimeException("Uri closure for ${obj} must accept 1 or 0 parameters")
        }
          
        // Assume the template replaces with the property, and just evaluate and clean
        evaluated = "${result}".replaceAll(/^\s*\/?(.*?)\s*$/, '/$1')
        break
      default:
        // evaluate
        evaluated = "${location}".replaceAll(/^\s*\/?(.*?)\/?\s*$/, '/$1') + '/' + obj[propName]
    }
    
    evaluated
  }
  
  public void decorateWithRemoteObjects (final def obj, final Map<String,Object> propertyNamesAndLocations, final String suffix) {
    log.debug "decorator called for ${obj}"
    propertyNamesAndLocations.each { propName, location ->
      final String uri = evaluateLocation(obj, propName, location)
      
      log.debug "checking request cache for ${uri}..."
      def backGroundFetch = retrieveCacheValue( uri )
      
      if (!backGroundFetch) {
        log.debug "not found actually request the resource..."
        
        // Use the okapi client to fetch a completeable future for the value.
        log.debug "prefetching uri ${uri}"
        backGroundFetch = cacheValue( uri, getAsync(uri) )
      }
      
      // Add a metaproperty to the instance metaclass so we can access it later :)
      log.debug "adding property ${propName}${suffix}"
      obj.metaClass["${propName}${suffix}"] = backGroundFetch
    }
  }
  
  private def cacheValue(final String key, final def value) {
    def req = requestObject
    if (req) {
      // Cache the value.
      Map<String, ?> requestCache = req.getAttribute(this.class.name) as Map<String, ?>
      if (requestCache == null) {
        requestCache = [:]
        req.setAttribute(this.class.name, requestCache)
      }
      requestCache[key] = value
    }
    
    value
  }
  
  private def retrieveCacheValue(final String key) {
    // Grab the value from the cache.
    Map<String, ?> requestCache = requestObject?.getAttribute(this.class.name) as Map<String, ?>
    requestCache?.get(key)
  }
  
  private static Serializable getCurrentTenantId () {
    final String tenantId = Tenants.CurrentTenant.get() ?: OkapiTenantResolver.resolveTenantIdentifierOptionally()
    tenantId ? OkapiTenantResolver.schemaNameToTenantId( tenantId ) : null
  }
  
  private final Map mapFromRequest() {
    
    log.trace "Current headers are:"
    requestObject?.getHeaderNames().each { String headerName ->
      log.trace "  ${headerName}:"
      for (String value : requestObject.getHeaders(headerName)) {
        log.trace "    ${value}"
      }
    }
    
    final Map theMap = [
      "okapi-url": requestObject?.getHeader(OkapiHeaders.URL),
//      "proxy-host": (requestObject?.getHeader(OkapiHeaders.REQUEST_ID) ? requestObject?.getHeader('host') : null),
      "headers" : [:]
    ]
    
    // Add tenant specific header here.
    try {
      final String tenantId = getCurrentTenantId()
      if (tenantId) {
        theMap['headers'][(OkapiHeaders.TENANT)] = tenantId
        log.debug "Adding header for tenant ${tenantId}"
      } else {
        log.debug ('No tenant specific headers added as no current tenant for this request.')
      }
      
    } catch (TenantNotFoundException e) {
      /* No tenant */
      log.debug ('Failed to resolve tenant')
    } catch (UnsupportedOperationException e) {
      /* Datastore doesn't support multi-tenancy */
      log.debug ('No tenant specific headers added as Multitenancy not supported.')
    }
    
    // Add the token if present
    final String token = requestObject?.getHeader(OkapiHeaders.TOKEN)
    if (token) {
      log.debug "Adding header for token"
      theMap['headers'][(OkapiHeaders.TOKEN)] = token
    }
    
    theMap
  }
  
  private void addConfig (final HttpConfig cfg, final Map cfgMap) {
    
    // If we have no config values then let's use okapi-url if present.
    if (!(okapiHost && okapiPort)) {
      addUrl(cfg, cfgMap['okapi-url'])
    }
    cfg.request.headers = cfgMap['headers']
  }
  
  private void addUrl (final HttpConfig cfg, final String url) {
    // Set the URL
    if (url) {
      log.debug "Setting url to: ${url}"
      final UriBuilder overrides = UriBuilder.root().setFull(url)
      
      // Now grab the host and port and scheme
      cfg.request.uri.scheme = overrides.scheme
      cfg.request.uri.host = overrides.host
      cfg.request.uri.port = overrides.port
    }
    
    log.debug "Url is now ${cfg.request.uri.toURI()}"
  }
  
  /**
   * Fetch the version of the OKAPI we are talking to.
   * @return The version string
   */
  String getVersion() {
    getSync(version)
  }
  
  @PostConstruct
  void init () {
    final String appName = Metadata.current.applicationName
        
    final String root = (okapiHost && okapiPort) ? "http://${okapiHost}:${okapiPort}" : null
    client = configure {
      
      // Default root as specified in config.
      if (root) {
                 
        log.info "Using default location for okapi at: ${root}"
        request.uri = root
      } else {
        log.info "No config options specifying okapiHost and okapiPort found on startup."
      }
      execution.executor = new ThreadPoolExecutor(
        3,  // Min Idle threads.
        50, // 100 threads max.
        1200, // 1.2 second wait.
        TimeUnit.MILLISECONDS, // Makes the above wait time in 'seconds'
        new SynchronousQueue<Runnable>() // Use a synchronous queue
      )
      
      // Default sending type.
      request.contentType = JSON[0]
      
      // Register vnd.api+json as parsable json.
      response.parser(EXTRA_JSON_TYPES) { HttpConfig cfg, FromServer fs ->
        NativeHandlers.Parsers.json(cfg, fs)
      }
      
      
      // Add timeouts.
      client.clientCustomizer { HttpURLConnection conn ->
        conn.connectTimeout = 2000
        conn.readTimeout = 3000
      }
    }
    
    registerAndDeploy()
  }

  private void registerAndDeploy() {    
    
    PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(grailsApplication.getClassLoader())
    Resource[] resources = resolver.getResources("classpath*:/okapi/*Descriptor.json")
    
    resources.each { Resource res ->
      log.info "Found Descriptor ${res.filename}"
      def matches = res.filename =~ /^(\w+)Descriptor\.json$/
      matches.each { groups ->
        log.info "Adding ${groups[1].toLowerCase()} to descriptors."
        descriptors."${groups[1].toLowerCase()}" = res
      }
    }
    
    // Attempt self registration.
    try {
      selfRegister()
    } catch (Exception err) {
      log.info "Error registering module with OKAPI. ${err.message}"
    }
    
    // Attempt self deployment
    try {
      selfDeploy()
    } catch (Exception err) {
      log.info "Error registering module deployment with OKAPI discovery. ${err.message}"
    }
  }
  
  /**
   * Because of a limitation of not being able to uppercase text within Kubernetes to generate the
   * necessary value. We may end up with a mixed case env variable that therefore gets missed. Try and clean here.
   */
  private final String cleanVals(String val) {
    
    val.replaceAll (/\$\(([^\)]+)\)/) { def fullMatch, def mixedName ->
      def key = "${mixedName}".replaceAll( /\W/, '_').toUpperCase()
      String newVal = System.getenv().getOrDefault(key, key)
      
      log.info "Rewriting ${fullMatch} as ${newVal}"
      
      newVal
    }
  }
  
  private void selfRegister () {
    
    if (!selfRegisterFlag) {
      log.info "Skipping registration with discovery as per config."
      return
    }
    
    if (!(okapiHost && okapiPort)) {
      
      log.info "Skipping registration with discovery as no okapiHost was specified"
      return
    }
    
    Resource modDescriptor = descriptors.module
    if (modDescriptor) {      
      
      // Post the descriptor to OKAPI
      def payload = new JsonSlurper().parseText ( modDescriptor.inputStream.text )
      
      log.info "Registering module with OKAPI... request path is ${URI_PROXY}/modules/${payload.id}"
      
      // Send the info.
      def response
      try {
        response = put ("${URI_PROXY}/modules/${payload.id}", payload)
        
        log.info "Success: Got response ${response}"
      } catch (HttpException err) {
        // Error on put for update. Try posting for new mod.
        log.info "Error updating. Must be newly registering. err:${err} sc:${err.getStatusCode()}"
        
        response = post ("${URI_PROXY}/modules", payload)
        log.info "Success: Got response ${response}"
      }
    } else {
      log.info "Skipping registration as no module descriptor could be found on the path."
    }
  }
  
  private void selfDeploy () {
    
    if (!selfDeployFlag) {
      log.info "Skipping deployment as per config."
      return
    }
    
    if (!(okapiHost && okapiPort)) {  
      log.info "Skipping deployment as no okapiHost and okapiPort was specified"
      return
    }
    
    Resource depDescriptor = descriptors.deployment
    if (depDescriptor) {
      
      // First we need to parse the deployment descriptor and replace with the correct values.
      def payload = new JsonSlurper().parseText ( depDescriptor.inputStream.text )
      
      if ( backReferenceHost && backReferencePort ) {
        backReferenceHost = cleanVals(backReferenceHost)
        backReferencePort = cleanVals(backReferencePort)
        payload.url = "http://${backReferenceHost}:${backReferencePort}/"
        
        // Using backReferenceHost default of localhost seems to cause okapi to choke with
        // BadRequest: 983996/discovery RES 400 3614us okapi Duplicate instance localhost
        // Current okapi du jour seems to be to supply a UUID for instId
        payload.instId = backReferenceHost
        
        // SO: The real issue with this was the deregistration below was pinging a request to the wrong URL. If you generate
        // many instance IDs that are unique here then OKAPI will think it can send to either. Only one instance will be running so OKAPI should have 1
        // Also, this would cause the deployed version of this module to not be found by OKAPI. Reinstating the above after fixing the issue below.
//        payload.instId = java.util.UUID.randomUUID().toString()
      }
      
      final String discoUrl = "${URI_DISCOVERY}/modules"
      
      def response
      try {
        final String delURI = "${discoUrl}/${payload.srvcId}/${payload.instId}"
        log.info "Attempt to de-register first using: ${delURI}"
        response = delete ("${delURI}")
        
      } catch (HttpException httpEx) {
        
        // Assume the response 404 means the module is
        if ((httpEx.fromServer?.statusCode ?: -1) == 404) {
          log.info "Treated error: \"${httpEx.body}\" as success."
        
        } else throw httpEx
      }
      
      log.info "Attempt to register deployment of module at ${payload.url}"
      
      // Send the info.
      response = post (discoUrl, payload)
      
      log.info "Success: Got response ${response}"
    } else {
      log.info "Skipping deployment registration with discovery as no deployment descriptor could be found on the path."
    }
  }
  private final String cleanUri (String uri) {
    if (uri.startsWith('//')) {
      uri = uri.substring(1)
    }
    
    uri
  }
  
  public final CompletableFuture getAsync (final String uri, final Map params = null, final Closure expand = null) {
    final Map rm = mapFromRequest()
    client.getAsync({      
      request.uri = cleanUri(uri)
      request.uri.query = params
      
      // Add any headers we can derive to all types of requests.
      addConfig(delegate, rm)
      
      if (expand) {
        expand.rehydrate(delegate, expand.owner, thisObject)()
      }
    })
  }
  
  public final def getSync (final String uri, final Map params = null, final Closure expand = null) {
    
    final Map rm = mapFromRequest()
    client.get({
      request.uri = cleanUri(uri)
      request.uri.query = params
      
      // Add any headers we can derive to all types of requests.
      addConfig(delegate, rm)
      
      if (expand) {
        expand.rehydrate(delegate, expand.owner, thisObject)()
      }
    })
  }
  
  public final def post (final String uri, final def jsonData, final Map params = null, final Closure expand = null) {
    
    final Map rm = mapFromRequest()
    client.post({
      request.uri = cleanUri(uri)
      request.uri.query = params
      request.body = jsonData
      
      // Add any headers we can derive to all types of requests.
      addConfig(delegate, rm)
      
      if (expand) {
        expand.rehydrate(delegate, expand.owner, thisObject)()
      }
    })
  }
  
  public def put (final String uri, final def jsonData, final Map params = null, final Closure expand = null) {
    
    final Map rm = mapFromRequest()
    client.put({
      request.uri = cleanUri(uri)
      request.uri.query = params
      request.body = jsonData
      
      // Add any headers we can derive to all types of requests.
      addConfig(delegate, rm)
      
      if (expand) {
        expand.rehydrate(delegate, expand.owner, thisObject)()
      }
    })
  }
  
  public final def patch (final String uri, final def jsonData, final Map params = null, final Closure expand = null) {
    
    final Map rm = mapFromRequest()
    client.patch({
      request.uri = cleanUri(uri)
      request.uri.query = params
      request.body = jsonData
      
      // Add any headers we can derive to all types of requests.
      addConfig(delegate, rm)
      
      if (expand) {
        expand.rehydrate(delegate, expand.owner, thisObject)()
      }
    })
  }
  
  public final def delete (final String uri, final Map params = null, final Closure expand = null) {
    
    final Map rm = mapFromRequest()
    
    client.delete({
      request.uri = cleanUri(uri)
      request.uri.query = params
      
      // Add any headers we can derive to all types of requests.
      addConfig(delegate, rm)
      
      if (expand) {
        expand.rehydrate(delegate, expand.owner, thisObject)()
      }
    })
  }
  
  public class OkapiTenantClient {
    
    private final OkapiClient client
    private static final String URI_TENANTS = "${URI_PROXY}/tenants"
    
    private OkapiTenantClient() { /* Hidden */ }
    private OkapiTenantClient(OkapiClient client) {
      this.client = client
    }
    
    private Serializable getCurrent() {
      getCurrentTenantId()
    }
    
    private String getCurrentUri() {
      "${URI_TENANTS}/${getCurrent()}"
    }
    
    private Version padToSemver ( final String version ) {
      if (version) {
        String semVerStr = version
        for (int i=version.split(/\./).length; i<3; i++) {
          semVerStr += '.0'
        }
        
        return Version.valueOf( semVerStr )
      }
      
      null
    }
    
    boolean providesInterface ( final String interfaceName, final String semverExpression ) {
     
      if (interfaceName && semverExpression) {        
        final String providedVersion = getInterfaces()?.get(interfaceName)?.getAt(0)
        if (!providedVersion) {
          log.debug "Enviroment does not provide interface: ${interfaceName}"
          return false
        }
                
        // Provides some version of the interface.
        return padToSemver(providedVersion).satisfies(semverExpression)
      }
      
      false
    }
    
    Map<String, Set<String>> getInterfaces() {
      final String cacheKey = 'getInterfaces'
      Map<String, Set<String>> keyedInterfaces = retrieveCacheValue(cacheKey)
      if (!keyedInterfaces) {
        keyedInterfaces = cacheValue (cacheKey, [:])
        
        final List<Map<String, String>> interfaces = client.getSync("${getCurrentUri()}/interfaces")
        
        interfaces.each { Map<String, String> ent ->
          if (!keyedInterfaces.containsKey(ent.id)) {
            keyedInterfaces[ent.id] = []
          }
          keyedInterfaces[ent.id] << ent.version
        }
      }
      
      keyedInterfaces
    }
  }
  
  private OkapiTenantClient tenantClient
  public OkapiTenantClient withTenant() {
    if (tenantClient == null) {
      tenantClient = new OkapiTenantClient(this)
    }
    
    if (tenantClient.current == null) {
      throw new UnsupportedOperationException("Cannot determine current tenant")
    }
    
    tenantClient
  }
}
