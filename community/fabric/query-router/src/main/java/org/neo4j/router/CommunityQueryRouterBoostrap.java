/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j Enterprise Edition. The included source
 * code can be redistributed and/or modified under the terms of the
 * GNU AFFERO GENERAL PUBLIC LICENSE Version 3
 * (http://www.fsf.org/licensing/licenses/agpl-3.0.html) with the
 * Commons Clause, as found in the associated LICENSE.txt file.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * Neo4j object code can be licensed independently from the source
 * under separate terms from the AGPL. Inquiries can be directed to:
 * licensing@neo4j.com
 *
 * More information is also available at:
 * https://neo4j.com/licensing/
 */
package org.neo4j.router;

import static org.neo4j.cypher.internal.tracing.CompilationTracer.NO_COMPILATION_TRACING;
import static org.neo4j.scheduler.Group.CYPHER_CACHE;
import static org.neo4j.scheduler.JobMonitoringParams.systemJob;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.neo4j.bolt.dbapi.BoltGraphDatabaseManagementServiceSPI;
import org.neo4j.bolt.dbapi.BoltGraphDatabaseServiceSPI;
import org.neo4j.bolt.dbapi.BoltTransaction;
import org.neo4j.bolt.protocol.common.message.AccessMode;
import org.neo4j.bolt.protocol.common.message.request.connection.RoutingContext;
import org.neo4j.collection.Dependencies;
import org.neo4j.configuration.Config;
import org.neo4j.cypher.internal.PreParser;
import org.neo4j.cypher.internal.cache.CacheTracer;
import org.neo4j.cypher.internal.cache.ExecutorBasedCaffeineCacheFactory;
import org.neo4j.cypher.internal.compiler.CypherParsing;
import org.neo4j.cypher.internal.compiler.CypherParsingConfig;
import org.neo4j.cypher.internal.compiler.CypherPlannerConfiguration;
import org.neo4j.cypher.internal.config.CypherConfiguration;
import org.neo4j.dbms.api.DatabaseManagementService;
import org.neo4j.dbms.database.DatabaseContext;
import org.neo4j.dbms.database.DatabaseContextProvider;
import org.neo4j.exceptions.InvalidSemanticsException;
import org.neo4j.fabric.bookmark.LocalGraphTransactionIdTracker;
import org.neo4j.fabric.bootstrap.CommonQueryRouterBoostrap;
import org.neo4j.fabric.executor.Location;
import org.neo4j.fabric.transaction.ErrorReporter;
import org.neo4j.internal.kernel.api.connectioninfo.ClientConnectionInfo;
import org.neo4j.internal.kernel.api.security.LoginContext;
import org.neo4j.kernel.api.KernelTransaction;
import org.neo4j.kernel.database.DatabaseReference;
import org.neo4j.kernel.database.DatabaseReferenceRepository;
import org.neo4j.kernel.impl.query.QueryExecutionConfiguration;
import org.neo4j.kernel.lifecycle.LifeSupport;
import org.neo4j.logging.internal.LogService;
import org.neo4j.monitoring.Monitors;
import org.neo4j.router.impl.CommunityLocationService;
import org.neo4j.router.impl.QueryRouterImpl;
import org.neo4j.router.impl.bolt.QueryRouterBoltSpi;
import org.neo4j.router.impl.query.DefaultDatabaseReferenceResolver;
import org.neo4j.router.impl.query.parsing.QueryTargetCache;
import org.neo4j.router.impl.query.parsing.StandardQueryTargetParser;
import org.neo4j.router.impl.transaction.database.LocalDatabaseTransactionFactory;
import org.neo4j.router.location.LocationService;
import org.neo4j.router.transaction.DatabaseTransactionFactory;
import org.neo4j.router.transaction.RoutingInfo;
import org.neo4j.scheduler.JobScheduler;
import org.neo4j.time.SystemNanoClock;

public class CommunityQueryRouterBoostrap extends CommonQueryRouterBoostrap {

    private final LogService logService;
    private final DatabaseContextProvider<? extends DatabaseContext> databaseProvider;
    private final DatabaseReferenceRepository databaseReferenceRepo;

    public CommunityQueryRouterBoostrap(
            LifeSupport lifeSupport,
            Dependencies dependencies,
            LogService logService,
            DatabaseContextProvider<? extends DatabaseContext> databaseProvider,
            DatabaseReferenceRepository databaseReferenceRepo) {
        super(lifeSupport, dependencies, databaseProvider);
        this.logService = logService;
        this.databaseProvider = databaseProvider;
        this.databaseReferenceRepo = databaseReferenceRepo;
    }

    public BoltGraphDatabaseManagementServiceSPI bootstrapServices(
            DatabaseManagementService databaseManagementService) {
        bootstrapCommonServices(databaseManagementService, logService);
        return createBoltDatabaseManagementServiceProvider();
    }

    protected BoltGraphDatabaseManagementServiceSPI getCompositeDatabaseStack() {
        return (databaseName, memoryTracker) -> new BoltGraphDatabaseServiceSPI() {

            @Override
            public BoltTransaction beginTransaction(
                    KernelTransaction.Type type,
                    LoginContext loginContext,
                    ClientConnectionInfo clientInfo,
                    List<String> bookmarks,
                    Duration txTimeout,
                    AccessMode accessMode,
                    Map<String, Object> txMetadata,
                    RoutingContext routingContext,
                    QueryExecutionConfiguration queryExecutionConfiguration) {
                // If a piece of code tries to use this in Community edition, it means a bug
                throw new InvalidSemanticsException("Composite database is not supported in Community Edition");
            }

            @Override
            public DatabaseReference getDatabaseReference() {
                // If a piece of code tries to use this in Community edition, it means a bug
                throw new InvalidSemanticsException("Composite database is not supported in Community Edition");
            }
        };
    }

    protected LocationService createLocationService(RoutingInfo routingInfo) {
        return new CommunityLocationService();
    }

    protected DatabaseTransactionFactory<Location.Remote> createRemoteDatabaseTransactionFactory() {
        // If a piece of code tries to use this in Community edition, it means a bug
        return (location, transactionInfo, bookmarkManager) -> {
            throw new IllegalStateException("Remote transactions are not supported in Community Edition");
        };
    }

    protected BoltGraphDatabaseManagementServiceSPI createBoltDatabaseManagementServiceProvider() {
        var config = resolve(Config.class);
        var cypherConfig = CypherConfiguration.fromConfig(config);
        var jobScheduler = resolve(JobScheduler.class);
        var monitoredExecutor = jobScheduler.monitoredJobExecutor(CYPHER_CACHE);
        var monitors = resolve(Monitors.class);
        var cacheFactory = new ExecutorBasedCaffeineCacheFactory(
                job -> monitoredExecutor.execute(systemJob("Query plan cache maintenance"), job));
        var targetCache = new QueryTargetCache(
                cacheFactory,
                cypherConfig.queryCacheSize(),
                monitors.newMonitor(CacheTracer.class, "cypher.cache.target"));
        var preParser = new PreParser(cypherConfig);
        CypherPlannerConfiguration plannerConfig =
                CypherPlannerConfiguration.fromCypherConfiguration(cypherConfig, config, true);
        var parsing = new CypherParsing(null, CypherParsingConfig.fromCypherPlannerConfiguration(plannerConfig));
        DefaultDatabaseReferenceResolver databaseReferenceResolver =
                new DefaultDatabaseReferenceResolver(databaseReferenceRepo);

        var transactionIdTracker = resolve(LocalGraphTransactionIdTracker.class);
        return new QueryRouterBoltSpi.DatabaseManagementService(
                new QueryRouterImpl(
                        config,
                        databaseReferenceResolver,
                        this::createLocationService,
                        new StandardQueryTargetParser(
                                targetCache, preParser, parsing, NO_COMPILATION_TRACING, () -> {}),
                        new LocalDatabaseTransactionFactory(databaseProvider, transactionIdTracker),
                        createRemoteDatabaseTransactionFactory(),
                        new ErrorReporter(this.logService),
                        resolve(SystemNanoClock.class),
                        transactionIdTracker),
                databaseReferenceResolver,
                getCompositeDatabaseStack());
    }

    @Override
    protected <T> T resolve(Class<T> type) {
        return dependencies.resolveDependency(type);
    }
}
