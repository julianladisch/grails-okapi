package com.k_int.okapi.remote_resources


import java.lang.annotation.Documented
import java.lang.annotation.ElementType
import java.lang.annotation.Retention
import java.lang.annotation.RetentionPolicy
import java.lang.annotation.Target

@Target([ElementType.FIELD])
@Retention(RetentionPolicy.RUNTIME)
@interface OkapiLookup {
  String value()
  Class<Closure> converter() default { it }
}