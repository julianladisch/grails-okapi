package com.k_int.okapi;

/**
 *
 * @See: https://objectcomputing.com/resources/publications/sett/september-2015-grails-3-interceptors
 */
public class OkapiInterceptor {

  int order = HIGHEST_PRECEDENCE + 100

  public OkapiInterceptor() {
    matchAll();
  }

  boolean before() {
    // See if this request has an X-OKAPI-TENANT header
    // If so, see if we have a hibernateDatastore for that tenant yet
    true
  }

}
