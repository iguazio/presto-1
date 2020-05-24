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

import io.prestosql.plugin.jdbc.ColumnMapping;
import io.prestosql.plugin.jdbc.StandardColumnMappings;
import io.prestosql.spi.type.DecimalType;
import io.prestosql.spi.type.Decimals;
import org.testng.annotations.Test;

import java.math.RoundingMode;
import java.sql.JDBCType;
import java.sql.Types;

import static io.prestosql.plugin.jdbc.StandardColumnMappings.decimalColumnMapping;
import static io.prestosql.plugin.jdbc.StandardColumnMappings.integerColumnMapping;
import static io.prestosql.spi.type.DecimalType.createDecimalType;
import static org.testng.Assert.assertEquals;

public class TestOracleNumberHandling
{
    // TODO test type-limit-exceed IGNORE, VARCHAR, FAIL
    // TODO test that scale >= precision, set precision to MAX_PRECISION
    //
    @Test
    void testReadMapCompare()
    {
        DecimalType type1 = createDecimalType(Decimals.MAX_PRECISION, 2);
        ColumnMapping read1 = OracleColumnMappings.roundDecimalColumnMapping(type1, RoundingMode.UP);
        DecimalType type2 = createDecimalType(Decimals.MAX_PRECISION, 2);
        ColumnMapping read2 = OracleColumnMappings.roundDecimalColumnMapping(type2, RoundingMode.UP);
        DecimalType type3 = createDecimalType(Decimals.MAX_SHORT_PRECISION + 1, 2);
        ColumnMapping read3 = OracleColumnMappings.roundDecimalColumnMapping(type3, RoundingMode.UP);

        TestOracleColumnMappings.assertColumnMappingEquals(read1, read2);
        TestOracleColumnMappings.assertColumnMappingNotEquals(read2, read3);

        // ensure our custom and default read mappings are not seen as equal, even with the same type parameters.
        ColumnMapping read4 = StandardColumnMappings.decimalColumnMapping(createDecimalType(30, 2), RoundingMode.UNNECESSARY);
        ColumnMapping read5 = OracleColumnMappings.roundDecimalColumnMapping(createDecimalType(30, 2), RoundingMode.UP);
        TestOracleColumnMappings.assertColumnMappingNotEquals(read4, read5);
    }

    @Test
    public void testUndefinedScaleDefault()
    {
        OracleConfig config = buildConfig();
        config.setNumberNullScaleType("DECIMAL");
        config.setNumberZeroScaleType("INTEGER");
        OracleNumberHandling numberHandling;

        numberHandling = buildNumberHandling(Decimals.MAX_PRECISION, 2, config);
        assertEquals(numberHandling.getMapToType(), JDBCType.DECIMAL);
    }

    @Test
    public void testDecimalMappingRatio()
    {
        int expectedScale;
        int precision;
        float ratioScale = 0.4f;
        OracleConfig config = buildConfig();
        config.setDecimalDefaultScale(OracleConfig.UNDEFINED_SCALE);
        config.setRatioDefaultScale(ratioScale);

        OracleNumberHandling numberHandling;
        ColumnMapping readExpected;
        RoundingMode round = config.getNumberRoundMode();

        // ensure we can get the ratio scale and its set properly.
        assertEquals(ratioScale, config.getRatioDefaultScale());

        // test (ratio) defined precision, defined scale (within limits)

        numberHandling = buildNumberHandling(30, 2, config);
        readExpected = decimalColumnMapping(createDecimalType(30, 2), RoundingMode.UNNECESSARY);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());

        // test (ratio) defined precision, undefined scale
        precision = 30;
        expectedScale = (int) (ratioScale * (float) precision);
        numberHandling = buildNumberHandling(precision, OracleConfig.UNDEFINED_SCALE, config);
        readExpected = OracleColumnMappings.roundDecimalColumnMapping(createDecimalType(precision, expectedScale), round);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());

        // test (ratio) undefined precision, defined scale
        expectedScale = 2;
        numberHandling = buildNumberHandling(0, expectedScale, config);
        readExpected = OracleColumnMappings.roundDecimalColumnMapping(createDecimalType(Decimals.MAX_PRECISION, expectedScale), round);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());

        // test (ratio) undefined precision, undefined scale
        expectedScale = (int) (ratioScale * (float) Decimals.MAX_PRECISION);
        numberHandling = buildNumberHandling(0, OracleConfig.UNDEFINED_SCALE, config);
        readExpected = OracleColumnMappings.roundDecimalColumnMapping(
                createDecimalType(Decimals.MAX_PRECISION, expectedScale),
                round);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());

        // test (ratio) scale >= precision, and scale <= MAX_PRECISION
        expectedScale = 24;
        numberHandling = buildNumberHandling(20, expectedScale, config);
        readExpected = decimalColumnMapping(createDecimalType(Decimals.MAX_PRECISION, expectedScale), RoundingMode.UNNECESSARY);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());

        // test (ratio) scale >= precision, and scale > MAX_PRECISION
        // in this case because the scale exceeds precision, precision will be set to MAX_PRECISION
        precision = 20;
        expectedScale = (int) (ratioScale * (float) Decimals.MAX_PRECISION);
        numberHandling = buildNumberHandling(precision, Decimals.MAX_PRECISION + 1, config);
        readExpected = OracleColumnMappings.roundDecimalColumnMapping(
                createDecimalType(Decimals.MAX_PRECISION, expectedScale),
                round);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());
    }

    @Test
    public void testDecimalMappingFixed()
    {
        int fixedScale = 8;
        OracleConfig config = buildConfig();
        config.setDecimalDefaultScale(fixedScale);

        OracleNumberHandling numberHandling;
        ColumnMapping readExpected;
        RoundingMode round = config.getNumberRoundMode();

        assertEquals(fixedScale, config.getDecimalDefaultScale());

        // test defined precision, defined scale (within limits)
        numberHandling = buildNumberHandling(30, 2, config);
        readExpected = decimalColumnMapping(createDecimalType(30, 2), RoundingMode.UNNECESSARY);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());

        // test defined precision, undefined scale
        numberHandling = buildNumberHandling(Decimals.MAX_PRECISION, OracleConfig.UNDEFINED_SCALE, config);
        readExpected = OracleColumnMappings.roundDecimalColumnMapping(createDecimalType(Decimals.MAX_PRECISION, fixedScale), round);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());

        // test undefined precision, defined scale
        fixedScale = 2;
        numberHandling = buildNumberHandling(0, fixedScale, config);
        readExpected = OracleColumnMappings.roundDecimalColumnMapping(createDecimalType(Decimals.MAX_PRECISION, fixedScale), round);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());

        // test undefined precision, undefined scale
        numberHandling = buildNumberHandling(0, OracleConfig.UNDEFINED_SCALE, config);
        readExpected = OracleColumnMappings.roundDecimalColumnMapping(
                createDecimalType(Decimals.MAX_PRECISION, config.getDecimalDefaultScale()),
                round);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());

        // test scale >= precision, and scale <= MAX_PRECISION

        fixedScale = 24;
        numberHandling = buildNumberHandling(20, fixedScale, config);
        readExpected = decimalColumnMapping(createDecimalType(Decimals.MAX_PRECISION, fixedScale), RoundingMode.UNNECESSARY);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());

        // test scale >= precision, and scale > MAX_PRECISION
        fixedScale = Decimals.MAX_PRECISION + 1;
        numberHandling = buildNumberHandling(20, fixedScale, config);
        readExpected = OracleColumnMappings.roundDecimalColumnMapping(
                createDecimalType(Decimals.MAX_PRECISION, config.getDecimalDefaultScale()),
                round);
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());
    }

    @Test
    void testDecimalZeroScaleAsInteger()
    {
        OracleConfig config = buildConfig();
        OracleNumberHandling numberHandling = buildNumberHandling(Decimals.MAX_PRECISION, 0, config);
        assertEquals(numberHandling.getMapToType(), JDBCType.INTEGER);
        ColumnMapping readExpected = integerColumnMapping();
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, numberHandling.getColumnMapping());
    }

    @Test
    void testDecimalNullScaleAsDecimalWithFixed()
    {
        int fixedScale = 8;

        OracleConfig config = buildConfig();
        config.setRatioDefaultScale(OracleConfig.UNDEFINED_SCALE);
        config.setDecimalDefaultScale(fixedScale);
        config.setNumberNullScaleType("DECIMAL");
        config.setNumberZeroScaleType("INTEGER");

        OracleNumberHandling numberHandling = buildNumberHandling(Decimals.MAX_PRECISION, OracleConfig.UNDEFINED_SCALE, config);
        assertEquals(numberHandling.getMapToType(), JDBCType.DECIMAL);
        DecimalType expectedType = createDecimalType(Decimals.MAX_PRECISION, fixedScale);
        ColumnMapping readExpected = OracleColumnMappings.roundDecimalColumnMapping(expectedType, config.getNumberRoundMode());
        ColumnMapping readCompare = numberHandling.getColumnMapping();
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, readCompare);

        // Ensure an undefined scale is treated as a default scale
        DecimalType decType = (DecimalType) readCompare.getType();
        assertEquals(decType.getScale(), fixedScale);
        assertEquals(decType.getPrecision(), Decimals.MAX_PRECISION);
    }

    @Test
    void testDecimalNullScaleAsDecimalWithRatio()
    {
        float ratioScale = 0.3f;
        int expectedScale = (int) ((float) Decimals.MAX_PRECISION * ratioScale);

        OracleConfig config = buildConfig();
        config.setDecimalDefaultScale(OracleConfig.UNDEFINED_SCALE);
        config.setRatioDefaultScale(ratioScale);
        config.setNumberNullScaleType("DECIMAL");
        config.setNumberZeroScaleType("INTEGER");

        OracleNumberHandling numberHandling = buildNumberHandling(Decimals.MAX_PRECISION, OracleConfig.UNDEFINED_SCALE, config);
        assertEquals(numberHandling.getMapToType(), JDBCType.DECIMAL);
        DecimalType expectedType = createDecimalType(Decimals.MAX_PRECISION, expectedScale);
        // when scale is undefined, the oracle round read mapping will be used.
        ColumnMapping readExpected = OracleColumnMappings.roundDecimalColumnMapping(expectedType, config.getNumberRoundMode());
        ColumnMapping readCompare = numberHandling.getColumnMapping();
        TestOracleColumnMappings.assertColumnMappingEquals(readExpected, readCompare);

        // Ensure an undefined scale is treated as a default scale
        DecimalType decType = (DecimalType) readCompare.getType();
        assertEquals(decType.getScale(), expectedScale);
        assertEquals(decType.getPrecision(), Decimals.MAX_PRECISION);
    }

    private OracleNumberHandling buildNumberHandling(int precision, int scale, OracleConfig config)
    {
        OracleJdbcTypeHandle typeHandle = new OracleJdbcTypeHandle(Types.NUMERIC, precision, scale);
        OracleNumberHandling numberHandling = new OracleNumberHandling(typeHandle, config);
        return numberHandling;
    }

    private OracleConfig buildConfig()
    {
        OracleConfig config = new OracleConfig()
                .setNumberExceedsLimitsMode("ROUND")
                .setNumberTypeDefault("DECIMAL")
                .setNumberZeroScaleType("INTEGER")
                .setNumberRoundMode("UP")
                .setDecimalDefaultScale(8);
        return config;
    }
}
