/*
 * Copyright (C) 2017 Dremio Corporation
 *
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
package com.dremio.dac.api;

import static com.dremio.service.accelerator.AccelerationServiceImpl.STARTTIME_ORDERING;
import static com.dremio.service.namespace.DatasetIndexKeys.DATASET_SOURCES;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nullable;
import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.inject.Provider;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.dremio.dac.annotations.APIResource;
import com.dremio.dac.annotations.Secured;
import com.dremio.dac.service.source.SourceService;
import com.dremio.datastore.SearchQueryUtils;
import com.dremio.datastore.SearchTypes;
import com.dremio.exec.proto.CoordinationProtos;
import com.dremio.exec.server.SabotContext;
import com.dremio.service.accelerator.AccelerationService;
import com.dremio.service.accelerator.AccelerationUtils;
import com.dremio.service.accelerator.proto.Acceleration;
import com.dremio.service.accelerator.proto.Layout;
import com.dremio.service.accelerator.proto.Materialization;
import com.dremio.service.accelerator.proto.MaterializationState;
import com.dremio.service.jobs.JobTypeStats;
import com.dremio.service.jobs.JobsService;
import com.dremio.service.namespace.NamespaceException;
import com.dremio.service.namespace.NamespaceKey;
import com.dremio.service.namespace.NamespaceService;
import com.dremio.service.namespace.proto.EntityId;
import com.dremio.service.namespace.source.proto.SourceConfig;
import com.dremio.service.namespace.source.proto.SourceType;
import com.dremio.service.users.UserService;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;

/**
 * Resource for information about sources.
 */
@APIResource
@Secured
@Path("/cluster/stats")
@Consumes(APPLICATION_JSON)
@Produces(APPLICATION_JSON)
public class ClusterStatsResource {
  private static final Logger logger = LoggerFactory.getLogger(ClusterStatsResource.class);

  private final Provider<SabotContext> context;
  private final SourceService sourceService;
  private final NamespaceService namespaceService;
  private JobsService jobsService;
  private AccelerationService accelerationService;
  private UserService userService;

  @Inject
  public ClusterStatsResource(Provider<SabotContext> context, SourceService sourceService, NamespaceService namespaceService, JobsService jobsService, AccelerationService accelerationService, UserService userService) {
    this.context = context;
    this.sourceService = sourceService;
    this.namespaceService = namespaceService;
    this.jobsService = jobsService;
    this.accelerationService = accelerationService;
    this.userService = userService;
  }

  @GET
  @RolesAllowed({"admin", "user"})
  public ClusterStats getStats() {
    ClusterStats result = new ClusterStats();

    // node stats
    result.setExecutors(processEndPoints(this.context.get().getExecutors()));
    result.setCoordinators(processEndPoints(this.context.get().getCoordinators()));

    // source stats
    List<SourceStats> sources = new ArrayList<>();

    // optimize vds count queries by only going one to the index with a list of queries
    List<SearchTypes.SearchQuery> vdsQueries = new ArrayList<>();

    for (SourceConfig sourceConfig : sourceService.getSources()) {
      int pdsCount = -1;
      try {
        pdsCount = namespaceService.getAllDatasetsCount(new NamespaceKey(sourceConfig.getName()));
      } catch (NamespaceException e) {
        logger.warn("Failed to get dataset count", e);
      }

      SourceStats source = new SourceStats(sourceConfig.getId(), sourceConfig.getType(), pdsCount);

      vdsQueries.add(SearchQueryUtils.newTermQuery(DATASET_SOURCES, sourceConfig.getName()));

      sources.add(source);
    }

    try {
      List<Integer> counts = namespaceService.getCounts(vdsQueries.toArray(new SearchTypes.SearchQuery[vdsQueries.size()]));
      for (int i = 0; i < counts.size(); i++) {
        sources.get(i).setVdsCount(counts.get(i));
      }
    } catch (NamespaceException e) {
      logger.warn("Failed to get vds counts", e);
    }

    result.setSources(sources);

    // job stats
    List<JobTypeStats> jobStats = jobsService.getJobStats(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(7), System.currentTimeMillis());
    result.setJobStats(jobStats);

    // acceleration stats
    Iterable<Acceleration> accelerations = accelerationService.getAllAccelerations();

    int activeReflections = 0;
    int errorReflections = 0;
    Long latestReflectionsSizeBytes = 0L;
    final long[] totalReflectionSizeBytes = {0L};
    int incrementalReflectionCount = 0;

    for (Acceleration acceleration : accelerations) {
      Iterable<Layout> layouts = AccelerationUtils.getAllLayouts(acceleration);

      for (Layout layout : layouts) {
        Iterable<Materialization> materializations = accelerationService.getMaterializations(layout.getId());

        // we want the last completed/failed materialization for our stats
        final Set<MaterializationState> states =
          EnumSet.of(MaterializationState.DONE, MaterializationState.FAILED);

        List<Materialization> list = STARTTIME_ORDERING
          .greatestOf(FluentIterable
            .from(materializations)
            .filter(new Predicate<Materialization>() {
              @Override
              public boolean apply(@Nullable final Materialization materialization) {
                totalReflectionSizeBytes[0] += materialization.getMetrics().getFootprint();

                if (!states.contains(materialization.getState())) {
                  return false;
                }
                return true;
              }
            }), 1);

        if (!list.isEmpty()) {
          if (layout.getIncremental()) {
            incrementalReflectionCount++;
          }

          switch (list.get(0).getState()) {
            case DONE:
              latestReflectionsSizeBytes += list.get(0).getMetrics().getFootprint();
              activeReflections++;
              break;

            case FAILED:
              errorReflections++;
              break;

            default:
              break;
          }
        }
      }
    }

    ReflectionStats reflectionStats = new ReflectionStats(activeReflections, errorReflections, totalReflectionSizeBytes[0], latestReflectionsSizeBytes, incrementalReflectionCount);
    result.setReflectionStats(reflectionStats);

    return result;
  }

  private List<EndPoint> processEndPoints(Collection<CoordinationProtos.NodeEndpoint> endpoints) {
    ArrayList<EndPoint> result = new ArrayList<>();
    for (CoordinationProtos.NodeEndpoint endpoint : endpoints) {
      EndPoint endPoint = new EndPoint(endpoint.getAddress(), endpoint.getAvailableCores(), endpoint.getMaxDirectMemory(), endpoint.getStartTime());
      result.add(endPoint);
    }

    return result;
  }

  /**
   * Endpoint Stats
   */
  public static class EndPoint {
    private final String address;
    private final int availableCores;
    private final long maxDirectMemoryBytes;
    private final long startedAt;

    @JsonCreator
    public EndPoint(
        @JsonProperty("address") String address,
        @JsonProperty("availableCores") int availableCores,
        @JsonProperty("maxDirectMemoryBytes") long maxDirectMemoryBytes,
        @JsonISODateTime
        @JsonProperty("startedAt") long startedAt) {
      this.address = address;
      this.availableCores = availableCores;
      this.maxDirectMemoryBytes = maxDirectMemoryBytes;
      this.startedAt = startedAt;
    }

    public String getAddress() {
      return address;
    }

    public int getAvailableCores() {
      return availableCores;
    }

    public long getMaxDirectMemoryBytes() {
      return maxDirectMemoryBytes;
    }

    public long getStartedAt() {
      return startedAt;
    }
  }

  /**
   * Source Stats
   */
  public static class SourceStats {
    private final EntityId id;
    private final SourceType type;
    private final int pdsCount;
    private int vdsCount;

    @JsonCreator
    public SourceStats(
        @JsonProperty("id") EntityId id,
        @JsonProperty("type") SourceType type,
        @JsonProperty("pdsCount") int pdsCount,
        @JsonProperty("vdsCount") int vdsCount) {
      this.id = id;
      this.type = type;
      this.pdsCount = pdsCount;
      this.vdsCount = vdsCount;
    }

    public SourceStats(EntityId id, SourceType type, int pdsCount) {
      this.id = id;
      this.type = type;
      this.pdsCount = pdsCount;
      this.vdsCount = -1;
    }

    public String getId() {
      return id.getId();
    }

    public String getType() {
      return type.name();
    }

    public int getPdsCount() {
      return pdsCount;
    }

    public int getVdsCount() {
      return vdsCount;
    }

    public void setVdsCount(int vdsCount) {
      this.vdsCount = vdsCount;
    }
  }

  /**
   * Reflection Stats
   */
  public static class ReflectionStats {
    private final int activeReflections;
    private final int errorReflections;
    private final long totalReflectionSizeBytes;
    private final long latestReflectionsSizeBytes;
    private final int incrementalReflectionCount;

    @JsonCreator
    public ReflectionStats(
        @JsonProperty("activeReflections") int activeReflections,
        @JsonProperty("errorReflections") int errorReflections,
        @JsonProperty("totalReflectionSizeBytes") long totalReflectionSizeBytes,
        @JsonProperty("latestReflectionsSizeBytes") long latestReflectionsSizeBytes,
        @JsonProperty("incrementalReflectionCount") int incrementalReflectionCount) {
      this.activeReflections = activeReflections;
      this.errorReflections = errorReflections;
      this.totalReflectionSizeBytes = totalReflectionSizeBytes;
      this.latestReflectionsSizeBytes = latestReflectionsSizeBytes;
      this.incrementalReflectionCount = incrementalReflectionCount;
    }

    public int getActiveReflections() {
      return activeReflections;
    }

    public int getErrorReflections() {
      return errorReflections;
    }

    public long getTotalReflectionSizeBytes() {
      return totalReflectionSizeBytes;
    }

    public long getLatestReflectionsSizeBytes() {
      return latestReflectionsSizeBytes;
    }

    public int getIncrementalReflectionCount() {
      return incrementalReflectionCount;
    }
  }

  /**
   * Cluster Stats
   */
  public static class ClusterStats {
    private List<EndPoint> coordinators;
    private List<EndPoint> executors;
    private List<SourceStats> sources;
    private List<JobTypeStats> jobStats;
    private ReflectionStats reflectionStats;

    public ClusterStats() {
    }

    @JsonCreator
    public ClusterStats(
        @JsonProperty("coordinators") List<EndPoint> coordinators,
        @JsonProperty("executors") List<EndPoint> executors,
        @JsonProperty("sources") List<SourceStats> sources,
        @JsonProperty("jobStats") List<JobTypeStats> jobStats,
        @JsonProperty("reflectionStats") ReflectionStats reflectionStats) {
      this.coordinators = coordinators;
      this.executors = executors;
      this.sources = sources;
      this.jobStats = jobStats;
      this.reflectionStats = reflectionStats;
    }

    public List<EndPoint> getCoordinators() {
      return coordinators;
    }

    public void setCoordinators(List<EndPoint> coordinators) {
      this.coordinators = coordinators;
    }

    public List<EndPoint> getExecutors() {
      return executors;
    }

    public void setExecutors(List<EndPoint> executors) {
      this.executors = executors;
    }

    public List<SourceStats> getSources() {
      return sources;
    }

    public void setSources(List<SourceStats> sources) {
      this.sources = sources;
    }

    public List<JobTypeStats> getJobStats() {
      return jobStats;
    }

    public void setJobStats(List<JobTypeStats> jobStats) {
      this.jobStats = jobStats;
    }

    public ReflectionStats getReflectionStats() {
      return reflectionStats;
    }

    public void setReflectionStats(ReflectionStats reflectionStats) {
      this.reflectionStats = reflectionStats;
    }
  }
}
