databaseChangeLog = {
  changeSet(author: "sosguthorpe", id: "2022-07-ten01") {
    createTable(tableName: "known_tenant") {
      column(name: "at_id", type: "VARCHAR(36)") {
        constraints(nullable: "false")
      }
      column(name: "at_name", type: "VARCHAR(255)") {
        constraints(nullable: "false")
      }
      column(name: "at_family", type: "VARCHAR(255)") {
        constraints(nullable: "false")
      }
      column(name: "at_active", type: "BOOLEAN") {
        constraints(nullable: "false")
      }
    }
    addPrimaryKey(columnNames: "at_id", constraintName: "known_tenantPK", tableName: "known_tenant")
    addUniqueConstraint(columnNames: "at_name", constraintName: "known_tenant_unique_name", tableName: "known_tenant")
    createIndex(indexName: "at_name_idx", tableName: "known_tenant") {
      column(name: "at_name")
    }
    createIndex(indexName: "at_family_idx", tableName: "known_tenant") {
      column(name: "at_family")
    }
  }
}
