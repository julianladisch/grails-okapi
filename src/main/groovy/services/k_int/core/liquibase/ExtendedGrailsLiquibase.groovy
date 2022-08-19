package services.k_int.core.liquibase

import java.sql.Connection

import org.grails.plugins.databasemigration.liquibase.GrailsLiquibase
import org.springframework.context.ApplicationContext

import groovy.transform.CompileStatic
import liquibase.Liquibase

@CompileStatic
class ExtendedGrailsLiquibase extends GrailsLiquibase {

  public ExtendedGrailsLiquibase (ApplicationContext applicationContext) {
    super(applicationContext)
  }
  
  protected Liquibase getLiquibase() {
    final Connection c = getDataSource().getConnection()
    final Liquibase liquibase = createLiquibase(c);
    liquibase
  }
  
  protected List<String> findChangesetsInDatabaseNotInLog (Liquibase liquibase = null) {
    
    boolean manage = false
    if (liquibase == null) {
      // Create here and manage.
      liquibase = getLiquibase()
      manage = true
    }
    
    try {
      List<String> cl = liquibase.listUnexpectedChangeSets(null, null).collect { it.id }
      return cl
    } finally {
      if (manage) {
        liquibase.close()
      }
    }
  }
  
  protected List<String> findChangesetsInLogNotInDatabase (Liquibase liquibase = null) {
    
    boolean manage = false
    if (liquibase == null) {
      // Create here and manage.
      liquibase = getLiquibase()
      manage = true
    }
    
    try {
      List<String> cl = liquibase.listUnrunChangeSets(null, null).collect { it.id }
      return cl
    } finally {
      if (manage) {
        liquibase.close()
      }
    }
  }
  
  public boolean logAndDatabaseMatch() {
    
    final Liquibase liquibase = getLiquibase()
    try {
      final List<String> discrepencies =
        findChangesetsInLogNotInDatabase( liquibase ) + findChangesetsInDatabaseNotInLog( liquibase )
        
      return discrepencies.empty
    } finally {
      liquibase.close()
    }
  }
  
  public boolean logAndDatabaseMatchAndIsTagged( final String tag ) {
    
    final Liquibase liquibase = getLiquibase()
    try {
      // Do the quick op first to save time/resource
      if (!internalTagExists( liquibase,  tag )) return false
      
      final List<String> discrepencies =
        findChangesetsInLogNotInDatabase( liquibase ) + findChangesetsInDatabaseNotInLog( liquibase )
        
      return discrepencies.empty
    } finally {
      liquibase.close()
    }
  }
  
//  public boolean logAheadOfDatabase() {
//    !findChangesetsInLogNotInDatabase().empty
//  }
  
  public boolean logAndDatabaseMatchThenEnforceTag( final String tag ) {
    final Liquibase liquibase = getLiquibase()
    try {
      
      final List<String> discrepencies = findChangesetsInLogNotInDatabase( liquibase ) + findChangesetsInDatabaseNotInLog( liquibase )
      
      // The real determiner here is if the database and log are in sync.
      // If it is we should return true. We should also check if the tag exists though.
      final boolean isValid = discrepencies.empty
      
      // Check tag. Not necessarily last ChangeSet though.
      if (isValid && !internalTagExists( liquibase,  tag )) {
        log.warning( "Database and migrations log in sync, but not tagged as valid for ${tag}. Assume valid but the db predates the tagging, so adding the tag." )
        liquibase.tag(tag)
      }

      return isValid      
    } finally {
      liquibase.close()
    }
  }
  
//  
//  public boolean databaseAheadOfLog() {
//    !findChangesetsInDatabaseNotInLog().empty
//  }
  
  protected boolean internalTagExists( Liquibase liquibase = null, final String tag ) {
    boolean manage = false
    if (liquibase == null) {
      // Create here and manage.
      liquibase = getLiquibase()
      manage = true
    }
    
    try {
      return liquibase.tagExists(tag)
    } finally {
      if (manage) {
        liquibase.close()
      }
    }
  }
  
  public boolean tagExists( final String tag ) {
    internalTagExists( null, tag )
  }
  
  public boolean ensureTag( final String tag ) {
    final Liquibase liquibase = getLiquibase()
    try {
      if (!internalTagExists( liquibase,  tag )) {
        liquibase.tag(tag)
      }
    } finally {
      liquibase.close()
    }

  }
}
