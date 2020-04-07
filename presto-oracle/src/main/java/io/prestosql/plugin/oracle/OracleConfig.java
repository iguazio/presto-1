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

import io.airlift.configuration.Config;
import io.airlift.units.Duration;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.type.Decimals;

import java.math.RoundingMode;
import java.sql.JDBCType;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static io.prestosql.spi.StandardErrorCode.CONFIGURATION_INVALID;

public class OracleConfig
{
    public static final JDBCType UNDEFINED_TYPE = JDBCType.OTHER;
    public static final int UNDEFINED_SCALE = OracleJdbcTypeHandle.UNDEFINED_SCALE;
    private static final JDBCType[] ALLOWED_NUMBER_DEFAULT_TYPES = {JDBCType.DECIMAL, JDBCType.DOUBLE, JDBCType.INTEGER, JDBCType.VARCHAR};
    private static final int MAX_DOUBLE_PRECISION = 15;
    private boolean synonymsEnabled;
    private UnsupportedTypeHandling typeStrategy = UnsupportedTypeHandling.IGNORE;
    private UnsupportedTypeHandling numberExceedsLimitsMode = UnsupportedTypeHandling.ROUND;
    private JDBCType numberTypeDefault = JDBCType.DECIMAL;
    private JDBCType numberZeroScaleType = UNDEFINED_TYPE;
    private JDBCType numberNullScaleType = UNDEFINED_TYPE;
    private RoundingMode numberRoundMode = RoundingMode.HALF_EVEN;
    private float ratioDefaultScale = 1.0f;
    private int decimalDefaultScale = UNDEFINED_SCALE;
    private int numberDefaultScale = UNDEFINED_SCALE;
    private int rowPrefetch = 50;
    private boolean autoReconnect;
    private Duration connectionTimeout = new Duration(30, TimeUnit.SECONDS);
    private int maxReconnects = 6;
    private boolean timezoneAsRegion; // Fix ORA-01882: timezone region not found; Set default to "false"

    public boolean isSynonymsEnabled()
    {
        return synonymsEnabled;
    }

    @Config("oracle.synonyms.enabled")
    public OracleConfig setSynonymsEnabled(boolean synonymsEnabled)
    {
        this.synonymsEnabled = synonymsEnabled;
        return this;
    }

    /**
     * oracle.row-prefetch
     */
    public int getRowPrefetch()
    {
        return rowPrefetch;
    }

    public boolean isTimezoneAsRegion()
    {
        return timezoneAsRegion;
    }

    /**
     * Handle timezone as region.
     *
     * @param enabled - default is "false"
     */
    @Config("oracle.jdbc.timezoneAsRegion")
    public OracleConfig setTimezoneAsRegion(boolean enabled)
    {
        timezoneAsRegion = enabled;
        return this;
    }

    /**
     * Controls the oracle driver option "defaultRowPrefetch"
     * Increasing this value will cause the minimum memory allocation per-query to be greater regardless of how many rows are returned.
     * Tuning this setting can greatly increase performance.
     *
     * @param prefetch
     * @return
     */
    @Config("oracle.row-prefetch")
    public OracleConfig setRowPrefetch(int prefetch)
    {
        this.rowPrefetch = prefetch;
        return this;
    }

    /**
     * Get unsupported-type.handling-strategy
     */
    public UnsupportedTypeHandling getUnsupportedTypeStrategy()
    {
        return typeStrategy;
    }

    /**
     * Determines how types that are not supported by Presto natively are handled.
     * Oracle supports custom user defined types, so this could be anything.
     *
     * @param typeStrategy
     * @return
     */
    @Config("unsupported-type.handling-strategy")
    public OracleConfig setUnsupportedTypeStrategy(String typeStrategy)
    {
        typeStrategy = typeStrategy.toUpperCase();
        this.typeStrategy = UnsupportedTypeHandling.valueOf(typeStrategy);
        if (typeStrategy.equals(UnsupportedTypeHandling.ROUND)) {
            throw new PrestoException(CONFIGURATION_INVALID, "ROUND is not a valid option for unsupported-type.handling-strategy");
        }
        return this;
    }

    /**
     * Get oracle.number.exceeds-limits
     */
    public UnsupportedTypeHandling getNumberExceedsLimitsMode()
    {
        return numberExceedsLimitsMode;
    }

    /**
     * Configure the driver to handle Oracle NUMBER type that exceeds Presto DECIMAL type size limits.
     * The default behavior is to ROUND.
     * <p>
     * If the rounding fails, or there is some other conversion exception, behavior falls-back to
     * "unsupported-type.handling-strategy"
     *
     * @param mode One of "ROUND", "CONVERT_TO_VARCHAR", "IGNORE", or "ERROR"
     * @return
     */
    @Config("oracle.number.exceeds-limits")
    public OracleConfig setNumberExceedsLimitsMode(String mode)
    {
        mode = mode.toUpperCase();
        this.numberExceedsLimitsMode = UnsupportedTypeHandling.valueOf(mode);
        return this;
    }

    // ------------------------------------------------------------------------
    // -- oracle.number.type fields -------------------------------------------

    /**
     * Get oracle.number.default-type
     */
    public JDBCType getNumberTypeDefault()
    {
        return numberTypeDefault;
    }

    @Config("oracle.number.default-type")
    public OracleConfig setNumberTypeDefault(String typeName)
    {
        numberTypeDefault = getNumericType(typeName);
        return this;
    }

    // ------------------------------------------------------------------------

    /**
     * Get oracle.number.zero-scale-type
     */
    public JDBCType getNumberZeroScaleType()
    {
        return numberZeroScaleType;
    }

    /**
     * Oracle NUMBER types with a scale set to ZERO should be treated as this datatype.
     * This overrides "oracle.number.type.default"
     *
     * @param typeName
     * @return
     */
    @Config("oracle.number.zero-scale-type")
    public OracleConfig setNumberZeroScaleType(String typeName)
    {
        if (typeName == null || typeName.isEmpty()) {
            numberZeroScaleType = UNDEFINED_TYPE;
        }
        else {
            numberZeroScaleType = getNumericType(typeName);
        }
        return this;
    }

    // ------------------------------------------------------------------------

    /**
     * Get oracle.number.null-scale-type
     */
    public JDBCType getNumberNullScaleType()
    {
        return numberNullScaleType;
    }

    /**
     * Oracle Number Handling - When scale is NULL, what type should be mapped to
     * Default behavior is to NOT map to any scale
     * This overrides "oracle.number.type.default"
     * <p>
     * Defaults to UNDEFINED_TYPE
     *
     * @param typeName
     * @return
     */
    @Config("oracle.number.null-scale-type")
    public OracleConfig setNumberNullScaleType(String typeName)
    {
        if (typeName == null || typeName.isEmpty()) {
            numberZeroScaleType = UNDEFINED_TYPE;
        }
        else {
            numberNullScaleType = getNumericType(typeName);
        }
        return this;
    }

    // ---------------------------------------------------------------------------
    // -- oracle.number.decimal fields -------------------------------------------

    /**
     * Get oracle.number.round-mode
     */
    public RoundingMode getNumberRoundMode()
    {
        if (getNumberExceedsLimitsMode().equals(UnsupportedTypeHandling.ROUND)
                && numberRoundMode.equals(RoundingMode.UNNECESSARY)) {
            throw new PrestoException(CONFIGURATION_INVALID, "'oracle.number.round-mode' must be set " +
                    "if 'oracle.number.exceeds-limits' is set to ROUND");
        }
        return numberRoundMode;
    }

    /**
     * When "oracle.number.exceeds-limits" is set to "ROUND" this must be set to a value other than UNNECESSARY.
     * This determines the method of rounding to conform values to Presto data type limits.
     *
     * @param roundingMode
     * @return
     */
    @Config("oracle.number.round-mode")
    public OracleConfig setNumberRoundMode(String roundingMode)
    {
        this.numberRoundMode = RoundingMode.valueOf(roundingMode);
        return this;
    }

    // ------------------------------------------------------------------------
    // -- oracle.number.decimal.default-scale fields --------------------------

    /**
     * Get oracle.number.default-scale.decimal
     */
    public int getDecimalDefaultScale()
    {
        return decimalDefaultScale;
    }

    /**
     * When NUMBER type scale is NULL and conversion to DECIMAL is set,
     * EXPLICITLY set the scale to a fixed integer value.
     * <p>
     * Note that scale cannot exceed Prestos maximum precision of 38
     *
     * @param numberScale
     * @return
     */
    @Config("oracle.number.default-scale.decimal")
    public OracleConfig setDecimalDefaultScale(int numberScale)
    {
        if (numberScale == UNDEFINED_SCALE) {
            this.decimalDefaultScale = UNDEFINED_SCALE;
            return this;
        }
        if (numberScale > Decimals.MAX_PRECISION) {
            String msg = String.format("oracle.number.default-scale.decimal (%d) exceeds Prestos max: %d", numberScale, Decimals.MAX_PRECISION);
            throw new PrestoException(CONFIGURATION_INVALID, msg);
        }
        this.decimalDefaultScale = numberScale;
        return this;
    }

    // ------------------------------------------------------------------------

    /**
     * Get oracle.number.default-scale.ratio
     */
    public float getRatioDefaultScale()
    {
        return ratioDefaultScale;
    }

    /**
     * When NUMBER type scale is NULL and conversion to DECIMAL is set,
     * use a ratio-based scale.
     * The scale will be set as a fraction of the precision.
     * <p>
     * Note that scale is only applied to data-types in Oracle in which scale has been left undefined.
     * <p>
     * Examples of (precision, scale) combinations and what they will be mapped to given an input
     * <p>
     * 0.5 => (40, null) => (38, 19)   // scale of 40 is capped at 38, half of 38 is 19.
     * 0.5 => (16, null) => (16, 8)
     * 0.5 => (16, 0)    => scale is set, ignored
     * 0.2 => (38, null) => (38, 8)
     * 0.3 => (14, null) => (14, 4)
     *
     * @param numberScale
     * @return
     */
    @Config("oracle.number.default-scale.ratio")
    public OracleConfig setRatioDefaultScale(float numberScale)
    {
        if (numberScale == (float) UNDEFINED_SCALE) {
            this.ratioDefaultScale = UNDEFINED_SCALE;
            return this;
        }
        if (getDecimalDefaultScale() != UNDEFINED_SCALE) {
            String msg = String.format("oracle.number.default-scale.decimal is set, and conflicts with oracle.number.default-scale.ratio");
            throw new PrestoException(CONFIGURATION_INVALID, msg);
        }
        if (numberScale > 1.0f) {
            String msg = String.format("oracle.number.default-scale.ratio (%f) exceeds 1.0", numberScale, Decimals.MAX_PRECISION);
            throw new PrestoException(CONFIGURATION_INVALID, msg);
        }
        this.ratioDefaultScale = numberScale;
        return this;
    }

    // ------------------------------------------------------------------------

    /**
     * Get oracle.number.default-scale.double
     */
    public int getDoubleDefaultScale()
    {
        return numberDefaultScale;
    }

    /**
     * When NUMBER type scale is NULL and conversion to DECIMAL is set,
     * EXPLICITLY set the scale to a fixed integer value.
     * <p>
     * Note that scale cannot exceed Prestos maximum precision of 38
     *
     * @param doubleScale
     * @return
     */
    @Config("oracle.number.default-scale.double")
    public OracleConfig setDoubleDefaultScale(int doubleScale)
    {
        if (doubleScale == UNDEFINED_SCALE) {
            this.numberDefaultScale = UNDEFINED_SCALE;
            return this;
        }
        if (doubleScale > MAX_DOUBLE_PRECISION) {
            String msg = String.format("oracle.number.default-scale.double (%d) exceeds javas Double type max: %d", doubleScale, MAX_DOUBLE_PRECISION);
            throw new PrestoException(CONFIGURATION_INVALID, msg);
        }
        this.numberDefaultScale = doubleScale;
        return this;
    }

    // ------------------------------------------------------------------------

    /**
     * Get JDBC numeric type or throw PrestoException if typeName passed is unsupported
     *
     * @param typeName String JDBCType name (case does not matter) - DECIMAL, INTEGER, DOUBLE
     */
    private JDBCType getNumericType(String typeName)
    {
        typeName = typeName.toUpperCase();
        JDBCType jdbcType = JDBCType.valueOf(typeName);
        boolean isAllowedType = Arrays.stream(ALLOWED_NUMBER_DEFAULT_TYPES).anyMatch(jdbcType::equals);
        if (!isAllowedType) {
            List<String> allowedVals = Arrays.stream(ALLOWED_NUMBER_DEFAULT_TYPES)
                    .map(JDBCType::getName)
                    .collect(Collectors.toList());

            throw new PrestoException(CONFIGURATION_INVALID,
                    String.format("'%s' is not valid for oracle.number.default-type %nAllowed Values: %s", typeName, allowedVals));
        }
        return jdbcType;
    }

    public boolean isAutoReconnect()
    {
        return autoReconnect;
    }

    @Config("oracle.auto-reconnect")
    public OracleConfig setAutoReconnect(boolean autoReconnect)
    {
        this.autoReconnect = autoReconnect;
        return this;
    }

    public Duration getConnectionTimeout()
    {
        return connectionTimeout;
    }

    @Config("oracle.connection-timeout")
    public OracleConfig setConnectionTimeout(Duration durationInSeconds)
    {
        this.connectionTimeout = durationInSeconds;
        return this;
    }

    public int getMaxReconnects()
    {
        return maxReconnects;
    }

    @Config("oracle.max-reconnects")
    public OracleConfig setMaxReconnects(int maxReconnects)
    {
        this.maxReconnects = maxReconnects;
        return this;
    }
}
