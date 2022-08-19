databaseChangeLog = {
  changeSet(author: "sosguthorpe", id: "2022-07-fed01") {
    createTable(tableName: "app_instance") {
      column(name: "ai_id", type: "VARCHAR(36)") {
        constraints(nullable: "false")
      }
      column(name: "ai_family", type: "VARCHAR(255)") {
        constraints(nullable: "false")
      }
      column(name: "ai_role", type: "VARCHAR(255)") {
        constraints(nullable: "false")
      }
      column(name: "ai_last_pulse", type: "TIMESTAMP WITHOUT TIME ZONE") {
        constraints(nullable: "false")
      }
    }

    addPrimaryKey(columnNames: "ai_id", constraintName: "app_instancePK", tableName: "app_instance")
    createIndex(indexName: "ai_family_idx", tableName: "app_instance") {
      column(name: "ai_family")
    }
    createIndex(indexName: "ai_role_idx", tableName: "app_instance") {
      column(name: "ai_role")
    }
  }
  
  changeSet(author: "sosguthorpe", id: "2022-07-fed02") {

    createTable(tableName: "federation_lock") {
      column(name: "fl_id", type: "VARCHAR(36)") {
        constraints(nullable: "false")
      }
      column(name: "fl_name", type: "VARCHAR(255)") {
        constraints(nullable: "false")
      }
      column(name: "fl_family", type: "VARCHAR(255)") {
        constraints(nullable: "false")
      }
      column(name: "fl_owner", type: "VARCHAR(36)") {
        constraints(nullable: "false")
      }
    }

    addPrimaryKey(columnNames: "fl_id", constraintName: "federation_lockPK", tableName: "federation_lock")
    createIndex(indexName: "fl_family_idx", tableName: "federation_lock") {
      column(name: "fl_family")
    }
    createIndex(indexName: "fl_name_idx", tableName: "federation_lock") {
      column(name: "fl_name")
    }
    createIndex(indexName: "fl_owner_idx", tableName: "federation_lock") {
      column(name: "fl_owner")
    }
  }
}

