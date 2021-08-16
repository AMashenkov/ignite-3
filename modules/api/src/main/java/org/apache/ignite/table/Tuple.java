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

package org.apache.ignite.table;

import java.util.BitSet;
import java.util.UUID;
import org.apache.ignite.binary.BinaryObject;
import org.apache.ignite.lang.IgniteException;

/**
 * Tuple represents arbitrary set of columns whose values is accessible by column name.
 * <p>
 * Provides specialized method for some value-types to avoid boxing/unboxing.
 */
public interface Tuple extends Iterable<Object> {
    /**
     * Factory method for tuple.
     *
     * @return Tuple.
     */
    static Tuple create() {
        return new TupleImpl();
    }

   /**
     * Gets the number of columns in this tuple.
     *
     * @return Number of columns.
     */
    int columnCount();

    /**
     * Gets the name of the column with the specified index.
     *
     * @param columnIndex Column index.
     * @return Column name.
     * @throws IndexOutOfBoundsException If a value for a column with given index was never set.
     */
    String columnName(int columnIndex);

    /**
     * Gets the index of the column with the specified name.
     *
     * @param columnName Column name.
     * @return Column index, or {@code -1} when a column with given name is not present.
     */
    int columnIndex(String columnName);

    /**
     * Gets column value when a column with specified name is present in this tuple; returns default value otherwise.
     *
     * @param columnName Column name.
     * @param def Default value.
     * @param <T> Column default value type.
     * @return Column value if this tuple contains a column with the specified name. Otherwise returns {@code default}.
     */
    <T> T valueOrDefault(String columnName, T def);

    /**
     * Sets column value.
     *
     * @param columnName Column name.
     * @param value Value to set.
     * @return {@code this} for chaining.
     */
    Tuple set(String columnName, Object value);

    /**
     * Gets column value for given column name.
     *
     * @param columnName Column name.
     * @param <T> Value type.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    <T> T value(String columnName) throws IgniteException;

    /**
     * Gets column value for given column index.
     *
     * @param columnIndex Column index.
     * @param <T> Value type.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    <T> T value(int columnIndex);

    /**
     * Gets binary object column.
     *
     * @param columnName Column name.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    BinaryObject binaryObjectValue(String columnName);

    /**
     * Gets binary object column.
     *
     * @param columnIndex Column index.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    BinaryObject binaryObjectValue(int columnIndex);

    /**
     * Gets {@code byte} column value.
     *
     * @param columnName Column name.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    byte byteValue(String columnName);

    /**
     * Gets {@code byte} column value.
     *
     * @param columnIndex Column index.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    byte byteValue(int columnIndex);

    /**
     * Gets {@code short} column value.
     *
     * @param columnName Column name.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    short shortValue(String columnName);

    /**
     * Gets {@code short} column value.
     *
     * @param columnIndex Column index.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    short shortValue(int columnIndex);

    /**
     * Gets {@code int} column value.
     *
     * @param columnName Column name.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    int intValue(String columnName);

    /**
     * Gets {@code int} column value.
     *
     * @param columnIndex Column index.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    int intValue(int columnIndex);

    /**
     * Gets {@code long} column value.
     *
     * @param columnName Column name.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    long longValue(String columnName);

    /**
     * Gets {@code long} column value.
     *
     * @param columnIndex Column index.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    long longValue(int columnIndex);

    /**
     * Gets {@code float} column value.
     *
     * @param columnName Column name.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    float floatValue(String columnName);

    /**
     * Gets {@code float} column value.
     *
     * @param columnIndex Column index.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    float floatValue(int columnIndex);

    /**
     * Gets {@code double} column value.
     *
     * @param columnName Column name.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    double doubleValue(String columnName);

    /**
     * Gets {@code double} column value.
     *
     * @param columnIndex Column index.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    double doubleValue(int columnIndex);

    /**
     * Gets {@code String} column value.
     *
     * @param columnName Column name.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    String stringValue(String columnName);

    /**
     * Gets {@code String} column value.
     *
     * @param columnIndex Column index.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    String stringValue(int columnIndex);

    /**
     * Gets {@code UUID} column value.
     *
     * @param columnName Column name.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    UUID uuidValue(String columnName);

    /**
     * Gets {@code UUID} column value.
     *
     * @param columnIndex Column index.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    UUID uuidValue(int columnIndex);

    /**
     * Gets {@code BitSet} column value.
     *
     * @param columnName Column name.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    BitSet bitmaskValue(String columnName);

    /**
     * Gets {@code BitSet} column value.
     *
     * @param columnIndex Column index.
     * @return Column value.
     * @throws org.apache.ignite.lang.IgniteException If column value was not set.
     */
    BitSet bitmaskValue(int columnIndex);
}
