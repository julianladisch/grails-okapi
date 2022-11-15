package com.k_int.okapi

import org.grails.config.PropertySourcesConfig
import org.grails.orm.hibernate.HibernateDatastore
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.support.ConfigurableConversionService
import org.springframework.core.env.PropertyResolver
import org.springframework.http.HttpStatus
import org.springframework.security.web.authentication.HttpStatusEntryPoint

import com.k_int.okapi.remote_resources.OkapiLookupHelper
import com.k_int.okapi.remote_resources.RemoteOkapiLinkListener
import com.k_int.okapi.springsecurity.OkapiAuthAwareAccessDeniedHandler
import com.k_int.okapi.springsecurity.OkapiAuthenticationFilter
import com.k_int.okapi.springsecurity.OkapiAuthenticationProvider
import com.k_int.okapi.system.FolioHibernateDatastore
import com.k_int.web.toolkit.utils.GormUtils

import grails.core.GrailsClass
import grails.plugin.springsecurity.SecurityFilterPosition
import grails.plugin.springsecurity.SpringSecurityUtils
import grails.plugins.*
import services.k_int.core.AppFederationService
import services.k_int.core.FolioLockService
import services.k_int.core.TenantManagerService

class GrailsOkapiGrailsPlugin extends Plugin {

  // the version or versions of Grails the plugin is designed for
  def grailsVersion = "4.0.4 > 5.0"
  
  def dependsOn = [
    "springSecurityCore": "4.0 > 5.0",
    "webToolkit":         "5.0 > *",
//    "databaseMigration":  "3.1 > 4"  // Because this plugin doesn't currently set a version we cannot depend on it.
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
    "springSecurityCore",
    "webToolkit",
    "databaseMigration",
    'hibernate'
  ]

  // License: one of 'APACHE', 'GPL2', 'GPL3'
  def license = "APACHE"

  // Details of company behind the plugin (if there is one)
  def organization = [ name: "Knowledge Integration", url: "http://www.k-int.com/" ]

  // Any additional developers beyond the author specified above.
  def developers = [ [ name: "Ian Ibbotson", email: "ian.ibbotson@k-int.com" ]]

  Closure doWithSpring() { {->
    
      okapiClient (OkapiClient)
      
      
      if (pluginManager.hasGrailsPlugin('hibernate')) {
        
        PropertyResolver config = (PropertyResolver)grailsApplication.config
        
        if(config instanceof PropertySourcesConfig) {
          ConfigurableConversionService conversionService = applicationContext.getEnvironment().getConversionService()
          conversionService.addConverter(new Converter<String, Class>() {
              @Override
              Class convert(String source) {
                  Class.forName(source)
              }
          })
          ((PropertySourcesConfig)config).setConversionService(conversionService)
        }
        
        hibernateDatastore(FolioHibernateDatastore, config, applicationContext)
        
        appFederationService ( AppFederationService )
        folioLockService ( FolioLockService )
        tenantManagerService ( TenantManagerService )
      }
    
      // If OKAPI aware app.
      if (pluginManager.hasGrailsPlugin('springSecurityCore')) {
        // Lets load some OKAPI authentication beans for easy integration.
        
        // Change the authentication end point to throw a 401
        authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED), "realm='${grailsApplication.config?.info?.app?.name ?: 'OKAPI'}'")
          
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
  
  @Override
  void doWithDynamicMethods() {
    // Bind extra methods to the class.
    (grailsApplication.getArtefacts("Domain")).each {GrailsClass gc ->
      OkapiLookupHelper.addMethods(gc, grailsApplication)
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
  
  @Override
  public void onStartup (Map<String, Object> event) {

    // Try and register the instance.
    final AppFederationService federationSvc = applicationContext.getBean ( AppFederationService )
    federationSvc.registerInstance()
    
    // TenantResolver can now return the actual tenants.
    OkapiTenantResolver.flagReady()
  }
}
