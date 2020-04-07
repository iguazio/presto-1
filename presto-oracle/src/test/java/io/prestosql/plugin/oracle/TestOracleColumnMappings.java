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

import io.airlift.slice.Slice;
import io.prestosql.plugin.jdbc.ColumnMapping;
import io.prestosql.plugin.jdbc.ReadFunction;
import io.prestosql.plugin.jdbc.SliceReadFunction;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.Decimals;
import io.prestosql.spi.type.Type;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;

import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static io.prestosql.spi.type.Decimals.encodeUnscaledValue;
import static io.prestosql.spi.type.UnscaledDecimal128Arithmetic.unscaledDecimalToBigInteger;
import static java.lang.String.format;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.fail;

public class TestOracleColumnMappings
{
    private DecimalType dec2Scale = createDecimalType(Decimals.MAX_PRECISION, 2);
    private DecimalType dec8Scale = createDecimalType(Decimals.MAX_PRECISION, 8);
    private DecimalType dec15Scale = createDecimalType(Decimals.MAX_PRECISION, 15);

    public static ResultSet getMockResultSet(int columnIndex, Object value)
    {
        ResultSet resultSetMock = Mockito.mock(ResultSet.class);
        try {
            if (value instanceof BigDecimal) {
                Mockito.when(resultSetMock.getBigDecimal(columnIndex)).thenReturn((BigDecimal) value);
            }
            else if (value instanceof Double) {
                Mockito.when(resultSetMock.getDouble(columnIndex)).thenReturn((Double) value);
            }
            else if (value instanceof Long) {
                Mockito.when(resultSetMock.getLong(columnIndex)).thenReturn((Long) value);
            }
            else if (value instanceof Boolean) {
                Mockito.when(resultSetMock.getBoolean(columnIndex)).thenReturn((Boolean) value);
            }
            else if (value instanceof Short) {
                Mockito.when(resultSetMock.getShort(columnIndex)).thenReturn((Short) value);
            }
            else if (value instanceof Integer) {
                Mockito.when(resultSetMock.getInt(columnIndex)).thenReturn((Integer) value);
            }
            // provide a getString() version always
            Mockito.when(resultSetMock.getString(columnIndex)).thenReturn((String) value.toString());
        }
        catch (SQLException ex) {
            throw new RuntimeException(ex);
        }

        return resultSetMock;
    }

    private static BigDecimal toBigDecimal(Slice valueSlice, int scale)
    {
        return new BigDecimal(unscaledDecimalToBigInteger(valueSlice), scale);
    }

    public static void assertPrestoTypeEquals(Type t1, Type t2)
    {
        if (!t1.getClass().equals(t2.getClass())) {
            fail(format("Presto data-types are not identical expected [%s] but found [%s]", t1.getClass(), t2.getClass()));
        }
        if (t1 instanceof DecimalType) {
            DecimalType t1Dec = (DecimalType) t1;
            DecimalType t2Dec = (DecimalType) t2;
            if (t1Dec.getPrecision() != t2Dec.getPrecision()
                    || t1Dec.getScale() != t2Dec.getScale()) {
                fail(format("Presto DecimalTypes not identical expected [%s] but found [%s]", t1Dec, t2Dec));
            }
        }
    }

    private static String repeat(String value, int times)
    {
        return String.format("%0" + times + "d", 0).replace("0", value);
    }

    public static void assertPrestoReadFunctionEquals(ReadFunction r1, ReadFunction r2, Type prestoType)
    {
        if (!r1.getClass().equals(r2.getClass())) {
            fail(format("ReadFunctions (class types) are not identical, expected [%s] but found [%s]", r1, r2, r1.getClass(), r2.getClass()));
        }

        if (!r1.getJavaType().equals(r2.getJavaType())) {
            fail(format("ReadFunctions 1:%s - 2:%s (return types) are not identical, expected [%s] but found [%s]", r1, r2, r1.getJavaType(), r1.getJavaType()));
        }
    }

    public static void assertColumnMappingEquals(ColumnMapping r1, ColumnMapping r2)
    {
        assertPrestoTypeEquals(r1.getType(), r2.getType());
        assertPrestoReadFunctionEquals(r1.getReadFunction(), r2.getReadFunction(), r1.getType());
    }

    public static void assertColumnMappingNotEquals(ColumnMapping r1, ColumnMapping r2)
    {
        assertThrows(AssertionError.class, () -> TestOracleColumnMappings.assertColumnMappingEquals(r1, r2));
    }

    @Test
    void testType()
    {
        ColumnMapping read = OracleColumnMappings.roundDecimalColumnMapping(dec2Scale, RoundingMode.UP);
        assertEquals(read.getType().getJavaType(), Slice.class);
    }

    @Test
    void testRound()
    {
        ColumnMapping roundDecimal2 = OracleColumnMappings.roundDecimalColumnMapping(dec2Scale, RoundingMode.UP);
        ColumnMapping roundDecimal8 = OracleColumnMappings.roundDecimalColumnMapping(dec8Scale, RoundingMode.UP);
        ColumnMapping roundDecimal15 = OracleColumnMappings.roundDecimalColumnMapping(dec15Scale, RoundingMode.UP);

        assertSliceColumnMappingRoundTrip(new BigDecimal("1.00"), new BigDecimal("1"), roundDecimal2, dec2Scale);
        assertSliceColumnMappingRoundTrip(new BigDecimal("1.00"), new BigDecimal("1.0"), roundDecimal2, dec2Scale);
        assertSliceColumnMappingRoundTrip(new BigDecimal("1.20"), new BigDecimal("1.199"), roundDecimal2, dec2Scale);
        assertSliceColumnMappingRoundTrip(new BigDecimal("0.10"), new BigDecimal("0.099"), roundDecimal2, dec2Scale);

        assertSliceColumnMappingRoundTrip(new BigDecimal("1.12345679"), new BigDecimal("1.123456789"), roundDecimal8, dec8Scale);
        assertSliceColumnMappingRoundTrip(new BigDecimal("0.00000002"), new BigDecimal("0.000000019"), roundDecimal8, dec8Scale);
        assertSliceColumnMappingRoundTrip(new BigDecimal("100.00000001"), new BigDecimal("100.000000009"), roundDecimal8, dec8Scale);
        assertSliceColumnMappingRoundTrip(new BigDecimal("500.00000000"), new BigDecimal("500"), roundDecimal8, dec8Scale);

        assertSliceColumnMappingRoundTrip(new BigDecimal("0.68" + repeat("0", 13)), new BigDecimal("0.680"), roundDecimal15, dec15Scale);
        assertSliceColumnMappingRoundTrip(new BigDecimal("98765.1" + repeat("0", 14)), new BigDecimal("98765.10"), roundDecimal15, dec15Scale);
    }

    private void assertSliceColumnMappingRoundTrip(BigDecimal correctValue, BigDecimal testInput, ColumnMapping readMapping, DecimalType decimalType)
    {
        try {
            int scale = decimalType.getScale();
            ResultSet rs = getMockResultSet(0, testInput);
            SliceReadFunction readFn = (SliceReadFunction) readMapping.getReadFunction();
            Slice testSlice = readFn.readSlice(rs, 0);
            Slice correctSlice = encodeUnscaledValue(correctValue.unscaledValue());

            String testVal = Decimals.toString(testSlice, scale);
            String correctVal = Decimals.toString(correctSlice, scale);
            if (!testSlice.equals(correctSlice)) {
                fail(format("Slice values do not match => expected: %s, sliceOutput: %s", correctVal, testVal));
            }
            if (!testVal.equals(correctVal)) {
                fail(format("Slice (text) values do not match => expected: %s, sliceOutput: %s", correctVal, testVal));
            }
            BigDecimal decVal = toBigDecimal(testSlice, scale);
            BigDecimal decCorrect = toBigDecimal(correctSlice, scale);
            if (!decVal.equals(decCorrect)) {
                fail(format("Slice (decimal) values do not match => expected: %s, sliceOutput: %s", decCorrect, decVal));
            }
        }
        catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }
}
