package services.k_int.core.liquibase
import liquibase.resource.ClassLoaderResourceAccessor
import org.springframework.core.io.support.ResourcePatternUtils
import java.sql.Connection
import java.util.jar.Attributes
import java.util.jar.Manifest

import org.grails.plugins.databasemigration.liquibase.GrailsLiquibase
import org.springframework.context.ApplicationContext
import org.springframework.core.io.Resource
import org.springframework.core.io.ResourceLoader
import org.springframework.core.io.support.PathMatchingResourcePatternResolver
import grails.plugins.GrailsPlugin
import grails.plugins.GrailsPluginManager
import groovy.transform.CompileStatic
import liquibase.Liquibase
import liquibase.exception.LiquibaseException
import liquibase.integration.spring.SpringLiquibase.SpringResourceOpener
import liquibase.logging.LogService
import liquibase.logging.LogType
import liquibase.resource.ResourceAccessor
import liquibase.util.file.FilenameUtils
import org.grails.plugins.BinaryGrailsPlugin
import org.grails.plugins.databasemigration.PluginConstants

@CompileStatic
class ExtendedGrailsLiquibase extends GrailsLiquibase {

  final PathMatchingResourcePatternResolver resolver
  final ApplicationContext applicationContext

  public ExtendedGrailsLiquibase (final ApplicationContext applicationContext) {
    super(applicationContext)
    this.applicationContext = applicationContext
    resolver = new PathMatchingResourcePatternResolver(applicationContext.classLoader)

    this.resourceLoader = new ResourceLoader() {
          ClassLoader cl = applicationContext.getClassLoader()

          @Override
          public Resource getResource (String location) {
            resolver.getResource(location)
          }

          @Override
          public ClassLoader getClassLoader () {
            this.cl
          }
        }
  }

  protected Liquibase getLiquibase() {
    final Connection c = getDataSource().getConnection()
    final Liquibase liquibase = createLiquibase(c);
    liquibase
  }

  @Override
  protected Liquibase createLiquibase(Connection connection) throws LiquibaseException {
    Liquibase liquibase = createLiquibaseInternal(connection, createResourceAccessor())
    return liquibase
  }

  protected ResourceAccessor createResourceAccessor() {

    final String parentResource = getChangeLog()
    final GrailsPluginManager pm = applicationContext.getBean(GrailsPluginManager.BEAN_NAME, GrailsPluginManager)

    GrailsResourceAccessor resAcc = new GrailsResourceAccessor(parentResource)
    resAcc.addPluginPaths(pm)
    
    resAcc
  }

  private Liquibase createLiquibaseInternal(Connection connection, ResourceAccessor resourceAccessor) throws LiquibaseException {

    if (resourceAccessor == null) return createLiquibase(connection)

    Liquibase liquibase = new Liquibase(getChangeLog(), resourceAccessor, createDatabase
        (connection, null))
    liquibase.setIgnoreClasspathPrefix(isIgnoreClasspathPrefix())
    if (parameters != null) {
      for (Map.Entry<String, String> entry : parameters.entrySet()) {
        liquibase.setChangeLogParameter(entry.getKey(), entry.getValue())
      }
    }
    liquibase.setChangeLogParameter(PluginConstants.DATA_SOURCE_NAME_KEY, dataSourceName)
    if (isDropFirst()) {
      liquibase.dropAll()
    }

    return liquibase
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

  public class GrailsResourceAccessor extends ClassLoaderResourceAccessor {

    private String parentFile;

    public GrailsResourceAccessor(String parentFile) {      
      this.parentFile = parentFile
    }
    
    protected void addPluginPaths( final GrailsPluginManager pm ) {
      for (GrailsPlugin plugin : pm.allPlugins) {
        
        if (plugin instanceof BinaryGrailsPlugin) {
          BinaryGrailsPlugin binaryPlugin = plugin
          
          final Resource descriptorResource = binaryPlugin.binaryDescriptor.resource
          final Resource binaryFolderPath = descriptorResource.createRelative('../')
          if (binaryFolderPath.exists()) {
            addRootPath(binaryFolderPath.getURL())
          }
        }
        
      }
    }

    @Override
    public Set<String> list(String relativeTo, String path, boolean includeFiles, boolean includeDirectories,
        boolean recursive) throws IOException {
      if (path == null) {
        return null;
      }

      //
      // Possible Resources Types
      //

      // Standalone Jar
      // Root Path: jar:file:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT-exec.jar!/BOOT-INF/lib/first-module-1.0.0-SNAPSHOT.jar!/
      // +Resource: jar:file:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT-exec.jar!/BOOT-INF/lib/first-module-1.0.0-SNAPSHOT.jar!/db/changelog/0-initial-schema.xml

      // Standalone War
      // Root Path: jar:file:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT-exec.war!/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar!/
      // +Resource: jar:file:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT-exec.war!/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar!/-db/changelog/0-initial-schema.xml

      // Openned Jar Dependency
      // Root Path: file:/Projects/my-project/first-module/target/classes/
      // +Resource: file:/Projects/my-project/first-module/target/classes/db/changelog/0-initial-schema.xml

      // War Wild-Fly Exploded
      // Root Path: vfs:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar/
      // +Resource: vfs:/Projects/my-project/second-module/target/second-module-1.0.0-SNAPSHOT/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar/db/changelog/0-initial-schema.xml

      // War Wild-Fly Artifact
      // Root Path: vfs:/content/second-module-1.0.0-SNAPSHOT.war/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar/
      // +Resource: vfs:/content/second-module-1.0.0-SNAPSHOT.war/WEB-INF/lib/first-module-1.0.0-SNAPSHOT.jar/db/changelog/0-initial-schema.xml

      Set<String> returnSet = new HashSet<>();
      path = path + (recursive ? "**" : '*'); // All files inside!
      String tempFile = FilenameUtils.concat(FilenameUtils.getFullPath(relativeTo), path);

      Resource[] resources = getResources(adjustClasspath(tempFile));
      for (Resource resource : resources) {
        String resourceStr = resource.getURL().toExternalForm();
        String resourcePath = convertToPath(resourceStr);
        if (resourceStr.endsWith(resourcePath) && !resourceStr.equals(resourcePath)) {
          returnSet.add(resourcePath);
        } else {
          // Closed Jar Dependency
          // Root Path:     file:/.m2/repository/org/liquibase/test/first-module/1.0.0-SNAPSHOT/first-module-1.0.0-SNAPSHOT.jar/
          // +Resource: jar:file:/.m2/repository/org/liquibase/test/first-module/1.0.0-SNAPSHOT/first-module-1.0.0-SNAPSHOT.jar!/db/changelog/0-initial-schema.xml

          String newResourceStr = resource.getURL().getFile(); // Remove "jar:" from begining.
          newResourceStr = newResourceStr.replaceAll("!", "");
          String newResourcePath = convertToPath(newResourceStr);
          if (newResourceStr.endsWith(newResourcePath) && !newResourceStr.equals(newResourcePath)) {
            returnSet.add(newResourcePath);
          } else {
            LogService.getLog(getClass()).warning(
                LogType.LOG, "Not a valid resource entry: " + resourceStr);
          }
        }
      }

      return returnSet;
    }

    @Override
    public Set<InputStream> getResourcesAsStream(String path) throws IOException {
      if (path == null) {
        return null;
      }
      Resource[] resources = getResources(adjustClasspath(path));

      if ((resources == null) || (resources.length == 0)) {
        return null;
      }

      Set<InputStream> returnSet = new HashSet<>();
      for (Resource resource : resources) {
        LogService.getLog(getClass()).debug(LogType.LOG, "Opening "
            + resource.getURL().toExternalForm() + " as " + path);
        URLConnection connection = resource.getURL().openConnection();
        connection.setUseCaches(false);
        returnSet.add(connection.getInputStream());
      }

      return returnSet;
    }

    public Resource getResource(String file) {
      return getResourceLoader().getResource(adjustClasspath(file));
    }

    private String adjustClasspath(String file) {
      if (file == null) {
        return null;
      }
      return (isPrefixPresent(parentFile) && !isPrefixPresent(file)) ? (ResourceLoader.CLASSPATH_URL_PREFIX +
          file) : file;
    }


    private Resource[] getResources(String foundPackage) throws IOException {
      return ResourcePatternUtils.getResourcePatternResolver(getResourceLoader()).getResources(foundPackage);
    }

    private Set<String> getPackagesFromManifest(Resource manifest) throws IOException {
      Set<String> manifestPackages = new HashSet<>();
      if (!manifest.exists()) {
        return manifestPackages;
      }
      InputStream inputStream = null;
      try {
        inputStream = manifest.getInputStream();
        Manifest manifestObj = new Manifest(inputStream);
        Attributes attributes = manifestObj.getAttributes("Liquibase-Package");
        if (attributes == null) {
          return manifestPackages;
        }
        for (Object attr : attributes.values()) {
          String packages = "\\s*,\\s*";
          for (String fullPackage : attr.toString().split(packages)) {
            manifestPackages.add(fullPackage.split("\\.")[0]);
          }
        }
      } finally {
        if (inputStream != null) {
          inputStream.close();
        }
      }
      return manifestPackages;
    }

    public boolean isPrefixPresent(String file) {
      if (file == null) {
        return false;
      }
      return file.startsWith("classpath") || file.startsWith("file:") || file.startsWith("url:");
    }

    @Override
    public ClassLoader toClassLoader() {
      return getResourceLoader().getClassLoader();
    }
  }
}
