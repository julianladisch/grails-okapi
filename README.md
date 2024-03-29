# Grails OKAPI
Grails 3.3.x plugin for integrating your grails project with OKAPI.

## Dependencies
* [K-Int web toolkit](https://github.com/k-int/web-toolkit)
* [Spring Security](http://plugins.grails.org/plugin/grails/spring-security-core)
* [Database Migration](http://plugins.grails.org/plugin/grails/database-migration)

The plugin currently handles incoming headers from OKAPI and creates a user object for use with the Spring Security Core plugin.

## Usage

Include the plugin.
``` Groovy
dependencies {
  compile "com.k_int.okapi:grails-okapi:3.3.0"
}
```
You must also install and configure the dependants listed above.

grails package-plugin
gradle uploadArchives

To publish to nexus

## Register the provider name with spring security
``` YML
...
plugin:
  springsecurity:
    providerNames:
      - 'okapiAuthenticationProvider'
```
Because OKAPI is a JSON based, if you don't plan on allowing none OKAPI access you should remove the unecessary filters from the chain.
``` YML
plugin:
  springsecurity:
    filterChain:
      chainMap:
        - 
          pattern: '/**'
          filters: 'JOINED_FILTERS,-securityContextPersistenceFilter,-rememberMeAuthenticationFilter,-basicAuthenticationFilter'
```

## Integration with database migration
The tenant managing prcesses currently relly upon the database migration plugin, as this provides the most manageable way to handle schema changes between versions.

## The OKAPI Controller
This module provides an OKAPI controller, but no route is provided in the mappings by default. In order to allow OKAPI to notify the application, when a new tenant
is created, you will have to add aan entry into the URLMappings.groovy file for the tenant path ('_/tenant').

``` Groovy
"/_/tenant" (controller: 'okapi', action:'tenant')
```

## Integration with Spring security
Any permissions detailed in the permissionDesired when supplying a module descriptor to OKAPI are sent through to in the request from OKAPI if the user has them.
See the [authorization section](https://github.com/folio-org/okapi/blob/294a4328f542c5df8fc9d2b03ab3ed9474ac5006/doc/security.md#authorization) of the OKAPI docs.
When the above permissions come through the module will make these available for you to use throughout the application asauthorities within spring security. All
the permissions from OKAPI will be prefixed with 'folio.', to avoid any collisions with any other authorities you might assign.

You can use these to secure roots:
``` YML
plugin:
  springsecurity:
    ...  
    controllerAnnotations:
      staticRules:
        -
          pattern: '/application/**'
          access: 
            - 'permitAll'
        -
          pattern: '/**'
          access:
            - 'hasAuthority("folio.resource-sharing.user")'
```
Helper methods are added to the OKAPI aware controllers to allow you to easily access the 'patron' (the user based on the headers from OKAPI) and
the current patrons granted authorities.
Secure single method stubs, or use helpers present on the OKAPI controllers to do different things when different authorities are granted.

## Grails Resource Controllers

Grails supports domain classes as controller resources - see http://docs.grails.org/latest/guide/REST.html. OkapiTenantAwareController provides
some base classes modifed by java Generics.

``` Groovy
@CurrentTenant
class SomeController extends OkapiTenantAwareController<SomeMultiTenantDomain> {
  
  RequestController() {
    super(SomeMultiTenantDomain)
  }
  
  @Override
  def index() {
    
    if (patron && hasAuthority('folio.my.super.permisison')) {
      // Do something...
    } else {
      // Do something else...
    }
  }
  
  @Secured('hasAuthority("folio.my.special.permission")')
  def securedEntryPoint() {
    // Do something ...
  }
}
```

# Build

## Complile locally

    grails package-plugin - To build locally
    grails install - to install plugin in local repo
    grails publish-plugin - To upload the plugin to the public repo

# Troubleshooting

Q: I updated my module-descriptor template, but can't see the new routes.

A: It appears that okapiClient auto-redeploy will not work properly if there are tennants attached to the module you
want to update. In normal production environments of course, you would increment the version number but if you're developing
a module, you likely want some kind of SNAPSHOT behaviour. Current work-around is to remove the module manually and then
use the okapiClient to auto redeploy. This should pick up your new module-descriptor template and make the paths available.
Here is an example we use to nuke the olf-erm module.

    curl -XDELETE http://localhost:9130/_/proxy/tenants/diku/modules/olf-erm-1.0.0
    curl -XDELETE http://localhost:9130/_/discovery/modules/olf-erm-1.0.0/localhost-olf-erm-1.0.0
    curl -XDELETE http://localhost:9130/_/proxy/modules/olf-erm-1.0.0

# Troubleshooting

## Liquibase Lock / General memory constraints

If you are running in an environment that allows fine grained control over memory and cpu resources be aware that
adding k8s memory constraints WITHOUT setting -Xmx and other java memory settings may cause grails-okapi pods to be killed outright by the OOM-killer.
In particular, this death can occour very early in the application lifecycle and has often been seen as liquibase starts it's upgrade cycle.
IF you are seeing liquibase lock problems, look first to your memory configuration and local hosting setup. Ensure that you manually align your -Xmx values
with whatever resource limits you are setting.

In FOLIO/Okapi projects this has commonly manifested itself in a failed pod, followed by subsequent runs bombing out because they are unable to acquire the lock.
If you find yourself in this situation, correct the memory settings in your deployment, and then manually release the lock from the psql console with

    update TENANT_mod_MODULE.tenant_changelog_lock set locked = false;
