/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.plugin.oracle;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import io.airlift.log.Logger;
import io.prestosql.plugin.jdbc.BaseJdbcClient;
import io.prestosql.plugin.jdbc.BaseJdbcConfig;
import io.prestosql.plugin.jdbc.ColumnMapping;
import io.prestosql.plugin.jdbc.ConnectionFactory;
import io.prestosql.plugin.jdbc.JdbcColumnHandle;
import io.prestosql.plugin.jdbc.JdbcIdentity;
import io.prestosql.plugin.jdbc.JdbcTableHandle;
import io.prestosql.plugin.jdbc.JdbcTypeHandle;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.StandardErrorCode;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.connector.TableNotFoundException;
import io.prestosql.spi.type.VarcharType;

import javax.inject.Inject;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static io.prestosql.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.prestosql.plugin.jdbc.StandardColumnMappings.timestampColumnMapping;
import static io.prestosql.plugin.jdbc.StandardColumnMappings.varcharColumnMapping;
import static io.prestosql.spi.type.VarcharType.createUnboundedVarcharType;
import static io.prestosql.spi.type.VarcharType.createVarcharType;
import static java.lang.String.format;
import static java.sql.DatabaseMetaData.columnNoNulls;

/**
 * OracleClient is where the actual connection to Oracle is built
 * DriverConnectionFactory is what does the work of actually connecting and applying JdbcOptions
 */
public class OracleClient
        extends BaseJdbcClient
{
    private static final Logger LOG = Logger.get(OracleClient.class);
    private static final String META_DB_NAME_FIELD = "TABLE_SCHEM";
    private static final String QUERY_SCHEMA_SYNS = format("SELECT distinct(OWNER) AS %s FROM SYS.ALL_SYNONYMS", META_DB_NAME_FIELD);
    //private static final String QUERY_TABLE_SYNS = "SELECT TABLE_OWNER, TABLE_NAME FROM SYS.ALL_SYNONYMS WHERE OWNER = ? AND SYNONYM_NAME = ?;";
    private OracleConfig oracleConfig;

    @Inject
    public OracleClient(BaseJdbcConfig config, OracleConfig oracleConfig, ConnectionFactory connectionFactory)
    {
        super(config, "\"", connectionFactory);
        this.oracleConfig = oracleConfig;
    }

    protected static ResultSet getColumns(JdbcTableHandle tableHandle, DatabaseMetaData metadata)
            throws SQLException
    {
        return metadata.getColumns(
                tableHandle.getCatalogName(),
                escapeNamePattern(Optional.ofNullable(tableHandle.getSchemaName()), metadata.getSearchStringEscape()).orElse(null),
                escapeNamePattern(Optional.ofNullable(tableHandle.getTableName()), metadata.getSearchStringEscape()).orElse(null),
                null);
    }

    @Override
    /**
     * SELECT distinct(owner) AS DATABASE_SCHEM FROM SYS.ALL_SYNONYMS;
     * SCHEMA synonyms must be included in the Schema List, ALL_SYNONYMS are any synonym visible by the current user.
     */
    protected Collection<String> listSchemas(Connection connection)
    {
        ImmutableSet.Builder<String> schemaNames = ImmutableSet.builder();
        try (ResultSet resultSet = connection.getMetaData().getSchemas()) {
            while (resultSet.next()) {
                // Schema Names are in "TABLE_SCHEM" for Oracle
                String schemaName = resultSet.getString(META_DB_NAME_FIELD);
                if (schemaName == null) {
                    LOG.error("connection.getMetaData().getSchemas() returned null schema name");
                    continue;
                }
                // skip internal schemas
                if (schemaName.equalsIgnoreCase("information_schema")) {
                    continue;
                }

                schemaNames.add(schemaName);
            }
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }

        // Merge schema synonyms with all schema names.
        if (oracleConfig.isSynonymsEnabled()) {
            try {
                schemaNames.addAll(listSchemaSynonyms(connection));
            }
            catch (PrestoException ex2) {
                LOG.error(ex2);
            }
        }
        return schemaNames.build();
    }

    private Collection<String> listSchemaSynonyms(Connection connection)
    {
        ImmutableSet.Builder<String> schemaSynonyms = ImmutableSet.builder();
        try {
            Statement stmt = connection.createStatement();
            ResultSet resultSet = stmt.executeQuery(QUERY_SCHEMA_SYNS);
            while (resultSet.next()) {
                String schemaSynonym = resultSet.getString(META_DB_NAME_FIELD);
                schemaSynonyms.add(schemaSynonym);
            }
        }
        catch (SQLException e) {
            throw new PrestoException(
                    JDBC_ERROR, format("Failed retrieving schema synonyms, query was: %s", QUERY_SCHEMA_SYNS));
        }

        return schemaSynonyms.build();
    }

    @Override
    /**
     * Retrieve information about tables/views using the JDBC Drivers DatabaseMetaData api,
     * Include "SYNONYM" - functionality specific to Oracle
     */
    protected ResultSet getTables(Connection connection, Optional<String> schemaName, Optional<String> tableName)
            throws SQLException
    {
        // Exactly like the parent class, except we include "SYNONYM" - specific to Oracle
        DatabaseMetaData metadata = connection.getMetaData();
        return metadata.getTables(
                connection.getCatalog(),
                escapeNamePattern(schemaName, metadata.getSearchStringEscape()).orElse(null),
                escapeNamePattern(tableName, metadata.getSearchStringEscape()).orElse(null),
                new String[] {"TABLE", "VIEW", "SYNONYM", "GLOBAL TEMPORARY", "LOCAL TEMPORARY"});
    }

    protected String getTableSchemaName(ResultSet resultSet)
            throws SQLException
    {
        return resultSet.getString(META_DB_NAME_FIELD);
    }

    @Override
    public List<JdbcColumnHandle> getColumns(ConnectorSession session, JdbcTableHandle tableHandle)
    {
        try (Connection connection = connectionFactory.openConnection(JdbcIdentity.from(session))) {
            if (oracleConfig.isSynonymsEnabled()) {
                ((oracle.jdbc.driver.OracleConnection) connection).setIncludeSynonyms(true);
            }
            int allColumns = 0;
            try (ResultSet resultSet = getColumns(tableHandle, connection.getMetaData())) {
                List<JdbcColumnHandle> columns = new ArrayList<>();
                while (resultSet.next()) {
                    allColumns++;
                    String columnName = resultSet.getString("COLUMN_NAME");
                    JdbcTypeHandle typeHandle = new JdbcTypeHandle(
                            resultSet.getInt("DATA_TYPE"),
                            Optional.ofNullable(resultSet.getString("TYPE_NAME")),
                            resultSet.getInt("COLUMN_SIZE"),
                            resultSet.getInt("DECIMAL_DIGITS"),
                            Optional.empty());
                    Optional<ColumnMapping> columnMapping = toPrestoType(session, connection, typeHandle);
                    // skip unsupported column types
                    if (columnMapping.isPresent()) {
                        boolean nullable = (resultSet.getInt("NULLABLE") != columnNoNulls);
                        columns.add(new JdbcColumnHandle(columnName, typeHandle, columnMapping.get().getType(), nullable));
                        /*Optional<String> comment = Optional.ofNullable(resultSet.getString("REMARKS"));
                        columns.add(JdbcColumnHandle.builder()
                                .setColumnName(columnName)
                                .setJdbcTypeHandle(typeHandle)
                                .setColumnType(columnMapping.get().getType())
                                .setNullable(nullable)
                                .setComment(comment)
                                .build());*/
                    }
                }
                if (columns.isEmpty()) {
                    // A table may have no supported columns. In rare cases (e.g. PostgreSQL) a table might have no columns at all.
                    throw new TableNotFoundException(
                            tableHandle.getSchemaTableName(),
                            format("Table '%s' has no supported columns (all %s columns are not supported)", tableHandle.getSchemaTableName(), allColumns));
                }
                return ImmutableList.copyOf(columns);
            }
        }
        catch (SQLException e) {
            throw new PrestoException(JDBC_ERROR, e);
        }
    }

    /**
     * Custom implementation of type-handling for reading column data from Oracle.
     * Deals with NUMERIC types intelligently to avoid overflows, and handles Oracle special cases.
     *
     * @param session
     * @param connection
     * @param typeHandle
     * @return
     */
    @Override
    public Optional<ColumnMapping> toPrestoType(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        String error = "";
        OracleJdbcTypeHandle orcTypeHandle = new OracleJdbcTypeHandle(typeHandle);
        int columnSize = orcTypeHandle.getColumnSize();

        // -- Handle JDBC to Presto Type Mappings -------------------------------------------------
        Optional<ColumnMapping> readType = Optional.empty();
        switch (typeHandle.getJdbcType()) {
            case Types.LONGVARCHAR:
                if (columnSize > VarcharType.MAX_LENGTH || columnSize == 0) {
                    readType = Optional.of(varcharColumnMapping(createUnboundedVarcharType()));
                }
                else {
                    readType = Optional.of(varcharColumnMapping(createVarcharType(columnSize)));
                }
                break;
            case Types.DATE:
                // Oracle DATE values may store hours, minutes, and seconds, so they are mapped to TIMESTAMP in Presto.
                // treat oracle DATE as TIMESTAMP (java.sql.Timestamp)
                readType = Optional.of(timestampColumnMapping(session));
                break;
            case Types.DECIMAL:
            case Types.NUMERIC:
                try {
                    OracleNumberHandling numberHandling = new OracleNumberHandling(orcTypeHandle, this.oracleConfig);
                    readType = Optional.of(numberHandling.getColumnMapping());
                }
                catch (IgnoreFieldException ex) {
                    return Optional.empty(); // skip field
                }
                catch (PrestoException ex) {
                    error = ex.toString();
                }
                break;
            default:
                readType = super.toPrestoType(session, connection, typeHandle);
        }

        if (!readType.isPresent()) {
            String msg = format("unsupported type %s - %s", orcTypeHandle.getDescription(), error);
            switch (oracleConfig.getUnsupportedTypeStrategy()) {
                case VARCHAR:
                    readType = Optional.of(varcharColumnMapping(createUnboundedVarcharType()));
                    break;
                case IGNORE:
                    break;
                case FAIL:
                    throw new PrestoException(StandardErrorCode.GENERIC_INTERNAL_ERROR,
                            msg + " - 'unsupported-type.handling-strategy' = FAIL");
            }
        }
        return readType;
    }
}
