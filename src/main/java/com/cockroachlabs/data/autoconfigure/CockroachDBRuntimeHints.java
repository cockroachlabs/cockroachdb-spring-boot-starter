package com.cockroachlabs.data.autoconfigure;

import java.util.Set;
import com.cockroachlabs.data.ai.DistanceMetric;
import com.cockroachlabs.data.ai.VectorSearchResult;
import com.cockroachlabs.data.ai.VectorSearchTemplate;
import com.cockroachlabs.data.ai.annotation.SemanticSearch;
import com.cockroachlabs.data.ai.annotation.VectorColumn;
import com.cockroachlabs.data.ai.annotation.VectorSearch;
import com.cockroachlabs.data.ai.aop.SemanticSearchAspect;
import com.cockroachlabs.data.ai.aop.VectorSearchAspect;
import com.cockroachlabs.data.ai.metrics.AIMetrics;
import com.cockroachlabs.data.annotation.CockroachDB;
import com.cockroachlabs.data.annotation.ComputedColumn;
import com.cockroachlabs.data.annotation.CrdbRegion;
import com.cockroachlabs.data.annotation.FollowerRead;
import com.cockroachlabs.data.annotation.GlobalTable;
import com.cockroachlabs.data.annotation.RegionalByRow;
import com.cockroachlabs.data.aop.CockroachDBAspect;
import com.cockroachlabs.data.aop.FollowerReadAspect;
import com.cockroachlabs.data.cdc.CDCEnvelope;
import com.cockroachlabs.data.cdc.CDCEvent;
import com.cockroachlabs.data.cdc.CDCOperationType;
import com.cockroachlabs.data.cdc.CDCPayload;
import com.cockroachlabs.data.cdc.annotation.CockroachDBCDC;
import com.cockroachlabs.data.cdc.handler.CDCHandlerRegistry;
import com.cockroachlabs.data.cdc.handler.CDCMethodInvoker;
import com.cockroachlabs.data.cdc.handler.CDCWebhookController;
import com.cockroachlabs.data.cdc.metrics.CDCMetrics;
import com.cockroachlabs.data.ddl.ComputedColumnHelper;
import com.cockroachlabs.data.metrics.CockroachDBMetrics;
import com.cockroachlabs.data.continuum.VirtualClusterContext;
import com.cockroachlabs.data.continuum.aegis.AegisClient;
import com.cockroachlabs.data.continuum.aegis.AegisExhaust;
import com.cockroachlabs.data.continuum.aegis.AppEvent;
import com.cockroachlabs.data.continuum.aegis.Finding;
import com.cockroachlabs.data.continuum.aegis.HttpAegisClient;
import com.cockroachlabs.data.continuum.aegis.Recommendation;
import com.cockroachlabs.data.continuum.aegis.SimulatedAegisClient;
import com.cockroachlabs.data.continuum.agent.AgentTool;
import com.cockroachlabs.data.continuum.agent.CrdbAgent;
import com.cockroachlabs.data.continuum.agent.CrdbOpsTools;
import com.cockroachlabs.data.continuum.agent.ToolDescriptor;
import com.cockroachlabs.data.continuum.agent.ToolRegistry;
import com.cockroachlabs.data.continuum.annotation.VirtualCluster;
import com.cockroachlabs.data.continuum.annotation.Workload;
import com.cockroachlabs.data.continuum.aop.VirtualClusterAspect;
import com.cockroachlabs.data.continuum.autoconfigure.CockroachDBContinuumProperties;
import com.cockroachlabs.data.continuum.plenum.StorageInsights;
import com.cockroachlabs.data.continuum.plenum.TableStorage;
import com.cockroachlabs.data.multiregion.RegionContext;
import com.cockroachlabs.data.multiregion.RegionResolver;
import com.cockroachlabs.data.multiregion.RegionalByRowListener;
import com.cockroachlabs.data.template.CockroachDBTemplate;
import com.cockroachlabs.data.test.CockroachDBConcurrencyRunner;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Registers GraalVM native image hints for all CockroachDB starter components.
 *
 * <p>Covers: annotations (reflection), AOP aspects (proxies), CDC DTOs
 * (serialization), configuration properties (binding), and template classes.
 */
public class CockroachDBRuntimeHints implements RuntimeHintsRegistrar {

    private static final Set<Class<?>> ANNOTATIONS = Set.of(
            CockroachDB.class,
            FollowerRead.class,
            RegionalByRow.class,
            GlobalTable.class,
            CrdbRegion.class,
            ComputedColumn.class,
            CockroachDBCDC.class,
            VectorSearch.class,
            SemanticSearch.class,
            VectorColumn.class
    );

    private static final Set<Class<?>> ASPECTS = Set.of(
            CockroachDBAspect.class,
            FollowerReadAspect.class,
            VectorSearchAspect.class,
            SemanticSearchAspect.class
    );

    private static final Set<Class<?>> CDC_DTOS = Set.of(
            CDCEnvelope.class,
            CDCEvent.class,
            CDCPayload.class,
            CDCOperationType.class
    );

    private static final Set<Class<?>> SERVICES = Set.of(
            CockroachDBTemplate.class,
            CockroachDBMetrics.class,
            CockroachDBMetrics.InMemory.class,
            CockroachDBProperties.class,
            CockroachDBProperties.Retry.class,
            CockroachDBProperties.Region.class,
            VectorSearchTemplate.class,
            VectorSearchResult.class,
            DistanceMetric.class,
            AIMetrics.class,
            CDCMetrics.class,
            CDCHandlerRegistry.class,
            CDCMethodInvoker.class,
            CDCWebhookController.class,
            ComputedColumnHelper.class,
            CockroachDBConcurrencyRunner.class,
            RegionContext.class,
            RegionResolver.class,
            RegionalByRowListener.class
    );

    private static final Set<Class<?>> CONTINUUM = Set.of(
            // annotations
            VirtualCluster.class,
            Workload.class,
            Workload.Priority.class,
            AgentTool.class,
            // aspect
            VirtualClusterAspect.class,
            // vCluster + Plenum
            VirtualClusterContext.class,
            StorageInsights.class,
            TableStorage.class,
            // Aegis
            AegisClient.class,
            SimulatedAegisClient.class,
            HttpAegisClient.class,
            AegisExhaust.class,
            Finding.class,
            Recommendation.class,
            AppEvent.class,
            // agent / MCP
            CrdbAgent.class,
            ToolRegistry.class,
            ToolDescriptor.class,
            CrdbOpsTools.class,
            // properties
            CockroachDBContinuumProperties.class,
            CockroachDBContinuumProperties.Aegis.class,
            CockroachDBContinuumProperties.Agent.class
    );

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        var reflection = hints.reflection();

        for (var clazz : ANNOTATIONS) {
            reflection.registerType(clazz, MemberCategory.values());
        }

        for (var clazz : ASPECTS) {
            reflection.registerType(clazz, MemberCategory.values());
        }

        for (var clazz : CDC_DTOS) {
            reflection.registerType(clazz,
                    MemberCategory.INVOKE_DECLARED_CONSTRUCTORS,
                    MemberCategory.INVOKE_DECLARED_METHODS,
                    MemberCategory.DECLARED_FIELDS);
        }

        for (var clazz : SERVICES) {
            reflection.registerType(clazz, MemberCategory.values());
        }

        for (var clazz : CONTINUUM) {
            reflection.registerType(clazz, MemberCategory.values());
        }

        hints.proxies()
                .registerJdkProxy(RegionResolver.class)
                .registerJdkProxy(CockroachDBMetrics.class)
                .registerJdkProxy(CDCMetrics.class)
                .registerJdkProxy(AIMetrics.class);
    }
}
