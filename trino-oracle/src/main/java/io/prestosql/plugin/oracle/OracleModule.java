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

import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.prestosql.plugin.jdbc.BaseJdbcConfig;
import io.prestosql.plugin.jdbc.ConnectionFactory;
import io.prestosql.plugin.jdbc.DriverConnectionFactory;
import io.prestosql.plugin.jdbc.ForBaseJdbc;
import io.prestosql.plugin.jdbc.JdbcClient;
import io.prestosql.plugin.jdbc.credential.CredentialProvider;
import oracle.jdbc.OracleDriver;

import java.util.Properties;

import static io.airlift.configuration.ConfigBinder.configBinder;

public class OracleModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        binder.bind(JdbcClient.class).annotatedWith(ForBaseJdbc.class).to(OracleClient.class).in(Scopes.SINGLETON);
        configBinder(binder).bindConfig(OracleConfig.class);
    }

    @Provides
    @Singleton
    @ForBaseJdbc
    public ConnectionFactory getConnectionFactory(BaseJdbcConfig config, CredentialProvider credentialProvider, OracleConfig oracleConfig)
    {
        // Extra oracle-specific connection properties
        Properties connectionProperties = new Properties();
        connectionProperties.setProperty("includeSynonyms", String.valueOf(oracleConfig.isSynonymsEnabled()));
        connectionProperties.setProperty("defaultRowPrefetch", String.valueOf(oracleConfig.getRowPrefetch()));
        connectionProperties.setProperty("oracle.jdbc.timezoneAsRegion", String.valueOf(oracleConfig.isTimezoneAsRegion()));

        return new DriverConnectionFactory(
                new OracleDriver(),
                config.getConnectionUrl(),
                connectionProperties,
                credentialProvider);
    }
}
