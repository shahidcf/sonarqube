/*
 * SonarQube
 * Copyright (C) 2009-2021 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.ce.task.projectanalysis.step;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nonnull;
import org.sonar.ce.task.projectanalysis.component.Component;
import org.sonar.ce.task.projectanalysis.component.CrawlerDepthLimit;
import org.sonar.ce.task.projectanalysis.component.DepthTraversalTypeAwareCrawler;
import org.sonar.ce.task.projectanalysis.component.TreeRootHolder;
import org.sonar.ce.task.projectanalysis.component.TypeAwareVisitorAdapter;
import org.sonar.ce.task.projectanalysis.measure.BestValueOptimization;
import org.sonar.ce.task.projectanalysis.measure.Measure;
import org.sonar.ce.task.projectanalysis.measure.MeasureRepository;
import org.sonar.ce.task.projectanalysis.measure.MeasureToMeasureDto;
import org.sonar.ce.task.projectanalysis.metric.Metric;
import org.sonar.ce.task.projectanalysis.metric.MetricRepository;
import org.sonar.ce.task.step.ComputationStep;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.measure.LiveMeasureDto;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableSet;
import static org.sonar.api.measures.CoreMetrics.FILE_COMPLEXITY_DISTRIBUTION_KEY;
import static org.sonar.api.measures.CoreMetrics.FUNCTION_COMPLEXITY_DISTRIBUTION_KEY;
import static org.sonar.ce.task.projectanalysis.component.ComponentVisitor.Order.PRE_ORDER;

public class PersistLiveMeasuresStep implements ComputationStep {

  /**
   * List of metrics that should not be persisted on file measure.
   */
  private static final Set<String> NOT_TO_PERSIST_ON_FILE_METRIC_KEYS = Set.of(FILE_COMPLEXITY_DISTRIBUTION_KEY, FUNCTION_COMPLEXITY_DISTRIBUTION_KEY);

  private final DbClient dbClient;
  private final MetricRepository metricRepository;
  private final MeasureToMeasureDto measureToMeasureDto;
  private final TreeRootHolder treeRootHolder;
  private final MeasureRepository measureRepository;

  public PersistLiveMeasuresStep(DbClient dbClient, MetricRepository metricRepository, MeasureToMeasureDto measureToMeasureDto,
    TreeRootHolder treeRootHolder, MeasureRepository measureRepository) {
    this.dbClient = dbClient;
    this.metricRepository = metricRepository;
    this.measureToMeasureDto = measureToMeasureDto;
    this.treeRootHolder = treeRootHolder;
    this.measureRepository = measureRepository;
  }

  @Override
  public String getDescription() {
    return "Persist live measures";
  }

  @Override
  public void execute(ComputationStep.Context context) {
    boolean supportUpsert = dbClient.getDatabase().getDialect().supportsUpsert();
    try (DbSession dbSession = dbClient.openSession(true)) {
      Component root = treeRootHolder.getRoot();
      MeasureVisitor visitor = new MeasureVisitor(dbSession, supportUpsert);
      new DepthTraversalTypeAwareCrawler(visitor).visit(root);
      dbSession.commit();
      context.getStatistics()
        .add("insertsOrUpdates", visitor.insertsOrUpdates);
    }
  }

  private class MeasureVisitor extends TypeAwareVisitorAdapter {
    private final DbSession dbSession;
    private final boolean supportUpsert;
    private int insertsOrUpdates = 0;

    private MeasureVisitor(DbSession dbSession, boolean supportUpsert) {
      super(CrawlerDepthLimit.LEAVES, PRE_ORDER);
      this.supportUpsert = supportUpsert;
      this.dbSession = dbSession;
    }

    @Override
    public void visitAny(Component component) {
      List<String> metricUuids = new ArrayList<>();
      Map<String, Measure> measures = measureRepository.getRawMeasures(component);
      List<LiveMeasureDto> dtos = new ArrayList<>();
      for (Map.Entry<String, Measure> measuresByMetricKey : measures.entrySet()) {
        String metricKey = measuresByMetricKey.getKey();
        if (NOT_TO_PERSIST_ON_FILE_METRIC_KEYS.contains(metricKey) && component.getType() == Component.Type.FILE) {
          continue;
        }
        Metric metric = metricRepository.getByKey(metricKey);
        Predicate<Measure> notBestValueOptimized = BestValueOptimization.from(metric, component).negate();
        Measure m = measuresByMetricKey.getValue();
        if (!NonEmptyMeasure.INSTANCE.test(m) || !notBestValueOptimized.test(m)) {
          continue;
        }

        LiveMeasureDto lm = measureToMeasureDto.toLiveMeasureDto(m, metric, component);
        dtos.add(lm);
        metricUuids.add(metric.getUuid());
      }

      if (supportUpsert) {
        for (LiveMeasureDto dto : dtos) {
          dbClient.liveMeasureDao().upsert(dbSession, dto);
        }
        // The measures that no longer exist on the component must be deleted, for example
        // when the coverage on a file goes to the "best value" 100%.
        // The measures on deleted components are deleted by the step PurgeDatastoresStep
        dbClient.liveMeasureDao().deleteByComponentUuidExcludingMetricUuids(dbSession, component.getUuid(), metricUuids);
      } else {
        dbClient.liveMeasureDao().deleteByComponent(dbSession, component.getUuid());
        dtos.forEach(dto -> dbClient.liveMeasureDao().insert(dbSession, dto));
      }

      dbSession.commit();
      insertsOrUpdates += dtos.size();
    }
  }

  private enum NonEmptyMeasure implements Predicate<Measure> {
    INSTANCE;

    @Override
    public boolean test(@Nonnull Measure input) {
      return input.getValueType() != Measure.ValueType.NO_VALUE || input.hasVariation() || input.getData() != null;
    }
  }
}
