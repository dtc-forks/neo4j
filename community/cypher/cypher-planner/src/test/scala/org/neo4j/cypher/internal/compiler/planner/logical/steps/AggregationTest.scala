/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler.planner.logical.steps

import org.neo4j.cypher.internal.compiler.planner.LogicalPlanningTestSupport
import org.neo4j.cypher.internal.expressions.CountStar
import org.neo4j.cypher.internal.expressions.Expression
import org.neo4j.cypher.internal.expressions.LogicalVariable
import org.neo4j.cypher.internal.ir.AggregatingQueryProjection
import org.neo4j.cypher.internal.ir.ordering.InterestingOrder
import org.neo4j.cypher.internal.logical.plans.Aggregation
import org.neo4j.cypher.internal.logical.plans.LogicalPlan
import org.neo4j.cypher.internal.logical.plans.Projection
import org.neo4j.cypher.internal.util.test_helpers.CypherFunSuite

class AggregationTest extends CypherFunSuite with LogicalPlanningTestSupport {
  private val aggregatingMap = Map[LogicalVariable, Expression](varFor("count(*)") -> CountStar()(pos))

  test("should introduce aggregation when needed") {
    val projection = AggregatingQueryProjection(
      groupingExpressions = Map.empty,
      aggregationExpressions = aggregatingMap.map { case (key, value) => key.name -> value }
    )

    val context = newMockedLogicalPlanningContextWithFakeAttributes(
      planContext = newMockedPlanContext()
    )
    val startPlan = newMockedLogicalPlan()

    val result = aggregation(startPlan, projection, InterestingOrder.empty, None, context)
    result should equal(
      Aggregation(startPlan, Map(), aggregatingMap)
    )
  }

  test("should introduce variables when needed") {
    // match (n) return n.y, sum(n.x)
    val aggregationMap = Map("count(n.prop)" -> count(prop("n", "prop")))
    val groupingMap = Map("n.bar" -> prop("n", "bar"))
    val projectionPlan = AggregatingQueryProjection(
      groupingExpressions = groupingMap,
      aggregationExpressions = aggregationMap
    )

    val context = newMockedLogicalPlanningContextWithFakeAttributes(
      planContext = newMockedPlanContext()
    )

    val startPlan = newMockedLogicalPlan()

    val result = aggregation(startPlan, projectionPlan, InterestingOrder.empty, None, context)
    result should equal(
      Aggregation(
        startPlan,
        groupingMap.map { case (key, value) => varFor(key) -> value },
        aggregationMap.map { case (key, value) => varFor(key) -> value }
      )
    )
  }

  test("RETURN x.prop, count(*) => WITH x.prop as `x.prop` RETURN `x.prop`, count(*)") {
    // Given RETURN x.prop, count(*) => WITH x.prop as `x.prop` RETURN `x.prop`, count(*)
    val groupingMap = Map("x.prop" -> prop("x", "prop"))
    val groupingKeyMap = Map("x.prop" -> varFor("x.prop"))
    val projection = AggregatingQueryProjection(
      groupingExpressions = groupingKeyMap,
      aggregationExpressions = aggregatingMap.map { case (key, value) => key.name -> value }
    )

    val context = newMockedLogicalPlanningContextWithFakeAttributes(
      planContext = newMockedPlanContext()
    )

    val startPlan = newMockedLogicalPlan()

    val projectionPlan: LogicalPlan =
      Projection(startPlan, Set.empty, groupingMap.map { case (key, value) => varFor(key) -> value })

    // When
    val result = aggregation(projectionPlan, projection, InterestingOrder.empty, None, context)
    // Then
    result should equal(
      Aggregation(projectionPlan, groupingKeyMap.map { case (key, value) => varFor(key) -> value }, aggregatingMap)
    )
  }
}
