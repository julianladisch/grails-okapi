package services.k_int.core

import static org.springframework.transaction.annotation.Propagation.MANDATORY

import grails.gorm.multitenancy.Tenant
import grails.gorm.services.Service
import grails.gorm.transactions.Transactional
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@Slf4j
@CompileStatic
@Service(KnownTenant)
@Tenant({ SystemDataService.DATASOURCE_SYSTEM })
abstract class KnownTenantDataService {
  
  @Transactional(propagation=MANDATORY)
  protected abstract String findKnownTenantId( String name, String family, boolean active, Map args =[:] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract KnownTenant findKnownTenantByNameAndFamily( String name, String family, Map args =[:] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract KnownTenant findKnownTenantByName( String name, Map args =[:] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract List<String> listKnownTenantName( String family, boolean active, Map args =[:] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract List<String> listKnownTenantName( String family, Map args =[:]  )
  
  @Transactional(propagation=MANDATORY)
  protected abstract KnownTenant saveKnownTenant ( String name, String family, boolean active, Map args = [flush: true, failOnError: true] )
  
  @Transactional(propagation=MANDATORY)
  protected abstract void deleteKnownTenant ( final Serializable id, final Map args = [flush: true, failOnError: true] )
  
  @Transactional(propagation=MANDATORY)
  public boolean isActiveFor( final Serializable id, final String family ) {
    return findKnownTenantId("${id}", family, true )
  }
  
  @Transactional(propagation=MANDATORY)
  public abstract KnownTenant updateKnownTenant(final Serializable id, final String family, final boolean active, Map args = [flush: true, failOnError: true])
}
