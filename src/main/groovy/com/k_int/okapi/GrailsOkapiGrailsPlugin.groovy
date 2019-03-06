package com.k_int.okapi

import org.grails.events.bus.*
import org.grails.orm.hibernate.HibernateDatastore
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.security.Http401AuthenticationEntryPoint
import org.springframework.boot.web.servlet.FilterRegistrationBean
import com.k_int.okapi.remote_resources.RemoteOkapiLinkListener
import com.k_int.okapi.springsecurity.OkapiAuthAwareAccessDeniedHandler
import com.k_int.okapi.springsecurity.OkapiAuthenticationFilter
import com.k_int.okapi.springsecurity.OkapiAuthenticationProvider

import grails.plugin.springsecurity.SecurityFilterPosition
import grails.plugin.springsecurity.SpringSecurityUtils
import grails.plugins.*

class GrailsOkapiGrailsPlugin extends Plugin {

  // the version or versions of Grails the plugin is designed for
  def grailsVersion = "3.3.2 > *"
  
  def dependsOn = [
    "springSecurityCore": "3.2.0 > *"
  ]
  // resources that are excluded from plugin packaging
  def pluginExcludes = [
    "grails-app/views/**",
    "grails-app/controllers/**"
  ]

  def title = "Grails Okapi" // Headline display name of the plugin
  def author = "Steve Osguthorpe"
  def authorEmail = "steve.osguthorpe@k-int.com"
  def description = '''\
    Adds helpers for integrating with the FOLIO OKAPI API gateway
  '''
  
  def profiles = ['web']
  def loadAfter = [
    'springSecurityCore'
  ]

  // License: one of 'APACHE', 'GPL2', 'GPL3'
  def license = "APACHE"

  // Details of company behind the plugin (if there is one)
  def organization = [ name: "Knowledge Integration", url: "http://www.k-int.com/" ]

  // Any additional developers beyond the author specified above.
  def developers = [ [ name: "Ian Ibbotson", email: "ian.ibbotson@k-int.com" ]]

  Closure doWithSpring() { {->
    
      okapiClient (OkapiClient)
    
    
      // If OKAPI aware app.
      if (pluginManager.hasGrailsPlugin('springSecurityCore')) {
        // Lets load some OKAPI authentication beans for easy integration.
        
        // Change the authentication end point to throw a 401
        authenticationEntryPoint(Http401AuthenticationEntryPoint, "realm='${grailsApplication.config?.info?.app?.name ?: 'OKAPI'}'")
          
        // This filter registers itself in a particular order in the chain.
        okapiAuthenticationFilter(OkapiAuthenticationFilter){ bean ->
          authenticationManager = ref('authenticationManager')
        }
        
        // Because of the custom ordering above we need to stop this from being de/registered automatically.
        okapiAuthenticationFilterDeregistrationBean(FilterRegistrationBean) {
          filter = ref('okapiAuthenticationFilter')
          enabled = false
        }
        
        okapiAuthenticationProvider(OkapiAuthenticationProvider)
        
        // Replace the AccessDenied handler to not redirect if the authentication was done with OKAPI.
        okapiAuthAwareAccessDeniedHandler(OkapiAuthAwareAccessDeniedHandler)
      }

    }
  }
  void doWithApplicationContext() {
    // Register this filter first.
    if (pluginManager.hasGrailsPlugin('springSecurityCore')) {
      SpringSecurityUtils.clientRegisterFilter(
        'okapiAuthenticationFilter', SecurityFilterPosition.FIRST)
    }
    
    // Register the listener _IF_ hibernate plugin is included in the application.
    if (pluginManager.hasGrailsPlugin('hibernate')) {
      HibernateDatastore datastore = applicationContext.getBean(HibernateDatastore)
      RemoteOkapiLinkListener.register(datastore, applicationContext)
    }
    
  }
}
