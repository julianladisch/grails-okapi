# Changelog

## Version 5.0.0-beta.2

### Fixes
* [General]
	* Prevent exception when app is killed.
	* Allow includes from grails pluggins

## Version 5.0.0-beta.1

### Additions
* [General]
	* **BREAKING** -  Federation management

### Changes
* [Chore]
	* Changelog - Generate the changelog
	* Upgrade gradle plugin
	* Turn down logging (debug to trace) in OkapiTenantAdminService so that performSchemaCheck doesn't by default log 2 lines on every http request

## Version 4.1.2

### Changes
* [Chore]
	* Build - Released version of TK.
	* build - MavenCentral instead of JCenter
	* dependencies - V6 of TK is supported.

## Version 4.1.1

### Fixes
* [Okapi Client]
	* Use okapi headers event in asynchronous calls

## Version 4.1.0

### Additions
* [Okapi Client]
	* multiple interface methods

## Version 4.0.2

### Changes
* [Chore]
	* Update the TK dep
	* Remove debug using System.out

## Version 4.0.1

### Fixes
* [General]
	* Use the basic MDC implementation to ensure it is fully cleaned

## Version 4.0.0

### Additions
* [General]
	* Use centralized MDC wrapper.

## Version 4.0.0-rc.1

### Additions
* [Dependencies]
	* **BREAKING** -  Make sure we declare dependencies

### Changes
* [Grails]
	* **BREAKING** -  Upgrade to Grails 4
* [Build]
	* Gradle - Ensure plugin version set in xml.
	* Eclipse - Exclude eclipse files
	* Incorrect group.
* [Upgrade]
	* Remove mavenLocal and use rc of toolkit-ce.
	* Grails - Downgrade liquibase. Breaking change in minor version
	* Need to ensure transaction.

### Fixes
* [General]
	* Specify the class-loader from the application context.