/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.catalog.commands;

import static java.util.stream.Collectors.toList;
import static org.apache.ignite.internal.catalog.CatalogService.DEFAULT_SCHEMA_NAME;
import static org.apache.ignite.internal.catalog.CatalogService.DEFAULT_ZONE_NAME;
import static org.apache.ignite.internal.catalog.CatalogTestUtils.createTestCatalogManager;
import static org.apache.ignite.internal.catalog.CatalogTestUtils.index;
import static org.apache.ignite.internal.catalog.commands.CatalogUtils.collectIndexes;
import static org.apache.ignite.internal.catalog.commands.CatalogUtils.pkIndexName;
import static org.apache.ignite.internal.catalog.commands.CatalogUtils.replaceTable;
import static org.apache.ignite.internal.testframework.matchers.CompletableFutureMatcher.willCompleteSuccessfully;
import static org.apache.ignite.sql.ColumnType.INT32;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import org.apache.ignite.internal.catalog.CatalogCommand;
import org.apache.ignite.internal.catalog.CatalogManager;
import org.apache.ignite.internal.catalog.CatalogValidationException;
import org.apache.ignite.internal.catalog.descriptors.CatalogIndexDescriptor;
import org.apache.ignite.internal.catalog.descriptors.CatalogSchemaDescriptor;
import org.apache.ignite.internal.catalog.descriptors.CatalogTableDescriptor;
import org.apache.ignite.internal.hlc.HybridClock;
import org.apache.ignite.internal.hlc.HybridClockImpl;
import org.apache.ignite.internal.testframework.BaseIgniteAbstractTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** For {@link CatalogUtils} testing. */
public class CatalogUtilsTest extends BaseIgniteAbstractTest {
    private static final String TABLE_NAME = "test_table";

    private static final String INDEX_NAME = "test_index";

    private static final String PK_INDEX_NAME = pkIndexName(TABLE_NAME);

    private static final String COLUMN_NAME = "key";

    private final HybridClock clock = new HybridClockImpl();

    private final CatalogManager catalogManager = createTestCatalogManager("test", clock);

    @BeforeEach
    void setUp() {
        assertThat(catalogManager.start(), willCompleteSuccessfully());
    }

    @AfterEach
    void tearDown() throws Exception {
        catalogManager.stop();
    }

    @Test
    void testCollectIndexesAfterCreateTable() {
        createTable(TABLE_NAME);

        int latestCatalogVersion = catalogManager.latestCatalogVersion();
        int earliestCatalogVersion = catalogManager.earliestCatalogVersion();

        int tableId = tableId(latestCatalogVersion, TABLE_NAME);

        assertThat(
                collectIndexes(catalogManager, tableId, latestCatalogVersion, latestCatalogVersion),
                hasItems(index(catalogManager, latestCatalogVersion, PK_INDEX_NAME))
        );

        assertThat(
                collectIndexes(catalogManager, tableId, earliestCatalogVersion, latestCatalogVersion),
                hasItems(index(catalogManager, latestCatalogVersion, PK_INDEX_NAME))
        );
    }

    @Test
    void testCollectIndexesAfterCreateIndex() {
        createTable(TABLE_NAME);
        createIndex(TABLE_NAME, INDEX_NAME);

        int latestCatalogVersion = catalogManager.latestCatalogVersion();
        int earliestCatalogVersion = catalogManager.earliestCatalogVersion();

        int tableId = tableId(latestCatalogVersion, TABLE_NAME);

        assertThat(
                collectIndexes(catalogManager, tableId, latestCatalogVersion, latestCatalogVersion),
                hasItems(
                        index(catalogManager, latestCatalogVersion, PK_INDEX_NAME),
                        index(catalogManager, latestCatalogVersion, INDEX_NAME)
                )
        );

        assertThat(
                collectIndexes(catalogManager, tableId, earliestCatalogVersion, latestCatalogVersion),
                hasItems(
                        index(catalogManager, latestCatalogVersion, PK_INDEX_NAME),
                        index(catalogManager, latestCatalogVersion, INDEX_NAME)
                )
        );
    }

    @Test
    void testCollectIndexesAfterCreateIndexAndStartBuildingIndexAndMakeAvailableIndex() {
        String indexName0 = INDEX_NAME + 0;
        String indexName1 = INDEX_NAME + 1;
        String indexName2 = INDEX_NAME + 2;

        createTable(TABLE_NAME);
        createIndex(TABLE_NAME, indexName0);
        createIndex(TABLE_NAME, indexName1);
        createIndex(TABLE_NAME, indexName2);

        startBuildingIndex(indexName1);
        makeIndexAvailable(indexName1);

        startBuildingIndex(indexName2);

        int latestCatalogVersion = catalogManager.latestCatalogVersion();
        int earliestCatalogVersion = catalogManager.earliestCatalogVersion();

        int tableId = tableId(latestCatalogVersion, TABLE_NAME);

        assertThat(
                collectIndexes(catalogManager, tableId, latestCatalogVersion, latestCatalogVersion),
                hasItems(
                        index(catalogManager, latestCatalogVersion, PK_INDEX_NAME),
                        index(catalogManager, latestCatalogVersion, indexName0),
                        index(catalogManager, latestCatalogVersion, indexName1),
                        index(catalogManager, latestCatalogVersion, indexName2)
                )
        );

        assertThat(
                collectIndexes(catalogManager, tableId, earliestCatalogVersion, latestCatalogVersion),
                hasItems(
                        index(catalogManager, latestCatalogVersion, PK_INDEX_NAME),
                        index(catalogManager, latestCatalogVersion, indexName0),
                        index(catalogManager, latestCatalogVersion, indexName1),
                        index(catalogManager, latestCatalogVersion, indexName2)
                )
        );
    }

    @Test
    void testCollectIndexesAfterDropIndexes() {
        String indexName0 = INDEX_NAME + 0;
        String indexName1 = INDEX_NAME + 1;
        String indexName2 = INDEX_NAME + 2;

        createTable(TABLE_NAME);
        createIndex(TABLE_NAME, indexName0);
        createIndex(TABLE_NAME, indexName1);
        createIndex(TABLE_NAME, indexName2);

        startBuildingIndex(indexName1);
        makeIndexAvailable(indexName1);

        startBuildingIndex(indexName2);

        int catalogVersionBeforeDropIndex0 = catalogManager.latestCatalogVersion();

        dropIndex(indexName0);

        int catalogVersionBeforeDropIndex1 = catalogManager.latestCatalogVersion();

        dropIndex(indexName1);

        int latestCatalogVersion = catalogManager.latestCatalogVersion();
        int earliestCatalogVersion = catalogManager.earliestCatalogVersion();

        int tableId = tableId(latestCatalogVersion, TABLE_NAME);

        assertThat(
                collectIndexes(catalogManager, tableId, latestCatalogVersion, latestCatalogVersion),
                hasItems(index(catalogManager, latestCatalogVersion, PK_INDEX_NAME))
        );

        assertThat(
                collectIndexes(catalogManager, tableId, earliestCatalogVersion, latestCatalogVersion),
                hasItems(
                        index(catalogManager, latestCatalogVersion, PK_INDEX_NAME),
                        index(catalogManager, catalogVersionBeforeDropIndex0, indexName0),
                        index(catalogManager, catalogVersionBeforeDropIndex1, indexName1),
                        index(catalogManager, catalogVersionBeforeDropIndex1, indexName2)
                )
        );
    }

    /**
     * Tests the more complex case of getting indexes.
     *
     * <p>Consider the following versions of the catalog with its contents:</p>
     * <pre>
     *     Catalog versions and entity IDs have been simplified.
     *
     *     0 : T0 Ipk(A)
     *     1 : T0 Ipk(A) I0(R) I1(R) I2(R) I3(R)
     *     2 : T0 Ipk(A) I0(A) I1(B) I2(A) I3(R)
     *     3 : T0 Ipk(A) I1(B) I2(A)
     * </pre>
     *
     * <p>Expected indexes for range version:</p>
     * <pre>
     *     3 -> 3 : Ipk(A) I1(B) I2(A)
     *     0 -> 3 : Ipk(A) I0(A) I1(B) I2(A) I3(R)
     * </pre>
     */
    @Test
    void testCollectIndexesComplexCase() {
        String indexName0 = INDEX_NAME + 0;
        String indexName1 = INDEX_NAME + 1;
        String indexName2 = INDEX_NAME + 2;
        String indexName3 = INDEX_NAME + 3;

        createTable(TABLE_NAME);
        createIndex(TABLE_NAME, indexName0);
        createIndex(TABLE_NAME, indexName1);
        createIndex(TABLE_NAME, indexName2);
        createIndex(TABLE_NAME, indexName3);

        startBuildingIndex(indexName0);
        startBuildingIndex(indexName1);
        startBuildingIndex(indexName2);

        makeIndexAvailable(indexName0);
        makeIndexAvailable(indexName2);

        int catalogVersionBeforeDropIndex0 = catalogManager.latestCatalogVersion();

        dropIndex(indexName0);

        int catalogVersionBeforeDropIndex3 = catalogManager.latestCatalogVersion();

        dropIndex(indexName3);

        int latestCatalogVersion = catalogManager.latestCatalogVersion();
        int earliestCatalogVersion = catalogManager.earliestCatalogVersion();

        int tableId = tableId(latestCatalogVersion, TABLE_NAME);

        assertThat(
                collectIndexes(catalogManager, tableId, latestCatalogVersion, latestCatalogVersion),
                hasItems(
                        index(catalogManager, latestCatalogVersion, PK_INDEX_NAME),
                        index(catalogManager, latestCatalogVersion, indexName1),
                        index(catalogManager, latestCatalogVersion, indexName2)
                )
        );

        assertThat(
                collectIndexes(catalogManager, tableId, earliestCatalogVersion, latestCatalogVersion),
                hasItems(
                        index(catalogManager, latestCatalogVersion, PK_INDEX_NAME),
                        index(catalogManager, catalogVersionBeforeDropIndex0, indexName0),
                        index(catalogManager, latestCatalogVersion, indexName1),
                        index(catalogManager, latestCatalogVersion, indexName2),
                        index(catalogManager, catalogVersionBeforeDropIndex3, indexName3)
                )
        );
    }

    @Test
    void testReplaceTable() {
        createTable("foo");
        createTable("bar");

        CatalogSchemaDescriptor schema = catalogManager.activeSchema(DEFAULT_SCHEMA_NAME, clock.nowLong());

        assertThat(schema, is(notNullValue()));

        CatalogTableDescriptor fooTable = catalogManager.table("foo", clock.nowLong());

        assertThat(fooTable, is(notNullValue()));

        CatalogTableDescriptor bazTable = fooTable.newDescriptor(
                "baz",
                fooTable.tableVersion(),
                fooTable.columns(),
                fooTable.updateToken()
        );

        CatalogSchemaDescriptor updatedSchema = replaceTable(schema, bazTable);

        List<String> tableNames = Arrays.stream(updatedSchema.tables()).map(CatalogTableDescriptor::name).collect(toList());

        assertThat(tableNames, contains("baz", "bar"));
    }

    @Test
    void testReplaceTableMissingTable() {
        CatalogSchemaDescriptor schema = catalogManager.activeSchema(DEFAULT_SCHEMA_NAME, clock.nowLong());

        assertThat(schema, is(notNullValue()));

        var table = mock(CatalogTableDescriptor.class);

        when(table.id()).thenReturn(Integer.MAX_VALUE);

        Exception e = assertThrows(CatalogValidationException.class, () -> replaceTable(schema, table));

        assertThat(e.getMessage(), is(String.format("Table with ID %d has not been found in schema with ID %d", Integer.MAX_VALUE, 0)));
    }

    private void createTable(String tableName) {
        CatalogCommand catalogCommand = CreateTableCommand.builder()
                .schemaName(DEFAULT_SCHEMA_NAME)
                .zone(DEFAULT_ZONE_NAME)
                .tableName(tableName)
                .columns(List.of(ColumnParams.builder().name(COLUMN_NAME).type(INT32).build()))
                .primaryKeyColumns(List.of(COLUMN_NAME))
                .colocationColumns(List.of(COLUMN_NAME))
                .build();

        assertThat(catalogManager.execute(catalogCommand), willCompleteSuccessfully());
    }

    private void createIndex(String tableName, String indexName) {
        CatalogCommand catalogCommand = CreateHashIndexCommand.builder()
                .schemaName(DEFAULT_SCHEMA_NAME)
                .tableName(tableName)
                .indexName(indexName)
                .columns(List.of(COLUMN_NAME))
                .unique(false)
                .build();

        assertThat(catalogManager.execute(catalogCommand), willCompleteSuccessfully());
    }

    private void startBuildingIndex(String indexName) {
        CatalogIndexDescriptor index = index(catalogManager, catalogManager.latestCatalogVersion(), indexName);

        CatalogCommand catalogCommand = StartBuildingIndexCommand.builder().indexId(index.id()).build();

        assertThat(catalogManager.execute(catalogCommand), willCompleteSuccessfully());
    }

    private void makeIndexAvailable(String indexName) {
        CatalogIndexDescriptor index = index(catalogManager, catalogManager.latestCatalogVersion(), indexName);

        CatalogCommand catalogCommand = MakeIndexAvailableCommand.builder()
                .indexId(index.id())
                .build();

        assertThat(catalogManager.execute(catalogCommand), willCompleteSuccessfully());
    }

    private void dropIndex(String indexName) {
        CatalogCommand catalogCommand = DropIndexCommand.builder()
                .schemaName(DEFAULT_SCHEMA_NAME)
                .indexName(indexName)
                .build();

        assertThat(catalogManager.execute(catalogCommand), willCompleteSuccessfully());
    }

    private int tableId(int catalogVersion, String tableName) {
        CatalogTableDescriptor tableDescriptor = catalogManager.tables(catalogVersion).stream()
                .filter(table -> tableName.equals(table.name()))
                .findFirst()
                .orElse(null);

        assertNotNull(tableDescriptor, "catalogVersion=" + catalogVersion + ", tableName=" + tableName);

        return tableDescriptor.id();
    }
}
