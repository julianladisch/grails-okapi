package com.k_int.okapi.testing

import static groovyx.net.http.ContentTypes.*
import static org.springframework.http.HttpStatus.*

import org.junit.Assume
import org.springframework.beans.factory.annotation.Autowired

import com.k_int.okapi.OkapiClient
import com.k_int.okapi.OkapiHeaders
import com.k_int.web.toolkit.testing.HttpSpec
import geb.spock.GebSpec
import grails.util.Holders
import grails.web.http.HttpHeaders
import groovyx.net.http.FromServer
import groovyx.net.http.FromServer.Header
import spock.lang.Shared

abstract class OkapiSpec extends HttpSpec {
  
  @Shared
  private final Map<String,String> loginDetails = [
    username:'diku_admin',
    password: 'admin'
  ]
  
  def setupSpect () {
    addDefaultHeaders ((OkapiHeaders.TENANT): 'diku')
  }
  
  protected void setLoginDetails(final String username, final String password) {
    loginDetails.putAll (
      username: username,
      password: password
    )
  }
  
  protected void setTenant(final String tenant) {
    setHeaders( (OkapiHeaders.TENANT), tenant)
  }

  @Autowired
  private OkapiClient okapiClient
  
  @Shared
  private boolean okapiPresent = false
  protected void assumeOkapiPresent() {
    Assume.assumeTrue( okapiPresent )
  }
  protected void assumeOkapiNotPresent() {
    Assume.assumeFalse( okapiPresent )
  }
  
  @Shared
  private boolean okapiSignedIn = false
  protected void assumeOkapiSignedIn() {
    Assume.assumeTrue( okapiSignedIn ) 
  }
  protected void assumeOkapiNotSignedIn() {
    Assume.assumeFalse( okapiSignedIn ) 
  }
  
  /**
   * The below methods handle all the OKAPI interaction. You should use these inside your spec to communicate with OKAPI. 
   */
  protected def doOkapiGet (final String uri, final Map params = null, final Closure expand = null) {
    okapiClient.getSync(uri, params) {
      request.headers = (specDefaultHeaders + headersOverride)
      if (expand) {
        expand.rehydrate(delegate, expand.owner, thisObject)()
      }
    }
  }
  
  protected def doOkapiPost (final String uri, final def jsonData, final Map params = null, final Closure expand = null) {
    okapiClient.post(uri, jsonData, params) {
      request.headers = (specDefaultHeaders + headersOverride)
      if (expand) {
        expand.rehydrate(delegate, expand.owner, thisObject)()
      }
    }
  }
  
  protected def doOkapiPut (final String uri, final def jsonData, final Map params = null, final Closure expand = null) {
    okapiClient.put(uri, jsonData, params) {
      request.headers = (specDefaultHeaders + headersOverride)
      if (expand) {
        expand.rehydrate(delegate, expand.owner, thisObject)()
      }
    }
  }
  
  protected def doOkapiDelete (final String uri, final Map params = null, final Closure expand = null) {
    okapiClient.delete(uri, params) {
      request.headers = (specDefaultHeaders + headersOverride)
      if (expand) {
        expand.rehydrate(delegate, expand.owner, thisObject)()
      }
    }
  }

  @Override
  def setupSpecWithSpring() {
    super.setupSpecWithSpring()
    try {
      okapiClient.post('/authn/login', loginDetails, [:]) {
        request.setHeaders((specDefaultHeaders + headersOverride))
        response.success { FromServer fs ->
          okapiPresent = true
  
          fs.getHeaders().each { Header h ->
            if (h.key.toLowerCase() == OkapiHeaders.TOKEN.toLowerCase()) {
              headers.put(h.key, h.value)
              okapiSignedIn = true
            }
          }
        }
      }
    } catch (RuntimeException ex) {
      // Suppress the none connectivity of OKAPI.
      if (!(ex.cause instanceof ConnectException)) {
        // Any other error should be thrown so that the specs fail.
        throw ex
      }
      okapiPresent = false
      okapiSignedIn = false 
    }
  }
}
