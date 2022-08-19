package com.k_int.okapi.system

import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

import org.grails.datastore.gorm.events.ConfigurableApplicationContextEventPublisher
import org.grails.datastore.mapping.config.Settings
import org.grails.datastore.mapping.core.Datastore
import org.grails.datastore.mapping.core.DatastoreUtils
import org.grails.datastore.mapping.core.connections.ConnectionSource
import org.grails.datastore.mapping.core.connections.ConnectionSourcesSupport
import org.grails.datastore.mapping.model.DatastoreConfigurationException
import org.grails.datastore.mapping.model.PersistentEntity
import org.grails.datastore.mapping.multitenancy.AllTenantsResolver
import org.grails.datastore.mapping.multitenancy.MultiTenancySettings
import org.grails.orm.hibernate.HibernateDatastore;
import org.grails.orm.hibernate.HibernateGormEnhancer
import org.grails.orm.hibernate.connections.HibernateConnectionSource
import org.grails.orm.hibernate.connections.HibernateConnectionSourceFactory
import org.hibernate.cfg.Environment
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.core.env.PropertyResolver
import org.springframework.jdbc.datasource.ConnectionHolder
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy
import org.springframework.transaction.support.TransactionSynchronizationManager

import grails.gorm.MultiTenant
import groovy.sql.Sql
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import net.bytebuddy.implementation.bind.annotation.Super
import services.k_int.core.SystemDataService
import services.k_int.core.liquibase.ExtendedGrailsLiquibase

@CompileStatic
@Slf4j
public class FolioHibernateDatastore extends HibernateDatastore {

  public static final String PROPERTY_HIBERNATE = 'hibernate'
  public static final String PROPERTY_FOLIO_SYSTEM = 'folio'

  private static PropertyResolver _systemSourceSettings

  private PropertyResolver getSystemSourceSettings() {

    if (_systemSourceSettings) return _systemSourceSettings

    _systemSourceSettings = DatastoreUtils.createPropertyResolvers(
        [
          (Settings.PREFIX) : this.connectionDetails.getProperty((Settings.PREFIX), Map, [:]) as Map, // grails.gorm
          (PROPERTY_HIBERNATE) : this.connectionDetails.getProperty((PROPERTY_HIBERNATE), Map, [:]) as Map, // hibernate
          (Settings.SETTING_DATASOURCE) : this.connectionDetails.getProperty((Settings.SETTING_DATASOURCE), Map, [:]) as Map // datasource
        ] as Map,
        // Defaults for the system DS only.
        [
          (Environment.DEFAULT_SCHEMA): SystemDataService.getSystemDatasourceSchemaName(),
          (Settings.SETTING_DB_CREATE) : 'none',
          (Environment.HBM2DDL_AUTO): 'none',
          (Settings.SETTING_MULTI_TENANCY_MODE): 'none'
        ] as Map,

        this.connectionDetails.getProperty((PROPERTY_FOLIO_SYSTEM), Map, [:]) as Map)

    _systemSourceSettings
  }

  public FolioHibernateDatastore( PropertyResolver config, ConfigurableApplicationContext applicationContext, HibernateConnectionSourceFactory factory) {
    super(config, factory, new ConfigurableApplicationContextEventPublisher(applicationContext))
    log.info 'Using the FolioHibernateDatastore'
  }

  public static boolean usesSystemConnectionSource( PersistentEntity entity ) {
    List<String> names = ConnectionSourcesSupport.getConnectionSourceNames(entity);
    return names.contains(SystemDataService.DATASOURCE_SYSTEM) || names.contains(ConnectionSource.ALL);
  }

  public Class<?>[] findSystemDomainArtefacts() {
    this.mappingContext.persistentEntities.findResults {
      usesSystemConnectionSource(it) ? it.javaClass : null
    } as Class<?>[]
  }

  protected void createSystemSchema() {
    Sql sql = null
    try {

      // Cast the event publisher so we can get at the application context below.
      // We do this because the internal property has not yet been set when this method is invoked.
      ConfigurableApplicationContextEventPublisher ep = (ConfigurableApplicationContextEventPublisher) this.eventPublisher

      final String schema_name = SystemDataService.getSystemDatasourceSchemaName()
      final DataSource ds = getDataSource()

      sql = new Sql(ds)
      sql.withTransaction {
        log.debug("Execute -- create schema (if not exists) ${schema_name}")
        sql.execute("CREATE SCHEMA IF NOT EXISTS ${schema_name}" as String)
      }

      // Run the migrations for the system datastore.
      final ExtendedGrailsLiquibase gl = new ExtendedGrailsLiquibase(ep.applicationContext)
      gl.dataSource = ds
      gl.dropFirst = false
      gl.changeLog = '_system.datasource.groovy'
      gl.contexts = []
      gl.labels = []
      gl.defaultSchema = schema_name
      gl.databaseChangeLogTableName = 'system_changelog'
      gl.databaseChangeLogLockTableName = 'system_changelog_lock'
      gl.afterPropertiesSet()
    } catch (Exception ex) {

      log.error("Error creating system database", ex)

      // Throw a runtime exception to prevent spring from retrying here.
      throw new RuntimeException("Migrations for system datasource", ex)
    } finally {
      sql?.close()
    }
  }

  protected void addSystemDatastore() {
    Class<?>[] systemArtefacts = this.findSystemDomainArtefacts()
    if (systemArtefacts.length) {

      log.info 'Found system classes. Intialising a datasource for it'

      this.getConnectionSources().addConnectionSource(SystemDataService.DATASOURCE_SYSTEM, this.getSystemSourceSettings())

      //      final HibernateDatastore parent = this;
      //
      //      final HibernateConnectionSourceFactory systemConnectionFactory = new HibernateConnectionSourceFactory(systemArtefacts)
      //      final PropertyResolver settings = this.getSystemSourceSettings()
      //      final HibernateConnectionSource defaultConnectionSource = (HibernateConnectionSource) systemConnectionFactory.create(ConnectionSource.DEFAULT, settings)
      //      final SingletonConnectionSources<SessionFactory, HibernateConnectionSourceSettings> systemConnectionSources = new SingletonConnectionSources<>(defaultConnectionSource, settings)
      //
      //      HibernateDatastore childDatastore = new HibernateDatastore(systemConnectionSources, systemConnectionFactory.getMappingContext(), this.eventPublisher) {
      //            //          @Override
      //            //          protected HibernateGormEnhancer initialize() {
      //            //            return null;
      //            //          }
      //            @Override
      //            public HibernateDatastore getDatastoreForConnection(final String connectionName) {
      //              // The default connection for this source is the system one.
      //              if (connectionName.equals(SystemDataService.DATASOURCE_SYSTEM) || connectionName.equals(ConnectionSource.DEFAULT)) {
      //                return this;
      //              }
      //
      //              return  parent.getDatastoreForConnection(connectionName)
      //            }
      //          };
      //      datastoresByConnectionSource.put(SystemDataService.DATASOURCE_SYSTEM, childDatastore);
    }
  }

  //  private void newAddDS () {
  //    SingletonConnectionSources<SessionFactory, HibernateConnectionSourceSettings> singletonConnectionSources = new SingletonConnectionSources<>(connectionSource, connectionSources.getBaseConfiguration());
  //    HibernateDatastore childDatastore = createChildDatastore(mappingContext, eventPublisher, parent, singletonConnectionSources);
  //    datastoresByConnectionSource.put(connectionSource.getName(), childDatastore);
  //    registerAllEntitiesWithEnhancer();
  //  }

  @Override
  protected void registerAllEntitiesWithEnhancer() {
    if (!gormEnhancer) return

      super.registerAllEntitiesWithEnhancer()
  }

  public boolean hasConnectionForSchema( String schemaName ) {
    if (schemaName.equals(Settings.SETTING_DATASOURCE) || schemaName.equals(ConnectionSource.DEFAULT)) return true

    // Else check for key
    this.datastoresByConnectionSource.containsKey(schemaName)
  }

  private static final Set<String> configuredTenantNames = ConcurrentHashMap.newKeySet()
  public Collection<String> allConfiguredTenantConnectionSourceNames() {
    configuredTenantNames
  } 
  

  private static final Set<String> ignoreConnectionsNamed = [
    Settings.SETTING_DATASOURCE, ConnectionSource.DEFAULT, SystemDataService.DATASOURCE_SYSTEM] as Set

  protected Collection<String> allTenantConnectionSourceNames() {
    // Else check for key
    this.datastoresByConnectionSource.keySet().findAll {
      !ignoreConnectionsNamed.contains( it )
    }
  }

  public boolean removeTenantForSchema(String schemaName) {

    if (!hasConnectionForSchema( schemaName )) {
      log.info( "No connection source defined for ${schemaName}, NOOP"  )
      return false
    }

    // Grab the datastore.
    final HibernateDatastore childDatastore = getDatastoreForConnection(schemaName)

    // Because the mappings are shared across all tenants, we should not close
    // the datastore. Instead we should clean up manually here.

    childDatastore.connectionSources.close() // Just close the connection source.

    datastoresByConnectionSource.remove( schemaName )
    configuredTenantNames.remove(schemaName)
    true
  }
  
  @Override
  public void addTenantForSchema(String schemaName) {
    super.addTenantForSchema(schemaName)
    configuredTenantNames.add(schemaName)
  }

  @Override
  protected HibernateGormEnhancer initialize() {

    createSystemSchema()
    addSystemDatastore()
    initializeGormEnhancer()
  }

  // Remove the extenisons added by the HibernateDatastore for returning AllTenantResolver items.
  protected HibernateGormEnhancer initializeGormEnhancer() {    
    final HibernateConnectionSource defaultConnectionSource = (HibernateConnectionSource) getConnectionSources().getDefaultConnectionSource();
    return new HibernateGormEnhancer(this, transactionManager, defaultConnectionSource.getSettings()) {
          @Override
          public List<String> allQualifiers(Datastore datastore, PersistentEntity entity) {
            List<String> allQualifiers = super.allQualifiers(datastore, entity);
            if( MultiTenant.class.isAssignableFrom(entity.getJavaClass()) ) {
              
              // TODO: Maybe remove "system"
              
              // Get the list of datasources by conection
              final Collection<String> currentConnections = 
                ((FolioHibernateDatastore)datastore)?.allTenantConnectionSourceNames()
              
              Iterable<Serializable> tenantIds = ((AllTenantsResolver) tenantResolver).resolveTenantIds();
              for(Serializable id : tenantIds) {
                if (currentConnections.contains(id)) {
                  allQualifiers.add(id.toString())
                }
              }
            }

            return allQualifiers;
          }
        }
  }
}
