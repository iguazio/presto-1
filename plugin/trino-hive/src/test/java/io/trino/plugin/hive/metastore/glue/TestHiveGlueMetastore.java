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
package io.trino.plugin.hive.metastore.glue;

import com.google.common.collect.ImmutableList;
import io.airlift.concurrent.BoundedExecutor;
import io.airlift.slice.Slice;
import io.trino.plugin.hive.AbstractTestHiveLocal;
import io.trino.plugin.hive.HiveConfig;
import io.trino.plugin.hive.HiveMetastoreClosure;
import io.trino.plugin.hive.HiveTestUtils;
import io.trino.plugin.hive.PartitionStatistics;
import io.trino.plugin.hive.authentication.HiveIdentity;
import io.trino.plugin.hive.metastore.HiveMetastore;
import io.trino.plugin.hive.metastore.MetastoreConfig;
import io.trino.plugin.hive.metastore.PartitionWithStatistics;
import io.trino.plugin.hive.metastore.Table;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ConnectorMetadata;
import io.trino.spi.connector.ConnectorOutputTableHandle;
import io.trino.spi.connector.ConnectorPageSink;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.predicate.Domain;
import io.trino.spi.predicate.Range;
import io.trino.spi.predicate.TupleDomain;
import io.trino.spi.statistics.ColumnStatisticMetadata;
import io.trino.spi.statistics.ComputedStatistics;
import io.trino.spi.statistics.TableStatisticType;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.DateType;
import io.trino.spi.type.IntegerType;
import io.trino.spi.type.SmallintType;
import io.trino.spi.type.TinyintType;
import io.trino.spi.type.VarcharType;
import io.trino.testing.MaterializedResult;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.airlift.concurrent.MoreFutures.getFutureValue;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.plugin.hive.HiveStorageFormat.ORC;
import static io.trino.plugin.hive.HiveStorageFormat.TEXTFILE;
import static io.trino.plugin.hive.HiveTestUtils.HDFS_ENVIRONMENT;
import static io.trino.plugin.hive.metastore.glue.PartitionFilterBuilder.DECIMAL_TYPE;
import static io.trino.plugin.hive.metastore.glue.PartitionFilterBuilder.decimalOf;
import static io.trino.spi.statistics.ColumnStatisticType.MAX_VALUE;
import static io.trino.spi.statistics.ColumnStatisticType.MIN_VALUE;
import static io.trino.spi.statistics.ColumnStatisticType.NUMBER_OF_DISTINCT_VALUES;
import static io.trino.spi.statistics.ColumnStatisticType.NUMBER_OF_NON_NULL_VALUES;
import static io.trino.testing.TestingConnectorSession.SESSION;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.UUID.randomUUID;
import static org.apache.hadoop.hive.common.FileUtils.makePartName;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/*
 * GlueHiveMetastore currently uses AWS Default Credential Provider Chain,
 * See https://docs.aws.amazon.com/sdk-for-java/v1/developer-guide/credentials.html#credentials-default
 * on ways to set your AWS credentials which will be needed to run this test.
 */
@Test(singleThreaded = true)
public class TestHiveGlueMetastore
        extends AbstractTestHiveLocal
{
    private static final HiveIdentity HIVE_CONTEXT = new HiveIdentity(SESSION);
    private static final List<ColumnMetadata> CREATE_TABLE_COLUMNS = ImmutableList.<ColumnMetadata>builder()
            .add(new ColumnMetadata("id", BigintType.BIGINT))
            .build();
    private static final String PARTITION_KEY = "part_key_1";
    private static final String PARTITION_KEY2 = "part_key_2";
    private static final List<ColumnMetadata> CREATE_TABLE_COLUMNS_PARTITIONED_VARCHAR = ImmutableList.<ColumnMetadata>builder()
            .addAll(CREATE_TABLE_COLUMNS)
            .add(new ColumnMetadata(PARTITION_KEY, VarcharType.VARCHAR))
            .build();
    private static final List<ColumnMetadata> CREATE_TABLE_COLUMNS_PARTITIONED_TWO_KEYS = ImmutableList.<ColumnMetadata>builder()
            .addAll(CREATE_TABLE_COLUMNS)
            .add(new ColumnMetadata(PARTITION_KEY, VarcharType.VARCHAR))
            .add(new ColumnMetadata(PARTITION_KEY2, BigintType.BIGINT))
            .build();
    private static final List<ColumnMetadata> CREATE_TABLE_COLUMNS_PARTITIONED_TINYINT = ImmutableList.<ColumnMetadata>builder()
            .addAll(CREATE_TABLE_COLUMNS)
            .add(new ColumnMetadata(PARTITION_KEY, TinyintType.TINYINT))
            .build();
    private static final List<ColumnMetadata> CREATE_TABLE_COLUMNS_PARTITIONED_SMALLINT = ImmutableList.<ColumnMetadata>builder()
            .addAll(CREATE_TABLE_COLUMNS)
            .add(new ColumnMetadata(PARTITION_KEY, SmallintType.SMALLINT))
            .build();
    private static final List<ColumnMetadata> CREATE_TABLE_COLUMNS_PARTITIONED_INTEGER = ImmutableList.<ColumnMetadata>builder()
            .addAll(CREATE_TABLE_COLUMNS)
            .add(new ColumnMetadata(PARTITION_KEY, IntegerType.INTEGER))
            .build();
    private static final List<ColumnMetadata> CREATE_TABLE_COLUMNS_PARTITIONED_BIGINT = ImmutableList.<ColumnMetadata>builder()
            .addAll(CREATE_TABLE_COLUMNS)
            .add(new ColumnMetadata(PARTITION_KEY, BigintType.BIGINT))
            .build();
    private static final List<ColumnMetadata> CREATE_TABLE_COLUMNS_PARTITIONED_DECIMAL = ImmutableList.<ColumnMetadata>builder()
            .addAll(CREATE_TABLE_COLUMNS)
            .add(new ColumnMetadata(PARTITION_KEY, DECIMAL_TYPE))
            .build();
    private static final List<ColumnMetadata> CREATE_TABLE_COLUMNS_PARTITIONED_DATE = ImmutableList.<ColumnMetadata>builder()
            .addAll(CREATE_TABLE_COLUMNS)
            .add(new ColumnMetadata(PARTITION_KEY, DateType.DATE))
            .build();
    private static final List<String> VARCHAR_PARTITION_VALUES = ImmutableList.of("2020-01-01", "2020-02-01", "2020-03-01", "2020-04-01");

    public TestHiveGlueMetastore()
    {
        super("test_glue" + randomUUID().toString().toLowerCase(ENGLISH).replace("-", ""));
    }

    @BeforeClass(alwaysRun = true)
    @Override
    public void initialize()
    {
        super.initialize();
        // uncomment to get extra AWS debug information
//        Logging logging = Logging.initialize();
//        logging.setLevel("com.amazonaws.request", Level.DEBUG);
    }

    @Override
    protected HiveMetastore createMetastore(File tempDir)
    {
        GlueHiveMetastoreConfig glueConfig = new GlueHiveMetastoreConfig();
        glueConfig.setDefaultWarehouseDir(tempDir.toURI().toString());
        glueConfig.setAssumeCanonicalPartitionKeys(true);

        HiveConfig hiveConfig = new HiveConfig();
        hiveConfig.setTableStatisticsEnabled(true);

        Executor executor = new BoundedExecutor(this.executor, 10);
        return new GlueHiveMetastore(
                HDFS_ENVIRONMENT,
                glueConfig,
                hiveConfig,
                executor,
                new DefaultGlueColumnStatisticsProviderFactory(glueConfig, executor, executor),
                Optional.empty(),
                new DefaultGlueMetastoreTableFilterProvider(
                        new MetastoreConfig()
                                .setHideDeltaLakeTables(true)).get());
    }

    @Override
    public void testRenameTable()
    {
        // rename table is not yet supported by Glue
    }

    @Override
    public void testUpdateTableColumnStatisticsEmptyOptionalFields()
            throws Exception
    {
        // this test expect consistency between written and read stats but this is not provided by glue at the moment
        // when writing empty min/max statistics glue will return 0 to the readers
        // in order to avoid incorrect data we skip writes for statistics with min/max = null
    }

    @Override
    public void testUpdatePartitionColumnStatisticsEmptyOptionalFields()
            throws Exception
    {
        // this test expect consistency between written and read stats but this is not provided by glue at the moment
        // when writing empty min/max statistics glue will return 0 to the readers
        // in order to avoid incorrect data we skip writes for statistics with min/max = null
    }

    @Override
    public void testStorePartitionWithStatistics()
            throws Exception
    {
        testStorePartitionWithStatistics(STATISTICS_PARTITIONED_TABLE_COLUMNS, BASIC_STATISTICS_1, BASIC_STATISTICS_2, BASIC_STATISTICS_1, EMPTY_TABLE_STATISTICS);
    }

    @Override
    public void testGetPartitions()
            throws Exception
    {
        try {
            SchemaTableName tableName = temporaryTable("get_partitions");
            createDummyPartitionedTable(tableName, CREATE_TABLE_COLUMNS_PARTITIONED);
            HiveMetastore metastoreClient = getMetastoreClient();
            Optional<List<String>> partitionNames = metastoreClient.getPartitionNamesByFilter(
                    HIVE_CONTEXT,
                    tableName.getSchemaName(),
                    tableName.getTableName(),
                    ImmutableList.of("ds"), TupleDomain.all());
            assertTrue(partitionNames.isPresent());
            assertEquals(partitionNames.get(), ImmutableList.of("ds=2016-01-01", "ds=2016-01-02"));
        }
        finally {
            dropTable(tablePartitionFormat);
        }
    }

    @Test
    public void testGetDatabasesLogsStats()
    {
        GlueHiveMetastore metastore = (GlueHiveMetastore) getMetastoreClient();
        GlueMetastoreStats stats = metastore.getStats();
        double initialCallCount = stats.getGetAllDatabases().getTime().getAllTime().getCount();
        long initialFailureCount = stats.getGetAllDatabases().getTotalFailures().getTotalCount();
        getMetastoreClient().getAllDatabases();
        assertEquals(stats.getGetAllDatabases().getTime().getAllTime().getCount(), initialCallCount + 1.0);
        assertTrue(stats.getGetAllDatabases().getTime().getAllTime().getAvg() > 0.0);
        assertEquals(stats.getGetAllDatabases().getTotalFailures().getTotalCount(), initialFailureCount);
    }

    @Test
    public void testGetDatabaseFailureLogsStats()
    {
        GlueHiveMetastore metastore = (GlueHiveMetastore) getMetastoreClient();
        GlueMetastoreStats stats = metastore.getStats();
        long initialFailureCount = stats.getGetDatabase().getTotalFailures().getTotalCount();
        assertThatThrownBy(() -> getMetastoreClient().getDatabase(null))
                .isInstanceOf(TrinoException.class)
                .hasMessageStartingWith("Database name cannot be equal to null or empty");
        assertEquals(stats.getGetDatabase().getTotalFailures().getTotalCount(), initialFailureCount + 1);
    }

    @Test
    public void testGetPartitionsFilterVarChar()
            throws Exception
    {
        TupleDomain<String> singleEquals = new PartitionFilterBuilder()
                .addStringValues(PARTITION_KEY, "2020-01-01")
                .build();
        TupleDomain<String> greaterThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThan(VarcharType.VARCHAR, utf8Slice("2020-02-01")))
                .build();
        TupleDomain<String> betweenInclusive = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.range(VarcharType.VARCHAR, utf8Slice("2020-02-01"), true, utf8Slice("2020-03-01"), true))
                .build();
        TupleDomain<String> greaterThanOrEquals = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThanOrEqual(VarcharType.VARCHAR, utf8Slice("2020-03-01")))
                .build();
        TupleDomain<String> inClause = new PartitionFilterBuilder()
                .addStringValues(PARTITION_KEY, "2020-01-01", "2020-02-01")
                .build();
        TupleDomain<String> lessThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.lessThan(VarcharType.VARCHAR, utf8Slice("2020-03-01")))
                .build();
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_VARCHAR,
                PARTITION_KEY,
                VARCHAR_PARTITION_VALUES,
                ImmutableList.of(singleEquals, greaterThan, betweenInclusive, greaterThanOrEquals, inClause, lessThan, TupleDomain.all()),
                ImmutableList.of(
                        ImmutableList.of("2020-01-01"),
                        ImmutableList.of("2020-03-01", "2020-04-01"),
                        ImmutableList.of("2020-02-01", "2020-03-01"),
                        ImmutableList.of("2020-03-01", "2020-04-01"),
                        ImmutableList.of("2020-01-01", "2020-02-01"),
                        ImmutableList.of("2020-01-01", "2020-02-01"),
                        ImmutableList.of("2020-01-01", "2020-02-01", "2020-03-01", "2020-04-01")));
    }

    @Test
    public void testGetPartitionsFilterBigInt()
            throws Exception
    {
        TupleDomain<String> singleEquals = new PartitionFilterBuilder()
                .addBigintValues(PARTITION_KEY, 1000L)
                .build();
        TupleDomain<String> greaterThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThan(BigintType.BIGINT, 100L))
                .build();
        TupleDomain<String> betweenInclusive = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.range(BigintType.BIGINT, 100L, true, 1000L, true))
                .build();
        TupleDomain<String> greaterThanOrEquals = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThanOrEqual(BigintType.BIGINT, 100L))
                .build();
        TupleDomain<String> inClause = new PartitionFilterBuilder()
                .addBigintValues(PARTITION_KEY, 1L, 1000000L)
                .build();
        TupleDomain<String> lessThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.lessThan(BigintType.BIGINT, 1000L))
                .build();
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_BIGINT,
                PARTITION_KEY,
                ImmutableList.of("1", "100", "1000", "1000000"),
                ImmutableList.of(singleEquals, greaterThan, betweenInclusive, greaterThanOrEquals, inClause, lessThan, TupleDomain.all()),
                ImmutableList.of(
                        ImmutableList.of("1000"),
                        ImmutableList.of("1000", "1000000"),
                        ImmutableList.of("100", "1000"),
                        ImmutableList.of("100", "1000", "1000000"),
                        ImmutableList.of("1", "1000000"),
                        ImmutableList.of("1", "100"),
                        ImmutableList.of("1", "100", "1000", "1000000")));
    }

    @Test
    public void testGetPartitionsFilterInteger()
            throws Exception
    {
        TupleDomain<String> singleEquals = new PartitionFilterBuilder()
                .addIntegerValues(PARTITION_KEY, 1000L)
                .build();
        TupleDomain<String> greaterThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThan(IntegerType.INTEGER, 100L))
                .build();
        TupleDomain<String> betweenInclusive = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.range(IntegerType.INTEGER, 100L, true, 1000L, true))
                .build();
        TupleDomain<String> greaterThanOrEquals = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThanOrEqual(IntegerType.INTEGER, 100L))
                .build();
        TupleDomain<String> inClause = new PartitionFilterBuilder()
                .addIntegerValues(PARTITION_KEY, 1L, 1000000L)
                .build();
        TupleDomain<String> lessThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.lessThan(IntegerType.INTEGER, 1000L))
                .build();
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_INTEGER,
                PARTITION_KEY,
                ImmutableList.of("1", "100", "1000", "1000000"),
                ImmutableList.of(singleEquals, greaterThan, betweenInclusive, greaterThanOrEquals, inClause, lessThan, TupleDomain.all()),
                ImmutableList.of(
                        ImmutableList.of("1000"),
                        ImmutableList.of("1000", "1000000"),
                        ImmutableList.of("100", "1000"),
                        ImmutableList.of("100", "1000", "1000000"),
                        ImmutableList.of("1", "1000000"),
                        ImmutableList.of("1", "100"),
                        ImmutableList.of("1", "100", "1000", "1000000")));
    }

    @Test
    public void testGetPartitionsFilterSmallInt()
            throws Exception
    {
        TupleDomain<String> singleEquals = new PartitionFilterBuilder()
                .addSmallintValues(PARTITION_KEY, 1000L)
                .build();
        TupleDomain<String> greaterThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThan(SmallintType.SMALLINT, 100L))
                .build();
        TupleDomain<String> betweenInclusive = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.range(SmallintType.SMALLINT, 100L, true, 1000L, true))
                .build();
        TupleDomain<String> greaterThanOrEquals = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThanOrEqual(SmallintType.SMALLINT, 100L))
                .build();
        TupleDomain<String> inClause = new PartitionFilterBuilder()
                .addSmallintValues(PARTITION_KEY, 1L, 10000L)
                .build();
        TupleDomain<String> lessThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.lessThan(SmallintType.SMALLINT, 1000L))
                .build();
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_SMALLINT,
                PARTITION_KEY,
                ImmutableList.of("1", "100", "1000", "10000"),
                ImmutableList.of(singleEquals, greaterThan, betweenInclusive, greaterThanOrEquals, inClause, lessThan, TupleDomain.all()),
                ImmutableList.of(
                        ImmutableList.of("1000"),
                        ImmutableList.of("1000", "10000"),
                        ImmutableList.of("100", "1000"),
                        ImmutableList.of("100", "1000", "10000"),
                        ImmutableList.of("1", "10000"),
                        ImmutableList.of("1", "100"),
                        ImmutableList.of("1", "100", "1000", "10000")));
    }

    @Test
    public void testGetPartitionsFilterTinyInt()
            throws Exception
    {
        TupleDomain<String> singleEquals = new PartitionFilterBuilder()
                .addTinyintValues(PARTITION_KEY, 127L)
                .build();
        TupleDomain<String> greaterThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThan(TinyintType.TINYINT, 10L))
                .build();
        TupleDomain<String> betweenInclusive = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.range(TinyintType.TINYINT, 10L, true, 100L, true))
                .build();
        TupleDomain<String> greaterThanOrEquals = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThanOrEqual(TinyintType.TINYINT, 10L))
                .build();
        TupleDomain<String> inClause = new PartitionFilterBuilder()
                .addTinyintValues(PARTITION_KEY, 1L, 127L)
                .build();
        TupleDomain<String> lessThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.lessThan(TinyintType.TINYINT, 100L))
                .build();
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_TINYINT,
                PARTITION_KEY,
                ImmutableList.of("1", "10", "100", "127"),
                ImmutableList.of(singleEquals, greaterThan, betweenInclusive, greaterThanOrEquals, inClause, lessThan, TupleDomain.all()),
                ImmutableList.of(
                        ImmutableList.of("127"),
                        ImmutableList.of("100", "127"),
                        ImmutableList.of("10", "100"),
                        ImmutableList.of("10", "100", "127"),
                        ImmutableList.of("1", "127"),
                        ImmutableList.of("1", "10"),
                        ImmutableList.of("1", "10", "100", "127")));
    }

    @Test
    public void testGetPartitionsFilterTinyIntNegatives()
            throws Exception
    {
        TupleDomain<String> singleEquals = new PartitionFilterBuilder()
                .addTinyintValues(PARTITION_KEY, -128L)
                .build();
        TupleDomain<String> greaterThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThan(TinyintType.TINYINT, 0L))
                .build();
        TupleDomain<String> betweenInclusive = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.range(TinyintType.TINYINT, 0L, true, 50L, true))
                .build();
        TupleDomain<String> greaterThanOrEquals = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThanOrEqual(TinyintType.TINYINT, 0L))
                .build();
        TupleDomain<String> inClause = new PartitionFilterBuilder()
                .addTinyintValues(PARTITION_KEY, 0L, -128L)
                .build();
        TupleDomain<String> lessThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.lessThan(TinyintType.TINYINT, 0L))
                .build();
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_TINYINT,
                PARTITION_KEY,
                ImmutableList.of("-128", "0", "50", "100"),
                ImmutableList.of(singleEquals, greaterThan, betweenInclusive, greaterThanOrEquals, inClause, lessThan, TupleDomain.all()),
                ImmutableList.of(
                        ImmutableList.of("-128"),
                        ImmutableList.of("100", "50"),
                        ImmutableList.of("0", "50"),
                        ImmutableList.of("0", "100", "50"),
                        ImmutableList.of("-128", "0"),
                        ImmutableList.of("-128"),
                        ImmutableList.of("-128", "0", "100", "50")));
    }

    @Test
    public void testGetPartitionsFilterDecimal()
            throws Exception
    {
        String value1 = "1.000";
        String value2 = "10.134";
        String value3 = "25.111";
        String value4 = "30.333";

        TupleDomain<String> singleEquals = new PartitionFilterBuilder()
                .addDecimalValues(PARTITION_KEY, value1)
                .build();
        TupleDomain<String> greaterThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThan(DECIMAL_TYPE, decimalOf(value2)))
                .build();
        TupleDomain<String> betweenInclusive = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.range(DECIMAL_TYPE, decimalOf(value2), true, decimalOf(value3), true))
                .build();
        TupleDomain<String> greaterThanOrEquals = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThanOrEqual(DECIMAL_TYPE, decimalOf(value3)))
                .build();
        TupleDomain<String> inClause = new PartitionFilterBuilder()
                .addDecimalValues(PARTITION_KEY, value1, value4)
                .build();
        TupleDomain<String> lessThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.lessThan(DECIMAL_TYPE, decimalOf("25.5")))
                .build();
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_DECIMAL,
                PARTITION_KEY,
                ImmutableList.of(value1, value2, value3, value4),
                ImmutableList.of(singleEquals, greaterThan, betweenInclusive, greaterThanOrEquals, inClause, lessThan, TupleDomain.all()),
                ImmutableList.of(
                        ImmutableList.of(value1),
                        ImmutableList.of(value3, value4),
                        ImmutableList.of(value2, value3),
                        ImmutableList.of(value3, value4),
                        ImmutableList.of(value1, value4),
                        ImmutableList.of(value1, value2, value3),
                        ImmutableList.of(value1, value2, value3, value4)));
    }

    // we don't presently know how to properly convert a Date type into a string that is compatible with Glue.
    @Test
    public void testGetPartitionsFilterDate()
            throws Exception
    {
        TupleDomain<String> singleEquals = new PartitionFilterBuilder()
                .addDateValues(PARTITION_KEY, 18000L)
                .build();
        TupleDomain<String> greaterThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThan(DateType.DATE, 19000L))
                .build();
        TupleDomain<String> betweenInclusive = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.range(DateType.DATE, 19000L, true, 20000L, true))
                .build();
        TupleDomain<String> greaterThanOrEquals = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThanOrEqual(DateType.DATE, 19000L))
                .build();
        TupleDomain<String> inClause = new PartitionFilterBuilder()
                .addDateValues(PARTITION_KEY, 18000L, 21000L)
                .build();
        TupleDomain<String> lessThan = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.lessThan(DateType.DATE, 20000L))
                .build();
        // we are unable to convert Date to a string format that Glue will accept, so it should translate to the wildcard in all cases. Commented out results are
        // what we expect if we are able to do a proper conversion
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_DATE,
                PARTITION_KEY,
                ImmutableList.of("18000", "19000", "20000", "21000"),
                ImmutableList.of(
                        singleEquals, greaterThan, betweenInclusive, greaterThanOrEquals, inClause, lessThan, TupleDomain.all()),
                ImmutableList.of(
//                        ImmutableList.of("18000"),
//                        ImmutableList.of("20000", "21000"),
//                        ImmutableList.of("19000", "20000"),
//                        ImmutableList.of("19000", "20000", "21000"),
//                        ImmutableList.of("18000", "21000"),
//                        ImmutableList.of("18000", "19000"),
                        ImmutableList.of("18000", "19000", "20000", "21000"),
                        ImmutableList.of("18000", "19000", "20000", "21000"),
                        ImmutableList.of("18000", "19000", "20000", "21000"),
                        ImmutableList.of("18000", "19000", "20000", "21000"),
                        ImmutableList.of("18000", "19000", "20000", "21000"),
                        ImmutableList.of("18000", "19000", "20000", "21000"),
                        ImmutableList.of("18000", "19000", "20000", "21000")));
    }

    @Test
    public void testGetPartitionsFilterTwoPartitionKeys()
            throws Exception
    {
        TupleDomain<String> equalsFilter = new PartitionFilterBuilder()
                .addStringValues(PARTITION_KEY, "2020-03-01")
                .addBigintValues(PARTITION_KEY2, 300L)
                .build();
        TupleDomain<String> rangeFilter = new PartitionFilterBuilder()
                .addRanges(PARTITION_KEY, Range.greaterThanOrEqual(VarcharType.VARCHAR, utf8Slice("2020-02-01")))
                .addRanges(PARTITION_KEY2, Range.greaterThan(BigintType.BIGINT, 200L))
                .build();

        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_TWO_KEYS,
                ImmutableList.of(PARTITION_KEY, PARTITION_KEY2),
                ImmutableList.of(
                        PartitionValues.make("2020-01-01", "100"),
                        PartitionValues.make("2020-02-01", "200"),
                        PartitionValues.make("2020-03-01", "300"),
                        PartitionValues.make("2020-04-01", "400")),
                ImmutableList.of(equalsFilter, rangeFilter, TupleDomain.all()),
                ImmutableList.of(
                        ImmutableList.of(PartitionValues.make("2020-03-01", "300")),
                        ImmutableList.of(
                                PartitionValues.make("2020-03-01", "300"),
                                PartitionValues.make("2020-04-01", "400")),
                        ImmutableList.of(
                                PartitionValues.make("2020-01-01", "100"),
                                PartitionValues.make("2020-02-01", "200"),
                                PartitionValues.make("2020-03-01", "300"),
                                PartitionValues.make("2020-04-01", "400"))));
    }

    @Test
    public void testGetPartitionsFilterMaxLengthWildcard()
            throws Exception
    {
        // this filter string will exceed the 2048 char limit set by glue, and we expect the filter to revert to the wildcard
        TupleDomain<String> filter = new PartitionFilterBuilder()
                .addStringValues(PARTITION_KEY, "x".repeat(2048))
                .build();

        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_VARCHAR,
                PARTITION_KEY,
                VARCHAR_PARTITION_VALUES,
                ImmutableList.of(filter),
                ImmutableList.of(
                        ImmutableList.of("2020-01-01", "2020-02-01", "2020-03-01", "2020-04-01")));
    }

    @Test
    public void testGetPartitionsFilterTwoPartitionKeysPartialQuery()
            throws Exception
    {
        // we expect the second constraint to still be present and provide filtering
        TupleDomain<String> equalsFilter = new PartitionFilterBuilder()
                .addStringValues(PARTITION_KEY, "x".repeat(2048))
                .addBigintValues(PARTITION_KEY2, 300L)
                .build();

        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_TWO_KEYS,
                ImmutableList.of(PARTITION_KEY, PARTITION_KEY2),
                ImmutableList.of(
                        PartitionValues.make("2020-01-01", "100"),
                        PartitionValues.make("2020-02-01", "200"),
                        PartitionValues.make("2020-03-01", "300"),
                        PartitionValues.make("2020-04-01", "400")),
                ImmutableList.of(equalsFilter),
                ImmutableList.of(ImmutableList.of(PartitionValues.make("2020-03-01", "300"))));
    }

    @Test
    public void testGetPartitionsFilterNone()
            throws Exception
    {
        // test both a global none and that with a single column none, and a valid domain with none()
        TupleDomain<String> noneFilter = new PartitionFilterBuilder()
                .addDomain(PARTITION_KEY, Domain.none(VarcharType.VARCHAR))
                .build();
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_VARCHAR,
                PARTITION_KEY,
                VARCHAR_PARTITION_VALUES,
                ImmutableList.of(TupleDomain.none(), noneFilter),
                ImmutableList.of(ImmutableList.of(), ImmutableList.of()));
    }

    @Test
    public void testGetPartitionsFilterNotNull()
            throws Exception
    {
        TupleDomain<String> notNullFilter = new PartitionFilterBuilder()
                .addDomain(PARTITION_KEY, Domain.notNull(VarcharType.VARCHAR))
                .build();
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_VARCHAR,
                PARTITION_KEY,
                VARCHAR_PARTITION_VALUES,
                ImmutableList.of(notNullFilter),
                ImmutableList.of(ImmutableList.of("2020-01-01", "2020-02-01", "2020-03-01", "2020-04-01")));
    }

    @Test
    public void testGetPartitionsFilterIsNull()
            throws Exception
    {
        TupleDomain<String> isNullFilter = new PartitionFilterBuilder()
                .addDomain(PARTITION_KEY, Domain.onlyNull(VarcharType.VARCHAR))
                .build();
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_VARCHAR,
                PARTITION_KEY,
                VARCHAR_PARTITION_VALUES,
                ImmutableList.of(isNullFilter),
                ImmutableList.of(ImmutableList.of()));
    }

    @Test
    public void testGetPartitionsFilterIsNullWithValue()
            throws Exception
    {
        TupleDomain<String> isNullFilter = new PartitionFilterBuilder()
                .addDomain(PARTITION_KEY, Domain.onlyNull(VarcharType.VARCHAR))
                .build();
        List<String> partitionList = new ArrayList<>();
        partitionList.add(null);
        doGetPartitionsFilterTest(
                CREATE_TABLE_COLUMNS_PARTITIONED_VARCHAR,
                PARTITION_KEY,
                partitionList,
                ImmutableList.of(isNullFilter),
                ImmutableList.of(ImmutableList.of(GlueExpressionUtil.NULL_STRING)));
    }

    @Test
    public void testUpdateStatisticsOnCreate()
    {
        SchemaTableName tableName = temporaryTable("update_statistics_create");
        try (Transaction transaction = newTransaction()) {
            ConnectorSession session = newSession();
            ConnectorMetadata metadata = transaction.getMetadata();

            List<ColumnMetadata> columns = ImmutableList.of(new ColumnMetadata("a_column", BigintType.BIGINT));
            ConnectorTableMetadata tableMetadata = new ConnectorTableMetadata(tableName, columns, createTableProperties(TEXTFILE));
            ConnectorOutputTableHandle createTableHandle = metadata.beginCreateTable(session, tableMetadata, Optional.empty());

            // write data
            ConnectorPageSink sink = pageSinkProvider.createPageSink(transaction.getTransactionHandle(), session, createTableHandle);
            MaterializedResult data = MaterializedResult.resultBuilder(session, BigintType.BIGINT)
                    .row(1L)
                    .row(2L)
                    .row(3L)
                    .row(4L)
                    .row(5L)
                    .build();
            sink.appendPage(data.toPage());
            Collection<Slice> fragments = getFutureValue(sink.finish());

            // prepare statistics
            ComputedStatistics statistics = ComputedStatistics.builder(ImmutableList.of(), ImmutableList.of())
                    .addTableStatistic(TableStatisticType.ROW_COUNT, singleValueBlock(5))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", MIN_VALUE), singleValueBlock(1))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", MAX_VALUE), singleValueBlock(5))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", NUMBER_OF_DISTINCT_VALUES), singleValueBlock(5))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", NUMBER_OF_NON_NULL_VALUES), singleValueBlock(5))
                    .build();

            // finish CTAS
            metadata.finishCreateTable(session, createTableHandle, fragments, ImmutableList.of(statistics));
            transaction.commit();
        }
        finally {
            dropTable(tableName);
        }
    }

    @Test
    public void testUpdatePartitionedStatisticsOnCreate()
    {
        SchemaTableName tableName = temporaryTable("update_partitioned_statistics_create");
        try (Transaction transaction = newTransaction()) {
            ConnectorSession session = newSession();
            ConnectorMetadata metadata = transaction.getMetadata();

            List<ColumnMetadata> columns = ImmutableList.of(
                    new ColumnMetadata("a_column", BigintType.BIGINT),
                    new ColumnMetadata("part_column", BigintType.BIGINT));

            ConnectorTableMetadata tableMetadata = new ConnectorTableMetadata(tableName, columns, createTableProperties(TEXTFILE, ImmutableList.of("part_column")));
            ConnectorOutputTableHandle createTableHandle = metadata.beginCreateTable(session, tableMetadata, Optional.empty());

            // write data
            ConnectorPageSink sink = pageSinkProvider.createPageSink(transaction.getTransactionHandle(), session, createTableHandle);
            MaterializedResult data = MaterializedResult.resultBuilder(session, BigintType.BIGINT, BigintType.BIGINT)
                    .row(1L, 1L)
                    .row(2L, 1L)
                    .row(3L, 1L)
                    .row(4L, 2L)
                    .row(5L, 2L)
                    .build();
            sink.appendPage(data.toPage());
            Collection<Slice> fragments = getFutureValue(sink.finish());

            // prepare statistics
            ComputedStatistics statistics1 = ComputedStatistics.builder(ImmutableList.of("part_column"), ImmutableList.of(singleValueBlock(1)))
                    .addTableStatistic(TableStatisticType.ROW_COUNT, singleValueBlock(3))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", MIN_VALUE), singleValueBlock(1))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", MAX_VALUE), singleValueBlock(3))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", NUMBER_OF_DISTINCT_VALUES), singleValueBlock(3))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", NUMBER_OF_NON_NULL_VALUES), singleValueBlock(3))
                    .build();
            ComputedStatistics statistics2 = ComputedStatistics.builder(ImmutableList.of("part_column"), ImmutableList.of(singleValueBlock(2)))
                    .addTableStatistic(TableStatisticType.ROW_COUNT, singleValueBlock(2))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", MIN_VALUE), singleValueBlock(4))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", MAX_VALUE), singleValueBlock(5))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", NUMBER_OF_DISTINCT_VALUES), singleValueBlock(2))
                    .addColumnStatistic(new ColumnStatisticMetadata("a_column", NUMBER_OF_NON_NULL_VALUES), singleValueBlock(2))
                    .build();

            // finish CTAS
            metadata.finishCreateTable(session, createTableHandle, fragments, ImmutableList.of(statistics1, statistics2));
            transaction.commit();
        }
        finally {
            dropTable(tableName);
        }
    }

    private Block singleValueBlock(long value)
    {
        return BigintType.BIGINT.createBlockBuilder(null, 1).writeLong(value).build();
    }

    private void doGetPartitionsFilterTest(
            List<ColumnMetadata> columnMetadata,
            String partitionColumnName,
            List<String> partitionStringValues,
            List<TupleDomain<String>> filterList,
            List<List<String>> expectedSingleValueList)
            throws Exception
    {
        List<PartitionValues> partitionValuesList = partitionStringValues.stream()
                .map(PartitionValues::make)
                .collect(toImmutableList());
        List<List<PartitionValues>> expectedPartitionValuesList = expectedSingleValueList.stream()
                .map(expectedValue -> expectedValue.stream()
                        .map(PartitionValues::make)
                        .collect(toImmutableList()))
                .collect(toImmutableList());
        doGetPartitionsFilterTest(columnMetadata, ImmutableList.of(partitionColumnName), partitionValuesList, filterList, expectedPartitionValuesList);
    }

    /**
     * @param filterList should be same sized list as expectedValuesList
     * @param expectedValuesList
     * @throws Exception
     */
    private void doGetPartitionsFilterTest(
            List<ColumnMetadata> columnMetadata,
            List<String> partitionColumnNames,
            List<PartitionValues> partitionValues,
            List<TupleDomain<String>> filterList,
            List<List<PartitionValues>> expectedValuesList)
            throws Exception
    {
        try (CloseableSchamaTableName closeableTableName = new CloseableSchamaTableName(temporaryTable(("get_partitions")))) {
            SchemaTableName tableName = closeableTableName.getSchemaTableName();
            createDummyPartitionedTable(tableName, columnMetadata, partitionColumnNames, partitionValues);
            HiveMetastore metastoreClient = getMetastoreClient();

            for (int i = 0; i < filterList.size(); i++) {
                TupleDomain<String> filter = filterList.get(i);
                List<PartitionValues> expectedValues = expectedValuesList.get(i);
                List<String> expectedResults = expectedValues.stream()
                        .map(expectedPartitionValues -> makePartName(partitionColumnNames, expectedPartitionValues.getValues()))
                        .collect(toImmutableList());

                Optional<List<String>> partitionNames = metastoreClient.getPartitionNamesByFilter(
                        HIVE_CONTEXT,
                        tableName.getSchemaName(),
                        tableName.getTableName(),
                        partitionColumnNames,
                        filter);
                assertTrue(partitionNames.isPresent());
                assertEquals(
                        partitionNames.get(),
                        expectedResults,
                        format("lists \nactual: %s\nexpected: %s\nmismatch for filter %s (input index %d)\n", partitionNames.get(), expectedResults, filter, i));
            }
        }
    }

    private void createDummyPartitionedTable(SchemaTableName tableName, List<ColumnMetadata> columns, List<String> partitionColumnNames, List<PartitionValues> partitionValues)
            throws Exception
    {
        doCreateEmptyTable(tableName, ORC, columns, partitionColumnNames);

        HiveMetastoreClosure metastoreClient = new HiveMetastoreClosure(getMetastoreClient());
        HiveIdentity identity = new HiveIdentity(HiveTestUtils.SESSION);
        Table table = metastoreClient.getTable(identity, tableName.getSchemaName(), tableName.getTableName())
                .orElseThrow(() -> new TableNotFoundException(tableName));
        List<PartitionWithStatistics> partitions = new ArrayList<>();
        List<String> partitionNames = new ArrayList<>();
        partitionValues.stream()
                .map(partitionValue -> makePartName(partitionColumnNames, partitionValue.values))
                .forEach(
                        partitionName -> {
                            partitions.add(new PartitionWithStatistics(createDummyPartition(table, partitionName), partitionName, PartitionStatistics.empty()));
                            partitionNames.add(partitionName);
                        });
        metastoreClient.addPartitions(identity, tableName.getSchemaName(), tableName.getTableName(), partitions);
        partitionNames.forEach(
                partitionName -> metastoreClient.updatePartitionStatistics(
                        identity, tableName.getSchemaName(), tableName.getTableName(), partitionName, currentStatistics -> EMPTY_TABLE_STATISTICS));
    }

    private class CloseableSchamaTableName
            implements AutoCloseable
    {
        private final SchemaTableName schemaTableName;

        private CloseableSchamaTableName(SchemaTableName schemaTableName)
        {
            this.schemaTableName = schemaTableName;
        }

        public SchemaTableName getSchemaTableName()
        {
            return schemaTableName;
        }

        @Override
        public void close()
        {
            dropTable(schemaTableName);
        }
    }

    // container class for readability. Each value is one for a partitionKey, in order they appear in the schema
    private static class PartitionValues
    {
        private final List<String> values;

        private static PartitionValues make(String... values)
        {
            return new PartitionValues(Arrays.asList(values));
        }

        private static PartitionValues make(List<String> values)
        {
            return new PartitionValues(values);
        }

        private PartitionValues(List<String> values)
        {
            this.values = values;
        }

        public List<String> getValues()
        {
            return values;
        }
    }
}