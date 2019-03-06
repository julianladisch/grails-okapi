package com.k_int.okapi

import static groovyx.net.http.ContentTypes.JSON
import static groovyx.net.http.HttpBuilder.configure

import javax.annotation.PostConstruct
import javax.servlet.http.HttpServletRequest

import org.grails.datastore.mapping.multitenancy.exceptions.TenantNotFoundException
import org.grails.io.support.PathMatchingResourcePatternResolver
import org.grails.io.support.Resource
import org.grails.web.util.WebUtils
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value

import grails.core.GrailsApplication
import grails.gorm.multitenancy.Tenants
import grails.http.client.*
import grails.util.Metadata
import groovy.json.JsonSlurper
import groovy.util.logging.Slf4j
import groovyx.net.http.ChainedHttpConfig
import groovyx.net.http.HttpBuilder
import groovyx.net.http.HttpException
import groovyx.net.http.HttpVerb
import groovyx.net.http.UriBuilder
import java.util.concurrent.CompletableFuture

@Slf4j
class OkapiClient {
  
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
  
  private HttpServletRequest getRequest() {
    try {
      
      return WebUtils.retrieveGrailsWebRequest()?.currentRequest
      
    } catch(IllegalStateException e) {
      log.debug "No request present."
    }
  }
  
  private void addConfig (ChainedHttpConfig cfg) {
    
    // Add tenant specific header here.
    try {
      String tenantId = OkapiTenantResolver.schemaNameToTenantId( Tenants.currentId().toString() )
      
      cfg.request.headers = [
        (OkapiHeaders.TENANT) : tenantId
      ]
      
      log.debug "Adding header for tenant ${tenantId}"
    } catch (TenantNotFoundException e) {
      /* No tenant */
      log.debug ('No tenant specific headers added as no current tenant for this request.')
    } catch (UnsupportedOperationException e) {
      /* Datastore doesn't support multi-tenancy */
      log.debug ('No tenant specific headers added as Multitenancy not supported.')
    }
    
    // Other config here.
    log.trace "Current headers are:"
    request?.getHeaderNames().each { String headerName ->
      log.trace "  ${headerName}:"
      for (String value : request.getHeaders(headerName)) {
        log.trace "    ${value}"
      } 
    }
    
    // Url...
    addUrl(cfg)
    
    // Add the token if present
    final String token = request?.getHeader(OkapiHeaders.TOKEN)
    if (token) {
      log.debug "Adding token"
      cfg.request.headers = [
        (OkapiHeaders.TOKEN) : token
      ]
    }
  }
  
  private void addUrl (ChainedHttpConfig cfg) {
    // Set the URL
    final url = (okapiHost && okapiPort) ? "http://${okapiHost}:${okapiPort}" : request?.getHeader(OkapiHeaders.URL)
    
    if (url) {
      log.debug "Setting url to: ${url}"
      final UriBuilder overrides = UriBuilder.root().setFull(url)
      
      // Now grab the host and port and scheme
      cfg.request.uri.scheme = overrides.scheme
      cfg.request.uri.host = overrides.host
      cfg.request.uri.port = overrides.port
    }
    
    // If there is a proxy... We should use that, but only if we have picked up an OKAPI request.
    final String proxyHost = request?.getHeader(OkapiHeaders.REQUEST_ID) ? request?.getHeader('host') : null
    if (proxyHost) {
      String[] parts = proxyHost.split(':')
      
      log.debug "Proxy present... Changing host to ${parts[0]}"
      cfg.request.uri.host = parts[0]
      
      if (parts.length > 1) {
        log.debug "\t...and port to ${parts[1]}"
        cfg.request.uri.port = (parts[1] as Integer)
      }
    }
    
    log.debug "Url is now ${cfg.request.uri.toURI()}"
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
      
      
      execution.interceptor(HttpVerb.values()) { ChainedHttpConfig cfg, fx ->
        
        // Add any headers we can derive to all types of requests.
        addConfig(cfg)
        
        // Request JSON.
        cfg.chainedRequest.contentType = JSON[0]
                  
        // Apply the original action.
        fx.apply(cfg)
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
   * 
   */
  private String cleanVals(String val) {
    
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
      
      log.info "Registering module with OKAPI... request path is /_/proxy/modules/${payload.id}"
      
      // Send the info.
      def response
      try {
        response = put ("/_/proxy/modules/${payload.id}", payload)
        
        log.info "Success: Got response ${response}"
      } catch (HttpException err) {
        // Error on put for update. Try posting for new mod.
        log.info "Error updating. Must be newly registering. err:${err} sc:${err.getStatusCode()}"
        
        response = post ('/_/proxy/modules', payload)
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
      
      final String discoUrl = '/_/discovery/modules'
      
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
  
  private cleanUri (String uri) {
    if (uri.startsWith('//')) {
      uri = uri.substring(1)
    }
    
    uri
  }
  
  public CompletableFuture getAsync (final String uri, final Map params = null, final Closure expand = null) {
    
    client.getAsync({
      request.uri = cleanUri(uri)
      request.uri.query = params
      if (expand) {
        expand.rehydrate(delegate, owner, thisObject)()
      }
    })
  }
  
  public def get (final String uri, final Map params = null, final Closure expand = null) {
    
    client.get({
      request.uri = cleanUri(uri)
      request.uri.query = params
      if (expand) {
        expand.rehydrate(delegate, owner, thisObject)()
      }
    })
  }
  
  public def post (final String uri, final def jsonData, final Map params = null, final Closure expand = null){
    client.post({
      request.uri = cleanUri(uri)
      request.uri.query = params
      request.body = jsonData
      
      if (expand) {
        expand.rehydrate(delegate, owner, thisObject)()
      }
    })
  }
  
  public def put (final String uri, final def jsonData, final Map params = null, final Closure expand = null) {
    client.put({
      request.uri = cleanUri(uri)
      request.uri.query = params
      request.body = jsonData
      
      if (expand) {
        expand.rehydrate(delegate, owner, thisObject)()
      }
    })
  }
  
  public def patch (final String uri, final def jsonData, final Map params = null, final Closure expand = null) {
    client.patch({
      request.uri = cleanUri(uri)
      request.uri.query = params
      request.body = jsonData
      
      if (expand) {
        expand.rehydrate(delegate, owner, thisObject)()
      }
    })
  }
  
  public def delete (final String uri, final Map params = null, final Closure expand = null) {
    client.delete({
      request.uri = cleanUri(uri)
      request.uri.query = params
      
      if (expand) {
        expand.rehydrate(delegate, owner, thisObject)()
      }
    })
  }
}
