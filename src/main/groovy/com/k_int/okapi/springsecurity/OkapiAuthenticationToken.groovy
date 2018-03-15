package com.k_int.okapi.springsecurity

import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.AuthorityUtils
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.userdetails.UserDetails

import grails.plugin.springsecurity.SpringSecurityUtils

class OkapiAuthenticationToken extends AbstractAuthenticationToken {

  final UserDetails principle

  public OkapiAuthenticationToken(String userId) {
    this(userId, AuthorityUtils.NO_AUTHORITIES)
  }

  public OkapiAuthenticationToken(String userId, Collection<? extends GrantedAuthority> authorities) {
    super(authorities)
    
    // Ensure we have at least 1 role to retain compatibility.
    principle = new OkapiUserDetails (userId, authorities ?: [new SimpleGrantedAuthority(SpringSecurityUtils.NO_ROLE)])
  }
  
  @Override
  public Collection<GrantedAuthority> getAuthorities() {
    principle.authorities;
  }

  @Override
  public Object getCredentials() {
    principle.password
  }

  @Override
  public Object getPrincipal() {
    principle
  }
  
  private class OkapiUserDetails implements UserDetails {
    
    // In memory User object as we don't store any details except the id.    
    Collection<? extends GrantedAuthority> authorities
    final String password = 'N/A'
    final boolean accountNonExpired = true
    final boolean accountNonLocked = true
    final boolean credentialsNonExpired = true
    final boolean enabled = true
    
    final String username
    final String id
    
    OkapiUserDetails (String userId, Collection<? extends GrantedAuthority> authorities) {
      this.authorities = authorities
      this.username = userId
      this.id = userId
    }
  }
}
