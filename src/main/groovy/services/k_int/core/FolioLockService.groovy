package services.k_int.core

import static org.springframework.transaction.annotation.Propagation.REQUIRES_NEW

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.locks.ReentrantLock

import javax.annotation.PreDestroy

import grails.events.annotation.Subscriber
import grails.gorm.multitenancy.Tenants
import grails.gorm.transactions.Transactional
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j

@CompileStatic
@Slf4j
public class FolioLockService {
  private final long TIMEOUT_INTERNAL_LOCK = 5L // 5 seconds
  private final long TIMEOUT_NAMED_LOCK = 1L // 5 seconds
  
  @Autowired(required=true)
  FederationLockDataService federationLockDataService
  
  private static final String PREFIX_LOCAL_ONLY_LOCK = '__LOCAL__' 
  
  private final ConcurrentHashMap<String, ReentrantLock> namedLocalLocks = new ConcurrentHashMap<>()
  private final ReentrantLock internalLock = new ReentrantLock(true)
  
  private final boolean tryGettingLocalLock() {
    return internalLock.tryLock() || internalLock.tryLock(TIMEOUT_INTERNAL_LOCK, TimeUnit.SECONDS);
  }
  
  private final ReentrantLock tryLocking(final ReentrantLock lock) {
    if ( lock.tryLock() || lock.tryLock(TIMEOUT_NAMED_LOCK, TimeUnit.SECONDS) ) {
      return lock
    }
    
    return null
  }
  
  private ReentrantLock getNamedLocalLock( final String name ) {
    final boolean lockOwner = tryGettingLocalLock();
    
    try {
      final String key = "${PREFIX_LOCAL_ONLY_LOCK}${name}"
      
      if (lockOwner) {
        // Just grab the value from the hashmap.
        ReentrantLock lock = namedLocalLocks.get(key)
        if (lock == null) {
          lock = new ReentrantLock(  )
          namedLocalLocks.put(key, lock)
        }

        return tryLocking( lock )
      }
      
      return null
    } finally {
      if (lockOwner) internalLock.unlock()
    }
  }
  
  @Transactional(propagation=REQUIRES_NEW)
  private ReentrantLock getNamedFederatedLock ( final String name ) {
    
    final boolean lockOwner = tryGettingLocalLock();
    try {
      
      // First try and get the remote lock
      FederationLock fedLock = federationLockDataService.aquireNamedLock(name)
      
      try {
        if ( fedLock == null ) return null
        
        // Just grab the value from the hashmap.
        ReentrantLock lock = namedLocalLocks.get(name)
        if (lock == null) {
        
          // Create a custom reentrant lock that will attempt to relinquish the remote lock
          // when closed.
          lock = new ReentrantLock(  ) {
            @Override
            public void unlock () {
              
              super.unlock();
              
              // Calling the unlock method above may or may not unlock this lock. Because of the
              // re-entrency it may just decrement the hold count by 1. We should only free in the database
              // if this lock is relinquished fully by the current thread.
              if (!this.isLocked()) {
                federationLockDataService.relinquishNamedLock(name)
              }
            }
          }
          namedLocalLocks.put(name, lock)
        }
        
        lock = tryLocking( lock )
        
        if (lock == null) {
          // ensure we release the database lock.
          federationLockDataService.relinquishNamedLock(name)
        }
        
        return lock
        
      } catch (Throwable t) {
        log.error "Error getting named federated lock", t
        
        // Tidy here if poss.
        if (fedLock != null) {
          federationLockDataService.relinquishNamedLock(name)
        }
        
        return null
      }
      
    } finally {
      if (lockOwner) internalLock.unlock()
    }
  }
  
  public boolean lockAndDo ( final String lockName, Runnable work ) {
    
    ReentrantLock lock = getNamedLocalLock(lockName)
    while (lock == null) {
      // We just block this thread.
      lock = getNamedLocalLock(lockName)
    }
    
    try {
      work.run();
    } finally {
      lock.unlock()
    }
    
    return true
  }
  
  /**
   * Attempts to get a named lock across the federation.
   * 
   * If the timeout is exceeded before a lock can be acquired, a TimeoutException is thrown. The
   * retry time is a minimum of 1000 milli seconds so actual times can be + 1 second.
   * 
   * @param lockName Lock name
   * @param work Runnable work unit to do once the lock has been obtained.
   * @param timeoutMillis Timeout in milliseconds
   * @throws TimeoutException
   */
  @Transactional
  public void federatedLockAndDoWithTimeout( final String lockName, long timeoutMillis, final Runnable work) throws TimeoutException {
    ReentrantLock lock = getNamedFederatedLock(lockName)
    final long start = System.currentTimeMillis()
    while (lock == null) {
      
      if (System.currentTimeMillis() - start > timeoutMillis) {
        throw new TimeoutException("Exhausted the timeout waiting for a federation lock.")
      }
      
      log.debug "Waiting for 1 second before retry..."
      sleep(1000)
      // We just block this thread.
      lock = getNamedFederatedLock(lockName)
    }
    
    try {
      work.run();
    } finally {
      lock.unlock()
    }
  }
  
  /**
   * Block this thread indefinitely until the lock is obtained.
   * WARNING: Use this sparingly and be sure you know what you are doing.
   * 
   * @param lockName Lock name
   * @param work Runnable work unit to do once the lock has been obtained.
   */
  @Transactional
  public void federatedLockAndDo ( final String lockName, Runnable work ) {
    ReentrantLock lock = getNamedFederatedLock(lockName)
    while (lock == null) {
      
      log.debug "Waiting for 1 second before retry..."
      sleep(1000)
      
      // We just block this thread.
      lock = getNamedFederatedLock(lockName)
    }
    
    try {
      work.run();
    } finally {
      lock.unlock()
    }
  }
  
  /**
   * Attempts to get a named lock across the federation.
   * 
   * If the timeout is exceeded before a lock can be acquired the work is not carried out
   * and a boolean false is returned from this method.
   * 
   * @param lockName Lock name
   * @param work Runnable work unit to do once the lock has been obtained.
   * @return TRUE if the lock was acquired and the work completed, FALSE otherwise
   */
  @Transactional
  public boolean federatedLockAndDoWithTimeoutOrSkip( final String lockName, long timeoutMillis, final Runnable work) {
    ReentrantLock lock = getNamedFederatedLock(lockName)
    final long start = System.currentTimeMillis()
    while (lock == null) {
      
      if (System.currentTimeMillis() - start > timeoutMillis) {
        return false
      }
      
      log.debug "Waiting for 1 second before retry..."
      sleep(1000)
      // We just block this thread.
      lock = getNamedFederatedLock(lockName)
    }
    
    try {
      work.run()
      return true
    } catch( Throwable t) {
      return false
    } finally {
      lock.unlock()
    }
  }
  
  @Transactional
  public void waitForNoFederatedLock( final String lockName ) {
    boolean exists = federationLockDataService.namedLockExists( lockName )
    while (exists) {
      log.debug "Waiting for 1 second before retry..."
      sleep(1000)
      // We just block this thread.
      exists = federationLockDataService.namedLockExists( lockName )
    }
  }
  
  
  /**
   * @param lockName Lock name
   * @param timeoutMillis Timeout in milliseconds
   * @return TRUE if the lock is eventually relinquished, FALSE otherwise
   */
  @Transactional
  public boolean waitMaxForNoFederatedLock( final String lockName, long timeoutMillis) {
    boolean exists = federationLockDataService.namedLockExists( lockName )
    final long start = System.currentTimeMillis()
    while (exists) {
      if (System.currentTimeMillis() - start > timeoutMillis) {
        return false
      }
      
      log.debug "Waiting for 1 second before retry..."
      sleep(1000)
      // We just block this thread.
      exists = federationLockDataService.namedLockExists( lockName )
    }
    
    // If we get here then the lock was relinquished
    return true
  }
  
  @PreDestroy
  private void destroy() {
    federationLockDataService.relinquishAllLocks()
  }
  
  
  @Transactional
  @Subscriber('federation:cleanup:instance')
  public void cleanupInstance( final String id ) {
    log.info "Cleanup locks owned by removed instance"
    Tenants.withId( SystemDataService.DATASOURCE_SYSTEM ) {
      federationLockDataService.removeNamedLocksForOwner(id)
    }
  }
  
}
