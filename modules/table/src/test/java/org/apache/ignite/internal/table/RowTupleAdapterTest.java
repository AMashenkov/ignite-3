/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.table;

import java.util.UUID;
import org.apache.ignite.internal.schema.ByteBufferRow;
import org.apache.ignite.internal.schema.Column;
import org.apache.ignite.internal.schema.NativeTypes;
import org.apache.ignite.internal.schema.SchemaDescriptor;
import org.apache.ignite.internal.schema.marshaller.TupleMarshaller;
import org.apache.ignite.internal.schema.row.Row;
import org.apache.ignite.internal.table.impl.DummySchemaManagerImpl;
import org.apache.ignite.schema.SchemaMode;
import org.apache.ignite.table.Tuple;
import org.apache.ignite.table.TupleImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests server tuple builder implementation.
 * <p>
 * Should be in sync with org.apache.ignite.client.ClientTupleBuilderTest.
 */
public class RowTupleAdapterTest {
    /** Mocked table. */
    private InternalTable tbl = Mockito.when(Mockito.mock(InternalTable.class).schemaMode()).thenReturn(SchemaMode.STRICT_SCHEMA).getMock();

    /** Schema descriptor. */
    private SchemaDescriptor schema = new SchemaDescriptor(
            UUID.randomUUID(),
            42,
            new Column[]{new Column("id", NativeTypes.INT64, false)},
            new Column[]{new Column("name", NativeTypes.STRING, true)}
    );

    @Test
    public void testValueReturnsValueByName() {
        assertEquals(3L, (Long) getTuple().value("id"));
        assertEquals("Shirt", getTuple().value("name"));
    }

    @Test
    public void testValueThrowsOnInvalidColumnName() {
        var ex = assertThrows(ColumnNotFoundException.class, () -> getTuple().value("x"));
        assertEquals("Invalid column name: columnName=x", ex.getMessage());
    }

    @Test
    public void testValueReturnsValueByIndex() {
        assertEquals(3L, (Long) getTuple().value(0));
        assertEquals("Shirt", getTuple().value(1));
    }

    @Test
    public void testValueThrowsOnInvalidIndex() {
        var ex = assertThrows(IndexOutOfBoundsException.class, () -> getTuple().value(-1));
        assertEquals("Index -1 out of bounds for length 2", ex.getMessage());

        ex = assertThrows(IndexOutOfBoundsException.class, () -> getTuple().value(3));
        assertEquals("Index 3 out of bounds for length 2", ex.getMessage());
    }

    @Test
    public void testValueOrDefaultReturnsValueByName() {
        assertEquals(3L, getTuple().valueOrDefault("id", -1L));
        assertEquals("Shirt", getTuple().valueOrDefault("name", "y"));
    }

    @Test
    public void testValueOrDefaultReturnsDefaultWhenColumnIsNotSet() {
        assertEquals("foo", getTuple().valueOrDefault("x", "foo"));
    }

    @Test
    public void testValueReturnsOverwrittenValue() {
        assertEquals("foo", getTuple().set("name", "foo").value("name"));
        assertEquals("foo", getTuple().set("name", "foo").valueOrDefault("name", "bar"));
    }

    @Test
    public void testValueOrDefaultReturnsNullWhenColumnIsSetToNull() {
        assertNull(getTuple().set("name", null).valueOrDefault("name", "foo"));
    }

    @Test
    public void testColumnCountReturnsSchemaSize() {
        assertEquals(2, getTuple().columnCount());

        Tuple tuple = getTuple();

        assertEquals(2, tuple.columnCount());
        assertEquals(2, tuple.set("id", -1).columnCount());

        tuple.valueOrDefault("name", "foo");
        assertEquals(2, tuple.columnCount());

        tuple.valueOrDefault("foo", "bar");
        assertEquals(2, tuple.columnCount());

        tuple.set("foo", "bar");
        assertEquals(3, tuple.columnCount());
    }

    @Test
    public void testColumnNameReturnsNameByIndex() {
        assertEquals("id", getTuple().columnName(0));
        assertEquals("name", getTuple().columnName(1));
    }

    @Test
    public void testColumnNameThrowsOnInvalidIndex() {
        var ex = assertThrows(IndexOutOfBoundsException.class, () -> getTuple().columnName(-1));
        assertEquals("Index -1 out of bounds for length 2", ex.getMessage());

        ex = assertThrows(IndexOutOfBoundsException.class, () -> getTuple().columnName(3));
        assertEquals("Index 3 out of bounds for length 2", ex.getMessage());
    }

    @Test
    public void testColumnIndexReturnsIndexByName() {
        assertEquals(0, getTuple().columnIndex("id"));
        assertEquals(1, getTuple().columnIndex("name"));
    }

    @Test
    public void testColumnIndexForMissingColumns() {
        assertEquals(-1, getTuple().columnIndex("foo"));
    }

    @Test
    public void testKeyValueChunks() {
        SchemaDescriptor schema = new SchemaDescriptor(
                UUID.randomUUID(),
                42,
                new Column[]{new Column("id", NativeTypes.INT64, false)},
                new Column[]{new Column("name", NativeTypes.STRING, true),
                        new Column("price", NativeTypes.DOUBLE, true)}
        );

        Tuple original = new TupleImpl()
                .set("id", 3L)
                .set("name", "Shirt")
                .set("price", 5.99d);

        TupleMarshaller marshaller = new TupleMarshallerImpl(null, tbl, new DummySchemaManagerImpl(schema));

        Row row = new Row(schema, new ByteBufferRow(marshaller.marshal(original).bytes()));

        Tuple key = TableRow.keyTuple(row);
        Tuple val = TableRow.valueTuple(row);

        assertEquals(3L, (Long) key.value("id"));
        assertEquals(3L, (Long) key.value(0));

        assertEquals("Shirt", val.value("name"));
        assertEquals("Shirt", val.value(1));

        assertEquals(5.99d, val.value("price"));
        assertEquals(5.99d, val.value(0));

        // Wrong columns.
        assertThrows(IndexOutOfBoundsException.class, () -> key.value(1));
        assertThrows(ColumnNotFoundException.class, () -> key.value("price"));

        assertThrows(IndexOutOfBoundsException.class, () -> val.value(2));
        assertThrows(ColumnNotFoundException.class, () -> val.value("id"));
    }

    private Tuple getTuple() {
        Tuple original = new TupleImpl()
                .set("id", 3L)
                .set("name", "Shirt");

        TupleMarshaller marshaller = new TupleMarshallerImpl(null, tbl, new DummySchemaManagerImpl(schema));

        return TableRow.tuple(new Row(schema, new ByteBufferRow(marshaller.marshal(original).bytes())));
    }
}
