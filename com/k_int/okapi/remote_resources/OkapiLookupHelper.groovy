package com.k_int.okapi.remote_resources;

import com.k_int.okapi.OkapiClient
import com.k_int.web.toolkit.utils.DomainUtils
import grails.core.GrailsApplication
import grails.core.GrailsClass
import grails.gorm.MultiTenant
import grails.util.GrailsClassUtils
import grails.util.Holders
import groovy.transform.CompileStatic
import groovy.util.logging.Slf4j
import java.lang.annotation.Annotation
import java.lang.reflect.Field
import org.apache.commons.lang.reflect.FieldUtils
import org.grails.datastore.mapping.model.PersistentEntity

@Slf4j
@CompileStatic
public class OkapiLookupHelper {
  
  private static OkapiClient okapiClient = null
  private static OkapiClient getOkapiClient() {
    if (!okapiClient) {
      okapiClient = getGrailsApplication().mainContext.getBean('okapiClient') as OkapiClient
    }
    okapiClient
  }
  private static GrailsApplication grailsApplication = null
  private static GrailsApplication setGrailsApplication(GrailsApplication grApp) {
    grailsApplication = grApp
  }
  private static GrailsApplication getGrailsApplication() {
    if (grailsApplication) {
      grailsApplication = Holders.grailsApplication
    }
  }
  
  public static void addMethods (final GrailsClass gc, final GrailsApplication grailsApplication = null) {
    PersistentEntity pe = DomainUtils.resolveDomainClass(gc)
    if (pe) {
      addMethods(pe, grailsApplication)
    }
  }
  
  public static void addMethods (final PersistentEntity pe, final GrailsApplication grailsApplication = null) {
    
    if (grailsApplication) {
      OkapiLookupHelper.grailsApplication = grailsApplication
    }
    
    // Excludes.
    final Set<Class<?>> excludes = [RemoteOkapiLink] as Set

    final Class<?> targetClass = pe.javaClass

    if ( !excludes.find { it.isAssignableFrom(targetClass) } ) {

      // Get each refdataValue type property (or derivative)
      final Map<String, Map<String,?>> fieldTemplates = new HashMap<String, Map<String,?>>()
      Class<?> c = targetClass
      while (c != null) {
        for (Field field : c.getDeclaredFields()) {
          if (field.isAnnotationPresent(OkapiLookup)) {
            // Annotated.
            OkapiLookup fieldAn = field.getAnnotation(OkapiLookup)
            def valueClass = fieldAn.converter()
            if (!valueClass || Closure.isAssignableFrom(valueClass)) {
              
              // We should create the meta-method that fetches the related doc.
              getOkapiClient().decorateWithRemoteObjectFetcher(c, field.name + '_object', fieldAn.value(), valueClass?.newInstance(null, null) as Closure)
            }
          }
        }
        c = c.getSuperclass()
      }
      
      // We should now have a list 
    }
  }

  private static Set<Field> findFields(Class<?> cls, Class<? extends Annotation> ann) {
    Set<Field> set = new HashSet<Field>()
    Class<?> c = cls
    while (c != null) {
      for (Field field : c.getDeclaredFields()) {
        if (field.isAnnotationPresent(ann)) {
          set.add(field);
        }
      }
      c = c.getSuperclass()
    }
    return set
}
}
