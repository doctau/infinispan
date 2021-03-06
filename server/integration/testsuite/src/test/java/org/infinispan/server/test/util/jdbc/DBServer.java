package org.infinispan.server.test.util.jdbc;

import static org.infinispan.server.test.util.ITestUtils.sleepForSecs;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;

import org.infinispan.commons.equivalence.ByteArrayEquivalence;

/**
 * @author <a href="mailto:mgencur@redhat.com">Martin Gencur</a>
 * @author <a href="mailto:vchepeli@redhat.com">Vitalii Chepeliuk</a>
 * @since 7.0
 */
public class DBServer {
    public static final long TIMEOUT = 15000;

    public static DBServer create() {
        return new DBServer();
    }

    private DBServer() {}

    public DBServer(String bucketTableName, String stringTableName, String idColumnName, String dataColumnName) {
        this.connectionUrl = System.getProperty("connection.url");
        this.username = System.getProperty("username");
        this.password = System.getProperty("password");
        this.bucketTableName = bucketTableName;
        this.stringTableName = stringTableName;

        String driver = System.getProperty("driver.class");

        if (bucketTableName != null)
            bucketTable = new DBServer.TableManipulation(driver, connectionUrl, username, password, bucketTableName, idColumnName, dataColumnName);
        if (stringTableName != null)
            stringTable = new DBServer.TableManipulation(driver, connectionUrl, username, password, stringTableName, idColumnName, dataColumnName);
    }

    public String connectionUrl;
    public String username;
    public String password;

    public String bucketTableName;
    public String stringTableName;

    public TableManipulation bucketTable;
    public TableManipulation stringTable;

    public static class TableManipulation {
        private final long RETRY_TIME = 1; // in seconds

        private final SimpleConnectionFactory factory;

        private final String idColumnName;
        private final String dataColumnName;

        private String tableName;

        private final String connectionUrl;
        private final String username;
        private final String password;

        private String identifierQuoteString;

        private final String getRowByKeySql;
        private final String getAllRowsSql;
        private final String deleteAllRowsSql;
        private final String dropTableSql;

        TableManipulation(String driverClass, String connectionUrl, String username, String password, String tableName,
                                 String idColumnName, String dataColumnName) {
            this.idColumnName = idColumnName;
            this.dataColumnName = dataColumnName;
            this.tableName = tableName;
            this.connectionUrl = connectionUrl;
            this.username = username;
            this.password = password;
            // inappropriate table name characters filter: https://github.com/infinispan/infinispan/pull/1610
            this.tableName = getIdentifierQuoteString() + this.tableName.replaceAll("[^\\p{Alnum}]", "_") + getIdentifierQuoteString();
            if (connectionUrl.contains("sybase")) {
                this.getRowByKeySql = "SELECT " + idColumnName + ", " + dataColumnName + " FROM " + this.tableName + " WHERE " + idColumnName + " = convert(VARCHAR(255),?)";
            } else if (connectionUrl.contains("postgre") || connectionUrl.contains("edb")) {
                this.getRowByKeySql = "SELECT " + idColumnName + ", " + dataColumnName + " FROM " + this.tableName + " WHERE " + idColumnName + " = cast(? as VARCHAR(255))";
            } else {
                this.getRowByKeySql = "SELECT " + idColumnName + ", " + dataColumnName + " FROM " + this.tableName + " WHERE " + idColumnName + " = ?";
            }


            this.getAllRowsSql = "SELECT " + dataColumnName + "," + idColumnName + " FROM " + this.tableName;
            this.deleteAllRowsSql = "DELETE from " + this.tableName;
            this.dropTableSql = "DROP TABLE " + this.tableName;

            factory = new SimpleConnectionFactory(connectionUrl, username, password);
            factory.start(driverClass);
        }

        public TableManipulation(String driverClass, DBServer DBServer, String tableName, String idColumnName, String dataColumnName) {
            this(driverClass, DBServer.connectionUrl, DBServer.username, DBServer.password, tableName, idColumnName, dataColumnName);
        }

        private String getIdentifierQuoteString() {
            if (identifierQuoteString == null) {
                if (connectionUrl.contains("mysql"))
                    identifierQuoteString = "`";
                else
                    identifierQuoteString = "\"";
            }
            return identifierQuoteString;
        }

        public Object getValueByKeyAwait(String key) throws Exception {
            final Connection connection = factory.getConnection();
            final PreparedStatement ps = connection.prepareStatement(getRowByKeySql);
            ps.setString(1, key);
            Object toReturn = null;
            try {
                toReturn = withAwait(() -> {
                    ResultSet rs;
                    rs = ps.executeQuery();
                    Object result = null;
                    if (rs.next()) {
                        result = rs.getObject(dataColumnName); //start from 1, not 0
                    }
                    return result;
                });
            } finally {
                factory.releaseConnection(connection);
            }
            return toReturn;
        }

        public Object getValueByKey(String key) throws Exception {
            Connection connection = factory.getConnection();
            Object result = null;
            try {
                PreparedStatement ps = connection.prepareStatement(getRowByKeySql);
                ps.setString(1, key);
                ResultSet rs = ps.executeQuery();
                if (rs.next()) {
                    result = rs.getObject(dataColumnName); //start from 1, not 0
                }
            } finally {
                factory.releaseConnection(connection);
            }
            return result;
        }

        public List<String> getAllRows() throws Exception {
            Connection connection = factory.getConnection();
            final Statement s = connection.createStatement();
            ResultSet rs;
            List<String> rows = new ArrayList<String>();
            try {
                rs = s.executeQuery(getAllRowsSql);
                while (rs.next()) {
                    rows.add(rs.toString());
                }
            } finally {
                factory.releaseConnection(connection);
            }
            return rows;
        }

        public List<String> getAllKeys() throws Exception {
            Connection connection = factory.getConnection();
            Statement s = connection.createStatement();
            List<String> keys = new ArrayList<String>();
            try {
                ResultSet rs = s.executeQuery(getAllRowsSql);
                while (rs.next()) {
                    keys.add(rs.getObject(idColumnName).toString());
                }
            } finally {
                factory.releaseConnection(connection);
            }
            return keys;
        }

        public void deleteAllRows() throws Exception {
            Connection connection = factory.getConnection();
            final Statement s = connection.createStatement();
            try {
                s.executeUpdate(deleteAllRowsSql);
            } finally {
                factory.releaseConnection(connection);
            }
        }

        private List<String> getTableNames() throws Exception {
            List<String> tables = new ArrayList<String>();
            Connection connection = factory.getConnection();
            try {
                DatabaseMetaData dbm = connection.getMetaData();
                String[] types = {"TABLE"};
                ResultSet rs = dbm.getTables(null, null, "%", types);
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    tables.add(table);
                }
            } finally {
                factory.releaseConnection(connection);
            }
            return tables;
        }

        public void dropTable() throws Exception {
            Connection connection = factory.getConnection();
            final Statement s = connection.createStatement();
            try {
                s.executeUpdate(dropTableSql);
            } finally {
                factory.releaseConnection(connection);
            }
        }

        public boolean exists() throws Exception {
            List<String> tables = getTableNames();
            return tables.contains(tableName.substring(1, tableName.length() - 1));
        }

        public String getConnectionUrl() {
            return connectionUrl;
        }

        public String getUsername() {
            return username;
        }

        public String getPassword() {
            return password;
        }

        private <T> T withAwait(Callable<T> c) {
            T result = null;
            final long timeout = System.currentTimeMillis() + TIMEOUT;
            while (result == null && System.currentTimeMillis() < timeout) {
                try {
                    result = c.call();
                } catch (Exception e) {
                    sleepForSecs(RETRY_TIME);
                }
            }
            return result;
        }
    }
}
